package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.CachingMediaIo
import kotlinx.coroutines.runBlocking
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The byte cache allocates about what it keeps, not a whole chunk for each upstream read (#649).
 * A network reader answers about 8 KiB a read, and the cache made a 256 KiB array for every one:
 * about 82 MB of garbage a second at 20 Mbit/s.
 */
class CacheAllocationTest {

    /** 64 MiB, answered 8 KiB a read, as Ktor's channel gives its head segment. */
    private class EightKibSource : MediaIo {
        private var position = 0L
        override val size: Long = TOTAL
        override val seekable: Boolean = false
        override suspend fun read(into: ByteArray, offset: Int, length: Int): Int {
            if (position >= TOTAL) return -1
            val count = minOf(length, 8192, (TOTAL - position).toInt())
            position += count
            return count
        }
        override suspend fun seek(position: Long) = error("not seekable")
        override fun close() = Unit
    }

    @Test
    fun readingThroughTheCacheAllocatesAboutWhatItKeeps() {
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val thread = Thread.currentThread().id
        val cache = CachingMediaIo(EightKibSource(), IoCachePolicy())
        val into = ByteArray(64 * 1024)
        runBlocking { cache.read(into, 0, into.size) }
        val before = threads.getThreadAllocatedBytes(thread)
        runBlocking {
            while (cache.read(into, 0, into.size) >= 0) Unit
        }
        val allocated = threads.getThreadAllocatedBytes(thread) - before
        // 64 MiB of chunks at most, and the coroutine machinery of 8,192 reads. A chunk for each
        // read made about 2.2 GB.
        assertTrue(allocated < 200L * 1024 * 1024, "reading 64 MiB through the cache allocated ${allocated / (1024 * 1024)} MiB")
    }

    private companion object {
        const val TOTAL = 64L * 1024 * 1024
    }
}
