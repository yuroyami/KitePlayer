# HANDOFF.md

Handoff document for Sol 5.6. Audience: an AI with no prior context on this codebase. Everything
in here was verified by reading the code on 2026-08-26, against KitePlayer 0.0.18 and KiteCodec
0.1.3. Where a claim matters, the file and function that proves it is named.

There are TWO missions, independent, both in this repo. Do them in either order, but keep their
commits separate.

- **Mission A: instant track switching, end to end.** Sections 1 through 8.
- **Mission B: root-cause the subtitle pollution of playback commands.** Section 10. A dense
  subtitle track measurably degrades seeks and pause/play. The correlation is proven on a real
  device; the exact mechanism is NOT yet proven. Find it, prove it with a test, fix it.

The owner is asleep. Work JVM-first: every real device bug of the 2026-08-25 session was
reproduced in the virtual-time harness before it was fixed, and both phones are unavailable to
you. Leave device verification steps documented for the owner to run.

## 1. Mission A: what "phenomenal track switching" means

When the user disables, enables, or switches an audio or subtitle track:

1. The video must never freeze, stutter, or reopen. Playback continuity is sacred.
2. The change must take effect within a couple hundred milliseconds.
3. If the engine cannot do the change, it must say so (typed refusal), never do nothing silently.

Reference behavior: mpv. It flips tracks mid-playback with no visible interruption.

## 2. Where things stand today (as of KitePlayer 0.0.18)

| Case | Today | Mechanism |
|---|---|---|
| Disable container subtitle | INSTANT, done | In-place path, see `inPlaceContainerSubtitleChange` in `PlaybackCore.kt` |
| Switch container subtitle | Works but reopens the whole pipeline. Visible interruption, seconds on a phone | Full session rebuild in `handleTrackChanges` |
| Switch audio track | Works but same full rebuild, same interruption | Same |
| External subtitle load/swap | Already in-place when no container stream is involved | The `CoreCommand.SelectTrack` execute branch in `PlaybackCore.kt` |
| Video track switch | Full rebuild, and that is CORRECT, leave it | Decoder and renderer genuinely need rebuilding |

The repo is `KitePlayer` (this repo). The consumer app is Synkplay at
`../../syncplay-mobile` (module `shared`), whose adapter is
`shared/src/commonMain/kotlin/app/player/kite/KiteImpl.kt`. Synkplay already surfaces refusals on
its OSD (see `KiteImpl.selectTrack`), so silent failure at the app layer is solved. Your work is
in this repo.

## 3. Architecture map (the five layers a packet crosses)

All in `kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt`
unless said otherwise.

1. **Source / reader.** `KiteCodecSource` (in `kiteplayer-ffmpeg`) wraps a KiteCodec `MediaSource`.
   `selectStreams(indices)` opens a `PacketReader` for a fixed stream set, exactly once, before
   the first read. `readPacket()` then delivers packets of the selected streams only.
2. **Demux worker.** `runDemux` loop. Reads packets and routes them by stream index:
   `when (packet.streamIndex) { session.videoStream?.index -> videoQueue ... else -> packet.close() }`.
   Note the `else` branch: a packet that matches no queue is closed and dropped. This is what made
   in-place DISABLE free: stop matching, packets drain to nowhere, nothing else changes.
3. **Queues.** One `PacketQueue` per selected stream, epoch-tagged (see section 6 on epochs).
4. **Decoders.** Video and audio decoders run on their own worker lanes. The subtitle decoder is
   different: it is drained inline by the actor in `handleSubtitles` (text parsing is cheap).
5. **Outputs.** Video: schedule plus renderer. Audio: decoded buffers feed a C ring, then the
   platform sink; the sink is negotiated once per session for ONE `AudioFormat`
   (`OpenSession.negotiatedFormat`). Subtitles: a cue table (`session.subtitleCues`) that a
   selector reads each pass; changed answers are rasterized and pushed as an overlay to the
   renderer (`timeAndPublishCues`, `publishOverlay`).

The whole engine is driven by a single actor (one coroutine that owns all session state and
processes commands from a mailbox). Every mutation below happens on that actor.

## 4. The key verified fact: stream selection is just FFmpeg discard flags

This is the fact that makes instant switching cheap. Verified in KiteCodec sources:

- JVM/Android: `kitecodec-core/src/jvmAndAndroidMain/kotlin/io/github/yuroyami/kitecodec/MediaSource.jvm.kt`,
  `openPacketReader` calls `Internals.streamDiscard(token, info.index !in selected)` per stream.
- iOS/native: `kitecodec-core/src/nativeMain/kotlin/io/github/yuroyami/kitecodec/MediaSource.native.kt`,
  `openPacketReader` calls `ffkmp_stream_discard_none(ptr)` or `ffkmp_stream_discard_all(ptr)` per stream.

So "the reader is fixed at open" is a POLICY of the current API surface, not a physical
limitation. The per-stream discard primitives exist on both platforms and can be flipped on a
live format context. FFmpeg itself supports this; mpv does exactly this for track switching.

Also verified: `PacketReader.close()` restores discard defaults and frees the reader slot
(`endPacketReader`), and a second `openPacketReader` on the same source afterwards is legal.
The format context is shared, so the demux cursor position survives a close/reopen. That gives a
fallback route even without any KiteCodec change.

## 5. The implementation plan

### Phase 1: instant container-subtitle switching

Recommended route, smallest and cleanest:

**Step 1a. Give KiteCodec a live reselect (small, additive).**
Add to `PacketReader` (all real targets, jvmAndAndroid + native; the unsupported/wasm actuals can
throw or return false):

```kotlin
/** Flips which streams this reader delivers, without reopening anything.
 *  Returns after the discard flags are applied. Safe only while no read is in flight. */
public fun reselect(streams: List<StreamInfo>)
```

Implementation is the same per-stream discard loop `openPacketReader` already runs, under the
reader's existing `lock` (reads and seeks already synchronize on it). Bump KiteCodec, publish.

**Step 1b. Expose it through the engine SPI (additive, default off).**
In `kiteplayer-core/.../spi/MediaSource.kt`:

```kotlin
/** Re-selects delivered streams mid-read. Returns false when this source cannot,
 *  in which case the caller must fall back to a reopen. */
public suspend fun reselectStreams(indices: Set<Int>): Boolean = false
```

`KiteCodecSource` overrides it: call `reader.reselect(...)` on the demux dispatcher, return true.
The test backend (`kiteplayer-core/src/commonTest/.../ScriptedBackend.kt`) overrides it too so the
virtual-time tests can exercise the path (update its `selected` set, allow it mid-read).
This is a public API addition: regenerate the API dumps (`apiDump`) in the same commit.

**Step 1c. Widen the in-place path in PlaybackCore.**
`inPlaceContainerSubtitleChange` currently bails when `request.track != null`. Change it to:

1. Resolve the target stream from `session.source.streams` by index. External subtitle targets
   still return false (they have their own path).
2. Park ONLY the demux worker: `session.demuxWorker?.quiesce(QUIESCE_DEADLINE)`, false on refusal.
   Do NOT park video or audio workers; that is the whole point. The video queue keeps feeding the
   schedule during the swap.
3. `session.source.reselectStreams(setOfNotNull(video, audio, newSubtitle))` on
   `dispatchers.demux`. If it returns false, release the worker and return false (rebuild runs).
4. Retire the old trio exactly as the disable path does today: pending packet closed, decoder
   closed, queue closed, `subtitleCues.clear()`, raster job cancelled, overlay withdrawn
   (copy the existing block; the overlay withdrawal rule is explained in section 6).
5. Wire the new trio: decoder from `session.backendSession.subtitleDecoders` (on null, warn
   `TrackDeselected` and select nothing, same as `buildSession` does), a fresh
   `PacketQueue(index, softLimitUs)` flushed to the CURRENT `requestedEpoch`.
6. `tracks = tracks.withSelection(TrackKind.Subtitle, id)`, `snapshotDirty = true`, complete the
   reply with `TrackChange.Applied`, release the demux worker with the current epoch.

Expected user experience after 1c: cues from the new track appear as soon as its next packets
arrive at the current position. Cues BEHIND the current position do not exist until the next seek
re-delivers them. That matches most players and is acceptable; note it in the KDoc.

**Optional enhancement (do only after 1c works): decode all subtitle tracks always.**
Subtitle packets are text, near-free. Select every subtitle stream at open, keep one queue and one
cue table per track, and make switching purely a selector choice. This buys retroactive cues
(history already decoded) and removes even the demux quiesce. It restructures the session's
subtitle state from a single trio to per-track lists, so treat it as its own change with its own
tests, not part of 1c.

### Phase 2: instant audio switching

Same skeleton as 1c (reselect streams, swap decoder and queue in place), plus three audio-only
problems that make it genuinely harder. Solve them in this order:

1. **Sink format.** The platform sink was negotiated for one `AudioFormat`
   (`OpenSession.negotiatedFormat`). A new track may have a different sample rate, channel count,
   or layout. Two options: renegotiate the sink when the format differs (a short, honest audio gap
   of tens of milliseconds), or convert the new track into the already negotiated format through
   the existing converter stage. Check what the feeder already does with format conversion before
   choosing. When formats are identical (common case: switching languages of the same show), no
   renegotiation is needed and the switch can be seamless.
2. **The ring and the clock.** The C audio ring holds roughly 100 to 300 ms of the OLD track. The
   clock master is usually Audio. Decide explicitly: either drain the ring naturally (old language
   plays for a fraction of a second, clock never jumps), or flush the ring and re-anchor the clock
   (instant language change, needs the same anchor discipline `applyPause` uses:
   `session.audio?.anchorClock()` before mutating). Flush plus re-anchor is what mpv feels like.
3. **Workers.** Audio decode and audio feed are separate lanes. Both must be quiesced (not just
   demux) before the decoder is swapped, because the decoder is confined to its worker's
   dispatcher. Video stays running throughout; that is what keeps the picture uninterrupted.

Phase 2 also needs `audioStream`, `audioDecoder`, `audioQueue` in `OpenSession` to become `var`
(the subtitle trio already is, with a comment naming the actor as the only writer; extend the same
comment).

## 6. House rules you must not break

These are engine invariants. Breaking them causes the exact class of bugs that were just fixed.

- **Everything on the actor.** All session mutation happens in actor context (command execute or
  a pass handler). Never mutate session fields from another coroutine.
- **Quiesce before touching what a worker owns.** A decoder belongs to its worker's dispatcher.
  Park the worker (`worker.quiesce(deadline)`), mutate, then `worker.release(epoch)`. A refusal to
  park within the deadline means fall back, never force.
- **Epochs.** `requestedEpoch` (a generation counter) invalidates in-flight work. Queues and
  decoders reject packets and frames stamped with an old epoch. For an in-place track swap you do
  NOT bump the epoch (video work must stay valid); new queues are flushed TO the current epoch so
  the demux worker's offers are accepted. The one-line KP-EPOCH lesson from 0.0.12: a fresh
  component must be aligned to the epoch the world is already at.
- **Withdraw overlays on death (the KP-SUBCLEAR rule from 0.0.17).** The platform renderer is
  shared across sessions; the "did I publish" bookkeeping (`publishedCueKey`) is per-session. Any
  path that retires subtitle state must withdraw the drawn overlay itself (publish an empty
  `SubtitleOverlay` with a bumped `overlayGeneration`) or the last text stays on screen forever.
  `releaseSession` and `inPlaceContainerSubtitleChange` both contain the reference block.
- **Refusals are typed and told.** Legality lives in `rejectionFor` (see the `SelectTrack` arm).
  If you add a new impossible case, refuse it there with a message that names what is missing.
  Synkplay shows those messages to the user.
- **Seek preemption exists (KP-SEEKPRE from 0.0.16).** `seekSuperseded()` drains the command
  mailbox into `heldCommands` from inside a running seek. Your in-place swap runs from
  `handleTrackChanges`, which is a pass handler, so it cannot collide with a running seek, but be
  aware a `SelectTrack` command CAN sit in `heldCommands` while a seek runs and will execute one
  pass later. Do not assume the selection executes in the same pass it was sent.
- **Replies exactly once.** Every `SelectTrack` carries a `CompletableDeferred<TrackChange>`.
  Applied, Discarded, or Superseded, but always completed, exactly once.

## 7. How to test (the proven workflow)

The engine has a virtual-time harness that reproduced every real device bug of the 2026-08-25
session on the JVM. Use it. Red test first, then the fix, then the full suite.

- Harness: `kiteplayer-core/src/commonTest/kotlin/io/github/yuroyami/kiteplayer/CoreHarness.kt`.
  `MediaScript(readDelayUs = ...)` simulates a slow source; `subtitleCues = ...` scripts a
  subtitle track; `harness.run(duration)` advances virtual time.
- Template for continuity assertions:
  `PlaybackSubtitleTest.aContainerSubtitleChangeInterruptsNothing`. It asserts three things across
  a change: `statusHistory` gained NOTHING (no status transition at all), `seekFlushCycles`
  unchanged (no repositioning seek ran), and `renderer.count` kept growing (video never stopped
  presenting). Extend exactly this test when the switch leg goes in-place: today its switch leg
  only asserts correctness, because the switch still rebuilds.
- Template for correctness: `PlaybackSubtitleTest.disablingTheSubtitleTrackClearsTheOverlayAndStaysOff`
  (disable clears, stays cleared, reselect brings cues back).
- For audio: `AudioPlaybackTest` and `AudioPipelineTest` show the ring and sink idioms.
- Run: `./gradlew :kiteplayer-core:jvmTest`. All green before shipping, no exceptions.

Acceptance criteria for "phenomenal":

1. Subtitle disable, enable, and switch: zero entries added to `statusHistory`, zero added seek
   flush cycles, video frame presentation continuous, correct cues drawn, all pinned by tests.
2. Audio switch, same-format tracks: same continuity assertions plus audio resumes on the new
   track; a bounded, documented gap is acceptable only on format renegotiation.
3. Every impossible case refuses with a message, tested.

## 8. Shipping and device verification

1. Bump `VERSION` in this repo's `gradle.properties`, run `./gradlew publishToMavenLocal -x check`.
2. Pin the new version in Synkplay: `kiteplayer = "x.y.z"` in
   `../../syncplay-mobile/gradle/libs.versions.toml`.
3. Build Synkplay for devices. iOS:
   `xcodebuild -workspace iosApp/iosApp.xcworkspace -scheme iosApp -configuration Release -destination 'generic/platform=iOS' build`
   (always Release for feel-testing: a Debug shared framework collapses the software frame path
   about 30x and invalidates any perceived-latency judgment). Android:
   `./gradlew :androidApp:assembleFullRelease`, and pick the KitePlayer engine on the home screen
   wheel, because mpv is the Android default.
4. On-device truth: Synkplay writes a log the app itself keeps. In-room setting
   "Engine statistics logging" adds one line per stats tick. Pull from an iPhone with
   `xcrun devicectl device copy from --device <id> --domain-type appDataContainer
   --domain-identifier com.yuroyami.syncplay.iosApp --source Documents/logs --destination <dir>`.
   The test file that exposed everything: a long-GOP anime MKV with a dense ASS subtitle track
   (tens of thousands of cue events). Keep one around; short clean files hide these bugs.

## 9. Known adjacent issues, NOT part of either mission

- **Synkplay temp diagnostics.** `KiteImpl` currently carries TEMP DIAG log lines (KiteCmd
  command timing, KiteStats probe fields, a presentation-name line) and an iOS
  `KiteIosDiagPresentation` used to read renderer draw counters. They are marked TEMP DIAG and
  scheduled for removal; they do not affect engine behavior.
- **Build config.** Synkplay's root build script used to hardcode `IS_DEBUG = true` into the
  generated BuildConfig; fixed on 2026-08-26 with per-invocation detection. Only relevant if an
  engine appears in a Release build that should not.
- **Unverified iOS compile.** The last Synkplay change (wiring the diagnostics probe into the
  pure-Compose presentations, `KiteComposeEngine.ios.kt` and `.android.kt` in the Synkplay repo)
  compiled clean on Android; the iOS build was interrupted before finishing. If an iOS build
  fails there, the fix is mechanical (imports or the `KiteProbeCapable` interface shape).

## 10. Mission B: the subtitle pollution of seeks and pause/play

### The evidence (real device, 2026-08-26 session)

- File: a 24 minute HEVC 480p MKV with one embedded ASS subtitle stream carrying **69,513
  packets**, about 48 cue events per second (heavy typesetting: signs, karaoke).
- On an iPhone XS, Release build, real engine: seeks and pause/play intermittently applied late
  (up to seconds), sometimes bunching up and then all firing in quick succession. Commands
  themselves were measured fast: the app-side diagnostics stamped every `pause()`/`play()`
  transaction at 1 to 7 ms. The lag was between the command applying and the world visibly
  changing, and in the actor finding time to run commands at all.
- The SAME file with the subtitle stream stripped (`ffmpeg -sn` remux): completely clean.
  Reported by the owner as "the hiccuping is nowhere to be found".
- The renderer was measured innocent: `presented == submitted` on every stats tick, zero
  superseded, zero failed. Frames that exist reach the glass. So the pollution is upstream:
  the subtitle machinery is stealing time from the pipeline, most plausibly from the ACTOR,
  whose passes run every handler including command drain.

### Verified suspects, in order of smell

All in `PlaybackCore.kt` and `subtitle/CueSelector.kt`. These are FACTS about the code; which one
dominates is what you must measure, not assume.

1. **`insertCues` full-sorts on every decoded batch.** `session.subtitleCues.sortBy { it.startMicros }`
   runs on the actor for every packet's worth of decoded cues. At 48 packets per second against a
   list of thousands, that is thousands of comparison sorts per minute on the thread that also
   drains commands.
2. **`CueSelector.activeAt` and `nextChangeAfter` scan from index zero.** Both iterate the cue
   list from the start on every call, and `handleSubtitles` calls them every actor pass while a
   subtitle stream is selected. `pruneCueHistory` bounds the list by dropping cues far behind the
   position, so measure the real steady-state list size before judging the cost.
3. **Backward seeks re-deliver the whole cue span.** A seek flushes and re-decodes; the comment
   on `pruneCueHistory` says backward seeks re-deliver cues from the landing. Right after a seek,
   suspect a flood of subtitle packets through suspect 1, which fits the owner's "seek, then
   commands are late" description exactly.
4. **48 raster storms per second at cue edges.** `timeAndPublishCues` publishes on every change
   of the active set; the design comment assumes "cues change about once a second". Rasters run
   on their own lane, not the actor, but each publish cancels and relaunches a coroutine and the
   Apple rasterizer (CoreText) is the heaviest implementation. This suspect explains platform
   skew (iPhone worse than the Android phone) rather than command latency itself.

### How to prove it

Virtual time cannot measure CPU cost, so mission B needs one of these:

- **Operation counting (works in the existing harness).** Add temporary counters (sort calls,
  cues scanned per pass, publishes per second) and drive `MediaScript(subtitleCues = ...)` with
  a synthetic 70,000 cue list shaped like the real file (dense overlapping windows). Assert the
  counts, they are the mechanism made visible.
- **Real-clock JVM measurement.** A plain JUnit test (not `runTest`) that times `insertCues` and
  `activeAt` against a 70,000 cue list. Milliseconds per call on the actor is the number that
  convicts.

Then fix what the numbers convict. Likely shapes: insertion by binary search instead of re-sort;
a moving cursor or index for the selector instead of scanning from zero; a raster rate cap or
coalescing window for suspect 4. Each fix gets its own red test first, and the whole suite
(`./gradlew :kiteplayer-core:jvmTest`) stays green.

### Acceptance

A scripted 70,000 cue session must show command handling (measure through the harness: a
`pause()` issued during dense cue churn transitions status within one pass) indistinguishable
from a session with 100 cues. Leave a note in this file for the owner with the on-device check:
play the subbed Kaguya file on the iPhone, scrub and mash, compare against the stripped copy;
the two must feel identical.
