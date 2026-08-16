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
import io.github.yuroyami.kiteplayer.spi.OverlayImage
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.spi.SubtitleOverlay
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import io.github.yuroyami.kiteplayer.subtitle.RgbaBitmap
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
import platform.Metal.MTLRegionMake2D
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The composer proved offscreen with REAL Metal on the host (S2.c): known YUV values render to
 * known sRGB within tolerance, the colour matrix uniform is demonstrably live, the letterbox
 * clears what it does not cover, and an overlay draws above the picture. This suite is the seed
 * the S2.e colour instrument grows from.
 */
class MetalFrameComposerTest {

    private class TestFrame(
        width: Int,
        height: Int,
        override val colorSpace: ColorSpaceInfo,
        override val rotationDegrees: Int = 0,
    ) : VideoFrame {
        override val pts: Pts = Pts.Zero
        override val duration: Pts? = null
        override val size: VideoSize = VideoSize(width, height, 1, 1)
        override val pixelFormat: PlayerPixelFormat = PlayerPixelFormat.Nv12
        override val hardwareSurface: HwSurfaceKind? = null
        override val generation: Generation = Generation.Initial
        override fun close() = Unit
    }

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

    private fun bt(matrix: ColorMatrix) = ColorSpaceInfo(
        matrix = matrix,
        primaries = ColorPrimaries.Bt709,
        transfer = ColorTransfer.Bt709,
        fullRange = false,
    )

    /** Renders and reads back BGRA bytes. */
    private fun render(
        composer: MetalFrameComposer,
        frame: VideoFrame,
        picture: MetalPicture,
        overlay: SubtitleOverlay? = null,
        targetWidth: Int = 64,
        targetHeight: Int = 64,
        adjustUniforms: FloatArray = DISABLED_ADJUST_UNIFORMS,
    ): ByteArray {
        val target = composer.device.makeTargetTexture(targetWidth, targetHeight)
        val commands = composer.encode(
            target, frame, picture, overlay, targetWidth, targetHeight,
            adjustUniforms = adjustUniforms,
        )
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

    private fun bgraAt(bytes: ByteArray, width: Int, x: Int, y: Int): IntArray {
        val at = (y * width + x) * 4
        return intArrayOf(
            bytes[at].toInt() and 0xFF,      // B
            bytes[at + 1].toInt() and 0xFF,  // G
            bytes[at + 2].toInt() and 0xFF,  // R
            bytes[at + 3].toInt() and 0xFF,  // A
        )
    }

    /** The same arithmetic as the shader and SoftwareConverter, for the expected value. */
    private fun expectedRgb(y: Int, cb: Int, cr: Int, rCr: Double, gCb: Double, gCr: Double, bCb: Double): IntArray {
        val luma = (y - 16) * (255.0 / 219.0)
        val cbv = (cb - 128) * (255.0 / 224.0)
        val crv = (cr - 128) * (255.0 / 224.0)
        fun clamp(v: Double) = v.coerceIn(0.0, 255.0).toInt()
        return intArrayOf(
            clamp(luma + rCr * crv),
            clamp(luma - gCb * cbv - gCr * crv),
            clamp(luma + bCb * cbv),
        )
    }

    @Test
    fun knownYuvRendersToTheExpectedSrgbWithinTolerance() {
        val device = MTLCreateSystemDefaultDevice() ?: error("this host has no Metal device")
        val composer = MetalFrameComposer(device)
        // The classic saturated studio-range red: Y=63 Cb=102 Cr=240 in BT.709.
        val bytes = render(
            composer,
            TestFrame(64, 64, bt(ColorMatrix.Bt709)),
            solidNv12(64, 64, y = 63, cb = 102, cr = 240),
        )
        val (b, g, r, a) = bgraAt(bytes, 64, 32, 32)
        val expected = expectedRgb(63, 102, 240, rCr = 1.5748, gCb = 0.187324, gCr = 0.468124, bCb = 1.8556)
        assertTrue(a == 255, "the picture must be opaque, alpha was $a")
        assertTrue(
            abs(r - expected[0]) <= 2 && abs(g - expected[1]) <= 2 && abs(b - expected[2]) <= 2,
            "got rgb($r,$g,$b), expected rgb(${expected[0]},${expected[1]},${expected[2]}) within 2",
        )
    }

    @Test
    fun theColourMatrixUniformIsLive() {
        val device = MTLCreateSystemDefaultDevice() ?: error("this host has no Metal device")
        val composer = MetalFrameComposer(device)
        val picture = solidNv12(64, 64, y = 100, cb = 80, cr = 200)
        val as709 = render(composer, TestFrame(64, 64, bt(ColorMatrix.Bt709)), picture)
        val as601 = render(composer, TestFrame(64, 64, bt(ColorMatrix.Bt601)), picture)
        val p709 = bgraAt(as709, 64, 32, 32)
        val p601 = bgraAt(as601, 64, 32, 32)
        val distance = abs(p709[0] - p601[0]) + abs(p709[1] - p601[1]) + abs(p709[2] - p601[2])
        assertTrue(
            distance >= 6,
            "BT.709 and BT.601 rendered the same pixel (${p709.toList()} vs ${p601.toList()}); the matrix uniform is dead",
        )
    }

    /**
     * The zero-copy path proved with a REAL CVPixelBuffer: a biplanar buffer is filled by hand,
     * wrapped through CVMetalTextureCache with no copy, and renders the same red as the software
     * path. This is the exact surface a VideoToolbox frame hands over in production.
     */
    @Test
    fun aCvPixelBufferWrapsWithoutACopyAndRendersTheSameRed() {
        val device = MTLCreateSystemDefaultDevice() ?: error("this host has no Metal device")
        val composer = MetalFrameComposer(device)
        memScoped {
            // CVMetalTextureCache wraps only IOSurface-backed buffers, which is also what
            // VideoToolbox produces; a malloc-backed buffer answers kCVReturnInvalidArgument.
            // CoreVideo COPIES the attribute dictionary, so it must carry the CFType callbacks;
            // a null-callback dictionary crashed inside CVPixelBufferCreate.
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
                pixelFormatType = platform.CoreVideo.kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
                pixelBufferAttributes = attributes,
                pixelBufferOut = bufferOut.ptr,
            )
            check(rc == platform.CoreVideo.kCVReturnSuccess) { "CVPixelBufferCreate failed: $rc" }
            val buffer = checkNotNull(bufferOut.value)
            platform.CoreVideo.CVPixelBufferLockBaseAddress(buffer, 0uL)
            try {
                val luma = platform.CoreVideo.CVPixelBufferGetBaseAddressOfPlane(buffer, 0u)!!
                    .reinterpret<ByteVar>()
                val lumaStride = platform.CoreVideo.CVPixelBufferGetBytesPerRowOfPlane(buffer, 0u).toInt()
                for (row in 0 until 64) {
                    for (column in 0 until 64) luma[row * lumaStride + column] = 63
                }
                val chroma = platform.CoreVideo.CVPixelBufferGetBaseAddressOfPlane(buffer, 1u)!!
                    .reinterpret<ByteVar>()
                val chromaStride = platform.CoreVideo.CVPixelBufferGetBytesPerRowOfPlane(buffer, 1u).toInt()
                for (row in 0 until 32) {
                    for (column in 0 until 32) {
                        chroma[row * chromaStride + column * 2] = 102.toByte()
                        chroma[row * chromaStride + column * 2 + 1] = 240.toByte()
                    }
                }
            } finally {
                platform.CoreVideo.CVPixelBufferUnlockBaseAddress(buffer, 0uL)
            }

            val bytes = render(
                composer,
                TestFrame(64, 64, bt(ColorMatrix.Bt709)),
                MetalPicture.CorePixelBuffer(buffer as COpaquePointer),
            )
            platform.CoreFoundation.CFRelease(buffer)
            val (b, g, r, _) = bgraAt(bytes, 64, 32, 32)
            val expected = expectedRgb(63, 102, 240, rCr = 1.5748, gCb = 0.187324, gCr = 0.468124, bCb = 1.8556)
            assertTrue(
                abs(r - expected[0]) <= 2 && abs(g - expected[1]) <= 2 && abs(b - expected[2]) <= 2,
                "hardware wrap got rgb($r,$g,$b), expected rgb(${expected[0]},${expected[1]},${expected[2]}) within 2",
            )
        }
    }

    @Test
    fun letterboxClearsTheUncoveredRowsAndOverlaysDrawAboveThePicture() {
        val device = MTLCreateSystemDefaultDevice() ?: error("this host has no Metal device")
        val composer = MetalFrameComposer(device)
        // A 64x32 picture into a 64x64 viewport: 16 clear rows above and below.
        val whiteOverlay = SubtitleOverlay(
            images = listOf(
                OverlayImage(
                    x = 28,
                    y = 28,
                    bitmap = RgbaBitmap(8, 8, ByteArray(8 * 8 * 4) { 0xFF.toByte() }),
                ),
            ),
            viewportWidth = 64,
            viewportHeight = 64,
            contentHash = 42L,
        )
        val bytes = render(
            composer,
            TestFrame(64, 32, bt(ColorMatrix.Bt709)),
            solidNv12(64, 32, y = 63, cb = 102, cr = 240),
            overlay = whiteOverlay,
        )
        val bar = bgraAt(bytes, 64, 32, 4)
        assertTrue(
            bar[0] == 0 && bar[1] == 0 && bar[2] == 0,
            "the letterbox bar must be cleared, got ${bar.toList()}",
        )
        val picture = bgraAt(bytes, 64, 8, 32)
        assertTrue(picture[2] > 150, "the picture row must be red, got ${picture.toList()}")
        val overlay = bgraAt(bytes, 64, 32, 32)
        assertTrue(
            overlay[0] > 200 && overlay[1] > 200 && overlay[2] > 200,
            "the overlay pixel must be white above the red picture, got ${overlay.toList()}",
        )
    }

    @Test
    fun theAdjustUniformsAreLiveAndDisabledIsBitExact() {
        val device = MTLCreateSystemDefaultDevice() ?: error("this host has no Metal device")
        val composer = MetalFrameComposer(device)
        val frame = TestFrame(64, 64, bt(ColorMatrix.Bt709))
        // Mid-grey: limited-range Y=126 with neutral chroma is 128-ish sRGB on every channel.
        val grey = { solidNv12(64, 64, y = 126, cb = 128, cr = 128) }

        val plain = render(composer, frame, grey())
        val disabled = render(composer, frame, grey(), adjustUniforms = DISABLED_ADJUST_UNIFORMS)
        assertTrue(
            plain.contentEquals(disabled),
            "a disabled adjust uniform must not change one bit: the instrument depends on it",
        )

        val lifted = render(
            composer, frame, grey(),
            adjustUniforms = packAdjustUniforms(
                io.github.yuroyami.kiteplayer.VideoAdjustments(brightness = 0.25f),
            ),
        )
        val base = bgraAt(plain, 64, 32, 32)
        val bright = bgraAt(lifted, 64, 32, 32)
        for (channel in 0..2) {
            val gain = bright[channel] - base[channel]
            assertTrue(
                gain in 56..72,
                "brightness 0.25 lifts each channel by about 64, channel $channel moved $gain " +
                    "(${base.toList()} -> ${bright.toList()})",
            )
        }

        val greyscale = render(
            composer, frame, solidNv12(64, 64, y = 81, cb = 90, cr = 240),
            adjustUniforms = packAdjustUniforms(
                io.github.yuroyami.kiteplayer.VideoAdjustments(saturation = 0f),
            ),
        )
        val desaturated = bgraAt(greyscale, 64, 32, 32)
        assertTrue(
            abs(desaturated[0] - desaturated[1]) <= 2 && abs(desaturated[1] - desaturated[2]) <= 2,
            "saturation 0 must land red on grey, got ${desaturated.toList()}",
        )
    }
}
