package io.github.yuroyami.kiteplayer.spi

import io.github.yuroyami.kiteplayer.Chapter
import io.github.yuroyami.kiteplayer.Pts

/**
 * One edition of a source's chapters, as the container states it (#501): a Matroska `EditionEntry`.
 * See [PlayerMediaSource.editions].
 *
 * A backend reports what the file says and nothing more. It does not search for the files that
 * [parts] link to, and it does not play an ordered edition; the engine does both, through the same
 * [MediaSourceFactory.open] that opens every item.
 */
public data class SourceEdition(
    /** The `EditionUID`, or null when the file states none. */
    val uid: Long?,
    /** The edition's first name in the file, or null. */
    val title: String?,
    /** True for the edition the file marks to play unless told otherwise. */
    val isDefault: Boolean,
    /** True when the file asks a player not to offer this edition. */
    val isHidden: Boolean,
    /** True when the edition is a timeline of its chapters rather than marks on the file. */
    val isOrdered: Boolean,
    /**
     * The edition's chapters as marks on this source, in the engine's time, for when the edition
     * plays as a plain file: the chapters a viewer is shown, in stored order.
     */
    val chapters: List<Chapter>,
    /**
     * The ranges an ordered edition plays, in the order it plays them, or null when the edition is
     * not ordered or cannot be played as one, as when a chapter it plays states no end.
     */
    val parts: List<SourceEditionPart>?,
)

/**
 * One range an ordered edition plays: one played chapter (#501).
 *
 * [start] and [end] are times of the segment that holds the range as the file states them, counted
 * from that segment's start. The engine turns them into its own time through
 * [PlayerMediaSource.fromSegmentTime] of the source that plays the range, which for a linked segment
 * is a source it has not opened yet when this is read.
 */
public data class SourceEditionPart(
    /** Where the range starts. */
    val start: Pts,
    /** Where the range ends; the range does not include it. */
    val end: Pts,
    /** The chapter's first name, or null. */
    val title: String?,
    /** True when the chapter plays and is not shown to a viewer. */
    val isHidden: Boolean,
    /**
     * The segment UID of the file the range is in, 32 lowercase hexadecimal digits, or null for this
     * source's own file.
     */
    val segmentUid: String?,
    /**
     * The `EditionUID` of the edition of [segmentUid] that this part plays whole, or null to play
     * the range from [start] to [end].
     */
    val segmentEditionUid: Long?,
)
