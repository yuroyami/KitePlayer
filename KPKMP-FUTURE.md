# KPKMP-FUTURE: what is true and what is ahead

**This file is STANDALONE. Everything needed to work is in it.** The pilot document is two files
and still one document; its other half, KPKMP-PAST.md, is finished business you never have to open.

> ## **IT NOW COVERS BOTH REPOSITORIES. THERE IS NO OTHER PLAN DOCUMENT ANYWHERE.**
>
> **On 2026-08-19 the two audit documents living in KiteCodec were distilled into this file and
> deleted.** They were `SOLSUPREME.md`, a third party code audit of the pair, and `SUPREME.md`, a
> verification pass over it plus six execution logs. Their open findings are in 17.15 through
> 17.19; the ORDER to do them in is 17.20; their execution logs moved to KPKMP-PAST.md section
> 14.114, where every other finished thing already lives. KiteCodec keeps a `PLANNING.md` pointer so
> a reader landing in that repository is not stranded.
>
> **Start at 17.20.** It is one page and it says what to do first and why.
>
> This is the same move 17.11 records for SOL_REVIEW.md and ANDROID_GPU_WORK.md on 2026-08-16, and
> it is made for the same reason: **an open item in a document nobody opens is not tracked, it is
> lost.** Two of the six rows this distillation had to correct were rows one document listed as
> open while another had already closed them.
>
> **The distillation was not a copy.** Every open claim in both documents was re-verified against
> KiteCodec `dd2823c` and KitePlayer `e201186` by locating each symbol BY NAME, because every line
> number in both documents had rotted. That pass found six rows logged DONE that are not done on
> every backend, one fix that introduced two leaks of its own, three previously unknown safety
> defects, and four counts that contradicted their own tables. All of it is in 17.16 and 17.19.

> ## **RULE ZERO: HOW TO TALK TO THE OWNER. READ THIS BEFORE ANY REPLY.**
>
> **The owner did not write this document and has not read it. An agent wrote it.**
> The owner does not know what `X-01`, `W-19`, `S6-D2`, `KV-6`, `SOL-P8` or `17.14` mean, and is
> not required to learn. Those codes exist so AGENTS can be precise with each other and with the
> tree. They are not a language to speak to a human in.
>
> **When reporting to the owner, or asking the owner anything, obey all five:**
>
> 1. **Never lead with a code.** Say the thing. "The web draw test" beats "X-01". If a code must
>    appear at all, it goes in brackets after the plain words, never instead of them.
> 2. **Say what it MEANS, not what it IS.** Not "the raster build is 13 ns per byte". Rather:
>    "getting one video frame onto the screen takes 150 ms, and it needs to take 33, so video on
>    the web would play like a slideshow. Here is why, and here is the fix."
> 3. **Assume zero knowledge of this file, the code, and the history.** The owner knows the
>    PRODUCT: a video player that should work on phones, desktop and web. Speak in those terms.
> 4. **A decision request must be answerable by someone who has read nothing.** State the choice,
>    what each option costs in time and in what the user gets, and give a recommendation. Never
>    ask the owner to pick between two register items by name.
> 5. **No wall of jargon.** Short sentences. Plain words. If a sentence needs this document to be
>    understood, rewrite it.
>
> **This rule outranks every style note elsewhere in this file.** The rest of this document is
> written agent-to-agent and stays that way; that is what it is for. This rule governs what
> LEAVES the document and reaches a person.
>
> Added 2026-08-17 at the owner's explicit instruction, after an agent reported a whole phase to
> them in register codes they had no way to read.

> ## **RULE ONE: THIS FILE IS UPDATED BY THE SAME COMMIT THAT CHANGES THE TREE.**
>
> **A register row is a claim about the code. A claim nobody revisits becomes a lie with a date on
> it.** This is not a style preference; it is the only thing that makes the register worth reading.
>
> **Whenever you do ANY work on either repository, before you report it done:**
>
> 1. **Close what you closed.** Find every row your work answers and, per RULE TWO, DELETE it from
>    this file, writing its closing entry into KPKMP-PAST.md with the commit that closed it. Do not
>    leave it for a later sweep. There is no later sweep.
> 2. **Open what you opened.** A limitation you introduced, accepted or discovered is a new row, in
>    the register nearest the work, written before you move on.
> 3. **Correct what you found false.** If a row describes the tree wrongly, say so on the row with
>    the evidence, and say when it stopped being true if you can tell.
> 4. **Reduce what you narrowed.** A row half answered is REDUCED with its remainder named, never
>    silently left whole and never quietly deleted.
> 5. **Log the work.** The execution log (KPKMP-PAST.md, section 14) gets what was measured and
>    what was NOT, in the same commit.
> 6. **Keep the index true.** 17.15's consolidated register is where a reader learns what is open.
>    A row you close there must say which commit closed it; a row you open is added there too, not
>    only in its home section. The index is the promise; the detail is the evidence.
> 7. **Run the gate that watches what you changed.** Tier 1 always, and the tier your changed paths
>    select. Three gate steps were found already red on 2026-08-18, each left by an earlier surge
>    that changed something structural and did not re-run the check watching it: a cross-repository
>    allowlist naming a file the document split had moved, an ABI dump one target behind the HTTPS
>    work, and a Linux JNI link the dav1d surge never taught to name `-ldav1d`. A register kept
>    perfectly true beside a gate nobody runs is only half of the promise.
>
> **The rule exists because it was broken.** On 2026-08-18 a verification pass found SIX rows the
> register still listed as open that the tree had already closed, four of them closed the previous
> day by surges that never looked at the register: SOL-S1 and SOL-S2 by the deep audit's own
> F-ALPHA1 and F-CFL1, SOL-API6 by W-18, SOL-B3 by a plugin change nobody re-ran. An owner reading
> this file would have paid for work already done, and an agent trusting it would have started it.
>
> **A commit that changes behaviour and does not touch this file is incomplete**, unless the work
> genuinely answers no row and opens none, which is rarer than it feels while coding.


> ## **RULE TWO: THIS FILE ONLY SHRINKS. WHAT IS DONE LEAVES IT.**
>
> **The owner's standing instruction, given 2026-08-19.** Anything this document lists that gets
> touched, fixed, enhanced or dropped is REMOVED from this file completely and written into
> KPKMP-PAST.md. Not struck through. Not marked CLOSED and left in place for a later sweep.
> Removed.
>
> **The target is an EMPTY KPKMP-FUTURE.md.** That is what the split was for: this file is the work
> that is LEFT. Every line in it that is no longer left makes the rest harder to find, and a CLOSED
> row still costs a reader the time to read it and decide it does not matter. The previous edition
> kept three closed rows "until the next sweep" and one struck through "for one edition". Both
> habits end here.
>
> **What this changes about RULE ONE.** Step 1 said "mark it CLOSED with the commit that closed
> it". It now reads: DELETE the row from this file, and write the closing entry into KPKMP-PAST.md
> section 14 with the commit and the evidence. Steps 2, 3, 4 and 7 are unchanged. Step 6 is
> unchanged in spirit: the index stays true, and a row that has left the index left because it is
> finished.
>
> **A REDUCED row does NOT leave.** Half answered is still open. Rewrite the row down to its
> remainder and keep it here; only the part that is genuinely finished goes to the archive.


> ## **THERE ARE TWO FILES. THIS IS THE ONE YOU READ, AND IT IS COMPLETE ON ITS OWN.**
>
> **KPKMP-FUTURE.md (this file) is what is TRUE and what is AHEAD.** It is STANDALONE: every rule,
> key and contract needed to work is in it. You never have to open the other file to decide what to
> do, and you are not missing context by not opening it.
>
> **KPKMP-PAST.md is what already HAPPENED**, and it is standalone too. The execution log's 113
> entries, the closed defect register, the executed stage plans, the superseded roadmaps. Open it
> only for archaeology: when a row here points into it, or when you need the ARGUMENT behind a
> decision the tree already embodies. It is an archive, not a backlog, and nothing in it is waiting.
>
> **Why it is split this way.** The pilot document reached 18,546 lines in ONE file and agents
> stopped reading it. That is measured, not felt: on 2026-08-18 an audit found six register rows
> listed as open that the code had already closed, and the agent auditing them checked twelve of
> sixty items and asserted the rest from the document's own summaries. 78% of what it had to scroll
> past was things that had already happened. A file too big to hold gets sampled, and a sampled file
> gets quoted with the confidence of a read one.
>
> So the cut is by TENSE, and it is the only cut that survives contact with a working agent: one
> file you always read, one you never do. 2,400 lines against 16,400. Both carry the same laws and
> the same keys, deliberately duplicated, because a file that needs its sibling to be understood is
> not two files, it is one file in two pieces.
>
> **Nothing was rewritten and nothing was deleted.** The split is mechanical and verbatim;
> `scripts/verify-kpkmp-split.py` proves it against the pre-split version in git and fails if a
> single line or register id stops resolving. It compares two COMMITS, the file before against the
> two files at the commit the split finished at, so it is a proof about the migration and not a
> freeze on these documents. It briefly read the working tree instead, and then went red on the
> first ordinary day of work afterwards for updating a register row exactly as RULE ONE demands.
>
> **Section numbers did not change.** A reference to "17.11" or "section 14" still lands; 17.15's
> register names the file for every open row. If a section is not here, it is finished, and that is
> the only thing its absence means.


> ## **THE KEYS. Everything in this file is written in these, so they are repeated in both files.**
>
> **Evidence levels**, strongest to weakest. No lower item may be presented as a higher one.
> 1 a repeatable release-mode automated test on a named real device with saved metrics ·
> 2 a deterministic model, differential oracle, sanitizer or fuzz result on the exact contract ·
> 3 a clean consumer build using only published artifacts · 4 a source-level proof with ownership
> and state invariants · 5 a unit test around an isolated helper · 6 a manual observation ·
> 7 compilation alone · 8 a declared target, KDoc, plan or README sentence.
> Section 2 below states this in full, with what it forbids.
>
> **Support tiers.** T1 API: common code compiles, no playback claim. T2 Codec: a runtime can open,
> decode, seek, cancel and close real media. T3-Full: qualified audio plus video, sync, subtitles
> and lifecycle. T3-Audio: audio only, explicitly no video or subtitle claim. T4 Product: T3 plus OS
> integrations and clean packaging. T5 Supported: T4 plus real-device qualification, security,
> performance and release gates.
>
> **Verification marks on a register row.** `[V]` re-verified against the tree on the date given.
> `[C]` carried from an audit, its anchor never re-checked, so check before trusting it.
> `[owner]` needs a decision or hardware that the build machine does not have.
>
> **Register id families.** `SOL-` the 2026-08-13 implementation audit (R rendering, A audio,
> S subtitles, API, P performance, C the C-reduction charter, K Kotlin modernization, B build) ·
> `F-` the 2026-08-17 deep audit · `PAR-` the 2026-08-18 parity sweep of the shipped archives ·
> `D` numbered defects · `B1-`/`I-` the B1 execution and its interlude · `W-` phase W (desktop) ·
> `X-` stage S6 (web) · `KV-` KiteVideo · `KD-` the piloting surface · `KC-`/`KP-` KiteCodec and
> KitePlayer cross-repository rows · `AGW-` the Android GPU work.
>
> **The two repositories.** KitePlayer is the engine and the player. KiteCodec is the sibling that
> binds FFmpeg and ships the native trees; it lives at `../KiteCodec` and has its own git history.

> ## **WHAT IS IN THIS FILE**
>
> | section | what |
> |---|---|
> | 1 | the executor contract: how to work here |
> | 2 | the evidence rules in full |
> | 3 | the state of the code, verified |
> | 7 | constants |
> | 8 | the design digests: the contracts the code implements |
> | 9 | the verification protocol: what you run before claiming anything |
> | 12 | DRM and scope boundaries |
> | 13 | decisions already taken, not to be re-litigated |
> | 17.5 | the format conformance matrix |
> | 17.6 | size tiers |
> | 17.8 | network, un-parked by D-4 |
> | 17.11, 17.11.a, 17.11.b | the registers that still hold open rows |
> | 17.12 | **the current road**: which phase is being bought now |
> | 17.14 | the web stage and its open rows |
> | **17.15** | **THE CONSOLIDATED OPEN REGISTER. Start here if you want to know what is left.** |
| **17.22** | **The two criticals, decided and expanded: KP-TONEMAP-WARN and KC-WEB-IO, executor-ready.** |
> | 18 | the skeleton, for an executor with no context |

KitePlayer Kotlin Multiplatform, the piloting plan. Written 2026-08-09, revised the same
day after a second independent full audit of both repositories was verified claim by claim
against the source and merged in. This is the only planning document; since 2026-08-18 it is two
FILES and still one document. Everything the executor needs to know what is TRUE is in this file
and in the code; what already happened is in KPKMP-PAST.md and is not needed to work.

**Who this is for.** An executor agent (Opus 5, Ultracode) that will do the work but not
the thinking. Every decision in this file is already made. Do not reopen decisions. Where
this file is silent, the committed code's existing behaviour is the specification. If the
code proves a decision here wrong, take the smallest correct alternative and record the
deviation, with proof, in the Execution log (section 14).

**The shape of the plan.** Two horizons. Horizon A (section 10) is the executable run:
phases A0 to A6, each with named defects, tests and a gate. Horizon B (section 11) is the
product roadmap to a real 1.0: it is sequenced and decided but NOT part of this run. Do not
start Horizon B work unless the owner says so. Do not let Horizon A ship a claim that only
Horizon B can make true.

---

## 1. Executor contract

Read this section twice.

1. Work the phases in section 10 strictly in order: A0, A1, A2, A3, A4, A5, A6. Do not
   start a phase before the previous phase's gate passed. Do not start anything in
   section 11.
2. Every phase ends with its verification gate (section 9), an Execution log entry
   (section 14), and one git commit per repository touched. Section 9 has THREE TIERS and the
   tier is selected by the paths the phase changed, not by judgement; the log entry must name
   which tier ran and the rule that selected it. Tier 1 costs fourteen measured seconds and runs
   for every phase including a prose-only one. Commit style: first line is one
   imperative sentence describing the outcome, like the existing history ("Play video and
   audio in sync, and check the colours against FFmpeg"). Body is short prose. Never add a
   Co-Authored-By trailer or any other trailer.
3. Never create a git branch. Both repositories work on `main`. The executor commits locally and
   never pushes; the owner pushes. External or public publication and release steps are prepared
   by the executor and executed by the owner. `publishToMavenLocal` remains an executor-run build
   and consumption step.
4. No em dashes in any file: no code comment, no Markdown, no commit message. After every
   phase run the em dash scan in section 9 and fix anything it finds.
5. Editing `../KiteCodec` is allowed and expected. Its 85 core `@Test` cases, counted with
   `rg -n '@Test' kitecodec-core/src | wc -l`, must pass after every KiteCodec change. Its
   Gradle plugin functional class holds four tests; the two named tests
   (`kitecodecDslConfiguredAfterKotlinBlockIsSeenByTasks`,
   `missingLicenseChoiceFailsConfigurationWithInstructions`) fail on a clean checkout
   already, from before any of this work. Ignore those two, fix nothing about them, do not
   let them block a gate.
6. Never apply the atomicfu Gradle plugin to any module. The library dependency is fine.
   The plugin's bytecode transform registers a task depending on `androidMainClasses`,
   which AGP 9's Kotlin Multiplatform library plugin does not create, so applying it breaks
   the Android target. The cost of not having the transform is one wrapper object per
   atomic field, allocated at construction, never per operation. This is the single most
   likely trap to re-trigger by "cleaning up" a build file.
7. `kiteplayer-ffmpeg` and `kiteplayer-sample` need the KiteCodec Gradle plugin applied (it
   supplies the FFmpeg library search path at link time; KiteCodec's cinterop declares bare
   `-lavformat` and friends with no `-L`) and resolve KiteCodec from `mavenLocal()`. If
   resolution fails, run in `../KiteCodec`:
   `./gradlew publishToMavenLocal -Pkitecodec.hostTargetsOnly=true`. After any KiteCodec
   change that KitePlayer must see, republish the same way. This developer-machine loop is
   a build convenience, not a distribution story; distribution is Horizon B.
8. Native tests read test media through the `KITEPLAYER_TESTMEDIA` environment variable,
   set by the Gradle test task. Test clips are generated by `scripts/testmedia.sh` (needs
   `ffmpeg` on PATH) and are gitignored, as is `vendor/`. Regenerate clips whenever the
   script changes.
9. Legal rule. KitePlayer ships Apache-2.0. The reference players that were studied for its
   design (mpv, VLC, ffplay, QMPlay2, libplacebo; clones live gitignored in `vendor/`) are
   GPL or LGPL licensed: they are study-only. Designs, algorithms, thresholds and
   behavioural rules are facts and may be restated; source code text is expression, and
   translating it into Kotlin produces a derivative work that inherits the licence. Never
   transliterate their code. Never name a study-only source in a code comment as the origin
   of an implementation. Android Media3 and ExoPlayer are Apache-2.0 and may be ported
   directly with credit in NOTICE. Using `ffmpeg` and `ffprobe` binaries as test oracles is
   always fine. Differential testing against other players compares outputs, never source.
10. Documentation rules, binding for README, KDoc and everything public: no em dashes, no
    porting-journey narration, provenance gets one mention plus the License section, and no
    claim the code cannot support. Support is a measured property of a runnable artifact on
    a named platform, never an intention, a source-set declaration, or the compilation of
    an interface module. `POM_DESCRIPTION`, when publishing ever happens, must describe
    only what is implemented at that moment.
11. Multi-agent hints, since Ultracode fans out: agents may verify, read and test in
    parallel freely. Agents must never edit the same file in parallel. KiteCodec work in a
    phase completes and republishes to mavenLocal before KitePlayer adoption work in the
    same phase starts. Within one repository, parallel edits are safe only when the touched
    file sets are disjoint; the phase step lists mark what is independent.
12. `explicitApi()` is on in every KitePlayer module. Nothing is published yet, so
    signature changes are free. Do not add new public API beyond what a phase step names.
13. Append to the Execution log (section 14) at the end of every phase, and immediately
    whenever a decision deviates from this file. A deviation may tighten, replace or split
    a requirement when evidence proves the original wrong; it may never silently weaken a
    product promise.

---

## 2. Evidence rules

Every claim carries the strength of its evidence and no more. From strongest to weakest:

1. A repeatable release-mode automated test on a named real device with saved metrics.
2. A deterministic model, differential oracle, sanitizer or fuzz result on the exact
   contract.
3. A clean consumer build using only published artifacts.
4. A source-level proof with ownership and state invariants.
5. A unit test around an isolated helper.
6. A manual observation.
7. Compilation alone.
8. A declared target, KDoc, plan or README sentence.

No lower item may be presented as a higher one. The existing "163 tests pass" is level 5
plus level 6 evidence for macOS arm64 and nothing else. A cached up-to-date Gradle run
proves only that the cache is not red; gates rerun for real.

**Support tiers**, used in every platform statement from phase A0 onward:

| Tier | Meaning |
|---|---|
| T1 API | Common code compiles for the target. No playback claim of any kind. |
| T2 Codec | A runtime on the target can open, decode, seek, cancel and close real media. |
| T3-Full | Qualified audio plus video output, sync, subtitles and lifecycle on the target. |
| T3-Audio | Qualified audio-only output and lifecycle; explicitly no video or subtitle claim. |
| T4 Product | T3 plus the OS integrations (focus, background, PiP, routes) and clean packaging. |
| T5 Supported | T4 plus real-device qualification, security, performance and release gates, documented OS range. |

At the measured S1.b.5 baseline, macOS arm64 remains an experimental T3-Full candidate. One named
iOS simulator is a narrower experimental T2 Codec candidate: it opens, decodes and seeks real media,
reaches Ended and completes causally awaited close in the private sample, but real-media cancellation
and the broader qualification matrix remain absent, so it does not meet the full T2 Codec definition.
iOS arm64 remains T1 link-only, and every other target remains T1 or unqualified. The READMEs must say
exactly that until later evidence moves it.

---

## 3. State of the code, verified

**Re-written 2026-08-19 because the previous version had rotted into fiction.** It said there was
no `KitePlayer` facade and no `PlaybackCore`; both have existed for months. It counted 163 tests;
there are 874 in KitePlayer alone. It named baselines two dozen commits stale. A "state of the
code" section that describes a tree from months ago is worse than no section, because it is read
with the confidence of a measured one. What follows is measured against KitePlayer `e201186` and
KiteCodec `dd2823c`.

**What exists and works.** The full engine: `KitePlayer` facade over `PlaybackCore`'s actor loop,
workers for demux, both decoders, the audio feed and the video schedule, the quiesce handshake,
`SyncLaw`, `MediaClock`, the seek machine, packet queues, `AudioPlayback` and `VideoPlayback`.
Audio and video play in sync on macOS arm64 at 1080p30, 720p59.94 VFR and 4K HEVC 10 bit, each at
zero dropped frames and zero underruns. The audio path has a real windowed sinc resampler, a
measured downmix policy, a WSOLA tempo stage and pitch preservation. Subtitles decode and draw,
with libass on all six Kotlin/Native targets plus Android. There are Android, Apple, desktop JVM,
Linux, and Web output paths, and the desktop JVM plays the whole 17.5 conformance matrix.

**The measured size of the thing.** 874 Kotlin test functions in KitePlayer across 123 test files,
256 in KiteCodec across 42; nine C suites in KiteCodec and ten in KitePlayer's real-time core.

**Where the two repositories stand apart, and it matters.** KiteCodec HAS continuous integration:
four workflows (`ci`, `docs`, `publish`, `release-binaries`). **KitePlayer has no `.github`
directory at all.** Every claim about KitePlayer's health is a claim about somebody's laptop.

**What still does not exist.** No published artifact of either repository that a stranger can
resolve. No audio device recovery. No viewport aware subtitle rasterisation. On the Web, no test
source set for the Wasm backend at all, so a whole class of fixes is written but unfalsifiable.
DRM is out of scope by decision (section 12), not missing by accident.

**The verdict this file acts on.** The 2026-08-18 audit and its verification pass agree with the
two earlier reviews: the engine core is genuinely Kotlin first, the KiteCodec layer is genuinely
FFmpeg first, and what is left divides cleanly in two. The CORRECTNESS half is nearly finished:
the logs close ten of twenty release blockers, and of the ten still open, nine are packaging, so
**KC-WEB-IO is the only correctness blocker left**. Read "closed" with one caution the
2026-08-19 pass earned: two of those ten are overstated, four are real code that no test can
falsify because the Wasm backend has no test source set, and one of them introduced two leaks while
fixing one. 17.16 names each. The DISTRIBUTION half, written when it had not started, is now half
finished: KiteCodec is on Maven Central and its whole `P0-11..P0-19` program closed on 2026-08-24.
KitePlayer's half has not started, and it is not owner-blocked any more, it is work: see `KP-PROD`
phase 1, which begins with this repository having no CI at all.
---

## 7. Constants

Every tuned number lives in code: `SyncLaw` (sync thresholds 40/100 ms, duplication
100 ms, no-sync 10 s, resync 100 ms, max frame duration 3600 s / 10 s),
`FrameDurationEstimator` (3.1 ms unrounding, snap after 16, 40 ms fallback), `SeekTiming`
(coalesce 0.3 s, precise tolerance 5 ms, backoff ladder 0 / 0.5 / 2 / 8 s), `BufferPolicy`
defaults (ready 25 packets or 1 s, soft 5 s, caps 32 MB and 30 s, frame queue 4, live
window 20 s / 10 s), `AudioPlayback` (ring: max of 8 device buffers or 200 ms). They are
the residue of a decade of other players' bug reports; retune only with measured evidence
recorded in the Execution log. Two more are decided for A5: the core loop sleeps at most
50 ms when nothing asks earlier, and a still image or cover-art frame displays for 5 s.
The gain ramp default is 5 ms (D12).

---

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
./gradlew :kitecodec-core:checkFFmpegRecipes          # vendored trees vs the recipe, no build
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
target whose archive this run did not build. It DOES now catch a vendored FFmpeg tree baked from a
different recipe than the checkout describes, which nothing caught before 2026-08-20: the
`av1_videotoolbox` pin sat unbaked in every Apple tree for a day with every gate green.

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

## 12. DRM and scope boundaries

Widevine, FairPlay, PlayReady and protected surfaces are licensed platform systems, not
FFmpeg features. Until a CDM integration exists (a separate product decision), DRM
content returns a typed `DRMUnsupported` result. Transport encryption FFmpeg can decrypt
with application-supplied keys is distinct and belongs to B6. Casting (Chromecast,
AirPlay) is a remote target abstraction, Horizon B at the earliest. Optical-disc menu
navigation is out of scope entirely. "Dependency-only" never means native-code-free: it
means the native runtime arrives transitively, legally and verified, without the consumer
writing build scripts.

---

## 13. Decisions already taken (do not re-litigate)

Standing decisions:

1. Clean-room legality per contract rule 9; a file that reads like a transliteration is a
   defect to rewrite.
2. Both repositories stay on `main`. Both were pushed on 2026-08-10, proved by their
   `origin/main` reflogs and the 2026-08-11 remote snapshot; commits since then are local by
   design. The executor commits locally and the owner pushes. External or public publication is
   the S5 decision and owner action. `vendor/` and `testmedia/` stay untracked.
3. macOS arm64 is the proving ground; the engine stays free of macOS assumptions, and
   platform-neutral APIs are checked against the Horizon B platform set before they
   freeze (the A5 gate includes that review for the facade surface).
4. KiteCodec exposes player needs behind `@KiteCodecLowLevelApi`; raw lifetimes deserve
   an explicit opt-in.
5. The audio path is pull-shaped end to end.
6. mavenLocal consumption is the developer loop (contract rule 7); distribution is B7 and
   the Gradle plugin is one provisioning strategy, not a law.
7. The atomicfu Gradle plugin stays banned.
8. The Core Graphics renderer is the Horizon A renderer and the permanent correctness
   reference; Metal (B5) is the qualifying renderer.

Decisions from the merged reviews, binding on this run:

9. The SPI stays backend-neutral, but information loss that destroys correctness is not
   accepted: channel masks (D30), colour metadata, and the timestamp model survive the
   mapping. Remaining lossy corners are named in the truth ledger.
10. Timestamps normalise exactly once at the source boundary; `mapTimestamp` subtracts
    the origin, `mapDuration` never does (D10).
11. Missing pts is synthesised at the decoder wrapper; `VideoFrame.pts` stays non-null.
    Low-level KiteCodec keeps representing absence honestly (null helpers).
12. SUPERSEDED 2026-08-19. The linear resampler is GONE, replaced by `SincResampler`, a
    32 tap windowed sinc at 512 phases. **swresample was never adopted and is no longer the
    plan**: it is linked into KiteCodec and its version is reported, but no `swr_` function is
    called from any Kotlin or C in either repository, and no `SwrContext` exists for anyone to
    own. The library being present keeps the option open; nothing depends on taking it. What
    remains of the old audio quality row is NOT the rate conversion, it is that `ChannelMixer`
    folds only to stereo (17.19).
13. SUPERSEDED 2026-08-19. Speed with audio open works. `TempoStage` does pitch preserving time
    stretch, `preservePitch` is public and published in the snapshot, and the tempo tail is part
    of the end of media gate.
14. Track switching reopens seekable sources only (D32 gate) until B6.
15. HDR and BT.2020 CL render approximately WITH a typed warning in Horizon A; the 1.0
    gate requires the managed path (B5), at which point approximate output becomes an
    explicit opt-in policy.
16. The facade ships the digest 8.2 surface; everything else is truth-ledger-marked, not
    stubbed.
17. `AudioPlayback` locks only its non-suspending members (D4); actor confinement is the
    real answer and A5 delivers it.
18. Frame ownership: single owner per instant now (D20); reference-counted leases arrive
    with hardware surfaces (B5).
19. Generations are defence in depth; quiescence acknowledgements are the seek contract
    (D25).
20. Presentation truth: submitted and drawn are different numbers with different names
    (D21); "first frame" always means terminal renderer feedback.
21. Support claims use the tier vocabulary of section 2, generated from evidence in
    Horizon B; hand-written claims carry today's honest tiers meanwhile.
22. The evidence hierarchy of section 2 governs every "done": when code, artifact,
    documentation and measurement disagree, the weakest result is the truth until all
    four agree.

---

### 17.5 The format conformance matrix

One suite, grown once, run everywhere: the existing testmedia clips plus MKV multi-track,
ordered-chapters-free MKV baseline, VP9/AV1/mpeg4 samples, 10-bit HEVC, audio-only files, files
with rotation, VFR, and broken-index torture cases. Every platform exit criterion above means
THIS matrix, so "plays all formats" is one measured claim, not a per-platform mood.

### 17.6 Size tiers (D-5)

- **lean**: h264, hevc, aac, mp3, flac, pcm; mp4/mov/matroska/webm demuxers. The default
  artifact, the number goal 5 is judged on.
- **standard**: lean plus vp8, vp9, av1, opus, vorbis, mpeg4, images. The current playback
  profile.
- **full**: consumer-built via the plugin, which remains for exactly this and GPL opt-ins.
Exit numbers are MEASURED per target at S5 and written in the log; no size is promised before it
is measured.

### 17.8 Parked: network (old B6)

**UN-PARKED 2026-08-16: D-4 amended by the owner; the work enters as 17.12 phase M1,
Kotlin-first (custom AVIO bridge plus platform TLS), with the paragraph below still binding.**

Everything in section 11's B6 stays specified there, unbuilt by decision D-4. Cost if it never
happens: KitePlayer plays files, not streams; the engine's undocumented URL path remains
unhardened (no interrupt callback, no timeout bounds: draft C-52 to C-54 record the exact holes)
and must not be advertised. First network work re-opens those three items before anything else.

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
- SOL-S3: **CLOSED BY CORRECTION 2026-08-25. The row was false, and had been since it was
  written.** It says the region's own `width`/`height` are "never read". `OverlayImage` has no
  `width` or `height` to read: it is `(x, y, bitmap)` and `git show 0759064` proves it was born
  that way and never carried anything else. The bitmap IS the region, so `bitmap.width * sx` is
  not a substitute for the region size, it is the region size. Two things this pass also
  established, because the row named only one renderer: `UIKitVideoRenderer.drawOverlayInto` is
  byte-identical to the AppKit one, so had the defect been real it was always two defects, not
  one. Re-shaping `OverlayImage` to carry a target rect is a public API change and a design act
  (18.3 rule 6), not the edit this row described.
- SOL-S4 to SOL-S6: CLOSED by S4.f's slice (7e9bb12): open-end resolution in both parsers,
  word-boundary block keywords, and span-text entity decoding.
- SOL-S7 **REDUCED 2026-08-25 (PAST 14.149) by the second of the two fixes it offered: the claims
  are narrowed, and pinned by tests so they cannot drift back.** The row was true and vaguer than
  the tree. Measured across all three rasterizers rather than described:

  | Field | Desktop | Apple | Android |
  |---|---|---|---|
  | `primaryColor`, `bold`, `italic`, `underline`, `strikeThrough` | per span | per span | per span |
  | `fontFamily` | per span | IGNORED | IGNORED |
  | `fontSizePx`, `outlineColor`, `outlineWidthPx` | first span, whole cue | first span, whole cue | first span, whole cue |
  | `shadowColor`, `shadowOffsetPx` | IGNORED | IGNORED | IGNORED |
  | `CueLayout.wrap` | IGNORED | IGNORED | IGNORED |

  Two things the row did not say. **`fontFamily` is desktop-only**, so the phone backends, which are
  the product, use the platform face whatever a script asks for. **`shadowColor` defaults to a
  visible 50% black at a 1px offset and nothing has ever drawn it**, so that default was inert on
  every platform. The remainder is feature work, not documentation: a shadow pass needs each cue's
  bitmap grown by the offset and its placement moved with it, which is layout, not a colour.
  Home: S4.f, unchanged.
- SOL-S8: **CLOSED 2026-08-25 (PAST 14.148). True as written, and it was THREE defects, not one.**
  Every rasterizer grew `stackedBottom` on `alignment.isBottom` alone, so a cue carrying an authored
  `positionY` reserved room in a stack it never stood in and lifted every later ordinary subtitle by
  its own height. The row named the behaviour once; `DesktopSubtitleRasterizer`, `AppleSubtitleRasterizer`
  and `AndroidSubtitleRasterizer` each held their own copy, each with a private
  `CueAlignment.isBottom` that had exactly one caller. One shared
  `CueLayout.usesImplicitBottomStack` in `kiteplayer-output` commonMain replaces all three, so the
  rule cannot drift a fourth time.

API truth:
- SOL-API1: CLOSED by the S4.g surge. startPosition is honoured in two halves (pre-worker
  source move, then a precise landing through the ordinary machine; unhonourable requests warn
  StartPositionIgnored, typed). headers and formatHint ride KD-4's pre-open funnel as the http
  `headers` option and a `format_whitelist` of one; an explicit openOptions key wins over the
  typed sugar (preOpenOptions, unit-tested pure).
- SOL-API2 **CLOSED 2026-08-25 (PAST 14.159) BY SUBTRACTION, owner-decided: the knobs are gone.**
  `preservePitch` was real and struck earlier. The rest are deleted from the public surface rather
  than documented or built.

  **The index row said FIVE and the detail bullet listed FOUR; the index was right.** Counted in the
  tree on 2026-08-25: `logger`, `liveBackBuffer`, `liveMaxLag`, `startDisabled` AND
  `assumedLatencyWhenUnreliable`, all declared, three of them validated in an `init` block, and
  every one of them read by nothing outside its own declaration. The fifth had been dropped from
  the detail on 08-24 and only the index kept it.

  **Deleted, on the production plan's own terms**, which say the dead knobs stop lying. Building
  them was not available: three are live-streaming settings and there is no live path at all, which
  `liveBackBuffer`'s own KDoc admitted. An API that ACCEPTS a setting and ignores it is worse than
  one that does not accept it, because the caller who sets it believes it took effect. This is 0.x,
  and the live knobs return with the live work.

  `PlayerLogger` and `LogLevel` went with them. `logger` was their only consumer, their KDoc already
  said "Nothing calls it", and they duplicated `KiteLog.Sink`, which is the shipped seam that
  actually works. Leaving a public interface nothing accepts would have been the same defect one
  level down.
- SOL-API3: CLOSED by the S4.g surge. KeyframeThenRefine runs the seek machine's ladder loop in
  two phases: the keyframe lands and PRESENTS first, then an ordinary precise landing on the
  exact frame; SeekCompleted and the replies carry the exact landing only, and a keyframe
  already on the target skips the refine. The coalescing test now pins two flush cycles for one
  merged two-phase seek.
- SOL-API4 **RECLASSIFIED 2026-08-25 (PAST 14.158) from defect to FEATURE ROADMAP, owner-decided.**
  `bufferedRanges` is REAL since M5's demuxer cache (PlaybackCore computes it from the cache window;
  CachingMediaIo's KDoc names it) and was struck earlier. Still declared and honestly KDoc'd as not
  implemented, verified 2026-08-18 and unchanged since: `droppedFramesDecode`, `audioLatency`,
  `containerBitrate`, `SyncMode.ExternalMaster` and `LateAndDecode`.

  **Its old pointer was FALSE and nearly cost the record.** The row said "Home: their section 11
  items", and section 11 is a superseded roadmap that has lived in KPKMP-PAST.md since the split,
  was marked superseded on 2026-08-11 by its own banner, and **does not name any of these five
  fields**. Closing this row as a duplicate of that roadmap, which is what its own text invited on
  2026-08-25, would have deleted the only record anywhere that five public API fields are unbuilt.
  The pointer is deleted rather than followed.

  **Kept, and marked ROADMAP so it stops being triaged as a defect.** Nothing here lies to a
  consumer: the fields exist, the KDoc says they are not implemented, and that is a promise not yet
  kept rather than a promise broken. It kept surfacing in effort sweeps as a phantom quick win
  because a row that says "five placeholders" reads like a cleanup. It is five features.
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
- SOL-C1 [V] STILL OPEN, and the number is **198**, corrected 2026-08-24. The detail said 213 while
  the index said 198, and the index was right: this row's own rule is that the DETAIL wins when they
  disagree, so it is the detail that had to be re-measured rather than the index adjusted to match.
  Two independent measurements agree. `nm -g` on the built `macos_arm64` archive reports exactly 198
  exported `T` symbols and no others, and the two public headers declare 191 (`kitecodec_helpers.h`)
  plus 7 (`kitecodec_abi.h`), which is the same 198. A grep for definitions across the eleven `.c`
  files returns 214, and that is the trap the old figure fell into: it counts `static` helpers and
  multi-line signatures the build never exports. The eleven sources are unchanged (helpers_codec,
  codecpar, error, filter, format, frame, hwaccel, packet, playback, stream, and kitecodec_abi).
  Replace the one-line helper C (packet, codecpar, stream,
  error, trivial frame and codec, most of format and playback) with direct cinterop on
  Kotlin/Native. KEEP: the JNI adapter, the ABI/identity probe, the get_format callback, FFmpeg
  itself, and the whole real-time C island. The goal is no redundant C, not no C.
- SOL-C2 [V] STILL OPEN, re-verified 2026-08-18: `kiteplayer-rt/native/src/kite_rt_coreaudio.c`
  is present and carries the setup. Move non-real-time CoreAudio setup, session policy,
  route/interruption handling, capability queries and error mapping to Kotlin; unsupported-platform
  C stubs become expect/actual. Home: S3.
- SOL-C3 [V] STILL OPEN as a C-REDUCTION item, and it is worth saying loudly what it is NOT,
  because a triage pass read this row as a live truncation bug on 2026-08-25. **Nothing truncates.**
  Every site is `snprintf(args, sizeof(args), ...)`, the format string carries only `%d` integers
  and a pixel-format name, and `native/kitecodec-c/tests/test_buffers.c` already measures the widest
  reachable input at 162 bytes against 512, with six cases pinning it. The caller's own
  `description` never enters `args`; it goes to `full_desc[2048]`, whose overflow closed under D27.
  What is open is only that the composition is still C: it moves to common Kotlin, retiring the
  buffers. Home: with SOL-C1, and it is that row's size, not an M.

Kotlin modernization (hygiene, no schedule, no syntax churn before ownership work):
- SOL-K1 **DONE 2026-08-24.** `-Xcontext-parameters` is gone from `kitecodec-core/build.gradle.kts`.
  It was two kinds of dead: the flag is no longer needed on this Kotlin, and this module declares no
  context parameter anywhere, which was checked rather than assumed (a grep for a context
  declaration across every source set returns nothing; the only `context(` hits are FFmpeg function
  names like `ffkmp_codecpar_from_context`). Verified by compiling jvm, macosArm64, wasmJs and
  linuxX64 plus the jvm suite with the flag removed.

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
- SOL-B4: **CLOSED 2026-08-25 (PAST 14.144), and every number in it was true.** Measured, not
  carried: `otool -l` on the committed `libavutil.a` read `minos 26.0`, `CompileKiteCodecCTask`
  compiled at `macos11.0`, and `minVersion.macos` in konan.properties for Kotlin 2.4.10 is 12.0.
  The macOS branches of `BuildFFmpegTask` passed no `-mmacosx-version-min` at all while every iOS
  branch always passed `-mios-version-min`, which is how the SDK's floor got in. One constant,
  `BuildFFmpegTask.MACOS_DEPLOYMENT_TARGET = "12.0"`, is now read by both macOS FFmpeg branches
  and by both macOS C targets. 12.0 because konan imposes it whatever anything else says. Left
  open as its own row: the pin is invisible to the staleness check (`KC-FLOOR-DRIFT`).
- SOL-B5 **DECIDED 2026-08-25 (PAST 14.154): every ABI stays supported. The proposal to drop
  32-bit ARM was REJECTED BY YUROYAMI.** The reasoning is recorded so no future pass re-argues it:
  the "minSdk 26 already excludes the 32-bit fleet" argument is true for phones and FALSE for TV.
  Fire-TV-class sticks are 32-bit-only for apps on modern Android, budget TV boxes ship 32-bit
  userspace on 64-bit silicon to save RAM, Synkplay ships all ABIs with Android TV first among
  them, and the owner's own kite3d already publishes androidNativeArm32. The performance objection
  also shrinks on TV: the shipping Android path is MediaCodec hardware decode, so v7a support is
  mostly JNI plumbing, demux and the audio ring, not software AV1.

  What remains is ENGINEERING, not a decision: add armeabi-v7a to LinkKiteCodecJniTask's ABI
  recipes and the libass JNI adapter. Three gates before the ABI is CLAIMED, per the device-true
  law: (1) the kiteplayer-rt ring publishes 64-bit positions, and 32-bit ARM only reads them
  untorn through real atomic ops (LDREXD class), so the ring gets an audit plus a compile-time
  lock-free assert; (2) a CI compile lane so it cannot rot; (3) one smoke run on a real TV stick.
  x86-32 is not refused either; it is added the day Synkplay actually ships it. Home: S5 entry.
- SOL-B6: **CLOSED 2026-08-25 (PAST 14.147). The defect was real and live; the audit's proposed
  fix was not the defect.** `mavenLocal()` sat FIRST in both of `settings.gradle.kts`'s resolution
  blocks. The trap measured: KiteCodec's working tree says `VERSION=0.1.3`, KitePlayer pins
  `kitecodec = "0.1.3"`, and Central serves 0.1.3, so the `publishToMavenLocal` the file's own
  comment INSTRUCTED would republish the same version string with different bytes and nothing
  anywhere would tell them apart. It was one command from biting, and only missed because this
  machine's `~/.m2` happened to stop at 0.1.1. mavenLocal is now opt-in behind
  `-Pkiteplayer.useMavenLocal=true` and says so out loud when on; it is DELETED outright from
  `pluginManagement`, where every plugin id is JetBrains, Android or vanniktech and it could only
  ever have shadowed something. **The composite build the audit proposed is declined**: it is a
  design act, and the defect this row actually describes did not need it. Accepted limitation,
  stated rather than hidden: nothing automated stops mavenLocal being re-added unconditionally.
  A CI grep would cry wolf on a reformat, and this file warns elsewhere that a check which cries
  wolf gets disabled within a day.
- SOL-B7 **REDUCED to nothing either project can fix, measured 2026-08-24.** Both builds emit
  exactly ONE Gradle 10 deprecation, and it is the same one in both: "Using a Project object as a
  dependency notation". It does not come from either project's build scripts. Gradle's own problems
  report names the source, which is how this stopped being a guess:
  `"locations":[{"pluginId":"com.android.internal.kotlin.multiplatform.library"}]`, that is AGP
  9.2.1's Android KMP library plugin. Checked by swapping the two `project(":x")` calls in
  `:kiteplayer-compose-interop` for type-safe accessors: the warning did not move.

  **LABELLED BLOCKED-UPSTREAM 2026-08-25, owner-decided, and that label is the whole point of this
  edit.** The row was correct and kept costing attention anyway: every sweep looking for something
  small found a one-warning build row, read the measurement, and moved on. A row nobody can act on
  should say so in its first three words rather than in its last sentence.

  **It is kept rather than closed because the recheck matters**: this deprecation becomes an ERROR
  in Gradle 10, so the day AGP ships a fix is the day this must be re-measured. The trigger is
  written down: **the next AGP bump, and only then.** Suppressing the warning was refused for the
  same reason, since silencing a deprecation you did not write is how you discover it as a build
  break instead of a warning. Home: S5.
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
**WALKED, all nineteen, 2026-08-24.** The 08-18 note sampled four of them and asked whoever came
next to walk the rest. Every test name in both repositories was collected (1,364 of them, Kotlin
backtick names plus the C suites' case strings) and each row was searched against that corpus; the
ones that matched nothing were searched again over test file CONTENT, so an absence is an absence
rather than a naming difference.

**Nine are already written, and the row should stop carrying them:**

| Row | The test that covers it |
|---|---|
| concurrent JNI op and close | "non suspending close and concurrent awaited closes share one success" |
| 32-bit near-boundary ring allocation under ASan | `test_ring_alloc.c`, run in the asan variant by `run-c-tests.sh` |
| the 24-hour AudioTrack wrap simulation | "the timestamp frame position wrap-extends exactly like the head", "a frame position past thirty two bits is read as is, with no wrap fold" |
| simultaneous subtitle images | "overlapping cues are kept and sorted by start time" |
| alpha golden tests | "the alpha row never touches colour", "no pixel ever carries more colour than alpha" |
| non-planar and odd-sized Metal frames | "an odd 17x9 frame converts, and the colour survives the half chroma row", "a packed BGRA pixel buffer wraps at the buffer's own size and passes through" |
| failed CoreAudio shutdown with a live callback | "closing while the device is running is safe and stops the callbacks" |
| attached-picture-first media | "a cover art still image is shown for five seconds and then finishes" |
| secure-protocol link smokes | "an https manifest cannot be talked down to http" |

**Ten are genuinely owed**: cached `Frame.info` after close, filter-callback frame retention, failed
quiescence during renderer replacement, cancellation after partial audio submission, device-sleep
clock epochs, negative start times, foreign `StreamInfo`, decoder output diverging from codec
parameters, empty-output `MediaSink` finalization, and midstream format changes.

**Four of those ten were checked twice** because a near-miss is easy to mistake for a hit: "the C
callback holds its deadline" is the audio callback and not filter-frame retention; "a flush after
both sides are quiescent" is the ring and not renderer replacement; "partial writes loop until the
block is fully submitted" is about writing and not about cancelling; and every `codecpar` test is a
NULL-argument refusal rather than a divergence check.

**The honest bound on this walk**: it matches what a test is NAMED, which is the strongest claim a
search can make. A test can cover a behaviour without naming it, so a struck row is "somebody wrote
a test for this" and not "the behaviour is fully covered".

**Register addition 2026-08-18 (the parity sweep: every shipped archive read with llvm-nm rather
than trusted from its configure record).** Closed the same day: KC-AV1SW above; the iOS assembly
and AudioToolbox gaps; libass on every Kotlin/Native target plus Android; https on the web. Opened
by the same sweep and NOT yet answered:

- PAR-1 **CLOSED 2026-08-25 (PAST 14.157) by moving the PROSE, owner-decided. Reopened narrower as
  `PAR-WIN-HW`.** mingw-x64's `libavcodec.a` carries EIGHTEEN d3d11va/d3d11va2/dxva2 hwaccels,
  compiled because the mingw profile never passed `--disable-autodetect`, while decision W-D4 and
  `PlatformDecoderSelection.mingw.kt` both stated that no D3D11VA hwaccel is compiled.

  **Either side of a contradiction can move, and here the cheap side was the true one.** The
  BEHAVIOUR was never wrong: Windows offers no hardware route, which is correct, because compiled
  is not plumbed and an unplumbed hwaccel would fail every attach and appear in diagnostics as a
  decoder that never ran. Only the sentence was wrong. Stripping the hwaccels instead would change
  the configure recipe, make every baked Windows tree stale, and cost a rebake and a binary release
  to DELETE code that Windows video output will want back.

  The comment now says compiled, not plumbed, planned. What remains is the plumbing, which is real
  KiteCodec work needing a hardware device context and a frame download path, plus a Windows machine
  to prove it: that is `PAR-WIN-HW`, and it lands with Windows video output rather than alone.
- PAR-2 [V] Linux x64 and arm64 compile ZERO hwaccels, so those trees decode everything on the CPU.
  Honest and recorded, unlike PAR-1. VAAPI is the candidate. Home: with PAR-1's decision.
- PAR-3 [V] android-x64 still builds with --disable-asm, so the emulator ABI has no SSE/AVX in
  libavcodec (0 SIMD symbols against android-arm64's 1363 NEON). Emulator-only, hence low priority,
  but it is the same omission the iOS arm64 targets carried until 2026-08-17.
- PAR-4 CLOSED 2026-08-18, commit "Hear the picture in a browser, and say what it costs". The wasm
  profile enabled the matroska and webm demuxers while enabling NEITHER the opus NOR the vorbis
  decoder, so a .webm on the web opened, decoded video, and had no audio decoder for its audio
  stream. `ffprobe` says BOTH `vp9.webm` and `av1.mkv` carry opus, and both are MustPlay rows, so
  this was silently costing the matrix two rows' audio rather than an exotic file's. Both decoders
  and both parsers are now in the profile; verified at the symbol level in the rebuilt tree with
  `llvm-nm --defined-only native-libs/lgpl/wasm32/lib/libavcodec.a`, which shows `ff_opus_decoder`,
  `ff_vorbis_decoder`, `ff_opus_parser` and `ff_vorbis_parser`. The test that should have caught it
  was the weak part: it asserted a flat hand-written codec list that nobody had put opus on. It now
  pins codecs to the ROWS that need them, with a named `knownAbsent` set carrying av1's reason, so
  a gap must be written down to be silenced. `--enable-demuxer=ogg` was deliberately NOT added: no
  matrix row is ogg-contained, and the web tier stays lean by naming what it serves.
  END TO END, which is stronger than the symbols: `scripts/wasm-matrix-probe.sh` links the wasm
  module with emcc and decodes the matrix under node, and now reports `vp9.webm PLAYS video:vp9
  plays, audio:opus plays` and `av1.mkv PLAYS video:av1 not in web tier, audio:opus plays`. Real
  opus frames out of the web build. The probe itself was the second thing hiding this: it decoded
  ONE stream per row, video where a row had video, so vp9.webm reported PLAYS on its picture alone.
  It now reports per stream, and separates the codec tier from the DEMUXER tier so a container the
  lean build cannot open reads as omitted rather than broken. 15 rows play, 11 streams or containers
  omitted by the tier, 0 unexpected failures.
- PAR-5 [V] :kiteplayer-output declares linuxX64, linuxArm64 and mingwX64 targets but has NO
  linuxMain or mingwMain source set, so those three compile the common file alone: no audio sink,
  no renderer, no clock. Desktop playback rides the jvm target instead. RECOMMENDED CLOSE: record
  it as a decision (native desktop targets are engine-only; consumers bring output through the SPI)
  rather than building ALSA and WASAPI backends nobody has asked for.
- PAR-6 [V] 08-19 REWRITTEN, and it is worse than the old row said. It used to read "AV1 hardware
  decode has never been positively proven", which implied the route was complete and only the
  hardware was missing. Reading the code around the claim showed the route CANNOT complete on any
  build this project ships.

  **ABSORBED THE `4K` ROW, 2026-08-25, owner decision.** That row asked whether 4K should stay a
  v1 non-goal, and it had sat unanswered since 08-18 because it was pointed at the wrong thing. The
  non-goal was set on a SOFTWARE measurement, 4K HEVC 10-bit at exactly 1.0x, and 4K on a phone was
  never going to be won in software. It is a hardware question, so it belongs to the hardware row.

  **The measurement that decides it is already on record and is not an opinion** (17.14, the web
  decode spike): hardware decode reached 715 fps against 182 fps software on comparable 1080p,
  about 3.9 times faster, and the capability probe answered YES for HEVC Main10. 4K is roughly four
  times 1080p's pixels, so that margin is wide. What is missing is a 4K clip run through a working
  hardware path, which is exactly what this row is blocking.

  **So 4K stops being a question and becomes an EXIT CRITERION here.** Software 4K remains a
  non-goal, permanently and by decision rather than by inheritance. When hardware decode can engage,
  one 4K clip through the hardware path settles what 4K support means, and the answer is recorded
  against this row rather than a separate one nobody could close.

  **What was fixed on 2026-08-19.** `appleHwaccelDecodeArgs()` pinned only `h264_videotoolbox` and
  `hevc_videotoolbox`, so `av1_videotoolbox` was never named. It is pinned now, and the two
  configure goldens moved with it. This takes effect the next time an Apple FFmpeg tree is built;
  no published artifact changes.

  **What the pin does NOT fix, which is the row's real remainder.** An hwaccel attaches to a
  DECODER. `libdav1d` is an external decoder and carries no hwaccel at all, and
  `avcodec_find_decoder(AV_CODEC_ID_AV1)` walks `codec_list` in the order `allcodecs.c` declares,
  where `ff_libdav1d_decoder` (line 776) sits ahead of `ff_av1_decoder` (line 846). Every
  KitePlayer consumer build carries dav1d, so the lookup returns libdav1d, `get_format` is never
  offered `AV_PIX_FMT_VIDEOTOOLBOX`, and `ffkmp_codecctx_use_videotoolbox` attaches a device
  context that nothing will ever ask about. **On an iPhone 15 Pro or newer, AV1 decodes on the CPU
  while the AV1 silicon sits idle, and no amount of hardware would change that.**

  **What closing it takes.** A decoder chosen BY NAME, which KiteCodec has no path for today, plus
  a policy: open native `av1` with VideoToolbox attached, and fall back to `libdav1d` in software
  when the hardware refuses, which is the same shape D-2's measured fallback already has for h264
  and hevc. Size M. After that it still needs an A17 Pro / M3 or newer machine for positive proof,
  because this one is an M2 with no AV1 silicon, so it can only ever prove the refusal path. Owner
  device fare for the proof, ordinary work for the route.
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
| Zero-copy hardware path | CVPixelBuffer to CAMetalLayer | Android API 31+ HardwareBuffer images; on APPLE it is NOT zero-copy and this row overstated it (corrected 2026-08-23): the iOS path reads the frame back from Metal into RGBA and Skia copies it again per frame. S2.d shipped the draw-phase overlays, not the Apple zero-copy, which is still KV-2's | interop WINS on Apple; even on Android |
| Sustained fullscreen power | display controller presents; GPU idles | GPU lightly awake per frame | interop wins, RECORDED (17.9's honest cost, unchanged by design) |

The one deliberate non-goal stands as designed: sustained fullscreen belongs to the baseline
wrapper (D-6 keeps both paths for exactly this reason), so it is not a loss against the
audit's rule but the division of labour the register chose.

**Corrected 2026-08-23.** "KiteVideo loses nowhere else" was true only if the Apple zero-copy row
was, and it was not: that row asserted a path S2.d never built. On Apple today KiteVideo pays a
Metal readback plus a Skia copy per frame where the interop renderer wraps the CVPixelBuffer with
no copy at all, so KiteVideo loses on Apple copies as well, until KV-2 lands. The Android half of
the row stands as written.

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

### 17.12 The renewed road, 2026-08-16

Written by Fable 5 at the owner's direction after the two 0.0.5/0.0.6 feature surges and the
mpv-android dependency study made the remaining gaps and their real costs visible. This
subsection supersedes the INTER-STAGE ORDER of 17.2 exactly the way section 17 superseded
section 11's numbering: every stage, register, expansion and exit criterion stands where it is
written and is not restated here; what changes is which outcome is bought first. The stage law
of 17.1 still rules: phases are named by user-visible outcomes, prerequisites live inside the
phase that needs them, and a phase entered without its 17.1 expansion ritual is a contract
violation. Register homes named below are owner moves under 17.11's own rule that homes are
proposals the owner may move.

**Owner decision amendments, 2026-08-16:**

- **D-4 AMENDED, network un-parked.** The owner reopened network for phase M below. 17.8's
  standing sentence applies on entry: draft items C-52 to C-54 (interrupt callback, timeout
  bounds, the unhardened URL path) reopen before anything else. The un-parking is Kotlin-first
  by construction: the network stack is Kotlin, not more C.
- **D-7, NEW: no new mandatory native libraries.** The measure for every mpv-parity gap is
  Kotlin (or shader source we author) first. A native library may arrive only as an OPTIONAL
  separate module, and only when no Kotlin path can exist (dav1d: SIMD software decode) or when
  correctness parity demands it after the Kotlin tier ships (libass). Applied verdicts, argued
  on cost against benefit and closed with the owner in session:
  - libxml2: NEVER. Adaptive-streaming manifests parse in commonMain Kotlin; the adaptive layer
    is Kotlin segment logic feeding the decoder, the media3 shape, not FFmpeg's dash demuxer.
  - mbedtls and curl: REJECTED. Vendored crypto is a recurring CVE duty. TLS comes from the OS
    through the M1 bridge (Ktor: OkHttp engine on Android, NSURLSession on Apple). Fallback
    only if an FFmpeg-native protocol Ktor cannot front is ever demanded, and then as its own
    decision.
  - libplacebo: REJECTED as a dependency. One feature is stolen as our own shader source: tone
    mapping (closes D16). Scaling kernels, debanding and dithering are parked until desktop and
    TV surfaces matter (phase W or later); their benefit on phone panels does not carry their
    cost.
  - libass chain (libass, freetype, harfbuzz, fribidi, fontconfig, libunibreak): DEFERRED to
    phase L as one optional module. The Kotlin ASS dialogue tier ships first in M; the platform
    text engines (CoreText, Android text stack) already contain the shaping, bidi, line-break
    and font-discovery jobs of four of those six libraries.
  - dav1d: ACCEPTED in principle, demand-driven, optional module, roughly one night (meson
    builds clean; FFmpeg adopts it with one configure flag). Executes when the first real
    no-AV1-hardware complaint arrives, not before. Register row KC-AV1SW keeps it.

**The phase order. Each phase completes before the next begins, riders excepted.**

**M. SUPREME ON MOBILE.** The owner's first market. Exit: on the named Android device and an
iPhone, KitePlayer streams https media, shows styled dialogue-grade ASS, presents HDR without
washout, and survives the robustness rows below; no new mandatory native libraries entered the
default artifact. Contents, in build order:

**Progress 2026-08-17 (the phase-M, network and M4 surges, section 14):** M1, M2, M3, M4 and
M5 CLOSED (A6 and a few M4 rows PARTIAL with named remainders); KP-TLS closed by design; the
adaptive layer's first tier landed end to end; dav1d in both phone-flagship trees behind its
DSL toggle; phase L's chain and module opened early (Android JNI bridge and per-frame hook
remain). PHASE M IS COMPLETE except the owner riders (iPhone KiteStats, AGW-1). Next by the
road's order: W.
  - **M1, the network trust layer.** Verify KP-TLS, then the custom AVIO bridge: one C callback
    surface in KiteCodec (avio read/seek into the engine), cinterop and JNI actuals, wired to
    `MediaIo` so the SPI stops being unimplemented surface, with Ktor engines supplying bytes
    and the OS supplying TLS. C-52 to C-54 reopen here per amended D-4. This bridge is the
    strategic door: KiteTorrent, encrypted stores, caches, auth flows and M5's adaptive future
    all pass through it. Estimate one to two focused sessions plus a KiteCodec window (golden
    regeneration per SOL-B1/B2 included).
  - **M2, the Kotlin ASS dialogue tier.** The S4.f remainder, evolved: an ASS parser in
    commonMain mapping styles and the dialogue-grade override subset (fonts, colours, outline,
    positioning, alignment, margins, bold, italic, basic fades) onto the EXISTING cue model,
    which SOL-S7 already records as richer than what the rasterizers draw. Karaoke, vector
    drawings and animated typesetting are phase L's, stated honestly in the track list until
    then. Estimate one to two sessions.
  - **M3, tone mapping.** PQ and HLG to SDR as shader source on the uniform infrastructure the
    eq work built (Metal first, the Android GPU tier second). Closes D16's software half with
    the same disabled-is-bit-exact discipline the adjust uniforms proved. Estimate one to two
    sessions.
  - **M4, the mobile robustness and dominance rows**, homes moved here from S3 by owner order:
    the audio-sink lifecycle rows SOL-A1 to A6 (AudioTrack writer machine, timestamp wrap,
    CoreAudio period and route), the hot-path rows SOL-P2, P4, P5, P6 (audio copies, thread
    per lane, cue history and raster worker, snapshot allocation), SOL-P1's software tier, the
    paused-frame overlay rows SOL-R1 to R3, and SOL-R14's Android half (the eq matrix in the
    Compose GPU tier's OES-to-RGBA blit, the natural hook the register names). The
    pure-Compose-beats-interop audit exits here: a feature table where KiteVideo loses nowhere.
  - **M5, the demuxer cache.** Forward RAM cache with a seek-back window in the engine's own
    Kotlin, `Progress.bufferedRanges` stops being the honest empty list. The one mpv advantage
    that is core engine work rather than a dependency. Sized at entry.
  - **M owner riders:** the physical device session AGW-1 alone. The iPhone rider is CLOSED:
    the device session ran on 2026-08-22 and 08-23 and did not need KiteStats, because the
    slideshow was not a decode or a renderer problem at all. PAST 14.122 has the whole finding.

**W. REAL ON DESKTOP AND WEB.** (ENTERED 2026-08-17. The expansion is 17.13. Progress: W.1, W.2,
W.5, W.8 and W.9 CLOSED; W.3 and the Kotlin/Native desktop targets landed; W.6 landed its split
half only. The desktop JVM plays the whole 17.5 matrix and Linux runs it in a container; Windows is
a link claim; the web spike passed and S6 is scheduled by its own verdict.) Exactly S3 then S6 as
written (S3 minus the rows M4 took),
in that order: Windows and Linux sinks and rendering, the desktop KiteVideo maturation, then
the timeboxed wasm spike with its stated physics. The parked libplacebo features (scaling
kernels, debanding) become eligible HERE as shader work if the desktop picture demands them,
still under D-7.

**L. LIBASS FULL THROTTLE.** (LARGELY LANDED EARLY, 2026-08-17/18, out of the road's order because
the chain build turned out to be the work and the integration was small, exactly as this phase
predicted. The six-library chain now cross-builds for ALL EIGHT native targets, and the module
renders on all six Kotlin/Native targets plus Android through a new JNI adapter, device-proved 2 of
2 on the Pixelu16KB emulator. REMAINING: the JVM desktop bridge, which reuses the Android adapter's
shape but needs host .dylib/.so/.dll packaging and resource loading instead of System.loadLibrary;
wasm, which needs libass under emscripten plus a binding; the per-frame hook for ANIMATED
typesetting, since rendering is still snapshot-per-call; and the exit criterion itself, a named
typesetting-heavy corpus rendered pixel-comparable to mpv, which nothing has run.) The
`kiteplayer-libass` OPTIONAL module: the six-library chain
built per target by BuildFFmpegTask-style machinery, rendering through the EXISTING bitmap-cue
path (integration is small; the build is the work). Exit: a named typesetting-heavy corpus
renders pixel-comparable to mpv, the Kotlin tier of M2 remains the default, and an app that
skips the module ships not one extra native byte. After W by design: M2 covers dialogue-grade
meanwhile, and the module's audience overlaps the desktop one.

**T. THE TAIL.** Everything else this document already holds, unchanged in content: S5 (public
artifacts, size tiers, the build and publication rows), S7 (soak, conformance, CI, 1.0), the
C-reduction charter SOL-C1 to C3, the Kotlin modernization posture SOL-K2, section 15/16's
remaining B-horizon obligations where stages reference them, and every register row not
adopted by an earlier phase. Nothing here is deleted by this renewal; it is sequenced behind
the outcomes the owner buys first.

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

  **DONE, 2026-08-18**, commit "Hear the picture in a browser, and say what it costs".
  `WebAudioSink` plus `WebAudioWorkletDevice` in `:kiteplayer-output`, wired as
  `WebOutputBackend.audioSink`, with the paced silent sink demoted to the no-`AudioContext`
  fallback so `nodejs` still gets a player whose clock runs.

  **It is a feeder, not a device callback, and that is structural.** An `AudioWorklet` is a real
  real-time thread, and this sink still cannot render on it: the worklet is its own realm, the
  engine's samples live in Kotlin/Wasm linear memory on the main thread, and making those one
  memory needs `SharedArrayBuffer`, which needs COOP and COEP on whoever embeds the player. X-02
  already refused to impose that on the default artifact. So this is the OTHER shape `AudioSink`'s
  own contract names: a push device wrapped by one writer coroutine turning "the device has room"
  into a pull. The worklet holds the queue and plays it gaplessly; the coroutine keeps it full.

  **The cost, stated rather than left implicit, which is what this row asked for.** The queue is
  4096 frames, about 85 ms at 48 kHz, and that is the buffering floor this design carries; the
  platform's own `outputLatency` sits on top of it and `latencyNanos()` reports both. Every block
  crosses the JS line one sample at a time, because Kotlin/Wasm has no typed-array bridge, the same
  limit `WebMemory.readBytes` and `BindingProof.fetchClip` already carry: 2048 calls per block, or
  about 96,000 a second for 48 kHz stereo. The deadline handed to the render callback counts what
  is already queued ahead of the block, so it is accurate to one worklet report interval, four
  render quanta or about 10 ms, and always errs toward claiming audio is further away than it is.

  **What is NOT claimed, again.** Nobody has heard it. Eleven tests drive the feeder's policy
  against a fake device with virtual time (prebuffer ceiling, refill on drain, deadline arithmetic,
  short-render silencing, seek discard, pause without discard, underrun reporting, latency honesty)
  and seven more cover the staging buffer. None of that proves the worklet source parses, that
  `addModule` resolves, or that a speaker moves. Those need a browser and belong with X-14. The
  underrun COUNT the row asked for exists and is reported as `AudioSinkEvent.Underrun`; a sustained
  run producing one is still unrun. And a browser starts every `AudioContext` suspended until a
  user gesture, so until the page has had a click the queue fills, the feeder backs off, and the
  audio-mastered clock sits at zero. That is correct behaviour, not a hang, and it is the one thing
  an embedder must know.

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

  **DONE, 2026-08-18**, commit "Draw the frame on the web, on a canvas rather than through Compose".

  **The plan's name for this sub-phase was "Draw the frame through Compose on the web", and the
  measurement above is what overruled it.** Compose on the web draws through Skia, which wants the
  pixels as a Kotlin `ImageBitmap`, which puts them in the Kotlin heap: the X-01 path, 160 to 240 ms
  per 1080p frame. The canvas path keeps them in the codec module and hands them to `putImageData`
  in one JS `set`: 8.5 to 9.7 ms. A renderer built the planned way would have compiled, satisfied
  the interface, and not played. `:kiteplayer-compose-video` still has no wasmJs target,
  deliberately, and that absence is now a decision rather than an omission.

  **What was built.** `WebFramePainter`, a seam that FILLS a JS array rather than returning a
  `ByteArray`, because `:kiteplayer-output` may not depend on KiteCodec and the Android seam's own
  shape is the slow one here. `WebRgbaConverter` in KiteCodec supplies it, converting with
  `ffkmp_frame_convert_pixfmt` and copying heap to array in one `set`, over a scratch buffer that
  grows to the largest frame seen and is freed with the renderer. `WebCanvasVideoRenderer` stages
  each frame into an offscreen canvas and draws that onto the visible one with `drawImage`, because
  `putImageData` alone ignores every transform and could never letterbox, zoom, pan or rotate.

  **The geometry law moved to `commonMain` rather than being copied.** `frameLayout` and
  `FrameLayout` were `internal` to androidMain; two renderers now share one law instead of a hundred
  lines of pixel-aspect and quarter-turn reasoning living in two places that must agree forever with
  no way to notice when they stop. Its eleven tests moved to `commonTest` with it and now run on
  wasm as well as Android.

  **NOT claimed: nobody has seen it.** Node has no `OffscreenCanvas`, no `document` and no
  `ImageData`, so every path the ten renderer tests exercise is a REFUSAL path. That is where
  ownership bugs hide and it is what they check: rule 2 says a renderer closes the frame exactly
  once on every path including failure, `present` has seven ways to refuse, and a leak there costs
  3.11 MB per frame at 1080p and 24.9 MB at 4K. Verified by mutation: removing the `use` turns
  seven of them red. A drawn pixel needs a browser and belongs to X-14.

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
- **X-10 LANDED 2026-08-18**, commit "Hear the picture in a browser, and say what it costs".
  `WebOutputBackend.audioSink` is `WebAudioSinkFactory`, an `AudioWorklet` fed by a queue this side
  keeps full. The silent sink is now the fallback for hosts with no `AudioContext`, which is
  `nodejs` and any embedder without Web Audio.
- **X-11 LANDED 2026-08-18**, commit "Draw the frame on the web, on a canvas rather than through
  Compose". `WebCanvasVideoRenderer` in `:kiteplayer-output`, wired to KiteCodec by
  `WebCanvasRendererFactory` in `:kiteplayer-mobile`, exactly as Android's renderer is wired.
  `WebOutputBackend.videoRenderer` is STILL null and that is correct: `DesktopOutputBackend` and
  `AndroidOutputBackend` are both null too, and both draw. A backend supplies what the platform
  alone can answer, and a renderer needs a surface, which only a consumer has. Calling that null
  field the gap was my own error and it is corrected here.
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

## 17.15 THE CONSOLIDATED OPEN REGISTER, BOTH REPOSITORIES

**Every open item in the project, one line each, with where its detail lives.** Born 2026-08-18 for
KitePlayer alone. **Widened 2026-08-19 to cover KiteCodec too**, when the two audit documents that
had been living in that repository (`SOLSUPREME.md`, a third party audit, and `SUPREME.md`, its
verification pass) were distilled into this file and deleted. This register is now the only
surviving index of their open findings, exactly as it already was for the two documents distilled
into 17.11 on 2026-08-16.

**If you only want to know what to do next, go to 17.20. It is the order, and it is short.**
This section is the inventory; 17.20 is the plan over it.

**This table is the index and the authority on WHAT is open. It is not the detail.** Follow the
pointer. "here" means this file; "PAST" means KPKMP-PAST.md.

Verified column: **[V]** re-verified against the tree on the date shown. **[C]** carried from an
audit, anchor never re-checked. **[owner]** needs a decision or hardware this machine does not have.

**Everything dated 08-19 was re-read against KiteCodec `dd2823c` and KitePlayer `e201186` by
locating each symbol by name, because every line number in both audit documents had rotted.**

### The KitePlayer rows

| Row | Open item, in one line | Ver | Detail |
|---|---|---|---|
| KP-PROD | THE PRODUCTION PROGRAM, owner-ordered 2026-08-22: the ordered handoff from here to a shippable player; every row below maps into one of its four phases | [V] 08-22 | 17.16, here |
| KP-RQ | THE RENDER-QUALITY LADDER, owner-ordered 2026-08-23: rungs 1 to 3 (dither, deband, kernel) are CLOSED on both renderers, PAST 14.125 to 14.128; linear light, Anime4K and HDR passthrough remain, and every rung still owes a phone measurement | [V] 08-23 | 17.21, here |
| SOL-S7 | REDUCED 08-25: the claims are narrowed and PINNED, so the type no longer promises what no rasterizer does. What is left is the features themselves, each a real body of work: per-span size and outline, a shadow pass, `CueWrap`, and `fontFamily` on Apple and Android | [V] 08-25 | 17.11, here |
| SOL-A6 | passthrough, offload, device selection, route recovery absent | [V] 08-19 | 17.11, here |
| SOL-P3 | frame access copies twice and boxes its plane list | [V] 08-19 | 17.11, here |
| SOL-P8 | REWRITTEN: the mixer folds ONLY to stereo; 8 into 6 is unmapped | [V] 08-19 | 17.19, here |
| SOL-P9 | a track change reopens the whole session, so live media cannot | [V] 08-19 | 17.11, here |
| SOL-API4 | **ROADMAP, not a defect** (reclassified 08-25). Five stats fields declared and honestly KDoc'd as unbuilt: `droppedFramesDecode`, `audioLatency`, `containerBitrate`, `SyncMode.ExternalMaster`, `LateAndDecode`. Nothing lies; they are simply not written yet. This row is their ONLY record anywhere, which is why it is kept rather than closed | [V] 08-25 | 17.11, here |
| SOL-API7 | REDUCED: refusal is typed; the engine never calls `supports()` | [V] 08-19 | 17.11, here |
| SOL-C1 | 198 exported C symbols, measured two ways 08-24; the C-reduction itself is untouched | [V] 08-24 | 17.11, here |
| SOL-C2 | non-real-time CoreAudio setup still lives in C, and GREW | [V] 08-19 | 17.11, here |
| SOL-C3 | NOT a truncation risk, re-read 08-25: `snprintf` bounds every write and `test_buffers.c` already measures the widest input at 162 bytes of 512. What is open is the C-reduction slice, moving composition to Kotlin, and it belongs to SOL-C1 | [V] 08-25 | 17.11, here |
| SOL-B5 | DECIDED 08-25: ALL ABIs stay supported; the drop proposal was REJECTED BY YUROYAMI. No ABI is ever formally refused. Remainder is engineering: add armeabi-v7a to the JNI recipes and the libass adapter, behind three gates (ARMv7 64-bit-atomics audit of the RT ring with a compile-time assert, a CI compile lane, one TV-stick smoke before support is claimed). x86-32 on demand if Synkplay ships it | [V] 08-25 | 17.11, here |
| SOL-B7 | **BLOCKED-UPSTREAM** (labelled 08-25). ONE deprecation left in each repo, owned by AGP 9.2.1's KMP library plugin and named by Gradle's own problems report, not by either project's scripts. NOTHING here is workable. Recheck trigger: the next AGP bump, and only then | [V] 08-25 | 17.11, here |
| SOL-B8 | REDUCED: the JVM half landed; no AAR ever reaches Maven Central | [V] 08-19 | 17.11, here |
| AGW-1 | the Android GPU path has no physical qualification at all | [owner] | 17.11, here |
| test debt | RECONCILED 08-24: nineteen walked against all 1,364 test names in both repositories. NINE are already written and struck; TEN are genuinely owed | [V] 08-19 | 17.11, here |
| F-ABI1 | no Android ABI dump exists in any of the twelve `api/` dirs | [V] 08-19 [owner] | 17.11.b, here |
| F-COV1 | six of twenty surfaces; tvos blocked by a missing RUNTIME, not an SDK | [V] 08-19 | 17.11.b, here |
| F-ALPHA1/ROT1/POS1 | the device-only halves: real pixels on a real screen | [owner] | 17.11.b, here |
| X-08 | nothing runs the player in a Worker, and X-06 waits on it | [V] 08-19 | 17.14, here |
| X-13 | no artifact layout and no deployment story | [V] 08-19 | 17.14, here |
| X-14 | the format matrix runs under node, never in a browser | [V] 08-19 | 17.14, here |
| PAR-WIN-HW | Windows carries 18 D3D11VA/DXVA2 hwaccels that are COMPILED and NOT PLUMBED, so no hardware route is offered. Opened 08-25 when `PAR-1` closed by correcting the prose instead of the binary. Needs a hardware device context and a frame download path in KiteCodec, and a Windows machine to prove it. Lands with Windows video output | [V] 08-25 | 17.11, here |
| PAR-2 | Linux compiles zero hwaccels | [V] 08-19 | 17.11, here |
| PAR-3 | android-x64 has 0 SIMD symbols against arm64's 1365 | [V] 08-19 | 17.11, here |
| PAR-5 | native linux and mingw declare targets with no source set | [V] 08-19 [owner] | 17.11, here |
| PAR-6 | REWRITTEN: hardware AV1 cannot engage at all; libdav1d wins the decoder lookup. ABSORBED the `4K` row 08-25 by owner decision: 4K stays a software non-goal and becomes an EXIT CRITERION here, because 4K was only ever a hardware question | [V] 08-25 | 17.11, here |
| PAR-7 | `fd:` still mutates the caller's descriptor | [V] 08-19 | 17.11, here |
| L | libass: JVM bridge, wasm, the animated hook, the mpv corpus | [V] 08-19 | 17.12, here |
| KP-NET | the network module: unvalidated 206, no resilience, unpublished | [V] 08-19 | 17.16, here |
| KP-API | throwing stubs, unusable default factory, five dead knobs, global logger | [V] 08-19 | 17.16, here |
| KP-B1..B13 | REDUCED 08-24: `.github/workflows/ci.yml` exists, seven jobs on four operating systems. The RELEASE half is untouched: debug signing, no wrapper checksum, no lockfiles, NDK by string sort, no signing or Sonatype configuration, gitignored unpinned fixtures | [V] 08-24 | 17.16, here |
| KP-WASM-RUNBLOCKING | `:kiteplayer-ffmpeg` and `:kiteplayer-mobile` commonTest DOES NOT COMPILE for wasmJs: `runBlocking` does not exist there, 31 call sites across two files. That target has never been built | [V] 08-24 | 17.16, here |
| KP-WEBPACK-CONTEXT | `:kiteplayer-network:wasmJsBrowserTest` aborts inside webpack with `RangeError: Invalid array length` while it timestamps a context directory. Deterministic across a cleaned build; the node half is fine | [V] 08-24 | 17.16, here |
| KP-TONEMAP-WARN | REDUCED 08-25 (PAST 14.153): the LIE IS GONE. Split shipped, `TonemappingUnavailable` deprecated and sited nowhere, `ColorApproximated` keeps the true half, `HdrToneMapped` maps from `RendererEvent.ToneMapEngaged` latched once per open, raw-frame caveat on `CapturedFrame`. Remainder: no built-in renderer PUBLISHES the event yet, so the notice is silent until each is wired, one truthful decision per renderer | [V] 08-25 | 17.22.A, here |
| KP-UNTESTED-MODULES | REDUCED 08-25 from three modules to ONE. `:kiteplayer-phone` now has a test source set and 3 tests, both falsified. `:kiteplayer-compose` is struck as debt rather than tested: it is one `internal object CompatibilityMarker` with NO public surface, so zero tests is the CORRECT state and counting it was a miscount. What is left is `:kiteplayer-compose-interop` alone, one public `@Composable` with five platform actuals, which needs the Compose UI test infrastructure this repository does not have | [V] 08-25 | 17.16, here |
| M riders | REDUCED: the physical device session; the iPhone run closed 2026-08-23 (PAST 14.122) | [owner] | 17.12, here |
| W riders | the Windows matrix run and the physical desktop measurements | [owner] | PAST 17.13 |
| B-horizon | REDUCED: items 4 and 9 are dead; the rest hold | [V] 08-19 | PAST 15.5, 16.4 |

### The KiteCodec rows

| Row | Open item, in one line | Ver | Detail |
|---|---|---|---|
| KC-CANCEL | a blocking FFmpeg call cannot be cancelled; no interrupt callback | [V] 08-19 | 17.16, here |
| KC-SPEC | output specs carry no colour, HDR, pixel aspect or exact layout | [V] 08-19 | 17.16, here |
| KC-REMUX | a "lossless" remux drops tags, disposition, rotation and side data | [V] 08-19 | 17.16, here |
| KC-AENC | the audio encoder validates and converts nothing | [V] 08-19 | 17.16, here |
| KC-COLOR-PROV | a guessed colour cannot be told from a declared one | [V] 08-19 | 17.16, here |
| KC-TRACKSEL | primary track selection has no policy; Wasm has no disposition | [V] 08-19 | 17.16, here |
| KC-WASM-MODEL | the Wasm probe answers with plausible emptiness | [V] 08-19 | 17.16, here |
| KC-FILTER-DIVERGE | JVM filters eagerly, Native lazily, and the key omits SAR | [V] 08-19 | 17.16, here |
| KC-FILTER-LOCK | user callbacks run under the graph lock, on BOTH backends now | [V] 08-19 | 17.16, here |
| KC-FILTER-SESSION | `process` is single use and the type does not say so | [V] 08-19 | 17.16, here |
| KC-FRAME-FLOW | a buffered frame Flow strands native frames; documented | [V] 08-19 | 17.16, here |
| KC-BRIDGE | 14 C and JNI bridge defects; no lease, no exception checks | [V] 08-19 | 17.16, here |
| KC-CFILTER | 7 C filter and frame defects; substring pins, unchecked strdup | [V] 08-19 | 17.16, here |
| KC-DSL | 11 DSL defects; untyped steps, no marker, raw map wins | [V] 08-19 | 17.16, here |
| KC-PERF | 10 hot paths; per-byte Web interop, the JVM copy chain | [V] 08-19 | 17.16, here |
| KC-BUILD | 23 build defects, including `/usr/lib/include` on Linux | [V] 08-19 | 17.16, here |
| KC-DOCTRUTH | REDUCED 08-24: the FFmpeg-profile half is CLOSED (encoder table, GPL tasks, NOTICE, CONTRIBUTING, FFmpegPaths KDoc, all measured with `nm` against the shipped archives). What is left is the register codes in shipped sources: 128 mentions of 35 distinct codes across 40 files, counted 08-24, not the 180 this row used to claim | [V] 08-24 | 17.16, here |
| KC-EVIDENCE-WASM | RESIZED 08-25, was S: the source set exists and is in CI, but the three `MediaSource.wasmJs` fixes need a fake that drives a real decode. MEASURED: 40 distinct `ffkmp_`/`kc_` entry points in that file alone, before `StreamDecoder` and `PacketReader`. That is a fake demuxer, so size M at best | [V] 08-25 | 17.16, here |
| KC-EVIDENCE-MUX | the muxer poison is right and unfalsifiable; no fault-injection seam | [V] 08-23 | 17.16, here |
| KC-ABI-SCOPE | the API ratchet is live again but covers 3 of 13 targets, so an iOS-only surface change passes | [V] 08-23 | 17.16, here |

| SEAM | 8 Gemini seam failures; version, targets, `api` leak, close order | [V] 08-19 | 17.16, here |
| KC-CAPS | REDUCED 08-25: the named refusal is DONE on all three backends, and the row's other claim was wrong. `FFmpeg.hasDecoder(name)` is public common API and always was, so a build CAN be asked about a decoder; what is missing is ENUMERATION and a measured build-time inventory | [V] 08-25 | 17.16, here |
| KC-CI-KONAN | REDUCED 08-24: CI is 11/11 green and no job links a system FFmpeg. Remainder is cosmetic: the two macOS jobs duplicate a from-source build on a cold cache | [V] 08-24 | 17.16, here |

**Counts, measured off these tables rather than estimated.** 42 KitePlayer rows. 26 KiteCodec rows.
**68 open rows in total.** P0-14 was CLOSED BY DELETION on 2026-08-21: the GPL build tasks whose
trees it described no longer exist.

**2026-08-24 (PAST 14.132), COUNTED off the tables rather than carried forward: 44 KitePlayer rows
and 26 KiteCodec rows, so 70 open in total.** `KC-BTBN-ROT` closed by deletion, the way `P0-14` did:
the Windows job no longer depends on BtbN at all, so the row has no subject left. `KC-CI-KONAN`
reduced to one cosmetic remainder, and nothing new opened.

**And the arithmetic above it was wrong, which is worth leaving visible.** The counts paragraph has
said "42 KitePlayer rows" since 2026-08-19; counted directly today the KitePlayer table holds 44.
Two rows were added to it at some point without the total being moved, exactly the drift the
corrections note at the end of this section describes for the previous edition. The numbers here
are now measured, and the way to keep them true is to count rather than to adjust.

**2026-08-24, third count of the day (PAST 14.135): 47 KitePlayer rows and 25 KiteCodec rows, so
72 open in total.** The count went UP by three and that is the right direction for this one.
KitePlayer's CI was written and merged, so `KP-B1..B13` reduced to its release half; writing it
found `KP-WASM-RUNBLOCKING` and `KP-WEBPACK-CONTEXT` before a single job ran, and running it found
`KP-CI-BILLING`, which is a private repository meeting an Actions spending limit rather than
anything about the code. **A CI that opens three rows on the day it lands is a CI doing its job**,
and none of the three is new breakage: all three were already true and nothing could see them.

**2026-08-24, end of day (PAST 14.135 to 14.142), counted again: 48 KitePlayer rows and 24 KiteCodec
rows, so 72 open in total, with 10 owner-gated and every one of those KitePlayer's.** The KitePlayer table GREW by three on the day it got CI, and that is
the CI working: `KP-WASM-RUNBLOCKING`, `KP-WEBPACK-CONTEXT`, `KP-UNTESTED-MODULES` and
`KP-FIXTURE-PIN` were all found by writing or running the workflow, and `KP-CI-BILLING` opened and
closed inside the same day. Against that, `KC-WASM-MIRROR` closed, `SOL-B7` reduced to a measurement
that belongs to AGP rather than to either project, `KC-DOCTRUTH` lost its whole FFmpeg-profile half,
`KP-FIXTURE-PIN` lost its recordable half, and `KP-API` lost the `AudioSink` self-contradiction.

**2026-08-24, midday (PAST 14.134), counted: 44 KitePlayer rows and 25 KiteCodec
rows, so 69 open in total.** `KC-PAGES` closed, and the SITE was fetched rather than the job read.
`P0-11..P0-19` closed as ALREADY SATISFIED: its one line said "ONLY Maven Central remains" and
Central went live the same day that line was written (PAST 14.121). Gate box 16 in 17.17 goes GREEN
with it, and 17.20's fourth tier loses its three stated blockers, all of which were resolved days
ago.

**The 14.132 paragraph two up was already wrong when it was written, which makes twice in two
days.** It says 26 KiteCodec rows, and the same commit that wrote it added `KC-PAGES` to that table
without moving the total. The rule it states in its own last sentence, count rather than adjust, is
the only thing that catches this, so both tables are now counted line by line every time this
number moves.

**The count did not move on 2026-08-23 and that is the honest number** (PAST 14.130). Three rows
left (`KC-CI-C`, `KC-NOTDONE`, `KC-P0-05-LEAK`, eight separate defects between them, all fixed) and
three arrived (`KC-EVIDENCE-WASM`, `KC-EVIDENCE-MUX`, `KC-APICHECK-RED`). Two of the three arrivals
are the same defects with their code half done and their evidence half missing, which under this
register's own law is not done. The third was found by running the gate rather than trusting it.
**A surge that fixes eight things and closes zero rows is what it looks like when evidence, not
code, is the bottleneck.** That is the argument for 17.20 items 1 to 3 in one sentence.

**The safety table that stood here is gone**: all six rows were fixed on 2026-08-19 and left this
file under RULE TWO (PAST 14.115).

**2026-08-25 (PAST 14.143), counted line by line: 48 KitePlayer rows and 24 KiteCodec rows, so 72
open, unchanged.** The wasm test source set landed and closed NOTHING, which is the correct outcome
and worth stating plainly: `KC-EVIDENCE-WASM` reduced from "there is nowhere to test this" to "this
is untested", and a row that still names three untested fixes has not closed. The count also carried
a stale number into this paragraph's predecessor, which said 47 KitePlayer rows against a table
holding 48. That is the third time in three days the same drift has been caught the same way, by
counting rather than adjusting.

**2026-08-25 (PAST 14.144), counted line by line: 46 KitePlayer rows and 25 KiteCodec rows, so 71
open.** Two closed and one opened. `SOL-B4` closed on measurement and is the only row in this pass
that was a real, mechanical fix. `SOL-S3` **closed by correction: it was false when written**, and
had been for the life of the row. It described reading `width`/`height` off `OverlayImage`, a type
that has only ever been `(x, y, bitmap)`, so there was nothing to fix and never had been. That is
the second false row this register has caught in two days and the first one that was false at
BIRTH rather than gone stale.

**One row was re-sized rather than closed, and the direction matters.** `KC-EVIDENCE-WASM` was rated
S while the belief was "the test source set is the only blocker". The source set landed on 08-24, so
the real blocker became visible and was measured: 40 distinct C entry points in
`MediaSource.wasmJs.kt` alone, before `StreamDecoder` and `PacketReader`. A fake that drives a real
decode is a fake demuxer. **An estimate made behind a blocker is a guess about what the blocker
hides**, and this register should treat S ratings on unreached code as provisional.

**2026-08-25, second pass (PAST 14.145), counted line by line: 46 KitePlayer rows and 24 KiteCodec
rows, so 70 open.** `KC-FLOOR-DRIFT` closed the day after it opened, which is the right lifetime for
a row that exists because a fix could not be seen by the check watching it.

**It found a trap on the way, and the trap is the part worth keeping.** `recipeFingerprint` has to
be IDEMPOTENT, because `CheckFFmpegRecipesTask` stores an already-fingerprinted set as its `@Input`
and hands it back to `staleReason`, which fingerprints it a second time. Every token survived that
for as long as every token was a real `--flag`. The first synthetic token added to the set did not,
so the EXPECTED side silently lost it while the installed side kept it, and every iOS tree was
reported stale for a floor that had never moved. **No unit test caught this; running the real task
did.** The invariant is now pinned by its own test, and the reason is written where the next person
will meet it.

**2026-08-25, third pass (PAST 14.147), counted line by line: 45 KitePlayer rows and 24 KiteCodec
rows, so 69 open.** `SOL-B6` closed. It is the third row this week whose DEFECT was real while the
fix written beside it was not the fix: the row proposed a composite build, and what was actually
wrong was one repository line sitting above another. **A row that names a remedy invites the remedy
to be mistaken for the defect**, and the three cheap closes of the last two days all came from
reading the tree instead of the proposal.

**2026-08-25, fourth pass (PAST 14.148), counted line by line: 44 KitePlayer rows and 24 KiteCodec
rows, so 68 open.** `SOL-S8` closed, and it is the FIRST carried row this week that was true exactly
as written. The three before it were not: `SOL-S3` was false at birth, `KC-CAPS` denied a query that
existed, and `SOL-B6` proposed a composite build for a one-line ordering bug. **What SOL-S8 got
wrong was only its size**: it described one behaviour and there were three copies of it, one per
rasterizer, each with its own private helper that had exactly one caller.

**A correction this pass had to make to its own triage, not to the register.** `SOL-C3` was pitched
to the owner as a live truncation bug worth an M. It is not a bug at all. Every write is
`snprintf` bounded, the widest reachable input measures 162 bytes against a 512 buffer, and
`test_buffers.c` has pinned that with six cases since B1-10. The row's real content is the
C-reduction slice it always said it was, and it says "Home: with SOL-C1" in its own last line. **The
row was read for its title instead of its last sentence**, which is the same failure mode as reading
a row for its proposed remedy rather than its defect.

**2026-08-25, fifth pass (PAST 14.149): 44 KitePlayer rows and 24 KiteCodec rows, 68 open, and the
count did NOT move.** `SOL-S7` reduced rather than closed, and the honest reading is that a row
offering two fixes had one cheap half and one expensive half, and only the cheap half was taken. The
type stops lying today; the features it stopped promising are still not built.

**This surge's contribution to the register's own method.** SOL-S7 is the fourth carried row this
week whose text was vaguer than the tree, and the pattern is now specific enough to name: **a row
written as prose describes ONE backend and the project has three.** SOL-S3 named AppKit and UIKit
was identical. SOL-S8 named the behaviour once and there were three copies. SOL-S7 said "family is
partial" where the truth is that family works on the one platform nobody ships on and is ignored on
both phones. Measuring per backend, and writing the result as a table rather than a sentence, is
what the last three closes have in common.

**2026-08-25, sixth pass (PAST 14.150): 44 KitePlayer rows and 24 KiteCodec rows, 68 open, count
unchanged.** `KP-UNTESTED-MODULES` reduced from three modules to one. Two thirds of it went for
different reasons and only one of them was work: `:kiteplayer-phone` got a test source set and three
tests, and `:kiteplayer-compose` was STRUCK AS A MISCOUNT. It is a single `internal object` with no
public surface, so zero tests is the correct state for it, and the row had lumped it in with a
module that genuinely needs Compose UI test infrastructure.

**The row said this in its own text and still counted it wrong.** It reads ":kiteplayer-compose (1
file, 3 code lines, an internal marker with NO public surface)" and then classes it with "the
compose halves need Compose UI test infrastructure". The measurement was right there and the
conclusion drawn from it was not. That is the same shape as SOL-C3 being read for its title rather
than its last sentence, twice in one day.

**2026-08-25, seventh pass (PAST 14.151): 43 KitePlayer rows and 24 KiteCodec rows, 67 open.**
`KP-FIXTURE-PIN` closed. `scripts/testmedia.sh` now REFUSES to build the clips with an ffmpeg
outside the pinned series, compared at major.minor, with a named override and a `--check-only` mode
CI runs in a second before the warmup.

**This one deliberately accepts a cost the register should record.** CI installs ffmpeg from
Homebrew, so the day brew moves to 8.1 this gate turns CI red. That is the gate working, not
failing: the same move is what cost a day on 2026-08-24 when it arrived disguised as an unrelated
subtitle-cue failure. The fix when it fires is one line and a regeneration. **The existing argument
in that script against gating was about CHECKSUMS**, which differ on every legitimate regeneration;
a version fires only when the toolchain actually moves, which is exactly when somebody should look.
If the owner would rather have a warning than a refusal, the change is the `exit 2`.

**2026-08-25, eighth pass (PAST 14.152): 43 KitePlayer rows and 24 KiteCodec rows, 67 open,
unchanged, and the [owner]-gated count drops from 10 to 9.** The two Criticals were decided and
expanded into 17.22 by owner delegation: `KP-TONEMAP-WARN` stopped being a decision and became a
spec, and `KC-WEB-IO` gained the expansion 17.20 always said it needed before execution. Nothing
closed, which is correct for a planning pass; what changed is that both items now start with 17.2
satisfied instead of stopping at 18.3 rule 6.

**2026-08-25, ninth pass (PAST 14.153): 43 KitePlayer rows and 23 KiteCodec rows, 66 open.** Both
Criticals executed from 17.22. `KC-WEB-IO` CLOSED. `KP-TONEMAP-WARN` reduced: the false message is
gone, which is the whole of what made it Critical, and what is left is wiring each renderer to
publish the new event.

**The spec was wrong about one thing in each item, and both were found by reading the code rather
than trusting the spec.** For B, the typed refusal before allocation was ALREADY implemented, so it
was verified and pinned instead of written. For A, the spec said kiteplayer-ffmpeg owns both the
converter and `warn(...)`; it does not. `SoftwareConverter` is a public stateless object consumed by
the OUTPUT layer, so the source can never observe engagement. That is why emission moved to a
renderer event rather than a converter callback, and why renderer publication is a remainder rather
than something quietly skipped. **A spec written from the register rather than from the tree
inherits the register's blind spots**, which is the fourth time this week that reading beat trusting.

**2026-08-25, tenth pass (PAST 14.154): 43 KitePlayer rows and 23 KiteCodec rows, 66 open,
unchanged, and the [owner]-gated count drops from 9 to 8.** `SOL-B5` stopped being a decision and
became work: the owner ruled that EVERY ABI stays supported and the drop proposal was REJECTED BY
YUROYAMI. The row now carries the reasoning in full, because a decision whose grounds are not
written down gets re-argued by the next agent who only sees the phone numbers and not the TV ones.

**2026-08-25, eleventh pass (PAST 14.155): 42 KitePlayer rows and 23 KiteCodec rows, 65 open.**
`SOL-K2` closed by RECLASSIFICATION, owner-decided. It was a Kotlin style list, and a style has no
exit criterion, which the row admitted in its own one-line summary by calling itself UNFALSIFIABLE.
Nothing was lost: the guidance is now rule 9 of the 18.3 executor fence, where it governs HOW work
is done, and the register went back to tracking only WHAT is left.

**Worth naming as a rule, because this register has been carrying the confusion for nine days.**
A row that cannot be finished is not a small row, it is a category error, and it inflates every
count it sits in. The test is one question: what would make this DONE? If the answer is "nothing,
you just keep doing it", it is guidance and belongs in the fence.

**2026-08-25, twelfth pass (PAST 14.156): 41 KitePlayer rows and 23 KiteCodec rows, 64 open, and
the [owner]-gated count drops to 7.** The `4K` row closed by ABSORPTION into `PAR-6`, owner-decided.
It had been unanswered since 08-18 because it was aimed at the wrong thing: the non-goal was set on
a SOFTWARE measurement, and 4K on a phone was never going to be won in software. It is a hardware
question and now lives with the hardware row as an exit criterion.

**Second row in two passes to close because it could not be answered as posed**, after `SOL-K2`.
Different fault, same family: K2 asked for something with no finish line, 4K asked a question of the
wrong subsystem. **A row nobody can answer is not always small; sometimes it is misfiled**, and the
tell is that every sweep reaches it and moves on without doing anything.

**2026-08-25, thirteenth pass (PAST 14.157): 41 KitePlayer rows and 23 KiteCodec rows, 64 open,
unchanged, and the [owner]-gated count drops to 6.** `PAR-1` closed and reopened narrower as
`PAR-WIN-HW`, owner-decided. The count did not move and that is the honest result: a contradiction
was cured and a smaller, truer row took its place.

**The principle is worth keeping, because this register meets contradictions constantly.** When the
BINARY and the PROSE disagree, both sides can move, and the right one to move is whichever is
actually false. Here the behaviour was correct (Windows offers no hardware route, because compiled
is not plumbed) and only the sentence was wrong. Moving the binary instead would have cost a rebake
and a release to delete code the product will want back. **Cure a contradiction at its false end,
not its convenient one.**

**2026-08-25, fourteenth pass (PAST 14.158): 41 KitePlayer rows and 23 KiteCodec rows, 64 open,
unchanged.** `SOL-API4` reclassified from DEFECT to ROADMAP rather than closed, owner-decided after
the closure premise turned out to be false.

**This pass nearly deleted a record by believing a row's own pointer.** SOL-API4 said "Home: their
section 11 items", so it was offered for closure as a duplicate of the roadmap. Section 11 is
superseded, has lived in KPKMP-PAST.md since the split, and **names none of the five fields**. The
row was not a duplicate; it was the ONLY record that five public API fields are unbuilt. Closing it
would have been the exact loss the 17.15 preamble was written to prevent.

**So the register earns a third rule this week, and it is about itself.** Sections 17.11 and 17.15
carry pointers written before the PAST/FUTURE split, and a pointer into PAST is not tracking, it is
an archive reference. **Verify a pointer before acting on it, especially when the action is
deletion.** The pointer here was deleted rather than followed.

**And the classification matters, not just the content.** A row reading "five stats placeholders"
scans as cleanup and kept surfacing in effort sweeps as a phantom quick win; it is five unwritten
features. Nothing in it lies to a consumer: the fields are declared, the KDoc says not implemented,
which is a promise not yet kept rather than a promise broken. The board should separate BROKEN from
NOT BUILT YET, and this is the first row marked that way.

**2026-08-25, fifteenth pass (PAST 14.159): 40 KitePlayer rows and 23 KiteCodec rows, 63 open.**
`SOL-API2` closed BY SUBTRACTION, owner-decided: five accepted-and-ignored config knobs deleted from
the public surface, plus the `PlayerLogger` interface and `LogLevel` enum that existed only to be
passed to one of them.

**The row's own two halves disagreed and the index was the correct one.** It read "five, not four"
while its detail bullet listed four; the tree says five, because `assumedLatencyWhenUnreliable` is
declared, validated in an `init` block, and read by nothing. It had been dropped from the detail on
08-24. **When the index and the detail disagree the DETAIL usually wins, and this is the exception
that proves the rule is about evidence rather than position**: the detail was newer and still wrong,
so the tie was broken by counting rather than by precedence.

**2026-08-25, sixteenth pass (PAST 14.160): 40 KitePlayer rows and 23 KiteCodec rows, 63 open,
unchanged.** `SOL-B7` labelled BLOCKED-UPSTREAM, owner-decided. Second label in this walk after
`SOL-API4`'s ROADMAP, and the register now carries three kinds of open row rather than one: BROKEN,
NOT BUILT YET, and NOT OURS.

**That distinction is what the S tier actually needed.** This walk started because every "what is
small and doable" sweep kept returning the same handful of rows and nothing got done. Four of the
six items so far were not small work at all: one had no finish line, one was filed against the wrong
subsystem, one was five unwritten features, and this one belongs to a third party. **An effort
estimate on a row nobody can act on is a category error wearing a number.**

Of the 40 open KitePlayer rows, **36 carry [V] and none carry [C]**: every row that was carried and
unverified before this pass has now been read against the tree. The remaining 4 carry neither mark
because they need hardware this machine does not have, and **10 rows in total are [owner] gated**,
all of them KitePlayer rows now that every KiteCodec [owner] row has closed. The tenth is
`KP-TONEMAP-WARN`, opened at the end of the day and a product decision rather than hardware. **So nothing in this
register is unverified except what cannot be verified here.** A tenth [owner] row existed for a few
hours and was a new KIND for this list, neither hardware nor a product decision but a payment
setting: `KP-CI-BILLING`. The owner chose to make the repository public and it closed the same day.

SOL-P10 is gone too: the previous edition struck it through and said it would go "next time", and
RULE TWO says there is no next time.

**Corrections this pass made to the register's own bookkeeping.** The previous counts paragraph said
"21 verified, 10 carried, 11 owner". Counted directly, it was 27, 8 and 10. Two rows, SOL-B7 and
SOL-API2, were marked [V] in this index while their own detail in 17.11 still said [C], so the index
and the detail disagreed about what had been checked and both rows were skipped by the sweep that
believed the index. Both are now genuinely verified. **A register whose index disagrees with its own
detail is the exact failure this section was created to end**, so the rule earns a sentence: when
they disagree, the DETAIL wins, because it is the one written next to the evidence.

### 17.16 The detail behind the register's new rows

#### KP-PROD. The production handoff: from a laptop-proven engine to a shippable player

Owner question, 2026-08-22, verbatim in spirit: what separates KitePlayer from production worth,
and from libVLC and libmpv. This section is the executable answer. It schedules NOTHING by
itself; it ORDERS the existing open rows so an executor starts at phase 1 and never wonders what
outranks what. Two standing laws outrank everything in it: the 17.18 rule (parity of what EXISTS
beats new feature count) and the versioning rule (minor frozen at 0.1x-style patch bumps,
owner-approved each time). DRM stays out of scope per section 12, and the 4K software non-goal
stays owner-gated (row: 4K).

**The definition being executed.** Production-worth means: (1) a stranger can INSTALL it from a
public repository and their first build is green, (2) its green claims are DEVICE claims, not
one-Mac claims, and (3) the first afternoon of real use hits no register-documented hole. The
giants comparison reduces to the same three plus time: libVLC and libmpv are twenty years of
hostile input baked into code. That tail cannot be shortcut, but it can be bounded: the wild
corpus row below is the down payment. What the pair has that the giants structurally do not: one
Kotlin engine on 11 targets, a typed coroutine API with truthful transactional commands, and
one-dependency-line provisioning proven on Maven Central today (KiteCodec 0.1.1, PAST 14.121).

**Phase 1: INSTALLABLE. Repeat KiteCodec's Central journey for KitePlayer.** The path is now
known and half the tooling exists. Rows: KP-B1..B13 (no CI of any kind on this repository: build
it, mirroring KiteCodec's ci/release/publish trio, konan warmup included per KC-CI-KONAN),
SOL-B8 (the AAR publication), F-ABI1 (Android ABI dump before anything is frozen), SOL-B7
(Gradle 10 warnings become breaks; fix before CI pins green), SOL-B6 (the twin repos resolve as
one graph or with explicit Central pins, never mavenLocal shadows), SOL-B4 (one macOS floor,
decided once), SOL-B5 (armeabi-v7a IN, decided 08-25, three gates), 17.17 boxes 11, 17, 18, 19, 20.
Exit: `implementation("io.github.yuroyami:kiteplayer-mobile:0.0.x")` from a machine that has
never seen this checkout, resolving KiteCodec 0.1.x from Central, building green. Everything in
this phase is mechanical; nothing needs a device.

**Phase 2: DEVICE-TRUE. Convert one-Mac claims into device claims.** Mostly owner-gated hardware
sessions, which is why phase 1 goes first: [owner] AGW-1 (Android GPU on a physical phone), the
M rider that is left (the physical-device session; the iPhone run is done, PAST 14.122), the
W riders (the Windows matrix has NEVER run on Windows; mingw links are not runs),
F-ALPHA1/ROT1/POS1 (real pixels on real screens), 17.17 boxes 12 and 13. Also
here: PAR-3 (android-x64 zero SIMD), PAR-1 [owner] and PAR-2 (hwaccel truth on mingw and linux),
PAR-6 (hardware AV1 cannot engage; needs the by-name decoder policy KC-CAPS also wants). Exit:
every platform the README advertises has at least one physical-device green run recorded.

**Phase 3: DAILY-DRIVER. The holes a user hits in the first afternoon, all already documented:**
SOL-P8 remainder (the mixer folds ONLY to stereo and CoreAudio is clamped to 2ch: on a 5.1
system this player outputs stereo; the single most user-visible row in the register), SOL-P9 (a
track change reopens the session, so live media cannot switch tracks), 17.17 box 10 (audio
device loss only warns), box 7 (the subtitle lane is outside the EOF gate: a trailing cue can be
cut), SOL-A6 (passthrough, offload, device selection, route recovery), KP-NET (HLS, real DASH
ABR, cache, reconnect, resume: today it is a range request and a static prototype), the platform
experience row of 17.18 (media session, audio focus, lock screen, interruptions, real PiP: the
OS must know a player is running), SOL-S3/S7/S8 (subtitle rendering truths), KP-API (the five
dead knobs and throwing stubs stop lying). Exit: a written first-afternoon script (play local,
play HTTP, switch tracks, unplug headphones, background the app, finish a file with subtitles)
passes on Android and iOS hardware.

**Phase 4: TAIL. Where the giants live; bounded, never finished:** the wild-input corpus (start
with the mpv/VLC public sample suites against the 17.5 matrix; every failure becomes a row), the
17.18 map consumed domain by domain in owner-picked order, X-08/X-13/X-14 for the web organ,
L (libass everywhere plus the mpv corpus), the test-debt row's nineteen named regressions, and
the SOL-C/K code-health rows riding along. This phase has no exit; it has a cadence.

**What NOT to do, stated so the executor cannot drift:** do not add features to close the 17.18
map while any phase 1-3 row is open (the 17.18 rule); do not bump the minor version ever; do not
publish anything whose gate is not green (17.17 box 20 becomes real in phase 1); do not treat
simulator green as device green (the exact confusion phase 2 exists to end).

Written 2026-08-19 from the distillation. Every anchor here was located by symbol name against
KiteCodec `dd2823c` and KitePlayer `e201186`.

#### KC-EVIDENCE-WASM. Three Wasm fixes exist in code and nothing can prove them

Opened 2026-08-23 as the remainder of KC-NOTDONE and KC-P0-05-LEAK, both of which closed in code
that day (PAST 14.130). **REDUCED 2026-08-24 (PAST 14.143): the source set now exists and the three
fixes are still untested.** The blocker is gone and the work is not done; those two are different
claims and this row previously conflated them. No test could fail today if any of these were
reverted tomorrow:

- P1-05's live `corruptDataSkipped` accumulation in `MediaSource.wasmJs.decodeStreams`.
- The decoder leak in the same function, where `openPacketReader` used to sit outside the `try` that
  owns the decoders it had just built.
- The permanent `readerActive` leak in `MediaSource.wasmJs.extractFrame`, where the reader and the
  decoder were both opened outside the `try`.

**What is left is three tests, not infrastructure.** All three live in `MediaSource.wasmJs`, which
the harness cannot reach yet: `FakeCodecModule` implements the report and string entry points that
`FFmpeg.identity` reads, and a decode path needs the packet and decoder entry points too. That is an
extension of an existing fake, which is ordinary work, rather than the missing-source-set problem it
used to be.

#### KC-EVIDENCE-MUX. The muxer state machine has no way to fail on purpose

Opened 2026-08-23, from P1-10's fix (PAST 14.130). `addCopyStream` now poisons the sink on both
backends when a step after `avformat_new_stream` throws, which is correct and matches what
`newStreamFor` has always done. **Nothing tests it, and nothing can from the public API:** the only
steps left after the mutation are `avcodec_parameters_copy` and a time base write, and no caller can
make either fail. Forging a `StreamInfo` was the one available lever and closing P1-11 in the same
surge removed it on purpose. Testing this needs a fault-injection seam in the sink, which does not
exist and should not be improvised into production code without deciding its shape first. Size S for
the seam, and it is the only thing standing between P1-10 and being genuinely done.

#### KC-ABI-SCOPE. The API ratchet is live again, and now watches 3 targets of 13

KC-APICHECK-RED closed 2026-08-23 (PAST 14.131): the baseline had been re-dumped WITHOUT the flag CI
uses, so it listed thirteen targets while the gate could only ever present three, and the check
failed on the target header before comparing one declaration. Re-dumped under the flag, and **proven
live rather than merely green**: a throwaway public function was added, `apiCheck` failed and named
it, and it went green again when the function was removed.

**What that leaves open, stated because it was chosen and not discovered.** No declaration was lost
(all 1895 lines unchanged; the diff was three lines of target bookkeeping), but the dump no longer
records WHICH targets a declaration exists on. Two extension declarations that read
`Targets: [native]`, meaning all eleven native targets, now read `Targets: [macosArm64]`. **So a
public API added on iOS but not on macOS would pass this gate.** Widening it back needs an FFmpeg
tree per target in CI, the same work `PAR-5` and the Windows rows want. Until that exists, a live
three-target gate is worth more than the dead thirteen-target record it replaced. Related: `F-ABI1`,
the Android half of the same theme.

#### KC-CI-KONAN. REDUCED 2026-08-24. Only the duplicate macOS build is left

**Closed on 2026-08-24 (PAST 14.132): no CI job links a system FFmpeg any more.** Linux, Windows,
both consumer smoke jobs and the Docs workflow all fetch the prebuilt STATIC trees `publish.yml`
uses, from this repository's own companion release, each zip checksum-verified. That was not a
preference in the end, it was forced: a distro FFmpeg CANNOT be linked by Kotlin/Native on a modern
Ubuntu, because its `.so` files reference glibc 2.29 and 2.34 while Kotlin/Native links its own
2.19 sysroot. **KC-BTBN-ROT closed with it, by deletion:** BtbN publishes shared builds, which carry
no `libavformat.a` for the embed to take, so that dependency had to go regardless of its retention
policy. The konan warmup was also fixed properly, by running it BEFORE the tree exists; see 14.132
for why that ordering is the whole mechanism.

**Open, and it is cosmetic.** The two macOS jobs share a cache key but still both build the
vendored FFmpeg from source on a cold cache. Merging them is obvious, cheap, and was deliberately
not done in the same surge that changed what every job links. Size S.

#### KC-CAPS. A build cannot be asked which decoders it carries

Opened 2026-08-19 by the owner, from a real failure: an iOS device threw FFmpeg's bare `-78`
(ENOSYS) on an AV1 file, and NOTHING could say whether that build carried dav1d. It took an hour of
binary archaeology to learn the answer was "the installed app was stale". Two layers are missing:

- **Runtime. HALF DONE 2026-08-25 (PAST 14.146), and half of the claim was false.** The refusal
  half is closed: all seven decoder-lookup sites across native, JVM and wasm now name the codec and
  point at the two calls the founding incident needed, `FFmpeg.hasDecoder` and `FFmpeg.identity`.
  Three of them printed `codec id 226` while the stream's own name sat in scope. **The row also
  said no "decoders in this build" query exists. `FFmpeg.hasDecoder(name)` is public common API on
  every backend and predates this row**, so the question was always answerable per name. What is
  genuinely missing is ENUMERATION: FFmpeg has it cheap (`av_codec_iterate`) and nothing binds it.
  Adding it is public API and therefore a design act, not this edit.
- **Build time.** Plugin 0.0.11 closed the biggest half (the dav1d contract is now two-way, and
  `kitecodecInfo` prints the provisioning per target), but the info line reports the TOGGLES, not a
  measured inventory of the tree it links.

Size S for the runtime query plus the named refusal; the error-message half pays for itself the
first time any decoder is missing anywhere.

#### KitePlayer's CI exists and cannot run, 2026-08-24

**Written and merged** (PAST 14.135, `a8f3dc4`): `.github/workflows/ci.yml`, seven jobs. The nine C
suites in six variant runs on macOS; real-media JVM and macosArm64 suites plus all three ratchets on
a macOS host; the device-free iOS simulator suites; JVM and Android host tests on Linux; `linuxX64`
EXECUTED on a Linux kernel with no container; `mingwX64` on Windows, which the register had said for
weeks has never happened; and wasmJs in node AND a headless browser.

**And every job was refused before its first step** (`KP-CI-BILLING`, opened and CLOSED the same
day). KitePlayer was a PRIVATE repository, so its Actions minutes were billed and the account's
payment or spending limit blocked them: "The job was not started because recent account payments
have failed or your spending limit needs to be increased." All seven, the same message, zero steps
each. **The workflow itself was fine and that was the evidence: GitHub PARSED it and scheduled all
seven jobs under their real names**, which a malformed file cannot do. The owner made the repository
public, which is the option that costs nothing per minute, and the jobs ran. See PAST 14.135.

**Three defects were found while WRITING it, before any of it ran**, and **three more on the day it
first ran**. That is the argument for the whole exercise, and none of the six is new breakage: every
one was already true and nothing could see it.

Found while writing: `KP-WASM-RUNBLOCKING`, `KP-WEBPACK-CONTEXT`, and a renderer test that asserted
a node-only answer while its own module declares a browser target (fixed, `2d5ba40`).

Found by the first run, each by the job built to find it:

- **Linux.** `KiteRtBindingTest` is in `nativeTest`, so it runs on every native target, and it
  asserted `KPRT_SINK_BAD_ARGUMENT`. Only macOS and iOS give that; everywhere else
  `kite_rt_sink_unsupported.c` answers `KPRT_SINK_UNSUPPORTED_PLATFORM` before it reads an argument,
  which the NEXT TEST IN THE SAME FILE already documented. Fixed, `86974ea`.
- **Windows.** The B1-11 architecture guard refused a correct object: `file` on the runner says
  "x86-64 COFF object file" where this Mac says "Intel amd64 COFF object file". The guard's own KDoc
  admitted the flaw, "every string here was measured on this host". Fixed, `a2be55b`, with a watched
  red test first. **This is the same defect KiteCodec fixed today in its own C task**, which is what
  makes it worth writing down: a description measured on one host is a claim about that host.
- **macOS.** The subtitle-cue matrix row was passing on MUXER INTERLEAVING. `decodeUntil` stopped at
  ten video frames and ten audio buffers, a third of a second at 30 fps, and the first cue is at
  half a second, so the row only saw a cue when the muxer put the packet early. ffmpeg 8.0 does;
  8.1.2 does not. Fixed, `8f0be06`: a row that wants a cue reads until it has one, proved able to
  fail by arming the flag on a clip with no subtitle track. `KP-FIXTURE-PIN` is the generator half.

And one correction to the workflow itself, from reading its own log: four tasks it named came back
NO-SOURCE, so `KP-UNTESTED-MODULES` replaced them.

#### Correctness, KiteCodec, still open

One release blocker is left and it is the Web reader; everything else here is a P1.

- **KC-WEB-IO (was P0-06). Wrong in five separate ways.** `WebIoBridge.kt:75-99` stages the whole
  source under a 512 MiB cap; `drain` calls `io.seek(0)` without asking `io.seekable`; `writeBytes`
  crosses into JavaScript once per byte; `open` does not suspend, so the staging blocks; and nothing
  ever closes the `MediaByteSource`. **Two of those five break a written contract rather than a
  quality bar.** `MediaByteSource` KDoc promises close runs exactly once and that seek is never
  called on a nonseekable source. JVM honours both, Native honours seekable, Wasm honours neither
  and says nothing. Size L. **The "untestable until a `wasmJsTest` source set exists" caveat this
  row carried is retired (PAST 14.143): the source set exists and the fake-module seam reaches this
  file.** Nothing about the five defects changed.
- **KC-CANCEL (was P1-07).** A repo-wide grep for `interrupt_callback` returns zero hits. The only
  cancellation is one `ensureActive()` per demux iteration, so a network open, read or seek blocks
  for ever, and `Transcoder.transcode` never leaves the calling dispatcher. Size L, C ABI change on
  every backend. **This is the same hole 17.12's amended D-4 reopened as C-52 to C-54, so the two
  rows are one piece of work and should be scheduled once.**
- **KC-SPEC (was P1-26).** `MediaSink.kt:77-101` carries codec, size, pixel format, frame rate,
  bitrate, keyframe interval and an untyped options map. Correction to the audit's wording: "cannot
  express" is too strong, because that map reaches `av_opt_set`, so a determined caller can set some
  of it by hand. The true claim is that there is no typed way and **nothing propagates from the
  source**, so an encode silently flattens HDR and turns 5.1(side) into 5.1(back). Size L.
- **KC-REMUX (was P1-27).** Both backends copy codec parameters and one time base and stop. Tags,
  language, title, disposition, rotation, display matrix, side data and stream groups are all
  dropped. Chapters are readable in the C and no writer consumes them; there is no program or
  attachment path at all. Size M, **and it makes the README's "bit-exact" remux wording false
  today**, which links it to KC-DOCTRUTH.
- **KC-AENC (was P1-28).** Both backends read `if (audio) 0L else conversionFor(...)`, so video gets
  a dimension check and a pixel format conversion while audio gets neither validation nor
  conversion. A rate, format, layout or frame size mismatch fails late and cryptically. Size M.
- **KC-COLOR-PROV (was P1-24).** All four sites overwrite an Unspecified field with a guess, so a
  declared BT.709 and a guessed one are indistinguishable. The new `ColorInfo.rangeSpecified` flag
  separates provenance for RANGE only. Wasm preserves Unspecified, so the backends disagree. Size M.
- **KC-TRACKSEL (was P1-08).** JVM and Native skip attached pictures then take the first video;
  `primaryAudio` is a plain `firstOrNull`; Wasm cannot exclude cover art because it never reads
  disposition. No `TrackSelector` exists. **Bigger than a P1 suggests: doing it properly adds public
  API.**
- **KC-WASM-MODEL (was P1-34).** Container metadata and chapters are hardcoded empty; the stream
  read omits metadata, disposition, start time, extradata, colour, VP9 and the layout mask, all
  silently defaulted; everything that is not audio, video or subtitle collapses to `Data`, erasing
  Attachment and Unknown. Rotation and SAR are populated now, so the audit's "erases major parts"
  overshoots slightly. Size M.
- **KC-FILTER-DIVERGE (was P1-17 and P1-18).** JVM builds both graphs once and eagerly from codec
  parameters; Native builds them lazily from the first decoded frame and rebuilds on a key change,
  so a mid stream format change behaves differently on the two. Native's key is a `List<Any>?`
  allocated per frame and it omits SAR, which the builder one line away actually uses, and the audio
  layout. Size L; the honest fix is moving orchestration to common Kotlin.
- **KC-FILTER-LOCK (was P1-20). Now true on both backends, and the second one is new.** JVM's
  `feedInput` is `synchronized(lock)` and invokes `onOutput` inside it. The operation ledger work
  gave Native the same shape: `feedInput` wraps `drainTo` in `operation { }`, which is
  `synchronized(opLock)`. **Two corrections on how to read this.** The reentrant case the audit
  describes is HANDLED on Native by design, because the lock is reentrant and a callback that closes
  the graph defers the free to the outermost exit; that is the ledger working. The residual Native
  hazard is narrower: a callback that blocks on another thread needing `opLock` deadlocks. And the
  comment at `FilterGraph.native.kt:111-113` argues that user code must not run under a lock while
  the enclosing `operation { }` does exactly that. It is talking about the frame lease, so it is not
  false, but it reads as a guarantee the function does not give. Size M.
- **KC-FILTER-SESSION (was P1-19 residual, P1-21, P1-31).** The close versus operation race is
  CLOSED on Native, with a ledger, a KDoc citing the audit, and a passing race test. What is left is
  API shape: neither backend refuses concurrent or repeated collection of `process`, which closes
  the graph in its `finally` and throws on a second collection at runtime rather than in the type;
  and `feedInput` returns `Unit`, so a multi input graph cannot answer `NeedsInput(otherPad)` and
  instead bounds its retry at two attempts and reports an untyped `InvalidArgument` carrying prose.
  Both are documented. Size L, same design as the convergence work.
- **KC-FRAME-FLOW (was P1-30).** `Frame.kt:13-18` states the defect verbatim in PUBLIC KDoc, so this
  is known and written rather than hidden, and it stays listed only because a documented leak is
  still a leak. There is no `Flow<Packet>`, so the blast radius is the frame path alone. Size M.

#### The KiteCodec long tails, each a body of work rather than one fix

| Row | Findings | What it actually is | Size |
|---|---|---|---|
| KC-BRIDGE | 14 | No lease on a resolved handle; O(n) close scan over a table that never shrinks, with caller controlled recursion depth; generation masked at 31 bits but stored and compared at 32, so a slot past `0x7FFFFFFF` stops resolving; the token's kind bits never validated; a pending JNI exception while a handle is minted, which leaks the context; modified UTF-8 on two paths while the correct decoder sits unused; callback exceptions cleared and collapsed to generic I/O; a thread attached and never detached; registration that erases every C signature | L |
| KC-CFILTER | 7 | `[out]` detected by substring search; sources published progressively then freed on failure, leaving earlier entries dangling; four unchecked `av_strdup`; a plane index never bounded, with the test still asserting the wrong answer; an eight channel cap on upload only | M |
| KC-DSL | 11 | Untyped steps, no `@DslMarker` anywhere, six of seven types with no validation, raw strings where a typed `SampleFormat` already exists, a raw options map applied after the typed keys so it wins, CBR emitted as VBV with no `nal-hrd`, the x264 preset ladder emitted for every codec including VideoToolbox, `CodecId` conflating bitstream identity with implementation | L |
| KC-PERF | 10 | Per byte Web interop, whole input staging, a JVM upload chain of at least three copies, an output path that allocates native memory then copies to Java, no common zero copy lease, handle table scaling, per frame graph keys, a thread local scaler with no session | XL |
| KC-BUILD | 23 | A 1,286 line build script; target truth duplicated across five hand synced representations; **`/usr/lib/include` on Linux, which is simply the wrong path**; a cache key that records a URL it never compares; redirects followed automatically inside the loop that validates them manually; filename only validation of local trees; two plugin tests excluded and no CI running any; unescaped `-D` values; unconditional `dllexport` for a static build; cache keys hashing one file | L |
| KC-DOCTRUTH | 11 | README 0.0.1 against VERSION 0.0.9; minSdk 26 against 24 in seven places; wasmJs called a placeholder with "no media runtime" against 2,303 real lines; **the core build script contradicting itself at `:206` and `:553` about whether the JVM is a placeholder**; portable LGPL claimed to carry libsvtav1 when it carries only zlib; "bit-exact" remux; 180 internal register codes left in shipped sources | M |
| SEAM | 8 | Target graphs that do not match, an `api` leak of a KiteCodec `Frame` pinned in both committed ABI dumps, non transactional source close, 467 hand written metadata mappings with one test, four modules repeating one config block with one of them missing a flag, and two version catalogs that have already drifted | L |

#### The KitePlayer rows this pass added

- **KP-NET.** Unvalidated 206 responses with no `Content-Range`, `ETag` or `If-Range` anywhere; a
  seek that validates nothing and a class with no closed flag; no timeout, retry, backoff or
  reconnect; DASH that picks one representation by bandwidth, drops audio, refuses live and
  multi period, and cannot seek; an MPD repeat count taken verbatim from XML; and the module is not
  published while all eleven other library modules are. **The six safety rows that used to sit
  beside this one, two of them in this very module, were all fixed on 2026-08-19 (PAST 14.115).**
  What is left here is resilience and publication, not safety.
- **KP-API.** `editions()` and `programs()` throw unconditionally and a test pins that they do; the
  default factory compiles and then throws; **five** accepted and unused config knobs, not the four
  the old row said; public models that are mutable through arrays, giving identity equality; raw
  FFmpeg option strings and filter chains at the public edge; a process wide logger beside a per
  player one nothing reads; `Pts` arithmetic that is naked `Long` and prints garbage at
  `Long.MIN_VALUE`; unchecked geometry in `CapturedFrame`; and no Java or Swift adaptation of any
  kind. Three KDoc blocks still promise behaviour that does not exist.
  **The "six KDoc blocks deny features that shipped" half is CLOSED, 2026-08-24, and there were
  five, all in the public warning surface.** Four in `PlaybackError.kt` opened with the words "Never
  emitted" while `PlaybackCore` emits all four: `FrameDropping` (stats tick, 5 drops per interval),
  `AudioDeviceChanged` (sink `DeviceLost`/`DeviceChanged`), `AudioUnderrun` (rising edge of the
  player-level total) and `NoRenderSurface` (`RendererEvent.SurfaceLost`). The code says the cause
  itself, three lines above two of them: "F-WRN1: the audit found these two documented warnings
  wired to nothing". **F-WRN1 wired them and updated none of the KDoc**, so a consumer reading the
  docs would not write a handler for warnings the engine actually sends. The fifth is
  `KP-TONEMAP-WARN`. Two neighbours were re-checked and are correctly documented:
  `DecoderUnavailable` and `AudioDeviceUnavailable` have zero emission sites. **One gap left:
  `FrameDropping` is the only one of the four with no test.** `AudioUnderrun` is pinned by
  `EngineAuditRegressionTest`, `NoRenderSurface` by `DiagnosticsTest`, `AudioDeviceChanged` by the
  new `AudioSinkEventTest`. Forcing five late drops inside one stats interval under a virtual clock
  is test-design work, not a fixture flag.
  **The `spi/AudioSink.kt` self-contradiction is CLOSED, 2026-08-24.** All four of its claims were
  wrong, not the two the row named: it said the engine collects nothing from the events feed (it
  collects), that an underrun is counted and may rebuffer (it is dropped), that a device change
  rebuilds the sink (it warns), and that a format request recreates the sink (it is dropped). The
  KDoc states the mapping per event now, and `AudioSinkEventTest` pins it, proved able to fail by
  routing the wrong event to the warning. That path had never been exercised at all: every fixture
  published `emptyFlow()`.
- **KP-B1..B13.** A release build that is debuggable and debug signed; no wrapper checksum, no
  dependency verification, no lockfiles; an NDK chosen by string sort; a publication readiness check
  that reads generated POM XML and nothing else; no `developers`, no signing, no Sonatype
  configuration anywhere; two optional modules unpublished; and test fixtures that are gitignored and
  regenerated by the host `ffmpeg` with no pin or checksum (`KP-FIXTURE-PIN`).
  **The CI half of this row closed on 2026-08-24**: `.github/workflows/ci.yml` runs seven jobs on
  four operating systems, a full run is 5 minutes, and the nine C suites finish in under 40 seconds.
  This paragraph said "no `.github` directory at all" for a day after that stopped being true, while
  the index row above already said otherwise. When the index and the detail disagree the DETAIL
  wins, which is exactly why the stale half is the one that had to be found.

### 17.17 The public release gate

**A public claim about the PAIR stays blocked until every box below is green.** Adopted from the
2026-08-18 audit's own gate, with each box's status re-checked against the tree on 2026-08-19.
This is not the phase gate of section 9; section 9 says whether a CHANGE is safe to keep, and this
says whether the pair may be offered to strangers.

The shape of the answer, in one sentence: **the correctness half is nearly done and the
distribution half has not started.**

| # | The box | Status |
|---|---|---|
| 1 | Invalid C pixel formats cannot abort; the test fails on any signal | GREEN |
| 2 | Native frame, decoder, filter and sink operations hold a lifetime lease for the whole FFI call | GREEN |
| 3 | JVM and Native sink close is a terminal atomic state machine | GREEN |
| 4 | Wasm send, drain, EOF, wrong stream, seek, extraction, options and ownership match the shared suite | STILL CODE ONLY, for a smaller reason. The source set exists since 2026-08-24 (PAST 14.143) and covers module adoption and the identity report; NO test reaches `MediaSource.wasmJs`, which is what this box is about |
| 5 | Web input is worker backed or explicitly small; no per byte interop, exactly once close | RED. KC-WEB-IO |
| 6 | Every custom I/O failure path preserves its cause and closes once | GREEN on JVM and Native. RED on Wasm, which never closes the source at all |
| 7 | Player EOF waits for decoded handoffs, every buffering stage, the video and subtitle lanes, and the sink | AMBER. Demux, both decoders, packet queues, the video frame queue, decoded audio in flight and the DSP tail and the sink drain are all in the gate, each bounded. The SUBTITLE lane is not |
| 8 | Reopen paths get a fresh `MediaIo`; no closed source is reused | GREEN |
| 9 | Open, stop, seek, track and subtitle selection, cancellation and close return truthful results | GREEN |
| 10 | Every native output and render capability is leased through close; device or renderer loss recovers or fails typed | AMBER. A failed renderer is now detached. Audio device loss is still only warned about |
| 11 | Every published JVM OS and architecture has a matching runtime artifact, or is not advertised | RED |
| 12 | The ordinary Android AAR builds on CI and is device tested for its declared ABIs | RED |
| 13 | Android Native attaches the JavaVM and device tests MediaCodec, or does not advertise it | RED |
| 14 | The Wasm `.mjs` and `.wasm` runtime is in a versioned package and browser tested | RED |
| 15 | GPL and LGPL names, configure flags, capabilities, link dependencies and licences agree | GREEN as of 08-22: GPL producers deleted 08-21, every profile portable and LGPL, packaging bundles nothing and verifies the dav1d flavour both ways |
| 16 | Prebuilt assets exist and a clean consumer installs and runs without this checkout | GREEN as of 08-24: all 22 assets live, and `kitecodec-core` resolves from Maven Central with no mavenLocal in the graph. Proved twice, on two machines' worth of evidence: a consumer project with only `mavenCentral()` ran 0.1.1 (PAST 14.121), and KitePlayer pins 0.1.3 from Central with 0.1.3 absent from mavenLocal (PAST 14.134) |
| 17 | Every published KitePlayer variant resolves one matching KiteCodec variant | RED |
| 18 | The player modules and the exact Web codec runtime release together; no Maven Local | RED |
| 19 | Licence, SBOM and provenance accompany every bundled native dependency | RED |
| 20 | Release candidate tests run BEFORE publication and publication is atomic | RED |

**How to read box 4 and box 7, because they are the two that could be misread as done.** Box 4's
code is written and reviewed and it compiles; what is missing is any test that could go red if it
regressed, and that is a different risk from an unwritten fix, not a smaller one. Box 7's gate is
genuinely thorough on the audio and video lanes and every wait in it is bounded, which is the part
that used to hang; the subtitle lane simply is not consulted, so a trailing cue can be cut.

### 17.18 What a mature player still needs, by domain

Distilled from the 2026-08-18 audit's parity table. **Read it as a map, not a backlog.** Nothing
here is scheduled; it exists so that a stage entry can ask "which of these does this phase buy?"
and get an answer instead of a feeling.

**The rule that governs this whole table, and it outranks the table.** Do not chase it as a
feature count. The first real differentiator is that every feature THAT ALREADY EXISTS has the
same transaction, ownership, timing, fallback, diagnostic and installation behaviour on every
platform where it is advertised. Parity built on a command that lies or on an artifact nobody can
install is not parity.

The comparison class is libmpv and libVLC. The audit did not read their source; "class" names the
product tier expected of them, while every statement about what THIS pair has or lacks comes only
from these two repositories.

| Domain | What the pair has today | What the mature class still requires |
|---|---|---|
| Session control | open, play, pause, stop, seek, observable state, queue commands, and since 2026-08-18 truthful transactional results | interruption policy, crash-safe recovery, exactly documented readiness |
| Clock and sync | common clock, sync law, drop and rebuffer machinery, device-anchored clock | device-route recovery, refresh-rate change, passthrough clocks, live-edge policy, telemetry-backed tuning |
| Queue | navigation, repeat and shuffle shaped controls | preloading, gapless, crossfade, failure policy, persistence and resume, nested playlists, library identity |
| Rate, loop, frame | speed, pitch preservation, AB loop, capture, and a frame step that now steps a real frame | reverse and trick play, slow motion policy, scan and jog |
| Track selection | runtime calls, default selection, per-kind transactions | in-place switching without reopening, ranked language and accessibility policy, decoder-support rationale, multi-angle, stable source-scoped handles |
| Subtitles | SubRip, WebVTT, an ASS dialogue tier, several rasterizers, libass on six native targets plus Android | persistent libass everywhere, bitmap PGS and DVD subtitles, attachments and fonts, karaoke and animation, style override, viewport and safe-area layout, external URL sources, accessibility captions |
| Video output | Android Surface, Canvas and GPU, Apple Metal and native views, software Compose, a Web canvas path | Linux and Windows GPU contexts, WebGL or WebGPU, HDR and display capability, display hotplug, exact control parity, direct-buffer leases, thumbnail paths, energy-aware tier selection |
| Audio output | CoreAudio, RemoteIO, AudioTrack, JVM desktop, WebAudio, a lock-free C ring, and since 2026-08-18 a real resampler and a measured downmix policy | WASAPI, ALSA, Pulse and PipeWire quality backends, device enumeration and hotplug, exclusive mode, passthrough, replaygain, limiter, equalizer, route-aware layout negotiation |
| Network and cache | an optional Ktor range source, a static DASH prototype | HLS, real DASH ABR, progressive cache, bounded prefetch, resume, validators, throughput estimation, proxy and auth, reconnect and backoff, offline cache, cancellation down to the socket |
| Live media | demux could consume a live source | DVR window, live-edge clock, low-latency HLS and DASH, catch-up rate, discontinuity policy, timeshift, latency metrics |
| Chapters and programs | chapters exist; programs and editions are throwing stubs | end-aware chapters, programs, editions, stream groups, attachments, multi-angle |
| Processing | a raw video filter string, capture | typed audio and video filter plans, runtime rebuild, equalizer, loudness, deinterlace policy, recording while playing, thumbnails, storyboards, waveforms |
| Observability | state, events, warnings, stats, bounded history, support bundle, and since 2026-08-18 a dropped-event count | sequenced state transitions, structured logs with redaction, per-stage latency and corruption metrics, trace export, reproducible bundles |
| Platform experience | native and Compose presentation modules, a PiP capability boolean | media session, lock screen, remote commands, audio focus and interruptions, real PiP, casting and AirPlay, background policy, accessibility semantics, desktop window and input |
| Security and protected media | headers and options are exposed | DRM and CDM callbacks (see section 12, out of scope until a product decision), secure-surface policy, credential redaction, certificate and proxy controls, sandboxed parsing, untrusted-media limits |
| Extensibility | backend and output SPIs | stable plugin points for protocols, decryptors, subtitle providers, render effects, telemetry and track policy, none of them exposing FFmpeg or JNI internals |

### 17.19 Found by this pass, in neither audit

**These are new. Nobody has seen them before 2026-08-19, they are not in SOLSUPREME and not in
SUPREME.** They exist because the re-verification read the code around each claim instead of only
the claim. The three most serious of them were safety defects, and those were fixed the same day.

**Six of this section's findings are gone, because they were fixed on 2026-08-19** (PAST 14.115).
What is left below is what is still true.

**An untyped map silently beats the typed field next to it, in two places.** The DSL row already
named it for decoder options. The same shape exists for encoders: `MediaSink.native.kt:157-166`
sets the bitrate from `spec.bitrateBps`, then applies `spec.options` immediately after, so
`options["b"]` wins over the typed spec field. Same at `:225` for audio.

**The mixer only ever folds to stereo, and the C already says so.** `ChannelMixer.kt:302` reads
`if (targetChannels != 2 || layout == null) return null`. A 7.1 source going to a six speaker
device therefore gets no matrix at all and falls through to a truncating pass through. This is not
a hypothetical: `kite_rt_coreaudio.c:347` CLAMPS CoreAudio to 2 channels rather than 6 for exactly
this reason, with the comment naming the row. **On a real 5.1 setup this player outputs stereo.**
That is a user visible product limitation and it appears nowhere in any register. It is the true
remainder of SOL-P8, and it is worth more than the two thirds of that row already closed.

**The generated Wasm binding and the file that actually compiles are two copies nothing compares.**
The generator writes `native-libs/deps/wasm32/binding/KiteCodecWasm.kt` (gitignored); the tree
compiles `kitecodec-core/src/wasmJsMain/.../wasm/KiteCodecWasm.kt` (committed). They are identical
today. Nothing keeps them so: `generateWasmBinding` is not a dependency of any compile or check
task, no test reads the committed copy, and `wasm-binding-probe.sh:21` only checks the GENERATED
file exists. Edit `signature-baseline.txt`, re-run the symbol audit, and the compiled binding keeps
the old names, compiles clean, and fails at runtime in a browser with
`m._<old_name> is not a function`. **The project already solved this exact problem once**:
`wasm-report-offsets.sh:9-10` has a `check` mode that refuses drift on a sibling generated file. It
was simply never applied here. Size S, and it is the best value in this whole section.

**Six other things worth having written down.**

- `AudioTrackSink.kt:113` builds its accepted format with no `channelLayoutMask`, so `targetLayout`
  is always null on Android and the equal count speaker matching added on 2026-08-18 **can never
  engage there**. The machinery is inert on the one platform with the most device variety.
- `CoreAudioSink.kt:431` nulls `handle`, `ring` and `negotiated` BEFORE calling `destroy()`, so
  after an unproven teardown `close()` is a permanent no op. There is no `TeardownPending` state.
- `DesktopAudioSink.kt:199` recovers with `dead?.close(); create(); fresh.open(); driver = fresh`
  and no `try/catch`, so a failed open leaks `fresh` and leaves `driver` pointing at the closed
  line. The `open()` path at `:143` DOES guard, which is what makes this an omission rather than a
  policy.
- `build.gradle.kts:26` hardcodes the group while `gradle.properties:23` declares `GROUP`, which is
  the exact double source bug the same file's comment at `:27-29` says it fixed for the version.
- `KiteRtBindingTest.kt:285` asserts the stats reader zeroes its struct while checking only 3 of 8
  fields, never `device_buffer_frames`. That blind spot is precisely why the unsupported sink's
  missing write stayed invisible.

### 17.20 THE ORDER. What to do next, and why that order

**This is the answer to "what now". The register above says what is open; this says what to do
first.** It replaces the priority orders of both distilled documents, which were written before the
2026-08-19 verification and did not know about the safety rows or the six unfinished DONE rows.
**The safety tier that used to stand above everything here is done (PAST 14.115), so this list
starts where the work actually starts.**

**The rule that outranks the order:** a fix is done when its EVIDENCE exists, not when the code
changes. Every item below lands with the gate its changed path selects, and any item whose truth no
test can express says so in writing rather than passing quietly. That rule is why the order looks
the way it does: the cheapest items are cheap precisely because they buy back the ability to tell
whether anything else is true.

#### First: buy back the ability to know things. All cheap, all high leverage

1. **DONE 2026-08-23 (PAST 14.130).** CI ran `test_identity` and nothing else; it now runs all
   eight suites in both variants. **Its replacement at the head of this list is `KC-APICHECK-RED`**,
   found while closing it: `apiCheck` fails on a clean checkout of main, so the public API ratchet
   is not guarding anything either. Same disease, one level up, and it should be swept with
   `KC-CI-KONAN` and the Docs workflow. `KP-CI-C` is the same job for the other repository and
   belongs to phase 1 of `KP-PROD`.
2. **DONE 2026-08-24.** `checkWasmBindingMirror` regenerates the binding in memory from
   `signature-baseline.txt` and compares it to the committed copy under `wasmJsMain`, failing with
   the first differing line and the two-command repair. It is in the macOS ratchets job and in
   `check`. The two copies were IDENTICAL when the check was written, 607 lines and 196 externals,
   so this bought no fix; it bought the guarantee that the next drift fails in a build rather than
   in a browser.
3. **DONE 2026-08-25 (PAST 14.143).** `src/wasmJsTest` exists, 15 tests over two suites, in CI.
   **It needed no build-script change, only the convention directory**, so twelve rows sat
   unprovable for the price of a `mkdir`. The seam is a FAKE emscripten module, available all along
   because every generated external takes the codec module as its first argument. Two corrections:
   46 `commonTest` tests already ran on wasmJs, and NO CI job ran any wasm or js test, so all 46
   were laptop results. **Its replacement here is the three `KC-EVIDENCE-WASM` fixes**, which now
   have somewhere to be tested and still are not.
4. **DONE 2026-08-23 (PAST 14.130), except for the evidence.** All six `KC-NOTDONE` rows and both
   `KC-P0-05-LEAK` halves are fixed. Four of the six carry a falsifying test that was watched to
   fail first. The remainder is `KC-EVIDENCE-WASM` and `KC-EVIDENCE-MUX`, and it is not code: three
   of the fixes are in `wasmJsMain` where no test source set exists, and P1-10's poison cannot be
   made to fail from the public API. **That remainder is why item 3 above moved from useful to
   blocking.** One correction the pass had to make: `KC-P0-05-LEAK` described a leak on JVM and
   Native that both backends had already lost in a rewrite, and a leak on Wasm that no edition of
   this register ever mentioned. It was live on Wasm only, on both of its halves.

#### Second: the correctness rows that are actually left

`KC-WEB-IO` first, because it is the last correctness release blocker. Then `KC-CANCEL`, and take it
together with 17.12's C-52 to C-54, which are the same hole seen from the network side; scheduling
them twice is how you build the interrupt callback twice. Then `SOL-P8`'s real remainder, the
stereo only mixer, because it is a user visible product limitation that the C already documents and
no register ever stated. Then audio device recovery (`SOL-A6`) and viewport subtitles
(`KP-P1-15`), the two rows the last surge honestly left open.

#### Third: the truth rows. Cheap, and they stop the next reader being misled

`KC-DOCTRUTH` and its KitePlayer twin inside `KP-API`. A build script that contradicts itself about
whether the JVM is a placeholder, a README that calls a 2,303 line backend a placeholder, minSdk
stated as 24 in seven places when it is 26, six KDocs that deny features which shipped, three that
promise recovery that does not exist, and one SPI file that contradicts itself sixteen lines apart.
None of this is hard. All of it is why the last two audits disagreed with the tree.

#### Fourth: the distribution program, and what is left of it

**The KiteCodec half is DONE** (`P0-11..P0-19` closed 2026-08-24). All three blockers this tier used
to name are gone: the Central credentials are on the repository, the signing keys have signed the three
versions Central serves (0.1.0, 0.1.1, 0.1.3; 0.1.2 was cut and superseded the same day and never
deployed), and `P0-14` closed by deletion on 08-21. A stranger can resolve `kitecodec-core` from
Central today.

What is left is the KitePlayer half, and it does not belong in this tier: it is `KP-PROD` phase 1,
which opens on the fact that **this repository has no CI of any kind**. The old rule still holds:
do not advertise any target that has not shipped.

#### Everything else

The long tails in 17.16, then 17.18's parity map, in whatever order the product wants. **Do not
chase 17.18 as a feature count.** The first real differentiator is that every feature that already
exists behaves the same on every platform where it is advertised.

#### What this order deliberately does NOT do

It does not put the biggest items first. `KC-PERF` is XL and sits near the bottom; the C and JNI
bridge is L and sits with the long tails. That is on purpose. **The project's measured failure mode
is not slowness, it is claims that were not true**: six rows open in one document and closed in
another, a fixture built so a disagreement could not surface, three "covered by existing tests"
lines that were false, and a count contradicting its own table two paragraphs later. The order above
spends its first two tiers buying back the ability to detect that, because every later estimate
depends on it.

### 17.22 THE TWO CRITICALS, DECIDED AND EXPANDED, 2026-08-25

**Provenance.** Both items below carried judgement calls. The owner delegated them on 2026-08-25
("take care of the judgement call, write the spec"), so the decisions here are DECIDED, not
options, and `KP-TONEMAP-WARN` loses its [owner] gate. Execute B first (17.20 names it the last
correctness release blocker), then A. One executor session each. Obey 18.3; every test is watched
RED before its fix and falsified after. Line numbers below are hints at KitePlayer `2af73f2` and
KiteCodec `11fe17d`; anchor by SYMBOL, and on any contradiction with the tree, STOP (18.3 rule 5).

#### 17.22.A KP-TONEMAP-WARN: the warning splits into the true half and the fixed half

**The finding that shapes the fix.** `TonemappingUnavailable` conflates two causes
(`KiteCodecSource.warnIfColorIsApproximated`, ~:622). The BT.2020 constant-luminance half is TRUE
everywhere: the converter runs the wrong-inverse matrix and nothing corrects it. The HDR half is
FALSE for every built-in display path: `HdrToneMap` (`Conversions.kt`,
`SoftwareConverter.native.kt`) rolls PQ/HLG off through BT.2390, and `kp_tone_map` does the same
in the Metal shader. It stays TRUE for one consumer only: a caller taking RAW frames from the
frame-access API and converting them itself. A per-stream runtime warning is the wrong tool for
that caveat; KDoc on the frame-access surface is the right one.

**Decided.**

1. Two new public warnings in `PlaybackError.kt`, one per cause:
   - `HdrToneMapped(transfer: String, streamIndex: Int)`, message:
     `"HDR ($transfer) tone mapped to standard dynamic range for this display on stream $streamIndex"`.
   - `ColorApproximated(detail: String)`, message: `"colour approximated: $detail"`. Carries the
     BT.2020 CL case with the existing detail text.
2. **Emission contract for `HdrToneMapped`: it fires where tone mapping ENGAGES, never from stream
   metadata.** Software path: the site where `HdrToneMap` first processes a frame for this open
   (kiteplayer-ffmpeg owns both the converter and `warn(...)`; reuse the
   `continuity.claimColorWarning` once-per-open latch pattern, ~:354). Metal path: the renderer
   publishes a new `RendererEvent.ToneMapEngaged(transfer)` (`spi/VideoRenderer.kt`) and the
   existing event-to-warning mapping (the SOL-API5 machinery that `AudioSinkEventTest` pins for
   audio) converts it, deduplicated once per open. Paths where the engine never touches pixels
   (Android MediaCodec interop tier; RQ-6 passthrough when it lands) emit NOTHING, which falls out
   of engagement-based emission by construction.
3. `ColorApproximated` keeps the metadata-based site: the CL approximation is a property of the
   conversion the engine will do, known at open, and it is true on every path that converts.
4. `TonemappingUnavailable` becomes `@Deprecated("the engine tone maps HDR; handle HdrToneMapped
   and ColorApproximated", ReplaceWith(...))`, is NEVER emitted, and its KDoc says exactly that
   with the date. Kept at 0.x for source compatibility (Synkplay pins 0.0.13); removal is a bump
   decision that stays with the owner. Both emission sites are deleted.
5. The raw-frame caveat moves to KDoc on the frame-access surface (`CapturedFrame` and the frame
   API): frames are decoded, not colour managed; the display pipeline tone maps, captured frames
   are not.
6. `WarningAuditTest` moves the old type to the deliberately-never-emitted set with the reason,
   and adds both new types with their sites. The two `ColorPolicyTest` cases (~:82, ~:119) are
   rewritten to the new types; they were pinning the lie.

**Tests, red first.** `HdrToneMapWarningTest` (commonTest, ScriptedBackend, virtual clock):
PQ stream through the software converter emits exactly one `HdrToneMapped` carrying "PQ", twice
through two opens emits twice; SDR stream emits nothing; an HDR-flagged stream whose frames never
reach a converting path emits NOTHING (this is the arm that dies if anyone regresses to
metadata-based emission, and the falsification is exactly that regression); a scripted renderer
publishing `ToneMapEngaged` maps to one warning, wrong-event routing falsified as
`AudioSinkEventTest` did. CL stream emits `ColorApproximated` and not `HdrToneMapped`.

**Gate.** Tier by path: `:kiteplayer-core:jvmTest`, `:kiteplayer-ffmpeg:jvmTest` and
`:kiteplayer-output:macosArm64Test`. No ABI dump exists (F-ABI1), so the API addition is reviewed,
not ratcheted; say so in the log. Size M. Exit: the four decided behaviours demonstrable, the old
type deprecated and silent, register row deleted into PAST per RULE TWO.

**Hostile review, written against this spec.** (1) The trap is emission on the interop tier: any
implementation that consults stream metadata re-creates the lie for users whose platform shows
real HDR; the no-convert test exists for exactly this. (2) The Metal event must carry the
transfer, not re-derive it in core, or a mid-stream transfer change misreports. (3) Deleting the
old emission sites must not delete the CL warning with them; the CL test pins it.

#### 17.22.B KC-WEB-IO: the staged web reader stops breaking its own contract

**Architecture decision.** Staging into codec memory STAYS. Worker-backed streaming is X-08's
program and is out of scope here; this makes the shipped architecture correct and honest. One
register defect is REDUCED rather than fixed, with the reason on the record: "open does not
suspend" is structural, because `MediaByteSource.read` is synchronous by contract ("block until a
byte exists", `MediaByteSource.kt:28`), and an async source is common-API surgery on a library
Central serves. That remainder joins X-08. The other four defects close.

**Decided.** All in `WebIoBridge.kt` and `WebMemory.kt` (hand-written; the generated binding is
not touched). Fake seam: `FakeCodecModule` already carries `_malloc` and `HEAPU8`; extend it with
only what the bridge calls, never a demuxer.

1. **Per-byte interop dies.** `writeBytes` (`WebMemory.kt`, the per-byte loop) becomes one JS
   crossing per CHUNK: pack the chunk as a latin1 string Kotlin-side (one char per byte, 0..255),
   one `@JsFun` writes `charCodeAt` into `HEAPU8` JS-side. Requirement: O(1) crossings per chunk;
   the JIT-speed loop lives in JS. If measurement favours a `Uint8Array` view over Kotlin's
   exported linear memory and it is reachable from `@JsFun`, that is an acceptable substitute; the
   requirement is the crossing count, not the mechanism.
2. **The seek contract holds.** `drain` calls `io.seek(0)` only when `io.seekable`; a non-seekable
   source is staged from its CURRENT position, and the wasm actual's KDoc says so. The common
   contract already promises seek is never called otherwise (`MediaByteSource.kt:22,33`); the JVM
   and Native backends honour it and the web one now does.
3. **Close runs exactly once, owned by the bridge.** The source is fully consumed once staging
   ends, so it is closed immediately after `drain` returns or throws, flag-guarded, in a finally
   beside the existing buffer-free.
4. **Oversize and unknown size refuse BEFORE allocation, typed.** `size == null` (live stream) or
   `size > 512 MiB` throws `FFmpegException(FFmpegError.Unsupported(...))` naming the size, the
   cap, and the design: the web backend stages whole sources; streaming input is not implemented
   on this target. The cap stays 512 MiB. Nothing is `wasmAlloc`ed on the refusal path.

**Tests, red first.** `WebIoBridgeTest` (wasmJsTest, FakeCodecModule + a fake `MediaByteSource`
with a seekable flag, a fail-on-seek arm, a close counter, scripted chunks and an optional
mid-read throw): close==1 on success; close==1 when a read throws mid-stage; a non-seekable
source is never seeked; a seekable one is rewound once; a 0..255 byte ramp crossing chunk
boundaries lands byte-identical in the fake heap (this is the arm that catches latin1/encoding
bugs, which are the real risk of the packing trick); an oversize source and a null-size source
refuse with `Unsupported`, allocate nothing (fake malloc counter), and still close once.
Falsifications: unconditional seek(0) back in, the close finally removed, the packing masked to
0x7F, the cap checked after allocation. Each must hit its own test.

**Gate.** `:kitecodec-core:wasmJsNodeTest`, `:kitecodec-core:jsNodeTest`, `apiCheck` (the bridge
is internal; the ratchet proves no surface moved). Size was L; with the harness standing and the
architecture decided it executes as M. Exit: the four contract tests green and falsified, gate
box 5 re-graded with the streaming remainder named, gate box 6 loses its "RED on Wasm" half, the
row REDUCED to the X-08 streaming remainder or closed if the register agrees the remainder
belongs wholly to X-08.

**Hostile review, written against this spec.** (1) The latin1 pack corrupts bytes over 0x7F if
anything UTF-8-encodes the string in transit; the ramp test exists for exactly this, do not
weaken it to ASCII. (2) Closing the source too early, before `installCallbacks`, breaks nothing
today because callbacks read the STAGED buffer, but verify that before relying on it. (3) The
refusal must not regress the error path that already frees the buffer on a drain throw.

## 18. The skeleton, for any executor

Written so a capable implementer with NO context, human or model, can work on this project
without damaging it. Read this, then sections 1, 2 and 9, then the register of the stage you are
executing, and obey 18.3 before your first edit.

### 18.1 The endoskeleton: what the thing is made of

Layers, inside out:

1. **FFmpeg** (C, vendored or system): demuxing and codecs. Never platform media APIs; hardware
   acceleration only as FFmpeg-internal decoders/hwaccels with software fallback (D-2).
2. **KiteCodec** (repo ../KiteCodec): Kotlin/Native binding over FFmpeg. Its C layer
   (native/kitecodec-c: nine helper units + identity gate, exported kc_/ffkmp_ ABI, symbol and
   metadata audits) is the STABLE BOUNDARY; the JNI bridge and the wasm binding both mount here.
   The identity gate refuses a mismatched FFmpeg runtime at process start.
3. **kiteplayer-core** (commonMain, pure Kotlin, 20 targets): the ENGINE. PlaybackCore's actor
   loop, workers (demux, decode x2, audio feed, video schedule) with the quiesce handshake,
   SyncLaw, MediaClock, seek machine, PacketQueues, AudioPlayback and VideoPlayback. It knows no
   platform. ALL platform entry is the SPI: spi/MediaBackend, MediaSource, Decoders, OutputBackend,
   AudioSink, VideoRenderer, VideoFrame. ScriptedBackend in commonTest is the reference
   implementation of every contract and the proof the SPI is sufficient.
4. **kiteplayer-rt** (C): the real-time audio core. Lock-free ring, pure-C device callback, no
   managed code on the device thread, proved by disassembly. Platform audio sinks in C live here
   (CoreAudio today; WASAPI, ALSA next). KotlinAudioRing in core is the same contract in Kotlin
   for targets C cannot serve (js, wasm, JVM) and is the C ring's differential oracle.
5. **kiteplayer-ffmpeg**: the KiteCodec-backed MediaBackend (the only real backend today).
6. **kiteplayer-output**: platform sinks/renderers glue (macOS today; iOS, desktop next).
7. **Apps/views**: sample player; later KitePlayerView and the optional Compose module with its
   two paths per D-6: the interop wrapper (baseline) and KiteVideo, the Compose-true renderer
   (17.9), which is just another VideoRenderer SPI consumer.

Data flow: source file -> KiteCodec demux -> packet queues -> KiteCodec decoders -> decoded
queues -> (audio) AudioPlayback -> ring -> device callback pulls; (video) VideoPlayback scheduler
-> VideoRenderer.present. The media clock is anchored by the AUDIO ring's published anchor
(seqlock, C side) and read lock-ordered on the Kotlin side; video schedules against it.

Ownership rules that break things when violated: frames and packets are AutoCloseable, closed
exactly once, on the worker that owns them; the ring is freed only after the feeder is joined;
flush requires both ring sides quiescent; every cross-thread reader of AudioPlayback state takes
its one lock.

### 18.2 The exoskeleton: the process that keeps it alive

1. **KPKMP is the only planning document, in two files.** If it is not in KPKMP, it is not the
   plan. KPKMP-FUTURE.md is what is true and what is ahead and is read every session;
   KPKMP-PAST.md is what already happened and is read almost never. Its
   Execution log (section 14) is the only progress record, append-only, every entry names the
   gate tier that ran and the rule that selected it.
2. **The evidence hierarchy (section 2) grades every claim.** Measure or say ASSUMED. Never
   present a lower level as higher. Twice this project found FALSE MEASURED claims inside its own
   plan; both times the process, not luck, caught them.
3. **Register discipline.** Work exists as register items: Where (file:line), Problem, Fix
   (decided, never options), Sub-phase, Test (named, and proved able to fail). Sub-phases name
   files, steps, gate, commit first lines. A stage without its expansion does not start (17.2).
4. **Gates are tiered and mechanical (section 9).** Tier 1 (fourteen seconds) every phase, no
   exceptions; Tier 2 by changed path; Tier 3 (device soak) only for the render path, callback,
   teardown ordering, tier promotion or release. The tier is selected by paths, never confidence.
5. **Reproduction first.** For any behavioural fix: write the failing test against the broken
   code, watch it fail at the predicted line, then fix, then watch it pass, then try to break the
   fix (falsifiability arm: revert the fix, the test must fail).
6. **Adversarial verification before risky execution.** Plans and irreversible changes get an
   independent hostile review told that NOT SAFE is a valued outcome.
7. **Ratchets move by procedure.** Every baseline (API dumps, coupling, metadata, symbols,
   deleted surface) has a move procedure in section 9's table. Raising one silently is forbidden;
   the log entry states old number, new number, why.
8. **Hard bans**: no em dashes in any file (scan in Tier 1); no new git branches; no
   Co-Authored-By or any trailer; the executor never pushes (D-3); no platform demuxer/decoder as
   source of truth (D-1).
9. **When you finish anything**: reread every changed file once against this document, run the
   selected tier, write the log entry, commit locally with a one-sentence imperative first line.
10. **When you do not know**: measure. When you cannot measure: write ASSUMED and the cheapest
    experiment that would settle it, and prefer running that experiment before building on the
    assumption.

### 18.3 The executor's fence: how not to over-build or under-build

Written for a code-strong, design-weak executor. The register says WHAT; this fence keeps the HOW
inside the lines. These are rules, not advice.

1. **Touch only the files the sub-phase names.** Anything else you believe needs changing: write
   it up as a proposed register item and leave the file alone. A fix that "also cleaned up" a
   neighbouring file is a defect at review, even when the cleanup is good.
2. **Build the smallest change that makes the named test pass and the gate green.** No
   abstraction, interface, helper, wrapper, configuration knob or generality the register did not
   ask for. If the Fix says "add a guard", add a guard, not a validation framework. Extract
   shared code only at the third repetition, and only when every touched file is already inside
   the sub-phase.
3. **No new dependencies without a register item.** Not a library, not a plugin, not a toolchain
   or Gradle version bump. Dependencies are owner decisions.
4. **No code for a later stage.** "While I am here, S2 will need..." is forbidden; S2's needs are
   decided at S2 entry, against the tree as it exists then.
5. **When the tree contradicts the register, STOP.** A path that does not exist, a symbol that is
   not there, an API that drifted, a test that already passes before your change: report the
   contradiction and wait. Never improvise the register back into truth. Both false claims this
   project has caught (section 14) were prose drifting from the tree; an executor who silently
   adapts recreates that defect class.
6. **When your stage has no expansion, STOP.** Expansion (17.2) is a planning act with its own
   adversarial ritual. If the register for your stage does not exist, request it; never author a
   plan and execute it in the same breath.
7. **Done is defined by exits, not by effort.** You are done when the named test passes, the
   selected tier is green, the exit criterion is demonstrable and the log entry is written. Under
   that bar nothing counts; above it nothing more is required.
8. **Report deviations louder than successes.** A skipped step, a flaky test, a widened
   tolerance, anything ASSUMED: its own sentence in the log entry. A deviation reported is
   process; a deviation hidden is corruption.
9. **The house Kotlin style, when a sub-phase gives you a choice.** Prefer sealed transactional
   outcomes over thrown control flow, structured finalizer scopes over hand-paired cleanup,
   ownership-aware lease APIs over raw handles, inline plane iteration over per-pixel calls,
   checked-size helpers over bare arithmetic, and resource ledgers over close-and-hope. Context
   parameters only for the worker helper cluster and a codec execution context.

   **This is guidance, not work.** It arrived here on 2026-08-25 from register row `SOL-K2`, which
   sat open from 2026-08-16 because it was UNFALSIFIABLE by its own admission: a style has no
   exit criterion, so no pass could ever close it. A register whose rows cannot be finished
   inflates every count it appears in. Style belongs in the fence that governs HOW work is done;
   the register tracks WHAT is left. Applying this list is never a task on its own, and a
   sub-phase that wants one of these moves specifically says so.


### 17.21 KP-RQ: the render-quality ladder, owner-ordered 2026-08-23

**The question this answers.** The owner asked what gives KitePlayer a visible edge instead of
integrating libplacebo. This section is the whole answer, written as a handoff: an agent with this
section, the code, and section 17.20's working laws needs nothing else. Every claim below about the
tree was verified against the source on 2026-08-23, the same day the audio channel and subtitle
canvas fixes were staged (register entry pending with the 0.0.14 commit).

#### The verdict on libplacebo, and the four reasons

REJECTED. (1) It is a large C dependency wanting Vulkan or GL or a Metal translation layer on every
target, and it ends the one-dependency-line story KC-EMBED was built to earn; that story is a
product feature. (2) It cannot follow the engine to wasm, and phase W says the web target is real.
(3) Its playback-correctness core is already SHIPPED here: M3's PQ and HLG decode, the BT.2100
OOTF, gamut conversion in linear light and an EETF roll-off all live in `MetalVideoSupport.kt` and
were measured, not assumed. (4) The part of libplacebo a viewer would actually notice on this
project's content is about 150 lines of shader, written below as RQ-1 to RQ-4.

#### What the tree has today, verified, with the gaps stated as defects

The Apple picture shader (`METAL_SHADER_SOURCE` in `kiteplayer-output` `MetalVideoSupport.kt`,
fragment `kp_picture`): correct YUV matrices keyed on the declared colour space, limited and full
range, 16-bit plane textures for 10-bit content, tone mapping and eq as separate bit-exact-off
uniform blocks. Since PAST 14.125 to 14.128 it also carries dithering, debanding, chroma siting and
a Catmull-Rom kernel, all opt-in and all bit-exact when off, and the Android GL blit carries the
same three. ONE gap from the original reading is left and it is RQ-4's: scaling still happens in
gamma space, because that is the rung that changes the shape of the pipeline rather than adding a
pass to it.

#### The surfaces, so nobody discovers coverage the hard way

| Surface | Programmable stage today | Gets the ladder? |
|---|---|---|
| Apple, ALL paths: Metal interop layer AND KiteVideo (the readback `MetalPictureReader` WRAPS `MetalFrameComposer`, verified) | one shared shader body | YES, written once |
| Android, KiteVideo GPU tier (API 31+) | the GL blit in `AndroidGpuImageVideoRenderer.kt` | YES, the second copy, and it is the ONLY place a kernel can run on Android: Compose maps every `FilterQuality` above `None` onto one `isFilterBitmap` flag, so the drawing step cannot resample better than bilinear no matter what it is asked for (proven in `AndroidFilterQualityDeviceTest`) |
| Android, INTEROP (the shipping default): MediaCodec decodes DIRECT to the SurfaceView Surface | NONE, no shader runs on the picture | NO. Honest row: closing this means routing interop through a GL compositor, which is SOL-R14 and AGW territory, not this ladder |
| CPU paths (Android pre-31 KiteVideo, desktop JVM, web canvas, Apple CG fallback) | none | NO, out of scope |

So Apple gets full coverage in one place; Android gets it only where a shader already runs. That
asymmetry is stated here so it is chosen, not discovered.

#### The ladder

Every rung obeys the same four laws. (1) DISABLED IS BIT-EXACT, the discipline the adjust uniforms
and tone mapping already prove: off means the write is identical to today's, byte for byte, and a
golden test pins it. (2) NOTHING DEFAULTS ON WITHOUT A DEVICE MEASUREMENT: the XS runs hard 800p
AV1 at 17 to 21 fps against a 24 fps target, so headroom is thin and every pass states its measured
fps delta from the sample app's `--scenario` harness before its default is decided. (3) One knob
surface: a `RenderQuality` config on `PlayerConfig` plus a live setter, modelled exactly on
`VideoAdjustments`. (4) A rung lands in the Metal body and the Android GL blit in the SAME surge,
or the surge opens a row saying which half it skipped and why.

**RQ-1 dithering, RQ-2 debanding and chroma siting, RQ-3 the Catmull-Rom kernel: ALL CLOSED
2026-08-23**, on the Metal body and the Android GL blit both, recorded in PAST 14.125 to 14.128.
Read those entries before starting RQ-4: three of the four findings in them are about passes that
compiled, cost every tap and did nothing, which is this ladder's characteristic failure and is not
caught by any test that only asks whether the code runs.

Law (2) is HALF met. The Android halves have their hardware numbers, taken on a Redmi Note 8
(Adreno 610, Android 10), per 1920x1080 draw over a 6.83 ms plain blit: dither +1.30 ms, deband
+7.72 ms, the kernel +22.01 ms. Against a 24 fps whole-frame budget of 41.7 ms that is 20, 35 and
69 percent, so dithering is affordable on the floor device, debanding is a choice, and the kernel
is not a default at this class. The METAL halves still have no device number, because neither
iPhone was reachable this session. Everything stays opt-in behind `RenderQuality.Off` until the
owner decides on those numbers; moving a default is not an implementer's call.

**RQ-4, linear-light scaling.** The structural rung, deliberately last of the four: today decode
and scale are ONE pass, with scaling done by the sampler in gamma space. Correct light-linear
scaling needs two passes: decode to a linear RGBA16F intermediate at source size, then the scaled
draw with the RQ-3 kernel. Touches `MetalFrameComposer`'s encode and the GL tier's FBO setup, so
it carries the regression risk the first three rungs avoid. Do it only after RQ-1 to RQ-3 are
measured in. Estimate: two sessions.

**RQ-5, Anime4K, the flagship.** A curated, built-in port of the Anime4K shader chains at two
quality tiers, not a user-shader format: mpv compatibility is explicitly out of scope. Its own
config surface, default OFF everywhere, device-gated guidance (A14 and M-class up), and its own
measurement story per tier per device. This is the one rung that is an EDGE rather than parity,
because the project's dominant content is exactly what those chains were built for. Its own
program, planned when RQ-1 to RQ-4 are done; rough shape two to four sessions.

**RQ-6, a horizon row, not scheduled.** True HDR passthrough on capable Apple displays: skip the
tone map, float16 target, `wantsExtendedDynamicRange` on the layer. The one libplacebo capability
worth envying that the ladder does not cover. Recorded so it is not forgotten; nothing below
depends on it.

#### Why this is the edge, stated once so the ladder is not mistaken for the strategy

The ladder buys parity with mpv's defaults on the things a viewer sees. The EDGE is what the
architecture already does that libplacebo's hosts structurally cannot: one Kotlin engine on
Android, iOS, desktop and web; video as a true Compose primitive; a virtual-time engine whose
device bugs reproduce in unit tests (this week: a 10 s device stall reproduced at 10190 ms virtual,
twice); and one dependency line. The ladder exists so nobody can dismiss the picture while those
advantages do the winning. Build RQ-1 and RQ-2 first, measure on the owner's XS, show the owner a
before and after on a gradient and an anime clip, and only then decide how far up the ladder to
climb.
