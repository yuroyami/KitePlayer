package io.github.yuroyami.kiteplayer.compose

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kiteplayer.PictureCrop
import io.github.yuroyami.kiteplayer.VideoScale
import io.github.yuroyami.kiteplayer.VideoSize
import io.github.yuroyami.kiteplayer.VideoTransform
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [videoQuad], the web's statement of where a picture lands, against [drawVideoPicture], the canvas
 * draw every other platform uses (#125). The web pass draws one quad from those four corners and
 * texture points, so if a point inside the quad shows a different cell of the image than the canvas
 * draw puts there, the web picture is in the wrong place, turned the wrong way or cropped wrongly.
 *
 * The image is 4 by 4 cells of sixteen colours, one pixel each, so each cell of what is shown is a
 * large patch on the 300 by 200 area, and the points sampled are the patches' centres.
 */
class VideoQuadTest {
    init { useSkiaGraphics() }

    private val side = 4

    /** Cell (column, row) of the image, as a colour no other cell has. */
    private fun cellColor(column: Int, row: Int): Int =
        (((column * 60 + 20) and 0xFF) shl 16) or (((row * 60 + 20) and 0xFF) shl 8) or (((column + row * side) * 15) and 0xFF)

    private fun image(): ImageBitmap {
        val bytes = ByteArray(side * side * 4)
        for (row in 0 until side) {
            for (column in 0 until side) {
                val color = cellColor(column, row)
                val at = (row * side + column) * 4
                bytes[at] = (color shr 16).toByte()
                bytes[at + 1] = (color shr 8).toByte()
                bytes[at + 2] = color.toByte()
                bytes[at + 3] = 0xFF.toByte()
            }
        }
        return FrameImagePool().imageFor(bytes, side, side).image
    }

    private fun drawnPixels(layout: VideoLayout, crop: PictureCrop?, width: Int, height: Int): IntArray {
        val target = ImageBitmap(width, height)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(target), Size(width.toFloat(), height.toFloat())) {
            clipRect { drawVideoPicture(image(), layout, layout.mirrored, FilterQuality.None, colorFilter = null, crop = crop) }
        }
        return IntArray(width * height).also(target::readPixels)
    }

    /** One case: a picture of [size] less [crop], turned, mirrored and laid out on a 300 by 200 area. */
    private fun assertQuadMatchesCanvas(
        rotation: Int,
        mirrored: Boolean,
        crop: PictureCrop?,
        mode: VideoScale = VideoScale.Fit,
        transform: VideoTransform = VideoTransform.Identity,
    ) {
        val width = 300
        val height = 200
        val size = VideoSize(side, side)
        val layout = videoLayout(width, height, size.cropped(crop), rotation, mode, transform, mirrored)!!
        val quad = videoQuad(layout, side, side, crop)
        val pixels = drawnPixels(layout, crop, width, height)
        val shownColumns = side - (crop?.left ?: 0) - (crop?.right ?: 0)
        val shownRows = side - (crop?.top ?: 0) - (crop?.bottom ?: 0)
        var checked = 0
        // The centre of every shown cell, as a fraction of the shown part across and down.
        for (cellRow in 0 until shownRows) {
            for (cellColumn in 0 until shownColumns) {
                val s = (cellColumn + 0.5f) / shownColumns
                val t = (cellRow + 0.5f) / shownRows
                val x = lerp(quad.x, s, t)
                val y = lerp(quad.y, s, t)
                val px = x.roundToInt()
                val py = y.roundToInt()
                // Fill and zoom put part of the picture outside the area; those points prove nothing.
                if (px !in 0 until width || py !in 0 until height) continue
                val u = lerp(quad.u, s, t)
                val v = lerp(quad.v, s, t)
                val expected = cellColor((u * side).toInt(), (v * side).toInt())
                val actual = pixels[py * width + px] and 0xFFFFFF
                assertEquals(
                    expected.toString(16),
                    actual.toString(16),
                    "turn $rotation, mirrored $mirrored, crop $crop, $mode, $transform: cell ($cellColumn, $cellRow) at ($px, $py)",
                )
                checked++
            }
        }
        assert(checked >= shownColumns) { "only $checked cells landed inside the area" }
    }

    /** The point [s] across and [t] down the quad, from its strip-ordered corners. */
    private fun lerp(corners: FloatArray, s: Float, t: Float): Float =
        (1 - s) * (1 - t) * corners[0] + s * (1 - t) * corners[1] + (1 - s) * t * corners[2] + s * t * corners[3]

    @Test
    fun everyTurnAndMirrorLandsWhereTheCanvasDrawsIt() {
        for (rotation in listOf(0, 90, 180, 270)) {
            for (mirrored in listOf(false, true)) {
                assertQuadMatchesCanvas(rotation, mirrored, crop = null)
            }
        }
    }

    @Test
    fun aCropShowsOnlyWhatItLeavesWhateverTheTurn() {
        val crop = PictureCrop(top = 0, bottom = 1, left = 1, right = 0)
        for (rotation in listOf(0, 90, 180, 270)) {
            for (mirrored in listOf(false, true)) {
                assertQuadMatchesCanvas(rotation, mirrored, crop)
            }
        }
    }

    @Test
    fun fillZoomAndPanMoveTheQuadAsTheyMoveTheCanvas() {
        assertQuadMatchesCanvas(90, mirrored = false, crop = null, mode = VideoScale.Fill)
        assertQuadMatchesCanvas(0, mirrored = true, crop = PictureCrop(left = 1), mode = VideoScale.Stretch)
        assertQuadMatchesCanvas(270, mirrored = false, crop = null, transform = VideoTransform(zoom = 1.5f, panX = 0.1f, panY = -0.05f))
    }
}
