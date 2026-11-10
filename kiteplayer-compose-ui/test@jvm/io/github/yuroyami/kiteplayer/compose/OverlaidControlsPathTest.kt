package io.github.yuroyami.kiteplayer.compose

import kotlin.test.Test
import kotlin.test.assertEquals

/** Controls drawn over the video get a path whose picture lets them be clicked (#940). */
class OverlaidControlsPathTest {

    @Test
    fun autoIsTheComposeCanvasWhereTheNativeViewTakesTheClicks() {
        val expected = if (nativeViewTakesOverlayClicks) KiteRenderPath.Auto else KiteRenderPath.ComposeCanvas
        assertEquals(expected, overlaidControlsPath(KiteRenderPath.Auto))
    }

    @Test
    fun anExplicitPathIsKept() {
        assertEquals(KiteRenderPath.NativeView, overlaidControlsPath(KiteRenderPath.NativeView))
        assertEquals(KiteRenderPath.ComposeCanvas, overlaidControlsPath(KiteRenderPath.ComposeCanvas))
    }
}
