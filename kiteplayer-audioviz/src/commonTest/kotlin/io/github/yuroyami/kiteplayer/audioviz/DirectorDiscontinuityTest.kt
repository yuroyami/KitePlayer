package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.audioviz.viz.VizCatalog
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDirector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** A seek or track change neither cuts a scene change short nor replays a consumed boundary. */
class DirectorDiscontinuityTest {
    init { useSkiaGraphics() }

    private val catalogue = VizCatalog.create()

    private fun frame(generation: Generation, sequence: Long? = null): SpectrumFrame {
        val events = if (sequence == null) emptyArray() else arrayOf(DeliveredAudioEvent(AudioEvent(generation, 0L,
            sequence, AudioDetection(AudioEventKind.SectionBoundary, 1_000_000L, 1_000_000L, 0.5f, 0.9f, 0.5f)), 0L))
        return SpectrumFrame(1_000_000L, FloatArray(4), FloatArray(4), FloatArray(4), 0.5f, 0.5f, 0.5f, 0.5f,
            0f, 0f, mood = 0.5f, generation = generation,
            events = AudioEventDelivery(generation, 0L, 1_000_000L, events))
    }

    @Test
    fun aSeekDuringAChangeFinishesItAndReplaysNothing() {
        val director = VizDirector(catalogue, minimumHoldSeconds = 0f)
        val before = director.current
        director.advance(frame(Generation.Initial, 0L), 1f / 60f)
        assertTrue(director.changing, "an accepted boundary starts a change")
        val arriving = director.incoming
        // The new timeline numbers its events from zero again.
        director.advance(frame(Generation(1), 0L), 1f / 60f)
        assertTrue(director.changing, "a seek does not cut an unfinished change")
        assertEquals(arriving, director.incoming)
        repeat(60 * 7) { director.advance(frame(Generation(1)), 1f / 60f) }
        assertEquals(arriving, director.current, "the change finishes over its planned duration")
        assertFalse(director.changing, "a boundary consumed during the change is not replayed")
        assertNotEquals(before, director.current)
    }
}
