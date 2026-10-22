package io.github.yuroyami.kiteplayer.ffmpeg

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The relay's own session rules, measured on raw loopback sockets with no codec and no clock
 * (#555). A player that the engine pauses and resumes sends PLAY, PAUSE, PLAY at every start, so a
 * relay that adds the same player again on each PLAY and ignores PAUSE sends every later packet to
 * it twice. FFmpeg's RTP parser takes an equal sequence number on this path, so the duplicates are
 * not harmless.
 *
 * Every count here is exact. Each phase ends with a marker packet, and a player's frames are read
 * up to that marker: TCP keeps the order, so anything the relay sent before the marker, a duplicate
 * included, has arrived by then. Each PLAY and PAUSE is followed by an OPTIONS request, whose reply
 * the relay can only send after it has finished acting on the request before it.
 */
class RtspRelayTest {

    @Test
    fun aRepeatedPlaySendsEachPacketOnce() {
        RtspRelay().use { relay ->
            Publisher(relay).use { publisher ->
                publisher.report(id = 1)
                Client(relay).use { player ->
                    player.setUp(channel = 0)
                    player.play()
                    player.play()
                    publisher.packets(100..109)
                    publisher.marker(1_000)
                    val seen = player.framesUntil(1_000)
                    assertEquals(listOf(Frame.report(0, 1)) + (100..109).map { Frame.media(0, it) }, seen)
                }
            }
        }
    }

    @Test
    fun aPauseStopsOnlyThatPlayerUntilItPlaysAgain() {
        RtspRelay().use { relay ->
            Publisher(relay).use { publisher ->
                publisher.report(id = 1)
                Client(relay).use { paused ->
                    Client(relay).use { watching ->
                        paused.setUp(channel = 0)
                        watching.setUp(channel = 4)
                        paused.play()
                        watching.play()
                        publisher.packets(100..104)
                        publisher.marker(1_000)
                        assertEquals(
                            listOf(Frame.report(0, 1)) + (100..104).map { Frame.media(0, it) },
                            paused.framesUntil(1_000),
                        )

                        paused.pause()
                        publisher.packets(200..204)
                        publisher.report(id = 2)
                        publisher.marker(2_000)
                        assertEquals(
                            listOf(Frame.report(4, 1)) + (100..104).map { Frame.media(4, it) } +
                                (200..204).map { Frame.media(4, it) } + Frame.report(4, 2),
                            watching.framesUntil(2_000).filter { it.id != 1_000 },
                            "the player that kept playing lost or repeated a packet",
                        )

                        paused.play()
                        publisher.packets(300..304)
                        publisher.marker(3_000)
                        assertEquals(
                            listOf(Frame.report(0, 2)) + (300..304).map { Frame.media(0, it) },
                            paused.framesUntil(3_000),
                            "a resumed player must get the newest report once, then each new packet once, " +
                                "and nothing sent while it was paused",
                        )
                        assertEquals((300..304).map { Frame.media(4, it) }, watching.framesUntil(3_000))
                    }
                }
            }
        }
    }

    @Test
    fun heldPacketsGoOnlyToThePlayersActiveAtRelease() {
        RtspRelay().use { relay ->
            Publisher(relay).use { publisher ->
                publisher.report(id = 1)
                Client(relay).use { paused ->
                    Client(relay).use { watching ->
                        paused.setUp(channel = 0)
                        watching.setUp(channel = 4)
                        paused.play()
                        watching.play()
                        assertEquals(listOf(Frame.report(0, 1)), paused.frames(1))
                        assertEquals(listOf(Frame.report(4, 1)), watching.frames(1))

                        relay.hold()
                        publisher.packets(100..104)
                        publisher.report(id = 2)
                        publisher.marker(1_000)
                        awaitHeld(relay, 7)
                        paused.pause()
                        relay.release()
                        assertEquals(
                            (100..104).map { Frame.media(4, it) } + Frame.report(4, 2),
                            watching.framesUntil(1_000),
                            "the held packets must reach the active player once and in order",
                        )

                        paused.play()
                        publisher.marker(2_000)
                        assertEquals(
                            listOf(Frame.report(0, 2)),
                            paused.framesUntil(2_000),
                            "a player paused before the release gets none of what was held, and the newest report on play",
                        )
                    }
                }
            }
        }
    }

    private fun awaitHeld(relay: RtspRelay, count: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (relay.heldPackets() < count) {
            if (System.nanoTime() > deadline) fail("the relay held ${relay.heldPackets()} packets, not $count")
            Thread.sleep(5)
        }
    }

    /** One packet as a player received it: its interleaved channel, whether it is RTCP, and the id the test wrote into it. */
    private data class Frame(val channel: Int, val rtcp: Boolean, val id: Int) {
        companion object {
            fun media(base: Int, id: Int) = Frame(base, rtcp = false, id = id)
            fun report(base: Int, id: Int) = Frame(base + 1, rtcp = true, id = id)
        }
    }

    /** An RTSP connection that reads interleaved packets and replies apart, so a request can wait for its reply. */
    private open class Connection(relay: RtspRelay) : AutoCloseable {
        private val socket = Socket(InetAddress.getLoopbackAddress(), relay.url.substringAfterLast(':').substringBefore('/').toInt())
        private val output: OutputStream = socket.getOutputStream()
        private val replies = LinkedBlockingQueue<String>()
        private val packets = LinkedBlockingQueue<Pair<Int, ByteArray>>()
        protected val base = relay.url
        private var cseq = 0

        init {
            thread(isDaemon = true, name = "rtsp-relay-test-reader") {
                runCatching {
                    val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
                    while (true) {
                        val first = input.read()
                        if (first < 0) break
                        if (first == '$'.code) {
                            val channel = input.readUnsignedByte()
                            val payload = ByteArray(input.readUnsignedShort())
                            input.readFully(payload)
                            packets.put(channel to payload)
                            continue
                        }
                        val head = StringBuilder().append(first.toChar())
                        while (!head.endsWith("\r\n\r\n")) head.append(input.read().toChar())
                        val length = Regex("Content-Length: (\\d+)").find(head)?.groupValues?.get(1)?.toInt() ?: 0
                        input.readFully(ByteArray(length))
                        replies.put(head.toString())
                    }
                }
            }
        }

        fun request(method: String, address: String, vararg headers: String, body: String? = null): String {
            val text = buildString {
                append(method).append(' ').append(address).append(" RTSP/1.0\r\nCSeq: ").append(++cseq).append("\r\n")
                headers.forEach { append(it).append("\r\n") }
                if (body != null) append("Content-Length: ").append(body.encodeToByteArray().size).append("\r\n")
                append("\r\n")
                if (body != null) append(body)
            }
            synchronized(output) {
                output.write(text.encodeToByteArray())
                output.flush()
            }
            val reply = replies.poll(10, TimeUnit.SECONDS) ?: fail("no reply to $method")
            assertTrue(reply.startsWith("RTSP/1.0 200"), "$method was refused: $reply")
            return reply
        }

        fun send(channel: Int, packet: ByteArray) {
            val frame = ByteArray(4 + packet.size)
            frame[0] = '$'.code.toByte()
            frame[1] = channel.toByte()
            frame[2] = (packet.size shr 8).toByte()
            frame[3] = packet.size.toByte()
            packet.copyInto(frame, 4)
            synchronized(output) {
                output.write(frame)
                output.flush()
            }
        }

        fun nextPacket(): Pair<Int, ByteArray> = packets.poll(10, TimeUnit.SECONDS) ?: fail("no packet arrived")

        override fun close() = socket.close()
    }

    /** The publisher: ANNOUNCE, SETUP and RECORD of one audio stream, whose RTP it sends on channel 0 and RTCP on 1. */
    private class Publisher(relay: RtspRelay) : Connection(relay) {
        init {
            request("ANNOUNCE", base, "Content-Type: application/sdp", body = SDP)
            check(relay.awaitAnnounced(10))
            request("SETUP", "$base/streamid=0", "Transport: RTP/AVP/TCP;unicast;interleaved=0-1;mode=record")
            request("RECORD", base)
        }

        fun packets(ids: IntRange) = ids.forEach { send(0, rtp(it)) }

        fun marker(id: Int) = send(0, rtp(id))

        /** An RTCP sender report, which the relay keeps as the stream's newest. */
        fun report(id: Int) = send(1, ByteArray(28).also { it[0] = 0x80.toByte(); it[1] = 200.toByte(); writeId(it, 8, id) })

        private fun rtp(id: Int) = ByteArray(16).also {
            it[0] = 0x80.toByte()
            it[1] = 97
            it[3] = id.toByte()
            writeId(it, 12, id)
        }

        private fun writeId(packet: ByteArray, at: Int, id: Int) {
            for (i in 0 until 4) packet[at + i] = (id shr (24 - 8 * i)).toByte()
        }
    }

    /** A player: DESCRIBE, then SETUP of the one stream on its own pair of interleaved channels. */
    private class Client(relay: RtspRelay) : Connection(relay) {
        init {
            request("DESCRIBE", base, "Accept: application/sdp")
        }

        fun setUp(channel: Int) {
            request("SETUP", "$base/streamid=0", "Transport: RTP/AVP/TCP;unicast;interleaved=$channel-${channel + 1}")
        }

        /** PLAY, then an OPTIONS round trip, so the relay has finished acting on PLAY before the test goes on. */
        fun play() {
            request("PLAY", base, "Session: 4b1d0c2e", "Range: npt=0.000-")
            request("OPTIONS", base)
        }

        fun pause() {
            request("PAUSE", base, "Session: 4b1d0c2e")
            request("OPTIONS", base)
        }

        fun frames(count: Int): List<Frame> = List(count) { frame(nextPacket()) }

        /** Every frame up to and without the first one carrying [marker]. */
        fun framesUntil(marker: Int): List<Frame> = buildList {
            while (true) {
                val next = frame(nextPacket())
                if (!next.rtcp && next.id == marker) return@buildList
                add(next)
            }
        }

        private fun frame(packet: Pair<Int, ByteArray>): Frame {
            val (channel, bytes) = packet
            val rtcp = bytes.size > 1 && (bytes[1].toInt() and 0xFF) == 200
            val at = if (rtcp) 8 else 12
            var id = 0
            for (i in 0 until 4) id = (id shl 8) or (bytes[at + i].toInt() and 0xFF)
            return Frame(channel, rtcp, id)
        }
    }

    private companion object {
        val SDP = """
            v=0
            o=- 0 0 IN IP4 127.0.0.1
            s=relay test
            t=0 0
            m=audio 0 RTP/AVP 97
            a=rtpmap:97 L16/8000/1
            a=control:streamid=0
        """.trimIndent().replace("\n", "\r\n") + "\r\n"
    }
}
