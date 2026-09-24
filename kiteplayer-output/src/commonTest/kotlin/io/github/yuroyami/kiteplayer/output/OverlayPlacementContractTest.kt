// A copy of kiteplayer-core/src/commonTest/.../OverlayPlacementContractTest.kt, because Kotlin
// Multiplatform shares no test code between modules. Change every copy in the same commit.
package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.VideoScale
import io.github.yuroyami.kiteplayer.VideoSize
import io.github.yuroyami.kiteplayer.VideoTransform
import io.github.yuroyami.kiteplayer.spi.OverlayImage
import io.github.yuroyami.kiteplayer.spi.SubtitleOverlay
import io.github.yuroyami.kiteplayer.subtitle.RgbaBitmap
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Where a renderer puts subtitle overlay images: rules 1 and 2 of docs/subtitle-placement.md.
 *
 * Every renderer, and every view that draws an overlay, runs this through a subclass of its own.
 * The subclass sets each scene up on the real renderer and answers two questions: what size the
 * renderer reports to the engine, and where each overlay image landed on the output. The test lays
 * the overlay out the way the engine does and checks every image against rule 1.
 */
abstract class OverlayPlacementContractTest {

    /** One frame to draw: the output that a test asks for, and the picture in it. */
    protected class Scene(
        /** The output the test asks for. A renderer whose output is its own picture ignores it. */
        val outputWidth: Int,
        val outputHeight: Int,
        /** The frame's stored size and pixel aspect. */
        val picture: VideoSize,
        val rotationDegrees: Int = 0,
        val scale: VideoScale = VideoScale.Fit,
        val transform: VideoTransform = VideoTransform.Identity,
    )

    /** A rectangle in output pixels. */
    protected data class Box(val left: Float, val top: Float, val width: Float, val height: Float) {
        val right: Float get() = left + width
        val bottom: Float get() = top + height
    }

    /**
     * What one drawn frame showed: the size of the output the renderer really drew, and where each
     * overlay image landed, in overlay order. Null marks an image that was not drawn.
     */
    protected class Composite(val outputWidth: Int, val outputHeight: Int, val boxes: List<Box?>)

    /**
     * What the renderer reports through `VideoRenderer.outputSize` once it is set up for [scene],
     * or null when it reports nothing.
     */
    protected abstract fun reportedOutput(scene: Scene): VideoSize?

    /** Draws one frame of [scene] with [overlay] above it, and says where each image landed. */
    protected abstract fun compose(scene: Scene, overlay: SubtitleOverlay): Composite

    /** The colour of overlay image [index], as `0xRRGGBB`: red, then green. Opaque, so premultiplying keeps it. */
    protected fun markerColor(index: Int): Int = MARKER_COLORS[index % MARKER_COLORS.size]

    /** The colour a subclass paints its picture in, as `0xRRGGBB`, so no marker is mistaken for it. */
    protected val pictureColor: Int = 0x0000FF

    /**
     * Finds each marker in a drawn output. [rgbAt] answers the pixel at (x, y) as `0xRRGGBB`, with
     * y counted from the top. A pixel belongs to a marker when every channel is within 48 of the
     * marker's, so a filtered edge is left out and a box can come out one pixel short on a side.
     */
    protected fun findMarkers(width: Int, height: Int, count: Int, rgbAt: (x: Int, y: Int) -> Int): List<Box?> =
        (0 until count).map { index ->
            val marker = markerColor(index)
            var minX = Int.MAX_VALUE
            var minY = Int.MAX_VALUE
            var maxX = Int.MIN_VALUE
            var maxY = Int.MIN_VALUE
            for (y in 0 until height) {
                for (x in 0 until width) {
                    if (!closeTo(rgbAt(x, y), marker)) continue
                    if (x < minX) minX = x
                    if (y < minY) minY = y
                    if (x > maxX) maxX = x
                    if (y > maxY) maxY = y
                }
            }
            if (minX > maxX) {
                null
            } else {
                Box(minX.toFloat(), minY.toFloat(), (maxX - minX + 1).toFloat(), (maxY - minY + 1).toFloat())
            }
        }

    @Test
    fun cornerImagesLandInTheBarsBesideAFittedPicture() {
        // A 4:3 picture fitted into a 2:1 output leaves bars left and right. An image at the
        // bottom-left of the viewport belongs at the bottom-left of the output, in the bar, and not
        // at the corner of the picture.
        val scene = Scene(outputWidth = 1000, outputHeight = 500, picture = VideoSize(640, 480))
        val size = layoutSize(scene)
        assertPlacement(
            scene,
            overlay(
                size,
                marker(0, x = 0, y = size.height - 40, width = 120, height = 40),
                marker(1, x = size.width - 120, y = 0, width = 120, height = 40),
            ),
        )
    }

    @Test
    fun aRendererWithAnOutputOfItsOwnReportsItsSize() {
        // Rule 2: when the output is not the picture's own image, only the renderer knows its size,
        // and without it the engine lays out for the picture and rule 1 stretches that over the
        // output. A renderer whose output is the picture itself may answer nothing.
        val scene = Scene(outputWidth = 1000, outputHeight = 500, picture = VideoSize(640, 480))
        val size = layoutSize(scene)
        val composite = compose(scene, overlay(size, marker(0, x = 0, y = size.height - 40, width = 120, height = 40)))
        val picture = uprightPicture(scene)
        if (composite.outputWidth == picture.width && composite.outputHeight == picture.height) return
        assertEquals(
            VideoSize(composite.outputWidth, composite.outputHeight),
            reportedOutput(scene),
            "the renderer drew a ${composite.outputWidth}x${composite.outputHeight} output and did not report it",
        )
    }

    @Test
    fun anOverlayLaidOutForAnotherSizeStretchesOverTheWholeOutput() {
        // Between a resize and the engine's next layout, a renderer holds an overlay laid out for
        // the old size. Rule 1 still maps its viewport onto the whole output.
        val scene = Scene(outputWidth = 1000, outputHeight = 500, picture = VideoSize(640, 480))
        val size = layoutSize(scene)
        val old = VideoSize(size.width / 2, size.height / 2)
        assertPlacement(
            scene,
            overlay(
                old,
                marker(0, x = 0, y = old.height - 20, width = 60, height = 20),
                marker(1, x = old.width - 60, y = 0, width = 60, height = 20),
            ),
        )
    }

    @Test
    fun aTurnedPictureLeavesTheOverlayUpright() {
        // A picture recorded on its side is drawn turned. The text is laid out upright for the
        // output, so it must neither turn nor move with the picture.
        val scene = Scene(outputWidth = 500, outputHeight = 1000, picture = VideoSize(640, 480), rotationDegrees = 90)
        val size = layoutSize(scene)
        assertPlacement(
            scene,
            overlay(
                size,
                marker(0, x = 0, y = size.height - 40, width = 120, height = 40),
                marker(1, x = size.width - 120, y = 0, width = 120, height = 40),
            ),
        )
    }

    @Test
    fun fillZoomAndPanMoveThePictureButNotTheOverlay() {
        val scene = Scene(
            outputWidth = 1000,
            outputHeight = 500,
            picture = VideoSize(640, 480),
            scale = VideoScale.Fill,
            transform = VideoTransform(zoom = 2f, panX = 0.5f, panY = -0.5f),
        )
        val size = layoutSize(scene)
        assertPlacement(
            scene,
            overlay(
                size,
                marker(0, x = 0, y = size.height - 40, width = 120, height = 40),
                marker(1, x = size.width - 120, y = 0, width = 120, height = 40),
            ),
        )
    }

    @Test
    fun anAnamorphicPictureLeavesTheOverlayOnTheOutput() {
        // Stored 720x480 with wide pixels, shown 853x480. The overlay still covers the output,
        // whatever size the renderer composes the picture at.
        val scene = Scene(outputWidth = 1000, outputHeight = 500, picture = VideoSize(720, 480, 32, 27))
        val size = layoutSize(scene)
        assertPlacement(
            scene,
            overlay(
                size,
                marker(0, x = 0, y = size.height - 40, width = 120, height = 40),
                marker(1, x = size.width - 120, y = 0, width = 120, height = 40),
            ),
        )
    }

    /** The size the engine lays out for: the renderer's answer, else the picture's display size turned upright. */
    private fun layoutSize(scene: Scene): VideoSize {
        reportedOutput(scene)?.let { reported ->
            if (reported.width > 0 && reported.height > 0) return reported
        }
        return uprightPicture(scene)
    }

    /** The picture's display size, turned upright when it carries a quarter turn. */
    private fun uprightPicture(scene: Scene): VideoSize {
        val displayWidth = scene.picture.displayWidth.takeIf { it > 0 } ?: scene.picture.width
        val turn = ((scene.rotationDegrees % 360) + 360) % 360
        return if (turn == 90 || turn == 270) {
            VideoSize(scene.picture.height, displayWidth)
        } else {
            VideoSize(displayWidth, scene.picture.height)
        }
    }

    private fun overlay(viewport: VideoSize, vararg images: OverlayImage): SubtitleOverlay =
        SubtitleOverlay(images.toList(), viewport.width, viewport.height, contentHash = 1L)

    /** A solid, opaque image in the colour of marker [index]. */
    private fun marker(index: Int, x: Int, y: Int, width: Int, height: Int): OverlayImage {
        val color = markerColor(index)
        val pixels = ByteArray(width * height * 4)
        for (at in pixels.indices step 4) {
            pixels[at] = (color shr 16).toByte()
            pixels[at + 1] = (color shr 8).toByte()
            pixels[at + 2] = color.toByte()
            pixels[at + 3] = 0xFF.toByte()
        }
        return OverlayImage(x, y, RgbaBitmap(width, height, pixels))
    }

    private fun assertPlacement(scene: Scene, overlay: SubtitleOverlay) {
        val composite = compose(scene, overlay)
        assertTrue(composite.outputWidth > 0 && composite.outputHeight > 0, "the renderer drew no output")
        assertEquals(overlay.images.size, composite.boxes.size, "one answer for each overlay image")
        val scaleX = composite.outputWidth.toFloat() / overlay.viewportWidth
        val scaleY = composite.outputHeight.toFloat() / overlay.viewportHeight
        overlay.images.forEachIndexed { index, image ->
            val expected = Box(
                left = image.x * scaleX,
                top = image.y * scaleY,
                width = image.bitmap.width * scaleX,
                height = image.bitmap.height * scaleY,
            )
            val actual = assertNotNull(composite.boxes[index], "overlay image $index was not drawn")
            val what = "overlay image $index on a ${composite.outputWidth}x${composite.outputHeight} output " +
                "landed at $actual, where rule 1 puts it at $expected"
            assertTrue(abs(expected.left - actual.left) <= TOLERANCE, what)
            assertTrue(abs(expected.top - actual.top) <= TOLERANCE, what)
            assertTrue(abs(expected.right - actual.right) <= TOLERANCE, what)
            assertTrue(abs(expected.bottom - actual.bottom) <= TOLERANCE, what)
        }
    }

    private fun closeTo(rgb: Int, marker: Int): Boolean {
        for (shift in intArrayOf(16, 8, 0)) {
            if (abs(((rgb shr shift) and 0xFF) - ((marker shr shift) and 0xFF)) > 48) return false
        }
        return true
    }

    private companion object {
        val MARKER_COLORS = intArrayOf(0xFF0000, 0x00FF00)

        /** A pixel of rounding, and one more for a filtered edge a pixel reader leaves out. */
        const val TOLERANCE = 1.5f
    }
}
