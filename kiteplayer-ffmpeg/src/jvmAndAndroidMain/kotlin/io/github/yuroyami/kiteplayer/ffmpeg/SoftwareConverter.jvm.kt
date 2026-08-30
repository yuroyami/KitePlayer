package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.spi.ColorSpaceInfo

/**
 * Turns a decoded CPU-readable frame into tightly packed RGBA on JVM and Android.
 *
 * KiteFFmpeg removes every source-row padding byte while copying, so the shared conversion kernel
 * derives its offsets and strides from the declared format. The native actual keeps its zero-copy
 * plane reads; this actual deliberately performs the one unavoidable JNI-to-Kotlin copy exactly once.
 */
public object SoftwareConverter {

    /**
     * Converts [frame] to `width * height * 4` RGBA bytes with no row padding.
     *
     * A VideoToolbox frame converts through its downloaded software twin (S2.b), one measured
     * copy per frame, which is exactly what HardwareWithDownload reports upstream. Hardware
     * kinds that cannot be read back still refuse inside [KiteFFmpegVideoFrame.readableFrame].
     */
    public fun toRgba(frame: KiteFFmpegVideoFrame): ByteArray {
        val readable = frame.readableFrame()
        val info = readable.info
        return tightlyPackedToRgba(
            bytes = readable.copyPlanesToByteArray(),
            width = frame.size.width,
            height = frame.size.height,
            pixelFormat = info.pixelFormat.toPlayerFormat(),
            colorSpace = info.color.toPlayerColorSpace(),
        )
    }

    /**
     * Whether [toRgba] will roll a frame with this colour off to SDR.
     *
     * A renderer publishes `RendererEvent.ToneMapEngaged` on the strength of THIS, never on the
     * strength of the stream's metadata: a renderer that shows HDR as HDR must stay quiet, and
     * asking the converter is the only way to tell the two apart.
     */
    public fun toneMapsHdr(colorSpace: ColorSpaceInfo): Boolean = toneMapsHdrColor(colorSpace)
}
