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
 * so this is what a consumer can rely on. Measured against the tree 2026-08-25 (SOL-S7).
 *
 * | Field | Desktop | Apple | Android |
 * |---|---|---|---|
 * | [primaryColor], [bold], [italic], [underline], [strikeThrough] | per span | per span | per span |
 * | [fontFamily] | per span | ignored | ignored |
 * | [fontSizePx], [outlineColor], [outlineWidthPx] | first span, whole cue | first span, whole cue | first span, whole cue |
 * | [shadowColor], [shadowOffsetPx] | ignored | ignored | ignored |
 *
 * "First span, whole cue" means a cue whose spans disagree renders with the FIRST span's value
 * everywhere, so mixed sizes or mixed outlines in one cue are flattened.
 *
 * The optional libass renderer is not covered by this table: it does its own ASS styling and reads
 * the original script rather than this type.
 */
public data class CueStyle(
    /** Honoured on desktop only; the Apple and Android rasterizers use the platform default face. */
    val fontFamily: String? = null,
    /** Taken from the cue's FIRST span and applied to all of them. */
    val fontSizePx: Float? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strikeThrough: Boolean = false,
    val primaryColor: Int = 0xFFFFFFFF.toInt(),
    /** Taken from the cue's FIRST span and applied to all of them. */
    val outlineColor: Int = 0xFF000000.toInt(),
    /**
     * Not drawn by any built-in rasterizer, so the default below is inert (SOL-S7).
     *
     * Kept because the parsers read it from the source and libass renders its own shadow. Drawing
     * one here means growing each cue's bitmap by the offset and moving its placement with it,
     * which is a change to layout rather than a colour, and is why this is stated instead of
     * quietly defaulted.
     */
    val shadowColor: Int = 0x80000000.toInt(),
    /** Taken from the cue's FIRST span and applied to all of them. */
    val outlineWidthPx: Float = 2f,
    /** Not drawn by any built-in rasterizer. See [shadowColor]. */
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
     * Not applied by any built-in rasterizer (SOL-S7).
     *
     * All three break at the safe width with their platform's own line breaker, so every cue wraps
     * that way whatever this says. It is parsed and carried because libass and any custom
     * [io.github.yuroyami.kiteplayer.spi.SubtitleRasterizer] can honour it.
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
