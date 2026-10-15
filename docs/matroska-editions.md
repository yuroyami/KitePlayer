# Matroska editions and linked files

This document is the design for issue #501. Nothing in it is implemented yet. It states the public
API, what the player does with an ordered edition, and how the engine plays one, so that all three
can be agreed before the code is written.

## What a Matroska file can say

A Matroska file can describe a timeline instead of a plain run of media.

- An **edition** is one set of chapters. A film can carry two, a theatrical and an extended cut.
- An **ordered** edition is a timeline: its chapters play in the order they are stored, each one a
  range of time, and anything outside them is not played.
- A chapter of an ordered edition can play a range of **another file**, named by that file's segment
  UID rather than by its name. Releases of anime from the Blu-ray era keep one opening and one
  ending file for a season and link them from every episode this way.
- A chapter can also play a whole edition of another file.

FFmpeg plays every such file as a plain file. The shared opening is missing, the parts meant to be
skipped play, and an edition cannot be chosen.

## What KiteFFmpeg already gives

The issue was written before this existed, and its open question is answered by it. KiteFFmpeg
0.5.1, which this repository pins, reads the segment and its editions through a patch of FFmpeg's
Matroska reader that lives in KiteFFmpeg (KiteFFmpeg #173), on every platform including the web.
`MediaSource.matroska` answers a `MatroskaSegment`: the file's own segment UID, its editions with
their default, hidden and ordered flags, and each chapter's start, end, hidden and enabled flags,
the segment UID and edition UID it links to, its names and its skip type. `MatroskaEdition.playlist`
already lists the chapters an ordered edition plays, in the order it plays them, following RFC 9559.

So the player needs no FFmpeg patch of its own. What is left is all in this repository: report the
editions, find the linked files, and play the timeline.

## Terms

- **The main file** is the file the item names.
- **A part** is one range of one file that the timeline plays: one played chapter of the edition.
- **The timeline** is the parts in order. Its length is the sum of the parts' lengths.
- **A linked file** is a file that holds a segment a part names by its UID.

## Public API

Every name below is new. Nothing that exists changes its meaning for a file with no ordered edition.

```kotlin
/** One edition of the media's chapters, such as one cut of a film (#501). */
public data class MediaEdition(
    /** Its position in [PlayerSnapshot.editions], which [KitePlayer.selectEdition] and [DemuxPolicy.edition] take. */
    val index: Int,
    /** The edition's first name in the file, or null when it names none. */
    val title: String?,
    /** The `EditionUID` the file states, or null. The same edition keeps it from one open to the next. */
    val uid: Long?,
    /** True for the edition the file marks to play unless told otherwise. */
    val isDefault: Boolean,
    /** True when the file asks a player not to offer it; it can still be selected. */
    val isHidden: Boolean,
    /** True when the edition is a timeline of its chapters, false when its chapters are only marks. */
    val isOrdered: Boolean,
    /**
     * How long the edition plays: for an ordered one, the sum of the ranges its chapters state; for
     * one that is not, the length of the main file. Null when that is not known.
     */
    val duration: Duration?,
)

public data class PlayerSnapshot(
    // ...
    /** The editions of the current media, in the order the file stores them. Empty for media with none. */
    val editions: List<MediaEdition> = emptyList(),
    /** The [MediaEdition.index] of the edition that plays, or null for media with no editions. */
    val edition: Int? = null,
)

public class KitePlayer {
    /**
     * Plays edition [index] of [PlayerSnapshot.editions]. The media opens again on that edition,
     * through the same rebuild as a programme change, and the choice is kept on the item as
     * [DemuxPolicy.edition], so a later rebuild keeps it too. The player keeps playing or stays
     * paused. See "Switching editions" for where the new edition starts.
     *
     * @throws IllegalStateException when nothing is open, or when a stop, a close, a new open or a
     *         later call ended the change first.
     * @throws IllegalArgumentException when the media has no edition at [index].
     * @throws PlaybackException when the reopen itself failed.
     */
    public suspend fun selectEdition(index: Int)
}

public data class DemuxPolicy(
    // ...
    /**
     * The [MediaEdition.index] of the edition to play, or null for the one the file marks as the
     * default. An index the media does not have is ignored. [KitePlayer.selectEdition] sets it.
     */
    val edition: Int? = null,
    /**
     * False plays every edition as marks on the main file, as FFmpeg does, for a release whose links
     * point at the wrong files. True, the default, plays an ordered edition as its timeline.
     */
    val orderedEditions: Boolean = true,
)

public data class MediaItem(
    // ...
    /**
     * Files that may hold the segments an ordered edition links to (#501). The player reads each
     * one's segment UID and plays the ranges the edition names from the file that matches. A local
     * file is also searched for in its own folder, so an application needs this only for media the
     * player cannot list, such as an address, a content URI or a file read through [io].
     */
    val linkedFiles: List<LinkedFile> = emptyList(),
)

/** A file that may hold a segment an ordered edition links to. See [MediaItem.linkedFiles]. */
public data class LinkedFile(
    /** Where the file is, as [MediaItem.uri] is. */
    val uri: String,
    /** Reads the bytes through your own code, as [MediaItem.io] does. */
    val io: MediaIoFactory? = null,
)

public sealed class PlaybackWarning {
    /**
     * A part of the edition that plays could not be played, so the timeline leaves it out: no file
     * holds the segment [segmentUid] it names, the file that holds it failed to open, or the link
     * leads back to a segment already on its way. [chapter] is the part's chapter name, or null when
     * it has none, and [detail] says which of the three it was. Emitted once for each segment at
     * each open.
     */
    public data class EditionPartLeftOut(
        val segmentUid: String,
        val chapter: String?,
        val detail: String,
    ) : PlaybackWarning()
}
```

`PlayerMemento` needs nothing new: it keeps the queue's items, so an edition chosen with
`selectEdition` comes back through `DemuxPolicy.edition`, and its position is a timeline position.

## What the player does

### Which edition plays

`DemuxPolicy.edition` when it names one, and otherwise the file's default: the first edition marked
default, or the first edition when none is. A hidden edition plays when it is the one chosen this
way, and is listed with `isHidden` so that an application can leave it out of a menu.

An edition plays as a timeline when it is ordered, `DemuxPolicy.orderedEditions` is true, every
chapter it plays states its end (the file is broken otherwise, and RFC 9559 requires it), the item
has no clip, the file is not growing, and its source can seek. Otherwise the main file plays
plainly, with the chosen edition's chapters as its chapters.

An item with a `MediaClip` plays its file plainly because a clip names a range of the file, a
growing file because its timeline is not known yet, and a source that cannot seek because it cannot
skip what the edition leaves out, or go back to a part another file interrupted.

### The timeline

Each chapter in `MatroskaEdition.playlist` is one part:

- with no segment UID, the range from its start to its end of the main file;
- with a segment UID, the same range of the linked file that holds that segment;
- with a segment UID and an edition UID, that edition of the linked file, played whole: its own
  parts, when it is ordered, or the whole linked file when it is not.

Links inside a linked edition are followed the same way, to a depth of four. A link back to a
segment already on the way is left out with `EditionPartLeftOut`, so a loop of links cannot hang
the open. A part of zero length is left out silently.

### Positions, duration and chapters

Everything the player reports as a position counts along the timeline: `position`, `progress`,
`duration`, `chapters`, the A-B points, the markers, the sleep timer, `SeekCompleted`, the memento
and `startPosition`. Every position the caller hands in is read the same way.

`PlayerSnapshot.duration` is the sum of the parts that were found.

`PlayerSnapshot.chapters` lists one chapter for each part, at the part's place on the timeline,
titled with the part's chapter name. A hidden chapter plays and is not listed. A part that plays a
linked edition whole is one chapter, named by its own chapter, and that edition's inner chapters are
not listed.

### Timestamps

The timestamps of the media stay the file's, as they do for a clip: every `Pts`, such as the audio
clock, a presented or captured frame, the audio tap and the times of a subtitle cue, is a time of
the file that plays it. Two parts can therefore carry the same `Pts`. A position, not a `Pts`, is
how an application tells two moments of the timeline apart.

### Tracks across files

The item's tracks are those of the main file, and track choices name them, as for any file. While a
part of a linked file plays, each selected track plays the linked file's track of the same kind that
matches it: the same language first, then the same title, then the same position among the tracks
of that kind. A kind the linked file does not have is silent while its part plays: no sound, no
picture, or no subtitles. A kind the main file lacks, as a file that only links can, takes its tracks
from the first part that has one.

### Finding linked files

The player looks for each segment UID the timeline needs, in this order, and stops as soon as every
one is found:

1. the files of `MediaItem.linkedFiles`, in their order;
2. for a main file named by a local path, with no `io`, the Matroska files beside it (`.mkv`,
   `.mka`, `.mks` and `.mk3d`), in the order of their names, at most 256 of them.

Each candidate is opened once, with the shallowest probe, to read its segment UID, and is closed
again unless a part plays from it. That open costs what any open costs, so a large folder costs
time, which the bound of 256 limits. An item's headers reach a linked address only when it has the scheme, host and port of
the item's own address, the rule external subtitles already follow.

The folder is listed on the JVM, Android and native targets. A page has no folder, so on the web a
linked file is found only through `linkedFiles`, and so is a file behind a content URI on Android.

### A linked file that is missing

The part is left out of the timeline, which closes up around it, and `EditionPartLeftOut` names
its segment. The rest plays. When no part at all can be played, the main file plays plainly. A
linked file that is found and then fails to open is left out the same way, with the failure in the
warning's `detail`.

### Playing and seeking

Parts join with no gap and no seam in the sound, and the picture keeps its schedule across a join,
as two items of a gapless queue do. Two parts that are adjacent ranges of the same file read on
without opening it again.

A seek to a position lands in the part that holds it. Inside the part that plays, it is an ordinary
seek. In another part, that part opens at the position, as `next` opens an item at a start position,
and the picture holds for a moment while it does.

The queue preloads its next item as the timeline's last part nears its end. An A-B loop whose two
points lie in different parts wraps by a seek to A rather than by the gapless pass a loop inside one
part makes. `SleepTimer.EndOfItem` ends at the end of the timeline.

No part join emits an item change, touches the queue or shows in `preloadedIndex`. A join that
the handoff refuses, because the next part's sound has another sample rate or channel count than
the device was opened for, or one part has sound and the other none, joins the old way instead,
with the short silence that brings. It is logged rather than warned, because
`PlaybackWarning.GaplessFallback` names a queue item.

### Switching editions

`selectEdition` opens the media again on the new edition. The new edition starts at the moment of
the main file that plays now, when it plays that moment too, so a switch from a theatrical to an
extended cut keeps the scene. Otherwise it starts from its beginning. The player keeps playing or
stays paused.

## The backend interface

Three members join `PlayerMediaSource` in the `spi` package, all defaulted so that every existing
backend keeps compiling and plays as before. The third was found while building the first step: a
part's range is stated in its own file's segment time, and only the source that opens that file
knows how its times map to the engine's, so a part's times stay as the file states them and each
source maps its own:

```kotlin
public interface PlayerMediaSource {
    // ...
    /** This file's segment UID, 32 lowercase hexadecimal digits, or null when it states none. */
    public val segmentUid: String? get() = null

    /** The file's editions, in stored order. Empty for a container with none. */
    public val editions: List<SourceEdition> get() = emptyList()

    /** A time of this file's segment as the file states it, in the engine's time. */
    public fun fromSegmentTime(time: Pts): Pts = time
}

public data class SourceEdition(
    val uid: Long?,
    val title: String?,
    val isDefault: Boolean,
    val isHidden: Boolean,
    val isOrdered: Boolean,
    /** The edition's chapters as marks, for when it plays plainly. */
    val chapters: List<Chapter>,
    /** The chapters an ordered edition plays, in order; null when it is not ordered or cannot be. */
    val parts: List<SourceEditionPart>?,
)

public data class SourceEditionPart(
    val start: Pts,
    val end: Pts,
    val title: String?,
    val isHidden: Boolean,
    /** The segment the range is in, or null for this file. */
    val segmentUid: String?,
    /** The edition of that segment to play whole, or null to play [start] to [end]. */
    val segmentEditionUid: Long?,
)
```

The FFmpeg backend fills them from `MediaSource.matroska` and its own timestamp mapper. A backend
only reports what a file says: it never searches for or opens a linked file. The engine does that
through the same `MediaSourceFactory.open` it opens every item with, so a custom backend gets linked
files for free by reporting these three members.

## How the engine plays it

The engine plays the parts through the preload and handoff that already join two items of a
gapless queue (`docs/gapless-queue.md`), as it already plays the next pass of an item under a
repeat or an A-B loop without telling the application that an item changed. A part is such a pass,
with a source and a range of its own, and the session keeps the map from the timeline to the part
and the file time.

The other way would be one stitched source in the backend, which the engine would read as one file.
It is the smaller change to write and the wrong one, for three reasons:

- Two files rarely share their decoder parameters, even within one release. The opening of a season
  is encoded apart from the episodes. One stream that changes its parameters in the middle would need
  the engine to change a decoder in the middle of a stream, which the interface cannot ask for today.
- A range that starts between two keyframes has to be decoded from the keyframe before it, with the
  pictures and the sound before its start thrown away. The lanes already do that for a clip and for
  a preloaded item; a source that only hands out packets cannot.
- The handoff already joins the sound of two files with no gap, carries the conversion stages across
  the join and keeps the picture's schedule. A stitched source would have to earn all of that again.

What changes in the engine:

- A session holds its timeline, and the actor maps between positions and parts.
- The preload opens the next part while a part plays, and the handoff joins it. A part join always
  uses the handoff when it can, whatever `QueueConfig.gapless` says, because the parts are one item.
- A seek that leaves the part opens the part that holds the target.
- The search for linked files runs on the open's lane, inside the open, under the open's bounds and
  its cancellation (`docs/cancellation-and-bounded-waits.md`).

## Not in this design

Each of these is a later issue if it is wanted:

- **Hard-linked segments**, which chain whole files through `PrevUUID` and `NextUUID` with no
  ordered edition. KiteFFmpeg reports them, and nothing here plays them.
- **Skip types.** A chapter's `ChapterSkipType` says it is an opening, an ending, a recap or a
  preview, which is what a "skip intro" button needs. KiteFFmpeg reports it; `Chapter` does not carry
  it yet.
- **Editions in `inspect()`**, for a library scanner.
- **Choosing an edition by its UID or name** across files, such as "always the extended cut".

## Tests

`ffmpeg` cannot write an ordered edition or a chapter that links to another file. A short Python
script of our own, `scripts/mkv-editions.py`, which `scripts/testmedia.sh` already has the language
for, rewrites a Matroska file that `ffmpeg` made: it sets the segment UID and replaces the Chapters
element, and moves the seek head and the cue positions to match. It adds no dependency.

The fixtures, in `testmedia/editions/`:

- a season: `episode.mkv`, whose ordered edition plays a range of `op.mkv`, its own content, and a
  range of `ed.mkv`; `op.mkv` is encoded at another size and with another sound layout, so the joins
  change decoders;
- the same season with `op.mkv` taken out;
- a film with two editions, a theatrical cut that skips one scene and an extended cut that plays it;
- an episode whose chapter plays an edition of another file whole;
- two files that link to each other, for the loop.

The tests drive the whole player on the JVM, with real files:

- The season plays the opening, the episode and the ending: its duration is the sum of the three
  ranges, its chapters sit at the joins, the sound has no gap and no repeat at either join, read
  through the audio tap, and every presented picture follows the one before it. A seek into the
  ending presents a picture of `ed.mkv`.
- With `op.mkv` missing, the episode and the ending play, the duration leaves the opening out, and
  `EditionPartLeftOut` names its segment once.
- The film lists both editions, plays the default, and `selectEdition` moves to the other at the same
  scene. The theatrical cut never presents a picture of the skipped scene.
- `linkedFiles` finds a linked file that the folder search cannot.
- The loop of links opens, warns, and plays what it can.
- `orderedEditions = false` plays `episode.mkv` plainly.

Each test is seen to fail with the timeline taken out, before it is seen to pass.

## The order of the work

1. The backend interface and the FFmpeg backend's report of editions, with `PlayerSnapshot.editions`
   and `edition`. Nothing plays differently yet.
2. The timeline for ordered editions within the main file: skipped ranges, positions, chapters and
   seeking.
3. Linked files: the search, the parts from other files, the track matching and the warning.
4. `selectEdition`, `DemuxPolicy.edition` and `orderedEditions`.
5. The user documentation in `docs/media-sources.md`, the README's format notes and the changelog.

Each step regenerates the ABI dumps it changes and carries its own tests.
