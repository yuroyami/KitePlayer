package io.github.yuroyami.kiteplayer.spi

import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.HwdecStatus
import io.github.yuroyami.kiteplayer.PictureCrop
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
    /** The media time at which this frame is shown. */
    public val pts: Pts

    /** The decoder's own duration for this frame, when it has one. */
    public val duration: Pts?

    /** The size the frame is stored at, before [rotationDegrees] turns it. */
    public val size: VideoSize
    /** How the pixels are laid out in memory. */
    public val pixelFormat: PlayerPixelFormat
    /** The colour metadata a renderer must honour. */
    public val colorSpace: ColorSpaceInfo

    /**
     * The static HDR metadata of this frame, or of its stream when the frame carries none, or null.
     * A renderer that tone maps reads the content's peak from it.
     */
    public val hdr: HdrStaticMetadata? get() = null

    /**
     * The brightest level of this frame's scene in nits, from dynamic HDR metadata that travels
     * with the frame, such as Dolby Vision's level 1, or null when the frame carries none.
     *
     * [hdr] describes the whole title, so a tone mapper that knows only it compresses every scene
     * for the title's brightest highlight. A dark scene then loses brightness it never needed to
     * give up. [toneMapPeakNits] is the peak a tone mapper rolls off from, with this taken first.
     */
    public val sceneMaxNits: Float? get() = null

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
     * transform. A display matrix that also mirrors the picture sets [mirrored] as well. Skewed
     * matrices are not modelled.
     */
    public val rotationDegrees: Int get() = 0

    /**
     * True when the display matrix also mirrors the picture, as a front camera can record it.
     *
     * A renderer mirrors the stored picture left to right first, and then turns it clockwise by
     * [rotationDegrees]. So an upside-down mirror arrives as a mirror and a half turn. Like the turn,
     * the mirror is a presentation instruction: the pixels and [size] stay as stored.
     */
    public val mirrored: Boolean get() = false

    /**
     * The edges of the stored picture that are not part of the image, as its container states, or
     * null when it states none (#497). See [PictureCrop].
     *
     * Like the turn and the mirror, the crop is a presentation instruction: the pixels and [size]
     * stay as stored, and a renderer draws only the rectangle the crop leaves, as a source
     * rectangle, so a hardware frame is cropped with no copy. The crop comes first, before the
     * mirror and the turn, and [visibleSize] is what the fit, the zoom and the overlay layout use.
     * A decoder never hands out a crop that does not [fit][PictureCrop.fits] its frame: it drops
     * such a crop with [io.github.yuroyami.kiteplayer.PlaybackWarning.CropIgnored].
     */
    public val crop: PictureCrop? get() = null

    /** Set when the frame lives in GPU or hardware memory and needs a matching renderer. */
    public val hardwareSurface: HwSurfaceKind?

    /** The epoch this frame belongs to. A frame from a superseded generation is never presented. */
    public val generation: Generation
}

/**
 * The size of the picture this frame shows: [VideoFrame.size] with [VideoFrame.crop]'s edges taken
 * away, before the turn, which still swaps width and height for a renderer.
 */
public val VideoFrame.visibleSize: VideoSize get() = size.cropped(crop)

/**
 * A frame whose pixels can be read, for the cases that genuinely need them: a screenshot, a
 * software renderer of last resort, a video thumbnail strip.
 *
 * This is not the render path. Every method here copies.
 */
public interface SoftwareReadableFrame : VideoFrame {
    /**
     * The pixel format of the planes [copyPlane] reads.
     *
     * It is [pixelFormat] for a frame in main memory. A hardware frame that is read through a
     * downloaded copy, such as a VideoToolbox frame, reports the copy's format here, while its
     * [pixelFormat] stays [PlayerPixelFormat.Opaque] for the renderers.
     */
    public val planeFormat: PlayerPixelFormat get() = pixelFormat

    /** How many planes [copyPlane] can read. */
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

    /** Rows in plane [index]. A subsampled chroma plane has fewer rows than the picture. */
    public fun planeHeight(index: Int): Int

    /** Copies plane [index] into [into] from [offset]: [planeStride] times [planeHeight] bytes. */
    public fun copyPlane(index: Int, into: ByteArray, offset: Int = 0)
}

/** A pixel layout the engine models. */
public enum class PlayerPixelFormat(
    /** Planes in memory. */
    public val planes: Int,
    /** Significant bits in each sample. */
    public val bitsPerComponent: Int,
    /** True for luma and chroma formats, planar or semi-planar, and false for packed RGB. */
    public val isPlanarYuv: Boolean,
) {
    Yuv420p(3, 8, true),
    Yuv422p(3, 8, true),

    /** Under [ColorMatrix.Identity] the three planes hold G, B and R. Video coded as RGB arrives so. */
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

    /** True when a sample carries more than eight bits. */
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
    /**
     * False when the source declared no matrix and [matrix] is this library's guess.
     *
     * The guess is right for nearly all video and wrong for the rest, so a consumer that has a
     * better answer (a container-level hint, a user override) can tell which fields it is allowed
     * to overrule and which are the file's own word. Every guess made anywhere is the
     * standard-versus-high-definition rule in [guessFor], so a guessed field is never HDR.
     */
    val matrixSpecified: Boolean = true,
    /** False when the source declared no primaries. See [matrixSpecified]. */
    val primariesSpecified: Boolean = true,
    /** False when the source declared no transfer function. See [matrixSpecified]. */
    val transferSpecified: Boolean = true,
) {
    /** True when the transfer function means high dynamic range. */
    public val isHdr: Boolean
        get() = transfer == ColorTransfer.Pq || transfer == ColorTransfer.Hlg

    /**
     * True when every field here is the source's own word rather than a guess.
     *
     * Useful for the one question a consumer actually asks: may I trust this, or should I look
     * somewhere else first?
     */
    public val allSpecified: Boolean
        get() = matrixSpecified && primariesSpecified && transferSpecified && rangeSpecified

    /** The values a decoder reports when the source says nothing. */
    public companion object {
        /** What a decoder should report when the container said nothing. See [guessFor]. */
        public val Unspecified: ColorSpaceInfo = ColorSpaceInfo(
            matrix = ColorMatrix.Unspecified,
            primaries = ColorPrimaries.Unspecified,
            transfer = ColorTransfer.Unspecified,
            chromaLocation = ChromaLocation.Unspecified,
            rangeSpecified = false,
            matrixSpecified = false,
            primariesSpecified = false,
            transferSpecified = false,
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
                matrixSpecified = false,
                primariesSpecified = false,
                transferSpecified = false,
            )
        } else {
            ColorSpaceInfo(
                ColorMatrix.Bt709,
                ColorPrimaries.Bt709,
                ColorTransfer.Bt709,
                rangeSpecified = false,
                matrixSpecified = false,
                primariesSpecified = false,
                transferSpecified = false,
            )
        }
    }
}

/** How Y, Cb and Cr derive from R, G and B, with the matrices ITU-T H.273 names. */
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

    /** No matrix: the three planes hold G, B and R rather than Y, Cb and Cr. */
    Identity,
}

/** The red, green and blue the picture was mastered with, as ITU-T H.273 names them. */
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

/** How coded values map to light, with the transfer characteristics ITU-T H.273 names. */
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

/** Where a subsampled chroma sample sits relative to the luma samples it covers. */
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

/**
 * The content peak a tone mapper rolls this frame off from, in nits: the scene's brightest level
 * when the frame carries one between 100 and 10000 nits, held at most at the title's own peak, else
 * the title's peak from [VideoFrame.hdr], else null for the 1000 nits a PQ master is assumed to have.
 */
public val VideoFrame.toneMapPeakNits: Float?
    get() {
        val title = hdr?.peakNits
        val scene = sceneMaxNits?.takeIf { it in 100f..10_000f } ?: return title
        return if (title != null) minOf(scene, title) else scene
    }
