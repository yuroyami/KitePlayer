package io.github.yuroyami.kiteplayer.compose

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kiteplayer.VideoSize
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [drawVideoPicture] on a Skia canvas: a quarter turn is clockwise, and a mirrored picture is
 * mirrored left to right before it turns. The picture is 4 by 2 in four colours, so where each
 * colour lands says which way the picture went.
 */
class VideoPictureDrawTest {
    init { useSkiaGraphics() }

    private val red = 0xFF0000
    private val green = 0x00FF00
    private val blue = 0x0000FF
    private val white = 0xFFFFFF

    /** Red and green along the top, blue and white along the bottom. */
    private fun quadrants(): ImageBitmap {
        val bytes = ByteArray(4 * 2 * 4)
        for (y in 0 until 2) {
            for (x in 0 until 4) {
                val color = when {
                    y == 0 && x < 2 -> red
                    y == 0 -> green
                    x < 2 -> blue
                    else -> white
                }
                val at = (y * 4 + x) * 4
                bytes[at] = (color shr 16).toByte()
                bytes[at + 1] = (color shr 8).toByte()
                bytes[at + 2] = color.toByte()
                bytes[at + 3] = 0xFF.toByte()
            }
        }
        return FrameImagePool().imageFor(bytes, 4, 2).image
    }

    /** The colour at each point of a 200 by 200 draw area, as the nearest of the four. */
    private fun drawn(rotation: Int, mirrored: Boolean, points: List<Pair<Int, Int>>): List<Int> {
        val layout = videoLayout(200, 200, VideoSize(4, 2), rotation)!!
        val target = ImageBitmap(200, 200)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(target), Size(200f, 200f)) {
            drawVideoPicture(quadrants(), layout, mirrored, FilterQuality.None, colorFilter = null)
        }
        val pixels = IntArray(200 * 200)
        target.readPixels(pixels)
        return points.map { (x, y) -> nearest(pixels[y * 200 + x]) }
    }

    /** The one of the four colours closest to [argb], so a rounding step in Skia cannot fail a test. */
    private fun nearest(argb: Int): Int = listOf(red, green, blue, white).minBy { color ->
        (16 downTo 0 step 8).sumOf { shift -> kotlin.math.abs((argb shr shift and 0xFF) - (color shr shift and 0xFF)) }
    }

    @Test
    fun aTurnIsClockwiseAndAMirrorComesFirst() {
        // Where each colour has to land, per turn: the same expectations as the output renderers.
        val upright = mapOf(
            0 to listOf(red to (50 to 75), green to (150 to 75), blue to (50 to 125), white to (150 to 125)),
            90 to listOf(red to (125 to 50), green to (125 to 150), blue to (75 to 50), white to (75 to 150)),
            180 to listOf(red to (150 to 125), green to (50 to 125), blue to (150 to 75), white to (50 to 75)),
            270 to listOf(red to (75 to 150), green to (75 to 50), blue to (125 to 150), white to (125 to 50)),
        )
        for ((turn, landings) in upright) {
            val points = landings.map { it.second }
            assertEquals(landings.map { it.first }, drawn(turn, mirrored = false, points), "upright at $turn degrees")
            // Mirrored first, red and green swap places, and so do blue and white.
            val swapped = landings.map { (color, _) ->
                when (color) {
                    red -> green
                    green -> red
                    blue -> white
                    else -> blue
                }
            }
            assertEquals(swapped, drawn(turn, mirrored = true, points), "mirrored at $turn degrees")
        }
    }
}
