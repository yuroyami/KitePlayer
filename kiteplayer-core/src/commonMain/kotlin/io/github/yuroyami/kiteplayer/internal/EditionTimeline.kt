package io.github.yuroyami.kiteplayer.internal

import io.github.yuroyami.kiteplayer.Chapter
import io.github.yuroyami.kiteplayer.MediaEdition
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.spi.PlayerMediaSource
import io.github.yuroyami.kiteplayer.spi.SourceEdition
import kotlin.time.Duration.Companion.microseconds

/**
 * The editions of [source] as the caller sees them (#501): each one's length is the sum of the
 * ranges an ordered edition states, or the file's own length for one that is not ordered.
 */
internal fun mediaEditionsOf(source: PlayerMediaSource): List<MediaEdition> =
    source.editions.mapIndexed { index, edition ->
        MediaEdition(
            index = index,
            title = edition.title,
            uid = edition.uid,
            isDefault = edition.isDefault,
            isHidden = edition.isHidden,
            isOrdered = edition.isOrdered,
            duration = if (edition.isOrdered) {
                edition.parts?.sumOf { (it.end.micros - it.start.micros).coerceAtLeast(0L) }?.microseconds
            } else {
                source.duration?.micros?.microseconds
            },
        )
    }

/**
 * The index of the edition that plays from [editions] (#501): [requested] when the media has it,
 * and otherwise the first one the file marks default, or the first one when none is. Null when the
 * media has no editions.
 */
internal fun chosenEdition(editions: List<SourceEdition>, requested: Int?): Int? {
    if (editions.isEmpty()) return null
    if (requested != null && requested in editions.indices) return requested
    return editions.indexOfFirst { it.isDefault }.takeIf { it >= 0 } ?: 0
}

/**
 * One range an ordered edition plays, placed on its timeline (#501): one played chapter.
 *
 * [startUs] and [endUs] are times of the file that plays the range, in the engine's time, and the
 * range does not include [endUs]. [atUs] is where the range starts on the timeline.
 */
internal class TimelinePart(
    val title: String?,
    val isHidden: Boolean,
    /** The segment UID of the file the range is in, or null for the main file. */
    val segmentUid: String?,
    val startUs: Long,
    val endUs: Long,
    val atUs: Long,
) {
    val lengthUs: Long get() = endUs - startUs
}

/**
 * Parts of one file that play on one reading of it, each starting where the one before ends
 * (#501). The engine plays a run as it plays a clip: from [startUs] to [endUs] of its file, with the
 * timeline's time counting from [atUs] at [startUs].
 */
internal class TimelineRun(
    /** The segment UID of the file the run is in, or null for the main file. */
    val segmentUid: String?,
    val startUs: Long,
    val endUs: Long,
    val atUs: Long,
) {
    val lengthUs: Long get() = endUs - startUs

    /** Where the timeline's time starts in the run's file: a time of the file less this is a position. */
    val originUs: Long get() = startUs - atUs

    /** The time of the run's file that plays at timeline position [itemUs]. */
    fun fileUsAt(itemUs: Long): Long = itemUs + originUs
}

/**
 * The timeline of the ordered edition at [edition] in the media's editions (#501): the [parts] it
 * plays in order, with no gap between them, and the [runs] that play them.
 */
internal class EditionTimeline(val edition: Int, val parts: List<TimelinePart>) {
    init {
        require(parts.isNotEmpty()) { "a timeline plays at least one part" }
    }

    /** The parts in runs: two parts share one when they are of the same file and the second starts where the first ends. */
    val runs: List<TimelineRun> = buildList {
        var current: TimelineRun? = null
        for (part in parts) {
            val run = current
            current = if (run != null && run.segmentUid == part.segmentUid && run.endUs == part.startUs) {
                TimelineRun(run.segmentUid, run.startUs, part.endUs, run.atUs)
            } else {
                run?.let(::add)
                TimelineRun(part.segmentUid, part.startUs, part.endUs, part.atUs)
            }
        }
        current?.let(::add)
    }

    /** How long the timeline plays, which is the sum of its parts. */
    val lengthUs: Long = parts.last().atUs + parts.last().lengthUs

    /** The run that plays at [itemUs]: the last one that starts at or before it, the first for a time before zero. */
    fun runAt(itemUs: Long): Int {
        val index = runs.indexOfLast { it.atUs <= itemUs }
        return if (index < 0) 0 else index
    }

    /**
     * One chapter for each part a viewer is shown, at the part's place on the timeline and named by
     * its chapter. A hidden part plays and is not listed.
     */
    val chapters: List<Chapter> = parts.filter { !it.isHidden }.mapIndexed { index, part ->
        Chapter(
            index = index,
            start = part.atUs.microseconds,
            end = (part.atUs + part.lengthUs).microseconds,
            title = part.title,
        )
    }
}

/**
 * The timeline [item] plays from [source], or null when it plays the file plainly (#501): the
 * chosen edition is not ordered or cannot be played as one, the item has a clip, which names a
 * range of the file, the file is still growing, so its timeline is not known yet, the source cannot
 * seek, so it cannot skip what the edition leaves out, or no part of the edition is in a file the
 * player has.
 *
 * A part in another file than [source] is left out here: those are found by [linked], which answers
 * the source of a segment UID, or null when no file holds it. A part of no length is left out too.
 */
internal fun editionTimelineOf(
    item: MediaItem,
    source: PlayerMediaSource,
    edition: Int?,
    linked: (segmentUid: String) -> PlayerMediaSource? = { null },
): EditionTimeline? {
    if (item.clip != null || item.growth != null || !source.seekable) return null
    val index = edition ?: return null
    val chosen = source.editions.getOrNull(index) ?: return null
    if (!chosen.isOrdered) return null
    val playlist = chosen.parts ?: return null
    val parts = mutableListOf<TimelinePart>()
    var atUs = 0L
    for (part in playlist) {
        val ownFile = part.segmentUid == null || part.segmentUid == source.segmentUid
        val file = if (ownFile) source else linked(part.segmentUid!!) ?: continue
        val startUs = file.fromSegmentTime(part.start).micros
        val endUs = file.fromSegmentTime(part.end).micros
        if (endUs <= startUs) continue
        parts += TimelinePart(
            title = part.title,
            isHidden = part.isHidden,
            segmentUid = if (ownFile) null else part.segmentUid,
            startUs = startUs,
            endUs = endUs,
            atUs = atUs,
        )
        atUs += endUs - startUs
    }
    return if (parts.isEmpty()) null else EditionTimeline(index, parts)
}
