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
import io.github.yuroyami.kiteplayer.spi.OverlayImage
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.spi.SubtitleOverlay
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import io.github.yuroyami.kiteplayer.subtitle.RgbaBitmap
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.get
import kotlinx.cinterop.reinterpret
import platform.CoreFoundation.CFArrayGetValueAtIndex
import platform.CoreFoundation.CFBooleanGetValue
import platform.CoreFoundation.CFBooleanRef
import platform.CoreFoundation.CFDictionaryGetValue
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreMedia.CMSampleBufferGetImageBuffer
import platform.CoreMedia.CMSampleBufferGetSampleAttachmentsArray
import platform.CoreMedia.CMSampleBufferRef
import platform.CoreMedia.kCMSampleAttachmentKey_DisplayImmediately
import platform.CoreVideo.CVPixelBufferGetBaseAddress
import platform.CoreVideo.CVPixelBufferGetBytesPerRow
import platform.CoreVideo.CVPixelBufferLockBaseAddress
import platform.CoreVideo.CVPixelBufferRef
import platform.CoreVideo.CVPixelBufferUnlockBaseAddress
import kotlin.test.assertNotNull

// What the sample buffer renderer tests share: the Apple tests and the macOS Metal tests.

/** One flat colour as NV12: limited range 709 with these values is a strong red. */
internal fun redNv12(width: Int, height: Int): MetalPicture.SoftwarePlanes = MetalPicture.SoftwarePlanes(
    width = width,
    height = height,
    format = PlayerPixelFormat.Nv12,
    planes = listOf(
        MetalPicture.SoftwarePlanes.Plane(ByteArray(width * height) { 63 }, width, height),
        MetalPicture.SoftwarePlanes.Plane(
            ByteArray(width * height / 2) { at -> if (at % 2 == 0) 102.toByte() else 240.toByte() },
            width,
            height / 2,
        ),
    ),
)

internal val limited709: ColorSpaceInfo = ColorSpaceInfo(
    matrix = ColorMatrix.Bt709,
    primaries = ColorPrimaries.Bt709,
    transfer = ColorTransfer.Bt709,
    fullRange = false,
)

/** An 8 by 8 white square in the middle of a 64 by 64 viewport. */
internal fun whiteSquare(hash: Long = 42L): SubtitleOverlay = SubtitleOverlay(
    images = listOf(
        OverlayImage(x = 28, y = 28, bitmap = RgbaBitmap(8, 8, ByteArray(8 * 8 * 4) { 0xFF.toByte() })),
    ),
    viewportWidth = 64,
    viewportHeight = 64,
    contentHash = hash,
)

internal fun noText(hash: Long = 7L): SubtitleOverlay = SubtitleOverlay(emptyList(), 64, 64, contentHash = hash)

internal fun imageOf(sample: CMSampleBufferRef): CVPixelBufferRef =
    assertNotNull(CMSampleBufferGetImageBuffer(sample), "the sample carries no image")

internal fun displaysImmediately(sample: CMSampleBufferRef): Boolean {
    val attachments = CMSampleBufferGetSampleAttachmentsArray(sample, createIfNecessary = false) ?: return false
    val first: CFDictionaryRef = CFArrayGetValueAtIndex(attachments, 0)?.reinterpret() ?: return false
    val value: CFBooleanRef = CFDictionaryGetValue(first, kCMSampleAttachmentKey_DisplayImmediately)
        ?.reinterpret() ?: return false
    return CFBooleanGetValue(value)
}

/** Blue, green, red and alpha of one pixel of a BGRA buffer. */
internal fun bgraAt(buffer: CVPixelBufferRef, x: Int, y: Int): IntArray {
    CVPixelBufferLockBaseAddress(buffer, 0uL)
    try {
        val base = assertNotNull(CVPixelBufferGetBaseAddress(buffer)).reinterpret<ByteVar>()
        val at = y.toLong() * CVPixelBufferGetBytesPerRow(buffer).toLong() + x * 4
        return IntArray(4) { channel -> base[at + channel].toInt() and 0xFF }
    } finally {
        CVPixelBufferUnlockBaseAddress(buffer, 0uL)
    }
}

/** Keeps every sample it is given, so a test can read what the layer would have shown. */
internal class RecordingSampleSink : SampleSink {
    val samples = mutableListOf<CMSampleBufferRef>()
    var flushes = 0
        private set

    override fun enqueue(sample: CMSampleBufferRef) {
        samples += sample
    }

    override fun flushAndRemoveImage() {
        flushes++
    }

    fun release() {
        samples.forEach { CFRelease(it) }
        samples.clear()
    }
}

/** The smallest frame the renderer's contract accepts. Its pixels come from the resolver. */
internal class SampleTestFrame(
    width: Int = 4,
    height: Int = 4,
    override val colorSpace: ColorSpaceInfo = ColorSpaceInfo(fullRange = true),
) : VideoFrame {
    var closed: Boolean = false
        private set

    override val pts: Pts = Pts(0)
    override val duration: Pts? = null
    override val size: VideoSize = VideoSize(width, height)
    override val pixelFormat: PlayerPixelFormat = PlayerPixelFormat.Yuv420p
    override val hardwareSurface: HwSurfaceKind? = null
    override val generation: Generation = Generation.Initial

    override fun close() {
        closed = true
    }
}
