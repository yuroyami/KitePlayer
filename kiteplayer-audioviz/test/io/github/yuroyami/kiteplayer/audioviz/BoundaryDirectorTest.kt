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

    /** [frame] with one accepted section boundary delivered in it, the [sequence]th of its history. */
    private fun boundary(frame: SpectrumFrame, sequence: Long = 0L): SpectrumFrame {
        val hit = AudioEvent(frame.generation, frame.analysisRevision, sequence,
            AudioDetection(AudioEventKind.SectionBoundary, frame.ptsMicros, frame.ptsMicros, 0.5f, 0.9f, 0.5f))
        return frame.withDeliveredEvents(AudioEventDelivery(frame.generation, frame.analysisRevision,
            frame.ptsMicros, arrayOf(DeliveredAudioEvent(hit, 0L))))
    }

    /**
     * The drawing a director opens on counts as shown, so the first accepted boundary replaces it.
     *
     * Two drawings make the case plain: a director that forgets its opening drawing picks it again
     * for about half of all seeds, and that boundary then passes with nothing changed.
     */
    @Test
    fun theFirstAcceptedBoundaryReplacesTheOpeningDrawing() {
        val two = VizCatalog.create().take(2)
        val kept = (0L until 64L).filter { seed ->
            val director = VizDirector(two, seed = seed, minimumHoldSeconds = 0f)
            director.advance(boundary(frame(0f)), 1f / 60f)
            !director.changing
        }
        assertEquals(emptyList<Long>(), kept, "the first boundary kept the opening drawing for these seeds")
    }

    /**
     * Every boundary changes the drawing while another one exists.
     *
     * Three drawings are fewer than the director remembers, so after two changes every drawing was
     * shown lately, and the fallback to the whole catalogue must still leave out the one on screen.
     */
    @Test
    fun everyBoundaryChangesTheDrawingInASmallCatalogue() {
        val three = VizCatalog.create().take(3)
        val kept = ArrayList<String>()
        for (seed in 0L until 16L) {
            val director = VizDirector(three, seed = seed, minimumHoldSeconds = 0f)
            var time = 0f
            for (index in 0L until 8L) {
                val before = director.current.name
                director.advance(boundary(frame(time), sequence = index), 1f / 60f)
                if (!director.changing) kept += "seed $seed, boundary $index kept $before"
                // A change without a usable tempo lasts 1.6 seconds, so it has finished after two.
                repeat(120) {
                    time += 1f / 60f
                    director.advance(frame(time), 1f / 60f)
                }
                assertFalse(director.changing, "the change did not finish")
            }
        }
        assertEquals(emptyList<String>(), kept, "these boundaries changed nothing")
    }

    @Test
    fun oneDrawingIsKeptAtEveryBoundary() {
        val one = VizCatalog.create().take(1)
        val director = VizDirector(one, seed = 3L, minimumHoldSeconds = 0f)
        director.advance(boundary(frame(0f)), 1f / 60f)
        assertFalse(director.changing)
        assertEquals(one[0], director.current)
    }

    @Test
    fun elapsedTimeDoesNotInventAMusicalBoundary() {
        val director = VizDirector(VizCatalog.create(), seed = 2L)
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
        val director = VizDirector(VizCatalog.create(), seed = 2L)
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
        val director = VizDirector(VizCatalog.create(), seed = 2L)
        director.maximumHoldSeconds = 30f
        val initial = director.current
        var changedAt = -1f
        repeat(60 * 120) { index ->
            val at = index / 60f
            director.advance(frame(at, tracked = true), 1f / 60f)
            if (changedAt < 0f && director.changing) changedAt = at
        }
        assertTrue(changedAt >= 0f, "a long hold never changed the drawing")
        assertTrue(changedAt >= 30f, "a long hold changed at $changedAt, before the 30 seconds asked for")
        assertTrue(changedAt < 40f, "a long hold waited until $changedAt")
        assertNotEquals(initial, director.current, "the drawing did not actually change")
    }

    /** With no usable tempo there is no beat to land on, so the wait alone has to be enough. */
    @Test
    fun aLongHoldStillChangesWhenTheTempoIsNotUsable() {
        val director = VizDirector(VizCatalog.create(), seed = 2L)
        director.maximumHoldSeconds = 30f
        var changedAt = -1f
        repeat(60 * 120) { index ->
            val at = index / 60f
            director.advance(frame(at), 1f / 60f)
            if (changedAt < 0f && director.changing) changedAt = at
        }
        assertTrue(changedAt >= 0f, "an untracked long hold never changed the drawing")
        assertTrue(changedAt >= 30f && changedAt < 32f, "an untracked long hold changed at $changedAt")
    }

    @Test
    fun anEnergyRecoveryOrThinningFlagDoesNotEstablishASectionChange() {
        val director = VizDirector(VizCatalog.create(), seed = 2L)
        repeat(60 * 10) { index -> director.advance(frame(index / 60f), 1f / 60f) }
        director.advance(frame(10f, rise = true), 1f / 60f)
        assertFalse(director.changing, "an energy-only flag cannot establish a musical drop")
    }
}
