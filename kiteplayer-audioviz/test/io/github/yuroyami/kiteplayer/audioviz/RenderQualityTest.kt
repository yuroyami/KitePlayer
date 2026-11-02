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

    @Test
    fun aSeverelyOverBudgetFrameReducesWorkImmediately() {
        val quality = RenderQuality()
        quality.afterFrame(millis = 500f, deltaSeconds = 0.1f)
        assertTrue(quality.current < 0.2f, "a half-second frame must not wait for four more stalls")
        assertTrue(quality.stepped < 0.25f, "dynamic feedback must be able to go below quarter scale")
        assertTrue(500f * quality.stepped * quality.stepped < 14f, "the next frame's estimated pixel work should fit")
    }

    @Test
    fun explicitFullResolutionStillWinsOverTheBudget() {
        val quality = RenderQuality(scale = 1f, dynamic = false)
        quality.afterFrame(millis = 500f, deltaSeconds = 0.1f)
        assertEquals(1f, quality.stepped)
    }

    /** On a 120 Hz screen 11 ms of trail work is more than a frame, so the buffer shrinks (#658). */
    @Test
    fun aFasterDisplayHasAShorterBudget() {
        val slow = RenderQuality()
        repeat(600) { slow.afterFrame(millis = 11f, deltaSeconds = 1f / 120f) }
        assertTrue(slow.current < 1f, "11 ms frames at 120 Hz kept the buffer at ${slow.current}")

        // 9 ms at 120 Hz is past its quick limit, so the buffer does not grow back.
        val held = RenderQuality()
        repeat(600) { held.afterFrame(millis = 30f, deltaSeconds = 1f / 120f) }
        val after = held.current
        repeat(600) { held.afterFrame(millis = 9f, deltaSeconds = 1f / 120f) }
        assertTrue(held.current <= after, "9 ms frames at 120 Hz grew the buffer from $after to ${held.current}")
    }

    /** The 60 Hz limits are the ones they always were, and a stall's long step does not raise them. */
    @Test
    fun theSixtyHertzLimitsAndAStallAreAsBefore() {
        val steady = RenderQuality()
        repeat(600) { steady.afterFrame(millis = 11f, deltaSeconds = 1f / 60f) }
        assertEquals(1f, steady.current, "11 ms at 60 Hz is inside its frame")
        val stalled = RenderQuality()
        repeat(10) { stalled.afterFrame(millis = 15f, deltaSeconds = 0.1f) }
        assertTrue(stalled.current < 1f, "a run of slow frames with stalled steps did not shrink the buffer")
    }
}
