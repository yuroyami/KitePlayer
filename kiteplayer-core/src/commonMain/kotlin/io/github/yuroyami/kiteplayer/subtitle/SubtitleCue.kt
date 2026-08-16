package io.github.yuroyami.kiteplayer.subtitle

/**
 * One subtitle event with a time window.
 *
 * Text subtitles and bitmap subtitles are genuinely different things. A text cue carries words and
 * styling and must be laid out for the current viewport. A bitmap cue carries pixels that were
 * authored for a fixed video size and must be placed and scaled. Unifying them at this level, and
 * not inside a renderer, is what keeps a plain SRT file from needing a font engine.
 */
public sealed interface SubtitleCue {
    /** When the cue becomes visible, in microseconds on the media timeline. */
    public val startMicros: Long

    /** When the cue stops being visible. See [SubtitleTrackState] for how open ends are resolved. */
    public val endMicros: Long

    /** Ordering hint from the source. Higher values are drawn on top. */
    public val layer: Int

    public data class Text(
        override val startMicros: Long,
        override val endMicros: Long,
        val spans: List<StyledSpan>,
        val layout: CueLayout = CueLayout(),
        override val layer: Int = 0,
    ) : SubtitleCue {
        /** The cue's text with all styling removed. Useful for accessibility and for logging. */
        public val plainText: String get() = spans.joinToString("") { it.text }
    }

    public data class Bitmap(
        override val startMicros: Long,
        override val endMicros: Long,
        val regions: List<BitmapRegion>,
        override val layer: Int = 0,
    ) : SubtitleCue
}

/** A run of text sharing one style. A cue is a list of these so that inline styling works. */
public data class StyledSpan(
    val text: String,
    val style: CueStyle = CueStyle(),
)

/**
 * Visual style for a run of text.
 *
 * Sizes are in the authoring resolution's pixels when [CueLayout.authoredHeight] is set, and in
 * points otherwise. Colours are ARGB with a non-premultiplied alpha, which is the form every
 * subtitle format uses.
 */
public data class CueStyle(
    val fontFamily: String? = null,
    val fontSizePx: Float? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strikeThrough: Boolean = false,
    val primaryColor: Int = 0xFFFFFFFF.toInt(),
    val outlineColor: Int = 0xFF000000.toInt(),
    val shadowColor: Int = 0x80000000.toInt(),
    val outlineWidthPx: Float = 2f,
    val shadowOffsetPx: Float = 1f,
)

/** Where a cue goes. */
public data class CueLayout(
    val alignment: CueAlignment = CueAlignment.BottomCenter,
    /** Fraction of the video width to keep clear on the left. */
    val marginLeft: Float = 0.05f,
    val marginRight: Float = 0.05f,
    /** Fraction of the video height to keep clear at the bottom, or top for top alignments. */
    val marginVertical: Float = 0.05f,
    /**
     * Explicit position as a fraction of the video size, from an ASS `\pos` tag or a WebVTT
     * setting. Overrides [alignment] when set.
     */
    val positionX: Float? = null,
    val positionY: Float? = null,
    /**
     * The video height the cue's pixel sizes were authored against, when the format declares one.
     * Layout scales by the ratio of the current viewport to this, so a subtitle authored for 720p
     * looks the same on a 4K display.
     */
    val authoredHeight: Int? = null,
    val wrap: CueWrap = CueWrap.Balanced,
    /**
     * Linear fade lengths from an ASS `\fad` tag, in microseconds from the cue's edges.
     * Renderers that cannot animate ignore them; the cue still shows and hides on time.
     */
    val fadeInMicros: Long = 0,
    val fadeOutMicros: Long = 0,
)

public enum class CueAlignment {
    BottomLeft, BottomCenter, BottomRight,
    MiddleLeft, MiddleCenter, MiddleRight,
    TopLeft, TopCenter, TopRight,
}

public enum class CueWrap {
    /** Break at the width limit only. */
    None,

    /** Break at the width limit, preferring even line lengths. What viewers expect. */
    Balanced,

    /** Never break automatically. Only explicit line breaks apply. */
    Never,
}

/** A pre-rendered subtitle image, positioned in the video's own coordinate space. */
public data class BitmapRegion(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    /** The size the coordinates above are relative to. */
    val canvasWidth: Int,
    val canvasHeight: Int,
    val bitmap: RgbaBitmap,
)

/**
 * Straight RGBA8888, row major, no padding, non-premultiplied alpha.
 *
 * Non-premultiplied is chosen because every subtitle source produces it, so converting once at
 * the renderer is cheaper than converting at every decoder. Renderers that need premultiplied
 * alpha convert on upload and document it.
 */
public class RgbaBitmap(
    public val width: Int,
    public val height: Int,
    public val pixels: ByteArray,
) {
    init {
        require(width > 0 && height > 0) { "bitmap must have positive dimensions, got ${width}x$height" }
        // In Long: width * height * 4 wraps Int at 23170x23170, and a wrapped requirement admits
        // a short array that every consumer then reads past.
        val needed = width.toLong() * height.toLong() * 4L
        require(pixels.size.toLong() >= needed) {
            "bitmap needs $needed bytes for ${width}x$height, got ${pixels.size}"
        }
    }
}
