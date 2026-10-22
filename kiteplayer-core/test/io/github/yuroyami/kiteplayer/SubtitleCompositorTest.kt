package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.spi.OverlayImage
import io.github.yuroyami.kiteplayer.spi.SubtitleOverlay
import io.github.yuroyami.kiteplayer.subtitle.RgbaBitmap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Burning subtitles into a picture.
 *
 * The cue alpha contract is premultiplied end to end, and premultiplying again here is the classic
 * way to render white text grey. So the blend is checked against values worked out by hand rather
 * than against another copy of the same arithmetic.
 */
class SubtitleCompositorTest {

    private fun picture(width: Int, height: Int, fill: Int = 0) =
        ByteArray(width * height * 4) { fill.toByte() }

    private fun overlay(
        image: OverlayImage,
        width: Int = 4,
        height: Int = 4,
    ) = SubtitleOverlay(listOf(image), width, height, contentHash = 1L)

    private fun bitmap(width: Int, height: Int, vararg pixels: Int) =
        RgbaBitmap(width, height, ByteArray(pixels.size) { pixels[it].toByte() })

    private fun ByteArray.pixelAt(x: Int, y: Int, width: Int): List<Int> =
        (0 until 4).map { this[(y * width + x) * 4 + it].toInt() and 0xFF }

    @Test
    fun `an opaque pixel replaces what was under it`() {
        val rgba = picture(4, 4, fill = 0x40)
        overlay(OverlayImage(1, 1, bitmap(1, 1, 200, 100, 50, 255))).drawOver(rgba, 4, 4)
        assertEquals(listOf(200, 100, 50, 255), rgba.pixelAt(1, 1, 4))
    }

    @Test
    fun `a transparent pixel changes nothing`() {
        val rgba = picture(4, 4, fill = 0x40)
        overlay(OverlayImage(1, 1, bitmap(1, 1, 200, 100, 50, 0))).drawOver(rgba, 4, 4)
        assertEquals(listOf(0x40, 0x40, 0x40, 0x40), rgba.pixelAt(1, 1, 4))
    }

    @Test
    fun `a half transparent pixel is added rather than mixed`() {
        // Premultiplied: dst = src + dst * (1 - a). Colour is 100 over 200 at alpha 128, so
        // 100 + 200 * 127/255 = 100 + 100 = 200. Mixing instead of adding would give 150, which is
        // the mistake this guards. Alpha runs the same sum from its OWN source value: 128 + 100.
        val rgba = picture(1, 1, fill = 200)
        SubtitleOverlay(listOf(OverlayImage(0, 0, bitmap(1, 1, 100, 100, 100, 128))), 1, 1, 1L)
            .drawOver(rgba, 1, 1)
        assertEquals(listOf(200, 200, 200, 228), rgba.pixelAt(0, 0, 1))
    }

    @Test
    fun `a blend never runs past full scale`() {
        val rgba = picture(1, 1, fill = 255.toByte().toInt())
        SubtitleOverlay(listOf(OverlayImage(0, 0, bitmap(1, 1, 255, 255, 255, 200))), 1, 1, 1L)
            .drawOver(rgba, 1, 1)
        assertEquals(listOf(255, 255, 255, 255), rgba.pixelAt(0, 0, 1))
    }

    @Test
    fun `pixels outside the overlay are untouched`() {
        val rgba = picture(4, 4, fill = 0x40)
        overlay(OverlayImage(1, 1, bitmap(1, 1, 200, 100, 50, 255))).drawOver(rgba, 4, 4)
        for (y in 0 until 4) {
            for (x in 0 until 4) {
                if (x == 1 && y == 1) continue
                assertEquals(listOf(0x40, 0x40, 0x40, 0x40), rgba.pixelAt(x, y, 4), "at $x $y")
            }
        }
    }

    @Test
    fun `an image hanging off the edge is clipped and not wrapped`() {
        // Without the bounds check the right-hand column lands on the next row's first pixel,
        // which draws a stray mark on the far side of the picture.
        val rgba = picture(4, 4)
        SubtitleOverlay(
            listOf(OverlayImage(3, 3, bitmap(2, 2, 9, 9, 9, 255, 9, 9, 9, 255, 9, 9, 9, 255, 9, 9, 9, 255))),
            4,
            4,
            1L,
        ).drawOver(rgba, 4, 4)
        assertEquals(listOf(9, 9, 9, 255), rgba.pixelAt(3, 3, 4))
        assertTrue(rgba.pixelAt(0, 0, 4).all { it == 0 }, "a clipped pixel wrapped to the other side")
    }

    @Test
    fun `an overlay laid out for another size is refused`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            overlay(OverlayImage(0, 0, bitmap(1, 1, 1, 1, 1, 255)), width = 8, height = 8)
                .drawOver(picture(4, 4), 4, 4)
        }
        assertTrue("8x8" in failure.message.orEmpty(), "the refusal names both sizes: ${failure.message}")
    }

    @Test
    fun `a picture whose bytes do not match its size is refused`() {
        assertFailsWith<IllegalArgumentException> {
            overlay(OverlayImage(0, 0, bitmap(1, 1, 1, 1, 1, 255))).drawOver(ByteArray(10), 4, 4)
        }
    }
}
