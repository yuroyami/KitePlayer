package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.VizTransition
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Bars
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Contour
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.Alchemy
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.transitionFor
import kotlin.test.Test
import kotlin.test.assertEquals

/** A change that renders into bitmaps never leaves a shader drawing blank where bitmaps run no shader (#947). */
class ChangeOnBitmapsTest {
    init { useSkiaGraphics() }

    @Test
    fun aShaderDrawingWithNoFallbackCrossfadesWhereBitmapsRunNoShader() {
        for (asked in listOf(VizTransition.ZoomThrough, VizTransition.WarpHandoff)) {
            assertEquals(VizTransition.Crossfade, transitionFor(asked, Bars(), Alchemy(), bitmapsRunShaders = false), "$asked into Alchemy")
            assertEquals(VizTransition.Crossfade, transitionFor(asked, Alchemy(), Bars(), bitmapsRunShaders = false), "$asked out of Alchemy")
            // Contour draws a fallback picture, so its change is kept.
            assertEquals(asked, transitionFor(asked, Bars(), Contour(), bitmapsRunShaders = false), "$asked into Contour")
            // Where bitmaps run shaders, as on the desktop and iOS, nothing changes.
            assertEquals(asked, transitionFor(asked, Bars(), Alchemy(), bitmapsRunShaders = true), "$asked with shaders on bitmaps")
        }
        // A change drawn on the screen canvas is never replaced.
        assertEquals(VizTransition.Crossfade, transitionFor(VizTransition.Crossfade, Bars(), Alchemy(), bitmapsRunShaders = false))
        assertEquals(VizTransition.StrobeCut, transitionFor(VizTransition.StrobeCut, Bars(), Alchemy(), bitmapsRunShaders = false))
    }
}
