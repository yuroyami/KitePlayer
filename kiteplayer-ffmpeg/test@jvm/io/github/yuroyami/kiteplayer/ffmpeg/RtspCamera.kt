package io.github.yuroyami.kiteplayer.ffmpeg

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * A small RTSP server on the loopback that holds its sessions the way a camera does (#441). It
 * describes one PCMU audio stream, delivers it interleaved over the RTSP connection once played,
 * and announces a session timeout of [timeoutSeconds]. A session that hears no request for longer
 * than that really ends: its media stops, and anything that names it afterwards is answered
 * 454 Session Not Found, which is what a player that paused and fell silent meets.
 *
 * Every packet's RTP timestamp is the wall time since the server started, at 8 kHz, as a camera
 * stamps its media, and each PLAY answers with the Range and RTP-Info that date the next packet, as
 * a camera does. FFmpeg starts its timestamps again at each PLAY from those two, so a stream played
 * again after a pause resumes at the live edge with a jump in its timestamps the length of the pause. Each packet carries 20 ms of a value that counts up, so
 * a test can tell which part of the stream it heard. Connections are served one after another, a
 * new session each, so a player that reopens the stream reaches a fresh one.
 *
 * With [keepsPausedSessions] false a keepalive does not keep a paused session, as on a camera that
 * drops one anyway, so a pause past the timeout always ends it.
 */
internal class RtspCamera(
    private val timeoutSeconds: Int,
    private val keepsPausedSessions: Boolean = true,
) : AutoCloseable {
    private val server = ServerSocket(0, 4, InetAddress.getLoopbackAddress()).apply { soTimeout = 100 }
    private val stopping = AtomicBoolean(false)
    private val started = System.nanoTime()
    private val log: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val sessions = AtomicInteger(0)

    /** The address a player opens. */
    val url: String = "rtsp://127.0.0.1:${server.localPort}/live"

    /** PLAY requests accepted, over every session. */
    val plays = AtomicInteger(0)

    /** PAUSE requests accepted, over every session. */
    val pauses = AtomicInteger(0)

    /** GET_PARAMETER and OPTIONS requests that named a live session, which keep it alive. */
    val keepalives = AtomicInteger(0)

    /** Sessions that ended because they heard nothing for longer than the timeout. */
    val expired = AtomicInteger(0)

    /** Connections served, which is one more than the first each time a player reopens. */
    val connections: Int get() = sessions.get()

    /** Every request line with the time it arrived, for a failure message. */
    val transcript: String get() = synchronized(log) { log.joinToString("\n") }

    private val worker = thread(name = "rtsp-camera", isDaemon = true) {
        while (!stopping.get()) {
            val socket = try {
                server.accept()
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: Exception) {
                break
            }
            sessions.incrementAndGet()
            socket.use { Session(it, "session${sessions.get()}").run() }
        }
    }

    override fun close() {
        stopping.set(true)
        server.close()
        worker.join(5_000)
    }

    private fun millis(): Long = (System.nanoTime() - started) / 1_000_000

    private fun note(line: String) {
        log += "${millis()} ms: $line"
    }

    private inner class Session(private val socket: Socket, private val id: String) {
        private val input: InputStream = socket.getInputStream()
        private val output: OutputStream = socket.getOutputStream()
        private var pending = ByteArray(0)
        private var lastRequest = System.nanoTime()
        private var setUp = false
        private var ended = false
        private var playing = false
        private var nextPacketAt = 0L
        private var sequence = 0

        fun run() {
            socket.soTimeout = 5
            val buffer = ByteArray(4096)
            while (!stopping.get()) {
                val count = try {
                    input.read(buffer)
                } catch (_: SocketTimeoutException) {
                    0
                } catch (_: Exception) {
                    return
                }
                if (count < 0) return
                if (count > 0) {
                    pending += buffer.copyOf(count)
                    while (true) {
                        val request = takeRequest() ?: break
                        if (!answer(request)) return
                    }
                }
                expireWhenSilent()
                if (playing && System.nanoTime() >= nextPacketAt && !send(packet())) return
            }
        }

        private fun expireWhenSilent() {
            if (!setUp || ended) return
            val silentMs = (System.nanoTime() - lastRequest) / 1_000_000
            if (silentMs <= timeoutSeconds * 1_000L) return
            ended = true
            playing = false
            expired.incrementAndGet()
            note("$id ended after $silentMs ms without a request")
        }

        private fun answer(request: Request): Boolean {
            note("$id ${request.line}")
            expireWhenSilent()
            val cseq = request.headers["cseq"] ?: "0"
            val named = request.headers["session"]?.substringBefore(';')?.trim()
            if (named != null && (named != id || ended)) return reply(cseq, "454 Session Not Found")
            val keepalive = request.method == "GET_PARAMETER" || request.method == "OPTIONS"
            if (named != null && (keepsPausedSessions || playing || !keepalive)) lastRequest = System.nanoTime()
            val session = "Session: $id;timeout=$timeoutSeconds"
            return when (request.method) {
                "OPTIONS" -> {
                    if (named != null) keepalives.incrementAndGet()
                    reply(cseq, "200 OK", listOf("Public: OPTIONS, DESCRIBE, SETUP, PLAY, PAUSE, GET_PARAMETER, TEARDOWN"))
                }
                "DESCRIBE" -> {
                    val sdp = "v=0\r\no=- 0 0 IN IP4 127.0.0.1\r\ns=KitePlayer\r\nc=IN IP4 0.0.0.0\r\nt=0 0\r\n" +
                        "m=audio 0 RTP/AVP 0\r\na=rtpmap:0 PCMU/8000\r\na=control:track0\r\n"
                    reply(cseq, "200 OK", listOf("Content-Base: $url/", "Content-Type: application/sdp"), sdp)
                }
                "SETUP" -> {
                    setUp = true
                    lastRequest = System.nanoTime()
                    reply(cseq, "200 OK", listOf("Transport: RTP/AVP/TCP;unicast;interleaved=0-1", session))
                }
                "PLAY" -> {
                    plays.incrementAndGet()
                    playing = true
                    nextPacketAt = System.nanoTime()
                    // The next packet, dated in the camera's own time, so FFmpeg's timestamps go on
                    // from the wall time rather than from zero.
                    val now = millis()
                    val range = "Range: npt=${now / 1000}.${(now % 1000).toString().padStart(3, '0')}-"
                    val info = "RTP-Info: url=$url/track0;seq=$sequence;rtptime=${(now * 8) and 0xFFFFFFFFL}"
                    reply(cseq, "200 OK", listOf(session, range, info))
                }
                "PAUSE" -> {
                    pauses.incrementAndGet()
                    playing = false
                    reply(cseq, "200 OK", listOf(session))
                }
                "GET_PARAMETER" -> {
                    keepalives.incrementAndGet()
                    reply(cseq, "200 OK", listOf(session))
                }
                "TEARDOWN" -> {
                    playing = false
                    true
                }
                else -> reply(cseq, "501 Not Implemented")
            }
        }

        private fun reply(cseq: String, status: String, headers: List<String> = emptyList(), body: String = ""): Boolean {
            val text = buildString {
                append("RTSP/1.0 ").append(status).append("\r\n")
                append("CSeq: ").append(cseq).append("\r\n")
                headers.forEach { append(it).append("\r\n") }
                if (body.isNotEmpty()) append("Content-Length: ").append(body.encodeToByteArray().size).append("\r\n")
                append("\r\n").append(body)
            }
            return send(text.encodeToByteArray())
        }

        /** 20 ms of PCMU in one RTP packet, stamped with the wall time, framed for channel 0. */
        private fun packet(): ByteArray {
            val payload = 160
            val stamp = (millis() * 8).toInt()
            val rtp = ByteArray(4 + 12 + payload)
            // The value counts up in steps of 20 ms of wall time, so what is heard dates itself.
            val level = ((millis() / 20) % 100).toInt() + 0x10
            for (index in 16 until rtp.size) rtp[index] = level.toByte()
            val length = 12 + payload
            rtp[0] = '$'.code.toByte(); rtp[1] = 0
            rtp[2] = (length shr 8).toByte(); rtp[3] = length.toByte()
            rtp[4] = 0x80.toByte(); rtp[5] = 0
            rtp[6] = (sequence shr 8).toByte(); rtp[7] = sequence.toByte()
            for (index in 0 until 4) rtp[8 + index] = (stamp shr (24 - 8 * index)).toByte()
            for (index in 0 until 4) rtp[12 + index] = (0x4B495445 shr (24 - 8 * index)).toByte()
            sequence = (sequence + 1) and 0xFFFF
            nextPacketAt += 20_000_000L
            return rtp
        }

        private fun send(bytes: ByteArray): Boolean = try {
            output.write(bytes)
            output.flush()
            true
        } catch (_: Exception) {
            false
        }

        private fun takeRequest(): Request? {
            while (pending.isNotEmpty() && pending[0] == '$'.code.toByte()) {
                if (pending.size < 4) return null
                val frame = 4 + (((pending[2].toInt() and 0xFF) shl 8) or (pending[3].toInt() and 0xFF))
                if (pending.size < frame) return null
                pending = pending.copyOfRange(frame, pending.size)
            }
            val text = pending.decodeToString()
            val end = text.indexOf("\r\n\r\n")
            if (end < 0) return null
            val lines = text.substring(0, end).split("\r\n")
            val headers = lines.drop(1).mapNotNull { line ->
                val colon = line.indexOf(':')
                if (colon < 0) null else line.substring(0, colon).trim().lowercase() to line.substring(colon + 1).trim()
            }.toMap()
            val bodyLength = headers["content-length"]?.toIntOrNull() ?: 0
            val total = end + 4 + bodyLength
            if (pending.size < total) return null
            pending = pending.copyOfRange(total, pending.size)
            return Request(lines[0], lines[0].substringBefore(' '), headers)
        }
    }

    private class Request(val line: String, val method: String, val headers: Map<String, String>)
}
