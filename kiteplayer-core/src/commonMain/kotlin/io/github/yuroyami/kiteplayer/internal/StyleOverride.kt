package io.github.yuroyami.kiteplayer.internal

import io.github.yuroyami.kiteplayer.subtitle.CueStyle
import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue
import io.github.yuroyami.kiteplayer.subtitle.SubtitleStyleOverride

/**
 * Applies the viewer's override to what is about to be rasterised. Pure, and null-cheap: with no
 * override the caller's list comes back as-is, so the steady state allocates nothing.
 */
internal fun applyOverride(
    cues: List<SubtitleCue>,
    override: SubtitleStyleOverride?,
): List<SubtitleCue> {
    if (override == null) return cues
    return cues.map { cue ->
        when (cue) {
            is SubtitleCue.Bitmap -> cue
            is SubtitleCue.Text -> cue.copy(
                spans = cue.spans.map { span -> span.copy(style = span.style.overridden(override)) },
            )
        }
    }
}

private fun CueStyle.overridden(o: SubtitleStyleOverride): CueStyle = copy(
    fontFamily = o.fontFamily ?: fontFamily,
    fontSizePx = o.fontSizePx ?: fontSizePx,
    primaryColor = o.primaryColor ?: primaryColor,
    outlineColor = o.outlineColor ?: outlineColor,
    outlineWidthPx = o.outlineWidthPx ?: outlineWidthPx,
    shadowColor = o.shadowColor ?: shadowColor,
    shadowOffsetPx = o.shadowOffsetPx ?: shadowOffsetPx,
    backgroundColor = o.backgroundColor ?: backgroundColor,
    backgroundPaddingPx = if (o.backgroundColor != null) o.backgroundPaddingPx else backgroundPaddingPx,
    bold = o.bold ?: bold,
    italic = o.italic ?: italic,
)
