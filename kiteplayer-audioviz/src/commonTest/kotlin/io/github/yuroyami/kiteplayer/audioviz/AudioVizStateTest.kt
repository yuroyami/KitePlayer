package io.github.yuroyami.kiteplayer.audioviz

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The state behind the composable: which moment it draws, and what a hand on the controls changes. */
class AudioVizStateTest {

    init { useSkiaGraphics() } // before the catalogue, which builds drawings

    @Test
    fun `the picture shows the analysis for the moment the player is at`() {
        var position = 0L
        val state = AudioVizState(positionMicros = { position })
        assertTrue(state.nextFrame().ptsMicros < 0, "before any sound the picture should be silent")

        state.tap.hear(fromMicros = 0, seconds = 2f)
        position = 1_000_000
        val frame = state.nextFrame()
        // A little ahead of the reported position, since a drawn frame reaches the eye a moment later.
        assertTrue(
            frame.ptsMicros in 1_000_000..1_030_000,
            "at 1 s the picture drew the analysis stamped ${frame.ptsMicros}",
        )
    }

    @Test
    fun `choosing a drawing by hand shows it at once even while the director runs`() {
        val state = AudioVizState(positionMicros = { 0L })
        state.directed = true
        val chosen = state.catalogue[5]
        state.drawing = chosen
        assertSame(chosen, state.showing)
        assertSame(chosen, state.director.current)
    }

    @Test
    fun `turning the director off keeps the drawing it was showing`() {
        val state = AudioVizState(positionMicros = { 0L })
        state.directed = true
        val pickedByTheDirector = state.catalogue[7]
        state.director.show(pickedByTheDirector)
        state.directed = false
        assertSame(pickedByTheDirector, state.drawing)
        assertSame(pickedByTheDirector, state.showing)
    }

    @Test
    fun `mutate changes the recipe of the drawing on screen`() {
        val state = AudioVizState(positionMicros = { 0L })
        val drawing = state.catalogue.first { it.genes != null }
        state.drawing = drawing
        val genes = assertNotNull(drawing.genes)
        val before = genes.changes
        state.mutate()
        assertTrue(genes.changes > before, "mutate left the recipe as it was")
    }
}
