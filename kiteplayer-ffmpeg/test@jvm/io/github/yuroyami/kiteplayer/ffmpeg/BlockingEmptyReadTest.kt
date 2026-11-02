package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.MediaIo
import kotlinx.coroutines.Job
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A reader that answers 0 while it waits for its data is asked again with a growing pause, not
 * every millisecond (#660). One that waited for a torrent piece woke the demux thread about a
 * thousand times a second for as long as it waited.
 */
class BlockingEmptyReadTest {

    /** Answers 0 until [readyAtNanos], then the end of the stream, and counts every read. */
    private class WaitingReader(private val readyAtNanos: Long) : MediaIo {
        val reads = AtomicInteger()
        override val size: Long? = null
        override val seekable: Boolean = false

        override suspend fun read(into: ByteArray, offset: Int, length: Int): Int {
            reads.incrementAndGet()
            return if (System.nanoTime() < readyAtNanos) 0 else -1
        }

        override suspend fun seek(position: Long) = error("this reader cannot seek")

        override fun close() = Unit
    }

    @Test
    fun aSecondOfEmptyReadsAsksTheReaderAFewDozenTimes() {
        val reader = WaitingReader(System.nanoTime() + 1_000_000_000L)
        val bridge = BlockingMediaIo(reader, Job())
        assertEquals(-1, bridge.read(ByteArray(64), 0, 64))
        // 1, 2, 4, 8 and 16 ms, then 20 ms at a time: about 55 reads in one second. A wait of 1 ms
        // each time made about 950.
        assertTrue(reader.reads.get() < 100, "a second of waiting asked the reader ${reader.reads.get()} times")
    }
}
