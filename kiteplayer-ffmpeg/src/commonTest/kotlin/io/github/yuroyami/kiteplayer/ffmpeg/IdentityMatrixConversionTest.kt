package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.spi.ColorMatrix
import io.github.yuroyami.kiteplayer.spi.ColorSpaceInfo
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An Identity frame read as the green, blue and red planes it holds, and not converted with BT.709.
 *
 * Identity is matrix coefficients 0 in ISO/IEC 23091-2. The planes that hold Y, Cb and Cr in a YCbCr
 * frame hold G, B and R here, so there is no matrix to apply. Studio range scales all three planes
 * the way it scales luma, because each plane is a colour and none is a colour difference.
 */
class IdentityMatrixConversionTest {

    private fun convert(first: Int, second: Int, third: Int, fullRange: Boolean): Triple<Int, Int, Int> {
        val rgba = tightlyPackedToRgba(
            bytes = byteArrayOf(first.toByte(), second.toByte(), third.toByte()),
            width = 1,
            height = 1,
            pixelFormat = PlayerPixelFormat.Yuv444p,
            colorSpace = ColorSpaceInfo(matrix = ColorMatrix.Identity, fullRange = fullRange),
        )
        return Triple(rgba[0].toInt() and 0xFF, rgba[1].toInt() and 0xFF, rgba[2].toInt() and 0xFF)
    }

    @Test
    fun theThreePlanesAreGreenBlueAndRed() {
        // Three distinct values, so a swap of any two planes shows. BT.709 made this (153, 8, 25).
        assertEquals(Triple(200, 40, 120), convert(40, 120, 200, fullRange = true))
    }

    @Test
    fun studioRangeScalesEveryPlaneLikeLuma() {
        // 16 is black and 235 is white on every plane: (v - 16) * 255 / 219. Scaling the red plane
        // over chroma's 16 to 240 instead would put this red at 249.
        assertEquals(Triple(255, 0, 128), convert(16, 126, 235, fullRange = false))
    }
}
