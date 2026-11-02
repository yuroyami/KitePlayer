package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.KitePlayerInternalApi
import io.github.yuroyami.kiteplayer.SphericalView
import io.github.yuroyami.kiteplayer.VideoProjection
import io.github.yuroyami.kiteplayer.spi.SphericalLens
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Draws a view into a 360 degree picture on the processor (#587), for a renderer that has no
 * shader to run [SphericalLens] in, such as the desktop's AWT canvas.
 *
 * Where each pixel of the view reads the picture depends only on the projection, the view, the
 * view's size and the picture's size, so that is worked out once into a map, two arctangents and a
 * square root for each pixel, and each picture after it is read through the map with a bilinear
 * blend of the four pixels around the point. A turn of the view builds the map again, at the next
 * draw. Where a tile of the sphere has no picture, the view shows [draw]'s background.
 *
 * Not thread safe: one renderer calls it from one paint at a time.
 */
@OptIn(KitePlayerInternalApi::class)
internal class SphereViewRemap {

    private var mapFor: MapKey? = null

    /** The point each view pixel reads, in 256ths of a picture pixel across and down, or [MISSING]. */
    private var across = IntArray(0)
    private var down = IntArray(0)

    /** Times the map was built, for a test to see when a draw reused it. */
    var builds: Int = 0
        private set

    /**
     * Fills [destination], [width] by [height] pixels of `0xRRGGBB`, with [view] into [projection]'s
     * picture: [pictureWidth] by [pictureHeight] pixels of [source], from ([left], [top]) of a raster
     * [stride] pixels wide, which is how a cropped frame sits in its full one.
     */
    fun draw(
        source: IntArray,
        stride: Int,
        left: Int,
        top: Int,
        pictureWidth: Int,
        pictureHeight: Int,
        projection: VideoProjection,
        view: SphericalView,
        destination: IntArray,
        width: Int,
        height: Int,
        background: Int = 0,
    ) {
        require(destination.size >= width * height) { "a $width by $height view needs ${width * height} pixels" }
        val key = MapKey(projection, view, width, height, pictureWidth, pictureHeight)
        if (key != mapFor) build(key)
        // The whole sphere's left and right edges join, so a blend across the seam takes both.
        val wraps = projection.kind is io.github.yuroyami.kiteplayer.ProjectionKind.Equirectangular
        val lastColumn = pictureWidth - 1
        val lastRow = pictureHeight - 1
        val across = across
        val down = down
        for (index in 0 until width * height) {
            val x = across[index]
            if (x == MISSING) {
                destination[index] = background
                continue
            }
            val y = down[index]
            var x0 = x shr 8
            var x1 = x0 + 1
            if (wraps) {
                if (x0 < 0 || x0 > lastColumn) x0 = floorMod(x0, pictureWidth)
                if (x1 > lastColumn) x1 = floorMod(x1, pictureWidth)
            } else {
                if (x0 < 0) x0 = 0 else if (x0 > lastColumn) x0 = lastColumn
                if (x1 > lastColumn) x1 = lastColumn else if (x1 < 0) x1 = 0
            }
            var y0 = y shr 8
            var y1 = y0 + 1
            if (y0 < 0) y0 = 0 else if (y0 > lastRow) y0 = lastRow
            if (y1 > lastRow) y1 = lastRow else if (y1 < 0) y1 = 0
            val row0 = (top + y0) * stride + left
            val row1 = (top + y1) * stride + left
            val fx = x and 0xFF
            val upper = lerp(source[row0 + x0], source[row0 + x1], fx)
            val lower = lerp(source[row1 + x0], source[row1 + x1], fx)
            destination[index] = lerp(upper, lower, y and 0xFF)
        }
    }

    /**
     * The map for [key]: the lens's law for each pixel's centre, as [SphericalLens.pictureAt] reads
     * it. The law is worked out exactly on a grid of every [STEP]th pixel and blended in between,
     * which is a sixteenth of the arctangents, except in a cell that the seam of the whole sphere
     * or a pole cuts, or that reaches past a tile's edge, where it is worked out for every pixel.
     */
    private fun build(key: MapKey) {
        val width = key.width
        val height = key.height
        val count = width * height
        if (across.size != count) {
            across = IntArray(count)
            down = IntArray(count)
        }
        val law = Law(SphericalLens(key.projection, key.view, width.toFloat(), height.toFloat()), key.pictureWidth, key.pictureHeight)
        val columns = (width + STEP - 1) / STEP + 1
        val rows = (height + STEP - 1) / STEP + 1
        val gridAcross = IntArray(columns * rows)
        val gridDown = IntArray(columns * rows)
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                val at = row * columns + column
                law.at(column * STEP + 0.5, row * STEP + 0.5)
                gridAcross[at] = law.across
                gridDown[at] = law.down
            }
        }
        // A cell whose corners read more than a 32nd of the picture apart across, once the seam
        // is undone, is one a pole or the seam itself runs through.
        val spread = key.pictureWidth * 256 / 32
        val wholeWidth = key.pictureWidth * 256
        for (cellRow in 0 until rows - 1) {
            for (cellColumn in 0 until columns - 1) {
                val topLeft = cellRow * columns + cellColumn
                val a = gridAcross[topLeft]
                var b = gridAcross[topLeft + 1]
                var c = gridAcross[topLeft + columns]
                var d = gridAcross[topLeft + columns + 1]
                val fromColumn = cellColumn * STEP
                val fromRow = cellRow * STEP
                val toColumn = minOf(fromColumn + STEP, width)
                val toRow = minOf(fromRow + STEP, height)
                var exact = a == MISSING || b == MISSING || c == MISSING || d == MISSING
                if (!exact && law.wraps) {
                    b = nearest(b, a, wholeWidth)
                    c = nearest(c, a, wholeWidth)
                    d = nearest(d, a, wholeWidth)
                }
                if (!exact) exact = maxOf(maxOf(a, b), maxOf(c, d)) - minOf(minOf(a, b), minOf(c, d)) > spread
                if (exact) {
                    for (row in fromRow until toRow) {
                        for (column in fromColumn until toColumn) {
                            law.at(column + 0.5, row + 0.5)
                            across[row * width + column] = law.across
                            down[row * width + column] = law.down
                        }
                    }
                    continue
                }
                val downA = gridDown[topLeft]
                val downB = gridDown[topLeft + 1]
                val downC = gridDown[topLeft + columns]
                val downD = gridDown[topLeft + columns + 1]
                for (row in fromRow until toRow) {
                    val fy = row - fromRow
                    for (column in fromColumn until toColumn) {
                        val fx = column - fromColumn
                        val index = row * width + column
                        across[index] = mix(a, b, c, d, fx, fy)
                        down[index] = mix(downA, downB, downC, downD, fx, fy)
                    }
                }
            }
        }
        mapFor = key
        builds++
    }

    /**
     * The lens's law for one point, kept in fields so a map of a million pixels makes no object per
     * pixel. [across] and [down] are in 256ths of a picture pixel from the first pixel's centre, or
     * [MISSING] where a tile has no picture.
     */
    private class Law(private val lens: SphericalLens, private val pictureWidth: Int, private val pictureHeight: Int) {
        private val m = lens.rotation
        private val scale = lens.scale
        private val halfWidth = lens.width / 2.0
        private val halfHeight = lens.height / 2.0
        val wraps: Boolean = lens.wraps
        var across = 0
        var down = 0

        fun at(x: Double, y: Double) {
            val rayX = scale * (x - halfWidth)
            val rayY = scale * (halfHeight - y)
            val dx = m[0] * rayX + m[1] * rayY - m[2]
            val dy = m[3] * rayX + m[4] * rayY - m[5]
            val dz = m[6] * rayX + m[7] * rayY - m[8]
            val longitude = atan2(dx, -dz)
            val latitude = atan2(dy, sqrt(dx * dx + dz * dz))
            var shareAcross = 0.5 + longitude / (2.0 * PI)
            val shareDown = 0.5 - latitude / PI
            if (wraps) shareAcross -= floor(shareAcross)
            val u = (shareAcross - lens.pictureLeft) / lens.pictureWidth
            val v = (shareDown - lens.pictureTop) / lens.pictureHeight
            if (u < 0.0 || u > 1.0 || v < 0.0 || v > 1.0) {
                across = MISSING
                down = MISSING
            } else {
                // A pixel's centre is half a pixel in, so a share of 0 reads the edge of the first.
                across = floor((u * pictureWidth - 0.5) * 256.0).toInt()
                down = floor((v * pictureHeight - 0.5) * 256.0).toInt()
            }
        }
    }

    private data class MapKey(
        val projection: VideoProjection,
        val view: SphericalView,
        val width: Int,
        val height: Int,
        val pictureWidth: Int,
        val pictureHeight: Int,
    )

    companion object {
        /**
         * The size to draw a view of [view] into [projection]'s picture, [pictureWidth] pixels wide,
         * for an area of [width] by [height]: the area's own, or smaller in proportion when the
         * picture has fewer pixels across the field of view than the area has across its shorter
         * side, which it spans. Drawn larger, the view would only blend the same pixels more finely,
         * which a bilinear scale of the smaller view onto the area does as well, for a fraction of
         * the cost: a 1920 pixel wide picture at the default 80 degrees fills 427 pixels.
         */
        fun viewSize(width: Int, height: Int, pictureWidth: Int, projection: VideoProjection, view: SphericalView): Pair<Int, Int> {
            val tile = projection.kind as? io.github.yuroyami.kiteplayer.ProjectionKind.EquirectangularTile
            val share = tile?.let { 1.0 - it.left - it.right }?.takeIf { it > 0.0 } ?: 1.0
            val pixelsPerDegree = pictureWidth / (360.0 * share)
            val needed = kotlin.math.ceil(view.fieldOfView * pixelsPerDegree).toInt().coerceAtLeast(1)
            val shorter = minOf(width, height)
            if (needed >= shorter) return width to height
            val ratio = needed.toDouble() / shorter
            return (width * ratio).toInt().coerceAtLeast(1) to (height * ratio).toInt().coerceAtLeast(1)
        }

        private const val MISSING = Int.MIN_VALUE

        /** The grid the law is worked out on exactly, in pixels. */
        private const val STEP = 4

        private fun floorMod(value: Int, by: Int): Int {
            val rest = value % by
            return if (rest < 0) rest + by else rest
        }

        /** [value] moved by whole turns of [wholeWidth] to the side of [near] it is closest to. */
        private fun nearest(value: Int, near: Int, wholeWidth: Int): Int = when {
            value - near > wholeWidth / 2 -> value - wholeWidth
            near - value > wholeWidth / 2 -> value + wholeWidth
            else -> value
        }

        /** The corners of a cell of the grid blended at ([fx], [fy]) pixels into it. */
        private fun mix(a: Int, b: Int, c: Int, d: Int, fx: Int, fy: Int): Int {
            val upper = a * (STEP - fx) + b * fx
            val lower = c * (STEP - fx) + d * fx
            return (upper * (STEP - fy) + lower * fy).floorDiv(STEP * STEP)
        }

        /**
         * Two `0xRRGGBB` colours blended by [f] 256ths of the way from [a] to [b], red and blue in one
         * multiply and green in another. Equal colours come back exactly.
         */
        private fun lerp(a: Int, b: Int, f: Int): Int {
            val g = 256 - f
            val redBlue = (((a and 0xFF00FF) * g + (b and 0xFF00FF) * f) ushr 8) and 0xFF00FF
            val green = (((a and 0x00FF00) * g + (b and 0x00FF00) * f) ushr 8) and 0x00FF00
            return redBlue or green
        }
    }
}
