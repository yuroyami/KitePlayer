package io.github.yuroyami.kiteplayer.internal

import io.github.yuroyami.kiteplayer.Chapter
import io.github.yuroyami.kiteplayer.MediaClip
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.PlaybackError
import io.github.yuroyami.kiteplayer.PlaybackException
import io.github.yuroyami.kiteplayer.spi.PlayerMediaSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds

/** Where a session's item ends when no clip ends it before its file does (#456). */
internal const val NO_CLIP_END: Long = Long.MAX_VALUE

/**
 * The chapters of a clipped item (#456): those of the file that overlap [startUs] to [endUs], cut
 * to that span, counted from [startUs], and numbered from 0 in their order. A null [endUs] runs to
 * the end of the file, and a chapter with no end of its own ends where the clip does.
 */
internal fun List<Chapter>.inClip(startUs: Long, endUs: Long?): List<Chapter> {
    val clipped = ArrayList<Chapter>(size)
    for (chapter in this) {
        val chapterStart = chapter.start.inWholeMicroseconds
        val chapterEnd = chapter.end?.inWholeMicroseconds
        if (endUs != null && chapterStart >= endUs) continue
        if (chapterEnd != null && chapterEnd <= startUs) continue
        val end = when {
            chapterEnd == null -> endUs
            endUs == null -> chapterEnd
            else -> minOf(chapterEnd, endUs)
        }
        clipped += Chapter(
            index = clipped.size,
            start = (maxOf(chapterStart, startUs) - startUs).microseconds,
            end = end?.let { (it - startUs).microseconds },
            title = chapter.title,
        )
    }
    return clipped
}

/**
 * Refuses a clip that starts where the media has already ended (#456), which has nothing to play.
 * Only a stated length can say so: an estimate, or no length at all, lets the clip open and end at
 * once if it was wrong.
 */
internal fun refuseClipPastTheEnd(clip: MediaClip, durationUs: Long?, durationIsEstimate: Boolean) {
    if (durationUs == null || durationIsEstimate) return
    val startUs = clip.start.inWholeMicroseconds
    if (startUs < durationUs) return
    throw PlaybackException(
        PlaybackError.ConfigurationInvalid(
            "the item's clip starts at ${clip.start}, and the media is only ${durationUs.microseconds} long",
        ),
    )
}

/**
 * Where an item ends in its file, in microseconds (#456): its clip's end at [clipEndUs], or the
 * media's length when that comes first or the clip has no end ([NO_CLIP_END]), or null when
 * neither is known.
 */
internal fun itemEndUs(clipEndUs: Long, source: PlayerMediaSource): Long? {
    val statedUs = source.duration?.micros
    return when {
        clipEndUs == NO_CLIP_END -> statedUs
        statedUs == null -> clipEndUs
        else -> minOf(clipEndUs, statedUs)
    }
}

/** Whether [itemEndUs] is the media's estimated length (#422) rather than a clip end or a stated length. */
internal fun itemEndIsEstimate(clipEndUs: Long, source: PlayerMediaSource): Boolean {
    val statedUs = source.duration?.micros ?: return false
    return source.durationIsEstimate && statedUs < clipEndUs
}

/** How far a seek in the item may go, in microseconds of its file: its end, unless that is an estimate. */
internal fun itemSeekCeilingUs(clipEndUs: Long, source: PlayerMediaSource): Long? =
    if (itemEndIsEstimate(clipEndUs, source)) null else itemEndUs(clipEndUs, source)

/** Where this clip ends in its file, in microseconds, or [NO_CLIP_END] for no clip or no end. */
internal val MediaClip?.endUs: Long get() = this?.end?.inWholeMicroseconds ?: NO_CLIP_END

/** Where this clip starts in its file, in microseconds, or zero for no clip. */
internal val MediaClip?.startUs: Long get() = this?.start?.inWholeMicroseconds ?: 0L

/** No next item joins the current one on its reads (#456). */
internal const val JOIN_NONE: Int = 0

/** A next item joins: the reads go on past the current item's end, and no lane has crossed it. */
internal const val JOIN_ARMED: Int = 1

/** A lane let sound or a picture of the joined item through, so the items move when it is heard. */
internal const val JOIN_COMMITTED: Int = 2

/** The join was given up, and the current item ends at its end. */
internal const val JOIN_WITHDRAWN: Int = 3

/**
 * Whether [next] is the next part of [current]'s file (#456), as the tracks of an album in one file
 * are: the same item in every field but its clip, its start position and its three titles, with a
 * clip that starts exactly where [current]'s ends, and no start position of its own to start
 * elsewhere.
 */
internal fun continuesInFile(current: MediaItem, next: MediaItem): Boolean {
    val end = current.clip?.end ?: return false
    if (next.clip?.start != end) return false
    if ((next.startPosition ?: Duration.ZERO) > Duration.ZERO) return false
    val alike = next.copy(
        clip = current.clip,
        startPosition = current.startPosition,
        title = current.title,
        artist = current.artist,
        album = current.album,
        // How a crossfade treats the join is no part of which file is played (#434).
        runsIntoNext = current.runsIntoNext,
    )
    return alike == current
}
