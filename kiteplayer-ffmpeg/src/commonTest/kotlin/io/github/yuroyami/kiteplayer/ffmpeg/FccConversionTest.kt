package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.spi.ColorMatrix
import io.github.yuroyami.kiteplayer.spi.ColorSpaceInfo
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An FCC frame converted with the FCC matrix rather than with BT.709.
 *
 * ITU-T H.273, MatrixCoefficients 4, has KR 0.30 and KB 0.11. The expected values are what ffmpeg
 * gives for the same studio-range sample with `in_color_matrix=fcc`. The software converters on
 * every platform share this table, so this runs wherever the common tests run.
 */
class FccConversionTest {

    private fun convert(y: Int, cb: Int, cr: Int, matrix: ColorMatrix): Triple<Int, Int, Int> {
        val rgba = tightlyPackedToRgba(
            bytes = byteArrayOf(y.toByte(), cb.toByte(), cr.toByte()),
            width = 1,
            height = 1,
            pixelFormat = PlayerPixelFormat.Yuv444p,
            colorSpace = ColorSpaceInfo(matrix = matrix, fullRange = false),
        )
        return Triple(rgba[0].toInt() and 0xFF, rgba[1].toInt() and 0xFF, rgba[2].toInt() and 0xFF)
    }

    @Test
    fun aSaturatedGreenKeepsItsGreen() {
        assertEquals(Triple(0, 254, 0), convert(145, 54, 34, ColorMatrix.Fcc))
    }

    @Test
    fun theSameSampleAsBt709LosesAboutFortyLevelsOfGreen() {
        // What FCC material used to look like: the BT.709 row was applied to it.
        assertEquals(Triple(0, 216, 0), convert(145, 54, 34, ColorMatrix.Bt709))
    }
}
