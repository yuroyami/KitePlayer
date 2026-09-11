package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.RenderQuality
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The render scale drops when frames run slow and climbs back slowly when they do not. */
class RenderQualityTest {

    @Test
    fun fiveSlowFramesTakeATenthOff() {
        val quality = RenderQuality()
        repeat(4) { quality.afterFrame(millis = 20f, deltaSeconds = 1f / 60f) }
        assertEquals(1f, quality.current, "four slow frames are not yet a pattern")
        quality.afterFrame(millis = 20f, deltaSeconds = 1f / 60f)
        assertEquals(0.9f, quality.current, 1e-4f, "the fifth takes a tenth off")
    }

    @Test
    fun quickFramesClimbBackTwoPercentASecond() {
        val quality = RenderQuality()
        repeat(15) { quality.afterFrame(millis = 20f, deltaSeconds = 1f / 60f) }
        val low = quality.current
        repeat(60) { quality.afterFrame(millis = 5f, deltaSeconds = 1f / 60f) }
        println("after three drops ${low}, a second of quick frames later ${quality.current}")
        assertEquals(low + 0.02f, quality.current, 1e-3f, "a second of quick frames should add two percent")
    }

    @Test
    fun itNeverGoesAboveWhatWasAskedFor() {
        val quality = RenderQuality(scale = 0.5f)
        repeat(600) { quality.afterFrame(millis = 1f, deltaSeconds = 1f / 60f) }
        assertTrue(quality.current <= 0.5f, "it should stay at or under the chosen scale, was ${quality.current}")
        val fixed = RenderQuality(dynamic = false)
        repeat(20) { fixed.afterFrame(millis = 40f, deltaSeconds = 1f / 60f) }
        assertEquals(1f, fixed.current, "with dynamic off it should never move")
    }
}
