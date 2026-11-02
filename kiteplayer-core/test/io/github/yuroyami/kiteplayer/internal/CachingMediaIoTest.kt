package io.github.yuroyami.kiteplayer.internal

import io.github.yuroyami.kiteplayer.IoCachePolicy
import io.github.yuroyami.kiteplayer.MediaIo
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The byte cache proved against a counting in-memory source: chunked upstream pulls, RAM
 * seek-back with zero upstream traffic, eviction that honours both budgets, and window
 * publication for the progress sampler.
 */
class CachingMediaIoTest {

    /** Deterministic content: byte at position p is (p * 31 + 7) mod 256. */
    private fun contentByte(position: Long): Byte = ((position * 31 + 7) and 0xFF).toByte()

    /** Answers at most [answerAtMost] bytes a read, as a network reader answers what it has. */
    private class MemorySource(val sizeBytes: Long, val answerAtMost: Int = Int.MAX_VALUE) : MediaIo {
        var position = 0L
        var reads = 0
        var seeks = 0
        var closed = false
        override val size: Long get() = sizeBytes
        override val seekable: Boolean = true
        override suspend fun read(into: ByteArray, offset: Int, length: Int): Int {
            reads++
            if (position >= sizeBytes) return -1
            val count = length.coerceAtMost(answerAtMost).coerceAtMost((sizeBytes - position).toInt())
            for (i in 0 until count) into[offset + i] = ((position + i) * 31 + 7 and 0xFF).toByte()
            position += count
            return count
        }
        override suspend fun seek(position: Long) {
            seeks++
            this.position = position
        }
        override fun close() { closed = true }
    }

    private fun policy(chunk: Int = 1024, back: Long = 4096, total: Long = 16384) =
        IoCachePolicy(readChunkBytes = chunk, backWindowBytes = back, forwardWindowBytes = total)

    private suspend fun readFully(io: MediaIo, want: Int): ByteArray {
        val out = ByteArray(want)
        var at = 0
        while (at < want) {
            val r = io.read(out, at, want - at)
            check(r > 0) { "short read at $at" }
            at += r
        }
        return out
    }

    @Test
    fun readsAreChunkedAndContentIsExact() = runTest {
        val source = MemorySource(100_000)
        val cache = CachingMediaIo(source, policy(chunk = 1024))
        val bytes = readFully(cache, 10_000)
        for (i in 0 until 10_000) {
            assertEquals(contentByte(i.toLong()), bytes[i], "byte $i corrupted through the cache")
        }
        // 10000 bytes at 1024-byte pulls is exactly 10 upstream reads, not one per demuxer nibble.
        assertEquals(10, source.reads, "the cache must pull in chunks")
    }

    @Test
    fun seekBackInsideTheWindowTouchesNoUpstream() = runTest {
        val source = MemorySource(100_000)
        val cache = CachingMediaIo(source, policy())
        readFully(cache, 8_000)
        val readsBefore = source.reads

        cache.seek(1_000)
        val replay = readFully(cache, 4_000)
        for (i in 0 until 4_000) {
            assertEquals(contentByte(1_000L + i), replay[i], "replayed byte $i wrong")
        }
        assertEquals(0, source.seeks, "a seek inside the window must reach no upstream seek")
        assertEquals(readsBefore, source.reads, "a replay inside the window must reach no upstream read")
    }

    @Test
    fun farSeekResetsTheWindowAndSeeksUpstreamOnce() = runTest {
        val source = MemorySource(1_000_000)
        val cache = CachingMediaIo(source, policy())
        readFully(cache, 2_000)
        cache.seek(500_000)
        val bytes = readFully(cache, 1_000)
        assertEquals(1, source.seeks, "one far seek is one upstream seek")
        for (i in 0 until 1_000) {
            assertEquals(contentByte(500_000L + i), bytes[i], "post-seek byte $i wrong")
        }
        assertEquals(500_000L, cache.window.value.start, "the window must restart at the seek target")
    }

    @Test
    fun evictionHonoursTheTotalBudgetAndKeepsTheBackWindow() = runTest {
        val source = MemorySource(1_000_000)
        val cache = CachingMediaIo(source, policy(chunk = 1024, back = 4096, total = 16384))
        readFully(cache, 200_000)
        val (start, end) = cache.window.value
        assertTrue(end - start <= 16384 + 1024, "window ${end - start} bytes exceeds its budget")
        assertTrue(200_000L - start >= 4096, "the back window behind the cursor was evicted")

        // The kept back window replays from RAM.
        val readsBefore = source.reads
        cache.seek(200_000L - 4096)
        readFully(cache, 4096)
        assertEquals(readsBefore, source.reads, "the back window must serve from RAM")
    }

    @Test
    fun eofIsRememberedAndCloseClosesUpstream() = runTest {
        val source = MemorySource(3_000)
        val cache = CachingMediaIo(source, policy(chunk = 1024))
        readFully(cache, 3_000)
        val out = ByteArray(16)
        assertEquals(-1, cache.read(out, 0, 16), "end of stream must answer -1")
        assertEquals(-1, cache.read(out, 0, 16), "and stay -1")
        cache.close()
        assertTrue(source.closed, "close must close the upstream reader")
    }

    /**
     * A source that answers less than a chunk a read fills each chunk over several reads (#649), so
     * a chunk boundary falls inside one upstream answer. The bytes, the replay from the window, the
     * budgets and a far seek all hold across those boundaries.
     */
    @Test
    fun smallUpstreamAnswersFillWholeChunks() = runTest {
        val source = MemorySource(1_000_000, answerAtMost = 300)
        val cache = CachingMediaIo(source, policy(chunk = 1024, back = 4096, total = 16384))
        val bytes = readFully(cache, 100_000)
        for (i in 0 until 100_000) {
            assertEquals(contentByte(i.toLong()), bytes[i], "byte $i corrupted through the cache")
        }
        val (start, end) = cache.window.value
        assertTrue(end - start <= 16384 + 1024, "window ${end - start} bytes exceeds its budget")
        assertTrue(100_000L - start >= 4096, "the back window behind the cursor was evicted")

        val readsBefore = source.reads
        cache.seek(100_000L - 4096 + 7)
        val replay = readFully(cache, 4096 - 7)
        for (i in replay.indices) {
            assertEquals(contentByte(100_000L - 4096 + 7 + i), replay[i], "replayed byte $i wrong")
        }
        assertEquals(readsBefore, source.reads, "the back window must serve from RAM")

        cache.seek(500_003)
        val far = readFully(cache, 5_000)
        for (i in far.indices) assertEquals(contentByte(500_003L + i), far[i], "post-seek byte $i wrong")
    }
}
