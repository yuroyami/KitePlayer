package io.github.yuroyami.kiteplayer.ffmpeg

/**
 * Turns a decoded CPU-readable frame into tightly packed RGBA on JVM and Android.
 *
 * KiteCodec removes every source-row padding byte while copying, so the shared conversion kernel
 * derives its offsets and strides from the declared format. The native actual keeps its zero-copy
 * plane reads; this actual deliberately performs the one unavoidable JNI-to-Kotlin copy exactly once.
 */
public object SoftwareConverter {

    /** Converts [frame] to `width * height * 4` RGBA bytes with no row padding. */
    public fun toRgba(frame: KiteCodecVideoFrame): ByteArray {
        check(frame.hardwareSurface == null) { "a hardware frame needs its matching renderer" }
        val copiedPlanes = frame.frame.copyPlanesToByteArray()
        return tightlyPackedToRgba(
            bytes = copiedPlanes,
            width = frame.size.width,
            height = frame.size.height,
            pixelFormat = frame.pixelFormat,
            colorSpace = frame.colorSpace,
        )
    }
}
