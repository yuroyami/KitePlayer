package io.github.yuroyami.kiteplayer.compose

import kotlin.test.Test
import kotlin.test.assertEquals

class RenderPathResolutionTest {

    @Test
    fun jvmCoercesEveryRequestToComposeCanvas() {
        assertEquals(KiteRenderPath.ComposeCanvas, resolveRenderPath(KiteRenderPath.Auto))
        assertEquals(KiteRenderPath.ComposeCanvas, resolveRenderPath(KiteRenderPath.NativeView))
        assertEquals(KiteRenderPath.ComposeCanvas, resolveRenderPath(KiteRenderPath.ComposeCanvas))
    }
}
