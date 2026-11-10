package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.spi.ColorMatrix
import io.github.yuroyami.kiteplayer.spi.ColorPrimaries
import io.github.yuroyami.kiteplayer.spi.ColorSpaceInfo
import io.github.yuroyami.kiteplayer.spi.ColorTransfer
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A 10-bit HDR frame is tone mapped before any cut to 8 bits (#731). The converter dropped each
 * sample to 8 bits, ran the matrix on those codes and only then undid the curve from a byte, so
 * three PQ steps in four were lost and a slow gradient came out in bands.
 */
class HdrDeepSourceTest {

    private val pq = ColorSpaceInfo(
        matrix = ColorMatrix.Bt2020Ncl,
        primaries = ColorPrimaries.Bt2020,
        transfer = ColorTransfer.Pq,
        fullRange = false,
    )

    /** A one-row yuv420p10le frame of [count] greys, luma from [first] up by one code each. */
    private fun ramp10(first: Int, count: Int): ByteArray {
        val width = count
        val height = 2
        val chromaWidth = (width + 1) / 2
        val bytes = ByteArray(width * height * 2 + chromaWidth * 2 * 2)
        for (row in 0 until height) for (x in 0 until width) {
            val at = (row * width + x) * 2
            val value = first + x
            bytes[at] = (value and 0xFF).toByte()
            bytes[at + 1] = (value shr 8).toByte()
        }
        var at = width * height * 2
        repeat(chromaWidth * 2) {
            bytes[at] = 0x00
            bytes[at + 1] = 0x02 // 512, neutral chroma
            at += 2
        }
        return bytes
    }

    @Test
    fun thirtyTwoNeighbouringPqCodesKeepTheirSteps() {
        val count = 32
        val out = tightlyPackedToRgba(ramp10(400, count), count, 2, PlayerPixelFormat.Yuv420p10le, pq)
        val greens = (0 until count).map { out[it * 4 + 1].toInt() and 0xFF }
        assertTrue(greens.zipWithNext().all { (a, b) -> b >= a }, "the ramp fell: $greens")
        val levels = greens.distinct().size
        // An 8-bit cut leaves at most nine of the thirty-two codes apart.
        assertTrue(levels >= 16, "32 codes came out on $levels levels: $greens")
        assertEquals(-1, out[3].toInt(), "alpha is opaque")
    }

    @Test
    fun anEightBitHdrFrameKeepsTheBytesItHad() {
        // Nothing to keep past 8 bits, so the byte path and its answers stay as they were.
        val width = 4
        val bytes = ByteArray(width * 2 + 2 * 2) { if (it < width * 2) (100 + it).toByte() else 128.toByte() }
        val out = tightlyPackedToRgba(bytes, width, 2, PlayerPixelFormat.Yuv420p, pq)
        val expected = tightlyPackedToRgba(bytes, width, 2, PlayerPixelFormat.Yuv420p, pq.copy(transfer = ColorTransfer.Bt709))
        HdrToneMap.forColorSpaceOrNull(pq)!!.mapInPlace(expected)
        assertContentEquals(expected, out)
    }
}
