package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.KotlinAudioRing
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.AudioSinkBuffer
import io.github.yuroyami.kiteplayer.spi.SampleFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The device callback never waits for the feeder (#221). A slot held odd is the state a feeder
 * preempted mid-update leaves behind; every render must still return, date its anchor from the last
 * segment it resolved, and count the give-up. The render runs on the thread that holds the slot, so
 * a render that waited would never return and the test would hang instead of passing.
 */
class AudioRingBoundedTest {

    private val format = AudioFormat(48_000, 2, SampleFormat.F32)

    private class NullBuffer(override val format: AudioFormat) : AudioSinkBuffer {
        override fun writeInterleaved(source: FloatArray, sourceOffset: Int, destinationFrameOffset: Int, frames: Int) = Unit
        override fun writePlane(channel: Int, source: FloatArray, sourceOffset: Int, destinationFrameOffset: Int, frames: Int) = Unit
        override fun writeSilence(frameOffset: Int, frames: Int) = Unit
    }

    @Test
    fun aSlotHeldOddBeforeAnyAnchorPublishesNothingAndCountsTheGiveUp() {
        val ring = KotlinAudioRing(format, 4_800)
        ring.write(FloatArray(960 * 2), 0, 960, Pts(1_000_000))
        ring.beginSegmentWrite(0)
        ring.render(NullBuffer(format), 480, 0L)
        assertNull(ring.anchor(), "nothing was dated yet, so nothing is published")
        assertEquals(1L, ring.segmentGiveups)
        ring.endSegmentWrite(0)
    }

    @Test
    fun aSlotHeldOddDatesTheAnchorFromTheLastResolvedSegment() {
        val ring = KotlinAudioRing(format, 4_800)
        ring.write(FloatArray(960 * 2), 0, 960, Pts(1_000_000))
        ring.render(NullBuffer(format), 480, 0L)
        val first = assertNotNull(ring.anchor())
        assertEquals(1_010_000L, first.pts.micros, "480 frames at 48 kHz past the first timestamp")

        ring.beginSegmentWrite(0)
        repeat(2) { ring.render(NullBuffer(format), 240, 0L) }
        val cached = assertNotNull(ring.anchor())
        assertEquals(1_020_000L, cached.pts.micros, "dated from the cached segment, by continuity")
        assertEquals(2L, ring.segmentGiveups)
        ring.endSegmentWrite(0)

        ring.write(FloatArray(480 * 2), 0, 480, Pts(1_020_000))
        ring.render(NullBuffer(format), 240, 0L)
        assertEquals(1_025_000L, assertNotNull(ring.anchor()).pts.micros)
        assertEquals(2L, ring.segmentGiveups, "a released slot is read again")
    }
}
