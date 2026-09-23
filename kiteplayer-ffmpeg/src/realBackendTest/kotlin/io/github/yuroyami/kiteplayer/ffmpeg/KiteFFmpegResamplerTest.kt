package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.PlaybackWarning
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [KiteFFmpegResampler] held to the frequency checks that the engine's own sinc passes, driven
 * through `AudioPlayback`, which runs the engine's audio pipeline.
 *
 * Every conversion also asserts that no `ResamplerUnavailable` warning came out. Without that, a
 * factory that refused would pass: the engine falls back to its own sinc, which passes the same
 * checks.
 */
class KiteFFmpegResamplerTest {

    private fun convert(
        input: FloatArray,
        fromRate: Int,
        toRate: Int,
        channels: Int = 1,
        chunkFrames: Int = 1_024,
    ): FloatArray = runBlocking {
        val converted = convertThroughPlayback(input, channels, fromRate, toRate, KiteFFmpegResampler(), chunkFrames)
        val refusals = converted.warnings.filterIsInstance<PlaybackWarning.ResamplerUnavailable>()
        assertTrue(refusals.isEmpty(), "libswresample did not run, the engine fell back: $refusals")
        converted.samples
    }

    @Test
    fun theRatioHoldsBufferAfterBuffer() {
        // Twenty buffers of 10 ms at 44.1 kHz, and the flush at the end: 200 ms in, 200 ms out.
        val produced = convert(tone(8_820, 44_100, 440.0), 44_100, 48_000, chunkFrames = 441)
        assertTrue(abs(produced.size - 9_600) <= 32, "200 ms at 44.1 kHz is 9600 frames at 48 kHz, got ${produced.size}")
    }

    @Test
    fun energySurvivesTheConversion() {
        val input = tone(44_100, 44_100, 440.0)
        val produced = convert(input, 44_100, 48_000)
        // Past the filter's fade-in at the start and its fade-out at the end.
        val after = meanSquare(produced, 64, produced.size - 64)
        val before = meanSquare(input)
        assertTrue(abs(after - before) / before < 0.02, "mean square went from $before to $after")
    }

    @Test
    fun splittingTheInputAcrossBuffersChangesNothing() {
        val input = tone(4_410, 44_100, 440.0)
        val whole = convert(input, 44_100, 48_000, chunkFrames = 4_410)
        val split = convert(input, 44_100, 48_000, chunkFrames = 147)
        assertEquals(whole.size, split.size, "the same input must give the same number of frames")
        val worst = whole.indices.maxOf { abs(whole[it] - split[it]) }
        assertTrue(worst < 1e-6f, "a buffer boundary changed the signal by $worst")
    }

    @Test
    fun channelsStayInTheirOwnLanes() {
        // A tone on the left, silence on the right.
        val input = FloatArray(4_410 * 2) { i ->
            if (i % 2 == 0) kotlin.math.sin(2.0 * kotlin.math.PI * 440.0 * (i / 2) / 44_100).toFloat() else 0f
        }
        val produced = convert(input, 44_100, 48_000, channels = 2)
        val right = FloatArray(produced.size / 2) { produced[it * 2 + 1] }
        val left = FloatArray(produced.size / 2) { produced[it * 2] }
        assertTrue(meanSquare(right) < 1e-10, "the silent channel picked up ${meanSquare(right)}")
        assertTrue(meanSquare(left) > 0.4, "the tone channel lost its tone: ${meanSquare(left)}")
    }

    @Test
    fun contentAboveTheNewNyquistIsRemovedRatherThanFolded() {
        val produced = convert(tone(48_000, 48_000, 15_000.0), 48_000, 16_000)
        // 15 kHz sampled at 16 kHz folds to 1 kHz.
        val alias = amplitudeAt(produced, 16_000, 1_000.0, from = 64, until = produced.size - 64)
        assertTrue(alias < 0.01, "a 15 kHz tone left $alias of amplitude at 1 kHz after converting to 16 kHz")
    }

    @Test
    fun contentTheTargetCanCarryPassesThroughAtItsOwnLevel() {
        val produced = convert(tone(48_000, 48_000, 1_000.0), 48_000, 16_000)
        val kept = amplitudeAt(produced, 16_000, 1_000.0, from = 64, until = produced.size - 64)
        // Bounded on both sides: garbage samples also measure large.
        assertTrue(kept in 0.95..1.05, "a 1 kHz tone must survive at full amplitude, got $kept")
    }

    @Test
    fun theFlushReleasesWhatTheGraphHeld() {
        val resampler = KiteFFmpegResampler().create(44_100, 48_000, 1)
        try {
            val output = FloatArray(resampler.outputCapacity(441))
            val processed = resampler.process(tone(441, 44_100, 440.0), 441, output)
            val flushed = resampler.flush(FloatArray(resampler.outputCapacity(0)))
            assertTrue(flushed > 0, "the filter holds input at the end of a stream, and it is real audio")
            assertTrue(abs(processed + flushed - 480) <= 2, "441 frames in owe 480 out, got ${processed + flushed}")
        } finally {
            resampler.close()
        }
    }

    @Test
    fun aResetDropsWhatWasHeldAndTheNextCallStartsOver() {
        val resampler = KiteFFmpegResampler().create(44_100, 48_000, 1)
        try {
            val input = tone(441, 44_100, 440.0)
            val first = FloatArray(resampler.outputCapacity(441))
            val firstCount = resampler.process(input, 441, first)
            resampler.reset()
            assertEquals(0, resampler.flush(FloatArray(resampler.outputCapacity(0))), "a reset must drop what was held")
            val again = FloatArray(resampler.outputCapacity(441))
            val againCount = resampler.process(input, 441, again)
            assertEquals(firstCount, againCount, "after a reset the same input must give the same output")
            for (i in 0 until firstCount) assertEquals(first[i], again[i], "frame $i differs after a reset")
        } finally {
            resampler.close()
        }
    }

    @Test
    fun aShortOutputArrayLosesNothing() {
        val resampler = KiteFFmpegResampler().create(44_100, 48_000, 1)
        try {
            var total = 0
            val small = FloatArray(100)
            repeat(10) { total += resampler.process(tone(441, 44_100, 440.0), 441, small) }
            // Whatever did not fit comes out of later calls, the flush included.
            while (true) {
                val more = resampler.flush(small)
                if (more == 0) break
                total += more
            }
            assertTrue(abs(total - 4_800) <= 2, "4410 frames in owe 4800 out, got $total")
        } finally {
            resampler.close()
        }
    }

    @Test
    fun moreChannelsThanAFrameCarriesAreRefused() {
        assertFailsWith<IllegalArgumentException> { KiteFFmpegResampler().create(44_100, 48_000, 9) }
    }
}
