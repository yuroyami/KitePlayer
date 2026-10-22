package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.MediaItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Reads that end when they must, on the JVM with real FFmpeg: an interrupt reaches a packet read
 * that waits inside the item's own reader, and the URL fallback gives up on a server that does not
 * answer. A failing run leaves a blocked daemon thread behind, which is why nothing here closes a
 * source whose read may still be running.
 */
class BoundedReadsTest {

    @Test
    fun anInterruptEndsAPacketReadThatWaitsForBytes() {
        val reader = GatedReader(baselineMkvBytes())
        val session = runBlocking { KiteFFmpegMediaBackend().open(MediaItem("custom://baseline.mkv", io = { reader })) }
        val source = session.source
        source.selectStreams(source.streams.map { it.index }.toSet())
        // A little further on, the next read waits for bytes that never come.
        reader.gateAt = reader.served + 256 * 1024
        val ended = CompletableDeferred<Throwable?>()
        thread(isDaemon = true, name = "bounded-reads-demux") {
            try {
                runBlocking {
                    while (true) (source.readPacket() ?: break).close()
                }
                ended.complete(null)
            } catch (failure: Throwable) {
                ended.complete(failure)
            }
        }
        assertNotNull(
            runBlocking { withTimeoutOrNull(10.seconds) { reader.waiting.await() } },
            "the packet reads never reached the held read",
        )
        val interruptedAt = TimeSource.Monotonic.markNow()

        source.interrupt()

        assertNotNull(
            runBlocking { withTimeoutOrNull(5.seconds) { ended.await(); true } },
            "the packet read kept waiting after interrupt()",
        )
        assertTrue(reader.cancelled.isCompleted, "the reader's own read must have been cancelled")
        println("BoundedReadsTest: the held read ended ${interruptedAt.elapsedNow()} after interrupt()")
        session.close()
    }

    @Test
    fun anHttpOpenThroughTheUrlFallbackEndsWithinItsReadTimeout() {
        ServerSocket(0).use { server ->
            // Accepts every connection and never answers, like a server that hangs.
            val accepted = mutableListOf<Socket>()
            thread(isDaemon = true, name = "bounded-reads-silent-server") {
                runCatching {
                    while (true) {
                        val socket = server.accept()
                        synchronized(accepted) { accepted += socket }
                    }
                }
            }
            val outcome = CompletableDeferred<Result<Unit>>()
            val started = TimeSource.Monotonic.markNow()
            thread(isDaemon = true, name = "bounded-reads-open") {
                outcome.complete(
                    runCatching {
                        runBlocking {
                            KiteFFmpegMediaBackend().open(MediaItem("http://127.0.0.1:${server.localPort}/media.mkv")).close()
                        }
                    },
                )
            }

            val result = runBlocking { withTimeoutOrNull(URL_FALLBACK_READ_TIMEOUT + 15.seconds) { outcome.await() } }
            val took = started.elapsedNow()

            assertNotNull(result, "the open through the URL fallback was still waiting after $took")
            assertTrue(result.isFailure, "a server that never answers cannot open")
            assertTrue(took >= URL_FALLBACK_READ_TIMEOUT - 1.seconds, "the open failed after $took, before the read timeout")
            println("BoundedReadsTest: the silent open failed after $took with ${result.exceptionOrNull()}")
            synchronized(accepted) { accepted.forEach { runCatching { it.close() } } }
        }
    }
}
