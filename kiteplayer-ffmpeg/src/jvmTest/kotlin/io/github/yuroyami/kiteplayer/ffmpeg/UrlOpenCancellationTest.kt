package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.MediaItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * An open through FFmpeg's own http protocol can be cancelled while it waits for a server that
 * accepted the connection and then sends nothing (#31). Without the cancel, FFmpeg waits for its
 * 10 second read timeout.
 */
class UrlOpenCancellationTest {

    @Test
    fun aCancelledUrlOpenOnASilentServerEndsWithACancellation() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val accepted = CompletableDeferred<Socket>()
        thread(isDaemon = true) { runCatching { accepted.complete(server.accept()) } }
        try {
            val outcome = CompletableDeferred<Result<Unit>>()
            val opening = CoroutineScope(Dispatchers.IO).launch {
                outcome.complete(
                    runCatching {
                        KiteFFmpegMediaBackend().open(MediaItem("http://127.0.0.1:${server.localPort}/film.mkv")).close()
                    },
                )
            }
            assertNotNull(
                runBlocking { withTimeoutOrNull(10.seconds) { accepted.await() } },
                "the open never connected",
            )
            runBlocking { kotlinx.coroutines.delay(200.milliseconds) }
            val cancelledAt = TimeSource.Monotonic.markNow()

            opening.cancel()

            val result = runBlocking { withTimeoutOrNull(DEADLINE) { outcome.await() } }
            val took = cancelledAt.elapsedNow()
            assertNotNull(result, "the cancelled open was still waiting after $took")
            assertIs<CancellationException>(result.exceptionOrNull(), "a cancelled open must end as a cancellation, ended with $result")
            println("UrlOpenCancellationTest: the cancelled open ended $took after the cancel")
        } finally {
            runCatching { accepted.getCompleted().close() }
            server.close()
        }
    }

    private companion object {
        /** Half of FFmpeg's read timeout, so an open that ignores the cancel cannot pass. */
        val DEADLINE = 5.seconds
    }
}
