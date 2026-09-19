package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.audioviz.viz.Camera2D
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Gestures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A new frame at an already drawn display instant delivers its events but adds no elapsed time. */
class DisplayInstantTest {
    private fun hit(sequence: Long) = AudioEvent(Generation.Initial, 0L, sequence,
        AudioDetection(AudioEventKind.LowTransient, 90_000L, 100_000L, 0.8f, 0.9f, 0.3f))

    private fun frame(vararg events: AudioEvent) = SpectrumFrame(100_000L, FloatArray(4), FloatArray(4), FloatArray(4),
        0.3f, 0.3f, 0.3f, 0.3f, 0f, 0f,
        events = AudioEventDelivery(Generation.Initial, 0L, 100_000L,
            Array(events.size) { DeliveredAudioEvent(events[it], 0L) }))

    private fun state(frame: SpectrumFrame, time: Float, delta: Float = 1f / 60f) =
        VizRenderState(frame, time, delta, VizPalette.Classic)

    @Test
    fun aFlatCameraKeepsAHitThatArrivesAtARepeatedInstant() {
        val repeated = Camera2D(wander = 0f, roll = 0f, shake = 0f, cuts = false)
        val reference = Camera2D(wander = 0f, roll = 0f, shake = 0f, cuts = false)
        repeated.advance(state(frame(), 1f))
        repeated.advance(state(frame(hit(1)), 1f))
        reference.advance(state(frame(hit(1)), 1f))
        repeat(6) { step ->
            val later = state(frame(), 1f + (step + 1) / 60f)
            repeated.advance(later)
            reference.advance(later)
            assertEquals(reference.zoom, repeated.zoom, 1e-6f, "zoom after ${step + 1} frames")
        }
        assertTrue(reference.zoom > 1.01f, "the reference camera must punch")
    }

    @Test
    fun gesturesDoNotIntegrateOneInstantTwice() {
        val repeated = Gestures()
        val reference = Gestures()
        repeated.update(state(frame(), 1f, 0.05f))
        repeated.update(state(frame(), 1f, 0.05f))
        reference.update(state(frame(), 1f, 0.05f))
        assertTrue(reference.cyclePhase > 0f, "the reference cycle must move")
        assertEquals(reference.cyclePhase, repeated.cyclePhase, 1e-6f)
        assertEquals(reference.slowCyclePhase, repeated.slowCyclePhase, 1e-6f)
    }

    @Test
    fun theSameStateTwiceIsConsumedOnce() {
        val twice = Camera2D(wander = 0f, roll = 0f, shake = 0f, cuts = false)
        val once = Camera2D(wander = 0f, roll = 0f, shake = 0f, cuts = false)
        val first = state(frame(hit(1)), 1f)
        twice.advance(first)
        twice.advance(first)
        once.advance(first)
        val later = state(frame(), 1.1f, 0.1f)
        twice.advance(later)
        once.advance(later)
        assertEquals(once.zoom, twice.zoom, 1e-6f)
    }
}
