package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.KotlinAudioRing
import io.github.yuroyami.kiteplayer.internal.framesToMicros
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

/** One step of a table case: either the feeder writing, or the device asking. */
private sealed interface RingStep

/**
 * The feeder hands over [frames] frames.
 *
 * @param startsAtMediaFrame where the decoder said the first of them sits on the media timeline,
 *        counted in frames at the rate under test, or null for a buffer that carries no timestamp.
 *        Counting in frames rather than in microseconds is what keeps one table meaningful at three
 *        sample rates: a gap of a hundred thousand frames is a real discontinuity at every rate.
 */
private class Feed(val frames: Int, val startsAtMediaFrame: Long?) : RingStep

/**
 * The device asks for [frames] frames, and the anchor the ring publishes is checked exactly.
 *
 * The expected timestamp is given as a segment and an offset into it, never as a number of
 * microseconds, because that is what sample exact means here: the boundary sits [framesIntoSegment]
 * frames after the first frame of the segment starting at media frame [segmentStartsAt], and the only
 * arithmetic allowed between the two is the one the sample rate defines.
 */
private class Pull(
    val frames: Int,
    val expectRealFrames: Int,
    val segmentStartsAt: Long,
    val framesIntoSegment: Long,
) : RingStep

private class RingCase(val name: String, val steps: List<RingStep>)

/**
 * The portable ring's own tests.
 *
 * ### What these 18 tests cover, and what they do NOT cover
 *
 * They cover [KotlinAudioRing], which is the implementation js, wasmJs, jvm and Android use. From
 * B1.8 onward it is NOT the implementation the macOS device path uses: there the C ring in
 * `kiteplayer-rt` holds the samples and a `static` C function renders them. So on macOS these tests
 * exercise a ring no user runs, and letting a total test count quietly imply otherwise would be
 * exactly the substitution KPKMP-PAST.md section 2 forbids. That is register item B1-20, and the plan
 * requires it said in three places: here, in the README, and in the execution log.
 *
 * The shipped macOS path is carried by two other things instead. The C suites under
 * `kiteplayer-rt/native/tests`, which run under plain, ASan plus UBSan, and TSan builds. And
 * `AudioRingDifferentialTest` in `kiteplayer-core/src/nativeTest`, which drives both rings through
 * the same scripted sequence and asserts the samples, the frame counts, the published anchor and the
 * underrun counter agree exactly. The oracle is the only thing that keeps the two implementations of
 * one contract from drifting apart, and neither of them can be deleted: `commonMain` targets js and
 * wasmJs, which can never contain C, and the Kotlin ring is the only oracle the C ring can be checked
 * against.
 */
class AudioRingTest {

    private val stereo48k = AudioFormat(sampleRate = 48_000, channels = 2, sampleFormat = SampleFormat.F32)

    /** A ramp, so every frame is identifiable by its value. */
    private fun ramp(frames: Int, from: Int = 0): FloatArray =
        FloatArray(frames * 2) { i -> (from + i / 2).toFloat() }

    @Test
    fun `samples come back in order`() {
        val ring = KotlinAudioRing(stereo48k, capacityFrames = 1_024)
        assertEquals(256, ring.write(ramp(256), 0, 256, pts(0)))

        val out = CapturingSinkBuffer(stereo48k, 256)
        assertEquals(256, ring.render(out, 256, deadlineNanos = 0))

        for (i in 0 until 256) assertEquals(i.toFloat(), out.frame(i))
    }

    @Test
    fun `a read that wraps the ring still lands contiguously in the device buffer`() {
        // This is the bug a destination offset exists to prevent: the second half of a wrapped read
        // overwriting the first half instead of following it.
        val ring = KotlinAudioRing(stereo48k, capacityFrames = 100)

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
        val ring = KotlinAudioRing(stereo48k, capacityFrames = 100)
        assertEquals(100, ring.write(ramp(500), 0, 500, pts(0)))
        assertEquals(0, ring.write(ramp(10), 0, 10, null), "a full ring accepts nothing")
        assertEquals(0, ring.freeFrames)

        ring.render(CapturingSinkBuffer(stereo48k, 30), 30, 0)
        assertEquals(30, ring.freeFrames)
        assertEquals(30, ring.write(ramp(100), 0, 100, null))
    }

    @Test
    fun `running dry writes silence and counts an underrun`() {
        val ring = KotlinAudioRing(stereo48k, capacityFrames = 512)
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
        val ring = KotlinAudioRing(stereo48k, capacityFrames = 48_000)
        ring.write(ramp(4_800), 0, 4_800, pts(0))
        assertEquals(100.milliseconds.inWholeMicroseconds, ring.bufferedUs)
        assertEquals(4_800, ring.bufferedFrames)
    }

    @Test
    fun `there is no anchor before the device has played anything`() {
        val ring = KotlinAudioRing(stereo48k, capacityFrames = 1_024)
        assertNull(ring.anchor())

        ring.write(ramp(100), 0, 100, pts(0))
        assertNull(ring.anchor(), "writing is not playing: the anchor comes from the device callback")
    }

    @Test
    fun `the anchor is the media time at the playhead boundary when that boundary is reached`() {
        val ring = KotlinAudioRing(stereo48k, capacityFrames = 4_800)
        // 480 frames is 10 ms at 48 kHz, starting at media time 5.000 s.
        ring.write(ramp(480), 0, 480, pts(5_000))

        val deadline = 1_000_000_000L
        ring.render(CapturingSinkBuffer(stereo48k, 480), 480, deadlineNanos = deadline)

        val anchor = assertNotNull(ring.anchor())
        // Once all 480 frames have been heard the media has played up to 5.000 s plus 480/48000 s.
        // Publishing the last frame's own start instead, 479/48000 s, is one sample period of fixed
        // error that nothing later corrects.
        assertEquals(5_000_000L + 480L * 1_000_000L / 48_000L, anchor.pts.micros)
        assertEquals(
            deadline,
            anchor.audibleAtNanos,
            "the deadline is when that boundary is reached, so no device latency needs subtracting anywhere",
        )
    }

    @Test
    fun `a partly silent buffer back-dates the anchor by the silence`() {
        val ring = KotlinAudioRing(stereo48k, capacityFrames = 4_800)
        ring.write(ramp(240), 0, 240, pts(1_000))

        // The device asked for 480 frames and got 240 of audio then 240 of silence. The last real
        // frame is heard 240 frames before the end of the buffer, which is 5 ms earlier.
        val deadline = 1_000_000_000L
        ring.render(CapturingSinkBuffer(stereo48k, 480), 480, deadlineNanos = deadline)

        val anchor = assertNotNull(ring.anchor())
        assertEquals(deadline - 5_000_000L, anchor.audibleAtNanos)
        assertEquals(1_000_000L + 240L * 1_000_000L / 48_000L, anchor.pts.micros, "the silence dates nothing")
    }

    @Test
    fun `the timestamp mapping survives buffers that carry no timestamp`() {
        // Most decoded audio buffers after the first carry a timestamp, but not all do, and the
        // mapping must keep working by continuity when they do not.
        val ring = KotlinAudioRing(stereo48k, capacityFrames = 48_000)
        ring.write(ramp(480), 0, 480, pts(2_000))
        repeat(9) { ring.write(ramp(480), 0, 480, null) }

        ring.render(CapturingSinkBuffer(stereo48k, 4_800), 4_800, deadlineNanos = 0)
        val anchor = assertNotNull(ring.anchor())
        // 4800 frames is 100 ms, so the boundary after all of them is 2.000 s plus 4800/48000 s.
        assertEquals(2_000_000L + 4_800L * 1_000_000L / 48_000L, anchor.pts.micros)
    }

    @Test
    fun `a real discontinuity opens a segment of its own`() {
        val ring = KotlinAudioRing(stereo48k, capacityFrames = 48_000)
        ring.write(ramp(480), 0, 480, pts(1_000))
        // The next buffer claims 9.000 s, an eight second gap. Continuity would predict 1.010 s, so
        // this buffer needs a segment of its own rather than the previous one being moved.
        ring.write(ramp(480), 0, 480, pts(9_000))

        ring.render(CapturingSinkBuffer(stereo48k, 960), 960, deadlineNanos = 0)
        val anchor = assertNotNull(ring.anchor())
        // The 960th frame is the 480th frame of the second segment, so the boundary is on the new
        // timeline and nothing of the old one leaks into it.
        assertEquals(9_000_000L + 480L * 1_000_000L / 48_000L, anchor.pts.micros)
    }

    @Test
    fun `flush discards what is unplayed and drops the anchor`() {
        val ring = KotlinAudioRing(stereo48k, capacityFrames = 4_800)
        ring.write(ramp(1_000), 0, 1_000, pts(3_000))
        ring.render(CapturingSinkBuffer(stereo48k, 100), 100, deadlineNanos = 0)
        assertNotNull(ring.anchor())

        ring.flush()
        assertEquals(0, ring.bufferedFrames)
        assertNull(ring.anchor(), "after a seek the audio clock has no valid reading until new audio plays")

        // And the ring is usable again immediately.
        assertEquals(500, ring.write(ramp(500), 0, 500, pts(20_000)))
        ring.render(CapturingSinkBuffer(stereo48k, 500), 500, deadlineNanos = 0)
        assertEquals(20_000_000L + 500L * 1_000_000L / 48_000L, assertNotNull(ring.anchor()).pts.micros)
    }

    @Test
    fun `a fifth segment waits for one to be consumed and then dates its own samples`() {
        val ring = KotlinAudioRing(stereo48k, capacityFrames = 4_800)
        // Four discontinuous buffers, none of them played, so all four segments are still needed to
        // date what is queued.
        for (segment in 0 until 4) {
            assertEquals(100, ring.write(ramp(100), 0, 100, pts(segment * 10_000L)), "segment $segment")
        }
        assertEquals(
            0,
            ring.write(ramp(100), 0, 100, pts(999_000)),
            "a fifth segment must not displace one that still dates queued audio: the feeder is told to wait",
        )

        // Playing past the first segment finishes it, which frees its slot.
        assertEquals(150, ring.render(CapturingSinkBuffer(stereo48k, 150), 150, deadlineNanos = 0))
        assertEquals(100, ring.write(ramp(100), 0, 100, pts(999_000)))

        // Frames 150 to 499, so the last of them is the hundredth frame of the newest segment.
        assertEquals(350, ring.render(CapturingSinkBuffer(stereo48k, 350), 350, deadlineNanos = 0))
        assertEquals(999_000_000L + 100L * 1_000_000L / 48_000L, assertNotNull(ring.anchor()).pts.micros)
    }

    @Test
    fun `the published anchor is sample exact at 44100 Hz`() {
        for (case in anchorCases) checkAnchors(case, sampleRate = 44_100)
    }

    @Test
    fun `the published anchor is sample exact at 48000 Hz`() {
        for (case in anchorCases) checkAnchors(case, sampleRate = 48_000)
    }

    @Test
    fun `the published anchor is sample exact at 96000 Hz`() {
        for (case in anchorCases) checkAnchors(case, sampleRate = 96_000)
    }

    @Test
    fun `the published anchor is sample exact at 192000 Hz`() {
        for (case in anchorCases) checkAnchors(case, sampleRate = 192_000)
    }

    @Test
    fun `dating a frame delta divides before it multiplies`() {
        // Register item B1-18. Both places this ring dates a frame used to compute
        // `delta * 1_000_000L / sampleRate`, whose product overflows a signed 64 bit intermediate once
        // the delta passes about 9.2e12: the same shape KPKMP-PAST.md section 4 records against KiteCodec's
        // timestamp helpers as defect D9.
        //
        // The table has to go through `framesToMicros` rather than through `write` and `render`,
        // because reaching a delta of 1e13 frames through the ring's own API would mean actually
        // writing ten trillion frames. Each overflowing row asserts twice: the correct answer, and
        // that the naive product really does wrap to something else, so a row whose vector was too
        // small to overflow cannot pass while proving nothing. The naive form is computed in ULong,
        // whose wrap-around is defined, because signed overflow in Kotlin/Native is not something to
        // rely on either.
        val rows = listOf(
            // frames, sampleRate, expected micros, whether the naive product overflows
            Rescale(480L, 48_000, 10_000L, overflows = false),
            Rescale(480L, 44_100, 10_884L, overflows = false),
            Rescale(1L, 192_000, 5L, overflows = false),
            Rescale(48_000L, 48_000, 1_000_000L, overflows = false),
            Rescale(0L, 48_000, 0L, overflows = false),
            Rescale(10_000_000_000_000L, 48_000, 208_333_333_333_333L, overflows = true),
            Rescale(10_000_000_000_000L, 44_100, 226_757_369_614_512L, overflows = true),
            Rescale(10_000_000_000_000L, 96_000, 104_166_666_666_666L, overflows = true),
            Rescale(10_000_000_000_000L, 192_000, 52_083_333_333_333L, overflows = true),
            Rescale(9_223_372_036_855L, 48_000, 192_153_584_101_145L, overflows = true),
            Rescale(100_000_000_000_000L, 48_000, 2_083_333_333_333_333L, overflows = true),
            Rescale(400_000_000_000_000_000L, 48_000, 8_333_333_333_333_333_333L, overflows = true),
            Rescale(-480L, 48_000, -10_000L, overflows = false),
            Rescale(-10_000_000_000_000L, 44_100, -226_757_369_614_512L, overflows = true),
            // AudioFormat.durationOf answers zero for a zero rate, so this does too.
            Rescale(480L, 0, 0L, overflows = false),
        )

        for (row in rows) {
            val where = "${row.frames} frames at ${row.sampleRate} Hz"
            assertEquals(row.expectedUs, framesToMicros(row.frames, row.sampleRate), where)
            if (row.overflows) {
                val naive = (row.frames.toULong() * 1_000_000UL).toLong() / row.sampleRate
                assertTrue(
                    naive != row.expectedUs,
                    "$where claims to overflow the naive product, but the naive form answered " +
                        "$naive, which is the correct answer: the vector proves nothing",
                )
            }
        }

        // A sweep, against an independent derivation that is safe because it stays small. A different
        // derivation is a check; restating the implementation would not be.
        for (sampleRate in listOf(44_100, 48_000, 96_000, 192_000)) {
            var frames = 0L
            while (frames < 1_000_000L) {
                assertEquals(
                    frames * 1_000_000L / sampleRate,
                    framesToMicros(frames, sampleRate),
                    "$frames frames at $sampleRate Hz",
                )
                frames += 4_999L
            }
        }
    }

    private class Rescale(
        val frames: Long,
        val sampleRate: Int,
        val expectedUs: Long,
        val overflows: Boolean,
    )

    /**
     * Every callback shape the device can produce, checked against the boundary convention.
     *
     * Written as a table because the shapes are what matter and they are all one paragraph each: a
     * callback that takes everything, callbacks that take part of what is queued, a callback that
     * runs into silence, a callback that stops exactly where a segment ends, and a callback that
     * crosses a discontinuity in the middle.
     */
    private val anchorCases = listOf(
        RingCase(
            "one callback takes everything",
            listOf(
                Feed(frames = 480, startsAtMediaFrame = 0),
                Pull(frames = 480, expectRealFrames = 480, segmentStartsAt = 0, framesIntoSegment = 480),
            ),
        ),
        RingCase(
            "partial callbacks walk through one segment",
            listOf(
                Feed(frames = 1_000, startsAtMediaFrame = 4_000),
                Pull(frames = 300, expectRealFrames = 300, segmentStartsAt = 4_000, framesIntoSegment = 300),
                Pull(frames = 300, expectRealFrames = 300, segmentStartsAt = 4_000, framesIntoSegment = 600),
                Pull(frames = 1, expectRealFrames = 1, segmentStartsAt = 4_000, framesIntoSegment = 601),
                Pull(frames = 399, expectRealFrames = 399, segmentStartsAt = 4_000, framesIntoSegment = 1_000),
            ),
        ),
        RingCase(
            "a callback runs into a silence tail, and the ring refills behind it",
            listOf(
                Feed(frames = 200, startsAtMediaFrame = 0),
                Pull(frames = 512, expectRealFrames = 200, segmentStartsAt = 0, framesIntoSegment = 200),
                // The frame stream carries on where it stopped, so this continues the same segment
                // even though the device heard silence in between.
                Feed(frames = 200, startsAtMediaFrame = 200),
                Pull(frames = 512, expectRealFrames = 200, segmentStartsAt = 0, framesIntoSegment = 400),
            ),
        ),
        RingCase(
            "buffers with no timestamp continue the segment",
            listOf(
                Feed(frames = 240, startsAtMediaFrame = 1_000),
                Feed(frames = 240, startsAtMediaFrame = null),
                Feed(frames = 240, startsAtMediaFrame = null),
                Pull(frames = 720, expectRealFrames = 720, segmentStartsAt = 1_000, framesIntoSegment = 720),
            ),
        ),
        RingCase(
            "a callback ends exactly on a segment boundary",
            listOf(
                Feed(frames = 480, startsAtMediaFrame = 0),
                // A landing after a seek: this buffer is nowhere near where the last one ended.
                Feed(frames = 480, startsAtMediaFrame = 96_000),
                // The boundary after the last frame of the first segment belongs to that segment, not
                // to the one starting at the same frame index.
                Pull(frames = 480, expectRealFrames = 480, segmentStartsAt = 0, framesIntoSegment = 480),
                Pull(frames = 480, expectRealFrames = 480, segmentStartsAt = 96_000, framesIntoSegment = 480),
            ),
        ),
        RingCase(
            "a callback crosses a discontinuity",
            listOf(
                Feed(frames = 300, startsAtMediaFrame = 0),
                Feed(frames = 300, startsAtMediaFrame = 96_000),
                Pull(frames = 600, expectRealFrames = 600, segmentStartsAt = 96_000, framesIntoSegment = 300),
            ),
        ),
        RingCase(
            "a callback stops one frame short of a boundary, and the next steps over it",
            listOf(
                Feed(frames = 256, startsAtMediaFrame = 0),
                Feed(frames = 256, startsAtMediaFrame = 96_000),
                Pull(frames = 255, expectRealFrames = 255, segmentStartsAt = 0, framesIntoSegment = 255),
                Pull(frames = 2, expectRealFrames = 2, segmentStartsAt = 96_000, framesIntoSegment = 1),
                Pull(frames = 255, expectRealFrames = 255, segmentStartsAt = 96_000, framesIntoSegment = 256),
            ),
        ),
        RingCase(
            "four segments in flight, each dating its own samples",
            listOf(
                Feed(frames = 100, startsAtMediaFrame = 0),
                Feed(frames = 100, startsAtMediaFrame = 96_000),
                Feed(frames = 100, startsAtMediaFrame = 192_000),
                Feed(frames = 100, startsAtMediaFrame = 288_000),
                Pull(frames = 100, expectRealFrames = 100, segmentStartsAt = 0, framesIntoSegment = 100),
                Pull(frames = 100, expectRealFrames = 100, segmentStartsAt = 96_000, framesIntoSegment = 100),
                Pull(frames = 100, expectRealFrames = 100, segmentStartsAt = 192_000, framesIntoSegment = 100),
                Pull(frames = 100, expectRealFrames = 100, segmentStartsAt = 288_000, framesIntoSegment = 100),
            ),
        ),
    )

    private fun checkAnchors(case: RingCase, sampleRate: Int) {
        val where = "${case.name}, at $sampleRate Hz"
        val format = AudioFormat(sampleRate = sampleRate, channels = 2, sampleFormat = SampleFormat.F32)
        val ring = KotlinAudioRing(format, capacityFrames = 2_048)
        val nanosPerFrame = 1_000_000_000.0 / sampleRate
        var writtenFrames = 0
        var pullIndex = 0

        for (step in case.steps) {
            when (step) {
                is Feed -> {
                    val accepted = ring.write(
                        source = ramp(step.frames, from = writtenFrames),
                        sourceOffset = 0,
                        frames = step.frames,
                        pts = step.startsAtMediaFrame?.let { Pts(mediaUs(it, sampleRate)) },
                    )
                    assertEquals(step.frames, accepted, "$where: the whole write must be taken")
                    writtenFrames += accepted
                }

                is Pull -> {
                    val deadline = FIRST_DEADLINE_NANOS + pullIndex * PULL_SPACING_NANOS
                    val out = CapturingSinkBuffer(format, step.frames)
                    val real = ring.render(out, step.frames, deadlineNanos = deadline)
                    pullIndex++

                    assertEquals(step.expectRealFrames, real, "$where: frames of real audio in callback $pullIndex")
                    val anchor = assertNotNull(ring.anchor(), "$where: callback $pullIndex published no anchor")
                    assertEquals(
                        mediaUs(step.segmentStartsAt, sampleRate) +
                            step.framesIntoSegment * 1_000_000L / sampleRate,
                        anchor.pts.micros,
                        "$where: the boundary timestamp after callback $pullIndex",
                    )
                    assertEquals(
                        deadline - ((step.frames - real) * nanosPerFrame).toLong(),
                        anchor.audibleAtNanos,
                        "$where: the boundary instant after callback $pullIndex, less its silence tail",
                    )
                }
            }
        }
    }

    /** A media position given in frames, as the timestamp a decoder would carry for it. */
    private fun mediaUs(frames: Long, sampleRate: Int): Long = frames * 1_000_000L / sampleRate

    @Test
    fun `a long session of small writes and reads stays consistent`() {
        // Exercises the wrap arithmetic repeatedly, which is where an off-by-one hides.
        val ring = KotlinAudioRing(stereo48k, capacityFrames = 333)
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

    private companion object {
        /** Any instant will do, as long as it is far enough from zero to notice a sign mistake. */
        const val FIRST_DEADLINE_NANOS = 1_000_000_000L

        /** Successive callbacks are given different deadlines, so a stale anchor cannot pass. */
        const val PULL_SPACING_NANOS = 10_000_000L
    }
}
