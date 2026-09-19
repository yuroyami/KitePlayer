# Audio visualiser timing and ownership contract

This contract implements the timing and worker ownership foundation of the
[audio visualiser standard](audioviz-standard.md).
It does not qualify recognition, signal calibration, rendering performance or physical audio/display
alignment. Those claims require the separate evidence named in the standard.

## Audible clock

`KitePlayer.audioClock()` returns one `AudioClockSnapshot` containing:

| Field | Meaning |
|---|---|
| `position: Pts?` | Source media time estimated to be audible at the sampled host instant. Null means unavailable. Negative time and zero are valid. |
| `hostTimeNanos: Long` | The corresponding instant in the output backend's monotonic clock domain. |
| `rate: Double` | Media seconds per host second, zero while held. |
| `generation: Generation` | Continuous audio delivery identity, independent of the video pipeline epoch. |
| `quality: LatencyQuality` | Device timing evidence, not end-to-end route certification. |

The actor publishes the entire mapping atomically from the device-anchored audio clock. A public
read projects that mapping to the host instant of the call. It never reads the seek-bar target or
substitutes video time. Seek, audio selection, reopen and teardown retire the old audio generation;
callbacks finishing in a retiring session keep their original identity. No audio device callback
performs analysis or publishes managed objects.

Consumers read once per display frame and compute `position + rate * displayDelay`. The device
buffering correction is already in the audible position. A callback timestamp from an unrelated
clock domain cannot be used directly as an audio host timestamp. A fresh reading replaces the
mapping after a pause or rate change.

## Tap identity

The engine calls `AudioTap.onAudio(generation, pts, interleaved, frames, format)` and
`AudioTap.onDiscontinuity(generation)`. Their defaults forward to the original callbacks, preserving
existing tap implementations. Consumers doing asynchronous work retain the generation with their
copied samples and reject work belonging to retired generations. Borrowed sample ownership and
the requirement to return promptly are unchanged.

Generation changes occur at the committed audio boundary. Merely preparing a replacement device
path must not retire the path that is still playing. An audio selection may change this identity
without changing the video epoch.

## Shared worker and PCM ownership

Views created with `rememberAudioVizState` share one analysis session per player. The first attached
view starts the session; detaching the last view removes its tap, cancels its worker and releases
its PCM pool. Each view keeps its own playback cursor. Remembering a state in an abandoned Compose
composition does not start a worker. A retained diagnostic object does not retain queued PCM after
the session closes.

The tap copies into a preallocated single-producer/single-consumer pool and signals one analysis
coroutine. The defaults reserve 256 KiB of sample storage (16 slots of 4096 floats), with a separate
250 ms bound on queued source-audio duration. Both limits include the slot currently being read by
the worker. These are media durations, not a promise about wall-clock latency at every playback
rate. Pool accounting excludes metadata, feature snapshots and analyser working storage.

An input block is accepted in full or dropped in full. No queued or consumer-owned slot can be
overwritten. Saturation never waits for analysis: it retires the local analysis revision, clears
the visible history and counts the dropped blocks and sample frames. The worker discards queued
work from that retired revision before accepting fresh analysis. A contiguous run of drops opens
one gap; a new accepted block ends that run. A gap does not falsely advance the player's audio
generation.

FFT and detector work, buffer creation, format handling and input sanitisation run on the analysis
coroutine. A format change, including a changed channel mask with the same channel count, resets
analysis. Timestamp discontinuities over 2 ms also reset it; smaller differences tolerate
millisecond container timestamp quantisation. Known dropped PCM resets explicitly, even when the
timestamp difference is below that tolerance. Worker exceptions invalidate history and increment
the failure diagnostic before the next block attempts recovery.

Non-finite samples become zero. Finite samples are limited to amplitude ±16, about 24 dB above
nominal full scale, before DSP; every changed sample is counted. This is an explicit robustness
limit, not a gain control or a claim of input calibration. Unsupported blocks, including invalid
sizes, sample rates outside 1000..768000 Hz and more than 64 channels, are dropped and diagnosed.

`AudioVizState.analysisStats` exposes the attached session's shared `AudioAnalysisStats` for polling.
It reports PCM capacity and queued duration, copies, drops, sanitised samples, resets, published
analyses, failures, and copy/analysis elapsed times. The counters are atomic independent readings,
not a transactional snapshot or Compose state. Elapsed wall time includes preemption and does not
prove the standard's thread-CPU budget or physical-device performance target.

## Authoring migration

`SpectrumFrame.generation` identifies its audio timeline; `hasTimestamp` distinguishes unknown
positions from valid negative positions. Consumers must use `hasTimestamp`, not `ptsMicros < 0`.
`analysisRevision` distinguishes local analysis gaps within one audio generation. A timeline
publisher must stamp both the current `generation` and `revision` into every frame. A reset
changes the revision even when the audio generation is unchanged; both identities must match for
publication, interpolation and event sampling. Captured internal publishers cannot publish into
a replacement history.
For an author-owned analyser/timeline pair, `analyzer.reset()` and `timeline.clear()` each
advance local continuity by one. When adopting an external audio identity, first call
`timeline.reset(generation)`, then `analyzer.reset(timeline.generation, timeline.revision)` on the
feeding thread. This explicitly aligns the producer after any additional history reset.
`SpectrumAnalyzer.feed` accepts nullable `ptsMicros`, with null meaning unknown. Existing Kotlin
calls passing a timestamp continue to compile; callers previously passing a negative sentinel
must pass null. The JVM parameter changes from primitive `long` to boxed `Long`, so binary
consumers must recompile against the updated module. This is a declared authoring API migration.

`SpectrumTimeline` publishes sequence-stamped slots atomically and replaces its history on reset.
It rejects retired generations and unknown timestamps. Continuous interpolation stays inside one
generation, refuses gaps over 100 ms, and expires data after 100 ms without a new measurement.
`ahead` returns null outside the actually available horizon. `availableAheadSeconds` reports the
retained horizon in media seconds. The scalar `sample` method remains a compatibility projection
of the strongest event of each kind; it does not preserve multiplicity and is not an event counter.
This projection and `nextOnsetSeconds` use detection times independently of feature-window centres.
Event anticipation uses `nextEvent` and retains the event's identity and energy strength; the
player's `VizFuture` adapter converts media-time distance to presentation seconds at the sampled
playback rate. A future feature's scalar hit fields must not substitute for a timestamped event.
`VizRenderState.ahead` now returns a nullable frame too. Drawing authors must handle an unavailable
future explicitly; the camera helpers suppress anticipation until the requested audio exists.

While the reading's rate is zero, `AudioVizState` delivers no events and holds the pulse: the
frame keeps its levels, its rhythm reports not usable and it has no beat countdown. The rate
estimate stays readable as a diagnostic, and resuming restores the accepted pulse.

`AudioVizState` reads the audible clock directly. `displayDelay` is a nullable presentation-duration
override. Null estimates one refresh period from the median of recent callback intervals, starting
at 60 Hz. An isolated missed callback and stalls over 50 ms do not redefine the refresh period.
`estimatedDisplayDelay` reports the last estimate used;
it is not measured presentation feedback. Clock availability and generation must match the queued
features before the state draws them. State-provided lookahead uses presentation seconds, scales
by the current rate, and admits no anticipation beyond 100 ms.

The independent `SpectrumTimeline.asFuture` adapter continues to use media seconds because it has
no playback-rate source. Authors wiring this adapter themselves are responsible for translating
presentation durations to media durations.
