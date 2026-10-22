package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.MediaIo
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The reader of a file still being written (#430): at the end it waits for more only while the
 * source reads packets, answers the end once the wait passes with nothing new, keeps that answer
 * for FFmpeg's second look, and reads on when the file grows after all.
 */
class GrowingMediaIoTest {

    /** Bytes of which the first [available] exist. */
    private class Growing(var available: Int) : MediaIo {
        private var position = 0L
        override val size: Long get() = available.toLong()
        override val seekable: Boolean = true
        override suspend fun read(into: ByteArray, offset: Int, length: Int): Int {
            if (position >= available) return -1
            val count = minOf(length.toLong(), available - position).toInt()
            position += count
            return count
        }
        override suspend fun seek(position: Long) {
            this.position = position
        }
        override fun close() = Unit
    }

    @Test
    fun anEndOutsideAPacketReadIsTheEndAtOnce() = runTest {
        val reader = GrowingMediaIo(Growing(100), endsAfter = 2.seconds)
        assertEquals(100, reader.read(ByteArray(200), 0, 200))
        assertEquals(-1, reader.read(ByteArray(200), 0, 200))
        assertEquals(0L, currentTime, "a probe or a seek search waited at the end")
    }

    @Test
    fun aPacketReadWaitsForTheFileAndEndsOneWaitAfterItStopsGrowing() = runTest {
        val file = Growing(100)
        val reader = GrowingMediaIo(file, endsAfter = 2.seconds).apply { waitAtEnd = true }
        assertEquals(100, reader.read(ByteArray(200), 0, 200))
        launch {
            kotlinx.coroutines.delay(500.milliseconds)
            file.available = 150
        }
        assertEquals(50, reader.read(ByteArray(200), 0, 200), "the bytes written while it waited were not read")
        val waitFrom = currentTime
        assertEquals(-1, reader.read(ByteArray(200), 0, 200))
        assertEquals(2_000L, currentTime - waitFrom, "the end did not come one wait after the file stopped")
        // FFmpeg looks again, and the end it was given stands.
        assertEquals(-1, reader.read(ByteArray(200), 0, 200))
        assertEquals(2_000L, currentTime - waitFrom, "the second look waited again")
        // A file that grows after all reads on.
        file.available = 180
        assertEquals(30, reader.read(ByteArray(200), 0, 200))
        assertEquals(150L + 30L, reader.size)
    }
}
