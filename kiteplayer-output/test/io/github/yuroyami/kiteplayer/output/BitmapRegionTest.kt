package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.spi.OverlayImage
import io.github.yuroyami.kiteplayer.subtitle.BitmapRegion
import io.github.yuroyami.kiteplayer.subtitle.RgbaBitmap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** How a pre-rendered subtitle region lands on the viewport, whatever size its canvas was. */
class BitmapRegionTest {
    /** Premultiplied grey at half alpha, every byte 0x7F. */
    private fun solid(width: Int, height: Int) = RgbaBitmap(width, height, ByteArray(width * height * 4) { 0x7F })

    private fun region(x: Int, y: Int, width: Int, height: Int, canvasWidth: Int, canvasHeight: Int) =
        BitmapRegion(x, y, width, height, canvasWidth, canvasHeight, solid(width, height))

    private fun OverlayImage.box() = listOf(x, y, bitmap.width, bitmap.height)

    @Test
    fun aRegionScalesItsOriginAndItsExtentTogether() {
        assertEquals(listOf(200, 100, 8, 8), assertNotNull(regionImage(region(100, 50, 4, 4, 320, 180), 640, 360)).box())
        assertEquals(listOf(50, 25, 2, 2), assertNotNull(regionImage(region(100, 50, 4, 4, 640, 360), 320, 180)).box())
    }

    @Test
    fun atTheAuthoredSizeThePixelsPassThroughUntouched() {
        val authored = region(10, 20, 4, 4, 640, 360)
        val image = assertNotNull(regionImage(authored, 640, 360))
        assertSame(authored.bitmap, image.bitmap)
        assertEquals(listOf(10, 20, 4, 4), image.box())
    }

    @Test
    fun regionsThatMeetOnTheCanvasStillMeetOnTheViewport() {
        // Uneven widths on a canvas that does not divide the viewport evenly.
        val left = assertNotNull(regionImage(region(0, 0, 3, 5, 7, 7), 10, 10))
        val right = assertNotNull(regionImage(region(3, 0, 4, 5, 7, 7), 10, 10))
        assertEquals(left.x + left.bitmap.width, right.x, "a gap or an overlap opened between the two")
        assertEquals(10, right.x + right.bitmap.width)
    }

    @Test
    fun aSolidRegionKeepsItsValueWhenScaled() {
        for ((width, height) in listOf(160 to 90, 640 to 360, 1000 to 700)) {
            val image = assertNotNull(regionImage(region(100, 50, 16, 8, 320, 180), width, height))
            assertTrue(image.bitmap.pixels.all { it == 0x7F.toByte() }, "a solid region changed value at $width by $height")
        }
    }

    @Test
    fun onlyThePartInsideTheViewportIsKept() {
        // Scaled twice over, this region would reach from (600, 300) to (800, 500).
        assertEquals(listOf(600, 300, 40, 60), assertNotNull(regionImage(region(300, 150, 100, 100, 320, 180), 640, 360)).box())
        assertNull(regionImage(region(400, 200, 10, 10, 320, 180), 640, 360), "a region wholly off the viewport")
    }

    @Test
    fun atTheAuthoredSizeOnlyThePartInsideTheViewportIsCopiedOut() {
        val image = assertNotNull(regionImage(region(600, 340, 100, 50, 640, 360), 640, 360))
        assertEquals(listOf(600, 340, 40, 20), image.box())
        assertNull(regionImage(region(700, 0, 10, 10, 640, 360), 640, 360), "a region wholly off the viewport")
    }

    @Test
    fun aRegionNearTheEndOfIntIsOffTheViewportAndNotAnOverflow() {
        assertNull(regionImage(region(Int.MAX_VALUE - 5, 0, 10, 10, 640, 360), 640, 360))
        assertNull(regionImage(region(Int.MIN_VALUE, Int.MIN_VALUE, 10, 10, 640, 360), 640, 360))
    }

    @Test
    fun aRegionWhoseResamplingCostsMoreThanTheBudgetIsRefusedBeforeAnyWork() {
        // A strip one pixel wide on a canvas one pixel wide spreads across the whole width, and all
        // 8192 of its rows are read to make 360: far more than four viewports of work.
        val strip = BitmapRegion(0, 0, 1, 8192, 1, 8192, solid(1, 8192))
        val budget = OverlayPixels(640, 360)
        assertNull(regionImage(strip, 640, 360, budget))
        assertTrue(budget.exhausted)
    }

    @Test
    fun aSteepReductionKeepsASolidValue() {
        // 200 source rows for each output row is past the rows the resampler keeps at once. The
        // region is small on a large viewport, so its work fits the budget.
        val tall = BitmapRegion(0, 0, 4, 4000, 120, 216_000, solid(4, 4000))
        val image = assertNotNull(regionImage(tall, 1920, 1080))
        assertEquals(listOf(0, 0, 64, 20), image.box())
        assertTrue(image.bitmap.pixels.all { it == 0x7F.toByte() }, "a steep reduction changed a solid value")
    }

    @Test
    fun upscalingBlendsNeighbouringRowsByTheirDistance() {
        // Two rows, transparent over opaque white, grown to four: 0, a quarter, three quarters, all.
        val pixels = ByteArray(2 * 4) { if (it < 4) 0 else 0xFF.toByte() }
        val grown = assertNotNull(regionImage(BitmapRegion(0, 0, 1, 2, 1, 2, RgbaBitmap(1, 2, pixels)), 1, 4))
        val alpha = (0 until 4).map { grown.bitmap.pixels[it * 4 + 3].toInt() and 0xFF }
        assertEquals(listOf(0, 64, 191, 255), alpha)
    }
}
