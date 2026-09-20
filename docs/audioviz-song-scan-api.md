# Song scan and song map

This contract implements the song scan of the [audio visualiser standard](audioviz-standard.md):
an independent background decode of the playing audio track that produces a song map. The live
path never waits for it. A map only improves context: a fixed loudness reference for the whole
song, and structural events known before playback reaches them.

## Core: decoding audio without playing it

```kotlin
public fun interface AudioScanSink {
    public suspend fun onAudio(pts: Pts, interleaved: FloatArray, frames: Int, format: AudioFormat)
}

public suspend fun scanAudio(media: MediaItem, backend: MediaBackend, track: TrackId? = null,
    sink: AudioScanSink): AudioScanResult

// On KitePlayer, with this player's backend and reader rules:
public suspend fun scanAudio(media: MediaItem, track: TrackId? = null, sink: AudioScanSink): AudioScanResult
```

`scanAudio` opens its own backend session, selects one audio stream, creates a decoder from the
same factory list in the same order as playback, and decodes from the start to the end. It
interleaves each decoded buffer the way the playback feed does and hands it to the sink with the
decoder's timestamp. The samples and timestamps are therefore the ones an `AudioTap` receives for
the same stream, before any seek trim. It opens no audio output and never touches the reader of a
playing session.

- `track` names the audio stream. Null picks the default stream: the container's default ordinary
  track, else the first ordinary track, else the first audio track. The player variant uses the
  player's own choice, including its preferred languages.
- The sink's array is borrowed until it returns, as with `AudioTap`. The scan reads nothing more
  while the sink runs, so a suspending sink paces the whole scan.
- The call blocks on reads in the caller's context. Call it from a context that may block.
- Cancel the calling coroutine to stop it. The decoder, the session and its reader are closed
  before the call returns or throws.
- `AudioScanResult` reports the stream scanned, the frames decoded, the first timestamp, the end
  of the last block and whether the stream's end was reached.

The player variant resolves the item's reader with the same rules as playback: the item's own
reader factory, then a configured resolver, then automatic network providers. It does not share
the playback byte cache. A reader factory must allow a second concurrent open for a scan to be
independent; whether it does is the application's knowledge, which is why the policy below keeps
custom readers off by default.

## Audioviz: the song map

`SongMap` is the result of one scan of one track:

| Field | Meaning |
| --- | --- |
| `version` | The analysis version that produced it. A different version is never reused. |
| `coveredThroughMicros` | Media time analysed, from the start of the stream. |
| `complete` | Whether the scan reached the end of the stream. |
| `referencePower` | The 95th percentile of valid ungated 400 ms programme powers, or null. |
| `structure` | Section boundaries, drops and breakdowns, with their original times. |
| `keys` | Key segments: start, end, tonic, mode and confidence. |

The reference is present only when the scan is complete and at least one reading is above
-70 dB relative to full scale; an all-silent map has none. It is the same linear K-weighted power
convention as the causal reference, bounded to the same plus or minus 24 dB gain.

Structure comes from running the live section detector over the scanned audio, plus key
changes. Where the key estimate changes from one known key to another, a `SectionBoundary` is
placed where the new key's lead over the old one steps up: the split of a least-squares fit of two
constant segments over the preceding 30 seconds, the later segment higher. A chord both keys share
dips that lead inside a section, so the fit follows the mean rather than the sign of each step.
The key estimate itself lagged a mode change by seventeen seconds on the development fixtures,
which is why the history is that long. No key change is added within four seconds of another
boundary. This is the harmony-only case the live detector leaves out. Offline results can differ
from live ones; the map does not claim they agree.

The map keeps a programme level curve, one value per 100 ms, to verify the map against live audio.
A four minute song holds about 2400 values; the whole map stays far below the standard's 2 MB.

## When a scan runs

`SongScanPolicy` decides, per item, whether a scan may start:

| Source | Default |
| --- | --- |
| A local file URI | Scanned |
| A network URI | Only with `network = true`; the application owns the extra download |
| An item with its own reader factory | Only with `customReaders = true` |

An item with no known duration, or that is not seekable, is live or read-once and is never
scanned. A scan starts when the player's item or selected audio track changes, after one second of
playback, so a quick skip through a queue scans nothing.

At most one scan runs at a time in the process by default, whatever the number of players and
views. A newer request cancels an older one for the same player. A result is installed only when
its item, track and audio generation lineage still match; a late result from a replaced request is
dropped. Scans run on a background dispatcher.

## Ranges

One scan of a whole song used to arrive after the song had finished, which helped nobody. A song
is now cut into ranges that are scanned at the same time, on half the device's cores and at most
four, and the parts are joined into one map.

A range is used only when each one is at least a minute long, because a shorter range spends more
time warming up than it saves. A song under two minutes is scanned in one pass.

Each worker analyses 40 seconds of audio before the stretch it owns and 5 seconds past its end,
and records nothing outside what it owns. The 40 seconds cover the longest memory in the analysis:
the section detector's 12.8 second ring and 8 second look-back, the key tracker's 8 second time
constant, and the 30 seconds of pitch-class profiles that place a key change. The 5 seconds cover
the section detector's look-ahead.

Joining the parts: the level histograms sum, so the loudness reference is the one a single pass
would have found. The level curves share one 100 ms grid and drop into their own places, and a gap
carries the previous reading forward rather than reading as silence. Structural events only sort,
because no two parts own the same time. Key segments that meet at a seam with the same key join
back into one.

A container that seeks by estimate can land late enough to leave a hole between two parts, so the
parts are checked for continuity before they are joined. A short one falls back to a single pass
over the whole song, which is also what an item with no duration gets.

`KitePlayer.scanAudio` takes an `AudioScanRange` for this. A range seeks first, so it needs a
seekable item, and it stops at the first block that ends at or after the range's end. Two ranges
that meet therefore cover every sample between them.

## Cache

A bounded session cache holds the 16 newest maps. The key is a hash of the item's URI, the audio
stream index, the stream's declared duration, sample rate and channel count, and the analysis
version. The URI is hashed and never stored, and headers never enter the key, so authentication
material does not reach the cache. A URI alone does not prove identical content, so a cached map
is also verified against live audio before it is used.

## Keeping maps between runs

The session cache dies with the process, so without a store every launch scans every song again.
`SongMapStore` is where a finished map is kept. The application owns the location, because only it
knows which of its directories the system may clear and which are backed up.

```kotlin
val maps = SongMapStore.inDirectory(File(context.cacheDir, "songmaps").absolutePath)
val viz = rememberAudioVizState(player, songMapStore = maps)
```

`inDirectory` writes one file per map and deletes the oldest when there are more than it keeps,
128 by default. A map of a four minute song is about ten kilobytes. Give it a directory of its own.

An application with its own database may implement the interface instead. It receives an opaque
key that is safe in a file name and already carries the analysis version, and bytes to store
unchanged. It answers null for anything missing or unreadable rather than raising. Calls happen on
a background dispatcher, so they may block.

The stored form is checked on every read: a file from another analysis version, a half-written
file and a file that is not a map at all are all misses, never wrong numbers. A map that live
audio disagrees with is dropped from the store as well as from the session cache, so it cannot
come back on the next launch.

## Applying a map

A complete map moves the one shared gain to its reference over two media seconds, and a seek
within the same track keeps it. Its structural events are installed on the timeline as the song
map source, which delivers them on time and counts live structural duplicates. A partial map
installs its structure for the covered range only and never supplies a reference.

Verification: during the first five seconds of live analysis with a map installed, the live
programme level is compared with the map's curve at the same media times. A mean absolute
difference above 1.5 dB discards the map and its cache entry, and live analysis continues alone.
This catches changed content behind the same URI and any timestamp convention mismatch.

## Views and migration

`rememberAudioVizState(player, songScan)` sets the policy for that player's shared analysis
session; the newest view to attach sets it. The parameter has a default, so source callers keep
compiling, but compiled callers of the old signature must recompile. `SongScanPolicy.Off` turns
scans off, and `songMapStore` says where finished maps are kept. `AudioAnalysisStats.rejectedSongMaps`
counts maps withdrawn after verification. Scans read on the platform's blocking-capable dispatcher.

## Development evidence on 2026-09-19

A real-media test plays a clip through the default desktop player with an audio tap attached,
scans the same track through `KitePlayer.scanAudio`, and requires every tap block after the first
to have an identical scan block at the same timestamp: same frame count, format and samples. The
first tap block is skipped because the start of playback may trim it.

| Clip | What it exercises | Blocks compared, all identical |
| --- | --- | ---: |
| `audio-aac.m4a` | AAC encoder delay | 94 |
| `audio-mp3.mp3` | MP3 padding | 77 |
| `tsoffset1400.ts` | A transport stream with a nonzero start time | 94 |
| `multitrack.mkv` | The second audio track, selected before playback | 94 |

Shifting the scan's timestamps by one microsecond failed all four. Scripted-backend tests check
stream choice, ordering, pacing by the sink, cancellation and the closing of the decoder, packets
and session. Scanner tests on fakes check the settle delay, the policy table, cancellation of a
replaced scan, one scan at a time across players, the cache and its key, and the pacer's share.
Feed tests check that a map of the same audio stays and moves the shared gain to its reference,
that a map of other audio is withdrawn within five seconds of readings, and that mapped structure
arrives on time with the live duplicate counted.

## Qualification boundary

Scan cost is measured on the host only; the standard's four-minute target of five core-seconds on
a phone remains a device measurement, and so does any effect of a scan on playback there. Network
scans are covered by the policy and the core resolver test, not by a slow-network measurement.
