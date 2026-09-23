package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Gestures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The visual cadence a drawing falls back on when the detectors accept nothing. */
class GestureFallbackTest {

    private fun frame(index: Int, energy: Float, level: Float = 0.3f): SpectrumFrame = SpectrumFrame(
        index * 1_000_000L / 60, FloatArray(1), FloatArray(1), FloatArray(1),
        level, level, level, level, 0f, 0f, energy = energy,
    )

    @Test
    fun everyEighthCycleWithoutABoundaryIsATurn() {
        val gestures = Gestures()
        repeat(60 * 240) { index ->
            gestures.update(VizRenderState(frame(index, 0.5f), index / 60f, 1f / 60f, VizPalette.Classic))
        }
        assertEquals(0, gestures.sections, "a hand-built frame carries no boundary")
        assertTrue(gestures.cycles >= 8, "four minutes of free motion should complete eight cycles, had ${gestures.cycles}")
        assertEquals(gestures.cycles / 8, gestures.turns, "one turn per eight cycles")
    }

    @Test
    fun silenceNeverTurns() {
        val gestures = Gestures()
        repeat(60 * 240) { index ->
            gestures.update(VizRenderState(frame(index, 0f, level = 0f), index / 60f, 1f / 60f, VizPalette.Classic))
        }
        assertEquals(0, gestures.turns)
    }

    @Test
    fun theFirstRiseAfterAQuietStretchSurgesOnceAMinute() {
        val gestures = Gestures()
        var surges = 0
        var firstSurgeAt = -1
        // Ten quiet seconds, five loud, ten quiet, five loud (inside the minute), then the same after it.
        val plan = listOf(10 to 0.1f, 5 to 0.9f, 10 to 0.1f, 5 to 0.9f, 40 to 0.1f, 5 to 0.9f)
        var index = 0
        for ((seconds, energy) in plan) {
            repeat(seconds * 60) {
                gestures.update(VizRenderState(frame(index, energy), index / 60f, 1f / 60f, VizPalette.Classic))
                if (gestures.surge) {
                    surges++
                    if (firstSurgeAt < 0) firstSurgeAt = index
                }
                index++
            }
        }
        assertEquals(2, surges, "the first rise surges, the rise inside the minute does not, the rise after it does")
        assertTrue(firstSurgeAt in 600..660, "the surge lands on the rise, at frame $firstSurgeAt")
        assertEquals(0, gestures.sections)
    }
}
