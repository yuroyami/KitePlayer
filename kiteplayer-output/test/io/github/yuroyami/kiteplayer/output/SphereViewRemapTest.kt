package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.KitePlayerInternalApi
import io.github.yuroyami.kiteplayer.ProjectionKind
import io.github.yuroyami.kiteplayer.SphericalView
import io.github.yuroyami.kiteplayer.VideoProjection
import io.github.yuroyami.kiteplayer.spi.SphericalLens
import kotlin.math.abs
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The view into a 360 degree picture drawn on the processor (#587), compared with [SphericalLens],
 * the law every renderer draws by, wherever the picture is one colour around the point the law
 * reads, with the band checks of the Compose path's own test.
 */
@OptIn(KitePlayerInternalApi::class)
class SphereViewRemapTest {

    private val whole = VideoProjection(ProjectionKind.Equirectangular)
    private val tile = VideoProjection(ProjectionKind.EquirectangularTile(left = 0.25, top = 0.0, right = 0.25, bottom = 0.0))

    private fun draw(picture: BandPicture, projection: VideoProjection, view: SphericalView, width: Int, height: Int, remap: SphereViewRemap = SphereViewRemap()): IntArray {
        val out = IntArray(width * height)
        remap.draw(picture.pixels, picture.width, 0, 0, picture.width, picture.height, projection, view, out, width, height, background = BACKGROUND)
        return out
    }

    @Test
    fun eachViewDrawsWhatTheLensReads() {
        val picture = BandPicture.whole()
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
            assertMatchesLens(picture, whole, view, width = 90, height = 160)
        }
        val posed = VideoProjection(ProjectionKind.Equirectangular, yaw = 30.0, pitch = 10.0, roll = -5.0)
        assertMatchesLens(picture, posed, SphericalView(yaw = 200f, pitch = -20f, roll = 10f), width = 160, height = 90)
    }

    @Test
    fun aTileDrawsItsPartAndTheBackgroundBeyondIt() {
        val picture = BandPicture.tile()
        assertMatchesLens(picture, tile, SphericalView(), width = 160, height = 90)
        assertMatchesLens(picture, tile, SphericalView(yaw = 90f), width = 160, height = 90)
        val behind = draw(picture, tile, SphericalView(yaw = 180f), 160, 90)
        assertTrue(behind.all { it == BACKGROUND }, "the view behind a tile drew a picture it does not have")
    }

    @Test
    fun eachViewLooksWhereItsTurnsPointIt() {
        val picture = BandPicture.whole()
        val centre = mapOf(
            SphericalView() to BandPicture.RED,
            SphericalView(yaw = 90f) to BandPicture.GREEN,
            SphericalView(yaw = -90f) to BandPicture.WHITE,
            SphericalView(yaw = 45f) to BandPicture.YELLOW,
            SphericalView(yaw = -45f) to BandPicture.ORANGE,
            // Straight behind, where the picture's left and right edges join.
            SphericalView(yaw = 180f) to BandPicture.BLUE,
            SphericalView(pitch = 90f) to BandPicture.TOP,
            SphericalView(pitch = -90f) to BandPicture.BOTTOM,
        )
        for ((view, colour) in centre) {
            assertEquals(colour, draw(picture, whole, view, 160, 90)[45 * 160 + 80], "the centre of $view")
        }
        val rolled = draw(picture, whole, SphericalView(roll = 90f, fieldOfView = 120f), 160, 90)
        assertEquals(BandPicture.BOTTOM, rolled[45 * 160 + 158], "the right edge of a view rolled clockwise")
        assertEquals(BandPicture.TOP, rolled[45 * 160 + 1], "the left edge of a view rolled clockwise")
    }

    @Test
    fun aCroppedFrameIsReadFromItsShownPartAlone() {
        val picture = BandPicture.whole()
        // The same picture inside a frame with 8 columns and 4 rows of noise around it.
        val stride = picture.width + 16
        val framed = IntArray(stride * (picture.height + 8)) { 0x123456 }
        for (row in 0 until picture.height) picture.pixels.copyInto(framed, (row + 4) * stride + 8, row * picture.width, (row + 1) * picture.width)
        val view = SphericalView(yaw = 170f, pitch = 20f)
        val out = IntArray(160 * 90)
        SphereViewRemap().draw(framed, stride, 8, 4, picture.width, picture.height, whole, view, out, 160, 90, BACKGROUND)
        assertContentEquals(draw(picture, whole, view, 160, 90), out, "the crop's border leaked into the view")
    }

    @Test
    fun theMapIsBuiltAgainOnlyWhenTheViewOrASizeChanges() {
        val picture = BandPicture.whole()
        val remap = SphereViewRemap()
        repeat(3) { draw(picture, whole, SphericalView(yaw = 10f), 160, 90, remap) }
        assertEquals(1, remap.builds, "each new picture of one view reads the same map")
        draw(picture, whole, SphericalView(yaw = 11f), 160, 90, remap)
        draw(picture, whole, SphericalView(yaw = 11f), 200, 90, remap)
        assertEquals(3, remap.builds)
    }

    /**
     * Compares every pixel the lens reads from a flat part of the picture, one colour all around the
     * point it reads, so the blend cannot matter. Where a tile has no picture, the background shows.
     */
    private fun assertMatchesLens(picture: BandPicture, projection: VideoProjection, view: SphericalView, width: Int, height: Int) {
        val drawn = draw(picture, projection, view, width, height)
        val lens = SphericalLens(projection, view, width.toFloat(), height.toFloat())
        var compared = 0
        val failures = mutableListOf<String>()
        var wrong = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val actual = drawn[y * width + x]
                val point = lens.pictureAt(x + 0.5, y + 0.5)
                val expected = if (point == null) {
                    BACKGROUND
                } else {
                    picture.flatAround(point.across * picture.width, point.down * picture.height, lens.wraps) ?: continue
                }
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

    private fun close(actual: Int, expected: Int): Boolean = (0..2).all { channel ->
        abs((actual shr (8 * channel) and 0xFF) - (expected shr (8 * channel) and 0xFF)) <= 1
    }

    private fun hex(rgb: Int): String = "#" + (rgb and 0xFFFFFF).toString(16).padStart(6, '0')

    private companion object {
        /** A colour no band has, so a pixel drawn beyond a tile is told apart from the picture. */
        const val BACKGROUND = 0x010203
    }
}

/**
 * An equirectangular picture of eight vertical bands, each 45 degrees wide and centred on a whole
 * multiple of 45 degrees, under a dark band along the top and over another along the bottom, each
 * 22.5 degrees high, as `SphereViewDrawTest` and `scripts/testmedia.sh` draw it. Pixels are
 * `0xRRGGBB`.
 */
internal class BandPicture private constructor(val width: Int, val height: Int, val pixels: IntArray) {

    /** The colour at ([x], [y]) when every pixel within two of it is that colour, else null. */
    fun flatAround(x: Double, y: Double, wraps: Boolean): Int? {
        var found: Int? = null
        for (dy in -2..2) {
            for (dx in -2..2) {
                var column = floor(x).toInt() + dx
                val row = floor(y).toInt() + dy
                if (row !in 0 until height) continue
                if (wraps) column = ((column % width) + width) % width else if (column !in 0 until width) continue
                val colour = pixels[row * width + column]
                if (found == null) found = colour else if (found != colour) return null
            }
        }
        return found
    }

    companion object {
        const val BLUE: Int = 0x0000FF
        const val MAGENTA: Int = 0xFF00FF
        const val WHITE: Int = 0xFFFFFF
        const val ORANGE: Int = 0xFF8000
        const val RED: Int = 0xFF0000
        const val YELLOW: Int = 0xFFFF00
        const val GREEN: Int = 0x00FF00
        const val CYAN: Int = 0x00FFFF
        const val TOP: Int = 0x303030
        const val BOTTOM: Int = 0x603000

        private val BANDS = intArrayOf(BLUE, MAGENTA, WHITE, ORANGE, RED, YELLOW, GREEN, CYAN, BLUE)

        /** The whole sphere, 512 by 256. */
        fun whole(): BandPicture = BandPicture(512, 256, IntArray(512 * 256) { index -> colourAt(index % 512, index / 512) })

        /** The middle half of the whole sphere's width, from -90 to 90 degrees, and all of its height. */
        fun tile(): BandPicture = BandPicture(256, 256, IntArray(256 * 256) { index -> colourAt(128 + index % 256, index / 256) })

        private fun colourAt(x: Int, y: Int): Int = when {
            y < 32 -> TOP
            y >= 224 -> BOTTOM
            else -> BANDS[(x + 32) / 64]
        }
    }
}
