// The oracle calls one cinterop function directly, `kprt_frames_to_micros`, to compare the two
// implementations of the B1-18 rescale across the seam.
@file:OptIn(ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.AudioAnchor
import io.github.yuroyami.kiteplayer.internal.KotlinAudioRing
import io.github.yuroyami.kiteplayer.internal.NativeAudioRing
import io.github.yuroyami.kiteplayer.internal.framesToMicros
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.AudioSinkBuffer
import io.github.yuroyami.kiteplayer.spi.SampleFormat
import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The differential oracle: one input sequence, two implementations, everything compared exactly.
 *
 * ### Why this test is the load-bearing one of sub-phase B1.7
 *
 * There are two implementations of the audio ring contract and there always will be.
 * [KotlinAudioRing] is portable and is what js, wasmJs, jvm and Android use. [NativeAudioRing] wraps
 * the C ring in `kiteplayer-rt`, which exists because on macOS the device's real-time callback enters
 * managed Kotlin on its first instruction and becomes a mutator the collector has to stop at a
 * safepoint. Neither can be deleted: `commonMain` targets js and wasmJs, which
 * can never contain C, and the Kotlin ring is the only oracle the C ring can be checked against. That
 * is register item B1-20, and the consequence is that two implementations of one contract exist
 * permanently. This file is the only thing that stops them drifting apart.
 *
 * ### What "compared exactly" means here, clause by clause
 *
 * For every step of every scenario, at four sample rates:
 *
 *  - `write` returns the same number of accepted frames, including the two zero cases (the ring is
 *    full, and a timestamp needs a fifth segment while four still date unplayed audio).
 *  - `render` returns the same number of real frames, and every float of the device buffer matches
 *    BIT for bit, silence tail included. Compared through `toRawBits` and not with `==`, so a plus
 *    zero could not pass for a minus zero and a NaN could not pass for a NaN by accident.
 *  - The published anchor matches exactly: the same validity, the same microsecond, the same
 *    nanosecond instant. Not "within a tolerance": these are two integer computations of the same
 *    quantity and any difference is a defect.
 *  - The underrun counter, the buffered and free frame counts and both frame totals match.
 *
 * ### Two things this test deliberately also asserts about the C ring alone
 *
 * `segment_giveups` and `anchor_giveups` must both stay zero. The C ring is allowed to answer a torn
 * seqlock read with a stale reading rather than by spinning, which is how it fixes register item
 * B1-16, and that freedom is exercised on purpose by `kiteplayer-rt/native/tests/test_ring_bounded.c`
 * with a second thread. Here everything is single threaded, so a non-zero count would mean the C ring
 * agreed with the Kotlin ring by way of a degraded path, which is agreement that proves nothing.
 *
 * ### What this test cannot do
 *
 * It cannot compare the concurrent behaviour, because the Kotlin ring's own render path is a Kotlin
 * function and there is no way to call it from a real-time thread that is not a Kotlin mutator, which
 * is the entire problem. Concurrency is covered by the C suites under TSan and by
 * `test_ring_bounded.c`; this file covers the contract.
 */
class AudioRingDifferentialTest {

    // ---- The scripted steps ----

    private sealed interface Step

    /**
     * The feeder hands over [frames] frames.
     *
     * @param mediaFrame where the decoder said the first of them sits on the media timeline, counted
     *        in FRAMES at the rate under test, or null for a buffer with no timestamp. Counting in
     *        frames rather than microseconds is what keeps one script meaningful at four rates: a gap
     *        of a hundred thousand frames is a real discontinuity at every one of them.
     * @param sourceFrameOffset where in the handed array the frames start, in frames. Non-zero in at
     *        least one scenario, because both implementations take that offset in FLOATS and a
     *        disagreement about the unit would be invisible at zero.
     */
    private class Feed(
        val frames: Int,
        val mediaFrame: Long?,
        val sourceFrameOffset: Int = 0,
    ) : Step

    /**
     * The feeder hands over [frames] frames stamped at an absolute microsecond, not at a frame.
     *
     * For the two rows at the ends of the `Long` range, where the point of the case is a value near
     * `Long.MAX_VALUE` and expressing it as a frame count at four different rates would move it.
     */
    private class FeedAtMicros(val frames: Int, val ptsUs: Long) : Step

    /**
     * The feeder hands over [frames] frames stamped exactly [driftUs] microseconds away from what
     * continuity predicts.
     *
     * This is the boundary case the independent verification of B1.8 found missing, and the finding is
     * worth restating because it is the whole reason this step type exists. The discontinuity tolerance
     * is a strict `<`, so a drift of 999 microseconds continues the segment and 1000 opens a new one.
     * Changing that one `<` into a `<=` in the C ring alone passed all seven of this file's original
     * tests, at all four rates, at one, six and eight channels, and through a four thousand iteration
     * pseudo-random session, because random sessions do not find boundaries. It also passed the whole
     * rest of the B1.7 and B1.8 gate, while misdating the audio clock by up to a millisecond at every
     * discontinuity whose drift landed on the boundary. Only an explicit row at 999, 1000 and 1001, on
     * both signs, separates the two implementations.
     *
     * The prediction is `framesToMicros(streamFrame, rate)`, which is exact for these scenarios because
     * each one opens its first segment at frame zero with a timestamp of zero.
     */
    private class FeedAtDrift(val frames: Int, val driftUs: Long) : Step

    /** The device asks for [frames] frames at [deadlineOffsetNanos] after the scenario's first pull. */
    private class Pull(val frames: Int) : Step

    /** The seek path: both rings are flushed. */
    private object Flush : Step

    /** The feeder says no more audio is coming, so trailing silence stops being an underrun. */
    private object MarkEnding : Step

    private class Scenario(val name: String, val capacityFrames: Int, val steps: List<Step>)

    private val rates = listOf(44_100, 48_000, 96_000, 192_000)

    // ---- The comparison ----

    /** Stands in for a device buffer on the Kotlin side, and records exactly what was written where. */
    private class CapturingSinkBuffer(override val format: AudioFormat, frames: Int) : AudioSinkBuffer {
        // Prefilled with a value that is not silence, so a case expecting zeroes asserts that the
        // ring wrote them rather than that nobody wrote anything.
        val samples = FloatArray(frames * format.channels) { POISON }

        override fun writeInterleaved(
            source: FloatArray,
            sourceOffset: Int,
            destinationFrameOffset: Int,
            frames: Int,
        ) {
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
    }

    private fun runScenario(scenario: Scenario, sampleRate: Int, channels: Int = 2) {
        val where = "${scenario.name}, at $sampleRate Hz, $channels channels"
        val format = AudioFormat(sampleRate = sampleRate, channels = channels, sampleFormat = SampleFormat.F32)
        val kotlinRing = KotlinAudioRing(format, capacityFrames = scenario.capacityFrames)
        val nativeRing = NativeAudioRing(format, capacityFrames = scenario.capacityFrames)
        try {
            var streamFrame = 0L
            var pullIndex = 0
            scenario.steps.forEachIndexed { index, step ->
                val at = "$where, step $index"
                when (step) {
                    is Feed -> {
                        val source = ramp(
                            frames = step.frames,
                            from = streamFrame,
                            channels = channels,
                            frameOffset = step.sourceFrameOffset,
                        )
                        val pts = step.mediaFrame?.let { Pts(framesToMicros(it, sampleRate)) }
                        val sourceOffset = step.sourceFrameOffset * channels
                        val kotlinAccepted = kotlinRing.write(source, sourceOffset, step.frames, pts)
                        val nativeAccepted = nativeRing.write(source, sourceOffset, step.frames, pts)
                        assertEquals(kotlinAccepted, nativeAccepted, "$at: accepted frames")
                        streamFrame += kotlinAccepted
                    }

                    is FeedAtMicros -> {
                        val source = ramp(frames = step.frames, from = streamFrame, channels = channels, frameOffset = 0)
                        val pts = Pts(step.ptsUs)
                        val kotlinAccepted = kotlinRing.write(source, 0, step.frames, pts)
                        val nativeAccepted = nativeRing.write(source, 0, step.frames, pts)
                        assertEquals(kotlinAccepted, nativeAccepted, "$at: accepted frames")
                        streamFrame += kotlinAccepted
                    }

                    is FeedAtDrift -> {
                        val source = ramp(frames = step.frames, from = streamFrame, channels = channels, frameOffset = 0)
                        val pts = Pts(framesToMicros(streamFrame, sampleRate) + step.driftUs)
                        val kotlinAccepted = kotlinRing.write(source, 0, step.frames, pts)
                        val nativeAccepted = nativeRing.write(source, 0, step.frames, pts)
                        assertEquals(kotlinAccepted, nativeAccepted, "$at: accepted frames")
                        streamFrame += kotlinAccepted
                    }

                    is Pull -> {
                        val deadline = FIRST_DEADLINE_NANOS + pullIndex * PULL_SPACING_NANOS
                        pullIndex++
                        val kotlinOut = CapturingSinkBuffer(format, step.frames)
                        val nativeOut = FloatArray(step.frames * channels) { POISON }

                        val kotlinReal = kotlinRing.render(kotlinOut, step.frames, deadline)
                        val nativeReal = nativeRing.render(nativeOut, step.frames, deadline)

                        assertEquals(kotlinReal, nativeReal, "$at: frames of real audio")
                        assertSameSamples(kotlinOut.samples, nativeOut, "$at")
                    }

                    Flush -> {
                        kotlinRing.flush()
                        nativeRing.flush()
                    }

                    MarkEnding -> {
                        kotlinRing.markEnding()
                        nativeRing.markEnding()
                    }
                }
                assertSameState(kotlinRing, nativeRing, at)
            }

            // The C ring's freedom to answer a torn read from a cache is never used here, so it
            // cannot be part of why the two agreed.
            assertEquals(0L, nativeRing.segmentGiveups, "$where: the C ring took a segment give-up")
            assertEquals(0L, nativeRing.anchorGiveups, "$where: the C ring took an anchor give-up")
        } finally {
            nativeRing.close()
        }
    }

    private fun assertSameState(kotlinRing: KotlinAudioRing, nativeRing: NativeAudioRing, at: String) {
        assertEquals(kotlinRing.underruns, nativeRing.underruns, "$at: underrun count")
        assertEquals(kotlinRing.bufferedFrames, nativeRing.bufferedFrames, "$at: buffered frames")
        assertEquals(kotlinRing.freeFrames, nativeRing.freeFrames, "$at: free frames")
        assertEquals(kotlinRing.bufferedUs, nativeRing.bufferedUs, "$at: buffered microseconds")
        assertSameAnchor(kotlinRing.anchor(), nativeRing.anchor(), at)
    }

    private fun assertSameAnchor(expected: AudioAnchor?, actual: AudioAnchor?, at: String) {
        if (expected == null) {
            assertNull(actual, "$at: the Kotlin ring published no anchor but the C ring published $actual")
            return
        }
        val published = actual ?: throw AssertionError("$at: the C ring published no anchor, expected $expected")
        assertEquals(expected.pts.micros, published.pts.micros, "$at: anchor timestamp in microseconds")
        assertEquals(expected.audibleAtNanos, published.audibleAtNanos, "$at: anchor instant in nanoseconds")
    }

    /**
     * Bit for bit, and through `toRawBits` on purpose.
     *
     * `==` on floats would let a minus zero pass for a plus zero and would make two NaNs unequal. The
     * ring moves bits and fills bits, so the bits are what has to match, and `writeSilence`'s `0f`
     * against `memset`'s zero bytes is exactly the comparison where the distinction could hide.
     */
    private fun assertSameSamples(expected: FloatArray, actual: FloatArray, at: String) {
        assertEquals(expected.size, actual.size, "$at: device buffer size")
        for (i in expected.indices) {
            val left = expected[i].toRawBits()
            val right = actual[i].toRawBits()
            if (left != right) {
                throw AssertionError(
                    "$at: float $i differs: Kotlin ring wrote ${expected[i]} (raw $left), " +
                        "C ring wrote ${actual[i]} (raw $right)",
                )
            }
        }
    }

    /**
     * A ramp, so every frame is identifiable by its value, laid out with [frameOffset] frames of a
     * distinct filler value in front of it.
     *
     * The filler is not silence and not part of the ramp: if either implementation read the source
     * offset in frames rather than in floats, or ignored it, the filler would land in the ring and the
     * comparison against the other implementation would fail on a value nothing else produces.
     */
    private fun ramp(frames: Int, from: Long, channels: Int, frameOffset: Int): FloatArray {
        val total = (frames + frameOffset) * channels
        return FloatArray(total) { i ->
            val frame = i / channels
            if (frame < frameOffset) FILLER else (from + (frame - frameOffset)).toFloat()
        }
    }

    // ---- The scenarios ----

    private val scenarios = listOf(
        Scenario(
            "one callback takes everything",
            capacityFrames = 2_048,
            steps = listOf(Feed(480, 0), Pull(480)),
        ),
        Scenario(
            "partial callbacks walk through one segment",
            capacityFrames = 2_048,
            steps = listOf(Feed(1_000, 4_000), Pull(300), Pull(300), Pull(1), Pull(399)),
        ),
        Scenario(
            "a callback runs into a silence tail and the ring refills behind it",
            capacityFrames = 2_048,
            steps = listOf(Feed(200, 0), Pull(512), Feed(200, 200), Pull(512)),
        ),
        Scenario(
            "buffers with no timestamp continue the segment",
            capacityFrames = 2_048,
            steps = listOf(Feed(240, 1_000), Feed(240, null), Feed(240, null), Pull(720)),
        ),
        Scenario(
            "a callback ends exactly on a segment boundary",
            capacityFrames = 2_048,
            steps = listOf(Feed(480, 0), Feed(480, 96_000), Pull(480), Pull(480)),
        ),
        Scenario(
            "a callback crosses a discontinuity",
            capacityFrames = 2_048,
            steps = listOf(Feed(300, 0), Feed(300, 96_000), Pull(600)),
        ),
        Scenario(
            "a callback stops one frame short of a boundary and the next steps over it",
            capacityFrames = 2_048,
            steps = listOf(Feed(256, 0), Feed(256, 96_000), Pull(255), Pull(2), Pull(255)),
        ),
        Scenario(
            "four segments in flight, each dating its own samples",
            capacityFrames = 2_048,
            steps = listOf(
                Feed(100, 0), Feed(100, 96_000), Feed(100, 192_000), Feed(100, 288_000),
                Pull(100), Pull(100), Pull(100), Pull(100),
            ),
        ),
        Scenario(
            "a fifth segment is refused by both rings on the same write",
            capacityFrames = 4_800,
            steps = listOf(
                Feed(100, 0), Feed(100, 96_000), Feed(100, 192_000), Feed(100, 288_000),
                // Both must return zero here: four segments still date audio nothing has played.
                Feed(100, 960_000),
                Pull(150),
                // And both must accept it now that the first segment is finished.
                Feed(100, 960_000),
                Pull(350),
            ),
        ),
        Scenario(
            "a full ring accepts part of a write and then nothing",
            capacityFrames = 100,
            steps = listOf(Feed(500, 0), Feed(10, null), Pull(30), Feed(100, null), Pull(100)),
        ),
        Scenario(
            "a write and a read that both wrap the ring",
            capacityFrames = 100,
            steps = listOf(Feed(80, 0), Pull(80), Feed(40, null), Pull(40)),
        ),
        Scenario(
            "a seek: flush, then a landing somewhere else entirely",
            capacityFrames = 4_800,
            steps = listOf(
                Feed(1_000, 0), Pull(100), Flush, Pull(64),
                Feed(500, 960_000), Pull(500),
            ),
        ),
        Scenario(
            "untimestamped audio after a flush is dated by neither ring",
            capacityFrames = 4_800,
            steps = listOf(Feed(500, 0), Pull(500), Flush, Feed(500, null), Pull(500)),
        ),
        Scenario(
            "the end of a file: mark ending, drain, then silence forever",
            capacityFrames = 4_800,
            steps = listOf(
                Feed(1_000, 0), MarkEnding,
                Pull(256), Pull(256), Pull(256), Pull(256), Pull(256), Pull(256),
            ),
        ),
        Scenario(
            "starvation before the end of the file counts on both sides",
            capacityFrames = 4_800,
            steps = listOf(
                Feed(100, 0), Pull(256), Pull(256), Feed(100, 100), Pull(256),
                MarkEnding, Pull(256), Pull(256),
            ),
        ),
        Scenario(
            "a flush clears the ending flag on both sides",
            capacityFrames = 4_800,
            steps = listOf(
                Feed(100, 0), MarkEnding, Pull(256), Flush, Feed(100, 0), Pull(256),
            ),
        ),
        Scenario(
            "the source offset is read in floats by both rings",
            capacityFrames = 2_048,
            steps = listOf(
                Feed(480, 0, sourceFrameOffset = 37),
                Feed(480, 480, sourceFrameOffset = 1),
                Pull(960),
            ),
        ),
        // The tolerance boundary, one row per side and per side of the boundary. See [FeedAtDrift] for
        // what was measured to slip through without these.
        Scenario(
            "a drift of 999 microseconds continues the segment",
            capacityFrames = 4_800,
            steps = listOf(Feed(100, 0), FeedAtDrift(100, 999), Pull(200)),
        ),
        Scenario(
            "a drift of exactly 1000 microseconds opens a segment",
            capacityFrames = 4_800,
            steps = listOf(Feed(100, 0), FeedAtDrift(100, 1_000), Pull(200)),
        ),
        Scenario(
            "a drift of 1001 microseconds opens a segment",
            capacityFrames = 4_800,
            steps = listOf(Feed(100, 0), FeedAtDrift(100, 1_001), Pull(200)),
        ),
        Scenario(
            "a drift of minus 999 microseconds continues the segment",
            capacityFrames = 4_800,
            steps = listOf(Feed(100, 0), FeedAtDrift(100, -999), Pull(200)),
        ),
        Scenario(
            "a drift of exactly minus 1000 microseconds opens a segment",
            capacityFrames = 4_800,
            steps = listOf(Feed(100, 0), FeedAtDrift(100, -1_000), Pull(200)),
        ),
        Scenario(
            "a drift of minus 1001 microseconds opens a segment",
            capacityFrames = 4_800,
            steps = listOf(Feed(100, 0), FeedAtDrift(100, -1_001), Pull(200)),
        ),
        // A zero frame write. Measured divergence, now closed on the Kotlin side: this ring used to
        // record the timestamp before it looked at the frame count, so four of these spent all four
        // segment slots and the fifth real write was refused, while the C ring refused a non-positive
        // frame count before it looked at anything. Unreachable from `AudioPlayback.submit`, and a real
        // difference in a contract this file claims to pin.
        Scenario(
            "four zero frame writes with four timestamps spend no segment on either side",
            capacityFrames = 4_800,
            steps = listOf(
                Feed(100, 0),
                Feed(0, 96_000), Feed(0, 192_000), Feed(0, 288_000), Feed(0, 384_000),
                Feed(100, 480_000),
                Pull(200),
            ),
        ),
        // The ends of the `Long` range. Both rows drive an arithmetic overflow that used to be
        // undefined behaviour in C, named by UBSan during the independent verification of B1.8 as
        // `-9223372036854774474 - 9223372036854775807 cannot be represented in type 'int64_t'`. Both
        // rings now treat an unrepresentable distance as a discontinuity and saturate an
        // unrepresentable anchor, and these rows are what makes that a checked agreement rather than
        // two matching wraps. Not reachable from real media: it needs a timestamp about 292,000 years
        // from the origin.
        Scenario(
            "a timestamp at the bottom of the range against one at the top",
            capacityFrames = 4_800,
            steps = listOf(
                FeedAtMicros(100, Long.MIN_VALUE + 1_000),
                FeedAtMicros(100, Long.MAX_VALUE),
                Pull(100),
            ),
        ),
        Scenario(
            "a timestamp near the top of the range, where continuity itself would overflow",
            capacityFrames = 4_800,
            steps = listOf(
                FeedAtMicros(100, Long.MAX_VALUE - 1_000),
                FeedAtMicros(100, 0),
                Pull(100),
                Pull(100),
            ),
        ),
        Scenario(
            "a one frame callback, repeatedly, across a discontinuity",
            capacityFrames = 2_048,
            steps = buildList {
                add(Feed(8, 0))
                add(Feed(8, 96_000))
                repeat(20) { add(Pull(1)) }
            },
        ),
    )

    @Test
    fun `both rings agree step for step at 44100 Hz`() {
        for (scenario in scenarios) runScenario(scenario, sampleRate = 44_100)
    }

    @Test
    fun `both rings agree step for step at 48000 Hz`() {
        for (scenario in scenarios) runScenario(scenario, sampleRate = 48_000)
    }

    @Test
    fun `both rings agree step for step at 96000 Hz`() {
        for (scenario in scenarios) runScenario(scenario, sampleRate = 96_000)
    }

    @Test
    fun `both rings agree step for step at 192000 Hz`() {
        for (scenario in scenarios) runScenario(scenario, sampleRate = 192_000)
    }

    @Test
    fun `both rings agree at one six and eight channels`() {
        // The interleave is a frame count multiplied by a channel count, in the wrap arithmetic, in
        // the silence fill and in the source offset. Two channels would not catch a mistake in that
        // multiplication.
        for (channels in listOf(1, 6, 8)) {
            for (scenario in scenarios) {
                runScenario(scenario, sampleRate = 48_000, channels = channels)
            }
        }
    }

    @Test
    fun `both implementations of framesToMicros saturate identically at the ends`() {
        // Interlude item I-05. `whole * 1000000` overflowed at the top of the range on both sides
        // of the contract: DEFINED wrapping in Kotlin, UNDEFINED behaviour in C (measured under
        // UBSan through kprt_frames_to_micros(INT64_MAX, 1)), so up here the oracle used to
        // compare two different kinds of wrong. Both saturate now, and this row pins them to the
        // same answer at both ends plus one large mid-range value that must stay exact.
        for ((frames, expected) in listOf(
            Long.MAX_VALUE to Long.MAX_VALUE,
            Long.MIN_VALUE to Long.MIN_VALUE,
        )) {
            assertEquals(expected, framesToMicros(frames, 1), "Kotlin at $frames")
            assertEquals(
                expected,
                io.github.yuroyami.kiteplayer.rt.cinterop.kprt_frames_to_micros(frames, 1),
                "C at $frames",
            )
        }
        val large = 10_000_000_000L
        assertEquals(
            framesToMicros(large, 48_000),
            io.github.yuroyami.kiteplayer.rt.cinterop.kprt_frames_to_micros(large, 48_000),
            "the two implementations disagree inside the representable range",
        )
        assertEquals(208_333_333_333L, framesToMicros(large, 48_000), "exactness lost inside the range")
    }

    @Test
    fun `both rings agree across a long pseudo-random session`() {
        // The scenarios above are the shapes somebody thought of. This is the part that finds the
        // shape nobody thought of: a deterministic sequence long enough to wrap the ring hundreds of
        // times, with discontinuities, flushes and starvation thrown in at fixed positions so a
        // failure is reproducible from the seed alone.
        for (sampleRate in rates) {
            val format = AudioFormat(sampleRate = sampleRate, channels = 2, sampleFormat = SampleFormat.F32)
            val capacity = 777
            val kotlinRing = KotlinAudioRing(format, capacityFrames = capacity)
            val nativeRing = NativeAudioRing(format, capacityFrames = capacity)
            try {
                var seed = 0x1f2e3d4cu
                fun next(): Int {
                    seed = seed * 1664525u + 1013904223u
                    return (seed shr 16).toInt() and 0x7fff
                }

                var streamFrame = 0L
                var mediaFrame = 0L
                var pull = 0
                var totalReal = 0L
                var flushes = 0
                var discontinuities = 0

                repeat(4_000) { iteration ->
                    val at = "the pseudo-random session at $sampleRate Hz, iteration $iteration"

                    // Feed a chunk, dating it every few buffers and jumping the timeline sometimes.
                    val wanted = next() % 200 + 1
                    val dates = (next() % 3) != 0
                    val jumps = dates && (next() % 40) == 0
                    if (jumps) {
                        mediaFrame += 500_000L
                        discontinuities++
                    }
                    val source = ramp(wanted, streamFrame, channels = 2, frameOffset = 0)
                    val pts = if (dates) Pts(framesToMicros(mediaFrame, sampleRate)) else null
                    val kotlinAccepted = kotlinRing.write(source, 0, wanted, pts)
                    val nativeAccepted = nativeRing.write(source, 0, wanted, pts)
                    assertEquals(kotlinAccepted, nativeAccepted, "$at: accepted frames")
                    streamFrame += kotlinAccepted
                    mediaFrame += kotlinAccepted

                    // Render a chunk, sometimes asking for more than is there.
                    val asked = next() % 300 + 1
                    val deadline = FIRST_DEADLINE_NANOS + pull * PULL_SPACING_NANOS
                    pull++
                    val kotlinOut = CapturingSinkBuffer(format, asked)
                    val nativeOut = FloatArray(asked * 2) { POISON }
                    val kotlinReal = kotlinRing.render(kotlinOut, asked, deadline)
                    val nativeReal = nativeRing.render(nativeOut, asked, deadline)
                    assertEquals(kotlinReal, nativeReal, "$at: frames of real audio")
                    assertSameSamples(kotlinOut.samples, nativeOut, at)
                    totalReal += kotlinReal

                    assertSameState(kotlinRing, nativeRing, at)

                    if ((next() % 500) == 0) {
                        kotlinRing.flush()
                        nativeRing.flush()
                        flushes++
                        // A flush abandons the timeline, so the next timestamp starts a new segment
                        // and the frame stream continues where it stopped.
                        mediaFrame += 1_000_000L
                        assertSameState(kotlinRing, nativeRing, "$at, after a flush")
                    }
                }

                assertTrue(
                    totalReal > 100_000L,
                    "the session at $sampleRate Hz moved only $totalReal frames, which is too few to " +
                        "have wrapped a $capacity frame ring enough times to mean anything",
                )
                assertTrue(discontinuities > 0, "the session at $sampleRate Hz had no discontinuity")
                assertTrue(flushes > 0, "the session at $sampleRate Hz had no flush")
                assertEquals(0L, nativeRing.segmentGiveups, "the C ring took a segment give-up")
                assertEquals(0L, nativeRing.anchorGiveups, "the C ring took an anchor give-up")
            } finally {
                nativeRing.close()
            }
        }
    }

    @Test
    fun `the rescale agrees between the two implementations at the overflow vectors`() {
        // Register item B1-18, checked across the seam. Both rings date frames through the same split
        // rescale, one written in Kotlin and one in C, and the whole reason both were corrected in the
        // same sub-phase is so this comparison is between two correct answers rather than two matching
        // wrong ones. A frame delta this large cannot be reached through either ring's own API, so it
        // is checked at the function.
        val vectors = listOf(
            480L to 48_000,
            480L to 44_100,
            1L to 192_000,
            10_000_000_000_000L to 48_000,
            10_000_000_000_000L to 44_100,
            10_000_000_000_000L to 96_000,
            10_000_000_000_000L to 192_000,
            9_223_372_036_855L to 48_000,
            400_000_000_000_000_000L to 48_000,
            -10_000_000_000_000L to 44_100,
        )
        for ((frames, sampleRate) in vectors) {
            assertEquals(
                framesToMicros(frames, sampleRate),
                io.github.yuroyami.kiteplayer.rt.cinterop.kprt_frames_to_micros(frames, sampleRate),
                "$frames frames at $sampleRate Hz",
            )
        }
    }

    private companion object {
        /** Any instant will do, as long as it is far enough from zero to notice a sign mistake. */
        const val FIRST_DEADLINE_NANOS = 1_000_000_000L

        /** Successive callbacks get different deadlines, so a stale anchor cannot pass. */
        const val PULL_SPACING_NANOS = 10_000_000L

        /** Not silence and not a ramp value, so an untouched device buffer float is recognisable. */
        const val POISON = -12_345.5f

        /** Not silence, not a ramp value and not the poison, so a misread source offset is visible. */
        const val FILLER = 91_919.5f
    }
}
