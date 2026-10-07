package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.StereoStage
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the two front speakers play (#462). Each test feeds a left side and a right side of
 * different, steady values, so where each one went can be read off the output.
 */
class StereoStageTest {

    private val left = 0.6f
    private val right = -0.2f

    /** [frames] stereo frames of [left] and [right], with [extra] more channels of 0.9 each. */
    private fun frames(frames: Int, extra: Int = 0): FloatArray {
        val channels = 2 + extra
        return FloatArray(frames * channels) { index ->
            when (index % channels) {
                0 -> left
                1 -> right
                else -> 0.9f
            }
        }
    }

    /** The stage set to [mode] before any audio, run over one buffer, and the last frame's two sides. */
    private fun settled(mode: StereoMode): Pair<Float, Float> {
        val stage = StereoStage(channels = 2, rampFrames = 480)
        stage.set(mode)
        val samples = frames(1000)
        stage.apply(samples, 1000)
        return samples[1998] to samples[1999]
    }

    private fun assertSides(expected: Pair<Float, Float>, actual: Pair<Float, Float>, what: String) {
        assertTrue(abs(expected.first - actual.first) < 1e-6f && abs(expected.second - actual.second) < 1e-6f, "$what: $actual")
    }

    @Test
    fun stereoLeavesTheBufferAlone() {
        val stage = StereoStage(channels = 2, rampFrames = 480)
        val samples = frames(10)
        val original = samples.copyOf()
        stage.apply(samples, 10)
        assertContentEquals(original, samples)
        assertTrue(stage.isIdentity)
    }

    @Test
    fun eachModeSendsEachSideWhereItSays() {
        assertSides(left to right, settled(StereoMode.Stereo), "stereo")
        assertSides((left + right) / 2 to (left + right) / 2, settled(StereoMode.Mono), "mono")
        assertSides(left to left, settled(StereoMode.LeftOnly), "left only")
        assertSides(right to right, settled(StereoMode.RightOnly), "right only")
        assertSides(right to left, settled(StereoMode.Swapped), "swapped")
    }

    @Test
    fun monoNeverPassesFullScale() {
        val stage = StereoStage(channels = 2, rampFrames = 0)
        stage.set(StereoMode.Mono)
        val samples = floatArrayOf(1f, 1f, -1f, -1f, 1f, -1f)
        stage.apply(samples, 3)
        assertTrue(samples.all { abs(it) <= 1f }, samples.toList().toString())
    }

    @Test
    fun onlyTheFrontTwoChannelsMove() {
        val stage = StereoStage(channels = 6, rampFrames = 0)
        stage.set(StereoMode.Swapped)
        val samples = frames(4, extra = 4)
        val original = samples.copyOf()
        stage.apply(samples, 4)
        assertEquals(right, samples[18], 1e-6f)
        assertEquals(left, samples[19], 1e-6f)
        for (frame in 0 until 4) {
            for (channel in 2 until 6) {
                val index = frame * 6 + channel
                assertEquals(original[index], samples[index], "surround channel $channel moved in frame $frame")
            }
        }
    }

    /** A change while playing crossfades over the ramp, so no frame jumps further than the ramp's step. */
    @Test
    fun aChangeWhilePlayingCrossfadesWithoutAClick() {
        val stage = StereoStage(channels = 2, rampFrames = 480)
        stage.apply(frames(100), 100)
        stage.set(StereoMode.Swapped)
        val samples = frames(1000)
        stage.apply(samples, 1000)
        val leftSide = (0 until 1000).map { samples[it * 2] }
        val largestStep = leftSide.zipWithNext { a, b -> abs(b - a) }.max()
        val swing = abs(right - left)
        assertTrue(largestStep <= swing / 480 * 1.01f, "the left side jumped $largestStep in one frame")
        assertEquals(right, leftSide.last(), 1e-6f, "the change never finished")
        // A second change part way through turns back from where the first had got to.
        stage.set(StereoMode.Stereo)
        val back = frames(10)
        stage.apply(back, 10)
        assertTrue(abs(back[0] - right) <= swing / 480 * 1.01f, "the second change jumped to ${back[0]}")
    }

    /** A stage that has played nothing yet takes its mode at once, as a rebuilt pipeline's must. */
    @Test
    fun aFreshStageTakesItsModeWithoutARamp() {
        assertSides(right to left, StereoStage(channels = 2, rampFrames = 480).let { stage ->
            stage.set(StereoMode.Swapped)
            val samples = frames(1)
            stage.apply(samples, 1)
            samples[0] to samples[1]
        }, "a fresh stage ramped")
    }
}
