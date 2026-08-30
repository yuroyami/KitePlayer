package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.spi.ChromaLocation
import io.github.yuroyami.kiteplayer.spi.ColorMatrix
import io.github.yuroyami.kiteplayer.spi.ColorPrimaries
import io.github.yuroyami.kiteplayer.spi.ColorSpaceInfo
import io.github.yuroyami.kiteplayer.spi.ColorTransfer
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import kotlin.math.pow
import kotlin.math.roundToInt
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

    @Test
    fun aPqFrameToneMapsAndAnSdrFrameStaysBitExact() {
        val pq = ColorSpaceInfo(
            matrix = ColorMatrix.Bt2020Ncl,
            primaries = ColorPrimaries.Bt2020,
            transfer = ColorTransfer.Pq,
            fullRange = true,
        )
        // Full-range PQ grey Y=160, neutral chroma: about 314 nits, above the EETF knee
        // but well below peak, so the spline shape itself is what the assertion proves.
        val bytes = byteArrayOf(160.toByte(), 128.toByte(), 128.toByte())
        val mapped = convert(bytes, 1, 1, PlayerPixelFormat.Yuv444p, pq)

        // The law written out in ST 2084 and BT.2390 CONSTANTS, never through the production
        // functions: an expected value recomputed by the code under test could
        // not fail however wrong that code became. Same style as MetalFrameComposerTest.
        val expected = independentToneMappedGrey(160.0 / 255.0)
        for (channel in 0..2) {
            val got = mapped[channel].toInt() and 0xFF
            check(kotlin.math.abs(got - expected) <= 2) {
                "tone-mapped PQ grey expected about $expected, channel $channel was $got"
            }
        }
        check(mapped[3].toInt() == -1) { "alpha must stay opaque" }

        // Same electricals declared SDR must be untouched by the tone-map hook.
        val sdrColor = ColorSpaceInfo(
            matrix = ColorMatrix.Bt709,
            primaries = ColorPrimaries.Bt709,
            transfer = ColorTransfer.Bt709,
            fullRange = true,
        )
        val sdr = convert(bytes, 1, 1, PlayerPixelFormat.Yuv444p, sdrColor)
        check(!sdr.contentEquals(mapped)) { "an SDR declaration must skip the tone map entirely" }
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
    // ST 2084 and BT.2390 written as literals, deliberately independent of HdrToneMap: the
    // whole point of this expected value is that it can disagree with production.
    private fun pqEncode1(y: Double): Double {
        val p = y.coerceAtLeast(0.0).pow(0.1593017578125)
        return ((0.8359375 + 18.8515625 * p) / (1.0 + 18.6875 * p)).pow(78.84375)
    }

    private fun pqDecode1(e: Double): Double {
        val p = e.coerceAtLeast(0.0).pow(1.0 / 78.84375)
        return ((p - 0.8359375).coerceAtLeast(0.0) / (18.8515625 - 18.6875 * p)).pow(1.0 / 0.1593017578125)
    }

    private fun independentToneMappedGrey(electrical: Double): Int {
        val nits = pqDecode1(electrical) * 10000.0
        val srcPq = pqEncode1(1000.0 / 10000.0)
        val dstPq = pqEncode1(203.0 / 10000.0)
        val e1 = (pqEncode1(nits / 10000.0) / srcPq).coerceIn(0.0, 1.0)
        val maxLum = dstPq / srcPq
        val ks = 1.5 * maxLum - 0.5
        val e2 = if (e1 <= ks) e1 else {
            val t = (e1 - ks) / (1.0 - ks)
            val t2 = t * t
            val t3 = t2 * t
            (2 * t3 - 3 * t2 + 1) * ks + (t3 - 2 * t2 + t) * (1 - ks) + (-2 * t3 + 3 * t2) * maxLum
        }
        val mapped = pqDecode1(e2 * srcPq) * 10000.0
        val ratio = if (nits > 1e-4) mapped / nits else 1.0
        val sdr = (nits * ratio / 203.0).coerceIn(0.0, 1.0)
        return (sdr.pow(1.0 / 2.2) * 255.0).roundToInt()
    }
}
