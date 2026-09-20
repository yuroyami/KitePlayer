package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.VizCatalog
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDirector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

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

    /**
     * The rule above is the default, and an application may buy out of it deliberately.
     *
     * Real music often offers the detector no section it will support, so a viewer-facing
     * application can ask for a change after a long wait instead. It still waits out the whole
     * hold, and it still lands on a beat rather than on the exact second it becomes due.
     */
    @Test
    fun aLongHoldChangesTheDrawingOnlyWhenTheApplicationAsksForIt() {
        val director = VizDirector(VizCatalog.create())
        director.maximumHoldSeconds = 30f
        val initial = director.current
        var changedAt = -1f
        repeat(60 * 120) { index ->
            val at = index / 60f
            director.advance(frame(at, tracked = true), 1f / 60f)
            if (changedAt < 0f && director.changing) changedAt = at
        }
        assertTrue(changedAt >= 30f, "a long hold changed at $changedAt, before the 30 seconds asked for")
        assertTrue(changedAt < 40f, "a long hold never changed, or waited until $changedAt")
        assertNotEquals(initial, director.current, "the drawing did not actually change")
    }

    /** With no usable tempo there is no beat to land on, so the wait alone has to be enough. */
    @Test
    fun aLongHoldStillChangesWhenTheTempoIsNotUsable() {
        val director = VizDirector(VizCatalog.create())
        director.maximumHoldSeconds = 30f
        var changedAt = -1f
        repeat(60 * 120) { index ->
            val at = index / 60f
            director.advance(frame(at), 1f / 60f)
            if (changedAt < 0f && director.changing) changedAt = at
        }
        assertTrue(changedAt >= 30f && changedAt < 32f, "an untracked long hold changed at $changedAt")
    }

    @Test
    fun anEnergyRecoveryOrThinningFlagDoesNotEstablishASectionChange() {
        val director = VizDirector(VizCatalog.create())
        repeat(60 * 10) { index -> director.advance(frame(index / 60f), 1f / 60f) }
        director.advance(frame(10f, rise = true), 1f / 60f)
        assertFalse(director.changing, "an energy-only flag cannot establish a musical drop")
    }
}
