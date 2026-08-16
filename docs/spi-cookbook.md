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
  its own locking for engine calls.
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

## Diagnostics

Implement `describeForDiagnostics()` to echo whatever configuration your backend carries; the
string lands verbatim in `KitePlayer.diagnosticsDump()`, which is what users paste into bug
reports. The FFmpeg backend echoes its decoder options exactly as configured, and yours should
echo whatever a report would need to reproduce a session.
