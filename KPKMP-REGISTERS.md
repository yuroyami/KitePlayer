# KPKMP registers: every row, open and closed

> Moved verbatim out of KPKMP.md on 2026-08-18. NOT ONE LINE WAS REWRITTEN; the split was
> mechanical and is proved by `scripts/verify-kpkmp-split.py`, which fails if any line or any of the
> 283 register ids stops resolving.
>
> **KPKMP.md is still the entry point and still the authority on what is OPEN.** Its consolidated
> register is the index; this file is the detail behind each row. Read the index first, come here
> for the row you are actually working.
>
> Contains: the defect register (4), dead surface disposition (5), the documentation truth register
> (6), the distilled audit register and its two audits (17.11, 17.11.a, 17.11.b), and the web
> register (17.14). Section numbers are unchanged so every existing cross-reference still lands.

## 4. Defect register

Every defect is numbered, located, verified against source, and has its fix decided. The
phase line says where it is fixed. "Test" names the regression test the fix must add. Do
not fix a defect without its test unless the row says none. D1 to D19 are from the first
review; D20 to D35 were found by the second audit and each was re-verified against the
code before being accepted here. Two earlier prescriptions (inside D9 and D10) were
corrected by that audit; the corrected text below is authoritative.

### D1. Late-drop uses the wrong frame's duration
- Where: `kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/VideoPlayback.kt`,
  the late-drop block in `tick()`.
- Problem: the drop deadline reuses `nominalUs`, the duration of the frame already shown.
  The correct rule uses the duration of the candidate frame, `following.pts - next.pts`.
  Identical for constant frame rate, wrong for VFR, which is when drops happen.
- Fix: compute the candidate duration when both frames are from the current generation and
  the value is positive and at most `maxFrameDurationUs`; otherwise fall back to
  `nominalUs`.
- Phase: A1. Test: a `VideoPlaybackTest` VFR case where the old rule drops the wrong frame.

### D2. `repeatedFrames` overcounts
- Where: same file, the `SyncLaw.classify` call before the "not due yet" early return.
- Problem: classification runs on every poll of a not-yet-due frame, so one repeated frame
  increments the counter once per scheduler wake-up.
- Fix: count exactly once, at the moment the frame is presented, when the delay used
  satisfied `delayUs >= nominalUs * 2`. (Renderer-reported repeats are a separate Horizon B
  statistic; this counter means scheduler-decided repeats and its KDoc says so.)
- Phase: A1. Test: video ahead of master, five polls of one frame, counter ends at 1.

### D3. Dead parameter in `present()`
- Where: same file, `private suspend fun present(frame: VideoFrame, ...)`.
- Fix: delete the parameter. Phase: A1. Test: none (compiler enforces).

### D4. The sample races the clock across threads
- Where: `kiteplayer-sample/.../Main.kt` (progress loop and present loop both call
  `audio.position()`), and `kiteplayer-core/.../AudioPlayback.kt`.
- Problem: `AudioPlayback.position()` re-anchors the internal `MediaClock`, documented
  single-owner, from two threads concurrently.
- Fix: an internal `SynchronizedObject` in `AudioPlayback` guarding the NON-suspending
  members: `anchorClock`, `position`, and the `speed` setter. The suspending members
  (`play`, `pause`, `flush`, `drain`) are NOT wrapped in the lock (a lock cannot hold
  across suspension); they stay thread-confined to the session owner, and their KDoc says
  so. In A5 the core actor becomes that owner. Also fix the `anchorClock` KDoc, which
  claims `position` does not re-anchor.
- Phase: A1. Test: none practical for the race; the lock plus KDoc is the fix.

### D5. AppKit renderer: close race, unhandled channel exception, leaked thread, unbounded main-queue work
- Where: `kiteplayer-output/.../AppKitVideoRenderer.kt`.
- Problems: (1) `present()` checks `closed` then stores into `pending`; a `close()` between
  the two strands the stored frame. (2) `close()` closes the signal channel, so the
  worker's `receive()` throws `ClosedReceiveChannelException`, unhandled. (3) The
  `newSingleThreadContext` dispatcher is never closed: one leaked thread per renderer.
  (4) Every converted frame is `dispatch_async`ed to the main queue; a slow main thread
  accumulates unbounded image work, and cancellation does not await an in-progress
  conversion or fence main-queue delivery.
- Fix: hold the dispatcher in a field. In `present()`, after `pending.getAndSet(frame)`,
  re-check `closed`; if closed, drain the slot and return false. Worker loop catches
  `ClosedReceiveChannelException` and exits. Main-thread delivery goes through its own
  latest-only slot: the worker stores the finished image in an atomic slot and enqueues at
  most one main-queue drain block at a time; a newer image replaces the waiting one.
  `close()` order: CAS closed, close signal, cancel worker and JOIN it (await the running
  conversion), drain pending, drain the image slot, close the dispatcher.
- Phase: A3. Test: appleTest presenting from one coroutine while closing from another, 100
  iterations, counting fake frames prove exactly-once close; plus a slow-main-queue
  simulation proving at most one queued delivery block.

### D6. Sample decode loop can discard an unconsumed packet
- Where: `Main.kt`, both decode loops.
- Problem: when `send` returns false and `receive` returns null, `break` exits and
  `packet.close()` runs on a packet the decoder never accepted. The adjacent comment claims
  the opposite.
- Fix: canonical loop for both streams:
  ```kotlin
  for (packet in channel) {
      while (!decoder.send(packet, generation)) {
          val frame = decoder.receive()
              ?: error("decoder refused a packet and produced nothing; this violates the codec contract")
          deliver(frame)
      }
      packet.close()
      while (true) { val frame = decoder.receive() ?: break; deliver(frame) }
  }
  ```
  Also check `video.submit(frame)`'s return; on false, stop the loop.
- Phase: A3. Test: none beyond the gate's sample runs.

### D7. AudioRing: dead `epoch` field and a documented behaviour that does not exist
- Where: `kiteplayer-core/.../internal/AudioRing.kt`.
- Problem: `epoch` is written and never read; its KDoc describes filtering that does not
  exist. `flush()` writes `consumed`, the callback's counter, safely only because callers
  stop the sink first, stated nowhere.
- Fix: delete `epoch`. `flush()` KDoc gains the precondition: sink stopped, both sides
  quiescent. (The timestamp-segment work in D24 supersedes the single-anchor mapping.)
- Phase: A1. Test: none (deletion plus documentation).

### D8. KiteCodec: `openPacketReader` poisons stream discard flags permanently
- Where: `../KiteCodec/.../MediaSource.native.kt` and `Playback.native.kt`.
- Problem: opening a reader sets discard-all on unselected streams; close never restores
  them, so the batch decode API silently returns zero frames for those streams afterward.
- Fix: `PacketReader.close()` restores discard-default on every stream before
  `endPacketReader()`.
- Phase: A2. Test: open reader on stream 0 of a two-stream file, close, batch-decode
  stream 1, assert frames arrive.

### D9. KiteCodec: missing overflow-safe microsecond helpers (pts, dts, duration)
- Where: `../KiteCodec/.../Playback.native.kt`, `Frame.native.kt`; consumer damage in
  `kiteplayer-ffmpeg/.../KiteCodecSource.kt`.
- Problems, all verified: KitePlayer computes `pts * 1_000_000 * num / den` in Long, which
  overflows on large time bases (a nanosecond-timescale MP4 after ~2.5 hours). Packet DTS
  is exposed as RAW TICKS wrapped in a microsecond type (`Pts(native.dts)`), no rescale at
  all. `ffkmp_rescale_q` (128-bit intermediate) exists and is already used for
  `Packet.ptsMicros`.
- Fix, KiteCodec: add under `@KiteCodecLowLevelApi`: `Packet.dtsMicros: Long?`,
  `Packet.durationMicros: Long?`, `Frame.ptsMicros: Long?`, `Frame.durationMicros: Long?`,
  all via `ffkmp_rescale_q` with the stream time base, null on NOPTS or zero duration.
- Fix, KitePlayer: delete every manual timestamp multiply and the raw-DTS cast; use the
  helpers through D10's mapper.
- Phase: A2. Test: rescale a value that overflows the naive multiply (pts 10^13 at
  1/10^9) and assert the exact result; assert dtsMicros for a 90 kHz stream.

### D10. Timeline: one relative timeline, and no fabricated zero
- Where: `kiteplayer-ffmpeg/.../KiteCodecSource.kt` throughout.
- Problems: seek takes content-relative micros while every produced timestamp stays
  absolute; a frame with no pts is reported as `Pts(0)`, a fabricated real timestamp.
- Fix, one mechanism with two distinct functions (this distinction is mandatory):
  `mapTimestamp(rawTicks, timeBase)` rescales AND subtracts the container start;
  `mapDuration(rawTicks, timeBase)` rescales ONLY. A duration is an interval; subtracting
  an origin from it is a bug. PTS and DTS both go through `mapTimestamp`; packet and frame
  durations go through `mapDuration`. The engine timeline starts at zero, normalised
  exactly once at this boundary. For missing pts, synthesise inside the decoder wrappers:
  video, previous pts plus a duration estimate (frame duration, else container rate, else
  40 ms); audio, a running counter advanced by exact sample count over sample rate. The
  SPI keeps non-null `VideoFrame.pts`.
- Phase: A2. Test: transport-stream clip with `-output_ts_offset 1400`; first frame pts
  within one frame duration of zero; packet duration unchanged by the offset; DTS relative.

### D11. CoreAudioSink half-honours its injected clock
- Where: `kiteplayer-output/.../CoreAudioSink.kt`.
- Fix: `init { require(clock === AppleHostClock) { ... } }` with a message explaining the
  shared time base. Keep the parameter for a future base-translating implementation.
- Phase: A3. Test: appleTest asserting the message on a foreign clock.

### D12. Multichannel audio plays garbage, and nothing resamples
- Where: end to end. Decoder outputs N-channel interleaved float; the sink coerces to
  stereo; nothing downmixes or resamples; the ring receives N-channel data as 2-channel.
- Fix: a new internal commonMain stage wired between decoder and `AudioPlayback.submit`:
  - `ChannelMixer`: mixes named layouts (mono, stereo, 2.1, quad, 5.0, 5.1, 5.1side, 6.1,
    7.1) to the negotiated layout with the standard ITU coefficients (centre and LFE at
    -3 dB, surrounds at -3 dB into stereo; exact matrix rows in KDoc). The layout comes
    from the channel MASK (see D30), not the count; 5.1 side and 5.1 back are distinct
    named cases that happen to share a stereo matrix but must not be conflated in the
    model. Unknown mask: first N channels plus a one-time warning.
  - `LinearResampler`: linear-interpolation rate conversion, stateful across buffers,
    KDoc-documented as interim quality, replaced by libswresample in Horizon B (B4). It is
    not the 1.0 production default and no document may present it as such.
  - `GainStage`: one multiply for volume and mute, applied last, with a short linear ramp
    (default 5 ms) so changes do not click.
  - `AudioPipeline` composing the three, rebuilt when the decoder's `outputFormat` stops
    matching the negotiated format.
- Phase: A4. Tests: mixer matrix unit tests per named layout (impulse per input channel,
  exact expected output); resampler ratio and continuity tests; `surround51.mp4` in
  testmedia compared against `ffmpeg -ac 2` reference PCM; ramp test (no step larger than
  the ramp slope).

### D13. `speed` is a lie while audio plays
- Where: `AudioPlayback.speed`, `MediaClock.speed`.
- Fix: the setter throws `UnsupportedOperationException` when a ring is open and the value
  is not 1.0. Video-only speed through the video clock stays legal.
  `AudioConfig.preservePitch` KDoc gains "not implemented; Horizon B".
- Phase: A4. Test: throws with audio open; succeeds video-only.

### D14. Frame queue holds more than the documented bound
- Where: `VideoPlayback` passes `FrameQueue(queueCapacity + 1)`.
- Fix: pass `queueCapacity` unchanged; default 4; document total held as capacity plus the
  shown slot (which after D20 is metadata, not a frame).
- Phase: A1. Test: `queuedFrames` never exceeds 4 in the harness.

### D15. SoftwareConverter correctness leftovers
- Where: `kiteplayer-ffmpeg/.../SoftwareConverter.kt`.
- Items: (1) unused `depth` parameter on `Coefficients.of`: delete (A0). (2) SMPTE 240M
  gets BT.601 numbers: own row, rCr 1.576, gCb 0.2266, gCr 0.4769, bCb 1.826 (A4).
  (3) NV12 ignores chroma siting: apply the same shift as planar (A4). (4) `readComponent`
  KDoc has the bit-dropping direction backwards (A0). (5) See D26 for P010.
- Tests for 2 and 3: smpte240m-tagged and centre-sited NV12 goldens, mean error under 2.

### D16. HDR converts silently with no tone mapping
- Where: `SoftwareConverter` accepts PQ and HLG and converts with the matrix only.
- Fix for Horizon A: keep converting, add
  `PlaybackWarning.TonemappingUnavailable(detail)`, emit once per stream. This
  approximate-plus-warning behaviour is ALSO the documented user-visible policy default
  until the colour-managed pipeline exists; the 1.0 gate in Horizon B (B5) requires a real
  tone-mapped path, and approximate output then becomes an explicit opt-in policy rather
  than the only behaviour.
- Phase: A4. Test: PQ-tagged clip emits the warning exactly once.

### D17. KiteCodec: `receive()` cannot distinguish "need input" from "drained"
- Where: `../KiteCodec/.../Playback.native.kt`.
- Fix: `public val isDrained: Boolean` on `StreamDecoder`, set on end-of-stream from
  receive, cleared by `flush()`.
- Phase: A2. Test: drive to drain, flag flips, flush clears. (The full typed
  send/receive outcome model is Horizon B, B2.)

### D18. KiteCodec: `seekMicros` not guarded against an open PacketReader
- Where: `MediaSource.native.kt`.
- Fix: `check(!readerActive)` directing callers to `PacketReader.seek`.
- Phase: A2. Test: assert the throw.

### D19. Window close leaves playback running headless
- Where: `AppKitWindow.kt`.
- Fix: `onCloseRequested` callback via an `NSWindowDelegateProtocol` object; `stop()` posts
  a dummy `NSApplicationDefined` event after `NSApp.stop` so the run loop wakes; the
  sample cancels its session scope on close.
- Phase: A3. Test: manual gate check (red button ends the process cleanly).

### D20. Shown-frame ownership is contradictory (double close)
- Where: `FrameQueue` stores the advanced frame as `shown` and closes it later (on the
  next advance, on flush, on close); `VideoPlayback.present` hands the SAME object to
  `renderer.present`, whose contract says the renderer owns and closes it. Verified: every
  presented frame is closed twice; the KiteCodec wrapper's idempotent `close()` masks it,
  `peekShown()` can observe a closed frame, and for future hardware surfaces this returns
  a buffer to its pool while in use.
- Fix, decided: the queue never retains a renderer-owned object. `FrameQueue` keeps a
  small value record of the last shown frame's metadata (`pts`, `duration`, `generation`)
  for duration measurement and generation checks, and the frame object itself has exactly
  one owner at every instant: queue until advance, renderer after present. Redraw-on-resize
  is the renderer's concern (the AppKit renderer already holds its last image).
  Reference-counted leases are the Horizon B answer for hardware surfaces (B5); they are
  not built now.
- Phase: A1 (queue and scheduler), A3 (renderer side unchanged but re-tested).
- Test: ledger test proving exactly one close per frame across present, drop, supersede,
  flush and close; `peekShown` is gone from the API so misuse cannot compile.

### D21. Presentation target time is not passed, and submission is counted as presentation
- Where: `VideoPlayback.present` calls `renderer.present(shown, nowNanos)`; the design
  says the renderer receives the intended presentation time. Verified. Also
  `presentedFrames` increments on renderer ACCEPTANCE (or on null renderer), while the
  AppKit renderer may later fail or supersede the frame, so engine statistics conflate
  submitted with drawn.
- Fix, decided for Horizon A: (1) pass the frame's target time (`frameTimerNanos`-derived)
  instead of `now`. (2) Rename engine counters to what they measure: `submittedFrames`
  (accepted by a renderer), `headlessFrames` (no renderer attached), keep `droppedFrames`
  and `repeatedFrames` as scheduler decisions. The renderer's own `presentedFrames`,
  `supersededFrames`, `failedFrames` remain the drawing truth and A5 surfaces both groups
  distinctly in `PlaybackStats` with KDoc stating the difference. First-frame semantics:
  any "first frame" signal in A5 means terminal renderer feedback, not submission. The
  full per-frame feedback protocol (typed terminal Presented/Superseded/Failed/Cancelled
  per submission with scanout quality) is Horizon B (B5).
- Phase: A1 (target time, renames), A5 (stats wiring).
- Test: harness renderer records received target times and they match the schedule, not
  the call instant; stats naming test.

### D22. Decoder wrappers restamp buffered frames with a new generation
- Where: `kiteplayer-ffmpeg/.../KiteCodecSource.kt`: both decoder wrappers set
  `this.generation = generation` on every `send`, before knowing whether the packet was
  accepted; frames still buffered from before then surface stamped with the new
  generation.
- Fix: the generation field changes only in `flush()` (which takes the new generation as a
  parameter); `send` never mutates it. The SPI `send(packet, generation)` parameter is
  removed and `flush(newGeneration: Generation)` added, since flush is the only legal
  epoch boundary.
- Phase: A2. Test: scripted sequence, send returns false with output pending, flush to a
  new generation, buffered frames must carry the OLD generation before the flush and none
  after.

### D23. CoreAudio real-time callback allocates, and open is not transactional
- Where: `CoreAudioSink.renderFromDevice` constructs a `CoreAudioSinkBuffer` on every
  device callback (verified), violating the sink's own no-allocation contract; a null ring
  path returns without explicitly zero-filling; the callback's returned frame count is
  ignored; `mHostTime` validity flags are unchecked; a failure between
  `AudioComponentInstanceNew` and the end of `open` leaks the instance or the `StableRef`.
- Fix for Horizon A: preallocate ONE reusable buffer wrapper at open; the callback only
  updates its target pointer and frame count fields (plain field writes, zero allocation).
  When the render callback is absent or returns fewer frames than requested, the sink
  zero-fills the remainder itself (the ring already writes silence; the sink is the last
  line). Validate `kAudioTimeStampHostTimeValid`; when absent, fall back to
  `clock.nanos()` plus the buffer duration and mark the anchor Estimated. Make `open`
  transactional: every failure path disposes what was created. The stricter rule (pure
  C-only callback body that never enters Kotlin) arrives with the shared C ABI in
  Horizon B (B1) and is recorded there.
- Phase: A3. Test: appleTest asserting one wrapper identity across callbacks (via a
  counting fake), silence-fill on a null callback, and no leak on a forced mid-open
  failure.

### D24. Audio anchor is one sample period off, and one anchor cannot describe two segments
- Where: `AudioRing.render` pairs the PTS of the last real sample (its start instant) with
  the deadline of the buffer end (the instant AFTER that sample finishes). Verified: the
  anchor is one sample period late. Also `anchorMapping` keeps ONE (pts, frame) mapping;
  writing a discontinuous segment while older samples are still queued re-anchors the
  mapping those older samples still need.
- Fix: standardise on the boundary convention: the published anchor is
  (ptsOfLastRealSample + one sample duration) at (deadline minus the silence adjustment),
  meaning "media time at the playhead boundary". Replace the single mapping with a small
  fixed ring of up to 4 ordered (startFrame, pts) segments; the writer appends on
  discontinuity, the callback resolves a frame index against the segment that contains it,
  and segments are retired once consumed. All storage preallocated, seqlock-published as
  today.
- Phase: A1. Test: table-driven ring tests: partial callbacks, silence tails, a callback
  ending exactly on a segment boundary, a callback crossing a discontinuity, at 44.1, 48
  and 96 kHz; the published anchor is sample-exact in every case.

### D25. Seek and flush ordering can race the device callback (plan defect, now fixed here)
- Where: the previous revision of this document ordered a seek as "clear queues and ring,
  then stop the sink". The code (`AudioPlayback.flush`) stops the sink first; the PLAN had
  it backwards, and generation tags do not excuse racing a real-time reader.
- Fix: the canonical quiesce-first sequence in digest 8.1 is authoritative: sink stopped
  and callback provably out before the ring is touched; workers acknowledge quiescence
  before their queues are cleared; decoders flush on their owning workers.
- Phase: A5 (PlaybackCore implements it); A1 keeps `AudioPlayback.flush`'s existing
  correct order and documents it as a contract.
- Test: A5 native stress: seeks fired during every pipeline stage; zero stale audible
  samples; bounded completion.

### D26. P010 is read with the wrong bit alignment
- Where: `SoftwareConverter.readComponent` shifts 10-bit words down by `depth - 8`,
  correct for low-aligned yuv420p10le, wrong for P010 whose samples are HIGH-aligned in
  16-bit words (the golden suite only covered the low-aligned case). Verified latent.
- Fix: the converter distinguishes low-aligned and high-aligned 10-bit formats; P010
  components are `word shr 8` (take the high byte). BT.2020 constant luminance is NOT
  silently treated as non-constant: it gets the same treatment as HDR (convert
  approximately plus a one-time typed warning), because a correct CL path needs the
  Horizon B colour pipeline.
- Phase: A4. Test: P010 golden generated in testmedia, mean error under 2; CL-tagged clip
  emits the warning.

### D27. Public filter descriptions can corrupt native stack memory
- Where: `../KiteCodec/.../ffmpeg.def`, both audio filter-description builders (the
  single-input and multi-input variants). Verified: repeated
  `n += snprintf(buf + n, sizeof(buf) - n, ...)` with the overflow check only AFTER all
  appends. snprintf returns the WOULD-BE length on truncation, so a user description near
  or over 2048 bytes makes `buf + n` point outside the array and `sizeof - n` wrap to a
  huge size_t: undefined behaviour reachable from the public `FilterGraph` API.
- Fix: after EVERY snprintf, check `n` (or `len`) against the buffer size and fail with
  `AVERROR(EINVAL)` before computing the next destination pointer. Both sites. (Moving to
  AVBPrint is fine but not required now.)
- Phase: A0 (memory safety does not wait). Test: KiteCodec test feeding descriptions of
  length 0, 2047, 2048, 4096 and 1 MB; all either succeed or fail cleanly, run under the
  default test harness plus one ASan-enabled local run recorded in the log.

### D28. FilterGraph: multi-input can spin forever, and the drain landing frame is not always released

**Corrected 2026-08-17 (phase W).** The fix below says "always `av_frame_unref` the landing frame
in a finally block". The code went FURTHER under audit P0-2 and closes the callback WRAPPER
instead: the wrapper is non-owning, so closing it both performs the unref and invalidates the
handle for a callback that retained it, which unreffing alone did not. A reader following only the
text below would conclude the tree is wrong.
- Where: `../KiteCodec/.../FilterGraph.native.kt`. Verified: `feedInput` retries the same
  pad after EAGAIN plus a synchronous drain; in a multi-input graph (overlay, amix) the
  sink's EAGAIN can mean "need the OTHER input", so the loop can spin without progress.
  And `drainTo` wraps the landing frame for the callback without an unconditional unref: a
  callback that neither closes nor throws leaves the landing frame populated, violating
  the empty-destination precondition of the next receive.
- Fix: in `drainTo`, wrap, invoke, then ALWAYS `av_frame_unref` the landing frame in a
  finally block (a consumer needing ownership clones, which `Frame.copy()` already does).
  In `feedInput`, track whether the drain produced any frame; on a second consecutive
  EAGAIN with no progress, throw a typed error naming the starved multi-input condition.
  Fair multi-input scheduling (NeedInput(pads) results) is Horizon B (B2).
- Phase: A2. Test: no-op callback across two consecutive outputs (frame content must not
  leak between them); a two-input graph fed only on one pad fails with the typed error
  instead of hanging.

### D29. MediaSink advances missing audio timestamps by the wrong frame's samples

**Corrected 2026-08-17 (phase W).** The encoder half below is still accurate, but nothing here
recorded that `EncoderCore.outputMicros` changed meaning under audit P1-14: it is where the output
timeline ENDS, the last frame's own extent included, not that frame's start. The test that read it
as a start had been red since 2e60bf3 without anyone seeing it, because `macosArm64Test` could not
link until phase W.
- Where: `../KiteCodec/.../MediaSink.native.kt`, `restampPts`. Verified: the NOPTS path
  and the monotonic-force path both step by the CURRENT frame's `nb_samples`; the correct
  step is the PREVIOUS frame's duration (960 then 1024 must start at 0 and 960, not 0 and
  1024).
- Fix: track the previous frame's sample count and use it as the step in both paths.
- Phase: A2. Test: encode frames of 960 and 1024 samples with missing pts; output pts are
  0 and 960; ffprobe agrees on total duration.

### D30. Channel layout is reduced to a count
- Where: KiteCodec discards `AVChannelLayout` (a count-derived default is invented);
  KitePlayer's `ChannelLayout.forChannelCount` guesses from the count. 5.1 side versus
  back, custom orders and anything above 8 channels are unrepresentable, and D12's mixer
  would route wrong speakers without this.
- Fix for Horizon A: KiteCodec exposes `channelLayoutMask: Long?` (the native order mask,
  null for custom/unspecified) on `AudioStreamInfo` and `FrameInfo` via one new def
  helper; KitePlayer's `AudioFormat` gains the mask, and the D12 mixer keys on it, falling
  back to count only when the mask is absent (with the warning). Full first-class layouts
  (custom order, ambisonics, above 8 channels, extended_data access) are Horizon B (B2).
- Phase: A2 (KiteCodec), A4 (mixer use). Test: a 5.1(side) clip reports the side mask and
  mixes with the 5.1 matrix, not the first-six fallback.

### D31. `withPlanes` on an audio frame computes nonsense heights
- Where: `ffkmp_frame_plane_height` interprets the frame format as a pixel format
  unconditionally; for audio frames that is a sample-format ordinal. Verified.
- Fix: `Frame.withPlanes` requires a video frame (`check(info.type == MediaType.Video)`),
  and the def helper returns 0 for a frame whose width is 0.
- Phase: A2. Test: audio frame throws the check message.

### D32. Fabricated source capabilities and unvalidated numeric inputs
- Where: `KiteCodecSource` hardcodes `seekable = true` (verified); `MediaClock.speed`
  accepts positive infinity (`require(value > 0.0)` passes for Inf; verified).
- Fix: KiteCodec adds `ffkmp_fmt_is_seekable` (pb seekable flag plus format flags) and
  `MediaSource.isSeekable`; `KiteCodecSource.seekable` reads it. `MediaClock.speed`
  requires finite and positive; every numeric public input added in A5 (volume, rate,
  seek positions, durations) validates finite value and documented range at the boundary.
- Phase: A2 (seekable), A1 (speed), A5 (facade inputs). Test: a pipe input reports
  non-seekable; `speed = Double.POSITIVE_INFINITY` throws.

### D33. The VFR fixture is not VFR, and one drift sign contradicts the other
- Where: `scripts/testmedia.sh` generates `vfr720p60.mp4` as a CONSTANT 59.94 fps stream
  (verified: plain testsrc2 rate, no timestamp modulation), so nothing exercises real VFR.
  And `PlaybackStats.avDrift` KDoc says positive means video late while
  `VideoPlayback.drift` is video minus master, positive meaning video ahead.
- Fix: regenerate the clip with genuine irregular timestamps (a `setpts` expression
  alternating frame durations) and rename it `truevfr720.mp4`; update every reference.
  Define ONE sign convention for the whole project: positive drift means video is AHEAD of
  the master clock; fix the `PlaybackStats` KDoc and any display strings.
- Phase: A0 (fixture, sign KDoc); the D1 VFR test uses the new clip in A1.

### D34. Backend composition cannot construct generic playback
- Where: `PlayerConfig.Backends` has video decoder factories, an audio SINK factory, a
  source factory and a clock, but NO audio decoder factory and no subtitle decoder factory
  (verified); the sample reaches them by downcasting to `KiteCodecSource`. Kotlin/Native
  has no classpath service discovery, so "the platform default is found on the classpath"
  cannot work either.
- Fix, decided: the SPI gains a session-shaped backend contract and the facade consumes
  only it:
  ```kotlin
  public interface MediaBackend {
      public suspend fun open(media: MediaItem): BackendSession
  }
  public interface BackendSession : AutoCloseable {
      public val source: PlayerMediaSource
      public val videoDecoders: List<VideoDecoderFactory>
      public val audioDecoders: List<AudioDecoderFactory>
      public val subtitleDecoders: List<SubtitleDecoderFactory>
  }
  ```
  `Backends` shrinks to `backend: MediaBackend?`, `output: OutputBackend?` where
  `OutputBackend` pairs the clock with the sink factory and (optionally) a renderer
  provider, so a mismatched clock/sink pair (D11) cannot be assembled. Defaults are
  explicit composition: on macOS the sample (and later the umbrella module, Horizon B B7)
  passes `KiteCodecMediaBackend` and `AppleOutputBackend` objects; core never guesses.
- Phase: A5 (this is the first A5 step, before PlaybackCore consumes it). Test: a fake
  backend and the FFmpeg backend both drive PlaybackCore with no cast anywhere.

### D35. Wrapper misuse guards
- Where: `../KiteCodec/Playback.native.kt`: `Packet` getters dereference native memory
  after `close()` (no checkOpen); `StreamDecoder.send` accepts a closed packet.
- Fix: `checkOpen()` in every `Packet` getter and at the top of `send` for the packet
  argument. Full confinement-or-lease enforcement across every wrapper is Horizon B (B2).
- Phase: A2. Test: closed-packet getter and closed-packet send both throw the message.

---

## 5. Dead surface disposition

| Symbol | Disposition |
|---|---|
| `PlayerConfig`, `BufferPolicy`, `AudioConfig`, `SubtitleConfig`, `PlayerLogger`, `PlayerEvent`, `PlaybackStats`, `PlayerSnapshot`, `Progress`, `Tracks`, `TrackId`, `TrackKind`, `LoopMode`, `SyncMode`, `MasterClock`, `PlaybackStatus`, `PlaybackError`, `SeekMode`, `Chapter` | Keep, A5 wires them into the facade. Every member that A5 does not implement gets an explicit KDoc marker "not implemented; see the roadmap" in A0 (the truth ledger seed), and A5's gate re-checks the list: implemented, typed-rejected, or removed. |
| `Backends` | Reshaped in A5 per D34. |
| `SeekRequest`, `SeekTarget`, `SeekPhase`, `SeekTiming` | Keep, A5 (the seek machine). |
| `PacketQueue.isReady`, `isWellBuffered`, `awaitDrain`, `dropFromTail`; `FrameQueue.awaitFrame`; `MediaClock.snapshot`, `ClockSnapshot` | Keep, A5 consumes them. `dropFromTail` gains a KDoc warning that arbitrary compressed-packet dropping is only legal at the tail of a not-yet-decoded run; GOP-safe discard is Horizon B. |
| `MediaIo`, `SubtitleSource`, `HwdecPolicy.Prefer`, `HwdecKind`, `HwSurfaceKind` values, `AudioSinkBuffer.writePlane`, `AudioSink.latencyNanos`, `LatencyQuality` | Keep: SPI surface for Horizon B backends; costs nothing; marked in the truth ledger. |
| `NO_PTS` top-level const | Delete in A0 (zero usages). |
| `AudioRing.epoch` | Delete in A1 (D7). |
| `MediaClock.lastUpdatedNanos` | Keep; KDoc rewritten in A0 to describe its real consumer, the pause and resume arithmetic of digest 8.1. |
| `FrameQueue.peekShown` | Removed in A1 by D20 (replaced by the metadata record). |
| `ffkmp_stream_r_frame_rate` in the def file | Delete in A2 (never called). |
| `kiteplayer-subtitles` module, `SubRipParser` | Keep. A0: package rename from `subtitles` to `subtitle` to match the cue model, plus parser unit tests (BOM, CRLF, missing sequence numbers, overlapping cues, malformed timestamps, the generated `subs.srt`). Rendering is Horizon B (B3). |

---

## 6. Documentation truth register

1. `README.md` (both repos from A0): rewritten against reality, including the tier table
   from section 2 with today's honest values (macOS arm64 experimental T3-Full candidate;
   everything else T1; no published install path). Rewritten again at the end of A6.
2. `AudioPlayback.anchorClock` KDoc: false separation claim (fixed with D4, A1).
3. `AudioRing.epoch` KDoc: describes nonexistent behaviour (deleted with D7, A1).
4. `SoftwareConverter.readComponent` KDoc: bit-drop direction backwards (D15.4, A0).
5. `MediaClock.lastUpdatedNanos` KDoc: cites a class that does not exist (A0).
6. KDoc in `PlayerConfig.kt`, `PlayerState.kt`, `PlayerEvent.kt` links to the not-yet
   existing facade: soften in A0, restore in A5.
7. `CoreAudioSink` and `AppleHostClock` KDoc reference config wiring that exists only
   after A5: soften in A0, restore in A5.
8. `PlaybackStats.avDrift` sign contradiction (D33, A0).
9. Truth ledger seed (A0): every public configuration member that nothing implements
   gains the explicit KDoc marker. The A5 gate walks the list again.
10. `CoreAudioSink` KDoc, the comment calling the duplicated silence fill deliberate: it stopped
    being true the moment the callback became C, because there is no absent callback to cover.
    Corrected with B1-19 in B1.8, in that file and in `AudioSink.kt`, and the obligation the
    collapse removed from the Kotlin path is now stated on `AudioRenderCallback` instead of being
    owned by nobody (found by the B1.8 verification).
11. `AudioRenderCallback` KDoc, the try-lock claim: it said the ring is read "with a try-lock, and
    on contention writes silence", which was never true of any ring here. Corrected in B1.8 with
    what is true instead, which is register item B1-16.
12. B1-20, in three places, in B1.9: the KitePlayer README, `AudioRingTest`'s class KDoc and the
    Execution log. On macOS the sixteen Kotlin ring tests do not cover the shipped path, and the C
    suites plus the differential oracle are what do.
13. KiteCodec's own documents, in B1.9, because that repository is public and its documents were
    behind its code: the README said 72 tests when 85 pass and said nothing catches an accidental
    signature change when `apiCheck` now does; `docs/about.md` described the helpers as `static
    inline` text in the def; `CHANGELOG.md` recorded none of B1; and `native/kitecodec-c/README.md`
    still listed B1.7 and B1.8 as not yet done. All corrected, and the C README now carries the
    instrument table with what each instrument cannot prove.

---

### 17.11 The distilled audit register

Born 2026-08-16 when SOL_REVIEW.md (the 2026-08-13 twin-repository implementation audit) and
ANDROID_GPU_WORK.md (the Android GPU path record) were distilled here and deleted. This register
is the only surviving copy of their open findings. Every open row carries a mark from the
2026-08-16 verification sweep: [V] means the defect was re-verified against the tree on that
date, [C] means the audit's claim is carried but its anchor was not re-verified line by line
(the audit's P0/P1 sections scored zero false positives when they were verified claim by claim,
so a [C] is a debt to check at pickup, not a doubt). Homes are proposals: a stage adopts its
rows at entry through the ordinary 17.1 expansion ritual, and the owner may move any row.

**Closed, with the commits that closed them.** All 9 P0 and all verified P1 rows (KitePlayer
6a74344, KiteCodec 2e60bf3). Perf blocker 1, the CPU-bound Android path (65625e8 direct
MediaCodec tier, 4c4e23a Compose GPU bridge; evidence in ANDROID_GPU_WORK.baseline.txt). The
sws half of perf blocker 3 (helpers_frame.c converts through a thread-local
sws_getCachedContext). The Android multi-image subtitle cursor (index-ordered overlay bitmap
cache in AndroidSurfaceVideoRenderer). Metal odd-size chroma (ceil division in
MetalFrameComposer). The Android mutable-bitmap reuse under HWUI sampling (immutable fallback
images). Overlay redraw on a paused frame for the Android view path (the split's separate
subtitle overlay view) and for KiteVideo (S2.d draw-phase overlays). The fd protocol and
content:// opening (0.0.6). The unconditional Android KMP plugin and the missing portable
JVM/JS/Wasm variants (3f0f1e3). The kitecodec plugin version pin (now the catalog's 0.0.6).
Backend-origin hardware decode recovery (ced6030). minSdk 26 by owner decision.

**Superseded by decision, not open.** Subtitle overlays composite in OUTPUT space on every
renderer (KiteVideo and Metal agree by law; the audit wanted fitted-video space). CoreAudioSink's
plain open() throwing is documented contract: the ring protocol is the real seam. The C
real-time island stays C (the audit's own keep-list, adopted below as the C-reduction charter).

**Open rows.**

Rendering and views:
- SOL-R1: CLOSED by the M4 surge (2026-08-17). All three Apple renderers retain the newest
  picture (a retained CVPixelBuffer or the converted pixels) and re-encode it on overlay AND
  picture-control changes; the render worker owns the redraw, so no lock was added.
- SOL-R2: CLOSED by the M4 surge. The overlay pass is shared by the with-picture and
  no-picture draws; audio-only media shows its subtitles.
- SOL-R3: CLOSED by the M4 surge. A failed image build keeps the old hash so the next
  publication retries, and a close that raced the build wins before publication.
- SOL-R4 to SOL-R8: CLOSED by S2.e (non-planar BGRA sizing, the per-frame cache flush, the
  missing composer close path, pre-commit texture ownership, rotation normalization). The log's
  S2.e entry carries the proofs.
- SOL-R9: CLOSED by phase W (W.7). Exactly one layer sits on the glass, chosen when the
  generation is created, and hasPicture answers about that layer. Proved in a real iOS simulator
  with real UIView, CALayer, CAMetalLayer and UIWindow; the falsification restored both visible
  layers and the cumulative counter and two of three tests went red.
- SOL-R10: ALREADY CLOSED when phase W checked it, and no work was done. PlayerViewBinding
  clears and closes the renderer on attach failure and its detach is finally-safe with
  suppression chained, both already pinned by PlayerViewBindingTest. A [C] is a debt to check,
  and checking it here saved churning a file other tests pin.
- SOL-R11: REDUCED by phase W (W.7), with the remainder stated. A close could wait on work
  STARTED after it began; that is fixed and pinned (an AppKit close measured two deliveries, now
  one), and Metal cancels its worker before joining like its two siblings. The runBlocking itself
  STAYS, because PlayerViewBinding rule 1 requires a synchronous detach; Metal's own drawable wait
  is not host-observable without a real CAMetalLayer and carries the same guard by inspection.
- SOL-R12: CLOSED by phase W (W.7). The claim was true and the code admitted it: contentsScale
  and drawableSize were set once at construction and a comment called the live path future polish.
  A MetalHostView resizes the drawable on setFrameSize and on backing-property changes.
- SOL-R13: CLOSED by phase W (W.7). Both fallbacks accept AT LEAST the minimum, which is what
  RgbaBitmap's contract says and what the Metal composer already honoured.
- SOL-R14: CLOSED by phase W (W.7). The M4 surge had reduced it to the two CPU fallbacks, which
  overrode neither setAdjustments nor setTransform, so the defaulted no-ops ran. A shared
  CpuPictureControls applies the colour matrix on the bytes and the zoom, pan and aspect on the CG
  transform, and both renderers wire it. Two things stay out BY CONSTRUCTION rather than as debt:
  gamma is absent by design, and the MediaCodec direct-to-SurfaceView tier cannot apply either.

Audio:
- SOL-A1: CLOSED by the M4 surge. The submitted count is what the device actually took,
  partial blocks included; proven by an interrupted-write host test and falsified in place.
- SOL-A2: CLOSED by the M4 surge. One writer ever (alive-writer guard), failure marks the
  machine FAILED with writerRun dropped (state before the event, so a listener's immediate
  start sees it), and the next start RECOVERS by releasing the dead device and opening a
  fresh one. All three proven by host tests.
- SOL-A3: CLOSED by the M4 surge. One DriverTimestamp holder for the driver's life (scratch
  by contract), and the frame position wrap-extends by the head's own law, unit-proven; a
  genuinely 64-bit position passes through untouched.
- SOL-A4: CLOSED by the M4 surge. MaximumFramesPerSlice is queried at open and re-queried at
  every start, and a stream-format property listener re-queries on CoreAudio's notification
  thread; 512 remains only as the query-refused fallback.
- SOL-A5: CLOSED by the M4 surge. The deadline publishes with release BEFORE the render
  consumes the ring, so an observer that sees this callback's consumption sees its deadline;
  `running` and the device period are atomics now. C suites, render audit and source
  discipline all green after.
- SOL-A6: PARTIAL by the M4 surge, deliberately. Remainder re-verified STILL OPEN 2026-08-18: no
  passthrough, offload, device-selection or route-recovery surface exists in kiteplayer-core or the
  Apple output tier. Multichannel PCM is REAL: AudioTrack
  accepts 1/2/6/8 with the masks that match FFmpeg's interleave, CoreAudio accepts up to 6
  with the MPEG 5.1 A layout declared, and unmapped counts fall to stereo (the mixer's safe
  landing; 8-into-6 folding is SOL-P8's business). Passthrough, offload, device selection and
  full route recovery remain OPEN here, each its own project. Home stays with B4.

Subtitles:
- SOL-S1: CLOSED by the deep audit's F-ALPHA1 (55a0d60), the day AFTER this row was marked [V],
  by a surge that never read this row. Verified against the tree 2026-08-18. The remedy taken was
  "premultiply exactly once", but in the direction this row did not consider: premultiplied is what
  BOTH platform rasterizers naturally produce (Android's ARGB_8888 copy, CoreGraphics' premultiplied
  context) and what the Compose and Metal consumers upload unconverted, so the CONTRACT moved to
  meet them. RgbaBitmap's KDoc now says PREMULTIPLIED, and the three consumers that premultiplied
  AGAIN (canvas target, overlay view, Metal's blend factor) are raw copies. The visible defect was
  white 50%-alpha text rendering grey. Pinned by DesktopSubtitleRasterizerTest, which asserts colour
  can never exceed alpha.
- SOL-S2: CLOSED by the deep audit's F-CFL1 (3078eb8), again without this row being consulted.
  Verified against the tree 2026-08-18: AppleSubtitleRasterizer carries nine release calls and its
  own comments state that every Create-rule object is released on every exit, naming the leak this
  row described (a two-hour film's cue edges used to leak the framesetter and its laid-out glyphs
  once per span per cue). The leak-test half of the row rides the existing cue-churn coverage.
- SOL-S3 [V] STILL OPEN, re-verified 2026-08-18 with the line in hand:
  AppKitVideoRenderer.drawOverlayInto computes `drawWidth = image.bitmap.width * sx` and
  `drawHeight = image.bitmap.height * sy`, so the SOURCE bitmap's dimensions are scaled by the
  viewport ratio while the region's own `width`/`height` are never read. Position is scaled from
  authoring space exactly as this row said. Home: S4.f.
- SOL-S4 to SOL-S6: CLOSED by S4.f's slice (7e9bb12): open-end resolution in both parsers,
  word-boundary block keywords, and span-text entity decoding.
- SOL-S7 [C] Public cue styling exceeds what the rasterizers apply (first span chooses global
  properties; family, shadow, wrapping, decoration and stroke are partial). Implement per-span
  layout or narrow the claims. Home: S4.f.
- SOL-S8 [C] Explicitly positioned bottom cues still consume implicit stacking space, shifting
  later implicit cues. Home: S4.f.

API truth:
- SOL-API1: CLOSED by the S4.g surge. startPosition is honoured in two halves (pre-worker
  source move, then a precise landing through the ordinary machine; unhonourable requests warn
  StartPositionIgnored, typed). headers and formatHint ride KD-4's pre-open funnel as the http
  `headers` option and a `format_whitelist` of one; an explicit openOptions key wins over the
  typed sugar (preOpenOptions, unit-tested pure).
- SOL-API2 [C] REDUCED by the S4.g surge: preservePitch is REAL (tempo stage against a
  speed-folded resampler, epoch-adopted at flush exactly like the rate; setPreservePitch on the
  facade). Still accepted and unused: logger (superseded by KiteLog, KDoc says so),
  liveBackBuffer, liveMaxLag, startDisabled. Home: S4.e.
- SOL-API3: CLOSED by the S4.g surge. KeyframeThenRefine runs the seek machine's ladder loop in
  two phases: the keyframe lands and PRESENTS first, then an ordinary precise landing on the
  exact frame; SeekCompleted and the replies carry the exact landing only, and a keyframe
  already on the target skips the refine. The coalescing test now pins two flush cycles for one
  merged two-phase seek.
- SOL-API4 [V] REDUCED. `bufferedRanges` is REAL since M5's demuxer cache (PlaybackCore computes
  it from the cache window; CachingMediaIo's KDoc names it), so it is no longer a placeholder and
  is struck from this row. Still honestly KDoc'd as not implemented, verified 2026-08-18:
  droppedFramesDecode, audioLatency, containerBitrate, SyncMode.ExternalMaster and LateAndDecode.
  They stay open as section 11 roadmap facts, not silent lies. Home: their section 11 items.
- SOL-API5: CLOSED by S4.d (46ac28b): renderer events collect into typed warnings, the bounded
  history and the dump.
- SOL-API6: CLOSED by W-18 (8becb00, "Take the C ring pointer off the public ABI"), which landed
  2026-08-17 AFTER this row's own re-verification the same day and was never reflected here.
  Verified against the committed ABI dump 2026-08-18: NativeRingHandoff's constructor and its `ring`
  getter both speak NativeRingAddress, a value class over kotlin/Long, so no CPointer<kprt_ring>
  appears in kiteplayer-core's public surface at all. The C type survives only inside CoreAudioSink's
  implementation, which is where the row wanted it. @RawRingApi remains as the marker on the raw
  path and is no longer the only thing standing between a consumer and the ABI.
- SOL-API7: REDUCED by W-13, and the reduction was never recorded here. Verified 2026-08-18: all
  three sites in :kiteplayer-compose-video (ios, jvm, android) are now `as?` followed by
  `throw UnsupportedFrameType(actual, expected)`, so an unsupported pairing fails as a typed,
  readable refusal rather than a ClassCastException. THE REMAINDER, unchanged and still open: there
  is no sealed hardware-surface model and no renderer capability negotiation, so the refusal still
  arrives at the first frame rather than at bind time. That half is a design act, not an edit
  (18.3 rule 6). Home: the next stage that touches the surface model.

Performance (the open remainder):
- SOL-P1: CLOSED for the software tier by the M4 surge: software planes convert on the CPU
  in one pass (tone mapping included) instead of upload-readback-reupload; the Metal reader
  serves hardware frames only, now tone-mapped for the display path. Interop stills stay
  with KV maturation in W.
- SOL-P2: CLOSED for the pipeline half by the M4 surge: a pass-through mixer aliases the
  caller's scratch instead of copying, so plain playback runs zero pipeline copies before
  the ring write (the aliasing is the documented output contract now). The native-to-
  ByteArray half belongs to SOL-P3's KiteCodec window, unchanged.
- SOL-P3 [C] KiteCodec frame access: native scratch plus second ByteArray on Native, copy
  before JNI's own copy on JVM, and per-access plane list boxing in nominally zero-copy reads.
  Home: the next KiteCodec window.
- SOL-P4: CLOSED by the M4 surge. SharedLaneDispatchers: limitedParallelism(1) lanes over
  the shared pools, suspend-only lanes on Default, blocking lanes (demux, decoders, feeder)
  on IO; the one platform-demanded pinned thread, the device callback, was never the
  engine's. Full suites including the real-thread stress run green over it.
- SOL-P5: CLOSED by the M4 surge. Container cues prune 30 seconds behind the position (a
  backward seek re-decodes them; external tables are never pruned because nothing re-supplies
  them), and rasterisation runs on its own serial lane with a generation guard so only the
  newest publication lands; the job rides session.jobs for teardown.
- SOL-P6: CLOSED by the M4 surge. The snapshot publishes only when a command, outcome or
  explicit site marked the pass dirty (progress and stats keep their own intervals either
  way), and the selected-queues list is cached for the session's life.
- SOL-P7: CLOSED by phase W (W.7), all three parts. Pipelines cache per device registryID and
  target format instead of compiling per composer, the UIKit fallback gained the identity fast
  path AppKit already had, and both fallbacks cache overlay CGImages by content hash: without the
  hash key a held cue built 60 CGImages where it now builds 1.
- SOL-P8 [V] STILL OPEN, re-verified 2026-08-18: LinearResampler's own KDoc still says it is
  "replaced by libswresample in Horizon B". It aliases under real rate changes, and ChannelMixer
  cannot remap equal-count layouts nor limit surround downmix (both KDoc'd interim). NOTE for
  whoever takes it: SOL-P10 turns out to name a SwrContext that does not exist, so this row, not
  that one, is where the swresample adoption actually lands. Home: B4, pulled by S3 if audio work
  lands there first.
- SOL-P9 [V] STILL OPEN, re-verified 2026-08-18: PlaybackCore still speaks of "handleTrackChanges
  to finish its container rebuild", and refuses a track switch on an unseekable source because it
  "cannot reopen it and seek back". Track changes reopen the whole backend session, which
  reconnects network inputs and cannot serve live media. Home: rides 17.8; until then it stays the
  documented limit.
- SOL-P10 [V] QUESTIONED 2026-08-18, and the row may be MOOT as written. `swr_` and `SwrContext`
  appear NOWHERE in KiteCodec's eleven C sources; the only mentions in the repository are two audit
  shell scripts. There is no SwrContext for anyone to own persistently, because audio conversion is
  Kotlin-side (LinearResampler and ChannelMixer, which SOL-P8 already covers). Either this row
  described a surface that was removed, or it was written by analogy to the sws half and never
  checked. DO NOT schedule it until someone confirms what it was meant to name. Home: the next
  KiteCodec window, as a five-minute reading rather than work.

C-reduction (the charter, owner-scheduled, earliest after S4):
- SOL-C1 [V] STILL OPEN, and now with a number: 213 exported helper functions across eleven C
  sources (helpers_codec, codecpar, error, filter, format, frame, hwaccel, packet, playback, stream,
  and kitecodec_abi), measured 2026-08-18. Replace the one-line helper C (packet, codecpar, stream,
  error, trivial frame and codec, most of format and playback) with direct cinterop on
  Kotlin/Native. KEEP: the JNI adapter, the ABI/identity probe, the get_format callback, FFmpeg
  itself, and the whole real-time C island. The goal is no redundant C, not no C.
- SOL-C2 [V] STILL OPEN, re-verified 2026-08-18: `kiteplayer-rt/native/src/kite_rt_coreaudio.c`
  is present and carries the setup. Move non-real-time CoreAudio setup, session policy,
  route/interruption handling, capability queries and error mapping to Kotlin; unsupported-platform
  C stubs become expect/actual. Home: S3.
- SOL-C3 [V] STILL OPEN, re-verified 2026-08-18: helpers_filter.c still composes into fixed
  `char args[512]` and `char layout_str[128]` buffers. Composition moves to common Kotlin, retiring
  them (P0 closed the overflow; the composition itself is still C). Home: with SOL-C1.

Kotlin modernization (hygiene, no schedule, no syntax churn before ownership work):
- SOL-K1 [V] STILL OPEN, re-verified 2026-08-18 at kitecodec-core/build.gradle.kts:74:
  -Xcontext-parameters is still passed and is redundant on Kotlin 2.4. Drop at the next window.
- SOL-K2 [C] The adopted guidance: context parameters only for the worker helper cluster and a
  codec execution context; higher-value moves are sealed transactional outcomes, structured
  finalizer scopes, ownership-aware lease APIs, inline plane iteration, checked-size helpers
  and resource ledgers. Not a stage; a style the stages apply.

Build and publication:
- SOL-B1: CLOSED by phase W (KiteCodec e7a8868). The three stale goldens now pin the wide
  read-side class policy, the VideoToolbox hwaccel pins and the MediaCodec AV1 decoder, and two
  new goldens pin the desktop cross flags. 59 tests green, so the suite gates again.
- SOL-B2: CLOSED, by observation at phase W entry rather than by a fix: StaticLinkFlags carries
  -llzma today and macosArm64Test links and runs, 113 tests green. Two tests it had been hiding
  since 2e60bf3 were red and are fixed in KiteCodec 2a087b4.
- SOL-B3: CLOSED, verified by running it 2026-08-18 rather than by reading the diff, which is what
  the row asked for: `-Pkitecodec.hostTargetsOnly=true` configures :kitecodec-core cleanly with no
  compileSdk failure. 3f0f1e3's conditional plugin did close it; nobody had re-run it since.
- SOL-B4 [C] Vendored archives carry a macOS 26 deployment version while Kotlin/Native links
  macOS 12 (and the shim uses 11); pin one deployment floor in BuildFFmpegTask. Same window.
- SOL-B5 [V] STILL OPEN, re-verified 2026-08-18: LinkKiteCodecJniTask's ABI recipes name arm64-v8a
  and x86_64 only. The same two-ABI limit now also governs the libass JNI adapter, which took its
  ABI list from the same reasoning, so the owner decision covers both. Owner decision required:
  either armeabi-v7a is a target (add it) or it is not (record the refusal in 17.6). Home: S5 entry.
- SOL-B6 [C] The twin repos are not one atomic graph: mavenLocal-first resolution can shadow
  the sibling checkout with stale artifacts; the audit proposes a composite build or shared
  root plus cross-repo CI. Home: S5, with S7's CI.
- SOL-B7 [C] Both builds emit deprecated Gradle API warnings that become Gradle 10 breaks.
  Home: S5.
- SOL-B8 [C] Remote publication still lacks the ordinary JVM and Android artifacts (the
  portable placeholders exist locally since 3f0f1e3). Home: S5, windows 4.
- AGW-1 [V] The Android GPU path's physical qualification is owed in full: before/after
  benchmark against ANDROID_GPU_WORK.baseline.txt, rapid-seek and lifecycle checks, the
  30-minute graphics-memory soak, the perf gate's physical profile, and wider-profile fixtures
  on real silicon including a Main10-capable device. Home: the owner device session, first
  hardware available.

Test debt (the audit's missing-regression list, adopted where each row lands): cached
Frame.info after close, filter-callback frame retention, concurrent JNI op and close, 32-bit
near-boundary ring allocation under ASan, failed quiescence during renderer replacement,
cancellation after partial audio submission, device-sleep clock epochs, the 24-hour AudioTrack
wrap simulation, simultaneous subtitle images, alpha golden tests, non-planar and odd-sized
Metal frames, failed CoreAudio shutdown with a live callback, attached-picture-first media,
negative start times, foreign StreamInfo, decoder output diverging from codec parameters,
empty-output MediaSink finalization, midstream format changes, and secure-protocol link
smokes. Each stage's expansion names the ones it owes.
**Sampled 2026-08-18 rather than assumed:** the list is PARTLY addressed and has never been
reconciled. Test files matching the wrap, quiescence, ASan and attached-picture rows exist; nothing
in either repository matches "negative start times" or "midstream format changes". Whoever next
owns this list should walk all nineteen and strike the ones already written, because carrying a
closed row costs the same attention as a real one.

**Register addition 2026-08-18 (the parity sweep: every shipped archive read with llvm-nm rather
than trusted from its configure record).** Closed the same day: KC-AV1SW above; the iOS assembly
and AudioToolbox gaps; libass on every Kotlin/Native target plus Android; https on the web. Opened
by the same sweep and NOT yet answered:

- PAR-1 [V] mingw-x64's libavcodec.a carries EIGHTEEN d3d11va/d3d11va2/dxva2 hwaccels, compiled
  because the mingw profile never passed --disable-autodetect, while decision W-D4 and
  PlatformDecoderSelection.mingw.kt both state that no D3D11VA hwaccel is compiled. The binary and
  the decision contradict each other. Owner decision: plumb them (KiteCodec needs a hw device
  context and a frame download for Windows, which is feature work and NOT the one-line route the
  Apple axis took) or add --disable-autodetect so the binary matches the recorded decision.
- PAR-2 [V] Linux x64 and arm64 compile ZERO hwaccels, so those trees decode everything on the CPU.
  Honest and recorded, unlike PAR-1. VAAPI is the candidate. Home: with PAR-1's decision.
- PAR-3 [V] android-x64 still builds with --disable-asm, so the emulator ABI has no SSE/AVX in
  libavcodec (0 SIMD symbols against android-arm64's 1363 NEON). Emulator-only, hence low priority,
  but it is the same omission the iOS arm64 targets carried until 2026-08-17.
- PAR-4 [V] The wasm profile enables the matroska and webm demuxers while enabling NEITHER the opus
  NOR the vorbis decoder, so a .webm on the web opens, decodes video, and has no audio decoder for
  its audio stream. Both are FFmpeg-native and cost no external library. This is the cheapest real
  gap the sweep found.
- PAR-5 [V] :kiteplayer-output declares linuxX64, linuxArm64 and mingwX64 targets but has NO
  linuxMain or mingwMain source set, so those three compile the common file alone: no audio sink,
  no renderer, no clock. Desktop playback rides the jvm target instead. RECOMMENDED CLOSE: record
  it as a decision (native desktop targets are engine-only; consumers bring output through the SPI)
  rather than building ALSA and WASAPI backends nobody has asked for.
- PAR-6 [V] AV1 hardware decode has never been positively proven. The Apple route is wired and the
  hwaccel is compiled, but this machine is an M2 with no AV1 silicon, so every run here proves only
  the refusal-and-fallback path. Positive proof needs an A17 Pro / M3 or newer machine. Owner
  device fare, like AGW-1.
- PAR-7 [V] The `fd:` protocol's contract stays spooky even after F-FD1's fix: rewinding before
  every open MUTATES the caller's descriptor (a dup shares the offset), an unseekable descriptor
  degrades silently to the streamed case, and the descriptor's lifetime is the caller's problem.
  Candidate close: retire `fd:` in favour of a positional-read MediaIo (pread / FileChannel.read at
  an offset), which removes the shared offset entirely and makes the reopen safe by construction
  rather than by rewind. Plain files keep FFmpeg's own file protocol, which has none of this.

**Register addition 2026-08-16 (from the mpv dependency study, 17.12):**
- KP-TLS: CLOSED by the network surge (same day). VERIFIED by configure evidence rather than a
  device run: both phone trees' protocol lists are pinned to file/fd/pipe/data/http/tcp, no
  https, no TLS entry, so an https URL through FFmpeg's own protocol path cannot open, ever.
  The CLOSE is the design, not a vendored backend: the engine's MediaIoResolver hands http and
  https URIs to kiteplayer-network's Ktor reader, whose platform engine (OkHttp on Android and
  the JVM, NSURLSession on Apple) terminates TLS in the OS, exactly as D-7's mbedtls/curl
  verdict demanded. Proven end to end over local http through real FFmpeg (the https path is
  the same code with the engine's TLS beneath it); a live-https device run remains the owner's
  ordinary device-session fare, not a blocker.
  **Extended to the web 2026-08-18** (commit 7793dd0): :kiteplayer-network gained a wasmJs target
  and Ktor's js engine, so `fetch` terminates TLS in the BROWSER and the web needs no more TLS code
  than any other target. Adding it forced a test split, recorded because it cost real coverage
  thinking: `runBlocking` does not exist where the only thread is the event loop and ktor-server
  publishes no wasm artifact, so the server-backed tests moved to a serverBackedTest source set
  while the DASH manifest parser stayed common and is now covered on the web too. Still without
  https, and RECOMMENDED TO STAY SO: native linuxX64, linuxArm64 and mingwX64. Linux has no OS HTTP
  or TLS API to delegate to, so the only engines are curl (rejected by D-7) or one carrying its own
  crypto (the same objection); those three targets also have no output backend at all (PAR-5), and
  the desktop story runs on the JVM, which has had https since M1.

### 17.11.a The pure-Compose audit (M4's exit, run 2026-08-17)

The feature table M4 demanded: KiteVideo (the Compose-true renderer) against the interop
wrapper (platform view hosting the native renderers), per user-visible capability, after the
M4 fixes landed. The rule was "KiteVideo loses nowhere"; one loss was found DURING the audit
and closed in the same surge (the HDR row).

| Capability | Interop (native renderers) | KiteVideo | Verdict |
|---|---|---|---|
| Scale modes (Fit/Fill/Stretch) | yes | yes, draw-phase | even |
| Picture controls (eq) | yes, incl. paused redraw (R1) | yes; state change invalidates draw, paused included | even |
| Framing (zoom/pan/aspect) | yes | yes, clipped draw | even |
| Subtitle overlays | yes, paused redraw (R1) | yes; audio-only and pre-first-frame too (R2) | even |
| HDR tone mapping | Metal shader (M3) | CPU law on software frames; the hardware readback now tone-maps for display (closed 2026-08-17) | even |
| Rotation | yes | yes, draw-phase rotate | even |
| Compose modifiers on the video itself (clip, alpha, shared elements) | no; a platform view hole | yes | KiteVideo wins |
| Zero-copy hardware path | CVPixelBuffer to CAMetalLayer | Android API 31+ HardwareBuffer images; Apple zero-copy rides S2.d | even, per-platform |
| Sustained fullscreen power | display controller presents; GPU idles | GPU lightly awake per frame | interop wins, RECORDED (17.9's honest cost, unchanged by design) |

The one deliberate non-goal stands as designed: sustained fullscreen belongs to the baseline
wrapper (D-6 keeps both paths for exactly this reason), so it is not a loss against the
audit's rule but the division of labour the register chose. KiteVideo loses nowhere else.

### 17.11.b The deep audit and its fix surge (2026-08-17)

Born from a 69-agent, 15-dimension code-only audit run against fa02a18 (comments, KDoc and
this file treated as hearsay; every non-trivial finding adversarially verified) followed by a
single-threaded fix surge that answered every confirmed row end to end. The falsification rule
held: every host-observable fix is pinned by a test proven RED first or falsified in place by
neutering the fix; the pins live in EngineAuditRegressionTest, ExternalSubtitleTest,
ColorPolicyTest, FilterAttachmentTest, DashManifestParserTest, DashRefusalTest,
AudioTrackSinkTest, AndroidSurfaceOverlayTest and AppKitVideoRendererTest.

**CLOSED, with their commits.**

| Row | Defect | Fix commit |
| --- | --- | --- |
| F-LOOP1 | LoopMode.One seeked an unseekable source and failed the session | 4620f93 |
| F-SEEK1 | a pending seek survived runOpen and ran against the new media | 4620f93 |
| F-EOS1 | the end-of-stream ring wait was unbounded when the device stopped pulling | 4620f93 |
| F-SP1 | SetSpeed wrote both pipelines before deciding to refuse | 4620f93 |
| F-API1 | refusals of fire-and-forget members were invisible; PlaybackWarning.CommandRefused | 4620f93 |
| F-MIX1 | the SOL-P2 alias keyed on isPassThrough and aliased unequal channel counts | 4620f93 |
| F-GAIN1 | rebuiltFor lost the gain ramp position, un-muting a rebuild for one ramp | 4620f93 |
| F-TS1 | a rebuild kept the scaled-axis base while the emitted counter restarted | 4620f93 |
| F-QSC1 | quiesce trusted a parkedNow the worker was already leaving | 4620f93 |
| F-LANE1 | the session lane made blocking joins on the computation pool | 4620f93 |
| F-CFG1 | BufferPolicy accepted videoFrameQueue = 1 and crashed the first open | 4620f93 |
| F-JOB1 | one completed raster Job per cue edge grew session.jobs for the whole film | 4620f93 |
| F-EXT1 | addExternalSubtitle minted an id a declared track already owned | 4620f93 |
| F-EXT2 | external ASS files were labelled external/subrip | 4620f93 |
| F-HDR1 | the native SoftwareConverter skipped the HDR-to-SDR hook the jvm path runs | 035c935 |
| F-FLT1 | a never-built filter graph held isDrained false for ever | 035c935 |
| F-FACT1 | KiteCodecSourceFactory dropped headers, options, formatHint and videoFilter | 035c935 |
| F-TSTL1 | the tone-map test computed its expected value with the production functions | 035c935 |
| F-DASH1 | SegmentTimeline r=-1 expanded to zero segments | 16bc094 |
| F-DASH2 | xs:duration year, month and week components killed the manifest | 16bc094 |
| F-DASH3 | multi-period manifests silently played period one | 16bc094 |
| F-XML1 | numeric character references above the basic plane truncated through toChar | 16bc094 |
| F-NET1 | KtorMediaIoResolver leaked the HttpClient it lazily created | 16bc094 |
| F-AUD1 | a short positive write return re-entered the blocking write past the signal | 55a0d60 |
| F-AUD2 | a pause mid-write dropped the interrupted block's ring-consumed tail | 55a0d60 |
| F-AUD3 | drain left submittedFrames and the wrap state stale | 55a0d60 |
| F-AUD4 | the timestamp wrap state was mutated without the head's lock | 55a0d60 |
| F-ALPHA1 | the cue alpha contract said straight while producers premultiplied; three consumers premultiplied again (canvas target, overlay view, Metal blend) | 55a0d60 |
| F-DDRW1 | a delegated overlay was also burned into the video canvas | 55a0d60 |
| F-ROT1 | the burned overlay ignored the picture's quarter turn | 55a0d60 |
| F-POS1 | an authored \pos hung the safe-width layout's top-left off the anchor | 55a0d60 |
| F-CFL1 | AppleSubtitleRasterizer leaked every Create-rule object per cue | 3078eb8 |
| F-DRW1 | an unwrappable picture was refused after the drawable was acquired | 3078eb8 |
| F-RDW1 | the redraw flag's else-arm clobbered a racing request, three renderers | 3078eb8 |
| F-RDWT1 | the retained-picture redraw had no pin; now falsified by neutering | 3078eb8 |
| F-DRAW1 | requestProofFrame wrote draw-observed state inside the draw phase | 2b681f3 |
| F-CLS1 | setOverlay could publish after close's final null and pin dead cues | 2b681f3 |
| F-DBF1 | device_buffer_frames was write-only; now in kprt_sink_stats, read live | e7cbc57 |
| F-CTRL1 | 13 of 16 negative controls in source-discipline.sh had drifted off their lines; all sixteen re-anchored on unique text and re-proven able to fail | e7cbc57 |
| F-WRN1 | FrameDropping, AudioUnderrun and AudioDeviceChanged were documented types wired to nothing; all three now emit (stats-pass edges and the new sink-event collection) | this commit |
| F-CFG2 | dead knobs: WorkerContext deleted; lookahead and the cache back-window KDocs now say what the code does; setSpeed and the facade KDocs match the refusal law | 4620f93 |
| F-PLAY1 | play at Ended was a no-op: the intent flag was already true after a natural end, so the button did nothing and the player sat in Ended for ever. Play at the end now restarts from zero on a seekable source, mpv's law; red-proven by the EngineAuditRegressionTest pin. The owner's same report measured the Ended-seek revival at 150 to 200 ms virtual in the engine, so the device slowness was the consumer's Precise bar seeks paying a whole GOP of decode-forward; Synkplay moved to KeyframeThenRefine | post-audit, owner report |
| F-FD1 | an fd: item could not be opened twice: FFmpeg's fd protocol dups but never rewinds, a dup shares the file offset, and the track-change rebuild reopens the same MediaItem, so the second open probed mid-file bytes and died AVERROR_INVALIDDATA. Both backend doors now rewind the descriptor before every open (Android through a dup's shared offset, native through lseek); red-proven by FdReopenTest with the exact device error | post-audit, found by the owner's device run |

**Register corrections from the audit's refutation pass.** Eleven findings were killed by
adversarial verification and are NOT rows above; the strongest refutations are recorded here
because they name guards worth keeping: BlockingMediaIo's runBlocking is safe because close
never queues behind the demux lane; the ratchet tasks ARE invoked by their documented
commands; the shipped-object audit in render-audit.sh covers what flag parity alone cannot.

**OPEN, honestly.**

- F-ABI1 [V] STILL OPEN, re-verified 2026-08-18: the `api/` directories carry a `jvm` dump and a
  `.klib.api` and no Android dump at all, so KitePlayerView and the other androidMain public APIs
  have nothing to disagree with. Needs either a KGP release that supports it or a hand-rolled
  classes.jar signature check. Owner decision on the mechanism.
- F-COV1: REDUCED 2026-08-18 to SIX of twenty. wasmJs now executes (kiteplayer-network runs 12
  tests on wasmJs/node, and the DASH manifest parser is covered there for the first time), and a
  real Android DEVICE surface ran for the first time (kiteplayer-libass, 2 of 2 on the Pixelu16KB
  emulator). Still owed: linux and mingw need their hosts; watchos needs a simulator run; `js`
  stays a deliberate placeholder per 17.14. NEWLY BLOCKED, not merely reachable: tvos cannot run on
  this machine at all, `:kiteplayer-core:tvosSimulatorArm64Test` fails with "Xcode does not support
  simulator tests for tvos_simulator_arm64" because the tvOS simulator SDK is absent, so
  `:kiteplayer-core:allTests` cannot pass here and host-only runs must name their targets.
- The device-only halves of F-ALPHA1, F-ROT1 and F-POS1 (real pixels on a real screen) ride
  the owner's existing emulator checklist, which already carries the three manual checks from
  the M4 surge.

### 17.14 The S6 expansion, decision complete

Authored 2026-08-17 by Opus 5 at the owner's direction, entering stage S6 (17.2: IT PLAYS ON THE
WEB). Written against the tree at KitePlayer 11a6167 and KiteCodec 3da948b. The spike this stage
was gated on RAN on 2026-08-17 and PASSED; its report is `docs/spikes/2026-08-17-web-spike.md` and
its verdict, numbers and 178-to-272-hour cost are in the section 14 W.9 entry. This subsection does
NOT re-argue the spike. It converts the spike's dependency-ordered cost table into register items
that carry Where, Problem, Fix, Sub-phase and Test, and it records the decisions the spike
deliberately left open.

**The entry facts, measured on 2026-08-17 against the tree above, not assumed.**

1. Four modules already declare `wasmJs` and publish real klibs: `:kiteplayer-core`,
   `:kiteplayer-subtitles`, `:kiteplayer-mobile` and `:kiteplayer-compose-interop`. They compile
   from `unsupportedMain`, so the surface is honest and empty rather than absent.
2. `:kiteplayer-compose-video`, which is the ONLY Compose rendering story on web because no
   interop view can exist there, declares `iosArm64`, `iosSimulatorArm64`, `jvm` and `android` and
   nothing else. It has no web target and no macOS target either.
3. `:kiteplayer-ffmpeg` and `:kiteplayer-output` declare no web target, which is why items X-07
   through X-12 exist at all.
4. The toolchain is present and is NOT the risk. `emcc 6.0.6-git` resolves at
   `/opt/homebrew/bin/emcc` and `node v26.7.0` runs the spike's own benchmarks. Compose
   Multiplatform is `1.12.0-rc01`, which carries a wasmJs target.
5. **W-19's row parallelism cannot follow the engine to the web, and the code says so in a comment
   that is about to become false.** `Conversions.kt:128` states "No expect/actual: every target
   this module compiles for has a multi-threaded `Dispatchers.Default`, and the module already
   calls `runBlocking` on this side of the code for the same kind of reason." Both halves of that
   premise fail on wasmJs: `runBlocking` does not exist there, and `Dispatchers.Default` is one
   event loop. The comment is true for every target the module compiles for TODAY, which is
   exactly why it must be re-decided by X-09 rather than discovered by a compile error. The
   measured consequence is the whole reason X-01 goes first: desktop pays about 2.1 ms per 1080p
   frame for the conversion only because four cores share it, and the pre-W-19 single-threaded
   number on the same machine was 6.33 ms.
6. `signature-baseline.txt` carries 213 normalized `KC_API` declaration records and is already
   gated, which is what makes X-05's generator a review problem instead of an authorship problem.

**Decisions taken for this stage, and why. Executor judgement calls under the owner's standing
17.11 rule that homes and order are proposals; each is recorded so the owner can reverse it.**

- **S6-D1, the KV-6 draw probe goes first and is a STOP GATE, not a formality.** The spike's own
  verdict says the codec half passed and the renderer half is unmeasured, and 17.9's KV-6 line says
  the same. Decode is proven at 6.1x real time; the draw path is proven at nothing. Every item
  after X-01 is wasted if the draw cannot hold 1080p30, so X-01 is built, measured and REPORTED
  before any binding work is committed to. If it fails, web ships engine-only and this expansion
  says so rather than being quietly re-scoped.
- **S6-D2, the probe measures the SINGLE-THREADED conversion, because that is what the default web
  artifact gets.** Entry fact 5. Measuring the parallel path would flatter the number by a factor
  the web cannot buy. The threaded artifact is an optional second build (spike order item 2) and
  is not what v1 ships.
- **S6-D3, the default artifact is single-threaded, no SIMD, no cross-origin isolation, everything
  in one Worker.** Straight from the spike's build order, and the reason is deployment, not
  performance: threads need COOP and COEP on whoever embeds the player, and the spike proved in a
  real browser that the failure mode without those headers is a HANG rather than an error. A
  player that hangs on an embedder's site is worse than a player that is 3x slower.
- **S6-D4, the two binding questions the spike flagged are deferred to X-05's own decision point,
  not answered here.** Whether to bind a playback-only subset instead of all 198 entry points, and
  whether to generate the wrappers from `signature-baseline.txt`, together move 40 to 60 hours.
  They are also moot if X-01 fails. Deciding them now would be deciding them blind, and 18.3 rule 6
  forbids folding a design act into an execution act. X-05 carries them as its first step.
- **S6-D5, no repository file is written by X-01 outside a probe module that is allowed to be
  thrown away.** The spike wrote to neither repository. The probe is one step less throwaway than
  that because a number nobody can re-derive is a number nobody can trust, so it lands as a real
  module whose only job is the measurement, exactly as `:kiteplayer-sample-desktop` did for KV-5.

- **S6-D6, second-seat review, 2026-08-17, authored by Fable 5 at the owner's direction.** The
  expansion above is execution-sound and its measurements hold. It inherits one narrowness from the
  spike it was built on: it treats the web as a place to REBUILD the native stack and never weighs
  what the browser already ships. Three corrections, each anchored to the item it changes, none of
  which discards work already landed (X-01 through X-04 stand as they are).
  1. **WebCodecs is absent from this expansion, and the project already promised it twice.** The S7
     support matrix names a "WebCodecs/WebAudio/MSE backend" as the web capability profile, and
     `kiteplayer-ffmpeg/build.gradle.kts` says in its own module doc that the engine's four
     interfaces exist so "WebCodecs on the web" can replace FFmpeg without the engine noticing.
     Current Chrome, Edge, Safari and Firefox all ship it, with hardware decode for h264 and most
     of the matrix's video codecs. It is the largest single lever this stage has: 4K measured 1.0x
     in SOFTWARE and was declared a non-goal on that number alone; hardware decode reopens it for
     nothing. It also shrinks the download, because hevc is 20.7% of the gzipped wasm module and
     need not ship where the platform decodes it. Decision: X-07 and X-09 proceed as written, as
     the always-available software floor. A register item X-15 must be authored, as its own act per
     18.3 rule 6, before this stage claims decode parity: demux stays FFmpeg, decode goes WebCodecs
     where the browser and codec allow, wasm decode is the fallback, all behind the decoder SPI the
     engine already has. If X-15 is instead rejected, it is rejected with measurements, the way
     W-14 and W-15 were. MSE with a `video` element is considered once here and rejected: it cannot
     serve the 17.5 matrix (mkv, the subtitle formats) and it surrenders the frame-level control
     the engine's contract is built on.
  2. **X-11's FIRST candidate is not a Skiko path.** X-01's own numbers already contain the fast
     route: `putImageData` moves the same 8.3 MB in 1.4 ms, and after X-09 the converted RGBA lives
     in emscripten linear memory, which IS a JS-visible ArrayBuffer. The frame never needs to touch
     the Kotlin heap. So X-11 tier one is a browser canvas (2d first, texture upload if 2d falls
     short) layered with the Compose canvas, Compose keeping the controls; the expansion's premise
     that "no interop view can exist on wasm" is true for platform views and false for DOM
     layering. Tier two, and only after tier one plays, is the Compose-true single-surface path
     that D-6 promises, where clip, alpha and rotation apply to the video pixels; that is what
     "KV-6 proper" measures and it stays the target. What tier one costs is exactly D-6's promise,
     and the register must say so on the item rather than let the layered canvas quietly become the
     end state.
  3. **X-05's cost analogy overstates wasm glue.** The JNI adapter is 13 lines per function because
     JNI demands env ceremony, string conversion and reference management. A `KC_API` function is
     already extern C with a fixed ABI: emscripten exports it directly, and the generator's real
     output is Kotlin/wasmJs externals plus a thin JS shim, with hand-written C reserved for the
     callback shapes (the AVIO bridge above all), string returns and struct-outs. The GENERATE
     decision in X-05 stands. Treat 40 to 60 hours as the ceiling, not the plan; the direct-export
     shape lands materially under it.

- **S6-D7, second-seat review of the engine-wiring day, 2026-08-17, authored by Fable 5 at the
  owner's direction.** Every finding below was verified by reading the committed tree and the
  engine's contracts, not by trusting the day's log. The wiring itself is sound; what follows is
  ranked most severe first.
  1. **SHOWSTOPPER: `SilentPacedAudioSink` never drives the render callback, so the engine's clock
     never starts.** Traced, not asserted: `AudioPath.kt` hands the sink an `AudioRenderCallback`
     closure that reads a `KotlinAudioRing`, and the ring's `consumed` counter and
     `anchorPtsUs`/`anchorNanos` pair, which is what the core anchors its clock from, advance ONLY
     when the sink invokes that callback. `DesktopAudioSink` has a loop calling
     `callback.onRender(...)` per block; the silent sink stores `render` in `open` and never calls
     it. Consequence: the ring fills, backpressure stalls the decoder, and audio-mastered playback
     hangs at position zero. The sink's own KDoc claimed "video plays on the web, at the right
     rate, with A/V sync logic running"; that sentence was false as written and is corrected in the
     same commit as this note. Its `pacedFrames()` accounting is doubly dead: nothing reads it, and
     it discards the accumulated `framesConsumed` base on resume, so even as a helper it loses
     position across a pause. The fix X-10's first increment actually needs: a pump, a coroutine
     that calls `onRender` for `deviceBufferFrames`-sized blocks on a wall-clock schedule and
     writes the result nowhere. That is the smallest thing that makes the claim true.
  2. **`MediaSource.open(io, options)` on wasmJs silently drops the options, and then
     `unusedOpenOptions` answers empty, which asserts they were all consumed.** The `openInputIo`
     `@JsFun` passes 0 for keys, values and count. Every other backend forwards open options and
     reports the genuinely unused ones. Either forward them (the C entry point already takes them)
     or return them ALL from `unusedOpenOptions` until then; answering empty is the one wrong
     choice, because a caller probing for option support reads it as full support.
  3. **`StreamInfo.video.frameRate` is hardcoded `0/1` and `sampleAspectRatio` `1/1` on wasmJs**,
     unrecorded. `ffkmp_stream_avg_frame_rate` and `ffkmp_codecpar_sample_aspect_ratio` both exist
     in the binding and cost two out-parameter reads, the same shape `readTimeBase` already does.
     A `0/1` frame rate feeds every pacing heuristic above; fill them or record the bound.
  4. **X-04's shared handle table is compiled into the wasm archive and WIRED TO NOTHING.** Zero
     `kj_handle_*` references in the wasmJs backend: Kotlin holds raw `Int` pointers with
     per-object `alive()` guards instead. The guards are real protection, but the register reads as
     if the generation-tagged table protects the web consumer, and today it protects only the
     probe that tested it. Either route the backend's pointers through the table or amend X-04's
     claim; the current state is the recorded intent and the tree disagreeing.
  5. **`BlockingMediaIo.wasmJs` can leave a zombie coroutine.** `runWithoutSuspending` throws when
     the body suspends, but the suspended body keeps running and its eventual resume writes into
     the caller's `ByteArray` (read) or moves the source position (seek) AFTER the throw. For the
     memory-backed sources this backend accepts the case is unreachable; the note belongs on the
     function so the Worker work does not inherit it unknowingly.
  6. **Two smalls.** `webIdentity()` re-reads the whole 2,176-byte report on every `availability`
     touch and `createOrNull` touches it at least twice; harmless, wasteful, cache-per-load would
     do. And `bypassedStatus` is derived as the post-bypass status, which is 0 in exactly the case
     `bypassed` is true, so the field carries no information; the C report has no original-status
     field, and the honest options are deriving it differently or documenting that it cannot be
     known here.

- **S6-D7 RESOLVED, 2026-08-17, all six by the executor Fable reviewed.** Outcome per finding, in
  the reviewer's order.
  1. **The sink now pumps, and the test proves the pump.** `SilentPacedAudioSink` runs a coroutine
     calling `onRender` for one `deviceBufferFrames` block per block-duration and discards the
     samples through a bounds-checking `DiscardBuffer`. The review was right that playback would
     have hung: the ring's `consumed` counter and clock anchor move only on that call. The scope
     and the clock became CONSTRUCTOR PARAMETERS while fixing it, which is what made the tests
     exact instead of timing-dependent: `SilentPacedAudioSinkTest` drives it with a `TestScope` and
     a clock reading virtual time, so block counts and deadlines are arithmetic. `AudioPath.kt`
     makes the same choice for the same reason. Falsification: restoring the empty pump fails 3 of
     the 4 new tests; the fix passes all of them.
  2. **Open options are forwarded and the unused set is real.** `CStringArrays` stages the two
     `char *` arrays in codec memory, `ffkmp_fmt_open_input_io` takes them, and the surviving
     non-NULL keys become `unusedOpenOptions`. The C side NULLs each consumed entry IN PLACE, so
     the leftovers are read before the arrays are freed, which is now stated on the class.
  3. **`frameRate` and `sampleAspectRatio` are read from the stream.** Both were hardcoded. The
     three out-parameter pairs now share one `readRational` helper with a per-caller fallback, so
     an undeclared rate is 0/1, an undeclared aspect 1/1 and an undeclared time base microseconds.
  4. **The review's mildest-looking finding was the most serious, and it was a live defect rather
     than a documentation mismatch.** Chasing "the handle table protects nothing here" found that
     `PacketReader` and `StreamDecoder` hold the `AVFormatContext` as a raw address while
     `MediaSource.close()` frees it: a reader outliving its source read released memory and would
     have answered plausible nonsense. The web backend now owes the same guarantee the table gives
     the JNI side and pays it in Kotlin: one `SourceLifetime` the container clears and every child
     checks, with a typed error naming what outlived what. X-04's claim stands corrected here
     rather than in prose: the table guards the JNI binding and its probe, and the web backend
     guards itself.
  5. **The zombie-coroutine limit is written on the function that has it.** A suspended body is not
     cancelled and its resume can still write into the caller's array after the throw. Unreachable
     for every source this backend accepts, and named so X-08's Worker does not inherit it blind.
  6. **Both smalls.** `FFmpeg.identity` caches per loaded module, keyed on the module so a reload
     cannot serve a stale answer. `bypassedStatus` is 0 with a comment saying it is a limit of
     `kc_ffmpeg_report`, which carries no original status, rather than a derived value that looked
     populated while carrying nothing.

**The register.**

#### X-01. The wasm draw cost is the one number S6 is gated on, and nothing has measured it
- Where: a new `:kiteplayer-sample-web` module; `kiteplayer-compose-video` for the shape being
  imitated; `Conversions.kt` for the conversion whose single-threaded cost is half the answer.
- Problem: the spike proved decode at 6.1x real time and measured the draw path at nothing,
  because no wasm renderer exists to measure. Desktop's KV-5 measurement is not transferable: it
  ran on a JVM with a JIT and four cores sharing the conversion, and web has one thread and a
  different Skia binding. The gap is not small enough to reason about, so it is measured.
- Fix, decided: a Compose for Web page that holds a synthetic 1080p yuv420p frame, converts it to
  RGBA with the SAME arithmetic `Conversions.kt` uses on one thread, builds a Skia image from the
  result and draws it, once per frame, reporting mean and p95 milliseconds over a warmed run. Two
  numbers reported separately, conversion and draw, because they have different fixes: a slow
  conversion moves into the wasm module beside FFmpeg, and a slow draw is a renderer problem.
- Why not the simpler thing: measuring only the draw would answer half the question and would
  answer the cheap half. The conversion is what desktop measured at 6.33 ms single-threaded, and
  33 ms is the whole 30 fps budget.
- Sub-phase: X.1. Test: the measurement itself, run in a real browser, with the numbers recorded
  in the module's own MEASUREMENTS.md and in the section 14 entry. Falsification: a run whose
  reported frame count does not match the frames actually drawn is rejected, the same trap KV-5's
  `graphicsLayer` arm already paid for once.
- Honest bound: a synthetic frame, not a decoded one. Real decoded frames arrive only after X-07,
  and the conversion cost depends on pixel VALUES not at all, which is why the synthetic frame is
  honest here and would not be for a decode measurement.
  Result, measured 2026-08-17 in Chromium, full report in `kiteplayer-sample-web/MEASUREMENTS.md`:
  **the naive path FAILS by 5 to 7 times, and the platform is not the reason.** Converting 1080p on
  one thread costs 50 to 87 ms and building the Skia image from the resulting Kotlin `ByteArray`
  costs 107 to 153 ms, against a 33.3 ms budget. Two measurements say where the fault is not.
  Drawing an ALREADY-RESIDENT 1080p image blits in 0.17 ms, so Compose is able to draw video here.
  And the browser's own `putImageData` moves the same 8.3 MB in 1.4 ms, so the machine is able to
  move the bytes. A size ladder settles the mechanism rather than guessing it: the raster build
  costs a flat 13 to 19 ns per byte from 2 MB upward, which is a bulk copy at 55 to 85 MB/s, three
  orders of magnitude off `memcpy`, and that is the crossing between the Kotlin GC heap where a
  `ByteArray` lives and Skia's own linear memory. A third number is worth carrying forward on its
  own: the Kotlin per-pixel loop is about 5x SLOWER than a line-for-line JavaScript mirror measured
  in the same page, 50 to 87 ms against 15.6 ms.
  **Verdict: the stage CONTINUES, and this is an executor judgement call the owner can reverse.**
  S6-D1 said X-01 stops the stage if it fails, and the naive path did fail. It does not stop
  because the gate's question was whether the web can draw 1080p30 at all, and the answer measured
  here is that it can, through a path the desktop renderer does not use. What the failure buys is
  that two later items are now CONSTRAINED instead of open: X-09 may not convert with a Kotlin
  per-pixel loop, and X-11 may not build a Skia raster from a Kotlin `ByteArray` per frame.
  **Two things this did NOT measure, stated so no reader infers them.** No end-to-end frame rate
  exists: the frame loop needs `requestAnimationFrame` and the browser pane used here is hidden, so
  the probe reports `NOT MEASURED, the frame clock never ticked` rather than a number, and the
  totals above are summed parts that exclude the compositor. And no fix is proven: that a Skiko
  path avoiding the heap crossing EXISTS is the obvious next question, is not answered here, and is
  X-11's first job rather than a settled plan.

#### X-02. FFmpeg has no wasm build task, only a proven shell recipe
- Where: `buildSrc/src/main/kotlin/BuildFFmpegTask.kt`; the spike's `build-lean.sh`.
- Problem: the spike built four full FFmpeg trees for wasm in 5 minutes 20 seconds, but it did it
  with a shell script in a scratch directory. konan has no wasm target, so there is no
  `TargetTriple` entry to extend and the existing task's target plumbing does not reach.
- Fix, decided: a new Gradle task rather than a `TargetTriple` row. The configure shape is already
  proven and is not the work; the work is output layout, provenance evidence, up-to-date checking
  and the two n8.0 corrections the spike found the hard way, that `--disable-postproc` does not
  exist on n8.0 and that `--disable-asm` silently kills SIMD.
- Sub-phase: X.2. Test: a built tree whose configure banner is captured, plus the size numbers the
  spike recorded reproduced within tolerance.
  Result, 2026-08-17: `:kitecodec-core:buildFFmpegForWasm` (plus `...Simd` and `...Mt`) builds all
  six archives into `native-libs/lgpl/wasm32`, and the objects are confirmed
  `WebAssembly (wasm) binary module version 0x1` rather than host code. Sizes reproduce the spike
  within a few hundred bytes per archive.
  **One correction the spike's recipe needed, found before the build rather than at link time.**
  The spike disabled avfilter, which its bare harness never used. `libkitecodec.a` DOES use it:
  `helpers_filter.c` looks up `abuffer`, `abuffersink`, `anull`, `buffer` and `buffersink` BY NAME
  and calls `avfilter_graph_parse_ptr`. A build without them links and fails at the first filter
  call. The web tree therefore enables avfilter plus those five and four negotiation filters, which
  costs 220 KB of archive. `libavfilter.a` is present and `av_buffersink_get_frame` resolves in it.
  **Also corrected: the scratch root.** The sibling task builds in the system temp dir because this
  repository lives under a `#Kite` directory and FFmpeg's configure cannot handle a `#` anywhere in
  its path. The first draft used Gradle's `temporaryDir`, which is INSIDE the project, and the task
  refused with exactly that message. Fixed to match the sibling.
  **Tests: `BuildFFmpegWasmTaskTest`, six cases, all proved able to fail.** They pin the three
  things that are silent when wrong: every filter the C library names is enabled, `--disable-asm`
  is absent from the simd variant (it turns SIMD off with it, so a simd build would silently be a
  base build and its measurement a lie), and `--disable-postproc` appears nowhere (it does not exist
  on n8.0). Falsification: reintroducing the missing-`buffersink` bug and the `--disable-asm`-in-simd
  bug fails 2 of 6; restoring returns green. A seventh behaviour was changed BY a test rather than
  pinned by it: an unknown variant used to fail with "property `sourceDir` has no value", which
  names the wrong thing, so the variant is now validated before any other input is read.

#### X-03. `libkitecodec.a` has never been compiled for wasm
- Where: `native/kitecodec-c/`; the KiteCodec repository.
- Problem: the C library is portable and makes no platform calls, so this is expected to be near
  mechanical, but "expected" is not "measured" and the archive does not exist.
- Fix, decided: compile the existing sources with `emcc` and link against X-02's archives.
- Sub-phase: X.3. Test: the archive links into a module that really calls `avformat_open_input`,
  which is the same bar the spike's `harness/minimal.c` already cleared.
  Result, 2026-08-17: `:kitecodec-core:compileKiteCodecCForWasm` compiles all 11 C sources with
  emscripten and `-Werror` into a 44,524 byte `libkitecodec.a`. The "portable C" claim held: not one
  source needed an `#ifdef __EMSCRIPTEN__`, and the only flag that had to go was `-fPIC`, which
  emscripten warns is meaningless for wasm.
  **Proved by running, not by compiling.** `scripts/wasm-link-probe.sh` links the archive against
  the six wasm FFmpeg archives into 877 KB of wasm and executes it under node. It reports the
  FFmpeg configuration string and then asks `ffkmp_filter_exists` for each of the five filters
  `helpers_filter.c` looks up by name: all five answer 1. That is the assertion the X-02 avfilter
  correction rests on, now measured rather than argued.
  Falsification: `--falsify` links the same probe WITHOUT `libavfilter.a` and the link fails with 3
  undefined symbols, so the probe cannot pass by accident.
  **One build-wiring mistake, found and fixed here.** The archive was first written to
  `native-libs/lgpl/wasm32/kitecodec`, which is INSIDE `BuildFFmpegWasmTask`'s declared output
  directory. Two consequences, one visible and one latent: FFmpeg re-ran on every invocation because
  its output tree kept changing, and the next FFmpeg run would have deleted the archive, since that
  task clears its output before installing. Moved to `native-libs/deps/wasm32/kitecodec`, a sibling,
  which is where the other generated dependency trees already live. FFmpeg now reports UP-TO-DATE.
  **Honest bound.** This proves the C layer links and runs on wasm and that the filter registry is
  populated. It decodes nothing: no `AVFormatContext` is opened, because there is no IO bridge until
  X-06 and no way to hand it a file before then.

#### X-04. JS would get raw heap offsets, which `kj_internal.h` already forbids for JNI
- Where: `native/kitecodec-c/kj_handles.c`, 202 lines; `kj_internal.h:16-20` for the reason.
- Problem: handing JavaScript raw heap offsets makes a use-after-free indistinguishable from a
  valid handle, and heap growth invalidates every view. The JNI side already solved this with
  generation-tagged tokens and the header states why.
- Fix, decided: **AMENDED 2026-08-17 by the owner, who chose sharing over porting.** The item said
  "port", which means a second copy. The owner was asked in plain terms and answered that one
  implementation is worth the surgery, on the ground the item itself gives: this table exists so a
  stale token is a typed error instead of memory corruption, and two copies of memory-safety code
  are how a fixed bug survives in the copy nobody edited.
  What sharing means here, measured before it was proposed: only three of the table's functions
  touch JNI at all (`kj_handle_put_checked`, `kj_handle_put_borrowed`, `kj_handle_get`), and each
  uses `JNIEnv` for one purpose, to throw. Everything else is `int64_t` arithmetic over a static
  table. `jlong` is a typedef for `int64_t`, so the core is already portable in fact.
  The portable core therefore moves to its own directory, `native/kitecodec-handles/`, and the JNI
  file keeps only the three throwing wrappers. It goes in a THIRD place rather than into
  `native/kitecodec-c/src/` deliberately: that directory compiles into every target's
  `libkitecodec.a`, and nothing outside `native/kitecodec-jni/*.c` calls the table, so putting it
  there would ship dead code into every mobile binary and move the symbol-audit baseline for
  targets this stage does not touch. The new directory is compiled into exactly two things, the JNI
  library and the wasm archive, which are the two that need it.
- Sub-phase: X.4. Test: a stale token is refused rather than honoured, proved by a test that frees
  a handle and then uses it. Because this now edits code that Android and the desktop JVM already
  ship, the existing JNI suites are the regression arm and must stay green with no baseline moved.
  Result, 2026-08-17: the table moved to `native/kitecodec-handles/kc_handles.{c,h}` and
  `kj_handles.c` shrank from 202 lines to the three wrappers that need a `JNIEnv` to throw. The JNI
  library links with 10 adapter units where it linked 9, and the wasm archive compiles 12 sources
  where it compiled 11. `LinkKiteCodecJniTask` derived its include path from `sources.first()`, which
  breaks the moment sources live in two directories, and `kc_handles.c` sorts ahead of `kj_*.c`; it
  now adds every distinct source directory. The corrupt-descriptor negative link stages a copy of
  the JNI tree only, so it names the shared directory explicitly or it would fail on every
  `kj_handle_*` symbol.
  **The test took three attempts to become real, and the first two are worth recording because both
  LOOKED green.** Attempt one asserted that a released token stops resolving. It passed with the
  generation counter deliberately removed, because a closed slot has a NULL pointer and that alone
  refuses the lookup: the assertion never reached the generation. Attempt two added a second mint
  and called it slot reuse. It also passed sabotaged, because the free-slot scan moves FORWARD, so
  the second mint took slot 1 and the freed slot 0 was never reused. The test's name claimed a
  property its body could not reach.
  Attempt three forces the reuse deterministically: the table grows in 1024-slot chunks, so filling
  the first chunk and then freeing its first slot makes the next mint land in that exact slot. The
  probe now asserts the reuse HAPPENED, by comparing the slot bits of the two tokens, before
  asserting the old token is refused. Without that guard the test could go back to proving nothing
  the next time an allocation detail changes.
  Falsification, clean red then green: deleting the `kj_slots[slot].gen == gen` comparison from
  `kj_resolve` fails with `a token for a FREED object resolved after its slot was reused`, and
  restoring it passes. Two earlier sabotage attempts that did NOT produce a failure are themselves a
  finding: the table defends the same property three ways, a NULL pointer check, an odd/even
  free-slot rule and the generation compare, so removing any one alone does not open the hole. The
  `-Werror` build also refused the sabotaged unit until an unused variable was silenced, which is a
  fourth layer nobody designed and everybody benefits from.
  **Honest bound.** No test covered the stale-token guarantee before this one, on either binding.
  The C suites cover ownership and buffers, and the JNI JVM suites cover the AVIO bridge. This probe
  is the first coverage of the table's own contract, and it runs on wasm only; the JNI side is
  covered by regression, not by a new test of its own.

#### X-05. The 198-entry binding is the stage's single largest item, and its shape is undecided
- Where: `signature-baseline.txt` (213 records); `kj_*.c` as the measured JNI precedent, about
  2,560 lines of C plus a 190-row manifest.
- Problem: 40 to 60 hours, which is where S6's original 80-to-120 estimate broke. The original S6
  sentence compressed this into "the JS interop shape over the same C ABI".
- Fix, decided: NOT decided here, by S6-D4. X-05 opens with its own decision act on the two
  questions the spike raised, playback-only subset versus all 198, and generated versus
  hand-written, and that act is committed separately from the code it authorizes.
  **Decision act, 2026-08-17. The subset question is answered by MEASUREMENT and it dissolves.**
  The spike wrote that "a playback-only subset could plausibly halve item 5". Counted against
  `signature-baseline.txt` rather than estimated, the 198 entry points split 149 playback core, 40
  encode and mux only, 9 filter graph. Dropping encode and mux therefore removes 20% of the surface,
  not 50%. The spike's own words were "plausibly", and this is what the count says instead.
  **Decided: GENERATE, and bind all 198.** Generation is the lever that matters, because it attacks
  100% of the surface where the subset attacks 20%, and once a generator exists the difference
  between emitting 149 wrappers and 198 is close to nothing. Binding everything also keeps the web
  at parity instead of creating a second, quieter definition of what KiteCodec does, which is the
  same argument W-20 settled for the format matrix. The 213 normalized records already exist and are
  already gated, so the generator's input is a file the build already fails on when it drifts, and
  item 5 becomes review rather than authorship.
  What this does NOT claim: that generation makes item 5 cheap. The JNI precedent is about 2,560
  lines of C plus a 190-row manifest for the same ABI, and a generator has to be written, its output
  has to be readable, and the hand-written exceptions (the IO bridge, the callbacks, anything taking
  a function pointer) still have to be written by hand. The 40-to-60-hour estimate stands.
- Sub-phase: X.5. Test: whatever the decision act selects, plus the existing signature gate, which
  is what keeps a generated binding honest against future ABI drift.
  Result, 2026-08-17, first increment of X.5: `:kitecodec-core:generateWasmBinding` parses the
  gated baseline and emits an emcc export list. **196 exported, 2 left hand-written**
  (`ffkmp_fmt_open_input_io`, which takes two function pointers and is X-06's whole subject, and
  `kc_jvm_attach`, which takes a `JavaVM *` that does not exist in a browser). The hand-written set
  is a named constant that the tests assert really appears in the baseline, so the boundary is
  visible rather than inferred.
  **Proved by CALLING it, not by counting names.** `scripts/wasm-binding-probe.sh` links the real
  archives with the generated list and exercises one call of every shape the ABI uses: `int(void)`
  (`ffkmp_averror_eof` returns -541478725), `const char*(void)` decoded as UTF-8 (the configuration
  string contains `wasm32`), `int(const char*)` with the string marshalled INTO wasm memory
  (`ffkmp_filter_exists` answers 1 for `buffersink` and 0 for a name that does not exist),
  pointer-returning alloc then `int64_t` getter then free (a fresh frame's pts is
  -9223372036854775808, which is `AV_NOPTS_VALUE` and therefore the right answer rather than a
  plausible one), a setter/getter round trip through the frame, and two `int(void)` constants
  asserted distinct because a collision there is silent corruption.
  Falsification: removing a single name from the export list still LINKS and then fails at the
  call, exit 1. That is the failure mode this probe exists for, since a missing export is invisible
  until something calls it.
  **The size question, measured rather than feared.** Exporting 196 functions defeats dead-code
  elimination: the module is 3,548,363 bytes raw against 877 KB for the minimal five-symbol probe.
  Gzipped it is **1,115,338 bytes, 1.06 MiB, against the spike's 1.00 MiB lean budget**, so binding
  the whole surface costs about 6% of download rather than the 4x the raw number suggests, because
  what the exports retain is largely redundant FFmpeg tables that compress well. That is a further
  argument for the bind-everything decision and it is now a number rather than an expectation.
  Second increment, same day: the generator now also emits `KiteCodecWasm.kt`, **196 Kotlin/wasmJs
  externals**, one `@JsFun` per entry point. Each takes the emscripten module as its first argument,
  because Kotlin/Wasm and the codec are two wasm modules with separate linear memories and every
  call crosses through JS. Pointers map to `Int`, which is what a wasm32 address is, and stay opaque
  on the Kotlin side. `int64_t` maps to `Long`, and that mapping was settled by COMPILING one across
  `@JsFun` before the emitter was written rather than assumed, because a type that silently
  truncated would corrupt every timestamp in the player.
  All 196 compile for wasmJs with no errors and no warnings. **And they run**: `BindingProof.kt` in
  `:kiteplayer-sample-web` calls them from Kotlin in a real browser and the page logs
  `config OK | averror_eof -541478725 | frame alloc OK | width round trip OK | int64 OK
  (AV_NOPTS_VALUE) | null name refused | media constants distinct`. The int64 line is the one that
  mattered: a fresh frame's pts is `Long.MIN_VALUE`, so the 64-bit path is exact from Kotlin and
  not merely non-crashing. Compiling proved the types were well formed; this proves a call reaches
  the codec.
  Still ahead in X.5 and NOT claimed: the generated file has no permanent home yet. It is copied
  into the sample module to be exercised; putting it in `kitecodec-core`'s wasmJs source set is
  X-07's business, because that moves a published klib's ABI and is not a side effect this item may
  take. No string, array or struct-out helper is generated either: the proof decodes C strings
  through emscripten's own `UTF8ToString`, which is a JS call and not part of the binding.

#### X-06. There is no AVIO bridge that can block on a Worker
- Where: `kj_format.c:450-660` as the reference; `ffkmp_fmt_open_input_io`.
- Problem: FFmpeg's IO callbacks are synchronous and the browser main thread cannot block.
- Fix, decided: a Worker-resident blocking source, buffered first, then `FileReaderSync`, then
  synchronous XHR. The spike proved in a real browser that a Worker CAN block this way, which is
  why this does not force cross-origin isolation.
- Sub-phase: X.6. Test: a seek that crosses a buffer boundary on a real file served over HTTP.
  Result, 2026-08-17: `scripts/wasm-io-probe.sh` opens `testmedia/sync1080p30.mp4` through
  `ffkmp_fmt_open_input_io` with read and seek callbacks that live in JavaScript, registered with
  emscripten's `addFunction`. **It demuxes the real file and then decodes it.** Reported: container
  `mov,mp4,m4a,3gp,3g2,mj2`, 2 streams, h264 1920x1080 and aac, duration 10.00s exactly, and the JS
  source served 131,120 bytes of a 19,867,162 byte file, which is the right shape: stream discovery
  seeks and reads a header rather than slurping the whole thing.
  **Decode, on the same run, because a demuxer that never decodes proves half a pipeline.** Three
  frames out of h264, first pts 0, converted to RGBA at 1920x1080 and 8,294,400 bytes. The pixels
  are inspected rather than trusted: 2,073,600 non-black pixels, which is exactly 1920 times 1080,
  so every pixel carries picture, and the alpha channel is opaque everywhere. A frame of zeroes
  would have satisfied every size and status assertion, which is why the content check exists.
  Falsification: a byte source whose read always returns -1 is REFUSED at open with
  `-1094995529` (`AVERROR_INVALIDDATA`) rather than producing an empty or fabricated context.
  **Two API traps paid for here.** `ffkmp_fmt_open_input_io` takes an `int64_t` size, and with
  `WASM_BIGINT` that argument must arrive as a JS BigInt; emscripten's `ccall` has no type spelling
  for it and fails with `Cannot convert N to a BigInt`, so the export is called directly. And
  `ffkmp_frame_copy_to_buffer` with a null destination answers -28, an error code and not a length,
  so the size query is `ffkmp_image_get_buffer_size`; treating the first as a size would have
  allocated nothing and copied into it.
  Honest bound: node, not a browser, and a whole file already in memory. The browser arm needs the
  Worker of X-08 before a blocking read is legal off the main thread, and range-request streaming
  is not attempted here. What is proved is that the callback shape crosses into JS correctly and
  that real media decodes through it.

#### X-07. `kitecodec-core`'s wasmJs actuals are `unsupportedMain` stubs
- Where: `kitecodec-core/build.gradle.kts:204-205` and its placeholder rule.
- Problem: `Playback`, `Frame`, `MediaSource` and the rest throw `AVERROR_PATCHWELCOME`.
- Fix, decided: real actuals over X-05's binding, keeping the placeholder rule intact for `js`,
  which is a separate target that this stage does not light up.
  **Amended 2026-08-17, before any code: the web needs an explicit initialise and the common API
  has nowhere to put one.** `kitecodec-core`'s surface is synchronous by design. `FFmpeg.identity`
  is a property, `MediaSource.streams` is a property, and every native target can answer them the
  moment the process starts because its codec is linked into the same binary. A browser cannot:
  the codec is a SECOND wasm module fetched over the network and instantiated asynchronously, and
  nothing can be answered before that resolves. There is no way to block for it either, because
  blocking the main thread is exactly what a browser forbids.
  Decided: a web-only entry point, `KiteCodecWeb.load()`, suspending, that must complete before any
  `kitecodec-core` call on wasmJs. Until it does, every actual throws ONE typed error that names the
  cause and the fix rather than failing as a null dereference somewhere inside a getter. This is
  additive and web-only: it appears in no common source set and no other target's ABI, and the `js`
  target keeps the placeholder rule.
  Why not the alternatives. Making the common API suspend would change every platform's ABI to
  serve one platform's constraint. Auto-loading on first touch would put a network fetch behind a
  property read, so a getter would sometimes take 200 ms and sometimes throw, which is worse than
  an explicit step. Loading the module inside the Worker of X-08 and hiding it behind a message
  protocol is the eventual shape, but the Worker still has to await the same instantiation, so the
  explicit load is required either way and is better proved here first.
  **Second amendment, same day: the web gets the PLAYBACK actuals and keeps a typed refusal for
  encode, mux and filter.** Compiling wasmJs against commonMain names exactly 14 missing actuals,
  and they split without argument: `MediaSource`, `Frame`, `Packet`, `PacketReader`,
  `StreamDecoder`, `SeekDirection` and `rescaleQ` are what a player needs, while `MediaSink`,
  `CopyStream`, `VideoEncoder`, `AudioEncoder`, `Remuxer`, `Transcoder` and `FilterGraph` are what
  a transcoder needs. S6 is "IT PLAYS ON THE WEB", so the first seven get real implementations and
  the second seven keep the placeholder that names what is missing and why.
  This is NOT the X-05 subset question returning. That one asked what to BIND, and the answer was
  everything, because generation made the subset saving worthless. This asks what to IMPLEMENT in
  Kotlin by hand, where each class is real work and none of it is generated. Encoding on the web is
  also the case with the strongest platform alternative, since `VideoEncoder` is a WebCodecs
  interface too, so hand-writing an FFmpeg encode path here would likely be replaced rather than
  extended.
  The refusing actuals move to a source set both `js` and `wasmJs` use, so there is one copy of
  each refusal rather than two that drift.
  **Result, 2026-08-17: the playback backend is real and a browser decodes through it.** `wasmJs`
  came off `unsupportedMain` and got `FFmpeg`, `MediaSource`, `Frame`, `Packet`, `PacketReader`,
  `StreamDecoder`, `SeekDirection` and `rescaleQ`. Proved by driving the ordinary API from Kotlin in
  a real browser, the same API Android and iOS use, over a 10-bit HEVC clip:
  `identity acceptable` / `build n8.0, abi 2.6, 6 libraries` / `container mov,mp4,m4a, 1 streams,
  200ms, seekable true` / `video hevc 320x240 timeBase 1/12800` / `frame 320x240 yuv420p10le,
  230400 plane bytes` / `DECODED 3 frames through kitecodec-core, first pts 0`.
  Two of those numbers are the ones that say it is CORRECT rather than merely running. Six
  libraries means the `kc_ffmpeg_report` struct read landed on the right fields, and 230400 is
  exactly 320x240 10-bit 4:2:0, so the plane copy sized itself from the real format.
  **The struct problem, and how it was solved.** `FFmpeg.identity` comes from a C struct, and
  JavaScript cannot see one: it needs a byte offset per field, and a wrong offset reads the
  NEIGHBOURING field and answers something plausible. So `native/kitecodec-c/probe/report_offsets.c`
  emits `offsetof()` for every field, `scripts/wasm-report-offsets.sh` turns that into a committed
  `ReportLayout.kt` and re-derives it on demand, failing when the struct moves underneath it. The
  numbers are the compiler's, never a human's.
  **Three defects the API ratchet and the browser caught, each fixed rather than dumped over.**
  First, `apiDump` showed the 196 generated externals were PUBLIC, which would have committed the
  library forever to a surface that exists only because the codec lives in a second wasm module;
  they are `internal` now, and the web adds 15 lines of public API instead of 610. Second, the
  module handle leaked as a public mutable `var`; it is internal. Third, a bundler rewrites
  `import(url)` at BUILD time, so `KiteCodecWeb.load()` fails inside webpack with "Cannot find
  module" even though the file serves correctly; `attach()` was added for that and is what a
  bundled application should use.
  **One cryptic failure turned into an instruction.** A module linked without `HEAP32` failed with
  `Cannot read properties of undefined (reading '4597710')`, naming neither the cause nor the fix.
  `attach()` now checks the ten runtime pieces this backend reads and throws `IncompleteModule`
  naming the missing ones and the exact `-sEXPORTED_RUNTIME_METHODS` line that supplies them.
  **Honest bounds.** `MediaSource.open(path)` refuses, because a browser has no filesystem. The
  byte source is staged whole into codec memory, capped at 512 MB, so streaming and range requests
  are refused explicitly and wait for the Worker of X-08. Container metadata and chapters answer
  empty pending the dictionary walk. Encode, mux and filter remain refused. And the staging copies
  byte by byte in both directions, because Kotlin/Wasm has no bulk typed-array move, which is why
  the proof uses a 39 KB clip and why a real page needs the fetch to land straight in codec memory.
  **One environment defect this work exposed, in the GATE itself.** Section 9's Tier 2 said
  `publishToMavenLocal -Pkitecodec.hostTargetsOnly=true` when KitePlayer must see KiteCodec
  changes. A publish REGENERATES the root module metadata, so that line deletes the ios, linux and
  mingw variants from it, and the linux and Windows lines further down the SAME gate then fail to
  resolve. Running it broke four unrelated steps at once: `checkKotlinAbi`, both linux scripts and
  the mingw link. A second publish with the target flags fixed three and left the fourth, because
  the Linux JNI libraries in the jvm jar are opt-in behind `-Pkitecodec.jni.linux=true`, and
  without them every matrix row fails on "kitecodec_jni is neither on java.library.path nor
  bundled". Section 9 now carries all three flags and says why. Worth stating plainly: none of the
  four failures were in the web code. The gate caught a machine-state drift no test in either
  repository would have seen, which is what a heavy gate is for.
- Sub-phase: X.7. Test: KiteCodec's own suites, run in a headless browser.

#### X-08. Nothing runs the player in a Worker, and X-06 depends on it
- Where: new; `:kiteplayer-mobile`'s web surface.
- Problem: the blocking IO of X-06 is only legal off the main thread, and every user-facing item
  after this one needs a main-thread facade that does not block.
- Fix, decided: Worker bootstrap, main-thread facade, message protocol and lifecycle.
- Sub-phase: X.8. Test: a player driven entirely through the facade, with the main thread proved
  responsive during decode.

#### X-09. `:kiteplayer-ffmpeg` has no web target, and its conversion assumes threads
- Where: `Conversions.kt:128`; `BlockingMediaIo.kt`.
- Problem: entry fact 5. Adding wasmJs to this module breaks on `runBlocking` at compile time, and
  the comment that explains why no expect/actual exists becomes false in the same commit.
- Fix, decided: an expect/actual split for `parallelRowSlices` whose wasm actual is the serial
  body, with the comment rewritten to say which targets have threads and which do not. X-01's
  number decides whether that serial body is acceptable or whether the conversion moves into the
  wasm module beside FFmpeg instead.
- Sub-phase: X.9. Test: the existing colour suites, which W-19 already proved able to catch a
  slice that skips its rows.
  Result, 2026-08-17: `:kiteplayer-ffmpeg` compiles for wasmJs. The module turned out to have a
  very small platform surface, two `expect` functions, and adding the target named exactly the two
  blockers entry fact 5 predicted and nothing else. Both were `runBlocking`, which does not exist
  in Kotlin/Wasm.
  **`parallelRowSlices` became expect/actual, and the comment it replaced is kept visible on the
  declaration because it was true when written and stopped being true.** It said "no expect/actual:
  every target this module compiles for has a multi-threaded `Dispatchers.Default`". Adding wasmJs
  falsified both halves at once. The web actual runs the body serially, and the note says plainly
  that the 3.36x W-19 measured is not available there, with X-01's 50-to-87 ms against about 2.1 ms
  on four desktop cores as the number. It also says why the serial path is not the one a web player
  should take: the conversion belongs in C beside the decoder (X-11 measured 6.0 to 6.8 ms), so this
  actual exists mainly so the module COMPILES for wasm.
  **`BlockingMediaIo` became expect/actual too, and its web actual refuses rather than spins.**
  Every other target parks the demux worker with `runBlocking`, which is legitimate because
  `MediaIo`'s contract already confines FFmpeg's synchronous pull to that one thread. The web has no
  such primitive at all. The wasmJs actual runs the suspending read through
  `startCoroutineUninterceptedOrReturn` and accepts the result ONLY if the body completed without
  suspending, which is what a memory-backed source does and a network-backed one does not. A source
  that suspends gets a typed refusal naming the two shapes that work and pointing at the Worker
  (X-08), instead of a busy-wait that would freeze the page.
  `platformDecoderSelection` answers software-only, with the reason on it: the wasm decoder has no
  hardware route, the BROWSER does, and that is X-15's subject and belongs in this exact function
  when it lands. `rewindFdOption` is empty because a browser has no file descriptors.
  Regression: the other targets still compile and `:kiteplayer-ffmpeg:jvmTest` is 60 tests, 0
  failures, so moving the blocking bridge and the parallel loop into a shared source set changed no
  behaviour on the platforms that already had them.
  Not claimed: nothing in this module has RUN on the web. It compiles, and the engine's SPI is not
  yet wired to a web `OutputBackend` (X-12) or renderer, which is what X.12 is for.
  **Ratchet move, named as section 9 requires.** `:kiteplayer-ffmpeg`'s klib ABI dump moved, 9
  insertions and 2 deletions, and NO declaration was added or removed. Adding a target changes how
  the dump annotates the ones already there: the target list gained `wasmJs`, a `native` alias
  appeared for the six Kotlin/Native targets, and the cinterop-only declarations
  (`SoftwareConverter`, `corePixelBufferOrNull`, `uploadPlanesOrNull`) are now labelled
  `// Targets: [native]` instead of being unannotated. `interleavedFloat` GAINED wasmJs and
  therefore moved out of the native-only section, which the diff shows as a delete and an insert of
  the same line. Verified by pairing every changed declaration line: none is unpaired.

#### X-10. There is no web `AudioSink`
- Where: `spi/AudioSink.kt`, already all-`suspend`.
- Problem: no AudioWorklet sink exists, and without `SharedArrayBuffer` the ring must be
  `postMessage`-fed, which raises the latency floor.
- Fix, decided: AudioWorklet plus a ring over the existing contract, with the latency floor
  MEASURED and stated the way W-D2 states the `SourceDataLine` floor rather than left implicit.
- Sub-phase: X.10. Test: an underrun count over a sustained run, plus the stated floor.
  Result, 2026-08-17, SHAPE PROVED, not the sink. The browser demo now decodes the aac stream
  beside the video and pushes it into Web Audio. Reported by the page: 48000 Hz, 2 channels,
  planar float, and a sample peak of 0.103, which is real content and not silence. The resample
  goes through `ffkmp_graph_build_audio` rather than by reading decoder planes raw, for two
  reasons: it guarantees the output format whatever the source was, which is what a player must do,
  and it is the first thing that has RUN an avfilter graph in wasm. X-02 added avfilter to the web
  build on the strength of a symbol lookup; this executes one.
  **What is NOT claimed.** Nobody heard it. The samples are correct and are scheduled onto an
  `AudioContext` timeline, but a hidden browser pane suspends audio under the autoplay policy, so
  audible output is unverified. Nor is this the `AudioSink` X-10 asks for: there is no ring, no
  underrun count, no latency floor measured, and no engine clock driving it. `createBufferSource`
  per decoded frame is a proof of the format path, and a real sink is an AudioWorklet fed by a ring,
  which is what X-10 still has to build and measure the way W-D2 measured `SourceDataLine`.

#### X-11. There is no web `VideoRenderer`, which is KV-6 proper
- Where: `KiteVideoRenderer.kt`, about 375 lines.
- Problem: no renderer exists for web, and the existing one carries threading web does not have.
- Fix, decided: a new renderer, not a port. `present` is already `suspend`, so it runs on the event
  loop with no worker, no dispatcher and no `runBlocking`.
- Sub-phase: X.11. Test: X-01's probe re-run against the real renderer, so the gate number and the
  shipped number are comparable by construction.
  Result, 2026-08-17, TIER ONE PLAYS. `scripts/wasm-browser-demo.sh` in KiteCodec builds the
  module and serves `native/kitecodec-c/probe/browser/index.html`, which decodes
  `testmedia/sync1080p30.mp4` with FFmpeg in wasm and draws it to a 2d canvas with `putImageData`.
  A screenshot of the running page shows the clip's colour bars, sweep line and burnt-in timecode
  at 1920x1080, and the timecode advances between frames, so this is playback and not one still.
  **The numbers settle S6-D6 correction 2 and, with it, the open half of the X-01 stop gate.**
  Per 1080p frame, measured in the page's own HUD: **convert 6.0 to 6.8 ms, draw 2.50 to 2.90 ms,
  about 8.5 to 9.7 ms together, against a 33.3 ms budget.** Set beside X-01's Kotlin-and-Skia path:

  | step | Kotlin loop + Skia raster (X-01) | FFmpeg sws_scale + putImageData (here) |
  |---|---|---|
  | YUV to RGBA | 50 to 87 ms | 6.0 to 6.8 ms |
  | onto the drawable | 107 to 153 ms | 2.50 to 2.90 ms |
  | total | 160 to 240 ms | 8.5 to 9.7 ms |

  About twenty times faster, and inside the frame budget with roughly 3.5x headroom. Both halves
  moved for the same reason: the pixels never enter the Kotlin heap. The conversion runs in C
  beside the decoder, and the result is already an ArrayBuffer view the canvas accepts directly.
  **Honest bounds, and the fps figure is NOT one of the numbers above.** The page's frames-per-second
  readout shows 0.0 to 0.1 and means nothing here: the browser pane is hidden, so
  `requestAnimationFrame` fires only when a screenshot forces a paint. What is measured is the
  per-frame COST, which is a span inside the callback and does not depend on how often the callback
  runs. A real throughput number needs a visible window and is not claimed. Also unclaimed: audio,
  A/V sync, seeking and the engine. This is the render path, proved end to end, with everything
  above it still to come. And this is tier one by S6-D6's own terms, a canvas layered under Compose
  controls; the Compose-true single-surface path that D-6 promises, where clip, alpha and rotation
  apply to the video pixels, is tier two and is NOT what this measured.

#### X-12. `platformKitePlayerDefaults` is `Unavailable` on wasmJs
- Where: `:kiteplayer-mobile`'s wasmJs source set.
- Problem: X-10 and X-11 are not reachable by a consumer until something wires them.
- Fix, decided: a web `OutputBackend` joining the two, and real defaults.
- Sub-phase: X.12. Test: a consumer building a player with no platform-specific code.
  **Decision act, 2026-08-17, two questions the item did not answer.**
  1. **How a web consumer supplies a surface: it does not, and the tree said so before any code was
     written.** The first draft of this decision invented `KitePlayerWeb.outputBackend(canvasId)` so
     a consumer could name a `<canvas>`. Checking `DesktopOutputBackend` before implementing it
     killed the idea: that backend answers `videoRenderer = null`, because on desktop Compose draws
     the frames and the backend supplies only the clock and the sink. The web is the same shape, and
     X-11 tier one is a canvas layered with Compose for exactly that reason. So `WebOutputBackend`
     is a plain `object` like every other one, no argument and no new API. Recorded as a correction
     rather than quietly dropped, per 18.3 rule 5: the register proposed something the tree
     contradicts, and the tree wins.
  2. **What the audio sink is in this increment, said plainly rather than implied.** The SPI wants a
     pull sink: `open(format, render)` and a callback the device drives. Doing that properly on the
     web means an `AudioWorklet`, a ring, and a message protocol to reach a callback that lives on
     another thread, which is X-10 and is the piece with the most real-time subtlety in the whole
     stage. This increment ships `SilentPacedAudioSink` instead: it advances the engine's clock at
     exactly real time and writes no samples anywhere. That is a REAL sink by the contract, and it
     is what makes video play and A/V sync work on the web today, but nothing is audible and the
     class name says so. X-10 remains open and this is not a substitute for it.
  Why a silent sink rather than no backend at all: this engine is audio-mastered, so the clock and
  the video path cannot be exercised without something answering the sink contract. A paced silence
  is the smallest thing that makes the rest testable, and its absence would have left the renderer
  unprovable inside the engine.
  **Result, 2026-08-17: `KitePlayerPlatform.createOrNull()` returns a real player in a browser.**
  Four layers went web-capable in order and each compiled before the next was touched:
  `kitecodec-core` (X-07), `:kiteplayer-ffmpeg` (X-09), `:kiteplayer-output` with `WebOutputBackend`,
  and `:kiteplayer-mobile`'s `platformKitePlayerDefaults`, which was a hardcoded
  "not implemented yet" until now. The page reports `player availability: Available` and
  `player: CREATED through KitePlayerPlatform, backends resolved`, and the player closes cleanly.
  That is the call a consumer makes on Android and iOS, answered on the web by the same code path.
  **Availability is computed, never cached, and that is deliberate.** Every other platform can read
  `FFmpeg.identity` whenever it likes because the codec is in the binary; the web's is a module the
  page fetches. So the web defaults report Unavailable with the fix in the message until
  `KiteCodecWeb.load` or `attach` completes, and then report Available. A lazily cached answer, which
  is what the desktop defaults do, would have been wrong forever for a consumer who asked before
  loading.
  Not claimed, and the class name says the first one out loud: nothing is audible, because
  `SilentPacedAudioSink` writes samples nowhere. No frame has been presented THROUGH the engine on
  the web either; the renderer is proved standalone at 8.5 ms per frame (X-11) and the engine
  resolves a backend, but the two have not been joined by a Compose surface yet.
  **Ratchet move.** `:kiteplayer-output`'s klib dump gains two public objects, `WebOutputBackend`
  and `WebMonotonicClock`, both `// Targets: [wasmJs]`, both mirroring the public desktop twins
  `DesktopOutputBackend` and `DesktopMonotonicClock`. A third, `SilentPacedAudioSinkFactory`, was
  public in the first draft and was made INTERNAL before the dump was accepted: it is scaffolding
  for X-10, publishing it would commit the library to a silent sink as API, and a consumer reaches
  it through `WebOutputBackend.audioSink` without needing the name. Nothing was removed.

#### X-13. There is no artifact layout and no deployment story
- Where: new; 17.6 for the tier sizes.
- Problem: an embedder needs to know what to serve, and the threaded artifact needs a feature
  detect BEFORE import because the failure without COOP and COEP is a hang.
- Fix, decided: artifact layout, a `self.crossOriginIsolated` detect before importing the threaded
  module, embedder documentation, and the 17.6 web tier sizes measured and written down.
- Sub-phase: X.13. Test: both artifacts served from the spike's own dual-mode server, with the
  detect proved to pick correctly in both modes.

#### X-14. Every web claim is level 8 until the matrix runs there
- Where: 17.5; CI.
- Problem: no headless browser run, no conformance suite, no size check.
- Fix, decided: the 17.5 matrix in a headless browser, plus the size checks, on the same principle
  W-20 settled for Linux: run the project's OWN suite rather than write a second, weaker one.
- Sub-phase: X.14. Test: the matrix run itself, with its pass count recorded.
  Interim result, 2026-08-17. `../KiteCodec/scripts/wasm-matrix-probe.sh` runs all 26 present
  matrix fixtures through the wasm demux-and-decode path in node and reports PER ROW. It is NOT
  the project's own suite and says so in its own header: that suite is Kotlin and needs the engine,
  which the web does not have yet, so this is the honest interim and X-14 still stands.
  **It found three real defects on its first run, which is what a conformance pass is for.**
  `vp9.webm` failed with "no decoder for vp9": the lean web tier had never included vp9, while 17.5
  lists that row as MustPlay. And `audio-mp3.mp3` and `audio-flac.flac` both failed at open with
  -29, because the tier carried the mp3 and flac DECODERS but only the `mov` and `matroska`
  demuxers, so a bare elementary stream could not be opened at all. A decoder without its demuxer
  is a codec nobody can reach.
  Fixed by widening the tier rather than by narrowing the matrix, because 17.5 is the project's one
  definition of playing all formats: vp9 joins the decoder and parser sets, and `mp3` and `flac`
  join the demuxers. **After the fix: 13 rows PLAY, 11 are omitted by the lean web tier by design,
  2 torture rows SURVIVE without crashing, and there are 0 unexpected failures.**
  **The cost, measured and NOT hidden: the module went from 1.06 MiB gzipped to 1.22 MiB, which is
  22.3% over the spike's 1.00 MiB budget.** That budget was set when the tier omitted three things
  the matrix requires, so the honest reading is that the original budget was measured against a set
  that could not serve the matrix. Whether 1.22 MiB is acceptable, or whether vp9 should ride on
  WebCodecs alone (X-15 measured the browser decoding vp9 in hardware) and leave the wasm build
  leaner, is a tier decision for 17.6 and is NOT taken here.
  The eleven omitted rows are mpeg4 part 2, mpeg-ts, av1, avi, wmv, flv, vob, eac3, dts, truehd and
  alac. X-15's capability probe already showed the browser decodes av1 in hardware, so some of that
  list is recoverable through the hardware path rather than by growing the download.

#### X-15. The browser decodes in hardware and this stage never asked it to
- Where: new, behind `:kiteplayer-core`'s existing decoder SPI; `kiteplayer-ffmpeg`'s module KDoc,
  which already says the four interfaces exist so "WebCodecs on the web" can replace FFmpeg without
  the engine noticing; 17.2's S7 support matrix, which already names a WebCodecs/WebAudio/MSE
  backend as the web capability profile.
- Problem: this expansion rebuilds the native stack in wasm and never weighs what the browser
  ships. Two of the plan's own promises named WebCodecs before phase W began and the S6 expansion
  dropped both. S6-D6 raised it; this item is that decision act.
- **Measured 2026-08-17 in the in-app Chromium, by asking `VideoDecoder.isConfigSupported` rather
  than by assuming.** `VideoDecoder`, `AudioDecoder`, `VideoFrame` and `AudioWorklet` all exist.
  Supported: h264 High (`avc1.640028`), HEVC Main (`hev1.1.6.L93.B0`), **HEVC Main10**
  (`hev1.2.4.L120.B0`), VP9, AV1, and audio aac, mp3 and opus. NOT supported: mpeg4 part 2
  (`mp4v.20.9`), and flac was refused as a configuration. So the matrix splits: most rows have a
  hardware path, and `mpeg4part2.mp4`, `avi-mpeg4.avi`, `wmv-msmpeg4.wmv` and the flac row do not.
- Why this is the stage's largest lever, in one number: 17.9 declares 4K a v1 non-goal, and the
  spike's evidence for that was 4K HEVC 10-bit running at exactly 1.0x in SOFTWARE. HEVC Main10
  answers YES here, so the non-goal rests on a measurement hardware erases. A second number: hevc
  is 20.7% of the gzipped wasm module and need not ship to a browser that decodes it.
- Fix, decided: demux stays FFmpeg in wasm, because no browser API demuxes mkv and none serves the
  17.5 matrix's subtitle rows. Decode goes to WebCodecs where the browser and the codec allow, and
  falls back to the wasm decoder otherwise, chosen per stream at open time behind the decoder SPI
  the engine already has. The engine does not learn that any of this happened, which is the
  property `kiteplayer-ffmpeg`'s own KDoc claims and nothing has ever exercised.
- Why not MSE and a `video` element: considered and rejected once, here. It cannot serve the 17.5
  matrix (mkv, the subtitle formats) and it surrenders the frame-level control the engine's whole
  contract is built on. That rejection is not revisited without a measurement.
- Sub-phase: X.15, after X-09 gives the software floor something to fall back TO. Test: the 17.5
  matrix run twice in a headless browser, once forced to wasm decode and once allowed hardware,
  with the per-row decoder recorded so a silent fallback cannot look like a hardware pass.
- Honest bound: measured in ONE browser on ONE machine. Safari and Firefox ship WebCodecs with
  different codec sets, and codec support is a per-device, per-OS fact, not a per-spec one. The
  fallback is therefore not a nicety, and the mpeg4 and flac rows above are proof it is load
  bearing on the very machine that has everything else.
  Result, 2026-08-17, THE HYBRID RUNS. `native/kitecodec-c/probe/browser/hardware.html` demuxes
  `sync1080p30.mp4` with FFmpeg in wasm and hands the packets to the browser's own `VideoDecoder`.
  It builds the codec string from what the stream says rather than hardcoding one
  (profile 578, level 40, giving `avc1.420028`) and passes the 40-byte avcC extradata as the
  `description` WebCodecs needs for length-prefixed h264. `isConfigSupported` answered true and the
  decoder produced a picture on the canvas.
  **120 chunks in, 120 frames out, 168 ms: 715 fps.** Against the spike's software wasm figure of
  182 fps on comparable 1080p, hardware is about 3.9 times faster and roughly 24 times real time.
  First frame cost 121.5 ms, which is hardware decoder initialisation and is a real startup number
  a player must hide, not a throughput one.
  **This is the measurement that reopens the 4K non-goal.** 17.9 declared 4K out of scope because
  4K HEVC 10-bit ran at exactly 1.0x in software. 4K is about four times 1080p's pixels, so 715 fps
  at 1080p leaves a wide margin at 4K, and the capability probe above already answered YES for HEVC
  Main10. The non-goal should be re-decided against a hardware measurement rather than inherited;
  that re-decision is not taken here because it needs a 4K clip run through this path, which is a
  measurement and not an opinion.
  Honest bounds on the 715: decode throughput with no display pacing, no audio, no sync, and
  frames closed immediately rather than presented. One browser, one machine. It says the decoder
  can keep up, and says nothing about a player keeping up.

**Sub-phases, in execution order.**

- **X.1 The draw cost is measured, and the stage stops if it fails** (X-01). Commit: "Measure what
  a web frame costs before building the thing that draws it".
- **X.2 FFmpeg builds for wasm from Gradle** (X-02). Commit: "Build FFmpeg for the web from the
  build, not from a script".
- **X.3 The C library compiles for wasm** (X-03). Commit: "Compile the codec library for the web".
- **X.4 Handles cross as tokens, not offsets** (X-04). Commit: "Give JavaScript a handle it cannot
  forge".
- **X.5 The binding, decided then built** (X-05). Commits: one decision act, then one per group.
- **X.6 IO blocks where blocking is legal** (X-06). Commit: "Read media on the thread allowed to
  wait".
- **X.7 The wasm actuals become real** (X-07). Commit: "Let the web variant carry the codec it
  now has".
- **X.8 The player runs in a Worker** (X-08). Commit: "Run the player off the thread the page
  draws on".
- **X.9 The backend reaches the web** (X-09). Commit: "Convert the frame without the threads the
  web does not have".
- **X.10 The web hears** (X-10). Commit: "Hear the picture in a browser, and say what it costs".
- **X.11 The web draws** (X-11). Commit: "Draw the frame through Compose on the web".
- **X.12 A consumer can build a web player** (X-12). Commit: "Let the web defaults be real".
- **X.13 The artifact ships** (X-13). Commit: "Say what to serve, and detect what the host
  allows".
- **X.14 The matrix runs on the web** (X-14). Commit: "Decode the whole matrix in a browser".
- **X.15 The browser decodes what it can in hardware** (X-15). Commit: "Let the browser decode the
  frames it already knows how to decode".

**Status, verified against the tree 2026-08-18 (not from notes).**
- X-01 to X-07, X-09, X-12, X-15: LANDED, each with its commit.
- **X-08 STILL OPEN.** No Worker exists anywhere in the web sources; X-06's blocking IO still has
  no thread that is allowed to wait.
- **X-10 STILL OPEN.** `WebOutputBackend.audioSink` is `SilentPacedAudioSinkFactory`, whose own
  KDoc names the real one: an AudioWorklet fed by a ring. The web keeps correct time and makes no
  sound.
- **X-11 STILL OPEN, and previously mis-summarised as done.** `WebOutputBackend.videoRenderer` is
  `null`. The web sample draws through its own Compose `Canvas` in `Main.kt`, which is what the
  spike measured; there is no `VideoRendererFactory` behind the SPI. A measurement that a frame CAN
  be drawn is not a renderer.
- **X-13 STILL OPEN.** No artifact layout and no deployment story: COOP and COEP appear only in
  this file and the spike document, in no header, sample or doc a consumer could follow.
- **X-14 STILL OPEN.** The 17.5 matrix has never run in a browser.
- The 4K non-goal re-decision stands open too: 17.9 declared 4K out of scope on a 1.0x software
  measurement, and X-15 then measured 715 fps through the browser's own decoder at 1080p. That
  non-goal must be re-decided against a 4K clip rather than inherited.

**The honest bound on this stage, written before it starts.** The spike costed this at 178 to 272
hours against 17.3's S6 estimate of 80 to 120, and this expansion does not shrink that. It changes
only the order and the honesty: X-01 is a stop gate rather than a step, X-05 opens with a decision
act rather than a keyboard, and X-09 carries a named compile-time blocker that entry fact 5 found
in the tree instead of leaving it to be discovered. Three things are NOT in this stage and are
named so their absence is not read as an oversight: the `js` target stays a placeholder and only
`wasmJs` is lit, the threaded artifact is optional and behind a feature detect rather than default,
and 4K stays the non-goal 17.9 already declared, now with the spike's measured 1.0x behind it.

