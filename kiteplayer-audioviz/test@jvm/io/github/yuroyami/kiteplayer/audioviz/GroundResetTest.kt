package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.ground.Detail
import io.github.yuroyami.kiteplayer.audioviz.viz.ground.DetailKind
import io.github.yuroyami.kiteplayer.audioviz.viz.ground.Ground
import io.github.yuroyami.kiteplayer.audioviz.viz.ground.GroundKind
import kotlin.test.Test
import kotlin.test.assertEquals

/** A reset ground and a reset detail layer start like new ones, palette turn included. */
class GroundResetTest {

    init { useSkiaGraphics() }

    @Test
    fun aResetForgetsTheTurnOfThePalette() {
        // The layers are drawn before the drawing sets this, so the first frame after a restart reads it as it is.
        val ground = Ground(GroundKind.Grid).also { it.walk = 0.5f }
        ground.reset()
        assertEquals(0f, ground.walk, "the ground kept the palette turn of the last run")
        val detail = Detail(DetailKind.Hatch).also { it.walk = 0.5f }
        detail.reset()
        assertEquals(0f, detail.walk, "the detail layer kept the palette turn of the last run")
    }
}
