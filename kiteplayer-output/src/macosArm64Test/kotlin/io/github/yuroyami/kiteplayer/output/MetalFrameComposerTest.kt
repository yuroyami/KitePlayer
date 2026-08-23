@file:OptIn(ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.RenderQuality
import io.github.yuroyami.kiteplayer.VideoScaler
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
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
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

    /** A luma plane the caller paints, so a test can build a band or an edge. Flat grey chroma. */
    private fun lumaNv12(width: Int, height: Int, luma: (x: Int, y: Int) -> Int): MetalPicture.SoftwarePlanes {
        val plane = ByteArray(width * height) { luma(it % width, it / width).toByte() }
        val chroma = ByteArray(width * height / 2)
        var i = 0
        while (i < chroma.size) {
            chroma[i] = 128.toByte()
            chroma[i + 1] = 128.toByte()
            i += 2
        }
        return MetalPicture.SoftwarePlanes(
            width = width,
            height = height,
            format = PlayerPixelFormat.Nv12,
            planes = listOf(
                MetalPicture.SoftwarePlanes.Plane(plane, width, height),
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
        toneMapped: Boolean = false,
        qualityUniforms: FloatArray = DISABLED_QUALITY_UNIFORMS,
    ): ByteArray {
        val target = composer.device.makeTargetTexture(targetWidth, targetHeight)
        val commands = composer.encode(
            target, frame, picture, overlay, targetWidth, targetHeight,
            adjustUniforms = adjustUniforms,
            toneMapped = toneMapped,
            qualityUniforms = qualityUniforms,
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

    // The Kotlin mirror of the shader's tone-mapping law, for expected values.
    @Test
    fun `dithering off writes exactly the pixels it always did`() {
        // The ladder's first law (17.21): a build that turns nothing on must be bit-exact against
        // the pipeline that existed before the pass was written. Same picture, both uniform blocks.
        val device = MTLCreateSystemDefaultDevice() ?: error("this host has no Metal device")
        val composer = MetalFrameComposer(device)
        val picture = solidNv12(8, 8, y = 120, cb = 128, cr = 128)
        val frame = TestFrame(8, 8, bt(ColorMatrix.Bt709))
        val before = render(composer, frame, picture, qualityUniforms = DISABLED_QUALITY_UNIFORMS)
        val after = render(
            composer, frame, picture,
            qualityUniforms = packQualityUniforms(RenderQuality.Off),
        )
        assertTrue(
            before.contentEquals(after),
            "the neutral RenderQuality changed the write, so nothing below it can be measured alone",
        )
    }

    @Test
    fun `dithering spreads one flat value across the two steps it sits between`() {
        // A flat field is the whole test. Undithered, every pixel quantises to the SAME output step,
        // which is exactly what makes a smooth ramp band. Dithered, the ordered pattern must push
        // part of the field onto the neighbouring step while leaving the average where it was.
        //
        // The target is the picture's own size so the 8x8 pattern maps one-to-one onto the 8x8 field
        // and every one of its 64 offsets is exercised. Y=124 is chosen because it lands about
        // three quarters of the way between two output steps, so the split is wide and the test does
        // not sit on a rounding knife edge.
        val device = MTLCreateSystemDefaultDevice() ?: error("this host has no Metal device")
        val composer = MetalFrameComposer(device)
        val picture = solidNv12(8, 8, y = 124, cb = 128, cr = 128)
        val frame = TestFrame(8, 8, bt(ColorMatrix.Bt709))

        val plain = render(composer, frame, picture, targetWidth = 8, targetHeight = 8)
        val dithered = render(
            composer, frame, picture, targetWidth = 8, targetHeight = 8,
            qualityUniforms = packQualityUniforms(RenderQuality(dither = true)),
        )

        val plainGreens = (0 until 64).map { bgraAt(plain, 8, it % 8, it / 8)[1] }
        val ditherGreens = (0 until 64).map { bgraAt(dithered, 8, it % 8, it / 8)[1] }
        val flat = plainGreens.first()

        assertEquals(1, plainGreens.toSet().size, "the undithered flat field must be a single value")
        assertTrue(
            ditherGreens.toSet().size > 1,
            "dithering left the whole field on one step, so it did nothing: $ditherGreens",
        )
        assertTrue(
            ditherGreens.all { kotlin.math.abs(it - flat) <= 1 },
            "dithering moved a pixel by more than one output step: $ditherGreens",
        )
        // A pattern that only ever brightens would pass everything above and still be wrong, because
        // it would lift the whole picture. Centring is the property that stops that.
        val drift = ditherGreens.average() - plainGreens.average()
        assertTrue(
            kotlin.math.abs(drift) < 0.6,
            "dithering shifted the average by $drift steps, so the pattern is not centred on zero",
        )
    }

    @Test
    fun `debanding flattens a band and leaves a real edge alone`() {
        // The two halves of the pass, in one picture, because a deband that only does the first is
        // a blur. The left half is a one-step BAND, the kind quantisation leaves in a gradient; the
        // right half is a hard EDGE of many steps, the kind real detail is made of.
        //
        // Grain is off here on purpose: it is a separate decision from the smoothing, and leaving
        // it on would make the assertions probabilistic for no gain.
        val device = MTLCreateSystemDefaultDevice() ?: error("this host has no Metal device")
        val composer = MetalFrameComposer(device)
        val size = 32
        val picture = lumaNv12(size, size) { x, _ ->
            when {
                x < size / 2 -> if (x < size / 4) 120 else 121   // a one-step band
                else -> if (x < 3 * size / 4) 80 else 200        // a hard edge
            }
        }
        val frame = TestFrame(size, size, bt(ColorMatrix.Bt709))
        val quality = RenderQuality(deband = true, debandGrain = 0f, debandRange = 3f)

        val plain = render(composer, frame, picture, targetWidth = size, targetHeight = size)
        val debanded = render(
            composer, frame, picture, targetWidth = size, targetHeight = size,
            qualityUniforms = packQualityUniforms(quality, sourceWidth = size, sourceHeight = size),
        )

        // The edge must survive: sample well clear of it so the ring cannot legitimately reach across.
        val darkPlain = bgraAt(plain, size, size / 2 + 2, size / 2)[1]
        val darkDeband = bgraAt(debanded, size, size / 2 + 2, size / 2)[1]
        val brightPlain = bgraAt(plain, size, size - 2, size / 2)[1]
        val brightDeband = bgraAt(debanded, size, size - 2, size / 2)[1]
        assertTrue(
            kotlin.math.abs(darkDeband - darkPlain) <= 1 && kotlin.math.abs(brightDeband - brightPlain) <= 1,
            "debanding moved a hard edge: dark $darkPlain to $darkDeband, bright $brightPlain to $brightDeband",
        )

        // The band must SOFTEN. What that can mean in an 8-bit target is worth being exact about,
        // because the obvious assertion is wrong: an 8-bit write cannot hold a value between two
        // adjacent 8-bit levels, so debanding can never invent a third one here. What it does is
        // break the hard step into a mixed transition, which is why this rung pairs with RQ-1.
        //
        // So the property is ORDER, not value: undebanded, the row is a clean step, every low
        // sample before every high one. Debanded, that monotonicity is broken.
        val bandRow = (2 until size / 2 - 2).map { bgraAt(debanded, size, it, size / 2)[1] }
        val plainRow = (2 until size / 2 - 2).map { bgraAt(plain, size, it, size / 2)[1] }
        fun nonDecreasing(row: List<Int>) = row.zipWithNext().all { (a, b) -> b >= a }
        assertTrue(nonDecreasing(plainRow), "the undebanded band must be a clean step: $plainRow")
        assertTrue(
            !nonDecreasing(bandRow),
            "debanding left the step exactly as it was, so it did nothing: $bandRow",
        )
        assertTrue(
            bandRow.toSet() == plainRow.toSet(),
            "debanding moved a sample outside the band's own two levels: $bandRow",
        )
    }

    @Test
    fun `debanding off leaves the write untouched`() {
        // The ladder's first law again, for the second rung: asking for nothing must change nothing,
        // including the chroma coordinate, which this rung also shifts.
        val device = MTLCreateSystemDefaultDevice() ?: error("this host has no Metal device")
        val composer = MetalFrameComposer(device)
        val picture = solidNv12(16, 16, y = 120, cb = 100, cr = 150)
        val frame = TestFrame(16, 16, bt(ColorMatrix.Bt709))
        val before = render(composer, frame, picture, targetWidth = 16, targetHeight = 16)
        val after = render(
            composer, frame, picture, targetWidth = 16, targetHeight = 16,
            qualityUniforms = packQualityUniforms(
                RenderQuality.Off, sourceWidth = 16, sourceHeight = 16,
            ),
        )
        assertTrue(before.contentEquals(after), "the neutral value changed the write")
    }

    @Test
    fun `the bicubic kernel interpolates at one to one and sharpens an upscale`() {
        // Two properties, and the first is the reason Catmull-Rom was chosen over B-spline.
        //
        // At its own size the kernel must return the texel itself, because Catmull-Rom
        // INTERPOLATES: a picture drawn 1:1 must be untouched, or every unscaled playback would be
        // quietly filtered. A B-spline kernel would blur it and still pass a "looks smoother" eye
        // test, which is exactly the trap.
        //
        // On an upscale it must be SHARPER than bilinear, which is measurable without eyeballs: a
        // hard edge stretched by bilinear becomes a long linear ramp, while a cubic kernel keeps the
        // transition tighter. Counting how many samples sit strictly between the two levels is that
        // difference, and fewer is sharper.
        val device = MTLCreateSystemDefaultDevice() ?: error("this host has no Metal device")
        val composer = MetalFrameComposer(device)
        val size = 16
        val picture = lumaNv12(size, size) { x, _ -> if (x < size / 2) 60 else 200 }
        val frame = TestFrame(size, size, bt(ColorMatrix.Bt709))
        val cubic = packQualityUniforms(
            RenderQuality(scaler = VideoScaler.CatmullRom),
            sourceWidth = size, sourceHeight = size,
        )

        val oneToOnePlain = render(composer, frame, picture, targetWidth = size, targetHeight = size)
        val oneToOneCubic = render(
            composer, frame, picture, targetWidth = size, targetHeight = size,
            qualityUniforms = cubic,
        )
        assertTrue(
            oneToOnePlain.contentEquals(oneToOneCubic),
            "the kernel changed a picture drawn at its own size, so it is not interpolating",
        )

        // Now the same picture stretched four times, where a kernel can actually show itself.
        val big = size * 4
        val stretchedPlain = render(composer, frame, picture, targetWidth = big, targetHeight = big)
        val stretchedCubic = render(
            composer, frame, picture, targetWidth = big, targetHeight = big,
            qualityUniforms = cubic,
        )
        fun row(bytes: ByteArray) = (0 until big).map { bgraAt(bytes, big, it, big / 2)[1] }
        val bilinearRow = row(stretchedPlain)
        val cubicRow = row(stretchedCubic)

        // The steepest single step across the transition. A sharper kernel concentrates the change
        // into fewer output pixels, so its largest neighbour-to-neighbour jump is bigger. Counting
        // how many samples lie between the two levels does NOT separate them, because both spread
        // the edge over the same four output pixels; what differs is the SHAPE inside those four.
        fun steepest(r: List<Int>) = r.zipWithNext().maxOf { (a, b) -> kotlin.math.abs(b - a) }
        assertTrue(
            steepest(cubicRow) > steepest(bilinearRow),
            "the kernel was no steeper than bilinear on a 4x upscale: cubic ${steepest(cubicRow)}, " +
                "bilinear ${steepest(bilinearRow)}",
        )

        // The signature of a cubic with negative lobes, and the thing bilinear can never do: it
        // overshoots past the two levels it is interpolating between. Asserting it pins that the
        // kernel really is Catmull-Rom and not a smoother in disguise.
        val low = bilinearRow.first()
        val high = bilinearRow.last()
        assertTrue(
            bilinearRow.all { it in low..high },
            "bilinear overshot its endpoints, so this picture cannot separate the two kernels",
        )
        assertTrue(
            cubicRow.any { it < low || it > high },
            "the kernel never overshot, so it is not a cubic with negative lobes: $cubicRow",
        )
    }

    private fun pqEncode1(y: Double): Double {
        val p = y.coerceAtLeast(0.0).pow(0.1593017578125)
        return ((0.8359375 + 18.8515625 * p) / (1.0 + 18.6875 * p)).pow(78.84375)
    }

    private fun pqDecode1(e: Double): Double {
        val p = e.coerceAtLeast(0.0).pow(1.0 / 78.84375)
        return ((p - 0.8359375).coerceAtLeast(0.0) / (18.8515625 - 18.6875 * p)).pow(1.0 / 0.1593017578125)
    }

    /** BT.2390 EETF at srcPeak 1000 on a PQ grey, exactly the shader's arithmetic. */
    private fun expectedToneMappedGrey(electrical: Double): Int {
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

    private fun hdrPq() = ColorSpaceInfo(
        matrix = ColorMatrix.Bt2020Ncl,
        primaries = ColorPrimaries.Bt2020,
        transfer = ColorTransfer.Pq,
        fullRange = true,
    )

    @Test
    fun aPqFrameToneMapsToTheExpectedSdrAndSdrStaysBitExact() {
        val device = MTLCreateSystemDefaultDevice() ?: error("this host has no Metal device")
        val composer = MetalFrameComposer(device)

        // Full-range PQ grey: Y=160, neutral chroma. Electrical 160/255 is about 314 nits,
        // above the EETF knee but below peak, so the spline shape is what renders.
        val pqFrame = TestFrame(64, 64, hdrPq())
        val pqGrey = { solidNv12(64, 64, y = 160, cb = 128, cr = 128) }

        val raw = render(composer, pqFrame, pqGrey())
        val mapped = render(composer, pqFrame, pqGrey(), toneMapped = true)
        assertTrue(
            !raw.contentEquals(mapped),
            "tone mapping a 314-nit PQ grey must change the picture; identical bytes mean the uniform is dead",
        )

        val got = bgraAt(mapped, 64, 32, 32)
        val expected = expectedToneMappedGrey(160.0 / 255.0)
        for (channel in 0..2) {
            assertTrue(
                abs(got[channel] - expected) <= 3,
                "tone-mapped grey expected about $expected on every channel, got ${got.toList()}",
            )
        }

        // An SDR frame through the tone-mapped path packs mode 0 and must stay bit-exact.
        val sdrFrame = TestFrame(64, 64, bt(ColorMatrix.Bt709))
        val sdrPlain = render(composer, sdrFrame, solidNv12(64, 64, y = 126, cb = 128, cr = 128))
        val sdrToneMapped = render(
            composer, sdrFrame, solidNv12(64, 64, y = 126, cb = 128, cr = 128),
            toneMapped = true,
        )
        assertTrue(
            sdrPlain.contentEquals(sdrToneMapped),
            "an SDR transfer must pack mode 0 and stay bit-exact through the tone-mapped path",
        )
    }
}
