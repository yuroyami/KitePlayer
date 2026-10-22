package io.github.yuroyami.kiteplayer.compose

import kotlin.test.Test
import kotlin.test.assertEquals

class RenderPathResolutionTest {

    @Test
    fun `an explicit native view request is honoured now that desktop has one`() {
        assertEquals(KiteRenderPath.NativeView, resolveRenderPath(KiteRenderPath.NativeView))
    }

    @Test
    fun `auto is the native view on desktop, owner-decided 2026-08-30`() {
        // The trade was taken deliberately and on measurements: with the UI choked to 4.7 frames
        // a second the native view kept painting about 29, where the Compose canvas draws the
        // picture at the UI's own rate. The cost is that Compose content over the video cannot be
        // clicked, so a consumer wanting overlaid controls asks for ComposeCanvas explicitly.
        assertEquals(KiteRenderPath.NativeView, resolveRenderPath(KiteRenderPath.Auto))
    }

    @Test
    fun `an explicit compose canvas request is honoured`() {
        assertEquals(KiteRenderPath.ComposeCanvas, resolveRenderPath(KiteRenderPath.ComposeCanvas))
    }
}
