@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.VideoAdjustments
import io.github.yuroyami.kiteplayer.VideoSize
import io.github.yuroyami.kiteplayer.spi.ColorSpaceInfo
import io.github.yuroyami.kiteplayer.spi.HwSurfaceKind
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import kotlinx.coroutines.test.runTest
import kotlin.js.JsAny
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The web canvas says it applies no picture controls, and what it draws must agree (#722): the
 * engine warns the viewer from that flag alone, so a renderer whose flag and pixels disagree
 * either warns for nothing or hides a control it dropped. Only where a real canvas exists.
 */
class WebCanvasCapabilityTest {

    private class Grey(override val size: VideoSize) : VideoFrame {
        override val rotationDegrees: Int = 0
        override val hardwareSurface: HwSurfaceKind? = null
        override val pts: Pts = Pts(0)
        override val duration: Pts? = null
        override val pixelFormat: PlayerPixelFormat = PlayerPixelFormat.Yuv420p
        override val colorSpace: ColorSpaceInfo = ColorSpaceInfo()
        override val generation: Generation = Generation(0)
        override fun close() {}
    }

    private suspend fun drawnCentre(adjustments: VideoAdjustments): Pair<Boolean, Int> {
        val canvas = capabilityCanvas(64, 36)
        val renderer = WebCanvasVideoRenderer(canvas) { _, destination -> fillGrey(destination, 128); true }
        try {
            renderer.setAdjustments(adjustments)
            assertTrue(renderer.present(Grey(VideoSize(64, 36)), 0), "the frame must draw")
            return renderer.appliesAdjustments to capabilityCentreRed(canvas)
        } finally {
            renderer.close()
        }
    }

    @Test
    fun theFlagSaysWhetherABrighterPictureIsDrawnBrighter() = runTest {
        if (!hasCapabilityCanvas()) return@runTest
        val (_, plain) = drawnCentre(VideoAdjustments.Identity)
        val (applies, brighter) = drawnCentre(VideoAdjustments(brightness = 0.3f))
        assertEquals(128, plain, "the plain grey picture")
        assertEquals(applies, brighter != plain, "appliesAdjustments is $applies and the picture read $plain then $brighter")
    }
}

@JsFun("() => typeof OffscreenCanvas !== 'undefined' && !!new OffscreenCanvas(1, 1).getContext('2d')")
private external fun hasCapabilityCanvas(): Boolean

@JsFun("(w, h) => new OffscreenCanvas(w, h)")
private external fun capabilityCanvas(width: Int, height: Int): JsAny

@JsFun("(c) => c.getContext('2d').getImageData(c.width >> 1, c.height >> 1, 1, 1).data[0]")
private external fun capabilityCentreRed(canvas: JsAny): Int

@JsFun("(d, level) => { for (let i = 0; i < d.length; i += 4) { d[i] = level; d[i + 1] = level; d[i + 2] = level; d[i + 3] = 255; } }")
private external fun fillGrey(destination: JsAny, level: Int)
