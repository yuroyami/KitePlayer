package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.motion.MusicClock
import kotlin.math.abs
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RhythmMotionTest {
    private fun frame(seconds: Float, usable: Boolean, revision: Long = 0L): SpectrumFrame {
        val at = (seconds * 1_000_000L).toLong()
        val phase = seconds * 2f + 0.37f
        return SpectrumFrame(at, FloatArray(1), FloatArray(1), FloatArray(1), 0f, 0f, 0f, 0f, 0f, 0f,
            bpm = 120f, beatConfidence = 0.99f,
            rhythm = RhythmEstimate(at, at, at + 1_000_000L, revision, 120f, 0.99f, 0.99f,
                usable, phase - floor(phase), 60f, 0.9f))
    }

    @Test
    fun highRawScoresWithoutAUsablePhaseFollowTheFreeMotionRate() {
        val motion = MusicClock(beatsPerCycle = 1f)
        repeat(60) { motion.advance(1f / 60f, frame(it / 60f, false), 0.3f) }
        assertEquals(0.3f, motion.phase, 1e-5f)
    }

    @Test
    fun aPulseCanLockMotionWithoutABarOrPhraseAndLossDoesNotJump() {
        val motion = MusicClock(beatsPerCycle = 1f)
        var previous = 0f
        var worst = 0f
        repeat(600) { index ->
            val reading = frame((index + 1) / 60f, true)
            val phase = motion.advance(1f / 60f, reading, 0.2f)
            var step = abs(phase - previous)
            if (step > 0.5f) step = 1f - step
            worst = maxOf(worst, step)
            previous = phase
        }
        var error = abs(motion.phase - frame(10f, true).rhythm!!.beatPhase)
        if (error > 0.5f) error = 1f - error
        assertTrue(error < 0.025f, "pulse error $error without invented phrase position")
        assertTrue(worst < 0.05f, "acquisition step $worst")
        val before = motion.phase
        motion.advance(1f / 60f, frame(10f + 1f / 60f, false), 0.2f)
        var step = abs(motion.phase - before)
        if (step > 0.5f) step = 1f - step
        assertTrue(step < 0.05f, "loss step $step")
        repeat(120) { motion.advance(1f / 60f, frame(11f, false), 0.2f) }
        val settled = motion.phase
        repeat(60) { motion.advance(1f / 60f, frame(12f, false), 0.2f) }
        assertEquals(0.2f, (motion.phase - settled + 1f) % 1f, 1e-4f)
    }

    @Test
    fun theSameTwoSecondHandoverWorksAtDifferentRefreshRates() {
        fun run(rate: Int): Float {
            val motion = MusicClock(beatsPerCycle = 4f)
            repeat(rate * 6) { index -> motion.advance(1f / rate, frame((index + 1f) / rate, true), 0.08f) }
            return motion.phase
        }
        val slow = run(30)
        val fast = run(120)
        var error = abs(slow - fast)
        if (error > 0.5f) error = 1f - error
        assertTrue(error < 0.01f, "refresh-dependent phase difference $error")
    }
}
