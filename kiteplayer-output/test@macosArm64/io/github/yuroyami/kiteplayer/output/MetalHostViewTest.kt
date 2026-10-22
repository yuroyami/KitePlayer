@file:OptIn(ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.output

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.QuartzCore.CAMetalLayer
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A CAMetalLayer does not resize its own drawable.
 *
 * The window is not part of this: the host view is built directly and its own resize callback is
 * called the way AppKit calls it during a live drag, which is the whole mechanism. What a real
 * window adds is only who calls it and with what, and that part is AppKit's to get right.
 */
class MetalHostViewTest {

    @Test
    fun `a resize carries the drawable with it in physical pixels`() {
        val layer = CAMetalLayer()
        layer.contentsScale = 2.0
        layer.drawableSize = CGSizeMake(1280.0, 960.0)
        val host = MetalHostView(
            frame = CGRectMake(0.0, 0.0, 640.0, 480.0),
            metalLayer = layer,
        )
        host.wantsLayer = true
        host.layer = layer

        host.setFrameSize(CGSizeMake(800.0, 600.0))

        val drawable = layer.drawableSize.useContents { width to height }
        assertEquals(1600.0, drawable.first, "800 points at the layer's own 2x scale")
        assertEquals(1200.0, drawable.second, "600 points at the layer's own 2x scale")
    }

    @Test
    fun `a window dragged to nothing still asks for a drawable Metal can make`() {
        val size = metalDrawableSize(0.0, 0.0, 2.0).useContents { width to height }
        assertEquals(1.0, size.first)
        assertEquals(1.0, size.second)
    }
}
