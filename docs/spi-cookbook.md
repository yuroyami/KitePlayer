# The SPI cookbook: a worked custom backend

KitePlayer's engine is pure Kotlin and knows no container format, no codec and no platform. Every
one of those arrives through the SPI in `kiteplayer-core`'s `spi` package, and the FFmpeg backend
in `kiteplayer-ffmpeg` is just one implementation of it. This page walks the complete, compiling
backend the engine's own test suite runs thousands of sessions against: `ScriptedBackend` in
`kiteplayer-core/src/commonTest/kotlin/io/github/yuroyami/kiteplayer/ScriptedBackend.kt`. It is
the reference implementation in the truest sense: if the engine's contracts and that file
disagree, the test suite fails, so they cannot drift apart.

Read this beside that file. Line references are avoided on purpose; the names are stable.

## The five things a backend is

```
MediaBackend.open(media) -> BackendSession
BackendSession.source    -> PlayerMediaSource        (the demuxer)
BackendSession.videoDecoders / audioDecoders / subtitleDecoders
                         -> factories, best first     (the codecs)
VideoDecoder / AudioDecoder / SubtitleDecoder         (send/receive pairs)
PlayerPacket / VideoFrame / AudioBuffer               (what flows through, each owned)
```

`MediaBackend` is one suspend function plus an optional `describeForDiagnostics()` line for the
dump. Throwing from `open` is how a backend refuses: the engine maps the failure to a typed
`PlaybackError` and the player reports it. Nothing else about your backend is called until open
succeeds.

## What `open` receives

`media.io` carries the bytes when the caller used a door: a function that turns a byte array, a
file, a stream or a content URI into a `MediaIoFactory`. `MediaIo` is the one byte contract. Call
the factory once in `open`, and close the reader it returns with the session. A backend that reads
through `MediaIo` plays what every door makes, and no door ever reaches it as a separate case.

`media.demux` is a `DemuxPolicy`: probe depth, damaged packets, timestamp generation, low latency
and bytes to skip. A backend that opens a container owes each field one of two answers. It applies
the field, or it refuses the open with a typed `PlaybackError`. It never ignores one, because a
caller cannot tell an ignored setting from an applied one. The FFmpeg backend turns each field into
FFmpeg options, and refuses a value that FFmpeg cannot take with `PlaybackError.ConfigurationInvalid`.

## The source: `ScriptedSource`

A `PlayerMediaSource` answers five questions and one command:

- `streams`: every stream with kind, codec name, timing and geometry. The scripted source builds
  its list from the script (`MediaScript`), which is exactly what a real demuxer does from a
  container header.
- `duration`, `seekable`, `metadata`, `chapters`, `timestampsMayJump`: facts, not promises. Say
  `seekable = false` and the engine refuses seeks with a typed error instead of trying.
- `selectStreams(indices)`: called once before the first read. The scripted source remembers the
  set and hands out only those streams' packets, which is the same contract libavformat's discard
  flags implement.
- `readPacket()`: the pump. Return packets in interleaved timestamp order; return null at end of
  stream. Every packet you return is OWNED by the caller from that moment: the engine closes it,
  exactly once, always. The scripted source allocates its packets against a `LeakLedger`, and the
  suite's teardown asserts `liveCount == 0`, which is how the ownership law stays true.
- `seekToKeyframe(target)`: move the cursor at or before the target. The engine handles discard
  and preroll; you only have to land on something decodable.

## The decoders: `ScriptedVideoDecoder` and its audio sibling

The send/receive shape mirrors libavcodec, and the engine drives it exactly as documented on the
`VideoDecoder` SPI:

- `send(packet)` returns false when the decoder is full. The engine will drain with `receive()`
  and offer the SAME packet again; a backend must tolerate that retry.
- `send(null)` begins the drain. After it, `receive()` returns the buffered frames and then null,
  with `isDrained` true once everything is out. The engine retries `send(null)` until accepted,
  so a full decoder is never wedged by end of stream.
- `flush(newGeneration)` empties everything and stamps the generation. Frames of an old
  generation that escape a race are discarded by the engine at the last hop; stamping is what
  makes that safety net work.
- Every frame you emit is owned by whoever holds it last. The scripted decoder's `FakeVideoFrame`
  counts closes in the ledger; a real decoder returns pool slots or unrefs AVFrames.

The factory list is ordered best first. Return null from `create` to refuse a stream: the engine
tries the next factory and deselects the stream when every candidate refused, with a
`TrackDeselected` warning naming why.

## What the engine guarantees back

- One thread per role: your source is only ever touched from the demux worker, each decoder from
  its own decode worker, and `flush` from that same worker during seeks. No backend object needs
  its own locking for engine calls. The one exception is a recording, below.
- Quiescence before mutation: seeks stop the sink, park the workers and flush the decoders in a
  fixed, tested order (the `ScriptTrace` assertions in the seek suite pin it).
- Ownership is absolute: anything you hand over is closed exactly once by the engine; anything
  handed to you is yours to close. The leak ledger pattern in the scripted backend is the
  cheapest way to prove your implementation holds the same line.

## Trying it

The scripted backend runs a full session in milliseconds of virtual time:

```kotlin
val harness = CoreHarness(this)          // in a runTest block
harness.openWithRenderer()               // open scripted://media, first frame presented, Paused
harness.core.play()
harness.run(2.seconds)                   // virtual: the whole file plays in wall-microseconds
assertEquals(PlaybackStatus.Ended, harness.core.snapshots.value.status)
```

A new backend can be developed the same way: point the harness at yours, keep the ledger at
zero, and the engine's own suites become your conformance tests.

## A recording source

`RecordingCapable` is an optional interface on the source. Implement it, and
`KitePlayer.startRecording` works with your backend. Without it, that call throws
`UnsupportedOperationException`.

- `startRecording(path)` starts copying the packets your source reads into a file.
- `stopRecording()` finishes the file. Closing the source does the same.
- `recordingPath` is null when no recording runs.

The engine calls these three members from its own thread while the demux worker reads, so guard
the recording state with a lock. The engine ends a recording before it seeks or retires the
session, so a file never gets a jump in it. `ScriptedRecordingSource` logs the calls and writes
nothing. `SourceRecorder` in `kiteplayer-ffmpeg` is the real one, and it writes Matroska with no
re-encode.

## A subtitle typesetter

`SubtitleTypesetter` is another optional seam, and `kiteplayer-libass` is its one implementation.
Implement the interface, wrap it in a `SubtitleTypesetterProvider` with a stable id, and install
the provider the way the transport providers install: a `META-INF/services` entry on the JVM and
Android, an eagerly initialised `SubtitleTypesetters.register(...)` on native and the web.

The engine calls every member from one lane and never concurrently, so hold no lock. It opens a
track from the container header or a whole script, adds one event per packet in the Matroska
form, clears events on a seek, and renders once per video frame while playing. Answer `null` from
`render` when the picture is what it was; the engine republishes nothing for that answer, which is
what keeps the cadence cheap. Answer an empty list when the picture became nothing. The images you
return are positioned in the output surface's pixels and are owned by the engine from then on, so
never reuse their byte arrays.

## An audio resampler

The engine converts the decoder's sample rate to the rate that the audio device accepted. By
default it uses its own windowed-sinc filter, written in Kotlin. `AudioResampler` is the optional
seam that replaces that filter. Give the engine an `AudioResamplerFactory` in
`AudioConfig.resampler`. `KiteFFmpegResampler` in `kiteplayer-ffmpeg` is the one implementation,
and it runs FFmpeg's libswresample.

```kotlin
val player = KitePlayerPlatform.createOrNull(
    PlayerConfig(audio = AudioConfig(resampler = KiteFFmpegResampler())),
)
```

The engine calls `create(inputRate, outputRate, channels)` for each audio stream, and again after
a format change. It asks only when the two rates differ. While pitch correction is off, the
playback speed is folded into `inputRate`, so expect any rate, not only the standard ones.

- The resampler runs after the channel mix, so `channels` is the device's channel count. Samples
  are interleaved floats.
- `outputCapacity(inputFrames)` is the most frames that the next `process` call can write, counting
  what the resampler holds from earlier calls. The engine sizes the output array from this answer.
  With zero input, it is the most frames that `flush` can write.
- `process` may answer zero while the filter fills. `flush` writes what is held at the end of the
  stream. `reset` drops everything held, for a seek.
- The engine never calls two members at the same time, so hold no lock. It closes a resampler when
  it makes a new one, and when the session closes.

When `create` throws, the engine keeps its own sinc for that stream and reports
`PlaybackWarning.ResamplerUnavailable` once per player. `KiteFFmpegResampler` throws on the web,
because the web build of the library has no filter graph. An exception from any other member stops
the session with an error, the same as any other failure in the audio feeder.

## East Asian subtitle text

The engine decides the encoding of an external subtitle file from its bytes: a byte-order mark,
then UTF-8, then a set of single-byte tables. The multi-byte East Asian encodings need tables that
take about 110 KB of generated source, so they live above the core, and
`SubtitleFileParser.decode(bytes, encoding)` is the optional member that reads them. The default
answers null.

The engine names the encoding from the shape of the byte pairs, spelled as the WHATWG Encoding
Standard spells it: `Shift_JIS`, `EUC-JP`, `GBK`, `Big5` or `EUC-KR`. It asks `decode` for the
likeliest name first. When the answer leaves more than one character in fifty as U+FFFD or as a
private use character, it asks for the other names in a fixed order, and keeps the first reading
that passes. So a table must turn every byte sequence it cannot read into U+FFFD.

- The likeliest name, read with nothing left over, is certain, and the engine says nothing.
- Any other reading is kept, and `PlaybackWarning.SubtitleCharsetGuessed` names the encoding used.
- When every answer is null, or no reading passes, the engine reads the file as windows-1252. The
  same warning names the encoding that the bytes appear to be in. A `decode` that throws counts as
  one that answered null.

`EastAsianText` in `kiteplayer-subtitles` is the one implementation, and the FFmpeg backend's
parser hands `decode` to it. It follows the standard's decoder algorithms, with tables that
`scripts/generate-east-asian-tables.py` writes from the standard's index files, so a file reads
the same on every target.

```kotlin
override fun decode(bytes: ByteArray, encoding: String): String? =
    EastAsianText.decode(bytes, encoding)
```

## Diagnostics

Implement `describeForDiagnostics()` to echo whatever configuration your backend carries; the
string lands verbatim in `KitePlayer.diagnosticsDump()`, which is what users paste into bug
reports. The FFmpeg backend echoes its decoder options exactly as configured, and yours should
echo whatever a report would need to reproduce a session.
