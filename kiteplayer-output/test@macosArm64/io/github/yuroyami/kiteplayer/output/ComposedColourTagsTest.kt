@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.VideoSize
import io.github.yuroyami.kiteplayer.VideoTransform
import io.github.yuroyami.kiteplayer.spi.ColorMatrix
import io.github.yuroyami.kiteplayer.spi.ColorPrimaries
import io.github.yuroyami.kiteplayer.spi.ColorSpaceInfo
import io.github.yuroyami.kiteplayer.spi.ColorTransfer
import io.github.yuroyami.kiteplayer.spi.HdrStaticMetadata
import io.github.yuroyami.kiteplayer.spi.HwSurfaceKind
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import io.github.yuroyami.kiteplayer.spi.toneMapPeakNits
import kotlinx.coroutines.runBlocking
import platform.CoreFoundation.CFRetain
import platform.CoreFoundation.CFStringRef
import platform.CoreMedia.CMFormatDescriptionGetExtension
import platform.CoreMedia.CMSampleBufferGetFormatDescription
import platform.CoreMedia.kCMFormatDescriptionExtension_ColorPrimaries
import platform.CoreMedia.kCMFormatDescriptionExtension_TransferFunction
import platform.CoreVideo.CVBufferCopyAttachment
import platform.CoreVideo.CVPixelBufferRef
import platform.CoreVideo.CVPixelBufferRelease
import platform.CoreVideo.kCVImageBufferColorPrimariesKey
import platform.CoreVideo.kCVImageBufferColorPrimaries_ITU_R_2020
import platform.CoreVideo.kCVImageBufferColorPrimaries_ITU_R_709_2
import platform.CoreVideo.kCVImageBufferGammaLevelKey
import platform.CoreVideo.kCVImageBufferTransferFunctionKey
import platform.CoreVideo.kCVImageBufferTransferFunction_ITU_R_709_2
import platform.CoreVideo.kCVImageBufferTransferFunction_UseGamma
import platform.CoreVideo.kCVImageBufferTransferFunction_sRGB
import platform.CoreVideo.kCVImageBufferYCbCrMatrixKey
import platform.Foundation.CFBridgingRelease
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * A picture the sample buffer renderer composes, for subtitles, a turn or the flash guard, says
 * what the composer wrote in it (#489): its own primaries and curve for standard range, the tone
 * mapped output for HDR, and no tag of the encoding a pooled buffer held before. The HDR peaks of
 * the frame reach the composer, so it rolls off from the scene's peak as the plain Metal path does.
 *
 * These need a Metal device, which the iOS simulator does not give a test process.
 */
class ComposedColourTagsTest {

    private val pq = ColorSpaceInfo(ColorMatrix.Bt2020Ncl, ColorPrimaries.Bt2020, ColorTransfer.Pq, fullRange = false)
    private val wideSdr = ColorSpaceInfo(ColorMatrix.Bt2020Ncl, ColorPrimaries.Bt2020, ColorTransfer.Bt2020Ten, fullRange = false)

    private fun name(value: CFStringRef?): String? = value?.let { CFBridgingRelease(CFRetain(it)) as String? }

    private fun attachment(buffer: CVPixelBufferRef, key: CFStringRef?): Any? =
        CVBufferCopyAttachment(buffer, key, null)?.let { CFBridgingRelease(it) }

    private fun assertTags(buffer: CVPixelBufferRef, primaries: CFStringRef?, transfer: CFStringRef?, gamma: Double?) {
        assertEquals(name(primaries), attachment(buffer, kCVImageBufferColorPrimariesKey), "primaries")
        assertEquals(name(transfer), attachment(buffer, kCVImageBufferTransferFunctionKey), "transfer")
        val level = when (val value = attachment(buffer, kCVImageBufferGammaLevelKey)) {
            is Number -> value.toDouble()
            is platform.Foundation.NSNumber -> value.doubleValue
            else -> null
        }
        // Core Video keeps the gamma as a float, so 2.2 reads back as 2.1999999992549419.
        if (gamma == null) assertNull(level, "gamma") else assertEquals(gamma, assertNotNull(level, "gamma"), 1e-6, "gamma")
        assertNull(attachment(buffer, kCVImageBufferYCbCrMatrixKey), "an RGB buffer has no matrix")
    }

    /** The colour of the sample a renderer with text over its picture gives for a frame in [colorSpace]. */
    private fun composedSample(colorSpace: ColorSpaceInfo, check: (CVPixelBufferRef, Any?, Any?) -> Unit) = runBlocking {
        val sink = RecordingSampleSink()
        val renderer = SampleBufferVideoRenderer(resolve = { redNv12(64, 64) }, sink = sink)
        try {
            renderer.setOverlay(whiteSquare())
            kotlin.test.assertTrue(renderer.present(SampleTestFrame(64, 64, colorSpace), targetNanos = 0L))
            val sample = sink.samples.single()
            val description = assertNotNull(CMSampleBufferGetFormatDescription(sample))
            val primaries = CMFormatDescriptionGetExtension(description, kCMFormatDescriptionExtension_ColorPrimaries)
                ?.let { CFBridgingRelease(CFRetain(it)) }
            val transfer = CMFormatDescriptionGetExtension(description, kCMFormatDescriptionExtension_TransferFunction)
                ?.let { CFBridgingRelease(CFRetain(it)) }
            check(imageOf(sample), primaries, transfer)
        } finally {
            renderer.close()
            sink.release()
        }
    }

    @Test
    fun aComposedStandardPictureKeepsItsOwnPrimariesAndCurve() = composedSample(limited709) { image, primaries, transfer ->
        assertTags(image, kCVImageBufferColorPrimaries_ITU_R_709_2, kCVImageBufferTransferFunction_ITU_R_709_2, gamma = null)
        assertEquals(name(kCVImageBufferColorPrimaries_ITU_R_709_2), primaries, "the sample's description says it too")
        assertEquals(name(kCVImageBufferTransferFunction_ITU_R_709_2), transfer)
    }

    @Test
    fun aComposedHdrPictureIsTaggedAsTheToneMappedOutput() = composedSample(pq) { image, primaries, transfer ->
        assertTags(image, kCVImageBufferColorPrimaries_ITU_R_709_2, kCVImageBufferTransferFunction_UseGamma, gamma = 2.2)
        assertEquals(name(kCVImageBufferColorPrimaries_ITU_R_709_2), primaries, "not the BT.2020 of the source")
        assertEquals(name(kCVImageBufferTransferFunction_UseGamma), transfer, "not the PQ of the source")
    }

    @Test
    fun aPooledBufferLosesTheTagsOfTheEncodingItHeldBefore() {
        val burner = assertNotNull(SubtitleBurner.createOrNull(), "no Metal device")
        val source = assertNotNull(copyIntoPixelBuffer(redNv12(64, 64), fullRange = false, colorSpace = limited709))
        try {
            val toneMapped = assertNotNull(burner.burn(source, PictureFacts(VideoSize(64, 64), pq), whiteSquare()))
            assertTags(toneMapped, kCVImageBufferColorPrimaries_ITU_R_709_2, kCVImageBufferTransferFunction_UseGamma, gamma = 2.2)
            // Given back, so the pool hands it out again for the next picture.
            CVPixelBufferRelease(toneMapped)
            val wide = assertNotNull(burner.burn(source, PictureFacts(VideoSize(64, 64), wideSdr), whiteSquare()))
            println("the pool ${if (wide == toneMapped) "gave the same buffer again" else "gave another buffer"}")
            assertTags(wide, kCVImageBufferColorPrimaries_ITU_R_2020, kCVImageBufferTransferFunction_ITU_R_709_2, gamma = null)
            CVPixelBufferRelease(wide)
        } finally {
            CVPixelBufferRelease(source)
            burner.close()
        }
    }

    @Test
    fun textOnBlackIsTaggedAsTheRasterizersDrawIt() {
        val burner = assertNotNull(SubtitleBurner.createOrNull(), "no Metal device")
        try {
            val text = assertNotNull(burner.burnBackground(whiteSquare()))
            assertTags(text, kCVImageBufferColorPrimaries_ITU_R_709_2, kCVImageBufferTransferFunction_sRGB, gamma = null)
            CVPixelBufferRelease(text)
        } finally {
            burner.close()
        }
    }

    private class HdrFrame(override val hdr: HdrStaticMetadata?, override val sceneMaxNits: Float?) : VideoFrame {
        override val pts = Pts.Zero
        override val duration: Pts? = null
        override val size = VideoSize(64, 64)
        override val colorSpace = ColorSpaceInfo(ColorMatrix.Bt2020Ncl, ColorPrimaries.Bt2020, ColorTransfer.Pq)
        override val pixelFormat = PlayerPixelFormat.Nv12
        override val hardwareSurface: HwSurfaceKind? = null
        override val generation = Generation.Initial
        override fun close() = Unit
    }

    @Test
    fun theComposerRollsOffFromTheScenesPeakAsThePlainPathDoes() {
        val frame = HdrFrame(HdrStaticMetadata(maxContentLightNits = 4000), sceneMaxNits = 600f)
        assertEquals(600f, frame.toneMapPeakNits, "the plain Metal path reads the scene's peak")
        val facts = PictureFacts.of(frame)
        assertEquals(600f, facts.toneMapPeakNits, "the facts the composer reads keep it")
        assertEquals(600f, facts.orientedBy(VideoTransform(rotationDegrees = 90)).toneMapPeakNits, "and so does a turned copy")
        assertEquals(4000f, PictureFacts.of(HdrFrame(HdrStaticMetadata(maxContentLightNits = 4000), null)).toneMapPeakNits)
    }
}
