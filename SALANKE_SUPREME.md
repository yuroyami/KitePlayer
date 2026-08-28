# SALANKE_SUPREME.md

- Date: 2026-08-26
- Input: `SALANKE_FINAL.md`, plus its three source ledgers
- Checked against: KitePlayer `0.0.19` **working tree** (not HEAD), KiteCodec `0.1.4`, FFmpeg `n8.0` as vendored in KiteCodec, and the project's own open register in `KPKMP-FUTURE.md`
- Method: every row in `SALANKE_FINAL.md` was re-read against the code by a separate reader, with no access to the previous reader's conclusion

## Read this first

`SALANKE_FINAL.md` mined three upstream trackers and asked, for each bug those projects hit, whether
KitePlayer can hit it too. That was a good question and the file answers it 904 times. This document
does not replace it. It puts every answer through four tests and writes down what changed.

The four tests:

| Test | Question |
|---|---|
| **Receipt** | Do the files and functions the row names actually exist, at that path, in that repo? |
| **Claim** | Does the code say what the row says it says? Read it, do not trust the sentence. |
| **Ownership** | Is this already an open row in `KPKMP-FUTURE.md`? A rediscovery is not a discovery. |
| **Staleness** | Did the uncommitted `0.0.19` work already close it, or already create it? |

Short version of the result:

- **The mechanical quality is excellent.** 1,372 issue links checked, zero wrong. Zero duplicate
  tracker IDs. Zero citations without a backing row. Zero SUSPECT findings dropped between the
  source ledgers and the final. Every severity tag matches its source. All five files are pure
  ASCII, so the no-dash house rule held perfectly.
- **The judgement is mostly right and sometimes wrong.** Of 50 SUSPECT mechanisms, 1 is false and
  should be deleted, 4 are not defects at all, and 11 had their severity changed: 10 down, 1 up.
- **The single P0 the ledger named is not the P0 the code has.** More on this below.
- **About a quarter of the ledger is rediscovery.** 14 of 50 SUSPECT rows and a large share of the
  MISSING-FEATURE rows are already open, by name, in the project's own register.
- **The header oversells the coverage.** "Full relevant-tracker sweep" describes a ranked sample of
  roughly 5 percent of what was listed.
- **The verification found 36 things the sweep did not.** Those are in section 6, and several are
  worth more than the rows they were found beside.

---

## 1. What the ledger claims about itself, measured

Everything here was counted with a shell command, not estimated.

### 1.1 What held, exactly as claimed

| Claim in `SALANKE_FINAL.md` | Measured |
|---|---|
| 904 rows processed, split 93 / 360 / 424 / 27 / 0 | Correct, and each tracker's own split adds up to its own processed count |
| "Each source issue appears exactly once in this section" | 468 citations, 468 distinct IDs, zero duplicates anywhere |
| "no duplicate tracker IDs, no ID-to-link mismatch" | 1,372 link checks across all four files (468 in the final, 904 in the ledgers), zero mismatches |
| "no invalid taxonomy value" | Every verdict is one of the five allowed words |
| "no Unicode em dash or en dash" | Zero non-ASCII bytes in any of the five files |
| SUSPECT preamble: 93 sources, 1 P0 / 49 P1 / 40 P2 / 3 P3 | Exact |
| MISSING-FEATURE preamble: 360 sources, 7 P0 / 114 P1 / 196 P2 / 43 P3 | Exact |
| Every FINAL severity tag matches its source ledger row | 468 of 468 agree |

Nothing in this table needed correcting. Whoever built the distillation pipeline built it carefully.

### 1.2 What did not hold

**A. "Full relevant-tracker sweep" is a bigger word than the evidence carries.**

The coverage section says "Every relevant full-history query and component inventory was paginated
to completion." That is true of the **listing**. It is not true of the **reading**. The mpv ledger's
own header says it plainly: "6,312 unique issues listed, 300 triaged in, 300 processed", and
"Processing follows signal rank, not issue number order." That is 4.8 percent of the listing.

The honest sentence is the one the very next clause already says: the inventory was paginated to
completion, then the highest-ranked 300 per tracker were read. The opener contradicts it.

**B. The "23,139 raw query records" total adds three different things.**

| Cell | What it actually counts |
|---|---|
| mpv `12,935` | raw, with the deduped `6,312` also given |
| VLC `5,423` | raw, and the header says duplicates are **not** removed |
| Media3 `4,781` | raw, and the header says **before** deduplication |
| Total `23,139` | the sum of three unlike quantities |

The number is arithmetically right and semantically meaningless. Printed next to a "Processed 904"
column it invites a coverage ratio that is not one. mpv's own 12,935 collapsing to 6,312 shows the
dedup ratio is roughly 2 to 1, so the real distinct-issue population is unknown.

**C. Two thirds of that total cannot be rebuilt from the documents.**

mpv's 12,935 decomposes exactly: its sixteen listed per-query totals plus four auxiliary ones sum to
12,935 on the nose. VLC's 5,423 is one unbroken lump. Media3's itemized searches sum to 3,282
against a claimed 4,781, leaving 1,499 unexplained. Only one of the three coverage figures is
auditable.

**D. The receipts in `SALANKE_FINAL.md` are names, not paths.**

Of 205 distinct backticked tokens in the "Mechanism and receipt" and "Gap and receipt" lines,
**203 carry no directory at all**. `SyncLaw`. `BufferPolicy`. `flush`. `activeAt`.

The protocol (`SALANKE.md` section 5 rule 3) asks for "the KitePlayer file/function that was
checked". The three source ledgers do this correctly. The distillation dropped the file half. The
final's own audit line "no missing receipt path" is only true if "path" is redefined to mean "name".
A reader who has only `SALANKE_FINAL.md` cannot act on it.

**E. Four receipts point into the wrong repo, and one is ambiguous between two real files.**

`vendor/ffmpeg/` does not exist in KitePlayer. FFmpeg is vendored in **KiteCodec**. KitePlayer's
`vendor/` holds mpv, VLC, QMPlay2 and libplacebo. Rows S24, S25 and M03 cite
`vendor/ffmpeg/libavformat/hls.c` as though it were local. `M06` cites `BuildFFmpegTask`, a KiteCodec
build class. `S19` cites `StreamDecoder.open`, also KiteCodec.

Worse, one row cites bare `hls.c`. That resolves to two unrelated real files: KiteCodec's FFmpeg HLS
demuxer, and `KitePlayer/vendor/vlc/modules/stream_out/hls/hls.c`, which is VLC's HLS **muxer**. The
source ledgers always qualified these. Distillation stripped the qualifier.

**F. The three agents did not apply the taxonomy the same way.**

| Ledger | N/A rows | MISSING-FEATURE share | P3 severities |
|---|---:|---:|---:|
| mpv | 0 of 300 | 63 percent | 47 |
| VLC | 23 of 304 | 25 percent | 6 |
| Media3 | 4 of 300 | 32 percent | 0 |

mpv found nothing at all out of scope in 300 issues and classed almost two thirds as
missing features. Media3 used no P3 at all. These are three different graders, and the FINAL's
cross-tracker counts ("a trap all three met") inherit that skew.

**G. Two small protocol slips, recorded for completeness.**

- `SALANKE.md` prefers closed-and-fixed issues. The corpus is 388 open against 350 closed-fixed.
- mpv's checkpoint line says "processed through issue #14650". 59 of its 300 rows are numbered above
  that, up to `MPV-18411`. The header itself explains why a numeric cursor is meaningless here, but
  the line as written still states something false about its own file.

---

## 2. Severity and ownership, corrected

This is the change that matters most for scheduling.

**The ledger's one P0 is not the code's one P0.**

`S01` (unbounded external subtitle ingestion) was rated P0 as an untrusted-input memory seam. Reading
the code lowers it:

- `SubtitleSource.io` is refused outright (`PlaybackCore.kt:253`), and network subtitle sources are
  parked, so the input is always a local file the user picked themselves.
- A byte cap already exists on the six Kotlin/Native targets:
  `ExternalText.native.kt:39` sets `MAX_EXTERNAL_SUBTITLE_BYTES = 32 * 1024 * 1024` and returns null
  past it.

So `S01` is P1, and the interesting half of it is not the one the row states. See `N01` in section 6.

**Meanwhile `S23` should go up, not down.** On Android, `AudioTrackSink.resetTimestampState()` is
called on open, on device-failure recovery, on stop and on drain, but **not on resume from pause**
(`AudioTrackSink.kt:249-256`). A stale pre-pause timestamp then passes every one of the four
admission checks in `acceptTimestamp` (`:429-438`), because two of them use `<` and therefore accept
an unchanged reading. The deadline that comes out is anchored at a pre-pause instant, and
`AudioPlayback.anchorLocked` writes it into the media clock without a sanity check.

Consequence: **a pause of N seconds can mis-anchor the master clock by up to N seconds**, on every
pause and resume, on the platform with the largest install base. It needs device evidence before it
is called P0 for certain, but it is the most serious row in the file.

### Severity changes, all of them

| Row | Was | Now | Why |
|---|---|---|---|
| S23 | P1 | **P0 pending device proof** | Android resume never resets the timestamp state; every pause can mis-anchor the clock |
| S01 | P0 | P1 | Local-file-only input, and Native already caps at 32 MB |
| S27 | P1 | P2 | Worst case is one frame period lost at a loop point |
| S30 | P1 | P2 | The stub is honest and already registered; only the cross-program position reuse is unowned, and it is unreachable until programs exist |
| S08 | P1 | P2 | Costs nothing on any container this build opens; needs live TS or HLS, which are themselves unbuilt |
| S20 | P1 | P2 | The only thing that can wedge is the uncancellable native call `KC-CANCEL` already owns |
| S28 | P1 | P2 | Not terminal: a dequeue throw does reach the hardware demotion path |
| S24 | P1 | P3 | The claimed missing correction is present in the vendored source |
| S25 | P1 | P3 | The policy is expressible today through `openOptions` |
| S39 | P2 | P3 | Both shipping paths already apply the crop |
| S47 | P2 | P3 | Polish, and bounded because the sinks pause rather than flush |

---

### Which SUSPECT rows the register already owns

Fourteen of the fifty. Read this before scheduling anything from the ledger, or the same work gets
opened twice under two names.

| Register row, and what it says today | SUSPECT rows that are it |
|---|---|
| **KP-NET**, "the network module: unvalidated 206, no resilience, unpublished" | S06, S10, S14, S24, S25 |
| **KC-CANCEL**, "a blocking FFmpeg call cannot be cancelled; no interrupt callback" | S05 (its KiteCodec half), S17, S20 |
| **SOL-A6**, "passthrough, offload, device selection, route recovery absent" | S07, S12 |
| **KP-P1-15**, "viewport subtitles" (named once, no detail anywhere) | S41, S44 |
| **KC-TRACKSEL**, "primary track selection has no policy; Wasm has no disposition" | S11 (its KiteCodec half) |
| **KP-API**, throwing stubs and dead knobs | S30 |

Two adjacent rows are already **closed** and should not be reopened: `SOL-S8` (the subtitle stack
leak, which touches the same three lines as S34 and S50) and `F-SP1` plus `F-CFG2` (the speed refusal
law, which is S33). `SOL-P9` partly owns S20's track-change half and is itself stale; see RC-3.

## 3. Rows to delete or close

### 3.1 Delete: S19 is false

**"Decoder reopen omits explicit B-frame reorder depth"** claims
`avcodec_parameters_to_context` does not carry `AVCodecContext.has_b_frames`.

It does. `KiteCodec/vendor/ffmpeg/libavcodec/codec_par.c:230` reads
`codec->has_b_frames = par->video_delay;` inside that exact function, and both KiteCodec backends
call it. The stated mechanism is contradicted by the source the row names.

Two smaller errors in the same row: it says "both backends" when there are three (`Playback.wasmJs.kt`
has no `StreamDecoder.open` at all), and it cites a KiteCodec symbol without saying so.

What is left after the correction is not a defect: nothing sets `has_b_frames` explicitly, so it is
only as good as what the demuxer parsed into `video_delay`. That is metadata quality. Also, video
never reopens a decoder mid-session in KitePlayer; only audio does, and only since `0.0.19`.

### 3.2 Close as not-a-defect

**S33, speed change performs a full seek.** The mechanism is real and it is deliberate, documented
at the public surface, and already has a closed audit row behind it. `KitePlayer.kt:162-166` says a
live speed change "re-anchors through an internal precise seek at the current position, which sounds
like the small rebuffer it is", and explains why an unseekable source is refused instead of
half-applied. `F-SP1` and `F-CFG2` are the closed rows. This belongs in the parity map, not a defect
register.

**S35, pause fallback discards device-buffer continuity.** The fallback is one line,
`AudioPlayback.kt:384`: `if (!sink.setPaused(true)) sink.stop()`. **No shipped sink ever returns
false while open.** Android, desktop, Apple, web and the silent paced sink all return true; Apple's
only `false` fires when nothing is open. Zero of five. This is a contract escape hatch, not a live
path. Worth a KDoc note saying the branch is currently dead, nothing more.

**S49, EOF handling relies on actor polling.** True as described and no behaviour follows from it.
The polling costs at most one 50 ms pass, and every wait that could actually hang is deadline-bounded
with a typed warning on expiry. Descriptive, not open work.

**S21, Dolby E bursts opened as ordinary PCM.** The code fact is true and trivially so. But Dolby E
in an SMPTE ST 337 burst is a broadcast contribution format that lives in MXF and AES3 plant
workflows. This player's declared scope is consumer local-file and HTTP playback. mpv, VLC and ffplay
all fail the same way without an explicit hint. Rating it P1 is noise. It belongs next to passthrough
in the parity map.

---

## 4. Rows whose wording must change before anyone acts on them

These are true findings pointed at the wrong line. Acting on the row as written would waste a session.

| Row | The row says | The code says |
|---|---|---|
| **S18** | Fix `CoreAudioSink.latencyNanos` | `latencyNanos` has **no engine reader at all**, stated twice in the tree (`spi/AudioSink.kt:74`, `PlayerState.kt:207`). The line that matters is `kiteplayer-rt/native/src/kite_rt_render.c:345`, whose own comment says "No device latency is subtracted anywhere". |
| **S07** | `AudioPlayback.open` counts and warns after starvation | `open` does neither; it warns once about unreliable latency. Counting is at `KotlinAudioRing.kt:353`. And `handlePlaybackRestart`, cited as evidence of missing recovery, **is** the resume half of the recovery `handleBuffering` starts. |
| **S03** | No first-class discontinuity epoch, conflating two things | Correct to suspect itself. `requestedEpoch` is a seek and rebuild generation counter and nothing else. A container discontinuity epoch does not exist. Restate the row so it stops reading as a critique of the seek generation. |
| **S13** | "platform decoder selection" ignores rotation | All seven `PlatformDecoderSelection` files contain **zero** rotation code. The mechanism is `MediaCodecVideoDecoder.applyCodecRotation` (`:76`, `:210-212`), which sets `KEY_ROTATION` and never reads it back. |
| **S38** | Audio anchor publication can spin | True for the **Kotlin** ring only (`KotlinAudioRing.anchor()` is an uncapped `while (true)`). The C ring is already bounded at 64 attempts with a `anchor_giveups` counter. Apple runs the C ring; Android, desktop and web run the Kotlin one. As written it reads as if Apple were affected. |
| **S44** | libass lacks storage geometry | libass is the one thing that is **not** missing geometry: it is handed the whole script and reads `PlayResX` itself. The gap is in the Kotlin ASS cue model, where `CueLayout` carries `authoredHeight` and discards `playResX`. Retitle. |
| **S26** | Inactive lanes can exhaust the byte budget | Correct, and **the `0.0.19` work created this mechanism**. Before the diff `overBudget` counted only the selected queues. See section 5. |
| **S17** | Teardown joins before closing | True, and the sharp form is different: `close()` **is** deadline-bounded through `awaitRelease`. `stop()`, `runOpen` and `handleTrackChanges` call `teardownSession` inline with no deadline at all. Restate as "stop and track-switch teardown are unbounded on a wedged read, unlike close". |
| **S28** | Platform dequeue exception is terminal | Not terminal. The throw becomes `VideoDecoderRuntimeFailure`, which `videoRecoveryFor` accepts and demotes to software. What is missing is a `CodecException.isRecoverable()` retry, and any recovery at all under `Require`, `Prefer`, an unseekable source, or a second failure. Also: the demotion **reopens the media**, it does not replay packets, so the IMMUNE row that says "replays retained packets" describes the decoder-level fallback, not this path. |
| **S24** | Pinned `hls.c` predates a 2026 correction | The vendored n8.0 file already contains a skipped-segment clamp (`hls.c:1597-1602`), a post-reload timeline correction (`:1073-1083`) and wrap detection (`:1611-1612`). The claim cannot be established from the source, and the mechanisms it names are present. What survives is smaller: `hls_read_seek` has no clamp of its own and hard-fails with `EIO` below the sliding window. |
| **S25** | No player-owned reload tolerance policy | `MediaItem.openOptions` **is** an arbitrary FFmpeg option passthrough, and KiteCodec's contract even reports unconsumed keys rather than dropping them. Every HLS knob (`max_reload`, `m3u8_hold_counters`, `seg_max_retry`, `live_start_index`) is settable today. The true claim is "expressible but not first-class, and KitePlayer sets no default". |
| **S11** | The audio picker ignores accessibility traits | True, and the real blocker is one layer down. KiteCodec's `Disposition` has five fields and **collapses `hearingImpaired` and `visualImpaired` into one boolean** at `KiteCodecSource.kt:408`. `AV_DISPOSITION_DESCRIPTIONS` and `AV_DISPOSITION_COMMENT` are read nowhere in either repo. Descriptive audio and director's commentary are indistinguishable from ordinary audio at every layer. Split into a KiteCodec API row and a KitePlayer picker row. |
| **S36** | Tone map hard-codes a 1000-nit peak | True, and **the fix is cross-repo**. `ColorSpaceInfo` has no field for it, and KiteCodec has no `AV_FRAME_DATA_MASTERING_DISPLAY_METADATA` or `AV_FRAME_DATA_CONTENT_LIGHT_LEVEL` accessor anywhere, so the field could not be filled if it existed. The row also misses that Metal hard-codes the same `1000f` independently at `MetalVideoSupport.kt:543`, so a fix touching only `HdrToneMap` leaves the GPU wrong. |

---

## 5. The one row the `0.0.19` work created

`S26` deserves its own section because it is the only SUSPECT row whose mechanism did not exist when
the sweep started. It was written against the new code and it is right, and reading the code makes it
worse rather than better.

**What changed.** Before the uncommitted work, the player selected one video, one audio and one
subtitle stream. Now `buildSession` selects the chosen video stream **plus every container audio and
subtitle stream**, and routes compressed packets into a queue per track. That is what makes instant
track switching possible. It also changes what the read-ahead budget is holding.

**The budget, measured.**

- One session-global pair: `totalBytes = 32 MiB`, `totalDuration = 30 s` (`PlayerConfig.kt:154-155`).
- `overBudget` sums **every** lane's bytes (`allPacketQueues`) but takes duration from **selected**
  lanes only. The comment says why, and the reasoning is sound.
- Behind the playhead, retention is bounded: inactive audio to `min(totalDuration/2, max(readyDuration, 1 s))`,
  which is **one second at defaults** with a 15 second ceiling, and inactive subtitles to thirty
  seconds. Both pruned at a 250 ms cadence.

**Three holes, in order of severity.**

1. **Nothing bounds a lane's forward extent.** `dropBefore` trims from the oldest end only. An
   inactive lane holds everything from its cutoff to the shared demux frontier. With six lossless
   audio tracks the 32 MiB splits roughly by bitrate, and the selected video gets a small fraction of
   the read-ahead it was designed for.
2. **A packet with no duration disables pruning for its whole lane, permanently.**
   `PacketQueue.dropBefore` reads `val endUs = entry.endUs ?: break`, and `endUs` needs both a
   timestamp and a duration. `Packet.durationMicros` is null whenever FFmpeg leaves
   `AVPacket.duration` at zero, which KiteCodec's own KDoc calls "common and not an error". Such a
   lane grows until it owns the cap.
3. **The relief valve points the wrong way.** `relieveInterleaving` inspects `selectedQueues()` and
   drops from the selected hoarder. When the cap is held by inactive lanes, the only lane it can cut
   is the one carrying playback. If both selected lanes are empty it returns false and the demuxer
   waits on a drain that cannot fire.

**And the cadence closes the trap.** `pruneInactiveSwitchCaches` gates on
`publishedPositionMicros` advancing by 250 ms. Once playback stalls, published position stops, so no
prune can ever run again. A budget mechanism that only runs while playback is healthy cannot be the
answer to a budget exhausted by a stall.

This is a reachable, permanent wedge, and no register row owns any part of it. It is the single most
important thing in this document that was not already known.

**One more consequence nobody wrote down.** On an HTTP source, the demuxer must now pull every byte
of every audio track through the byte cache and the socket, whether or not the user will ever hear
them. On a well-interleaved six-track remux that is several times the bandwidth `0.0.18` used.

---

## 6. What the sweep missed

Thirty-six findings the tracker sweep could not have produced, because they come from reading the
code rather than from matching it against someone else's bug. Grouped by kind. Severity uses the same
scale as the ledger.

### 6.1 Correctness, and one likely-broken public feature

| # | Finding | Receipt | Sev |
|---|---|---|---|
| **N01** | `captureFrame()` looks broken on all real media. **No production frame type implements `SoftwareReadableFrame`.** The only implementers are `CapturedFrame` itself (the output) and a commonTest fake, so `CapturedFrame.of` takes its throw branch for every real decoded frame on every platform. | `CapturedFrame.kt:37,59`; `KiteCodecSource.kt:847`; `TestSupport.kt:94` | P1, confirm on real media first |
| **N02** | The packet-cache wedge. See section 5, items 2 and 3. | `PacketQueue.kt:245`; `PlaybackCore.kt:5311,5363` | P1 |
| **N03** | `BlockingMediaIo` is uncancellable **by construction**, not merely uninterruptible: `runBlocking` opens a fresh root scope, not a child job, around a `while (r == 0) { delay(1) }` spin with no cap. Cancelling the demux coroutine cannot reach it. This is on every `MediaItem.io` open, https included. | `BlockingMediaIo.blocking.kt:21-28` | P1 |
| **N04** | On the web, **every stream's disposition is empty**. `MediaSource.wasmJs.kt` builds `StreamInfo` with no disposition argument, so it defaults to `Disposition.None`. `isDefault`, `isForced`, `isAccessibility` and `isCoverArt` are false for every stream, which kills the default-flag tier of `pickAudio`, all of `pickSubtitle`'s forced and accessibility logic, and the cover-art skip. | KiteCodec `MediaSource.wasmJs.kt:518-548` | P1 on web |
| **N05** | The subtitle lane is excluded from **every** end-of-stream condition. `selectedQueues()` is video plus the active audio lane; `decodersDrained()` covers video and audio only. `handleEof` declares Ended while subtitle packets are still queued, and `handleLoop` runs in the same pass and flushes them, so a looping file drops its last cue. Pre-existing, not new. | `PlaybackCore.kt:5954-5957`, `:632-633` | P2 |
| **N06** | The demux worker now reads `session.subtitleQueue`, a plain non-atomic `var` documented three lines above its declaration as "**Demux never reads it**". The audio side went through an atomic snapshot precisely to avoid this; the subtitle side did not. A torn read lets demux prune 30 seconds off the lane the actor just selected. | `PlaybackCore.kt:5320` against `:5804-5806` | P2 |
| **N07** | The external subtitle byte cap is **per platform, and the product platforms are the unprotected ones**. Native caps at 32 MB and aborts past it; JVM and Android are a bare `file.readText()`. Same expect/actual, opposite safety. | `ExternalText.native.kt:25,39` vs `ExternalText.jvm.kt:6`, `ExternalText.android.kt:6` | P2 |
| **N08** | The subtitle raster job has **no failure route**. It is launched on `scope`, which has no exception handler, and unlike every worker it does not pass through `launchWorker`'s catch into the outcomes channel. A rasterizer that throws produces no typed warning, no event and no overlay. | `PlaybackCore.kt:3611` against `:5237-5255` | P2 |
| **N09** | Double rotation is structurally possible and nothing asserts against it. `KiteCodecSource.kt:573` stamps `stream.rotationDegrees` onto every FFmpeg-backed frame, VideoToolbox hardware frames included, and the Metal and UIKit renderers then turn by that value. Any hwaccel that ever returns a pre-rotated surface turns the picture twice. Same family as `S13`, opposite failure. | `KiteCodecSource.kt:573`; `MetalVideoSupport.kt:811` | P2 |
| **N10** | `SyncMode.ExternalMaster` is **silently ignored rather than refused**, which breaks this codebase's own "refused rather than ignored" convention. Stats then report `syncMode = ExternalMaster` beside `masterClock = Audio` with no warning. | `PlaybackCore.kt:4957-4962` | P2 |

### 6.2 Audio, beyond what the ledger looked at

| # | Finding | Receipt | Sev |
|---|---|---|---|
| **N11** | The sinks' own underrun signal is **emitted and thrown away**. Desktop and web emit `AudioSinkEvent.Underrun`; the collector handles `DeviceLost` and `DeviceChanged` and drops everything else with `else -> Unit`. This is a separate channel from the ring counter. | `DesktopAudioSink.kt:399`; `WebAudioSink.kt:183`; `PlaybackCore.kt:5217-5225` | P2 |
| **N12** | `CoreAudioSink` declares an event flow it **never emits into**, so no production sink ever sends `DeviceChanged`. The warning path for a route change is dead code on the platform where routes change most. | `CoreAudioSink.kt:216` | P2 |
| **N13** | The three sinks disagree about the same file. A 7.1 source plays 8 channels on Android, stereo on Apple, stereo on desktop. `SOL-P8` records the mixer and Apple halves only. | `AudioTrackSink.kt:118`; `kite_rt_coreaudio.c:371`; `DesktopAudioSink.kt:134` | P2 |
| **N14** | Two of the tempo stage's five branches splice with **no crossfade at all**: `emitRepeat` re-appends the head period raw, and the drop branch calls a bare `consume(period)` whose own comment admits "the next overlap-add heals the splice", which is not guaranteed to be next. | `TempoStage.kt:273`, `:151` | P2 |
| **N15** | Pipeline order makes the tempo stage worse on Android. The mixer runs first, so the tempo stage sees the **device** channel count, which on Android is 6 or 8 unmixed. The mono average then folds the LFE channel into the pitch signal, and LFE is low-passed and high-energy. | `AudioPipeline.kt:13,58,79`; `TempoStage.kt:308` | P2 |
| **N16** | `DesktopAudioSink` also builds its accepted format with no `channelLayoutMask`, the twin of the known Android finding. Harmless today because desktop caps at 2 channels, and live the moment multichannel desktop output lands. | `DesktopAudioSink.kt:130-138` | P3 |
| **N17** | The three `17.19` audio findings from 2026-08-19 are **all still true**, and one has a second-order fault nobody wrote down: when `CoreAudioSink.close()` nulls its fields before `destroy()`, and `destroy()` throws on an unproven teardown, the throw also skips `owned.lease?.close()`, so the audio session lease leaks alongside the sink and a retry returns early because the fields are already null. | `CoreAudioSink.kt:431-438` | P2 |

### 6.3 Video and colour

| # | Finding | Receipt | Sev |
|---|---|---|---|
| **N18** | **The CPU tone map runs on 8-bit RGBA.** The converter allocates a `ByteArray(w*h*4)` and `TenHighAligned` throws away the low 8 bits of every 10-bit sample, and only then is `mapInPlace` called, whose PQ EOTF is a 256-entry table indexed by a byte. PQ across 256 codes puts several stops in the first few steps, so shadows band hard. Metal does not do this: it uses 16-bit plane textures. This is the real CPU-versus-GPU divergence, and `S42` looked at the wrong stage to find it. | `Conversions.kt:173,211,218`; `HdrToneMap.kt:26-34` | P2 |
| **N19** | No Android renderer answers `outputSize`, which is the input the whole "rasterise at 1:1" design depends on, and the SPI KDoc motivates that field with the Android phone case by name. Only Metal and the Compose renderer override it. | `PlaybackCore.kt:3587-3594`; `VideoRenderer.kt:103-107` | P2 |
| **N20** | An authored `\pos` anchors differently on Apple than on the other two. Android and desktop orient the anchor by the cue's alignment, which is what audit row `F-POS1` fixed. Apple treats the point as the bitmap's top-left. `F-POS1` is recorded closed; it closed on two rasterizers of three. | `AppleSubtitleRasterizer.kt:221,227` vs `AndroidSubtitleRasterizer.kt:173-193` | P2 |

### 6.4 Truth rows: the code and the words disagree

Cheap, and each one is a future reader misled.

| # | Finding | Receipt |
|---|---|---|
| **N21** | `SyncLaw.NO_SYNC_THRESHOLD_US` is **dead**. Two hits in the whole repo: its own declaration and its own KDoc. Meanwhile the design section states the rule as if built: "a jump of 5 s or more in a non-discontinuous stream is a stream reset at the new position". Either build the rule or delete the constant and correct the doc. | `SyncLaw.kt:32,58`; `KPKMP-FUTURE.md:507-509` |
| **N22** | `reselectStreams` is a **new public ABI member with no caller in the engine**. Declared on the SPI, implemented on `KiteCodecSource`, tested, and never called from `PlaybackCore`, because the all-lanes cache landed and made it unnecessary. Both `.api` dumps changed for it. Either wire it or record why it exists. | `spi/MediaSource.kt:57`; no call site in `PlaybackCore.kt` |
| **N23** | `SubtitleSource.io` is a **public field guaranteed to fail on every call**: declared on `MediaItem`, refused unconditionally at parse time with "custom subtitle IO is not wired". | `MediaItem.kt:159`; `PlaybackCore.kt:253-257` |
| **N24** | `PlayerMediaSource.metadata` has **zero consumers**. Container tags are read into a map and dropped. A music file's title and artist are already in memory and no application can reach them. | `spi/MediaSource.kt:29` |
| **N25** | `LoopMode.All`'s KDoc says it is "Refused ... there is no queue and no playlist ... Not implemented yet". Two call sites implement it. | `PlayerState.kt:254-259` vs `KitePlayer.kt:222`, `PlaybackCore.kt:3818` |
| **N26** | The `SubtitleRasterizer` KDoc promises bitmap cues are "scaled to the cue's declared canvas". All three implementations scale the position and hand the bitmap over untouched, and `OverlayImage` has no target rect to express a scale with. | `SubtitleRasterizer.kt:18-19`; `VideoRenderer.kt:170` |
| **N27** | The same KDoc says the viewport is "the video's own display size", while `publishOverlay` has passed the renderer's surface size since 2026-08-23. | `SubtitleRasterizer.kt:14-16` vs `PlaybackCore.kt:3587` |
| **N28** | A stale comment says a refused in-place track change "falls through to the rebuild". It does not: `discardSelection` removes the pending entry, so `handleTrackChanges` returns at the empty check. | `PlaybackCore.kt:2779-2783` |
| **N29** | `KiteCodecSource.kt:132` still says chapters are "Always empty", three lines above the ingest that fills them. | `KiteCodecSource.kt:132,138` |
| **N30** | The srcPeak rationale KDoc is attached to the wrong function: the block explaining the 1000-nit choice sits above `packQualityUniforms`, and `packToneUniforms`, the function that actually uses it, has no doc. Anyone grepping finds it filed under quality. | `MetalVideoSupport.kt:476-490,534` |
| **N31** | ASS `Collisions:` is never read; only `playresx` and `playresy` are taken from the script-info section. A script asking for reverse stacking gets the one hard-coded order. | `AssParser.kt:105` |
| **N32** | **`KP-SEEKPRE` and `KP-PLAYACK` exist only in code comments and `HANDOFF.md`.** Neither appears in `KPKMP-FUTURE.md` or `KPKMP-PAST.md`. That is the RULE ONE failure the project already warns about, repeating inside the current uncommitted surge. | `PlaybackCore.kt:1036,3249,4166`; zero hits in either register |

### 6.5 Test debt with teeth

| # | Finding | Receipt | Sev |
|---|---|---|---|
| **N33** | **A fault injector nobody uses.** `ScriptedBackend` declares `sinkOpenFails` and wires it to make the scripted device refuse to open. No test in either repo sets it. So deleting the entire reverse-order rollback ledger from `buildSession`, which is the guard the honor roll calls load-bearing, would not turn a single test red. | `ScriptedBackend.kt:307,1110`; zero setters | P1 |
| **N34** | **The new seek test cannot fail on the real defect.** `MissionBSubtitleRegressionTest` passes because `ScriptedBackend` rewinds its subtitle cursor to the first packet spanning the landing, which is exactly the bounded replay contract `KiteCodecSource.seekToKeyframe` does **not** implement. The simulation is more generous than the backend it stands in for. | `MissionBSubtitleRegressionTest.kt:56`; `ScriptedBackend.kt:787` vs `KiteCodecSource.kt:190-198` | P1 |

### 6.6 One free win, and one trap

| # | Finding | Receipt |
|---|---|---|
| **N35** | **A bounded backward seek search is available and never used.** `PacketReader.seek` takes `notEarlierThan` explicitly for indexless containers. `KiteCodecSource.seekToKeyframe` never passes it, so `min_ts` is `Long.MIN_VALUE` on every seek. This is the cheapest available partial for the unbounded-seek row, and it needs no C ABI change. | KiteCodec `Playback.kt:99-111`; `KiteCodecSource.kt:194` |
| **N36** | **A trap for whoever closes `KC-CANCEL`.** FFmpeg's HLS reload sleep already honours `ff_check_interrupt`, so installing a top-level interrupt callback makes most HLS blocking interruptible for free. But `hls_read_header` sets `interrupt_callback` on only one branch: the SAMPLE-AES path and `init_subtitle_context` both leave it zeroed before their own `avformat_open_input`. | KiteCodec `vendor/ffmpeg/libavformat/hls.c:1618,2302-2313,1782-1815` |

---

## 7. Three corrections to the project's own register

These are not SALANKE findings. They came out of checking SALANKE, and they matter more than several
SALANKE rows because the register is what people schedule from.

### RC-1. The 5.1 claim is false, and it has been repeated three times

`17.19` says: "`kite_rt_coreaudio.c:347` CLAMPS CoreAudio to 2 channels rather than 6", and concludes
in bold: "**On a real 5.1 setup this player outputs stereo.**" That conclusion is repeated in
`SOL-P8` and again in `KP-PROD` phase 3, where it is called the most user-visible row in the
register.

It is not true. The line reads:

```c
#define KPRT_MAX_CHANNELS 6
...
if (accepted_channels > KPRT_MAX_CHANNELS)
    accepted_channels = 2; /* not 6: see the KPRT_MAX_CHANNELS note (SOL-A6, SOL-P8) */
```

Only counts **above six** fall to 2. Counts 1 through 6 pass through and get a real layout tag.
Multichannel landed on 2026-08-17, two days before `17.19` was written, and the line already read
this way at the exact tree `17.19` names as its verification anchor.

**What is actually still true**, and should replace the claim:

- `ChannelMixer` builds a matrix only when the target is stereo, so 8 into 6 gets a truncating
  passthrough.
- JVM desktop is stereo only.
- Android accepts 8 channels, Apple falls to 2 above six, desktop caps at 2. Three answers to one
  file.

### RC-2. `17.18` claims a shuffle that does not exist

The parity map's Queue row says the pair has "navigation, repeat and shuffle shaped controls".
A repo-wide grep for `shuffle` over every Kotlin file returns nothing. The only mode enum is
`LoopMode` Off, One, All. That cell is wrong, and `17.18` is the map the project uses to answer
"which of these does this phase buy".

### RC-3. `SOL-P9` is stale

`SOL-P9` reads "a track change reopens the whole session, so live media cannot". The working tree
routes subtitle and audio changes through `inPlaceContainerSubtitleChange` and `inPlaceAudioChange`
before any rebuild. Only **video** still rebuilds, and that is deliberate. The row needs reducing to
its video half when the `0.0.19` work commits.

One smaller note: `KP-P1-15` is named once, as the two words "viewport subtitles", and carries no
detail anywhere in the file. Three SUSPECT rows (`S41`, `S44`, and the geometry half of `S50`) land
on it, so it is doing more work than its one line suggests.

---

## 8. The IMMUNE honor roll, re-graded

Fifteen rows claiming the architecture prevents an upstream bug. This is the most dangerous kind of
claim to get wrong, because it tells a future maintainer a design is load-bearing and must survive.

**Result: 9 SOLID, 6 PARTIAL, 0 wrong.** No row is false. Every PARTIAL is a case where the guard
exists and works, but the sentence promises more than the code delivers.

### The six that need rewording

| Row | The sentence says | The code delivers |
|---|---|---|
| **12** (MPV-12369) | The audio switch preserves source and network progress | It preserves progress by **refusing the switch** when the cache does not cover the position. `audioCacheRefusal` leads to `TrackChange.Discarded` and the old track keeps playing. That is a different promise to a caller. |
| **5, 13** (MEDIA3-2309, MPV-7780) | "Every subtitle lane is cached" | The cache is a bounded 30 second window. The reason a long-running cue survives a switch is one line: `PacketQueue.dropBefore` trims by a packet's **end**, not its start, and stops entirely at a packet whose duration is unknown. That single line is the whole immunity and neither row names it. Also, the switch is not instant: the retained backlog re-decodes at 32 packets per actor pass. |
| **6** (VLC-28541) | "Typed open failure" | Typed as `PlaybackError.Internal`, **not** the `PlaybackError.AudioDeviceUnavailable` that exists and is never produced here. The code says that is deliberate: `AudioDeviceUnavailable` carries no cause, and a support bundle needs the original throwable (`PlaybackCore.kt:4746-4748`). So the fix is to give the typed error a cause, not to retype the classification. And the guard covers **open only**: a device lost mid-playback is a warning, which the SPI says out loud. |
| **3** (EXOPLAYER-6155) | Pinned `hls_read_seek` discards through the exact target | It does, with two escapes visible in the same function: the discard is abandoned on the first packet with no DTS, and the target is rewritten to the segment start when the playlist match resolves that way, which is the segment-boundary landing the upstream issue is about. Also this is upstream FFmpeg behaviour, not a KitePlayer guard. |
| **11** (MPV-2654) | The DSD crash path is absent | True of the sink, which negotiates one fixed format and never converts. What was not established is the front end: whether DSD input becomes a typed error or is decoded to PCM before it reaches the pipeline. "The crash path is absent" is supported; "KitePlayer handles DSD" is not. |

### The nine that hold, and what must not be removed

Numbers are the honor roll's own row numbers, so they are not consecutive.

- **Row 1, frame-owned format metadata.** Do not replace per-frame `size`, `colorSpace` and
  `rotationDegrees` with a decoder-side lookup keyed by presentation time.
- **Row 2, one AudioTrack driver per session.** Do not delete the `if (session.audio == null)`
  condition in `inPlaceAudioChange`, and keep sink construction under `AudioPlayback`'s ownership.
- **Row 4, MP3 seeking.** Do not add `AVFMT_FLAG_FAST_SEEK`, and do not pass demuxer options from a
  config map without an allowlist. The real guard is `usetoc=0` **and** fast-seek unset, not
  `usetoc=0` alone.
- **Row 7, epoch rejection.** Do not let `runSeek` continue on best effort when `quiesceWorkers`
  returns false, and keep the generation comparison in both `PacketQueue.offer` and `poll`.
- **Row 8, actor-owned flush.** Do not move `AudioPlayback.flush` off the actor or ahead of
  `quiesceWorkers`, and do not let `Play` bypass the command channel.
- **Row 9, single-writer clocks.** Do not add a public clock-listener or clock-source registration
  API. This is immunity by absence of a feature, so the row stops meaning anything the day such an
  API lands.
- **Row 10, staged EOF.** Do not collapse the gates in `handleEof` into one "decoders drained"
  check, and keep `AudioPipeline.finish`'s resampler-drain half.
- **Row 14, tempo latency.** Do not delete `TempoStage.countBypassed`, and re-anchor the media clock
  only from the render callback's audible deadline.
- **Row 15, hardware demotion.** `usingHardware` must stay write-once-to-false, and
  `forceBackendSoftwareForMedia` must be cleared only by a new open. Note the honor roll's wording
  "replays retained packets" describes the decoder-level fallback; the session-level recovery
  **reopens the media** instead, and says so in its own comment.

### Load-bearing guards with no test

This is the finding that matters. See `N33` and `N34` in section 6.5. Row 6's rollback ledger has a
fault injector that no test uses, so deleting the whole ledger would keep the suite green. Row 1's
mid-stream format-change half is untested. Rows 3, 4 and 11 have no coverage at all, and rows 3 and 4
arguably belong in KiteCodec's fixtures rather than here.

Well covered, for contrast: rows 2, 5, 7, 8, 10, 12, 13 and 15 all have named tests that would fail
if the guard were removed, and row 15 has the deepest coverage in the repository at 30 tests.

---

## 9. MISSING-FEATURE, mapped

The 40 capability areas, with what already exists, who already owns it, and what the row got wrong.
`Own` names the register row that already covers it, or `-` for genuinely unowned.

| Row | Verdict | Own | The correction that matters |
|---|---|---|---|
| M01 bitmap subtitles | Accurate | - | **Nobody has to write a subtitle engine.** `ff_pgssub_decoder`, `ff_dvbsub_decoder`, `ff_dvdsub_decoder` and `ff_xsub_decoder` are already in KiteCodec's shipped archives. The work is a decode-subtitle bridge plus a routing branch, not four engines. |
| M02 broadcast captions | Accurate | - | CEA-608 and 708 ride the **video** stream's frame side data, so this needs a per-frame surface in KiteCodec, not another branch in the subtitle factory. ARIB and Teletext additionally need libraries the build deliberately does not carry. |
| M03 HLS live and LL-HLS | Partly exists | KP-NET | Two hard limits the row misses: `ff_https_protocol` is **absent** (no TLS backend, by decision) and `ff_crypto_protocol` is **absent**, so an AES-128 playlist cannot open its segments. Also cites KiteCodec's path as if local. |
| M04 adaptive variants | Accurate, partly out of scope | KP-NET | One representation is a **declared scope boundary**, not a defect. SABR is YouTube-specific and inflates the row. |
| M05 dynamic DASH | Partly exists | KP-NET | The **parser already reads** multiple periods, adaptation sets, representations, SegmentTemplate, SegmentTimeline and a BaseURL chain. Only the resolver refuses, in two `require` lines. Multi-period joining is therefore M, not XL. |
| M06 RTSP, UDP, multicast | Out of scope | - | The file the row cites is the file that disproves it: protocols were removed on purpose as an attack-surface decision, and configure then drops the rtsp and sdp demuxers. Wrong repo, too. |
| M07 source transports | Partly exists | KP-NET | Welds two unrelated things. SRT and WSS are out of scope **and already reachable** through the public `MediaItem.io` hook. The connection-control half (pause, resume, idle close, follow a growing source) is in scope, absent and much cheaper. Split it. |
| M08 reconnect and recovery | Partly exists | KP-NET | "No failure classification" is **wrong**: `classify` exists and types a failure by the open stage it reached. And the retry transaction this needs already exists in the hardware-decoder recovery path and can be copied. |
| M09 disk cache and offline | Accurate | KP-NET | "Merged parallel acquisition" is the same segment scheduler M04 asks for, double-counted. |
| M10 cache and live latency | Partly exists | KP-NET | "No backward-read optimization" is **wrong**, and it is the one thing this class definitely does: a seek landing inside the window costs no upstream traffic, and the back window is protected from eviction. |
| M11 gapless and prefetch | Accurate | 17.18 only | Two live sessions at once breaks the single-session invariant the whole actor is built on. |
| M12 queue editing | Accurate | 17.18 only | **Shuffle does not exist anywhere in the tree**, which corrects `17.18` itself. See RC-2. Also found the `LoopMode.All` doc lie (`N25`). |
| M13 seek indexing | Partly exists | - | Understates what already sits above `seekToKeyframe`: an overshoot retry ladder and a two-phase `KeyframeThenRefine` mode. MP3 index policy is a demuxer option a caller can already pass. Growing-duration refresh alone is S. |
| M14 chapters and editions | Partly exists | KP-API | "One flat list" undersells it. The end is honoured and gaps resolve to no chapter, which is exactly the "end-aware chapters" `17.18` lists as a mature-class requirement the pair already meets. |
| M15 text formats and encoding | **Accurate, and the best row in this section** | - | Every external subtitle is read as UTF-8 on every target, with only a BOM strip. A Windows-1256, Shift-JIS or Big5 SRT renders as replacement characters with no warning. SAMI, JACOsub, MicroDVD, MPL2, RealText, PJS, VPlayer and STL decoders are **all compiled** and unreachable. TTML is absent entirely. Charset appears nowhere in the register **or** in `17.18`. |
| M16 subtitle styling | Partly exists | SOL-S7, L | **libass exists and is not wired.** A real module on seven target families, device-proved, with zero call sites outside its own tests. The reason a user never sees it is not that it is unbuilt; it is that `PlaybackCore` never calls it. |
| M17 subtitle discovery | Accurate | - | Half the same-audio-language machinery already exists, pointed the other way: the forced branch reads the audio language. What is missing is suppression when the languages match. |
| M18 concurrent lanes | Accurate | - | Sharpest half is `N23`: `SubtitleSource.io` is a public field that fails on every call. Wiring it alone is S. |
| M19 subtitle placement | Accurate | - | A horizontal offset is one more parameter on three implementations. S. |
| M20 dynamic track inventory | Partly exists, **stale** | SOL-P9, KC-TRACKSEL | In-place audio and subtitle switching landed in the working tree; only video rebuilds. Required-audio fallback also already exists. What remains real: PMT-added tracks, persisted selections, richer predicates, stable cross-open identity. |
| M21 external audio lanes | Accurate | - | The "external-audio seek continuity" clause is a problem this work would **create**, not one that exists: subtitles avoid it only because they are fully in memory. |
| M22 audio device routing | Partly exists | SOL-A6 | Write-failure recovery **does** exist on JVM and Android; CoreAudio has none. And the `DeviceChanged` warning path is dead code because no production sink emits the event (`N12`). |
| M23 codecs and passthrough | Partly exists | SOL-A6, SOL-P8 | The exotic-format half is accurate and complete. The 5.1 premise is stale: see RC-1. Native profiles compile the whole read side, so ape, truehd, dts and dsd decoders ship; the wasm allowlist carries none. |
| M24 audio processing | Partly exists | 17.18 | "No seamless rate transaction" is **wrong**: the transaction exists, is tested, and is simply not gapless, which the code says out loud. "Transport ramps" is half wrong too: a 5 ms de-click ramp ships. |
| M25 HDR metadata and DV | Accurate | KP-RQ RQ-6 (output half) | The 1000-nit assumption is the single fact holding the whole tone map together. Nothing owns mastering metadata or Dolby Vision. |
| M26 display colour management | Accurate | - | One correction: Android is not colour-unaware; it hands the system a tagged `ColorSpace`. Apple draws into an explicitly unmanaged space. |
| M27 tone mapping and gain maps | Partly exists | KP-TONEMAP-WARN, D-7 | The BT.2408 clause is **wrong**, and the shader says so in the file: 203 nits **is** BT.2408 reference white. The real gap under this row is M25's metadata wearing a different hat. |
| M28 deep colour and depth | Partly exists | KP-RQ | The dither clause **contradicts the tree and the register**: rung 1 is closed on both renderers, 8x8 ordered Bayer, and the code is there to read. Error-diffusion is a deliberate refusal, not a hole. |
| M29 frame geometry and fields | Accurate | - | The interlace flag dies one layer lower, in KiteCodec's own stream info, so this is a two-repo change. |
| M30 crop, rotation, reflection | **Out of scope by decision** | 8.4, B-horizon | Deferred to Horizon B by the design section and stated in public KDoc: "Mirrored and arbitrarily skewed display matrices are not modelled". Reporting a documented deferral as a missing feature is noise. |
| M31 360 and stereoscopic | Accurate | - | Zero vocabulary at any level, not even a declared enum, which is unusual here: most unbuilt things at least have honest placeholders. The register never says 360, projection or stereoscopic. |
| M32 refresh matching | **Accurate and understated** | - | `vsyncIntervalNanos` is declared and **overridden by all seven shipping renderers to return literal null**, two of them documenting that as deliberate because vsync already lands them on the display's refresh. Nothing calls it, and its `VsyncChanged` event is explicitly discarded. A fully declared, fully overridden SPI member with no consumer. |
| M33 hardware decode routes | Partly exists | PAR-2, PAR-WIN-HW, PAR-6 | About two thirds is rediscovery. Genuinely new: Vulkan Video, CUDA and D3D12VA as candidate routes, fixed-function VPP, and **selective copyback**, since the download is currently unconditional rather than chosen. |
| M34 shader extensibility | Partly exists | KP-RQ, D-7 | Understates what ships: the tier also carries dither, deband, chroma siting and an authored BT.2390 tone map. The extensibility half was **rejected by decision**, not overlooked. |
| M35 output backends | Accurate | PAR-5 | One correction that matters: Linux and Windows **desktop do get a picture**, via the JVM target and the Compose renderer. What is missing is a native GPU renderer, which is narrower than the row implies and is what PAR-5 already says, with a standing recommendation to close it as a decision. |
| M36 capture and screenshots | **Wrong on framing** | KP-TONEMAP-WARN | Both headline complaints are already answered: the tone-map caveat is documented KDoc, and hardware-opaque is a typed documented refusal. Meanwhile it walked past `N01`, which is that capture appears broken on all real media. |
| M37 timed metadata | Accurate, and undersells | KP-NET (DASH half) | Container tags are not merely static, they are read and dropped (`N24`). And `PlayerPacket` has **no side-data channel at all**, which is the one structural blocker under every timed form the row names. That makes it one change rather than six. |
| M38 diagnostics | Partly exists | SOL-API4 | Two clauses are factually wrong: aggregate queue duration **exists** (two per-lane depths plus a cross-lane minimum feeding progress), and `submitDecoded` is **public**. A third of the row is SOL-API4, which is explicitly reclassified as roadmap, not defect. |
| M39 bounded playback and loops | Partly exists | - | "Reset-on-open" is a documented decision stated twice in KDoc. One-sided loops **do** work: A alone wraps at end of media, overriding `LoopMode`. "Speed-aware seek distance" is moot: there is no public relative seek to scale. |
| M40 external clock | Accurate | SOL-API4 | Confirmed exactly. Plus `N10`: selecting it is silently ignored rather than refused, and both KDocs point readers at a section that names none of these fields. |

**Merge these before scheduling any of them:**

- M03, M04, M05 and M10's live half are **one subject**, the adaptive and live layer, and none can be
  scheduled before the one below it. All four are inside KP-NET.
- M07, M08 and M10's cache half are **one subject**, HTTP resilience, all in the same worker's
  failure and idle path.
- M01, M02 and M15 collapse to **one missing piece**: KitePlayer has no bridge to FFmpeg's own
  subtitle decoders, and all three families are already compiled and waiting for one.
- M25, M27 and M28 are the fixed 1000-nit peak and the 8-bit target, seen three times.
- M33 and M35 are the same absence from two sides: no Linux or Windows GPU context means no hardware
  route and no native renderer, and neither lands without the other.
- M29, M30 and M31 all want richer geometry on `VideoFrame`, and M31 subsumes the others' rendering.

---

## 10. The corrected work order

`SALANKE_FINAL.md` ends with a ten-item work order. It is a reasonable order for a document that did
not know about the register or the uncommitted tree. This replaces it. Tier 0 is new and it is
urgent for one reason: the `0.0.19` work is **uncommitted**, and this is the cheapest moment it will
ever be to fix what it introduced.

### Tier 0. Before the `0.0.19` work commits

- **1. Close the packet-cache wedge.** Section 5. Three local fixes: bound each inactive lane's forward
  extent, fall back to a byte cap when a lane's timestamps are unusable so an unknown duration
  cannot disable pruning forever, and let `relieveInterleaving` see inactive lanes instead of only
  cutting the lane carrying playback. Also move the prune cadence off published media position, or
  a stall can never recover.
- **2. Set `sinkOpenFails` in one test** (`N33`). The injector is already written and wired. Until
  something uses it, the rollback ledger the honor roll calls load-bearing is unprotected.
- **3. Make the scripted subtitle source no more generous than the real one** (`N34`), or the seek
  reconstruction row can never be proved or disproved.
- **4. Open `KP-SEEKPRE` and `KP-PLAYACK` in the register** (`N32`). Two codes live in code comments
  and in no register. That is RULE ONE, happening inside the surge about to commit.
- **5. Decide `reselectStreams`** (`N22`). It is shipped public ABI with no engine caller. Wire it or
  write down why it exists.

### Tier 1. Correctness, ranked by what a user actually hits

- **6. `S23`, the Android resume anchor.** Every pause and resume can mis-anchor the master clock by
  the length of the pause. Needs device evidence before it is called P0 for certain, and it is the
  most serious row in the file either way.
- **7. `N01`, confirm `captureFrame()` on real media**, then implement `SoftwareReadableFrame` on the
  decoded frame types. If the type graph is telling the truth, a public feature is broken
  everywhere and only a test fake has ever exercised it.
- **8. `N04`, populate disposition on wasmJs.** One constructor argument restores four selection
  policies on the web.
- **9. `S02` plus `S11`'s player half.** The subtitle picker already ranks accessibility twenty lines
  above the audio picker that does not, so the player-side change is small. Split `S11`'s KiteCodec
  half out: `Disposition` collapses hearing-impaired and visual-impaired into one boolean and reads
  neither `DESCRIPTIONS` nor `COMMENT`, so descriptive audio and director's commentary are
  indistinguishable at every layer.
- **10. `S05`'s player half plus `N35`.** Time-box the seek so the actor cannot block unbounded, and
  pass `notEarlierThan` today as the cheap partial that needs no C ABI change. The KiteCodec half
  is `KC-CANCEL` and should not be reopened separately.

### Tier 2. The register's own second tier, corrected

- **11. `KC-CANCEL`**, with `N36` attached: installing the interrupt callback makes most HLS blocking
  interruptible for free, but two branches of `hls_read_header` leave it zeroed.
- **12. `SOL-P8`'s real remainder, restated per RC-1**: 8 into 6 is unmapped and JVM desktop is stereo
  only. Delete the "5.1 outputs stereo" claim from all three places it appears.
- **13. `SOL-A6`**, noting that write-failure recovery already exists on two of four sinks so the shape
  can be copied, and that `N11` and `N12` mean the sinks are already telling the engine things it
  throws away.

### Tier 3. Truth rows

- **14.** The twelve items in section 6.4, plus RC-1, RC-2 and RC-3. None is hard. All of them are why a
  future reader will believe something false. This is exactly the tier `17.20` already puts third,
  and the project's own stated failure mode is claims that were not true.

### Tier 4. Features, re-ranked by real user value

- **15. Subtitle charset detection and conversion** (M15's charset half). Most of the non-English
  subtitle world is not UTF-8. Today those files render as replacement characters on every
  platform with no warning, and this appears nowhere in the register **or** in the parity map.
- **16. Wire the libass module that already exists** (M16's wiring half). Seven target families, built
  and device-proved, and the engine never calls it.
- **17. A bridge to FFmpeg's subtitle decoders** (M01 plus M02 plus the compiled-but-unreachable half
  of M15). PGS and VobSub are what every ripped disc carries, and the decoders already ship.
- **18. Container metadata to the public surface** (`N24`, M37's static half). The title and artist are
  already in memory and no application can reach them.

### What NOT to schedule

| Item | Why |
|---|---|
| `S19` | False. The vendored source carries `has_b_frames` in the exact function the row says drops it. |
| `S21`, `S33`, `S35`, `S49` | Not defects. Out of scope, deliberate and documented, a dead branch, and descriptive, in that order. |
| `M06`, `M30` | Decided out of scope. One is an attack-surface decision in the build; the other is a Horizon B deferral stated in public KDoc. |
| M34's extensibility half | Rejected by decision, including mpv shader compatibility being explicitly out of scope. |
| M04's SABR clause | A vendor-specific transport in neither the scope statement nor the register. |

---

## 11. How to use this file

- **`SALANKE_FINAL.md` is still the source of the mechanisms.** Its 468 citations are all real, all
  correctly linked, and all traceable. Do not delete it.
- **Use this file for the verdicts.** Where the two disagree, this one read the code that ships in
  the working tree today; the other read a snapshot and sometimes read the wrong file.
- **A second single-threaded pass re-verified the load-bearing claims directly** before this file
  was finalized: the S19 deletion, the S23 elevation, RC-1's git history (`e201186` already carried
  the 6-channel clamp; `628d899` on 2026-08-17 landed multichannel), the packet-cache wedge lines,
  and every receipt in sections 5 and 6 that a work order depends on. Where a line number in this
  file drifts, locate by symbol.
- **Nothing here is CONFIRMED.** The taxonomy reserves that word for a defect reproduced by a
  failing test in the virtual-time harness, and this pass wrote no code. Section 6.5 is a reason that
  matters: two of the guards that would carry those tests are not actually protected by one.
- **Receipts.** Every claim in this file names a file and a line. Where a receipt is in KiteCodec, it
  says so. When line numbers rot, locate by symbol name.
- **The register is the authority on what is open**, not this file. Section 7 corrects three of its
  claims; sections 6 and 9 say which rows should be promoted into it and which are already there.
