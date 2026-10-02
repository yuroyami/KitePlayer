@file:OptIn(ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.VideoSize
import io.github.yuroyami.kiteplayer.spi.ColorMatrix
import io.github.yuroyami.kiteplayer.spi.ColorPrimaries
import io.github.yuroyami.kiteplayer.spi.ColorSpaceInfo
import io.github.yuroyami.kiteplayer.spi.ColorTransfer
import io.github.yuroyami.kiteplayer.spi.HdrStaticMetadata
import io.github.yuroyami.kiteplayer.spi.HwSurfaceKind
import io.github.yuroyami.kiteplayer.spi.OverlayImage
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.spi.SubtitleOverlay
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import io.github.yuroyami.kiteplayer.subtitle.RgbaBitmap
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Metal.MTLCreateSystemDefaultDevice
import platform.Metal.MTLPixelFormatRGBA16Float
import platform.Metal.MTLRegionMake2D
import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * HDR on an extended-range target, with real Metal on the host (#68): a PQ picture becomes linear
 * light with 1.0 at reference white, highlights roll off at the display's headroom rather than at
 * white, a headroom of 1 matches the standard-range picture, and a subtitle's white lands on 1.0.
 */
class MetalExtendedRangeTest {

    private class TestFrame(
        width: Int,
        height: Int,
        override val colorSpace: ColorSpaceInfo,
        override val hdr: HdrStaticMetadata? = null,
    ) : VideoFrame {
        override val pts: Pts = Pts.Zero
        override val duration: Pts? = null
        override val size: VideoSize = VideoSize(width, height, 1, 1)
        override val pixelFormat: PlayerPixelFormat = PlayerPixelFormat.Nv12
        override val hardwareSurface: HwSurfaceKind? = null
        override val generation: Generation = Generation.Initial
        override fun close() = Unit
    }

    private val device = MTLCreateSystemDefaultDevice() ?: error("this host has no Metal device")
    private val extended = MetalFrameComposer(device, MTLPixelFormatRGBA16Float)
    private val standard = MetalFrameComposer(device)

    private val pq = ColorSpaceInfo(
        matrix = ColorMatrix.Bt2020Ncl,
        primaries = ColorPrimaries.Bt2020,
        transfer = ColorTransfer.Pq,
        fullRange = true,
    )

    /** A full-range grey: luma [y], neutral chroma. */
    private fun grey(y: Int): MetalPicture.SoftwarePlanes {
        val luma = ByteArray(SIZE * SIZE) { y.toByte() }
        val chroma = ByteArray(SIZE * SIZE / 2) { 128.toByte() }
        return MetalPicture.SoftwarePlanes(
            width = SIZE,
            height = SIZE,
            format = PlayerPixelFormat.Nv12,
            planes = listOf(
                MetalPicture.SoftwarePlanes.Plane(luma, SIZE, SIZE),
                MetalPicture.SoftwarePlanes.Plane(chroma, SIZE, SIZE / 2),
            ),
        )
    }

    /** The light at the centre of an extended-range render, one value per channel. */
    private fun renderLight(
        y: Int,
        headroom: Float,
        overlay: SubtitleOverlay? = null,
        colorSpace: ColorSpaceInfo = pq,
        hdr: HdrStaticMetadata? = null,
    ): FloatArray {
        val target = device.makeTargetTexture(SIZE, SIZE, MTLPixelFormatRGBA16Float)
        extended.encode(
            target, TestFrame(SIZE, SIZE, colorSpace, hdr), grey(y), overlay, SIZE, SIZE,
            toneMapped = true,
            extendedRangeHeadroom = headroom,
        ).waitUntilCompleted()
        val bytes = ByteArray(SIZE * SIZE * 8)
        bytes.usePinned { pinned ->
            target.getBytes(
                pinned.addressOf(0),
                bytesPerRow = (SIZE * 8).toULong(),
                fromRegion = MTLRegionMake2D(0u, 0u, SIZE.toULong(), SIZE.toULong()),
                mipmapLevel = 0u,
            )
        }
        val at = (SIZE / 2 * SIZE + SIZE / 2) * 8
        return FloatArray(3) { channel ->
            val low = bytes[at + channel * 2].toInt() and 0xFF
            val high = bytes[at + channel * 2 + 1].toInt() and 0xFF
            halfToFloat((high shl 8) or low)
        }
    }

    /** The standard-range render's centre byte, green channel. */
    private fun renderStandard(y: Int): Int {
        val target = device.makeTargetTexture(SIZE, SIZE)
        standard.encode(target, TestFrame(SIZE, SIZE, pq), grey(y), null, SIZE, SIZE, toneMapped = true)
            .waitUntilCompleted()
        val bytes = ByteArray(SIZE * SIZE * 4)
        bytes.usePinned { pinned ->
            target.getBytes(
                pinned.addressOf(0),
                bytesPerRow = (SIZE * 4).toULong(),
                fromRegion = MTLRegionMake2D(0u, 0u, SIZE.toULong(), SIZE.toULong()),
                mipmapLevel = 0u,
            )
        }
        return bytes[(SIZE / 2 * SIZE + SIZE / 2) * 4 + 1].toInt() and 0xFF
    }

    @Test
    fun aPqGreyBecomesItsOwnLightBelowTheHeadroom() {
        // Luma 160 of 255 is about 314 nits: under a 406 nit peak it passes the curve unchanged.
        for (y in listOf(80, 120, 160)) {
            val light = renderLight(y, headroom = 2f)
            val expected = expectedLight(y / 255.0, dstPeak = 406.0)
            light.forEach { got ->
                assertTrue(abs(got - expected) <= expected * 0.01 + 0.002, "luma $y gave $got light, expected $expected")
            }
        }
    }

    @Test
    fun aHighlightRollsOffAtTheHeadroomRatherThanAtWhite() {
        // Luma 235 is far above 1000 nits, so it lands on the peak: the headroom itself.
        val twice = renderLight(235, headroom = 2f)
        val fourTimes = renderLight(235, headroom = 4f)
        twice.forEach { assertTrue(abs(it - 2f) < 0.02f, "a headroom of 2 peaked at $it") }
        fourTimes.forEach { assertTrue(abs(it - 4f) < 0.04f, "a headroom of 4 peaked at $it") }
    }

    @Test
    fun aHeadroomOfOneMatchesTheStandardRangePicture() {
        for (y in listOf(60, 120, 160, 200, 235)) {
            val light = renderLight(y, headroom = 1f)[1]
            // The standard-range target holds the same light, gamma 2.2 encoded into eight bits.
            val byte = renderStandard(y)
            val low = ((byte - 1).coerceAtLeast(0) / 255.0).pow(2.2)
            val high = ((byte + 1).coerceAtMost(255) / 255.0).pow(2.2)
            assertTrue(light in low.toFloat()..high.toFloat(), "luma $y: extended $light, standard byte $byte")
        }
    }

    @Test
    fun aBrighterMasterKeepsHighlightsApartThatAThousandNitAssumptionFlattens() {
        // Full-range PQ greys at about 1000 and 2000 nits.
        val thousand = 192
        val twoThousand = 212
        val assumed = renderLight(thousand, headroom = 2f)[1] to renderLight(twoThousand, headroom = 2f)[1]
        assertTrue(abs(assumed.first - assumed.second) < 0.02f, "a 1000 nit assumption should flatten both, got $assumed")
        val master = HdrStaticMetadata(masteringMaxNits = 4000f, maxContentLightNits = 4000)
        val graded = renderLight(thousand, headroom = 2f, hdr = master)[1] to renderLight(twoThousand, headroom = 2f, hdr = master)[1]
        assertTrue(graded.second - graded.first > 0.05f, "a 4000 nit master kept them only $graded apart")
    }

    @Test
    fun subtitleWhiteLandsOnReferenceWhite() {
        val white = SubtitleOverlay(
            images = listOf(OverlayImage(x = 0, y = 0, bitmap = RgbaBitmap(SIZE, SIZE, ByteArray(SIZE * SIZE * 4) { 0xFF.toByte() }))),
            viewportWidth = SIZE,
            viewportHeight = SIZE,
            contentHash = 7L,
        )
        renderLight(235, headroom = 2f, overlay = white).forEach {
            assertTrue(abs(it - 1f) < 0.01f, "opaque subtitle white over a highlight gave $it, not 1.0")
        }
    }

    private fun pqEncode1(y: Double): Double {
        val p = y.coerceAtLeast(0.0).pow(0.1593017578125)
        return ((0.8359375 + 18.8515625 * p) / (1.0 + 18.6875 * p)).pow(78.84375)
    }

    private fun pqDecode1(e: Double): Double {
        val p = e.coerceAtLeast(0.0).pow(1.0 / 78.84375)
        return ((p - 0.8359375).coerceAtLeast(0.0) / (18.8515625 - 18.6875 * p)).pow(1.0 / 0.1593017578125)
    }

    /** BT.2390 EETF from 1000 nits to [dstPeak] on a PQ grey, divided by reference white. */
    private fun expectedLight(electrical: Double, dstPeak: Double): Double {
        val nits = pqDecode1(electrical) * 10000.0
        val srcPq = pqEncode1(1000.0 / 10000.0)
        val dstPq = pqEncode1(dstPeak / 10000.0)
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
        return nits * ratio / 203.0
    }

    private fun halfToFloat(bits: Int): Float {
        val exponent = (bits shr 10) and 0x1F
        val mantissa = bits and 0x3FF
        val magnitude = when (exponent) {
            0 -> mantissa / 1024f * 2f.pow(-14)
            31 -> if (mantissa == 0) Float.POSITIVE_INFINITY else Float.NaN
            else -> (1 + mantissa / 1024f) * 2f.pow(exponent - 15)
        }
        return if ((bits shr 15) and 1 == 1) -magnitude else magnitude
    }

    private companion object {
        const val SIZE = 16
    }
}
