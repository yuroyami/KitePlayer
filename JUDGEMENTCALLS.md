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
