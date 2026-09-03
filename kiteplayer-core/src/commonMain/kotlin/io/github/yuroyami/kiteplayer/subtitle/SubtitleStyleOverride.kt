package io.github.yuroyami.kiteplayer.subtitle

/**
 * The viewer's word over the author's: fields set here replace the authored style on every span
 * of every text cue, and null fields keep the authored value. Bitmap subtitles are authored
 * pixels, not text, and pass through untouched.
 *
 * ASS scripts keep their authored look unless a field is named. That is by design: the person
 * who cannot read yellow-on-white outranks the person who typeset it.
 */
public data class SubtitleStyleOverride(
    val fontFamily: String? = null,
    val fontSizePx: Float? = null,
    val primaryColor: Int? = null,
    val outlineColor: Int? = null,
    val outlineWidthPx: Float? = null,
    val shadowColor: Int? = null,
    val shadowOffsetPx: Float? = null,
    /** ARGB. A box drawn behind each line, padded by [backgroundPaddingPx]. Transparent draws nothing. */
    val backgroundColor: Int? = null,
    val backgroundPaddingPx: Float = 4f,
    val bold: Boolean? = null,
    val italic: Boolean? = null,
) {
    init {
        require(backgroundPaddingPx.isFinite() && backgroundPaddingPx >= 0f) {
            "backgroundPaddingPx must be finite and at least zero, was $backgroundPaddingPx"
        }
    }
}
