# KPKMP: the pilot document

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
> 1. **Close what you closed.** Find every row your work answers and mark it CLOSED with the commit
>    that closed it. Do not leave it for a later sweep. There is no later sweep.
> 2. **Open what you opened.** A limitation you introduced, accepted or discovered is a new row, in
>    the register nearest the work, written before you move on.
> 3. **Correct what you found false.** If a row describes the tree wrongly, say so on the row with
>    the evidence, and say when it stopped being true if you can tell.
> 4. **Reduce what you narrowed.** A row half answered is REDUCED with its remainder named, never
>    silently left whole and never quietly deleted.
> 5. **Log the work.** The execution log (now KPKMP-LOG.md) gets what was measured and what was
>    NOT, in the same commit.
> 6. **Keep the index true.** 17.15's consolidated register is where a reader learns what is open.
>    A row you close there must say which commit closed it; a row you open is added there too, not
>    only in its home file. The index is the promise; the detail file is the evidence.
>
> **The rule exists because it was broken.** On 2026-08-18 a verification pass found SIX rows the
> register still listed as open that the tree had already closed, four of them closed the previous
> day by surges that never looked at the register: SOL-S1 and SOL-S2 by the deep audit's own
> F-ALPHA1 and F-CFL1, SOL-API6 by W-18, SOL-B3 by a plugin change nobody re-ran. An owner reading
> this file would have paid for work already done, and an agent trusting it would have started it.
>
> **A commit that changes behaviour and does not touch this file is incomplete**, unless the work
> genuinely answers no row and opens none, which is rarer than it feels while coding.


> ## **HOW THIS DOCUMENT IS ARRANGED. READ THIS PART EVEN IF YOU READ NOTHING ELSE.**
>
> **This file was 18,546 lines and agents stopped reading it.** Not a guess: on 2026-08-18 an audit
> found SIX register rows listed as open that the code had already closed, and the agent auditing
> them checked twelve of sixty items and asserted the rest from this document's own summaries. A
> file too big to hold gets sampled, and a sampled file is quoted with the confidence of a read one.
>
> So on 2026-08-18 it was split BY LIFETIME, mechanically, every line moved verbatim. Nothing was
> rewritten and nothing was deleted. `scripts/verify-kpkmp-split.py` proves it and fails if any line
> or any register id stops resolving; it passed with 0 lines and 0 ids lost.
>
> | file | what is in it | read it |
> |---|---|---|
> | **KPKMP.md** (this) | the rules, the contract, what is true now, **the consolidated open register**, the current road, the skeleton | **every session** |
> | KPKMP-REGISTERS.md | every register row in full, open and closed, with its evidence | when working a row |
> | KPKMP-DECISIONS.md | the design digests, the verification protocol, the roadmaps | before re-litigating anything |
> | KPKMP-LOG.md | the execution log, 113 dated entries | when auditing history |
> | KPKMP-ARCHIVE.md | executed and superseded plans, kept for their arguments | archaeology |
>
> **Section numbers did not change.** A cross-reference to "17.11" or "section 14" still lands; it
> is simply in a sibling file now. The consolidated register below names the file for every row.
>
> **What this replaces.** The old rule said "everything the executor needs is in this file". It is
> now: everything the executor needs to KNOW WHAT IS TRUE is in this file; everything else is one
> hop away and named. That is a smaller promise, and unlike the old one it is kept.

KitePlayer Kotlin Multiplatform, the piloting plan. Written 2026-08-09, revised the same
day after a second independent full audit of both repositories was verified claim by claim
against the source and merged in. This is the only planning document. Everything the
executor needs is in this file and in the code.

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

**Proven working, measured on this machine (evidence levels 5 and 6).** Audio and video
playback in sync on macOS arm64: 1080p30 (300/300 frames, 0 dropped, 0 underruns),
720p59.94 (480 frames, 0 dropped), 4K HEVC 10-bit video-master (180 frames), 3 minute
audio soak with 0 ms clock drift. Colour conversion verified against `ffmpeg` CLI output
with mean component error under 2 of 255 for BT.709, BT.601 and yuv420p10le. A window
draws real frames via Core Graphics. 163 tests pass across 4 suites. KitePlayer baseline
is `e5ddccc`; KiteCodec baseline is `f442b82`.

**The architecture that is settled.** One engine in `commonMain` with no platform code and
an injected `MonotonicClock`. Pull-shaped audio sink whose real-time callback publishes
(pts, audibleAtNanos) anchors through a seqlock; the clock is anchored to what the device
reports, never estimated from queued sample counts. Generation counters invalidate stale
work after a seek by comparison at the next hop; they are defence in depth, and after this
revision they no longer substitute for quiescence (see D34). The sync law and its
constants live in code (`SyncLaw`, `FrameDurationEstimator`, `SeekTiming`, `BufferPolicy`
defaults) and are not retuned without evidence. The KiteCodec low-level layer
(`PacketReader`, `StreamDecoder`, `withPlanes`, `hardwareSurface`, behind
`@KiteCodecLowLevelApi`) mirrors libavcodec's send/receive shape and moves packet
references without copying.

**What does not exist.** No `KitePlayer` facade, no `PlaybackCore`; the sample wires the
pipeline by hand. Seeking is designed but connected to nothing. No resampler, downmixer or
gain stage. Rotation stops at the KiteCodec boundary. Subtitles are one orphaned parser.
Only macOS arm64 has backends. There is no Metal renderer, no hardware decode, no
network/live path, no published artifact of any kind, and KiteCodec's own README records
that its Maven artifacts and prebuilt FFmpeg release assets do not exist yet.

**The verdict this file acts on.** Two independent reviews agree: the engine core is
genuinely Kotlin-first, the KiteCodec layer is genuinely FFmpeg-first, and the faults
cluster in three places. First, the middle layer re-derives things FFmpeg already solved
and gets some wrong (timestamp rescale, DTS units, start time, channel layouts). Second,
ownership is inconsistent at exactly one seam (the shown frame) and the real-time audio
contract is violated in one place (per-callback allocation). Third, a large public surface
promises what nothing implements. The correct response is to repair the substrate in
Horizon A, then build the facade on valid contracts, and to let Horizon B carry everything
that makes it a product.

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
12. The linear resampler is interim, labelled as such in KDoc and README, and is replaced
    by swresample in B4. It is not the 1.0 default.
13. Speed with audio open throws until B4.
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
  - **M owner riders:** the iPhone KiteStats background-slideshow run and the physical device
    session AGW-1, both owner-blocked, both unchanged.

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

## 17.15 THE CONSOLIDATED OPEN REGISTER

**Every open item in the project, one line each, with where its detail lives.** Born 2026-08-18,
because the open rows were scattered across EIGHT places (4, 11, 15.5, 16.4, 17.11, 17.11.b, 17.13,
17.14) and no reader ever held all of them at once. That scattering is what let six closed rows sit
marked open for a day, and what let SOL-P10 ask for a `SwrContext` that has never existed in the C.

**This table is the index and the authority on WHAT is open. It is not the detail.** Follow the
pointer for the argument, the evidence and the history.

**Keeping it true is RULE ONE's job.** Close a row here in the same commit that closes it in the
code. A row that leaves this table must leave it as CLOSED with a commit, never by deletion.

Verified column: **[V]** re-verified against the tree on the date shown. **[C]** carried from an
audit, anchor never re-checked, so check before trusting. **[owner]** needs a decision or hardware
this machine does not have.

| Row | Open item, in one line | Ver | Detail |
|---|---|---|---|
| SOL-S3 | overlay draws the SOURCE bitmap's size, never the region's own | [V] 08-18 | REGISTERS 17.11 |
| SOL-S7 | public cue styling claims more than the rasterizers apply | [C] | REGISTERS 17.11 |
| SOL-S8 | positioned bottom cues still consume implicit stacking space | [C] | REGISTERS 17.11 |
| SOL-A6 | passthrough, offload, device selection, route recovery absent | [V] 08-18 | REGISTERS 17.11 |
| SOL-P3 | KiteCodec frame access copies twice and boxes its plane list | [C] | REGISTERS 17.11 |
| SOL-P8 | LinearResampler aliases; ChannelMixer cannot remap equal counts | [V] 08-18 | REGISTERS 17.11 |
| SOL-P9 | a track change reopens the whole session, so live media cannot | [V] 08-18 | REGISTERS 17.11 |
| SOL-P10 | **QUESTIONED**: asks for a SwrContext that exists nowhere in the C | [V] 08-18 | REGISTERS 17.11 |
| SOL-API2 | logger, liveBackBuffer, liveMaxLag, startDisabled accepted and unused | [V] 08-18 | REGISTERS 17.11 |
| SOL-API4 | droppedFramesDecode, audioLatency, containerBitrate, ExternalMaster, LateAndDecode are placeholders | [V] 08-18 | REGISTERS 17.11 |
| SOL-API7 | REDUCED: refusal is typed now; no sealed surface model yet | [V] 08-18 | REGISTERS 17.11 |
| SOL-C1 | 213 exported C helpers that Kotlin/Native cinterop could do | [V] 08-18 | REGISTERS 17.11 |
| SOL-C2 | non-real-time CoreAudio setup still lives in C | [V] 08-18 | REGISTERS 17.11 |
| SOL-C3 | filter composition still builds into a fixed char args[512] | [V] 08-18 | REGISTERS 17.11 |
| SOL-K1 | kitecodec-core still passes -Xcontext-parameters, redundant | [V] 08-18 | REGISTERS 17.11 |
| SOL-K2 | the modernization posture, a style rather than a task | [C] | REGISTERS 17.11 |
| SOL-B4 | vendored archives and Kotlin/Native disagree on the macOS floor | [C] | REGISTERS 17.11 |
| SOL-B5 | JNI and the libass adapter both omit armeabi-v7a | [V] 08-18 [owner] | REGISTERS 17.11 |
| SOL-B6 | the twin repos are not one graph; mavenLocal can shadow a sibling | [C] | REGISTERS 17.11 |
| SOL-B7 | both builds emit Gradle API warnings that become Gradle 10 breaks | [V] 08-18 | REGISTERS 17.11 |
| SOL-B8 | remote publication still lacks the ordinary JVM and Android artifacts | [C] | REGISTERS 17.11 |
| AGW-1 | the Android GPU path has no physical qualification at all | [owner] | REGISTERS 17.11 |
| test debt | nineteen named missing regressions, PARTLY written, never reconciled | [V] 08-18 | REGISTERS 17.11 |
| F-ABI1 | no Android ABI dump exists, so androidMain has nothing to disagree with | [V] 08-18 [owner] | REGISTERS 17.11.b |
| F-COV1 | tests run on six of twenty surfaces; tvos BLOCKED, no SDK here | [V] 08-18 | REGISTERS 17.11.b |
| F-ALPHA1/ROT1/POS1 | the device-only halves: real pixels on a real screen | [owner] | REGISTERS 17.11.b |
| X-08 | nothing runs the player in a Worker, and X-06 waits on it | [V] 08-18 | REGISTERS 17.14 |
| X-10 | the web sink is silent by design; the real one is an AudioWorklet | [V] 08-18 | REGISTERS 17.14 |
| X-11 | the web has NO renderer; videoRenderer is null | [V] 08-18 | REGISTERS 17.14 |
| X-13 | no artifact layout and no deployment story | [V] 08-18 | REGISTERS 17.14 |
| X-14 | the format matrix has never run in a browser | [V] 08-18 | REGISTERS 17.14 |
| 4K | the non-goal was set at 1.0x software and never re-decided against 715 fps hardware | [V] 08-18 [owner] | REGISTERS 17.14 |
| PAR-1 | mingw carries 18 hwaccels while the decision says it carries none | [V] 08-18 [owner] | REGISTERS 17.11 |
| PAR-2 | Linux compiles zero hwaccels | [V] 08-18 | REGISTERS 17.11 |
| PAR-3 | android-x64 still builds with --disable-asm | [V] 08-18 | REGISTERS 17.11 |
| PAR-4 | the web opens webm and has no opus or vorbis decoder for its audio | [V] 08-18 | REGISTERS 17.11 |
| PAR-5 | native linux and mingw have no output backend at all | [V] 08-18 [owner] | REGISTERS 17.11 |
| PAR-6 | AV1 hardware decode has never been positively proven | [owner] | REGISTERS 17.11 |
| PAR-7 | fd: still mutates the caller's descriptor; a positional MediaIo would not | [V] 08-18 | REGISTERS 17.11 |
| L | libass: JVM bridge, wasm, the per-frame animated hook, the mpv corpus | [V] 08-18 | 17.12 below |
| M riders | the iPhone KiteStats run and the physical device session | [owner] | 17.12 below |
| W riders | the Windows matrix run and the physical desktop measurements | [owner] | ARCHIVE 17.13 |
| B-horizon | Deferrals 3, 4, 5 and interlude items 2 to 9 (gate call, ABI ratchet, fuzz rule, derived suite lists, the kprt_ decision) | [C] | ARCHIVE 15.5, 16.4 |

**Counts, so a reader knows the shape without adding up:** 43 rows. 24 verified against the tree on
2026-08-18, 10 carried and unverified [C], 11 needing an owner decision or hardware. One row
(SOL-P10) is QUESTIONED and should be read before it is scheduled.

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

1. **KPKMP.md is the only planning document.** If it is not in KPKMP, it is not the plan. Its
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
