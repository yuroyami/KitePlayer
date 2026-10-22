# Opening media

A `MediaItem` tells the player what to open. This page describes the sources beyond a plain
address: local files, bytes, a file that the user picked, a file that is still growing, and audio
that plays beside the media.

## Paths and addresses

A file path or a URL needs nothing more. `MediaItem("/sdcard/movie.mkv")` goes straight to FFmpeg's
own file reader, which is the fastest way to read a local file.

The address of a Compose Multiplatform resource works the same way on Android, iOS and the desktop
JVM. `MediaItem(Res.getUri("files/intro.mp4"))` plays the bundled file, from the app's assets on
Android and from the app's jar on the desktop. On Android, the player reads the assets through the
application context, which a small content provider of `kiteplayer-io` keeps from the moment the
app starts (as Compose's own resources do). Your app declares that provider in its manifest, as the
[module contract](module-contract.md) shows.

Network addresses are the subject of [Network streams](streaming.md).

## Other sources

For anything else, use a door. A door is a function that turns what you have into a
`MediaIoFactory` for the item's `io` field. Each open of the item gets a new reader from the
factory, because a track switch, a loop or a recovery opens the item again.

| You have | Door | Where |
| --- | --- | --- |
| A `ByteArray` | `MediaIo.ofBytes(bytes)` | Everywhere |
| Bytes that your code pushes, from a socket or a decryptor | `PipedMediaIo`, a new one in each open | Everywhere |
| A `File` or a `Path` | `MediaIo.ofFile(file)`, `MediaIo.ofPath(path)` | JVM, Android |
| A `FileChannel` that you keep open | `MediaIo.ofChannel(channel)` | JVM, Android |
| An `InputStream` | `MediaIo.ofStream { openStream() }` | JVM, Android |
| A `content://` URI, such as one from the file picker | `MediaIo.ofUri(contentResolver, uri)` | Android |
| A file in the app's `assets` | `MediaIo.ofAsset(assets, "clip.mp4")` | Android |
| A Compose resource's `Res.getUri` address, when you set a resolver of your own | `MediaIo.ofResourceUri(context, uri)`, `MediaIo.ofResourceUri(uri)` | Android, JVM |
| A path that every read must pass through Kotlin | `MediaIo.ofPath("/path/to/clip.mp4")` | Apple, Linux |
| A file URL, such as one from the document picker | `MediaIo.ofUrl(url)` | Apple |

```kotlin
import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.from
import io.github.yuroyami.kiteplayer.io.ofUri

val io = MediaIo.ofUri(contentResolver, uri)
player.open(MediaItem.from(io, label = "picked.mkv"))
```

- The label names the item in logs. It also helps FFmpeg guess the format.
- A stream and a pipe read forward only, so the player cannot seek in them.
- `MediaIo.ofBytes` does not copy the array. Keep the array unchanged while playback can read it.
- `MediaIo.ofBytes` and `PipedMediaIo` are in `kiteplayer-core`. The other doors are in
  `kiteplayer-io`, which comes with `kiteplayer`.
- Each `MediaIo.of...` door is an extension function that needs its own import: `ofBytes` from
  `io.github.yuroyami.kiteplayer`, and the others from `io.github.yuroyami.kiteplayer.io`.

## Files that are still growing

A file that is still being written, such as a recording in progress or a download that plays as it
arrives, plays to its current end and on as it grows when the item says so:
`MediaItem(path, growth = FileGrowth())`. The player waits at the end for more, and ends the item
once the file has not grown for `FileGrowth.endsAfter` (two seconds by default). Its length grows
with the file, and a seek reaches any part already written. A plain path needs `kiteplayer-io` for
this, while an item with its own `io` needs nothing more.

## Editions and linked files

A Matroska file can carry several editions of its chapters, such as a theatrical and an extended
cut of a film. The player lists them in `state.value.editions`, and `state.value.edition` names the
one that plays: the one the file marks as the default, unless the item asks for another with
`DemuxPolicy(edition = index)`. `selectEdition(index)` plays another while the media is open. The
new edition starts at the same moment of the file when it plays that moment too, so a switch between
two cuts keeps the scene, and starts from its beginning otherwise. The player keeps playing or stays
paused.

An ordered edition is a timeline: it plays its chapters in order, each one a range of time, and
skips everything else. The player plays it that way:

- The position, the length, the chapters, seeks, the start position, the A-B loop, the markers
  and the seek bar pictures all count along the timeline. The chapters are the edition's, at their
  places on it, and a hidden chapter plays without being listed.
- The parts join with no gap in the sound, and no part starts or ends the item.
- The times of the frames and the sound, such as those an audio tap or a captured frame carry, stay
  the times of the file that plays them, so two parts can repeat a time. Use the position to tell
  two moments of the timeline apart.

A chapter of an ordered edition can play a range of another file, which it names by that file's
segment UID rather than by its name. Releases of a season often keep one opening and one ending
file and link them from every episode. The player looks for those files in this order:

1. the files in `MediaItem.linkedFiles`;
2. for a local file with no `io`, the Matroska files beside it (`.mkv`, `.mka`, `.mks` and
   `.mk3d`), in the order of their names, at most 256 of them.

```kotlin
import io.github.yuroyami.kiteplayer.LinkedFile
import io.github.yuroyami.kiteplayer.mediaItem

val episode = mediaItem("https://cdn.example.com/s1/e03.mkv") {
    linkedFile(LinkedFile("https://cdn.example.com/s1/opening.mkv"))
    linkedFile(LinkedFile("https://cdn.example.com/s1/ending.mkv"))
}
```

- An application needs `linkedFiles` only when the player cannot list the folder: for an address,
  a content URI, a file read through `io`, and anything on the web, which has no folders.
- The player opens each candidate once to read its segment UID. The item's headers go to a linked
  address only when it has the scheme, host and port of the item's own address. Give a file its
  own `io` to read it any other way.
- A part whose file is not found is left out, the timeline closes up around it, and the warning
  `EditionPartLeftOut` names its segment once. When no part can play, the file plays plainly.
- The item's tracks, tags and editions stay those of the main file. While a part of another file
  plays, each selected track plays that file's track of the same kind and language, then title,
  then place. A track choice made then names the main file's track, as always.
- A chapter can play another file's whole edition. The player follows such links four deep, and
  leaves out a link that leads back to a file already on the way.
- An external subtitle file is timed by the file that plays, so it lines up with the parts of the
  main file and not with those of another file.

An edition plays plainly, as FFmpeg plays it, when the item has a `clip`, when the file is still
growing, when the source cannot seek, and when the item asks with
`DemuxPolicy(orderedEditions = false)`, which suits a release whose links point at the wrong files.

## Several settings on one item

Build the item in a block. An empty block gives the same item as `MediaItem(uri)`.

```kotlin
import io.github.yuroyami.kiteplayer.CorruptPackets
import io.github.yuroyami.kiteplayer.ProbeDepth
import io.github.yuroyami.kiteplayer.mediaItem

val item = mediaItem("https://cdn.example.com/live/channel.ts") {
    header("Authorization", "Bearer $token")
    probe(ProbeDepth.Fast)
    corruptPackets(CorruptPackets.Drop)
    lowLatency()
}
```

`probe`, `corruptPackets`, `lowLatency` and the other demux settings say how the container opens,
and they fill the item's `demux` field. Raw FFmpeg options still go in `openOptions`, but an option
that a typed field also sets refuses the open with a typed error. `MediaItem` also carries
`startPosition`, `externalSubtitles`, `externalAudio`, `linkedFiles`, `videoFilter` for an FFmpeg
filter chain, and `formatHint` when a container needs naming.

## Audio beside the media

An item can play audio files or streams beside its media, such as a dub or a commentary. Each one
is an external audio input.

```kotlin
import io.github.yuroyami.kiteplayer.AudioSource
import io.github.yuroyami.kiteplayer.mediaItem

val french = AudioSource(
    "https://cdn.example.com/film.fr.m4a",
    title = "French",
    language = "fr",
)
val item = mediaItem("https://cdn.example.com/film.mp4") {
    externalAudio(french)
}
```

- The player lists the audio tracks of each input in `tracks.audio`, after the media's own tracks.
- `selectTrack(TrackKind.Audio, id)` plays one. The player reads only the input that plays.
- At open, the player chooses the media's own sound first. When the media has no sound, the player
  chooses the first input.
- A seek moves the media and the input together. An input that is shorter than the media goes
  silent at its end.
- When an input does not open, the player leaves it out and sends the warning
  `AudioSourceUnreadable`. The item still plays.
