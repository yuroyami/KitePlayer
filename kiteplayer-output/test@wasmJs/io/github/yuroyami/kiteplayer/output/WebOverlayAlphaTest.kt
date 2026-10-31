package io.github.yuroyami.kiteplayer.output

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The overlay is premultiplied and an `ImageData` holds straight alpha, so the bytes the web canvas
 * renderer hands `putImageData` are divided by their alpha first (#607). Without that, a white edge
 * at half alpha drew at a quarter of its light.
 */
class WebOverlayAlphaTest {

    private fun straight(vararg premultiplied: Int): List<Int> {
        val bytes = ByteArray(premultiplied.size) { premultiplied[it].toByte() }
        return straightAlphaLatin1(bytes, bytes.size).map { it.code }
    }

    @Test
    fun aHalfTransparentWhiteEdgeIsWhiteAtHalfAlpha() {
        assertEquals(listOf(255, 255, 255, 128), straight(128, 128, 128, 128))
    }

    @Test
    fun aHalfTransparentYellowBoxKeepsItsColour() {
        assertEquals(listOf(255, 255, 0, 128), straight(128, 128, 0, 128))
        assertEquals(listOf(128, 64, 0, 128), straight(64, 32, 0, 128))
    }

    @Test
    fun opaqueAndClearPixelsAreUnchanged() {
        assertEquals(listOf(12, 200, 255, 255), straight(12, 200, 255, 255))
        assertEquals(listOf(0, 0, 0, 0), straight(0, 0, 0, 0))
    }

    @Test
    fun aColourPastItsAlphaIsHeldAtFullScale() {
        // Not a valid premultiplied pixel, but a rasterizer's rounding can come near it.
        assertEquals(listOf(255, 0, 0, 10), straight(11, 0, 0, 10))
    }

    @Test
    fun everyByteOfAnOpaqueRampCrossesUnchanged() {
        // The Latin-1 crossing must carry every value from 0 to 255 (the ramp test of the web path).
        val ramp = IntArray(256 * 4) { if (it % 4 == 3) 255 else (it / 4) }
        assertEquals(ramp.toList(), straight(*ramp))
    }
}
