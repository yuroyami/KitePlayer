package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteffmpeg.FFmpegError
import io.github.yuroyami.kiteffmpeg.FFmpegException
import io.github.yuroyami.kiteffmpeg.MediaSource
import io.github.yuroyami.kiteffmpeg.OpenInterrupt
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.PlaybackError
import io.github.yuroyami.kiteplayer.PlaybackException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The live schemes through the URL fallback on the JVM with real FFmpeg (#395): a scheme the build
 * has no protocol for is refused before anything goes over the network, the list of schemes it
 * does open matches the protocols FFmpeg was built with, and a sender that never sends ends the
 * open within the read timeout instead of holding it for ever.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveSchemeTest {

    @Test
    fun anSrtAddressIsRefusedWithoutADatagramSent() {
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { listener ->
            listener.soTimeout = 300
            val started = TimeSource.Monotonic.markNow()
            val error = refusal("srt://127.0.0.1:${listener.localPort}?mode=caller")
            val took = started.elapsedNow()
            assertEquals("srt", error.scheme)
            assertTrue(took < 2.seconds, "the refusal took $took")
            val received = try {
                listener.receive(DatagramPacket(ByteArray(2048), 2048))
                true
            } catch (_: SocketTimeoutException) {
                false
            }
            assertFalse(received, "a datagram reached the SRT listener")
        }
    }

    @Test
    fun tlsAddressesAreRefusedWithoutAConnection() {
        for (scheme in listOf("rtmps", "rtsps", "https")) {
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
                server.soTimeout = 300
                val error = refusal("$scheme://127.0.0.1:${server.localPort}/live/kite")
                assertEquals(scheme, error.scheme)
                val accepted = try {
                    server.accept().close()
                    true
                } catch (_: SocketTimeoutException) {
                    false
                }
                assertFalse(accepted, "$scheme connected to the server")
            }
        }
    }

    /**
     * The drift guard between the list and the build: FFmpeg finds a protocol for every scheme the
     * fallback lets through and none for the schemes it refuses. Each open is meant to fail, on a
     * port nothing listens on or a file that is not there, and only the kind of failure counts.
     */
    @Test
    fun theListedSchemesAreTheProtocolsInTheBuild() {
        val closed = ServerSocket(0).use { it.localPort }
        val probes = mapOf(
            "file" to "/definitely/missing.mkv",
            "fd" to "fd:",
            "pipe" to "pipe:99",
            "data" to "data:,nothing",
            "http" to "http://127.0.0.1:$closed/a.mkv",
            "tcp" to "tcp://127.0.0.1:$closed",
            "udp" to "udp://127.0.0.1:$closed?timeout=200000",
            "rtp" to "rtp://127.0.0.1:${closed and 0xfffe}?timeout=200000",
            "rtsp" to "rtsp://127.0.0.1:$closed/live",
            "rtmp" to "rtmp://127.0.0.1:$closed/live/kite",
        )
        assertEquals(fallbackSchemes, probes.keys, "every listed scheme has a probe")
        for ((scheme, address) in probes) {
            val failure = ffmpegOpenFailure(address)
            assertFalse(failure is FFmpegError.ProtocolNotFound, "the build has no $scheme protocol: $failure")
        }
        for (address in listOf("https://127.0.0.1:$closed/a.mkv", "srt://127.0.0.1:$closed", "rtmps://127.0.0.1:$closed/live/kite")) {
            assertIs<FFmpegError.ProtocolNotFound>(ffmpegOpenFailure(address), address)
        }
    }

    /**
     * A sender that is silent, or a server that accepts and never answers, ends the open within the
     * read timeout for each live scheme. The four wait side by side, each on its own thread.
     */
    @Test
    fun aSilentLiveSourceEndsItsOpenWithinTheReadTimeout() {
        val silent = SilentServer()
        try {
            val quiet = DatagramSocket(0).use { it.localPort } and 0xfffe
            val addresses = listOf(
                "udp://127.0.0.1:${quiet + 2}",
                "rtp://127.0.0.1:${quiet + 4}",
                "rtsp://127.0.0.1:${silent.port}/live",
                "rtmp://127.0.0.1:${silent.port}/live/kite",
            )
            val started = TimeSource.Monotonic.markNow()
            val outcomes = addresses.associateWith { address ->
                CompletableDeferred<Duration>().also { took ->
                    thread(isDaemon = true, name = "silent-open $address") {
                        runCatching { runBlocking { KiteFFmpegMediaBackend().open(MediaItem(address)).close() } }
                        took.complete(started.elapsedNow())
                    }
                }
            }
            runBlocking { withTimeoutOrNull(URL_FALLBACK_READ_TIMEOUT + 15.seconds) { outcomes.values.forEach { it.await() } } }
            val ended = outcomes.mapValues { (_, outcome) -> if (outcome.isCompleted) outcome.getCompleted().toString() else "still waiting" }
            println("LiveSchemeTest: the silent opens ended after $ended")
            for ((address, outcome) in outcomes) {
                assertTrue(
                    outcome.isCompleted && outcome.getCompleted() <= URL_FALLBACK_READ_TIMEOUT + 5.seconds,
                    "the open of $address ended after ${ended[address]}; all: $ended",
                )
            }
        } finally {
            silent.close()
        }
    }

    private fun refusal(address: String): PlaybackError.SchemeUnsupported {
        val failure = runCatching { runBlocking { KiteFFmpegMediaBackend().open(MediaItem(address)).close() } }.exceptionOrNull()
        val typed = assertIs<PlaybackException>(failure, "$address was $failure")
        return assertIs<PlaybackError.SchemeUnsupported>(typed.error, "$address was ${typed.error}")
    }

    /** The error KiteFFmpeg's own open of [address] fails with, ended after five seconds at most. */
    private fun ffmpegOpenFailure(address: String): FFmpegError? {
        val interrupt = OpenInterrupt()
        val watchdog = thread(isDaemon = true) {
            try {
                Thread.sleep(5_000)
                interrupt.interrupt()
            } catch (_: InterruptedException) {
            }
        }
        return try {
            MediaSource.open(address, emptyMap(), interrupt).close()
            null
        } catch (failure: FFmpegException) {
            failure.error
        } finally {
            watchdog.interrupt()
        }
    }

    /** Accepts every connection and never answers, like a camera that hangs. */
    private class SilentServer : AutoCloseable {
        private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        private val accepted = mutableListOf<Socket>()
        val port: Int get() = server.localPort

        init {
            thread(isDaemon = true, name = "silent-server") {
                runCatching {
                    while (true) {
                        val socket = server.accept()
                        synchronized(accepted) { accepted += socket }
                    }
                }
            }
        }

        override fun close() {
            server.close()
            synchronized(accepted) { accepted.forEach { runCatching { it.close() } } }
        }
    }
}
