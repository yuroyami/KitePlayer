package io.github.yuroyami.kiteplayer.ffmpeg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Thumbnails and waveforms compare by content, so a cache keyed on one finds an equal one. */
class ArrayValueEqualityTest {

    @Test
    fun thumbnailsWithEqualBytesAreEqual() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val first = Thumbnail(2.seconds, 160, 90, bytes, SnapshotFormat.Png)
        val second = Thumbnail(2.seconds, 160, 90, bytes.copyOf(), SnapshotFormat.Png)
        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertEquals(setOf(first), setOf(second))
        assertNotEquals(first, second.copy(bytes = byteArrayOf(1, 2, 3, 5)))
        assertNotEquals(first, second.copy(width = 161))
    }

    @Test
    fun waveformsWithEqualBucketsAreEqual() {
        val first = Waveform(10.milliseconds, floatArrayOf(0.1f, 0.5f), floatArrayOf(0.05f, 0.2f))
        val second = Waveform(10.milliseconds, floatArrayOf(0.1f, 0.5f), floatArrayOf(0.05f, 0.2f))
        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertNotEquals(first, second.copy(rms = floatArrayOf(0.05f, 0.3f)))
        assertNotEquals(first, second.copy(bucketDuration = 20.milliseconds))
    }
}
