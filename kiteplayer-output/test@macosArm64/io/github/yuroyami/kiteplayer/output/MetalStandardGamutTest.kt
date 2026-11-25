@file:OptIn(ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.VideoSize
import io.github.yuroyami.kiteplayer.spi.ColorMatrix
import io.github.yuroyami.kiteplayer.spi.ColorPrimaries
import io.github.yuroyami.kiteplayer.spi.ColorSpaceInfo
import io.github.yuroyami.kiteplayer.spi.ColorTransfer
import io.github.yuroyami.kiteplayer.spi.HwSurfaceKind
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Metal.MTLCreateSystemDefaultDevice
import platform.Metal.MTLPixelFormatRGBA8Unorm
import platform.Metal.MTLRegionMake2D
import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Standard range BT.2020 on a layer with no colour matching (#732), with real Metal on the host.
 * On iOS `tagsStandardRangeLayer` is false and the renderer asks the shader to convert the
 * primaries. These tests force that case on macOS through the composer's flag.
 */
class MetalStandardGamutTest {

    private class TestFrame(override val colorSpace: ColorSpaceInfo) : VideoFrame {
        override val pts: Pts = Pts.Zero
        override val duration: Pts? = null
        override val size: VideoSize = VideoSize(SIZE, SIZE, 1, 1)
        override val pixelFormat: PlayerPixelFormat = PlayerPixelFormat.Nv12
        override val hardwareSurface: HwSurfaceKind? = null
        override val generation: Generation = Generation.Initial
        override fun close() = Unit
    }

    private val device = MTLCreateSystemDefaultDevice() ?: error("this host has no Metal device")
    private val composer = MetalFrameComposer(device, MTLPixelFormatRGBA8Unorm)

    private fun sdr(primaries: ColorPrimaries) = ColorSpaceInfo(
        matrix = ColorMatrix.Bt2020Ncl,
        primaries = primaries,
        transfer = ColorTransfer.Bt709,
        fullRange = true,
    )

    private fun picture(y: Int, cb: Int, cr: Int): MetalPicture.SoftwarePlanes {
        val luma = ByteArray(SIZE * SIZE) { y.toByte() }
        val chroma = ByteArray(SIZE * SIZE / 2) { if (it % 2 == 0) cb.toByte() else cr.toByte() }
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

    /** The centre pixel's red, green and blue bytes. */
    private fun render(
        colorSpace: ColorSpaceInfo,
        picture: MetalPicture.SoftwarePlanes,
        converts: Boolean,
        toneMapped: Boolean = true,
    ): IntArray {
        val target = device.makeTargetTexture(SIZE, SIZE, MTLPixelFormatRGBA8Unorm)
        composer.encode(
            target, TestFrame(colorSpace), picture, null, SIZE, SIZE,
            toneMapped = toneMapped,
            convertsStandardGamut = converts,
        ).waitUntilCompleted()
        val bytes = ByteArray(SIZE * SIZE * 4)
        bytes.usePinned { pinned ->
            target.getBytes(
                pinned.addressOf(0),
                bytesPerRow = (SIZE * 4).toULong(),
                fromRegion = MTLRegionMake2D(0u, 0u, SIZE.toULong(), SIZE.toULong()),
                mipmapLevel = 0u,
            )
        }
        val at = (SIZE / 2 * SIZE + SIZE / 2) * 4
        return IntArray(3) { bytes[at + it].toInt() and 0xFF }
    }

    @Test
    fun aBt2020ColourMovesToItsBt709Values() {
        val green = picture(y = 150, cb = 90, cr = 100)
        val raw = render(sdr(ColorPrimaries.Bt2020), green, converts = false)
        val converted = render(sdr(ColorPrimaries.Bt2020), green, converts = true)
        // Compared as light: the readback of raw is rounded to 8 bits, and near black the curve
        // turns half a step of that rounding into several steps of output.
        val expected = expectedBt709Light(raw)
        for (channel in 0 until 3) {
            val got = toLight(converted[channel] / 255.0)
            assertTrue(
                abs(got - expected[channel]) <= 0.006,
                "channel $channel: got ${converted.toList()} ($got), expected light ${expected.toList()} from ${raw.toList()}",
            )
        }
        // A saturated BT.2020 green is outside BT.709, so its red falls and its green rises.
        assertTrue(converted[0] < raw[0], "red should fall: ${raw.toList()} -> ${converted.toList()}")
        assertTrue(converted[1] > raw[1], "green should rise: ${raw.toList()} -> ${converted.toList()}")
    }

    @Test
    fun aGreyKeepsItsValue() {
        for (y in listOf(16, 80, 128, 200, 235)) {
            val grey = picture(y, 128, 128)
            val raw = render(sdr(ColorPrimaries.Bt2020), grey, converts = false)
            val converted = render(sdr(ColorPrimaries.Bt2020), grey, converts = true)
            for (channel in 0 until 3) {
                assertTrue(abs(converted[channel] - raw[channel]) <= 1, "luma $y: ${raw.toList()} -> ${converted.toList()}")
            }
        }
    }

    @Test
    fun bt709PrimariesAreLeftBitExact() {
        val colour = picture(y = 150, cb = 90, cr = 100)
        assertContentEquals(
            render(sdr(ColorPrimaries.Bt709), colour, converts = false),
            render(sdr(ColorPrimaries.Bt709), colour, converts = true),
        )
    }

    @Test
    fun theRawReaderIsLeftBitExact() {
        val colour = picture(y = 150, cb = 90, cr = 100)
        val raw = render(sdr(ColorPrimaries.Bt2020), colour, converts = false)
        // The offscreen reader reads the stored picture, so the flag alone changes nothing there.
        assertContentEquals(raw, render(sdr(ColorPrimaries.Bt2020), colour, converts = true, toneMapped = false))
    }

    @Test
    fun theFlagDoesNotChangeTheHdrToneMap() {
        val pq = sdr(ColorPrimaries.Bt2020).copy(transfer = ColorTransfer.Pq)
        val colour = picture(y = 150, cb = 90, cr = 100)
        assertContentEquals(render(pq, colour, converts = false), render(pq, colour, converts = true))
    }

    @Test
    fun theUniformsNameTheThirdModeOnlyForStandardRangeBt2020() {
        val bt2020 = sdr(ColorPrimaries.Bt2020)
        assertEquals(3, packToneUniforms(bt2020, convertsStandardGamut = true)[0].toRawBits())
        assertTrue(packToneUniforms(bt2020) === DISABLED_TONE_UNIFORMS, "off unless asked")
        assertTrue(
            packToneUniforms(sdr(ColorPrimaries.Bt709), convertsStandardGamut = true) === DISABLED_TONE_UNIFORMS,
            "BT.709 needs no conversion",
        )
        assertEquals(1, packToneUniforms(bt2020.copy(transfer = ColorTransfer.Pq), convertsStandardGamut = true)[0].toRawBits())
    }

    /** The light mode 3 should write for [raw]: the sRGB curve to light, then the BT.2020 matrix. */
    private fun expectedBt709Light(raw: IntArray): DoubleArray {
        val light = raw.map { toLight(it / 255.0) }
        val out = doubleArrayOf(
            1.6605 * light[0] - 0.5876 * light[1] - 0.0728 * light[2],
            -0.1246 * light[0] + 1.1329 * light[1] - 0.0083 * light[2],
            -0.0182 * light[0] - 0.1006 * light[1] + 1.1187 * light[2],
        )
        return DoubleArray(3) { out[it].coerceIn(0.0, 1.0) }
    }

    private fun toLight(v: Double): Double = if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)

    private companion object {
        const val SIZE = 16
    }
}
