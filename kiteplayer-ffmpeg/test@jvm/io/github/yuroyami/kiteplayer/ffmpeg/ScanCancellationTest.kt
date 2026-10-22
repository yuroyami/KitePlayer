package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.scanAudio
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Cancelling a scan stops it while FFmpeg is blocked in a read through the item's own reader
 * (#411), on the JVM with real FFmpeg: it ends as a cancellation, and the reader is closed once.
 */
class ScanCancellationTest {

    /** A RIFF/WAVE header for ten minutes of 48 kHz stereo 16-bit, then silence: [total] bytes in all. */
    private fun wavPrefix(total: Int): ByteArray = ByteArray(total).also { b ->
        fun ascii(at: Int, s: String) = s.encodeToByteArray().copyInto(b, at)
        fun le(at: Int, x: Int, n: Int) { for (i in 0 until n) b[at + i] = (x ushr (8 * i)).toByte() }
        ascii(0, "RIFF"); le(4, 192_000 * 600 + 36, 4); ascii(8, "WAVEfmt "); le(16, 16, 4)
        le(20, 1, 2); le(22, 2, 2); le(24, 48_000, 4); le(28, 192_000, 4); le(32, 4, 2); le(34, 16, 2)
        ascii(36, "data"); le(40, 192_000 * 600, 4)
    }

    /** Serves 2 MiB of a WAV file and then waits for ever, as a reader whose sender went quiet does. */
    private inner class PrefixThenSuspend : MediaIo {
        private val prefix = wavPrefix(2 * 1024 * 1024)
        private var cursor = 0
        val stalled = CompletableDeferred<Unit>()
        val readCancelled = CompletableDeferred<Unit>()
        val closes = AtomicInteger()
        override val size: Long? = null
        override val seekable: Boolean = false
        override suspend fun read(into: ByteArray, offset: Int, length: Int): Int {
            if (cursor < prefix.size) {
                val n = minOf(length, prefix.size - cursor)
                prefix.copyInto(into, offset, cursor, cursor + n)
                cursor += n
                return n
            }
            stalled.complete(Unit)
            try {
                CompletableDeferred<Unit>().await()
            } catch (cancellation: CancellationException) {
                readCancelled.complete(Unit)
                throw cancellation
            }
            return -1
        }
        override suspend fun seek(position: Long) = error("not seekable")
        override fun close() {
            closes.incrementAndGet()
        }
    }

    @Test
    fun aCancelledScanEndsWhileFFmpegWaitsInARead() {
        val io = PrefixThenSuspend()
        val outcome = CompletableDeferred<Result<Unit>>()
        val blocks = AtomicInteger()
        val scan = CoroutineScope(Dispatchers.IO).launch {
            outcome.complete(
                runCatching {
                    scanAudio(MediaItem("test://stalled.wav", formatHint = "wav", io = { io }), KiteFFmpegMediaBackend(), sink = { _, _, _, _ ->
                        blocks.incrementAndGet()
                    })
                    Unit
                },
            )
        }
        assertNotNull(runBlocking { withTimeoutOrNull(20.seconds) { io.stalled.await() } }, "the scan never reached the held read")
        assertTrue(blocks.get() > 0, "nothing was decoded before the read held")

        scan.cancel()

        val result = runBlocking { withTimeoutOrNull(5.seconds) { outcome.await() } }
        assertNotNull(result, "the cancelled scan was still waiting in its read")
        assertIs<CancellationException>(result.exceptionOrNull(), "a cancelled scan must end as a cancellation, ended with $result")
        assertTrue(io.readCancelled.isCompleted, "the reader's own read was never cancelled")
        assertEquals(1, io.closes.get(), "the reader was closed ${io.closes.get()} times")
    }
}
