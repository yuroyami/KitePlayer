package io.github.yuroyami.kiteplayer

import kotlin.time.Duration

/**
 * One edition of the media's chapters, such as one cut of a film (#501). A Matroska file can carry
 * several, and an ordered one is a timeline: it plays its chapters in their order, each a range of
 * this file or of a linked one, and leaves out the rest. [PlayerSnapshot.editions] lists them,
 * [PlayerSnapshot.edition] names the one that plays, and [KitePlayer.selectEdition] plays another.
 */
public data class MediaEdition(
    /**
     * Its position in [PlayerSnapshot.editions], which [KitePlayer.selectEdition] and
     * [DemuxPolicy.edition] take.
     */
    val index: Int,
    /** The edition's first name in the file, or null when it names none. */
    val title: String?,
    /** The `EditionUID` the file states, or null. The same edition keeps it from one open to the next. */
    val uid: Long?,
    /** True for the edition the file marks to play unless told otherwise. */
    val isDefault: Boolean,
    /** True when the file asks a player not to offer it. It can still be selected. */
    val isHidden: Boolean,
    /** True when the edition is a timeline of its chapters, false when its chapters are only marks. */
    val isOrdered: Boolean,
    /**
     * How long the edition plays: for an ordered one, the sum of the ranges its chapters state; for
     * one that is not, the length of the file. Null when that is not known. The edition that plays
     * reports its length as it is played in [PlayerSnapshot.duration], which leaves out a linked part
     * that was not found.
     */
    val duration: Duration?,
)
