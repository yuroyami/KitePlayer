package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.VizCatalog
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDirector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class BoundaryDirectorTest {
    init { useSkiaGraphics() }

    private fun frame(time: Float, tracked: Boolean = false, rise: Boolean = false): SpectrumFrame =
        SpectrumFrame((time * 1_000_000L).toLong(), FloatArray(4), FloatArray(4), FloatArray(4),
            0.5f, 0.5f, 0.5f, 0.5f, 0f, 0f, mood = 0.8f, bpm = if (tracked) 120f else 0f,
            beatConfidence = if (tracked) 0.99f else 0f, barPhase = time % 2f / 2f,
            phrasePhase = time % 8f / 8f, drop = rise, breakdown = rise)

    @Test
    fun elapsedTimeDoesNotInventAMusicalBoundary() {
        val director = VizDirector(VizCatalog.create())
        val initial = director.current
        repeat(60 * 120) { index ->
            director.advance(frame(index / 60f), 1f / 60f)
            assertFalse(director.changing, "a timer started a change at ${index / 60f}")
        }
        assertEquals(initial, director.current)
        assertEquals(-1f, director.nextChangeSeconds)
    }

    @Test
    fun confidentTempoAndCountedPhasesAreNotSectionEvidence() {
        val director = VizDirector(VizCatalog.create())
        repeat(60 * 120) { index ->
            director.advance(frame(index / 60f, tracked = true), 1f / 60f)
            assertFalse(director.changing, "a counted phrase started a change at ${index / 60f}")
        }
    }

    @Test
    fun anEnergyRecoveryOrThinningFlagDoesNotEstablishASectionChange() {
        val director = VizDirector(VizCatalog.create())
        repeat(60 * 10) { index -> director.advance(frame(index / 60f), 1f / 60f) }
        director.advance(frame(10f, rise = true), 1f / 60f)
        assertFalse(director.changing, "an energy-only flag cannot establish a musical drop")
    }
}
