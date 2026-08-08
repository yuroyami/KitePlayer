# Judgement calls

Decisions taken without asking, because the repo owner was asleep and said to keep moving.
Each entry states what was decided, why, what the alternative was, and how to reverse it.
Read this before reviewing anything else.

Newest entries go at the bottom of each section.

---

## 1. Legal: clean-room, architecture study only

**Decided.** KitePlayer ships Apache-2.0, like the rest of the family. The reference players
studied for it are licensed GPL-2-or-later (mpv, VLC), LGPL-3 (QMPlay2) and LGPL-2.1-or-later
(ffplay, libplacebo). Not one line of their code is translated into this repo. What was taken
is architecture, algorithms, numeric thresholds and hard-won behavioural rules, all restated in
new prose and reimplemented from scratch in Kotlin.

**Why.** Translating LGPL or GPL C into Kotlin produces a derivative work. The Kotlin file would
inherit that licence and poison the artifact. Linking FFmpeg at runtime is fine and is what
KiteCodec already does. Copying source text is not.

**Alternative rejected.** Never opening those trees at all, which is what KiteAudio's plan
requires for its own porting work. Rejected because the repo owner explicitly asked for the
opposite: clone them, map them out, learn from them. Facts, designs and numbers are not
copyrightable; expression is. The study agents were instructed accordingly.

**Residual risk, stated plainly.** A clean-room claim is strongest when the implementer never
read the source. Here the source was read deliberately. The mitigation is that KitePlayer's
engine is written against a written specification (KITEPLAYER.md), in a different language, with
a different concurrency model, different data structures and different naming. If any file ever
ends up looking like a transliteration, that file is a defect and must be rewritten.

**Reverse it by.** Deleting `vendor/` and rewriting from the spec alone.

---

## 2. New repository, on `main`, local only

**Decided.** `KitePlayer/` is a new git repository beside the other Kite libraries, initialised
on branch `main`. Commits are local. Nothing is pushed anywhere.

**Why.** Every other Kite library is its own repository with its own release train. The parent
`#Kite` directory is not a repository. A player has a different release cadence and a different
dependency set from a codec binding, so folding it into KiteCodec would couple two things that
should version independently.

**On the branch rule.** The standing instruction is never to create a branch other than the one
in use. `git init` creates the initial branch of a repository that did not exist, which is not
branching off existing work. No second branch was created.

**Reverse it by.** `rm -rf KitePlayer/.git` and moving the modules into KiteCodec.

---

## 3. Reference trees and test media are untracked

**Decided.** `vendor/` (mpv, QMPlay2, VLC, libplacebo; about 230 MB) and `testmedia/`
(generated clips; about 490 MB) are in `.gitignore`.

**Why.** The owner asked for the reference clones to be gitignored. Test clips are reproducible
from a script, so tracking them would only bloat the repository.

**Reproduce with.** `scripts/testmedia.sh`, which needs the `ffmpeg` CLI on PATH.

---

## 4. The study was run as a parallel multi-agent workflow

**Decided.** Eleven reader agents mapped ffplay's clock and sync, mpv's playloop, libmpv's client
API, VLC's architecture and clock, QMPlay2's structure, GPU rendering (libplacebo and mpv vo_gpu),
audio output backends, hardware decode, subtitles, KiteCodec's gaps, and Kite family scaffolding
conventions.

**Why.** The global instruction file encourages multi-agent work on Opus 5 when it materially
improves the result, and says result quality outranks token cost. Reading five large C and C++
code bases serially would have consumed the whole night before a line of Kotlin was written.

**Reverse it by.** Nothing to reverse. The findings are recorded in this repo's plan; the source
trees remain for verification.

---

## 5. Development platform for the first milestone is macOS arm64

**Decided.** The first end-to-end playing pipeline is proven on macOS arm64, against Homebrew
FFmpeg 8.0, even though the stated v0.1 promise is every target.

**Why.** It is the only target that both KiteCodec and this machine can build and test today, so
it is the only place a claim of working playback can be verified rather than asserted. Homebrew
FFmpeg 8.0 here is a GPL build with VideoToolbox, AudioToolbox, libass and gnutls enabled, which
covers hardware decode, audio output, subtitles and https in one install.

**What this does not mean.** It does not narrow the plan. Every other target keeps its full
specification in KITEPLAYER.md, and the engine is written in `commonMain` with no macOS
assumptions. It only means the order of proof starts where proof is possible.

**Reverse it by.** Reordering the milestone table in KITEPLAYER.md.

---

## 6. KiteCodec grows an opt-in low-level API, rather than KitePlayer moving inside it

**Decided.** KiteCodec will expose the pieces a player needs as public declarations behind a
`@KiteCodecLowLevelApi` opt-in annotation. KitePlayer stays a separate repository.

**Why this came up.** The gap analysis found that everything a player needs from KiteCodec is
`internal`: the native frame pointer, the stream time base, the routed demux engine, the seek
helper, the timestamp conversions, the packet and frame helpers, the error mapping. KiteCodec's
`settings.gradle.kts` declares no friend or associate compilation, so a separate module can reach
none of it. This blocks everything else, so it had to be settled first.

**Alternative rejected.** Putting KitePlayer's FFmpeg backend inside `kitecodec-core`. That would
tie a player's release cadence to a codec binding's, and would put several thousand lines of
coroutine-heavy playback code inside a module whose whole selling point is being a thin binding.

**Why the annotation is the honest form.** These declarations hand out raw pointers with manual
lifetimes. A consumer should have to write down that they accept that, and the annotation makes the
compiler ask.

**Reverse it by.** Removing the annotation and merging the modules.

---

## 7. The audio sink interface is pull-shaped, not push-shaped

**Decided.** The engine's `AudioSink` is pulled by the device through a non-suspending
`AudioRenderCallback`, and platforms whose native API is push (ALSA read-write, PulseAudio, the JVM
`SourceDataLine`, Android `AudioTrack` blocking write) are wrapped by one writer coroutine that
turns available space into a pull.

**Why.** The first draft of the plan had the engine pushing buffers into the sink with a suspending
`write`. The audio output study showed that is the wrong shape: the real-time audio thread cannot
suspend, cannot allocate and cannot take a contended lock, and the master clock needs the instant at
which a specific buffer becomes audible, which only a pull callback can supply. mpv reached the same
conclusion and standardises on pull internally for exactly this reason. ffplay does the opposite and
its Windows path contains a busy-wait workaround as the visible consequence.

**Cost.** Push platforms need a wrapper. That wrapper is written once, in the output module, not per
platform.

**Reverse it by.** Nothing sensible. This one is settled by physics rather than taste.

---

## 8. Audio before video, and why that is not the easy way out

**Decided.** The first working playback is audio only.

**Why.** Video is blocked, not merely harder. KiteCodec exposes pixels only through
`copyPlanesToByteArray`, a full copy of every frame: 3.11 MB for 1080p and 24.9 MB for 4K 10-bit,
which is 187 MB/s to 1.5 GB/s at 60 fps plus an allocation per frame. A renderer needs plane
pointers, and hardware decoding needs the surface handle. Both are specified in KITEPLAYER.md
section 16.1 and neither exists yet.

Audio, by contrast, exercises the parts of the design that were most at risk of being wrong: the
master clock, the device contract, the real-time callback rules, and the backpressure that paces
decoding to playback. Those now have measured evidence behind them rather than an argument.

**What building video on the copy would have cost.** A renderer written against
`copyPlanesToByteArray` would be thrown away when 16.1 lands, and it would have hidden the very
problem the plan says to fix.

**Reverse it by.** Nothing to reverse. The order is recorded in PROGRESS.md.

---

## 9. KiteCodec is consumed from a local Maven publication

**Decided.** `kiteplayer-ffmpeg` and `kiteplayer-sample` resolve KiteCodec and its Gradle plugin from
`mavenLocal()`, which means running this in the KiteCodec checkout first:

```bash
./gradlew publishToMavenLocal -Pkitecodec.hostTargetsOnly=true
```

**Why.** KiteCodec is not on Maven Central, and its own README documents this as the supported route
for a consumer project, which is what its CI uses. The alternative, a Gradle composite build, would
have to supply the KiteCodec Gradle plugin as well as the library, and that plugin is load-bearing:
KiteCodec's cinterop declares its linker options as bare `-lavformat` and friends with no `-L`, so
every module whose link task pulls FFmpeg in needs the plugin to supply the library directory. The
sample needed it too, which was not obvious until the linker said `library 'avformat' not found`.

**Cost.** A fresh clone cannot build `kiteplayer-ffmpeg` without that one command. It is written down
in README.md and PROGRESS.md.

**Reverse it by.** Publishing KiteCodec, or switching to `includeBuild`.

---

## 10. Two defects were found by running the thing, and fixed

Recorded because both are the kind of mistake that survives code review and dies on contact with real
input.

**End-of-media silence was counted as an underrun.** When the decoder finishes, the ring runs dry and
the device is handed silence until the buffer empties. That is the end of the file, not a failure to
keep up, but the counter did not know the difference, so every clean playthrough finished by reporting
six to eight underruns. A counter that always reads non-zero is useless for spotting the real thing.
`AudioPlayback.endOfStream()` now marks it, and the caller invokes it when the decoder completes rather
than when the buffer empties. Verified: the same clip now reports 0.

**A missing file produced a stack trace.** The sample caught `IllegalStateException`, and KiteCodec
throws its own exception type. So a wrong path printed a Kotlin/Native backtrace, which is exactly the
failure mode KITEPLAYER.md section 8.4 forbids. Now every open failure prints one sentence. Verified
against a missing path and against a text file.

---

## 11. The atomicfu Gradle plugin was dropped, keeping the library

**Decided.** Every module uses the `kotlinx.atomicfu` runtime library. None applies its Gradle plugin.

**Why.** The plugin's bytecode transform registers a task that depends on `androidMainClasses`, and
AGP 9's Kotlin Multiplatform library plugin does not create a task by that name. So applying it makes
`assembleAndroidMain` fail with `Task with name 'androidMainClasses' not found`.

This was found by checking a claim rather than by a bug report. README.md states that the engine
compiles for every target Kotlin supports, and KITE.md rule 5 says never to write a claim the code
cannot support. Verifying it target by target is what surfaced the failure.

**Cost, stated precisely.** Without the transform, each atomic field is one wrapper object. That object
is allocated when its owner is constructed, not per operation, so the real-time audio path still does
no allocation. The transform is an optimisation, not a correctness requirement.

**Alternative considered.** Moving to the standard library's `kotlin.concurrent.atomics`, which would
remove the dependency altogether. Rejected for now only because it is a wider change than one late-night
verification pass should carry. It is the better long-term answer.

**Verified after the change.** 157 tests pass, every target compiles including Android, js and wasmJs,
and the sample still plays with zero underruns.

---

## 12. KiteCodec was changed rather than worked around

**Decided.** With permission given to edit KiteCodec freely, it grew the API a player needs instead of
KitePlayer working around its absence. Fifteen items from KITEPLAYER.md section 16 were specified; six
landed, chosen as the ones that unblock video.

**What landed.** The opt-in low-level surface (16.0), the colour metadata and frame duration on
`FrameInfo` plus disposition and rotation on `StreamInfo` (16.2), the split of demuxing from decoding
(16.3), the decoder flush (16.4), seeking with a real window and flag set (16.5), and zero-copy plane
and hardware-surface access (16.1).

**What did not, and why.** Hardware decode (16.6) needs a C function pointer callback and a change to
the vendored FFmpeg configure line, which is a build-system job rather than an API one. Custom I/O
(16.7), demuxer options (16.8), subtitle decode (16.9), the resampler (16.10), https (16.11) and the
JNI bridge (16.13) are all specified and untouched. None of them blocks a picture on screen.

**Why the opt-in annotation rather than plain public API.** These declarations hand out raw pointers
with manual lifetimes and no stability promise. Making a consumer write that down is the honest form,
and it keeps KiteCodec's safe-by-construction batch API as the thing a casual user finds first.

**Verified.** KiteCodec's own 53 tests still pass. Two of its Gradle plugin functional tests fail, and
they failed before any of this, confirmed by stashing the changes and re-running.

---

## 13. The sample wires the pipeline by hand, and that is temporary

**Decided.** `kiteplayer-sample` connects the demuxer, the two decoders, the audio path and the video
scheduler with plain coroutine channels, rather than through the `PlaybackCore` the plan specifies.

**Why.** Every piece `PlaybackCore` will coordinate now exists and is tested: the clock, the
synchronisation law, the duration estimator, the queues, the audio ring, the seek merge, and both
playback paths. What was missing was evidence that they fit together and that the result stays in sync
on real media. Wiring them by hand produced that evidence in one file, and the measurements it prints
are what says the design works.

**What it costs.** The sample's channels are not the engine's `PacketQueue`, so it does not exercise
generation filtering or the byte and duration bounds. Those are unit tested separately.

**What replaces it.** `PlaybackCore`, with the generation plumbing and the seek state machine attached.
That is the next engine piece, and the sample then shrinks to opening a file and collecting state.
