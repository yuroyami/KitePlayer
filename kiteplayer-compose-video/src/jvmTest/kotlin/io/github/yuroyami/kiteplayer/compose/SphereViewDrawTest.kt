package io.github.yuroyami.kiteplayer.compose

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kiteplayer.ProjectionKind
import io.github.yuroyami.kiteplayer.SphericalView
import io.github.yuroyami.kiteplayer.VideoProjection
import io.github.yuroyami.kiteplayer.VideoScale
import io.github.yuroyami.kiteplayer.VideoSize
import io.github.yuroyami.kiteplayer.VideoTransform
import io.github.yuroyami.kiteplayer.spi.SphericalLens
import kotlin.math.abs
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Compose canvas path's view into a 360 degree picture, drawn by Skia (#460). Each view is
 * compared with [SphericalLens], the law every renderer draws by, wherever the picture is one
 * colour around the point the law reads; the band checks below it lean on the picture alone.
 */
class SphereViewDrawTest {
    init { useSkiaGraphics() }

    private val whole = VideoProjection(ProjectionKind.Equirectangular)
    private val tile = VideoProjection(ProjectionKind.EquirectangularTile(left = 0.25, top = 0.0, right = 0.25, bottom = 0.0))

    @Test
    fun eachViewDrawsWhatTheLensReads() {
        val picture = SpherePicture.whole()
        val views = listOf(
            SphericalView(),
            SphericalView(yaw = 90f, pitch = 30f),
            SphericalView(yaw = -135f, pitch = -45f, roll = 20f, fieldOfView = 100f),
            SphericalView(pitch = 90f, fieldOfView = 120f),
            SphericalView(yaw = 180f, fieldOfView = 30f),
            SphericalView(yaw = 725f, roll = -60f),
        )
        for (view in views) {
            assertMatchesLens(picture, whole, view, width = 160, height = 90)
            // On a screen held upright, the field of view spans the width.
            assertMatchesLens(picture, whole, view, width = 90, height = 160)
        }
        val posed = VideoProjection(ProjectionKind.Equirectangular, yaw = 30.0, pitch = 10.0, roll = -5.0)
        assertMatchesLens(picture, posed, SphericalView(yaw = 200f, pitch = -20f, roll = 10f), width = 160, height = 90)
    }

    @Test
    fun aTileDrawsItsPartAndNothingBeyondIt() {
        val picture = SpherePicture.tile()
        assertMatchesLens(picture, tile, SphericalView(), width = 160, height = 90)
        // Half of this view is beyond the tile's right edge.
        assertMatchesLens(picture, tile, SphericalView(yaw = 90f), width = 160, height = 90)
    }

    @Test
    fun eachViewLooksWhereItsTurnsPointIt() {
        val picture = SpherePicture.whole()
        val centre = mapOf(
            SphericalView() to SpherePicture.RED,
            SphericalView(yaw = 90f) to SpherePicture.GREEN,
            SphericalView(yaw = -90f) to SpherePicture.WHITE,
            SphericalView(yaw = 45f) to SpherePicture.YELLOW,
            SphericalView(yaw = -45f) to SpherePicture.ORANGE,
            // Straight behind, where the picture's left and right edges join.
            SphericalView(yaw = 180f) to SpherePicture.BLUE,
            SphericalView(yaw = 450f) to SpherePicture.GREEN,
            SphericalView(pitch = 90f) to SpherePicture.TOP,
            SphericalView(pitch = -90f) to SpherePicture.BOTTOM,
        )
        for ((view, colour) in centre) {
            assertEquals(hex(colour), hex(draw(picture, whole, view, 160, 90).at(80, 45)), "the centre of $view")
        }
        // A positive roll turns the view clockwise, so its right edge looks down and its left up.
        val rolled = draw(picture, whole, SphericalView(roll = 90f, fieldOfView = 120f), 160, 90)
        assertEquals(hex(SpherePicture.BOTTOM), hex(rolled.at(158, 45)), "the right edge of a view rolled clockwise")
        assertEquals(hex(SpherePicture.TOP), hex(rolled.at(1, 45)), "the left edge of a view rolled clockwise")
        // The field of view spans the shorter side: 90 degrees across a square reaches the bands
        // that are 45 degrees out at its edges.
        val square = draw(picture, whole, SphericalView(fieldOfView = 90f), 100, 100)
        assertEquals(hex(SpherePicture.YELLOW), hex(square.at(97, 50)))
        assertEquals(hex(SpherePicture.ORANGE), hex(square.at(2, 50)))
    }

    @Test
    fun theFilesPoseStartsTheView() {
        val picture = SpherePicture.whole()
        // The front of a picture posed 30 degrees to the right is 30 degrees to the right.
        val posed = VideoProjection(ProjectionKind.Equirectangular, yaw = 30.0)
        assertEquals(hex(SpherePicture.RED), hex(draw(picture, posed, SphericalView(yaw = 30f), 160, 90).at(80, 45)))
        assertEquals(hex(SpherePicture.ORANGE), hex(draw(picture, posed, SphericalView(), 160, 90).at(80, 45)))
        // Posed 30 degrees up, the front is above where the view starts.
        val raised = VideoProjection(ProjectionKind.Equirectangular, pitch = 30.0)
        assertEquals(hex(SpherePicture.RED), hex(draw(picture, raised, SphericalView(pitch = 30f), 160, 90).at(80, 45)))
    }

    @Test
    fun aTileShowsItsPartAndTheBackgroundBeyondIt() {
        val picture = SpherePicture.tile()
        assertEquals(hex(SpherePicture.RED), hex(draw(picture, tile, SphericalView(), 160, 90).at(80, 45)))
        assertEquals(hex(SpherePicture.GREEN), hex(draw(picture, tile, SphericalView(yaw = 80f), 160, 90).at(80, 45)))
        val behind = draw(picture, tile, SphericalView(yaw = 180f), 160, 90)
        assertTrue(behind.pixels.all { it ushr 24 == 0 }, "the view behind a tile drew a picture it does not have")
    }

    /** With no sphere to draw, the draw is the flat picture's, pixel for pixel, in every mode. */
    @Test
    fun aPictureWithNoViewDrawsExactlyAsAFlatOne() {
        val image = SpherePicture.whole().image
        val frame = KiteVideoFrame(image, VideoSize(512, 256), rotationDegrees = 0)
        for (mode in VideoScale.entries) {
            for (framing in listOf(VideoTransform.Identity, VideoTransform(zoom = 1.5f, panX = 0.2f))) {
                val flat = paint(160, 90) {
                    val layout = videoLayout(160, 90, frame.shownSize, 0, mode, framing)!!
                    val draw: DrawScope.() -> Unit = {
                        drawVideoPicture(image, layout, false, FilterQuality.Low, colorFilter = null)
                    }
                    if (mode == VideoScale.Fill || !framing.isIdentity) {
                        clipRect { draw() }
                    } else {
                        draw()
                    }
                }
                val drawn = paint(160, 90) {
                    drawFramePicture(frame, mode, framing, FilterQuality.Low, videoFilter = null, sphere = null)
                }
                assertContentEquals(flat.pixels, drawn.pixels, "$mode with $framing")
            }
        }
    }

    /** The renderer hands the draw a sphere only for a view it draws into a picture it draws. */
    @Test
    fun theRendererPublishesASphereOnlyForAViewItDraws() {
        var published: SphereView? = SphereView(whole, SphericalView())
        val renderer = KiteVideoRenderer(
            convert = { ByteArray(0) },
            makeImage = { _, width, height -> FrameImage(ImageBitmap(width, height)) },
            publish = {},
            publishSphere = { published = it },
        )
        try {
            assertTrue(renderer.projects(ProjectionKind.Equirectangular))
            assertTrue(renderer.projects(ProjectionKind.EquirectangularTile(0.25, 0.0, 0.25, 0.0)))
            assertEquals(false, renderer.projects(ProjectionKind.Cubemap(0)))
            assertEquals(false, renderer.projects(ProjectionKind.Other("fisheye")))

            val view = SphericalView(yaw = 10f)
            renderer.setProjection(whole, view)
            assertEquals(SphereView(whole, view), published)
            renderer.setProjection(whole, view.copy(projected = false))
            assertEquals(null, published, "a view switched to the flat picture")
            renderer.setProjection(VideoProjection(ProjectionKind.Cubemap(0)), view)
            assertEquals(null, published, "a cube map, which is drawn flat")
            renderer.setProjection(tile, view)
            assertEquals(SphereView(tile, view), published)
            renderer.setProjection(null, view)
            assertEquals(null, published, "a flat video")
        } finally {
            renderer.close()
        }
    }

    // ---------------------------------------------------------------------------------------------

    private class Drawn(val width: Int, val height: Int, val pixels: IntArray) {
        fun at(x: Int, y: Int): Int = pixels[y * width + x]
    }

    private fun paint(width: Int, height: Int, block: DrawScope.() -> Unit): Drawn {
        val target = ImageBitmap(width, height)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(target), Size(width.toFloat(), height.toFloat()), block)
        val pixels = IntArray(width * height)
        target.readPixels(pixels)
        return Drawn(width, height, pixels)
    }

    private fun draw(picture: SpherePicture, projection: VideoProjection, view: SphericalView, width: Int, height: Int): Drawn {
        val frame = KiteVideoFrame(picture.image, VideoSize(picture.width, picture.height), rotationDegrees = 0)
        return paint(width, height) {
            assertTrue(
                drawFramePicture(frame, VideoScale.Fit, VideoTransform.Identity, FilterQuality.Low, null, SphereView(projection, view)),
                "nothing was drawn",
            )
        }
    }

    /**
     * Draws [view] and compares every pixel the lens reads from a flat part of the picture: one
     * colour all around the point it reads, so the smoothing of a sample cannot matter. Where a
     * tile has no picture, nothing may be drawn.
     */
    private fun assertMatchesLens(picture: SpherePicture, projection: VideoProjection, view: SphericalView, width: Int, height: Int) {
        val drawn = draw(picture, projection, view, width, height)
        val lens = SphericalLens(projection, view, width.toFloat(), height.toFloat())
        var compared = 0
        var wrong = 0
        val failures = mutableListOf<String>()
        for (y in 0 until height) {
            for (x in 0 until width) {
                val actual = drawn.at(x, y)
                val point = lens.pictureAt(x + 0.5, y + 0.5)
                if (point == null) {
                    compared++
                    if (actual ushr 24 != 0) {
                        wrong++
                        if (failures.size < 5) failures += "($x, $y) drew ${hex(actual)} beyond the picture"
                    }
                    continue
                }
                val expected = picture.flatAround(point.across * picture.width, point.down * picture.height, lens.wraps) ?: continue
                compared++
                if (!close(actual, expected)) {
                    wrong++
                    if (failures.size < 5) failures += "($x, $y) drew ${hex(actual)} where the lens reads ${hex(expected)}"
                }
            }
        }
        assertTrue(compared > width * height * 3 / 4, "too few pixels to compare for $view: $compared of ${width * height}")
        assertEquals(0, wrong, "$view on $width by $height: $failures")
    }

    private fun close(actual: Int, expected: Int): Boolean {
        if (actual ushr 24 != 0xFF) return false
        return (0..2).all { channel ->
            abs((actual shr (8 * channel) and 0xFF) - (expected shr (8 * channel) and 0xFF)) <= 3
        }
    }

    private fun hex(argb: Int): String = "#" + (argb.toLong() and 0xFFFFFFFFL).toString(16).padStart(8, '0')
}

/**
 * An equirectangular picture of eight vertical bands, each 45 degrees wide and centred on a whole
 * multiple of 45 degrees, under a dark band along the top and over another along the bottom, each
 * 22.5 degrees high: the picture `scripts/testmedia.sh` encodes into `testmedia/spherical`.
 */
internal class SpherePicture private constructor(
    val width: Int,
    val height: Int,
    private val colours: IntArray,
) {
    val image: ImageBitmap by lazy {
        val bytes = ByteArray(width * height * 4)
        for (index in colours.indices) {
            val colour = colours[index]
            bytes[index * 4] = (colour shr 16).toByte()
            bytes[index * 4 + 1] = (colour shr 8).toByte()
            bytes[index * 4 + 2] = colour.toByte()
            bytes[index * 4 + 3] = 0xFF.toByte()
        }
        FrameImagePool().imageFor(bytes, width, height).image
    }

    /**
     * The colour at ([x], [y]) of the picture, in its pixels, when every pixel within two of it is
     * that colour, or null on the edge between two. Across the whole sphere the left and right
     * edges join.
     */
    fun flatAround(x: Double, y: Double, wraps: Boolean): Int? {
        var found: Int? = null
        for (dy in -2..2) {
            for (dx in -2..2) {
                var column = floor(x).toInt() + dx
                val row = floor(y).toInt() + dy
                if (row !in 0 until height) continue
                if (wraps) column = Math.floorMod(column, width) else if (column !in 0 until width) continue
                val colour = colours[row * width + column]
                if (found == null) found = colour else if (found != colour) return null
            }
        }
        return found?.let { it or (0xFF shl 24) }
    }

    companion object {
        const val BLUE: Int = 0xFF0000FF.toInt()
        const val MAGENTA: Int = 0xFFFF00FF.toInt()
        const val WHITE: Int = 0xFFFFFFFF.toInt()
        const val ORANGE: Int = 0xFFFF8000.toInt()
        const val RED: Int = 0xFFFF0000.toInt()
        const val YELLOW: Int = 0xFFFFFF00.toInt()
        const val GREEN: Int = 0xFF00FF00.toInt()
        const val CYAN: Int = 0xFF00FFFF.toInt()
        const val TOP: Int = 0xFF303030.toInt()
        const val BOTTOM: Int = 0xFF603000.toInt()

        /** The bands from the left edge, which is 180 degrees behind: blue is split between the two edges. */
        private val BANDS = intArrayOf(BLUE, MAGENTA, WHITE, ORANGE, RED, YELLOW, GREEN, CYAN, BLUE)

        /** The whole sphere, 512 by 256. */
        fun whole(): SpherePicture = SpherePicture(512, 256, IntArray(512 * 256) { index -> colourAt(index % 512, index / 512) })

        /** The middle half of the whole sphere's width, from -90 to 90 degrees, and all of its height. */
        fun tile(): SpherePicture = SpherePicture(256, 256, IntArray(256 * 256) { index -> colourAt(128 + index % 256, index / 256) })

        private fun colourAt(x: Int, y: Int): Int = when {
            y < 32 -> TOP
            y >= 224 -> BOTTOM
            // The first band is half as wide: it is the right half of the band behind the viewer.
            else -> BANDS[(x + 32) / 64]
        } and 0xFFFFFF
    }
}
