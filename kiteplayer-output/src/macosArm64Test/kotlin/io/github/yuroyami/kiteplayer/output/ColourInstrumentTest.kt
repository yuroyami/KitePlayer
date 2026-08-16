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
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.Metal.MTLCreateSystemDefaultDevice
import platform.Metal.MTLDeviceProtocol
import platform.Metal.MTLRegionMake2D
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The S2.e colour instrument: programmatic frames at known YUV values, decoded by nothing and
 * rendered offscreen through REAL Metal on this host, pixels read back and judged against an
 * INDEPENDENT reference (the coefficients are written here by hand, never read from the
 * production tables, so a wrong production coefficient cannot agree with its own echo). One
 * falsifying arm proves a deliberately wrong matrix FAILS, which is what makes the green runs
 * evidence rather than tautology.
 *
 * The corners: BT.601 and BT.709, each in studio and full range; BT.2020 NCL rides as far as the
 * software-plane pipeline allows (both ranges, 8-bit, plus the P010 high-aligned 10-bit shape).
 */
class ColourInstrumentTest {

    private companion object {
        /** The stated per-channel tolerance, in 8-bit steps, same as the S2.c proofs. */
        const val TOLERANCE = 2
    }

    private class InstrumentFrame(
        width: Int,
        height: Int,
        override val colorSpace: ColorSpaceInfo,
        override val pixelFormat: PlayerPixelFormat = PlayerPixelFormat.Nv12,
        override val rotationDegrees: Int = 0,
    ) : VideoFrame {
        override val pts: Pts = Pts.Zero
        override val duration: Pts? = null
        override val size: VideoSize = VideoSize(width, height, 1, 1)
        override val hardwareSurface: HwSurfaceKind? = null
        override val generation: Generation = Generation.Initial
        override fun close() = Unit
    }

    private fun space(matrix: ColorMatrix, fullRange: Boolean) = ColorSpaceInfo(
        matrix = matrix,
        primaries = ColorPrimaries.Bt709,
        transfer = ColorTransfer.Bt709,
        fullRange = fullRange,
    )

    private fun solidNv12(width: Int, height: Int, y: Int, cb: Int, cr: Int): MetalPicture.SoftwarePlanes {
        val luma = ByteArray(width * height) { y.toByte() }
        val chroma = ByteArray(width * height / 2)
        var i = 0
        while (i < chroma.size) {
            chroma[i] = cb.toByte()
            chroma[i + 1] = cr.toByte()
            i += 2
        }
        return MetalPicture.SoftwarePlanes(
            width = width,
            height = height,
            format = PlayerPixelFormat.Nv12,
            planes = listOf(
                MetalPicture.SoftwarePlanes.Plane(luma, width, height),
                MetalPicture.SoftwarePlanes.Plane(chroma, width, height / 2),
            ),
        )
    }

    /** P010: the 10-bit value sits in the HIGH bits of each 16-bit sample. */
    private fun solidP010(width: Int, height: Int, y10: Int, cb10: Int, cr10: Int): MetalPicture.SoftwarePlanes {
        fun le16(value: Int): Pair<Byte, Byte> {
            val stored = value shl 6
            return (stored and 0xFF).toByte() to ((stored shr 8) and 0xFF).toByte()
        }
        val luma = ByteArray(width * height * 2)
        val (yLo, yHi) = le16(y10)
        var i = 0
        while (i < luma.size) {
            luma[i] = yLo
            luma[i + 1] = yHi
            i += 2
        }
        val chroma = ByteArray(width * height)
        val (cbLo, cbHi) = le16(cb10)
        val (crLo, crHi) = le16(cr10)
        var c = 0
        while (c < chroma.size) {
            chroma[c] = cbLo
            chroma[c + 1] = cbHi
            chroma[c + 2] = crLo
            chroma[c + 3] = crHi
            c += 4
        }
        return MetalPicture.SoftwarePlanes(
            width = width,
            height = height,
            format = PlayerPixelFormat.P010le,
            planes = listOf(
                MetalPicture.SoftwarePlanes.Plane(luma, width * 2, height),
                MetalPicture.SoftwarePlanes.Plane(chroma, width * 2, height / 2),
            ),
        )
    }

    private fun render(
        composer: MetalFrameComposer,
        frame: VideoFrame,
        picture: MetalPicture,
        targetWidth: Int = 64,
        targetHeight: Int = 64,
    ): ByteArray {
        val target = composer.device.makeTargetTexture(targetWidth, targetHeight)
        val commands = composer.encode(target, frame, picture, null, targetWidth, targetHeight)
        commands.waitUntilCompleted()
        val bytes = ByteArray(targetWidth * targetHeight * 4)
        bytes.usePinned { pinned ->
            target.getBytes(
                pinned.addressOf(0),
                bytesPerRow = (targetWidth * 4).toULong(),
                fromRegion = MTLRegionMake2D(0u, 0u, targetWidth.toULong(), targetHeight.toULong()),
                mipmapLevel = 0u,
            )
        }
        return bytes
    }

    private fun rgbAt(bytes: ByteArray, width: Int, x: Int, y: Int): IntArray {
        val at = (y * width + x) * 4
        return intArrayOf(
            bytes[at + 2].toInt() and 0xFF,  // R (BGRA storage)
            bytes[at + 1].toInt() and 0xFF,  // G
            bytes[at].toInt() and 0xFF,      // B
        )
    }

    /** The independent coefficient table: BT.601, BT.709 and BT.2020 NCL, written by hand. */
    private class Coefficients(val rCr: Double, val gCb: Double, val gCr: Double, val bCb: Double)

    private val bt601 = Coefficients(1.402, 0.344136, 0.714136, 1.772)
    private val bt709 = Coefficients(1.5748, 0.187324, 0.468124, 1.8556)
    private val bt2020 = Coefficients(1.4746, 0.164553, 0.571353, 1.8814)

    /**
     * The reference conversion, on continuous values so the 10-bit probes reuse it: range
     * expansion first (studio: Y over 16..235, C over 16..240; full: identity), then the matrix.
     */
    private fun expectedRgb(y: Double, cb: Double, cr: Double, k: Coefficients, fullRange: Boolean): IntArray {
        val luma = if (fullRange) y else (y - 16.0) * (255.0 / 219.0)
        val cbv = if (fullRange) cb - 128.0 else (cb - 128.0) * (255.0 / 224.0)
        val crv = if (fullRange) cr - 128.0 else (cr - 128.0) * (255.0 / 224.0)
        fun clamp(v: Double) = v.coerceIn(0.0, 255.0).roundToInt()
        return intArrayOf(
            clamp(luma + k.rCr * crv),
            clamp(luma - k.gCb * cbv - k.gCr * crv),
            clamp(luma + k.bCb * cbv),
        )
    }

    private fun maxDelta(got: IntArray, expected: IntArray): Int =
        maxOf(abs(got[0] - expected[0]), abs(got[1] - expected[1]), abs(got[2] - expected[2]))

    /** The probes: neutral gray, a chroma-heavy saturated value, near-black, near-white. */
    private val probes = listOf(
        Triple(128, 128, 128),
        Triple(63, 102, 240),
        Triple(20, 120, 130),
        Triple(230, 128, 128),
    )

    private fun device(): MTLDeviceProtocol =
        MTLCreateSystemDefaultDevice() ?: error("this host has no Metal device")

    @Test
    fun `the four corners and 2020 render within the stated tolerance`() {
        val composer = MetalFrameComposer(device())
        val corners = listOf(
            Triple(ColorMatrix.Bt601, bt601, false),
            Triple(ColorMatrix.Bt601, bt601, true),
            Triple(ColorMatrix.Bt709, bt709, false),
            Triple(ColorMatrix.Bt709, bt709, true),
            Triple(ColorMatrix.Bt2020Ncl, bt2020, false),
            Triple(ColorMatrix.Bt2020Ncl, bt2020, true),
        )
        val failures = buildList {
            for ((matrix, k, fullRange) in corners) {
                for ((y, cb, cr) in probes) {
                    val bytes = render(
                        composer,
                        InstrumentFrame(64, 64, space(matrix, fullRange)),
                        solidNv12(64, 64, y, cb, cr),
                    )
                    val got = rgbAt(bytes, 64, 32, 32)
                    val expected = expectedRgb(y.toDouble(), cb.toDouble(), cr.toDouble(), k, fullRange)
                    val delta = maxDelta(got, expected)
                    if (delta > TOLERANCE) {
                        add(
                            "$matrix ${if (fullRange) "full" else "studio"} yuv($y,$cb,$cr): " +
                                "got rgb(${got.toList()}), expected rgb(${expected.toList()}), delta $delta",
                        )
                    }
                }
            }
        }
        composer.close()
        assertEquals(emptyList(), failures, "corners out of tolerance ($TOLERANCE per channel)")
    }

    @Test
    fun `the ten bit P010 shape renders within the stated tolerance`() {
        val composer = MetalFrameComposer(device())
        // The 10-bit siblings of the saturated 8-bit probe: value10 = round(value8 * 1023 / 255).
        val y10 = 253
        val cb10 = 409
        val cr10 = 963
        val bytes = render(
            composer,
            InstrumentFrame(64, 64, space(ColorMatrix.Bt709, fullRange = false), PlayerPixelFormat.P010le),
            solidP010(64, 64, y10, cb10, cr10),
        )
        composer.close()
        val got = rgbAt(bytes, 64, 32, 32)
        val expected = expectedRgb(
            y10 * 255.0 / 1023.0,
            cb10 * 255.0 / 1023.0,
            cr10 * 255.0 / 1023.0,
            bt709,
            fullRange = false,
        )
        val delta = maxDelta(got, expected)
        assertTrue(
            delta <= TOLERANCE,
            "P010 got rgb(${got.toList()}), expected rgb(${expected.toList()}), delta $delta",
        )
    }

    /**
     * The falsifying arm. A saturated BT.601 frame deliberately TAGGED BT.709 must land outside
     * the tolerance against the true BT.601 reference. If this arm ever passes inside tolerance,
     * the instrument cannot tell matrices apart and every green corner above means nothing.
     */
    @Test
    fun `a deliberately wrong matrix fails the instrument`() {
        val composer = MetalFrameComposer(device())
        val (y, cb, cr) = Triple(63, 102, 240)
        val bytes = render(
            composer,
            InstrumentFrame(64, 64, space(ColorMatrix.Bt709, fullRange = false)),
            solidNv12(64, 64, y, cb, cr),
        )
        composer.close()
        val got = rgbAt(bytes, 64, 32, 32)
        val truth601 = expectedRgb(y.toDouble(), cb.toDouble(), cr.toDouble(), bt601, fullRange = false)
        val delta = maxDelta(got, truth601)
        assertTrue(
            delta > TOLERANCE,
            "a BT.709-rendered frame judged against the BT.601 truth stayed within $TOLERANCE " +
                "(delta $delta): the instrument cannot detect a wrong matrix",
        )
    }

    /**
     * SOL-R4: a packed BGRA CVPixelBuffer has no planes, and the per-plane size functions answer
     * zero for it. The wrap must size the texture from the buffer itself and pass the pixels
     * through untouched (shader mode 2 applies no matrix).
     */
    @Test
    fun `a packed BGRA pixel buffer wraps at the buffer's own size and passes through`() {
        val composer = MetalFrameComposer(device())
        memScoped {
            val emptySurfaceProps = platform.CoreFoundation.CFDictionaryCreate(
                null, null, null, 0,
                platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks.ptr,
                platform.CoreFoundation.kCFTypeDictionaryValueCallBacks.ptr,
            )
            val keys = allocArrayOf(platform.CoreVideo.kCVPixelBufferIOSurfacePropertiesKey)
            val values = allocArrayOf(emptySurfaceProps)
            val attributes = platform.CoreFoundation.CFDictionaryCreate(
                null,
                keys.reinterpret(),
                values.reinterpret(),
                1,
                platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks.ptr,
                platform.CoreFoundation.kCFTypeDictionaryValueCallBacks.ptr,
            )
            val bufferOut = alloc<platform.CoreVideo.CVPixelBufferRefVar>()
            val rc = platform.CoreVideo.CVPixelBufferCreate(
                allocator = null,
                width = 64u,
                height = 64u,
                pixelFormatType = platform.CoreVideo.kCVPixelFormatType_32BGRA,
                pixelBufferAttributes = attributes,
                pixelBufferOut = bufferOut.ptr,
            )
            check(rc == platform.CoreVideo.kCVReturnSuccess) { "CVPixelBufferCreate failed: $rc" }
            val buffer = checkNotNull(bufferOut.value)
            platform.CoreVideo.CVPixelBufferLockBaseAddress(buffer, 0uL)
            try {
                val base = platform.CoreVideo.CVPixelBufferGetBaseAddress(buffer)!!.reinterpret<ByteVar>()
                val stride = platform.CoreVideo.CVPixelBufferGetBytesPerRow(buffer).toInt()
                for (row in 0 until 64) {
                    for (column in 0 until 64) {
                        val at = row * stride + column * 4
                        base[at] = 10          // B
                        base[at + 1] = 200.toByte() // G
                        base[at + 2] = 40      // R
                        base[at + 3] = 0xFF.toByte()
                    }
                }
            } finally {
                platform.CoreVideo.CVPixelBufferUnlockBaseAddress(buffer, 0uL)
            }
            val bytes = render(
                composer,
                InstrumentFrame(64, 64, space(ColorMatrix.Bt709, false), PlayerPixelFormat.Bgra),
                MetalPicture.CorePixelBuffer(buffer as COpaquePointer),
            )
            platform.CoreFoundation.CFRelease(buffer)
            val got = rgbAt(bytes, 64, 32, 32)
            val delta = maxDelta(got, intArrayOf(40, 200, 10))
            assertTrue(
                delta <= TOLERANCE,
                "BGRA passthrough got rgb(${got.toList()}), expected rgb(40, 200, 10), delta $delta",
            )
        }
        composer.close()
    }

    /** SOL-R8: rotation is normalized modulo 360 before the quarter-turn reading. */
    @Test
    fun `rotation normalizes modulo 360 and non quarter turns read as zero`() {
        assertEquals(270, normalizedQuarterTurn(-90))
        assertEquals(90, normalizedQuarterTurn(450))
        assertEquals(180, normalizedQuarterTurn(-180))
        assertEquals(0, normalizedQuarterTurn(0))
        assertEquals(0, normalizedQuarterTurn(45))
        val minus90 = quadUniformsFor(InstrumentFrame(64, 32, space(ColorMatrix.Bt709, false), rotationDegrees = -90), 64, 64)
        val plus270 = quadUniformsFor(InstrumentFrame(64, 32, space(ColorMatrix.Bt709, false), rotationDegrees = 270), 64, 64)
        assertEquals(plus270.toList(), minus90.toList(), "-90 and 270 must produce one identical quad")
    }

    /** SOL-R6: close fences the GPU, releases the cache, and stays idempotent. */
    @Test
    fun `close is idempotent after hardware and software renders`() {
        val composer = MetalFrameComposer(device())
        render(
            composer,
            InstrumentFrame(64, 64, space(ColorMatrix.Bt709, false)),
            solidNv12(64, 64, 128, 128, 128),
        )
        composer.close()
        composer.close()
    }
}
