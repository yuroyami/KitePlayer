# KPKMP decisions: the arguments behind the rules

> Moved verbatim out of KPKMP.md on 2026-08-18. Not one line rewritten; see
> `scripts/verify-kpkmp-split.py`.
>
> **Read this before re-litigating anything.** KPKMP.md's rule stands: decisions here are already
> made. This file exists so that a decision can be CHECKED cheaply rather than re-argued expensively.
>
> Contains: the design digests (8), the verification protocol (9), Horizon A's executable run (10),
> and Horizon B's product roadmap (11). Section numbers unchanged.

## 8. Design digests

### 8.1 PlaybackCore (built in A5)

One session actor coroutine on a dedicated single-thread dispatcher owns playback state,
ordinary command replies, track selection, epochs and published snapshots until terminal close.
After the actor has completed, one parentless finalizer on an independent dispatcher closes the owned
dispatchers and alone publishes the final close snapshot and shared terminal result. This sequential
ownership handoff is the only code outside the actor that may publish state. Workers (demux
pump, audio decoder, video decoder, audio feeder, video scheduler) are coroutines on their
own single-thread dispatchers (a decoder context is touched by exactly one thread),
communicating only through the existing queues and typed commands. Every accepted non-terminal
state-changing command is a message: suspending routes carry their own `CompletableDeferred` reply, while
fire-and-forget routes omit or discard one. The two close routes instead share one parentless terminal
result. All worker terminal outcomes (including crashes) arrive on one channel the actor selects on,
so a dead worker becomes a handled failure, not a hang.

The loop is level-triggered: handlers are small, read state, decide, and may only lower
the shared wake-up deadline; none is a transition hook, so a condition that becomes true
at the wrong moment is noticed on the next pass and no command sequence can wedge the
player. Each iteration, in this exact order, asserted by a test:

```
drainCommands()
handleTrackChanges()
handleAudioFill()
handleVideoWrite()
handlePlaybackRestart()
handlePlaybackTime()
handleBuffering()
handleSubtitles()        // empty body this run, present so the order is stable
handleEof()
handleLoop()
handleQueuedSeek()       // exactly one seek per iteration
publishSnapshot()
awaitWork(wakeAt)        // select on the command channel, 50 ms floor
```

**Command legality.** Every command has a documented legal-state rule and completes exactly once.
Except repeated `close` and `closeAndAwait`, every command rejects once terminal close is requested;
the rules below apply during the live lifetime:

| Command | Rule |
|---|---|
| open | Legal from Idle, Ended and Failed. From any playing state it requires `stop()` first (an explicit replace policy is Horizon B). Cancellation of a suspended `open` leaves Idle, never a half-open graph. |
| play / pause | Idempotent in their own state; requests queue during Opening and Seeking and reject after close. The actor publishes Paused only after the sink is quiescent and clocks are frozen; the non-suspending facade call itself is not that completion fence. |
| seek (suspend) | Completes with the landed position or a typed failure, exactly once. Concurrent suspend seeks queue; each completes (Applied or Superseded). |
| seekLater | Fire-and-forget, coalescing by contract (the merge rules in `SeekRequest`); rejects synchronously after terminal close. |
| stop | Preempts open, seek and drain; returns to Idle after teardown of the session's workers completes. |
| close / closeAndAwait | Idempotent terminal routes sharing one result; the non-suspending route only requests close, while the awaited route proves success or rethrows the same typed `RuntimeCompromised` outcome to every non-cancelled waiter after actor termination. The one Close result and every other outstanding command resolve exactly once. Caller cancellation stops only that wait. Teardown has a ten-second request deadline, but its non-cancellable ownership join can outlive the deadline when a native call wedges and may require process termination (full isolation is Horizon B). |
| selectTrack | Legal while open; reopens per digest 8.3. |
| attachRenderer / detachRenderer | Legal in every live state. The actor fences outstanding renderer work before applying detach; the non-suspending facade return is only the request boundary. |

Playback failures from suspending commands use `PlaybackException` (wrapping the `PlaybackError`
value); documented argument, state and unsupported-operation refusals retain their standard exception
types. Caller cancellation stays `CancellationException` and is never converted into a failure or an
end-of-stream. A terminal failure is ALSO retained in
`PlayerSnapshot.error` until replaced, because a replay-zero event stream must never be
the only record of a fatal error.

**Stream status and the start rendezvous.** Per selected stream:
`Syncing, Ready, Playing, Draining, Eof`. Playback starts only when every selected stream
is at least Ready (BufferPolicy thresholds or already ended) and the initial fill happened
paused.

**End of stream is six conditions:** demuxer end; audio decoder drained; video decoder
drained (`StreamDecoder.isDrained` via the backend); draining (decoders done, sink playing
out); sink drained (bounded: device loss during drain completes the drain as failed rather
than polling forever); keep-open (last frame stays). Ended only when every selected stream
is Eof, queues empty, sink drained; never while paused with a frame on screen. EOS travels
in band as a null packet; the per-decoder finished marker holds a generation.

**Buffering needs two signals:** a remembered demuxer underrun AND a current output
underrun; leave when the start rule holds again; the demuxer flag is sticky until the
cache recovers.

**Open sequence:** Opening; open the backend session on the demux dispatcher; select
defaults (first non-cover-art video; audio by language preference, else default
disposition, else first); create decoders from the session's factory lists, deselecting a
stream whose factories all fail (open fails only when nothing playable remains); open the
sink and build the audio pipeline against the negotiated format; fill paused until Ready
everywhere; present the first frame with the clock stopped; return Paused.

**Seek execution order (quiesce first; this order is the contract):**

1. Coalesce per `SeekRequest`/`SeekPhase`; bump the requested epoch; publish Buffering.
2. Stop the sink (the device callback is provably out before anything it reads is
   touched). Ask the pump and every decoder/feeder/scheduler worker to quiesce at a safe
   boundary; await bounded acknowledgements.
3. Fence the renderer: no submission for the old epoch after this point.
4. Flush each decoder ON its owning worker with the new generation (D22's
   `flush(newGeneration)`).
5. Clear packet queues, frame queue and the audio ring, now that every consumer is
   quiescent.
6. Seek the source on its owner.
7. Restart workers under the acknowledged epoch; preroll; for Precise and
   KeyframeThenRefine discard frames earlier than target minus the precise tolerance;
   overshoot uses the `OVERSHOOT_BACKOFF_US` ladder when the first decoded frame proves a
   late landing.
8. Anchor clocks from the first accepted frame, present it, restore play state, complete
   the command exactly once.

Generations remain defence in depth at every hop; they do not replace the acknowledgements
above.

**Pause and resume:** pause freezes clocks after the sink is quiescent and CONSUMES the
final device anchor first (a late callback must not re-anchor a frozen clock). Resume
shifts the scheduler's `frameTimerNanos` forward by `now - videoClock.lastUpdatedNanos`
and re-anchors the clock at its frozen value.

**Timestamp rules in the wild:** missing video pts uses best-effort then synthesis (D10);
missing audio pts uses the exact sample counter; a jump of 5 s or more in a
non-discontinuous stream is a stream reset at the new position; `timestampsMayJump`
containers tolerate jumps to 5 s and already use the 10 s duration ceiling.

### 8.2 The KitePlayer facade (built in A5)

The v-now surface, exactly:

```
state: StateFlow<PlayerSnapshot>
progress: StateFlow<Progress>
stats: StateFlow<PlaybackStats>
events: SharedFlow<PlayerEvent>
position(): Duration
suspend open(media: MediaItem)
play(); pause()
suspend seek(to: Duration, mode: SeekMode = Precise)
seekLater(to: Duration, mode: SeekMode = KeyframeThenRefine)
suspend stop()
setSpeed(Double)                          // honest per D13; validates finite
setVolume(Float); setMuted(Boolean)       // real via the GainStage; validated
setLoop(LoopMode)                         // LoopMode.All rejects: no queue exists yet
suspend selectTrack(kind: TrackKind, track: TrackId?)   // video and audio only
attachRenderer(renderer: VideoRenderer); detachRenderer()
close()                                      // non-suspending terminal request
suspend closeAndAwait()                      // shared terminal result; caller cancellation only stops its wait
companion: create(config: PlayerConfig = PlayerConfig()): KitePlayer
```

Not in this run (marked in the truth ledger, not stubbed): external subtitles, filter
chains, a command escape hatch, chapters (empty list plus marker), playlist/queue,
frame stepping, balance. `create()` resolves `config.backends`; on macOS the explicit
pair is the FFmpeg `MediaBackend` and the Apple `OutputBackend` (D34); a null backend is a
typed configuration error on every target, never reflection. Warnings and
errors flow through `events` AND the snapshot per digest 8.1. `stats` separates scheduler
counters from renderer counters per D21. The macOS CLI sample shrinks to: parse args, create,
open, play, collect progress, window wiring, summary. The private UIKit host owns its Play,
Pause and Seek controls plus the bounded smoke orchestration around the same facade.

### 8.3 Track selection limitation

`selectStreams` permits one call before the first read, so A5's `selectTrack` reopens the
source and seeks back under a new generation. Legal ONLY when the source reports seekable
(D32); a non-seekable source gets a typed rejection. Seamless switching is Horizon B (B6).

### 8.4 Rotation (built in A6)

KiteCodec already exposes `rotationDegrees`. Add it to `PlayerStreamInfo`, populate in
`toPlayerStream()`, apply in the AppKit renderer's `makeImage` via a rotated `CGContext`
(90/270 swap output dimensions, 180 flips). `VideoSize` is storage, not presentation. Full
display-matrix support (mirror, arbitrary affine) is Horizon B (B5). Test: a
`-display_rotation 90` clip draws with swapped dimensions.

### 8.5 Simulation invariants (A5 testing)

Scripted fake backend, sink and renderer under virtual time plus seeded fault injection.
Invariants, not outcomes: no frame from a superseded generation is ever presented OR
AUDIBLE; within a generation presented timestamps never decrease; every frame and packet
is closed exactly once (LeakLedger); the session reaches a terminal state in bounded
virtual time; drift stays inside the sync law's tolerance; status transitions follow the
state machine; every command completes exactly once. One hundred seeds per run; a failing
seed is checked in by name. Virtual-time tests cannot find native races: A5 also runs one
real-thread native stress test (seek and close hammered during playback of a real clip)
as its own gate step.

---

## 9. Verification protocol

The standing gate for every phase, in THREE TIERS chosen by what the phase changed. A phase is
done only when its tier passes, rerun for real. A cached `UP-TO-DATE` run proves nothing; section
2 says why, and the rule bears repeating here because the C archives and the cinterop klib have
both been observed stale while Gradle reported success.

**Why the tiers exist, owner-mandated 2026-08-10.** Promoted at the interlude (I-15) from B1's
base gate, this section became one undivided gate, and the interlude then ran that whole gate six
times, once per sub-phase. Sub-phase I.2 changed only prose in Markdown files, and verifying
spelling corrections cost three real-media sample runs and a seventeen-target cross compile. That
is measured waste, not diligence. The split below is the correction: the pre-S1.a.4 Tier 1 block
was measured at FOURTEEN SECONDS on 2026-08-10. After S1.a.4 added the kitert coupling read, the
complete expanded block measured 8.24 seconds on 2026-08-11. Both numbers are observed wall-clock
runs, not budgets or guarantees, so Tier 1 runs every phase without exception and no schedule
pressure can ever justify skipping it; the expensive half runs when what changed can actually
break it. (The first estimate was seven seconds, taken over a smaller subset before Tier 1's
contents were settled. It is retained as history rather than left as the current number, because
a gate document that rounds its own cost downward is how a gate starts getting skipped.)

**The three rules that stop this from becoming a loophole.**

1. *The trigger is the changed path, never the executor's confidence.* A tier is selected by the
   mechanical file rules below. An executor may not choose a lower tier because a higher one is
   slow, and may not choose a lower tier because the change "obviously cannot" break anything;
   the interlude's own I-04 fix looked obviously local and broke an unrelated segment test.
2. *Every Execution log entry names the tier that ran and the rule that selected it.* An entry
   with no tier named is an incomplete entry.
3. *A defect must never become load-bearing.* Tier 1 every phase is what enforces this, and it is
   why the fast block is not negotiable: a later phase must never be built on an ungated one. The
   interlude exists because seams BETWEEN gated sub-phases went unowned, so deferring verification
   across whole horizon items would turn the same class of defect from a fix into a redesign.

On this machine every `apiDump`, `apiCheck` and cinterop invocation in KiteCodec needs
`-Pkitecodec.hostTargetsOnly=true`: only macosArm64 has an FFmpeg tree here, and without the flag
the other targets fail on unresolved `ffmpeg` references. The B1 log recorded that twice as a
deviation; it is now part of the protocol. A scratch clone additionally needs the Android SDK
location that the gitignored `local.properties` carries in the working tree, or
`assembleAndroidMain` fails before its task graph exists: copy `local.properties` into the clone
or export `ANDROID_HOME` first. Both clean-clone requirements in this paragraph were found by
running this gate from clean clones at I.1, not deduced.

`checkPublicationReadiness` is a named S5-only step; it belongs to no tier, and the commit that
first adds it to any block must make it green.

### Tier 1, FAST. Every phase, no exception. Measured 8.24 seconds

Selected by: every change, including a change to prose alone. Nothing is exempt.

```bash
# Neither block needs a build: these read source text, committed baselines and the tree itself,
# or run already-built host binaries. Run the C suites' build step only when a C file changed.
cd ../KiteCodec
./gradlew checkCinteropCoupling                       # counts source text, no build required
./native/kitecodec-c/scripts/check-deleted-surface.sh  # reads the tree only
./native/kitecodec-c/scripts/run-c-tests.sh plain

cd ../KitePlayer
./gradlew checkKitertCoupling                         # present scoped Kotlin source, no product build
./gradlew checkKotlinAbi                              # committed dumps across five library modules
./gradlew :kiteplayer-core:jvmTest :kiteplayer-subtitles:jvmTest
kiteplayer-rt/native/scripts/run-c-tests.sh plain
kiteplayer-rt/native/scripts/render-audit.sh
kiteplayer-rt/native/scripts/source-discipline.sh

# The em dash scan, both repositories, must print nothing. The pattern is the escape text
# backslash-u2014 (expanded by the shell), so no literal em dash exists in the repos. The scan
# walks `git ls-files`, replacing the extension allowlist the gate used through B1: an allowlist
# cannot reach an extensionless file, and both LICENSE files carried an em dash through every
# widened run until the interlude (I-18) found them. Tracked files only, so the gitignored scratch
# checkouts under .claude/, build/ and vendor/ are excluded by construction rather than by flags.
# grep exits 1 when it finds nothing, and for this scan that exit is the passing outcome; do not
# wrap it in `set -e` and read the exit as failure.
cd ../KiteCodec  && git ls-files -z | xargs -0 grep -n $'\u2014'
cd ../KitePlayer && git ls-files -z | xargs -0 grep -n $'\u2014'
```

**What Tier 1 cannot catch, stated so nobody reads a green Tier 1 as a green gate.** No data race
(that is tsan), no wrong-architecture archive (that is the per-target compile), no cinterop
surface change (that needs the klib built), no real-media regression, and nothing at all about a
target whose archive this run did not build.

### Tier 2, MEDIUM. Roughly 10 to 15 minutes

Selected by ANY of these, mechanically, by changed path:

- any file under `native/` in either repository (C sources, headers, scripts, corpus)
- any file under `buildSrc/` in either repository
- any file under `kitecodec-gradle-plugin/src/`
- any `*.def`, any `build.gradle.kts`, any `gradle/libs.versions.toml`
- any Kotlin under `nativeMain`, `nativeTest`, `jvmMain`, `jvmTest`, `jvmAndAndroidMain`,
  `jvmAndAndroidTest`, `androidMain`, `androidHostTest`, `androidDeviceTest`, `appleMain`,
  `appleTest`, `macos*Main`, `macos*Test`, `ios*Main` or `ios*Test` (the wildcard includes
  shared iosMain/iosTest), and from phase W also `linux*Main`, `linux*Test`, `mingw*Main`,
  `mingw*Test` and `realBackendTest`
- the completion of any Horizon item, unconditionally, whatever it changed

```bash
# Tier 1 first, then everything below. Build before you audit: every audit here reads the archive
# or the klib that the gradle lines produce.
cd ../KiteCodec
./gradlew :kitecodec-core:cinteropFfmpegMacosArm64 -Pkitecodec.hostTargetsOnly=true
./gradlew :kitecodec-core:apiCheck -Pkitecodec.hostTargetsOnly=true
./gradlew :buildSrc:test
./gradlew :kitecodec-gradle-plugin:test
./native/kitecodec-c/scripts/build-host.sh asan  && ./native/kitecodec-c/scripts/run-c-tests.sh asan
./native/kitecodec-c/scripts/build-host.sh tsan  && ./native/kitecodec-c/scripts/run-c-tests.sh tsan
./native/kitecodec-c/scripts/run-c-tests.sh interpose   # plain binaries, accounting REQUIRED (I-08)
./native/kitecodec-c/scripts/replay-corpus.sh asan
./native/kitecodec-c/scripts/symbol-audit.sh            # the shipped macos_arm64 archive
./native/kitecodec-c/scripts/klib-metadata-diff.sh --check
./gradlew :kitecodec-core:macosArm64Test
./gradlew :kitecodec-core:jvmTest -Pkitecodec.hostTargetsOnly=true   # the real JNI backend, W-01
./scripts/linux-tests.sh                                             # the cross-built FFmpeg, W-06
# When KitePlayer must see KiteCodec changes. All three flags, and NOT hostTargetsOnly alone:
# a publish regenerates the root module metadata, so -Pkitecodec.hostTargetsOnly=true DELETES the
# ios, linux and mingw variants from it and the linux and Windows lines further down this same
# gate then fail to resolve. -Pkitecodec.jni.linux=true is the third because the Linux JNI
# libraries the jvm jar carries (W-16) are opt-in, and without them linux-jvm-tests.sh fails all
# 26 matrix rows on "kitecodec_jni is neither on java.library.path nor bundled". Found the hard
# way on 2026-08-17: one host-only publish broke four unrelated gate steps at once.
./gradlew publishToMavenLocal \
  -Pkitecodec.phoneTargetsOnly=true -Pkitecodec.withDesktopTargets=true -Pkitecodec.jni.linux=true

# KitePlayer. Media generation comes FIRST, not with the sample runs where it used to sit:
# kiteplayer-ffmpeg's native tests read testmedia/, which is gitignored and generated, so a clean
# checkout has none. Found by this gate's own clean-clone arm at I.1, where 29 tests failed on
# missing files with the generation line still four commands below them; a working tree keeps old
# media around, which is why the wrong order never bit before.
cd ../KitePlayer
./scripts/testmedia.sh                                # regenerate when testmedia.sh changed
./gradlew :buildSrc:test
./gradlew :kiteplayer-core:macosArm64Test :kiteplayer-output:macosArm64Test \
          :kiteplayer-ffmpeg:macosArm64Test
# Real UIKit, on the simulator: 22 tests since phase W gave SOL-R9 its proof there. It was never
# named here because it had nothing in it.
./gradlew :kiteplayer-view:iosSimulatorArm64Test
kiteplayer-rt/native/scripts/build-host.sh asan  && kiteplayer-rt/native/scripts/run-c-tests.sh asan
kiteplayer-rt/native/scripts/build-host.sh tsan  && kiteplayer-rt/native/scripts/run-c-tests.sh tsan
kiteplayer-rt/native/scripts/run-c-tests.sh interpose  # plain binaries, interposer must be live

# The desktop surfaces, added by phase W. The JVM suites are ordinary Gradle tasks; the Linux one
# is a script because Gradle CREATES linuxX64Test and linuxArm64Test on a macOS host and then
# permanently disables them, so naming those tasks would be green by definition rather than by
# evidence. mingw has no run line on purpose: a PE binary needs Windows, and the link is the claim.
./gradlew :kiteplayer-output:jvmTest :kiteplayer-mobile:jvmTest :kiteplayer-ffmpeg:jvmTest
# The web output side (17.14 X-12). Named because the web sink is a PUMP and its defect mode is
# silence: the first version held the render callback and never called it, which compiled, resolved
# a player and would have hung playback at position zero. These tests assert the calling.
./gradlew :kiteplayer-output:wasmJsNodeTest
./scripts/linux-tests.sh                              # core, subtitles and ffmpeg, in a container
# The same jvm suite the line above ran natively, on a Linux JVM against the jar's own bundled
# library: 60 tests and all 27 matrix rows. Pass linux/amd64 for the emulated second arm (W-20).
./scripts/linux-jvm-tests.sh
# Windows stays a link claim, and the FFmpeg backend is the strong form of it: a PE32+ binary
# carrying the engine, the backend and FFmpeg itself.
./gradlew :kiteplayer-ffmpeg:linkDebugTestMingwX64 \
          -Pkitecodec.ffmpeg.localRoot="$PWD/../KiteCodec/native-libs"

# Cross-target compile spot checks
./gradlew :kiteplayer-core:compileKotlinJs :kiteplayer-core:compileKotlinWasmJs \
          :kiteplayer-core:assembleAndroidMain

# Sample runs, from A1 onward; the media was generated at the top of this block
./gradlew :kiteplayer-sample:linkDebugExecutableMacosArm64
BIN=kiteplayer-sample/build/bin/macosArm64/debugExecutable/kiteplayer.kexe
$BIN testmedia/sync1080p30.mp4        # expect: all frames submitted, 0 dropped, 0 underruns
$BIN testmedia/truevfr720.mp4         # expect: 0 dropped (real VFR from A0 onward)
$BIN testmedia/hevc4k10.mp4           # expect: video-master playback completes
$BIN /nonexistent.mp4                 # expect: one sentence, no stack trace
```

From A2 add the transport-stream offset clip, from A4 add `surround51.mp4` and the P010 golden,
from A6 add the rotated clip, each with the expected line stated in its phase. The sample binary
is a debug executable and its numbers are development evidence (level 6), never performance
qualification; qualification budgets live in Horizon B. A sample clip that misses its expected
line on a LOADED machine and meets it on two quiet reruns is a load observation and is recorded as
one, not silently rerun until green: I.1 recorded a dropped frame and I.6 a Buffering seek that
way.

**What Tier 2 cannot catch.** A real-time deadline miss on a real audio device under collector
pressure. Only Tier 3 sees that, and only on this one machine.

### Tier 3, HEAVY. Roughly 50 minutes, supervised

Selected by ANY of these:

- any change to `kiteplayer-rt/native/src/kite_rt_render.c`, which is the whole of what the
  device's thread executes
- any change to `kprt_render_cb` or to the ring handoff and teardown ordering in
  `kite_rt_coreaudio.c`
- any change to the ordering of `AudioPlayback`'s `submit`, `flush` or `close`, or to
  `PlaybackCore`'s `teardownSession`
- any proposed support-tier promotion under section 2
- before publishing a release artifact

Tier 2, plus the supervised device run and its negative control: two ten-minute commands with the
control at ten minutes per arm. Its numbers are level 6, a manual observation with saved metrics on
one machine in a debug binary with one operator, and its authority rests on `render-audit.sh` and
the interposed C suites, which are level 2. Never present it as level 1; section 2 forbids it and
the interlude corrected exactly that overclaim (I-16).

**Deliberately not gated by Tier 3.** Except for the explicit support-promotion and release-artifact
selectors above, a phase that changes no line of the render path, callback or teardown ordering
does not run it, even when it touches audio elsewhere: the render audit proves the shipped object
has no allocator, lock, log or framework symbol to call on any run, and that proof does not weaken
because a resampler changed.

### How every ratchet moves

One row per committed baseline. A ratchet without a written move procedure gets moved by whatever
the executor improvises at the moment it fires, which is how a real change gets absorbed silently;
this table is the procedure. Two of the files are installed by the interlude itself and their rows
say which sub-phase.

| Baseline | Fires when | The move | The log entry must say |
|---|---|---|---|
| the KiteCodec API dumps under `../KiteCodec/kitecodec-core/api/`: `kitecodec-core.klib.api`, plus the JVM dump installed by S1.c.2 | `:kitecodec-core:apiCheck`, on any public API change in kitecodec-core | before S1.c.2 run `./gradlew :kitecodec-core:apiDump -Pkitecodec.hostTargetsOnly=true`; from S1.c.2 run `./gradlew :kitecodec-core:apiDump -Pkitecodec.phoneTargetsOnly=true -Pkitecodec.requireAllTargets=true`, and commit every changed dump with the declaration | every declaration added or removed, which dump moved, and why |
| the KitePlayer api dumps under `*/api/` across five library modules | `checkKotlinAbi`, on any public API change in those five modules | `./gradlew updateKotlinAbi`, commit the dumps with the change | every declaration added or removed, and why |
| `kitert-coupling-baseline.txt` (installed by S1.a.4) | `checkKitertCoupling`, when a previously unlisted Kotlin source file in any active non-excluded module names `cnames.structs.kprt_` or `kiteplayer.rt.cinterop` after comment stripping | remove the direct cinterop name, or add or remove the exact `allowed_kitert_file` line and update the measured count in the baseline header in the same commit | the old and new file count, every path added or removed, why the facade could not avoid the naming site, and whether either module exclusion changed |
| `../KiteCodec/native/kitecodec-c/coupling-baseline.txt` | `checkCinteropCoupling`, when a ratcheted count rises | edit the number by hand to the value re-measured with the command written beside it in the file | the old and new number, and the change that moved it |
| `../KiteCodec/native/kitecodec-c/klib-metadata-baseline.txt` | `klib-metadata-diff.sh --check`, on any cinterop metadata difference | `./scripts/klib-metadata-diff.sh --update`, in the same commit as the deliberate surface change | the script's whole SUMMARY block, pasted, so the record carries the reviewed numbers and not a pointer to a 19,000 line diff |
| `../KiteCodec/native/kitecodec-c/deleted-surface.txt` (installed by I.3) | `check-deleted-surface.sh`, on any use of a name whose status is `deleted` | change that name's status to `resurrected-in-<item>` in the same commit that resurrects it | one sentence naming the item and the reason |
| `../KiteCodec/native/kitecodec-c/exported-symbols-baseline.txt` (installed by I.4) | `symbol-audit.sh` check 6, when the archive's exported name set differs from the baseline | regenerate with the command the baseline's own header names, in the same commit as the deliberate export change | every symbol added or removed, and why |
| `../KiteCodec/native/kitecodec-c/signature-baseline.txt` (installed by S1.a.8) | `symbol-audit.sh` check 7, when any committed normalized public C declaration record differs: 189 before S1.c.1, 192 after its compatible addition and 193 after S1.c.2's named-decoder helper | from `native/kitecodec-c`, run `./scripts/symbol-audit.sh --write-signature-baseline` after reviewing the exact diff, in the same commit as the deliberate declaration change | every declaration class added, removed or changed, the old and new normalized records, and why the ABI move is intentional |
| `ALLOWED_UNDEFINED` in `../KiteCodec/native/kitecodec-c/scripts/symbol-audit.sh` | `symbol-audit.sh` check 4, when an archive references an undefined symbol outside the list | add the symbol to the list in the same commit as the code that needs it | the symbol and the helper that pulls it in |

Multi-pass rule, owner-mandated: after each phase's code is written, re-read every changed
file once against this document, run the gate, write the Execution log entry, then commit.

---

## 10. Horizon A: the executable run

### A0: truth, hygiene, and the memory-safety fix
1. D27 snprintf bounds checks in both def-file sites, plus the length tests. This is the
   only code-behaviour change in A0 and it ships first.
2. README rewrites per section 6 item 1, including the tier table; KDoc truth fixes
   (section 6 items 4, 5, 6, 7, 8); truth-ledger markers (item 9).
3. Deletions and nits: `NO_PTS`; `Coefficients` depth parameter; `readComponent` comment.
4. D33 fixture: regenerate real VFR, rename, update references; drift sign KDoc.
5. Subtitles module package rename plus parser tests (section 5 last row).
6. Gate. Commit ("Fix the filter buffer overflow, and make the words match the code").

### A1: engine correctness and ownership
1. D1, D2, D3, D14 in `VideoPlayback`; D21 target-time and counter renames; new
   `VideoPlaybackTest` harness (TestClock, fake frames, recording renderer) covering VFR
   drop, repeat count, queue bound, generation discard, resync, and target-time delivery.
2. D20 shown-frame ownership: metadata record replaces the retained frame; ledger test.
3. D24 anchor boundary and timestamp segments in `AudioRing`; table-driven tests.
4. D4 lock and KDoc; D7 epoch deletion; D32 finite-speed check.
5. Gate. Commit ("Give every frame one owner, and make the audio anchor sample-exact").

### A2: KiteCodec correctness, then adoption
KiteCodec steps 1 to 6 first (disjoint files may run in parallel), republish, then
KitePlayer steps 7 to 8.
1. D8 discard restore. 2. D9 helpers (pts, dts, duration) plus overflow tests.
3. D17 `isDrained`; D18 seek guard; D35 closed guards. 4. D28 drain unref and multi-input
   bound. 5. D29 previous-frame step in `restampPts`. 6. D30 channel mask helper; D31
   audio-plane guard; D32 `isSeekable`; delete `ffkmp_stream_r_frame_rate`.
7. KitePlayer: D10 mapper (mapTimestamp versus mapDuration), pts synthesis, delete manual
   multiplies and the raw DTS cast; D22 generation-on-flush SPI change; transport-stream
   clip and tests.
8. Gate. Commits ("Make every wrapper safe against misuse, and every timestamp exact" /
   "Put the whole player on one relative timeline").

### A3: output layer
1. D5 renderer rework (including the main-queue latest-only slot) plus tests.
2. D23 CoreAudio: preallocated buffer, silence fill, host-time validation, transactional
   open, plus tests. 3. D11 clock check. 4. D19 window delegate and stop wake-up.
5. D6 canonical decode loops in the sample.
6. Gate, including the manual window and red-button checks. Commit ("Honour the real-time
   audio contract, and close the renderer without leaking").

### A4: the audio pipeline and converter correctness
1. D12 mixer (mask-keyed per D30), resampler, gain ramp, pipeline, `surround51.mp4`,
   reference-PCM comparison, wired into the sample.
2. D13 speed honesty. 3. D15 items 2 and 3 with goldens. 4. D26 P010 fix with golden;
   BT.2020 CL warning. 5. D16 HDR warning.
6. Gate. Commit ("Downmix and resample correctly, and stop pretending about speed").

### A5: PlaybackCore, the facade, and seeking end to end
1. D34 backend session SPI, fake backend, FFmpeg backend adoption.
2. PlaybackCore per digest 8.1 with the handler-order test and the command-legality
   table tests.
3. The seek machine with the quiesce-first sequence (D25); virtual-time seek tests
   (twenty seeks in one virtual millisecond, one flush cycle; stale-generation and
   bounded-termination invariants).
4. Simulation campaign per digest 8.5, including the one real-thread native stress step.
5. The facade per digest 8.2 with input validation (D32), exception policy, snapshot
   error retention, stats separation (D21), and the truth-ledger walk.
6. Sample rewritten onto the facade; real-media seek test (20 precise seeks land within
   one frame duration; seek past end reaches Ended; seek to 0 mid-play works).
7. Gate. Commit ("Give the player its core loop, its facade and real seeking").

### A6: rotation, polish, and the honest rewrite
1. Rotation per digest 8.4 with the rotated-clip check.
2. README rewritten again: facade sample first, tier table updated with evidence, limits
   list, Horizon B stated as not done.
3. ABI dumps generated and committed for every KitePlayer module.
4. Soak: `soak30min.mp4` audio-only full length, 1080p with video at least 10 minutes;
   drift under 40 ms, no underruns, resident set reaches a plateau and stays there for the
   final 20 minutes (recorded numbers in the log; this is development evidence, not the
   Horizon B 24-hour qualification).
5. Full gate. Final log entry. Commit ("Rotate what phones record, and make every document
   match the code").

---

## 11. Horizon B: the product roadmap (decided, sequenced, NOT this run)

> **Superseded 2026-08-11:** items B2 to B11 below remain the historical roadmap, but the live
> map of remaining work is section 17, which absorbs every obligation here into stages S1 to S7
> (network parked at 17.8 by owner decision D-4). B1 and the interlude were executed from
> sections 15 and 16 and are complete.

Everything the owner's product goals require beyond the run above. Order is
dependency-driven. Each item ends with its exit truth.

- **B1. Shared C ABI and the real-time gold standard.** Lift the def-file helpers into a
  versioned `kitecodec-c` library with opaque handles, explicit ownership annotations, no
  public FFmpeg struct embedding, error records, C unit tests, fuzz targets and
  sanitizers. Kotlin/Native binds to it; the same ABI later serves JNI. FFmpeg
  header/runtime major-version identity is validated at startup, and the audio device
  callback becomes pure native code that never enters managed Kotlin. Exit: one C
  implementation serves cinterop; a mismatched FFmpeg runtime is rejected with a report;
  callback allocation instrumentation reads zero.
- **B2. KiteCodec completion for experts and the player.** Typed send/receive outcomes
  (Consumed, NeedOutput, Drained, RecoverableInvalidData, Fatal and the receive
  equivalents with FormatChanged), full `AVChannelLayout` and `extended_data` (1 to 32
  channels, custom and ambisonic), complete colour and side-data model (mastering,
  content light, HDR10+/Dolby Vision identifiers, full display matrix), interruptible
  `OpenRequest` (custom IO, headers, options, deadlines, protocol allowlist), pooled
  plane views (zero steady-state allocation, negative strides), managed swresample and
  cached swscale contexts, bitstream filters, subtitle decode (text and bitmap with
  attachments and fonts), fair multi-input filter scheduling, corruption-budget policy,
  transactional output replacement (never truncate an input that is the output path),
  half-open trim semantics for Transcoder, and closed-state leases across every wrapper.
  Exit: an expert builds a C-equivalent pipeline with no internal binding and no lossy
  mapping; the batch APIs preserve VFR and variable audio timing.
- **B3. Subtitles.** Cue timing in the core loop; SubRip and WebVTT policy paths; libass
  as the reference ASS/SSA renderer in a `kiteplayer-subtitles-libass` module; bitmap
  subtitle pipeline (PGS, VobSub, DVB); seek reconstruction of active state; forced and
  accessibility dispositions in auto-selection; user style precedence (accessibility
  wins, then user override, then authored, then defaults); HDR-aware composition.
  Exit: the reference corpus (scripts, bidi, karaoke, overlaps, palettes, seeks) matches
  pinned libass output; SRT-only support is never called subtitle support.
- **B4. Audio quality completion.** swresample replaces the linear resampler as default;
  WSOLA (or better) tempo with pitch preserved, making `setSpeed` and `preservePitch`
  real; drift compensation for video-master and external-master modes; gapless (priming
  and padding accounting) and A-B loop; device-change and latency-quality upgrades on
  every sink; passthrough and bit-perfect modes as negotiated capabilities.
  Exit: spectral gates (alias energy, impulse response), sample-exact gapless captures,
  and the tempo range published with evidence.
- **B5. Video quality completion.** Metal renderer (plane textures, shader conversion,
  `present(at:)` timing, quality-tagged presentation feedback per submission with
  terminal Presented/Superseded/Failed/Cancelled); VideoToolbox hardware decode with
  typed surface leases, pool negotiation and device-loss recovery; the colour-managed
  pipeline (correct P010/CL handling, transfer linearisation, gamut and tone mapping,
  display capability queries, dithering); deinterlacing policy; full display matrix;
  display-rate policies. The CPU converter remains the reference oracle and fallback.
  Exit: 1080p60 software and 4K60 hardware sustain 30 minutes on named Macs with the
  scanout-error budget met and colour goldens (DeltaE distributions, not mean byte error)
  passing.
- **B6. Sources, network, live and adaptive.** Interruptible network operations with
  deadlines; TLS and protocol profile work in the FFmpeg build (https, HLS, DASH, RTSP);
  a real packet cache (byte/time watermarks, GOP-safe discard, live window with published
  seekable ranges); capability snapshots replacing seekable booleans; delegated versus
  player-owned adaptive architectures chosen per open; reconnect policy; in-place track
  switching where the backend permits. Exit: network chaos tests (stall, 404, redirect
  loops, token expiry, reconnect) pass; live edge and DVR windows behave; blocked reads
  cancel within their deadline.
- **B7. Distribution and packaging.** The umbrella `kiteplayer` artifact with per-target
  default composition; KiteCodec runtime artifacts published so ordinary consumption is
  ONE dependency line with no plugin (static-library-in-klib or runtime-variant strategy
  per target; the plugin remains for custom profiles, system FFmpeg and GPL opt-ins that
  never arrive transitively); Android AAR over the JNI bridge (16 KiB ELF load segments,
  extractNativeLibs=false, per-ABI jniLibs, with consuming APKs storing and page-aligning the
  entries); JVM desktop runtime jars; iOS/tvOS
  frameworks; content-addressed plugin cache with digest and provenance validation,
  bounded extraction, and profile-aware identity; licensing bundles (notices, exact
  corresponding source, relink material) with no categorical legal claims about static
  LGPL on the App Store. Exit: a clean consumer on each shipped ecosystem builds and
  plays with one dependency and zero local setup.
- **B8. Security and supply chain.** Fuzz every C entry point that accepts bytes,
  descriptions or options; sanitizer jobs in CI; resource budgets (dimensions, channels,
  probe bytes, playlist depth, decompression ratio) with typed limit errors; protocol
  allowlists and SSRF policy; secret redaction; SBOM, provenance, reproducible builds,
  CVE monitoring with a patch SLA. Exit: no known reachable Critical or High issue at any
  release; two isolated rebuilds byte-match or explain their differences.
- **B9. Platform qualification to 1.0.** The mandatory set, each to T5 for its named
  profile with real-device evidence: macOS 13+ (arm64, x64), iOS 16+ and tvOS 16+
  (devices; simulators at least T4), Android API 24+ (arm64-v8a and x86_64; drop or
  qualify armeabi-v7a explicitly), JVM desktop on JDK 17+ (Windows 10 22H2+ x64,
  macOS 13+, Ubuntu 22.04-baseline Linux x64/arm64), current-and-previous stable
  browsers for a documented Web capability profile (WebCodecs/WebAudio/MSE backend), and
  watchOS 9+ either qualified T3-Audio or removed from the product coordinate. Per
  platform: OS integrations (focus, sessions, PiP, background, routes, media keys),
  lifecycle, packaging and consumer smoke. Exit: generated support tables from
  qualification runs replace every hand-written claim.
- **B10. Performance constitution and parity.** Release-mode benchmarks on pinned
  devices and media with distributions (p50/p95/p99 and worst), warm and cold: audio
  callback worst case under half its deadline with zero engine-caused underruns; zero
  full-frame CPU copies on qualified paths; local open-to-first-feedback p50 100 ms and
  p95 250 ms; keyframe seek p95 200 ms; precise seek p95 500 ms landing within one
  frame; A/V drift p99 within 20 ms; scanout error p95 within a quarter refresh; 24-hour
  soak with a bounded RSS slope; a copy-and-allocation ledger per qualified graph; and a
  published parity dashboard against pinned mpv, VLC and QMPlay2 builds. Marketing never
  substitutes for the dashboard. Exit: the numbers exist, with raw artifacts retained.
- **B11. Product facade completion.** Playlist/queue (which unlocks LoopMode.All),
  chapters, editions and programs, frame stepping, filters (video, audio, subtitle) with
  typed graph descriptions, external audio/video tracks, the advanced option escape
  hatch with namespaced dictionaries and unused-option reporting, screenshots, support
  bundles, Compose integration, Swift and Java ergonomics, and the API truth ledger
  completed: every public member implemented, typed-rejected, or gone.

**What does not count as done, ever:** one frame per minute; a source set compiling; a
unit test around a fake renderer presented as playback; enqueuing a frame and calling it
displayed; an FFmpeg feature present in source but absent from the shipped profile; a
warning standing in for correct colour at 1.0; an ignored configuration field; a
developer-machine build via Homebrew or mavenLocal presented as installation; a debug
benchmark; a ten-minute soak called leak-free; a simulator standing in for a device
claim; a README promise without a passing gate.

---

