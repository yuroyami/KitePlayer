package io.github.yuroyami.kiteplayer.spi

/**
 * What a decoder's frames will be, known before the first frame exists.
 *
 * A decoder says it through [VideoDecoder.output], and a renderer answers [VideoRenderer.accepts].
 * So the engine can pass over a decoder whose frames the attached renderer cannot show, and refuse
 * a renderer that cannot show the running decoder's frames, before a blank picture says so.
 */
public sealed interface FrameShape {
    /** How the pixels are laid out, as [VideoFrame.pixelFormat] will report them. */
    public val pixelFormat: PlayerPixelFormat

    /** Planes in main memory, which a renderer that supports [pixelFormat] can upload. */
    public data class Memory(override val pixelFormat: PlayerPixelFormat) : FrameShape

    /**
     * Frames that live on a hardware surface of [kind], as [VideoFrame.hardwareSurface] will report
     * it, with no copy in main memory: only a renderer that presents that surface can show them. A
     * hardware decoder whose frames can also be read as planes does not declare this shape, because
     * a renderer without the surface can still show them.
     */
    public data class Surface(val kind: HwSurfaceKind, override val pixelFormat: PlayerPixelFormat) : FrameShape
}
