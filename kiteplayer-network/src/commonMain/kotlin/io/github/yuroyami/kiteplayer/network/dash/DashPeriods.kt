package io.github.yuroyami.kiteplayer.network.dash

import kotlin.math.abs
import kotlin.math.ln

/**
 * A Period's place in the presentation (#403): where it starts and how long it lasts, as the
 * manifest says or as the Periods around it imply (ISO/IEC 23009-1, 5.3.2.1). A Period with no
 * start begins where the one before it ends, and one with no duration lasts until the next begins,
 * or, the last of a finished presentation, until the presentation ends.
 */
internal class DashPeriodTiming(val period: DashPeriod, val startMicros: Long, val durationMicros: Long?) {
    val endMicros: Long? get() = durationMicros?.let { startMicros + it }

    /** A name for the Period that stays the same across the fetches of a live manifest. */
    val key: String get() = "p$startMicros"
}

/** The timing of every Period of this manifest, in order. */
internal fun DashManifest.periodTimings(): List<DashPeriodTiming> {
    var previousEnd: Long? = 0L
    return periods.mapIndexed { index, period ->
        val start = period.startMicros ?: previousEnd
            ?: throw DashUnsupportedException("Period ${index + 1} has no start, and the Period before it no length")
        val nextStart = periods.getOrNull(index + 1)?.startMicros
        val duration = period.durationMicros
            ?: nextStart?.let { it - start }
            ?: if (index == periods.lastIndex) durationMicros?.let { it - start } else null
        previousEnd = duration?.let { start + it }
        DashPeriodTiming(period, start, duration)
    }
}

/** The presentation time offset of [representation], in microseconds: the media time its Period starts at. */
internal fun DashRepresentation.offsetMicros(): Long {
    val (offset, timescale) = segmentTemplate?.let { it.presentationTimeOffset to it.timescale }
        ?: segmentList?.let { it.presentationTimeOffset to it.timescale }
        ?: segmentBase?.let { it.presentationTimeOffset to it.timescale }
        ?: return 0L
    return offset / timescale * 1_000_000 + offset % timescale * 1_000_000 / timescale
}

/** The container a representation's segments arrive in, which decides how a later Period's are moved in time. */
internal enum class DashContainer { Mp4, Webm, Ts, Other }

internal object DashPeriods {

    /**
     * The adaptation set and representation of [period] that carry [track]'s part there, or null
     * when the Period has none. [reference] is the Period the presentation's tracks were taken
     * from. A set with the same `id` wins, then the set at the same place among the sets of its
     * kind when the two Periods have as many, then one in the same language, then the first of
     * its kind. Within it, a picture or sound track takes the representation whose bandwidth is
     * nearest its own, and a subtitle track the first.
     */
    fun match(track: DashHlsTrack, reference: DashPeriod, period: DashPeriod): Pair<Int, Int>? {
        fun ofKind(of: DashPeriod) = of.adaptationSets.withIndex().filter { (_, set) ->
            DashHls.roleOf(set) == track.role && (track.subtitleFormat == null || DashHls.subtitleFormat(set) != null)
        }
        val sets = ofKind(period)
        if (sets.isEmpty()) return null
        val referenceSets = ofKind(reference)
        val place = referenceSets.indexOfFirst { it.index == track.setIndex }
        val chosen = track.set.id?.let { id -> sets.firstOrNull { it.value.id == id } }
            ?: sets.getOrNull(place)?.takeIf { place >= 0 && referenceSets.size == sets.size }
            ?: track.set.lang?.let { lang -> sets.firstOrNull { it.value.lang == lang } }
            ?: sets.first()
        val representations = chosen.value.representations
        if (representations.isEmpty()) return null
        if (track.role == DashHlsRole.Subtitles) return chosen.index to 0
        val wanted = ln(track.representation.bandwidth + 1.0)
        return chosen.index to representations.indices.minBy { abs(ln(representations[it].bandwidth + 1.0) - wanted) }
    }

    /** The container of [representation] in [set], by its type, or by its segments' extension when it states none. */
    fun containerOf(set: DashAdaptationSet, representation: DashRepresentation): DashContainer {
        val mime = (representation.mimeType ?: set.mimeType)?.lowercase()
        val path = (representation.segmentTemplate?.media ?: representation.baseUrl).substringBefore('?').lowercase()
        return when {
            mime != null && (mime.endsWith("/mp4") || mime.endsWith("/iso.segment")) -> DashContainer.Mp4
            mime != null && (mime.endsWith("/webm") || mime.endsWith("/x-matroska")) -> DashContainer.Webm
            mime != null && mime.endsWith("/mp2t") -> DashContainer.Ts
            mime != null -> DashContainer.Other
            listOf(".m4s", ".mp4", ".m4v", ".m4a", ".cmfv", ".cmfa").any { path.endsWith(it) } -> DashContainer.Mp4
            listOf(".webm", ".weba", ".mkv", ".mka").any { path.endsWith(it) } -> DashContainer.Webm
            path.endsWith(".ts") -> DashContainer.Ts
            else -> DashContainer.Other
        }
    }
}
