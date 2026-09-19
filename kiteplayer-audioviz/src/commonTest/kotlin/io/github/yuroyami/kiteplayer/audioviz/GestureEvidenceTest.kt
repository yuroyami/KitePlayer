package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Gestures
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Genes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class GestureEvidenceTest {
    @Test
    fun freeMotionDoesNotManufactureBarsPhrasesOrRecipeChanges() {
        val gestures = Gestures()
        val genes = Genes(1).apply { choice("layout", 4) }
        repeat(60 * 60) { index ->
            val frame = SpectrumFrame(index * 1_000_000L / 60, FloatArray(1), FloatArray(1), FloatArray(1),
                0.3f, 0.3f, 0.3f, 0.3f, 0f, 0f)
            gestures.update(VizRenderState(frame, index / 60f, 1f / 60f, VizPalette.Classic))
            genes.advance(gestures, 1f / 60f)
            assertFalse(gestures.bar)
            assertFalse(gestures.phrase)
        }
        assertEquals(0, gestures.bars)
        assertEquals(0, gestures.phrases)
        assertEquals(0, genes.changes)
    }

    @Test
    fun energyOnlyFlagsDoNotBecomeStructuralGestures() {
        val gestures = Gestures()
        val frame = SpectrumFrame(1_000_000L, FloatArray(1), FloatArray(1), FloatArray(1),
            0.3f, 0.3f, 0.3f, 0.3f, 0f, 0f, drop = true, breakdown = true)
        gestures.update(VizRenderState(frame, 1f, 1f / 60f, VizPalette.Classic))
        assertFalse(gestures.drop)
        assertFalse(gestures.breakdown)
    }
}
