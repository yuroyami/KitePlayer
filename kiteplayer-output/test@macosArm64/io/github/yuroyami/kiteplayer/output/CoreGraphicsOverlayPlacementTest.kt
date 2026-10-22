@file:OptIn(ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.VideoSize
import io.github.yuroyami.kiteplayer.spi.ColorSpaceInfo
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.spi.SubtitleOverlay
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import kotlinx.atomicfu.atomic
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import platform.AppKit.NSImage
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGRectMake
import kotlin.test.fail

/**
 * The placement contract on the Core Graphics renderer for macOS.
 *
 * This renderer composes the picture and its subtitles into an image of the picture's own size,
 * and a platform image view fits that image into the window. Its output is that image, so it
 * reports no size and the contract lays the overlay out for the picture, upright, the way the
 * engine does. The image is read back pixel by pixel.
 */
class CoreGraphicsOverlayPlacementTest : OverlayPlacementContractTest() {

    override fun reportedOutput(scene: Scene): VideoSize? {
        val renderer = renderer { }
        return try {
            renderer.outputSize
        } finally {
            renderer.close()
        }
    }

    override fun compose(scene: Scene, overlay: SubtitleOverlay): Composite = runBlocking {
        val drawn = atomic<NSImage?>(null)
        val renderer = renderer { image -> drawn.value = image }
        try {
            renderer.setScaleMode(scene.scale)
            renderer.setTransform(scene.transform)
            renderer.setOverlay(overlay)
            check(renderer.present(PlacementFrame(scene.picture, scene.rotationDegrees), targetNanos = 0L)) {
                "the renderer refused the frame"
            }
            var waited = 0
            while (drawn.value == null && waited < 5_000) {
                delay(1)
                waited++
            }
            val image = drawn.value ?: fail("the renderer drew nothing in five seconds")
            readBack(image, overlay.images.size)
        } finally {
            renderer.close()
        }
    }

    private fun renderer(show: (NSImage) -> Unit) = AppKitVideoRenderer(
        convert = { frame -> pictureBytes(frame.size.width, frame.size.height) },
        enqueueOnMain = { block -> block() },
        showImage = show,
    )

    /** Tightly packed RGBA in the picture colour, opaque. */
    private fun pictureBytes(width: Int, height: Int): ByteArray {
        val bytes = ByteArray(width * height * 4)
        for (at in bytes.indices step 4) {
            bytes[at] = (pictureColor shr 16).toByte()
            bytes[at + 1] = (pictureColor shr 8).toByte()
            bytes[at + 2] = pictureColor.toByte()
            bytes[at + 3] = 0xFF.toByte()
        }
        return bytes
    }

    /** The drawn image's own pixels, row zero at the top, and where each marker sits in them. */
    private fun readBack(image: NSImage, markers: Int): Composite {
        val cgImage = image.CGImageForProposedRect(null, null, null) ?: fail("the drawn image has no bitmap")
        val width = CGImageGetWidth(cgImage).toInt()
        val height = CGImageGetHeight(cgImage).toInt()
        val rgba = ByteArray(width * height * 4)
        val colorSpace = CGColorSpaceCreateDeviceRGB() ?: fail("no device RGB colour space")
        try {
            rgba.usePinned { pinned ->
                val context = CGBitmapContextCreate(
                    data = pinned.addressOf(0),
                    width = width.toULong(),
                    height = height.toULong(),
                    bitsPerComponent = 8u,
                    bytesPerRow = (width * 4).toULong(),
                    space = colorSpace,
                    bitmapInfo = CGImageAlphaInfo.kCGImageAlphaNoneSkipLast.value,
                ) ?: fail("no read-back context")
                try {
                    CGContextDrawImage(context, CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()), cgImage)
                } finally {
                    CGContextRelease(context)
                }
            }
        } finally {
            CGColorSpaceRelease(colorSpace)
        }
        val boxes = findMarkers(width, height, markers) { x, y ->
            val at = (y * width + x) * 4
            ((rgba[at].toInt() and 0xFF) shl 16) or ((rgba[at + 1].toInt() and 0xFF) shl 8) or (rgba[at + 2].toInt() and 0xFF)
        }
        return Composite(width, height, boxes)
    }

    private class PlacementFrame(picture: VideoSize, override val rotationDegrees: Int) : VideoFrame {
        override val pts: Pts = Pts.Zero
        override val duration: Pts? = null
        override val size: VideoSize = picture
        override val pixelFormat: PlayerPixelFormat = PlayerPixelFormat.Rgba
        override val colorSpace: ColorSpaceInfo = ColorSpaceInfo.Unspecified
        override val hardwareSurface: Nothing? = null
        override val generation: Generation = Generation.Initial
        override fun close() = Unit
    }
}
