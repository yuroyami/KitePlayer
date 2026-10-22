@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

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
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.set
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import platform.CoreFoundation.CFStringRef
import platform.CoreGraphics.CGColorSpaceCopyName
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGSizeMake
import platform.CoreVideo.CVBufferCopyAttachment
import platform.CoreVideo.CVPixelBufferRelease
import platform.CoreVideo.kCVImageBufferColorPrimariesKey
import platform.CoreVideo.kCVImageBufferColorPrimaries_EBU_3213
import platform.CoreVideo.kCVImageBufferColorPrimaries_ITU_R_2020
import platform.CoreVideo.kCVImageBufferColorPrimaries_ITU_R_709_2
import platform.CoreVideo.kCVImageBufferColorPrimaries_SMPTE_C
import platform.CoreVideo.kCVImageBufferTransferFunctionKey
import platform.CoreVideo.kCVImageBufferTransferFunction_ITU_R_709_2
import platform.CoreVideo.kCVImageBufferTransferFunction_SMPTE_ST_2084_PQ
import platform.CoreVideo.kCVImageBufferYCbCrMatrixKey
import platform.CoreVideo.kCVImageBufferYCbCrMatrix_ITU_R_2020
import platform.CoreVideo.kCVImageBufferYCbCrMatrix_ITU_R_601_4
import platform.CoreVideo.kCVImageBufferYCbCrMatrix_ITU_R_709_2
import platform.QuartzCore.CAMetalLayer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** A picture's colour reaches the system, on the Metal layer and on a software pixel buffer (#489). */
class AppleColorTagsTest {

    private val unstated = ColorSpaceInfo(ColorMatrix.Unspecified, ColorPrimaries.Unspecified, ColorTransfer.Unspecified)
    private val bt709 = ColorSpaceInfo(ColorMatrix.Bt709, ColorPrimaries.Bt709, ColorTransfer.Bt709)
    private val pq = ColorSpaceInfo(ColorMatrix.Bt2020Ncl, ColorPrimaries.Bt2020, ColorTransfer.Pq)

    private fun text(name: CFStringRef?): String? =
        name?.let { platform.Foundation.CFBridgingRelease(platform.CoreFoundation.CFRetain(it)) as String? }

    private fun same(a: CFStringRef?, b: CFStringRef?) = a != null && b != null && text(a) == text(b)

    @Test
    fun aStreamThatStatesNothingIsTaggedByItsSize() {
        val hd = colorTagsOf(unstated, 1920, 1080)
        assertTrue(same(hd.primaries, kCVImageBufferColorPrimaries_ITU_R_709_2))
        assertTrue(same(hd.matrix, kCVImageBufferYCbCrMatrix_ITU_R_709_2))
        assertTrue(same(hd.transfer, kCVImageBufferTransferFunction_ITU_R_709_2))
        val pal = colorTagsOf(unstated, 720, 576)
        assertTrue(same(pal.primaries, kCVImageBufferColorPrimaries_EBU_3213), "576 lines is PAL")
        assertTrue(same(pal.matrix, kCVImageBufferYCbCrMatrix_ITU_R_601_4))
        val ntsc = colorTagsOf(unstated, 720, 480)
        assertTrue(same(ntsc.primaries, kCVImageBufferColorPrimaries_SMPTE_C), "480 lines is NTSC")
        assertTrue(same(ntsc.matrix, kCVImageBufferYCbCrMatrix_ITU_R_601_4))
    }

    @Test
    fun whatTheStreamStatesWinsOverItsSize() {
        val small = colorTagsOf(bt709, 640, 360)
        assertTrue(same(small.primaries, kCVImageBufferColorPrimaries_ITU_R_709_2))
        assertTrue(same(small.matrix, kCVImageBufferYCbCrMatrix_ITU_R_709_2))
        val hdr = colorTagsOf(pq, 3840, 2160)
        assertTrue(same(hdr.primaries, kCVImageBufferColorPrimaries_ITU_R_2020))
        assertTrue(same(hdr.matrix, kCVImageBufferYCbCrMatrix_ITU_R_2020))
        assertTrue(same(hdr.transfer, kCVImageBufferTransferFunction_SMPTE_ST_2084_PQ))
        assertEquals(colorTagsOf(bt709, 1920, 1080), colorTagsOf(unstated, 1920, 1080), "tags compare by value")
    }

    /**
     * Core Video names the space it shows tagged video in. For BT.709 that is its Core Media 709
     * space, the one QuickTime Player shows a BT.709 file in, and not the plain ITU-R 709 space.
     */
    @Test
    fun theColourSpaceOfATagIsTheSystemsOwnVideoSpace() {
        fun nameOf(tags: ColorTags): String? {
            val space = assertNotNull(createColorSpace(tags))
            val name = CGColorSpaceCopyName(space)?.let { platform.Foundation.CFBridgingRelease(it) as String? }
            CGColorSpaceRelease(space)
            return name
        }
        assertEquals("kCGColorSpaceCoreMedia709", nameOf(colorTagsOf(bt709, 1920, 1080)))
        assertEquals("kCGColorSpaceCoreMedia709", nameOf(colorTagsOf(unstated, 1920, 1080)), "an HD stream that states nothing")
        val wide = ColorSpaceInfo(ColorMatrix.Bt2020Ncl, ColorPrimaries.Bt2020, ColorTransfer.Bt2020Ten)
        // The rest have no name: Core Video builds each from its primaries and curve.
        assertNull(nameOf(colorTagsOf(wide, 3840, 2160)))
        assertNull(nameOf(colorTagsOf(unstated, 720, 576)))
        assertNull(nameOf(toneMappedColorTags), "gamma 2.2 on BT.709 primaries")
    }

    /**
     * What the tag changes on a P3 display: the system turns BT.709 red into the P3 colour that
     * looks the same. With no tag the display shows its own, more saturated red.
     */
    @Test
    fun taggedBt709RedIsMatchedToTheSameColourOnAP3Display() = kotlinx.cinterop.memScoped {
        val video = assertNotNull(createColorSpace(colorTagsOf(bt709, 1920, 1080)))
        val p3 = platform.CoreGraphics.CGColorSpaceCreateWithName(platform.CoreGraphics.kCGColorSpaceDisplayP3)
        val red = allocArray<kotlinx.cinterop.DoubleVar>(4)
        red[0] = 1.0; red[1] = 0.0; red[2] = 0.0; red[3] = 1.0
        val color = platform.CoreGraphics.CGColorCreate(video, red)
        val matched = assertNotNull(
            platform.CoreGraphics.CGColorCreateCopyByMatchingToColorSpace(
                p3, platform.CoreGraphics.CGColorRenderingIntent.kCGRenderingIntentDefault, color, null,
            ),
        )
        val parts = assertNotNull(platform.CoreGraphics.CGColorGetComponents(matched))
        val (r, g, b) = Triple(parts[0], parts[1], parts[2])
        // BT.709 red sits inside P3, at about (0.92, 0.20, 0.14) of the display's own primaries.
        assertTrue(r in 0.88..0.96 && g in 0.15..0.26 && b in 0.09..0.19, "matched to ($r, $g, $b)")
        platform.CoreGraphics.CGColorRelease(matched)
        platform.CoreGraphics.CGColorRelease(color)
        CGColorSpaceRelease(p3)
        CGColorSpaceRelease(video)
    }

    private fun plane(width: Int, height: Int, value: Int) =
        MetalPicture.SoftwarePlanes.Plane(ByteArray(width * height) { value.toByte() }, width, height)

    private fun picture(width: Int = 64, height: Int = 64) = MetalPicture.SoftwarePlanes(
        width, height, PlayerPixelFormat.Yuv420p,
        listOf(plane(width, height, 128), plane(width / 2, height / 2, 128), plane(width / 2, height / 2, 128)),
    )

    @Test
    fun aSoftwareFrameCopiedIntoAPixelBufferCarriesItsTags() {
        fun attachment(buffer: platform.CoreVideo.CVPixelBufferRef, key: CFStringRef?): CFStringRef? =
            CVBufferCopyAttachment(buffer, key, null)?.let { it as CFStringRef }
        val buffer = assertNotNull(copyIntoPixelBuffer(picture(), fullRange = false, colorSpace = bt709))
        try {
            assertTrue(same(attachment(buffer, kCVImageBufferColorPrimariesKey), kCVImageBufferColorPrimaries_ITU_R_709_2))
            assertTrue(same(attachment(buffer, kCVImageBufferTransferFunctionKey), kCVImageBufferTransferFunction_ITU_R_709_2))
            assertTrue(same(attachment(buffer, kCVImageBufferYCbCrMatrixKey), kCVImageBufferYCbCrMatrix_ITU_R_709_2))
        } finally {
            CVPixelBufferRelease(buffer)
        }
        // A standard definition frame that states nothing gets the 601 matrix, not a guess of 709.
        val sd = assertNotNull(copyIntoPixelBuffer(picture(720, 480), fullRange = false, colorSpace = unstated))
        try {
            assertTrue(same(attachment(sd, kCVImageBufferYCbCrMatrixKey), kCVImageBufferYCbCrMatrix_ITU_R_601_4))
            assertTrue(same(attachment(sd, kCVImageBufferColorPrimariesKey), kCVImageBufferColorPrimaries_SMPTE_C))
        } finally {
            CVPixelBufferRelease(sd)
        }
    }

    private class TestFrame(override val colorSpace: ColorSpaceInfo) : VideoFrame {
        override val pts = Pts.Zero
        override val duration: Pts? = null
        override val size = VideoSize(64, 64, 1, 1)
        override val pixelFormat = PlayerPixelFormat.Yuv420p
        override val hardwareSurface: HwSurfaceKind? = null
        override val generation = Generation.Initial
        override fun close() = Unit
    }

    private class FixedHeadroom : HeadroomSource {
        override val potential = 1f
        override val current = 1f
        override val known = true
        override fun refresh() = Unit
        override fun close() = Unit
    }

    /** The name of the layer's colour space once [frame] has gone through the renderer. */
    private fun layerSpaceAfter(vararg frames: VideoFrame): List<Pair<String, Long>?> = runBlocking {
        val layer = CAMetalLayer()
        layer.setDrawableSize(CGSizeMake(64.0, 64.0))
        assertNull(layer.colorspace, "a new layer has no colour space, so macOS matches nothing")
        val renderer = MetalVideoRenderer(layer, MetalPictureResolver { picture() }) { FixedHeadroom() }
        try {
            frames.map { frame ->
                val before = renderer.presentedFrames + renderer.failedFrames
                renderer.present(frame, 0L)
                withTimeoutOrNull(10.seconds) {
                    while (renderer.presentedFrames + renderer.failedFrames == before) delay(5)
                }
                layer.colorspace?.let { space ->
                    val name = CGColorSpaceCopyName(space)
                    val text = name?.let { platform.Foundation.CFBridgingRelease(it) as String? } ?: "unnamed"
                    text to space.rawValue.toLong()
                }
            }
        } finally {
            renderer.close()
        }
    }

    @Test
    fun theLayerIsTaggedWithThePicturesColourAndFollowsItFromFrameToFrame() {
        val names = layerSpaceAfter(
            TestFrame(bt709),
            TestFrame(ColorSpaceInfo(ColorMatrix.Bt2020Ncl, ColorPrimaries.Bt2020, ColorTransfer.Bt2020Ten)),
            TestFrame(pq),
        )
        val (hd, wide, toneMapped) = names.map { assertNotNull(it, "the layer has no colour space") }
        assertEquals("kCGColorSpaceCoreMedia709", hd.first, "BT.709 video")
        // BT.2020 video, then HDR tone mapped to gamma 2.2 on BT.709 primaries: each its own space.
        assertEquals(3, setOf(hd.second, wide.second, toneMapped.second).size, "the layer kept one space for three encodings")
    }
}
