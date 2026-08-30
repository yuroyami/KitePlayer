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
 *
 * ## What the built-in rasterizers actually apply
 *
 * A parser fills every field it can read. The three built-in rasterizers do NOT all use every one,
 * so this is what a consumer can rely on. Measured against the tree 2026-08-30.
 *
 * | Field | Desktop | Apple | Android |
 * |---|---|---|---|
 * | [primaryColor], [bold], [italic], [underline], [strikeThrough] | per span | per span | per span |
 * | [fontSizePx], [outlineColor], [outlineWidthPx] | per span | per span | per span |
 * | [fontFamily] | per span | per span | per span |
 * | [shadowColor], [shadowOffsetPx] | first span, whole cue | first span, whole cue | first span, whole cue |
 *
 * "First span, whole cue" means a cue whose spans disagree renders with the FIRST span's value
 * everywhere. Only the shadow works that way now: it changes the cue's bitmap SIZE, so two spans
 * asking for different shadows would be asking for two different layouts of one cue.
 *
 * The optional libass renderer is not covered by this table: it does its own ASS styling and reads
 * the original script rather than this type.
 */
public data class CueStyle(
    /**
     * The face to use. A family this system does not have falls back to the platform default,
     * so a script naming a font only its author had still reads.
     */
    val fontFamily: String? = null,
    val fontSizePx: Float? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strikeThrough: Boolean = false,
    val primaryColor: Int = 0xFFFFFFFF.toInt(),
    val outlineColor: Int = 0xFF000000.toInt(),
    /**
     * The drop shadow's colour. A fully transparent one turns the shadow off, and costs nothing.
     *
     * Taken from the cue's FIRST span and applied to all of them.
     */
    val shadowColor: Int = 0x80000000.toInt(),
    val outlineWidthPx: Float = 2f,
    /**
     * How far down and right the shadow falls. Negative reaches up and left; zero turns it off.
     *
     * The cue's bitmap grows by this much to hold the shadow, and the placement still measures
     * the TEXT, so turning a shadow on never moves the words. Taken from the cue's FIRST span.
     */
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
    /**
     * How lines break. All three built-in rasterizers honour it.
     *
     * Each of them still uses its own platform line breaker, so shaping and bidi stay the
     * platform's; this only decides the WIDTH that breaker is given. See [CueWrap].
     */
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
    /**
     * Break at the safe width, greedily.
     *
     * Every line is filled before the next one starts, so a cue that spills by one word reads as
     * a full line and a lonely word under it.
     */
    None,

    /**
     * Break at the narrowest width that still uses the same number of lines, so they come out
     * near-equal. The default, and what viewers expect.
     */
    Balanced,

    /**
     * Never break automatically. Only the author's own line breaks apply.
     *
     * A cue wider than the picture is drawn centred and clipped at both edges rather than
     * shrunk: not breaking is what was asked for.
     */
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
 * PREMULTIPLIED RGBA8888, row major, no padding.
 *
 * Premultiplied is what both platform rasterizers actually produce (Android's ARGB_8888 copy
 * and CoreGraphics' premultiplied context) and what the Compose and Metal consumers upload
 * without conversion. The 2026-08-17 audit found this doc claiming straight alpha while half
 * the consumers premultiplied AGAIN, turning white 50% text grey; one written contract with
 * raw-copy consumers is the fix. A producer that computes straight colour premultiplies once
 * at emit and documents it, as the libass renderer does.
 */
public class RgbaBitmap(
    public val width: Int,
    public val height: Int,
    /**
     * SHARED, not copied. A renderer reads these bytes once per frame, and copying a bitmap that
     * often to protect against a caller who mutates it is the wrong trade. Treat it as read-only:
     * writing to it changes what every other consumer of the same cue draws.
     */
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
