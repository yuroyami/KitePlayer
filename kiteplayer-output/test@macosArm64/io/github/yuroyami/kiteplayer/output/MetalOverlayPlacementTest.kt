@file:OptIn(ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.VideoSize
import io.github.yuroyami.kiteplayer.spi.SubtitleOverlay
import kotlinx.cinterop.ExperimentalForeignApi
import platform.QuartzCore.CAMetalLayer
import kotlin.test.assertContentEquals

/**
 * The placement contract on the Metal renderer.
 *
 * The size comes from a real renderer over a layer with no window, told its viewport the way a
 * host view tells it. The placement comes from [overlayQuadUniforms], the one function the
 * composer draws every overlay quad with, turned back from normalised device coordinates into
 * output pixels.
 */
class MetalOverlayPlacementTest : OverlayPlacementContractTest() {

    override fun reportedOutput(scene: Scene): VideoSize? {
        val renderer = MetalVideoRenderer(CAMetalLayer(), MetalPictureResolver { null })
        return try {
            renderer.setViewport(scene.outputWidth, scene.outputHeight, 1f)
            renderer.outputSize
        } finally {
            renderer.close()
        }
    }

    override fun compose(scene: Scene, overlay: SubtitleOverlay): Composite {
        val width = scene.outputWidth
        val height = scene.outputHeight
        val boxes = overlay.images.map { image ->
            val quad = overlayQuadUniforms(image, overlay, width, height)
            // An upright texture: the basis that samples the image without turning it.
            assertContentEquals(floatArrayOf(1f, 0f, 0f, 1f), quad.copyOfRange(2, 6), "the overlay texture is turned")
            val halfWidth = quad[0]
            val halfHeight = quad[1]
            val centreX = quad[6]
            val centreY = quad[7]
            // Device coordinates run from -1 to 1 with y up; output pixels run down from the top.
            val left = (centreX - halfWidth + 1f) / 2f * width
            val right = (centreX + halfWidth + 1f) / 2f * width
            val top = (1f - (centreY + halfHeight)) / 2f * height
            val bottom = (1f - (centreY - halfHeight)) / 2f * height
            Box(left, top, right - left, bottom - top)
        }
        return Composite(width, height, boxes)
    }
}
