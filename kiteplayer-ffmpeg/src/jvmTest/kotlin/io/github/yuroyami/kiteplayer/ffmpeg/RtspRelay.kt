package io.github.yuroyami.kiteplayer.ffmpeg

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
 * network that stalls and then delivers.
 */
internal class RtspRelay : AutoCloseable {
    private val loopback = InetAddress.getLoopbackAddress()
    private val server = ServerSocket(0, 50, loopback)
    private val sockets = CopyOnWriteArrayList<Socket>()
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

    /** When each RTP packet of [timedStream] arrived from the publisher, with its media time. */
    val arrivals: MutableList<Pair<Long, Double>> = CopyOnWriteArrayList()

    /** The stream whose packets [arrivals] dates, and that stream's RTP clock rate. */
    @Volatile
    var timedStream: Int = -1
        private set
    private var timedClockRate = 0
    private var firstTimestamp = -1L
    private var unwrapped = 0L
    private var lastTimestamp = -1L

    val url: String get() = "rtsp://127.0.0.1:${server.localPort}/live"

    init {
        thread(isDaemon = true, name = "rtsp-relay-accept") {
            runCatching {
                while (true) {
                    val socket = server.accept()
                    sockets += socket
                    thread(isDaemon = true, name = "rtsp-relay-connection") { runCatching { serve(socket) } }
                }
            }
        }
    }

    /** Waits until the publisher has announced its session. */
    fun awaitAnnounced(seconds: Long): Boolean = announced.await(seconds, TimeUnit.SECONDS)

    fun hold() = synchronized(held) { holding = true }

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
        val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        val output = socket.getOutputStream()
        var publisher = false
        var player: Player? = null
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
                "OPTIONS" -> reply(output, cseq, "Public: OPTIONS, DESCRIBE, ANNOUNCE, SETUP, PLAY, RECORD, TEARDOWN, GET_PARAMETER")
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
                        val joining = player ?: Player(output).also { player = it }
                        reply(output, cseq, "Transport: ${joining.setUp(stream, transport)}", SESSION)
                    }
                }
                "RECORD" -> reply(output, cseq, SESSION)
                "PLAY" -> {
                    reply(output, cseq, SESSION, "Range: npt=0.000-")
                    player?.let { joining ->
                        synchronized(held) {
                            reports.forEach { (stream, report) -> joining.send(2 * stream + 1, report) }
                            players += joining
                        }
                    }
                }
                "TEARDOWN" -> {
                    reply(output, cseq, SESSION)
                    break
                }
                else -> reply(output, cseq, SESSION)
            }
        }
        player?.let(players::remove)
        player?.close()
        socket.close()
    }

    private fun fromPublisher(channel: Int, packet: ByteArray) {
        val stream = synchronized(publisherChannels) {
            publisherChannels.entries.firstOrNull { it.value == channel || it.value + 1 == channel }
        } ?: return
        val rtcp = channel == stream.value + 1
        if (!rtcp) date(stream.key, packet)
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

    /** Dates the RTP packets of the first audio stream the publisher announced. */
    private fun date(stream: Int, packet: ByteArray) {
        if (timedStream == -1) {
            val rate = audioClockRate(stream) ?: return
            timedStream = stream
            timedClockRate = rate
        }
        if (stream != timedStream || packet.size < 12) return
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
        arrivals += System.nanoTime() to unwrapped.toDouble() / timedClockRate
    }

    /** The RTP clock rate of [stream] when the announced session says it is audio. */
    private fun audioClockRate(stream: Int): Int? {
        val media = sdp?.split("\nm=")?.drop(1)?.getOrNull(stream) ?: return null
        if (!media.startsWith("audio")) return null
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

    override fun close() {
        server.close()
        players.forEach(Player::close)
        sockets.forEach { runCatching { it.close() } }
    }

    /** One player's session: where each stream's packets go, over its TCP connection or by UDP. */
    private inner class Player(private val output: OutputStream) {
        /** Over TCP, the player's interleaved channel for each stream's RTP. */
        private val channels = mutableMapOf<Int, Int>()

        /** Over UDP, the player's RTP port for each stream, and the two sockets it is sent from. */
        private val ports = mutableMapOf<Int, Int>()
        private val udp = mutableMapOf<Int, Pair<DatagramSocket, DatagramSocket>>()

        fun setUp(stream: Int, transport: String): String {
            val interleaved = Regex("interleaved=(\\d+)").find(transport)
            if ("TCP" in transport.uppercase() || interleaved != null) {
                val channel = interleaved?.groupValues?.get(1)?.toInt() ?: (2 * stream)
                channels[stream] = channel
                return "RTP/AVP/TCP;unicast;interleaved=$channel-${channel + 1}"
            }
            val client = Regex("client_port=(\\d+)").find(transport)!!.groupValues[1].toInt()
            val rtp = DatagramSocket(0, loopback)
            val rtcp = DatagramSocket(0, loopback)
            ports[stream] = client
            udp[stream] = rtp to rtcp
            return "RTP/AVP/UDP;unicast;client_port=$client-${client + 1};server_port=${rtp.localPort}-${rtcp.localPort}"
        }

        /** Sends [packet], tagged as stream times two plus one for RTCP, on this player's transport. */
        fun send(tagged: Int, packet: ByteArray) {
            val stream = tagged / 2
            val rtcp = tagged % 2 == 1
            channels[stream]?.let { channel ->
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
            val port = ports[stream] ?: return
            val (rtpSocket, rtcpSocket) = udp[stream] ?: return
            val target = if (rtcp) port + 1 else port
            runCatching { (if (rtcp) rtcpSocket else rtpSocket).send(DatagramPacket(packet, packet.size, loopback, target)) }
        }

        fun close() = udp.values.forEach { (rtp, rtcp) -> rtp.close(); rtcp.close() }
    }

    private companion object {
        const val SESSION = "Session: 4b1d0c2e;timeout=60"
        const val SENDER_REPORT = 200
    }
}
