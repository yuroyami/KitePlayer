package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.subtitle.SubtitleStyleOverride

/**
 * How the system's caption settings draw the edge of each letter (#679). Android's
 * `CaptioningManager` and Apple's MediaAccessibility name the same five, and either may leave it
 * unset.
 */
internal enum class CaptionEdge { Unspecified, None, Outline, DropShadow, Raised, Depressed }

/**
 * The caption look the viewer chose in the system's settings, as an override (#679). Colours are
 * ARGB, and null where the system leaves the author's. An outline edge colours the outline and
 * draws no shadow; a drop shadow, raised or depressed edge draws a shadow and no outline, the
 * nearest a single shadow comes to the last two; no edge draws neither. A background colour boxes
 * each line, and the window colour, the box behind the whole cue on Android and Apple, stands in
 * for it when the system names no line background.
 */
internal fun systemCaptionOverride(
    foreground: Int?,
    background: Int?,
    window: Int?,
    edge: CaptionEdge,
    edgeColor: Int?,
    fontFamily: String?,
): SubtitleStyleOverride {
    val edgeOrBlack = edgeColor ?: BLACK
    return SubtitleStyleOverride(
        fontFamily = fontFamily,
        primaryColor = foreground,
        backgroundColor = background?.takeIf { it ushr 24 != 0 } ?: window?.takeIf { it ushr 24 != 0 } ?: background,
        outlineColor = if (edge == CaptionEdge.Outline) edgeOrBlack else null,
        outlineWidthPx = when (edge) {
            CaptionEdge.None, CaptionEdge.DropShadow, CaptionEdge.Raised, CaptionEdge.Depressed -> 0f
            CaptionEdge.Outline, CaptionEdge.Unspecified -> null
        },
        shadowColor = when (edge) {
            CaptionEdge.None, CaptionEdge.Outline -> TRANSPARENT
            CaptionEdge.DropShadow, CaptionEdge.Raised, CaptionEdge.Depressed -> edgeOrBlack
            CaptionEdge.Unspecified -> null
        },
    )
}

private const val BLACK = 0xFF000000.toInt()
private const val TRANSPARENT = 0
