package io.github.yuroyami.kiteplayer.internal

import io.github.yuroyami.kiteplayer.Chapter
import io.github.yuroyami.kiteplayer.MediaEdition
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.PlaybackWarning
import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.spi.PlayerMediaSource
import io.github.yuroyami.kiteplayer.spi.PlayerStreamInfo
import io.github.yuroyami.kiteplayer.spi.SourceEdition
import io.github.yuroyami.kiteplayer.spi.SourceEditionPart
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

/** How deep the links of one linked edition into another are followed (#501). */
internal const val LINKED_DEPTH_LIMIT: Int = 4

/**
 * One range an ordered edition plays, placed on its timeline (#501): one played chapter, or one
 * range of an edition of another file that a chapter plays whole.
 *
 * [startUs] and [endUs] are times of the file that plays the range, in the engine's time, and the
 * range does not include [endUs]. [atUs] is where the range starts on the timeline.
 */
internal class TimelinePart(
    /** The chapter's name, which for a range of a linked edition is the name of the chapter that links it. */
    val title: String?,
    val isHidden: Boolean,
    /** The segment UID of the file the range is in, or null for the main file. */
    val segmentUid: String?,
    val startUs: Long,
    val endUs: Long,
    val atUs: Long,
    /** The chapter the range is listed in: the ranges of one linked edition share one. */
    val chapter: Int,
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
internal class EditionTimeline(
    val edition: Int,
    val parts: List<TimelinePart>,
    /** The item that opens each linked file, by its segment UID. */
    val files: Map<String, MediaItem> = emptyMap(),
    /** The main file's streams, which a linked file's tracks are matched against. */
    val mainStreams: List<PlayerStreamInfo> = emptyList(),
    /** The main file's editions as the caller sees them, which stay listed while a linked file plays. */
    val editions: List<MediaEdition> = emptyList(),
    /** The main file's tags, which stay the item's while a linked file plays. */
    val metadata: Map<String, String> = emptyMap(),
) {
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

    /** True when run [index] is in another file than the main one. */
    fun isLinked(index: Int): Boolean = runs[index].segmentUid != null

    /** The item that opens run [index]'s file, or [main] for a run of the main file. */
    fun itemFor(index: Int, main: MediaItem): MediaItem =
        runs[index].segmentUid?.let { files[it] } ?: main

    /**
     * One chapter for each chapter of the edition a viewer is shown, at its place on the timeline
     * and named by it. A hidden one plays and is not listed, and the ranges of an edition that a
     * chapter plays whole are that one chapter.
     */
    val chapters: List<Chapter> = buildList {
        var index = 0
        var at = 0
        while (at < parts.size) {
            val first = parts[at]
            var last = at
            while (last + 1 < parts.size && parts[last + 1].chapter == first.chapter) last++
            if (!first.isHidden) {
                add(
                    Chapter(
                        index = index++,
                        start = first.atUs.microseconds,
                        end = (parts[last].atUs + parts[last].lengthUs).microseconds,
                        title = first.title,
                    ),
                )
            }
            at = last + 1
        }
    }
}

/**
 * The timeline [item] plays from [source], or null when it plays the file plainly (#501): the
 * item's [io.github.yuroyami.kiteplayer.DemuxPolicy.orderedEditions] is false, the chosen edition
 * is not ordered or cannot be played as one, the item has a clip, which names a
 * range of the file, the file is still growing, so its timeline is not known yet, the source cannot
 * seek, so it cannot skip what the edition leaves out, or no part of the edition is in a file the
 * player has.
 *
 * [find] answers the item and the open source of the file that holds a segment UID, or null when no
 * file does. A part it cannot play is left out, and [leftOut] is told once for each segment: no
 * file holds it, or its link leads back to a segment already on the way, or nests deeper than
 * [LINKED_DEPTH_LIMIT]. A part of no length is left out silently.
 */
internal suspend fun editionTimelineOf(
    item: MediaItem,
    source: PlayerMediaSource,
    edition: Int?,
    find: suspend (segmentUid: String) -> Pair<MediaItem, PlayerMediaSource>? = { null },
    leftOut: (PlaybackWarning.EditionPartLeftOut) -> Unit = {},
    missingDetail: () -> String = { "no file the player could read holds this segment" },
): EditionTimeline? {
    if (!item.demux.orderedEditions || item.clip != null || item.growth != null || !source.seekable) return null
    val index = edition ?: return null
    val chosen = source.editions.getOrNull(index) ?: return null
    if (!chosen.isOrdered) return null
    val playlist = chosen.parts ?: return null
    val builder = TimelineBuilder(source, find, leftOut, missingDetail)
    builder.expand(source, null, playlist, setOfNotNull(source.segmentUid), depth = 0, group = null)
    if (builder.parts.isEmpty()) return null
    return EditionTimeline(
        edition = index,
        parts = builder.parts,
        files = builder.files,
        mainStreams = source.streams,
        editions = mediaEditionsOf(source),
        metadata = source.metadata,
    )
}

/** Lays an edition's parts out on its timeline, following its links (#501). */
private class TimelineBuilder(
    private val main: PlayerMediaSource,
    private val find: suspend (String) -> Pair<MediaItem, PlayerMediaSource>?,
    private val leftOut: (PlaybackWarning.EditionPartLeftOut) -> Unit,
    private val missingDetail: () -> String,
) {
    val parts = mutableListOf<TimelinePart>()
    val files = LinkedHashMap<String, MediaItem>()
    private val warned = HashSet<String>()
    private var atUs = 0L
    private var chapters = 0

    /** The chapter a linked edition's ranges are all listed in, named by the chapter that links it. */
    class Group(val chapter: Int, val title: String?, val isHidden: Boolean)

    /**
     * Lays out [playlist], an edition of [file], whose segment UID is [fileUid], or null for the
     * main file. [chain] holds the segments on the way to it, which no link may lead back to.
     */
    suspend fun expand(
        file: PlayerMediaSource,
        fileUid: String?,
        playlist: List<SourceEditionPart>,
        chain: Set<String>,
        depth: Int,
        group: Group?,
    ) {
        for (part in playlist) {
            val link = part.segmentUid?.takeIf { it != file.segmentUid }
            var targetUid = fileUid
            var target = file
            if (link != null) {
                // The main file's own segment is always on the way.
                if (link in chain) {
                    tell(link, part, "the link leads back to a segment already on its way")
                    continue
                }
                val found = find(link)
                if (found == null) {
                    tell(link, part, missingDetail())
                    continue
                }
                files.getOrPut(link) { found.first }
                targetUid = link
                target = found.second
            }
            val nested = part.segmentEditionUid?.let { uid -> target.editions.firstOrNull { it.uid == uid } }
            if (nested != null) {
                if (depth >= LINKED_DEPTH_LIMIT) {
                    tell(targetUid ?: main.segmentUid ?: "", part, "its links nest deeper than $LINKED_DEPTH_LIMIT")
                    continue
                }
                val outer = group ?: Group(chapters++, part.title, part.isHidden)
                val inner = nested.parts
                if (nested.isOrdered && inner != null) {
                    expand(target, targetUid, inner, chain + setOfNotNull(target.segmentUid), depth + 1, outer)
                } else {
                    // An edition that is no timeline plays the whole file.
                    val length = target.duration?.micros ?: continue
                    val start = target.fromSegmentTime(Pts.Zero).micros
                    add(targetUid, start, start + length, outer.title, outer.isHidden, outer.chapter)
                }
                continue
            }
            val startUs = target.fromSegmentTime(part.start).micros
            val endUs = target.fromSegmentTime(part.end).micros
            val chapter = group?.chapter ?: chapters++
            add(targetUid, startUs, endUs, group?.title ?: part.title, group?.isHidden ?: part.isHidden, chapter)
        }
    }

    private fun add(segmentUid: String?, startUs: Long, endUs: Long, title: String?, isHidden: Boolean, chapter: Int) {
        if (endUs <= startUs) return
        parts += TimelinePart(title, isHidden, segmentUid, startUs, endUs, atUs, chapter)
        atUs += endUs - startUs
    }

    private fun tell(segmentUid: String, part: SourceEditionPart, detail: String) {
        if (warned.add(segmentUid)) leftOut(PlaybackWarning.EditionPartLeftOut(segmentUid, part.title, detail))
    }
}
