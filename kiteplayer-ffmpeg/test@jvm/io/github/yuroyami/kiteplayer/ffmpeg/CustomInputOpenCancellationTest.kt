package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.MediaItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * An open that reads through the item's own reader can be cancelled while FFmpeg discovers the
 * streams, and it ends with a `CancellationException` within the deadline, on the JVM with real
 * FFmpeg. The reader sends the start of the file and then nothing. A failing run leaves the open's
 * thread blocked, which is why nothing here closes what the open may still hold.
 */
class CustomInputOpenCancellationTest {

    @Test
    fun aCancelledOpenWhoseReaderHangsEndsWithACancellation() {
        // The Matroska header fits in the first 64 KiB; the streams need more than that.
        val reader = GatedReader(baselineMkvBytes(), gateAt = 64 * 1024)
        val outcome = CompletableDeferred<Result<Unit>>()
        val opening = CoroutineScope(Dispatchers.IO).launch {
            outcome.complete(
                runCatching {
                    KiteFFmpegMediaBackend().open(MediaItem("custom://baseline.mkv", io = { reader })).close()
                },
            )
        }
        assertNotNull(
            runBlocking { withTimeoutOrNull(10.seconds) { reader.waiting.await() } },
            "the open never reached the held read",
        )
        val cancelledAt = TimeSource.Monotonic.markNow()

        opening.cancel()

        val result = runBlocking { withTimeoutOrNull(DEADLINE) { outcome.await() } }
        val took = cancelledAt.elapsedNow()
        assertNotNull(result, "the cancelled open was still waiting after $took")
        assertIs<CancellationException>(result.exceptionOrNull(), "a cancelled open must end as a cancellation, ended with $result")
        assertTrue(reader.cancelled.isCompleted, "the reader's own read must have been cancelled")
        println("CustomInputOpenCancellationTest: the cancelled open ended $took after the cancel")
    }

    private companion object {
        /** The design promises an end as soon as the read resumes; this leaves room for a loaded machine. */
        val DEADLINE = 5.seconds
    }
}
