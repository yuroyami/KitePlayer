# SALANKE ledger

The running record of what the SALANKE verification pass (`SALANKE_SUPREME.md`) told us to do,
and what actually got done. One row per work item. Update this file in the same session that
does the work. Statuses: DONE, OPEN, DROPPED (with the reason), SUBSUMED (closed by another row).

Rules for this file:
- Every DONE row names its evidence: a test, a file, or a measured output. No evidence, no DONE.
- Deviations from the plan are written down in the Notes column, not smoothed over.
- This file does not replace the register (`KPKMP-FUTURE.md`). When a row here graduates into
  real engine work, it gets a register row and this file points at it.

## Tier 0: before the 0.0.19 surge commits

Worked 2026-08-26, single pass, all on the working tree. Suites after: kiteplayer-core jvmTest
346/346, kiteplayer-ffmpeg jvmTest 60/60, both ABI checks green.

| # | Item | Status | Evidence |
|---|---|---|---|
| T0-1a | A packet with a start but no duration blocks `PacketQueue.dropBefore` forever, so its lane grows until it owns the byte budget | DONE | `dropBefore` takes a required `assumedDurationUs`; each caller states a bound (audio 1 s, subtitles 30 s). Red first: the old pinning test was rewritten and failed to compile on the new parameter, then `PacketQueueTest` 3/3 green |
| T0-1b | `relieveInterleaving` could only cut selected lanes, so an inactive switch cache holding the byte cap wedged playback forever | DONE | New test `an inactive switch cache that hoards the byte budget is truncated rather than wedging` in `PlaybackCoreTest`. Watched red (stuck in Buffering), green after the fix, and mutation-checked: disabling the inactive cut turns it red again |
| T0-1c | Prune cadence gated on published position, so a stalled player could never prune again | SUBSUMED | Closed by T0-1b: relief no longer depends on the prune at all. With relief able to cut inactive lanes, a stall recovers through the relief path, and the position-gated prune is only a retention nicety. No code changed for this half |
| T0-2 | `ScriptedBackend.sinkOpenFails` existed and no test ever set it, so the buildSession rollback ledger was unguarded | DONE | New test `a device that refuses to open fails typed and rolls the whole build back` in `PlaybackCoreTest`. Mutation-checked: disabling the rollback loop fails this test plus three older ones, so the sink-open leg is now covered from its own angle |
| T0-3 | The scripted source redelivered a seek-spanning subtitle cue that the real source never redelivers, so S16 was unprovable | DONE | `ScriptedBackend.seekToKeyframe` and the packet-candidate filter now model file order only. The old MissionB test that passed on the generosity is rewritten as `aSeekIntoASpanningCueLosesItUntilASeekBeforeItsStart`, which pins the honest contract, says loudly that it pins limitation S16 and not a desired outcome, and proves the positive half (a seek before the cue start does redeliver it) |
| T0-4 | KP-SEEKPRE and KP-PLAYACK existed only in code comments and HANDOFF.md, in neither register file (RULE ONE) | DONE | `KPKMP-PAST.md` section 14.167 documents both mechanisms with anchors |
| T0-5 | `reselectStreams` was shipped public ABI on the player SPI with no engine caller | DONE, by deletion | Removed from `spi/MediaSource.kt`, `KiteCodecSource`, `ScriptedBackend`; the ffmpeg jvmTest for it deleted; both modules' ABI dumps regenerated and clean. KiteCodec keeps `PacketReader.reselect`: it is a committed, tested primitive there, and the day a low-memory or network mode needs live reselection the SPI member comes back WITH its caller |

### What Tier 0 changed in the engine, in one place

- `PacketQueue.dropBefore(cutoffUs, assumedDurationUs)`: a missing duration is bounded by the
  caller's stand-in instead of stopping the trim forever. A packet with no timestamps at all
  still stops the trim.
- `relieveInterleaving`: relief now runs only while some selected queue is held UNDER readiness
  by the budget (a ready, paused session is healthy and never cut). It first cuts the fattest
  inactive lane, which cannot gap playback and needs no warning because the cost surfaces later
  as a typed switch refusal. Cutting selected media still requires true starvation and still
  warns `PathologicalInterleaving`.

### Findings made during the work, not in SALANKE_SUPREME

| # | Finding | Status |
|---|---|---|
| L-1 | A config with `readyDuration` greater than `totalDuration` can wedge any file longer than the buffer in Buffering forever: readiness needs more buffered than the budget ever allows. Found when the wedge test used exactly that config by accident. Defaults (1 s vs 30 s) are safe. A `BufferPolicy` init require would close it | OPEN, candidate register row |
| L-2 | First relief draft cut inactive lanes on ANY over-budget state, which ate the switch caches of every healthily paused session sitting at its duration cap and broke five MissionA tests. The readiness gate is the fix, and those five tests are the regression net for it | DONE, lesson recorded |

## Tier 1: worked 2026-08-26, same session

Suites after: core jvmTest 349/349, ffmpeg jvmTest 61/61, output androidHostTest 126/126,
KiteCodec wasmJsNodeTest 74/74, both KitePlayer ABI checks and KiteCodec apiCheck green.
KiteCodec 0.1.4 republished to Maven Local with the disposition fix (the version never left
this machine, so no bump).

| # | Item | Status | Evidence |
|---|---|---|---|
| T1-6 | S23: a cached pre-pause AudioTimestamp passes every admission check after resume, mis-anchoring the master clock by up to the pause length | DONE on host; device confirmation still owed | `AudioTrackSink` now arms `timestampFloorNanos` from its own injected clock at resume; a reading sampled before that instant is rejected and the head fallback answers until the HAL produces a post-resume reading. Red first in `androidHostTest` with the scripted driver parroting the stale reading (`a pre-pause device timestamp cannot anchor the clock after resume`): watched fail on `expected head, was timestamp`, then green, including the fresh-reading re-acceptance half |
| T1-7 | N01: no production frame type implemented `SoftwareReadableFrame`, so `captureFrame()` threw for every real decoded frame | DONE | `KiteCodecVideoFrame` implements the interface over `readableFrame()` (software frames directly, VideoToolbox through the existing downloaded twin) with a tightly packed plane-geometry table for all ten modelled formats; Opaque refuses typed. Red first in ffmpeg jvmTest decoding a real frame from `baseline.mkv` (`aDecodedSoftwareFrameExposesItsPlanes`), then green. ABI dumps regenerated |
| T1-8 | N04: wasmJs built every `StreamInfo` without a disposition, so default, forced, accessibility and cover-art policies were all dead on the web | DONE | All five binding entry points existed; `readStreams` now reads `ffkmp_stream_disposition` and decodes it with the hoisted flag constants, mirroring the JVM. Red first in `StreamDispositionWasmTest` against the fake module answering real AV_DISPOSITION bit values, then green |
| T1-9 | S02: forced-subtitle fallback hid behind `preferredLanguages`; S11 player half: the audio picker could auto-pick a descriptive track over an ordinary sibling | DONE | `pickSubtitle` gains the audio-language forced leg (a forced track is authored for this audio's viewers; works with no preference configured); `pickAudio` ranks ordinary above accessibility at every tier, stable sort, accessibility still reachable when it is the only candidate. Two red tests plus one green negative pin in `PlaybackCoreTest`; `autoSelectForced` KDoc updated in the same change |
| T1-10 | S05 player half plus N35: time-box the seek, pass `notEarlierThan` | REPAID by T2-11 on 08-27: the time-box half landed through the interrupt seam; `notEarlierThan` stays unused, still no falsifiable behavior. The original deferral reasoning follows | Both halves fail safety or falsifiability today. A `withTimeoutOrNull` around the blocking seek frees the actor but leaves the uncancellable FFmpeg call running on the demux lane, violating the PacketReader serialization contract the moment the next read runs, and the eventual teardown join blocks on the same stuck call (the S17 shape). A `notEarlierThan` floor at content start plumbs correctly on both backends but has no falsifiable host behavior with the existing fixtures, and a real bounded window needs the retry-on-refusal policy that belongs with the interrupt callback. One trap already recorded for that work: `hls_read_header` installs `interrupt_callback` on only one branch (SUPREME N36) |

### S11's other half, for the record

The KiteCodec side of S11 stays open: `Disposition` collapses hearing-impaired and visual-impaired
into one player-facing boolean, and `AV_DISPOSITION_DESCRIPTIONS` and `COMMENT` are read nowhere.
Widening that is a KiteCodec public API change and was not part of this tier's player half.

## Tier 2: worked 2026-08-27, same rules

Suites after: KitePlayer core 355/355, ffmpeg 61/61, output androidHostTest 127/127; KiteCodec
jvm 70/70, macosArm64 147/147, wasm 74/74; every ABI gate green; KiteCodec 0.1.4 republished to
Maven Local twice (disposition, then the interrupt seam).

| # | Item | Status | Evidence |
|---|---|---|---|
| T2-11 | KC-CANCEL: no interrupt callback anywhere, so a blocking FFmpeg call could not be cancelled | LANDED, register row REDUCED (not closed) | The seam exists on every backend: a per-open int cell behind `interrupt_callback`, `MediaSource.interrupt()`, `AVERROR_EXIT` as the new typed `FFmpegError.Interrupted`. Key discovery: FFmpeg only polls the seam inside find_stream_info and the URL protocol loop, so KiteCodec's read and seek entry helpers and BOTH custom-AVIO callbacks poll it too, proven red-first by the jvm contract test (read, interrupt, next read fails typed, close stays legal), which also runs on native. KitePlayer consumption: container seeks are deadline-bounded (10 s, 2 s grace, typed SourceUnavailable, replies answered after the state settles), and teardown interrupts the source before its joins. Remainder lives in the reduced register row: open() not interruptible, a mid-playback stall still waits for the user, C-52..54 are the network twins. KPKMP-PAST 14.168 |
| T2-12 | SOL-P8 remainder: the mixer folded only to stereo, Android never named its device layout, and RC-1's false "5.1 outputs stereo" claim sat in three register places | DONE, register row REDUCED | `ChannelMixer.matrixFor` now folds any modelled wider layout into a smaller surround target by speaker name (side/back equivalence, back-centre split at -3 dB, LFE policy honoured, house normalize policy), red-first in `ChannelMixerTest`, including the maskless 6-channel device. `AudioTrackSink` names the layout mask for counts 1/2/6/8, red-first in the host suite. All three stale register passages corrected. Remainder: desktop output is stereo only, and there is no upmix |
| T2-13 | SOL-A6's N11 half: the sinks emit Underrun and FormatChangeRequested and the engine dropped both with `else -> Unit` | DONE for the drops; SOL-A6 proper stays open | New typed `PlaybackWarning.AudioDeviceUnderrun`, warned once per session; FormatChangeRequested surfaces as `AudioDeviceChanged` naming the request. The old test that PINNED the drop was rewritten red-first, and the warning audit plus three KDoc contracts moved in the same change. N12 stays open under SOL-A6: CoreAudioSink still never emits DeviceChanged, so the route-change warning path is dead code on Apple, and proving a real CoreAudio property listener needs hardware, not a host test |

### Tier 2 notes

- The teardown-interrupt mutation check did not fail a test, it hung the entire suite past every
  timeout, which is exactly the failure mode the guard removes. Recorded as evidence: red by hang.
- The T2-11 engine wiring was written before its tests in one batch, a TDD ordering slip worth
  naming. The seek test was still genuinely born red, on a reply-ordering bug it caught and
  fixed; the teardown test's bite was then proven by the mutation above.
- The fold keeps the house normalize policy: OFF by default for FFmpeg and mpv parity, so merged
  surrounds sum unnormalized; `DownmixConfig(normalize = true)` bounds them. Tested both ways.

## What remains of the SALANKE_SUPREME work order

| Tier | Item | Status |
|---|---|---|
| 3 | The twelve truth rows in SUPREME 6.4 plus RC-2 and RC-3 (RC-1 was corrected with T2-12) | OPEN |
| 4 | M15 charset half, M16 libass wiring, M01/M02 subtitle decoder bridge, N24 metadata surface | OPEN |
