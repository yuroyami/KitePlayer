package io.github.yuroyami.kiteplayer.compose

import kotlin.test.Test
import kotlin.test.assertEquals

class RenderPathResolutionTest {

    @Test
    fun `an explicit native view request is honoured now that desktop has one`() {
        assertEquals(KiteRenderPath.NativeView, resolveRenderPath(KiteRenderPath.NativeView))
    }

    @Test
    fun `auto stays on the compose canvas until the owner moves the default`() {
        // Deliberate: the native view wins on jank and loses on input, since Compose content over
        // it cannot be clicked. Which of those a consumer should get by default is an owner
        // decision taken on measurements, not a default to drift into.
        assertEquals(KiteRenderPath.ComposeCanvas, resolveRenderPath(KiteRenderPath.Auto))
    }

    @Test
    fun `an explicit compose canvas request is honoured`() {
        assertEquals(KiteRenderPath.ComposeCanvas, resolveRenderPath(KiteRenderPath.ComposeCanvas))
    }
}
