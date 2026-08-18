package io.github.yuroyami.kiteplayer.spi

import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.HwdecStatus
import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.VideoSize

/**
 * A decoded video frame.
 *
 * **The pixels are not in Kotlin memory and this interface gives no way to read them.** A frame is
 * a handle to whatever the decoder produced: an `AVFrame`, a `CVPixelBuffer`, a MediaCodec output
 * buffer, a VA surface, a browser `VideoFrame`.
 *
 * That is not an omission. A 1080p frame in `yuv420p` is 3.11 MB and a 4K 10-bit frame is 24.9 MB.
 * Copying either into a `ByteArray` sixty times a second is between 187 MB/s and 1.5 GB/s of pure
 * waste, plus an allocation per frame, and the destination is a GPU texture that could have been
 * filled from the original pointer. Reading pixels is a renderer's job, and a renderer is chosen to
 * match the decoder that produced the frame, so it knows how.
 *
 * **Ownership.** Whoever receives a frame closes it. Exactly once. A frame may be moved between
 * threads but must not be used from two at once. [VideoRenderer.present] takes ownership, including
 * when it fails.
 */
public interface VideoFrame : AutoCloseable {
    public val pts: Pts

    /** The decoder's own duration for this frame, when it has one. */
    public val duration: Pts?

    public val size: VideoSize
    public val pixelFormat: PlayerPixelFormat
    public val colorSpace: ColorSpaceInfo

    /**
     * Clockwise rotation a renderer applies before the picture is shown, in degrees.
     *
     * Phones write this into every recording they make in portrait, and a player that ignores it shows
     * the whole video on its side. It is a presentation instruction and not a property of the pixels,
     * which is why it travels here next to [colorSpace] rather than inside [size]: [size] is the size
     * the frame is stored at, so a quarter turn produces an output whose width and height are swapped
     * while [size] still reads the other way round. A non-square pixel aspect stretches the stored
     * width, so after a quarter turn that stretch applies to the output's height.
     *
     * Real media produces 0, 90, 180 and 270. A renderer draws any other value unrotated rather than
     * refusing the frame, because a picture the right way up matters more than an exact affine
     * transform. Mirrored and arbitrarily skewed display matrices are not modelled; see the roadmap in
     * KPKMP-PAST.md section 11.
     */
    public val rotationDegrees: Int get() = 0

    /** Set when the frame lives in GPU or hardware memory and needs a matching renderer. */
    public val hardwareSurface: HwSurfaceKind?

    /** The epoch this frame belongs to. A frame from a superseded generation is never presented. */
    public val generation: Generation
}

/**
 * A frame whose pixels can be read, for the cases that genuinely need them: a screenshot, a
 * software renderer of last resort, a video thumbnail strip.
 *
 * This is not the render path. Every method here copies.
 */
public interface SoftwareReadableFrame : VideoFrame {
    public val planeCount: Int

    /**
     * Bytes per row of plane [index], which is at least the plane's width in bytes and usually
     * more.
     *
     * A renderer that assumes stride equals width produces an image that skews diagonally. This is
     * the most common first bug in every new video renderer, so the value is a required part of the
     * interface rather than something a caller computes.
     */
    public fun planeStride(index: Int): Int

    public fun planeHeight(index: Int): Int

    public fun copyPlane(index: Int, into: ByteArray, offset: Int = 0)
}

public enum class PlayerPixelFormat(
    public val planes: Int,
    public val bitsPerComponent: Int,
    public val isPlanarYuv: Boolean,
) {
    Yuv420p(3, 8, true),
    Yuv422p(3, 8, true),
    Yuv444p(3, 8, true),
    Yuv420p10le(3, 10, true),
    Yuv422p10le(3, 10, true),
    Nv12(2, 8, true),
    P010le(2, 10, true),
    Rgba(1, 8, false),
    Bgra(1, 8, false),
    Rgb24(1, 8, false),
    /** The decoder produced something the engine does not model. Only a matching renderer can draw it. */
    Opaque(0, 0, false),
    ;

    public val isTenBitOrMore: Boolean get() = bitsPerComponent > 8
}

/**
 * The colour metadata a renderer must honour to produce a correct picture.
 *
 * Every field here changes what the viewer sees. Ignoring [matrix] shifts hues, most visibly on
 * saturated reds. Ignoring [fullRange] turns blacks grey and clips whites. Ignoring [chromaLocation]
 * bleeds colour half a pixel at sharp edges. None of this is subtle once it is wrong, and all of it
 * is already known at decode time.
 */
public data class ColorSpaceInfo(
    val matrix: ColorMatrix = ColorMatrix.Bt709,
    val primaries: ColorPrimaries = ColorPrimaries.Bt709,
    val transfer: ColorTransfer = ColorTransfer.Bt709,
    /** True for 0 to 255, false for the 16 to 235 studio range. Most video is studio range. */
    val fullRange: Boolean = false,
    val chromaLocation: ChromaLocation = ChromaLocation.Left,
    /** False only when the source did not declare a range and [fullRange] is a rendering fallback. */
    val rangeSpecified: Boolean = true,
) {
    /** True when the transfer function means high dynamic range. */
    public val isHdr: Boolean
        get() = transfer == ColorTransfer.Pq || transfer == ColorTransfer.Hlg

    public companion object {
        /** What a decoder should report when the container said nothing. See [guessFor]. */
        public val Unspecified: ColorSpaceInfo = ColorSpaceInfo(
            matrix = ColorMatrix.Unspecified,
            primaries = ColorPrimaries.Unspecified,
            transfer = ColorTransfer.Unspecified,
            chromaLocation = ChromaLocation.Unspecified,
            rangeSpecified = false,
        )

        /**
         * The conventional guess when a container declares nothing, which is common.
         *
         * Standard definition content is BT.601 and high definition is BT.709, split at 576 lines.
         * Every player uses this rule, and using a single default instead visibly wrongs one half
         * of the world's video.
         */
        public fun guessFor(height: Int): ColorSpaceInfo = if (height <= 576) {
            ColorSpaceInfo(
                ColorMatrix.Bt601,
                ColorPrimaries.Bt601,
                ColorTransfer.Bt601,
                rangeSpecified = false,
            )
        } else {
            ColorSpaceInfo(
                ColorMatrix.Bt709,
                ColorPrimaries.Bt709,
                ColorTransfer.Bt709,
                rangeSpecified = false,
            )
        }
    }
}

public enum class ColorMatrix {
    Unspecified,
    Bt601,
    Bt709,
    Fcc,
    Bt470bg,
    Smpte170m,
    Smpte240m,
    YCgCo,
    Bt2020Ncl,
    Bt2020Cl,
    ICtCp,
    Identity,
}

public enum class ColorPrimaries {
    Unspecified,
    Bt601,
    Bt709,
    Bt470m,
    Bt470bg,
    Smpte170m,
    Smpte240m,
    Film,
    Bt2020,
    SmpteSt428,
    DciP3,
    DisplayP3,
}

public enum class ColorTransfer {
    Unspecified,
    Bt601,
    Bt709,
    Srgb,
    Linear,
    Gamma22,
    Gamma28,
    Smpte240m,
    Log,
    LogSqrt,
    Iec6196624,
    Bt1361Ecg,
    Bt2020Ten,
    Bt2020Twelve,
    Pq,
    SmpteSt428,
    Hlg,
}

public enum class ChromaLocation { Unspecified, Left, Center, TopLeft, Top, BottomLeft, Bottom }

/**
 * What kind of hardware surface a frame holds, so a renderer can say whether it can draw it.
 *
 * Null [VideoFrame.hardwareSurface] means the pixels are CPU-readable or otherwise not tied to one of
 * these platform surfaces. A hardware decoder may still report [HwdecStatus.HardwareWithDownload]
 * while returning such frames, because it copied decoded pixels back to main memory.
 */
public enum class HwSurfaceKind {
    /** Apple `CVPixelBuffer` backed by an `IOSurface`. Reaches Metal with no copy. */
    CoreVideoPixelBuffer,

    /**
     * An Android MediaCodec output buffer.
     *
     * Presenting one means releasing it with the render flag set, which makes MediaCodec draw
     * straight into the surface the codec was configured with. No texture, no shader, no GL code.
     * The cost is that such a frame cannot be read back, filtered or screenshotted.
     */
    MediaCodecBuffer,

    /** An Android `HardwareBuffer` that a GPU renderer can sample without a CPU pixel copy. */
    AndroidHardwareBuffer,

    /** A VA-API surface, reaching EGL through a dmabuf export. */
    VaapiSurface,

    /** A Direct3D 11 texture, used directly as a shader resource. */
    D3d11Texture,

    /** A CUDA device pointer. */
    CudaDevicePointer,

    /** A browser `VideoFrame`, drawn straight to a canvas. */
    WebVideoFrame,
}
