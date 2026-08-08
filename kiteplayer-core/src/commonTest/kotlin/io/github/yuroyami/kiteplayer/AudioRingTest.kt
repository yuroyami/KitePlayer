package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.AudioRing
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.AudioSinkBuffer
import io.github.yuroyami.kiteplayer.spi.SampleFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** Stands in for a device buffer, and records exactly what the ring wrote where. */
private class CapturingSinkBuffer(override val format: AudioFormat, frames: Int) : AudioSinkBuffer {
    val samples = FloatArray(frames * format.channels) { Float.NaN }

    override fun writeInterleaved(source: FloatArray, sourceOffset: Int, destinationFrameOffset: Int, frames: Int) {
        source.copyInto(
            destination = samples,
            destinationOffset = destinationFrameOffset * format.channels,
            startIndex = sourceOffset,
            endIndex = sourceOffset + frames * format.channels,
        )
    }

    override fun writePlane(
        channel: Int,
        source: FloatArray,
        sourceOffset: Int,
        destinationFrameOffset: Int,
        frames: Int,
    ) {
        for (i in 0 until frames) {
            samples[(destinationFrameOffset + i) * format.channels + channel] = source[sourceOffset + i]
        }
    }

    override fun writeSilence(frameOffset: Int, frames: Int) {
        for (i in 0 until frames * format.channels) {
            samples[frameOffset * format.channels + i] = 0f
        }
    }

    /** The interleaved frame at [index], as its first channel's value. */
    fun frame(index: Int): Float = samples[index * format.channels]
}

class AudioRingTest {

    private val stereo48k = AudioFormat(sampleRate = 48_000, channels = 2, sampleFormat = SampleFormat.F32)

    /** A ramp, so every frame is identifiable by its value. */
    private fun ramp(frames: Int, from: Int = 0): FloatArray =
        FloatArray(frames * 2) { i -> (from + i / 2).toFloat() }

    @Test
    fun `samples come back in order`() {
        val ring = AudioRing(stereo48k, capacityFrames = 1_024)
        assertEquals(256, ring.write(ramp(256), 0, 256, pts(0)))

        val out = CapturingSinkBuffer(stereo48k, 256)
        assertEquals(256, ring.render(out, 256, deadlineNanos = 0))

        for (i in 0 until 256) assertEquals(i.toFloat(), out.frame(i))
    }

    @Test
    fun `a read that wraps the ring still lands contiguously in the device buffer`() {
        // This is the bug a destination offset exists to prevent: the second half of a wrapped read
        // overwriting the first half instead of following it.
        val ring = AudioRing(stereo48k, capacityFrames = 100)

        // Fill and drain most of the ring so the write cursor sits near the end.
        ring.write(ramp(80), 0, 80, pts(0))
        ring.render(CapturingSinkBuffer(stereo48k, 80), 80, 0)

        // Now write 40 frames, which wraps at 100.
        assertEquals(40, ring.write(ramp(40, from = 1_000), 0, 40, null))

        val out = CapturingSinkBuffer(stereo48k, 40)
        assertEquals(40, ring.render(out, 40, 0))
        for (i in 0 until 40) {
            assertEquals((1_000 + i).toFloat(), out.frame(i), "frame $i of a wrapped read")
        }
    }

    @Test
    fun `a write larger than the free space is accepted in part`() {
        val ring = AudioRing(stereo48k, capacityFrames = 100)
        assertEquals(100, ring.write(ramp(500), 0, 500, pts(0)))
        assertEquals(0, ring.write(ramp(10), 0, 10, null), "a full ring accepts nothing")
        assertEquals(0, ring.freeFrames)

        ring.render(CapturingSinkBuffer(stereo48k, 30), 30, 0)
        assertEquals(30, ring.freeFrames)
        assertEquals(30, ring.write(ramp(100), 0, 100, null))
    }

    @Test
    fun `running dry writes silence and counts an underrun`() {
        val ring = AudioRing(stereo48k, capacityFrames = 512)
        ring.write(ramp(100, from = 1), 0, 100, pts(0))

        val out = CapturingSinkBuffer(stereo48k, 256)
        assertEquals(100, ring.render(out, 256, 0), "only the real audio is reported")
        assertEquals(1, ring.underruns)

        assertEquals(1f, out.frame(0))
        assertEquals(100f, out.frame(99))
        for (i in 100 until 256) {
            assertEquals(0f, out.frame(i), "frame $i must be silence, never left uninitialised")
        }
    }

    @Test
    fun `buffered duration is reported in media time`() {
        val ring = AudioRing(stereo48k, capacityFrames = 48_000)
        ring.write(ramp(4_800), 0, 4_800, pts(0))
        assertEquals(100.milliseconds.inWholeMicroseconds, ring.bufferedUs)
        assertEquals(4_800, ring.bufferedFrames)
    }

    @Test
    fun `there is no anchor before the device has played anything`() {
        val ring = AudioRing(stereo48k, capacityFrames = 1_024)
        assertNull(ring.anchor())

        ring.write(ramp(100), 0, 100, pts(0))
        assertNull(ring.anchor(), "writing is not playing: the anchor comes from the device callback")
    }

    @Test
    fun `the anchor reports the timestamp of the last frame handed over and when it is audible`() {
        val ring = AudioRing(stereo48k, capacityFrames = 4_800)
        // 480 frames is 10 ms at 48 kHz, starting at media time 5.000 s.
        ring.write(ramp(480), 0, 480, pts(5_000))

        val deadline = 1_000_000_000L
        ring.render(CapturingSinkBuffer(stereo48k, 480), 480, deadlineNanos = deadline)

        val anchor = assertNotNull(ring.anchor())
        // The last of 480 frames is frame 479, which is 479/48000 s after 5.000 s.
        assertEquals(5_000_000L + 479L * 1_000_000L / 48_000L, anchor.pts.micros)
        assertEquals(
            deadline,
            anchor.audibleAtNanos,
            "the deadline is when that frame is heard, so no device latency needs subtracting anywhere",
        )
    }

    @Test
    fun `a partly silent buffer back-dates the anchor by the silence`() {
        val ring = AudioRing(stereo48k, capacityFrames = 4_800)
        ring.write(ramp(240), 0, 240, pts(1_000))

        // The device asked for 480 frames and got 240 of audio then 240 of silence. The last real
        // frame is heard 240 frames before the end of the buffer, which is 5 ms earlier.
        val deadline = 1_000_000_000L
        ring.render(CapturingSinkBuffer(stereo48k, 480), 480, deadlineNanos = deadline)

        val anchor = assertNotNull(ring.anchor())
        assertEquals(deadline - 5_000_000L, anchor.audibleAtNanos)
    }

    @Test
    fun `the timestamp mapping survives buffers that carry no timestamp`() {
        // Most decoded audio buffers after the first carry a timestamp, but not all do, and the
        // mapping must keep working by continuity when they do not.
        val ring = AudioRing(stereo48k, capacityFrames = 48_000)
        ring.write(ramp(480), 0, 480, pts(2_000))
        repeat(9) { ring.write(ramp(480), 0, 480, null) }

        ring.render(CapturingSinkBuffer(stereo48k, 4_800), 4_800, deadlineNanos = 0)
        val anchor = assertNotNull(ring.anchor())
        // 4800 frames is 100 ms, so the last frame is at 2.000 s plus 4799/48000 s.
        assertEquals(2_000_000L + 4_799L * 1_000_000L / 48_000L, anchor.pts.micros)
    }

    @Test
    fun `a real discontinuity re-anchors the mapping`() {
        val ring = AudioRing(stereo48k, capacityFrames = 48_000)
        ring.write(ramp(480), 0, 480, pts(1_000))
        // The next buffer claims 9.000 s, an eight second gap. Continuity would predict 1.010 s, so
        // the mapping must move rather than keep reporting the old timeline.
        ring.write(ramp(480), 0, 480, pts(9_000))

        ring.render(CapturingSinkBuffer(stereo48k, 960), 960, deadlineNanos = 0)
        val anchor = assertNotNull(ring.anchor())
        assertTrue(
            anchor.pts.micros >= 9_000_000L,
            "after a discontinuity the anchor must follow the new timeline, was ${anchor.pts}",
        )
    }

    @Test
    fun `flush discards what is unplayed and drops the anchor`() {
        val ring = AudioRing(stereo48k, capacityFrames = 4_800)
        ring.write(ramp(1_000), 0, 1_000, pts(3_000))
        ring.render(CapturingSinkBuffer(stereo48k, 100), 100, deadlineNanos = 0)
        assertNotNull(ring.anchor())

        ring.flush()
        assertEquals(0, ring.bufferedFrames)
        assertNull(ring.anchor(), "after a seek the audio clock has no valid reading until new audio plays")

        // And the ring is usable again immediately.
        assertEquals(500, ring.write(ramp(500), 0, 500, pts(20_000)))
        ring.render(CapturingSinkBuffer(stereo48k, 500), 500, deadlineNanos = 0)
        assertEquals(20_000_000L + 499L * 1_000_000L / 48_000L, assertNotNull(ring.anchor()).pts.micros)
    }

    @Test
    fun `a long session of small writes and reads stays consistent`() {
        // Exercises the wrap arithmetic repeatedly, which is where an off-by-one hides.
        val ring = AudioRing(stereo48k, capacityFrames = 333)
        var nextFrame = 0
        var readFrames = 0

        repeat(2_000) {
            val toWrite = 77
            val accepted = ring.write(ramp(toWrite, from = nextFrame), 0, toWrite, null)
            nextFrame += accepted

            val out = CapturingSinkBuffer(stereo48k, 50)
            val got = ring.render(out, 50, deadlineNanos = 0)
            for (i in 0 until got) {
                assertEquals((readFrames + i).toFloat(), out.frame(i), "sample ${readFrames + i}")
            }
            readFrames += got
        }

        assertTrue(readFrames > 90_000, "the session should have moved real audio, moved $readFrames frames")
    }
}
