package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.shader.NeonLoFiRegions
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.SHADER_TIME_PERIOD
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Night Drive's clocks still move after days of play at 144 Hz (#960). */
class NeonLoFiLongRunClockTest {

    @Test
    fun theRegionClockStillAddsA144HzStepAfter36Hours() {
        val regions = NeonLoFiRegions()
        val step = 1f / 144f
        // 40 hours in one step, then one display step: a Float clock past 131,072 s added nothing.
        regions.advance(40f * 3600f, 1f, manual = 0, pace = 1f, seed = 0f, level = 0f, density = 0f, centroid = 0f, structure = false)
        val before = regions.clock
        regions.advance(step, 1f, manual = 0, pace = 1f, seed = 0f, level = 0f, density = 0f, centroid = 0f, structure = false)
        assertEquals(step.toDouble(), regions.clock - before, 1e-9, "one step of heard time")
    }

    @Test
    fun theShaderTimeWrapsWhereBothOfItsSinesRepeat() {
        assertEquals(20.0 * PI, SHADER_TIME_PERIOD)
        for (t in listOf(1.0, 123.4, 5_000.0)) {
            for (rate in listOf(0.5, 0.4)) {
                val wrapped = (t + SHADER_TIME_PERIOD) % SHADER_TIME_PERIOD
                assertTrue(abs(sin((t % SHADER_TIME_PERIOD) * rate) - sin(wrapped * rate)) < 1e-9)
                assertTrue(abs(sin(t * rate) - sin((t + SHADER_TIME_PERIOD) * rate)) < 1e-9, "sin($rate t) repeats at the period")
            }
        }
    }
}
