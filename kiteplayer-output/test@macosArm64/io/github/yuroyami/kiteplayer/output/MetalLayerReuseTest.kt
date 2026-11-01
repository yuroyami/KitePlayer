@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.VideoSize
import io.github.yuroyami.kiteplayer.spi.ColorMatrix
import io.github.yuroyami.kiteplayer.spi.ColorPrimaries
import io.github.yuroyami.kiteplayer.spi.ColorSpaceInfo
import io.github.yuroyami.kiteplayer.spi.ColorTransfer
import io.github.yuroyami.kiteplayer.spi.HwSurfaceKind
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import platform.CoreGraphics.CGSizeMake
import platform.Metal.MTLPixelFormatRGBA16Float
import platform.QuartzCore.CAMetalLayer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.seconds

/**
 * One layer, two renderers (#689). A view keeps its Metal layer across a detach or a change of
 * player and makes a new renderer for it, and a renderer that closed in extended range left the
 * layer half-float with its extended-range flag, so the next one inherited HDR state it never asked
 * for and took that format as its standard one.
 */
class MetalLayerReuseTest {

    private class TestFrame(override val colorSpace: ColorSpaceInfo) : VideoFrame {
        override val pts = Pts.Zero
        override val duration: Pts? = null
        override val size = VideoSize(SIZE, SIZE, 1, 1)
        override val pixelFormat = PlayerPixelFormat.Nv12
        override val hardwareSurface: HwSurfaceKind? = null
        override val generation = Generation.Initial
        override fun close() = Unit
    }

    /** A display that goes four times beyond standard white. */
    private class BrightDisplay : HeadroomSource {
        override val potential = 4f
        override val current = 4f
        override val known = true
        override fun refresh() = Unit
        override fun close() = Unit
    }

    private fun grey(): MetalPicture.SoftwarePlanes = MetalPicture.SoftwarePlanes(
        width = SIZE,
        height = SIZE,
        format = PlayerPixelFormat.Nv12,
        planes = listOf(
            MetalPicture.SoftwarePlanes.Plane(ByteArray(SIZE * SIZE) { 180.toByte() }, SIZE, SIZE),
            MetalPicture.SoftwarePlanes.Plane(ByteArray(SIZE * SIZE / 2) { 128.toByte() }, SIZE, SIZE / 2),
        ),
    )

    private val pq = ColorSpaceInfo(ColorMatrix.Bt2020Ncl, ColorPrimaries.Bt2020, ColorTransfer.Pq, fullRange = true)

    private suspend fun MetalVideoRenderer.showOne(frame: VideoFrame) {
        val before = presentedFrames + failedFrames
        present(frame, 0L)
        withTimeoutOrNull(10.seconds) { while (presentedFrames + failedFrames == before) delay(5) }
    }

    @Test
    fun aClosedRendererGivesTheLayerBackAsItFoundIt() = runBlocking {
        val layer = CAMetalLayer()
        layer.setDrawableSize(CGSizeMake(SIZE.toDouble(), SIZE.toDouble()))
        val format = layer.pixelFormat
        val first = MetalVideoRenderer(layer, MetalPictureResolver { grey() }) { BrightDisplay() }
        try {
            first.showOne(TestFrame(pq))
            assertEquals(MTLPixelFormatRGBA16Float, layer.pixelFormat, "the HDR frame did not put the layer in extended range")
        } finally {
            first.close()
        }
        assertEquals(format, layer.pixelFormat, "the layer stayed half-float after its renderer closed")
        assertFalse(layer.wantsExtendedDynamicRangeContent, "the layer kept asking for extended range")

        // The next renderer of the same layer starts from the layer's own standard settings.
        val second = MetalVideoRenderer(layer, MetalPictureResolver { grey() }) { BrightDisplay() }
        try {
            second.setHdrPolicy(io.github.yuroyami.kiteplayer.HdrPolicy.ToneMap)
            second.showOne(TestFrame(pq))
            assertEquals(format, layer.pixelFormat, "a tone mapping renderer drew into a half-float layer")
        } finally {
            second.close()
        }
        assertEquals(format, layer.pixelFormat)
    }

    private companion object {
        const val SIZE = 64
    }
}
