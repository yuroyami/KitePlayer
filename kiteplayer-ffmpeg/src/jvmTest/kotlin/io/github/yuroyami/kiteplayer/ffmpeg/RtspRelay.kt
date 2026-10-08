package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.MonotonicClock
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * A loopback RTSP server for the live tests (#395), which the `ffmpeg` command line cannot be by
 * itself. The command line publishes to it, with ANNOUNCE and RECORD over TCP, and the player plays
 * from it as it would from a camera, with DESCRIBE, SETUP and PLAY over TCP or UDP. Each RTP and
 * RTCP packet the publisher sends goes on to every player that has pressed play; a player that
 * joins late starts at the next keyframe, as it would on a camera. As a camera does, the relay
 * gives a player each stream's latest RTCP sender report the moment it presses play, because
 * those reports are what line the streams up and the publisher sends one only every 5 seconds.
 *
 * [hold] keeps the media back, in order, and [release] sends what was held at once, which is a
 * network that stalls and then delivers. Held packets go to the players playing at the release.
 *
 * PAUSE stops sending to that player alone, and PLAY starts it again with the newest reports, while
 * the publisher, the other players and the reports carry on. A second PLAY changes nothing, so no
 * player is ever sent a packet twice (#555). A paused player keeps its UDP sockets until its
 * connection ends or the relay closes.
 */
internal class RtspRelay(
    private val onReceiverReleased: () -> Unit = {},
    private val wrapOutput: (OutputStream) -> OutputStream = { it },
) : AutoCloseable {
    private val loopback = InetAddress.getLoopbackAddress()
    private val server = ServerSocket(0, 50, loopback)
    // Admission and resource ownership are independent of active packet delivery. In particular,
    // PAUSE removes a player from delivery while its UDP sockets remain owned until release.
    private val ownership = Any()
    private val closing = Any()
    private var closed = false
    private val sockets = mutableSetOf<Socket>()
    private val receivers = mutableSetOf<Player>()
    private val players = CopyOnWriteArrayList<Player>()
    private val announced = CountDownLatch(1)

    @Volatile
    private var sdp: String? = null

    /** The publisher's interleaved channel of each stream's RTP, by stream number. */
    private val publisherChannels = mutableMapOf<Int, Int>()

    /** The latest RTCP sender report of each stream, by stream number. */
    private val reports = mutableMapOf<Int, ByteArray>()

    private val held = ArrayDeque<Pair<Int, ByteArray>>()
    private var holding = false

    private val audio = Timeline("audio")
    private val video = Timeline("video")

    /**
     * When each RTP packet of the first audio stream the publisher announced arrived from it, on
     * [MonotonicClock.System], with its media time in seconds from that stream's first packet. The
     * publisher packs several AAC frames into one packet, so a packet can leave well after the
     * media time it carries.
     */
    val arrivals: List<Pair<Long, Double>> get() = audio.arrivals

    /** The same for the first video stream, whose pictures leave as they are due, a packet at a time. */
    val videoArrivals: List<Pair<Long, Double>> get() = video.arrivals

    val url: String get() = "rtsp://127.0.0.1:${server.localPort}/live"

    private val acceptWorker = thread(isDaemon = true, name = "rtsp-relay-accept") {
        runCatching {
            while (true) {
                val socket = server.accept()
                val admitted = synchronized(ownership) {
                    if (closed) false else sockets.add(socket)
                }
                if (admitted) {
                    thread(isDaemon = true, name = "rtsp-relay-connection") { runCatching { serve(socket) } }
                } else {
                    socket.close()
                }
            }
        }
    }

    /** Waits until the publisher has announced its session. */
    fun awaitAnnounced(seconds: Long): Boolean = announced.await(seconds, TimeUnit.SECONDS)

    fun hold() = synchronized(held) { holding = true }

    /** How many packets [hold] is keeping back, so a test can wait until the publisher's have arrived. */
    fun heldPackets(): Int = synchronized(held) { held.size }

    fun release() {
        synchronized(held) {
            holding = false
            while (held.isNotEmpty()) {
                val (stream, packet) = held.removeFirst()
                players.forEach { it.send(stream, packet) }
            }
        }
    }

    private fun serve(socket: Socket) {
        var player: Player? = null
        try {
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
            val output = wrapOutput(socket.getOutputStream())
            var publisher = false
            while (true) {
                val first = input.read()
                if (first < 0) break
                if (first == '$'.code) {
                    val channel = input.readUnsignedByte()
                    val length = input.readUnsignedShort()
                    val payload = ByteArray(length)
                    input.readFully(payload)
                    if (publisher) fromPublisher(channel, payload)
                    continue
                }
                val head = StringBuilder().append(first.toChar())
                while (!head.endsWith("\r\n\r\n")) {
                    val next = input.read()
                    if (next < 0) return
                    head.append(next.toChar())
                }
                val lines = head.trim().lines()
                val (method, address) = lines.first().split(' ').let { it[0] to it.getOrElse(1) { "" } }
                val headers = lines.drop(1).associate { line -> line.substringBefore(':').trim().lowercase() to line.substringAfter(':').trim() }
                val body = headers["content-length"]?.toInt()?.let { length -> ByteArray(length).also(input::readFully).decodeToString() }
                val cseq = headers["cseq"] ?: "0"
                when (method) {
                    "OPTIONS" -> reply(output, cseq, "Public: OPTIONS, DESCRIBE, ANNOUNCE, SETUP, PLAY, PAUSE, RECORD, TEARDOWN, GET_PARAMETER")
                    "ANNOUNCE" -> {
                        publisher = true
                        sdp = body
                        announced.countDown()
                        reply(output, cseq)
                    }
                    "DESCRIBE" -> {
                        announced.await(15, TimeUnit.SECONDS)
                        val description = checkNotNull(sdp) { "nothing was published" }
                        reply(output, cseq, "Content-Base: $url/", "Content-Type: application/sdp", body = description)
                    }
                    "SETUP" -> {
                        val stream = Regex("streamid=(\\d+)").find(address)?.groupValues?.get(1)?.toInt() ?: 0
                        val transport = headers["transport"].orEmpty()
                        if (publisher) {
                            val channel = Regex("interleaved=(\\d+)").find(transport)?.groupValues?.get(1)?.toInt() ?: (2 * stream)
                            synchronized(publisherChannels) { publisherChannels[stream] = channel }
                            reply(output, cseq, "Transport: $transport", SESSION)
                        } else {
                            val answer = synchronized(ownership) {
                                check(!closed) { "the relay is closed" }
                                val joining = player ?: Player(output).also {
                                    player = it
                                    receivers += it
                                }
                                // No SETUP may allocate after close has captured its owners.
                                joining.setUp(stream, transport)
                            }
                            reply(output, cseq, "Transport: $answer", SESSION)
                        }
                    }
                    "RECORD" -> reply(output, cseq, SESSION)
                    "PLAY" -> {
                        synchronized(held) {
                            // A resumed connection owns the same receiver. Repeating PLAY must not
                            // make every later packet reach that receiver more than once.
                            val joining = synchronized(ownership) {
                                check(!closed) { "the relay is closed" }
                                player?.takeIf { players.addIfAbsent(it) }
                            }
                            reply(output, cseq, SESSION, "Range: npt=0.000-")
                            joining?.let { receiver ->
                                reports.forEach { (stream, report) -> receiver.send(2 * stream + 1, report) }
                            }
                        }
                    }
                    "PAUSE" -> {
                        synchronized(held) {
                            // Only this receiver stops. The publisher and other players keep going,
                            // and reports continue to refresh for the receiver's next PLAY.
                            player?.let(players::remove)
                            reply(output, cseq, SESSION)
                        }
                    }
                    "TEARDOWN" -> {
                        reply(output, cseq, SESSION)
                        break
                    }
                    else -> reply(output, cseq, SESSION)
                }
            }
        } finally {
            // Closing the TCP connection first also releases a publisher blocked while forwarding
            // to it. Resource close must not wait for that sender while leaving its socket open.
            runCatching { socket.close() }
            val owned = player
            try {
                owned?.close()
            } finally {
                owned?.let(players::remove)
                synchronized(ownership) {
                    if (owned != null) receivers.remove(owned)
                    sockets.remove(socket)
                }
            }
        }
    }

    private fun fromPublisher(channel: Int, packet: ByteArray) {
        val stream = synchronized(publisherChannels) {
            publisherChannels.entries.firstOrNull { it.value == channel || it.value + 1 == channel }
        } ?: return
        val rtcp = channel == stream.value + 1
        if (!rtcp) {
            audio.date(stream.key, packet)
            video.date(stream.key, packet)
        }
        val tagged = (if (rtcp) 2 * stream.key + 1 else 2 * stream.key)
        synchronized(held) {
            if (rtcp && packet.size > 1 && (packet[1].toInt() and 0xFF) == SENDER_REPORT) reports[stream.key] = packet
            if (holding) {
                held.addLast(tagged to packet)
            } else {
                players.forEach { it.send(tagged, packet) }
            }
        }
    }

    /** Dates the RTP packets of the first stream of [kind] the publisher announced, on the thread that reads the publisher. */
    private inner class Timeline(private val kind: String) {
        val arrivals: MutableList<Pair<Long, Double>> = CopyOnWriteArrayList()
        private var stream = -1
        private var clockRate = 0
        private var firstTimestamp = -1L
        private var unwrapped = 0L
        private var lastTimestamp = -1L

        fun date(stream: Int, packet: ByteArray) {
            if (this.stream == -1) {
                clockRate = clockRate(stream, kind) ?: return
                this.stream = stream
            }
            if (stream != this.stream || packet.size < 12) return
            val timestamp = ((packet[4].toLong() and 0xFF) shl 24) or ((packet[5].toLong() and 0xFF) shl 16) or
                ((packet[6].toLong() and 0xFF) shl 8) or (packet[7].toLong() and 0xFF)
            if (firstTimestamp < 0) {
                firstTimestamp = timestamp
                lastTimestamp = timestamp
            }
            // The 32-bit timestamp wraps; the difference from the last one is small either way.
            var step = timestamp - lastTimestamp
            if (step < -(1L shl 31)) step += 1L shl 32
            if (step > (1L shl 31)) step -= 1L shl 32
            unwrapped += step
            lastTimestamp = timestamp
            arrivals += MonotonicClock.System.nanos() to unwrapped.toDouble() / clockRate
        }
    }

    /** The RTP clock rate of [stream] when the announced session says it is of [kind]. */
    private fun clockRate(stream: Int, kind: String): Int? {
        val media = sdp?.split("\nm=")?.drop(1)?.getOrNull(stream) ?: return null
        if (!media.startsWith(kind)) return null
        return Regex("a=rtpmap:\\d+ [^/]+/(\\d+)").find(media)?.groupValues?.get(1)?.toInt()
    }

    private fun reply(output: OutputStream, cseq: String, vararg headers: String, body: String? = null) {
        val text = buildString {
            append("RTSP/1.0 200 OK\r\nCSeq: ").append(cseq).append("\r\n")
            headers.forEach { append(it).append("\r\n") }
            if (body != null) append("Content-Length: ").append(body.encodeToByteArray().size).append("\r\n")
            append("\r\n")
            if (body != null) append(body)
        }
        synchronized(output) {
            output.write(text.encodeToByteArray())
            output.flush()
        }
    }

    override fun close(): Unit = synchronized(closing) {
        val owned = synchronized(ownership) {
            if (closed) return
            closed = true
            sockets.toList() to receivers.toList()
        }
        runCatching { server.close() }
        // Do not take held or a receiver lock before unblocking TCP writers and readers.
        owned.first.forEach { runCatching { it.close() } }
        owned.second.forEach(Player::close)
        players.clear()
        synchronized(ownership) {
            sockets.removeAll(owned.first.toSet())
            receivers.removeAll(owned.second.toSet())
        }
        // An accept may have returned just before admission closed. The accept worker rejects
        // and closes that connection; join it so no unregistered socket outlives close's return.
        acceptWorker.join(3_000)
        check(!acceptWorker.isAlive) { "the RTSP accept worker did not stop" }
    }

    /** One player's session: where each stream's packets go, over its TCP connection or by UDP. */
    private inner class Player(private val output: OutputStream) {
        private val state = Any()
        private var closed = false

        /** Over TCP, the player's interleaved channel for each stream's RTP. */
        private val channels = mutableMapOf<Int, Int>()

        /** Over UDP, the player's RTP port for each stream, and the two sockets it is sent from. */
        private val ports = mutableMapOf<Int, Int>()
        private val udp = mutableMapOf<Int, Pair<DatagramSocket, DatagramSocket>>()

        fun setUp(stream: Int, transport: String): String = synchronized(state) {
            check(!closed) { "the receiver is closed" }
            val interleaved = Regex("interleaved=(\\d+)").find(transport)
            if ("TCP" in transport.uppercase() || interleaved != null) {
                val channel = interleaved?.groupValues?.get(1)?.toInt() ?: (2 * stream)
                udp.remove(stream)?.let { (rtp, rtcp) -> rtp.close(); rtcp.close() }
                ports.remove(stream)
                channels[stream] = channel
                return "RTP/AVP/TCP;unicast;interleaved=$channel-${channel + 1}"
            }
            val client = Regex("client_port=(\\d+)").find(transport)!!.groupValues[1].toInt()
            val rtp = DatagramSocket(0, loopback)
            val rtcp = try {
                DatagramSocket(0, loopback)
            } catch (failure: Throwable) {
                rtp.close()
                throw failure
            }
            udp.put(stream, rtp to rtcp)?.let { (oldRtp, oldRtcp) -> oldRtp.close(); oldRtcp.close() }
            channels.remove(stream)
            ports[stream] = client
            return "RTP/AVP/UDP;unicast;client_port=$client-${client + 1};server_port=${rtp.localPort}-${rtcp.localPort}"
        }

        /** Sends [packet], tagged as stream times two plus one for RTCP, on this player's transport. */
        fun send(tagged: Int, packet: ByteArray) {
            val stream = tagged / 2
            val rtcp = tagged % 2 == 1
            // Copy routing under the state lock, but never hold it during a possibly blocked send.
            // close can then close the sockets and wait only for actual resource cleanup.
            val destination = synchronized(state) {
                if (closed) return
                Triple(channels[stream], ports[stream], udp[stream])
            }
            destination.first?.let { channel ->
                val frame = ByteArray(4 + packet.size)
                frame[0] = '$'.code.toByte()
                frame[1] = (channel + if (rtcp) 1 else 0).toByte()
                frame[2] = (packet.size shr 8).toByte()
                frame[3] = packet.size.toByte()
                packet.copyInto(frame, 4)
                runCatching {
                    synchronized(output) {
                        output.write(frame)
                        output.flush()
                    }
                }
                return
            }
            val port = destination.second ?: return
            val (rtpSocket, rtcpSocket) = destination.third ?: return
            val target = if (rtcp) port + 1 else port
            runCatching { (if (rtcp) rtcpSocket else rtpSocket).send(DatagramPacket(packet, packet.size, loopback, target)) }
        }

        fun close(): Unit = synchronized(state) {
            if (closed) return
            closed = true
            udp.values.forEach { (rtp, rtcp) -> rtp.close(); rtcp.close() }
            udp.clear()
            ports.clear()
            channels.clear()
            // This observer runs only after every socket is closed, once per receiver. Holding
            // state until it returns also makes a competing close wait for completed release.
            onReceiverReleased()
        }
    }

    private companion object {
        const val SESSION = "Session: 4b1d0c2e;timeout=60"
        const val SENDER_REPORT = 200
    }
}
