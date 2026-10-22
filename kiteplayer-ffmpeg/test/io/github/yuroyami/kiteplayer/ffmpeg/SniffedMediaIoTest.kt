package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.MediaIo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** A sniffed prefix must not swallow the seek that lets a byte cache read past its old end (#430). */
class SniffedMediaIoTest {

    /** Like the byte cache: once the end is read, appended bytes need a seek before another read. */
    private class LatchedEnd(var available: Int, override val seekable: Boolean = true) : MediaIo {
        private var position = 0
        private var ended = false
        var zerosRemaining = 0
        var reads = 0
        val seeks = mutableListOf<Long>()

        override val size: Long get() = available.toLong()

        override suspend fun read(into: ByteArray, offset: Int, length: Int): Int {
            reads++
            if (ended) return -1
            if (zerosRemaining > 0) { zerosRemaining--; return 0 }
            if (position == available) { ended = true; return -1 }
            val count = minOf(length, available - position)
            for (index in 0 until count) into[offset + index] = (position + index).toByte()
            position += count
            return count
        }

        override suspend fun seek(position: Long) {
            check(seekable) { "a nonseekable reader was asked to seek" }
            require(position in 0L..available.toLong())
            seeks += position
            this.position = position.toInt()
            ended = false
        }

        override fun close() = Unit
    }

    private suspend fun expectBytes(reader: MediaIo, from: Int, count: Int) {
        val out = ByteArray(count + 2) { -1 }
        assertEquals(count, reader.read(out, 1, count))
        assertContentEquals(ByteArray(count) { (from + it).toByte() }, out.copyOfRange(1, count + 1))
        assertEquals((-1).toByte(), out.first(), "the byte before the read slice changed")
        assertEquals((-1).toByte(), out.last(), "the byte after the read slice changed")
    }

    @Test
    fun aSamePositionSeekAfterEofRefreshesTheUpstreamReaderLazily() = runTest {
        val source = LatchedEnd(6)
        val reader = SniffedMediaIo.sniff(source, 4)
        expectBytes(reader, 0, 4)
        expectBytes(reader, 4, 2)
        assertEquals(-1, reader.read(ByteArray(4), 0, 4))
        source.available = 10
        reader.seek(6)
        assertEquals(emptyList(), source.seeks, "the seek was eager")
        expectBytes(reader, 6, 4)
        assertEquals(listOf(6L), source.seeks)
    }

    @Test
    fun anEndSeenDuringTheSniffIsAlsoRefreshedByAnExplicitSeek() = runTest {
        val source = LatchedEnd(3)
        val reader = SniffedMediaIo.sniff(source, 8)
        assertEquals(2, source.reads, "sniffing must actually observe the short file's EOF")
        source.available = 5
        reader.seek(3)
        assertEquals(emptyList(), source.seeks)
        expectBytes(reader, 3, 2)
        assertEquals(listOf(3L), source.seeks)
    }

    @Test
    fun replayingTheHeadAfterEofKeepsTheRefreshUntilTheNextUpstreamRead() = runTest {
        val source = LatchedEnd(4)
        val reader = SniffedMediaIo.sniff(source, 4)
        expectBytes(reader, 0, 4)
        assertEquals(-1, reader.read(ByteArray(4), 0, 4))
        source.available = 6
        val readsAtEnd = source.reads
        reader.seek(0)
        expectBytes(reader, 0, 4)
        assertEquals(readsAtEnd, source.reads, "the cached prefix was read again upstream")
        assertEquals(emptyList(), source.seeks, "replaying the prefix sought upstream")
        expectBytes(reader, 4, 2)
        assertEquals(listOf(4L), source.seeks)
    }

    @Test
    fun anOrdinarySamePositionSeekDoesNotTouchUpstream() = runTest {
        val source = LatchedEnd(8)
        val reader = SniffedMediaIo.sniff(source, 4)
        expectBytes(reader, 0, 4)
        reader.seek(4)
        expectBytes(reader, 4, 2)
        reader.seek(6)
        expectBytes(reader, 6, 2)
        assertEquals(emptyList(), source.seeks)
    }

    @Test
    fun headReplayAndAChangedTailPositionKeepBytesInOrder() = runTest {
        val source = LatchedEnd(10)
        val reader = SniffedMediaIo.sniff(source, 4)
        expectBytes(reader, 0, 4)
        expectBytes(reader, 4, 4)
        reader.seek(1)
        expectBytes(reader, 1, 3)
        assertEquals(emptyList(), source.seeks)
        expectBytes(reader, 4, 6)
        assertEquals(listOf(4L), source.seeks)
    }

    @Test
    fun aNonseekableReaderCanReplayItsPrefixWithoutAnUpstreamSeek() = runTest {
        val source = LatchedEnd(8, seekable = false)
        val reader = SniffedMediaIo.sniff(source, 4)
        expectBytes(reader, 0, 2)
        reader.seek(1)
        expectBytes(reader, 1, 3)
        expectBytes(reader, 4, 4)
        assertEquals(emptyList(), source.seeks)

        val short = LatchedEnd(3, seekable = false)
        val shortReader = SniffedMediaIo.sniff(short, 8)
        shortReader.seek(1)
        expectBytes(shortReader, 1, 2)
        assertEquals(-1, shortReader.read(ByteArray(4), 0, 4))
        assertEquals(emptyList(), short.seeks, "a short nonseekable prefix tried to refresh by seeking")
    }

    @Test
    fun repeatedZeroReadsDoNotCountAsAnEndOrCauseExtraSeeks() = runTest {
        val source = LatchedEnd(6).apply { zerosRemaining = 3 }
        val reader = SniffedMediaIo.sniff(source, 4)
        assertEquals(3L, currentTime, "the sniff did not retry transient empty reads")
        expectBytes(reader, 0, 4)
        source.zerosRemaining = 3
        repeat(3) {
            reader.seek(4)
            assertEquals(0, reader.read(ByteArray(2), 0, 2))
        }
        expectBytes(reader, 4, 2)
        assertEquals(emptyList(), source.seeks)
    }

    @Test
    fun aSuccessfulEofRefreshIsNotRepeatedForTransientEmptyReads() = runTest {
        val source = LatchedEnd(4)
        val reader = SniffedMediaIo.sniff(source, 4)
        expectBytes(reader, 0, 4)
        assertEquals(-1, reader.read(ByteArray(2), 0, 2))
        source.available = 6
        source.zerosRemaining = 2
        repeat(2) {
            reader.seek(4)
            assertEquals(0, reader.read(ByteArray(2), 0, 2))
        }
        reader.seek(4)
        expectBytes(reader, 4, 2)
        assertEquals(listOf(4L), source.seeks)
    }

    @Test
    fun growthThroughASniffedReaderPassesTheOldEndThenFinishesAfterItsWait() = runTest {
        val source = LatchedEnd(6)
        val reader = GrowingMediaIo(SniffedMediaIo.sniff(source, 4), endsAfter = 1.seconds).apply {
            waitAtEnd = true
        }
        expectBytes(reader, 0, 4)
        expectBytes(reader, 4, 2)
        launch {
            delay(500.milliseconds)
            source.available = 9
        }
        expectBytes(reader, 6, 3)
        assertTrue(currentTime in 500L..600L, "the appended bytes arrived at $currentTime ms")
        val from = currentTime
        assertEquals(-1, reader.read(ByteArray(4), 0, 4))
        assertEquals(1_000L, currentTime - from)
        assertEquals(-1, reader.read(ByteArray(4), 0, 4))
        assertEquals(1_000L, currentTime - from, "the confirmed end waited again")
    }
}
