package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.spi.ColorMatrix
import io.github.yuroyami.kiteplayer.spi.ColorSpaceInfo
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A YCgCo frame converted as YCgCo rather than as BT.709.
 *
 * The four-coefficient form the converter used could not hold this transform at all: red reads
 * both chroma channels and so does blue, which a YCbCr matrix never does. So the matrix was
 * carried all the way to the converter, recognised, and then quietly replaced with BT.709.
 *
 * The expected values are the transform written out by hand, and they are what FFmpeg's own
 * scaler produces for the same input.
 */
class YCgCoConversionTest {

    private fun convert(y: Int, cg: Int, co: Int, matrix: ColorMatrix): Triple<Int, Int, Int> {
        val rgba = tightlyPackedToRgba(
            bytes = byteArrayOf(y.toByte(), cg.toByte(), co.toByte()),
            width = 1,
            height = 1,
            pixelFormat = PlayerPixelFormat.Yuv444p,
            colorSpace = ColorSpaceInfo(matrix = matrix, fullRange = true),
        )
        return Triple(rgba[0].toInt() and 0xFF, rgba[1].toInt() and 0xFF, rgba[2].toInt() and 0xFF)
    }

    @Test
    fun `a green sample keeps its green`() {
        // Y 128, Cg +32, Co 0. By hand: R = Y - Cg = 96, G = Y + Cg = 160, B = Y - Cg = 96.
        assertEquals(Triple(96, 160, 96), convert(128, 160, 128, ColorMatrix.YCgCo))
    }

    @Test
    fun `an orange sample splits red from blue`() {
        // Y 128, Cg 0, Co +32. R = Y + Co = 160, G = Y = 128, B = Y - Co = 96.
        assertEquals(Triple(160, 128, 96), convert(128, 128, 160, ColorMatrix.YCgCo))
    }

    @Test
    fun `a colourless sample stays grey`() {
        assertEquals(Triple(128, 128, 128), convert(128, 128, 128, ColorMatrix.YCgCo))
    }

    @Test
    fun `the same sample under BT 709 gives a different answer`() {
        // The proof the branch is reached at all: this is what YCgCo content used to look like.
        assertEquals(Triple(128, 122, 187), convert(128, 160, 128, ColorMatrix.Bt709))
    }

    @Test
    fun `the YCbCr matrices are unchanged by the wider form`() {
        // Widening the matrix flipped the sign of the two green terms, so every existing matrix is
        // re-checked against a value computed before the change.
        assertEquals(Triple(128, 117, 185), convert(128, 160, 128, ColorMatrix.Bt601))
        assertEquals(Triple(128, 123, 188), convert(128, 160, 128, ColorMatrix.Bt2020Ncl))
        assertEquals(Triple(128, 121, 186), convert(128, 160, 128, ColorMatrix.Smpte240m))
    }
}
