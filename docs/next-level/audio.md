# Program A: audio

Read `README.md` in this directory first. Facts below were verified against the tree: the volume
ceiling is one `require` at `KitePlayer.kt:210`, the gain is applied in the C ring at
`kiteplayer-rt/native/src/kite_rt_render.c:244-287` and mirrored by `KotlinAudioRing.applyGain`
at `kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/KotlinAudioRing.kt:448-467`,
and fourteen tests in `kiteplayer-core/src/nativeTest/kotlin/io/github/yuroyami/kiteplayer/AudioRingDifferentialTest.kt`
hold the two sample for sample. There is no equaliser, no balance, no pitch API, no fade beyond
the 5 ms gain ramp, no audio-only flag, no audio session id on Android, and no ReplayGain.

Paths below are relative to the repository root. `core` means `kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/`.

---

### A1 Volume boost with a soft limiter. LANDED 2026-09-02 except the device run

**What shipped, and where it differs from the plan below.** All of it, plus three things the plan
did not foresee:

- `NativeAudioRing.setGain` carried its OWN copy of the 0..1 bound, so widening
  `KotlinAudioRing` alone left the differential oracle refusing 1.5. Both rings check now.
- `AudioConfig.volumeCeiling` went LAST in the parameter list, not beside `preservePitch`.
  Inserting it earlier moved `downmix`'s position and broke every positional caller for nothing.
- The first version of the walk test could not fail: it walked UP from unity, where the first
  slope step is already above unity, so every frame was boosted and a per-buffer saturator would
  have passed. It walks DOWN through unity now. Found by falsifying, which is what falsifying is
  for.

The plan's remaining text is kept as the record of what was asked for. The device run is DEVICE-DAY
step 19 and is the owner's.

### A1, as planned. Size M, Tier 3 (the owner runs the device half)

**Why.** `setVolume` refuses anything above 1.0 (`KitePlayer.kt:210`). Consumers want 200 percent for
quiet material, and mpv and VLC let the multiply exceed unity and clip. Clipping a hot passage
sounds worse than folding it, so the boost lands with a soft clipper, and only when the gain is
above unity, so that everything at or below unity stays bit-exact with today.

The clipper has to live where the gain lives, the ring's render path, because the gain is applied
as frames LEAVE the ring (`GOTCHAS.md` section 6) and a clipper before the ring would see samples
before the boost. The render callback may call no allocator, lock, log or framework and the audit
allows only `memcpy memset bzero` as undefined names (`kiteplayer-rt/native/scripts/render-audit.sh:81`),
so `tanhf` is out. A rational saturator needs one division and no libm.

**Depends on:** nothing.

**Files.**
- Modify `kiteplayer-rt/native/include/kite_rt.h` (add `KPRT_GAIN_MAX`), `kiteplayer-rt/native/src/kite_rt_ring.c` (`kprt_ring_set_gain` range), `kiteplayer-rt/native/src/kite_rt_render.c` (the gain walk).
- Create `kiteplayer-rt/native/tests/test_ring_gain_boost.c`.
- Modify `core/internal/KotlinAudioRing.kt` (`applyGain`, the `0..1` check at line 388), `core/PlayerConfig.kt` (`AudioConfig`), `core/KitePlayer.kt` (`setVolume` at 209), `core/AudioPlayback.kt` (the KDoc at 498).
- Modify `kiteplayer-core/src/nativeTest/.../AudioRingDifferentialTest.kt`, create `kiteplayer-core/src/commonTest/.../VolumeCeilingTest.kt`.
- Modify `kiteplayer-rt/native/scripts/build-host.sh` line 57 and `buildSrc/src/main/kotlin/CompileKiteRtTask.kt` line 278: add `-ffp-contract=off` to the C flags. Reason in a comment: the clipper's `knee + (1 - knee) * s` must not be fused into an FMA on one side and not the other, or the differential oracle diverges by one ulp.

**Contract.**

```c
/* kite_rt.h, beside KPRT_GAIN_RAMP_MICROS */
#define KPRT_GAIN_MAX 2.0f
```

```c
/* kite_rt_render.c, above the render function. Identity below the knee, folds above it, never
 * reaches 1.0. Same operations in the same order as KotlinAudioRing.softClip. */
static inline float kprt_soft_clip(float x) {
    const float knee = 0.75f;
    float mag = x < 0.0f ? -x : x;
    if (mag <= knee) return x;
    {
        float excess = (mag - knee) / (1.0f - knee);
        float folded = knee + (1.0f - knee) * (excess / (1.0f + excess));
        return x < 0.0f ? -folded : folded;
    }
}
```

In the gain walk, both branches. Flat branch (line 263):

```c
if (wanted != 1.0f) {
    if (wanted > 1.0f) {
        for (i = 0; i < total; i++) {
            destination[i] *= wanted;
            destination[i] = kprt_soft_clip(destination[i]);
        }
    } else {
        for (i = 0; i < total; i++)
            destination[i] *= wanted;
    }
}
```

Ramp branch (line 281): after `destination[base + i] *= gain;` add
`if (gain > 1.0f) destination[base + i] = kprt_soft_clip(destination[base + i]);`. The check is per
frame, so a ramp that crosses unity clips only the frames above it.

Kotlin twin, `KotlinAudioRing.kt`:

```kotlin
internal fun softClip(x: Float): Float {
    val knee = 0.75f
    val mag = if (x < 0f) -x else x
    if (mag <= knee) return x
    val excess = (mag - knee) / (1f - knee)
    val folded = knee + (1f - knee) * (excess / (1f + excess))
    return if (x < 0f) -folded else folded
}
```

and the same two insertions in `applyGain`. The check at line 388 becomes
`require(target in 0f..GAIN_MAX)` with `internal const val GAIN_MAX = 2f`.

Engine:

```kotlin
// AudioConfig gains one field.
/** The loudest [KitePlayer.setVolume] accepts. 1 is unity; up to 2 boosts, with a soft limiter above unity. */
val volumeCeiling: Float = 1f,
// init: require(volumeCeiling in 1f..2f) { "volumeCeiling must be between 1 and 2, was $volumeCeiling" }
```

`KitePlayer.setVolume`: the check becomes `value in 0f..config.audio.volumeCeiling`, message names
the ceiling. KDoc: "from silence at 0 to unity at 1, and up to [AudioConfig.volumeCeiling] with a
soft limiter above unity". `AudioPlayback.kt:498` KDoc says the same.

**Tests.**
- `test_ring_gain_boost.c`: fill the ring with a square wave at plus and minus 1.0, set gain 2.0,
  render one buffer. Every output sample has magnitude below 1.0, and the sample for an input of
  1.0 equals `0.75f + 0.25f * (5.0f / 6.0f)` to 1e-6. Second case: gain 1.0 leaves the buffer
  bit-identical to a `memcpy` of the input. Third: gain 0.5 on the same input is bit-identical
  to today's arithmetic (input times 0.5, no clip). Add the file to whatever list
  `kiteplayer-rt/native/scripts/run-c-tests.sh` reads; if it globs `tests/*.c`, nothing to add.
- `AudioRingDifferentialTest`: `the boosted gain walk agrees sample for sample and never exceeds full scale`,
  built like `the gain walk agrees between the two implementations sample for sample` at line 688,
  with target 1.5 and a hot pseudo-random input (magnitudes up to 1.0). Assert C and Kotlin
  outputs identical (exact, as the existing tests do) and every magnitude below 1.0. Second:
  `a ramp crossing unity clips only the frames above it`: target 1.5 from 0.5, input constant
  0.9; the first frames (gain below 1) equal `0.9 * gain` exactly, the later ones are folded.
- `VolumeCeilingTest` (commonTest): default config refuses 1.01 with a message naming `1.0`;
  `AudioConfig(volumeCeiling = 2f)` accepts 2.0 and refuses 2.01; `AudioConfig(volumeCeiling = 0.5f)`
  and `3f` throw at construction.

**Steps.**
1. Red: write the C test, `./scripts/build-host.sh plain && ./scripts/run-c-tests.sh plain` in
   `kiteplayer-rt/native`. It fails: output magnitude 2.0 for the boosted case (or the gain
   setter refuses 2.0; either way red).
2. Add `KPRT_GAIN_MAX`, widen `kprt_ring_set_gain` if it clamps, add the clipper and the two
   insertions. Green on plain, asan, tsan, interpose.
3. `./kiteplayer-rt/native/scripts/render-audit.sh`: the undefined set must still be exactly
   `memcpy memset bzero`. If the compiler emitted a call for the division, the audit fails and
   the item stops here; report.
4. Red: the two differential tests. Kotlin twin. Green on `:kiteplayer-core:macosArm64Test`.
5. Red: `VolumeCeilingTest`. Engine change. `updateKotlinAbi`. Green on `jvmTest`.
6. Falsify: remove the per-frame `gain > 1.0f` check on the C side only; the differential test
   goes red. Restore.
7. `MASTER_PLAN.md`: add DEVICE-DAY step 19: "Play `sync1080p30.mp4` on Android at volume 2.0
   through Synkplay or the sample. PASS: louder, no crackle on the loud passages, no click when
   moving between 1.0 and 2.0." Delete the A1 row.

**Gate.** Tier 3: Tier 2 plus the owner's supervised run. The executor runs Tier 2 in full
(`GOTCHAS.md` section 3) and stops.

**Commit.** `audio: volume boosts to 2.0 through a soft limiter that lives with the gain`

---

### A2 The Android audio session id. LANDED 2026-09-03

Shipped as planned. Two notes for whoever does the next one:

- `PlayerSnapshot.audioSessionId` went LAST in the parameter list. Putting it beside `volume`
  where it reads best shifted every `componentN` after it, which is a source break for anyone
  destructuring the snapshot. The ABI diff went from 46 lines to 16 by moving it. Same lesson as
  A1's `volumeCeiling`: append to a data class, never insert.
- Adding a member to the internal `AudioTrackDriver` seam breaks the existing host suite's fake,
  which is the seam working as intended. The fake takes a default, so no test case changed.

### A2, as planned. Size S, Tier 2

**Why.** Android's `LoudnessEnhancer`, `Equalizer` and `Visualizer` attach to an audio session id.
`PlatformAudioTrackDriver` builds the track at `kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/AudioTrackDriver.kt:105-122`
and never reads `audioSessionId`. Nothing in the SPI carries a platform handle.

**Depends on:** nothing.

**Files.** Modify `core/spi/AudioSink.kt`, `core/PlayerState.kt` (`PlayerSnapshot`), `core/internal/PlaybackCore.kt`
(the snapshot build near line 4971), `kiteplayer-output/src/androidMain/.../AudioTrackDriver.kt`,
`kiteplayer-output/src/androidMain/.../AudioTrackSink.kt`. Test `kiteplayer-output/src/androidHostTest/.../AudioTrackSinkSessionIdTest.kt`
and a core commonTest on the snapshot.

**Contract.**

```kotlin
// AudioSink, new member with a default
/** A platform handle for effects that attach to the device stream: Android's audio session id. Null elsewhere. */
public val platformSessionId: Int? get() = null
```

```kotlin
// PlayerSnapshot, new field
/** The audio sink's platform session id, when it has one. Android only today. */
val audioSessionId: Int? = null,
```

`AudioTrackDriver` exposes `val sessionId: Int get() = track.audioSessionId` after the track is
built; `AudioTrackSink.platformSessionId` returns it once open, null before. `PlaybackCore` copies
`sink.platformSessionId` into the snapshot when the audio pipeline opens.

**Tests.** Host test: `android.media.AudioTrack` is a stub on the host, so the test drives the
sink through the existing fake driver (find how `AudioTrackSinkFactory` tests substitute a
driver; follow it) with a driver that answers session id 42, and asserts `platformSessionId == 42`
after `open` and `null` before. Core test: a scripted sink with `platformSessionId = 7` puts 7 in
`state.value.audioSessionId` after open.

**Steps.** Red both tests, add the members, `updateKotlinAbi`, green on
`:kiteplayer-output:testAndroidHostTest` and `:kiteplayer-core:jvmTest`. Falsify by returning
null from the driver.

**Commit.** `audio: the Android audio session id is published, so effects can attach`

---

### A3 ReplayGain from the container's tags. LANDED 2026-09-03

Shipped as planned, with one bug the tests caught that is worth carrying forward:

- **`replayGainFor` first read `session?.source?.metadata`, and `session` is null at both call
  sites**, because both run while the session is still being assembled. Every container tag was
  silently missed and the gain fell back to the configured default. It looked like it worked: the
  "tag makes it quieter" case read 1.0 against 1.0, which is what a correctly-applied 0 dB also
  looks like. The case that exposed it was the preamp one, where a cancelling +6.02 came out at
  twice the level instead of the same level, because only the preamp had been applied. The tags
  are an explicit parameter now, so the mistake cannot recur silently.
- A4's balance rides `TrimStage`, which this item built. It is per channel already; balance needs
  only `set(perChannel)` and a ramp for live changes.

### A3, as planned. Size M, Tier 1

**Why.** `PlayerSnapshot.metadata` already carries the container tags (`PlayerState.kt:34`) and
each track carries its own (`Tracks.kt:57`). `REPLAYGAIN_TRACK_GAIN` and friends arrive there for
FLAC, Ogg, and ID3v2 (TXXX frames become metadata keys), and Opus carries `R128_TRACK_GAIN`.
Nothing reads them.

**Depends on:** A1 for the ceiling only; without A1 the applied gain is clamped so peak times gain
never exceeds unity.

**Files.** Create `core/internal/ReplayGain.kt`, `core/internal/TrimStage.kt`. Modify `core/PlayerConfig.kt`
(`AudioConfig`), `core/PlayerState.kt` (`PlayerSnapshot.appliedReplayGainDb`), `core/internal/AudioPipeline.kt`
(the stage runs last, just before `submit`), `core/internal/PlaybackCore.kt` (compute at open and at
audio track switch). Tests `core/commonTest/.../ReplayGainTest.kt`, `TrimStageTest.kt`.

**Contract.**

```kotlin
public enum class ReplayGainMode { Off, Track, Album }

// AudioConfig gains:
/** Apply the container's ReplayGain or R128 tags as a pre-gain. Off by default. */
val replayGain: ReplayGainMode = ReplayGainMode.Off,
/** Added to the tag's gain, in dB. */
val replayGainPreampDb: Float = 0f,
/** Used when [replayGain] is on and the media carries no usable tag, in dB. */
val replayGainFallbackDb: Float = 0f,
```

```kotlin
// ReplayGain.kt, pure
internal data class ReplayGainTags(val trackGainDb: Float?, val albumGainDb: Float?, val trackPeak: Float?, val albumPeak: Float?)

/** Reads the tags out of container and stream metadata; stream keys win. Keys are matched case-insensitively. */
internal fun parseReplayGain(container: Map<String, String>, stream: Map<String, String>): ReplayGainTags

/** "-6.54 dB" to -6.54f; "R128_TRACK_GAIN" values are Q7.8 relative to -23 LUFS and are converted to the -18 LUFS ReplayGain reference by adding 5 dB. */
internal fun parseGainDb(value: String, r128: Boolean): Float?

/** The linear gain to apply, clamped so that peak * gain stays at or below [ceiling]. */
internal fun replayGainLinear(tags: ReplayGainTags, mode: ReplayGainMode, preampDb: Float, fallbackDb: Float, ceiling: Float): Float
```

`TrimStage(channels)` holds one linear gain per channel (`setGain(left, right)` for A4, `setAll(g)`
here), multiplies in place, and is skipped entirely when every gain is exactly 1f. `PlayerSnapshot`
gains `val appliedReplayGainDb: Float? = null`, null when the mode is off.

**Tests.** `parseGainDb("-6.54 dB", r128 = false) == -6.54f`; `"+2 dB"`; `R128` value `"-1280"` gives
`-5f + 5f = 0f`; garbage gives null. `replayGainLinear` with peak 0.9 and gain +3 dB and ceiling 1
returns `1 / 0.9` not `1.41`. A `ScriptedBackend` (core commonTest has it) session whose container
metadata says `-6.02 dB` in `Track` mode: the samples reaching the scripted sink are half
amplitude, and `state.value.appliedReplayGainDb == -6.02f` within 0.01. Mode `Off`: bytes identical
to today (bit-exact off).

**Steps.** Red the pure tests, write `ReplayGain.kt`. Red the stage test, write `TrimStage`. Red
the session test, wire the pipeline and the snapshot. `updateKotlinAbi`. Falsify by skipping the
clamp. Note in KDoc which tags are read.

**Commit.** `audio: ReplayGain and R128 tags are honoured as a clamped pre-gain`

---

### A4 Stereo balance. LANDED 2026-09-03

One thing to carry forward, and it cost two debugging rounds:

- **A live balance change is invisible for one ring depth**, because the trim is applied on the
  way IN and cannot reach audio already buffered. The first test set the balance and measured
  200 ms later, which is inside the drain, so it read the old setting and looked exactly like the
  code not working. The test waits 400 ms now, and both public members say so. This is the same
  law that put the VOLUME on the ring's read side; the difference is that a balance is set once
  and a volume is swept, so paying the depth is right here and wrong there.
- The scripted sink's channel peaks are running maxima, so a test that changes something mid
  playback must clear them or it measures the loudest moment of the whole session.

### A4, as planned. Size S, Tier 1

**Why.** `KitePlayer.kt:49` says stereo balance is "absent rather than stubbed". It is absent. One
per-channel multiply in `TrimStage` gives it.

**Depends on:** A3 (the stage).

**Files.** Modify `core/KitePlayer.kt` (new `setBalance`), `core/PlayerState.kt` (`balance`),
`core/internal/PlaybackCore.kt` (a command), `core/internal/AudioPipeline.kt`. Test `BalanceTest.kt`.

**Contract.**

```kotlin
/**
 * Stereo balance, from fully left at -1 through centre at 0 to fully right at 1. Applied as a
 * per-channel gain before the volume, constant-power: at 0 both channels are unity.
 * @throws IllegalArgumentException outside -1 to 1 or not finite.
 */
public fun setBalance(value: Float)
```

Gains: `left = if (value > 0) 1 - value else 1`, `right = if (value < 0) 1 + value else 1`. Mono and
multichannel: applied to the first two channels only, documented. `PlayerSnapshot.balance: Float = 0f`.

**Tests.** `setBalance(1f)` makes the left channel silent and the right unchanged at the scripted
sink; `0f` is bit-exact with today; `-1.01f` throws. Also rewrite the sentence at `KitePlayer.kt:49`
(it also wrongly lists chapters, queues and frame stepping as absent; Q10 covers the rest, this
commit fixes the balance word only).

**Commit.** `audio: stereo balance, one multiply per channel before the volume`

---

### A5 Audio-only playback, without reopening the container. LANDED 2026-09-03

Shipped as planned. Two notes:

- **A rebuild must not silently un-park.** A track switch or a decoder recovery builds a fresh
  session whose flags start at their defaults, so the park is reasserted at all three sites where
  a session is assigned. Missing one would un-park video the moment a user changed audio track.
- **`PlayerConfig.videoEnabled` went last in the parameter list.** Third data class in this
  program to need that, after A1's `volumeCeiling` and A2's `audioSessionId`. It is worth
  treating as the rule rather than the exception: append to a data class, never insert.

### A5, as planned. Size M, Tier 1

**Why.** The only way to stop video work is `selectTrack(TrackKind.Video, null)`, which reopens the
container and seeks back (`KitePlayer.kt:507`, sample `Main.kt:269-274`). An app going to the
background wants the video lane to stop spending CPU, instantly, and to come back without a
reopen.

**Depends on:** nothing. S10 uses it.

**Files.** Modify `core/PlayerConfig.kt` (`videoEnabled`), `core/KitePlayer.kt` (`setVideoEnabled`),
`core/PlayerState.kt` (`videoEnabled`), `core/internal/PlaybackCore.kt` (the video lane's packet
skip). Tests `VideoEnabledTest.kt` on the harness.

**Contract.**

```kotlin
// PlayerConfig
/** False opens media with video decoding parked: audio plays, no frame is decoded or presented. */
val videoEnabled: Boolean = true,
```

```kotlin
/**
 * Parks or resumes video decoding in place. Parked, video packets are discarded before the decoder
 * and the picture freezes on the last frame; resumed, decoding restarts at the next keyframe and,
 * when the source can seek, a precise seek to the current position brings the picture back at
 * once. Audio and subtitles are untouched. No reopen.
 */
public fun setVideoEnabled(enabled: Boolean)
```

Mechanism: the video lane already has a pre-decode packet skip rule (`skipVideoPacketBeforeDecode`,
beside `SyncLaw`, from the frame-drop work). A parked lane answers "skip" for every packet and
records nothing as dropped (a new counter `PlaybackStats.parkedFrames` is optional; do not count
them as drops). Resume: clear the flag; if `isSeekable`, queue a precise seek to the current
position so the picture returns immediately; otherwise wait for the next keyframe.

**Tests.** Harness (`CoreHarness`, `MediaScript` in core commonTest): open a scripted A/V item,
`setVideoEnabled(false)`, advance 2 s: the scripted video decoder received zero packets after the
call, audio position advanced 2 s, no reopen happened (the backend's open count stays 1).
`setVideoEnabled(true)`: within one GOP the renderer receives a frame. `PlayerConfig(videoEnabled = false)`
opens with the lane parked from the first packet.

**Steps.** Red the three harness tests, implement, `updateKotlinAbi`, green `jvmTest` and one
native compile. Real media (`macosArm64Test`, `kiteplayer-ffmpeg`): `sync1080p30.mp4` parked for 3 s
shows `decodedVideoFrames` unchanged while `position` advances.

**Commit.** `core: video decoding parks and resumes in place, no reopen`

---

### A6 A speed change without a click. ATTEMPTED AND REVERTED 2026-09-03

**Read this before rebuilding it.** The item below asserts a click exists. That was never
verified, and an attempt to verify it failed in a way worth recording:

- The fade was implemented exactly as described and the test could not tell it apart from no fade
  at all. In the virtual harness the seek's flush and the refill both happen between two device
  pumps, so the scripted device never receives the silence a click would come from. A test that
  passes identically with and without the fix proves nothing about the fix.
- `AudioPlayback.speed`'s own KDoc already calls the change "one brief, gapless-sounding rebuffer,
  the same trade mpv makes". Someone judged this with ears and found it acceptable.
- The fix costs a real 20 ms delay on every speed change, which is a cost paid by everyone for a
  benefit nobody has heard.

Everything was reverted. The next step is not code: play a file, nudge the speed repeatedly, and
listen. If the click is real, the design below is right and the diff is recoverable from the
session that wrote this.

### A6, as planned. Size S, Tier 1

**Why.** `setSpeed` lands as a precise seek to the current position (`PlaybackCore.kt:1330-1341`),
which flushes the ring: the device plays a hard edge. The engine changes speed through a flush on
purpose (`TempoStage.kt:63-64`), so the fix is not to remove the flush but to hide it: the ring
already has a 5 ms gain ramp.

**Depends on:** nothing.

**Files.** Modify `core/internal/PlaybackCore.kt` (the speed command). Test on the harness with
the scripted sink recording samples.

**Contract.** No public change. The speed command becomes: set the ring gain to 0, wait one ramp
(`GAIN_RAMP_DURATION`, 5 ms, plus one device buffer), perform today's seek and flush, restore the
gain to the user's volume. `PlayerSnapshot.volume` never changes during it.

**Tests.** Harness: play a constant full-scale tone, `setSpeed(1.5)`: the recorded samples around
the flush contain no jump larger than the ramp slope allows (assert the maximum absolute
difference between consecutive samples across the whole buffer is below `2 * gainSlopePerFrame`),
where today's code fails that assertion at the flush boundary. Falsify by removing the fade.

**Commit.** `audio: a speed change fades through its flush instead of clicking`

---

### A7 Sleep timer with a fade. LANDED 2026-09-03

Two departures from the plan, both deliberate:

- **The fade is computed from the time remaining, not stepped every 50 ms** as the plan suggested.
  A stepped fade strands the level wherever it was if the actor is late for a pass, and the actor
  is late whenever anything else is busy. Deriving `remaining / fade` each pass is self-correcting
  and needs no timer of its own.
- **It is a separate multiplier on the ring gain, not the volume.** The plan said to step "the ring
  gain", which would have meant reading and writing the user's own volume. The multiplier keeps the
  published volume still throughout, which is what stops a UI bound to it sliding to the bottom
  while the user watches.

Adding a pass handler moves the order ratchet in `PlaybackCoreTest`. That is the ratchet working:
update it in the same commit with the reason for the placement, which here is after the status has
settled and before the queue advances.

### A7, as planned. Size S, Tier 1

**Why.** Every app writes its own. The library owns the only clean way to do it: a slow volume
ramp the ring cannot do by itself (its ramp is fixed at 5 ms), then pause, then restore the
volume so the next play is not silent.

**Depends on:** nothing.

**Files.** Modify `core/KitePlayer.kt`, `core/PlayerState.kt`, `core/internal/PlaybackCore.kt`.
Test `SleepTimerTest.kt` on the harness with virtual time.

**Contract.**

```kotlin
public sealed interface SleepTimer {
    public data class After(val duration: Duration) : SleepTimer
    public data class At(val position: Duration) : SleepTimer
    public data object EndOfItem : SleepTimer
}

/** Pauses when [timer] fires, fading the volume over [fade] first, then restores the volume. Null cancels. */
public fun setSleepTimer(timer: SleepTimer?, fade: Duration = 3.seconds)
```

`PlayerSnapshot.sleepTimer: SleepTimer? = null`. The fade steps the ring gain every 50 ms from the
user's volume to 0 (each step rides the ring's own 5 ms ramp), then pauses, then sets the gain
back to the user's volume while paused. `EndOfItem` fires at `Ended` and does not advance the
queue.

**Tests.** Virtual time: `After(10.seconds)` with `fade = 2.seconds`: at t = 8 s the volume is
unity, at t = 9 s the gain reaching the sink is about half, at t = 10 s the status is `Paused` and
`state.value.volume` is the original. `EndOfItem` in a two-item queue: paused at the boundary,
`queueIndex` unchanged. Cancel before it fires: nothing happens.

**Commit.** `core: a sleep timer that fades, then pauses, then gives the volume back`

---

### A8 The METER landed 2026-09-03; the file-measuring call is what remains

`LoudnessMeter` is done and held to the standard's own numbers. Two test mistakes worth carrying
forward, both found by falsifying rather than by reading:

- **The LFE case used a DC constant** and could never fail. The meter's own 38 Hz high pass removes
  0 Hz entirely, so a constant in the LFE looks excluded whether it is weighted or not. It carries a
  real sine now, and counting the LFE turns the case red.
- **The relative gate was untested.** The quiet passage in the gating case sat below -70 LUFS, so
  the ABSOLUTE gate already removed it and the relative rule was never exercised. A second case now
  uses a passage 20 LU down but well above -70, which only the relative rule can exclude.

What is left is the `kiteplayer-ffmpeg` half below: the sample conversion and the one call that
measures a file. Write the conversion once; K9's waveforms need the same thing.

### A8, as planned. Size M, Tier 1 (plus one real-media test)

**Why.** A3 needs a number when the tags are missing, and a library that can say "this file is
-14 LUFS" is a library apps stop wrapping. The `ebur128` filter is not compiled into the LGPL
build (K3 adds it), and a pure Kotlin meter is testable in virtual time against synthesised
buffers and against the host `ffmpeg` binary as an oracle.

**Depends on:** nothing. K9's sample conversion helper is shared; write it here first and K9 reuses it.

**Files.** Create `core/audio/LoudnessMeter.kt` (pure DSP, public), `kiteplayer-ffmpeg/src/commonMain/.../AudioSamples.kt`
(frame bytes to interleaved floats per `SampleFormat`), `kiteplayer-ffmpeg/src/commonMain/.../AudioAnalysis.kt`
(`measureLoudness`). Tests `core/commonTest/.../LoudnessMeterTest.kt`, `kiteplayer-ffmpeg/src/jvmTest/.../LoudnessOracleTest.kt`.

**Contract.**

```kotlin
public data class LoudnessResult(val integratedLufs: Double, val samplePeak: Float, val blocksMeasured: Int)

/** ITU-R BS.1770-4 integrated loudness with the two-stage gate. Feed interleaved float samples; read once. */
public class LoudnessMeter(sampleRate: Int, channels: Int) {
    public fun feed(samples: FloatArray, frames: Int)
    public fun result(): LoudnessResult
}
```

Filter design, per channel, two biquads in series (coefficients computed from the sample rate;
these formulas are the standard's, restated):

```
pre-filter (high shelf):  f0 = 1681.974450955533, G = 3.999843853973347 dB, Q = 0.7071752369554196
  K = tan(pi * f0 / fs); Vh = 10^(G/20); Vb = Vh^0.4996667741545416
  a0 = 1 + K/Q + K*K
  b0 = (Vh + Vb*K/Q + K*K) / a0;  b1 = 2*(K*K - Vh) / a0;  b2 = (Vh - Vb*K/Q + K*K) / a0
  a1 = 2*(K*K - 1) / a0;  a2 = (1 - K/Q + K*K) / a0
RLB (high pass):  f0 = 38.13547087602444, Q = 0.5003270373238773
  K = tan(pi * f0 / fs); a0 = 1 + K/Q + K*K
  b0 = 1; b1 = -2; b2 = 1;  a1 = 2*(K*K - 1) / a0;  a2 = (1 - K/Q + K*K) / a0
```

Blocks of 400 ms with 75 percent overlap (a new block every 100 ms). Channel weights: first two
and centre 1.0, surrounds 1.41, LFE 0 (channel roles from the count: 1 or 2 plain; 6 is
L R C LFE Ls Rs). Block loudness `-0.691 + 10 log10(sum over channels of weight * meanSquare)`.
Absolute gate: drop blocks below -70 LUFS. Relative gate: drop blocks more than 10 LU below the
mean loudness of the blocks that survived the absolute gate. Integrated = mean of the survivors,
same formula. Fewer than one block: `integratedLufs = Double.NEGATIVE_INFINITY`.

`AudioSamples.toFloatInterleaved(frame: Frame): FloatArray` handles `S16`, `S16P`, `S32`, `S32P`, `Flt`,
`Fltp`, `Dbl`, `Dblp`, `U8` from `frame.copyPlanesToByteArray()` (planar formats arrive plane by
plane; the KDoc at `Frame.kt:30-31` says so). Anything else throws `IllegalArgumentException`
naming the format.

```kotlin
/** Decodes the primary audio stream and measures it. Reads the whole file; seconds on a long album. */
public suspend fun AudioAnalysis.measureLoudness(item: MediaItem): LoudnessResult
```

**Tests.** `LoudnessMeterTest`: a 997 Hz sine at amplitude 0.1 (-20 dBFS) at 48 kHz for 5 s
measures -23.0 LUFS within 0.1 (the standard's reference: a full-scale 997 Hz sine reads
-3.01 LUFS); the same at 44.1 kHz within 0.1; digital silence returns negative infinity; a
3 s tone followed by 3 s of silence at -80 dBFS equals the tone alone (the absolute gate).
`LoudnessOracleTest` (jvmTest, real media): `audio-flac.flac` measured here versus
`ffmpeg -nostats -i testmedia/audio-flac.flac -af ebur128 -f null - 2>&1 | grep 'I:'` parsed, within
0.5 LU; skips itself when `ffmpeg` is not on PATH, the way `testmedia.sh` documents.

**Commit.** `audio: an EBU R128 meter in Kotlin, and one call that measures a file`

---

### A9 A ten-band equaliser. LANDED 2026-09-03

Three things worth carrying forward:

- **A session test cannot measure a band.** The scripted source is a constant, which is a signal at
  0 Hz, and a peaking filter at 1 kHz passes 0 Hz at unity: that is what makes it a peaking filter
  rather than a shelf. The first session test asserted a band boost raised the level and failed
  because the DSP was RIGHT. The session tests use the preamp, which scales everything including a
  constant; the bands are measured against real sines in the stage's own test.
- **`EqualizerSettings.Bands` must be declared before `Flat` in the companion.** `Flat` constructs
  an instance whose `init` reads `Bands`, and a companion initialises in source order, so the other
  way round the list is still null and the entire class fails to load with
  `ExceptionInInitializerError`. Every test in the file failed at once, including the trivial ones,
  which is the signature of a class-initialisation problem rather than a logic one.
- **A pipeline rebuild needs the settings cache invalidated.** The stage is reasserted per buffer
  only when the settings object changes, so a fresh pipeline, which starts flat, would never have
  been configured. Cleared where the rebuild is detected.

### A9, as planned. Size M, Tier 1

**Why.** The parity map lists it, Synkplay's users ask for it, and every knob it needs already
exists in the pipeline shape: a stage, per-channel state, bit-exact off.

**Depends on:** A3 (stage placement convention).

**Files.** Create `core/audio/Equalizer.kt` (public settings), `core/internal/EqualizerStage.kt`.
Modify `core/KitePlayer.kt` (`setEqualizer`), `core/PlayerState.kt`, `core/internal/AudioPipeline.kt`
(after the tempo stage, before `TrimStage`). Tests `EqualizerStageTest.kt`, `EqualizerTest.kt`.

**Contract.**

```kotlin
/** Ten ISO octave bands, 31 Hz to 16 kHz. Gains in dB, -12 to 12. All zero is bypass, bit-exact. */
public data class EqualizerSettings(val gainsDb: List<Float> = List(10) { 0f }, val preampDb: Float = 0f) {
    init {
        require(gainsDb.size == 10) { "ten bands, got ${gainsDb.size}" }
        require(gainsDb.all { it in -12f..12f }) { "band gains stay within -12 to 12 dB" }
        require(preampDb in -12f..12f) { "preamp stays within -12 to 12 dB" }
    }
    public val isFlat: Boolean get() = preampDb == 0f && gainsDb.all { it == 0f }
    public companion object {
        public val Flat: EqualizerSettings = EqualizerSettings()
        public val Bands: List<Float> = listOf(31.25f, 62.5f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
    }
}

public fun setEqualizer(settings: EqualizerSettings)
```

Each band is an RBJ peaking biquad with `Q = 1.41`, coefficients rebuilt when settings or the
sample rate change (a band whose centre is above `sampleRate / 2` is skipped). Preamp is a plain
multiply. State per channel, `Float` maths, direct form 1. A settings change swaps coefficients
at a buffer boundary; the transient is accepted and documented.

**Tests.** Stage: white noise (seeded) through `gainsDb[5] = 6f`: the power at 1 kHz measured by a
Goertzel bin rises by 6 dB within 1 dB, and at 125 Hz changes by less than 1 dB. `Flat` leaves the
buffer bit-identical. A 96 kHz stream with a 16 kHz band still builds. Player: `setEqualizer` shows
in `state.value.equalizer`; a value with eleven bands throws at construction.

**Commit.** `audio: a ten-band equaliser in the pipeline, bypassed bit-exact when flat`
