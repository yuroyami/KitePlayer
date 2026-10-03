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
     * its kind. An encrypted set is never chosen. Within it, a picture or sound track takes the representation whose bandwidth is
     * nearest its own, and a subtitle track the first.
     */
    fun match(track: DashHlsTrack, reference: DashPeriod, period: DashPeriod): Pair<Int, Int>? {
        fun ofKind(of: DashPeriod) = of.adaptationSets.withIndex().filter { (_, set) ->
            !set.isProtected && DashHls.roleOf(set) == track.role && (track.subtitleFormat == null || DashHls.subtitleFormat(set) != null)
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

    /**
     * The adaptation set and representation of [period], a fetch of the Period [track] was taken
     * from, that are [track]'s own, or null when that fetch no longer has them (#406). A live
     * manifest's refresh may add, remove or reorder sets and representations, and only their `id`
     * stays put across the refreshes (ISO/IEC 23009-1, 5.4, and DASH-IF IOP 4.4.3.3), so a track
     * is bound by its set's `id` and its representation's `id`, and by place only where the one at
     * its place is still the same. Without an `id`, a set or representation is found by what it is:
     * its kind and language, or its bandwidth and codec, when exactly one agrees. A match whose kind
     * or codec changed is no match. Unlike [match], which finds the like of a track in another
     * Period, this never gives a track another set's or another representation's segments.
     */
    fun bind(track: DashHlsTrack, period: DashPeriod): Pair<Int, Int>? {
        val sets = period.adaptationSets
        val setIndex = when {
            sets.getOrNull(track.setIndex)?.let { sameSet(it, track) } == true -> track.setIndex
            track.set.id != null -> sets.indexOfFirst { it.id == track.set.id && sameSet(it, track) }
            else -> sets.indices.singleOrNull { sameSet(sets[it], track) } ?: -1
        }
        if (setIndex < 0) return null
        val representations = sets[setIndex].representations
        val wanted = track.representation
        val representationIndex = when {
            representations.getOrNull(track.representationIndex)?.let { sameRepresentation(it, wanted) } == true -> track.representationIndex
            wanted.id != null -> representations.indexOfFirst { it.id == wanted.id && sameRepresentation(it, wanted) }
            else -> representations.indices.singleOrNull {
                representations[it].bandwidth == wanted.bandwidth && sameRepresentation(representations[it], wanted)
            } ?: -1
        }
        if (representationIndex < 0) return null
        return setIndex to representationIndex
    }

    /** True when [set] is [track]'s set by its `id`, and still the same kind of content, in the same language. */
    private fun sameSet(set: DashAdaptationSet, track: DashHlsTrack): Boolean =
        set.id == track.set.id && !set.isProtected && DashHls.roleOf(set) == track.role &&
            set.lang == track.set.lang && agree(set.mimeType, track.set.mimeType)

    /** True when [representation] is [wanted] by its `id`, in the same codec and type. */
    private fun sameRepresentation(representation: DashRepresentation, wanted: DashRepresentation): Boolean =
        representation.id == wanted.id && agree(representation.codecs, wanted.codecs) &&
            agree(representation.mimeType, wanted.mimeType)

    /** Two attributes agree unless both are stated and differ. */
    private fun agree(one: String?, other: String?): Boolean = one == null || other == null || one.equals(other, ignoreCase = true)

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
