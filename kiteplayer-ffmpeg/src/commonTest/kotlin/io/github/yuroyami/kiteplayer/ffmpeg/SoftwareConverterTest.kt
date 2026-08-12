package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.spi.ChromaLocation
import io.github.yuroyami.kiteplayer.spi.ColorMatrix
import io.github.yuroyami.kiteplayer.spi.ColorPrimaries
import io.github.yuroyami.kiteplayer.spi.ColorSpaceInfo
import io.github.yuroyami.kiteplayer.spi.ColorTransfer
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import kotlin.test.Test
import kotlin.test.assertContentEquals

class SoftwareConverterTest {

    @Test
    fun packedRedBlueOrderAndOpaqueRgb24() {
        val rgba = byteArrayOf(1, 2, 3, 4, 10, 20, 30, 40)
        assertContentEquals(rgba, convert(rgba, 2, 1, PlayerPixelFormat.Rgba, bt709(fullRange = true)))
        assertContentEquals(
            rgba,
            convert(byteArrayOf(3, 2, 1, 4, 30, 20, 10, 40), 2, 1, PlayerPixelFormat.Bgra, bt709(true)),
        )
        assertContentEquals(
            byteArrayOf(1, 2, 3, -1, 10, 20, 30, -1),
            convert(byteArrayOf(1, 2, 3, 10, 20, 30), 2, 1, PlayerPixelFormat.Rgb24, bt709(true)),
        )
    }

    @Test
    fun planarAndSemiplanarLayoutsMatch() {
        val planar = byteArrayOf(16, 82, 145.toByte(), 235.toByte(), 90, 240.toByte())
        val semiplanar = byteArrayOf(16, 82, 145.toByte(), 235.toByte(), 90, 240.toByte())
        val expected = goldenRedRamp()
        assertContentEquals(expected, convert(planar, 2, 2, PlayerPixelFormat.Yuv420p, bt709(false)))
        assertContentEquals(expected, convert(semiplanar, 2, 2, PlayerPixelFormat.Nv12, bt709(false)))
    }

    @Test
    fun tenBitLowAndHighAlignmentMatchEightBitMagnitudes() {
        val lowAligned = words(64, 328, 580, 940, 360, 960)
        val highAligned = words(64 shl 6, 328 shl 6, 580 shl 6, 940 shl 6, 360 shl 6, 960 shl 6)
        val expected = goldenRedRamp()
        assertContentEquals(expected, convert(lowAligned, 2, 2, PlayerPixelFormat.Yuv420p10le, bt709(false)))
        assertContentEquals(expected, convert(highAligned, 2, 2, PlayerPixelFormat.P010le, bt709(false)))
    }

    @Test
    fun matrixAndRangeAreNotIgnored() {
        val bytes = byteArrayOf(82, 90, 240.toByte())
        val bt601 = convert(bytes, 1, 1, PlayerPixelFormat.Yuv444p, color(ColorMatrix.Bt601, false))
        val bt709 = convert(bytes, 1, 1, PlayerPixelFormat.Yuv444p, color(ColorMatrix.Bt709, false))
        val bt2020 = convert(bytes, 1, 1, PlayerPixelFormat.Yuv444p, color(ColorMatrix.Bt2020Ncl, false))
        val full = convert(bytes, 1, 1, PlayerPixelFormat.Yuv444p, color(ColorMatrix.Bt709, true))
        check(!bt601.contentEquals(bt709))
        check(!bt709.contentEquals(bt2020))
        check(!bt709.contentEquals(full))
    }

    @Test
    fun chromaSitingUsesTheSameNearestNeighbourRule() {
        val bytes = byteArrayOf(16, 82, 145.toByte(), 235.toByte(), 90, 240.toByte())
        val left = convert(bytes, 2, 2, PlayerPixelFormat.Yuv420p, bt709(false, ChromaLocation.Left))
        val centre = convert(bytes, 2, 2, PlayerPixelFormat.Yuv420p, bt709(false, ChromaLocation.Center))
        assertContentEquals(left, centre)
    }

    private fun convert(
        bytes: ByteArray,
        width: Int,
        height: Int,
        format: PlayerPixelFormat,
        color: ColorSpaceInfo,
    ): ByteArray = tightlyPackedToRgba(bytes, width, height, format, color)

    private fun bt709(
        fullRange: Boolean,
        chromaLocation: ChromaLocation = ChromaLocation.Left,
    ): ColorSpaceInfo = ColorSpaceInfo(
        matrix = ColorMatrix.Bt709,
        primaries = ColorPrimaries.Bt709,
        transfer = ColorTransfer.Bt709,
        fullRange = fullRange,
        chromaLocation = chromaLocation,
    )

    private fun color(matrix: ColorMatrix, fullRange: Boolean): ColorSpaceInfo =
        bt709(fullRange).copy(matrix = matrix)

    private fun words(vararg values: Int): ByteArray = ByteArray(values.size * 2).also { bytes ->
        values.forEachIndexed { index, value ->
            bytes[index * 2] = value.toByte()
            bytes[index * 2 + 1] = (value shr 8).toByte()
        }
    }

    private fun goldenRedRamp(): ByteArray = byteArrayOf(
        201.toByte(), 0, 0, -1,
        -1, 25, 0, -1,
        -1, 99, 70, -1,
        -1, 203.toByte(), 175.toByte(), -1,
    )
}
