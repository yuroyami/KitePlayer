package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteffmpeg.Frame as KiteFrame
import io.github.yuroyami.kiteplayer.spi.ColorSpaceInfo
import io.github.yuroyami.kiteplayer.spi.toneMapPeakNits

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
     * A VideoToolbox frame converts through its downloaded software twin, one measured
     * copy per frame, which is exactly what HardwareWithDownload reports upstream. Hardware
     * kinds that cannot be read back still refuse inside [KiteFFmpegVideoFrame.readableFrame].
     */
    public fun toRgba(frame: KiteFFmpegVideoFrame): ByteArray = toRgba(frame, reuse = null)

    /**
     * [toRgba] into [reuse] when it is exactly `width * height * 4` bytes, and into a new array
     * otherwise. A renderer that passes back the array it got for the previous frame allocates the
     * RGBA bytes once instead of once per frame. It must be done with that array first: the bytes
     * are overwritten in place.
     */
    public fun toRgba(frame: KiteFFmpegVideoFrame, reuse: ByteArray?): ByteArray {
        val readable = frame.readableFrame()
        return convert(frame, readable, readable.copyPlanesToByteArray(), reuse)
    }

    /**
     * [toRgba] with both of its arrays taken from [buffers]: the planes copied out of KiteFFmpeg,
     * and the RGBA bytes. A renderer that keeps one [Buffers] allocates neither array again until
     * the frame size changes. The returned array belongs to [buffers], and the next call with the
     * same [buffers] overwrites it.
     */
    public fun toRgba(frame: KiteFFmpegVideoFrame, buffers: Buffers): ByteArray {
        val readable = frame.readableFrame()
        val count = readable.planesByteCount()
        // Exactly the frame's size, so the kernel's short-frame check sees what it always saw.
        if (buffers.planes.size != count) buffers.planes = ByteArray(count)
        readable.copyPlanesInto(buffers.planes)
        return convert(frame, readable, buffers.planes, buffers.rgba).also { buffers.rgba = it }
    }

    /** The two arrays one renderer reuses from frame to frame. Use one instance from one thread at a time. */
    public class Buffers {
        internal var planes: ByteArray = ByteArray(0)
        internal var rgba: ByteArray? = null
    }

    private fun convert(frame: KiteFFmpegVideoFrame, readable: KiteFrame, planes: ByteArray, into: ByteArray?): ByteArray {
        val info = readable.info
        return tightlyPackedToRgba(
            bytes = planes,
            width = frame.size.width,
            height = frame.size.height,
            pixelFormat = info.pixelFormat.toPlayerFormat(),
            colorSpace = info.color.toPlayerColorSpace(info.pixelFormat),
            into = into,
            hdrPeakNits = frame.toneMapPeakNits,
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
