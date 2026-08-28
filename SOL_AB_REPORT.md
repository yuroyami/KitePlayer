# SOL A/B Implementation and Research Report

Date: 2026-08-26  
Scope: `KitePlayer` Mission A + Mission B, plus the required `KiteCodec` reader API  
Release candidates: KitePlayer `0.0.19`, KiteCodec `0.1.4`

## Executive result

Both missions are implemented and pass the host-side acceptance gates.

- Mission A: container audio and subtitle disable/enable/switch operations now stay inside the
  running graph. They do not reopen the backend, seek, change epoch, transition playback status,
  or park the video path. Video-track changes deliberately retain the rebuild path.
- Mission B: the dominant command-latency mechanism was an unbounded actor-inline subtitle drain.
  A dense packet backlog could keep the actor inside `handleSubtitles` while pause, play, seek, or
  worker-failure messages waited. The drain is now explicitly budgeted and mailbox-preemptible.
  Ordered cue append/merge and pruning cadence were also corrected so the actor does less work per
  subtitle packet.
- The final KitePlayer JVM core suite passes 344/344 tests. The final FFmpeg/KiteCodec adapter JVM
  suite passes 61/61 tests. KiteCodec's full Wasm suite passes 73/73 tests.
- Public ABI dumps are updated and their repository-specific compatibility gates pass.
- KiteCodec `0.1.4` is published to Maven Local. KitePlayer `0.0.19` publication is recorded in the
  verification table below.

This is code-complete and ready for the owner's physical-device acceptance run. It is not honest to
call the tactile iPhone/Android result verified until those devices and the original dense Kaguya
fixture are available.

## Mission A: instant track switching

### 1. KiteCodec live reader selection

KiteCodec `0.1.4` adds the public additive API:

```kotlin
public fun PacketReader.reselect(streams: List<StreamInfo>)
```

The implementation is present on JVM/Android, Kotlin/Native, and Wasm. Unsupported targets retain
an explicit refusal. Its contract is deliberately narrow:

- it changes delivery at the current demux cursor;
- it never reopens or seeks;
- it never backfills packets already passed;
- read, seek, reselect, and close must be serialized by the caller;
- requests must be non-empty, contain no duplicate indices, and contain the exact `StreamInfo`
  descriptors belonging to the source;
- a failed backend update rolls every discard flag back to the prior selection before surfacing the
  error;
- the Kotlin delivery map/set changes only after the backend transaction succeeds.

The exact Kotlin gate remains necessary because FFmpeg discard flags are advisory for some
demuxers. A packet from a deselected stream is still filtered even if a demuxer surfaces it.

Close restores the source's default stream selection and returns the single-reader cursor lease.
An independent final audit found that the Native close path could strand that lease if restoring a
discard flag threw after `closed = true`. `endPacketReader()` now runs in `finally`, preserving the
restore exception as primary while making the source reusable.

The new Wasm runtime test proves live selection, exact filtering despite advisory discard flags,
zero reopen, zero cursor movement, rejected-request non-mutation, close restoration, and reuse by a
second reader.

### 2. KitePlayer source SPI and adapter

`PlayerMediaSource` now exposes an additive, source-optional seam:

```kotlin
public suspend fun reselectStreams(indices: Set<Int>): Boolean = false
```

`KiteCodecSource` implements it with the active `PacketReader`. It resolves every requested index
before mutation and returns `false` for no reader, an empty request, an unknown/partly unknown set,
or a backend `Unsupported` result. It does not hop dispatchers: the caller-owned demux lane provides
portable serialization.

The adapter's raw lookup table is built only from streams exposed as player video, audio, or
subtitle tracks. This closes an audit finding where a guessed KiteCodec data/attachment index could
have been accepted even though it was absent from `PlayerMediaSource.streams`.

The real `multitrack.mkv` integration test proves that A-to-B selection changes exact packet
delivery without rewinding the byte/demux cursor, and that invalid requests leave the prior
selection intact.

### 3. Why the player uses retained compressed caches

Live reselect is an important general source capability, but it cannot recover packets that the
single demux cursor already read past. KitePlayer normally reads seconds ahead. Reselecting a new
track only at the visible playhead would therefore create a gap until the demux cursor caught up.

The player uses the stronger optional design from the handoff: at session open it selects the
chosen video stream plus every container audio and subtitle stream, then routes compressed packets
into a queue per track. A switch changes the active decoder/queue/selector; it does not touch the
demux cursor. This gives history at the presentation position and removes demux parking from the
hot path.

The added memory is bounded rather than open-ended:

- all queues participate in the existing global byte budget;
- only the selected video/audio queues govern readiness and selected-duration backpressure;
- inactive audio history is retained for at least the ready window/one second and at most half the
  configured total-duration budget;
- inactive subtitle packet history is retained for 30 seconds;
- inactive caches are pruned at a 250 ms media-position cadence;
- unknown packet timestamps or durations stop destructive trimming instead of guessing ownership
  away.

If an alternate audio cache cannot prove coverage at the commit position, the switch returns a
typed `TrackChange.Discarded` and leaves the old track running.

### 4. Subtitle transaction

A container subtitle change now prepares the target decoder at the current epoch, withdraws the
old overlay, retires the old pending packet/decoder/cue selection, and swaps to the retained queue
and per-track cue cache. The actor is the sole writer; video, audio, and demux continue.

Disable, re-enable, A-to-B switching, decoder refusal, overlay withdrawal, cue identity, no reopen,
no seek, no epoch change, no status transition, continuous frame presentation, and leak-free
teardown are all covered.

### 5. Audio transaction

The audio fast path is a prepare/validate/park/commit transaction:

1. Resolve the target and perform an early cache preflight.
2. Construct and epoch-align the target decoder without mutating the live lane.
3. Park only audio decode and audio feed; demux and the full video path keep running.
4. Re-anchor at the post-park commit position and revalidate cache coverage. This matters because
   decoder creation and cooperative parking take real time while audio A continues.
5. Drain actor-owned decoded buffers, flush the old ring in the current epoch, atomically publish
   the new audio lane, close the retired decoder on its owner dispatcher, and release both workers.
6. Restart the device only after the new ring contains data. A dormant sink is not restarted while
   audio is disabled, and a buffering restart cannot call `play()` twice in one pass.

The existing `AudioPlayback` conversion pipeline accepts a different decoder format and converts
into the already-open negotiated sink path. A 48 kHz stereo to 44.1 kHz mono switch is tested with
exactly one sink open. The ring is flushed at the transaction boundary, so no old-language samples
remain after the applied reply; the media epoch and video schedule remain unchanged.

The focused risk suite covers:

- A-to-B after a precise seek, with no stale-epoch/A samples;
- a different sample rate and channel count on one sink;
- switching while paused;
- switching an unseekable source without a seek or reopen;
- typed refusal when disabling audio would leave an audio-only item with no timeline;
- insufficient alternate-cache refusal with A preserved;
- post-park cache revalidation after a deliberately slow decoder construction;
- same-pass play + audio switch, with one device start after data arrives;
- repeated A/B/off/on ownership cycles, one close per decoder and no ledger leak;
- pause/play while audio is disabled, with the dormant sink left stopped.

### 6. Mission A acceptance status

The virtual-time acceptance suite proves, for audio and subtitles:

- backend sessions opened: unchanged;
- source object: unchanged;
- source seeks and core seek-flush cycles: unchanged;
- generation/epoch: unchanged;
- status history entries during each applied change: zero;
- rendered video frames after each change: increasing;
- selected track and audible/rasterized content: changed to the requested target;
- refused decoder/cache targets: old track remains selected and running;
- packet/frame/buffer leak ledger: zero live, zero double-close.

Video selection still rebuilds. That is intentional and retains the prior correctness path.

## Mission B: dense subtitles delaying commands

### 1. Root cause and proof

The device evidence isolated subtitle presence as the variable and cleared the renderer. The code
trace then found the actor executing subtitle parsing/draining inline. Before this work, that drain
had no packet/output budget and no mailbox probe inside it. With a dense read-ahead backlog, one
actor pass could keep consuming subtitle work after a pause/play/seek command had arrived.

The deterministic regression posts `play()` and then `pause()` from inside the scripted subtitle
decoder's `send()` callback. This reproduces a command arriving while `handleSubtitles` owns the
actor without relying on thread races or wall-clock timing. It compares 100 cues with 70,000 cues
shaped at 48 packets/second, matching the device file.

The acceptance result is:

- dense and sparse command wait are identical;
- pause is drained after no more than one additional subtitle packet boundary;
- no actor pass attempts more than 32 subtitle packets;
- the dense fixture forms a genuine multi-pass backlog;
- each packet performs exactly one keyed cue lookup;
- timestamp-ordered packets never enter the cold reorder path.

This convicts actor monopolization, not renderer upload, as the command-delay mechanism represented
by the host regression.

### 2. Fix

`handleSubtitles` now has two hard per-pass budgets: 32 packet attempts and 32 decoder receive
batches. It probes both worker outcomes and commands before work, after each decoder send/receive
boundary, and before cue timing/raster launch. Mailbox items are moved into the existing held queues,
so their order and exactly-once reply rules are preserved. A hit budget or observed mailbox item
reschedules the actor with zero delay.

Cue insertion now has an ordered append fast path. Reordered output takes a stable linear merge,
instead of sorting the entire accumulated table after every decoded packet. Cue-history pruning was
moved from once per decoded packet to once per actor pass and at most once per media second.

The scripted backend was also corrected:

- equal-start cues share one packet and one O(1) keyed lookup;
- the old test model's cue-by-packet full scan was O(cues x packets), about 4.8 billion comparisons
  for 69,513 cues, and was a harness artifact rather than engine behavior;
- seeks redeliver a cue packet when at least one cue in that packet spans the landing.

### 3. Residual Mission B risks

The observed mechanism and acceptance criterion are covered, but the final audit identified useful
non-blocking follow-up measurements:

- budgets count packets/receive batches, not the number of cues inside one pathological decoded
  batch; one enormous equal-start batch or cold merge is still an indivisible actor operation;
- `CueSelector.activeAt` and `nextChangeAfter` remain linear, although container history is now
  pruned to a bounded window and mailbox work is checked before cue timing;
- the 70k test proves dense-backlog command fairness, not an exhaustive render of every one of the
  70,000 cues;
- cold merge stability and pruning cadence are verified by inspection and broader behavior tests,
  not by a dedicated reordered-batch operation-count test;
- seek-spanning cue redelivery is modeled in the scripted source; a real FFmpeg integration fixture
  with a cue beginning before the seek landing would add another useful layer.

These are reasons to keep the device counters on for the acceptance run, not evidence that the
original starvation remains.

## Independent review defects caught and closed

The final read-only reviews found four issues beyond the first green path. All four were fixed before
release:

1. A disabled audio path could be restarted by a later `play()` because session audio ownership
   survived intentionally. Restart is now gated by an active `audioLane`.
2. A slow decoder prepare/worker park could make the early switch position stale. Commit position
   and cache coverage are now sampled again only after both workers park.
3. A buffering restart could start the audio device in the live-swap branch and again in the
   general restart branch. It now waits for ring data and starts once per pass.
4. Native reader close could strand the cursor lease on discard-restore failure, and the adapter
   could accept a hidden raw stream index. Both boundaries are now closed as described above.

## Verification ledger

| Scope | Command/gate | Result |
|---|---|---|
| KitePlayer core | `./gradlew :kiteplayer-core:jvmTest --rerun-tasks` | 344/344 passed; 0 skipped/failures/errors |
| Mission A adversarial audio lane | included `MissionAAudioFastPathRiskTest` | 10/10 passed |
| Mission A graph continuity | included `MissionATrackSwitchAcceptanceTest` | 3/3 passed |
| Mission B dense regression | included `MissionBSubtitleRegressionTest` | 3/3 passed |
| KitePlayer FFmpeg adapter | `./gradlew -Pkiteplayer.useMavenLocal=true :kiteplayer-ffmpeg:jvmTest --rerun-tasks` | 61/61 passed |
| KitePlayer public ABI | `updateKotlinAbi`, then core + FFmpeg `checkKotlinAbi` | passed; JVM/Klib dumps updated |
| KitePlayer multiplatform compile | ABI/update and publication graph | core and FFmpeg JVM, JS/Wasm, Android, Apple, Linux, Windows targets compiled |
| KiteCodec JVM/JNI | `:kitecodec-core:jvmTest -Pkitecodec.hostTargetsOnly=true --rerun-tasks` | 69/69 passed |
| KiteCodec macOS Native | `:kitecodec-core:macosArm64Test -Pkitecodec.hostTargetsOnly=true --rerun-tasks` | 146/146 passed after close hardening |
| KiteCodec Wasm | `:kitecodec-core:wasmJsNodeTest` | 73/73 passed |
| KiteCodec focused Wasm reselect | `PacketReaderReselectWasmTest` | 1/1 passed |
| KiteCodec API compatibility | `:kitecodec-core:apiCheck -Pkitecodec.hostTargetsOnly=true` | passed |
| KiteCodec publication | `./gradlew publishToMavenLocal -x check` | BUILD SUCCESSFUL; 220 tasks; `0.1.4` published locally |
| KitePlayer publication | `./gradlew -Pkiteplayer.useMavenLocal=true publishToMavenLocal -x check` | BUILD SUCCESSFUL in 2m 4s; 1,497 tasks; `0.0.19` published locally |
| Diff hygiene | `git diff --check` in both repositories | passed |

KiteCodec's API dump is intentionally host-target scoped in CI. Running `apiCheck` without
`-Pkitecodec.hostTargetsOnly=true` registers every native target and changes only the dump's target
header/alias grouping; the CI-equivalent scoped command above is the reproducible gate and passes.

Build output still contains pre-existing Kotlin opt-in/deprecation, Dokka-link, Compose duplicate
Klib-name, and Gradle-10 compatibility warnings. None became a compilation or test failure.

## Shipping state and owner device checklist

Local artifacts:

- `io.github.yuroyami:kitecodec-core:0.1.4`
- KitePlayer modules at `0.0.19`

Synkplay was inspected read-only. Its worktree is heavily dirty and its version catalog still pins
`kiteplayer = "0.0.18"`; changing that file would have mixed this work into unrelated owner changes.
The owner should make the one-line pin to `0.0.19` in a clean/understood Synkplay change.

Device verification:

1. Pin Synkplay to `0.0.19` and resolve from Maven Local.
2. Build iOS in Release, never Debug for feel testing:
   `xcodebuild -workspace iosApp/iosApp.xcworkspace -scheme iosApp -configuration Release -destination 'generic/platform=iOS' build`.
3. Build Android with `./gradlew :androidApp:assembleFullRelease`, then explicitly select
   KitePlayer because mpv is the Android default.
4. On the iPhone XS, play the original dense Kaguya/ASS file and its `ffmpeg -sn` remux back to
   back. Scrub repeatedly and mash pause/play. They should feel indistinguishable.
5. While the dense file plays, run subtitle A/off/on/B and audio A/B/off/on cycles. Confirm correct
   text/audio, no picture hitch, no Buffering/Seeking transition, and no backend reopen.
6. Repeat after a precise seek, while paused, and on an unseekable/live source if one is available.
7. Keep Engine statistics logging enabled. Confirm command application remains immediate, presented
   frames continue increasing, and no burst of rebuffer/seek-flush counters accompanies a track
   change.
8. Pull the iOS log with:
   `xcrun devicectl device copy from --device <id> --domain-type appDataContainer --domain-identifier com.yuroyami.syncplay.iosApp --source Documents/logs --destination <dir>`.

If the dense and stripped files still differ, capture the stats window around one delayed command.
The next suspects to distinguish are the bounded selector scan and a single oversized decoder batch,
not the now-bounded packet drain.

## Workspace/commit note

Both repositories, and especially Synkplay, were already dirty before this work. KitePlayer's dirty
baseline included overlapping production/test/version files plus untracked handoff/research/device
files. Creating the handoff's requested separate commits would have captured or split unknown owner
work inside the same files. No commits were created for that reason. The implementation and report
are left as reviewable workspace changes, with unrelated files preserved.
