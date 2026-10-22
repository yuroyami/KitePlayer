package io.github.yuroyami.kiteplayer.internal

import io.github.yuroyami.kiteplayer.SecondarySubtitlePlacement
import io.github.yuroyami.kiteplayer.SubtitleConfig
import io.github.yuroyami.kiteplayer.TrackId
import io.github.yuroyami.kiteplayer.TrackInfo
import io.github.yuroyami.kiteplayer.TrackKind
import io.github.yuroyami.kiteplayer.subtitle.CueAlignment
import io.github.yuroyami.kiteplayer.subtitle.CueStacking
import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue

/**
 * The second subtitle tracks an open may select for [SubtitleConfig.secondaryLanguages], best first
 * (#494): text tracks other than [primary] in a language of [languages], ranked by the language's
 * place in the list, then by how closely it matches, then full tracks before forced ones, then the
 * default-flagged first. Picture tracks are left out, because their pictures carry their own place
 * and cannot be moved beside the primary. More than one, so a track no decoder takes gives way to
 * the next. mpv's `secondary-slang` chooses the same way.
 */
internal fun secondarySubtitleCandidates(
    subtitles: List<TrackInfo>,
    primary: TrackId?,
    languages: List<String>,
): List<TrackInfo> {
    if (languages.isEmpty()) return emptyList()
    val preferences = LanguagePreferences(languages)
    return subtitles
        .filter { it.kind == TrackKind.Subtitle && it.id != primary && it.codec.lowercase() !in PICTURE_SUBTITLE_CODECS }
        .mapNotNull { track -> preferences.match(track.language, track.title)?.let { track to it } }
        .sortedWith(
            compareBy(
                { it.second.preference },
                { -it.second.closeness },
                { it.first.isForced },
                { !it.first.isDefault },
            ),
        )
        .map { it.first }
}

/** The subtitle formats that carry pictures rather than text. */
private val PICTURE_SUBTITLE_CODECS: Set<String> =
    setOf("hdmv_pgs_subtitle", "dvd_subtitle", "dvb_subtitle", "xsub")

/**
 * The secondary track's [cues] moved to [placement] (#494). At the top they stand apart from the
 * primary. Above or below it they join the bottom stack the primary's ordinary lines stand in, so
 * both follow the subtitle position, and they take the primary's stacking so the one pile grows one
 * way. A primary line its author placed elsewhere keeps its place, and the secondary then holds the
 * bottom alone. A secondary line leaves its TTML region behind (#492). Pictures carry their own
 * place and keep it.
 */
internal fun placeSecondaryCues(
    cues: List<SubtitleCue>,
    primary: List<SubtitleCue>,
    placement: SecondarySubtitlePlacement,
): List<SubtitleCue> {
    val stacking = primaryStacking(primary)
    return cues.map { cue ->
        when (cue) {
            is SubtitleCue.Text -> cue.copy(
                layout = cue.layout.copy(
                    alignment = if (placement == SecondarySubtitlePlacement.Top) CueAlignment.TopCenter else CueAlignment.BottomCenter,
                    positionX = null,
                    positionY = null,
                    region = null,
                    stacking = if (placement == SecondarySubtitlePlacement.Top) cue.layout.stacking else stacking ?: cue.layout.stacking,
                ),
            )
            is SubtitleCue.Bitmap -> cue
        }
    }
}

/**
 * Whether the secondary cues go before the primary's in the published list (#494). The bottom stack
 * piles cues in list order, the first at the bottom, or the last under the reverse stacking of an
 * ASS script, so below the primary means first in the one and last in the other.
 */
internal fun secondaryFirst(primary: List<SubtitleCue>, placement: SecondarySubtitlePlacement): Boolean {
    val reversed = primaryStacking(primary) == CueStacking.LastAtBottom
    return when (placement) {
        SecondarySubtitlePlacement.Top -> false
        SecondarySubtitlePlacement.BelowPrimary -> !reversed
        SecondarySubtitlePlacement.AbovePrimary -> reversed
    }
}

/** The stacking of the first of [cues] that stands in the bottom stack, which the stack obeys, or null. */
private fun primaryStacking(cues: List<SubtitleCue>): CueStacking? = cues.firstNotNullOfOrNull { cue ->
    (cue as? SubtitleCue.Text)?.layout?.takeIf { layout ->
        layout.positionY == null && layout.region == null && (
            layout.alignment == CueAlignment.BottomLeft ||
                layout.alignment == CueAlignment.BottomCenter ||
                layout.alignment == CueAlignment.BottomRight
            )
    }?.stacking
}
