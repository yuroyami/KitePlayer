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
changes: where the key estimate changes from one known key to another, a `SectionBoundary` is
placed at the change point that best separates the two keys' correlations over the preceding
12 seconds, unless another boundary lies within four seconds. This is the harmony-only case the
live detector leaves out. Offline detector results can differ from live ones; the map does not
claim they agree.

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
dropped. The scan runs on a background dispatcher and paces itself to use at most a quarter of one
core, measured as busy time over wall time. Low thread priority alone is not the budget.

## Cache

A bounded session cache holds the 16 newest maps. The key is a hash of the item's URI, the audio
stream index, the stream's declared duration, sample rate and channel count, and the analysis
version. The URI is hashed and never stored, and headers never enter the key, so authentication
material does not reach the cache. A URI alone does not prove identical content, so a cached map
is also verified against live audio before it is used.

## Applying a map

A complete map moves the one shared gain to its reference over two media seconds, and a seek
within the same track keeps it. Its structural events are installed on the timeline as the song
map source, which delivers them on time and counts live structural duplicates. A partial map
installs its structure for the covered range only and never supplies a reference.

Verification: during the first five seconds of live analysis with a map installed, the live
programme level is compared with the map's curve at the same media times. A mean absolute
difference above 1.5 dB discards the map and its cache entry, and live analysis continues alone.
This catches changed content behind the same URI and any timestamp convention mismatch.

## Qualification boundary

Core tests use a scripted backend to check stream choice, ordering, pacing, cancellation and
resource closing. A real-media test decodes the same file through playback and through a scan
and compares their samples and timestamps. Scan cost is measured on the host; the standard's
four-minute target of five core-seconds on a phone remains a device measurement.
