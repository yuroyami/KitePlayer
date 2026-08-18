# KPKMP archive: executed and superseded plans

> Moved verbatim out of KPKMP.md on 2026-08-18. Not one line rewritten; see
> `scripts/verify-kpkmp-split.py`.
>
> **Nothing here is deleted and nothing here is current.** These are plans that have been executed,
> or that a later decision superseded. They are kept because they carry the ARGUMENT behind choices
> the tree still embodies, and because a register row often points into them for its detail.
>
> Contains: Horizon B execution B1 (15), the B1-to-B2 interlude (16), the road to 1.0 as originally
> written (17.0 to 17.10), and the phase W expansion (17.13), whose seventeen sub-phases are all
> accounted for. Section numbers unchanged so cross-references still land.
>
> **17.12 stayed in KPKMP.md**: it is the CURRENT phase order, not history.

## 15. Horizon B execution: B1

Written 2026-08-09, after Horizon A completed, from five reconnaissance reports and two
competing ABI designs, each verified claim by claim against the source and re-measured where
the claim was load bearing. This section is to B1 what section 10 is to Horizon A: the
executable run. It is decision complete. An implementer needs this section, section 1, section
2, section 9 and the code, and nothing else.

### 15.0 The decision, and what it costs

**The judgement.** Neither proposal ships as written. The ambitious reading (full opaque
handles across all 176 helpers, eleven handle families, 136 exported functions, an entire
rewrite of KiteCodec's native implementation) pays its largest cost for a benefit that no
measurement in front of us supports and that B1's own exit criteria never ask for. The
incremental reading is right about the spine and wrong about its end game, because its way of
seeding the opaque surface introduces a second cinterop module, which is the one thing
`kitecodec-core/build.gradle.kts` lines 168 to 171 exists to forbid. What follows is a
synthesis: the incremental spine, the opaque discipline applied only to C surface that is
new anyway, the audio work split so the shipped real-time path changes exactly once, and three
pieces of scaffolding neither proposal had because neither measured the gap.

**Why the opaque rename is not in B1.** Three reasons, in descending strength.

1. B1's exit sentence has three clauses: one C implementation serves cinterop, a mismatched
   FFmpeg runtime is rejected with a report, callback allocation instrumentation reads zero.
   Opaque handles are required by none of them. Clause one needs external linkage. Clause two
   needs the header macros frozen by the compilation that bakes the offsets, plus one
   process-wide once-only initialiser, which is an argument for a real library and against a
   header-only inline one, and has nothing to do with how a parameter is spelled. Clause three
   lives in KitePlayer and touches no FFmpeg type.
2. Opacity does not reduce the struct-layout hazard by one bit. The offsets are baked into the
   publisher's compiled artifact either way: today into `cstubs.bc` inside the klib, after the
   lift into `libkitecodec.a` inside the same klib. A consumer with a different FFmpeg gets the
   publisher's offsets in both worlds. The identity gate is what makes that safe. A B1 that
   shipped opacity and skipped the gate would have done the expensive thing and left the
   dangerous one.
3. Partial opacity buys nothing measurable. Reproduced from the incremental proposal's own
   artifacts: a def naming only a helper header, with no FFmpeg header at all, still emits 82
   `CStructVar` classes and still contains `ffkmp/AVFrame`, because cinterop drags in every
   complete type reachable from a bound signature. The klib shrink arrives only when the last
   FFmpeg-typed signature is gone, so a half-migration pays all the churn for none of the
   prize.

**What was decided against the ambitious reading, item by item.** The eleven handle families
included six (`kc_swr`, `kc_sws`, `kc_hwdevice`, `kc_hwframes`, `kc_io`, `kc_cancel`) with
nothing to lift: the def body contains zero `swr_` calls, zero function-pointer parameters and
one `SwsContext` use. Its justification was that the ABI major must be right the first time.
Measured against that: `CHANGELOG.md` states "The library is source-only for now. Nothing has
been published, not `kitecodec-core`, not the Gradle plugin", and `git tag` in KiteCodec returns
nothing. There is no downstream consumer for whom an ABI major bump costs anything, so
speculative surface has no purchase. Those six families are B2's, where their consumers live.

**What was decided against the incremental reading.** Its B1.5 seeded a second cinterop module
and crossed the seam with a `reinterpret()`. That is exactly the duplicate-type hazard the
single-module rule prevents, and it would sit in production code for the length of a migration
that spans two horizons. Rejected. The opaque surface, when it comes, arrives inside the one
existing `ffmpeg` cinterop module, family by family, and the FFmpeg headers leave that def only
on the last day. Its second flaw was a mid-flight state in which the C ring is on the shipped
path while the Kotlin callback still drives it, which changes the real-time path twice and is
worse than either endpoint in between. Rejected: see B1.7 and B1.8.

**What this section adds that neither proposal had.** A committed ABI baseline, which does not
exist today. A coupling ratchet, so the thing being deferred can only shrink. A host C build
that depends on neither cmake nor make. A leak instrument that works on this machine. And the
stale-embedded-archive question asked out loud, which is the largest unmeasured build risk in
both proposals; B1.3 measured it, found that cinterop does NOT track the archive on its own, and
declared it an input. See the corrected first bullet below.

**Evidence gained during this judgement (level 2 unless stated).**

- CORRECTED AT B1.3, and the correction is the load-bearing part. This bullet used to read "the
  cinterop task tracks the static archive", on the strength of the ambitious proposal's prototype
  at `scratchpad/proof-abi`, where mutating only `kitecodec.c` (`kc_abi_minor` returning 1, then
  77, then 88) made `cinteropKitecodecMacosArm64` re-execute and the linked binary print
  `abi=1.77` and then `abi=1.88`. That prototype result is real but it does not generalise, and
  B1.3 measured the opposite in the actual wiring: with the archive named only by
  `staticLibraries` in the def and its directory supplied as `extraOpts("-libraryPath", ...)`,
  editing only `native/kitecodec-c/src/kitecodec_helpers.c` re-executes the C compile and writes
  a new archive, and `cinteropFfmpegMacosArm64` then reports UP-TO-DATE and leaves the STALE
  archive inside the klib, with the configuration cache on or off. Gradle names the mechanism
  under `--info`: "CInterop task uses custom Up-To-Date check for content of headers instead of
  Gradle mechanisms", and that check covers the def and the headers, not a library the def merely
  names. A `.h` edit or a def edit does re-execute it, and a MISSING archive fails loudly with a
  non-zero exit, so the hazard is exactly one shape: a C body edit during local incremental
  development, which is what every sub-phase from B1.4 onward does. The fix is three lines in
  `kitecodec-core/build.gradle.kts`, `inputs.files(<the archive>)` on the cinterop task, because
  an input change makes a task out of date whatever its own predicate says; no
  `outputs.upToDateWhen { false }` is needed and none was added. Proved at level 2 both ways:
  before the fix the embedded archive stayed at digest `6ad670ad...` while the built one moved to
  `da6406da...`; after it, the same C-body-only edit made cinterop re-execute and the object
  inside the klib disassembled to `mov w8, #0x4d ; =77`. A no-op rebuild still reports both tasks
  UP-TO-DATE, so nothing churns.
- KiteCodec has no ABI baseline. `KiteCodec/kitecodec-core/api` does not exist and no `.api`
  file exists anywhere in KiteCodec outside `build/`, although `build.gradle.kts` line 61
  configures `apiValidation` with `klib { enabled = true }`, and no CI job runs `apiCheck`.
  The six committed dumps are all KitePlayer's. Horizon A step A6.3 said "every KitePlayer
  module", so this gap is expected, not a regression.
- LeakSanitizer does not run here. `ASAN_OPTIONS=detect_leaks=1` on an ASan-and-UBSan binary
  built by Apple clang 17 prints "AddressSanitizer: detect_leaks is not supported on this
  platform." ThreadSanitizer builds and runs. libFuzzer does not link:
  `library '.../libclang_rt.fuzzer_osx.a' not found`.
- cmake is not installed (`which cmake` finds nothing). `ninja` and `make` are present. GNU
  make starts a comment at an unescaped `#`, which this repository already documents and
  guards against in `buildSrc/src/main/kotlin/BuildFFmpegTask.kt` lines 113 to 124, and both
  repositories live under `/Users/macbook/StudioProjects/#Kite/`. So the host C build uses a
  shell script driving clang directly, with no make, no cmake and no ninja.
- The incremental proposal's headline differential is right and its description is wrong. Its
  own dumps measure 18684 filtered metadata lines for the inline variant and 18860 for the
  archive variant, a difference of exactly 176, and the raw diff carries 176 added
  `CCall.Direct` lines. The raw diff is 356 lines, not 178, because normal-diff format prints
  a position marker per hunk. A gate written as `diff | wc -l` compared against 178 would fail
  a correct build. The gate below counts the substance, not the diff lines.
- `Frame.withPlanes` really is one plus three per plane crossings. Read at
  `../KiteCodec/.../Playback.native.kt` lines 453 to 463: one `ffkmp_frame_plane_count`, then
  per plane `ffkmp_frame_plane`, `ffkmp_frame_linesize` and `ffkmp_frame_plane_height`. Ten
  for a three-plane frame.

**One design claim contradicted by its own reconnaissance, recorded so it is not repeated.**
The ambitious proposal wrote that its new packed-copy function "takes a capacity, which the
current `ffkmp_frame_copy_to_buffer` (ffmpeg.def:116) does not". Recon R1 section 7 says the
opposite: "Four helpers take a raw pointer plus a size and copy across it:
`ffkmp_frame_copy_to_buffer` (L116), `ffkmp_samples_copy_to_buffer` (L130),
`ffkmp_frame_fill_video` (L148), `ffkmp_frame_fill_audio` (L163)". The source at
`ffmpeg.def:116` reads
`static inline int ffkmp_frame_copy_to_buffer(AVFrame *f, uint8_t *dst, int dst_size)` and
passes `dst_size` to `av_image_copy_to_buffer`. R1 is correct. The bound already exists; what
is missing is a test that exercises it, which is task B1-10.

**What B1 delivers.** The 176 helpers become a compiled, versioned, symbol-audited library with
its own C tests, sanitizer runs and fuzz targets. The FFmpeg header versus runtime identity gate
exists, is called before anything allocates, and its rejection path is proved to fire by a
hermetic test rather than by argument. The real-time audio path becomes C from the device
callback down, behind a differential oracle against the Kotlin ring. KiteCodec gains the ABI
baseline it never had.

**What B1 does not deliver, stated plainly so no later reader mistakes silence for completion.**
No opaque handles over the legacy helpers. No Kotlin call-site migration: the 253 cinterop
import lines, the 273 `ffkmp_` call sites and the 21 raw libav call sites are untouched, and the
FFmpeg headers stay in the def. The 11 FFmpeg struct types still reach Kotlin as phantom pointer
types. Section 15.5 records where each of those goes and what breaks if it never happens.

**What would make this decision wrong.** If B7 were pulled forward into the same release as B1,
paying the opaque migration once here would beat carrying the coupling across two horizons, and
the ambitious reading would be correct. The roadmap puts B7 six items later. If the owner
reorders, this section's B1.4 onwards is still valid and section 15.5's deferral becomes a
B1.10 rather than a B2 item.

### 15.1 B1 task register

Same shape as section 4. Every item is located, has its fix decided, and names its test. The
Phase line says where it is fixed; items whose fix belongs to a later Horizon B item say so and
are carried here only so nobody rediscovers them. 25 items.

#### B1-01. The def body cannot be compiled, tested, sanitized or fuzzed as a unit
- Where: `../KiteCodec/kitecodec-core/src/nativeInterop/cinterop/ffmpeg.def`, lines 13 to 961
  (949 lines of C after the `---` separator on line 11), 176 `static inline ffkmp_*` helpers.
- Problem: the C exists only as def-file text. It has no translation unit, no object file, no
  test, no sanitizer run and no coverage. Its only compile check is cinterop's, and its only
  test is whatever Kotlin happens to call. 19 of the 176 are never called from Kotlin at all.
- Fix: extract to `include/kitecodec_helpers.h` plus `src/kitecodec_helpers.c` by a committed
  generator, prove the extraction faithful by re-running the generator and byte-comparing, then
  point the def at the compiled archive.
- Phase: B1.2 and B1.3. Test: `scripts/verify-lift.sh` byte equality, plus the C suite of 15.3.

#### B1-02. No FFmpeg header versus runtime identity check exists
- Where: `../KiteCodec/.../FFmpeg.native.kt` lines 17 to 29 read only runtime values;
  `ffmpeg.def` has two `LIBAVCODEC_VERSION_INT` gates at lines 278 and 291 and no assertion.
- Problem: in the direction that matters, older headers against a newer runtime, every symbol
  resolves and the link succeeds while 38 measured field offsets are wrong and 48 of the 176
  helpers read or write through one of them. Demonstrated live during reconnaissance: wrong
  values read, then SIGSEGV inside `av_frame_free`, with ASan naming a four byte read 36 bytes
  past a 416 byte region. Silent in the nondeterministic case.
- Fix: a generated translation unit inside the same C compilation freezes the six
  `LIB*_VERSION_INT` macros; `kc_init` compares them to the six `*_version()` functions under
  `pthread_once`; policy is major exactly equal and a hard reject, runtime minor at or above
  header minor or reject, micro reported and never fatal, plus a cross-library
  `*_configuration()` agreement check that catches a mixed install. Every entry point calls
  `kc_init` first.
- Phase: B1.6. Test: the hermetic doctored-macro negative test of 15.3, one case per verdict,
  plus a positive test, plus the KitePlayer typed-error surface.

#### B1-03. `ffmpeg.version` in the plugin DSL is unvalidated
- Where: `../KiteCodec/kitecodec-gradle-plugin/.../KiteCodecExtension.kt` line 35, a free
  `Property<String>` with a convention of `n8.0` from `KiteCodecPlugin.kt` line 11 and no check.
- Problem: a consumer writing `kitecodec { ffmpeg { version = "n7.1" } }` with the default
  `Prebuilt` source downloads FFmpeg 7.1 archives and links them against a klib whose stubs were
  compiled against n8.0 headers. Every symbol the def needs exists in 7.1, so the static link
  succeeds and there is no SONAME to stop it. This is the most likely route a real consumer
  takes to the corruption of B1-02.
- Fix: validate the property against the set of refs the artifact was built for, and fail
  configuration with the actionable sentence naming both refs and the two ways out.
- Phase: B1.6. Test: a Gradle plugin functional test that sets a mismatched version and asserts
  the failure message names both refs.

#### B1-04. The `n8.0` expectation is duplicated in three places bound only by a comment
- Where: `buildSrc/.../BuildFFmpegTask.kt` line 506, `kitecodec-gradle-plugin/.../KiteCodecPlugin.kt`
  line 11, `.github/workflows/publish.yml` line 41, whose lines 38 to 40 say to keep them in sync.
- Problem: nothing enforces it, and nothing checks either against `vendor/ffmpeg/RELEASE` or the
  vendored `libavutil/version.h`.
- Fix: one build-time assertion that all three agree, and, when the vendored path is used, that
  they agree with the checkout.
- Phase: B1.6. Test: a buildSrc unit test over the assertion with agreeing and disagreeing inputs.

#### B1-05. KiteCodec has no committed ABI baseline although the validator is configured
- Where: `../KiteCodec/build.gradle.kts` line 61 configures `apiValidation` with klib validation
  enabled; `kitecodec-core/api` does not exist; `.github/workflows/ci.yml` never runs `apiCheck`.
- Problem: B1 rewrites KiteCodec's build and its C layer, and the only mechanical proof that the
  public Kotlin API did not move is a baseline nobody generated. Without it, "the public API is
  unchanged" is a level 8 claim.
- Fix: generate and commit the klib dump, wire `apiCheck` into the gate and into the macOS CI job.
- Phase: B1.1. Test: `apiCheck` is itself the test; the gate runs it after every later sub-phase.

#### B1-06. The Kotlin to FFmpeg coupling has no ratchet
- Where: nowhere; this is a missing artifact. The measured baseline is 253 cinterop import lines,
  273 `ffkmp_` call sites, 21 direct libav call sites and 11 FFmpeg struct types reaching Kotlin,
  across 10 Kotlin files in `kitecodec-core`.
- Problem: B1 defers the opaque migration. A deferral with no ratchet becomes a permanent
  half-state, and the coupling can grow while everyone believes it is shrinking.
- Fix: commit the four counts as a baseline file and a check task that recomputes them and fails
  when any is higher than the baseline. Lowering the baseline is a normal commit; raising it needs
  an Execution log entry saying why.
- Phase: B1.1. Test: the check task, run in every later gate, plus a unit test proving it fails on
  a deliberately raised count.

#### B1-07. The em dash scan does not cover the file types B1 introduces
- Where: section 9's scan lists `*.kt`, `*.kts`, `*.md`, `*.def`.
- Problem: B1 adds `.c`, `.h`, `.sh` and edits `.yml`. Contract item 4 bans em dashes in every
  file, and the scan that enforces it would not see the new ones.
- Fix: extend the scan's include list to `*.c`, `*.h`, `*.sh`, `*.yml`, `*.py`, `*.txt` for both
  repositories, keeping the existing exclusions.
- Phase: B1.1. Test: the scan itself, run in every gate.

#### B1-08. 15 helpers are dead exported surface, and `archived/` duplicates 176 helper names
- Where: `ffmpeg.def` lines 43, 44, 59, 76, 199, 203, 228, 315, 335, 375, 384, 444, 452, 810, 811;
  `../KiteCodec/kitecodec-core/src/nativeInterop/cinterop/archived/` holds six def files
  (`libavcodec.def`, `libavfilter.def`, `libavformat.def`, `libavutil.def`, `libswresample.def`,
  `libswscale.def`) that no build file references.
- Problem: dead `static inline` text is harmless; 15 dead exported symbols in a versioned library
  are a compatibility promise nobody meant to make. The archived defs duplicate helper names and
  will produce false hits in every later grep, including this item's own cross-check.
- Fix: delete the 15 and delete the directory. Safe because nothing has been published (see 15.0)
  and because zero KitePlayer files import from the cinterop package.
- Phase: B1.4. Test: a repository-wide cross-check, after the directory is gone, proving zero
  references to each deleted name in either repository, in any file type.

#### B1-09. `ffkmp_strerror` returns a thread-affine pointer into static storage
- Where: `ffmpeg.def` lines 36 to 40, `static __thread char buf[256]` at line 37, the only static
  storage in the whole body.
- Problem: the returned pointer is invalidated by the next call on the same thread. Nothing states
  it and nothing tests it. `Internals.kt` line 20 consumes it immediately, which is correct today
  by accident of call shape rather than by contract.
- Fix in B1: state the contract in the header and prove it with a two-thread C test. The error
  record that removes the helper is B2's, because it changes 176 signatures.
- Phase: B1.2 for the test and the documented contract; replacement in B2. Test:
  `tests/test_strerror_thread.c`, two threads, interleaved calls, each asserting its own message.

#### B1-10. Nine fixed stack buffers and 18 snprintf sites have no C-level test
- Where: `ffmpeg.def` line 37 (`buf[256]`), 506, 558, 663, 708 (`args[512]`), 553
  (`layout_str[128]`), 704 (`lay_str[128]`), 623, 669, 712 (`name[16]`), 578, 723
  (`full_desc[2048]`). Plus the four copy helpers that take a caller pointer and a size at lines
  116, 130, 148, 163.
- Problem: D27 installed running-length discipline in the two audio builders and A0 tested it
  through the Kotlin API only. At the C level none of the nine buffers, and none of the four size
  checks, has a direct test. The bound in `ffkmp_frame_copy_to_buffer` exists (see 15.0) and is
  unexercised.
- Fix: direct C tests driving every buffer to its limit and one byte past, and every copy helper
  with a destination one byte short, under ASan and UBSan.
- Phase: B1.2. Test: `tests/test_buffers.c`, table driven, one row per buffer and per copy helper.

#### B1-11. cinterop embeds a wrong-architecture archive silently
- Where: the mechanism, measured during reconnaissance: a linuxX64 ELF archive placed where the
  macosArm64 one belongs was embedded without complaint and failed only at the consumer's final
  link with `ld: archive member '/' not a mach-o file`.
- Problem: B1 creates this failure mode; it does not exist today because there is no archive. It
  surfaces at the consumer, not at the producer.
- Fix: the C compile task's output directory is keyed by `konanTarget.name` and never shared, and
  the task asserts the produced object's architecture before archiving.
- Phase: B1.3. Test: a buildSrc unit test over the architecture assertion with a deliberately
  wrong object, plus a gate step that inspects each embedded archive.

#### B1-12. The def declares no `linkerOpts.ios` although three iOS targets are registered
- Where: `ffmpeg.def` lines 5 to 9 carry `osx`, `linux`, `mingw` and `android` only;
  `kitecodec-core/build.gradle.kts` lines 126 to 128 register `iosArm64`, `iosSimulatorArm64` and
  `iosX64`; `StaticLinkFlags.forTarget` never emits a libav flag, so the six `-l` flags reach the
  link only from this file.
- Problem: on iOS they do not reach it at all.
- Fix: add `linkerOpts.ios` with the same six flags while the def is being edited anyway.
- Phase: B1.3. Test: none possible here. No iOS FFmpeg tree exists on this machine, so
  `FFmpegPaths.resolve` fails and the target is skipped before the def is read. The change is
  level 8 evidence, a declared flag, and the register row stays open until a target with an iOS
  FFmpeg tree exists in B7 or B9.

#### B1-13. libFuzzer is absent from every clang on this machine
- Where: Apple clang 17 and konan's LLVM 21 essentials package both fail with
  `library '.../libclang_rt.fuzzer_osx.a' not found`; Homebrew LLVM is not installed.
- Problem: B1 promises fuzz targets. Coverage-guided fuzzing cannot run locally.
- Fix: each fuzz entry point gets two drivers from one source: `LLVMFuzzerTestOneInput` for the
  Linux CI job, and a corpus replay `main()` compiled everywhere, so the committed corpus runs as
  an ordinary sanitized regression test on this machine. Installing Homebrew LLVM is optional and
  is not a prerequisite.
- Phase: B1.5. Test: the replay driver is the local test; the CI job is the real fuzzer.

#### B1-14. LeakSanitizer is not supported on macOS arm64
- Where: measured on this machine. `ASAN_OPTIONS=detect_leaks=1` on an ASan build by Apple clang
  17 prints "AddressSanitizer: detect_leaks is not supported on this platform."
- Problem: the obvious instrument for the 29 ownership helpers does not exist here, and a plan
  that assumed it would have had no leak evidence at all on the proving platform.
- Fix: a `malloc`, `calloc`, `realloc` and `free` interposer through the Mach-O
  `__DATA,__interpose` section, proven working during reconnaissance, is the local instrument and
  asserts exact pairing per helper. LSan runs in the Linux CI job as corroboration. Note that
  naive `DYLD_INSERT_LIBRARIES` symbol shadowing silently counts zero because of the two-level
  namespace, so the interposer must use the interpose section.
- Phase: B1.2. Test: `tests/test_ownership.c` under the interposer, one case per ownership helper,
  including the three awkward ones named in 15.3.

#### B1-15. cmake is absent, and GNU make truncates a path at `#`
- Where: `which cmake` finds nothing; `ninja` and `make` are present;
  `buildSrc/.../BuildFFmpegTask.kt` lines 113 to 124 document that make "starts a COMMENT at an
  unescaped '#'" and refuse to build under such a path; both repositories are under
  `/Users/macbook/StudioProjects/#Kite/`.
- Problem: any host C build routed through cmake or make is either unavailable or exposed to the
  hazard this repository already had to guard against, in the one directory where the guard
  applies.
- Fix: the host C build is a shell script invoking clang directly, and the shipped per-target
  archive is a Gradle task invoking clang and `llvm-ar` directly. No make, no cmake, no ninja.
  Direct clang invocation under a `#` path is proven: reconnaissance compiled and archived
  successfully in a directory literally named `hash#dir`.
- Phase: B1.2. Test: the scripts run from this checkout, which is itself the proof.

#### B1-16. The real-time thread is the seqlock reader and can spin unbounded
- Where: `kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/AudioRing.kt`
  lines 279 to 294, inside `publishAnchor`, reached from `render` at line 261.
- Problem: the device thread loads `segmentSeq` and `continue`s while it is odd. The writer is the
  feeder coroutine. If the feeder is preempted between its two sequence increments, the real-time
  thread waits with no bound. The class comment at lines 13 to 14 says "No lock anywhere", which
  is true of mutexes and not of this. It is a priority inversion on a real-time thread and it is
  independent of the language the ring is written in. A transliteration into C would reproduce it.
- Fix: invert the roles. In the C ring the real-time thread is the seqlock writer and never waits;
  the non-real-time reader retries a bounded 64 times and then keeps its previous reading. Segment
  resolution becomes wait free through per-slot sequence numbers plus a consumer-private cache and
  a counted give-up, so the reader dates from the cache instead of spinning.
- Phase: B1.7. Test: a TSan run over the ring, plus a C test that holds a slot's sequence odd for
  a bounded interval from a second thread and asserts the render path never blocks and increments
  `segment_giveups`.

#### B1-17. The real-time callback enters managed Kotlin on its first instruction
- Where: `kiteplayer-output/src/appleMain/kotlin/.../CoreAudioSink.kt` lines 224 to 229; line 225
  is `refCon?.asStableRef<CoreAudioSink>()?.get()`.
- Problem: the device thread becomes a Kotlin mutator the collector must stop at a safepoint. 13
  long-lived Kotlin objects, 14 atomic wrapper objects, two virtual interface calls, a scalar
  float copy loop, and up to five transient cinterop view objects per callback are on that path.
  In a debug build the view objects are real allocations: five million invocations of a faithful
  probe produced 1235 GC epochs against 1 for a non-allocating control. In an optimized build
  escape analysis removed them in that probe, which is a compiler heuristic with no contract and
  is not a defence. Worst observed stop-the-world pauses on this machine were 63 to 256
  microseconds with one allocating mutator, against a 10.67 millisecond period at 512 frames and
  48 kHz. The honest statement is not that audio glitches today; it is that the deadline depends
  on a pause nobody has bounded.
- Fix: the render callback becomes a `static` C function inside `kiteplayer-rt`, installed by C,
  reading a plain struct pointer with no `StableRef`, converting host ticks with a
  `mach_timebase_info` cached at create, and calling `kprt_ring_render` straight into the device
  buffer.
- Phase: B1.8. Test: the four assertions of 15.3, in their stated order of authority, including
  the negative control that must fail.

#### B1-18. The ring's microsecond dating multiplies before dividing
- Where: `AudioRing.kt` lines 172 and 296.
- Problem: a frame delta is multiplied by 1,000,000 before dividing by the sample rate. That is
  the same overflow shape D9 records against KiteCodec's timestamp helpers, latent at ordinary
  session lengths and wrong at long ones. The Kotlin ring has it too and it should not be
  transliterated.
- Fix: a 128 bit intermediate or a split rescale in the C ring, and the same correction applied to
  `KotlinAudioRing` so the differential oracle compares two correct implementations.
- Phase: B1.7. Test: a table-driven case at a frame delta large enough to overflow the naive
  product, asserted exact in both rings.

#### B1-19. Silence fill and underrun counting are duplicated at two levels
- Where: `CoreAudioSink.kt` lines 391 to 392 and `AudioRing.kt` lines 252 to 255; the comment at
  `CoreAudioSink.kt` lines 90 to 93 calls the duplication deliberate.
- Problem: after B1.8 there is no callback that can be absent, so the sink's last-line fill has no
  case left to cover, and the comment stops being true.
- Fix: silence and the underrun counter collapse into `kprt_ring_render`, using `memset` and
  `memcpy` rather than the scalar loops at `CoreAudioSink.kt` lines 434 to 437 and 460 to 465. The
  only absent-ring case becomes teardown, where the callback zeroes the whole buffer. Update the
  KDoc.
- Phase: B1.8. Test: the C test asserting a short read produces exactly the expected real frames
  followed by exact zeroes, and that the underrun counter moves only when the ring is not ending.

#### B1-20. After B1.8, `AudioRingTest`'s 16 tests exercise a ring no macOS user runs
- Where: `kiteplayer-core/src/commonTest/kotlin/.../AudioRingTest.kt`, 16 `@Test` functions, plus
  the whole A5 simulation campaign driving the Kotlin ring through `ScriptedBackend`'s fake sink.
- Problem: `AudioRing` cannot be deleted or made an `expect class`. `kiteplayer-core`'s
  `commonMain` targets js and wasmJs, which can never contain C, and the Kotlin ring is the only
  oracle the C ring can be checked against. So two implementations of one contract exist forever,
  and on macOS the 16 tests stop covering the shipped path. Letting "414 tests pass" quietly cover
  a ring nothing uses would be exactly the substitution section 2 forbids.
- Fix: say it plainly in the README, in `AudioRingTest`'s class KDoc and in the log entry, and
  carry the shipped path with the C suite plus the differential oracle, which is the only thing
  that keeps the two from drifting.
- Phase: B1.7 for the oracle, B1.9 for the words. Test: the differential oracle of 15.3.

#### B1-21. The declared FFmpeg licence flavour contradicts the linked runtime's licence string
- Where: `kiteplayer-ffmpeg/build.gradle.kts` line 60 and `kiteplayer-sample/build.gradle.kts`
  line 36 set `FFmpegLicense.LGPL`; the linked Homebrew runtime's `avutil_license()` returns
  "GPL version 3 or later".
- Problem: the build declares one thing and the artifact links another. Nothing surfaces it.
- Fix in B1: the identity report carries the runtime licence string and the flavour the artifact
  was built for, so the contradiction is visible in every rejection and in every diagnostic dump.
  Resolving it is a distribution and legal question, not an ABI one.
- Phase: B1.6 for visibility; resolution in B7. Test: an assertion that the report's two licence
  fields are both populated, and a diagnostic path that prints them.

#### B1-22. The 11 raw libav calls at 21 Kotlin call sites are behind no helper
- Where: `FFmpeg.native.kt` 10 sites, `Frame.native.kt` 4 (lines 191, 236, 247, 253),
  `MediaSink.native.kt` 3 (210, 498, 511; re-measured at the interlude, this row said 496 and
  509), `MediaSource.native.kt` 2 (262, 283), `Playback.native.kt` 2 (317, 340).
- Problem: the four hottest paths in the codebase, decode send and receive and encode send and
  receive, cross straight to libav with no C layer in between. Any future handle boundary leaks on
  the busiest calls. Three of the enclosing declarations name no FFmpeg type at all
  (`MediaSource.native.kt:279`, `Playback.native.kt:314` and `:338`), so a type-name audit misses
  them.
- Fix in B1: the six `*_version()` functions and `avcodec_configuration` move behind the identity
  report, because that is where they belong. The four hot calls stay raw, because wrapping them
  without B2's typed send and receive outcomes would change their signatures twice.
- Phase: B1.6 for the version queries; in B2, the four hot functions at their NINE sites and the
  three `find_*_by_name` queries at their FIVE sites (split corrected at the interlude per I-13:
  this row and the baseline's prose both said seven hot sites and four lookups, which matched no
  measurement; the fourteen sites are enumerated with their lines in `coupling-baseline.txt`).
  Test: since I-13 the coupling ratchet counts one `ffmpeg_typed_crossings` number, so moving
  these behind helpers is neutral where it used to fail the build, and a genuine rise still
  refuses.

#### B1-23. `ffkmp_frame_convert_pixfmt` allocates and frees an SwsContext on every call
- Where: `ffmpeg.def` line 93.
- Problem: the only swscale use in the library rebuilds its context per frame. B2 names cached
  swscale contexts as its own work.
- Fix: none in B1. Carried here so the C tests cover the current behaviour rather than being
  rewritten when B2 changes it.
- Phase: B2. Test: a C test asserting the current conversion is correct and leak free under the
  interposer, so B2's caching has a baseline to match.

#### B1-24. KitePlayer has no CI
- Where: `/Users/macbook/StudioProjects/#Kite/KitePlayer` has no `.github` directory.
- Problem: every KitePlayer gate in B1, including the 10 minute real-device audio test and its
  negative control, runs on this one machine. That caps the evidence at level 2 on one named
  platform and no higher, and it makes B1.8's gate a serial human-supervised run of roughly 25
  minutes per attempt.
- Fix: none in B1. Recorded so the evidence claims stay honest and so the gate's cost is not a
  surprise.
- Phase: B8, settled at the interlude (I-16): this row said B9 while section 15.6 question 2
  reassigned continuous integration to B8 and section 11's B9 text names no CI. Test: none.

#### B1-25. The opaque handle migration is deferred and needs a deadline
- Where: the whole of 15.0 and 15.5.
- Problem: a deferral with no owner and no deadline is a permanent half-state. The measured
  coupling is 253 import lines, 273 helper call sites, 21 raw libav sites, 45 declarations naming
  an FFmpeg type and 117 declarations touching the cinterop surface, all inside 10 files of
  `kitecodec-core` and zero files of KitePlayer.
- Fix: B2 owns the signatures, because B2 redesigns them anyway for typed outcomes, pooled plane
  views with negative strides, the full channel layout and the side-data model. B7 owns the
  completion deadline, because the Android AAR over JNI is the first consumer that benefits. The
  ratchet of B1-06 holds the line in between. The migration happens inside the one existing
  `ffmpeg` cinterop module, never in a second one.
- Phase: B2 and B7. Test: the ratchet, and B7's exit criterion.
- Closed by S1.a.8. The one existing cinterop now parses only helpers, opaque handles and ABI;
  Kotlin source names no raw FFmpeg import, call or struct type; both direct-coupling ceilings are
  zero; and the public C ABI is 2.0. The historical measurements above describe the deferred
  state that S1.a.8 removed.

### 15.2 Sub-phases

Nine sub-phases, strictly in order, same rules as section 10: no sub-phase starts before the
previous gate passed, every gate reruns for real, every sub-phase ends with an Execution log
entry and one commit per repository touched. Contract items 1 to 13 apply unchanged, including
the ban on branches, the ban on trailers and the ban on em dashes.

**The base gate**, referred to below as "the section 9 gate", was section 9's protocol plus two
additions installed at B1.1, `:kitecodec-core:apiCheck` and `checkCinteropCoupling`, plus an em
dash scan widened by file extension. The interlude (I-15) promoted both gradle additions into
section 9 itself and deleted the widened scan in favour of section 9's `git ls-files` form,
because an extension allowlist cannot reach an extensionless file: both `LICENSE` files carried an
em dash through every widened run until I-18 found them. Where an entry below says "the section 9
gate", read today's section 9.

Paths below are relative to the repository they belong to. `../KiteCodec` means the KiteCodec
repository; an unprefixed path means KitePlayer.

---

#### B1.1 Baseline and ratchets

Pure addition. Nothing in either repository's behaviour changes. This sub-phase exists so every
later sub-phase can prove compatibility mechanically instead of asserting it.

**Files.**
- `../KiteCodec/kitecodec-core/api/kitecodec-core.klib.api` (new, generated)
- `../KiteCodec/native/kitecodec-c/coupling-baseline.txt` (new)
- `../KiteCodec/buildSrc/src/main/kotlin/CheckCinteropCouplingTask.kt` (new)
- `../KiteCodec/buildSrc/src/test/kotlin/CheckCinteropCouplingTaskTest.kt` (new)
- `../KiteCodec/build.gradle.kts` (register the check task)
- `../KiteCodec/.github/workflows/ci.yml` (add `apiCheck` and the coupling check to the macOS job)

**Steps.**
1. Run `./gradlew :kitecodec-core:apiDump` in `../KiteCodec` and commit the generated dump.
   Record its line count in the log entry. This closes B1-05.
2. Write `coupling-baseline.txt` with the four measured numbers, one per line, each with the
   command that produces it: cinterop import lines 253, `ffkmp_` call sites 273, direct libav call
   sites 21, FFmpeg struct types named in Kotlin 11. Re-measure before committing; if a number
   differs from this section, the measured number wins and the deviation goes in the log.
3. Write `CheckCinteropCouplingTask`, an `abstract class ... : DefaultTask()` with
   `DirectoryProperty` inputs and a `RegularFileProperty` baseline, recomputing the four counts
   over `kitecodec-core/src` and failing when any exceeds its baseline. It must exclude `build/`
   and `.claude/`, which hold gitignored scratch checkouts of the same files. Configuration cache
   safe: no script references captured, no process started at configuration time.
4. Widen the em dash scan as shown above, and re-run it over both repositories. This closes B1-07.
5. Add `apiCheck` and `checkCinteropCoupling` to the macOS CI job.

**Tests it must add.** `CheckCinteropCouplingTaskTest`, two cases: a baseline that matches passes,
and a baseline lowered by one for each of the four counts fails with a message naming the count,
the baseline and the actual. `apiCheck` is its own test.

**Gate.**
```bash
cd ../KiteCodec && ./gradlew :kitecodec-core:apiDump && git diff --exit-code kitecodec-core/api
cd ../KiteCodec && ./gradlew :kitecodec-core:apiCheck checkCinteropCoupling :buildSrc:test
# then the full section 9 gate, both repositories
```
`git diff --exit-code` after a fresh `apiDump` is the proof that the committed dump is the one the
build produces.

**Commit first line.** `Record KiteCodec's public ABI and its FFmpeg coupling so both can only shrink`

---

#### B1.2 Real C sources and a host test harness, referenced by nothing

Pure addition. The Gradle build does not reference the new tree; the def file is untouched; the
klib is bit-identical to B1.1's. A regression here is impossible by construction, which is why
the whole C test and sanitizer harness lands before the lift rather than after it.

**Files**, all new, all under `../KiteCodec/native/kitecodec-c/`.
- `tools/extract_from_def.py`
- `include/kitecodec_helpers.h`
- `src/kitecodec_helpers.c`
- `scripts/build-host.sh`
- `scripts/verify-lift.sh`
- `scripts/run-c-tests.sh`
- `tests/harness.h`, `tests/harness.c`
- `tests/interpose_alloc.c`
- `tests/test_ownership.c`, `tests/test_buffers.c`, `tests/test_rescale.c`,
  `tests/test_strerror_thread.c`, `tests/test_convert.c`
- `README.md`

**Steps.**
1. Write the extractor. Rules, all measured: the body is `ffmpeg.def` lines 13 to 961; its 20
   `#include` lines move to the header; 176 declarations are emitted by paren balancing, and nine
   signatures span more than one line (def lines 251, 262, 470, 489, 531, 616, 644, 684, 816); the
   `.c` file is the body verbatim with the token `static inline ` removed and
   `#include "kitecodec_helpers.h"` first. The four internal trailing-underscore helpers
   (`ffkmp_graph_finish_` at def line 470, `ffkmp_graph_finish_multi_` at 616,
   `ffkmp_codec_pix_fmts_` at 289, `ffkmp_ch_layout_mask_` at 908) keep `static`, lose `inline`,
   and are not declared in the header. Each of the four is called only from within its own banner
   section, so the split in B1.4 keeps every call intra-unit; verify that before splitting.
2. Because the `.c` includes its own header, compiling it is the proof that all 176 declarations
   match their definitions. Compile with `-Werror`, so one mismatch is a hard failure.
3. Write `build-host.sh`: `/usr/bin/clang` for host test binaries, flags
   `-std=c11 -Wall -Wextra -Werror -Werror=vla -g`, FFmpeg include and library flags from
   `pkg-config --cflags --libs libavcodec libavformat libavfilter libavutil libswscale libswresample`
   or from `KC_FFMPEG_PREFIX` when set. Variants: `plain`, `asan` (`-fsanitize=address,undefined
   -fno-omit-frame-pointer -O1`), `tsan` (`-fsanitize=thread -O1`). No make, no cmake, no ninja;
   B1-15 says why.
4. Write `verify-lift.sh`: extract from the def body at a given git revision, byte-compare against
   the committed sources, print the two sha256 digests.
5. Write the C tests per 15.3. Every test binary returns non-zero on the first failure and prints
   one line per case.

**Tests it must add.** `test_ownership.c` covering all 29 ownership helpers under the interposer,
including `ffkmp_fmt_new_stream` (allocates but the parent owns the result, so the pairing rule is
different), `ffkmp_frame_convert_pixfmt` (allocates and frees an `SwsContext` per call and returns
a caller-owned frame) and `ffkmp_fmt_free_output` (conditionally closes `ctx->pb` before freeing).
`test_buffers.c` covering the nine stack buffers at limit and limit plus one, and the four
size-taking copy helpers with a destination one byte short, closing B1-10. `test_rescale.c`
covering the ten macro, 128 bit and by-value-struct helpers with the D9 overflow vectors.
`test_strerror_thread.c`, closing B1-09. `test_convert.c`, the baseline for B1-23.

**Gate.**
```bash
cd ../KiteCodec/native/kitecodec-c
./scripts/verify-lift.sh HEAD          # byte equality against the def body, both digests printed
./scripts/build-host.sh plain && ./scripts/run-c-tests.sh plain
./scripts/build-host.sh asan  && ./scripts/run-c-tests.sh asan
./scripts/build-host.sh tsan  && ./scripts/run-c-tests.sh tsan
# then the full section 9 gate plus B1.1's additions, both repositories
```
The Gradle side must be untouched, so the KiteCodec and KitePlayer test counts and the klib
metadata are identical to B1.1's. State that in the log.

**Commit first line.** `Extract the FFmpeg helper layer into real C sources with their own tests`

---

#### B1.3 The lift

The one irreversible sub-phase. Everything after it assumes the new shape. Zero Kotlin source
edits.

**Files.**
- `../KiteCodec/kitecodec-core/src/nativeInterop/cinterop/ffmpeg.def`
- `../KiteCodec/kitecodec-core/build.gradle.kts`
- `../KiteCodec/buildSrc/src/main/kotlin/CompileKiteCodecCTask.kt` (new)
- `../KiteCodec/buildSrc/src/test/kotlin/CompileKiteCodecCTaskTest.kt` (new)
- `../KiteCodec/native/kitecodec-c/scripts/klib-metadata-diff.sh` (new)
- `../KiteCodec/native/kitecodec-c/klib-metadata-baseline.txt` (new)
- `../KiteCodec/.github/workflows/ci.yml`

**Steps.**
1. Def file edit, exactly this and nothing else. Delete lines 10 to 961 inclusive: the blank line,
   the `---` separator on line 11, and the 949 line body. On line 3, keep all 36 `headers =`
   entries in their exact current order and append ` kitecodec_helpers.h` at the end. On line 4,
   keep the 7 `headerFilter =` entries and append ` kitecodec_helpers.h` at the end. The helper
   header goes LAST in both, and this is not a style preference: with it first, the metadata
   differential measured 1725 lines instead of 178, because `AVBSFContext`, `AVBitStreamFilter`,
   `AVFilterPadParams` and `AVFilterParams` change which of them get complete bindings, and
   nothing in KiteCodec uses those four, so it would have compiled and passed every test while
   quietly changing the published surface. Keep all four `linkerOpts.*` lines and the `#` comment
   between them. Add `linkerOpts.ios` with the same six libav flags (B1-12). Add
   `staticLibraries = libkitecodec.a`. Do NOT add `libraryPaths`: a def-relative path resolves
   against the Gradle project directory rather than the def's directory, and the path must be per
   konan target, so it comes from Gradle.
2. Write `CompileKiteCodecCTask` in `buildSrc`, next to `BuildFFmpegTask`. It must be an
   `abstract class ... : DefaultTask()` with `DirectoryProperty`, `Property` and `ListProperty`
   inputs, an injected `ExecOperations`, and `xcrun` resolved inside `@TaskAction`. The ad-hoc
   `tasks.register { doLast { } }` shape breaks the configuration cache, which
   `gradle.properties` line 6 has on, with `Starting an external process 'xcrun ...' during
   configuration time is unsupported` among five problems. Action: for each `.c` file run konan's
   clang (`~/.konan/dependencies/llvm-21-aarch64-macos-essentials-97/bin/clang`, version 21.1.6,
   the compiler Kotlin/Native itself uses per `konan.properties` lines 129 and 788) with the
   target's triple and sysroot, flags
   `-c -O2 -std=c11 -fvisibility=hidden -fPIC -Wall -Wextra -Werror -Werror=vla`, then `llvm-ar
   crs` from the same package. Output directory keyed by `konanTarget.name` and never shared
   (B1-11). Assert the object's architecture before archiving.
3. Per-target triples and sysroots, all measured working from this macOS host: macos_arm64
   `-target arm64-apple-macos11.0 -isysroot $(xcrun --sdk macosx --show-sdk-path)`; ios_arm64
   `-target arm64-apple-ios14.0 -isysroot $(xcrun --sdk iphoneos --show-sdk-path)`; linux_x64
   `-target x86_64-unknown-linux-gnu --sysroot=~/.konan/dependencies/x86_64-unknown-linux-gnu-gcc-8.3.0-glibc-2.19-kernel-4.9-2/x86_64-unknown-linux-gnu/sysroot`;
   android_arm64 `-target aarch64-unknown-linux-android24
   --sysroot=~/.konan/dependencies/target-toolchain-2-osx-android_ndk/sysroot` (the toolchain
   package, not the sysroot package, whose `--sysroot` fails with `'stdlib.h' file not found`);
   mingw_x64 `-target x86_64-pc-windows-gnu --sysroot=~/.konan/dependencies/msys2-mingw-w64-x86_64-2`.
4. Wire it in `kitecodec-core/build.gradle.kts` inside the existing `knTargetMap.forEach` loop,
   after the `} ?: return@forEach` that ends the FFmpeg path resolution, so a target with no
   FFmpeg tree is skipped for the C compile exactly as it is skipped for the cinterop. The C
   compile needs the same per-target FFmpeg include directory the cinterop gets, because
   `kitecodec_helpers.c` includes libav headers. Modify the existing `create("ffmpeg")` block; do
   not create a second cinterop. Add `includeDirs.allHeaders(<c include dir>)`,
   `compilerOpts("-I<c include dir>")` and a second
   `extraOpts("-libraryPath", <per-target archive dir absolute>)` alongside the existing one for
   FFmpeg; two independent `-libraryPath` entries coexist. Make the cinterop task depend on the C
   compile task for the same target.
5. Do not touch `kitecodec-gradle-plugin`. The archive travels inside the klib and needs no
   link-time search path; the FFmpeg `-l` flags still need the plugin's `-L`, and removing it
   produces `ld: library 'avutil' not found`.
6. Write `klib-metadata-diff.sh`: dump the ffmpeg cinterop klib metadata, filter `knifunptr_`
   lines (they renumber for reasons with no Kotlin meaning), compare against
   `klib-metadata-baseline.txt`, and report added and removed declarations by name. Commit the
   baseline produced at B1.1's commit, then the one produced here.

**Tests it must add.** `CompileKiteCodecCTaskTest`, two cases: a correct object archives, and an
object of the wrong architecture fails with a message naming both architectures and the target.
The metadata differential script is the compatibility test.

**Gate.**
```bash
cd ../KiteCodec
./native/kitecodec-c/scripts/klib-metadata-diff.sh --check   # spelling fixed at I-09; at this
#   sub-phase the differential below was the expected outcome, read from the failing run's report
#   ACCEPT only: exactly 176 added declarations, every one carrying
#   @kotlinx/cinterop/internal/CCall.Direct(name = "_ffkmp_..."), zero removed declarations,
#   and the module name line. Count the substance, not the diff lines: the raw normal-format
#   diff is 356 lines because it prints one position marker per hunk.
./gradlew :buildSrc:test :kitecodec-core:apiCheck checkCinteropCoupling
./gradlew --configuration-cache :kitecodec-core:macosArm64Test   # expect: entry stored
./gradlew --configuration-cache :kitecodec-core:macosArm64Test   # expect: entry reused
for a in kitecodec-core/build/kitecodec-c/*/libkitecodec.a; do file "$a"; done
#   every archive must report the object format of its own target directory
./gradlew publishToMavenLocal -Pkitecodec.hostTargetsOnly=true
# then the full section 9 gate plus B1.1's additions, both repositories, plus the sample e2e
```
State in the log which targets built an archive and which were skipped for want of an FFmpeg
tree. On this machine that is one built and nine skipped, so "the C library builds for every
target" is a claim this gate cannot make and must not make.

**Commit first line.** `Compile the FFmpeg helpers as a real library instead of inline def-file text`

---

#### B1.4 One unit per subsystem, and delete what nothing uses

**Files.**
- `../KiteCodec/native/kitecodec-c/src/` split into `helpers_error.c`, `helpers_frame.c`,
  `helpers_packet.c`, `helpers_codecpar.c`, `helpers_codec.c`, `helpers_format.c`,
  `helpers_stream.c`, `helpers_filter.c`, `helpers_playback.c`
- `../KiteCodec/native/kitecodec-c/include/kitecodec_helpers.h` (add `KC_API`)
- `../KiteCodec/native/kitecodec-c/scripts/symbol-audit.sh` (new)
- `../KiteCodec/native/kitecodec-c/scripts/build-host.sh`, `tools/extract_from_def.py`,
  `scripts/verify-lift.sh` (teach them the split)
- `../KiteCodec/kitecodec-core/src/nativeInterop/cinterop/archived/` (delete, six files)
- `../KiteCodec/native/kitecodec-c/klib-metadata-baseline.txt` (regenerate)

**Steps.**
1. Split along the banner map of the def body, which is where the sections already are. Verify
   first that each of the four internal trailing-underscore helpers and its callers land in the
   same unit; if any does not, that helper becomes `KC_API` rather than being made non-static
   silently, and the log records it.
2. Define `KC_API` as `__declspec(dllexport)` on `_WIN32` and
   `__attribute__((visibility("default")))` elsewhere, and mark the 157 helpers Kotlin imports.
   The four internals stay `static`. Compile with `-fvisibility=hidden`, which governs the dynamic
   table and does not affect static linking: an unmarked helper still resolves at link time.
3. Delete the 15 dead helpers and the `archived/` directory. Closes B1-08. Then re-run the
   cross-check with the directory already gone, so its duplicate definitions cannot mask a real
   reference.
4. Write `symbol-audit.sh`: `nm -u` over the archive must resolve only to `libav*` and `libsw*`
   symbols plus a fixed allowlist (`memcpy`, `memset`, `snprintf`, `strlen`, `strerror`), and must
   contain no `printf`, no `av_log`, no `objc_msgSend`, no `dispatch_`. `nm -m` must show the 157
   as external and the four internals as private external or non-external.
5. `verify-lift.sh` can no longer byte-compare a single file, so it changes shape: it extracts
   from the def body at the pre-B1.3 revision, concatenates the split units in banner order after
   stripping their per-unit includes and `KC_API` tokens, and compares the normalised text. Print
   both digests. The 15 deletions are supplied to the script as an explicit exclusion list, so the
   comparison stays exact rather than fuzzy.

**Tests it must add.** No new C behaviour tests; the existing suite must pass unchanged, which is
the point of splitting after the harness exists. Add the symbol audit and a repository-wide
cross-check script for the 15 deletions.

**Gate.**
```bash
cd ../KiteCodec/native/kitecodec-c
./scripts/verify-lift.sh <pre-B1.3-revision>     # normalised equality, minus the 15 named
./scripts/symbol-audit.sh
./scripts/build-host.sh plain && ./scripts/run-c-tests.sh plain
./scripts/build-host.sh asan  && ./scripts/run-c-tests.sh asan
./scripts/build-host.sh tsan  && ./scripts/run-c-tests.sh tsan
cd ../KiteCodec && ./native/kitecodec-c/scripts/klib-metadata-diff.sh --check   # spelling fixed at I-09
#   ACCEPT only: exactly the 15 named declarations removed, zero added
for n in <the 15 names>; do grep -rnw "$n" . ../KitePlayer | grep -v build/ | grep -v '/\.claude/'; done
#   must print nothing but the absence of the definitions themselves
# then the full section 9 gate plus B1.1's additions
```

**Commit first line.** `Give the C library one unit per subsystem and delete the surface nothing uses`

---

#### B1.5 Fuzz every C entry point that parses a caller's string

**Files.**
- `../KiteCodec/native/kitecodec-c/fuzz/fuzz_filter_video.c`, `fuzz_filter_audio.c`,
  `fuzz_codec_option.c`, `fuzz_format_option.c`, `fuzz_metadata.c`, `fuzz_format_name.c`
- `../KiteCodec/native/kitecodec-c/fuzz/replay_main.c`
- `../KiteCodec/native/kitecodec-c/fuzz/corpus/<target>/` seeds
- `../KiteCodec/native/kitecodec-c/scripts/run-fuzz.sh`, `scripts/replay-corpus.sh`
- `../KiteCodec/.github/workflows/ci.yml` (new job on `ubuntu-24.04`)

**Steps.**
1. Six entry points, chosen because each hands caller-controlled text to a parser: the four graph
   builders through `avfilter_graph_parse_ptr` (def lines 483 and 638 in the pre-lift numbering),
   `ffkmp_codecctx_set_opt` and `ffkmp_fmt_set_opt` and `ffkmp_fmt_set_metadata` through
   `av_opt_set` and `av_dict_set`, and `ffkmp_pix_fmt_from_name` with
   `ffkmp_sample_fmt_from_name`. Each target is one `LLVMFuzzerTestOneInput` over a source file
   that also compiles against `replay_main.c`, so one body serves both drivers (B1-13).
2. Seeds: the D27 vectors (descriptions of length 0, 2047, 2048, 4096 and 1048576), valid graph
   descriptions from `FilterGraph`'s own tests, option keys and values with embedded separators
   and NUL bytes, and format names that are valid, invalid and empty.
3. Path entry points (`ffkmp_fmt_open_input`, `ffkmp_fmt_alloc_output2`, `ffkmp_fmt_io_open`) get
   no fuzz target in B1: fuzzing a path opens the filesystem, and container byte fuzzing is B8's
   remit by name. Record that boundary in the fuzz directory's README so B8 knows what it inherits.
4. The CI job runs each target for five minutes with `-fsanitize=fuzzer,address,undefined`, uploads
   any crash artifact, and fails on any finding. Locally, `replay-corpus.sh` runs every corpus file
   through the replay driver under ASan and UBSan.

**Tests it must add.** The six replay drivers, which are ordinary tests and run in every later
gate. Plus one deliberately planted defect, proved to be caught and then removed in the same
commit, so the harness is shown to have power rather than merely to be green; record the finding
text in the log.

**Gate.**
```bash
cd ../KiteCodec/native/kitecodec-c
./scripts/build-host.sh asan && ./scripts/replay-corpus.sh     # every seed, zero findings
# CI, on ubuntu-24.04: six targets, five minutes each, zero crashes, LSan enabled
# then the full section 9 gate plus B1.1's additions
```

**Commit first line.** `Fuzz every C entry point that parses a caller's string`

---

#### B1.6 The FFmpeg identity gate

The highest value clause in B1. Opaque from birth: this header includes no FFmpeg header and names
no FFmpeg type, so the surface B2 grows starts clean.

**Files.**
- `../KiteCodec/native/kitecodec-c/include/kitecodec_abi.h` (new)
- `../KiteCodec/native/kitecodec-c/src/kitecodec_abi.c` (new)
- `../KiteCodec/native/kitecodec-c/tests/test_identity.c` (new)
- `../KiteCodec/native/kitecodec-c/tests/fake_headers/` (new, the doctored shim tree)
- `../KiteCodec/kitecodec-core/src/nativeInterop/cinterop/ffmpeg.def` (headers and headerFilter
  gain `kitecodec_abi.h`, LAST, after `kitecodec_helpers.h`)
- `../KiteCodec/kitecodec-core/build.gradle.kts` (the three `-D` defines)
- `../KiteCodec/buildSrc/src/main/kotlin/CompileKiteCodecCTask.kt` (carry the defines as inputs)
- `../KiteCodec/buildSrc/src/main/kotlin/BuildFFmpegTask.kt` (the three-way ref assertion)
- `../KiteCodec/kitecodec-gradle-plugin/src/main/kotlin/.../KiteCodecPlugin.kt` and
  `KiteCodecExtension.kt` (version validation and the configuration-time major check)
- `../KiteCodec/kitecodec-core/src/commonMain/kotlin/.../FFmpeg.kt`, `Errors.kt`
- `../KiteCodec/kitecodec-core/src/nativeMain/kotlin/.../FFmpeg.native.kt`, `Internals.kt`,
  and the 15 entry points listed in step 4
- `kiteplayer-ffmpeg/src/nativeMain/kotlin/.../KiteCodecMediaBackend.kt`, `Probe.kt`

**Steps.**
1. `kitecodec_abi.h` declares `KITECODEC_C_ABI_MAJOR 1`, `KITECODEC_C_ABI_MINOR 0`, a flat POD
   report and three functions: `int kc_init(void)`, `void kc_ffmpeg_report_get(kc_ffmpeg_report *)`
   and `uint32_t kc_abi_version(void)`. The report is plain data with fixed char arrays and no
   pointers, because cinterop binds our own struct with real offsets and Kotlin reads it with one
   `nativeHeap.alloc` and plain field reads. No two-dimensional arrays anywhere in it: cinterop
   flattens `char names[6][16]` into a single byte array, so `names[i]` is byte `i`, not row `i`.
   Use one accessor returning `const char *` per index instead.
   Report contents: per library, name, header major, minor and micro, runtime major, minor and
   micro, and a verdict; plus the six-way `*_configuration()` agreement flag and the names of any
   that disagreed; plus the build FFmpeg ref, `av_version_info()`, `avutil_license()`, the licence
   flavour the artifact was built for (B1-21), and the resolved provisioning directory.
2. `kitecodec_abi.c` reads the six `LIB*_VERSION_INT` macros. It is compiled in the same task,
   against the same include tree, as every helper unit, so the frozen expectations and the baked
   offsets cannot diverge: if the compiler saw the offsets, it saw the macros. This is the only
   construction that is correct by definition; nothing can recover the header version afterwards.
3. Policy, decided, not to be reopened. Major must be exactly equal: hard reject, no override,
   because 38 field offsets were measured to move across a major and FFmpeg's own
   `doc/developer.texi` permits reordering struct contents at a major bump. Runtime minor must be
   at or above header minor: reject when lower, because FFmpeg guarantees backward compatibility
   only. Micro is compared and reported and never rejects. The six `*_configuration()` strings
   must agree with each other, and disagreement is a reject, because that is a mixed install and
   version numbers alone cannot see it. Guard `kc_init` with `pthread_once`; a function-local
   static in a `static inline` function would give one flag per translation unit, which is why the
   gate needs external linkage and why a header-only C library would not do.
4. Call `kc_init` first in every entry point, before any allocation: `MediaSource.open`
   (`MediaSource.native.kt:464`), `MediaSink.open` (`MediaSink.native.kt:310`),
   `FilterGraph.buildVideo`, `buildAudio`, `buildVideoMulti`, `buildAudioMulti`
   (`FilterGraph.native.kt:237`, `:268`, `:300`, `:332`), `Frame.ofVideo` and `Frame.ofAudio`
   (`Frame.native.kt:285`, `:316`), `Transcoder.transcode` (`Transcoder.native.kt:8`),
   `Remuxer.remux` (`Remuxer.native.kt:8`), and the five `FFmpeg` queries. Not a Kotlin `object`
   `init` block: Kotlin/Native object initialisation is per-thread-reachability and gives no
   ordering guarantee relative to a different entry point on a different thread.
5. Kotlin side: one new `FFmpegError` subclass carrying the report, distinct from
   `FFmpegError.Internal` so a consumer can catch it and it can never be confused with a media
   error. `FFmpeg.versions` gains the header-side numbers so a diagnostic prints both columns.
   `Errors.kt`'s existing `fromCode` and its `AVERROR_*` tag algebra stay untouched: B1 does not
   introduce a C-side error category, so there is no duplicate classification to reconcile. That
   reconciliation belongs with B2's error record.
6. Build side: validate `ffmpeg.version` (B1-03), assert the three `n8.0` sites agree and, on the
   vendored path, agree with the checkout (B1-04), and add a configuration-time major check for
   `FFmpegSource.System` using the library directory `KiteCodecPlugin.kt` line 195 already
   resolves, read through `pkg-config --modversion` which answers correctly for all six here. The
   klib must publish its expected majors where the plugin can read them; a properties resource
   beside the klib is sufficient and is the decided form.
7. KitePlayer: surface a rejection as a typed `PlaybackError` through `KiteCodecMediaBackend` and
   `Probe`. This is new code, not changed code, and it is the only KitePlayer work in B1.6.

**Tests it must add.** `test_identity.c`, hermetic, no second FFmpeg install: compile
`kitecodec_abi.c` a second time into a test-only object against `tests/fake_headers/`, a shim
include tree carrying doctored `LIB*_VERSION_*` macros, and assert one case per verdict. Case 1,
avutil header major 59 against runtime 60: report status negative, verdict major mismatch, both
numbers present, provisioning sentence non-empty. Case 2, header minor above runtime minor: verdict
runtime older, reject. Case 3, header micro above runtime micro: verdict micro older, status
accepting. Case 4, the true build: status 0 and all six verdicts ok. Case 5, a doctored
configuration string on one library: reject naming that library. Plus a Kotlin native test
asserting the typed error surfaces from a KiteCodec entry point, a Gradle plugin functional test
for the version validation, a buildSrc test for the three-way assertion, and a KitePlayer test
asserting the rejection reaches `PlaybackError`. A gate that has never fired is level 8 evidence;
these make it level 2.

**Gate.**
```bash
cd ../KiteCodec/native/kitecodec-c
./scripts/build-host.sh asan && ./scripts/run-c-tests.sh asan    # includes test_identity
cd ../KiteCodec && ./gradlew :kitecodec-core:macosArm64Test :buildSrc:test \
  :kitecodec-gradle-plugin:test :kitecodec-core:apiCheck checkCinteropCoupling
./native/kitecodec-c/scripts/klib-metadata-diff.sh --check   # spelling fixed at I-09
#   ACCEPT only: the kc_* declarations of kitecodec_abi.h added, zero removed
./gradlew publishToMavenLocal -Pkitecodec.hostTargetsOnly=true
# then the full section 9 gate plus B1.1's additions, both repositories
```
Note: `apiCheck` will now fail until the new error subclass is dumped. Regenerate with `apiDump`
and commit the dump in the same commit, and state in the log exactly which public declarations
were added. This is the only sub-phase in B1 that legitimately changes KiteCodec's public API.

**Commit first lines.** KiteCodec: `Reject an FFmpeg runtime that does not match the headers we compiled against`.
KitePlayer: `Report an incompatible FFmpeg runtime as a playback error`.

---

#### B1.7 The lock-free C audio ring, proved but not yet shipped

The C ring is built, tested and proved against the Kotlin ring here, and it is not put on the
device path. The shipped real-time path changes exactly once, in B1.8. This is deliberate: a
middle state in which C holds the samples while Kotlin still drives the callback is worse than
either endpoint and would change the real-time path twice.

**Files.**
- `kiteplayer-rt/` (new module): `native/include/kite_rt.h`, `native/src/kite_rt_ring.c`,
  `native/tests/*.c`, `native/scripts/build-host.sh`, `native/scripts/run-c-tests.sh`,
  `native/tests/interpose_alloc.c`, `src/nativeInterop/cinterop/kitert.def`, `build.gradle.kts`
- `buildSrc/src/main/kotlin/CompileKiteRtTask.kt` (new; the same shape as KiteCodec's, and the
  two must not be shared across repositories)
- `kiteplayer-core/src/commonMain/kotlin/.../internal/AudioRingHandle.kt` (new interface)
- `kiteplayer-core/src/commonMain/kotlin/.../internal/AudioRing.kt` (renamed to
  `KotlinAudioRing`, implementing the interface, plus the B1-18 arithmetic fix)
- `kiteplayer-core/src/commonMain/kotlin/.../AudioPlayback.kt` (talks to the interface; public
  surface unchanged)
- `kiteplayer-core/src/nativeMain/kotlin/.../internal/NativeAudioRing.kt` (new)
- `kiteplayer-core/src/commonTest/kotlin/.../AudioRingTest.kt` (retarget to `KotlinAudioRing`,
  add the B1-20 KDoc)
- `kiteplayer-core/src/nativeTest/kotlin/.../AudioRingDifferentialTest.kt` (new)
- `settings.gradle.kts`

**Steps.**
1. The library is `kiteplayer-rt`, in KitePlayer, with symbol prefix `kprt_`. It does not belong
   in `kitecodec-c`: a lock-free audio ring has nothing to do with FFmpeg, and putting it there
   would make KitePlayer's real-time core a transitive consequence of a codec dependency.
2. One allocation at create, nothing afterwards: the sample block 64 byte aligned, every contended
   counter padded onto its own cache line, and the timestamp segment ring sized at create. The
   only function that allocates is `kprt_ring_create`, and `kprt_ring_destroy` is the only one that
   frees.
3. Ordering rules, stated because the Kotlin ring gets them right by accident of its two counters
   and C must state them: in commit, fill the segment slot's `start_frame` and `pts_us`, then its
   own sequence with a release store, then store `written` with release. The release on `written`
   publishes both the samples and the segment, so a callback that sees the new `written` is
   guaranteed to see the segment that dates it.
4. Invert the seqlock (B1-16). The real-time thread is the anchor writer and never waits: two
   sequence stores with three field stores and two release fences between them. The non-real-time
   reader retries a bounded 64 times and then keeps its previous reading. Segment resolution walks
   live slots newest first, validating each by its own sequence, and on failure dates from a
   consumer-private cache while incrementing `segment_giveups`, so the give-up is visible rather
   than silent. Publish nothing, and let the clock read null, only when the cache is empty too.
5. The writer API is reservation shaped: `kprt_ring_begin_write` grants a window of one or two
   spans, the caller writes its floats straight into ring storage, and `kprt_ring_commit_write`
   publishes and opens a timestamp segment only when the pts disagrees with continuity by at least
   the tolerance, returning a distinct status and publishing nothing when a segment was needed and
   all live slots still date unplayed audio, so the caller retries exactly as `AudioPlayback.submit`
   does today. This removes one full copy of every sample and the per-call `Pinned` object that
   `usePinned` allocates.
6. Fix B1-18 in both rings, so the oracle compares two correct implementations.
7. The seam: extract `internal interface AudioRingHandle` in `commonMain` with exactly the members
   `AudioPlayback` already uses, and no `render`. Do not make `AudioRing` an `expect class`: that
   deletes the portable implementation from native, which destroys the only oracle the C code can
   be checked against, and js and wasmJs can never contain C. `AudioPlayback`'s public surface
   does not change.
8. Do not wire the C ring into any sink in this sub-phase. `NativeAudioRing` exists, is
   constructed by tests only, and the production path still uses `KotlinAudioRing`.

**Tests it must add.** `AudioRingDifferentialTest`, native only, the load-bearing test of this
sub-phase: one scripted sequence of writes, partial reads, silence tails, discontinuities and
flushes driven through both rings at 44.1, 48 and 96 kHz, asserting the produced samples match bit
for bit, the published anchor matches to the microsecond, and the underrun count matches exactly.
Plus the C suite: a producer and consumer thread pair under TSan; the allocation test of 15.3; the
bounded-reader test of B1-16; the exact-zero silence test of B1-19; the overflow case of B1-18.
Plus the 16 existing `AudioRingTest` tests, unchanged in behaviour against `KotlinAudioRing`.

**Gate.**
```bash
cd kiteplayer-rt/native
./scripts/build-host.sh plain && ./scripts/run-c-tests.sh plain
./scripts/build-host.sh asan  && ./scripts/run-c-tests.sh asan
./scripts/build-host.sh tsan  && ./scripts/run-c-tests.sh tsan
./scripts/run-c-tests.sh interpose        # zero malloc/calloc/realloc/free/mmap between
                                          # kprt_ring_create returning and kprt_ring_destroy
cd .. && ./gradlew :kiteplayer-core:macosArm64Test :kiteplayer-core:jvmTest \
                   :kiteplayer-rt:macosArm64Test
./gradlew :kiteplayer-core:compileKotlinJs :kiteplayer-core:compileKotlinWasmJs
# then the full section 9 gate plus B1.1's additions
```

**Commit first line.** `Give the audio ring a lock-free C implementation and prove it against the Kotlin one`

---

#### B1.8 The pure C device callback

**Files.**
- `kiteplayer-rt/native/src/kite_rt_coreaudio.c` (new), `native/include/kite_rt.h`
- `kiteplayer-rt/native/scripts/render-audit.sh` (new)
- `kiteplayer-output/src/appleMain/kotlin/.../CoreAudioSink.kt`
- `kiteplayer-core/src/commonMain/kotlin/.../spi/AudioSink.kt`
- `kiteplayer-core/src/nativeMain/kotlin/.../spi/NativeRingAudioSink.kt` (new)
- `kiteplayer-core/src/commonMain/kotlin/.../AudioPlayback.kt` and the `openAudioPath`
  expect declaration, with one `actual` per existing source set, following the module's current
  expect and actual layout rather than inventing a new one
- `kiteplayer-output/src/appleTest/kotlin/.../CoreAudioSinkRealTimeTest.kt`,
  `CoreAudioSinkTest.kt`
- `kiteplayer-core/src/commonTest/kotlin/.../ScriptedBackend.kt`, `AudioPlaybackTest.kt`
- `kiteplayer-ffmpeg/src/nativeTest/kotlin/.../ReferencePcmTest.kt`

**Steps.**
1. `kprt_render_cb` is a `static` C function, never exported and never reachable from Kotlin,
   installed by `kprt_sink_create`. It casts `ref` to a plain struct pointer with no `StableRef`
   and no reference counting; reads `sink->ring`, and when it is NULL (teardown) zeroes the whole
   buffer and returns; computes the buffer duration from a precomputed rational; takes the host
   time from the timestamp when the host-time-valid flag is set and otherwise from
   `AudioGetCurrentHostTime` while incrementing `estimated_anchors`; converts host ticks with a
   `mach_timebase_info` cached at create rather than calling `AudioConvertHostTimeToNanos` inside
   the callback; calls `kprt_ring_render` straight into the device buffer; stores
   `last_deadline_nanos`; updates `worst_callback_nanos` from a `mach_absolute_time` pair around
   the body; increments `callbacks`; returns success.
2. The device glue moves to C too, so no Kotlin ever touches an `AudioUnit`: create, negotiate,
   set the stream format, install the callback, initialise, start, stop, set paused, destroy.
   Every failure path disposes what it created, which is D23's transactional-open rule moved into
   C. `kprt_sink_destroy` stops, uninitialises, disposes, and only then clears `sink->ring`, so
   the callback is provably out before `kprt_ring_flush`'s precondition is claimed.
3. `CoreAudioSink` becomes a thin owner of the two handles. It keeps `retainedResources()` so the
   existing "a failed open hands back everything it created" test still has something to assert,
   and it keeps `estimatedAnchors` and the other counters by reading them from one stats call.
4. Add `internal interface NativeRingAudioSink : AudioSink` in `nativeMain` with one member that
   opens against a ring rather than against an `AudioRenderCallback`. The choice is made by the
   sink, not by the platform, because the sink owns the device callback: a native sink with no C
   callback simply does not implement it and keeps working with `KotlinAudioRing`. This is what
   makes it a capability rather than a fork.
5. `AudioPlayback.open` delegates ring creation to one `internal expect` function whose native
   `actual` branches on the sink implementing `NativeRingAudioSink` and whose every other `actual`
   returns the Kotlin ring wired through the existing lambda. One expect function and one
   native-only interface: every line of policy, backpressure, clock anchoring and flush ordering
   stays in `commonMain` where it is.
6. Collapse silence and underrun counting per B1-19 and update the KDoc that calls the duplication
   deliberate.
7. Retarget the six test files that pin the Kotlin callback contract. `AudioRenderCallback` stays,
   because non-device sinks still use it, so those tests keep a real subject.

**Tests it must add**, in this order of authority, which is the order the gate runs them.
1. A symbol and instruction audit of the render translation unit, deterministic, no runtime, the
   strongest evidence available for this claim. `nm -u` on the object must yield nothing outside a
   fixed allowlist of `_memcpy` and `_memset`, and `objdump -d` over `kprt_ring_render` and
   `kprt_render_cb` must contain no call to `malloc`, `calloc`, `realloc`, `free`, `objc_msgSend`,
   `objc_retain`, `objc_release`, `pthread_mutex_lock`, `os_unfair_lock_lock`, `dispatch_`,
   `printf`, `os_log`, `AudioConvertHostTimeToNanos`, or any symbol beginning `Kotlin_` or
   `kfun:`. Build the unit without `-fno-builtin-memcpy` and `-fno-builtin-memset` so the
   allowlisted calls stay visible, and close the stack-allocation loophole with `-Werror=vla` plus
   a grep for `alloca`, because a variable length array is an allocation no symbol list shows.
2. A C test with the allocator interposed: five million synthetic callbacks of pseudo-random frame
   counts against a real feeder thread, asserting zero `malloc`, `calloc`, `realloc`, `free` and
   `mmap` between create and destroy, samples out equal to samples in, the underrun count equal to
   the deliberately induced starvations, and `segment_giveups` zero. Repeated under ASan, UBSan and
   TSan. TSan earns its keep here: a seqlock written with `volatile` instead of C11 atomics is a
   real race and TSan will say so, which is the reason to write it with atomics and explicit
   fences.
3. A GC-pressure differential on Kotlin/Native, which is what B1's exit line actually promises:
   open a real device through the C sink, play real media for 10 minutes, and simultaneously run a
   Kotlin thread allocating hard with `GC.autotune = false` and `GC.targetHeapBytes` pinned at 1
   MiB so collections happen hundreds of times a second. Assert from the stats call that underruns
   are zero, `segment_giveups` is zero, and `worst_callback_nanos` is under half the device period,
   which is 5,333,333 ns at 512 frames and 48 kHz per B10's budget. Then run the identical test
   against a test-only sink whose callback deliberately enters Kotlin, and require that version to
   fail. A test that cannot fail proves nothing.
4. A Kotlin heap drift check as corroboration only, presented as level 5 and never as the gate:
   force a collection, record total object bytes, run 10 minutes of callbacks, force another, and
   assert the heap did not grow. It cannot attribute growth to the callback rather than to anything
   else alive in the process.

Refused as evidence, and the log must say so: a malloc interposer as proof about Kotlin
allocation, because Kotlin/Native takes pages by `mmap` and hands objects out of them, so an
interposer read 229 mallocs before and 230 after one million Kotlin object allocations and would
read zero on a callback allocating millions of objects. Sampling, because the callback runs about
94 times a second for tens of microseconds and a clean profile would be evidence of nothing being
sampled presented as evidence of nothing happening. And any claim that the current release-mode
callback already allocates zero on the strength of escape analysis.

**Gate.**
```bash
cd kiteplayer-rt/native
./scripts/render-audit.sh                       # assertion 1, must pass
./scripts/run-c-tests.sh interpose              # assertion 2
./scripts/build-host.sh tsan && ./scripts/run-c-tests.sh tsan
cd .. && ./gradlew :kiteplayer-output:macosArm64Test :kiteplayer-core:macosArm64Test \
                   :kiteplayer-core:jvmTest :kiteplayer-ffmpeg:macosArm64Test \
                   :kiteplayer-rt:macosArm64Test
# assertion 3: the 10 minute device run plus the negative control, run by hand, numbers recorded
# assertion 4: the heap drift check, recorded as corroboration
# then the full section 9 gate plus B1.1's additions
```

**Commit first line.** `Take the real-time audio callback out of managed Kotlin`

---

#### B1.9 Close-out: the words, the evidence and the log

**Files.**
- `../KiteCodec/README.md`, `../KiteCodec/CHANGELOG.md`,
  `../KiteCodec/native/kitecodec-c/README.md`
- `README.md`, `kiteplayer-rt/README.md`
- `../KiteCodec/native/kitecodec-c/coupling-baseline.txt` (re-record, lower or equal)
- `api/` dumps for every KitePlayer module whose surface moved
- `KPKMP.md` section 14 (the log entries) and section 6 (documentation truth register)

**Steps.**
1. Re-record the coupling baseline at its new, lower values and state the deltas.
2. Write the deferral record: what is deferred, to which item, and what breaks if it never
   happens, copied from 15.5 so the log stands alone.
3. State B1-20 in three places: the KitePlayer README, `AudioRingTest`'s class KDoc, and the log.
   On macOS the 16 ring tests no longer cover the shipped path; the C suite and the differential
   oracle do.
4. Tier table: no promotion. B1 adds no playback capability. macOS arm64 stays an experimental
   T3-Full candidate on one development machine and everything else stays T1. Say which of the ten
   targets built a C archive and which did not.
5. `kitecodec-c/README.md` records the build commands, the sanitizer variants, the fuzz corpus,
   the three instruments and what each one can and cannot prove, and the four platform limits
   measured here: no libFuzzer, no LeakSanitizer, no cmake, one FFmpeg tree.

**Tests it must add.** None. This sub-phase adds words and evidence only.

**Gate.** The full section 9 gate plus B1.1's additions, both repositories, rerun for real with
`--rerun-tasks`, plus every C suite and every sanitizer variant, plus the fuzz corpus replay, plus
the fresh `apiDump` diff in both repositories. Record the measured test counts.

**Commit first lines.** KiteCodec: `State what the C layer proves and what it does not`.
KitePlayer: `Say plainly which audio ring the shipped path uses`.

### 15.3 The C testing, fuzzing and sanitizer strategy, concretely

**Compilers and tools, all measured present on this machine unless noted.**

| Purpose | Tool | Notes |
|---|---|---|
| Host test binaries | `/usr/bin/clang`, Apple clang 17.0.0 | Sanitizers ride on this |
| Shipped per-target archives | `~/.konan/dependencies/llvm-21-aarch64-macos-essentials-97/bin/clang`, 21.1.6 | The compiler Kotlin/Native itself uses |
| Archiving | `llvm-ar` from the same konan package | The package has no `llvm-nm`, `llvm-strip` or `llvm-objcopy` |
| Symbol inspection, Mach-O | `/usr/bin/nm`, `/usr/bin/objdump` | |
| Symbol inspection, ELF | `aarch64-linux-android-nm` and `-readelf` from the Android toolchain package | These are macOS-native binaries; the Linux gcc package's own binaries are Linux ELF and cannot run here |
| Apple sysroots | `xcrun --sdk macosx` and `--sdk iphoneos` | Resolved inside `@TaskAction`, never at configuration time |
| FFmpeg flags | `pkg-config`, or `KC_FFMPEG_PREFIX` | Answers correctly for all six libraries here |
| Build driver | a shell script, plus a Gradle task | Not make, not cmake, not ninja: B1-15 |

**Sanitizer matrix.** Three build variants, because ASan and TSan are mutually exclusive.

- `plain`: `-O2 -Wall -Wextra -Werror -Werror=vla`. Catches nothing at runtime; it is the
  compile-fidelity and correctness variant.
- `asan`: `-fsanitize=address,undefined -fno-omit-frame-pointer -g -O1`. Both build and run were
  verified on this machine. This is the variant that catches the class the identity gate prevents:
  reconnaissance reproduced a stale-header helper reading four bytes 36 bytes past a 416 byte
  region and ASan named it exactly.
- `tsan`: `-fsanitize=thread -g -O1`. Verified building and running here. This is the variant that
  keeps the seqlocks honest.
- LeakSanitizer is not available here (B1-14). Leak evidence comes from the interposer locally and
  from LSan in the Linux CI job.

**The allocation interposer.** One file, `tests/interpose_alloc.c`, using the Mach-O
`__DATA,__interpose` section to count `malloc`, `calloc`, `realloc`, `free`, `mmap` and `munmap`.
Naive `DYLD_INSERT_LIBRARIES` plus symbol shadowing silently counts zero because of the two-level
namespace, which is a trap worth knowing rather than rediscovering. The interposer is exactly
correct for C allocation, which does go through these functions, and exactly wrong for Kotlin
allocation, which does not; 15.2 B1.8 records that refusal.

**C test suites and what each targets.** Every suite is table driven, prints one line per case, and
returns non-zero on the first failure.

The S1.a.7 tree has seven suites and 274 cases per variant, 822 across plain, ASan and TSan.
`test_ownership.c` has 41 cases over 39 ownership helpers: B1.6's historical 39-case suite gained
the NULL option-key and out-of-range stream-index guard cases at I-12. `test_args.c` adds 22.

| Suite | Targets | What it asserts |
|---|---|---|
| `test_ownership.c` | the 39 ownership helpers | exact alloc and free pairing under the interposer, including the parent-owned result of `ffkmp_fmt_new_stream`, the per-call `SwsContext` in `ffkmp_frame_convert_pixfmt`, and the conditional `ctx->pb` close in `ffkmp_fmt_free_output` |
| `test_buffers.c` | 9 stack buffers, 4 size-taking copy helpers | limit and limit plus one for each buffer; a destination one byte short for each copy helper; no write past the declared size, under ASan |
| `test_rescale.c` | the 10 macro, 128 bit and by-value helpers | exact results at the D9 overflow vectors; `AV_CEIL_RSHIFT` plane heights for subsampled formats |
| `test_strerror_thread.c` | `ffkmp_strerror` | two threads, interleaved calls, each sees only its own message |
| `test_convert.c` | `ffkmp_frame_convert_pixfmt` | correct conversion and leak-free operation, as B2's caching baseline |
| `test_identity.c` | `kc_init`, the report | five verdict paths against doctored header shim trees, plus the report, accessor and bypass contracts |
| `test_args.c` | 16 required-argument guards, 6 nullable contracts | every old invalid call is refused with `AVERROR(EINVAL)`; NULL retains its six intentional meanings, including both audio graph builders in one case |
| `kiteplayer-rt` suites | the ring and the callback | the four assertions of B1.8, plus the bounded-reader, exact-silence and overflow cases |

**Fuzzing.** Six targets, one per string entry point, listed in B1.5. One source per target
compiles two ways: `LLVMFuzzerTestOneInput` with `-fsanitize=fuzzer,address,undefined` in the
`ubuntu-24.04` CI job, five minutes per target, zero findings to pass; and a corpus replay `main()`
compiled everywhere with ASan and UBSan, which is what runs on this machine and in every later
gate. Corpus is committed, small and textual. Container byte fuzzing and path entry points are
B8's, by its own wording, and the fuzz directory's README says so.

**The audit scripts, which are tests and not documentation.**
- `symbol-audit.sh` over the helper archive: undefined symbols resolve only to `libav*`, `libsw*`
  and its measured allowlist; exports are exactly 169 `ffkmp_` helpers, comprising 157 legacy
  helpers Kotlin consumes plus twelve compatible S1.a.7 additions, and six `kc_` identity functions.
- `render-audit.sh` over the render translation unit: the allowlist and forbidden-call scan of
  B1.8, plus `-Werror=vla` and the `alloca` grep.
- `klib-metadata-diff.sh` over the cinterop klib: the compatibility instrument for the cinterop
  surface, which `apiCheck` does not cover because the cinterop klib is a separate artifact from
  `kitecodec-core`'s own klib.

**How each claim is graded**, against section 2. The metadata differential, the doctored-macro
identity test, the differential ring oracle, the interposer counts and the sanitizer runs are level
2: deterministic differentials, oracles and sanitizer results on the exact contract. The render
audit is stronger than a runtime test for what it covers and is still level 2, because it is a
deterministic property check rather than a device measurement. The 10 minute device run is level 6,
a manual observation with saved metrics, corrected at the interlude (I-16) from the "level 1" that
stood here: one operator and a debug binary do not meet section 2's level 1 wording, and its
authority rests on the two level 2 assertions beside it. It is not release-mode
qualification and B10 owns that. Compilation of the per-target archives is level 7 and says nothing
about behaviour. Any statement that the C library works on a target whose archive was never built
is level 8 and is banned.

### 15.4 Rollback, per irreversible step

B1 has exactly one structurally irreversible sub-phase and four steps whose reversal costs more
than a revert. Everything else is a plain `git revert` of one commit in one repository.

**B1.3, the lift.** The only sub-phase after which the tree cannot be understood without the new
shape. Rollback: revert the single commit. The 949 line def body is in git at the parent commit,
and `verify-lift.sh` re-derives the extracted sources from it, so the two representations can be
proved equal in either direction at any time. Nothing else in either repository changed in that
commit, and the Kotlin side is untouched, so a revert restores a tree that was green minutes
earlier. Cost: one revert plus one gate run. The precondition that makes this cheap is B1.2
landing first: the C sources and their tests already exist and are proved faithful before the def
is edited, so the risky commit contains only build wiring.

  *Withdrawn at the interlude (I-12).* The paragraph above was true while `verify-lift.sh` and
  the extractor existed; both are retired, because their anchor was a fixed point no future
  revision could replace and the byte equality proof was blocking real fixes to exported code,
  including two reproduced crashes. Rollback to a pre-lift state therefore stops being an
  available operation, and nothing replaces it in kind: the lift's faithfulness is a recorded
  historical fact (the final run at `2b4287f`, all eleven comparisons matching, is pasted in the
  I.3 Execution log entry), and from the interlude onward the nine units are ordinary maintained
  sources whose rollback story is the ordinary revert of whichever commit changed a unit.

**B1.4, deleting the 15 helpers and the `archived/` directory.** Reversible by revert, and the
deletion is safe for a reason rather than by luck: nothing has ever been published from KiteCodec
(`CHANGELOG.md` Unreleased, no git tags), and zero KitePlayer files import from the cinterop
package. If a later phase discovers a need for one of the 15, restoring it is a three line change
plus a baseline update, not a redesign. Do not perform this deletion in the same commit as the
lift, so each can be reverted alone.

**B1.6, the entry-point gate.** `kc_init` at the top of 15 entry points changes the failure
behaviour of every public API in KiteCodec, and a false rejection would make the library refuse to
start against a runtime that is actually fine. Two mitigations, both required. First, the policy
has exactly one hard-reject condition set and it is the one measured to matter: major inequality,
runtime minor below header minor, and configuration disagreement. Micro never rejects. Second, the
gate must be provably bypassable for diagnosis: an environment variable that downgrades the
rejection to a warning printed once, which the negative test asserts does not exist as a silent
default and does exist when set. That escape hatch is what keeps a false positive from being an
outage; it is not a supported configuration and the report says so when it is used.

**B1.7 and B1.8, the audio path.** The irreversible-feeling part is the callback move, and the
split is what makes it revertible. After B1.7 the C ring exists and is proved and the production
path is unchanged, so B1.7 is revertible by revert with no behavioural consequence. B1.8 flips the
device path in one commit; reverting it returns to a Kotlin callback over a Kotlin ring that the
same gate proved green one sub-phase earlier. Keep `KotlinAudioRing` and `AudioRenderCallback`
alive permanently: they are the js and wasmJs implementation, the differential oracle, and the
rollback target, and B1-20 says they must not be presented as covering the shipped path.

**B1.1, the ABI baseline.** Not irreversible, but note the direction: once `apiCheck` is in the
gate, an accidental public API change fails a gate instead of shipping. If a legitimate change is
needed, `apiDump` plus a log entry naming the added or removed declarations is the procedure, and
B1.6 is the only sub-phase in B1 expected to use it.

**What has no rollback, and must therefore not be attempted in B1.** Publishing anything. B1 must
not publish to Maven Central, must not tag, and must not change `GROUP` or any coordinate. The
moment an artifact is public, the ABI decisions in 15.0 stop being free, and the whole argument for
deferring the opaque migration depends on them staying free until B7 decides.

### 15.5 What is deferred, to which item, and what breaks if it never happens

**Deferral 1, opaque handles across the 176 legacy helpers, and removing the FFmpeg headers from
the def.** To B2 for the signatures, because B2 redesigns them anyway for typed send and receive
outcomes, pooled plane views with negative strides, the full channel layout and the side-data
model, and doing an opaque rename in B1 and a semantic redesign in B2 pays twice. To B7 for the
completion deadline, because the Android AAR over the JNI bridge is the first consumer that
genuinely benefits. Held in place meanwhile by the ratchet of B1-06. When it happens it happens
inside the one existing `ffmpeg` cinterop module, family by family, and never in a second module.
*If it never happens:* the published klib keeps the FFmpeg struct layout classes; a future Kotlin
author can write `frame.pointed.sample_rate` and reintroduce a coupling the gate does not cover
(today that is zero call sites, measured, so this is regression prevention and not a fix); and no
non-Kotlin consumer has a supported API, which means B7's AAR carries the FFmpeg include tree into
its NDK build. *What does not break:* correctness against a mismatched runtime, which the gate
covers, and the practical layout coupling, which is zero today.

**Closed by S1.a.8.** The existing cinterop parses no FFmpeg header, all 140 FFmpeg-typed helper
declarations use the eleven opaque aliases, the six Kotlin consumers cross only the `kc_*` and
`ffkmp_*` boundary, and the source-level direct-coupling ceilings are both zero. The private
forward tags behind the aliases remain implementation identities rather than Kotlin source names.

**Deferral 2, explicit ownership annotations in the compiler-attribute sense.** Delivered instead
as documented ownership contracts in the header plus exact pairing tests over all 29 ownership
helpers under the interposer. `__attribute__((ownership_returns))` is honoured only by clang's
static analyzer and not by the compiler proper, so the attribute is level 8 evidence and the test
is level 2. *If it never happens:* nothing. The tests carry the guarantee, and this should not be
restored later.

**Deferral 3, an error record replacing `ffkmp_strerror` and the Kotlin `AVERROR_*` tag algebra.**
To B2, with the signatures. B1 documents and tests the thread affinity instead (B1-09). *If it
never happens:* `Errors.kt` keeps reimplementing FFmpeg's tag arithmetic in Kotlin, which is
duplicated knowledge that can drift from the headers, and every error message crosses a
thread-affine pointer.

**Deferral 4, exhaustive fuzzing of every C entry point that accepts bytes.** B8's stated remit.
B1 delivers the harness, the replay driver, the committed corpus and six targets over the
parser-reaching string entry points. *If it never happens:* the demuxer and decoder byte paths stay
unfuzzed, which is a real security gap and is B8's gap; B1's contribution is the infrastructure
without which B8 cannot start.

**Deferral 5, coverage-guided fuzzing on this machine.** Impossible here (B1-13). True fuzzing runs
in the Linux CI job; locally the corpus replays as a sanitized regression. *If it never happens
locally:* a new crash is found one CI cycle later instead of immediately. Installing Homebrew LLVM
fixes it and is not a prerequisite.

**Deferral 6, the six speculative handle families** (`kc_swr`, `kc_sws`, `kc_hwdevice`,
`kc_hwframes`, `kc_io`, `kc_cancel`). To B2 for swresample, swscale caching and the interruptible
open request, and to B5 for the hardware families. Prototyped and proved workable during design,
including a pure C interrupt callback returning `AVERROR_EXIT` from a pre-cancelled open with no
Kotlin function pointer anywhere, so B2 inherits a proof rather than a guess. *If it never
happens:* nothing regresses; B2 simply cannot be finished, since these are its named contents.

**Deferral 7, per-target verification of the C library.** Nine of the ten registered targets have
no FFmpeg tree on this machine, so the real library can be compiled only for macOS arm64 here. To
B7 and B9. *If it never happens:* the C layer's cross-target claims stay at level 7 or level 8
forever, which the README must keep saying.

**Refused permanently, not deferred.** Making `AudioRing` an `expect class`. js and wasmJs cannot
contain C; 16 `commonTest` tests and the whole A5 virtual-time simulation campaign drive the Kotlin
ring; and deleting the portable implementation on native destroys the only oracle the C ring can be
checked against.

**Pre-existing gaps B1 declines to fix, recorded so silence is not mistaken for completion.** The
licence flavour contradiction (B1-21), which B1 makes visible and B7 must resolve. The absence of
KitePlayer CI (B1-24). The def's missing iOS link flags, added in B1.3 but unverifiable here
(B1-12). And the CI comment claiming the Linux job proves the lavc 6.x compat path: it proves 6.1
and newer, because the def body's real compilation floor is lavc 60.30.100 and lavu 58.7.100, not a
major boundary, and FFmpeg 6.0 fails to compile it with four errors.

### 15.6 The three blocking questions, decided

Answered by the orchestrator on 2026-08-09, before B1.1 started. All three are settled; do not
reopen them. B1.3 is unblocked.

1. **Is JNI, and therefore B7's Android AAR, actually coming? Yes, and the deferral's deadline is
   B7.** This is not a preference, it is the product's founding motivation: the owner built this
   because wiring a native player into a Kotlin Multiplatform application by hand was the pain
   worth removing, and that application targets Android and iOS. B7 already commits to "Android
   AAR over the JNI bridge" and B9 makes Android API 24 or newer a mandatory T5 platform, so
   Kotlin/JVM must reach this C layer, and `androidNative` klibs cannot serve a normal Android
   application. The consequence for 15.5's Deferral 1 is therefore concrete rather than open:
   the opaque handle migration is owed to B7, it is scheduled rather than hypothetical, and every
   C signature written from B1.2 onward is designed so that a later opaque wrapper can be added
   over it without changing the implementation body.
2. **Is a one-machine, hand-supervised device gate acceptable for B1.8? Yes, and its evidence
   ceiling is recorded rather than glossed.** Building continuous integration first would block
   the foundation on infrastructure, and infrastructure is not what makes the audio callback
   correct. So B1.8 proceeds with the supervised run, and its Execution log entry must state, in
   the same paragraph as its numbers, that the evidence is one machine, one debug binary, one
   operator, which is level 2 on a single platform under section 2 and is not the release-mode
   qualification B10 will demand. B1-24 stays in the register but is reassigned: continuous
   integration belongs to **B8**, which already owes sanitizer jobs in CI and cannot deliver its
   own gates without it. No claim anywhere may present a supervised local run as CI evidence.
3. **May B1.6 ship the diagnostic bypass? Yes, under three conditions, all mandatory.** An
   unbypassable gate makes a false rejection our outage in a consumer's product, and that
   consumer cannot patch us. So the bypass exists, and it is built so that using it is never
   quiet: it is opt-in only and never the default; it emits a warning naming the exact mismatch,
   the expected identity and the found identity, once per process; and the fact that it was used
   is recorded in the diagnostics a bug report carries, so no investigation ever starts from a
   silently bypassed gate. This is the same shape the project already uses for approximate colour
   and for refused speed: continue, but say so in a typed and visible way.

One note the orchestrator checked rather than assumed: the coupling numbers in B1-06 were measured
at KiteCodec `cdb8ad2`, which is still `HEAD` with a clean tree, so 253, 273, 21 and 11 stand and
B1.1 may write them as its baseline without re-measuring. If that stops being true before B1.1
runs, re-measure and record the difference.

---

## 16. The B1 to B2 interlude

Written 2026-08-10 by the B1 close-out review, after B1 was closed and before B2 opened. Six
independent review fronts attacked the combined end state at KiteCodec `2b4287f` and KitePlayer
`1fd0e15`: the real-time C memory model, the `kitecodec-c` end state, the Kotlin seams, build and
packaging and CI, the claims and the register, and B2 readiness. Their findings were treated as
claims and not as facts: every finding severe enough to influence the verdict was reproduced by
measurement in a scratch clone before it was accepted, and one was discarded because it did not
reproduce. This section is to the interlude what section 15.2 is to B1: the executable run. It is
decision complete. An implementer needs this section, section 1, section 2, section 9 and the
code, and nothing else.

### 16.0 The verdict, and the bound on this section

**The verdict: B1 to B2 interlude required.** B1 is not defective in the sense that would reopen a
sub-phase. Every sub-phase's deliverable survived attack: the memory ordering is textbook in all
four seqlocks, the lift is byte for byte faithful and was re-proved at this review, the identity
gate rejects every verdict it claims to, the render audit and the interposed C test both hold, and
the shipped callback body measured a worst case of 5,958 nanoseconds against a 5,333,333
nanosecond budget with no underrun, no give-up, no zero-filled callback and no estimated anchor.
What did not survive is the space between the sub-phases: seams no per-phase gate owned,
instruments whose coverage is narrower than the record reads, ratchets that fire on the work B2 is
about to do, and a handful of numbers and grades in the record that a rerun contradicts.

**The four conditions the owner set, and what fires each.** A finding that B2 will fix in its
natural course does not need this section. A finding that would corrupt memory, mislead the
project record, fire a ratchet with no documented move procedure, or silently invalidate an
instrument the plan relies on, does. All four fire.

1. *Would corrupt memory.* I-01 (a byte count that wraps to zero on four of the seventeen shipped
   targets, admitting a heap overflow), I-02 (a use after free on the shipped audio path when a
   close times out), I-04 (a replaced reservation publishing ring storage nobody wrote), I-05 (two
   unchecked signed arithmetic sites on documented public parameters).
2. *Would mislead the project record.* I-16 (a ten minute negative control whose direction three
   later measurements reverse, with the causal conclusion built on it), I-17 (four sentences in a
   public repository that grade or present continuous integration which has never executed, one of
   them grading a never-run job level 2 under section 2's own scale), I-18 (one em dash in each
   repository, in the one file every documented scan is blind to, with contract item 4 forbidding
   it), I-19 (a register row the closing entry announced and never wrote, plus eighteen exported
   entry points that crash on an argument and are recorded nowhere the register can see).
3. *Would fire a ratchet with no documented move procedure.* I-12 (`verify-lift.sh` freezes 909
   lines of C against an anchor no future revision can replace, and it already blocks a proved
   crash fix), I-13 (the coupling ratchet fails B2's own first named improvement, and fires on a
   KDoc comment), I-14 (the deleted surface list lives in three files with no way to resurrect a
   name), I-15 (the standing gate of section 9 runs none of the thirteen instruments B1 built, so
   a B2 executor following contract item 2 fires none of them at all).
4. *Would silently invalidate an instrument the plan relies on.* I-07 (the FFmpeg include tree is
   not a tracked input, so the archive's frozen identity macros and struct offsets go stale while
   the cinterop half of the same klib regenerates), I-08 (the allocation interposer can be blinded
   by one word and the ownership gate still reports success, which is the whole of deferral 2's
   guarantee), I-09 (the exported C surface can grow with every check reporting the two sets
   equal), I-10 (the only producer side guard against a wrong architecture archive can be deleted
   at its call site with every test still green), I-11 (thirteen of eighteen load bearing ordering
   decisions have no instrument, and both ends of the one edge that stops the feeder overwriting
   storage the consumer is copying out of are among them).

**The bound.** This is an interlude and not a horizon. It adds no product capability, no target,
no backend and no public API beyond what an item below names. It promotes no tier. Twenty items,
six sub-phases, and an explicit list in section 16.4 of what was deliberately left to B2 with the
reason. Anything a reviewer proposed that B2 will do anyway is in that list and not in the
register.

**What was rejected, and by what measurement.** One finding was discarded rather than carried.
`check-deleted-surface.sh` was reported to have no positive control, on the strength of a run in
which all three checks reported clean while eight files really did mention the deleted names. That
run reached the two repositories through sibling symlinks, and BSD `grep -r` with that
`--exclude-dir` set does not descend a symlinked path argument. Run from a real checkout, which is
what the gate does, the check bites: resurrecting `ffkmp_frame_make_writable` in a scratch clone
gives `FAIL: 1 use site(s) survive` and exit 1, and the untouched tree gives exit 0. So the
committed gate is sound and the reported defect was an artifact of the reviewer's own layout. The
hardening it suggested, failing when the prose set is empty because the allowlist proves at least
eight files must match, is recorded in section 16.4 as a B2 nicety and is not in the register.

### 16.1 The interlude task register

Same shape as section 15.1. Every item is located, has its fix decided, names its sub-phase and
names its test. 20 items.

#### I-01. `kprt_ring_create` bounds the factors, not the product, and admits a heap overflow on four targets
- Where: `kiteplayer-rt/native/src/kite_rt_ring.c:68` (the validation) and `:72` (the multiply);
  consequence at `:79` and at every `memcpy` in `kite_rt_render.c:198` to `:205`. The target list
  that decides which targets are affected is `buildSrc/src/main/kotlin/CompileKiteRtTask.kt:295`
  to `:320`.
- Problem: line 68 refuses `capacity_frames > (1 << 27) || channels > 64` and its comment says the
  purpose is to remove the overflow of the multiply on line 72. That reasoning holds only for a 64
  bit `size_t`. Four of the seventeen shipped targets have a 32 bit `size_t`, and every admitted
  pair whose frame count times channel count reaches 2^30 wraps. Measured with the shipped konan
  clang and the shipped triples: three `_Static_assert`s over the exact expression of line 72, at
  `(1 << 27, 8)`, `(1 << 27, 32)` and `(1 << 24, 64)`, all hold on `armv7k-apple-watchos7.0`,
  `arm64_32-apple-watchos7.0`, `armv7a-unknown-linux-androideabi24` and
  `i686-unknown-linux-android24`, and all three are rejected with three failures each on the two
  controls `arm64-apple-macos11.0` and `aarch64-unknown-linux-android24`. In a deterministic model
  in which only the three byte count widths of `kprt_ring_create` become 32 bit and nothing else
  changes, `kprt_ring_create(48000, 32, 1 << 27)` succeeds, reports `capacity=134217728
  channels=32`, and the first ordinary feeder fill of the granted window gives `AddressSanitizer:
  heap-buffer-overflow ... WRITE of size 4 ... 0 bytes after 960-byte region ... allocated by ...
  kprt_ring_create`. The unmodified 64 bit control at the same vector is clean. Level 2 both ways,
  with a control that rejects. Not reachable from the shipped device path today, which was checked
  rather than assumed: `kprt_sink_create` clamps to `KPRT_MIN_CHANNELS` 1 and `KPRT_MAX_CHANNELS`
  2, and at two channels the admitted maximum product is 2^30 bytes, which does not wrap. It is
  reachable through `kprt_ring_create` itself, which is `KPRT_API` exported, and through
  `NativeAudioRing`, whose constructor passes `format.channels` and which the differential oracle
  drives at 6 and 8 channels. The defect is that the validation whose only job is memory safety
  does not do that job on four shipped targets.
- Fix: bound the product and not the factors. Keep the two existing refusals as cheap sanity
  bounds, and add, before the multiply, `if ((uint64_t)capacity_frames * (uint64_t)channels *
  sizeof(float) > (uint64_t)SIZE_MAX - 2u * KPRT_CACHELINE) return NULL;` so the guard is correct
  at every pointer width. Add one sentence to `CompileKiteRtTask.specFor`'s comment saying that a
  new target must be checked for pointer width against this guard.
- Sub-phase: I.5. Test: a new `test_ring_alloc` case asserting that a create whose byte count
  cannot be represented is refused and allocates nothing, driven under `interpose` so the refusal
  is proved to allocate nothing rather than argued; plus the three `_Static_assert`s of the
  measurement above, kept as a compile time check in `kite_rt_ring.c` so the arithmetic is proved
  at every target's own pointer width by the build itself.

#### I-02. `AudioPlayback.close()` orders the four documented readers against the free and leaves the feeder out
- Where:
  `kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt:379` to
  `:403` (close), `:164` to `:184` (submit), and the class KDoc at `:46` to `:56`;
  `kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:1449`
  and `:1485` to `:1508` (teardown).
- Problem: the B1.8 blocking finding was fixed by clearing `ring` inside the lock every
  cross-thread reader takes, which covers `position`, `anchorClock`, `buffered` and `underruns`.
  `submit` is not in that set and is not in the class KDoc's list of members confined to the
  session owner, so the documented contract does not exclude a `submit` concurrent with a `close`,
  and `close` states no quiescence precondition although `flush` states one in the same file. The
  producer side call on a freed ring is a use after free and not an argument: measured under
  AddressSanitizer, `heap-use-after-free ... READ of size 8 ... in kprt_ring_begin_write
  kite_rt_ring.c:250 ... 64 bytes inside of 33728-byte region ... freed by`. Level 2 for the
  hazard. The engine route is level 4 and every link was read: `runClose` wraps `teardownSession`
  in `withTimeoutOrNull(CLOSE_DEADLINE)` with `CLOSE_DEADLINE` at 10 seconds; `teardownSession`
  first calls `workers.forEach { it.quiesce(QUIESCE_DEADLINE) }` with `QUIESCE_DEADLINE` at 2
  seconds across five workers, which is exactly the close budget; it then cancels the jobs and
  joins them with `runCatching { it.join() }`, and `runCatching` catches `CancellationException`,
  so once the timeout has fired every join throws and is swallowed and the joins do not wait; and
  `session.audio?.close()` is not a suspend call, so it runs to completion in the cancelled
  coroutine and frees the C ring. `submit`'s only suspension point is `delay(FULL_RING_WAIT)`,
  reached only when the ring is full, so a cancelled feeder can execute a whole buffer of
  `ring.write` calls after the free.
- Fix: two changes, both small, and the second is what makes the first true. Extend the one
  sentence rule to the producer: `submit` and `submitDecoded` read `ring` under the lock, so the
  rule stays "a member that may be called from another thread touches that field only under the
  lock", and add the quiescence precondition to `close`'s KDoc in the same words `flush` uses.
  Make `teardownSession` join for real: wrap the cancel and join pair in
  `withContext(NonCancellable)` so a close that has run out of budget still waits for the feeder
  before anything frees a ring, and say in the KDoc that the join is the only thing standing
  between a cancelled feeder and a freed C ring.
- Sub-phase: I.5. Test: a `PlaybackCoreTest` case that makes one worker refuse to reach a
  quiescent boundary, drives a close, and asserts the feeder is finished before the audio path is
  closed; proved falsifiable by removing the `NonCancellable` wrapper, which must fail it. Plus
  one `AudioPlaybackTest` case pinning that a `submit` racing a `close` never touches a cleared
  ring.

#### I-03. A failed open after the device is negotiated leaks the C sink, the C ring and an initialised AudioUnit
- Where:
  `kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:796`
  to `:846`; the catch at `:842` to `:846`.
- Problem: `sink = output.audioSink.create()` and `audioPlayback.open(...)` publish a live device
  into locals. The catch closes only `backendSession`, although its comment says "Nothing half
  built survives an open that failed", and `runOpen`'s catch calls `teardownSession()`, which
  returns immediately because `this.session` has not been assigned yet. The reachable thrower is
  the very next statement, `withContext(dispatchers.demux) { source.selectStreams(...) }`, which
  reaches `KiteCodecSource.selectStreams`
  (`kiteplayer-ffmpeg/src/nativeMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt:114`
  to `:119`), a `check`, a `require` and `source.openPacketReader`. Level 4, with the ownership
  invariant read from the code: `CoreAudioSink.close()` is the only caller of `kprt_sink_destroy`,
  and `grep` over `PlaybackCore.kt` shows `session.sink` is only ever stopped, never closed.
  Before B1.8 this leaked a Kotlin object holding an AudioUnit; it now also leaks two C
  allocations while `retainedResources()` reports zero.
- Fix: wrap everything after the audio path is opened in a `try`, and on any throwable
  `runCatching { audioPlayback?.close() }` before rethrowing. Correct the catch comment to say
  what it now covers.
- Sub-phase: I.5. Test: a `PlaybackCoreTest` case with a source that throws from `selectStreams`,
  asserting the fake sink recorded exactly one close; proved falsifiable by removing the new
  catch.

#### I-04. A second `kprt_ring_begin_write` can publish ring storage the caller never wrote
- Where: `kiteplayer-rt/native/src/kite_rt_ring.c:250` to `:276`, and the contract at
  `kiteplayer-rt/native/include/kite_rt.h:210` to `:211`.
- Problem: `begin_write` never checks `has_pending`, so a second call recomputes the grant from
  the current `consumed` and can hand back a larger window than the first, and `pending_frames`
  becomes the larger number. Measured through the public surface only, under AddressSanitizer and
  UndefinedBehaviorSanitizer: with the ring poisoned by an earlier write, `grant1=128 grant2=512`,
  a commit of the second grant returns `KPRT_COMMIT_PUBLISHED`, and of the 1024 published samples
  768 are the poison the reservation never wrote. The mirror case is a smaller second grant:
  `grant=256 probe grant=64`, and the original commit is then refused with
  `KPRT_COMMIT_BAD_ARGUMENT` and `written` stays at 0, losing a filled buffer. Level 2. The header
  says a double begin is "a programming error rather than a supported idiom" and then says "the
  second call reports the same window", which is true only when the second call asks for the same
  count and room has not changed, and which is what makes the idiom look harmless. Not reachable
  from the shipped consumer today, which was checked: `NativeAudioRing.write` begins, fills and
  commits inside one function, and `AudioPlayback.submit` retries with identical arguments so the
  window is refilled every time.
- Fix: refuse a second begin while `has_pending` is set, returning 0 and leaving the outstanding
  reservation untouched, and correct `kite_rt.h:210` to `:211` to say exactly that. Refusing beats
  clamping because the clamped form leaves a caller believing it holds a window it does not.
- Sub-phase: I.5. Test: two `test_ring_basic` cases, one per direction, asserting that a second
  begin returns 0 with the first reservation intact and that the first commit then still publishes
  exactly what was written; plus one row in the differential oracle so both rings agree.

#### I-05. Two unchecked signed arithmetic sites on documented public parameters
- Where: `kiteplayer-rt/native/src/kite_rt_render.c:225` to `:226` (the silence back-dating in
  `kprt_ring_render`) and `:37` to `:59` (`kprt_frames_to_micros`, the multiply at `:59`).
  Documented at `kite_rt.h:245` to `:249` and `:289` to `:296`.
- Problem: both are signed overflow, which is undefined behaviour, on `KPRT_API` entry points with
  no stated domain. Measured under UndefinedBehaviorSanitizer through the public surface:
  `kprt_ring_render(frames=128, deadline_nanos=INT64_MIN)` with 64 frames available gives
  `kite_rt_render.c:225:49: runtime error: signed integer overflow: -9223372036854775808 - 1333333
  cannot be represented in type 'int64_t'`, and `kprt_frames_to_micros(INT64_MAX, 1)` gives
  `kite_rt_render.c:59:18: runtime error: signed integer overflow: 9223372036854775807 * 1000000`.
  Level 2 for both. Neither is reachable from a real clock or a real frame count, and that was
  measured rather than assumed. The sibling site in `publish_anchor` was given `add_saturating` at
  the B1.8 verification and these two were not, so this is the last unchecked signed arithmetic on
  the anchor path. `kprt_frames_to_micros` matters twice over, because its whole reason for being
  exported is that both implementations of the contract must agree on it, and `KotlinAudioRing`'s
  `Long` arithmetic wraps with defined behaviour where this wraps with undefined behaviour, so the
  differential oracle compares two different kinds of wrong at the top of the range.
- Fix: a `sub_saturating` at `:225` mirroring the `add_saturating` twelve lines above it, and
  saturation in `kprt_frames_to_micros`, matching the decision already taken for the anchor. State
  the saturating behaviour in both header comments in place of the current unqualified "Exact, not
  approximate".
- Sub-phase: I.5. Test: two `test_ring_rescale` rows at each end of the range and one
  `test_ring_basic` case rendering against `INT64_MIN` and `INT64_MAX` deadlines, all four run
  under the asan variant which carries UndefinedBehaviorSanitizer; plus two differential oracle
  rows at the ends so the two rings are pinned to the same answer rather than to two different
  wraps.

#### I-06. The reservation fields break the internal header's own stated rule, and a flush between begin and commit fails the player
- Where: `kiteplayer-rt/native/src/kite_rt_ring_internal.h:13` to `:18` (the rule), `:99` and
  `:104` to `:107` (the two false comments), `kite_rt_ring.c:471` and `:481` to `:482` (flush
  writing them), `kite_rt.h:35` to `:45` (the quiescence contract),
  `kiteplayer-core/src/nativeMain/kotlin/io/github/yuroyami/kiteplayer/internal/NativeAudioRing.kt:189`
  to `:190` (the throw),
  `kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt:1292`
  to `:1296` (the seek path that continues after a failed quiesce), and
  `kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt:281` to
  `:283` (flush called outside the lock).
- Problem: three related truths, all read from the code. The internal header states in capitals
  that every field touched by more than one thread is `_Atomic` and that this is not decoration,
  then declares `pending_start_frame`, `pending_frames` and `has_pending` plain with the comment
  "Feeder-private, so plain fields", and `kprt_ring_flush` writes two of them from the session
  owner's thread. The same header says of the `reader_*` block "Only `kprt_ring_anchor` writes
  these", which `kite_rt_ring.c:471` contradicts, while the neighbouring `cache_*` block documents
  the identical exception correctly. And the consequence of the documented precondition being
  violated is now a hard failure rather than a degradation: a flush landing between the feeder's
  begin and its commit makes the commit answer `KPRT_COMMIT_BAD_ARGUMENT`, which was measured
  (`commit(first grant) verdict=2 written=0`), and `NativeAudioRing.write` turns that verdict into
  an `error(...)`, which kills the audio feed worker and fails the player with
  `PlaybackError.Internal`. The engine reaches exactly that state on purpose: `runSeek` warns
  `PlaybackWarning.BadTimestamps` when `quiesceWorkers` times out and then continues to the flush.
  The Kotlin ring had no such state and degraded to garbled audio. Level 4 for the reachability,
  level 2 for the C behaviour. Separately, `kprt_ring_flush` clears the anchor and both caches
  while `kite_rt.h:35` to `:41` says any thread may call `kprt_ring_anchor` while the ring is
  alive, and `AudioPlayback.flush` calls `ring?.flush()` outside the lock that `position()` and
  `anchorClock()` take, so nothing excludes the two. The reviewer could not make that interleaving
  fire: 400,000 flushes against 2,304,154 concurrent anchor reads produced zero resurrections,
  which is level 2 negative evidence, and the consequence is marked unverified.
- Fix: four things, none of which is a design change. Make the three reservation fields `_Atomic`
  with relaxed access, which is zero instructions on every target here and makes the struct honour
  its own rule; correct both false comments; add the anchor reader to the quiescence sentence in
  `kite_rt.h:42` to `:45`, so the C contract says what the code needs; and move `ring?.flush()`
  inside `synchronized(lock)` in `AudioPlayback.flush`. Leave `NativeAudioRing.write`'s throw as
  it is: a `BAD_ARGUMENT` after a correct call sequence is a real programming error and must be
  loud.
- Sub-phase: I.5. Test: a `test_ring_threads` case running a flusher against a live feeder under
  ThreadSanitizer, which must be clean after the change and which was proved to report a race
  before it (`data race ... Write of size 4 ... kprt_ring_flush kite_rt_ring.c:481 ... Previous
  write ... kprt_ring_begin_write kite_rt_ring.c:273`); plus the existing `AudioPlaybackTest`
  extended with one case pinning that `flush` and `position` cannot interleave.

#### I-07. The FFmpeg include tree is not a tracked input, so the archive's frozen identity and the klib's cinterop half can disagree
- Where: `../KiteCodec/buildSrc/src/main/kotlin/CompileKiteCodecCTask.kt:86` to `:87` (`@get:Input
  abstract val ffmpegIncludeDirs: ListProperty<String>`), wired at
  `../KiteCodec/kitecodec-core/build.gradle.kts:189`. The claim it falsifies is at
  `../KiteCodec/native/kitecodec-c/include/kitecodec_abi.h:24` to `:28`.
- Problem: the include tree is tracked as a list of path strings, so its contents are not
  fingerprinted, while `cinteropFfmpeg<Target>` tracks header content through its own custom up to
  date check. Measured at byte level in a scratch clone against a copied FFmpeg prefix: changing
  only `LIBAVUTIL_VERSION_MICRO` from 100 to 177 inside that tree, with every path unchanged,
  makes `compileKiteCodecCForMacosArm64` report UP-TO-DATE while `cinteropFfmpegMacosArm64`
  re-executes, and `kitecodec_abi.o` stays byte identical, still carrying the word `6408 3c00`,
  which is `AV_VERSION_INT(60, 8, 100)`. Forcing the compile to rerun against the same edited
  header changes exactly one byte, at offset 4185, from `0x64` to `0xB1`, so the UP-TO-DATE
  verdict hid a real change to the frozen expectation the identity gate compares. Level 2, byte
  level. Reachable on the default developer path, because `FFmpegPaths.resolveSystem` returns the
  unversioned `/opt/homebrew/include`, so `brew upgrade ffmpeg` changes header content with the
  path string unchanged. This is the mirror image of the hazard B1.3 measured and fixed with
  `inputs.files` on the other input, and the reverse direction was never measured. The consequence
  today is contained by FFmpeg's within-major ABI promise and by the gate rejecting rather than
  corrupting across a major, so it is not a live corruption; it is the instrument that carries
  B1's second exit clause becoming stale without a word.
- Fix: make the FFmpeg include tree a real input. Change `ffmpegIncludeDirs` to a tracked file
  collection with `@InputFiles` and `@PathSensitive(PathSensitivity.NAME_ONLY)`, or keep the
  string property for the compiler arguments and add `inputs.files(...)` over the six `version.h`
  files beside it. Then close the gap the fix leaves open: add one assertion to
  `klib-metadata-diff.sh` that the cinterop klib's `LIBAVUTIL_VERSION_INT` equals the archive's
  own reported `header_*` value for avutil, so the two bakings inside one klib are compared rather
  than assumed equal. Correct the two header comments at `kitecodec_abi.h:24` to `:28` to say
  which compile they describe.
- Sub-phase: I.4. Test: a `CompileKiteCodecCTaskTest` case proving the task is out of date after a
  header content change with no path change; plus the new assertion, proved able to fail by
  doctoring one of the two sides.

#### I-08. The allocation interposer can be blinded by one word and the ownership gate still reports success
- Where: `../KiteCodec/native/kitecodec-c/tests/harness.h:234` to `:256`,
  `../KiteCodec/native/kitecodec-c/tests/harness.c:50`,
  `../KiteCodec/native/kitecodec-c/tests/interpose_alloc.c:172`,
  `../KiteCodec/native/kitecodec-c/scripts/run-c-tests.sh:33`. The already written fix is at
  `kiteplayer-rt/native/scripts/run-c-tests.sh:59` to `:62` and its `harness.c`.
- Problem: `KC_ALLOC_BALANCED` and `KC_ALLOC_LIVE` degrade to `kc_partial()` when the interposer
  is not effective, and there is no mode that makes ineffectiveness a failure. Measured: renaming
  the Mach-O section in `interpose_alloc.c` from `__DATA,__interpose` to `__DATA,__nointerpose`,
  one word, and rebuilding gives `test_ownership: 39 cases, 39 passed, 39 with a property this
  variant cannot observe` and exit 0, where the same cases print real counters before the change.
  Level 2. This matters more than a missing test, because section 15.5's deferral 2 says the
  ownership guarantee is carried by "exact pairing tests over all 39 ownership helpers under the
  interposer" in place of a compiler attribute, so the whole of that guarantee can go dark with
  the gate green. KitePlayer's `kiteplayer-rt` already solved this and the older copy did not,
  which is the first concrete cost of the two harnesses having forked.
- Fix: port the `interpose` run mode and the `KPRT_REQUIRE_ALLOC_ACCOUNTING` mechanism into
  `kitecodec-c` under the name `KC_REQUIRE_ALLOC_ACCOUNTING`, so "the interposer is not effective"
  becomes a hard failure rather than a recorded partial, and add the mode to `run-c-tests.sh` and
  to the standing gate of I-15. Record in both READMEs that the harness and the interposer exist
  in two repositories, that a fix to either lands in both, and that the mirror is part of the same
  commit.
- Sub-phase: I.4. Test: the new mode itself, proved able to fail by the same one word section
  rename, which must now report a failure rather than a partial.

#### I-09. Nothing constrains the exported C surface, and one gate command exits 0 on a real mismatch
- Where: `../KiteCodec/native/kitecodec-c/scripts/symbol-audit.sh:158` to `:160` and `:245` to
  `:260`; `../KiteCodec/native/kitecodec-c/scripts/klib-metadata-diff.sh:315`.
- Problem: two instrument defects that answer each other. Check 3 of `symbol-audit.sh` derives the
  expected export set from the two headers and compares it to `nm`, so it is a header against
  archive consistency check and not a baseline: declaring a new symbol makes it expected.
  Measured, by adding one ordinary `KC_API int32_t` function to `src/kitecodec_abi.c` with a well
  formed declaration beside its neighbours in `include/kitecodec_abi.h`: `nm` confirms the new `T`
  symbol in the archive and `symbol-audit.sh --host` reports `header declares 164 KC_API helpers`,
  `archive exports 164 symbols`, `ok: the two sets are equal`, PASS, with `verify-lift.sh` and
  `check-deleted-surface.sh` both exit 0. The coupling ratchet excludes the `kc_` surface by
  construction, so nothing in the base gate holds the line. The one instrument that does catch a
  surface change is `klib-metadata-diff.sh --check`, and the plan writes that gate in the bare
  form at three places, which exits 0 on a real mismatch: measured on the same injected mismatch,
  `--check` exits 1 and the bare form exits 0. Level 2 for both.
- Fix: commit `native/kitecodec-c/exported-symbols-baseline.txt`, the 163 external names of the
  archive today, 157 `ffkmp_` plus 6 `kc_`, generated by `symbol-audit.sh` itself; add a sixth
  check comparing the archive against that baseline as well as against the headers, with the same
  move procedure the coupling baseline already has, which I-15 writes down. Change the three bare
  invocations in section 15.2's gate blocks to `--check` and make the bare form exit non-zero on a
  mismatch so the two forms cannot disagree.
- Sub-phase: I.4. Test: the new check, proved able to fail by the same declared probe export, and
  a negative control proving the bare form now exits non-zero.

#### I-10. The producer side architecture guard can be deleted at its call site with every test green
- Where: `buildSrc/src/main/kotlin/CompileKiteRtTask.kt:191` and
  `../KiteCodec/buildSrc/src/main/kotlin/CompileKiteCodecCTask.kt:217` (the call sites);
  `buildSrc/src/test/kotlin/CompileKiteRtTaskTest.kt` and
  `../KiteCodec/buildSrc/src/test/kotlin/CompileKiteCodecCTaskTest.kt` (the suites).
- Problem: both suites test the predicate `verifyObjectArchitecture` thoroughly and neither
  asserts that the task action calls it, so the only producer side guard against register item
  B1-11 is fully tested and fully bypassable. Measured: replacing the call site in
  `CompileKiteRtTask` with a comment, verified one call site before and zero after, leaves
  `:buildSrc:test` at 11 tests and BUILD SUCCESSFUL. Level 2. The CI step that echoes `file -b`
  asserts nothing on its output.
- Fix: one test per repository that runs `compile()` for a target whose expected object
  description cannot match the object the compiler will actually produce, and asserts the failure
  names both architectures and the target. KiteCodec's fixture already compiles real objects for
  two targets, so the material exists on both sides. While both suites are open, copy across the
  three guards the mutation matrix showed each suite missing, so the two near twin tasks are
  covered identically: the output directory naming guard, the numeric LLVM package ordering, and
  the stale object clearing.
- Sub-phase: I.4. Test: the new cases themselves, each proved able to fail by deleting the guard
  it covers.

#### I-11. Thirteen of eighteen load bearing ordering decisions have no instrument
- Where: `kiteplayer-rt/native/scripts/source-discipline.sh:124` to `:168` (the five checks that
  exist); the unpinned decisions at `kite_rt_ring.c:117,131,158,159,164,251,332,343`,
  `kite_rt_render.c:110,121,206,288` and `kite_rt_coreaudio.c:299`.
- Problem: `source-discipline.sh` was added because a planted ordering defect passed the whole
  gate, and it pins five decisions: the `written` release store, the `written` acquire load, the
  closing release store on a segment slot's sequence, and the two release fences in
  `publish_anchor` counted as at least two. Measured at this review, the script reports exactly `5
  checks, all passed`. Three mutants were planted on unpinned decisions and each passed
  everything: the `consumed` release store downgraded to relaxed at `kite_rt_render.c:206`, the
  `consumed` acquire load downgraded to relaxed at `kite_rt_ring.c:251`, and the `sink->ring`
  release store downgraded to relaxed at `kite_rt_coreaudio.c:299`. Each of the three passed
  `run-c-tests.sh` in all four modes including tsan, plus `source-discipline.sh` and
  `render-audit.sh`. The suites are not inert, which was checked with a functional control in the
  same clone: breaking the wrap arithmetic at `kite_rt_render.c:195` is rejected by
  `test_ring_basic`, `test_ring_threads` and `test_sink_callback`. Level 2. Two of the three are
  worse than untested. The `consumed` pair is the only happens before edge that stops the feeder
  from overwriting ring storage the consumer is still copying out of, and the `sink->ring` pair is
  the edge whose own comment says that without it a callback could read whatever `malloc` last
  held in the sample block.
- Fix: extend `source-discipline.sh` from five checks to eighteen, one grep per load bearing
  ordering decision, with one negative control each, in exactly the shape of the checks already
  there. Keep the script's own header sentence that it is level 4 and that ThreadSanitizer grades
  atomicity and not ordering strength, and add one sentence naming how many decisions are pinned,
  so the count cannot be read as coverage again. Correct the B1.9 log entry's "five checks"
  sentence in the same commit, per I-16.
- Sub-phase: I.4. Test: the script's own `--prove-it-can-fail` arm, extended to thirteen new
  negative controls, every one of which must be rejected.

#### I-12. `verify-lift.sh` freezes 909 lines of C against an anchor that can never move, and it blocks a proved crash fix
- Where: `../KiteCodec/native/kitecodec-c/scripts/verify-lift.sh:40`
  (`PRE_LIFT_REVISION="5364329"`) and its comparison C at `:140` to `:168`;
  `../KiteCodec/native/kitecodec-c/tools/extract_from_def.py` (1108 lines);
  `../KiteCodec/native/kitecodec-c/src/helpers_format.c:57` and `:22` to `:25` (the two blocked
  guards); `../KiteCodec/native/kitecodec-c/README.md` and `../KiteCodec/README.md:347`.
- Problem: the script proves the committed C is byte for byte what the extractor produces from the
  def body at `5364329`. Every commit from the lift onward has a def with no body, so the anchor
  is a fixed point in history no future revision can replace. Measured in a scratch clone: at HEAD
  all eleven comparisons MATCH with payload digest
  `e63a7b56e4fe61a8f804d65b6066478dfa5e7eebcf5485685c327081391726ea` over 909 lines on both sides
  and exit 0; applying the one line NULL key guard the B1.6 log entry already records as blocked
  gives `MISMATCH: the units do not reassemble into the def body` and exit 1, with the failure
  message telling the reader to "Re-run the extractor and commit its output, or fix the def",
  which cannot be done because the def has no body; and re-anchoring with
  `./scripts/verify-lift.sh HEAD` exits 2 with `extract_from_def.py: expected exactly one '---'
  separator line, found 0`. Level 2. The cost is not hypothetical. Eighteen exported helpers crash
  on a NULL or out of range argument, and twelve of the twelve probed were reproduced at level 2,
  one fork per call against the committed host archive: signal 11 for `ffkmp_frame_get_buffer`,
  `ffkmp_codecpar_from_context`, `ffkmp_codecpar_copy_for_mux`, `ffkmp_fmt_open_input` with a NULL
  out pointer, `ffkmp_fmt_find_stream_info`, `ffkmp_fmt_seek_micros` with an out of range stream
  index, `ffkmp_fmt_read_frame`, `ffkmp_fmt_alloc_output2` with a NULL out pointer,
  `ffkmp_fmt_set_opt` with a NULL key, `ffkmp_fmt_write_frame`, `ffkmp_codecctx_open` and
  `ffkmp_codecctx_from_par`, against two calibration controls that guard and answer cleanly. Every
  one of them is exported `KC_API` surface of a public library, and the reason none is fixed is a
  script.
- Fix: retire and replace, because re-anchoring is impossible and splitting the units into frozen
  and evolving buys only bookkeeping, since B2's first named edits touch five of the nine. In one
  commit: run `verify-lift.sh` at `2b4287f` one last time and paste its full output into the log
  entry, all eleven pairs and both digests, as the permanent record that the lift was faithful;
  delete `scripts/verify-lift.sh` and `tools/extract_from_def.py`; rewrite the README sections so
  they say the nine units are now ordinary maintained sources and the lift's faithfulness is a
  historical fact with recorded digests; and land the two guards the instrument was blocking, `!k`
  in `ffkmp_fmt_set_opt` and a `stream_index` bound in `ffkmp_fmt_seek_micros`, which are the two
  reproduced crashes with no owner at all. The remaining sixteen get register rows under I-19 and
  are B2's, because they want the generator plus its exclusion machinery and B2 owns that.
- Sub-phase: I.3. Test: two new `test_ownership` or `test_buffers` cases, one per guard, each
  proved able to fail by reverting its guard; the full C suite in all three variants plus the new
  `interpose` mode of I-08; `symbol-audit.sh` including its new baseline check;
  `klib-metadata-diff.sh --check`, which must be unchanged because neither guard adds a
  declaration; and the corpus replay under asan.

#### I-13. The coupling ratchet fails B2's own first named improvement, and fires on a comment
- Where: `../KiteCodec/buildSrc/src/main/kotlin/CheckCinteropCouplingTask.kt:156`, `:162` and
  `:180` to `:200`; `../KiteCodec/native/kitecodec-c/coupling-baseline.txt:50`, `:57` to `:60` and
  `:66`.
- Problem: three defects in one instrument. Counts 2 and 3 are separate ceilings, and moving a
  call from raw libav behind a helper, which is exactly what register item B1-22 asks B2 to do to
  the hot decode and encode calls, lowers count 3 and raises count 2, and the ratchet only looks
  at rises. Measured: replacing `avcodec_send_packet(` with `ffkmp_codecctx_send_packet(` at
  `Playback.native.kt:317` gives `ffkmp_call_sites: baseline 273, actual 274` and BUILD FAILED,
  with a message that reads "this coupling may only shrink" and offers only "raise the baseline".
  Count 4 is measured over whole file text, comments included, so a KDoc sentence naming a struct
  type moves it with no code change at all: appending the single line `// B2 note: the full
  AVChannelLayout model lands here.` to `FFmpeg.kt` gives `ffmpeg_struct_types_named_in_kotlin:
  baseline 11, actual 12` and BUILD FAILED, and B2's headline deliverable is the full
  `AVChannelLayout` model, so B2 cannot document its own work. And the baseline's own prose
  mis-splits the fourteen remaining raw libav sites: it says "the four hot decode and encode calls
  at 7 sites, the three `find_*_by_name` queries at 4 sites, and 3 more send/receive sites", while
  the baseline's own command finds nine hot send and receive sites (`avcodec_send_packet` at
  `MediaSource.native.kt:262` and `Playback.native.kt:317`, `avcodec_receive_frame` at
  `MediaSource.native.kt:283` and `Playback.native.kt:340`, `avcodec_send_frame` at
  `Frame.native.kt:247`, `Frame.native.kt:253` and `MediaSink.native.kt:498`,
  `avcodec_receive_packet` at `Frame.native.kt:236` and `MediaSink.native.kt:511`) and five lookup
  sites (`avcodec_find_encoder_by_name` at `FFmpeg.native.kt:55`, `Frame.native.kt:191` and
  `MediaSink.native.kt:210`, `avcodec_find_decoder_by_name` at `FFmpeg.native.kt:60`,
  `avfilter_get_by_name` at `FFmpeg.native.kt:65`). The total, fourteen, is right; the split is
  not, and B1-22's row carries the same error, so B2 would plan against seven sites where there
  are nine. Level 2 for all three.
- Fix: make the ratchet measure coupling. Introduce one ratcheted number,
  `ffmpeg_typed_crossings`, defined as helper mentions plus raw libav calls, which is 287 today,
  and keep the two components as reported detail that is not ratcheted, so a category move is
  neutral and a genuine reduction shows as a fall. Strip line comments and KDoc before counting,
  because a comment is not coupling. Turn count 4 from a bare number into a named allowlist of the
  eleven types, so a raise is reviewed per type. Correct the baseline's prose split and B1-22's
  row to nine hot sites and five lookup sites.
- Sub-phase: I.3. Test: three new `CheckCinteropCouplingTaskTest` cases, each measured here: the
  `Playback.native.kt:317` move must now pass where it fails today at 274 against 273; the comment
  naming `AVChannelLayout` must now pass where it fails today at 12 against 11; and a genuinely
  new FFmpeg typed call must still fail.

#### I-14. The deleted surface list lives in three files with no way to resurrect a name
- Where: `../KiteCodec/native/kitecodec-c/scripts/check-deleted-surface.sh:47` (`DELETED=`),
  `../KiteCodec/native/kitecodec-c/scripts/verify-lift.sh:45` (`DELETED_HELPERS=`),
  `../KiteCodec/native/kitecodec-c/tools/extract_from_def.py:151` (`DELETED = {`).
- Problem: check 1 treats any of the fifteen names followed by an open bracket as a failure, in
  any file type, in both repositories, and there is no `--update`, no baseline and no written
  procedure: a grep of the script for an update path, an allow path or a baseline finds nothing.
  Meanwhile the script's check 4 pins its own list to the extractor's table and refuses any
  difference, and the extractor additionally validates each name against a recorded def line range
  in a def that no longer has a body. Measured: resurrecting `ffkmp_frame_make_writable` as an
  ordinary `KC_API` helper gives `check-deleted-surface.sh` exit 1 with `FAIL: 1 use site(s)
  survive` and `verify-lift.sh` exit 1, and restoring the tree returns both to exit 0. Level 2.
  Four of the fifteen are plausible B2 needs: `ffkmp_fmt_alloc_output` for transactional output
  replacement, `ffkmp_frame_ref` and `ffkmp_frame_make_writable` for pooled plane views, and
  `ffkmp_packet_ref` for bitstream filters. That B2 wants them is judgement and is recorded as a
  risk rather than a measurement; that the mechanism has no move procedure is measured.
- Fix: collapse the three copies into one committed data file,
  `native/kitecodec-c/deleted-surface.txt`, one name per line with a status column reading
  `deleted` or `resurrected-in-<item>`. `check-deleted-surface.sh` reads it; the extractor stops
  existing under I-12; the third copy therefore disappears with it. A resurrection becomes one
  line changed plus one Execution log sentence, which is the same weight as lowering a ratchet
  number, and check 1 keeps its full power over every name still marked `deleted`. Write the
  procedure into the file's own header and into section 9's move table under I-15.
- Sub-phase: I.3. Test: `check-deleted-surface.sh` still failing on a planted use site of every
  name marked `deleted`, and passing for exactly the one name marked `resurrected`, with the other
  fourteen still failing, which is the falsifiability arm.

#### I-15. Section 9, the standing gate, runs none of the thirteen instruments B1 built
- Where: KPKMP.md section 9 (the gate every phase is pointed at by contract item 2) against
  section 15.2's base gate; `../KiteCodec/.github/workflows/ci.yml:54` and `:78` to `:81`;
  `../KiteCodec/native/kitecodec-c/klib-metadata-baseline.txt`.
- Problem: contract item 2 points every phase at section 9. Section 9 names `macosArm64Test`,
  `publishToMavenLocal`, four KitePlayer test tasks, three cross compile spot checks, four sample
  runs and the em dash scan. It names none of `:kitecodec-core:apiCheck`, `checkCinteropCoupling`,
  `klib-metadata-diff.sh --check`, `symbol-audit.sh`, `check-deleted-surface.sh`, the host C suite
  in either repository, `replay-corpus.sh`, either `:buildSrc:test`, `checkLegacyAbi`,
  `render-audit.sh` or `source-discipline.sh`. Measured by grep over the whole plan: every one of
  those thirteen names appears zero times inside section 9's block. The two that are in a standing
  gate at all, `apiCheck` and `checkCinteropCoupling`, live inside 15.2's base gate, which is
  described as inherited by "every later sub-phase" of B1, and B1 is closed. So every ratchet B1
  built now depends on a continuous integration workflow that has never executed and on nobody
  forgetting. Level 4 for the gap, with level 2 that the instruments themselves are healthy: every
  one of them was run at HEAD in a clean clone at this review and every one passed, including
  `symbol-audit.sh` PASS at 163 declared and 163 exported, `klib-metadata-diff.sh --check` exit 0
  at 19024 lines and sha256 `5e90ff81806aec7e3b9087a50316a78c5045c6bb8dccda081030d01c69a6986c`
  with every one of its fourteen counters at zero, `check-deleted-surface.sh` exit 0,
  `verify-lift.sh` exit 0, both C suites in every variant, `apiCheck` green,
  `checkCinteropCoupling` at 246, 273, 14 and 11, `checkKotlinAbi` green across four modules, and
  `kiteplayer-core:macosArm64Test` at 189 tests and 0 failures. Two adjacent defects belong with
  it. Neither `verify-lift.sh` nor `check-deleted-surface.sh` appears in any workflow file,
  measured as zero matches across all four. And the 19024 line metadata baseline hardcodes 24
  FFmpeg version constants, including exact micro values, while `ci.yml:54` installs FFmpeg with
  an unpinned `brew install ffmpeg`, so the macOS job goes red on the next Homebrew bump and the
  local gate goes red after any `brew upgrade`, and the cheapest way out of that red is an
  `--update` that would absorb a real surface change in the same commit.
- Fix: three text changes and one CI change, and this item lands first so the rest of the
  interlude is gated by the result. Promote the B1 base gate and the eleven scripts into section 9
  as the standing gate, in dependency order, so section 9 is again the whole gate an executor
  needs. Add to section 9 a ratchet move table with one row per baseline: the file, what makes it
  fire, the exact command that moves it, and what the Execution log entry must say. Rows for
  `coupling-baseline.txt`, `klib-metadata-baseline.txt`, `kitecodec-core.klib.api`, the four
  KitePlayer api dumps, `deleted-surface.txt` of I-14, `exported-symbols-baseline.txt` of I-09 and
  `symbol-audit.sh`'s `ALLOWED_UNDEFINED` list; and one convention, that a metadata re-baseline
  pastes the script's own SUMMARY block into the log entry, so the record carries the reviewed
  numbers rather than a pointer to a 19000 line diff. Change section 9's em dash scan from an
  extension allowlist to `git ls-files -z | xargs -0 grep -n`, so no extensionless file can hide
  from it again, and delete the now redundant widened form from 15.2. Add
  `check-deleted-surface.sh` to the macOS CI job beside the coupling ratchet, and pin the FFmpeg
  that CI installs to an exact formula version so the metadata baseline stops being hostage to a
  package manager.
- Sub-phase: I.1. Test: run the promoted gate verbatim from a clean clone of both repositories and
  require it to pass; then run the new `git ls-files` scan and require it to find the two LICENSE
  hits, which is the falsifiability arm and which I-18 then fixes.

#### I-16. Numbers and grades in the record that a rerun contradicts
- Where: KPKMP.md at the B1.7 to B1.9 entry: the negative control table and the paragraph after
  it, the zero underrun sentence, the level 1 grading in three places, exit clause 1's helper
  count, the seventeen archive object counts, the announced register row, and B1-24's owner;
  `README.md:145` to `:158`; `kiteplayer-rt/README.md:80`.
- Problem: seven record defects, each measured.
  1. The ten minute negative control's numbers do not reproduce and the two documents disagree in
     direction on the pair that carries the causal attribution. The entry records pressured worst
     57,051,458 nanoseconds with 1,482 of 51,533 over budget and unpressured worst 81,357,584 with
     159 of 51,675 over, and concludes the unpressured arm is "WORSE than the pressured arm and
     more than seven whole device periods" and therefore "The collector pause was never the
     mechanism". `RealTimeSoakTest.kt`'s own KDoc records the opposite direction for the same two
     arms, and so does the entry's own 0.4 minute decomposition. Re-measured at this review at 0.5
     minutes per arm on the same machine: pressured worst 12,444,083 nanoseconds with 99 of 2,575
     over budget and 4,175 collections; unpressured worst 10,519,500 nanoseconds with 18 of 2,585
     over budget and 31 collections. So the unpressured arm is better on both numbers, which is
     the KDoc's direction and the reverse of the entry's. Level 2. What survives is the load
     bearing conclusion: the managed arrangement misses the budget with the pressure removed, 18
     callbacks over a 5,333,333 nanosecond budget with a worst body of 10.5 milliseconds and 31
     collections. What does not survive is "worse rather than better" and the 81.4 millisecond
     figure.
  2. "Zero starvations" is presented as the property the media soak carries. The committed test
     asserts `audio.underruns <= loops` and its own comment records that "a first version asserted
     zero and reported one after three loops in 24 seconds", so the ten minute zero is a
     measurement of one run and not a property the gate holds.
  3. The supervised device run is graded level 1 in three places in this file and in
     `kiteplayer-rt/README.md`, while section 2's level 1 is "a repeatable release-mode automated
     test on a named real device with saved metrics" and the same sentences say debug binary and
     one operator. Section 2 ends with "No lower item may be presented as a higher one".
  4. Exit clause 1 says "The 176 def-body helpers are nine compiled translation units". The nine
     units define 161: 157 with `KC_API` and 4 `static`, measured, because 15 were deleted at
     B1.4.
  5. The seventeen archive object counts are archive bookkeeping members and not objects. Measured
     on the macos_arm64 archive: 10264 bytes, `llvm-ar t` reports 3 members and the three C
     sources of the module, `/usr/bin/ar t` reports 4 because it lists `__.SYMDEF`. The entry says
     4. The causal clause, that the Apple archives are smaller because only macos_arm64 carries
     the device implementation, also fails, because the non Apple archives carry the same refusing
     stubs and are larger; size tracks object format.
  6. The entry announces "one new row for the register from the verification" about a device
     answering with a layout it did not negotiate. Section 15.1 still says 25 items and still ends
     at B1-25, measured as 25 rows.
  7. B1-24's row says "Phase: B9" while section 15.6 question 2 says continuous integration is
     reassigned to B8, and section 11's B9 text contains no mention of CI while B8's does.
- Fix: correct each in place, append only, with the measurement beside it. For 1, print both runs
  with their dates and quote the reproducible statement rather than the outlier, in this file and
  in `README.md`. For 2, say what the test says: starvation is bounded by the loop seams and the
  ten minute run measured zero. For 3, relabel the supervised run as a manual observation with
  saved metrics, level 6 under section 2, and say in the same sentence that assertion 3's
  authority rests on assertions 1 and 2, which are level 2 and unchanged; amend no level and add
  none. For 4, 161. For 5, three objects and the refusal constant as the evidence that sixteen
  targets carry stubs. For 6, write the announced row as I-19 does. For 7, settle B1-24 on B8 in
  the row itself. Also correct the B1.9 entry's "five checks" sentence to name how many ordering
  decisions are pinned, per I-11.
- Sub-phase: I.2. Test: the em dash scan and a rerun of each corrected number, with the run that
  produced it named in the text.

#### I-17. Public KiteCodec documents grade or present continuous integration that has never executed
- Where: `../KiteCodec/native/kitecodec-c/fuzz/README.md:13`, `../KiteCodec/CHANGELOG.md:18`,
  `../KiteCodec/README.md:315`, `../KiteCodec/README.md:333`, `../KiteCodec/README.md:355`,
  `../KiteCodec/.github/workflows/ci.yml:5`,
  `../KiteCodec/native/kitecodec-c/include/kitecodec_abi.h:59` to `:65` and `:150` to `:156`,
  `../KiteCodec/kitecodec-core/api/kitecodec-core.klib.api`.
- Problem: five claim defects in a public repository, all measured, and all reachable by anyone
  the moment the seven local commits are pushed.
  1. `fuzz/README.md:13` grades the libFuzzer row "Level 2. A real search for unknown inputs" for
     a job that has never run, and `CHANGELOG.md:18` says the six targets "build as libFuzzer
     targets in a Linux CI job" when no libFuzzer driver has ever been linked anywhere. That is
     level 8, a declared configuration, presented as level 2. `README.md:315` says `apiCheck`
     "verifies in the macOS CI job, so an accidental signature change fails a build" and
     `README.md:355` says coverage guided fuzzing "runs only in the Linux CI job". The workflow
     file itself is honest and the plan is honest; these four sentences are not.
  2. `README.md:333` says "3 TestKit functional tests for the Gradle plugin". There are 4,
     measured by name: `kitecodecDslConfiguredAfterKotlinBlockIsSeenByTasks`,
     `missingLicenseChoiceFailsConfigurationWithInstructions`,
     `mismatchedFFmpegVersionFailsConfigurationNamingBothRefs` and
     `prebuiltSourceForTripleWithoutAssetFailsConfigurationWithOptions`. The same commit that
     corrected 72 to 85 in the line above left this one at 3.
  3. B1.6 added 32 public declarations and no public document names one of them. Measured: the api
     dump grew to 1082 lines and `git grep` over `README.md`, `CHANGELOG.md` and `docs/` finds
     zero occurrences of `FFmpegIdentity`, `FFmpegLibraryIdentity`, `IncompatibleFFmpegRuntime` or
     `avutilHeader`. A Keep a Changelog "Added" section is exactly where a consumer looks for a
     new public type.
  4. `ci.yml:5` still says the Linux job "also proves the lavc 6.x compat path in ffmpeg.def". The
     def holds no C since B1.3, and section 15.5 already records that the claim is wrong because
     the real compilation floor is lavc 60.30.100 and lavu 58.7.100.
  5. `kitecodec_abi.h` says the provisioning sentence is "sized so it is never truncated either"
     and that 1024 "leaves the whole sentence room even with a 511 byte directory in the middle of
     it". Measured by compiling `src/kitecodec_abi.c` with the three build defines at their
     declared field capacities: `strlen(report.provisioning)` is 1011 of 1024, with
     `runtime_version_info` at 3 of 64 and `runtime_license` at 22 of 64, so the margin is 12
     bytes and a git built FFmpeg's longer `av_version_info()` plus a long provisioning directory
     drops the tail sentence, which is the part that records that the bypass was used. Contract
     item 10 forbids a claim the code cannot support.
- Fix: one clause per sentence for 1, saying the job is configured and has not run yet, and
  regrade the fuzz README row from level 2 to level 8 with the corpus replay's level 2 stated
  separately. One word for 2. An "Added" entry naming the new public types for 3. Correct the
  comment for 4 to what 15.5 already records. For 5, raise `KC_TEXT_SENTENCE` to 1152 and soften
  the header sentence to what the arithmetic supports, and extend `test_identity`'s non truncation
  case to assert against the worst case capacity rather than against this machine's instance.
- Sub-phase: I.2. Test: the C suite for the capacity change, including a case at full declared
  capacities; the em dash scan; and a read of each corrected sentence against the state of the
  repository at the commit that makes it.

#### I-18. One em dash in each repository, in the one file every documented scan is blind to
- Where: `LICENSE:20` and `../KiteCodec/LICENSE:20`.
- Problem: both documented scans, section 9's and 15.2's widened form, print nothing, and `git
  grep` over all tracked files finds exactly one hit in each repository: `licence (LGPL-2.1+
  minimum; GPL when --enable-gpl is set , effectively GPL-3.0 for` with an em dash where that
  comma is written here. No extension allowlist can reach a file called `LICENSE`. Contract item 4
  says no em dashes in any file, so the instrument cannot enforce the rule it was widened to
  enforce, and B1-07 was recorded as closed. Level 2. The same line is a second defect in
  KitePlayer: the two `LICENSE` files are byte identical, measured, so KitePlayer's own licence
  file says "KiteCodec merely binds to FFmpeg" and describes "KiteCodec's GPL build flavour".
- Fix: replace the em dash in both files; reword KitePlayer's `LICENSE` note so it describes
  KitePlayer; and rely on the `git ls-files` scan I-15 installs, which finds these two hits today
  and will find the next one.
- Sub-phase: I.2. Test: the new scan, which must print nothing over both repositories after the
  fix and which was proved able to find these two before it.

#### I-19. Eighteen exported entry points crash on an argument, and the announced register row was never written
- Where: `../KiteCodec/native/kitecodec-c/src/helpers_frame.c:30`;
  `../KiteCodec/native/kitecodec-c/src/helpers_codecpar.c:25` and `:30`;
  `../KiteCodec/native/kitecodec-c/src/helpers_format.c:12`, `:21`, `:22`, `:27`, `:49`, `:56` and
  `:86`; `../KiteCodec/native/kitecodec-c/src/helpers_codec.c:14` and `:15`;
  `../KiteCodec/native/kitecodec-c/src/helpers_filter.c:39`, `:85`, `:196`, `:205` to `:212`,
  `:235`, `:243` to `:255`, `:308` and `:311`; and section 15.1 of this file.
- Problem: the frozen body's NULL guard discipline is inconsistent, roughly twenty helpers guard
  and these do not, and B1.4 turned that inconsistency into exported surface with default
  visibility in a versioned archive. Twelve of the twelve probed were reproduced at level 2 as
  signal 11, listed under I-12, against two calibration controls that guard and answer. Two are
  recorded today only inside log entry prose: `ffkmp_fmt_seek_micros`'s unbounded `stream_index`,
  whose own log sentence says it "wants a register row of its own, in B1 or B2" and never got one,
  and `ffkmp_fmt_set_opt`'s NULL key, assigned in prose to "B2's error record work". None of the
  eighteen is in the register. Separately the B1 closing entry announces a register row about a
  device answering with a layout it did not negotiate, and section 15.1 still ends at B1-25 with
  25 rows.
- Fix: write the rows, in this section rather than in section 15.1, so section 15.1 stays the
  record of what B1 decided. One row enumerating all eighteen argument guards with their
  locations, owned by B2 and to be fixed as one change through the generator plus its exclusion
  machinery now that I-12 has removed the byte equality proof that made each hand edit expensive;
  two of the eighteen are fixed in the interlude under I-12 and the row says which. One row for
  the un-negotiated device layout, moved forward from B8 to S1.b.3 because the same callback is
  promoted to iOS there. Its test-only callback seam and wrong-layout cases land before that
  promotion. Both rows carry the header contract sentence they need, since
  `include/kitecodec_helpers.h` documents ownership only and says nothing about arguments, and for
  `ffkmp_fmt_set_opt` says at line 282 that "A NULL context is refused with AVERROR(EINVAL)" while
  saying nothing about the key that segfaults.
- Sub-phase: I.2. Test: none of its own; the rows are the deliverable, and their tests are named
  in the rows.

#### I-20. Four continuous integration jobs fail on the first push because two toolchain names are host specific
- Where: `../KiteCodec/buildSrc/src/main/kotlin/CompileKiteCodecCTask.kt:383` (used at `:359`,
  `:363`, `:367`), `:155`, `:156`, `:439` and `:444`; the same two shapes at
  `buildSrc/src/main/kotlin/CompileKiteRtTask.kt:335` (used at `:317`, `:320`, `:322`, `:323`),
  `:131`, `:132`, `:398` and `:403`; failing jobs at `../KiteCodec/.github/workflows/ci.yml:372`
  to `:409` (the `android` matrix of three on `ubuntu-24.04`) and `:616` (`windows-x64`).
- Problem: two hardcoded host spellings. `ANDROID_TOOLCHAIN_SYSROOT` is
  `"target-toolchain-2-osx-android_ndk/sysroot"`, and konan names that package per host: the
  authoritative `konan.properties` on this machine reads `targetToolchain.linux_x64-android_arm64
  = target-toolchain-2-linux-android_ndk` and `targetToolchain.mingw_x64-android_arm64 =
  target-toolchain-2-windows-android_ndk`. On the Ubuntu runner the osx package never exists, so
  the C compile task throws before cinterop and `compileKotlinAndroidNative<Abi>` cannot run;
  before B1.3 there was no C compile on that path and the job passed. And the konan tools are
  looked up as `bin/clang` and `bin/llvm-ar` with `File.canExecute()`, in both the preferred
  package and the newest package fallback, so on a Windows host where the package ships
  `clang.exe` and `llvm-ar.exe` every candidate is rejected and the task throws; measured against
  a Windows shaped dependencies tree, `File("bin/clang").canExecute()` is false and
  `File("bin/clang.exe").canExecute()` is true. Level 2 for the package names and the predicate,
  level 4 for the code paths. The release path is unaffected, because `publish.yml`'s core job
  runs on `macos-latest`.
- Fix: derive both from the build host rather than hardcoding them. One helper returning the konan
  host infix, `osx`, `linux` or `windows`, used to build the Android toolchain package name; and a
  tool resolution that tries the bare name and then the `.exe` name, used in all four places in
  each repository. Same change in both repositories, in one commit each, because the two files are
  byte identical in these regions.
- Sub-phase: I.6. Test: `CompileKiteCodecCTaskTest` and `CompileKiteRtTaskTest` cases driving the
  real resolution against a Linux shaped and a Windows shaped dependencies tree, both measured to
  fail today, asserting each resolves. The jobs themselves stay unexecuted, so their evidence
  stays level 8 and no document may say otherwise until a run exists.

#### Register rows written by I-19, owned by later phases

These two are register rows and not interlude items: I-19's deliverable is that they exist where
a register search finds them, because both were previously recorded only inside log entry prose.

**R-B2-guards. Eighteen exported entry points crash on an argument.**
- Where: `../KiteCodec/native/kitecodec-c/src/helpers_frame.c:30` (`ffkmp_frame_get_buffer`);
  `helpers_codecpar.c:25` and `:30` (`ffkmp_codecpar_from_context`,
  `ffkmp_codecpar_copy_for_mux`); `helpers_format.c:12`, `:21`, `:22`, `:27`, `:49`, `:56` and
  `:86` (`ffkmp_fmt_open_input` NULL out pointer, `ffkmp_fmt_find_stream_info`,
  `ffkmp_fmt_seek_micros` out of range stream index, `ffkmp_fmt_read_frame`,
  `ffkmp_fmt_alloc_output2` NULL out pointer, `ffkmp_fmt_set_opt` NULL key,
  `ffkmp_fmt_write_frame`); `helpers_codec.c:14` and `:15` (`ffkmp_codecctx_open`,
  `ffkmp_codecctx_from_par`); `helpers_filter.c:39`, `:85`, `:196`, `:205` to `:212`, `:235`,
  `:243` to `:255`, `:308` and `:311` (the filter graph builders and accessors).
- Problem: the frozen body's NULL guard discipline is inconsistent: roughly twenty helpers guard
  and these do not, and B1.4 made the inconsistency exported `KC_API` surface of a versioned
  archive. Twelve of the twelve probed reproduce as signal 11 at level 2, one fork per call
  against the committed host archive, with two calibration controls that guard and answer
  cleanly.
- Fix: B2, as one change through the generator plus its exclusion machinery, unblocked by I-12's
  retirement of the byte equality proof that made each hand edit expensive. Two of the eighteen
  are fixed in the interlude under I-12 because they had no owner at all: `ffkmp_fmt_set_opt`'s
  NULL key and `ffkmp_fmt_seek_micros`'s unbounded `stream_index`. The header contract sentence
  B2 must add to `include/kitecodec_helpers.h`, which today documents ownership only: for every
  argument-taking helper, state what a NULL or out of range argument returns, in the shape
  `ffkmp_fmt_set_opt`'s line 282 already uses for its context ("A NULL context is refused with
  AVERROR(EINVAL)") while saying nothing about the key that segfaults.
- Phase: B2. Test: one C case per guard, each proved able to fail by reverting its guard, in the
  suite that owns the unit.

**R-B8-layout. Nothing tests a device that answers with a layout it did not negotiate.**
- Where: the callback's contract checks in `kiteplayer-rt/native/src/kite_rt_coreaudio.c`; the
  B1 closing entry announced this row and never wrote it.
- Problem: the callback checks its buffer's size against the negotiated layout, two compares and
  a comment, but the present callback test seam cannot present the rejected shapes. Worse, the
  early returns do not zero every safely visible buffer or set
  `kAudioUnitRenderAction_OutputIsSilence`.
- Fix: S1.b.3, before the same callback is promoted to iOS. Extend the existing callback seam and
  `test_sink_callback.c`: a buffer list whose shape differs from the negotiated stream format
  must refuse the whole callback, zero every safely writable buffer, set the silence flag and
  never render into a shape it did not negotiate.
- Phase: S1.b.3. Test: wrong buffer count, null or zero-sized destination, short-size canary and
  correct-layout cases in the existing suite, with each refusal check deleted once as a negative
  control. Keep eight suites and remeasure the case total.

### 16.2 Sub-phases

Paths are relative to the repository they belong to. `../KiteCodec` means the KiteCodec
repository; an unprefixed path means KitePlayer. Every sub-phase ends with the standing gate that
I.1 installs, an Execution log entry, and one commit per repository touched. Contract items 1 to
13 apply unchanged, including the ban on branches, the ban on trailers and the ban on em dashes.

**Dependency order and why.** I.1 first, because it installs the gate the rest of the interlude is
measured by and the scan that finds what I.2 fixes. I.2 second, because the record must be correct
before any of it is pushed and because nothing in it can break code. I.3 third, because retiring
the extraction proof is what unblocks every later edit to the nine units, and because two ratchets
must stop firing on B2's own work before B2's first commit. I.4 fourth, because the instruments it
repairs are what the last two sub-phases are checked with. I.5 fifth, the only sub-phase that
changes shipped behaviour. I.6 last, because it touches build logic that only continuous
integration exercises.

#### I.1 The standing gate, and how every ratchet moves

Items: I-15.

Files: `KPKMP.md` (section 9, and the redundant widened scan in 15.2);
`../KiteCodec/.github/workflows/ci.yml`.

Steps.
1. Rewrite section 9's command block as the standing gate for every phase from here on: the
   existing KiteCodec and KitePlayer test tasks and sample runs, plus `:kitecodec-core:apiCheck`,
   `checkCinteropCoupling`, `../KiteCodec/native/kitecodec-c/scripts/symbol-audit.sh`,
   `../KiteCodec/native/kitecodec-c/scripts/klib-metadata-diff.sh --check`,
   `../KiteCodec/native/kitecodec-c/scripts/check-deleted-surface.sh`, both host C builds and test
   runs in every variant and mode, `../KiteCodec/native/kitecodec-c/scripts/replay-corpus.sh
   asan`, both `:buildSrc:test`, `checkLegacyAbi`, `kiteplayer-rt/native/scripts/render-audit.sh`
   and `kiteplayer-rt/native/scripts/source-discipline.sh`. State beside the block that a cached
   up to date run proves nothing, which section 2 already says, and that `apiDump` needs
   `-Pkitecodec.hostTargetsOnly=true` on this machine, which the B1 log recorded twice as a
   deviation and which 15.2's own gate lines never absorbed.
2. Replace section 9's em dash scan with `git ls-files -z | xargs -0 grep -n` over both
   repositories, and delete the widened extension form from 15.2 with one sentence saying why it
   was replaced rather than tightened again.
3. Add the ratchet move table to section 9: one row per baseline, with the file, what makes it
   fire, the exact command that moves it, and what the Execution log entry must say. Include the
   rows named in I-15 and the convention that a metadata re-baseline pastes the script's SUMMARY
   block into the entry.
4. In `ci.yml`, pin the FFmpeg the macOS job installs to an exact formula version, and add
   `check-deleted-surface.sh` to that job beside the coupling ratchet.

Gate. Run the promoted section 9 gate verbatim from a clean clone of both repositories; it must
pass. Run the new scan; it must report exactly the two `LICENSE` hits, which is the proof it can
see what the old form could not.

Commit first lines. KitePlayer: `Make section 9 the standing gate and say how every ratchet
moves`. KiteCodec: `Pin the FFmpeg continuous integration installs and run the deleted surface
check`.

#### I.2 The record, corrected

Items: I-16, I-17, I-18, I-19.

Files: `KPKMP.md`; `README.md`; `kiteplayer-rt/README.md`; `LICENSE`; `../KiteCodec/LICENSE`;
`../KiteCodec/README.md`; `../KiteCodec/CHANGELOG.md`;
`../KiteCodec/native/kitecodec-c/fuzz/README.md`; `../KiteCodec/.github/workflows/ci.yml`;
`../KiteCodec/native/kitecodec-c/include/kitecodec_abi.h`;
`../KiteCodec/native/kitecodec-c/tests/test_identity.c`.

Steps.
1. Correct the seven record defects of I-16 in place, each with the run that measured it named.
2. Correct the five public claim defects of I-17, including raising `KC_TEXT_SENTENCE` to 1152 and
   extending `test_identity`'s non truncation case to the worst case capacity.
3. Replace the em dash in both `LICENSE` files and reword KitePlayer's note so it describes
   KitePlayer.
4. Append the two register rows of I-19 to this section, one for the eighteen argument guards
   owned by B2 and one for the un-negotiated device layout owned by B8.

Gate. The standing gate of I.1, whose scan must now print nothing; the C suite in all three
variants for the capacity change; and a read of every corrected sentence against the state of the
repository at this commit, which is the multi-pass rule of section 9 applied to prose.

Commit first lines. KitePlayer: `Quote the runs the gate measured, not the ones it did not`.
KiteCodec: `Say that the continuous integration jobs are configured and have not run`.

#### I.3 The two ratchets that cannot move, and the guards one of them blocked

Items: I-12, I-13, I-14.

Files: `../KiteCodec/native/kitecodec-c/scripts/verify-lift.sh` (deleted);
`../KiteCodec/native/kitecodec-c/tools/extract_from_def.py` (deleted);
`../KiteCodec/native/kitecodec-c/scripts/check-deleted-surface.sh`;
`../KiteCodec/native/kitecodec-c/deleted-surface.txt` (new);
`../KiteCodec/native/kitecodec-c/src/helpers_format.c`;
`../KiteCodec/native/kitecodec-c/include/kitecodec_helpers.h`;
`../KiteCodec/native/kitecodec-c/tests/test_buffers.c`;
`../KiteCodec/buildSrc/src/main/kotlin/CheckCinteropCouplingTask.kt`;
`../KiteCodec/buildSrc/src/test/kotlin/CheckCinteropCouplingTaskTest.kt`;
`../KiteCodec/native/kitecodec-c/coupling-baseline.txt`;
`../KiteCodec/native/kitecodec-c/README.md`; `../KiteCodec/README.md`; `KPKMP.md` (B1-22's row and
section 15.4's rollback story).

Steps.
1. Run `verify-lift.sh` at `2b4287f` and capture its complete output. Paste it into the Execution
   log entry, including the payload digest
   `e63a7b56e4fe61a8f804d65b6066478dfa5e7eebcf5485685c327081391726ea` over 909 lines on both sides
   and all eleven comparison lines. That output is the permanent record of the lift's
   faithfulness.
2. Delete `scripts/verify-lift.sh` and `tools/extract_from_def.py`. Rewrite the README sections
   that tell a reader to "Change the def, or change the generator", which is an instruction that
   cannot be followed, so that they say the nine units are ordinary maintained sources and the
   lift is a recorded historical fact. Amend section 15.4 to say that rollback to a pre-lift state
   is no longer an option and what replaces it, which is the ordinary revert of the commits that
   changed a unit.
3. Create `deleted-surface.txt` with the fifteen names and a status column, make
   `check-deleted-surface.sh` read it, and delete its hardcoded list and its check 4 against the
   extractor. Write the resurrection procedure into the file's header.
4. Add the two guards: `if (!c || !k)` in `ffkmp_fmt_set_opt`, and a `stream_index` bound in
   `ffkmp_fmt_seek_micros` returning `AVERROR(EINVAL)` for an index outside `0` to
   `ctx->nb_streams` with `-1` still meaning any stream, which is what the only Kotlin caller
   passes. Document both in `kitecodec_helpers.h` beside the ownership sentences.
5. Rework `CheckCinteropCouplingTask` per I-13: one ratcheted `ffmpeg_typed_crossings` at 287, the
   two components reported and not ratcheted, comments and KDoc stripped before counting, and
   count 4 a named allowlist of the eleven types. Correct the baseline's prose split and B1-22's
   row to nine hot sites and five lookup sites.

Gate. The standing gate. Additionally: the two new C cases, each proved able to fail by reverting
its guard; the three new coupling cases, each measured at this review to behave the opposite way
today; `klib-metadata-diff.sh --check` unchanged, because neither guard adds a declaration; and
`check-deleted-surface.sh` failing on a planted use site of every name still marked `deleted`.

Commit first lines. KiteCodec, three commits in this order: `Record that the lift was faithful,
then retire the proof that froze it`; `Refuse a NULL option key and an out of range stream index`;
`Count FFmpeg coupling so that reducing it cannot fail the ratchet`. KitePlayer: `Say that
rollback to a pre-lift state is no longer available`.

#### I.4 The instruments that can go blind

Items: I-07, I-08, I-09, I-10, I-11.

Files: `../KiteCodec/buildSrc/src/main/kotlin/CompileKiteCodecCTask.kt`;
`../KiteCodec/buildSrc/src/test/kotlin/CompileKiteCodecCTaskTest.kt`;
`../KiteCodec/kitecodec-core/build.gradle.kts`;
`../KiteCodec/native/kitecodec-c/include/kitecodec_abi.h`;
`../KiteCodec/native/kitecodec-c/scripts/klib-metadata-diff.sh`;
`../KiteCodec/native/kitecodec-c/scripts/symbol-audit.sh`;
`../KiteCodec/native/kitecodec-c/exported-symbols-baseline.txt` (new);
`../KiteCodec/native/kitecodec-c/tests/harness.h`;
`../KiteCodec/native/kitecodec-c/tests/harness.c`;
`../KiteCodec/native/kitecodec-c/scripts/run-c-tests.sh`;
`../KiteCodec/native/kitecodec-c/README.md`; `kiteplayer-rt/README.md`;
`buildSrc/src/main/kotlin/CompileKiteRtTask.kt`;
`buildSrc/src/test/kotlin/CompileKiteRtTaskTest.kt`;
`kiteplayer-rt/native/scripts/source-discipline.sh`; `KPKMP.md` (the B1.9 five checks sentence).

Steps.
1. Make the FFmpeg include tree a tracked input and add the klib against archive version assertion
   to `klib-metadata-diff.sh`. Correct the two `kitecodec_abi.h` comments to name which compile
   they describe.
2. Generate `exported-symbols-baseline.txt` from `symbol-audit.sh` and add the sixth check. Make
   the bare form of `klib-metadata-diff.sh` exit non-zero on a mismatch.
3. Port the `interpose` mode and `KC_REQUIRE_ALLOC_ACCOUNTING` into `kitecodec-c`'s harness, add
   the mode to `run-c-tests.sh` and to the standing gate, and write the two repository harness
   pairing rule into both READMEs.
4. Add the call site test for `verifyObjectArchitecture` in both repositories, and copy across the
   three guards each suite was missing.
5. Extend `source-discipline.sh` from five checks to eighteen with one negative control each, and
   correct the B1.9 entry's sentence.

Gate. The standing gate, now including the new `interpose` mode and the eighteen check discipline
script. Additionally every new check must be proved able to fail: the header content change with
no path change, the declared probe export, the one word section rename, the deleted guard call
site, and thirteen ordering mutants.

Commit first lines. KiteCodec, two commits: `Track the FFmpeg headers the archive freezes, and
compare both bakings`; `Make a blind interposer and a grown surface fail the gate`. KitePlayer:
`Pin every ordering decision the design took, and the guard call site`.

#### I.5 The real-time seam

Items: I-01, I-02, I-03, I-04, I-05, I-06.

Files: `kiteplayer-rt/native/src/kite_rt_ring.c`; `kiteplayer-rt/native/src/kite_rt_render.c`;
`kiteplayer-rt/native/src/kite_rt_ring_internal.h`; `kiteplayer-rt/native/include/kite_rt.h`;
`kiteplayer-rt/native/tests/test_ring_alloc.c`; `kiteplayer-rt/native/tests/test_ring_basic.c`;
`kiteplayer-rt/native/tests/test_ring_rescale.c`;
`kiteplayer-rt/native/tests/test_ring_threads.c`; `buildSrc/src/main/kotlin/CompileKiteRtTask.kt`
(one comment);
`kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt`;
`kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt`;
`kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/KotlinAudioRing.kt`
(only where the oracle needs the same answer);
`kiteplayer-core/src/nativeTest/kotlin/io/github/yuroyami/kiteplayer/AudioRingDifferentialTest.kt`;
`kiteplayer-core/src/commonTest/kotlin/io/github/yuroyami/kiteplayer/AudioPlaybackTest.kt`;
`kiteplayer-core/src/commonTest/kotlin/io/github/yuroyami/kiteplayer/PlaybackCoreTest.kt`.

Steps.
1. Bound the product in `kprt_ring_create` and add the three compile time assertions, plus the
   pointer width sentence in `CompileKiteRtTask.specFor`'s comment.
2. Refuse a second `begin_write` while a reservation is outstanding and correct `kite_rt.h:210` to
   `:211`.
3. Saturate the two signed arithmetic sites and state the saturating behaviour in both header
   comments; mirror the decision in `KotlinAudioRing` only where the oracle compares the two, so
   the oracle keeps comparing two correct implementations rather than two matching ones.
4. Make the three reservation fields `_Atomic`, correct the two false comments in
   `kite_rt_ring_internal.h`, and add the anchor reader to the quiescence sentence in `kite_rt.h`.
5. Move `ring?.flush()` inside the lock in `AudioPlayback.flush`; extend the one sentence lock
   rule to `submit` and `submitDecoded`; add the quiescence precondition to `close`'s KDoc; wrap
   `teardownSession`'s cancel and join pair in `withContext(NonCancellable)`.
6. Close the failed open leak in `buildSession` and correct its catch comment.

Gate. The standing gate. Additionally: the new C cases under the asan variant so
UndefinedBehaviorSanitizer sees the two saturating sites; the new `test_ring_threads` flusher case
under tsan, which must be clean and which was measured to report a race before the change; the
differential oracle with its new rows; and the two new Kotlin cases, each proved able to fail by
reverting the line it covers. The supervised device run is NOT required for this sub-phase:
nothing here changes the callback body, and `render-audit.sh` plus the interposed C test are what
carry that claim. Say so in the entry rather than leaving a reader to wonder why fifty minutes of
sound is missing.

Commit first lines. KitePlayer, two commits: `Bound the ring by the byte count it will actually
allocate`; `Make a close wait for the feeder before anything frees a ring`.

#### I.6 The build host, and the closing entry

Items: I-20.

Files: `../KiteCodec/buildSrc/src/main/kotlin/CompileKiteCodecCTask.kt`;
`../KiteCodec/buildSrc/src/test/kotlin/CompileKiteCodecCTaskTest.kt`;
`buildSrc/src/main/kotlin/CompileKiteRtTask.kt`;
`buildSrc/src/test/kotlin/CompileKiteRtTaskTest.kt`; `KPKMP.md` (the closing entry).

Steps.
1. Derive the Android toolchain package infix from the build host in both repositories.
2. Resolve `clang` and `llvm-ar` by bare name and then by `.exe` name, in all four places in each
   repository.
3. Write the interlude's Execution log entry: what landed per sub-phase, every number the closing
   gate measured, the instruments retired and re-anchored with what replaces each guarantee, the
   deviations with the evidence that forced each, and the statement that the four continuous
   integration jobs remain unexecuted so their evidence is level 8.

Gate. The standing gate, rerun for real in both repositories, plus the four new build logic cases.

Commit first lines. KiteCodec: `Name the build host in the toolchain package it is named after`.
KitePlayer: `Name the build host in the toolchain package it is named after` and `Record the
interlude, its measurements and its retired instruments`.

### 16.3 Instruments retired, re-anchored, and what replaces each guarantee

This is the part a later reader needs most, because an instrument that disappears without a
replacement is how a guarantee is lost quietly.

**Retired: `verify-lift.sh` and `tools/extract_from_def.py`.** They carried three things. First,
that the nine units are byte for byte the def body at `5364329` minus the fifteen deletions. That
becomes a historical fact, recorded with its digests in the I.3 log entry, verified once more at
`2b4287f` immediately before deletion; a one time event does not need a permanently re-executable
proof, and a re-executable proof that forbids every future edit is a liability. Second, that
nobody hand edits generated code unnoticed. After retirement there is no generated code: the nine
units become ordinary maintained sources, and what holds their shape is the export baseline of
I-09, the six C suites in three variants plus the new `interpose` mode, `symbol-audit.sh`'s five
other checks, and `klib-metadata-diff.sh --check`. Third, the rollback story of section 15.4 for
the one irreversible sub-phase. That guarantee is withdrawn rather than replaced, and 15.4 must
say so: rollback to a pre-lift state stops being available, and what replaces it is the ordinary
revert of whichever commit changed a unit.

**Re-anchored: `coupling-baseline.txt`.** Counts 2 and 3 collapse into one ratcheted number,
`ffmpeg_typed_crossings`, at 287 today. The guarantee before was "the deferred coupling can only
shrink", and it was false in one direction, because the improvement that reduces coupling most
raised count 2. The guarantee after is the same sentence, now true: a category move is neutral, a
genuine reduction shows as a fall, and the two components remain visible as reported detail so
nothing is hidden. Count 4 becomes a named allowlist of the eleven struct types, so the guarantee
changes from "the number eleven does not rise" to "no new FFmpeg struct type reaches Kotlin
without being named in a commit", which is stronger and is what the deferral actually needs.
Comments and KDoc leave the measurement, because a comment is not coupling and the count must not
punish B2 for documenting its own work.

**Re-anchored: `check-deleted-surface.sh`'s list, into `deleted-surface.txt`.** Same fifteen
names, same power over every name marked `deleted`. What is added is a move: one line changed to
`resurrected-in-<item>` plus one Execution log sentence. Check 4 against the extractor's table
disappears with the extractor, and what replaces it is that there is now exactly one copy of the
list instead of three.

**Re-anchored: `source-discipline.sh`, five checks to eighteen.** Before, it proved that five
ordering decisions are still written where the design put them, and the record read as though that
were the ordering front. After, it proves the same thing for all eighteen. Its level does not
change: it is level 4, a text check, and it remains the right shape for a decision that was
reversed rather than for a property, which its own header says and which stays.

**New: `exported-symbols-baseline.txt`.** Nothing carried this before. It replaces a false
impression rather than a real guarantee: `symbol-audit.sh` check 3 reads like a baseline and is a
consistency check, and the B1.4 log entry's sentence that the opaque surface is "deliberately not
ratcheted in either direction because it is meant to grow; `symbol-audit.sh` holds it to a decided
set" is wrong in its second clause. After I-09 the second clause is true.

**New: `KC_REQUIRE_ALLOC_ACCOUNTING` and the `interpose` mode in `kitecodec-c`.** It replaces
nothing that existed and it protects something that did: deferral 2's decision to deliver
ownership guarantees as pairing tests instead of a compiler attribute. Before, those tests could
all report "cannot observe" and pass. After, an ineffective interposer is a failure, which is the
same distinction `kiteplayer-rt` already draws.

**Unchanged and deliberately so.** `kitecodec-core.klib.api` and the four KitePlayer api dumps
keep `apiDump` as their move, which was verified to reproduce byte for byte at this review.
`klib-metadata-baseline.txt` keeps its content and gains only a gate form and a log convention.
`KITECODEC_C_ABI_MAJOR` and `KITECODEC_C_ABI_MINOR` gain no ratchet here: B2 is the first item
that grows the `kc_` surface, so the rule belongs in the same commit as the first growth, and it
is in section 16.4.

### 16.4 Not in the interlude, and where each lands

Everything a review front proposed that is not in the register above, with its owner and the
reason it waits. A deferral whose cost is written nowhere is a deferral nobody weighs again.

1. **The sixteen remaining argument guards.** B2, as one change through the generator plus its
   exclusion machinery, now unblocked by I-12. Recorded as a register row by I-19. *If it never
   happens:* eighteen exported entry points of a public library keep crashing on a NULL, and B7's
   JNI bridge inherits them.
   **Closed by S1.a.7.** All sixteen remaining guards now reject their invalid arguments with
   `EINVAL`, while the six intentional nullable contracts remain positive controls in the
   22-case argument suite.
2. **A gate call in the C library itself.** B2. The identity gate is enforced by fifteen Kotlin
   call sites and the C library never calls `kc_init`, measured as zero references from any of the
   nine units. The cheap form is a gate call in the ten or so constructor helpers and not in all
   157, so no hot path pays. *If it never happens:* a JNI or C consumer reaches FFmpeg with no
   gate at all, which is the exit clause B1 met for one language only.
3. **An entry point audit for `requireCompatibleFFmpeg`.** B2. Fifteen call sites exist today and
   nothing keeps them complete as the surface triples. *If it never happens:* a forgotten call is
   silent on a healthy machine.
4. **A C ABI version ratchet.** B2 phase one, in the same commit as the first growth of the `kc_`
   surface, using the baseline I-09 commits. *If it never happens:* the library keeps claiming ABI
   1.0 while its surface grows, which is worse than no version number because a consumer may trust
   it.
5. **A rule tying a new string parsing entry point to a fuzz target, and the corpus gaps.** B2 for
   the rule and the seeds, B8 for the fuzzing. Measured: 103 files, 38,077 bytes, 64 distinct byte
   values, 9 with an embedded NUL, and the single quote, double quote, backslash, carriage return,
   semicolon and backtick all absent, which is exactly libavfilter's own escaping and chain
   separator alphabet. Two text entry points reachable from public Kotlin API have no target at
   all, `ffkmp_fmt_alloc_output2`'s `format` argument and the three raw `*_by_name` lookups. *If
   it never happens:* the parser paths that exist stay unexercised by anything but committed
   seeds.
6. **Splitting `test_convert.c` into contract and baseline.** B2, which owns the caching that
   changes the two per call allocation counts. The cheap half, a comment beside each of the two
   counts naming B2 as their owner, may ride with any commit. *If it never happens:* an
   implementer facing a red test changes a number instead of thinking.
7. **`symbol-audit.sh`'s `ALLOWED_UNDEFINED` entries for `pthread_mutex_*`.** B2, measured against
   the real change rather than guessed in advance, because a speculative entry weakens the audit.
   Named in B2's phase one notes so its first C commit expects two refusals at once.
8. **Deriving the C suite and fuzz target lists from the files on disk.** B2, cheapest fix on the
   whole list. Six hardcoded literals across both repositories, consistent today. *If it never
   happens:* a new suite added to `build-host.sh` and not to `run-c-tests.sh` is compiled and
   never run, with every gate green.
9. **An opt-in marker on `NativeRingHandoff`, and the decision about the exported `kprt_`
   surface.** B2 to mark it, B7 to decide it. `kiteplayer-core`'s committed dump exposes
   `NativeRingHandoff.ring: CPointer<cnames.structs/kprt_ring>` and, through
   `api(projects.kiteplayerRt)`, the whole `kprt_` cinterop surface; the build comment's reasoning
   that "Nothing public leaks by doing so" is inverted, because the generated bindings are the
   surface. Nothing is published from KitePlayer, verified, so the decision is free. Correct the
   comment whenever that file is next touched. *If it never happens:* B7 publishes a consumer's
   ability to destroy a ring under a running device.
   **Closed by S1.a.2.** The generated `kprt_` cinterop remains a deliberately public callable
   surface; `NativeRingHandoff` is marked with the error-level `RawRingApi` and all six
   compiler-required use sites opt in. The corrected build comment records that publication
   exposes the bindings; nothing was publicly published in S1.
10. **The Kotlin and C write asymmetry, the documented retry that is not the shipped retry, the
    two dead accessors, the `AudioRenderCallback` sentence the Kotlin ring contradicts, and the
    misattached KDoc block in `KotlinAudioRing`.** B2 or B4, all small, all recorded here so they
    are not rediscovered: `NativeAudioRing.write`'s `require` rejects short sources the Kotlin
    ring accepts; `kite_rt.h:203` to `:226` calls the shipped retry a programming error and
    documents a different one as supported; `writtenFrames` and `consumedFrames` have no reader
    and the oracle's KDoc claims both totals are compared when `assertSameState` compares neither;
    `AudioSink.kt:127` to `:131` says nothing above the sink zeroes the tail while
    `KotlinAudioRing.render` does exactly that.
11. **Teardown defence in depth, and the un-negotiated device layout.** B8, with a mock AudioUnit.
    The order in `kprt_sink_destroy` is right and was proved by disassembly rather than by the
    text check, and 280 real teardowns under both sanitizers were clean; what is recorded is that
    the platform promise is the only thing standing there, and that it becomes live the moment any
    path releases a ring without going through the sink that stopped the device. The register row
    is written by I-19.
12. **The four early returns in the device callback that leave the buffer unwritten and do not set
    `kAudioUnitRenderAction_OutputIsSilence`.** B2 or B8. `kite_rt_render.c:291` to `:297` zeroes
    the whole buffer for the same hazard and gives the reason; the callback applies the opposite
    policy. Zeroing every listed buffer and setting the flag adds no call, so the audited call set
    of `kprt_render_cb` is unchanged.
13. **The two forked harnesses.** B4 or B5 to decide whether one becomes the source and the other
    vendors a pinned copy with a digest. I-08 pays the first concrete cost and writes the pairing
    rule into both READMEs, which is the minimum. *If it never happens:* every instrument fix
    costs two edits in two repositories with two gates.
14. **KitePlayer continuous integration.** B8, per section 15.6 question 2, and B1-24's row now
    says so. One mitigation that costs nothing and is not new infrastructure: make "publish to
    mavenLocal and run the standing gate in KitePlayer" a step of every B2 sub-phase that changes
    a KiteCodec signature, not only of the sub-phase that adopts it.
15. **Failing `check-deleted-surface.sh` when its prose set is empty.** B2, a nicety. The
    allowlist proves at least eight files must match, so an empty set means the grep died rather
    than that the tree is clean. The committed gate is sound from a real checkout, which was
    measured; this is hardening and not a defect.
16. **Per target verification of both C libraries, and the single target metadata baseline.** B7
    and B9, unchanged from deferral 7. Everything measured in this review is one target, and
    `symbol-audit.sh`'s cross `nm` path for an ELF archive was not exercised.
17. **The opaque migration prototype.** B2, early rather than late. The whole of deferral 1 rests
    on the assumption that the migration works inside the one existing `ffmpeg` cinterop module,
    family by family. That assumption is supported by reconnaissance and was not re-proved at this
    review. One prototype of a single family, early in B2, converts an assumption into a
    measurement before B2's shape depends on it.

## 17. The road to 1.0

Written 2026-08-11 by Fable 5 under the owner's direction, after the B5/B6/B7 planning run and its
three adversarial verifications. This section supersedes section 11's items B2 to B11 as the map of what remains: section 11 stays as the historical roadmap, and every one of its obligations is
absorbed into a phase below. The B-numbering is retired; work from here is staged S1 to S7, each stage named by the outcome it delivers.

### 17.0 The nine goals, and the decisions that shape them

The owner set nine goals on 2026-08-11, recorded here verbatim in substance:

1. Works on Android playing all formats.
2. Works on iOS playing all formats.
3. Works on Windows, Linux and macOS playing all formats.
4. Works on Web playing all formats.
5. Smallest stub size possible.
6. One `implementation()` line to integrate, no plugin for ordinary use.
7. Easy to debug, customise and enhance.
8. Subtitles working.
9. FFmpeg powered everywhere: never trust platform demuxers or decoders as the source of truth,
   libmpv-grade consistency, never ExoPlayer-grade per-device fragility.

**Decisions taken by the owner, closed, not to be reopened without the owner:**

- **D-1, FFmpeg owns the pipeline on every platform.** Demuxing, decoding semantics, timestamps,
  seeking behaviour all come from FFmpeg through KiteCodec. Platform media APIs are never the
  source of truth. This kills the MediaCodec-as-backend proposal of 2026-08-10 and restores the
  JNI bridge as B7 always stated.
- **D-2, hardware acceleration only INSIDE FFmpeg, with software fallback.** FFmpeg's own
  `h264_mediacodec`/`hevc_mediacodec` decoders and the VideoToolbox hwaccel are the only hardware
  paths, selected at open, refused at runtime on misbehaviour, falling back to the same software
  pipeline with identical semantics. This is mpv's own design and is how goal 9's consistency is
  achieved without goal-9-violating shortcuts.
- **D-3, push rule.** The executor never pushes. Local commits only; the owner pushes. External or
  public publication and release steps are prepared by the executor and executed by the owner;
  `publishToMavenLocal` remains an executor-run build and consumption proof. This amends the
  working reading of contract item 3: the branch ban, trailer ban and em dash ban stand unchanged.
- **D-4, network is parked.** Goal scope is local file playback. The old B6 (network, live, adaptive) moves to a parked register at 17.8 with its costs stated. The engine's existing
  URL-open path stays undocumented rather than removed.
- **D-5, size is a policy, not a wish.** "All formats" and "smallest stub" oppose each other. The
  resolution is published profile tiers (17.6): a lean default artifact and opt-in fuller ones,
  with measured per-target sizes as exit criteria, using the profile machinery KiteCodec's plugin
  already has.
- **D-6, two Compose paths forever: interop baseline, Compose-true flagship.** Decided by the
  owner 2026-08-11. The S1.d Composable wraps AndroidView/UIKitView and stays as the BASELINE,
  because an embedded platform view keeps the hardware overlay path (best battery and HDR for
  sustained fullscreen). The FLAGSHIP is KiteVideo (17.9): decoded frames drawn through Compose's
  own rendering pipeline, so video is true Compose content, every Modifier applies to the video
  itself, and Compose on Desktop and Web becomes possible at all (no interop view exists there).
  D-1 is what makes this reachable: FFmpeg decoding means this project owns the decoded pixels; a
  MediaCodec-into-Surface player never sees them. Neither path replaces the other; the wrapper
  ships in S1, KiteVideo lands in slices per 17.9.

### 17.1 The stage law, and the reading order

Restructured 2026-08-11 at the owner's direction, because the first shape of this section ordered
work by engineering dependency and left the owner's primary outcome, a library USABLE on phones,
smeared across three phases. The law now: **every stage is named by the user-visible outcome its
completion delivers, and a stage is done when that outcome is demonstrable, not when its code
merges.** Prerequisites live INSIDE the stage that needs them. Engineering order still rules
inside a stage; it never defines a stage. One refinement: a stage exit may carry an explicitly
listed RIDER from a cross-stage package (today only KiteVideo, 17.9); the stage name follows the
bulk of its outcome, and a rider is never implicit.

Reading order for a NEW executor (this is how to onboard, not what to build first): section 18,
then sections 1, 2 and 9, then the register of the stage being executed, and nothing else until
it is needed. Build order is 17.2.

| Goal | Delivered by | Exit proof |
|---|---|---|
| 1 Android | S1 | format matrix plays on a named device from one dependency line |
| 2 iOS | S1 | format matrix plays on an iPhone (simulator here, device with owner) |
| 3 Desktop | macOS done; S3 for Windows and Linux | format matrix per named OS |
| 4 Web | S6 | matrix at stated resolution limits, spike-gated |
| 5 Size | S5 | measured artifact sizes per tier per target |
| 6 One line | S1 for the owner's apps (mavenLocal or private repo); S5 publicly | scratch consumer builds and plays |
| 7 DX | S4 | debuggability register closed |
| 8 Subtitles | S4 | old B3 exit criteria |
| 9 FFmpeg everywhere | every stage | D-1 and D-2 hold; no platform demuxer exists anywhere |

### 17.2 The stages

**Inter-stage ORDER superseded 2026-08-16 by 17.12 (mobile supremacy first, then desktop and
web, then libass, then the tail). Stage contents, registers and exits below stand unchanged.**

Each stage receives its section-15-style execution expansion AT ENTRY: located register items,
decided fixes, sub-phases with files, steps, gates and commit first lines, authored against the
tree at that time and adversarially verified before execution. Run on 2026-08-10 that ritual
caught ten blocking defects, including one false measured claim; a stage entered without its
expansion is a contract violation.

**S1. IT PLAYS ON PHONES.** Exit: one `implementation()` line in a Compose Multiplatform app (and
a plain-view app) plays the 17.5 format matrix on a named Android device and an iPhone, audio in
sync, seeking working, FFmpeg powered per D-1/D-2. Consumption at this stage is mavenLocal or the
owner's private repository; PUBLIC publication is deliberately not here (S5). Sub-stages, in
dependency order inside the stage:
  - **S1.a Foundations** (the register at 17.4, formerly named P0): the verifier corrections, the
    ratchet groundwork, and the opaque handle migration that the JNI bridge stands on.
  - **S1.b iOS backend**: kiteplayer-output splits AppKit-only code out of appleMain; iosArm64 and
    iosSimulatorArm64 targets; the CoreAudio sink qualified on iOS (the kiteplayer-rt C ring has
    cross-compiled for iOS since B1); AVAudioSession policy; a straightforward CPU-converter
    renderer into a caller-owned CALayer (the reusable view is S1.d; Metal is S2, deliberately:
    usable beats beautiful); an iOS sample.
  - **S1.c Android backend over the JNI bridge**: the already-portable engine stays JVM bytecode;
    KiteCodec gains complete JVM and Android actuals implemented by one dynamically registered JNI
    library over the kc_/ffkmp_ opaque C ABI; the existing LGPL Android FFmpeg profile produces
    arm64-v8a and x86_64, with MediaCodec selected inside FFmpeg per D-2 and decoded in buffer mode
    to CPU frames; AudioTrack pulls through the engine's KotlinAudioRing path; a caller-owned Surface
    receives the CPU converter output; the AAR carries exactly those two ABI libraries with 16 KiB
    ELF load segments, while the consuming APK stores and zip-aligns them and preserves
    extractNativeLibs=false. The complete execution expansion is 17.4.3.
  - **S1.d The pluggable views**: a new optional `:kiteplayer-phone` aggregate API-depends on
    `:kiteplayer-ffmpeg` and `:kiteplayer-output` and owns KitePlayerView for Android plus the iOS
    counterpart. That keeps output FFmpeg-free while giving a plain phone consumer one coordinate.
    The SEPARATE optional `:kiteplayer-compose` module depends on phone and exposes one Composable
    that wraps AndroidView on Android and UIKitView on iOS, so a non-Compose consumer never pulls
    Compose. This wrapper is the BASELINE Compose path per D-6; the Compose-true flagship
    (KiteVideo, 17.9) was deliberately NOT in S1 when this register was written. Owner amendment
    2026-08-12: KiteVideo's CORE rides S1.d as an explicit 17.1 rider, software-fed; every
    measurement exit stays in S2. The execution expansion is 17.4.4.
  - **S1.e Stage exit**: re-consume the provisional S1.b iOS host and S1.c Android application
    through the S1.d phone coordinate, run the matrix on both platforms, write every measured
    number, and complete the owner device session.

**S2. IT PLAYS BEAUTIFULLY ON APPLE.** (Expanded 17.4.8; entered 2026-08-12 by owner order,
after S4.a to S4.c, before S4.d to S4.g. The wide-profile order 17.4.9, entered 2026-08-13,
rode inside it between S2.d and S2.e and closed the same day, KiteCodec 0.0.5. CLOSED
2026-08-16: every sub-phase landed, the exit numbers live in the section 14 S2.e and S2.f
entries, and S4 resumes at S4.c's device proofs, then S4.d.) Exit: Metal
renderer on macOS and iOS, VideoToolbox inside
FFmpeg per D-2 with measured software fallback, colour instrument, vsync-snapped scheduling,
sustained 4K runs with committed thresholds. Absorbs draft items C-09 to C-31, C-33, C-48 to
C-50 with their verifier corrections. Also KiteVideo's first landing (17.9, KV-1 to KV-3): the
Compose-true core with draw-phase-only invalidation, the YUV image path, and the Apple zero-copy
handoff (CVPixelBuffer through CVMetalTextureCache onto Skiko's Metal context), riding the
VideoToolbox work already here. Exit gains: KiteVideo plays 1080p on iOS and macOS with Compose
modifiers applied (clip, alpha, rotation), per-frame cost and dropped frames measured.

**S3. IT PLAYS ON EVERY DESKTOP.** Exit: format matrix on named Windows and Linux machines.
WASAPI and ALSA/Pulse sinks in C inside kiteplayer-rt, same ring and audit discipline; desktop
rendering through the JVM path for Compose Desktop and native paths for K/N consumers; the mingw
and linux FFmpeg triples become consumable artifacts. KiteVideo rides here (17.9, KV-4 and KV-5):
the desktop per-frame upload path IS the Compose Desktop rendering named above, and the Android
software path lands as an explicit exit rider (one copy per frame over the S1.c converter, days
of work once KV-1/KV-2 exist; this is the stage where the JVM rendering paths mature). Exit rider:
the KiteVideo modifier demo runs on the S1 Android device. Absorbs the 17.11
renderer-lifecycle, audio-sink, hot-path and capability-negotiation rows.

**S4. IT EXPLAINS ITSELF.** (CLOSED 2026-08-16: every sub-phase landed, S4.f as its recorded
PARTIAL; the exit numbers and the remaining S4.f expansion live in the section 14 entries.)
Exit: subtitles per old B3, the debuggability register (diagnostics
dump API, logging policy, typed warning audit, SPI cookbook with a worked custom backend), facade
completion absorbed from old B11, and the KD piloting package (17.10): the typed filter DSL,
decoder and encoder option layers, playback profiles and their goldens, with KD's two C funnels
arriving earlier inside KiteCodec window 3. Absorbs the 17.11 subtitle and API-truth rows.

**S5. ANYONE CAN HAVE IT.** Exit: public artifacts. Size tiers of 17.6 measured per target; the
umbrella artifact; POMs, licences, readiness checks (named steps HERE, never in Tier 1 earlier);
publication PREPARED by the executor, EXECUTED by the owner per D-3. Absorbs draft C-32 to C-42
and the 17.11 build and publication rows.

**S6. IT PLAYS ON THE WEB.** (The spike RAN on 2026-08-17 and PASSED; its report is
`docs/spikes/2026-08-17-web-spike.md` and its verdict, numbers and 178-to-272-hour cost are in the
section 14 W.9 entry. Do not re-run it; enter S6 at the KV-6 draw-cost probe the verdict names as
its first item. EXPANDED in 17.14, where that probe is register item X-01 and a stop gate.) Spike first, timeboxed, measured (FFmpeg-to-wasm size and decode
throughput, threads and SIMD, the JS interop shape over the same C ABI); build only if the spike
clears its bar; exit criteria carry the physics honestly (software decode, 1080p target, 4K a
stated non-goal of v1). If the spike fails, web ships engine-only and the register says so.
KiteVideo (KV-6) is the ONLY Compose rendering story here: no interop view exists on wasm, so the
spike measures its per-frame draw cost alongside decode throughput.

**S7. IT IS 1.0.** Per-platform soak matrix, conformance suite everywhere, CI actually running
(level 8 until then), fuzz jobs executed, tier promotions per section 3, the parked security
rows. 1.0 is declared here and nowhere earlier.

### 17.3 Stage estimates and KiteCodec windows

KiteCodec changes are batched so KitePlayer re-consumes as few times as possible: window 1 inside
S1.a (opaque migration and guards); window 2a inside S1.b (the iOS standard software-playback
FFmpeg build, local-only Apple target publication and Local consumer wiring); window 2b inside
S1.c (the C/JNI bridge and complete JVM/Android actuals, followed by one local-only phone-superset
publication that preserves the window-2a Apple variants); window 3 inside S2 (VideoToolbox device
context, plus KD's two option funnels from 17.10: pre-open format options and chapter accessors);
window 4 inside S5 (runtime artifacts); and window 5 inside S6 (wasm binding) if the spike
passes.

| Stage | Hours, honest range | Dominated by |
|---|---|---|
| S1.a | 25 to 35 | four check tasks, opaque migration completion, corrections |
| S1.b | 55 to 75 | mobile FFmpeg provisioning, the appleMain split, iOS audio and the Xcode host |
| S1.c | 140 to 190 | JNI actuals with a shared differential suite across JVM and native |
| S1.d + S1.e | 35 to 55 | the phone aggregate, two view surfaces, the Compose baseline, the KiteVideo core rider (17.4.4), two app re-consumptions, the matrix runs |
| S2 | 105 to 150 | Metal renderer 30 to 40; VideoToolbox in KiteCodec; colour and vsync; KiteVideo KV-2/KV-3 plus KV-1's measurement exits at 20 to 30 |
| S3 | 70 to 108 | two C audio sinks with their instruments; KiteVideo KV-5 plus KV-4's demo and device numbers at 8 to 13 (KV-4's core moved to the 17.4.6 rider) |
| S4 | 90 to 125 | subtitle rendering correctness; KD piloting package (17.10) at 30 to 45 |
| S5 | 40 to 60 | cheap in hours, irreversible in consequence |
| S6 | 80 to 120 | the spike bounds it; failure path is cheap |
| S7 | 60 to 90 | soak time and owner device sessions |

**S1 in total: 255 to 355 hours to the owner's first outcome.** Whole road: 710 to 1015 focused
hours, network excluded. The earlier phase-shaped totals reconcile: the same work moved between
containers, plus the iOS-usable slice pulled forward out of the old P1. The growth over the first
staging (45 to 70 hours across S2 and S3) is KiteVideo (17.9), added by owner decision D-6 on
2026-08-11; its web slice sits inside S6's existing spike bound. The 2026-08-12 growth (30 to 45
hours in S4, 3 to 5 inside window 3) is the KD piloting package (17.10), added at the owner's
direction. The 2026-08-12 KiteVideo-core rider (17.4.4) moves 10 to 15 hours from S2 into S1.d,
which is why S1 grew while the road total did not.

### 17.4 The S1.a register, decision complete

Every item below comes from a verifier finding of 2026-08-10 (all three reports are in the run
records; the findings were independently spot-checked) or from an owner decision. The item IDs
keep their original P0- prefix, since the 2026-08-11 log entry already refers to them; P0 and
S1.a name the same work package. S1.a is expanded NOW because it is next; later sub-stages and
stages expand at entry per 17.2's rule.

#### P0-01. Contract item 3's working reading is D-3
- Where: KPKMP.md section 1 item 3; every publication-touching step in S5.
- Problem: item 3 says local commits only and never push, written when pushing was deferred; both
  repositories are now pushed and publication is on the road. Three verifiers flagged the plan
  amending every rule except this one.
- Fix: append the D-3 reading to item 3: executor never pushes, owner pushes, external/public
  publication and release steps are prepared-not-executed by the executor, while
  `publishToMavenLocal` remains an executor-run build step. Branch, trailer and em dash bans
  unchanged.
- Sub-phase: S1.a.1. Test: prose only; the tier 1 scan and a reread.

#### P0-02. The kiteplayer-rt coordinate question is decided: it publishes
- Where: kiteplayer-core/build.gradle.kts nativeMain api(projects.kiteplayerRt); section 16.4
  item 9.
- Problem: three verifiers independently proved the draft's api-to-implementation demotion does
  NOT remove an unpublished sibling from published Kotlin/Native metadata, leaving an
  unresolvable coordinate.
- Fix: kiteplayer-rt becomes a published coordinate (publish plugin, POM, abiValidation on),
  with the RingHandle opt-in annotation closing 16.4 item 9's exposure in the same change.
  Merging it into kiteplayer-core was rejected: the module boundary carries its own C build,
  tests and audits, and folding them would blur the render-audit's object scope.
- Sub-phase: S1.a.2. Test: a scratch consumer resolves kiteplayer-core from mavenLocal with no
  unresolved coordinate; the readiness task (P0-03) stops naming kiteplayer-rt.

#### P0-03. CheckPublicationReadinessTask exists but joins no tier until S5
- Where: new, KitePlayer buildSrc.
- Problem: the draft installed it in Tier 1 while designing it to be red for twenty-four
  sub-phases, which section 9 forbids twice over.
- Fix: build the task now (POM completeness, sibling-coordinate publishability), run it as a
  NAMED STEP inside S5's sub-phases only. It enters no tier block before S5, and when it enters
  it must be green the same commit.
- Sub-phase: S1.a.3. Test: task unit tests; proved able to fail on a module lacking a POM.

#### P0-04. The KitePlayer coupling baseline starts at its MEASURED number
- Where: new coupling-baseline at KitePlayer root; the draft claimed zero.
- Problem: the claim "measured at zero" was false. The historical extension-limited grep
  reported eight files; a literal tracked-module scan on 2026-08-11 found nine: seven Kotlin
  files, kitert.def matching its package declaration, and kiteplayer-core's committed api dump.
  Neither is the scoped, comment-stripped Kotlin measurement this task needs. A ratchet born on
  a false number is the exact defect class the interlude repaired.
- Fix: measure at S1.a.4 execution time, write the real number with the producing command beside
  it, ratchet from there; kiteplayer-output is excluded by design (it owns the C sink pointer).
- Sub-phase: S1.a.4. Test: the check task fails on a planted new naming site; passes at baseline.

#### P0-05. The plugin's two known-failing tests get a named exclusion mechanism
- Where: ../KiteCodec/kitecodec-gradle-plugin test task; contract rule 5.
- Problem: adding :kitecodec-gradle-plugin:test to any tier with two tests that fail on a clean
  checkout makes that tier permanently red; "ignored" named no mechanism.
- Fix: test-task filter excluding exactly the two named tests, comment citing contract rule 5,
  tracked by a register row so the exclusion cannot silently grow.
- Sub-phase: S1.a.5. Test: the suite passes with the filter; removing the filter reproduces the
  two known failures and nothing else.

#### P0-06. The phantom helper sentence in coupling-baseline.txt is corrected
- Where: ../KiteCodec/native/kitecodec-c/coupling-baseline.txt line 13.
- Problem: prose describing an interlude ratchet experiment reads as if ffkmp_codecctx_send_packet
  exists; a planner consumed it as fact within hours. Zero hits in the headers: it never existed.
- Fix: reword to name it as a hypothetical mutation vector; add the sentence "no such helper
  exists" so it can never be harvested as an API again.
- Sub-phase: S1.a.6. Test: tier 1 reread; grep for the name finds the corrected prose and one
  synthetic mutation string in CheckCinteropCouplingTaskTest, but no declaration or definition.

#### P0-07. The opaque handle migration completes (B1-25 paid)
- Where: ../KiteCodec def with 36 FFmpeg headers; ten Kotlin files in the import census, split
  into six carrying migration material and four compile-proof-only (raw FFmpeg pointer types are
  in five, with FFmpeg.native.kt carrying the sixth file's raw lookups); fourteen raw libav call
  sites; five AVMEDIA_TYPE_* enum constants
  (MediaSource.native.kt:553 to :557, the verifier's catch the draft missed).
- Problem: publication freezes FFmpeg struct layout as public API forever (draft C-43); the JNI
  bridge of S1.c needs the opaque C ABI as its boundary; sixteen exported entry points still crash
  on NULL at that exact boundary (R-B2-guards).
- Fix: the draft's C-43/C-44/C-45 design as corrected by the verifiers: compatible-addition half
  first (eleven opaque aliases, seven wrappers over fourteen call sites, five media-type
  accessors and the sixteen guards), then the def and public helper header lose FFmpeg headers,
  existing signatures respell to the opaque aliases, Kotlin migrates, and the C ABI major ratchet
  moves with a NEW signature baseline file (a names-only baseline cannot see shape changes:
  verifier M4).
- Sub-phase: S1.a.7 and S1.a.8, the reversible half gated before the irreversible half, exactly as
  B1.3's lift was. Test: metadata differential proves the first half contains only compatible
  additions plus the declared minor-version replacement; the migration half re-proves every
  suite; guards reproduction-first like I-12's two.

#### P0-08. Record corrections
- Where: KPKMP section 13 stale remote-state sentence (draft C-07); README network sentence
  (draft C-08); section 11 supersession note.
- Problem: the record must match the tree before a new horizon builds on it; the interlude's
  whole I.2 exists because it once did not.
- Fix: correct each in place with the measurement beside it, per the established style.
- Sub-phase: S1.a.9. Test: tier 1; reread of every corrected sentence.

### 17.4.1 The S1.a sub-phases, execution detail

Authored 2026-08-11 by Fable 5 against the tree as it stands (KitePlayer at 06b3ec8, KiteCodec at
a086b49, both clean). Verification note, stated per 18.3 rule 8: the adversarial pass on this
expansion was a single-threaded hostile reread by the same model that authored it, because the
owner's standing rule forbids Fable 5 from spawning agents; it re-derived every file, line and
count below from the tree and corrected three upstream claims (named inline where they occur).
That is weaker independence than the 2026-08-10 three-verifier ritual. Mitigation, binding on the
executor: **your first act is S1.a.0, mechanical verification, run as ONE FULL SWEEP.** Check
every file path, line number, symbol name and count in this expansion against the tree. Complete
the WHOLE sweep even after finding a mismatch, then deliver ONE consolidated report classifying
every finding: BLOCKING when the mismatch changes anything a step acts on (a file set, a line
target, a symbol, a gate, a commit line, a number a step would write), DESCRIPTIVE when only the
surrounding explanation is wrong and every action stands as written. Any BLOCKING finding: stop
after the report and wait for the corrected plan UNLESS the run-specific owner-direction
exception below applies. All findings DESCRIPTIVE: report them and PROCEED to S1.a.1 without
waiting; the planner folds the corrections into this section at its next pass, and the executor
never edits the plan except under that same explicit exception. Zero findings: proceed. This
calibration was
added 2026-08-11 after the first two S1.a.0 runs each stopped serially on one descriptive
finding apiece, costing the owner a relay round-trip per defect; sweep-then-classify keeps every
catch and caps the cost at one relay per sweep. Outside the run-specific exception immediately
below, 18.3 rule 5 is untouched and contradictions met DURING a sub-phase stop immediately.

Owner direction for the execution begun 2026-08-11: the executor may resolve a mechanical
S1 planning contradiction without a relay when the tree and the existing decisions leave one
conservative answer. It must record the correction, run Tier 1, commit it separately, and rerun
the complete applicable sweep before product work continues. This exception applies both to an
S1.a.0 finding and to a mechanical contradiction found during an S1 sub-phase. It does not widen
product scope, permit external publication or pushing, or permit guessing through an irreversible
choice; any such decision still stops for the owner.

Execution order is numbered order. Hard ordering constraints: S1.a.7 strictly before S1.a.8 (the
reversible half gates the irreversible one, B1.3's lift precedent); S1.a.2 before S1.a.3's final
cross-check run. S1.a.1, S1.a.6 and S1.a.9 are prose-only and may interleave anywhere.

#### S1.a.1 Contract item 3 reads through D-3

Items: P0-01.

Files: `KPKMP.md` section 1 item 3 (the two lines beginning "Never create a git branch").

Steps.
1. Append to item 3: the D-3 reading. The executor never pushes; the owner pushes; external or
   public publication and release steps are prepared by the executor and executed by the owner.
   `publishToMavenLocal` remains an executor-run build and consumption step. The branch ban, the
   trailer ban and the em dash ban stand unchanged.

Gate. Tier 1 (rule: prose only). Reread of section 1 as a whole.

Commit first line. KitePlayer: `Read contract item three through decision D-3`.

#### S1.a.2 kiteplayer-rt publishes, and the raw ring surface takes an opt-in

Items: P0-02. Closes section 16.4 item 9.

Files: `kiteplayer-rt/build.gradle.kts`; `settings.gradle.kts` (the kiteplayer-rt module note);
`kiteplayer-core/build.gradle.kts` (the dependency
comment around lines 85 to 101); `kiteplayer-core/src/nativeMain/kotlin/io/github/yuroyami/
kiteplayer/spi/NativeRingAudioSink.kt` (holds `NativeRingHandoff`); `kiteplayer-core/src/
nativeMain/kotlin/io/github/yuroyami/kiteplayer/internal/AudioPath.native.kt`;
`kiteplayer-core/src/nativeMain/kotlin/io/github/yuroyami/kiteplayer/internal/NativeAudioRing.kt`
(the file-level cinterop opt-in over its two `memcpy` calls);
`kiteplayer-rt/src/nativeMain/kotlin/io/github/yuroyami/kiteplayer/rt/PublicationAnchor.kt` (new,
package and explanatory comment only, no declaration);
`kiteplayer-output/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/output/CoreAudioSink.kt`;
`kiteplayer-output/src/appleTest/kotlin/io/github/yuroyami/kiteplayer/output/
CoreAudioSinkTest.kt`; `RealTimeSoakTest.kt` and `CoreAudioSinkRealTimeTest.kt` beside it; the
committed kiteplayer-core api dump; the new declaration-free committed kiteplayer-rt api dump.

Steps.
1. Apply `alias(libs.plugins.vanniktech.publish)` to kiteplayer-rt exactly as the four siblings
   do. Coordinates come from the root build.gradle.kts allprojects block at lines 13 to 16
   (group io.github.yuroyami, version 0.0.1), giving `io.github.yuroyami:kiteplayer-rt:0.0.1`.
   gradle.properties carries matching GROUP and VERSION values that the build never reads;
   leave them untouched, and do not "unify" the two mechanisms here (S1.a.0's second run caught
   this expansion claiming the properties were the source; the block is). Publication remains
   LOCAL: nothing in this sub-phase pushes or releases anything, per D-3.
2. Add `PublicationAnchor.kt` to nativeMain with only its package declaration and an explanatory
   comment, and no Kotlin declaration or callable API. This is the empty main-klib carrier the
   Kotlin publication model requires around the separately published cinterop klib. Measured on
   the first execution: without a main source, `compileKotlinAndroidNativeArm64` was `NO-SOURCE`
   and `generateMetadataFileForAndroidNativeArm64Publication` failed because its expected main
   klib did not exist; with this API-free anchor, both the main-klib task and metadata task pass.
   Do not invent a marker object or expose a Kotlin symbol just to make the artifact exist. Update
   the kiteplayer-rt build-file module note from "no Kotlin sources" to the exact state: no Kotlin
   declarations or callable API, with one package-only publication carrier. In both that note and
   the kiteplayer-rt module note in settings.gradle.kts, replace "publishes exactly one thing" with
   the exact two-artifact statement: the module exposes one callable surface, the `kitert` cinterop
   klib, beside the declaration-free main publication klib.
3. Declare `abiValidation {}` as the siblings do and run the dump task. Commit the generated
   `kiteplayer-rt/api/kiteplayer-rt.klib.api`; it is declaration-free, proving that the carrier
   introduced no Kotlin API while the separately published `kitert` klib remains the
   module's callable surface. Keep `render-audit.sh`, `source-discipline.sh` and the kitert def as
   the generated binding's additional ABI witnesses.
4. Add a `@RequiresOptIn` annotation (name it `RawRingApi`, in kiteplayer-core's nativeMain
   beside the SPI) and mark `NativeRingHandoff`, whose `ring: CPointer<cnames.structs/kprt_ring>`
   is the exposure 16.4 item 9 measured. The marker propagates through
   `NativeRingAudioSink.openWithRing`, which returns the marked type, so the affected set is
   SIX files, not two (S1.a.0's third sweep caught the two-file claim): NativeRingAudioSink.kt
   (declaration), CoreAudioSink.kt, AudioPath.native.kt (the openWithRing caller at :28 to :36),
   and the three output tests CoreAudioSinkTest.kt, RealTimeSoakTest.kt and
   CoreAudioSinkRealTimeTest.kt. The COMPILER is the final enumerator: opt in wherever it
   demands, and paste the final opted-in file list into the log. Update kiteplayer-core's
   committed api dump; the log entry states the dump moved because the annotation is now part
   of the surface.
5. Correct the inverted comment in kiteplayer-core/build.gradle.kts ("Nothing public leaks by
   doing so"): the generated bindings ARE the surface; publishing makes them public; the opt-in
   is what marks them deliberate.
6. Opt `NativeAudioRing.kt` into Kotlin/Native's error-level `kotlinx.cinterop.UnsafeNumber` marker
   at file scope beside `ExperimentalForeignApi`. Local publication runs the shared
   `compileNativeMainKotlinMetadata` task that ordinary per-target compilation did not; its first
   run rejected both `platform.posix.memcpy` calls because `size_t` is `UInt` on four declared
   targets and `ULong` on the other thirteen. The explicit platform-number opt-in is the narrow
   fix: it preserves the existing `.convert()` calls and every target-specific signature. Prove it
   with `./gradlew :kiteplayer-core:compileNativeMainKotlinMetadata --rerun-tasks` before retrying
   publication; no copy loop, C helper, declaration or public signature changes here.
7. Scratch consumer proof: publish kiteplayer-rt and kiteplayer-core to mavenLocal, then build a
   scratch macosArm64 consumer (outside both repositories) whose only dependency is
   `io.github.yuroyami:kiteplayer-core:0.0.1` from mavenLocal. It must resolve with no
   unresolvable coordinate and compile a trivial use of the facade.

Gate. Tier 2 (rule: build.gradle.kts changed), plus the scratch consumer as a named step.

Commit first line. KitePlayer: `Publish kiteplayer-rt as its own coordinate behind an opt-in ring
surface`.

#### S1.a.3 The publication readiness check exists and joins nothing

Items: P0-03.

Files: `buildSrc/src/main/kotlin/CheckPublicationReadinessTask.kt` (new);
`buildSrc/src/test/kotlin/CheckPublicationReadinessTaskTest.kt` (new); root `build.gradle.kts`
(task registration only); `KPKMP.md` section 9 (one sentence naming where the task runs).

Steps.
1. Implement two checks over every module that applies the publish plugin: POM completeness
   (group, version, name, description, licence, scm) and sibling publishability (every
   `project(...)` dependency of a publishing module must itself publish, the exact defect class
   P0-02 closed).
2. Register it at the root as `checkPublicationReadiness`. It enters NO tier block. Add one
   sentence to section 9: this task runs as a named step inside S5 only, and must be green in the
   same commit that adds it to any block.
3. Tests, reproduction-first where a red case is claimed: a fully configured fixture passes; a
   fixture missing a POM field fails naming the field; a fixture whose publishing module depends
   on a non-publishing sibling fails naming both modules; the failure messages name the fix.
4. The final cross-check, defined against P0-03's deliberate pre-S5 red state: after S1.a.2 and
   this task both exist, run `./gradlew checkPublicationReadiness` once, by hand. Expected result:
   NONZERO for the incomplete POM fields that S5 owns, with every finding printed, but ZERO
   sibling-publishability findings; in particular kiteplayer-rt must not be reported as an
   unpublishable sibling (P0-02's exit). Paste the exit and complete finding set into the log.
   The task still joins no tier; this one manual run is the whole cross-check.

Gate. Tier 2 (rule: buildSrc changed), which includes `:buildSrc:test`.

Commit first line. KitePlayer: `Build the publication readiness check and keep it out of every
tier`.

#### S1.a.4 The engine's kitert coupling ratchet is born on a measured number

Items: P0-04.

Files: `buildSrc/src/main/kotlin/CheckKitertCouplingTask.kt` (new); its test (new);
`kitert-coupling-baseline.txt` (new, at the KitePlayer root); root `build.gradle.kts`;
`KPKMP.md` section 9 (Tier 1 block gains the task; the ratchet move table gains a row).

Steps.
1. Scope, decided: all Kotlin files present under `src/` of every Gradle subproject actually
   included by `settings.gradle.kts` when the task runs, including untracked files, EXCEPT the two
   excluded by design and said so in the baseline header: `kiteplayer-output`
   (it owns the C sink pointer) and `kiteplayer-rt` (it IS the binding). Today that means
   kiteplayer-core, kiteplayer-ffmpeg, kiteplayer-subtitles and kiteplayer-sample are in scope
   (the sample was named explicitly after S1.a.0's third sweep flagged it ambiguous: an app
   consumes the facade and must never name the rt cinterop), and any future module is in scope
   the moment it is included. Patterns: `cnames.structs.kprt_` and `kiteplayer.rt.cinterop`.
   Counting is over comment-stripped Kotlin under `src/` only; generated api dumps are not
   sources (kiteplayer-core's committed dump names kprt_ring because the surface does, which is
   what S1.a.2's opt-in governs), and kitert.def's own match is its package declaration line,
   not a type naming site.
2. MEASURE at execution time with the task itself, before the baseline exists: run exactly
   `./gradlew checkKitertCoupling`. The task measures before reading its baseline and, when the
   file is absent, fails after printing `baseline missing` plus the measured file count and the
   exact baseline stanza to create. Paste that first failing output into the log, write its number
   and this command into the baseline header, then rerun the same command green. For calibration
   only, not to be trusted over the fresh run: the 2026-08-11 planning measurement found three
   in-scope files in kiteplayer-core (NativeAudioRing.kt, NativeRingAudioSink.kt,
   AudioRingDifferentialTest.kt). A literal all-module scan finds nine matching files: seven
   Kotlin files (three core, three output, one rt), kitert.def matching its package declaration,
   and kiteplayer-core's api dump. The live action counts only scoped, comment-stripped Kotlin.
3. Task fails when a NEW file names the cinterop (by name, allowlist style, like KiteCodec's
   allowed_struct_type lines); test plants a fixture file and watches the task fail, then passes
   at baseline; falsifiability by reverting the plant. A separate test proves the missing-baseline
   run prints the measured number and creation stanza before it fails.
4. Add the task to section 9's Tier 1 block (it is a source-text read, no build) and a row to the
   ratchet move table.

Gate. Tier 2 (rule: buildSrc changed). Then run the NEW Tier 1 block once, verbatim, to prove the
block still passes with its new member and to remeasure its cost; the log entry records the new
number beside the old fourteen seconds.

Commit first line. KitePlayer: `Ratchet the engine's naming of the kitert cinterop at its
measured number`.

#### S1.a.5 The plugin suite becomes gateable, and Tier 2 can reach it

Items: P0-05. Absorbs draft C-06 (its selector hole is this sub-phase's stated purpose).

Files: `../KiteCodec/kitecodec-gradle-plugin/build.gradle.kts` (the `tasks.test` block at line
79); `KPKMP.md` section 9 (Tier 2 selector list and command block). The register row that tracks
the exclusion is P0-05 itself and needs no edit.

Steps.
1. Add a test filter excluding EXACTLY the two tests contract rule 5 names:
   `kitecodecDslConfiguredAfterKotlinBlockIsSeenByTasks` and
   `missingLicenseChoiceFailsConfigurationWithInstructions`, with a comment citing contract rule
   5. Both live in `KiteCodecPluginFunctionalTest.kt`, verified 2026-08-11.
2. Prove the filter: the suite passes with it; removing it locally reproduces exactly those two
   failures and nothing else; the log entry pastes both results.
3. Add `kitecodec-gradle-plugin/src/` to section 9's Tier 2 selector list and
   `./gradlew :kitecodec-gradle-plugin:test` to the Tier 2 command block, beside `:buildSrc:test`.

Gate. Tier 2 (rule: build.gradle.kts changed), including the newly added plugin test line.

Commit first lines. KiteCodec: `Exclude the two contract-rule-five tests by name so the plugin
suite can gate`. KitePlayer: `Let tier two reach the Gradle plugin's sources`.

#### S1.a.6 The phantom helper sentence dies

Items: P0-06.

Files: `../KiteCodec/native/kitecodec-c/coupling-baseline.txt` (the sentence spanning lines 12 to
14, "replacing avcodec_send_packet with ffkmp_codecctx_send_packet at one call site gave
baseline 273, actual 274, BUILD FAILED").

Steps.
1. Reword so the experiment reads as the hypothetical mutation it was, and add the sentence: "No
   helper named ffkmp_codecctx_send_packet exists in this library today; S1.a.7 is what creates
   it." (This sub-phase runs before S1.a.7; if order changes, change the tense.)
2. Prove from the KitePlayer root:
   `rg -n -F 'ffkmp_codecctx_send_packet' . ../KiteCodec` finds only prose plus ONE test fixture
   (CheckCinteropCouplingTaskTest.kt:138 uses the name as a synthetic mutation string, which is
   executable test code and sound), and no declaration, until S1.a.7 lands. Repository-aware
   search excludes ignored build output while retaining ordinary untracked source.

Gate. Tier 2, because the changed file lives under `native/` and section 9's selector is
mechanical (S1.a.0's third sweep caught this expansion selecting Tier 1 by "prose only", which
is judgment, exactly what contract item 2 forbids). Within it, `checkCinteropCoupling` must not
move: counting is comment-stripped, so a comment edit that moved a count would indict the task,
not the edit.

Commit first line. KiteCodec: `Say the ratchet experiment helper never existed`.

#### S1.a.7 The opaque surface, compatible addition: handles, wrappers, accessors, guards

Items: P0-07 first half (draft C-43 first half, C-44, C-45, as corrected below). Closes section
16.4 item 1; closes register row R-B2-guards.

Corrections to the draft carried into this expansion, found 2026-08-11: (a) C-44 claims
`ffkmp_codecctx_send_packet` "already exists"; it does not (P0-06's own finding; grep confirms
zero declarations), so all SEVEN wrappers below are new. (b) P0-07's phrase "fourteen wrapping
helpers" is imprecise: FOURTEEN call sites are wrapped by SEVEN helpers. (c) C-43's claim that
the first half leaves the klib "bit identical" over-promises: `kitecodec_helpers.h` is in the
def, so new typealiases and twelve new functions WILL appear in the metadata; the honest
expected outcome is compatible declaration additions plus the declared minor-version constant
replacement, verified before re-baselining.

Files: `../KiteCodec/native/kitecodec-c/include/kitecodec_handles.h` (new);
`include/kitecodec_helpers.h`; the nine helper units under `src/` (helpers_codec.c,
helpers_codecpar.c, helpers_error.c, helpers_filter.c, helpers_format.c, helpers_frame.c,
helpers_packet.c, helpers_playback.c, helpers_stream.c); `tests/test_args.c` (new suite);
`scripts/run-c-tests.sh` and `scripts/build-host.sh` (the agreed suite lists gain test_args);
`scripts/symbol-audit.sh` (its live export-count comment follows the same measured move);
`exported-symbols-baseline.txt`; `klib-metadata-baseline.txt`; `include/kitecodec_abi.h` (minor
version); `kitecodec-core/src/nativeInterop/cinterop/ffmpeg.def`;
`native/kitecodec-c/README.md`; `../KiteCodec/README.md`; `../KiteCodec/CHANGELOG.md`;
`../KiteCodec/docs/about.md`; `../KiteCodec/native/kitecodec-c/fuzz/README.md`; the
committed `kitecodec-core/api/kitecodec-core.klib.api` if `apiDump` moves it; `KPKMP.md` (log and
the section 15.3 suite-count correction).

Steps.
1. `kitecodec_handles.h`: forward struct declarations plus one opaque typedef per FFmpeg struct
   type that appears in `kitecodec_helpers.h` SIGNATURES, which is ELEVEN: the ten
   allowlisted in coupling-baseline.txt (`kc_codec` for AVCodec, `kc_codec_ctx`, `kc_codec_par`,
   `kc_dict`, `kc_filter_ctx`, `kc_filter_graph`, `kc_fmt_ctx`, `kc_frame`, `kc_packet`,
   `kc_stream`) plus `kc_dict_entry` for AVDictionaryEntry, which S1.a.0's third sweep found in
   three public signatures at kitecodec_helpers.h:136 to :138 and which Kotlin never names (so
   the allowlist could not see it). SwsContext and AVFMT appear in comments only, measured, and
   get no handle. Form: `struct AVFrame; typedef struct AVFrame kc_frame;`. The header includes
   NO FFmpeg header. Tag-aliasing keeps every existing Kotlin call site compiling unchanged
   while the def still carries FFmpeg headers.
2. `kitecodec_helpers.h` includes `kitecodec_handles.h`, but its EXISTING parameter and return
   types and its transitive FFmpeg includes remain unchanged in this half. Only the new wrappers
   and accessors use the opaque names. This is what keeps 1.0 to 1.1 source-compatible; S1.a.8
   owns the breaking respelling and include removal. Each `.c` body compiles against its own
   header with `-Werror`; behaviour is untouched. Add `kitecodec_handles.h` to BOTH the existing
   `headers` and `headerFilter` lists in `ffmpeg.def`, without removing any existing entry, so
   cinterop emits all eleven aliases during this compatible-addition half.
3. The SEVEN wrappers, new, one header sentence each saying the typed outcome model comes later
   and the wrapper exists because the def's header removal forces it. Return types, decided
   from the measured call sites (S1.a.0's third sweep caught "raw int forwarded" contradicting
   the lookups): the four hot send/receive wrappers forward the raw int
   (`ffkmp_codecctx_send_packet`, `ffkmp_codecctx_receive_frame`, `ffkmp_codecctx_send_frame`,
   `ffkmp_codecctx_receive_packet`); the two codec lookups return `const kc_codec*`
   (`ffkmp_find_encoder_by_name`, `ffkmp_find_decoder_by_name`), because Frame.native.kt:191
   and MediaSink.native.kt:210 pass the found encoder straight into `ffkmp_codecctx_alloc` and
   need the pointer; the filter lookup becomes `ffkmp_filter_exists` returning int 0 or 1,
   because its only caller (FFmpeg.native.kt:65, `hasFilter`) null-checks and discards, and
   AVFilter earns no opaque handle for one boolean. They cover the fourteen sites the baseline
   lists at lines 55 to 65.
4. The FIVE media-type accessors for MediaSource.native.kt:553 to :557: `ffkmp_media_type_video`,
   `_audio`, `_subtitle`, `_data`, `_attachment`, each returning the AVMediaType value as int.
5. The SIXTEEN guards, identified by FUNCTION NAME because line anchors drift and this very
   sub-phase moves them (S1.a.0's third sweep found the register row's anchors stale; anchors
   below are as of a78dffc and are re-derived at execution): `ffkmp_frame_get_buffer`
   (helpers_frame.c:32); `ffkmp_codecpar_from_context` (:27) and `ffkmp_codecpar_copy_for_mux`
   (:32); `ffkmp_fmt_open_input` (:14), `ffkmp_fmt_find_stream_info` (:23),
   `ffkmp_fmt_read_frame` (:33), `ffkmp_fmt_alloc_output2` (:55) and `ffkmp_fmt_write_frame`
   (:94); `ffkmp_codecctx_open` (:16) and `ffkmp_codecctx_from_par` (:17); and the six filter
   entry points `ffkmp_graph_build_video` (:35), `ffkmp_graph_build_audio` (:77),
   `ffkmp_graph_build_video_multi` (:190), `ffkmp_graph_build_audio_multi` (:230),
   `ffkmp_graph_send` (:310) and `ffkmp_graph_receive` (:313). EXCLUDED, already guarded at
   I-12 and unable to reproduce a crash: `ffkmp_fmt_seek_micros` (:24) and `ffkmp_fmt_set_opt`
   (:62); the register row's eighteen minus these two is this sixteen. Each guard is a leading
   NULL or range refusal returning the documented failure value, with one header contract
   sentence in the shape `ffkmp_fmt_set_opt` already uses. REPRODUCTION-FIRST: `tests/
   test_args.c` carries exactly one registered invalid vector for each of the sixteen entry
   points against the UNGUARDED build first and the log records its crash class, then the guard
   lands, then the same case passes, then falsifiability by reverting one guard. Preserve the
   existing nullable contracts: an audio-filter description may be NULL to select `anull`, a
   graph-send frame may be NULL to signal EOF, a mux packet may be NULL to flush, and an
   output-format name may be NULL for inference. The codec argument to `ffkmp_codecctx_open` may
   be NULL when the context already remembers its codec, and the path argument to
   `ffkmp_fmt_alloc_output2` may be NULL
   when a nonempty format name is supplied. Pin all six as passing positive controls in
   `test_args.c`; those positions never become blanket errors. Give every vector exactly one
   `kc_case`, so the suite has exactly twenty-two cases: sixteen invalid vectors and six nullable
   positive controls.
6. `exported-symbols-baseline.txt` refreshed by the section 9 move procedure, naming all twelve
   added symbols (seven wrappers, five accessors).
7. `KITECODEC_C_ABI_MINOR` moves to 1 in the same commit, decided here: twelve new exports are a
   consumer-visible compatible addition. The identity gate suite re-baselines accordingly.
8. Suite lists in run-c-tests.sh and build-host.sh gain test_args, keeping their stated
   agreement. Update every CURRENT count while leaving explicitly historical counts historical:
   seven suites; 274 cases per variant and 822 across plain/asan/tsan; 169 `ffkmp_` exports (157
   already consumed plus twelve additions), six `kc_` exports, 175 total. The count starts from
   252 in the current six-suite tree: B1.6's historical 250 gained the two guard cases in I-12
   (`fmt_set_opt` NULL key and `fmt_seek_micros` out-of-range stream), then `test_args` adds 22.
   The native README gains
   the handles-header row, runner/test rows at seven, the seven-suite heading and the 22-case
   `test_args.c` row, while its historical six-suite and 240/234/250 statements remain explicitly
   historical. KiteCodec's root README says seven C suites. Its CHANGELOG records all eleven
   alias names, the seven wrapper names, five accessor names, exports 163 to 175, ABI 1.0 to 1.1,
   the sixteen guards and six nullable controls; it narrows "opaque migration deferred" to the
   breaking header/def/Kotlin half, changes only the current suite count to seven, and preserves
   the six-fuzz-target facts. `docs/about.md` distinguishes the 157 legacy helpers Kotlin already
   consumes from twelve dormant compatible additions and says seven C suites. The fuzz README
   marks its "other 151 helpers" arithmetic explicitly historical to B1.5's 157-helper surface;
   it makes no unproved coverage claim for the twelve additions. Section 15.3's current "six
   suites" statements gain the same correction.
   `symbol-audit.sh`'s live export comment becomes 157 legacy helpers consumed by Kotlin plus the
   twelve compatible additions and the six `kc_` identity functions; its derived checks do not
   change.
   Contract rule 5's stale KOTLIN test count is a separate defect and is fixed at S1.a.9, not
   here: a C suite cannot move a Kotlin count.

Gate. KiteCodec Tier 2 (rule: native/ changed): full C suites in plain, asan and tsan including
the new test_args; `symbol-audit.sh` (check 6 against the refreshed baseline). Metadata evidence
is ordered: FIRST run `klib-metadata-diff.sh --check` against the pre-S1.a.7 baseline, expect
NONZERO, and paste its complete report into the log; review by CLASS, accepting only the eleven
typealiases, twelve new functions, and the paired `KITECODEC_C_ABI_MINOR` replacement from 0 to
1. A removed old minor line is allowed only with its added value-1 mate; any other removed line
or changed declaration fails. THEN run `klib-metadata-diff.sh --update`; THEN run `--check`
green, including the two-bakings assertion. Run `checkCinteropCoupling` (counts exactly unmoved,
because Kotlin does not use the new helpers yet) and `apiCheck`, refreshing the named api dump
only if `apiDump` proves it moved. KitePlayer is NOT re-consumed here; that happens once, at
S1.a.8.

Commit first lines. KiteCodec: `Add the opaque surface: handles, wrappers, accessors, guards`.
KitePlayer: `Record the opaque surface addition and its gate`.

#### S1.a.8 The def drops FFmpeg, and Kotlin crosses only the opaque boundary

Items: P0-07 second half (draft C-43 second half). Closes section 15.1's B1-25 row; closes
section 15.5 deferral 1.

Files: `../KiteCodec/kitecodec-core/src/nativeInterop/cinterop/ffmpeg.def` (lines 8 and 9);
`native/kitecodec-c/include/kitecodec_helpers.h`; the nine helper units under
`native/kitecodec-c/src/`; `native/kitecodec-c/klib-metadata-baseline.txt`;
`native/kitecodec-c/tests/test_buffers.c`, `test_convert.c`, `test_ownership.c`,
`test_rescale.c`, `test_strerror_thread.c` and the S1.a.7 `test_args.c`;
`native/kitecodec-c/fuzz/kc_fuzz.c`, `fuzz_codec_option.c`, `fuzz_filter_audio.c`,
`fuzz_filter_video.c`, `fuzz_format_name.c`, `fuzz_format_option.c` and `fuzz_metadata.c`;
`native/kitecodec-c/README.md`; `native/kitecodec-c/scripts/klib-metadata-diff.sh`;
`../KiteCodec/CHANGELOG.md`; `../KiteCodec/docs/about.md`;
`../KiteCodec/docs/platforms.md`; `../KiteCodec/docs/getting-started.md`;
the SIX Kotlin files carrying the migrating material (FFmpeg.native.kt, FilterGraph.native.kt,
Frame.native.kt, MediaSink.native.kt, MediaSource.native.kt, Playback.native.kt) and the FOUR
compile-proof files (Internals.kt, Remuxer.native.kt, Transcoder.native.kt,
PlayerSurfaceTest.kt; S1.a.0's third sweep caught the earlier "ten files migrate": these four
import ONLY ffkmp_ helper names, all of which survive the def reduction, so their whole step is
proving they compile unchanged; FFmpegIdentityTest.kt imports only kc_/KC_ names and is not
touched at all); `buildSrc/src/main/kotlin/CheckCinteropCouplingTask.kt` and its test;
`native/kitecodec-c/coupling-baseline.txt` (rewritten);
`include/kitecodec_abi.h` (version); `native/kitecodec-c/signature-baseline.txt` (new) and its
checking step in `symbol-audit.sh`; KiteCodec's committed api dump (one file,
kitecodec-core.klib.api); `KPKMP.md` rows named above.

Steps.
1. This is the breaking header half deliberately deferred from S1.a.7: respell the 140 existing
   AV-typed public helper declarations to their `kc_*` aliases; remove every FFmpeg include from
   `kitecodec_helpers.h`, leaving the standard headers it uses plus `kitecodec_handles.h`; and
   add this syntax-checked direct-owner include map (names below omit the `lib` prefix and `.h`):
   helpers_codec gets avcodec/avcodec, avutil/error, avutil/opt; codecpar gets
   avcodec/avcodec; error gets avutil/error and avutil/mathematics; filter gets
   avfilter/avfilter, avfilter/buffersrc, avfilter/buffersink, avutil/channel_layout,
   avutil/error, avutil/mem, avutil/pixdesc and avutil/samplefmt; format gets
   avformat/avformat, avutil/error and avutil/opt; frame gets avutil/frame,
   avutil/channel_layout, avutil/dict, avutil/error, avutil/imgutils, avutil/pixdesc,
   avutil/samplefmt and swscale/swscale; packet gets avcodec/packet and avutil/avutil;
   playback gets avcodec/avcodec, avcodec/packet, avformat/avformat, avformat/avio,
   avutil/channel_layout, avutil/common, avutil/display, avutil/error, avutil/frame,
   avutil/pixdesc and avutil/samplefmt; stream gets avformat/avformat, avutil/avutil and
   avutil/mathematics.

   Replace the C suites' transitive dependencies with the same rule: test_buffers gets
   avfilter/buffersink, avutil/channel_layout, avutil/error, avutil/frame, avutil/pixdesc,
   avutil/pixfmt and avutil/samplefmt; test_convert gets avutil/frame, avutil/log and
   avutil/pixfmt; test_ownership gets avcodec/avcodec, avfilter/avfilter, avformat/avformat,
   avutil/channel_layout, avutil/dict, avutil/error, avutil/frame, avutil/mem, avutil/pixfmt
   and avutil/samplefmt; test_rescale gets avcodec/avcodec, avfilter/avfilter,
   avformat/avformat, avutil/error, avutil/frame, avutil/pixdesc and avutil/pixfmt;
   test_strerror_thread gets avutil/error. `test_args` gets no FFmpeg header, but it retains the
   S1.a.7 child-process reproduction machinery it actually owns. Respell `AVFilterGraph`,
   `AVFilterContext`, `AVFrame`, `AVFormatContext`, `AVCodecContext` and `AVCodec` to their
   `kc_*` aliases; replace `AVERROR(EINVAL)` with `-EINVAL`; resolve `yuv420p`, `fltp` and `s16`
   through `ffkmp_pix_fmt_from_name` and `ffkmp_sample_fmt_from_name`; and replace
   `avcodec_find_decoder(AV_CODEC_ID_PCM_S16LE)` with
   `ffkmp_find_decoder_by_name("pcm_s16le")`. Its exact direct headers are `harness.h`,
   `kitecodec_helpers.h`, `kitecodec_handles.h`, errno, signal, stddef, stdio, string, sys/types,
   sys/wait and unistd. Remove the unused stdlib include. This keeps all 22 cases and their
   focused reproduction selector while proving the suite itself consumes only the opaque surface.

   Fuzz support is explicit too: kc_fuzz.c gets avutil/log; fuzz_codec_option gets
   avcodec/avcodec and avutil/error; fuzz_filter_audio gets avfilter/avfilter and
   avutil/samplefmt; fuzz_filter_video gets avfilter/avfilter and avutil/pixfmt;
   fuzz_format_name gets avutil/pixfmt and avutil/samplefmt; fuzz_format_option gets
   avformat/avformat and avutil/error; fuzz_metadata gets avformat/avformat, avutil/dict and
   avutil/error. `kc_fuzz.h` and replay_main.c remain FFmpeg-header-free compile proofs;
   harness.c already owns avutil/log directly and needs no change.

   Then reduce `ffmpeg.def`
   `headers` and `headerFilter` to `kitecodec_helpers.h kitecodec_handles.h kitecodec_abi.h`.
   Update the native README's layout without rewriting extraction history: B1.3 installed twenty
   include lines (four standard, sixteen FFmpeg), and S1.a.7 added the handles include as the
   twenty-first. The four standard-library includes and handles include remain as needed, while
   the sixteen FFmpeg includes move directly into the production/test/fuzz translation units that
   use them. Update the three architecture guides in the same change: the reduced def parses only
   helpers, handles and ABI headers and no FFmpeg header; the compiled C archive consumes the
   FFmpeg include path; the untouched module build still supplies that path redundantly to
   cinterop, recorded as out-of-fence plan debt rather than misdescribed as removed; the handle
   header is part of the layout; the helper sources are ordinary maintained files and no retired
   extraction/lift script is described as live.
2. The six files migrate: FFmpeg-typed CPointer parameters move to the opaque typealiases; the
   fourteen raw call sites move to the seven wrappers (six ffkmp_ names plus ffkmp_filter_exists
   per S1.a.7's decided returns); the five AVMEDIA_TYPE_* constants at MediaSource.native.kt:553
   to :557 move to the five accessors; unresolvable imports are deleted. The four compile-proof
   files are rebuilt untouched, and if the compiler flags one (a typealias respelling reaching a
   signature they use), the mechanical fix is recorded in the log. File-by-file compile;
   falsifiability: reverting any one migrated file must fail compilation, which is the proof
   the headers are really gone.
3. Rewrite the coupling task, its tests and `coupling-baseline.txt` around the boundary that now
   exists. `cinterop_import_lines` excludes `ffkmp_` as well as `kc_` and `KC_`, so it counts only
   direct FFmpeg imports and must fall from 246 to zero. `ffmpeg_typed_crossings` counts direct
   libav calls only and must fall from 287 to zero. `ffkmp_call_sites` remains reported-only as
   opaque-boundary traffic and may grow; `direct_libav_call_sites` remains reported and must be
   zero. Delete every `allowed_struct_type` line: no raw FFmpeg struct type may be imported or
   named directly in Kotlin source, though cinterop still has private forward-declared C tags.
   Tests prove a new raw import, raw call or raw struct name fails while a new `ffkmp_`, `kc_` or
   `KC_` use does not. Record the old and new measurements and semantic rewrite in the log.
   The task's current string-state comment stripper is not correct for Kotlin template code: it
   preserves comments inside `${...}` and can therefore count a commented raw call or type. Port
   the already-proved context-stack lexer from KitePlayer's `CheckKitertCouplingTask`: unescaped
   ordinary and raw `${...}` re-enter code mode; nested braces, strings, raw strings, chars,
   backtick identifiers and nested templates are tracked; line and nested block comments are
   stripped inside template expressions; escaped `\${...}` stays literal. Tests prove a
   comment-only template expression does not count, a live raw call or type inside one does count,
   nested raw templates work and an escaped template remains string content.
   Both call counters measure executable syntax, not diagnostic prose: derive a code-only view from
   the same lexer by blanking ordinary-string, raw-string and character content while retaining
   whitespace/newlines, live `${...}` template expressions and backtick identifiers. Use that view
   for imports, raw struct names, `ffkmp_` calls and direct libav calls. Accept horizontal whitespace
   before a call's opening parenthesis; include the `avio_` family; tolerate optional backticks
   around raw function names; and let the raw-type candidate matcher cover initialism-bearing names
   such as `AVIOContext` and `AVHWFramesContext`. Tests prove an indented raw import, a spaced raw
   call, a backticked raw call, a fully qualified `ffmpeg.avio_open(...)` and an initialism-bearing
   raw type fail, while identical text in a diagnostic string does not count.
4. `KITECODEC_C_ABI_MAJOR` moves to 2 and `KITECODEC_C_ABI_MINOR` resets to 0 (S1.a.7 set it to
   1), because the 140 of 157 helper declarations that name FFmpeg types changed shape for a C
   consumer (the other 17 carry none, counting complete multiline declarations on 2026-08-11).
   Rebuild and run the unchanged macro-derived C and Kotlin identity tests at 2.0; neither test
   hardcodes the old version and neither file needs an edit.
5. The NEW signature baseline (verifier M4: a names-only baseline cannot see a shape change):
   generate one line per exported declaration from `kitecodec_helpers.h`, `kitecodec_handles.h`
   AND `kitecodec_abi.h` (full declaration text, normalised whitespace; handles.h is included
   precisely so a silent retarget of an opaque alias cannot pass unseen, S1.a.0's third sweep)
   into `signature-baseline.txt`. The installed scope is exactly 189 normalised records: 169
   helper `KC_API` prototypes, eleven handle typedefs, six ABI `KC_API` prototypes, two ABI enum
   definitions and the full `kc_ffmpeg_report` typedef. Select those classes per header: helper
   `KC_API` prototypes from helpers, typedefs from handles, and ABI `KC_API` prototypes, enums and
   report typedef from ABI. Ignore standalone forward struct declarations and extern-C braces.
   Strip comments and preprocessor lines, accumulate complete multiline declarations, and treat a
   semicolon as a terminator only at brace depth zero so enum fields and report fields cannot split
   into false records; then normalise whitespace and sort without deduplicating. Extend
   `symbol-audit.sh` with check 7 and a distinct
   `--write-signature-baseline` move path; do not overload the export baseline's
   `--write-baseline`. Prove check 7 fails once on a temporary function-parameter change and once
   on a handle retarget, then restore both. Add its row to section 9's ratchet move table.
6. Capture and accept the metadata only AFTER steps 1 to 5 created the difference. FIRST run
   `klib-metadata-diff.sh --check` against the post-S1.a.7 baseline, expect NONZERO, and paste its
   full differential into the log as the before-picture; THEN review the exact breaking surface;
   the first run must also show that the old instrument can no longer find
   `LIBAVUTIL_VERSION_INT`, because the reduced def correctly parses no FFmpeg header. Before
   accepting the baseline, rewrite that obsolete two-bakings assertion in the named script into
   an opaque-boundary assertion over every target: the metadata contains none of
   `LIBAVUTIL_VERSION_INT`, `LIBAVFORMAT_VERSION_INT`, `LIBAVCODEC_VERSION_INT`,
   `LIBAVFILTER_VERSION_INT`, `LIBSWSCALE_VERSION_INT` or `LIBSWRESAMPLE_VERSION_INT`, and every
   direct binding is `_ffkmp_` or `_kc_`. Run this invariant before every mode, including
   `--update`, so the write path cannot accept forbidden metadata. The archive identity gate
   remains the one intentional FFmpeg header baking; cinterop no longer has a second one to
   compare. THEN `--update`; THEN `--check` again green, which must prove the new absence/prefix
   assertion as well as baseline equality.
7. KitePlayer re-consumes ONCE: rebuild against the republished klib, then the full KitePlayer
   Tier 2 block. This is KiteCodec window 1 closing.
8. Close the KPKMP rows: 15.1 B1-25, 15.5 deferral 1; mark 16.4 items 1 and 9 closed with their
   closing sub-phases named (S1.a.7 and S1.a.2). Update KiteCodec's Unreleased changelog from
   "breaking half deferred" to the measured result and call out the 0.x source/cinterop break:
   140 of the original 157 declarations respell, the public helper header stops transitively
   supplying FFmpeg typedefs/layouts, raw libav declarations leave the klib, and native consumers
   must use the `kc_*`/`ffkmp_*` boundary. Six Kotlin files migrate, coupling ceilings become
   zero, C ABI becomes 2.0, Kotlin's public API stays unchanged, and nothing is published.
   The same changelog edit must retire its stale present-tense extraction claim: the helper files
   are ordinary maintained sources now, `verify-lift.sh` is retired with its final proof retained
   in the execution record, and the old sentence saying the def and Kotlin call sites remain
   unchanged is replaced by this measured opaque migration result.

Gate. KiteCodec Tier 2 in full, then KitePlayer Tier 2 in full after the re-consume, then the
Tier 1 blocks of both repositories verbatim as the closing check. The metadata evidence is the
step 6 triple (failing check pasted, update, green check); this half is the deliberate breaking
change, nothing is published, and the log entry says exactly that.

Commit first lines. KiteCodec: `Drop the FFmpeg headers from the def and finish the opaque
migration`. KitePlayer: `Re-consume KiteCodec over the opaque surface`.

#### S1.a.9 The record matches the tree

Items: P0-08 (draft C-07 and C-08; the section 11 supersession note was verified already in
place on 2026-08-11 and needs only the reread).

Files: `KPKMP.md` section 13 decision 2 ("local commits, nothing pushed") and section 1
contract rule 5 (two stale counts); `README.md` line 192 ("No network path, no live or adaptive
streaming, no DRM"); `../KiteCodec/README.md` line 314 (the https troubleshooting row).

Steps.
1. Correct the push history from LOCAL evidence first, which is sufficient on its own (S1.a.0's
   third sweep caught the old fallback preserving a known-false sentence when the remote is
   unreachable): `origin/main` in both repositories already proves pushes happened (KitePlayer
   origin/main at 5b0e066, KiteCodec origin/main at a086b49, plus the reflog). Then ATTEMPT the
   remote snapshot: `git rev-list --count origin/main..main`, `git ls-remote --heads origin`,
   `git ls-remote --tags origin`; paste what succeeds into the log, and mark ONLY the current
   remote snapshot ASSUMED if lookup fails (measured 2026-08-11: lookup does fail from the
   executor's environment, and KitePlayer sits several local commits ahead of origin/main). The
   "nothing pushed" sentence is corrected regardless.
2. Correct section 13 decision 2 to the D-3 reality: both repositories were pushed on
   2026-08-10; commits since then are local by design; the owner pushes; publication is the
   irreversible act and it belongs to S5.
3. Correct README.md line 192 to what C-08 actually measured (S1.a.0's third sweep caught the
   earlier "any scheme will open" as an extrapolation): the URI is passed to FFmpeg unchanged,
   so whatever protocols the linked build carries are reachable through it; MEASURED, one
   loopback http case played to completion; there is no allowlist, deadline or redaction over
   the path yet; network hardening is parked at 17.8. Do not advertise the path; state it.
4. Correct contract rule 5's two stale counts, measured 2026-08-11: "53 core tests" is really
   85 `@Test` cases in kitecodec-core (write the number with the counting command beside it),
   and the plugin sentence reads as if the class holds two functional tests when it holds four,
   of which the two NAMED ones fail; reword to say exactly that.
5. Review `../KiteCodec/README.md:314` (the row advising `http`, a local file, or a system
   FFmpeg when vendored https fails): the http advice is TRUE as measured (the loopback run)
   and the row is a build-limitation note, not a feature promise, so the expected outcome is
   that it STANDS; correct it only if its wording claims more than the measurement, and record
   the read in the log either way. This replaces the earlier claim here that KiteCodec's README
   makes no network statement, which S1.a.0's third sweep disproved.

Gate. Tier 1 (rule: every touched file here is a README or KPKMP.md, none of which appears in
the Tier 2 selector list; S1.a.6's lesson is why the rule is spelled out rather than judged).
Reread of each corrected sentence in place.

Commit first lines. KitePlayer: `Correct the record: remote state measured, the network sentence
made true`. KiteCodec: `Say only what the https row measured` (ONLY if step 5 changes it;
otherwise no KiteCodec commit).

### 17.4.2 The S1.b register and sub-phases, decision complete

Authored 2026-08-11 against clean KitePlayer `954f075` and KiteCodec `c2447c8`, after the
S1.a exit and a fresh tree sweep. The owner directed the executor to finish S1 autonomously and
to make conservative judgement calls rather than wait for relay. That direction authorises this
entry expansion as a separate planning act. It does not authorise product work until S1.b.0 has
adversarially checked every located fact below, this prose has passed Tier 1 and landed in its own
local commit, and the sweep has rerun clean.

The sweep found three upstream planning contradictions and resolves them here and in their standing
sentences. First, section 17.3 put KiteCodec window 2 wholly inside S1.c, but S1.b cannot compile
or link `kiteplayer-ffmpeg` for iOS without iOS KiteCodec variants and matching FFmpeg trees.
Window 2 therefore has a narrow **window 2a** at S1.b.1: build and local-consumption plumbing only.
The JVM/JNI ABI work remains window 2b in S1.c. Second, 17.2 called S1.b's renderer a view while
S1.d owns both reusable views. S1.b supplies a caller-owned CALayer renderer and its provisional
sample host only. Third, the existing `buildFFmpegForIos*` tasks are not usable merely because
they exist. The exact pre-change command
`./gradlew :kitecodec-core:buildFFmpegForIosSimulatorArm64` exits nonzero before configure because
both source and install paths contain `#`; after that is removed, the task still applies the
desktop encoder and text stack to iOS, whose fourteen third-party archives are not available for
the iOS SDK on this machine. The fix below is a mobile software-playback profile, not a claim that
the desktop profile cross-builds.

Execution order is S1.b.0 through S1.b.5. Nothing from S1.c, S1.d or S2 may enter these commits.
In particular there is no JNI, Android, reusable `KitePlayerView`, Compose, Metal, VideoToolbox
decode or platform demuxer here. FFmpeg remains the only demuxer and decoder. The iOS profile uses
software decode; D-2's VideoToolbox selection and fallback belong to S2.

#### S1B-01. iOS needs a buildable, locally consumable KiteCodec substrate

- Where: `../KiteCodec/buildSrc/src/main/kotlin/BuildFFmpegTask.kt`; `StaticLinkFlags.kt`;
  `../KiteCodec/kitecodec-core/build.gradle.kts`; the Gradle plugin's `FFmpegSource` path; current
  `native-libs/lgpl/`, which has one empty ignored ios-simulator-arm64 directory left by the
  refused command and no usable iOS tree; Maven local, which has only the macosArm64 KiteCodec
  variant.
- Problem: the task refuses the repository's real `#Kite` path, selects desktop-only external
  libraries for iOS, and the publication guard offers either host-only macOS or the full release
  set. Player cannot resolve or link an iOS backend from that state. The plugin's `System` source
  is host-desktop-only and therefore cannot supply the final iOS link either.
- Fix: S1.b.1 adds path-safe transactional temporary staging; an LGPL-only mobile Apple profile
  containing the current STANDARD shared software-playback set, zlib and no desktop third-party,
  GPL, hardware-encode or VideoToolbox addition; a Local tree source in the plugin; and a local-only
  host-plus-phone target set. Lean does not become the default until S5. Remote/public publishing
  remains unable to select experimental targets. Generated FFmpeg trees stay untracked inputs.
- Test: reproduce the current `#` refusal; pure buildSrc tests pin the mobile argument and staging
  sets; plugin tests reject missing, GPL-iOS and incomplete Local trees; then real macosArm64,
  iosArm64 and iosSimulatorArm64 trees build in one invocation, a later invocation compiles and
  links both iOS targets, every archive's architecture and LC_BUILD_VERSION platform agree, and a
  three-framework scratch consumer resolves and final-links the three Maven-local variants offline.

#### S1B-02. AppKit stops leaking into the shared Apple source set

- Where: `kiteplayer-output/src/appleMain/.../AppKitVideoRenderer.kt` and `AppKitWindow.kt`;
  `AppKitVideoRendererTest.kt` and `RealTimeSoakTest.kt` under appleTest; the macOS-only target
  declarations in `kiteplayer-output`, `kiteplayer-ffmpeg` and `kiteplayer-sample`.
- Problem: adding either iOS target today asks the iOS compiler to resolve AppKit. The output and
  FFmpeg modules also publish no iOS variant even though their core and rt dependencies do.
- Fix: S1.b.2 moves the two AppKit production files to macosArm64Main and the two macOS-only tests
  to macosArm64Test, keeps the clock, backend, sink and device-independent C-ring tests shared in
  appleMain/appleTest, and registers iosArm64 plus iosSimulatorArm64 in output and FFmpeg. Only
  kiteplayer-ffmpeg carries FFmpeg and consumes the S1.b.1 Local tree; output remains FFmpeg-free.
  The sample target is added only in S1.b.5.
- Test: the pre-move compile with a temporary iOS target fails on `platform.AppKit`; after the
  move `rg -n 'platform.AppKit' kiteplayer-output/src/appleMain kiteplayer-output/src/appleTest`
  prints nothing, both iOS test binaries link, the existing macOS tests remain green, and the two
  ABI dumps move only in target metadata: three targets, no declaration added or removed.

#### S1B-03. The C sink runs RemoteIO and AVAudioSession ownership is explicit

- Where: `kiteplayer-rt/native/src/kite_rt_coreaudio.c`; `kitert.def`; the rt header, build note,
  README and source-discipline/render audits; `CoreAudioSink.kt` and new Apple platform session
  files in kiteplayer-output; shared Apple device tests.
- Problem: the C file compiles a complete refusal anywhere except macOS. iOS needs
  `kAudioUnitSubType_RemoteIO`, the iOS AudioToolbox link flag and an active playback audio
  session. Silently taking ownership of the process-wide AVAudioSession would be an integration
  defect.
- Fix: S1.b.3 compiles the existing static render callback and lifecycle for macOS and iOS, with
  DefaultOutput on macOS and RemoteIO on iOS. Add `linkerOpts.ios = -framework AudioToolbox`.
  Pull R-B8-layout forward from B8 and make every refused CoreAudio layout write silence safely
  and set the silence action flag. Add public `AppleAudioSessionPolicy` with exactly two policies.
  `ManagedPlayback` uses a process-wide synchronized lease: the first lease sets Playback,
  MoviePlayback and no category options, activates before C creates RemoteIO, later leases only
  increment a count, every failed C open releases, and only the last release after C disposal
  deactivates with NotifyOthersOnDeactivation. `ApplicationManaged` makes no session call. The
  default Apple backend uses ManagedPlayback. macOS actuals are no-ops. No session call enters the
  render callback. Route, interruption and background recovery remain out of S1.b because the
  engine does not yet collect `AudioSink.events`.
- Test: on named simulator `Test iPhone 17`, a test written first expects a successful open and
  sees the current unsupported verdict; after the fix it observes callbacks, a consumed ring and
  a near-future anchor, then closes twice and successfully opens a fresh sink. Policy fixtures pin
  activation failure, rollback, concurrent managed leases, final-release ordering and zero calls
  under ApplicationManaged. The existing callback suite gains wrong-count, null/zero, short-size
  canary and correct-layout cases without adding a ninth suite. Source and object audits pin
  RemoteIO on iOS, DefaultOutput on macOS and the callback call set in all three Apple archives.
  Temporarily selecting DefaultOutput in the iOS arm makes the simulator test fail, then restoration
  passes. iosArm64 compiles and links; no physical-device result is inferred.

#### S1B-04. iOS gets the deliberately simple CPU layer renderer

- Where: new `kiteplayer-output/src/iosMain/.../UIKitVideoRenderer.kt` and its iosTest;
  `VideoRenderer`, `VideoFrame` and `SoftwareConverter`, which stand unchanged.
- Problem: a decoded iOS frame has no presentation consumer. Reusing AppKit is impossible and
  building Metal here would steal S2.
- Fix: S1.b.4 adds `UIKitVideoRenderer`, constructed with a caller-owned `CALayer` and the same
  `(VideoFrame) -> ByteArray` conversion seam as the macOS fallback. It converts off the main
  thread, keeps one waiting frame and one waiting image, posts at most one main-queue delivery,
  writes a retained CGImage to `layer.contents`, uses aspect-fit gravity with implicit animations
  disabled, applies quarter-turn rotation, closes every frame exactly once and reports presented,
  superseded and failed counts. The public constructor takes the layer and converter; an internal
  constructor takes enqueue and delivery lambdas for deterministic tests. Ownership explicitly
  releases a displaced or closed CGImage after CALayer has retained it. It creates no UIView and no
  reusable player view; S1.d owns that surface.
- Test: iosSimulatorArm64 tests pin newest-frame wins, exact-once close on success/failure/
  supersession/close, bounded main-queue deliveries, width/height/rotation and no opaque-frame
  support. The negative control removes the delivery guard and the queued-block bound test fails.
  A real simulator layer receives a non-null CGImage. The macOS renderer and tests stay byte-for-
  byte unchanged in this sub-phase.

#### S1B-05. A runnable iOS host proves the backend is not a library-only claim

- Where: `kiteplayer-sample/build.gradle.kts`; new iosMain sample controller; new
  `kiteplayer-sample/iosApp` Swift host, Info.plist and Xcode project; sample documentation.
- Problem: compiling libraries does not prove an application can link, open media, attach the
  renderer, hear audio, seek and tear down under UIKit.
- Fix: S1.b.5 adds iosArm64 and iosSimulatorArm64 static sample frameworks and a minimal UIKit
  host. The Kotlin controller composes `KiteCodecMediaBackend`, `AppleOutputBackend` and
  `UIKitVideoRenderer`; the Swift app only hosts that controller. The build embeds a generated
  testmedia clip and no platform media API. It exposes play/pause and one seek action, and prints
  the same decoded/submitted/drop/underrun summary style as the macOS sample.
- Test: `xcodebuild` builds the simulator app, `simctl` installs and launches it on `Test iPhone
  17`; a sample-only `--s1b-smoke` mode plays the bundled sync clip, seeks to five seconds, reaches
  Ended, closes and writes a bounded JSON result before terminating. That result proves decoded and
  submitted frames, renderer presentation, non-null layer contents, the seek landing, public audio
  underruns and teardown completion. Positive callback and zero-handle teardown evidence belongs to
  the S1.b.3 module tests, not to inaccessible sample internals. Build the iosArm64 release framework
  and unsigned device app as link proof. Physical-iPhone playback is an S1.e owner-session item and
  is not inferred from that build.

#### S1.b.0 Mechanical expansion sweep

Files: read-only across every file named in S1.b.1 to S1.b.5, both target graphs, Maven local,
the Xcode SDK and device inventories. Product files do not move in this sub-phase.

Steps.
1. Verify all paths, declarations, task names, target names, counts and current outcomes above
   against the tree. Run the entire sweep before classifying findings.
2. Classify a mismatch BLOCKING if it changes a file, symbol, command, expected result, gate or
   commit first line. Classify it DESCRIPTIVE only when every action stands unchanged.
3. Use the owner's S1 correction exception for a mechanical contradiction with one conservative
   tree-backed answer: record it, Tier 1, separate prose commit, then rerun this complete sweep.
   Stop for an irreversible, scope-expanding or product-policy choice.

Gate. Tier 1, because this sub-phase and the expansion commit change KPKMP only.

Commit first line. KitePlayer: `Expand the iOS phone stage against the current tree`.

#### S1.b.1 Build and consume the mobile Apple Codec locally

Execution-fence correction commit first line. KitePlayer:
`Correct the Apple phone proof against generated archives`.

Second execution-fence correction commit first line. KitePlayer:
`Scope the Apple phone proof to the core project`.

Third execution-fence correction commit first line. KitePlayer:
`Make native media tests use writable paths`.

Files, KiteCodec: `buildSrc/src/main/kotlin/BuildFFmpegTask.kt`;
`buildSrc/src/main/kotlin/StaticLinkFlags.kt`; `buildSrc/src/main/kotlin/FFmpegPaths.kt`; new
`buildSrc/src/test/kotlin/BuildFFmpegTaskTest.kt` and `FFmpegPathsTest.kt`;
`kitecodec-core/build.gradle.kts`;
`kitecodec-core/src/nativeTest/kotlin/io/github/yuroyami/kitecodec/EncoderRestampTest.kt`,
`PipelineRoundTripTest.kt` and `PlayerSurfaceTest.kt` in that same directory;
`kitecodec-gradle-plugin/src/main/kotlin/io/github/yuroyami/kitecodec/gradle/FFmpegLicense.kt`,
`KiteCodecExtension.kt`, `KiteCodecPlugin.kt`, `PrebuiltLinkFlags.kt`;
`kitecodec-gradle-plugin/src/test/kotlin/io/github/yuroyami/kitecodec/gradle/KiteCodecPluginFunctionalTest.kt`;
`kitecodec-gradle-plugin/README.md`;
`README.md`, `CHANGELOG.md`, `native/kitecodec-c/README.md`, `docs/about.md`,
`docs/getting-started.md`, `docs/gradle-plugin.md`, `docs/platforms.md` and
`docs/troubleshooting.md`; `.github/scripts/package-ffmpeg.sh`; `.github/workflows/ci.yml` and
`.github/workflows/release-binaries.yml`; and
`native/kitecodec-c/scripts/symbol-audit.sh`. Player: KPKMP execution log only. Generated
`native-libs` trees, `dist` packages, scratch consumers and Maven-local files are evidence, never
committed files.

Steps.
1. Preserve the captured pre-change nonzero `#`-path run. Copy FFmpeg source into a unique
   hash-free directory under `java.io.tmpdir`, excluding `.git` and every `build` subtree while
   preserving executable attributes. Configure, build and install entirely there. GNU make must
   never receive the repository source path or the final `#Kite` install path. After verifying the
   six libav archives and headers in scratch, copy with Java/NIO to a sibling staging directory
   beside the declared output, verify the copy, then replace the final tree. Delete scratch in a
   success `finally`; on failure retain it and print its path. A failed build never replaces a
   previously good final tree. After successful `make install` but before verification/replacement,
   read the first line of scratch `build/ffbuild/config.log`, strip only its leading `# `, require a
   nonblank result and write it as exactly one UTF-8 newline-terminated line at
   `scratchInstall/lib/kitecodec/ffmpeg-configure.txt`. `verifyInstall` requires that record, so a
   build lacking provenance never replaces a good tree. `.github/scripts/package-ffmpeg.sh` reads
   only `native-libs/<license>/<triple>/lib/kitecodec/ffmpeg-configure.txt`, hard-fails when it is
   missing/empty/multiline, and uses that exact line for `BUILD-INFO.txt`'s `Configure:` field. It
   no longer reads a stale vendor build log or treats `(unavailable)` as success. Tests pin the
   log normalization, stable path, missing-log refusal and rollback preservation.
2. For IosArm64 and IosSimulatorArm64 use the current STANDARD playback profile:
   `sharedCoreArgs()`, `--disable-autodetect`, `--enable-zlib` and the target SDK/cross flags.
   The PNG decoder makes zlib load-bearing. Do not add `desktopBaseArgs()`, GPL flags, desktop
   third-party archive bundling, `appleHardwareArgs()` or VideoToolbox. Lean remains deferred to
   S5. Reject GPL for iOS and stop registering iOS GPL tasks. Make
   `StaticLinkFlags.thirdPartyArchives(iOS, LGPL)` and host fallback search flags empty, and make
   the iOS static target flags exactly `-lz`. `BuildFFmpegTask` and `FFmpegPaths.resolve` both
   reject GPL for every iOS target before looking for a vendored/system tree; the latter's KDoc and
   error no longer advertise nonexistent `[Gpl]` tasks or VideoToolbox on iOS. The Apple-phone
   selector also rejects `-Pkitecodec.ffmpeg.license=gpl` during configuration before resolution.
   The selector and `FFmpegPaths` diagnostics contain the exact stable prefix
   `iOS GPL refusal: FFmpegLicense.GPL is unsupported for iOS; use LGPL.`
   `BuildFFmpegTaskTest` compares the complete ordered device and simulator argument lists, not a
   tail/contains subset, and pins LGPL acceptance plus GPL refusal. `FFmpegPathsTest` pins the
   general iOS GPL refusal. Link tests compare the exact sets and prove a path containing `#` is
   staged rather than refused.
3. Add `-Pkitecodec.applePhoneTargetsOnly=true`, mutually exclusive with the existing target-set
   properties, registering exactly macosArm64, iosArm64 and iosSimulatorArm64 on this arm64 Mac.
   Accept it only for Maven-local publication. Every remote publish continues to require the
   stable release set and rejects this property.
4. Add `FFmpegSource.Local` plus required `FFmpegSpec.localRoot: DirectoryProperty`. Its only
   layout is `<localRoot>/<license.id>/<target.triple>/{include,lib}`. Validate
   `include/libavformat/avformat.h` and all six `libav*.a` files for every wired target with
   actionable diagnostics; Local never fetches or accesses a network. Reject Local plus GPL on
   either iOS target. For Local macosArm64 put `-L<local>/lib` first, the host fallback `-L`
   second, and reuse `PrebuiltLinkFlags.extraLinkerOpts(target, license)` for the desktop static
   stack. For Local iOS use only `-L<local>/lib` plus `-lz`, with none of the desktop archives,
   C++ runtime or VideoToolbox frameworks. Pin every branch in functional tests, including a fake
   complete Local linuxX64 tree that receives the local `-L` plus the desktop link set, no macOS
   host fallback `-L`, and no fetch task. This is the non-Apple Local branch; leaving it untested is
   not "every branch".
5. Producer and consumer are separate invocations because `FFmpegPaths.resolve()` runs during
   Gradle configuration and cannot see a tree produced later in the same invocation. Run:

   ```bash
   cd ../KiteCodec
   ./gradlew :kitecodec-core:buildFFmpegForMacosArm64 \
     :kitecodec-core:buildFFmpegForIosArm64 \
     :kitecodec-core:buildFFmpegForIosSimulatorArm64 --rerun-tasks

   ./gradlew :kitecodec-core:compileKotlinMacosArm64 \
     :kitecodec-core:compileKotlinIosArm64 \
     :kitecodec-core:compileKotlinIosSimulatorArm64 \
     :kitecodec-core:linkDebugTestIosArm64 \
     :kitecodec-core:linkDebugTestIosSimulatorArm64 \
     -Pkitecodec.applePhoneTargetsOnly=true --rerun-tasks

   S1B_SIM=5DBA149A-E990-4197-8A7D-31E97658B568
   xcrun simctl boot "$S1B_SIM" 2>/dev/null || :
   xcrun simctl bootstatus "$S1B_SIM" -b
   ./gradlew :kitecodec-core:iosSimulatorArm64Test \
     -Pkitecodec.applePhoneTargetsOnly=true \
     --device "Test iPhone 17" --rerun-tasks
   ```

   Do not pass the global `kitecodec.requireAllTargets` property to these explicit core-only
   commands. It also configures the unrelated sample's five desktop targets and fails on the
   deliberately absent macosX64 tree before a requested core task can run. The three named target
   tasks, the preceding complete archive/provenance inspection and the later publication-implied
   core require-all check keep this proof fail-closed. Make the command in `docs/platforms.md`
   match this corrected form. Verify all generated task names during S1.b.0. Keep the standing
   host-only `apiCheck`, because
   the committed KiteCodec API dump remains the macOS baseline and the public Kotlin surface does
   not change. The first real simulator run exposed a pre-existing native-test path defect: 19 of
   85 tests failed because the three named file-writing suites use relative `build/` outputs, and
   that directory does not exist inside the simulator app sandbox. Preserve that red transcript.
   In each of those three test files, replace the duplicated relative root with the first nonblank
   value from POSIX `TMPDIR`, `TEMP` and `TMP`, converted with `toKString()`. Fail explicitly if no
   writable temporary root is advertised; trim a trailing slash or backslash, append the same
   `kitecodec-test-<name>` filename and retain the existing cleanup list. Do not use Foundation in
   shared `nativeTest`, and do not add a fourth helper file. Rerun the exact simulator command and
   require all 85 tests green, then run macosArm64Test to prove the portable path preserves the
   host suite.
6. Inspect every one of the six archives in every tree, not a representative member. The gate
   asserts rather than merely prints: `lipo` must report exactly arm64, the real object-member
   count from `ar -t` must equal the number of `otool` platform records after excluding only the
   Apple archive index `__.SYMDEF` or `__.SYMDEF SORTED`, and the only platform value must match the
   tree:

   ```bash
   set -euo pipefail
   for t in macos-arm64 ios-arm64 ios-simulator-arm64; do
     case "$t" in
       macos-arm64) S1B_PLATFORM=1 ;;
       ios-arm64) S1B_PLATFORM=2 ;;
       ios-simulator-arm64) S1B_PLATFORM=7 ;;
       *) exit 2 ;;
     esac
     for a in avformat avcodec avfilter avutil swscale swresample; do
       S1B_ARCHIVE="native-libs/lgpl/$t/lib/lib$a.a"
       test "$(xcrun lipo -archs "$S1B_ARCHIVE")" = arm64 || exit 1
       S1B_MEMBERS="$(/usr/bin/ar -t "$S1B_ARCHIVE" | \
         awk '$0 !~ /^__[.]SYMDEF( SORTED)?$/' | wc -l | tr -d ' ')"
       S1B_RECORDS="$(xcrun otool -l "$S1B_ARCHIVE" | \
         awk '/platform / {print $2}' | wc -l | tr -d ' ')"
       test "$S1B_RECORDS" = "$S1B_MEMBERS" || exit 1
       S1B_UNIQUE="$(xcrun otool -l "$S1B_ARCHIVE" | \
         awk '/platform / {print $2}' | sort -u)"
       test "$S1B_UNIQUE" = "$S1B_PLATFORM" || exit 1
     done
   done
   ```

   Architecture is `arm64` throughout; the unique `LC_BUILD_VERSION` platform is 1 for macOS, 2
   for iOS and 7 for iOS Simulator. The correction-time generated trees measured object/platform
   counts, in avformat/avcodec/avfilter/avutil/swscale/swresample order, of
   `104/307/48/102/30/13` for macOS and `104/235/46/94/20/9` for each iOS tree. Re-measure rather
   than hard-coding those counts as a future ceiling. Run the export, signature and metadata audits
   unchanged; none of their committed baselines moves.
   Prove stable configure provenance in all three final trees and one packaged phone asset:

   ```bash
   set -euo pipefail
   for t in macos-arm64 ios-arm64 ios-simulator-arm64; do
     S1B_CONFIG="native-libs/lgpl/$t/lib/kitecodec/ffmpeg-configure.txt"
     test -s "$S1B_CONFIG"
     test "$(wc -l < "$S1B_CONFIG" | tr -d ' ')" = 1
     grep -q '[^[:space:]]' "$S1B_CONFIG"
   done
   S1B_CONFIG=native-libs/lgpl/ios-arm64/lib/kitecodec/ffmpeg-configure.txt
   S1B_EXPECTED_CONFIGURE="$(cat "$S1B_CONFIG")"
   S1B_ASSET=dist/ffmpeg-n8.0-lgpl-ios-arm64.zip
   trap 'rm -f "$S1B_ASSET" "$S1B_ASSET.sha256"' EXIT
   bash .github/scripts/package-ffmpeg.sh n8.0 lgpl ios-arm64 vendor/ffmpeg
   test -f "$S1B_ASSET"
   unzip -p "$S1B_ASSET" BUILD-INFO.txt | \
     grep -Fx "Configure:        $S1B_EXPECTED_CONFIGURE"
   rm -f "$S1B_ASSET" "$S1B_ASSET.sha256"
   trap - EXIT
   ```

   The generated dist pair is test evidence only and is removed by exact path after inspection.
   A missing provenance record or stale `(unavailable)` fallback must fail before packaging.
7. Prove GPL iOS and the local-only selector cannot escape before using it:

   ```bash
   set -euo pipefail
   S1B_NEGATIVE="$(mktemp)"
   trap 'rm -f "$S1B_NEGATIVE"' EXIT
   if ./gradlew help \
     -Pkitecodec.applePhoneTargetsOnly=true \
     -Pkitecodec.ffmpeg.license=gpl >"$S1B_NEGATIVE" 2>&1; then
     echo "expected iOS GPL configuration refusal" >&2
     exit 1
   fi
   grep -F 'iOS GPL refusal: FFmpegLicense.GPL is unsupported for iOS; use LGPL.' \
     "$S1B_NEGATIVE"
   if ./gradlew :kitecodec-core:publish \
     -Pkitecodec.applePhoneTargetsOnly=true --dry-run >"$S1B_NEGATIVE" 2>&1; then
     echo "expected remote publication refusal" >&2
     exit 1
   fi
   grep -F 'Experimental phone selector refusal: -Pkitecodec.applePhoneTargetsOnly=true may only' \
     "$S1B_NEGATIVE"
   rm -f "$S1B_NEGATIVE"
   trap - EXIT
   ```

   Expect the first command nonzero during configuration with an explicit no-iOS-GPL refusal, and
   the second nonzero during configuration, before repository or network work, with an explicit
   experimental-phone-selector refusal. Then run exactly one local publication:
   `./gradlew publishToMavenLocal -Pkitecodec.applePhoneTargetsOnly=true`. Create a temporary
   consumer outside both repositories at the fixed cross-shell path
   `/private/tmp/kitecodec-s1b-phone-consumer` and preserve it through S1.c.2. Its exact files are
   `settings.gradle.kts`, `build.gradle.kts` and
   `src/commonMain/kotlin/Smoke.kt`. Settings use plugin management `mavenLocal()` plus the plugin
   portal, dependency resolution `mavenLocal()` plus Maven Central, and one root project. The build
   applies KMP 2.4.10 and `io.github.yuroyami.kitecodec` 0.0.1, declares macosArm64, iosArm64 and
   iosSimulatorArm64 with one static framework each, and configures `FFmpegSource.Local`, LGPL and
   the absolute `/Users/macbook/StudioProjects/#Kite/KiteCodec/native-libs` root. Common source
   exposes one function that calls `FFmpeg.hasDecoder("h264")`, so dead-code elimination cannot
   make the link vacuous. Recreate those three files exactly if the scratch directory is absent;
   no later shell relies on an inherited variable. Materialize them with `apply_patch`, preserving
   these exact bytes.

   `settings.gradle.kts`:

   ```kotlin
   pluginManagement {
       repositories {
           mavenLocal()
           google()
           gradlePluginPortal()
           mavenCentral()
       }
   }

   dependencyResolutionManagement {
       repositories {
           mavenLocal()
           google()
           mavenCentral()
       }
   }

   rootProject.name = "kitecodec-s1b-phone-consumer"
   ```

   `build.gradle.kts`:

   ```kotlin
   import io.github.yuroyami.kitecodec.gradle.FFmpegLicense
   import io.github.yuroyami.kitecodec.gradle.FFmpegSource

   plugins {
       kotlin("multiplatform") version "2.4.10"
       id("io.github.yuroyami.kitecodec") version "0.0.1"
   }

   kotlin {
       macosArm64 {
           binaries.framework { baseName = "Smoke"; isStatic = true }
       }
       iosArm64 {
           binaries.framework { baseName = "Smoke"; isStatic = true }
       }
       iosSimulatorArm64 {
           binaries.framework { baseName = "Smoke"; isStatic = true }
       }
       sourceSets.commonMain.dependencies {
           implementation("io.github.yuroyami:kitecodec-core:0.0.1")
       }
   }

   kitecodec {
       ffmpeg {
           source = FFmpegSource.Local
           license = FFmpegLicense.LGPL
           localRoot.set(file("/Users/macbook/StudioProjects/#Kite/KiteCodec/native-libs"))
       }
   }
   ```

   `src/commonMain/kotlin/Smoke.kt`:

   ```kotlin
   package smoke

   import io.github.yuroyami.kitecodec.FFmpeg

   public fun hasH264Decoder(): Boolean = FFmpeg.hasDecoder("h264")
   ```

   Prove the three Maven-local variants offline:

   ```bash
   set -euo pipefail
   S1B_CODEC_SMOKE=/private/tmp/kitecodec-s1b-phone-consumer
   test -f "$S1B_CODEC_SMOKE/settings.gradle.kts"
   test -f "$S1B_CODEC_SMOKE/build.gradle.kts"
   test -f "$S1B_CODEC_SMOKE/src/commonMain/kotlin/Smoke.kt"
   ./gradlew -p "$S1B_CODEC_SMOKE" \
     linkDebugFrameworkMacosArm64 \
     linkDebugFrameworkIosArm64 \
     linkDebugFrameworkIosSimulatorArm64 \
     --offline --refresh-dependencies --rerun-tasks
   ```
8. Update every named current-state guide and executable comment. In particular, the native README must no longer say
   that BuildFFmpegTask merely refuses `#Kite`; the task now keeps configure and make in the
   hash-free scratch tree. The getting-started and plugin guides distinguish System, repository
   builds, and a no-network Local consumer tree, and the iOS pages state the measured standard
   software profile without claiming public artifacts or CI. CI comments say iosArm64 and
   iosSimulatorArm64 LGPL are locally buildable but not CI-covered; iosX64 remains unqualified and
   there are no iOS GPL tasks. Release-workflow comments make the same distinction without adding
   a job or release claim. The symbol-audit header acknowledges the three Apple FFmpeg trees and
   archives while leaving its logic and baselines unchanged.

Gate. Tier 2, selected by buildSrc, plugin source and build scripts. The one local publish above
replaces Tier 2's host-only publish for this sub-phase; do not publish twice. Run every other
Tier 2 command, with its ordinary host-only target selector where written. Then both Tier 1 blocks
close the window. Close with scans proving no AVPlayer, AVAssetReader,
AVSampleBufferDisplayLayer, VideoToolbox, VTDecompressionSession, Metal, CVPixelBuffer, Compose,
UIKitView or KitePlayerView entered the product diff and neither version catalog changed. Apple
SDK frameworks and zlib are system inputs, not new Gradle dependencies.

Commit first line. KiteCodec: `Make the mobile Apple FFmpeg build local and reproducible`.
KitePlayer: `Record the local mobile Apple Codec proof`.

#### S1.b.2 Split the Apple targets at their real platform boundary

Execution-fence correction commit first line. KitePlayer:
`Correct the Apple clock boundary for iOS`.

Second execution-fence correction commit first line. KitePlayer:
`Use CoreMedia for the shared Apple host clock`.

Third execution-fence correction commit first line. KitePlayer:
`Use CoreVideo for the shared Apple host clock`.

Files: `settings.gradle.kts`; `kiteplayer-output/build.gradle.kts`; move
`kiteplayer-output/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/output/AppKitVideoRenderer.kt`
and `AppKitWindow.kt` to the same package under `src/macosArm64Main`; move
`kiteplayer-output/src/appleTest/kotlin/io/github/yuroyami/kiteplayer/output/AppKitVideoRendererTest.kt`
and `RealTimeSoakTest.kt` to the same package under `src/macosArm64Test`, all four without other
edits; `kiteplayer-output/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/output/AppleHostClock.kt`;
`kiteplayer-output/src/appleTest/kotlin/io/github/yuroyami/kiteplayer/output/CoreAudioSinkTest.kt`;
`kiteplayer-ffmpeg/build.gradle.kts`;
`kiteplayer-output/api/kiteplayer-output.klib.api`;
`kiteplayer-ffmpeg/api/kiteplayer-ffmpeg.klib.api`; KPKMP log.

Steps.
1. Temporarily register iosSimulatorArm64 in output before the moves and retain the exact failure
   from `./gradlew :kiteplayer-output:compileKotlinIosSimulatorArm64 --rerun-tasks`: unresolved
   `platform.AppKit`, plus unavailable `AudioConvertHostTimeToNanos` and
   `AudioGetCurrentHostTime` in AppleHostClock. The later test link would also fail on the direct
   AudioGetCurrentHostTime call in CoreAudioSinkTest. Restore, perform only the four moves, then
   register iosArm64 and iosSimulatorArm64 in output and FFmpeg. Keep macosArm64 and its tests. Do
   not add sample targets yet.
2. Keep AppleHostClock in shared appleMain and preserve its public object and functions exactly.
   Kotlin/Native 2.4.10 does not expose `mach_absolute_time` or `mach_timebase_info`, even though
   those APIs exist in the Apple SDK. Use Kotlin/Native's bound scalar CoreVideo host-time surface.
   Cache `CVGetHostClockFrequency()` once. Require it to be finite, positive, exactly representable
   as an integral ULong, and no larger than `ULong.MAX_VALUE / 1_000_000_000`. Convert raw ticks by
   splitting them into whole seconds and a remainder: check the whole-seconds product against
   `Long.MAX_VALUE`, multiply the remainder only after the frequency bound makes that safe, divide
   it by the frequency and check the final addition. Round toward zero. Never multiply the complete
   raw tick count by one billion and never convert that absolute count through Double. Implement
   `nanos()` by converting `CVGetCurrentHostTime()` and make `hostTimeToNanos(raw)` use the identical
   converter. CoreAudioSinkTest reads its comparison raw tick with `CVGetCurrentHostTime()`.

   Apple documents CoreVideo and CoreAudio host-time interchangeability on macOS; the iOS SDK
   exposes the same CoreVideo host-time APIs and defines CoreAudio `mHostTime` as mach absolute
   time. On this machine the cached frequency is exactly 24,000,000 ticks per second, and one
   million same-raw comparisons against `AudioConvertHostTimeToNanos` have a maximum difference of
   zero nanoseconds. A ten-million read Kotlin/Native benchmark measured this checked quotient at
   30.35 ns per read and 50 GC epochs, against 50.61 ns and 50 epochs for the old macOS-only
   CoreAudio route and 312.72 ns and 405 epochs for the rejected CoreMedia CValue route. The three
   target klibs and final links must gain CoreVideo through the generated platform binding without
   an explicit Gradle dependency. The macOS clock tests, RealMediaSeekTest and both iOS final test
   links must pass. This adds no public API or dependency.
3. Only `kiteplayer-ffmpeg` applies the KiteCodec plugin and carries FFmpeg. Configure it from the
   provider `kitecodec.ffmpeg.localRoot`: when present, select `FFmpegSource.Local`, LGPL and that
   absolute root for phone links; when absent, preserve `FFmpegSource.System` for the standing
   host-only gate. `kiteplayer-output` remains FFmpeg-free and receives no plugin or Local block.
   Update the settings build note with the new local-only Apple publication and consumption
   command.
4. Prove the shared Apple sets have no AppKit imports and compile and final-link all three target
   families from a fresh local dependency resolution:

   ```bash
   rg -n 'platform\.AppKit' \
     kiteplayer-output/src/appleMain kiteplayer-output/src/appleTest

   ./gradlew \
     :kiteplayer-output:compileKotlinMacosArm64 \
     :kiteplayer-output:compileKotlinIosArm64 \
     :kiteplayer-output:compileKotlinIosSimulatorArm64 \
     :kiteplayer-output:linkDebugTestIosArm64 \
     :kiteplayer-output:linkDebugTestIosSimulatorArm64 \
     :kiteplayer-ffmpeg:compileKotlinMacosArm64 \
     :kiteplayer-ffmpeg:compileKotlinIosArm64 \
     :kiteplayer-ffmpeg:compileKotlinIosSimulatorArm64 \
     :kiteplayer-ffmpeg:linkDebugTestIosArm64 \
     :kiteplayer-ffmpeg:linkDebugTestIosSimulatorArm64 \
     -Pkitecodec.ffmpeg.localRoot="$PWD/../KiteCodec/native-libs" \
     --offline --refresh-dependencies --rerun-tasks
   ```

   The `rg` command must print nothing and return 1. The link command must consume the S1.b.1
   Maven-local variants and generated standard FFmpeg trees, not stale Gradle cache entries.
5. Target addition necessarily changes ABI dump metadata. Run:

   ```bash
   ./gradlew :kiteplayer-output:updateKotlinAbi :kiteplayer-ffmpeg:updateKotlinAbi
   ./gradlew :kiteplayer-output:checkKotlinAbi :kiteplayer-ffmpeg:checkKotlinAbi
   ```

   Review both dumps from their current macosArm64-only baselines, 82 and 94 lines. Their target
   sets become `[iosArm64, iosSimulatorArm64, macosArm64]`; no declaration is added or removed,
   and AppKit declarations remain macOS-only.

Gate. Tier 2, selected by build scripts and source-set moves, plus the exact fresh local phone
link proof above. No new dependency or version is allowed.

Commit first line. KitePlayer: `Split AppKit from the shared Apple output target`.

#### S1.b.3 Qualify the real-time sink on iOS

Execution-fence correction commit first line. KitePlayer:
`Correct the iOS sink truth fence`.

Simulator-host correction commit first line. KitePlayer:
`Run iOS native tests inside an app host`.

Files: `README.md`; `kiteplayer-rt/native/src/kite_rt_coreaudio.c`;
`kiteplayer-rt/native/include/kite_rt.h`;
`kiteplayer-rt/src/nativeInterop/cinterop/kitert.def`; `kiteplayer-rt/build.gradle.kts`;
`kiteplayer-rt/README.md`; `kiteplayer-rt/native/scripts/build-host.sh`;
`kiteplayer-rt/native/scripts/render-audit.sh`;
`kiteplayer-rt/native/scripts/source-discipline.sh`;
`kiteplayer-rt/native/tests/test_sink_callback.c`;
`kiteplayer-rt/src/nativeTest/kotlin/io/github/yuroyami/kiteplayer/rt/KiteRtBindingTest.kt`;
`kiteplayer-output/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/output/CoreAudioSink.kt`;
`AppleOutputBackend.kt`; new `AppleAudioSessionPolicy.kt` in that package; new
`kiteplayer-output/src/macosArm64Main/kotlin/io/github/yuroyami/kiteplayer/output/AppleAudioSession.macos.kt`;
new `kiteplayer-output/src/iosMain/kotlin/io/github/yuroyami/kiteplayer/output/AppleAudioSession.ios.kt`;
new `kiteplayer-output/src/appleTest/kotlin/io/github/yuroyami/kiteplayer/output/AppleAudioSessionPolicyTest.kt`;
existing `CoreAudioSinkTest.kt`, `CoreAudioSinkRealTimeTest.kt` and `CRingSupport.kt` under
appleTest; new
`kiteplayer-output/src/iosTest/kotlin/io/github/yuroyami/kiteplayer/output/CoreAudioSinkIosTest.kt`;
`kiteplayer-output/api/kiteplayer-output.klib.api`; KPKMP log.

Steps.
1. Add the simulator and policy fixtures first. Retain the exact unsupported-platform failure from
   the former and behavioral failures from the latter. Extend the existing callback C suite through
   a test-only wrapper compiled by `build-host.sh`, never exported by the shipped archive. Wrong
   buffer count must zero every safely writable buffer and set the silence flag; null or zero-sized
   destinations set silence; a short byte size clamps without crossing a canary; the correct single
   interleaved layout still renders. Delete each relevant guard once, retain the failures, restore,
   and keep eight suites while remeasuring the current 127-case baseline.
2. Widen the C implementation guard to macOS or iOS, choose DefaultOutput on macOS and RemoteIO on
   iOS, and keep the callback body common. Add `linkerOpts.ios = -framework AudioToolbox`. Update
   every touched current-state comment. `render-audit.sh` must extract and audit both
   `kite_rt_render.o` and `kite_rt_coreaudio.o` from all three generated archives:
   `build/kiteplayer-rt-c/macos_arm64/libkiteplayerrt.a`,
   `ios_arm64/libkiteplayerrt.a` and `ios_simulator_arm64/libkiteplayerrt.a`. A clean clone may
   skip absent optional archives, but the S1.b.3 gate first builds all three and permits no skip.
   The source and object audits reject swapped subtypes and any new callback call.
3. Add public `AppleAudioSessionPolicy` with exactly `ManagedPlayback` and
   `ApplicationManaged`. Preserve the existing `CoreAudioSink(clock = ...)` and
   `CoreAudioSinkFactory(clock = ...)` signatures. Add policy-first overloads with the clock
   defaulted; `AppleOutputBackend` continues to choose ManagedPlayback. ManagedPlayback is a
   process-wide synchronized lease: the first lease sets Playback, MoviePlayback and no category
   options, then activates; later leases increment a count without another session call. C
   creation happens only after acquisition, every C open or attach failure releases, close stops
   and destroys RemoteIO before release, and only the final release deactivates with
   NotifyOthersOnDeactivation. ApplicationManaged performs no session call; macOS is a no-op
   lease. All session work is off the callback thread. Use an internal fake-controller seam to pin
   activation failure, rollback, concurrent leases and exact call order. Route, interruption and
   background recovery remain out of S1.b because the engine does not collect `AudioSink.events`.
4. Boot and run exact native proofs:

   ```bash
   xcrun simctl list devices available
   S1B_SIM=5DBA149A-E990-4197-8A7D-31E97658B568
   xcrun simctl boot "$S1B_SIM" 2>/dev/null || :
   xcrun simctl bootstatus "$S1B_SIM" -b

   ./gradlew :kiteplayer-rt:compileKiteRtCForMacosArm64 \
     :kiteplayer-rt:compileKiteRtCForIosArm64 \
     :kiteplayer-rt:compileKiteRtCForIosSimulatorArm64
   ./gradlew :kiteplayer-rt:iosSimulatorArm64Test \
     --device "Test iPhone 17" --rerun-tasks
   ./gradlew :kiteplayer-output:linkDebugTestIosSimulatorArm64 \
     --rerun-tasks
   ./gradlew :kiteplayer-output:linkDebugTestIosArm64 --rerun-tasks
   kiteplayer-rt/native/scripts/render-audit.sh
   kiteplayer-rt/native/scripts/render-audit.sh --prove-it-can-fail
   kiteplayer-rt/native/scripts/source-discipline.sh
   ```

   Kotlin Gradle plugin 2.4.10 launches `KotlinNativeSimulatorTest` as `simctl spawn --standalone`
   around a bare kexe. RemoteIO cannot initialise in that command-line host even though the same
   linked program succeeds when SpringBoard launches it as an application. Copy the exact freshly
   linked `test.kexe` byte for byte, without recompilation or a second test implementation, into a
   minimal ad-hoc-signed simulator app and run it with the following self-contained recipe:

   ```bash
   set -euo pipefail
   cd '/Users/macbook/StudioProjects/#Kite/KitePlayer'
   S1B_SIM=5DBA149A-E990-4197-8A7D-31E97658B568
   S1B3_ROOT="$(mktemp -d /private/tmp/kiteplayer-s1b3-output-tests.XXXXXX)"
   S1B3_APP="$S1B3_ROOT/KitePlayerOutputTests.app"
   S1B3_EXECUTABLE="$S1B3_APP/KitePlayerOutputTests"
   S1B3_KEXE="$PWD/kiteplayer-output/build/bin/iosSimulatorArm64/debugTest/test.kexe"
   S1B3_TRANSCRIPT="$S1B3_ROOT/launch.txt"
   test -f "$S1B3_KEXE"
   mkdir -p "$S1B3_APP"
   cp -f "$S1B3_KEXE" "$S1B3_EXECUTABLE"
   cmp -s "$S1B3_KEXE" "$S1B3_EXECUTABLE"
   plutil -create xml1 "$S1B3_APP/Info.plist"
   plutil -insert CFBundleDevelopmentRegion -string en "$S1B3_APP/Info.plist"
   plutil -insert CFBundleExecutable -string KitePlayerOutputTests "$S1B3_APP/Info.plist"
   plutil -insert CFBundleIdentifier -string io.github.yuroyami.kiteplayer.output-tests \
     "$S1B3_APP/Info.plist"
   plutil -insert CFBundleInfoDictionaryVersion -string 6.0 "$S1B3_APP/Info.plist"
   plutil -insert CFBundleName -string KitePlayerOutputTests "$S1B3_APP/Info.plist"
   plutil -insert CFBundlePackageType -string APPL "$S1B3_APP/Info.plist"
   plutil -insert CFBundleShortVersionString -string 1.0 "$S1B3_APP/Info.plist"
   plutil -insert CFBundleVersion -string 1 "$S1B3_APP/Info.plist"
   plutil -insert LSRequiresIPhoneOS -bool YES "$S1B3_APP/Info.plist"
   plutil -insert MinimumOSVersion -string 14.0 "$S1B3_APP/Info.plist"
   plutil -insert UIDeviceFamily -json '[1]' "$S1B3_APP/Info.plist"
   codesign --force --sign - "$S1B3_APP"
   codesign --verify --strict "$S1B3_APP"
   xcrun simctl uninstall "$S1B_SIM" io.github.yuroyami.kiteplayer.output-tests \
     >/dev/null 2>&1 || :
   xcrun simctl install "$S1B_SIM" "$S1B3_APP"
   xcrun simctl launch --console --terminate-running-process \
     "$S1B_SIM" io.github.yuroyami.kiteplayer.output-tests 2>&1 | tee "$S1B3_TRANSCRIPT"
   grep -Fqx '[==========] Running 28 tests from 4 test cases.' "$S1B3_TRANSCRIPT"
   grep -Fqx '[  PASSED  ] 28 tests.' "$S1B3_TRANSCRIPT"
   grep -Fq '[       OK ] io.github.yuroyami.kiteplayer.output.CoreAudioSinkIosTest.RemoteIO consumes the C ring publishes an anchor and tears down completely' \
     "$S1B3_TRANSCRIPT"
   test "$(grep -Fc '[  FAILED  ]' "$S1B3_TRANSCRIPT")" -eq 0
   ```

   The simulator test observes a positive callback count, consumed ring, near-future anchor,
   idempotent close, zero retained C handles and a fresh second sink open/start/stop/close. It does
   not claim an externally observable running AudioUnit after termination. Temporarily select
   DefaultOutput in the iOS arm; the app-hosted simulator test must fail, then restoration passes.
   The ordinary standalone task is retained only as the red host-boundary control and is never
   reported as a product failure. Any sandbox CoreSimulator denial is retried with required host
   access and recorded, never reported as a simulator result.
5. Run the existing supervised macOS device pair exactly:

   ```bash
   KPRT_DEVICE_SOAK=1 KPRT_DEVICE_SOAK_MINUTES=10 \
     ./gradlew :kiteplayer-output:macosArm64Test \
     --tests '*RealTimeSoakTest*' --rerun-tasks -i
   KPRT_DEVICE_SOAK=1 KPRT_DEVICE_SOAK_MINUTES=10 \
     ./gradlew :kiteplayer-ffmpeg:macosArm64Test \
     --tests '*RealTimeMediaSoakTest*' --rerun-tasks -i
   ```

   Update the output ABI dump for additions only and run its update/check pair.
6. Keep the root repository's current-state guide true at every remaining S1.b product commit
   rather than waiting for the final sample. State narrowly that macOS remains the only end-to-end
   candidate above T1, while the local/private iOS arm64 and simulator substrate now has the
   software-codec backend plus RemoteIO audio but still has no renderer, runnable consumer,
   physical-device result or tier promotion. Correct the affected macOS-only/refusal sentences,
   current test counts and module target rows, including the already-landed S1.b.2 output and
   FFmpeg target expansion. Do not claim iosX64 qualification, public artifacts, end-to-end iOS
   playback or anything owned by S1.b.4/S1.b.5. `gradle.properties` remains unread and untouched
   under S1.a.2 and is rechecked as
   such at S1.c.0; this correction does not override that explicit owner constraint.

Gate. Tier 3, selected conservatively because this promotes a new platform through the same
`kprt_render_cb` and changes its compilation guard. Run full Tier 2, the existing supervised macOS
device pair, the iOS simulator device suite, then Tier 1. Record simulator and macOS numbers
separately. Do not call either a physical-iPhone result. Close with scans rejecting platform
demux/decoders, VideoToolbox, Metal, Compose and reusable view symbols from the product additions.

Commit first line. KitePlayer: `Run the real-time sink through RemoteIO on iOS`.

#### S1.b.4 Render converted frames into a caller-owned iOS layer

Ownership-fence correction commit first line. KitePlayer:
`Keep the caller-owned iOS layer contents intact`.

Files: `README.md`; new
`kiteplayer-output/src/iosMain/kotlin/io/github/yuroyami/kiteplayer/output/UIKitVideoRenderer.kt`;
new `kiteplayer-output/src/iosTest/kotlin/io/github/yuroyami/kiteplayer/output/UIKitVideoRendererTest.kt`;
`kiteplayer-output/api/kiteplayer-output.klib.api`; KPKMP log.

Steps.
1. Land the fixture tests against a deliberately unguarded delivery skeleton and retain the
   predicted queued-block failure. The complete public production shape is:

   ```kotlin
   public class UIKitVideoRenderer(
       layer: CALayer,
       convert: (VideoFrame) -> ByteArray,
   ) : VideoRenderer
   ```

   Its public counters are exactly `presentedFrames`, `supersededFrames` and `failedFrames`, all
   `Long`. Its internal constructor is exactly
   `(convert: (VideoFrame) -> ByteArray, enqueueOnMain: (block: () -> Unit) -> Unit,
   deliverImage: (CGImageRef?) -> Unit)`. The delivery callback receives a borrowed image for the
   duration of the call; the renderer retains and releases its own reference. The nullable call
   clears deterministic test-seam bookkeeping without inventing a second public surface. The
   production callback treats null as a no-op and never clears the caller-owned layer.
   Implement the bounded two-slot renderer without changing the AppKit renderer or extracting a
   shared abstraction at only the second use. Output remains FFmpeg-free and does not depend on
   `SoftwareConverter`; the caller injects conversion.
2. Pin newest-frame wins, exact-once close on delivery, failure, supersession and renderer close,
   bounded queued deliveries, dimensions, quarter-turn rotation and opaque-frame refusal. Set
   aspect-fit gravity, disable implicit CALayer animations and define CGImage retain/release
   ownership. A Create/Copy image in the pending delivery slot is the renderer's +1; superseding it
   before delivery releases it immediately because CALayer never retained it. In production, a
   successful callback assigns the image so CALayer retains it, then moves the renderer's +1 into a
   last-delivered slot and releases the displaced last-delivered +1. Close releases the renderer's
   final +1 without clearing CALayer. The pending slot, queued-delivery flag, delivery callback,
   last-delivered ownership and close use one critical section, so no queued block can deliver after
   close returns or strand an in-flight reference. Invoke `enqueueOnMain` only after releasing that
   section because the deterministic seam may run its block inline. Test non-null real
   `CALayer.contents` on the named simulator.
   `supportedHardwareSurfaces()` is empty, `supports(format)` is exactly `format != Opaque`,
   vsync is null, viewport and overlay are no-ops, and `events` is an empty flow. `close()` is
   idempotent: stop acceptance, stop and join the conversion worker, drain and close both slots,
   release every image still owned by the renderer, then close the worker dispatcher. A queued
   delivery after close observes an empty slot and owns nothing. CALayer's already retained last
   contents remains owned by the caller's layer until that caller clears or replaces it. Update
   the dump with only this class and its counters. Update only the root README's affected renderer
   and current test-count truth: the local/private iOS substrate now has a caller-owned layer
   renderer, but still has no runnable consumer, end-to-end result, physical-device qualification,
   public artifact or tier move. Leave the final sample/run wording to S1.b.5.
3. Run:

   ```bash
   ./gradlew :kiteplayer-output:iosSimulatorArm64Test \
     --device "Test iPhone 17" \
     --tests '*UIKitVideoRendererTest*' --rerun-tasks
   ./gradlew :kiteplayer-output:updateKotlinAbi \
     :kiteplayer-output:checkKotlinAbi
   ```

Gate. Tier 2, selected because iosMain is native product code and this is a completed phone
render-path sub-phase. The simulator renderer test is an added named step. Tier 3 is not selected:
the C callback and teardown ordering do not change here. Scan this diff for AVPlayer,
AVAssetReader, AVSampleBufferDisplayLayer, VideoToolbox, Metal, CVPixelBuffer, Compose,
UIKitView and KitePlayerView; all must be absent.

Commit first line. KitePlayer: `Render software frames into an iOS layer`.

#### S1.b.5 Link, install and run the iOS sample

Execution-fence correction commit first line. KitePlayer:
`Correct iOS sample teardown and support truth`.

Files: `kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/KitePlayer.kt`;
`kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt`;
`kiteplayer-core/src/commonTest/kotlin/io/github/yuroyami/kiteplayer/KitePlayerTest.kt`;
`kiteplayer-core/src/commonTest/kotlin/io/github/yuroyami/kiteplayer/PlaybackCoreTest.kt`;
`kiteplayer-core/api/kiteplayer-core.klib.api`;
`kiteplayer-core/api/jvm/kiteplayer-core.api`; `kiteplayer-sample/build.gradle.kts`; new
`kiteplayer-sample/src/iosMain/kotlin/io/github/yuroyami/kiteplayer/sample/SampleViewController.kt`;
new `kiteplayer-sample/iosApp/KitePlayerSample/AppDelegate.swift` and `Info.plist`;
`kiteplayer-sample/iosApp/KitePlayerSample.xcodeproj/project.pbxproj`;
`kiteplayer-sample/iosApp/KitePlayerSample.xcodeproj/xcshareddata/xcschemes/KitePlayerSample.xcscheme`;
`kiteplayer-sample/iosApp/README.md`; root `README.md`; `KPKMP.md` sections 2, 8.1 and 8.2 truth
and log.

Steps.
1. Make the core's non-suspending and awaited close routes share one parentless terminal
   `CompletableDeferred<Unit>`. Reuse `closedNow` as the atomic request guard: its false-to-true CAS is
   the linearization point that immediately rejects later commands, and its winner synchronously queues
   the only Close command. `close()` triggers it, while concurrent and later awaited callers observe the
   same terminal success or `PlaybackError.RuntimeCompromised`. Settle the shared result at the final
   `runClose` step, after snapshot, termination state and dispatcher closure; structure that tail so an
   exception before success settlement reaches the typed actor-completion fallback. A rejected sole
   enqueue or actor completion before that step completes it with a typed
   `PlaybackException(PlaybackError.RuntimeCompromised)` instead of suspending waiters; the actor
   completion hook is the fallback and all completion paths settle the result exactly once. Await it,
   rethrow caller `CancellationException` immediately so teardown continues independently, and for
   success or a reported close failure join the actor before returning or rethrowing. Do not weaken the
   existing non-cancellable worker join. Add an internal close-deadline constructor override whose
   production default remains ten seconds and whose timeout and error detail both read the injected
   value. `PlaybackCoreTest` constructs `PlaybackCore` directly with a zero deadline for the deterministic
   failure fixture; do not route this override through `CoreHarness` or expand the file fence. Make
   `send`, the three direct fire-and-forget seek enqueues and cancellation's best-effort Stop consult
   `closedNow`, so a suspending command completes with the existing `IllegalStateException`, a direct
   seek throws that same terminal refusal synchronously, and cancellation cleanup becomes a no-op once
   close won the CAS. No command that starts after the CAS can enter the channel.

   Expose `public suspend fun closeAndAwait()` on `KitePlayer` as a direct facade over that corrected
   core route. Its KDoc says that a successful return means actor and teardown completion, that every
   reported compromised outcome throws `PlaybackException`, and that caller cancellation does not
   cancel teardown. Preserve the existing non-suspending `AutoCloseable.close()` behavior. Correct the
   `KitePlayer` and `PlaybackCore` overview and close KDocs: ordinary interactions still use actor
   messages, awaited ones use per-call replies, fire-and-forget ones discard or omit theirs, close routes
   share one result, the non-suspending call provides no completion proof, and the non-cancellable
   ownership join can outlive the request deadline and require process termination. Add
   `@throws IllegalStateException` after close to `KitePlayer.seekLater`. In the product
   commit, make the matching KPKMP section 8.1 message/reply description and close row retain the
   outstanding-command exactly-once guarantee while describing the shared result, non-cancel join and
   cancellation escape, and make its `seekLater` row name the terminal refusal. Add
   `suspend closeAndAwait()` to section 8.2's exact facade list. Add a
   facade success test proving the awaited call returns only after the scripted session and audio sink
   have closed and the public state is Idle; a healthy session that starts with no error must still
   have none. Add core tests proving `close()` followed by concurrent and repeated awaited calls share
   one success, share one deterministic `RuntimeCompromised` failure and remain terminal, and that
   cancelling one waiter does not cancel the shared close seen by the next waiter. Pin the CAS by calling
   non-suspending `close()` and, before the actor advances, proving both a suspending command and a direct
   fire-and-forget seek throw `IllegalStateException`. A directly constructed core whose parent is
   cancelled proves the actor-completion fallback returns typed failure rather than hanging. The failure
   fixtures use the internal zero-deadline override and do not change the production deadline. Update
   both the KLIB and JVM core ABI dumps for the facade addition only and run
   `./gradlew :kiteplayer-core:updateKotlinAbi` followed by
   `./gradlew :kiteplayer-core:checkKotlinAbi`. Add the awaited alternative to the root README's
   public-surface description, and remeasure every affected JVM, macOS and aggregate host test count
   after the new common tests instead of assuming a delta.

   Add both iOS framework targets, static and named `KitePlayerSample`, using Local FFmpeg. Export
   one controller factory. The Xcode build phase selects the matching Gradle framework from
   `PLATFORM_NAME`, passes the absolute `kitecodec.ffmpeg.localRoot` property and introduces no
   downloaded framework or CocoaPods layer. The bundle id is
   `io.github.yuroyami.kiteplayer.sample.ios`. `scripts/testmedia.sh` generates
   `testmedia/sync1080p30.mp4`, and the Xcode project declares it as a Copy Bundle Resources input.
   The Kotlin controller composes FFmpeg-owned demux/decode, Apple output and
   `UIKitVideoRenderer`; Swift owns only the UIKit host. The PBX Gradle build phase maps
   `iphonesimulator` to `:kiteplayer-sample:linkDebugFrameworkIosSimulatorArm64` and `iphoneos`
   to `:kiteplayer-sample:linkReleaseFrameworkIosArm64`, and exits nonzero for every other
   `PLATFORM_NAME`.
2. Add sample-only launch argument `--s1b-smoke`. Run the entire smoke workflow inside one 45-second
   application timeout. It auto-plays the bundled sync clip, requests one precise seek to five seconds,
   waits for Ended or Failed, closes and writes a bounded JSON result to the app Documents directory
   before terminating. `seekLanded` becomes true only after at least one
   post-seek presented frame and public `KitePlayer.position()` in the inclusive range 5,000 to
   5,034 milliseconds; a successful return from `seek()` alone cannot set it. The 34 millisecond
   tolerance is one frame of this 30 fps fixture. After playback reaches Ended, run
   `player.closeAndAwait()` inside a 12 second outer timeout. This is the smoke's external bound, not
   a promise that the core's ten-second deadline can interrupt its non-cancellable ownership join. A
   `RuntimeCompromised` result the core reaches throws `PlaybackException`; an uninterruptible wedge
   can instead reach the outer timeout, whose cancellation stops only the caller's wait while the
   queued terminal close continues. Success returns only after the actor terminates. For this healthy
   smoke, whose error is null before close, require the final public state to be Idle with error still
   null. Watch for Ended or Failed rather than waiting only for Ended. One outer catch/finally boundary
   covers open, decode, seek, that terminal wait and close. On any failure
   or total timeout, request the player's idempotent non-suspending close if it exists; an already queued
   awaited close continues. Always close the concurrency-safe renderer from `finally`. A thrown or
   timed-out operation fails the smoke and must never set `teardownCompleted`; that key becomes true only
   after the successful awaited player close, the Idle/no-error assertion and the renderer's synchronous
   close all complete. The result writer is outermost: cleanup and renderer-close exceptions are captured
   and cannot suppress it. On every path atomically write the exact nine-key result, using
   `teardownCompleted=false` for failure or timeout, and then terminate. Write
   `s1b-smoke.json.tmp` in the same directory, flush and close it, then atomically replace
   `s1b-smoke.json`; the observer must never see a partial record. It never exposes a production
   diagnostics API.
   Use the verified simulator UUID and exact commands:

   ```bash
   ./scripts/testmedia.sh
   xcrun simctl shutdown 5DBA149A-E990-4197-8A7D-31E97658B568 >/dev/null 2>&1 || :
   xcrun simctl boot 5DBA149A-E990-4197-8A7D-31E97658B568
   xcrun simctl bootstatus 5DBA149A-E990-4197-8A7D-31E97658B568 -b
   xcodebuild \
     -project kiteplayer-sample/iosApp/KitePlayerSample.xcodeproj \
     -scheme KitePlayerSample -configuration Debug \
     -destination 'platform=iOS Simulator,id=5DBA149A-E990-4197-8A7D-31E97658B568' \
     -derivedDataPath kiteplayer-sample/iosApp/build/DerivedData \
     CODE_SIGNING_ALLOWED=NO build
   xcrun simctl uninstall 5DBA149A-E990-4197-8A7D-31E97658B568 \
     io.github.yuroyami.kiteplayer.sample.ios || :
   xcrun simctl install 5DBA149A-E990-4197-8A7D-31E97658B568 \
     kiteplayer-sample/iosApp/build/DerivedData/Build/Products/Debug-iphonesimulator/KitePlayerSample.app
   xcrun simctl launch --terminate-running-process \
     5DBA149A-E990-4197-8A7D-31E97658B568 \
     io.github.yuroyami.kiteplayer.sample.ios --s1b-smoke

   S1B_DATA="$(xcrun simctl get_app_container \
     5DBA149A-E990-4197-8A7D-31E97658B568 \
     io.github.yuroyami.kiteplayer.sample.ios data)"
   S1B_RESULT="$S1B_DATA/Documents/s1b-smoke.json"
   S1B_TRIES=0
   while [ ! -s "$S1B_RESULT" ] && [ "$S1B_TRIES" -lt 60 ]; do
     sleep 1
     S1B_TRIES=$((S1B_TRIES + 1))
   done
   test -s "$S1B_RESULT"
   /usr/bin/jq -e '
     (keys | sort) == [
       "audioUnderruns", "decodedFrames", "layerImage", "presentedFrames",
       "seekLanded", "seekRequested", "submittedFrames", "teardownCompleted", "terminalState"
     ] and .seekRequested == true and
     .seekLanded == true and
     .terminalState == "Ended" and
     (.decodedFrames | type) == "number" and .decodedFrames > 0 and
     (.submittedFrames | type) == "number" and .submittedFrames > 0 and
     (.presentedFrames | type) == "number" and .presentedFrames > 0 and
     .layerImage == true and
     (.audioUnderruns | type) == "number" and .audioUnderruns >= 0 and
     .teardownCompleted == true
   ' "$S1B_RESULT"
   ```

   The JSON schema is exactly the nine keys used above:
   `seekRequested`, `seekLanded`, `terminalState`, `decodedFrames`, `submittedFrames`,
   `presentedFrames`, `layerImage`, `audioUnderruns` and `teardownCompleted`; no missing or renamed
   key can pass the oracle. Positive device callback and zero retained C handles are cited from
   S1.b.3, not inferred through inaccessible sample state.
3. Prove the device binaries link without claiming an unsigned archive or physical run:

   ```bash
   ./gradlew :kiteplayer-sample:linkReleaseFrameworkIosArm64 \
     -Pkitecodec.ffmpeg.localRoot="$PWD/../KiteCodec/native-libs" --rerun-tasks
   xcodebuild \
     -project kiteplayer-sample/iosApp/KitePlayerSample.xcodeproj \
     -scheme KitePlayerSample -configuration Release \
     -destination 'generic/platform=iOS' \
     -derivedDataPath kiteplayer-sample/iosApp/build/DeviceDerivedData \
     CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO build
   ```

   Record the connected physical iPhone inventory but reserve signing, audible/visual judgment
   and physical playback for S1.e's owner session.
4. Update the root README support, module and run sections to the measured local/private state.
   Split the Apple phone targets: iOS arm64 remains T1 link-only, while iOS simulator arm64 becomes
   an experimental T2 Codec candidate because the named run opens, decodes, seeks and reaches a
   causally awaited teardown over real media. This is a proposed above-T1 evidence label and therefore
   selects Tier 3, but real-media cancellation and the broader qualification matrix remain absent, so
   it does not grant the full T2 Codec tier. Update section 2's current baseline sentence to the same
   measured split. In the README replace the top summary and public-method list, the combined iOS row,
   the macOS-only-candidate sentences, the local-substrate and no-runnable-consumer limitations, the
   What-does-not-exist lifecycle text, the sample module/target/run instructions and every remeasured
   test count. Do not claim physical-iPhone qualification, public artifacts, VideoToolbox, reusable
   views, Compose or T3-Full. Close with scans rejecting platform
   demux/decoders, VideoToolbox, Metal, CVPixelBuffer, Compose, UIKitView and KitePlayerView from
   S1.b additions and prove neither version catalog changed.

Gate. Tier 3, selected by the proposed above-T1 simulator candidate label. Run full Tier 2, the named
simulator application run and iosArm64 framework/device-app link, then the standing supervised macOS
pair exactly once:

```bash
KPRT_DEVICE_SOAK=1 KPRT_DEVICE_SOAK_MINUTES=10 \
  ./gradlew :kiteplayer-output:macosArm64Test \
  --tests '*RealTimeSoakTest*' --rerun-tasks -i
KPRT_DEVICE_SOAK=1 KPRT_DEVICE_SOAK_MINUTES=10 \
  ./gradlew :kiteplayer-ffmpeg:macosArm64Test \
  --tests '*RealTimeMediaSoakTest*' --rerun-tasks -i
```

The candidate label remains below the full T2 Codec definition because real-media cancellation and
the broader matrix are absent; Tier 3 satisfies the standing promotion selector without upgrading the
claim. Close with Tier 1.

Commit first line. KitePlayer: `Add the runnable iOS phone sample`.

S1.b exits only when all five product commits exist locally, both trees are clean, the named
simulator plays and seeks through FFmpeg with RemoteIO and CALayer output, and every deviation is
in section 14. Nothing is pushed, publicly published or released.

### 17.4.3 The S1.c register and sub-phases, decision complete

Authored 2026-08-11 against clean KitePlayer `798f875` and KiteCodec `c2447c8`, after the S1.a
exit and against the committed S1.b expansion. S1.b product work had not landed at authorship, so
S1.b's clean exit is the one current BLOCKING entry condition. S1.c.0 runs only after that exit and
must verify this expansion against the resulting tree before any product edit. A contradiction is
handled by S1.c.0's classification rule, not silently adapted by an executor.

Expansion-authorship commit first line. KitePlayer:
`Expand the Android phone stage against the planned iOS substrate`.

The located substrate is exact at authorship, and the counts in THIS paragraph were re-anchored
by the 2026-08-12 scaffold (see the scaffold layer below): KiteCodec has ten common `expect`
declarations and nine native implementation files totalling 3,108 lines. Its opaque C boundary
has 192 normalized declaration records: 170 helper prototypes (ffkmp_packet_clone landed), eleven
opaque typedefs, seven ABI prototypes (kc_jvm_attach landed), three enums (kc_jvm_status landed)
and the report typedef. The C ABI stands at 2.1. The Android FFmpeg tasks are
`:kitecodec-core:buildFFmpegForAndroidArm64`,
`:kitecodec-core:buildFFmpegForAndroidArm32` and
`:kitecodec-core:buildFFmpegForAndroidX64`. S1.c deliberately uses the first and third only. The
current LGPL Android profile is API 24, static, zlib-enabled, and contains both software playback
decoders and FFmpeg's H.264/HEVC MediaCodec decoders with `--enable-jni` and
`--enable-mediacodec`. No Android FFmpeg output exists yet, which is expected producer work in
S1.c.1 rather than a blocker.

The machine has SDK 36 and NDK 27, 28c and 29 under
`/Users/macbook/WORKSTATION/AndroidSDK`. Every S1.c command pins
`ANDROID_NDK_HOME=/Users/macbook/WORKSTATION/AndroidSDK/ndk/29.0.14206865` and uses JDK 21.
`Pixelu16KB` is the one named Android device: an Android 36.1 arm64-v8a Google APIs 16 KiB AVD.
There is no x86_64 AVD or image, so x86_64 is compile, link and package qualified in S1.c but never
reported as runtime qualified. There is no attached physical Android device. That is BLOCKING for
S1.e's owner-device exit only; it does not block S1.c's named 16 KiB emulator proof.

Execution order is S1.c.0 through S1.c.6. KiteCodec window 2b closes once, after S1.c.2: build the
C/JNI layer and complete Kotlin actuals, publish one phone-superset coordinate locally, then prove
that the S1.b Apple consumer still links and an ordinary Android consumer resolves, shrinks, loads
and decodes from the same coordinate. Publishing an Android-only `0.0.1` is forbidden because it
would replace the root Gradle metadata written in window 2a and silently remove the iOS variants.
No later S1.c phase changes KiteCodec. Nothing from S1.d, S2 or KiteVideo enters these commits.

**THE 2026-08-12 SCAFFOLD LAYER, planner-landed, and the executor's remaining work.** At the
owner's direction the planner landed the mechanical substrate of S1.c.1 in the tree, every piece
gated as it landed. S1.c.0 verifies against the post-scaffold heads; the facts below are the new
ground truth and override any step text they contradict.

LANDED AND PROVEN (KiteCodec scaffold commit, 2026-08-12):

- S1.c.1 steps 1 and 2 are LANDED except for one Android attach-order defect found by the
  post-scaffold S1.c.0 sweep: `kc_jvm_attach` exists and calls `av_jni_set_java_vm` only on Android,
  but it must inspect `kc_init()`'s gate result before making that call. Every other build answers
  KC_JVM_UNSUPPORTED without referencing the symbol. `enum kc_jvm_status` and
  `ffkmp_packet_clone` are complete. Tests: identity suite
  grew to 18 cases (NULL and host-sentinel arms), ownership to 44 (clone metadata equality,
  independent close in both orders, NULL refusal), and the clone's falsifiability arm was RUN:
  an alias-the-input mutation crashed the ASan suite at the predicted case, then the real
  implementation went green in plain, asan, tsan and interpose. Baselines moved by ritual:
  export baseline +2 names, signature baseline 189 to 192 with the audit's count assertions
  updated at every site, klib metadata re-baselined at 1004 lines with the diff verified
  additions-plus-relocations-only and zero LOST, host-selector apiCheck green,
  KITECODEC_C_ABI_MINOR now 1. The phone-superset API dump remains S1.c.2 work.
  One test-infrastructure fix rode along: `kc_rename.h` renames `kc_jvm_attach` per doctored
  copy, keeping the production file free of test branches.
- S1.c.1 steps 4, 5 and 6 are SUBSTANTIALLY COMPLETE: `native/kitecodec-jni` exists with the
  full handle table (generation-tagged, odd-generation-live, idempotent close, live counter),
  `kj_util.c` (the two exception classes are `io.github.yuroyami.kitecodec.JniHandleException`
  and `JniNativeException`; the ffmpeg throw carries `code|context|text`), the X-macro
  registration engine over `methods.def`, `JNI_OnLoad` doing registration only (no kc_init, no
  attach, per S1.c.2 step 5's reasoning), and IMPLEMENTED category rows: abi complete
  (17 rows including the 31-field unit-separator identity report documented in kj_abi.c),
  packet complete (9 rows), and the playable core of format (9), codec (10) and frame (8).
  `kj_filter.c` is empty by design. `exports.map` (ELF) and `exports.macos` (Mach-O) exist.
  Both JNI audit scripts exist and PASS; the symbol audit reads ELF and Mach-O.
- `LinkKiteCodecJniTask` exists in buildSrc and all three arms are registered.
  `linkKiteCodecJniMacosArm64` was RUN through Gradle: BUILD SUCCESSFUL, and the audit proves
  the dylib exports exactly JNI_OnLoad. The macOS link recipe (vendored LGPL archives plus
  Homebrew SvtAv1Enc and graphite2, JDK 21 includes, -exported_symbols_list) is encoded in the
  registration after being hand-proved first.

THE EXECUTOR'S REMAINING S1.c.1 (the bounded post-scaffold remainder):

1. Correct the residual current-truth drift in `native/kitecodec-c/README.md`, the signature
   baseline header and its writer, `methods.def` and `kj_abi.c`: the declaration split is 170
   helper prototypes, eleven opaque typedefs, seven ABI prototypes, three ABI enums and the report
   typedef; exports are 170 helpers plus seven ABI names, 177 total; the C suites contain 279 cases
   per variant, including identity 18 and ownership 44, hence 837 across the three README variants.
   Preserve explicitly historical counts while correcting every current-state sentence. The
   manifest record has four X-macro fields with category supplied by its section and function
   prefix; the identity wire has 31 fields. Make Android `kc_jvm_attach` return
   `KC_JVM_FFMPEG_REFUSED` without calling FFmpeg when `kc_init()` rejects, and add a falsifiable
   Android-side gate probe. Add exact Android-arm64 and x64 configure-argument tests, including
   `--enable-pic`, `--enable-mediacodec` and `--enable-jni`.
2. Run the two Android FFmpeg producers (step 3's exact commands; machine time, not judgment)
   and the provenance/archive assertions on their outputs.
3. Decouple each Android JNI arm's opaque-helper compile provider from the Kotlin/Native target
   map, because neither the Apple selector nor S1.c.2's phone selector registers `androidNative*`.
   Dedicated arm64/x64 `CompileKiteCodecCTask` providers use the same API-24 target recipes and
   feed the link tasks directly. Make `kitecodec-sample` honor the Apple selector so the exact
   `requireAllTargets` link gate configures only its macosArm64 executable. Track `exports.macos`
   as a macOS link-task input. Then run both Android link arms and step 8's ELF assertions
   (JNI_OnLoad-only, no libav NEEDED, PT_LOAD 0x4000); adjust the Android `-l` set only if the NDK
   link demands it and record any change.
4. Run step 9's four falsifiability controls: a direct helper-bypassing FFmpeg call, a `Java_`
   export, a 4 KiB page setting and a corrupted descriptor. The descriptor arm may land with
   S1.c.2's manifest-parser test only if no earlier parser harness exists.
5. Write `LinkKiteCodecJniTaskTest` pinning both ABI recipes, both dedicated Android helper
   providers, all consumed export-control inputs and the four-field manifest schema; then write the
   S1.c.1 log entry and commits under the sub-phase's named first lines.

S1.c.2 ONWARD: phase ownership and order remain unchanged; the corrected fixed points include the
following scaffold decisions (no executor judgment needed): the bridge class is
`io/github/yuroyami/kitecodec/Internals`; the external method
names, descriptors and semantics for every implemented row are exactly methods.def's; the
identity report crosses as one \x1f-separated 31-field string whose order kj_abi.c documents;
zero jlong means "no handle" and a zero packet token to send is the drain packet; new rows for
the remaining operations (dict walk, stream and codecpar accessors, filter, sink, remux,
transcode) are ADDED to methods.def and implemented by kj_abi.c's canonical pattern, one row
per operation, in their category units.

**WINDOW 2c, THE ANNEX-B HOTFIX, owner-authorized and planner-landed 2026-08-12.** The executor
found Android playback blocked by a real Codec defect and stopped honestly rather than shipping a
contrived pass: FFmpeg's h264_mediacodec path feeds MediaCodec Annex-B where only parameter sets
and the FIRST NAL of an access unit get a 4-byte start code; every later NAL gets 3 bytes
(`count_or_copy`'s else branch, CONFIRMED by the planner at
vendor/ffmpeg/libavcodec/bsf/h264_mp4toannexb.c:66 to :68). The Goldfish API 36 decoder does not
split on 3-byte boundaries and returns zero frames; the executor measured 12/12 and 24/24
hardware frames once boundaries were widened. The fix is landed, not planned:

- A committed source patch,
  `../KiteCodec/native/patches/ffmpeg/0001-h264-mp4toannexb-always-4-byte-start-codes.patch`,
  makes the BSF emit the 4-byte form always (Android's documented MediaCodec shape, universally
  accepted, one byte per non-first NAL; count and copy share the function so sizing stays
  consistent by construction). Proved to apply cleanly from pristine n8.0 with the exact tool
  the build uses (`/usr/bin/patch -p1 --forward --fuzz=0`) and to leave zero 3-byte emission
  lines.
- `BuildFFmpegTask` gains content-tracked `sourcePatches`, applied in name order to the SCRATCH
  source copy before configure, so `vendor/ffmpeg` stays pristine at its ref; each install tree
  gains `lib/kitecodec/ffmpeg-patches.txt` naming every applied patch with its SHA-256. The
  producer registrations wire `native/patches/ffmpeg/*.patch`.
- Versions: KiteCodec `gradle.properties` VERSION is 0.0.2; `0.0.1` is never overwritten, per
  the executor's own correct instinct. Player's `kiteplayer-ffmpeg` consumes
  `kitecodec-core:0.0.2`. The Gradle PLUGIN coordinate stays 0.0.1 deliberately: that artifact
  did not change and is not republished by the phone-superset publication.
- Apple FFmpeg trees are deliberately NOT rebuilt in this window: the Apple software play path
  consumes avcC extradata directly and does not run mp4toannexb; the patch rides into Apple
  trees at their next natural rebuild. ASSUMED boundary, stated: any Apple flow that does invoke
  the BSF gets the spec-legal 4-byte widening and nothing else.

THE EXECUTOR'S REQUALIFICATION LIST (machine-heavy, in order): rerun both Android producers
with `--rerun-tasks` and assert each tree's `ffmpeg-patches.txt` names the patch; rerun both
Android link arms and the ELF assertions; publish the superset EXACTLY once as 0.0.2
(`:kitecodec-core:publishToMavenLocal -Pkitecodec.phoneTargetsOnly=true`); bump BOTH scratch
consumers' dependency lines to 0.0.2 and rerun them (Apple links offline, Android decodes in
debug and release); rerun Player's re-consume, the contract arms, the device arms and both
sample smokes; the S1.c.6 jq oracle is unchanged and ordinary MP4 playback on the named
emulator is the exit that was blocked. The 12/12 and 24/24 normalization measurements were an
experiment, not the proof; the patched-build run is the proof. One OBSERVATION for the
executor, found while gating this hotfix: on clean af061c0, `./gradlew :buildSrc:test` fails
during `:kitecodec-core` configuration with "compileSdk version is not set" under BOTH the
default and hostTargetsOnly selectors (planner-verified against the pristine tree, so it is
not this hotfix). Classify and fix it as your own S1.c.2 defect: a selector that breaks every
non-phone invocation will strand the next stage.

#### S1C-01. The JNI library is a narrow adapter over the opaque C boundary

- Where: KiteCodec's `native/kitecodec-c` headers, helper sources and audits; new
  `native/kitecodec-jni`; `BuildFFmpegTask`; a new JNI link task; the two selected Android FFmpeg
  trees.
- Problem: a normal Android or JVM application cannot consume an `androidNative*` klib. Directly
  binding libav from JNI would create a second FFmpeg surface, defeat S1.a's opaque migration and
  make D-1 unenforceable.
- Fix: add exactly two compatible C exports: `kc_jvm_attach(void *java_vm)` and
  `ffkmp_packet_clone(const kc_packet *packet)`, plus `enum kc_jvm_status`. The C ABI moves from
  2.0 to 2.1, the normalized signature baseline from 189 to 192 records and the export baseline by
  exactly those two names. Build one dynamically registered JNI adapter that includes only JNI and
  KiteCodec's opaque headers, exports only `JNI_OnLoad`, and carries no `Java_*` symbol. JNI handle
  values are generation-tagged table tokens, never native pointers.
- Test: C ownership and identity tests cover the two exports; buildSrc tests pin both ABI link
  recipes; source audit rejects any libav include or call in the JNI tree; symbol audit sees exactly
  `JNI_OnLoad`; ELF program headers are 16 KiB aligned. The controls deliberately add one direct
  `av_*` call, one `Java_*` export, one 4 KiB link setting and one descriptor mismatch, and each
  named audit must fail before the control is reverted.

#### S1C-02. KiteCodec becomes a complete JVM and Android library

- Where: KiteCodec's root and core builds, version catalog, ten common `expect` declarations, the
  low-level packet/decoder API, nine native implementation files, new shared JVM/Android actuals,
  leaf load bootstraps, shared contract tests, API dumps and AAR metadata.
- Problem: the public API is Native-only. Its player-critical `Packet`, `PacketReader`,
  `StreamDecoder` and `SeekDirection` are concrete native declarations, and a generated Android AAR
  has neither JNI libraries nor a keep rule for dynamic registration.
- Fix: add JVM and AGP 9.2.1 Kotlin Multiplatform Android targets at compileSdk 36 and minSdk 24;
  make all ten expects and all low-level playback declarations real on both through the JNI bridge;
  add O(1) `Packet.copy()` and named-decoder selection to
  `MediaSource.openDecoder(..., decoder: CodecId? = null)`; restore `@JvmInline` on
  `PixelFormat`, `SampleFormat` and `CodecId`; generate exactly arm64-v8a and x86_64 JNI inputs for
  the AAR and carry `extractNativeLibs=false` in its manifest. Each consuming APK, not the AAR ZIP,
  owns nonlegacy stored-entry packaging and 16 KiB ZIP alignment. No public operation may be an
  unsupported placeholder.
- Test: one `codecContractTest` source set drives the same full API transcript on macosArm64, JVM
  and Android device, with JVM and macOS transcripts compared byte for byte. Host tests cover handle
  misuse, typed identity rejection and dynamic registration. The AAR contains exactly two
  `libkitecodec_jni.so` entries, no arm32 entry, no loose libav shared object, valid consumer rules
  and 16 KiB ELF load segments. Compression and ZIP alignment are asserted on each final consuming
  APK, because AGP's AAR bundle is an ordinary ZIP and does not inherit APK JNI packaging policy.

#### S1C-03. The FFmpeg backend runs on Android and honours D-2

- Where: Player's `kiteplayer-ffmpeg` targets; five platform-neutral implementation files; the
  software converter; player hardware-policy KDoc; new platform decoder selection and retained-GOP
  fallback driver; shared, host and device tests.
- Problem: every backend implementation currently lives in `nativeMain`. Hardware policy is honest
  only because `Require` refuses everything. Opening MediaCodec and then failing later cannot fall
  back correctly without replaying packets from a keyframe.
- Fix: move the generic backend to commonMain; keep the zero-copy pointer converter native and add a
  JVM converter over the safe copied-plane surface. Android `Auto` and `Prefer` try FFmpeg's named
  H.264/HEVC MediaCodec decoders and fall back to software; `Off` is software; `Require` refuses
  ineligible or failed hardware. FFmpeg runs MediaCodec in buffer mode with no decoder Surface, so
  CPU YUV/NV12 frames report `HardwareWithDownload(MediaCodec)`. Retain O(1) packet clones from the
  latest accepted keyframe, bounded to 16 MiB, and on open/send/receive failure reopen software,
  replay and discard exactly the number of outputs already delivered from the retained window. A
  delivered-output ordinal, rather than PTS, handles duplicate and missing timestamps. A pending
  keyframe does not replace the old replay boundary until its decoded keyframe is observed, so
  delayed B-frames remain recoverable. A cap hit demotes proactively while the complete handover
  window is still valid.
- Test: a driver seam proves open fallback, mid-GOP send and receive fallback, no duplicate output,
  cap demotion, flush/close ownership and strict `Require`. The named emulator proves FFmpeg's
  `h264_mediacodec` selection and CPU-readable output, while controls remove the retained keyframe
  or select a platform decoder directly and fail.

#### S1C-04. Android audio uses AudioTrack through the existing pull contract

- Where: Player's `kiteplayer-output` target graph; new Android clock, AudioTrack driver/sink/factory
  and output backend; host-driver tests, device tests and the output API dump.
- Problem: Android has no output backend. `AudioTrack` is a push API while the engine and
  `KotlinAudioRing` expose the pull callback that owns the audio clock anchor.
- Fix: add `AndroidMonotonicClock`, `AudioTrackSink`, `AudioTrackSinkFactory` and
  `AndroidOutputBackend`. A dedicated priority-audio writer owns one MODE_STREAM PCM-float
  AudioTrack, calls the engine callback into a preallocated buffer, silences a short tail and loops
  short writes. It accepts mono or stereo only; mixing and resampling remain in the engine.
  `AudioTimestamp` plus a 64-bit submitted-frame counter computes the callback deadline, with an
  extended playback-head fallback. Lifecycle calls stop the writer and join it before release.
- Test: an injected driver pins format negotiation, short callback silence, partial writes,
  timestamp and wrap calculations, pause/stop/drain/close order, failed open rollback, idempotence
  and no write after release. A real AudioTrack on `Pixelu16KB` advances its playback head and closes
  cleanly. A control that releases before join makes the fake report a post-release write.

#### S1C-05. Android video presents converted frames to a caller-owned Surface

- Where: one new Android renderer and its host/device tests in `kiteplayer-output`, plus that
  module's API dump. `kiteplayer-ffmpeg` remains a dependency of the caller, never output.
- Problem: decoded Android frames have no presentation consumer, and putting
  `SoftwareConverter` inside output would reverse the module boundary and make every output
  consumer depend on FFmpeg.
- Fix: add exactly

  ```kotlin
  public class AndroidSurfaceVideoRenderer(
      surface: android.view.Surface,
      convert: (VideoFrame) -> ByteArray,
  ) : VideoRenderer
  ```

  The caller retains and releases the Surface. A newest-frame worker converts to tightly packed
  RGBA, swizzles into ARGB_8888, draws aspect-fit through `Surface.lockCanvas` and always calls
  `unlockCanvasAndPost` after a successful lock. It applies 0/90/180/270 degree rotation, closes
  every frame exactly once, bounds work to one waiting frame and reports presented, superseded and
  failed counters. It owns no SurfaceHolder, View or FFmpeg dependency.
- Test: host seams pin geometry, channel order, newest-wins, exact close counts, lock/post pairing,
  conversion failure and close during conversion. A device test draws a red/blue asymmetric frame
  through a real Surface, observes it with PixelCopy, repeats at 90 degrees, then destroys the
  surface and sees a refusal plus `SurfaceLost` without stopping audio.

#### S1C-06. A regular Android application proves the backend as assembled

- Where: a new `:kiteplayer-sample-android` application, one media-preparation buildSrc task, root
  settings and plugin catalog, generated assets and sample documentation.
- Problem: module tests do not prove Gradle variant resolution, transitive JNI packaging, R8,
  Activity/Surface lifecycle, audio, seek and teardown in one ordinary application.
- Fix: add a plain Android application at compile/target SDK 36 and minSdk 24, ABI-filtered to
  arm64-v8a and x86_64 with nonlegacy native packaging and `extractNativeLibs=false`. It uses project
  dependencies on core, FFmpeg and output, assembles `KiteCodecMediaBackend`,
  `AndroidOutputBackend` and `AndroidSurfaceVideoRenderer` in a private SurfaceView host, and embeds
  the generated sync clip. It adds no reusable view, Compose, ExoPlayer or platform media API.
- Test: debug and minified release package exactly the two aligned JNI libraries. A
  `s1c_smoke` intent on `Pixelu16KB` opens the bundled clip, plays, lands a precise five-second
  seek, reaches Ended, presents a Surface frame, advances audio, closes and atomically writes a
  bounded JSON oracle. D-1/D-2 scans reject platform demux/decode imports. Physical Android
  playback remains S1.e and is not inferred.

#### S1.c.0 Mechanical expansion sweep

Files: read-only across every file named in S1.c.1 to S1.c.6; both Gradle target graphs; the
window-2a Maven-local module metadata and scratch consumer; Android SDK, NDK, AVD and device
inventories. Product files do not move in this sub-phase.

Steps.
1. Start only after S1.b's five product commits, clean exits and log entries exist. Record the two
   resulting heads. Verify every path, declaration, target, task, profile flag, count, artifact
   path, device fact, command and expected outcome in this expansion against those heads and the
   machine. Run the whole sweep before reporting.
2. Recount common expects, native implementation files and lines, normalized C declarations,
   exports, selected FFmpeg configure flags, installed SDK/NDK versions, AVD ABIs/page size and
   attached devices. Confirm `gradle.properties` remains unread and untouched; the coordinate still
   comes from the root `allprojects` block.
3. Ask Gradle for the authoritative task names under JDK 21 and confirm the existing names plus the
   names this expansion will register. Confirm AGP 9.2.1 is already available through Player and is
   the exact version added to Codec. Confirm the S1.b Apple scratch consumer can still resolve the
   current window-2a publication offline before window 2b starts.
4. Classify a mismatch BLOCKING when it changes a file fence, symbol, API, command, expected result,
   publication order, gate or commit first line. Classify it DESCRIPTIVE only when every action
   remains unchanged. Report one consolidated sweep. Under the owner's S1 correction exception, a
   BLOCKING mechanical contradiction with one conservative tree-backed correction gets its own
   KPKMP-only Tier 1 commit, then this entire sweep reruns. Stop for an irreversible, scope-expanding
   or product-policy choice.
5. Record the external classification exactly: S1.b not landed is BLOCKING at authorship; absent
   Android outputs, the explicit NDK environment, no x86_64 emulator and no physical device are
   DESCRIPTIVE for S1.c. The physical-device absence is carried forward as an S1.e blocker.

Gate. Tier 1, because S1.c.0 changes KPKMP only. A clean consolidated report authorises S1.c.1.

Commit first line. KitePlayer: `Verify the Android phone stage against the landed iOS substrate`.

#### S1.c.1 Build the opaque JNI and Android native substrate

Files, KiteCodec: `native/kitecodec-c/include/kitecodec_abi.h` and
`kitecodec_helpers.h`; `native/kitecodec-c/src/kitecodec_abi.c` and
`helpers_packet.c`; `native/kitecodec-c/tests/test_identity.c` and
`test_ownership.c`; `native/kitecodec-c/signature-baseline.txt` and
`exported-symbols-baseline.txt`; `native/kitecodec-c/scripts/symbol-audit.sh`;
`native/kitecodec-c/README.md`;
`buildSrc/src/main/kotlin/BuildFFmpegTask.kt`; new
`buildSrc/src/main/kotlin/LinkKiteCodecJniTask.kt`; `kitecodec-core/build.gradle.kts`;
`kitecodec-sample/build.gradle.kts`;
`buildSrc/src/test/kotlin/BuildFFmpegTaskTest.kt` and new
`LinkKiteCodecJniTaskTest.kt`. New `native/kitecodec-jni` files are exactly
`methods.def`, `exports.map`, `exports.macos`, `kj_internal.h`, `kj_handles.c`, `kj_util.c`,
`kj_registration.c`, `kj_abi.c`, `kj_format.c`, `kj_packet.c`, `kj_codec.c`,
`kj_frame.c`, `kj_filter.c`, `scripts/source-discipline.sh`,
`scripts/symbol-audit.sh` and `README.md`. Generated JNI libraries and Android FFmpeg trees are
evidence, not committed files. The landed scaffold fence also includes
`native/kitecodec-c/tests/fake_headers/kc_rename.h` and
`native/kitecodec-c/klib-metadata-baseline.txt`. Player: KPKMP execution log only.

Steps.
1. Add this stable C surface, with the enum in `kitecodec_abi.h`, the attach implementation in
   `kitecodec_abi.c` and the clone in the packet helper pair:

   ```c
   enum kc_jvm_status {
       KC_JVM_OK = 0,
       KC_JVM_BAD_ARGUMENT = -1,
       KC_JVM_UNSUPPORTED = -2,
       KC_JVM_FFMPEG_REFUSED = -3
   };

   KC_API int kc_jvm_attach(void *java_vm);
   KC_API kc_packet *ffkmp_packet_clone(const kc_packet *packet);
   ```

   `kc_jvm_attach(NULL)` returns `KC_JVM_BAD_ARGUMENT`. On Android it calls `kc_init()` first,
   returns `KC_JVM_FFMPEG_REFUSED` without touching FFmpeg when the identity gate rejects, and only
   then calls `av_jni_set_java_vm` inside the C archive, returning `KC_JVM_OK` or
   `KC_JVM_FFMPEG_REFUSED`. On every non-Android build it returns `KC_JVM_UNSUPPORTED` without
   referencing the FFmpeg JNI symbol. `ffkmp_packet_clone` rejects null, allocates one packet and
   uses `av_packet_ref`; allocation or ref failure frees everything and returns null. It is O(1)
   over the compressed payload. Raise `KITECODEC_C_ABI_MINOR` from 0 to 1, never the major.
2. Write the failing C assertions first. Host identity expects null to be bad-argument and a
   non-null sentinel to be unsupported. Ownership expects clone metadata equality, independent
   close in both orders and an allocation-accounting balance. Temporarily return the input packet
   from the clone and watch the second close arm fail under ASan before implementing it. Regenerate
   the signature baseline only after reviewing its three additions: the enum and two prototypes.
   Regenerate the export baseline only after reviewing its two added names. The final counts are
   192 normalized declaration records and the old export set plus exactly `kc_jvm_attach` and
   `ffkmp_packet_clone`. Update `symbol-audit.sh`'s count assertion, write refusal, baseline prose
   and success text from 189 to 192 in this same commit. Correct every current README/baseline
   category sentence to the measured final split: 170 helper prototypes, eleven opaque typedefs,
   seven ABI prototypes, three ABI enums and the report typedef. Add an Android-side gate probe
   that forces identity rejection and proves `av_jni_set_java_vm` was not called before restoring
   the accepting control.
3. Preserve the existing `--enable-pic` from `sharedCoreArgs()` in both Android FFmpeg argument
   sets and pin its inclusion in exact Android-arm64 and x64 argument tests. Preserve API 24, static-only
   LGPL, zlib, the current software formats and
   `--enable-mediacodec --enable-jni`. Do not build arm32. Producer and JNI-link invocations remain
   separate because target trees are resolved during configuration:

   ```bash
   export ANDROID_SDK_ROOT=/Users/macbook/WORKSTATION/AndroidSDK
   export ANDROID_NDK_HOME=/Users/macbook/WORKSTATION/AndroidSDK/ndk/29.0.14206865
   cd ../KiteCodec
   ./gradlew :kitecodec-core:buildFFmpegForAndroidArm64 \
     :kitecodec-core:buildFFmpegForAndroidX64 --rerun-tasks
   ```

   Assert each output has six archives and the public headers. The transactional install tree does
   not contain build-root `config.h`, so prove the feature selection twice: the full-list
   `BuildFFmpegTaskTest` expectation contains `--enable-pic`, `--enable-mediacodec` and
   `--enable-jni`, and each
   installed tree's S1.b provenance file `lib/kitecodec/ffmpeg-configure.txt` contains both exact
   options. `AndroidArm32` must have no generated output.
4. Make `methods.def` the single registration manifest. Each non-comment X-macro record contains
   exactly the binary class name, Kotlin external-method name, JVM descriptor and C function. Its
   section and function prefix supply the category and handle/result convention; there is no fifth
   field. `kj_registration.c` includes the manifest to create the exact `JNINativeMethod`
   tables. No hand-copied second list is allowed. `JNI_OnLoad` gets `JNIEnv`, finds the declared
   bridge classes and calls `RegisterNatives`, then returns `JNI_VERSION_1_6`. It does not call
   `kc_init`, does not call `kc_jvm_attach` and does not convert an identity rejection into an
   uninspectable library-load failure.
5. Pass every C object through a generation-tagged handle table in `kj_handles.c`. A `jlong`
   encodes a slot and generation, never a pointer. Every lookup checks nonzero, live generation and
   the exact one of the eleven opaque handle kinds before returning a pointer; close invalidates
   once and is idempotent. Stale, zero and wrong-kind tokens throw a typed JVM exception before a
   helper call. JNI string, array and exception conversion lives only in `kj_util.c`. The category
   source files call `kc_*` and `ffkmp_*` only. They may call JNI and the C runtime, and may not
   include a libav header, spell an `av_*` call or reproduce an FFmpeg struct.
6. `LinkKiteCodecJniTask` compiles the opaque C archive and JNI sources, then links one shared
   library. It registers exactly:

   - `:kitecodec-core:linkKiteCodecJniMacosArm64` to
     `kitecodec-core/build/kitecodec-jni/macos-arm64/libkitecodec_jni.dylib`
   - `:kitecodec-core:linkKiteCodecJniAndroidArm64` to
     `kitecodec-core/build/kitecodec-jni/android-arm64/arm64-v8a/libkitecodec_jni.so`
   - `:kitecodec-core:linkKiteCodecJniAndroidX64` to
     `kitecodec-core/build/kitecodec-jni/android-x64/x86_64/libkitecodec_jni.so`

   The Android helper archives come from dedicated arm64/x64 `CompileKiteCodecCTask` providers,
   not from Kotlin/Native target registration. This is required because the ordinary Android KMP
   library and application consume JNI and deliberately never register `androidNative*` klibs.
   The two Android arms use NDK r29 clang, `-shared -fPIC -fvisibility=hidden` and
   `-Wl,-z,defs -Wl,-z,noexecstack -Wl,-z,relro -Wl,-z,now -Wl,--gc-sections`,
   `-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384`,
   `-Wl,--exclude-libs,ALL` and `--version-script=exports.map`. Link the opaque helper archive and
   six FFmpeg static archives with `mediandk`, `android`, `log`, `z`, `dl` and `m`. The output has
   no dependency on a `libav*.so`. The macOS dylib is test-only and may dynamically link the S1.b
   Local macOS FFmpeg tree; S5 owns desktop runtime distribution. Track both `exports.map` and
   `exports.macos` as task inputs, and pin the target recipes, helper providers and export-control
   inputs in `LinkKiteCodecJniTaskTest`. Make `kitecodec-sample` honor the existing Apple selector
   so this sub-phase's `requireAllTargets` gate configures only macosArm64 on this machine.
7. Run the link tasks in one consumer invocation after the FFmpeg producer finishes:

   ```bash
   export ANDROID_SDK_ROOT=/Users/macbook/WORKSTATION/AndroidSDK
   export ANDROID_NDK_HOME=/Users/macbook/WORKSTATION/AndroidSDK/ndk/29.0.14206865
   cd '/Users/macbook/StudioProjects/#Kite/KiteCodec'
   ./gradlew :buildSrc:test \
     :kitecodec-core:linkKiteCodecJniMacosArm64 \
     :kitecodec-core:linkKiteCodecJniAndroidArm64 \
     :kitecodec-core:linkKiteCodecJniAndroidX64 \
     -Pkitecodec.applePhoneTargetsOnly=true \
     -Pkitecodec.requireAllTargets=true --rerun-tasks
   ```

   Then run `native/kitecodec-jni/scripts/source-discipline.sh` and
   `native/kitecodec-jni/scripts/symbol-audit.sh` on all three outputs. The source audit prints zero
   forbidden includes/calls. Dynamic defined-symbol output contains exactly `JNI_OnLoad` after
   normal platform decoration, never `Java_*`, `kc_*`, `ffkmp_*` or `av_*`.
8. Assert both Android ELF arms rather than sampling one:

   ```bash
   export ANDROID_NDK_HOME=/Users/macbook/WORKSTATION/AndroidSDK/ndk/29.0.14206865
   cd '/Users/macbook/StudioProjects/#Kite/KiteCodec'
   S1C_READELF="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-readelf"
   S1C_NM="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-nm"
   for so in \
     kitecodec-core/build/kitecodec-jni/android-arm64/arm64-v8a/libkitecodec_jni.so \
     kitecodec-core/build/kitecodec-jni/android-x64/x86_64/libkitecodec_jni.so; do
     test "$("$S1C_NM" -D --defined-only "$so" | awk '{print $3}')" = JNI_OnLoad
     ! "$S1C_NM" -D "$so" | grep -E 'Java_| (kc_|ffkmp_|av_)'
     S1C_LOAD_ALIGN="$("$S1C_READELF" -lW "$so" | \
       awk '$1 == "LOAD" {print $NF}' | sort -u)"
     test "$S1C_LOAD_ALIGN" = 0x4000
     ! "$S1C_READELF" -dW "$so" | grep -E 'NEEDED.*lib(av|sw)'
   done
   ```

   The assertion sees at least one PT_LOAD line because an empty value differs from `0x4000`, and
   every PT_LOAD segment must carry exactly that 16 KiB alignment.
9. Run four falsifiability arms separately and restore after each: introduce a direct
   `avcodec_version()` call and see source discipline fail; export a `Java_fake` symbol and see
   the symbol audit fail; set max/common page size to 4096 and see the ELF assertion fail; corrupt
   one `methods.def` descriptor and see the manifest parser test fail. A control that does not fail
   is BLOCKING.

Gate. Tier 2, selected by native C, buildSrc and build-script changes and by completion of a Horizon
sub-phase. Run both C sanitizer arms, interpose, corpus replay, C export/signature/metadata audits,
buildSrc tests and the three JNI audits. The optional host-only Maven publication in Tier 2 is
omitted here because window 2b must publish exactly once after S1.c.2. Run Player's Tier 2 and both
Tier 1 blocks after the execution-log entry. Close with `git diff --check` and prove no product
file outside the fence changed.

Commit first line. KiteCodec: `Build the Android JNI bridge on the opaque boundary`.
KitePlayer: `Record the Android JNI boundary proof`.

#### S1.c.2 Make every KiteCodec operation real on JVM and Android, then close window 2b

Files, KiteCodec: `gradle/libs.versions.toml`; root `build.gradle.kts`;
`kitecodec-core/build.gradle.kts`; `kitecodec-sample/build.gradle.kts`;
`buildSrc/src/main/kotlin/LinkKiteCodecJniTask.kt`;
`native/kitecodec-c/include/kitecodec_abi.h` and `kitecodec_helpers.h`;
`native/kitecodec-c/src/helpers_codec.c`;
`native/kitecodec-c/tests/test_args.c`;
`native/kitecodec-c/signature-baseline.txt`, `exported-symbols-baseline.txt`,
`klib-metadata-baseline.txt`, `scripts/symbol-audit.sh` and
`README.md`;
`kitecodec-core/src/commonMain/.../FFmpeg.kt`,
`FilterGraph.kt`, `Frame.kt`, `MediaSink.kt`, `MediaSource.kt`, `Remuxer.kt`,
`Transcoder.kt`, `MediaType.kt` and `LowLevelApi.kt`; new common `Playback.kt`;
all nine existing `kitecodec-core/src/nativeMain` implementation files. New
`kitecodec-core/src/jvmAndAndroidMain/kotlin/io/github/yuroyami/kitecodec` files are
`Internals.jvm.kt`, `FFmpeg.jvm.kt`, `FilterGraph.jvm.kt`, `Frame.jvm.kt`,
`MediaSink.jvm.kt`, `MediaSource.jvm.kt`, `Playback.jvm.kt`, `Remuxer.jvm.kt` and
`Transcoder.jvm.kt`. New leaf files are
`jvmMain/.../JniLibrary.jvm.kt` and `androidMain/.../JniLibrary.android.kt`. Android packaging
files are `src/androidMain/AndroidManifest.xml` and `consumer-rules.pro`.

Test files are new under
`src/codecContractTest/kotlin/io/github/yuroyami/kitecodec/`: `CodecContractTest.kt`,
`CodecContractTranscript.kt`, `ContractMedia.kt` and `ContractMediaMaterializer.kt`; new under
`src/jvmTest/kotlin/io/github/yuroyami/kitecodec/`: `JniBoundaryTest.kt`, `JniIdentityTest.kt` and
`ContractMediaMaterializer.jvm.kt`; new
`src/macosArm64Test/kotlin/io/github/yuroyami/kitecodec/ContractMediaMaterializer.macos.kt`; new
`src/androidHostTest/kotlin/io/github/yuroyami/kitecodec/JniPackagingModelTest.kt`; new under
`src/androidDeviceTest/kotlin/io/github/yuroyami/kitecodec/`: `CodecAndroidDeviceTest.kt` and
`ContractMediaMaterializer.android.kt`; and `src/androidDeviceTest/AndroidManifest.xml`. Build
support adds
`buildSrc/src/main/kotlin/CompareCodecContractTask.kt` and
`buildSrc/src/test/kotlin/CompareCodecContractTaskTest.kt`; existing
`buildSrc/src/test/kotlin/LinkKiteCodecJniTaskTest.kt`. Comment-only source-boundary truth edits
also fence `buildSrc/src/main/kotlin/BuildFFmpegTask.kt` and
`kitecodec-core/src/nativeInterop/cinterop/ffmpeg.def`: their existing Android-link comments spell
the same direct platform C token that the gate below must reject everywhere in Kotlin/native
sources. Existing JNI files in this fence are
`native/kitecodec-jni/methods.def`, `kj_internal.h`, `kj_handles.c`, `kj_util.c`, `kj_abi.c`, `kj_format.c`,
`kj_packet.c`, `kj_codec.c`, `kj_frame.c`, `kj_filter.c` and `README.md`. Also in the fence:
`kitecodec-core/api/`; root `README.md` and `CHANGELOG.md`; `kitecodec-core/Module.md`;
`docs/about.md`, `docs/index.md`, `docs/decoding.md`, `docs/transcoding.md`,
`docs/gradle-plugin.md`, `docs/platforms.md`, `docs/getting-started.md` and
`docs/troubleshooting.md`; and both JNI audits from S1.c.1. Remove every live "Kotlin/Native
only", "no JVM", "no JNI", "no Android AAR" and "upcoming Android substrate" claim from those
named documents without rewriting historical evidence. The S1.b Apple scratch consumer and the
new Android scratch consumer are ignored evidence only. Player: KPKMP execution log only.

Steps.
1. Add AGP 9.2.1 and `com.android.kotlin.multiplatform.library` to Codec's catalog and root with
   `apply(false)`. Add AndroidX test core/runner 1.7.0 and ext-junit 1.3.0 for device tests. Keep
   `jvmToolchain(21)`. Remove the AtomicFU Gradle plugin application from `kitecodec-core` before
   registering Android: AtomicFU 0.31's Android KMP transform looks for the nonexistent
   `androidMainClasses` task under AGP 9.2.1. Retain the `kotlinx-atomicfu` runtime dependency and
   its existing common code. In core register `jvm()` and an Android KMP library named `android` with
   namespace `io.github.yuroyami.kitecodec`, compileSdk 36, minSdk 24, host tests and device tests
   using `androidx.test.runner.AndroidJUnitRunner`. Create the device compilation with
   `withDeviceTestBuilder { sourceSetTreeName = "test" }` and configure the returned device test;
   the default device-test tree cannot depend directly on `codecContractTest` and emits a hierarchy
   warning before compilation. Do not add `androidNative*` to the S1.c
   selector: those klibs are a different platform and cannot satisfy a normal Android app.
2. Add `-Pkitecodec.phoneTargetsOnly=true`. It is mutually exclusive with
   `stableTargetsOnly`, `hostTargetsOnly` and `applePhoneTargetsOnly` and registers exactly
   macosArm64, iosArm64, iosSimulatorArm64, jvm and the Android JVM target on this machine. It is
   accepted only by Maven-local publication; every remote publish rejects it during configuration.
   It requires all three S1.b Local FFmpeg trees and both S1.c Android trees. Preserve the old
   selectors and their exact behavior. Extend `kitecodec-sample`'s selector handling: on this
   arm64 Mac the Apple and phone scopes register only the sample's macosArm64 executable. Thus root
   invocations that combine the phone selector with `kitecodec.requireAllTargets=true` do not
   configure unrelated macosX64, Linux or MinGW sample targets.
3. Restore `@JvmInline` on `PixelFormat`, `SampleFormat` and `CodecId`, as the existing source
   comment already directs when a JVM target arrives. Move `Packet`, `PacketReader`,
   `StreamDecoder` and `SeekDirection` declarations into common `Playback.kt`, make the native
   file their actual implementation without behavior drift, and add the two members shown below.
   This block is deliberately an excerpt, not a replacement class declaration:

   ```kotlin
   @KiteCodecLowLevelApi
   public expect class Packet : AutoCloseable {
       @KiteCodecLowLevelApi
       public fun copy(): Packet
   }

   public expect class MediaSource : AutoCloseable {
       @KiteCodecLowLevelApi
       public fun openDecoder(
           stream: StreamInfo,
           threadCount: Int = 0,
           lowDelay: Boolean = false,
           decoder: CodecId? = null,
       ): StreamDecoder
   }
   ```

   Preserve the complete current low-level surface and its opt-in annotations: `Packet.timeBase`,
   `streamIndex`, `pts`, `dts`, `duration`, `isKeyframe`, `sizeBytes`, `bytePosition`, `hasPts`,
   `ptsMicros`, `dtsMicros`, `durationMicros` and `close`; `SeekDirection.Backward`, `Forward` and
   `Any`; `PacketReader.read`, `seek(micros, direction = Backward, notEarlierThan = null)` and
   `close`; `StreamDecoder.stream`, read-only externally `isDrained`, `send`, `receive`, `flush`
   and `close`; and `MediaSource.openPacketReader(streams)` plus the extended `openDecoder` above.
   Keep `@KiteCodecLowLevelApi` on the four low-level declarations and both MediaSource entry
   points, keep every default, and preserve all existing ownership/use-after-close KDoc. The copy
   is an owned O(1) packet ref through `ffkmp_packet_clone`. A non-null decoder selects that exact
   FFmpeg decoder by name and verifies it can decode the stream codec before open; null preserves
   the existing by-id default. Close, use-after-close, seek and drain behavior remains identical
   on native and JVM.

   The existing opaque helper surface does not expose a selected `kc_codec`'s codec id, so add the
   compatible helper `int ffkmp_codec_id(const kc_codec *codec)` in the helper header/source named
   by this corrected fence. The named-decoder path compares that value with
   `ffkmp_codecpar_codec_id` before allocating or opening the decoder. Add null and selected-codec
   argument assertions. This compatible public C addition moves `KITECODEC_C_ABI_MINOR` from 1 to
   2; then move every affected signature, export and cinterop-metadata ratchet by its
   section 9 procedure and correct the C README's current counts and ABI truth. Record every exact
   measured delta. The fixed expected counts are 171 helper prototypes plus seven ABI exports =
   178 exports, 193 normalized declarations, `test_args` 23 and 280 C cases per variant, hence 840
   across plain/ASan/TSan. The one new table case contains both the null-to-zero and selected
   `pcm_s16le` codec-id/name assertions. Letting decoder open discover an incompatible named codec
   is not the required pre-open verification.

   `coupling-baseline.txt` remains byte-identical at its two zero ceilings;
   `checkCinteropCoupling` records the measured `ffkmp_call_sites` increase as reported-only
   traffic.

   Route the same check through the private JNI row `nativeCodecId(J)I`: `methods.def` registers
   it, `kj_codec.c` resolves the exact codec handle kind and calls `ffkmp_codec_id`, and
   `Internals.jvm.kt` declares it. Native compares before `ffkmp_codecctx_alloc`; JVM/Android
   compares before `nativeCodecCtxAlloc`. A temporary always-zero helper mutation must make the
   selected-`pcm_s16le` test fail before restoring the real accessor. Invalid JNI tokens remain
   typed handle-table errors; only the C helper's direct null contract returns zero.
4. Implement all ten common expects and the four low-level declarations in the shared
   JVM/Android source set. `Internals.jvm.kt` owns only private external methods matching
   `methods.def`, typed handle wrappers, identity-report conversion and error mapping. Each public
   owner has one nonzero token, checks open before every operation and closes idempotently. JNI
   errors become the same `FFmpegError` and `FFmpegException` subclasses native uses. Borrowed
   stream and static-codec tokens have explicit release semantics; stream tokens record their
   format parent and are invalidated when that parent closes, while codec tokens are released when
   their wrapper is finished. Parent close, explicit borrowed release and repeated release each
   decrement the live-handle count exactly once. A stale stream token must never resolve to a
   freed FFmpeg pointer. Lambda
   callbacks receive an owned or callback-scoped object under the existing KDoc, never a raw token.
   Arrays and strings are copied at the declared API boundary; no direct ByteBuffer or native
   pointer becomes public. Extend `kj_util.c` with the Java-byte-array-to-owned-C-bytes helper used
   by `Frame.ofVideo` and `Frame.ofAudio`; `kj_frame.c` consumes that helper rather than becoming a
   second JNI array-conversion unit.
5. Leaf loading is explicit. JVM reads a test-only absolute library override
   `kitecodec.jni.path` before falling back to `System.loadLibrary("kitecodec_jni")`; the dylib is
   not added to the published JVM jar. Android always uses `System.loadLibrary`. The private
   `attachCurrentVm` native method obtains the VM with `GetJavaVM` and passes it to
   `kc_jvm_attach`, but is not invoked yet. Immediately after load, call `kc_init` and copy/map the
   full identity report first, throwing
   `FFmpegError.IncompatibleFFmpegRuntime` with the same typed fields as native on rejection. Only
   an accepted identity proceeds to VM attach: Android requires `KC_JVM_OK`; JVM accepts
   `KC_JVM_UNSUPPORTED`. Attach redundantly observes the once-only gate, which keeps direct C
   callers safe without making Kotlin's typed report unreachable. This is why `JNI_OnLoad` itself
   did not run the gate.
   Wire `jvmTest` to `linkKiteCodecJniMacosArm64` with a `TaskProvider`, and set the
   `kitecodec.jni.path` test system property from that task's output-file provider. No test command
   relies on a caller exporting the property or on a stale dylib.
6. Keep `codecContractTest` out of `commonTest` and attach it directly to macosArm64Test, jvmTest
   and androidDeviceTest. Android host tests use no native library and test only pure handle,
   descriptor and packaging models. The contract fixture is a bounded byte fixture in
   `ContractMedia.kt`; `ContractMediaMaterializer.kt` declares an internal expect function that
   accepts those bytes and their committed SHA-256 and returns a path only after materializing and
   verifying them. Its JVM actual uses `Files.createTempFile`, its macosArm64 actual uses a private
   `mkstemp` file, and its Android device actual uses the instrumentation target context's
   `cacheDir`. Each registers cleanup in the test owner. This gives the path-only
   `MediaSource.open` API a real private file without a cross-repository test dependency. The
   contract transcript covers:

   - FFmpeg identity, capability lookup and error typing
   - MediaSource metadata, stream selection, packet read/copy/close, seek and exact decoder choice
   - frame info, plane copy, frame copy, raw video/audio construction and image encode
   - decoder send/receive/drain/flush and wrong-state guards
   - video and audio filter construction, feed, flush and output ownership
   - MediaSink video/audio/copy streams, metadata, header/write/trailer and rollback
   - Remuxer and Transcoder success, refusal and cancellation cleanup

   Each arm writes stable scalar values and hashes, never pointer values or platform paths.
   `compareJvmNativeContract` fails on the first byte difference between the JVM and macosArm64
   transcripts. Android device runs the same assertions and additionally requires VM attachment.
7. Give each S1.c.1 Android link task an annotated `DirectoryProperty` output rooted one level
   above its ABI directory: the arm task outputs `.../android-arm64/`, containing
   `arm64-v8a/libkitecodec_jni.so`, and the x64 task outputs `.../android-x64/`, containing
   `x86_64/libkitecodec_jni.so`. Configure `androidComponents.onVariants` so each variant's
   `checkNotNull(variant.sources.jniLibs).addGeneratedSourceDirectory(
   taskProvider, LinkKiteCodecJniTask::outputDirectory)` consumes
   both exact task providers. Passing either leaf ABI directory is forbidden because it would
   package the library under the wrong path. Nothing is copied into `src/androidMain/jniLibs`.
   The library manifest carries `android:extractNativeLibs="false"`; APK-side nonlegacy packaging
   is owned by each consuming application, not inferred from the AAR ZIP. AGP 9.2.1 KMP does not
   publish consumer rules by default, so configure
   `optimization { consumerKeepRules.apply { publish = true; file("consumer-rules.pro") } }`
   explicitly.
   That file keeps the internal bridge class and all registered native method names/descriptors
   while allowing the public Kotlin API to shrink normally. The generated AAR contains only
   `arm64-v8a/libkitecodec_jni.so` and `x86_64/libkitecodec_jni.so`, and its `proguard.txt` contains
   the pinned native-method rule from this exact input.
8. Write reproduction-first tests. Before actuals, `compileKotlinJvm` fails on missing actuals.
   Before JNI packaging, the device test fails with `UnsatisfiedLinkError`. A zero, stale and
   wrong-kind token must each fail at the JNI table rather than crash. Tests also pin explicit
   borrowed-codec release, parent-driven stream invalidation, repeated release, and a zero
   live-handle ledger after every contract arm. Load a host test library
   built against the existing mismatched fake identity headers and require a typed
   `IncompatibleFFmpegRuntime` containing all six libraries and provisioning text. Change one
   `methods.def` descriptor while leaving Kotlin intact and require library load to fail; restore
   it and require all registrations to succeed.
9. Build and test under the superset selector:

   ```bash
   export ANDROID_SDK_ROOT=/Users/macbook/WORKSTATION/AndroidSDK
   export ANDROID_NDK_HOME=/Users/macbook/WORKSTATION/AndroidSDK/ndk/29.0.14206865
   cd ../KiteCodec
   ./gradlew :buildSrc:test \
     :kitecodec-core:compileKotlinJvm \
     :kitecodec-core:jvmTest \
     :kitecodec-core:compileAndroidMain \
     :kitecodec-core:testAndroidHostTest \
     :kitecodec-core:assembleAndroidMain \
     :kitecodec-core:bundleAndroidMainAar \
     :kitecodec-core:macosArm64Test \
     :kitecodec-core:compareJvmNativeContract \
     -Pkitecodec.phoneTargetsOnly=true \
     -Pkitecodec.requireAllTargets=true --rerun-tasks
   ```

   Start the one named emulator at a fixed serial and keep it running through S1.c.6:

   ```bash
   adb -s emulator-5554 emu kill >/dev/null 2>&1 || :
   S1C_STOP_TRIES=0
   while adb devices | awk '$1 == "emulator-5554" {found=1} END {exit !found}'; do
     S1C_STOP_TRIES=$((S1C_STOP_TRIES + 1))
     if [ "$S1C_STOP_TRIES" -ge 60 ]; then
       echo "old emulator-5554 did not disappear within 60 seconds" >&2
       exit 1
     fi
     sleep 1
   done
   "$ANDROID_SDK_ROOT/emulator/emulator" \
     -avd Pixelu16KB -port 5554 \
     -no-snapshot-load -no-snapshot-save -no-boot-anim \
     > /private/tmp/s1c-Pixelu16KB.log 2>&1 &
   S1C_DEVICE_TRIES=0
   until adb devices | awk '$1 == "emulator-5554" && $2 == "device" {found=1} END {exit !found}'; do
     S1C_DEVICE_TRIES=$((S1C_DEVICE_TRIES + 1))
     if [ "$S1C_DEVICE_TRIES" -ge 180 ]; then
       echo "emulator-5554 did not become an adb device within 180 seconds" >&2
       exit 1
     fi
     sleep 1
   done
   S1C_BOOT_TRIES=0
   until [ "$(adb -s emulator-5554 shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; do
     S1C_BOOT_TRIES=$((S1C_BOOT_TRIES + 1))
     if [ "$S1C_BOOT_TRIES" -ge 180 ]; then
       echo "Pixelu16KB did not complete boot within 180 seconds" >&2
       exit 1
     fi
     sleep 1
   done
   test "$(adb -s emulator-5554 shell getprop ro.boot.qemu.avd_name | tr -d '\r')" = Pixelu16KB
   test "$(adb -s emulator-5554 shell getconf PAGE_SIZE | tr -d '\r')" = 16384

   ANDROID_SERIAL=emulator-5554 ./gradlew \
     :kitecodec-core:connectedAndroidDeviceTest \
     -Pkitecodec.phoneTargetsOnly=true \
     -Pkitecodec.requireAllTargets=true --rerun-tasks
   ```

   No x86_64 runtime result is written. Its link and package assertions are its evidence.
10. Update the API dumps deliberately:

    ```bash
    ./gradlew :kitecodec-core:apiDump \
      -Pkitecodec.phoneTargetsOnly=true \
      -Pkitecodec.requireAllTargets=true
    ./gradlew :kitecodec-core:apiCheck \
      -Pkitecodec.phoneTargetsOnly=true \
      -Pkitecodec.requireAllTargets=true
    ```

    Review the KLIB additions `Packet.copy` and the optional named decoder parameter, the common
    location of the low-level types, and the newly installed JVM dump. The JNI bridge remains
    absent because it is internal. Record every declaration in the ratchet log.
11. Inspect `kitecodec-core/build/outputs/aar/kitecodec-core.aar` exactly:

    ```bash
    S1C_AAR=kitecodec-core/build/outputs/aar/kitecodec-core.aar
    test -f "$S1C_AAR"
    test "$(zipinfo -1 "$S1C_AAR" | \
      awk '/^jni\/.*[.]so$/ {count++} END {print count + 0}')" = 2
    zipinfo -1 "$S1C_AAR" | grep -Fx 'jni/arm64-v8a/libkitecodec_jni.so'
    zipinfo -1 "$S1C_AAR" | grep -Fx 'jni/x86_64/libkitecodec_jni.so'
    ! zipinfo -1 "$S1C_AAR" | grep -E 'armeabi|arm32|lib(av|sw).*[.]so'
    unzip -p "$S1C_AAR" AndroidManifest.xml | \
      grep -F 'android:extractNativeLibs="false"'
    unzip -p "$S1C_AAR" proguard.txt | grep -F 'native <methods>'
    ```

    Extract both SOs to a temporary directory and rerun S1.c.1's symbol, dependency and PT_LOAD
    assertions on the packaged bytes. Do not assert AAR entry compression or run `zipalign` on the
    AAR: AGP 9.2.1's `BundleAar` is an ordinary Gradle ZIP and APK JNI packaging policy does not
    govern it. The `0x1000` control must still fail the packaged ELF arm. Stored-entry and ZIP
    alignment controls run against the final scratch/sample APKs that actually load the library.
12. Prove remote refusal, then publish once:

    ```bash
    ./gradlew :kitecodec-core:publish \
      -Pkitecodec.phoneTargetsOnly=true \
      -Pkitecodec.requireAllTargets=true
    ```

    Expect nonzero during configuration with the explicit local-only selector refusal. Then run
    exactly one window-2b publication:

    ```bash
    ./gradlew :kitecodec-core:publishToMavenLocal \
      -Pkitecodec.phoneTargetsOnly=true \
      -Pkitecodec.requireAllTargets=true
    ```

    Do not run host-only, Apple-only or Android-only publication afterwards.
13. Re-consume in preservation order. The cross-shell paths are fixed, never implicit shell state:
    `S1B_CODEC_SMOKE=/private/tmp/kitecodec-s1b-phone-consumer` and
    `S1C_CODEC_SMOKE=/private/tmp/kitecodec-s1c-android-consumer`. The first directory contains the
    exact settings, build and common source files installed by S1.b.1; if it is absent, reconstruct
    those named files from S1.b.1 before continuing. Set the variable and rerun its three framework
    links with the recorded absolute Local FFmpeg root:

    ```bash
    cd '/Users/macbook/StudioProjects/#Kite/KiteCodec'
    S1B_CODEC_SMOKE=/private/tmp/kitecodec-s1b-phone-consumer
    S1C_AAR="$PWD/kitecodec-core/build/outputs/aar/kitecodec-core.aar"
    test -f "$S1C_AAR"
    test -f "$S1B_CODEC_SMOKE/settings.gradle.kts"
    test -f "$S1B_CODEC_SMOKE/build.gradle.kts"
    test -f "$S1B_CODEC_SMOKE/src/commonMain/kotlin/Smoke.kt"
    ./gradlew -p "$S1B_CODEC_SMOKE" \
      linkDebugFrameworkMacosArm64 \
      linkDebugFrameworkIosArm64 \
      linkDebugFrameworkIosSimulatorArm64 \
      --offline --refresh-dependencies --rerun-tasks
    ```

    Then create the Android consumer at the fixed second path with exactly these files:
    `settings.gradle.kts`, root `build.gradle.kts`, `app/build.gradle.kts`,
    `app/proguard-rules.pro`, `app/src/main/AndroidManifest.xml`,
    `app/src/main/kotlin/io/github/yuroyami/kitecodec/smoke/MainActivity.kt` and
    `ContractMedia.kt`. Settings give plugin management `google()`, `mavenLocal()`, the plugin
    portal and Maven Central, dependency resolution `mavenLocal()`, `google()` and Maven Central,
    and include only `:app`. The root declares Android application 9.2.1 and Kotlin Android 2.4.10
    with `apply false`; the latter selects Kotlin 2.4.10 on the shared plugin classpath without
    applying the incompatible plugin. AGP 9.2.1 owns the app's Kotlin compilation through
    built-in Kotlin, so the app does not apply `org.jetbrains.kotlin.android`. The app uses
    namespace/application id
    `io.github.yuroyami.kitecodec.smoke`, compileSdk 36, minSdk 24 and JDK 21. Debug is debuggable;
    release is minified with the default optimized rules, uses the otherwise empty
    `proguard-rules.pro`, and is debuggable and signed by the debug signing configuration solely
    for this local `run-as` proof. Both use
    `packaging.jniLibs.useLegacyPackaging = false` and the name-specific
    `packaging.jniLibs.keepDebugSymbols += "**/libkitecodec_jni.so"`. The latter makes AGP copy
    the two already-audited JNI inputs byte for byte rather than strip them before the existing
    AAR-to-APK identity assertion. The scratch manifest preserves
    `android:extractNativeLibs="false"`. It applies only the Android application plugin,
    uses no app keep rule and has exactly one library dependency:

    ```kotlin
    implementation("io.github.yuroyami:kitecodec-core:0.0.1")
    ```

    It does not apply `io.github.yuroyami.kitecodec`. `ContractMedia.kt` contains the same bounded
    byte fixture and SHA-256 used by the shared contract test. Its plain `android.app.Activity`
    writes that fixture to app-private storage, calls `FFmpeg.hasDecoder("h264")`, opens it, reads
    and decodes a frame, closes every owner, then atomically writes the one-line oracle `PASS` to
    `files/result.txt`. Any exception writes `FAIL: <type>: <message>`. Prove metadata, consumer
    rules, JNI loading and real decode in both debug and minified release:

    ```bash
    S1C_CODEC_SMOKE=/private/tmp/kitecodec-s1c-android-consumer
    S1C_AAR='/Users/macbook/StudioProjects/#Kite/KiteCodec/kitecodec-core/build/outputs/aar/kitecodec-core.aar'
    test -f "$S1C_AAR"
    ./gradlew -p "$S1C_CODEC_SMOKE" \
      assembleDebug assembleRelease \
      --offline --refresh-dependencies --rerun-tasks
    for S1C_VARIANT in debug release; do
      S1C_SMOKE_APK="$S1C_CODEC_SMOKE/app/build/outputs/apk/$S1C_VARIANT/app-$S1C_VARIANT.apk"
      adb -s emulator-5554 install -r "$S1C_SMOKE_APK"
      adb -s emulator-5554 shell am force-stop io.github.yuroyami.kitecodec.smoke
      adb -s emulator-5554 shell run-as io.github.yuroyami.kitecodec.smoke \
        rm -f files/result.txt files/result.txt.tmp
      adb -s emulator-5554 shell am start -W \
        -n io.github.yuroyami.kitecodec.smoke/.MainActivity
      S1C_RESULT_TRIES=0
      until adb -s emulator-5554 shell run-as io.github.yuroyami.kitecodec.smoke \
        test -s files/result.txt; do
        S1C_RESULT_TRIES=$((S1C_RESULT_TRIES + 1))
        if [ "$S1C_RESULT_TRIES" -ge 120 ]; then
          echo "$S1C_VARIANT consumer produced no oracle within 120 seconds" >&2
          exit 1
        fi
        sleep 1
      done
      adb -s emulator-5554 shell run-as io.github.yuroyami.kitecodec.smoke \
        cat files/result.txt | grep -Fx PASS
    done
    test -s "$S1C_CODEC_SMOKE/app/build/outputs/mapping/release/mapping.txt"
    for S1C_VARIANT in debug release; do
      S1C_SMOKE_APK="$S1C_CODEC_SMOKE/app/build/outputs/apk/$S1C_VARIANT/app-$S1C_VARIANT.apk"
      test "$(zipinfo -1 "$S1C_SMOKE_APK" | \
        awk '/^lib\/.*[.]so$/ {count++} END {print count + 0}')" = 2
      zipinfo -1 "$S1C_SMOKE_APK" | grep -Fx 'lib/arm64-v8a/libkitecodec_jni.so'
      zipinfo -1 "$S1C_SMOKE_APK" | grep -Fx 'lib/x86_64/libkitecodec_jni.so'
      test "$(unzip -lv "$S1C_SMOKE_APK" 'lib/*/libkitecodec_jni.so' | \
        awk '/libkitecodec_jni[.]so$/ {print $2}' | sort -u)" = Stored
      /Users/macbook/WORKSTATION/AndroidSDK/build-tools/36.1.0/zipalign \
        -c -P 16 -v 4 "$S1C_SMOKE_APK"
      for S1C_ABI in arm64-v8a x86_64; do
        test "$(unzip -p "$S1C_AAR" "jni/$S1C_ABI/libkitecodec_jni.so" | \
          shasum -a 256 | awk '{print $1}')" = \
          "$(unzip -p "$S1C_SMOKE_APK" "lib/$S1C_ABI/libkitecodec_jni.so" | \
          shasum -a 256 | awk '{print $1}')"
      done
    done
    ```

    Inspect both APKs by counting every `lib/**/*.so` entry, requiring exactly the two named ABI
    paths and no other native library; require both entries Stored and run `zipalign -c -P 16 -v 4`.
    A successful debug-only load is insufficient. Both must decode, release must actually run R8,
    and each packaged `libkitecodec_jni.so` must be byte-identical to its AAR input.
14. Update Codec documentation to say JVM actuals exist, the macOS dylib is test-only, Android is
    minSdk 24 with arm64-v8a/x86_64 and 16 KiB packaging, MediaCodec is exposed only through FFmpeg,
    and public artifacts still do not exist. Do not claim x86_64 runtime qualification, physical
    Android, desktop runtime jars, Compose, views or T3-Full.

Gate. Tier 2 plus the named JVM/native transcript comparison, AAR inspection, 16 KiB emulator
contract run, one superset local publication and both offline consumers. The publication above
replaces Tier 2's host-only publish. Run every other Tier 2 command with the selector this sub-phase
names, then both Tier 1 blocks. Source scans reject any libav include in JNI, `Java_*` export, and
the tokens `android.media.MediaCodec`, `android.media.MediaExtractor`, `AMediaCodec` or
`AMediaExtractor` anywhere in Kotlin/native sources; an import-only pattern is insufficient.
Before that scan, replace the two existing comment-only `AMediaCodec` spellings in
`BuildFFmpegTask.kt` and `ffmpeg.def` with platform-neutral native-codec wording. The comments do
not authorize a platform API call, and narrowing or allowlisting the scan is forbidden. The
Codec version catalog change is limited to AGP and the three AndroidX test coordinates named here.

Commit first line. KiteCodec: `Make KiteCodec real on JVM and Android`.
KitePlayer: `Record the JVM and Android Codec proof`.

#### S1.c.3 Run the FFmpeg backend on Android, including runtime hardware fallback

Files, KitePlayer: `gradle/libs.versions.toml`; `kiteplayer-ffmpeg/build.gradle.kts`; move
`Conversions.kt`, `FFmpegRuntimeCheck.kt`, `KiteCodecMediaBackend.kt`,
`KiteCodecSource.kt` and `Probe.kt` from nativeMain to commonMain; rename the existing converter
to `SoftwareConverter.native.kt` and add
`kiteplayer-ffmpeg/src/jvmAndAndroidMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/SoftwareConverter.jvm.kt`.
New common files are `DecoderFallback.kt` and `PlatformDecoderSelection.kt`; actuals are
`PlatformDecoderSelection.native.kt`, `PlatformDecoderSelection.jvm.kt` and
`PlatformDecoderSelection.android.kt` in their matching source sets. Public truth and stats
propagation corrections include `kiteplayer-core/.../PlayerConfig.kt`, `PlayerState.kt`,
`PlaybackError.kt`, `spi/VideoFrame.kt` and `internal/PlaybackCore.kt`. Existing core test support
in the fence is `kiteplayer-core/src/commonTest/kotlin/io/github/yuroyami/kiteplayer/ScriptedBackend.kt`
and `KitePlayerTest.kt`. Test files are new common
`DecoderFallbackTest.kt`, `PlatformDecoderSelectionTest.kt` and `BackendContractTranscript.kt`;
JVM `JvmBackendContractTest.kt`; macosArm64 `MacosBackendContractTest.kt`; Android host
`AndroidDecoderSelectionHostTest.kt`; and Android device
`AndroidMediaCodecDeviceTest.kt` plus `AndroidMedia.kt` and the device-test manifest. Existing
native backend tests remain and are moved to the narrowest shared source set that compiles without
changing assertions. Also in the fence: the core and FFmpeg API dumps, root `README.md`, the
already named FFmpeg build-file comments and source KDoc, and KPKMP execution log. There is no
module README in this tree.

Steps.
1. Add AndroidX test core/runner 1.7.0 and ext-junit 1.3.0 to Player's catalog once for all S1.c
   device tests. Register `jvm()` and an Android KMP target in `kiteplayer-ffmpeg` with namespace
   `io.github.yuroyami.kiteplayer.ffmpeg`, compileSdk 36, minSdk 24, host tests and device tests.
   Create `jvmAndAndroidMain` from `commonMain` and make both `jvmMain` and `androidMain` depend on
   it; the exact shared converter path named in the file fence lives there. Native targets keep
   `SoftwareConverter.native.kt` in nativeMain. Move the test-only
   `implementation(project(":kiteplayer-output"))` dependency from `commonTest` to `nativeTest`:
   the real-media output tests are native, while c.3's new JVM and Android test variants cannot
   resolve an output JVM variant and output does not gain Android until S1.c.4.
   The Android target consumes the published KiteCodec AAR transitively; it neither declares
   jniLibs nor rebuilds Codec. JVM tests map
   `-Pkitecodec.jni.localPath=<absolute dylib>` to the test JVM system property
   `kitecodec.jni.path`. No desktop native library is packaged or published here.
2. Move only Kotlin-platform-neutral code to commonMain. Remove
   `ExperimentalForeignApi` and cinterop imports from those files. Native
   `SoftwareConverter` keeps `Frame.withPlanes` and its no-copy reads. JVM/Android
   `SoftwareConverter` calls `Frame.copyPlanesToByteArray()` once, derives tightly packed plane
   offsets and strides from the declared pixel format and uses the same coefficient, range,
   ten-bit alignment and chroma rules. Both public objects keep the same `toRgba` signature and
   golden bytes. A shared golden test covers YUV420P, NV12, P010, RGBA/BGRA, BT.601/709/2020,
   full/studio range and red-blue channel order. Before the D-2 source gate, replace the four
   native-era diagnostic/KDoc spellings that name `AVIOContext`, `AVFormatContext` or
   `libavformat` in `KiteCodecSource.kt` and `KiteCodecMediaBackend.kt` with platform-neutral
   wording. Do not weaken the scan that catches direct-native tokens.
3. Make platform selection an internal expect/actual plan, never a platform decoder call.
   Native and ordinary JVM return software only. Android maps H.264 to
   `CodecId("h264_mediacodec")` and HEVC to `CodecId("hevc_mediacodec")`, then uses the new
   named-decoder argument on KiteCodec's `MediaSource.openDecoder`. The table is:

   | Policy | Eligible Android H.264/HEVC | Other codec or failed MediaCodec open |
   |---|---|---|
   | `Off` | software only | software only |
   | `Auto` | MediaCodec, then software with one warning | software |
   | `Prefer` | try `MediaCodec` only when it appears in the supplied order, then software | software |
   | `Require` | MediaCodec or refuse | refuse |

   A `Prefer` list containing other kinds does not call those platform APIs. `Require` never
   silently opens software. Update the four stale public KDocs that say hardware has no effect.
4. Report the actual data path. FFmpeg n8.0's MediaCodec decoder, when opened without an output
   Surface, maps codec output to CPU YUV420P/NV12 frames and copies it into AVFrame storage.
   `AV_PIX_FMT_MEDIACODEC` is used only when a Surface is supplied. S1.c supplies none. Therefore
   the decoder's `hardware` getter is `HardwareWithDownload(HwdecKind.MediaCodec)` while each
   delivered frame is software-readable with `hardwareSurface == null`. Do not claim zero copy or
   create a Surface in the decoder. D-2 is satisfied because selection remains FFmpeg's named
   decoder. Make `PlaybackCore.publishSnapshot()` assign
   `hardwareDecode = session?.videoDecoder?.hardware ?: HwdecStatus.Software`; the fallback
   driver's dynamic getter uses cross-thread-safe storage so the actor cannot retain a stale
   hardware claim. Extend `ScriptedVideoDecoder` with a minimal injectable status and add a
   `KitePlayerTest` that observes hardware first and Software immediately after demotion.
5. Put open and runtime fallback in `DecoderFallback.kt` behind a small internal decoder-driver
   seam. For `Auto` and `Prefer`, a hardware open refusal emits one
   `HardwareDecodeUnavailable` warning and opens software. For `Require` it returns no decoder.
   `Off` never probes hardware. The seam exposes only open, send, receive, flush, drained and close,
   so tests can schedule failures without a codec process.
6. Retain an O(1) copy of every packet the hardware decoder accepts, starting with the newest
   confirmed replay keyframe. Represent keyframe handover as one retained sequence plus a pending
   boundary index: accepting a newer keyframe marks the candidate boundary but does not close the
   older prefix. FFmpeg may still emit delayed B-frames from the preceding GOP. Only a decoded
   `FrameInfo.isKeyframe` observed after that candidate was accepted confirms handover; then close
   clones before the candidate boundary and make the just-delivered keyframe output ordinal one in
   the new window. Track retained bytes across the complete old-plus-candidate sequence and cap one
   decoder at exactly 16 MiB. Before accepting a packet that would cross the cap, open software and
   replay while that complete window is still valid. This is a proactive demotion and emits the
   same one warning. Never retain audio packets.
7. Track the exact count of outputs delivered since the confirmed replay boundary, independent of
   PTS. On hardware send or receive failure under `Auto`/`Prefer`, close the failed decoder, open
   the by-id software decoder and replay every retained packet in order through the normal
   send/receive backpressure loop. Discard and close exactly that many replay outputs, then deliver
   the next one and continue on software. PTS, when present, is only a validation diagnostic; it
   never decides how many frames to suppress because consecutive frames may share a PTS and output
   may carry `NOPTS`. If failure occurs after a candidate keyframe packet was accepted but before
   its decoded keyframe confirmed handover, replay still starts at the older confirmed keyframe.
   Close every retained packet after replay. A failure before any confirmed retained keyframe is a
   typed decoder failure because replay would be corrupt; it is never presented as successful
   fallback. `Require` reports the original failure and never reopens software.
8. `flush(newGeneration)` closes the full retained sequence, flushes the active decoder, resets the
   delivered-output ordinal and pending keyframe boundary, and starts the new epoch. Drain packets
   participate in the same recovery rule. `close` closes the active decoder and every retained
   packet exactly once. The public `hardware` getter changes from HardwareWithDownload to Software
   immediately after a demotion, so stats do not preserve a stale claim.
9. Write the failing seam tests first. Exact arms: hardware-open refusal; send failure on the third
   packet of a two-frame GOP; receive failure after one delivered frame; replay backpressure;
   legitimate consecutive outputs with duplicate PTS; replay output with `NOPTS`; a new keyframe
   packet accepted followed by a delayed old-GOP B-frame and failure before the new decoded
   keyframe; confirmed handover resetting the ordinal to the delivered keyframe; 16 MiB proactive
   demotion counting both handover windows; failure without keyframe; strict `Require`; `Off` no
   probe; flush during retained GOP; close during failed reopen. The ownership ledger ends at zero
   in every arm. Remove the retained keyframe in the receive-failure test and require the expected
   frame/hash sequence to fail before restoring it.
10. Build the JVM, Android and native arms against the single window-2b publication:

    ```bash
    export ANDROID_SDK_ROOT=/Users/macbook/WORKSTATION/AndroidSDK
    export ANDROID_NDK_HOME=/Users/macbook/WORKSTATION/AndroidSDK/ndk/29.0.14206865
    cd ../KitePlayer
    ./scripts/testmedia.sh
    ./gradlew :kiteplayer-ffmpeg:jvmTest \
      :kiteplayer-ffmpeg:testAndroidHostTest \
      :kiteplayer-ffmpeg:assembleAndroidMain \
      :kiteplayer-ffmpeg:macosArm64Test \
      -Pkitecodec.jni.localPath="$PWD/../KiteCodec/kitecodec-core/build/kitecodec-jni/macos-arm64/libkitecodec_jni.dylib" \
      --offline --rerun-tasks
    test -s kiteplayer-ffmpeg/build/s1c-transcripts/jvm.txt
    test -s kiteplayer-ffmpeg/build/s1c-transcripts/macosArm64.txt
    cmp -s kiteplayer-ffmpeg/build/s1c-transcripts/jvm.txt \
      kiteplayer-ffmpeg/build/s1c-transcripts/macosArm64.txt || {
      diff -u kiteplayer-ffmpeg/build/s1c-transcripts/macosArm64.txt \
        kiteplayer-ffmpeg/build/s1c-transcripts/jvm.txt
      exit 1
    }
    ```

    `BackendContractTranscript.kt` returns the same sorted, path-free scalar transcript to both
    wrappers. `kiteplayer-ffmpeg/build.gradle.kts` passes the JVM output path as the tracked
    `s1c.transcript.path` system property, while its `KotlinNativeTest` configuration passes the
    macosArm64 path through the tracked `S1C_TRANSCRIPT_PATH` environment variable. The native
    wrapper reads it with `platform.posix.getenv`, following the repository's existing
    `KITEPLAYER_TESTMEDIA` pattern; it does not call a nonexistent Kotlin/Native
    `System.getProperty`. Each wrapper writes atomically. The exact `cmp` above is the comparison
    gate, and `diff` makes the first mismatch visible. The JVM real-media hashes, seek landing and
    runtime-identity transcript equal macosArm64. The Android host arm uses only fake drivers and
    does not pretend mockable `android.jar` is a device.
11. In `AndroidMedia.kt` embed bounded H.264 and MPEG-4 MP4 fixtures generated from the repository's
    sync source and record each SHA-256 beside its bytes. The device test writes them to app-private storage,
    requires `FFmpeg.hasDecoder("h264_mediacodec")`, opens under `Auto`, receives a CPU-readable
    frame, compares it to the software decode at least 40 dB luma PSNR and observes
    `HardwareWithDownload(MediaCodec)`. It then runs `Off` and sees Software, and runs
    `Require` after substituting an ineligible MPEG-4 stream and sees refusal:

    ```bash
    ANDROID_SERIAL=emulator-5554 ./gradlew \
      :kiteplayer-ffmpeg:connectedAndroidDeviceTest \
      --offline --rerun-tasks
    ```

    Capture `adb -s emulator-5554 logcat -d` only as diagnostic evidence. The test result, not a
    codec-name log line, is the gate. No x86_64 runtime claim follows.
12. Update and check API dumps:

    ```bash
    ./gradlew :kiteplayer-core:updateKotlinAbi \
      :kiteplayer-ffmpeg:updateKotlinAbi
    ./gradlew :kiteplayer-core:checkKotlinAbi \
      :kiteplayer-ffmpeg:checkKotlinAbi
    ```

    Core declaration signatures do not move; only stale KDoc truth changes. FFmpeg gains JVM and
    Android target metadata but no new public declaration. Any other API difference is BLOCKING.
13. Run source controls over the complete S1.c.3 diff:

    ```bash
    test -d kiteplayer-core/src
    test -d kiteplayer-ffmpeg/src/commonMain
    test -d kiteplayer-ffmpeg/src/jvmMain
    test -d kiteplayer-ffmpeg/src/androidMain
    ! rg -n \
      'android[.]media[.](MediaCodec|MediaExtractor|[*])|AMedia(Codec|Extractor)|androidx[.]media3|ExoPlayer' \
      -g '*.kt' kiteplayer-core/src kiteplayer-ffmpeg/src
    ! rg -n \
      '(^|[^A-Za-z0-9_])(ffmpeg[.]|libav(codec|format|filter|util)|cnames[.]structs[.]AV|av(codec|format|filter|util)_[A-Za-z0-9_]+|sw(scale|resample)_[A-Za-z0-9_]+|AV[A-Z][A-Za-z0-9_]+)' \
      -g '*.kt' \
      kiteplayer-ffmpeg/src/commonMain \
      kiteplayer-ffmpeg/src/jvmMain \
      kiteplayer-ffmpeg/src/androidMain
    ```

    Strings `h264_mediacodec` and `hevc_mediacodec` are required and do not fail the first scan.
    Temporarily add a wildcard `import android.media.*`, a fully qualified
    `android.media.MediaCodec` use, an `AMediaCodec` token and one direct `avcodec_send_packet`
    token in separate controls and prove the matching scan fails each time. D-1 forbids
    MediaExtractor or another demuxer even as a fallback.

Gate. Tier 2, selected by JVM/Android/native Kotlin, build script and catalog changes and by Horizon
completion. Add the JVM/Android tests and named 16 KiB device run above. Do not republish
KiteCodec: the one window-2b coordinate is immutable for the rest of S1.c. Tier 3 is not selected;
no real-time C callback or core teardown ordering changed. Finish with both Tier 1 blocks and the
D-1/D-2 scans.

Commit first line. KitePlayer: `Run the FFmpeg backend on Android`.

#### S1.c.4 Play Android audio through AudioTrack

Files: `kiteplayer-output/build.gradle.kts`; new
`kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/AndroidMonotonicClock.kt`,
`AudioTrackDriver.kt`, `AudioTrackSink.kt` and `AndroidOutputBackend.kt`; new
`src/androidHostTest/.../AudioTrackSinkTest.kt` and `AndroidAudioClockTest.kt`; new
`src/androidDeviceTest/.../AudioTrackSinkDeviceTest.kt` and device-test manifest;
`kiteplayer-output/api/`, root `README.md`, the already named output build-file comments and source
KDoc, and KPKMP execution log. There is no output module README. No core engine file is in this
fence.

Steps.
1. Register the Android KMP target in output with namespace
   `io.github.yuroyami.kiteplayer.output`, compileSdk 36, minSdk 24, host tests and device tests.
   Output depends only on core and its existing portable libraries. It does not depend on
   KiteCodec, FFmpeg, the NDK, an Android media support library or the future phone aggregate.
2. Add these public declarations and no Android audio policy surface beyond them:

   ```kotlin
   public object AndroidMonotonicClock : MonotonicClock

   public class AudioTrackSink() : AudioSink

   public class AudioTrackSinkFactory() : AudioSinkFactory

   public object AndroidOutputBackend : OutputBackend
   ```

   `AndroidMonotonicClock.nanos()` calls `SystemClock.elapsedRealtimeNanos()`.
   `AndroidOutputBackend` pairs that exact object with `AudioTrackSinkFactory` and returns null for
   video. Public constructors use that same clock. An internal sink constructor accepts a driver
   factory and clock for tests, so production cannot accidentally pair AudioTimestamp with another
   time base.
3. Keep Android calls in one internal `AudioTrackDriver`. Production creates
   `AudioTrack` with `AudioAttributes.USAGE_MEDIA`, `CONTENT_TYPE_MOVIE`, MODE_STREAM and
   ENCODING_PCM_FLOAT. The accepted format is requested sample rate with one channel when requested
   mono and stereo otherwise. Requests with no channels or invalid sample rate fail before device
   creation. Surround downmix and resampling remain in `AudioPipeline` because the sink returns its
   accepted format and never converts.
4. Allocate the AudioTrack buffer at least `getMinBufferSize` and expose
   `bufferSizeInFrames` as `deviceBufferFrames`. The writer callback block is exactly
   `min(deviceBufferFrames, 512)` frames. Allocate one FloatArray sized for 512 stereo frames and
   one `AudioSinkBuffer` adapter during open, never in the loop. `writeInterleaved` copies into that
   array, `writeSilence` zeroes the requested tail and `writePlane` is rejected because AudioTrack
   is interleaved.
5. On start, one dedicated Java thread calls
   `Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)`, then repeats: compute the deadline
   for the last frame of the requested block, invoke the engine's `AudioRenderCallback`, silence
   every frame after a short return, and loop `AudioTrack.write(..., WRITE_BLOCKING)` until all
   frames or a lifecycle signal. A zero/negative platform write is a device failure, not a busy
   loop. The thread owns all writes; lifecycle methods never write.
6. Maintain `submittedFrames: Long`. Prefer a valid, monotonic `AudioTimestamp`:

   `deadline = timestamp.nanoTime + duration(submittedFrames + requestedFrames -
   timestamp.framePosition)`.

   Reject a timestamp ahead of submitted data or behind the prior timestamp. Fallback extends the
   unsigned 32-bit `playbackHeadPosition` across wraps and computes the same queued-frame deadline
   from `clock.nanos()`. `latencyNanos()` uses submitted minus the newest valid played position and
   clamps at zero. Report `LatencyQuality.Estimated` because the fallback is an estimate even when
   timestamps are usually present.
7. Make lifecycle ordering explicit:

   - `stop` signals the writer, pauses/stops the driver to unblock a blocking write, joins, flushes,
     and resets submitted/timestamp extension state.
   - `setPaused(true)` signals, pauses the driver to unblock a write, then joins without flushing;
     `setPaused(false)` plays and starts one new writer. It returns true.
   - `drain` marks the writer draining. The writer keeps pulling and writing until the callback's
     first short return, silences and submits that final tail, then exits. The owner joins, waits
     with a bounded poll until the extended playback head reaches submitted frames, then stops
     without flushing.
   - `close` performs the stop ordering, joins any writer and only then releases AudioTrack. It is
     idempotent. Failed open releases a partially created driver and leaves no writer.

   No release can race a write. No lifecycle call enters AudioTrack after release.
8. Write fake-driver tests before production calls. Pin exact open attributes/format, mono/stereo
   negotiation, preallocated callback buffer identity across 10,000 loops, short callback tail
   silence, three partial writes, zero-write failure, timestamp deadline arithmetic, rejected
   timestamp fallback, 32-bit playback-head wrap, latency clamp, pause/resume, stop flush, drain
   without flush, open rollback, double close and close while blocked. The fake records any write
   after release as a hard failure. The negative control moves release before join and must make
   that test fail.
9. On `Pixelu16KB`, open 48 kHz stereo float, render a bounded sine wave, start, and require the
   playback head to advance by at least 256 frames within five seconds. Require monotonically
   increasing callback deadlines, stop, close twice, reopen and repeat once. The test accepts the
   documented playback-head fallback when AudioTimestamp is unavailable, but records which source
   it observed. It does not claim a human heard the emulator:

   ```bash
   export ANDROID_SDK_ROOT=/Users/macbook/WORKSTATION/AndroidSDK
   cd ../KitePlayer
   ./gradlew :kiteplayer-output:testAndroidHostTest \
     :kiteplayer-output:assembleAndroidMain --rerun-tasks
   ANDROID_SERIAL=emulator-5554 ./gradlew \
     :kiteplayer-output:connectedAndroidDeviceTest --rerun-tasks
   ```
10. Update and inspect the output API dump. The four declarations above and Android target metadata
    are the only public change:

    ```bash
    ./gradlew :kiteplayer-output:updateKotlinAbi \
      :kiteplayer-output:checkKotlinAbi
    ```

11. Scan the phase diff. `android.media.AudioTrack`, `AudioTimestamp`, `AudioAttributes` and
    `android.os` occur only in output's Android source set. `AAudio`, `Oboe`, AudioFocus,
    `MediaSession`, `MediaCodec`, `MediaExtractor`, Compose and the ffmpeg package are absent.

Gate. Tier 2, selected by Android Kotlin, build script and Horizon completion. Add the fake-driver
suite and named emulator AudioTrack run. This is deliberately not Tier 3: it changes neither
`kite_rt_render.c`, a C device callback nor core teardown ordering. The Android writer is tested by
its driver ordering and device head advance, not promoted to the native real-time claim. Do not
republish either repository. Finish with both Tier 1 blocks.

Commit first line. KitePlayer: `Play Android audio through AudioTrack`.

#### S1.c.5 Present converted frames on a caller-owned Android Surface

Files: `kiteplayer-output/build.gradle.kts`; new
`kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/AndroidSurfaceVideoRenderer.kt`;
new `src/androidHostTest/.../AndroidSurfaceVideoRendererTest.kt` and
`AndroidSurfaceGeometryTest.kt`; new
`src/androidDeviceTest/.../AndroidSurfaceVideoRendererDeviceTest.kt`,
`RendererTestActivity.kt` and device-test manifest; `kiteplayer-output/api/`, root `README.md`, the
already named output build-file comments and source KDoc, and KPKMP execution log. There is no
output module README. No ffmpeg, core, sample or reusable-view file is in this fence.

Steps.
1. Add exactly this public renderer and the same three counters as the Apple CPU fallback:

   ```kotlin
   public class AndroidSurfaceVideoRenderer(
       surface: android.view.Surface,
       convert: (VideoFrame) -> ByteArray,
   ) : VideoRenderer {
       public val presentedFrames: Long
       public val supersededFrames: Long
       public val failedFrames: Long
   }
   ```

   The constructor stores the caller's Surface but never calls `release()`. The caller must close
   the renderer before releasing or replacing that Surface. There is no SurfaceHolder constructor,
   View, Activity, lifecycle observer, backend factory or FFmpeg type. S1.d's phone aggregate owns
   the reusable host and supplies `SoftwareConverter::toRgba`.
2. Mirror the proven AppKit ownership shape: one atomic pending-frame slot, one signal and one
   single-thread conversion worker. `present` returns false and closes immediately after close or
   while the Surface is invalid. Otherwise it swaps the pending slot, closes/counts any displaced
   frame, signals once and returns true. The worker takes the newest frame, calls `convert` and
   closes the frame in `finally`. Conversion failure increments failed and emits
   `RendererEvent.Failed` without killing the worker.
3. Validate that converted bytes are exactly `width * height * 4` tightly packed RGBA. Swizzle into
   a reusable ARGB IntArray and reuse an ARGB_8888 Bitmap while dimensions match; a size change
   replaces the two buffers after the prior draw completes. Red stays red and blue stays blue on
   little-endian Android. A short or oversized converter result is a typed conversion failure,
   never a partial draw.
4. Put Android graphics calls behind an internal canvas-target seam. Production checks
   `surface.isValid`, calls `lockCanvas(null)` on the worker, clears to opaque black, computes an
   aspect-fit destination from frame width, height and pixel-aspect ratio, applies exactly
   0/90/180/270 clockwise rotation about the destination centre and draws the bitmap. A successful
   lock always reaches `unlockCanvasAndPost(canvas)` in `finally`, including when drawing throws.
   A lock failure or invalid Surface emits one `SurfaceLost` transition and increments failed; the
   first later successful post emits `SurfaceAvailable`. It does not stop playback or call the
   player.
5. `supportedHardwareSurfaces()` is empty, `supports` rejects only Opaque,
   `vsyncIntervalNanos()` returns null, and `setViewport`/`setOverlay` remain no-ops like the
   existing tier-0 Apple fallback. This phase does not add subtitle drawing or Choreographer timing.
   Target-time scheduling remains the engine's responsibility.
6. `close` first blocks acceptance, wakes and joins the worker, then drains and closes the pending
   slot and releases reusable Bitmap storage. It waits for an in-progress lock/post to finish before
   returning, but never waits on the UI thread: Canvas operations already run on the private worker.
   A queued present racing close is owned by exactly one of the swap or drain paths.
7. Write host-seam tests first. Pin: newest of 100 frames wins with 99 exact closes; conversion
   exception; short/long RGBA; red/blue channel order; 4:3 into 16:9 and 16:9 into 4:3 rectangles;
   non-square pixels; every quarter-turn; invalid Surface refusal; lock exception; draw exception
   still posts; one lost/available event per transition; close before worker take; close during
   conversion; present racing close; double close. Every frame ledger ends at zero and pending work
   never exceeds one. Remove the slot swap and watch the 100-frame bound fail before restoring it.
8. Device-test a real SurfaceView hosted only by `RendererTestActivity`. Wait for
   `surfaceCreated`, present an asymmetric red-left/blue-right frame, use PixelCopy to assert centre
   pixels and black letterbox, repeat at 90 degrees and assert the axes swap, then destroy the
   Surface and require the next present to return false plus `SurfaceLost`. Close the renderer
   before the Activity releases the Surface:

   ```bash
   export ANDROID_SDK_ROOT=/Users/macbook/WORKSTATION/AndroidSDK
   cd ../KitePlayer
   ./gradlew :kiteplayer-output:testAndroidHostTest \
     :kiteplayer-output:assembleAndroidMain --rerun-tasks
   ANDROID_SERIAL=emulator-5554 ./gradlew \
     :kiteplayer-output:connectedAndroidDeviceTest --rerun-tasks
   ```

9. Update and inspect the output dump:

   ```bash
   ./gradlew :kiteplayer-output:updateKotlinAbi \
     :kiteplayer-output:checkKotlinAbi
   ```

   The renderer, constructor and three counters are the only new declarations in this sub-phase.
   No core or FFmpeg dump moves.
10. Run boundary scans:

    ```bash
    ! rg --pcre2 -n \
      '^(?!\s*(?:[*]|//)).*(kiteplayer[.]ffmpeg|kitecodec|SoftwareConverter|MediaCodec|MediaExtractor|ExoPlayer)' \
      -g '*.kt' kiteplayer-output/src
    ! rg -n \
      'kiteplayer-ffmpeg|kitecodec|SoftwareConverter|MediaCodec|MediaExtractor|ExoPlayer' \
      kiteplayer-output/build.gradle.kts
    ! rg -n \
      'SurfaceHolder|AndroidView|KitePlayerView|Compose|OpenGL|GLES|Vulkan' \
      kiteplayer-output/src/androidMain
    ```

    Historical comments may name the neighbouring FFmpeg module while explaining the boundary;
    imports, fully qualified executable references and Gradle dependencies may not cross it. The
    sample and later phone module may spell `SoftwareConverter`; output may not. Temporarily import
    it into the renderer and prove the first scan fails.

Gate. Tier 2, selected by Android Kotlin and Horizon completion. Add host geometry/ownership tests
and the real Surface device test. Tier 3 is not selected because this is not the C audio render
path, callback or core teardown order. No publication occurs. Finish with both Tier 1 blocks and
the module-boundary scans.

Commit first line. KitePlayer: `Present converted frames on an Android Surface`.

#### S1.c.6 Add and run the provisional Android phone application

Files: `gradle/libs.versions.toml`; root `build.gradle.kts` and `settings.gradle.kts`; new
`buildSrc/src/main/kotlin/PrepareAndroidSampleMediaTask.kt` and
`buildSrc/src/test/kotlin/PrepareAndroidSampleMediaTaskTest.kt`. New module files are
`kiteplayer-sample-android/build.gradle.kts`, `proguard-rules.pro`,
`src/main/AndroidManifest.xml`,
`src/main/kotlin/io/github/yuroyami/kiteplayer/sample/android/MainActivity.kt`,
`SampleController.kt` and `SmokeResult.kt`, `src/main/res/values/strings.xml` and
`README.md`. Generated `build/generated/s1cAssets/sync1080p30.mp4`, APKs, smoke JSON and adb logs
are evidence only. Root README support/run sections and KPKMP execution log are also in the fence.
No existing macOS/iOS sample source moves.

Steps.
1. Add the `com.android.application` alias at the existing AGP 9.2.1 version and declare it
   `apply(false)` at the root. Include exactly `:kiteplayer-sample-android`. This is a regular
   application, not a KMP library, and applies no KiteCodec plugin. Configure namespace/application
   id `io.github.yuroyami.kiteplayer.sample.android`, compile/target SDK 36, minSdk 24 and JDK 21.
   Filter ABIs to arm64-v8a and x86_64, set
   `packaging.jniLibs.useLegacyPackaging = false` and put
   `android:extractNativeLibs="false"` on the application. Debug is ordinary; release enables R8,
   uses the default optimized rules plus the empty sample rule file, and is locally debuggable and
   signed with the debug key only so the same `run-as` oracle can inspect it after installation.
   R8 still runs. No distributed release or publication consumes either local-only choice.
2. The app has exactly these three project dependencies:

   ```kotlin
   implementation(project(":kiteplayer-core"))
   implementation(project(":kiteplayer-ffmpeg"))
   implementation(project(":kiteplayer-output"))
   ```

   It does not add Compose, AndroidX media, ExoPlayer, a platform codec or another playback
   dependency. This is the provisional assembly proof. S1.d creates `:kiteplayer-phone`, moves the
   reusable host there and re-consumes this app through one coordinate.
3. `PrepareAndroidSampleMediaTask` takes `testmedia/sync1080p30.mp4` as an input file, verifies it
   is nonempty, copies it transactionally to
   `build/generated/s1cAssets/sync1080p30.mp4` and writes its SHA-256 beside the task log. Wire the
   output through `androidComponents.onVariants` and
   `checkNotNull(variant.sources.assets).addGeneratedSourceDirectory`; do not commit a 20 MB media
   file. The explicit check is required because AGP 9.2.1 exposes nullable layered asset sources;
   a safe-call could silently omit the generated fixture. Its unit
   test pins missing input, byte equality, SHA output, rerun after content change and no partial
   destination after injected failure. The Gradle task never invokes ffmpeg or a network; the
   producer remains the explicit first command:

   ```bash
   cd ../KitePlayer
   ./scripts/testmedia.sh
   ./gradlew :buildSrc:test
   ```
4. `MainActivity` is sample-private glue. It creates a SurfaceView and play, pause and seek buttons
   programmatically, with no XML layout and no reusable public View. `SampleController` creates
   `KiteCodecMediaBackend()` and `AndroidOutputBackend`, constructs
   `AndroidSurfaceVideoRenderer(holder.surface)` with a conversion lambda that accepts only
   `KiteCodecVideoFrame` and calls `SoftwareConverter.toRgba`, creates the player and attaches the
   renderer. On `surfaceDestroyed` it detaches, closes the renderer and only then returns the
   Surface to the framework. Activity teardown closes player then any renderer once. Backgrounding
   pauses rather than inventing audio-focus policy.
5. The ordinary controls open the copied private path, play/pause and seek to five seconds. No
   sample class enters a library API dump. Do not move this host into output: output must remain
   FFmpeg-free, and S1.d owns the reusable phone abstraction.
6. Add boolean intent extra `s1c_smoke`. In smoke mode, wait for a valid Surface, copy the bundled
   asset to app-private storage, open and play, request one precise seek to 5,000 milliseconds, wait
   for at least one later renderer presentation and public position in the inclusive range 5,000 to
   5,034 milliseconds, then wait for Ended. Close player and renderer before setting
   `teardownCompleted`. Write `s1c-smoke.json.tmp`, flush and `fd.sync()`, then atomically rename it
   over `files/s1c-smoke.json`. The exact eleven-key schema is:

   - `pageSize`
   - `seekRequested`
   - `seekLanded`
   - `terminalState`
   - `decodedFrames`
   - `submittedFrames`
   - `presentedFrames`
   - `surfaceFrame`
   - `audioUnderruns`
   - `hardwareDecode`
   - `teardownCompleted`

   `pageSize` comes from `Os.sysconf(_SC_PAGESIZE)`. `hardwareDecode` maps the public sealed stats
   value to the stable labels `Software`, `HardwareWithDownload(MediaCodec)` or the corresponding
   named alternative; it never uses a data class's default `toString()` or a codec-name log.
   `surfaceFrame` becomes true only after the renderer's presented counter rises. Positive
   AudioTrack head movement belongs to S1.c.4's device test rather than inaccessible sample
   internals.
7. Build both variants offline from the immutable Codec publication:

   ```bash
   export ANDROID_SDK_ROOT=/Users/macbook/WORKSTATION/AndroidSDK
   export ANDROID_NDK_HOME=/Users/macbook/WORKSTATION/AndroidSDK/ndk/29.0.14206865
   cd ../KitePlayer
   ./gradlew :kiteplayer-sample-android:assembleDebug \
     :kiteplayer-sample-android:assembleRelease \
     --offline --rerun-tasks
   ```

   Inspect both APKs:

   ```bash
   for apk in \
     kiteplayer-sample-android/build/outputs/apk/debug/kiteplayer-sample-android-debug.apk \
     kiteplayer-sample-android/build/outputs/apk/release/kiteplayer-sample-android-release.apk; do
     test -f "$apk"
     test "$(zipinfo -1 "$apk" | \
       awk '/^lib\/.*[.]so$/ {count++} END {print count + 0}')" = 2
     zipinfo -1 "$apk" | grep -Fx 'lib/arm64-v8a/libkitecodec_jni.so'
     zipinfo -1 "$apk" | grep -Fx 'lib/x86_64/libkitecodec_jni.so'
     ! zipinfo -1 "$apk" | grep -E 'armeabi|arm32|lib(av|sw).*[.]so'
     test "$(unzip -lv "$apk" 'lib/*/libkitecodec_jni.so' | \
       awk '/libkitecodec_jni[.]so$/ {print $2}' | sort -u)" = Stored
     /Users/macbook/WORKSTATION/AndroidSDK/build-tools/36.1.0/zipalign \
       -c -P 16 -v 4 "$apk"
   done
   ```

   Extract both ABI entries from both APKs and rerun the `JNI_OnLoad`-only, no-libav-NEEDED and
   `0x4000` PT_LOAD checks. The release APK must contain the registered bridge after R8 with no
   app-specific keep rule.
8. Run the complete smoke on debug, save its result, then replace it with minified release and run
   the same smoke again:

   ```bash
   S1C_PACKAGE=io.github.yuroyami.kiteplayer.sample.android
   S1C_COMPONENT="$S1C_PACKAGE/.MainActivity"
   for S1C_VARIANT in debug release; do
     S1C_APK="kiteplayer-sample-android/build/outputs/apk/$S1C_VARIANT/kiteplayer-sample-android-$S1C_VARIANT.apk"
     adb -s emulator-5554 install -r "$S1C_APK"
     adb -s emulator-5554 shell run-as "$S1C_PACKAGE" \
       rm -f files/s1c-smoke.json files/s1c-smoke.json.tmp
     adb -s emulator-5554 shell am start -W \
       -n "$S1C_COMPONENT" --ez s1c_smoke true
     S1C_TRIES=0
     until adb -s emulator-5554 shell run-as "$S1C_PACKAGE" \
       test -s files/s1c-smoke.json; do
       S1C_TRIES=$((S1C_TRIES + 1))
       if [ "$S1C_TRIES" -ge 120 ]; then
         echo "$S1C_VARIANT sample produced no oracle within 120 seconds" >&2
         exit 1
       fi
       sleep 1
     done
     adb -s emulator-5554 exec-out run-as "$S1C_PACKAGE" \
       cat files/s1c-smoke.json > "/private/tmp/s1c-$S1C_VARIANT.json"
   done
   ```

   Apply the same exact oracle to both files:

   ```bash
   for result in /private/tmp/s1c-debug.json /private/tmp/s1c-release.json; do
     /usr/bin/jq -e '
       (keys | sort) == [
         "audioUnderruns", "decodedFrames", "hardwareDecode", "pageSize",
         "presentedFrames", "seekLanded", "seekRequested", "submittedFrames",
         "surfaceFrame", "teardownCompleted", "terminalState"
       ] and
       .pageSize == 16384 and
       .seekRequested == true and .seekLanded == true and
       .terminalState == "Ended" and
       (.decodedFrames | type) == "number" and .decodedFrames > 0 and
       (.submittedFrames | type) == "number" and .submittedFrames > 0 and
       (.presentedFrames | type) == "number" and .presentedFrames > 0 and
       .surfaceFrame == true and
       (.audioUnderruns | type) == "number" and .audioUnderruns >= 0 and
       .hardwareDecode == "HardwareWithDownload(MediaCodec)" and
       .teardownCompleted == true
     ' "$result"
   done
   ```

   A successful debug run cannot excuse a release failure. The two JSON objects may differ in
   measured counts and underruns; both must satisfy the same typed predicates. Save logcat only when
   a predicate fails.
9. Close with D-1/D-2 and scope scans:

   ```bash
   test -d kiteplayer-sample-android/src
   test -d kiteplayer-core/src
   test -d kiteplayer-ffmpeg/src
   test -d kiteplayer-output/src
   ! rg -n \
     'android[.]media[.](MediaCodec|MediaExtractor|[*])|AMedia(Codec|Extractor)|androidx[.]media3|ExoPlayer' \
     -g '*.kt' kiteplayer-sample-android/src kiteplayer-core/src kiteplayer-ffmpeg/src kiteplayer-output/src
   ! rg -n \
     'Compose|AndroidView|KitePlayerView|OpenGL|GLES|Vulkan' \
     -g '*.kt' kiteplayer-sample-android/src
   ! rg -n \
     '(^|[^A-Za-z0-9_.])ffmpeg[.]|(^|[^A-Za-z0-9_])(lib(av(codec|format|filter|util|device|resample)|sw(scale|resample))|cnames[.]structs[.](AV|Sws|Swr)|av(codec|format|filter|util|device|io|resample)?_[A-Za-z0-9_]+|sws_[A-Za-z0-9_]+|swr_[A-Za-z0-9_]+|(AV|Sws|Swr)[A-Z][A-Za-z0-9_]+|SW(S|R)_[A-Z0-9_]+)' \
     -g '*.kt' kiteplayer-sample-android/src
   ```

   `AudioTrack` and `Surface` remain confined to output. The sample sees them only through
   `AndroidOutputBackend`, its caller-owned Surface and renderer constructor. The first scan's
   negative controls use a wildcard `import android.media.*`, a fully qualified
   `android.media.MediaCodec` reference and an `AMediaExtractor` token; the second adds one
   forbidden view import. The direct-native scan intentionally does not ban the required
   `io.github.yuroyami.kiteplayer.ffmpeg` project-package imports. Its controls separately add
   `import ffmpeg.*`, `import ffmpeg.ffkmp_packet_clone`, `ffmpeg.kc_init()`,
   `ffmpeg.KC_JVM_OK`, `ffmpeg.KITECODEC_C_ABI_MINOR`, bare `av_frame_alloc()` and
   `swr_convert()`, one `cnames.structs.AVFrame` reference and the `libswresample` library token;
   every control must fail while imports of both `KiteCodecMediaBackend` and `SoftwareConverter`
   through the required project package remain accepted. Observe each failure, then revert it.
10. Update root and sample docs to the measured state: Android debug and minified release run on
    `Pixelu16KB` at 16 KiB, arm64-v8a is runtime-qualified, x86_64 is package-qualified, Codec is
    Maven-local/private only, and this app uses three project dependencies until S1.d. Promote the
    official Android support label from T1 API to T2 Codec. Record the separately measured
    AudioTrack and Surface output as provisional stage evidence, not T3-Full: subtitles and the
    full lifecycle/format qualification required by that tier do not exist yet. Do not claim
    physical Android, the full 17.5 matrix, reusable views, Compose, one Player coordinate, public
    publication or zero-copy Android video.

Gate. Tier 3, selected by the explicit T1-to-T2 Android support-tier promotion. Run Tier 2, both
APK inspections and both application smoke runs, then the standing supervised macOS negative and
media soaks exactly once:

```bash
KPRT_DEVICE_SOAK=1 KPRT_DEVICE_SOAK_MINUTES=10 \
  ./gradlew :kiteplayer-output:macosArm64Test \
  --tests '*RealTimeSoakTest*' --rerun-tasks -i
KPRT_DEVICE_SOAK=1 KPRT_DEVICE_SOAK_MINUTES=10 \
  ./gradlew :kiteplayer-ffmpeg:macosArm64Test \
  --tests '*RealTimeMediaSoakTest*' --rerun-tasks -i
```

Do not republish KiteCodec or Player. Run both Tier 1 blocks last, then reread every changed file
against this fence. The Tier 3 run satisfies the standing promotion trigger; it does not elevate
Android past T2 Codec or turn emulator/device-soak evidence into T3-Full.

Commit first line. KitePlayer: `Add the runnable Android phone sample`.

S1.c exits only when all six product sub-phases have their named local commits and log entries,
both trees are clean, the one window-2b publication still links the Apple scratch consumer, the
ordinary Codec Android consumer and both sample APKs pass, `Pixelu16KB` reports 16 KiB and runs
decode/audio/Surface/seek/teardown, Android is labelled T2 Codec with provisional output evidence
below T3-Full, the promotion-triggered Tier 3 gate passes, and every deviation is in section 14.
x86_64 remains compile/link/package only. The absent physical Android remains an explicit S1.e
blocker. Nothing is pushed, publicly published or released.

### 17.4.4 The S1.d register and sub-phases, decision complete

Authored 2026-08-12 by the planner against clean KitePlayer `c2716cf` and KiteCodec `52a3e5d`,
immediately after the S1.c exit, at the owner's direction. The owner's instruction changes the
stage's scope in one way: S1.d delivers the native views, the baseline interop Composable AND the
Compose-true KiteVideo core. That pulls the core of KV-1 (17.9) forward from S2 into S1.d as an
explicit stage rider under 17.1's refinement, fed the only way S1 can feed it: through the S1.c
software converter, which is the KV-4-shaped path. What does NOT move: every measurement exit
(per-frame cost and dropped frames on named devices stay S2), the YUV image path (KV-2, S2),
Apple zero-copy (KV-3, S2) and the desktop paths (KV-5, S3). The rider lands the API and the
three laws as code contracts, not the performance claims. KiteVideo's S1.d truth is "correct,
law-abiding and honest about its CPU cost", never "fast", and no README row may claim measured
smoothness before S2 measures it.

Expansion-authorship commit first line. KitePlayer:
`Expand the pluggable views stage with the owner's Compose rider`.

**Owner-fixed points (no executor judgment):**

- Compose Multiplatform `1.12.0-rc01` (the newest published plugin at authorship, verified
  against Maven Central metadata) with the Kotlin-bundled compose compiler plugin
  (`org.jetbrains.kotlin.plugin.compose` at the catalog's Kotlin version). Prerelease is
  acceptable by the owner's standing rule. If resolution or compilation fails against Kotlin
  2.4.10, step down one version at a time toward 1.11.1 and record the landing version in the
  log; do not burn hours patching an rc.
- The interop wrapper and KiteVideo live in ONE module, `:kiteplayer-compose`, exactly as the
  D-6 register text names it. The reusable platform views live in `:kiteplayer-phone`.
- Both new modules are library modules in full standing: root coordinates, `explicitApi()`,
  `abiValidation`, vanniktech publish and dokka, committed API dumps where the tooling produces
  them (the AGP KMP android target still produces none; record that honestly, never fake a dump).
- No new C, no KiteCodec change, no new FFmpeg symbol, no network. Window 2c remains the last
  Codec change inside S1. Nothing from KD (17.10) enters these commits; 18.3 rule 4 applies.
- `:kiteplayer-phone` API-depends on `:kiteplayer-ffmpeg` and `:kiteplayer-output`, so ONE
  implementation line gives a phone consumer the playable stack. `:kiteplayer-compose` depends on
  phone. `:kiteplayer-output` stays FFmpeg-free and Compose-free; `:kiteplayer-phone` stays
  Compose-free, so a plain-View consumer never pulls Compose.

Execution order is S1.d.0 through S1.d.4. Targets for both new modules are exactly `android`
(namespace per module, compileSdk 36, minSdk 24, `withHostTest {}`), `iosArm64` and
`iosSimulatorArm64`. No macOS, jvm-desktop or web target enters S1.d; those are S3 and S6 work.

#### S1.d.0 One verification sweep

Verify this expansion against the tree before any product edit: one full sweep, one consolidated
report, findings classified BLOCKING versus DESCRIPTIVE per the recalibrated S1.a.0 protocol.
The checkable claims: `settings.gradle.kts` carries `:kiteplayer-compose` commented and no
`:kiteplayer-phone` line yet; `AndroidSurfaceVideoRenderer(surface, convert)` and
`UIKitVideoRenderer(layer, convert)` are the public constructors with the
`presentedFrames`/`supersededFrames`/`failedFrames` counters; `SoftwareConverter.toRgba` takes a
`KiteCodecVideoFrame` on both jvmAndAndroidMain and nativeMain; `AndroidOutputBackend` and
`AppleOutputBackend` exist; `Backends(backend, output)` is the config shape;
`kiteplayer-ffmpeg`'s android block is compileSdk 36 minSdk 24; `Pixelu16KB` boots and reports
`getconf PAGE_SIZE` 16384.

#### S1.d.1 The phone aggregate and the two platform views

Files: `settings.gradle.kts`; new `kiteplayer-phone/build.gradle.kts`; new
`kiteplayer-phone/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/phone/PhoneBackends.kt`
and `PlayerViewBinding.kt`; new `src/androidMain/.../KitePlayerView.kt` and
`PhoneBackends.android.kt`; new `src/iosMain/.../KitePlayerUIView.kt` and `PhoneBackends.ios.kt`;
new `src/commonTest/.../PlayerViewBindingTest.kt`; the module's API dump; KPKMP execution log.

Steps.
1. Create the module with the four library plugins and the three targets above. commonMain:
   `api(project(":kiteplayer-ffmpeg"))`, `api(project(":kiteplayer-output"))`. The build-file
   comment states the aggregate's one job: one coordinate, two reusable views, zero policy.
2. `public fun phoneBackends(): Backends` as commonMain expect. Android actual returns
   `Backends(KiteCodecMediaBackend(), AndroidOutputBackend)`; iOS actual returns
   `Backends(KiteCodecMediaBackend(), AppleOutputBackend)`. This is convenience, not policy: a
   consumer may still assemble `Backends` by hand.
3. `internal class PlayerViewBinding` in commonMain: the one attach state machine both views
   drive, pure Kotlin, effects injected as functions (`createRenderer`, `attachToPlayer`,
   `detachFromPlayer`, `closeRenderer`). The law: a renderer exists exactly while a player is set
   AND the platform surface is ready. Surface teardown closes the renderer BEFORE the platform
   callback returns (the output renderers' close blocks until their worker is done, which is what
   makes that safe), then detaches. A player swap mid-surface closes the old renderer, detaches
   the old player, then builds fresh for the new one. Every transition is idempotent.
   commonTest pins: create/close pairing, close-before-detach ordering, swap mid-surface,
   idempotent double teardown, no renderer without both preconditions.
4. Android `KitePlayerView(context)`: a FrameLayout hosting one SurfaceView, SurfaceHolder
   callbacks driving the binding, `var player: KitePlayer?`, and read-only passthrough counters
   (`presentedFrames`, `supersededFrames`, `failedFrames`, zero with no renderer). The renderer is
   `AndroidSurfaceVideoRenderer(holder.surface) { SoftwareConverter.toRgba(it as KiteCodecVideoFrame) }`.
   No Compose import, no android.media import, no raw FFmpeg name; the S1.c.6 scan families keep
   applying to this module's sources.
5. iOS `KitePlayerUIView`: a UIView subclass, black background, one CALayer video sublayer whose
   frame follows `layoutSubviews` inside a CATransaction with actions disabled, window
   attachment driving the binding (`didMoveToWindow`: a window means surface-ready, nil means
   teardown), same `player` property and counters, renderer
   `UIKitVideoRenderer(videoLayer) { SoftwareConverter.toRgba(it as KiteCodecVideoFrame) }`.
6. Run `updateKotlinAbi` then `checkKotlinAbi` as two invocations; commit the new dumps. Where
   the plan's prose counts "five library modules" for the ABI gate, it now reads six.

Gate. Tier 1, then Tier 2 (selected by build scripts and platform Kotlin): compile the android
target and both iOS klibs, run the phone host tests. Link-level iOS proof needs the local FFmpeg
trees flag from settings.gradle.kts's own comment; run it if the trees are present, record SKIPPED
with the reason if not. Do not republish anything.

Commit first line. KitePlayer: `Add the phone aggregate with the two platform views`.

#### S1.d.2 The baseline Composable

Files: `gradle/libs.versions.toml`; `settings.gradle.kts` (activate the commented module line);
new `kiteplayer-compose/build.gradle.kts`; new
`kiteplayer-compose/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/compose/KitePlayerSurface.kt`;
new `src/androidMain/.../KitePlayerSurface.android.kt`; new
`src/iosMain/.../KitePlayerSurface.ios.kt`; the module's API dump; KPKMP execution log.

Steps.
1. Catalog: `compose-multiplatform = "1.12.0-rc01"` under versions; plugins
   `compose-multiplatform` (`org.jetbrains.compose`) and `kotlin-compose`
   (`org.jetbrains.kotlin.plugin.compose`, version.ref kotlin). Root build declares both
   `apply false` beside the existing plugin roster.
2. Create the module: the four library plugins plus the two Compose plugins, the three targets,
   commonMain `api(project(":kiteplayer-phone"))` plus `compose.runtime`, `compose.foundation`,
   `compose.ui`.
3. One expect Composable, the whole baseline surface:
   `@Composable public expect fun KitePlayerSurface(player: KitePlayer?, modifier: Modifier = Modifier)`.
   Android actual wraps `AndroidView` (factory builds a `KitePlayerView`, update assigns
   `player`, onRelease assigns null). iOS actual wraps `UIKitView` the same way over
   `KitePlayerUIView`. The KDoc says what D-6 says: this is the baseline path every player
   offers, the one that wins sustained fullscreen battery, and KiteVideo is the flagship beside
   it, not its replacement.
4. Boundary: `kiteplayer-phone/src` must contain no compose import (scan it), and
   `kiteplayer-output/src` stays clean under the standing S1.c scans.

Gate. Tier 1, then compile all three targets of the new module and the API dump ritual. A
Compose-plugin/AGP-KMP incompatibility discovered here is a BLOCKING finding to report with the
exact error, not something to hack around silently.

Commit first line. KitePlayer: `Wrap the phone views in one Composable`.

#### S1.d.3 KiteVideo core, the owner's rider

Files, all in `kiteplayer-compose`: new commonMain `KiteVideo.kt`, `KiteVideoState.kt`,
`KiteVideoRenderer.kt`, `VideoGeometry.kt`, `ImageBitmaps.kt` (expect); new androidMain
`ImageBitmaps.android.kt`; new iosMain `ImageBitmaps.ios.kt`; new androidHostTest
`KiteVideoRendererTest.kt` and `VideoGeometryTest.kt`; the API dump; KPKMP execution log.

Steps.
1. The three 17.9 laws land as code contracts. Law 1: the frame holder is a
   `MutableState<KiteVideoFrame?>` read at exactly one site, inside `drawBehind`, and the comment
   at that site says reading it during composition or layout is the bug that turns video into a
   recomposition storm. Law 2 is explicitly deferred: this rider converts to tightly packed RGBA
   through the same converter the platform views use, the KDoc names that as 17.9's stated
   last-resort, and KV-2 replaces it in S2. Law 3 has no Android path (KV-7 stays parked).
2. `public class KiteVideoState`: the internal frame state, `public val renderer: VideoRenderer`,
   and the three passthrough counters. `public fun rememberKiteVideoState(): KiteVideoState`
   remembers one and closes its renderer when it leaves composition.
3. `internal class KiteVideoRenderer`, the fourth instance of the proven renderer shape: one
   atomic newest-wins pending slot whose displaced frame is closed and counted, one conflated
   signal channel, one single-thread worker, `present` returns immediately, close blocks
   acceptance then joins the worker then drains the slot. The worker converts, builds an
   ImageBitmap, publishes a `KiteVideoFrame(bitmap, displayWidth, height, quarterTurn)` into the
   state. Internal constructor takes `convert`, `makeImage` and `publish` so host tests drive it
   with fakes; the public wiring supplies the real three.
4. `internal expect fun rgbaToImageBitmap(rgba: ByteArray, width: Int, height: Int): ImageBitmap`.
   Android actual: one ARGB_8888 `Bitmap` filled by `copyPixelsFromBuffer` (whose in-memory
   layout IS tightly packed RGBA), then `asImageBitmap()`. iOS actual: a Skia raster image over
   the bytes (RGBA_8888, opaque) converted with `toComposeImageBitmap()`. One allocation per
   published frame is the honest S1 cost; KV-2 owns removing it.
5. `@Composable public fun KiteVideo(state: KiteVideoState, modifier: Modifier = Modifier)`:
   a Box whose `drawBehind` reads the frame once, computes the integer aspect-fit rectangle with
   the same law the output renderers obey (pixel-aspect scales the stored width, a quarter turn
   exchanges the sides, the fit is centred and symmetric), rotates about the destination centre
   and draws with low filter quality. The geometry is a pure commonMain function.
6. Host tests pin the renderer's ownership truths (displaced frame closed and counted, convert
   failure counted without killing the worker, close order, publish called on the worker, byte
   validation refuses short buffers) and the geometry table (plain, anamorphic, quarter-turned,
   odd-pixel, degenerate).

Gate. Tier 1, all three targets compile, host tests green, API dump ritual. No performance claim
anywhere: the log entry records that per-frame cost is UNMEASURED until S2.

Commit first line. KitePlayer: `Land the Compose-true KiteVideo core as the stage rider`.

#### S1.d.4 The Android sample consumes the phone view

Files: `kiteplayer-sample-android/build.gradle.kts`; `SampleController.kt` and `MainActivity.kt`
as the wiring demands; the sample README and root README sentences that count its dependencies;
KPKMP execution log.

Steps.
1. Replace the three project dependencies with `implementation(project(":kiteplayer-phone"))`,
   which is the whole point of the aggregate: the settings.gradle.kts comment promised exactly
   this replacement.
2. Replace the private SurfaceView plus hand-built renderer with one `KitePlayerView`; use
   `phoneBackends()` for the config; read the smoke's presentation counters through the view's
   passthroughs. The surface-evidence key keeps its existing mechanism against the view's
   surface.
3. The S1.c.6 close-scan line evolves with the scope: `KitePlayerView` and the Surface renderer
   are now the sanctioned path, so the sample scan becomes a ban on
   `Compose|AndroidView|OpenGL|GLES|Vulkan|AndroidSurfaceVideoRenderer|android[.]view[.]Surface`
   in `kiteplayer-sample-android/src`, while the android.media and raw-FFmpeg scans run
   unchanged. Run all three with one planted control each, observe the failures, revert.
4. Re-run S1.c.6 step 8 verbatim on `Pixelu16KB`: debug and release smokes, the same eleven-key
   jq oracle, both must pass. A debug pass never excuses a release failure.

Gate. Tier 2 plus the two smoke runs plus the scans. Do not republish, do not re-run Tier 3 (no
support-tier promotion happens here; the sample changed lanes, not evidence class).

Commit first line. KitePlayer: `Consume the phone view from the Android sample`.

**S1.d exits** when the four product sub-phases have their named local commits and log entries,
both trees are clean, both new modules carry committed API dumps (with the android-target gap
recorded, not faked), the Android sample passes both smokes through the phone coordinate, and the
README states the new truth without overclaiming: reusable views exist on both phones, the
baseline Composable exists, KiteVideo's core exists with its cost unmeasured until S2. The iOS
host re-consumption, the 17.5 matrix runs and the owner device session remain S1.e, unchanged.

Estimates. S1.d.1 6 to 9 hours, S1.d.2 3 to 5, S1.d.3 8 to 12, S1.d.4 2 to 4: S1.d totals 19 to
30 focused hours. The rider moves 10 to 15 of S2's KiteVideo hours into S1.d, so S2 reads 105 to
150 and the whole-road total stays 710 to 1015: hours moved, not added.

### 17.4.5 The S1.e register and sub-phases, decision complete

Authored 2026-08-12 by the planner against clean KitePlayer `64e9ae1` and KiteCodec `52a3e5d`,
immediately after the S1.d exit. S1.e is the stage exit of S1: the iOS host re-consumed through
the phone coordinate, the 17.5 matrix grown once and run on both phone platforms, every measured
number written, and the owner device session. The owner device session CANNOT be executed by
this executor (physical iPhone and Android hardware plus signing are the owner's); it is
recorded as the stage's one open item, loudly, and everything else completes around it. S1's
public promise is then complete on the named simulator and the named emulator, with physical
devices the last unchecked box.

**Located substrate, verified at authorship.** The booted simulator is `Test iPhone 17`
(iOS 26.0, UDID 5DBA149A-E990-4197-8A7D-31E97658B568). `Pixelu16KB` is up at 16384. The S1.b
lesson stands: RemoteIO fails on a bare `simctl spawn --standalone` host, so anything driving an
audio DEVICE on the simulator needs the app host; the matrix deliberately drives the MediaBackend
SPI directly (KiteCodecSourceFactory, select, decode, seekToKeyframe, close), which needs no
output backend and therefore runs in the bare spawn host. The phone FFmpeg profile's software
decoder set is exactly `h264,hevc,vp8,vp9,av1,mpeg4,aac,mp3,opus,vorbis,flac,pcm_*,png,mjpeg,webp`
(BuildFFmpegTask.kt:351); FFmpeg carries no native software AV1 decoder and no dav1d is vendored,
so the AV1 row's verdict is MEASURED, not assumed, and a clean typed refusal is an acceptable
recorded outcome for it. The host ffmpeg CLI has libx264, libx265, libvpx-vp9, libaom-av1,
libsvtav1 and mpeg4 encoders, enough to grow every planned clip.

Execution order is S1.e.0 through S1.e.5. No new module, no KiteCodec change, no C. The one
public-API addition is a single diagnostic property on KitePlayerUIView (below), mirrored on
nothing else.

#### S1.e.0 One verification sweep

Claims to verify before edits: the simulator and emulator states above; testmedia/ holds the
S1.b-era clips including rotated90ccw.mp4, truevfr720.mp4, hevc4k10.mp4, tsoffset1400.ts,
subbed.mkv, surround51.mp4; KiteCodecSourceFactory, PlayerMediaSource.seekToKeyframe and the
decoder factories are public or same-package-visible from the module's own commonTest;
kiteplayer-ffmpeg's androidDeviceTest tree already runs on the emulator (S1.c.3's 37 device
tests); the sample framework builds against the local FFmpeg trees flag.

#### S1.e.1 Grow the matrix once, run it on the host

Files: `scripts/testmedia.sh`; new
`kiteplayer-ffmpeg/src/commonTest/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/FormatMatrix.kt`
(the table and the runner, plain classes, no @Test); new
`kiteplayer-ffmpeg/src/jvmAndNativeTest/.../FormatMatrixTest.kt` (the wrapper); KPKMP log.

Steps.
1. Grow testmedia.sh by exactly the 17.5 gaps, deterministic, appended once: `multitrack.mkv`
   (h264 + two audio languages + two SubRip tracks), `baseline.mkv` (plain h264+aac Matroska,
   the ordered-chapters-free baseline), `vp9.webm` (libvpx-vp9 + opus), `av1.mkv` (libsvtav1 +
   opus), `mpeg4part2.mp4` (mpeg4 part 2 + aac), `audio-aac.m4a`, `audio-mp3.mp3`,
   `audio-flac.flac`, and two torture cases produced from bytes, not encoders:
   `torture-truncated.mp4` (the first 40% of sync1080p30.mp4, which amputates the trailing moov)
   and `torture-garbage.mp4` (a deterministic non-media byte pattern with a media extension).
2. FormatMatrix.kt: one row per clip with an expected verdict. `MustPlay` means: opens, the
   expected stream kinds are present (asserted counts for the multitrack row, rotation degrees
   for the rotated row), N video frames decode (N small for the 4K row), an audio row decodes
   audio buffers instead, `seekToKeyframe` to mid-file returns a position and decoding resumes,
   close is clean. `MustSurvive` means: open and every subsequent call either succeeds or fails
   with a typed exception, nothing crashes or hangs (bounded by withTimeout), close of whatever
   opened is clean; AV1 and both torture rows are MustSurvive, and the ACTUAL outcome of every
   MustSurvive row is captured and logged per platform, so capability stays a measurement.
3. The wrapper test iterates every row and reports one line per row (clip, verdict, frames,
   landing) so a matrix run leaves a readable transcript. Run it on macosArm64Test first: the
   host run is the baseline that debugging happens against.

Gate. Tier 2 (test Kotlin under nativeTest paths). The new clips regenerate from the script
alone; nothing binary is committed. Tier 1 both repos.

Commit first line. KitePlayer: `Grow the format matrix and run it on the host`.

#### S1.e.2 Re-consume the iOS host through the phone coordinate

Files: `kiteplayer-sample/build.gradle.kts` (iosMain gains :kiteplayer-phone);
`kiteplayer-sample/src/iosMain/.../SampleViewController.kt`;
`kiteplayer-phone/src/iosMain/.../KitePlayerUIView.kt` and the phone API dump (one addition);
KPKMP log.

Steps.
1. Add to KitePlayerUIView the one diagnostic the S1.b oracle needs and any consumer may
   reasonably ask: `public val hasPicture: Boolean` (true when the video layer holds contents).
   Update the phone ABI dump by ritual.
2. Rewire SampleViewController: the hand-built CALayer and UIKitVideoRenderer go away; the
   controller hosts one KitePlayerUIView sized to its bounds, builds its player from
   `phoneBackends()`, and assigns `view.player`. The smoke's counters read the view's
   passthroughs; `layerImage` reads `hasPicture`. Oracle keys and thresholds stay EXACTLY
   S1.b's; only the wiring under them changed.
3. Rebuild the simulator framework against the local trees and run the smoke on the booted
   simulator by the S1.b recipe (kiteplayer-sample/iosApp README); read s1b-smoke.json and
   assert its keys as S1.b did: seek landed in 5000..5034, terminal Ended, decoded and presented
   positive, layerImage true, teardown completed.

Gate. Tier 2. Do not touch the macOS sample path. Tier 1 both repos.

Commit first line. KitePlayer: `Consume the phone view from the iOS host`.

#### S1.e.3 The matrix on the iOS simulator

Files: `kiteplayer-ffmpeg/build.gradle.kts` (simulator test tasks must see the media directory:
`simctl spawn` forwards only SIMCTL_CHILD_-prefixed variables, so the existing
KITEPLAYER_TESTMEDIA line gains a SIMCTL_CHILD_KITEPLAYER_TESTMEDIA twin); KPKMP log.

Steps.
1. `./gradlew :kiteplayer-ffmpeg:iosSimulatorArm64Test --tests '*FormatMatrix*'` with the local
   FFmpeg trees flag, on the booted `Test iPhone 17`. The whole nativeTest suite is NOT the
   subject; the matrix class is (RemoteIO-hosted suites keep their S1.b app-host recipe and are
   out of this fence).
2. Record every row: verdict, frames decoded, seek landing, and the MEASURED outcome of the
   three MustSurvive rows. A hang is a finding, not a retry.

Gate. The matrix run itself plus Tier 1. If simctl refuses environment forwarding, the fallback
is the documented relative-path convention run from the repository root; record which path fed
the binary.

Commit first line. KitePlayer: `Run the format matrix on the iOS simulator`.

#### S1.e.4 The matrix on the Android emulator

Files: new `kiteplayer-ffmpeg/src/androidDeviceTest/.../FormatMatrixDeviceTest.kt` (thin
wrapper: media directory = the instrumentation package's external files dir); KPKMP log.

Steps.
1. Push the grown testmedia set once:
   `adb -s emulator-5554 push testmedia/. /storage/emulated/0/Android/data/<testAppId>/files/testmedia/`
   after the test APK is installed (the directory belongs to the test package; no permission is
   involved). `<testAppId>` is whatever the installed instrumentation reports, verified, not
   assumed.
2. Run the device matrix test on Pixelu16KB and record every row exactly as S1.e.3 does. The 4K
   HEVC row decodes its small N in software on an emulator; slow is acceptable, a hang is not
   (the same withTimeout bounds it).

Gate. The device run plus Tier 1. Do not re-run the S1.c Tier 3 soaks; no support tier moves in
this sub-phase.

Commit first line. KitePlayer: `Run the format matrix on the Android emulator`.

#### S1.e.5 Stage exit

Files: root `README.md`; KPKMP log; nothing else.

Steps.
1. Write the exit log entry with EVERY measured number: both platforms' matrix row results, the
   re-run iOS smoke's numbers, and the S1.d.4 Android smoke numbers it joins.
2. README: the support rows gain the matrix as evidence where it ran; no tier moves (the tier
   ladder's promotion rules are untouched by S1.e); the blockquote keeps saying nothing is
   published.
3. Record the stage's one open item in the log and README exactly as it is: the owner device
   session (physical iPhone and physical Android, owner signing) has not happened, S1's "IT
   PLAYS ON PHONES" is proven on one named simulator and one named emulator, and the physical
   half is waiting on the owner, not on code.

Gate. Tier 1 both repos, final reread of every changed file against this fence.

Commit first line. KitePlayer: `Close the phone stage on simulator and emulator evidence`.

**S1.e exits** when the five product sub-phases have their named commits and log entries, both
trees are clean, the matrix has one committed table and two phone-platform transcripts in the
log, the iOS host consumes the phone coordinate with its S1.b oracle green, and the owner
session is the stage's only open item, stated in log and README. Estimates: S1.e.1 4 to 6,
S1.e.2 3 to 5, S1.e.3 2 to 4, S1.e.4 2 to 4, S1.e.5 1 to 2: 12 to 21 focused hours, inside the
17.3 envelope.

### 17.4.6 The Android rider package: KV-4's core, owner-directed

Authored 2026-08-12 by the planner against clean KitePlayer `770187f` and KiteCodec `52a3e5d`,
at the owner's direction: work the Android-only parts of the remaining road now, defer the
Apple, desktop and cross-platform stages. The only Android-only package on the road is KV-4
(17.9, homed S3) plus the parked KV-7 research whose declared judge is KV-4's measurements.
This rider pulls KV-4's CORE forward under 17.1's refinement: the frame-cost instrumentation,
the Android image reuse that removes the per-published-frame allocation, and one measured
KiteVideo run on the named emulator. What does NOT move: the launchable modifier demo
application and the physical-device numbers stay in S3's exit rider and the owner's device
session; KV-7 stays parked, and its go/no-go note is written at S3 entry using the numbers this
rider produces. Emulator numbers are provisional stage evidence, never device truth, and every
doc that quotes them says so.

Execution order is A1 through A3, then the closing entry. No KiteCodec change, no new module,
no engine change; every edit lives in `:kiteplayer-compose` and its device-test tree.

#### A1 Frame-cost instrumentation

Files: `kiteplayer-compose/src/commonMain/.../KiteVideoRenderer.kt`, `KiteVideoState.kt`; new
commonMain `KiteVideoFrameCost.kt`; androidHostTest `KiteVideoRendererTest.kt` (new arms); the
module's API dump; KPKMP log.

Steps.
1. The worker measures each published frame's conversion-plus-image-build with the monotonic
   time source and folds it into four numbers behind one lock-free shape: samples, last,
   average, worst, all in nanoseconds. Failed and superseded frames are not samples.
2. `public class KiteVideoFrameCost` (samples, lastNanos, averageNanos, worstNanos) and
   `public val KiteVideoState.frameCost: KiteVideoFrameCost` snapshotting those atomics. The
   KDoc states what the number is (CPU cost of the S1 software path per published frame) and
   what it is not (draw cost, GPU cost, or a device claim when measured on an emulator).
3. Host-test arms: samples count only published frames, worst is monotone, average within
   bounds, zero-sample snapshot is all zeros.

Gate. Tier 1; module host tests; API dump ritual.
Commit first line. KitePlayer: `Measure the KiteVideo frame cost`.

#### A2 The Android image ring

Files: commonMain `ImageBitmaps.kt` (the seam becomes a small pool type), `KiteVideoRenderer.kt`
(holds one pool per renderer); androidMain `ImageBitmaps.android.kt`; iosMain
`ImageBitmaps.ios.kt`; androidHostTest (new arms through the injected seam); KPKMP log.

Steps.
1. The stateless `rgbaToImageBitmap` becomes `internal expect class FrameImagePool` with one
   method (`imageFor(rgba, width, height): ImageBitmap`) and a `release()`. One pool per
   renderer, released in close after the worker joins.
2. Android actual: a three-image ring of ARGB_8888 bitmaps reused while dimensions match,
   rebuilt on a size change, filled by `copyPixelsFromBuffer` exactly as before. Three, not
   two, because the image just published may still be in HWUI's async draw while the worker
   fills the next; the ring depth is the standing assumption to re-examine at S3 with device
   numbers. The KDoc carries that reasoning.
3. iOS actual: unchanged behaviour behind the new shape (one Skia raster per frame; KV-2 owns
   Apple).
4. Host tests keep driving the renderer through the injected `makeImage` seam, plus new arms
   pinning that the renderer asks the pool rather than allocating, and that close releases it.

Gate. Tier 1; module host tests; all three targets compile; API dump unchanged or moved by
ritual.
Commit first line. KitePlayer: `Reuse the Android KiteVideo images through a ring`.

#### A3 The measured emulator run

Files: `kiteplayer-compose/build.gradle.kts` (device-test builder plus its dependencies); new
`src/androidDeviceTest/AndroidManifest.xml`, `KiteVideoTestActivity.kt`,
`KiteVideoDeviceTest.kt`; KPKMP log.

Steps.
1. Give `:kiteplayer-compose` the same device-test shape the sibling modules use
   (`withDeviceTestBuilder { sourceSetTreeName = "test" }`, AndroidJUnitRunner, androidx test
   plus activity-compose in the device tree only).
2. The activity hosts `KiteVideo(state)` under real Compose modifiers (a rounded-corner clip
   and a graphicsLayer rotation), because modifiers applying to the video is the whole D-6
   claim.
3. The device test builds a player from `phoneBackends()`, attaches `state.renderer`, plays the
   pushed conformance clip to Ended, then asserts and logs the measured truth: published frames
   positive, cost snapshot populated, superseded counted honestly, terminal Ended, teardown
   clean, and the KV4-tagged logcat lines carry every number. Media arrives by the S1.e.4
   recipe (push after install, chown per the recorded FUSE truth, am instrument directly).
4. The log entry records the numbers and names them EMULATOR numbers.

Gate. Tier 2 (build scripts and platform Kotlin changed) plus the device run plus Tier 1. No
soak, no tier move.
Commit first line. KitePlayer: `Measure KiteVideo on the Android emulator`.

**The rider exits** when the three sub-phases have their named local commits and log entries,
both trees are clean, and the S3 register can point at real KV-4 numbers instead of assumptions.
Estimates: A1 2 to 3 hours, A2 2 to 4, A3 3 to 5: 7 to 12 focused hours, moved out of S3's
KiteVideo slice (S3 reads 70 to 108 after the move; road total unchanged).

### 17.4.7 The S4 register and sub-phases, decision complete

Authored 2026-08-12 by the planner against clean KitePlayer `deaf4ce` and KiteCodec `52a3e5d`,
at the owner's direction: "Do all of S4", executed BEFORE S2 and S3. The stage law permits it
(stages are outcome-named; prerequisites live inside the stage needing them), and the one
cross-stage consequence is recorded as an owner decision: KD-4 and KD-5's C funnels were homed
in KiteCodec window 3 (S2); running S4 first pulls that window's KD half forward as its own
KiteCodec window, full S1.a.7-style ritual, VERSION 0.0.3 beside the untouched 0.0.2. The
VideoToolbox half of window 3 stays in S2, untouched.

S4's whole scope, from the 17.2 register: subtitles per old B3, the debuggability register,
facade completion from old B11, and KD (17.10). The honest size note stands: this is the
largest remaining stage (90 to 125 hours), and its sub-phases land in engineering order, each
with its own commit and log entry, so an interruption always leaves a clean, continuable tree.

**Owner-fixed points (no executor judgment):**

- The renderers that exist are the compositing targets: the Apple layers, the Android Surface
  renderer, and KiteVideo. No Metal work enters S4 (that is S2); overlays composite on the
  software paths and their contracts must survive S2 unchanged.
- Subtitle text rasterisation uses each platform's own text engine behind ONE seam (Android
  Canvas/StaticLayout, Apple CoreText through CGBitmapContext), because shipping a font
  rasteriser is libass's job (S4.f), not the text path's.
- The subtitle model stays in `kiteplayer-subtitles` (pure Kotlin: parsers, cue selection,
  layout arithmetic); platform raster lives in `kiteplayer-output`; the engine learns cue
  TIMING only. The dependency arrow still never points into the core.
- SRT-plus-WebVTT alone is never called subtitle support (old B3's law). The stage exit
  labels the text path exactly that: the TEXT path, with ASS/bitmap state stated beside it.
- B11's network-adjacent items stay parked with 17.8 (external URL tracks over the network).
  External tracks land for LOCAL files. Editions and programs land as typed API over what the
  container reader exposes; where KiteCodec exposes nothing, the API is typed-rejected per the
  truth-ledger rule, never silently empty.

Execution order: S4.a KD Kotlin, S4.b the C funnels window, S4.c subtitles text path, S4.d
debuggability, S4.e facade completion, S4.f the ASS/bitmap half, S4.g exit.

#### S4.a The KD Kotlin slices (KD-1, KD-2, KD-3, KD-6, KD-8)

Files, KiteCodec: new `kitecodec-core/src/commonMain/kotlin/io/github/yuroyami/kitecodec/dsl/`
(`FilterDsl.kt`, `FilterEscaping.kt`, `DecoderOptions.kt`, `EncoderTuning.kt`); the openDecoder
path gains one typed options parameter threaded to the EXISTING `ffkmp_codecctx_set_opt` funnel
between context creation and open (one Kotlin injection point per platform actual, no new C);
new `kitecodec-core/src/commonTest/.../KdGoldensTest.kt` (KD-8); api dumps; README row.
Files, KitePlayer: new `kiteplayer-core/src/commonMain/.../PlaybackProfile.kt` (KD-6) compiling
into PlayerConfig plus decoder options; the ffmpeg backend threads profile decoder options into
its openDecoder calls; api dumps; log.

Steps.
1. KD-1 exactly as 17.10 registers it: typed builders (video scale, crop, pad,
   transpose/rotate, fps, format, eq, yadif/bwdif, drawbox; audio volume, atempo, aresample,
   pan, aformat, loudnorm; raw), one centralised escaping function, compiles to the existing
   buildVideo/buildAudio strings. Law 4: the compiled description is a value a caller can
   print.
2. KD-2: `DecoderOptions(skipLoopFilter, skipFrame, errDetect, threadType, options)` applied
   through the existing funnel; a wrong key must reproduce the measured EINVAL path.
3. KD-3: typed encoder knobs (crf, preset, profile, tune, rate control, gop) compiling INTO
   the existing options maps; contradictory combinations refuse typed (CRF plus CBR).
4. KD-6: `PlaybackProfile.Scrubbing/LowLatency/Battery` as data, compiled configuration
   golden-tested; the player's backend threads the profile's decoder options.
5. KD-8: every golden in one host suite per repo, exact strings pinned including escaping and
   the 2048-byte bound refusal.
6. Publish KiteCodec 0.0.3 locally (phone superset, same one-publication rule as window 2b);
   Player consumes 0.0.3.

Gate. Both repos' host suites, KiteCodec four-variant C suites untouched (no C), api dump
rituals, Tier 1 both repos, one end-to-end real-media filter run reusing the existing
FilterGraph tests.
Commit first lines. KiteCodec: `Compile typed FFmpeg control onto the existing funnels`.
KitePlayer: `Give the player its playback profiles`.

#### S4.b The two C funnels, a KiteCodec window (KD-4, KD-5)

Files, KiteCodec: `native/kitecodec-c/include/kitecodec_helpers.h` and `src/helpers_format.c`
(`ffkmp_fmt_open_input2(out, path, keys, values, n)` applying pairs between alloc and open;
`ffkmp_fmt_chapter_count(fmt)`, `ffkmp_fmt_chapter_get(fmt, i, out_id, out_start_us,
out_end_us)` plus dict reuse for chapter metadata); guard suite NULL arms; signature and export
baselines plus the audit count assertions; KITECODEC_C_ABI_MINOR bump; the JNI rows for the new
funnels in methods.def plus kj_format.c; Kotlin commonMain `Chapter`, `MediaInfo`, and
`MediaSource.open(path, openOptions)`; klib metadata re-baseline; api dumps.
Files, KitePlayer: 17.5 gains a chaptered fixture (`chapters.mkv`, exact bounds asserted in the
matrix); `KiteCodecSource.chapters` stops being the documented empty list and maps the real
table; log.

Steps. The full S1.a.7-style ritual, no shortcuts: prototypes and NULL-arm tests first, the
four C suite variants (plain, asan, tsan, interpose), baselines moved by ritual with counts
updated at every site, JNI rows following kj_abi.c's canonical pattern, one local 0.0.3a
publication is NOT made (0.0.3 from S4.a is re-published complete with the funnels; exactly one
new coordinate this stage), the Apple scratch consumer and the Android consumer must still
link, and the chaptered fixture must round-trip exact chapter bounds on the host matrix run.
Gate. KiteCodec Tier 2 equivalents plus both consumers; KitePlayer matrix row on the host;
Tier 1 both repos.
Commit first lines. KiteCodec: `Open with options and read the chapter table`.
KitePlayer: `Read chapters through the source`.

#### S4.c Subtitles, the text path, end to end

Files, KitePlayer: `kiteplayer-subtitles` gains `WebVttParser.kt`, `CueSelector.kt` (active-set
by time, overlap policy, seek reconstruction = selector is a pure function of (cues, time)),
`CueLayout.kt` (line breaking arithmetic against a measured-width callback, bottom-up regions,
user style precedence: accessibility wins, then user override, then authored, then defaults);
`kiteplayer-core` learns subtitle TIMING: the session loop reads the selected subtitle stream's
packets, drives a `SubtitleDecoder` (the SPI exists unused today), holds the active cue set,
publishes `SubtitleOverlay` to the attached renderer on cue-set changes and viewport changes
(never per frame; cues change about once a second, 9's measured law); `TrackKind.Subtitle`
auto-selection honours forced and accessibility dispositions; `kiteplayer-ffmpeg` implements
`SubtitleDecoderFactory` for srt/subrip and webvtt PACKETS (text payloads through the existing
packet path, parsed by the subtitles module: no new C); `kiteplayer-output` gains the ONE text
raster seam (`SubtitleRasterizer`: Android StaticLayout into a Bitmap's pixels, Apple CoreText
into a CGBitmapContext) producing `RgbaBitmap`s for `OverlayImage`; the three renderers'
`setOverlay` become real compositors (draw overlay images after the picture, skip re-upload on
unchanged contentHash); KiteVideo draws the overlay in the same drawBehind after the frame.
Matrix: `subbed.mkv` grows a WebVTT sibling and the matrix asserts a cue is DECODED (not
drawn) on every platform; drawn proof is the device tests below.

Steps in that order, each with host tests (parser goldens incl. BOM/CRLF/overlap/malformed,
selector properties incl. seek reconstruction, layout arithmetic against a scripted measurer,
renderer compositing through the existing canvas seams) plus one Android device test asserting
non-black overlay pixels above the picture and one simulator assertion of layer contents, and
the engine's virtual-clock tests for cue timing (cue appears and disappears at its bounds,
seeks rebuild the active set exactly).
Gate. Tier 2 plus the two device/simulator proofs; Tier 1 both repos.
Commit first lines, one per landing: `Parse WebVTT and select cues by time`,
`Time subtitle cues in the engine loop`, `Decode subtitle packets in the FFmpeg backend`,
`Rasterise and composite subtitle overlays`.

#### S4.d The debuggability register (with KD-7)

Files, KitePlayer: new `kiteplayer-core/src/commonMain/.../Diagnostics.kt`
(`KitePlayer.diagnosticsDump(): String`: config as resolved, backends by name, tracks and
selections, the state/stats/progress snapshot, warning history, and every compiled KD artifact
attached to the session: filter strings as sent, option pairs as applied with their per-key
answer, the active profile = KD-7); a bounded warning HISTORY on the facade (the existing
warning flow gains a replayed, capped log the dump prints); the logging policy stated in code
(one `KiteLog` seam, silent by default, pluggable sink, never printing on its own: the policy
is a documented contract, not a framework); the typed warning audit (every `PlaybackWarning`
emission site enumerated in one table test that fails when a new warning ships undocumented);
`docs/spi-cookbook.md` with a WORKED custom backend: a complete, compiling test-fixture backend
(the scripted container the core tests already use, promoted to a documented example).
Gate. Host tests (dump golden with a scripted session, warning audit table), Tier 1.
Commit first line: `Let the player explain itself`.

#### S4.e Facade completion (old B11, the local scope)

Files, KitePlayer core plus backend. The decided list, each typed-implemented or
typed-rejected, never silent: playlist/queue with `LoopMode.All` unlocked; chapters surfaced on
the facade from S4.b's `MediaInfo` (`chapters`, `chapterAt(position)`, seek-to-chapter); frame
stepping (`stepFrame()` while paused, precise-seek plus one-frame decode, video-only media
included); typed filter attachment on open (KD-1 constructs compiled and handed to the
backend's existing graph path; runtime hot-swap stays a documented non-goal); external LOCAL
subtitle files (`MediaItem.externalSubtitles`, parsed by the subtitles module, timed by the
same engine path); screenshots (`captureFrame(): SoftwareReadableFrame` from the newest
presented frame, the documented use of that interface); support bundle
(`supportBundle(): String` = diagnosticsDump plus platform and version block, redaction rule:
paths trimmed to basenames); the option escape hatch with unused-option REPORTING (openOptions
echoed per key: applied, rejected, unused); editions/programs typed-REJECTED with the ledger
sentence naming what the container reader does not expose; the API truth ledger swept: every
public member implemented, typed-rejected, or deleted, one table in the log entry.
Gate. Host suites for each feature (virtual-clock playlist transitions, step determinism,
screenshot pixel assertions through the fake seams), one real-media run each for stepping and
screenshots on the host, Tier 1.
Commit first lines, one per landing: `Play a queue and loop it`, `Step frames and capture
pictures`, `Surface chapters and external subtitles`, `Finish the facade truthfully`.

#### S4.f The ASS and bitmap half of old B3

The honest register, decided now, expanded at ITS entry (the one sub-phase big enough to carry
its own expansion, exactly as stages do): vendored libass builds per platform behind the
KiteCodec build machinery's pattern (new third-party provisioning, macOS first, then iOS and
Android); a `kiteplayer-subtitles-libass` module implementing the same renderer-overlay
contract; FFmpeg bitmap subtitle decode (PGS, VobSub, DVB) which REQUIRES a new C surface for
AVSubtitle (a KiteCodec window with the full ritual; register change per 17.10 law 3, named
here so it is never an improvisation); the reference corpus (scripts, bidi, karaoke, overlaps,
palettes, seeks) matched against pinned libass output; HDR-aware composition deferred to S2's
colour work by construction (composition happens before tone mapping today and the exit says
so). S4.f EXECUTES AFTER S4.a-e and may close as PARTIAL with its state recorded: old B3's
exit is the corpus match, and the stage exit reports exactly how far that got.
Commit first lines at its entry expansion.

#### S4.g Stage exit

Every measured number in the log; the matrix re-run where fixtures grew (host at minimum,
phones for the subtitle decode row); README: subtitles described as exactly what they are
(text path end to end; ASS/bitmap state as measured), the debuggability and facade truths, the
KD surface documented; the API dumps clean; both trees clean; nothing pushed.
Commit first line: `Close the stage that explains itself`.

Estimates. S4.a 10 to 15, S4.b 5 to 8, S4.c 25 to 35, S4.d 8 to 12, S4.e 20 to 30, S4.f 25 to
35, S4.g 2 to 4: 95 to 139 focused hours, consistent with the 17.3 row (90 to 125) within its
own error bars; the table is not re-litigated mid-stage.

### 17.4.8 The S2 register and sub-phases, decision complete

Authored 2026-08-12 by the planner against KitePlayer `885ccc0` and KiteCodec `613766d`, at the
owner's direction: "do s2 fully", given while S4 stood mid-S4.c (landings 1 to 3 committed, the
Android half of landing 4 committed as `885ccc0`, the Apple and KiteVideo halves open). The
stage law permits the reordering; three owner decisions are recorded so nothing is implicit:

1. S4 PAUSES. S4.d to S4.g stay S4 work and resume after S2's exit, against the tree S2
   leaves behind.
2. S4.c landing 4's Apple and KiteVideo halves ride S2 as explicit riders. They are renderer
   work; writing their compositing twice, once onto the CG layers and once onto Metal weeks
   later, is the kind of duplicated throwaway the stage law exists to prevent. The landing's
   device proofs (non-black overlay pixels on the phone targets) return to S4.c when S4
   resumes.
3. The premise is recorded honestly: S2 removes the CPU blit on APPLE. Android's GPU story
   remains KV-7, parked, judged at S3 entry with KV-4's measured 128.3 ms as the number to
   beat. The owner ordered S2 knowing this.

S2's whole scope, from the 17.2 register: the Metal renderer on macOS and iOS (old draft
C-09 to C-31: plane targets, texture pools, stride discipline, presentation feedback, vsync
snapping, the colour instrument), VideoToolbox inside FFmpeg per D-2 with measured software
fallback (C-33, C-48 to C-50), sustained 4K runs with committed thresholds, and KiteVideo's
first landing (KV-2, KV-3, and KV-1's measurement exits; KV-1's core landed in S1.d by the
17.4.4 rider).

**Owner-fixed points (no executor judgment):**

- ONE Metal core in appleMain, hosted by thin platform layers. The CG renderers
  (AppKitVideoRenderer, UIKitVideoRenderer) stay exactly as they are: they are the measured
  software fallback, their contracts do not change, and no consumer is forced to migrate.
- VideoToolbox is an HWACCEL behind the standard `h264`/`hevc` decoders, not a named decoder
  the way `h264_mediacodec` is. Selection therefore happens at the codec context (a device
  context attached between creation and open), and D-2's fallback discipline extends the
  existing replay driver rather than forking it. The 16 MiB retention law and the pts-based
  boundary confirmation from `8a47d35` apply unchanged.
- Pixels never enter Kotlin memory on the hardware path. A hardware frame crosses as an
  opaque handle; the renderer reads it through CVMetalTextureCache. The only per-frame
  crossings are the ones that exist today.
- Zero-copy is CLAIMED only where measured. Simulator numbers are provisional by definition
  and labelled so in the log, exactly like A3's emulator numbers.
- KV-3 is a SPIKE with a committed fallback: if Skiko's public API cannot adopt a Metal
  texture into an Image, KiteVideo keeps its upload path, the exact API gap is named in the
  log, and the stage exit states law 3's Apple status truthfully. The stage does not block
  on Skiko.
- Shaders are MSL source strings compiled at runtime through the Metal API. No .metallib
  toolchain enters the build; there is nothing to provision and nothing new for a consumer
  to carry.
- KiteCodec work is window 3's VideoToolbox half exactly as 17.3 homed it, full S1.a.7-style
  ritual, VERSION 0.0.4, one new coordinate, local publication only, nothing pushed.

Execution order: S2.a the KiteCodec window, S2.b the player's D-2 integration, S2.c the Metal
renderer, S2.d the KiteVideo GPU path, S2.e colour and 4K, S2.f exit.

#### S2.a KiteCodec window 3: VideoToolbox decode behind the opaque boundary

Files, KiteCodec: `buildSrc/src/main/kotlin/BuildFFmpegTask.kt` (`appleHardwareArgs()` gains
`--enable-hwaccel=h264_videotoolbox,hevc_videotoolbox`; the mobile Apple profile gains
VideoToolbox DECODE for device and simulator, encoders stay desktop-only per the existing
comment); `native/kitecodec-c/include/kitecodec_helpers.h` plus a new `src/helpers_hwaccel.c`:
`ffkmp_codecctx_use_videotoolbox(ctx)` (creates the AV_HWDEVICE_TYPE_VIDEOTOOLBOX device
context, attaches it, installs the get_format callback preferring the VideoToolbox format,
returns 0 or the AVERROR; a non-Apple build returns ENOSYS from its own `#if`),
`ffkmp_frame_hw_download(src, out)` (av_hwframe_transfer_data plus av_frame_copy_props, the
measured software download), `ffkmp_frame_cv_pixel_buffer(frame)` (the CVPixelBufferRef as an
opaque pointer on Apple, NULL elsewhere), and the existing hw_frames_ctx predicate promoted to
the exported surface if it is not already there. Guard suite NULL arms; the doctored-header
rule holds (tests speak `kc_` types); signature, exported-symbols and klib baselines moved by
ritual with counts updated at every site; `KITECODEC_C_ABI_MINOR` 3 to 4; JNI parity rows in
`methods.def` following the canonical pattern (on macOS JVM they WORK, because the C links
VideoToolbox there; on other JVM platforms they return the same typed ENOSYS, so the bridge
surface never forks). Kotlin commonMain: a typed hardware-decode request on the decoder-open
path (Apple natives real, elsewhere typed-rejected per D-5's capability honesty), and the
frame surface gains isHardware, downloadToSoftware and the pixel-buffer handle.
Steps: prototypes and NULL arms first, then the four C suite variants (plain, asan, tsan,
interpose), then the Kotlin actuals, then 0.0.4 published locally (phone superset preserving
the Apple variants), then both scratch consumers must still link.
Gate: KiteCodec Tier 2 equivalents, api dump rituals, four C variants green.
Commit first line, KiteCodec: `Decode through VideoToolbox behind the opaque boundary`.

#### S2.b VideoToolbox in the player, per D-2, with measured fallback

Files, KitePlayer: `kiteplayer-ffmpeg` decoder selection grows an Apple axis (the nativeMain
actual stops returning bare software: on Apple targets h264/hevc map to the VideoToolbox
request under the same policy table, driven by the existing exhaustive common test);
`KiteCodecSource` opens the hw path when selected and maps frames honestly: a hardware frame
becomes `PlayerPixelFormat.Opaque` with `HwSurfaceKind.CoreVideoPixelBuffer` when the sink
renderer accepts that surface, and is downloaded to NV12 (HwdecStatus.HardwareWithDownload)
when it does not; `DecoderFallback` gains the VideoToolbox arms (open refusal and mid-stream
refusal), red-tested against the unfixed path first exactly like `8a47d35`; HwdecStatus
reported as Hardware, HardwareWithDownload or Software, never optimistically. Player consumes
0.0.4.
Gate: ffmpeg suites on every host, fallback suite grown and green, macOS host matrix re-run
(must-play rows through VideoToolbox where the renderer path exists, download path
elsewhere), Tier 1.
Commit first line: `Select VideoToolbox on Apple and fall back with proof`.

#### S2.c The Metal renderer on macOS and iOS

Files, KitePlayer: new `kiteplayer-output/src/appleMain/.../MetalVideoRenderer.kt` plus its
shader source and geometry support; thin hosts in macosArm64Main and iosMain (layer
attachment and the display link); `AppleSubtitleRasterizer.kt` in appleMain (CoreText through
CGBitmapContext into RgbaBitmap, the S4.c Apple rider) wired as
`AppleOutputBackend.subtitleRasterizer`; `setOverlay` made real on the CG renderers too (the
same newest-wins slot and draw-above-picture law as `885ccc0`); the phone and sample view
paths choose Metal where a CAMetalLayer is available, CG remains the fallback.
Content, in the old draft's own words made concrete: plane targets (NV12, YUV420P, P010,
BGRA), texture pools (CVMetalTextureCache for hardware frames, a stride-respecting
MTLTexture ring for software planes), stride discipline (row-by-row uploads honouring
planeStride, the classic skew bug pinned by a test), colour matrix selection from
ColorSpaceInfo (601/709/2020, studio and full range) in the fragment shader, rotation in the
vertex transform, the same letterbox geometry law the existing renderers obey, vsync-snapped
scheduling (CADisplayLink on iOS, the display link or layer pacing on macOS), presentation
feedback (presented, superseded, failed counters, same names as every other renderer),
newest-wins slot discipline copied from AppKitVideoRenderer, and overlay compositing as
textured quads above the picture in video-display space.
Tests: macOS can run REAL Metal on the host, so the renderer's correctness suite renders
offscreen and reads pixels back (this is also where the colour instrument will point); iOS
simulator gets the smoke and a layer-has-picture assertion through the phone view.
Gate: apple test suites, render audit extended to the new instrument names, macOS sample and
iOS host re-consumed and their smokes green, Tier 1.
Commit first lines: `Render video through Metal on Apple`, then
`Rasterise and composite subtitle overlays on Apple`.

#### S2.d The KiteVideo GPU path (KV-2, KV-3, KV-1's exits)

KV-2, the YUV image path, Apple first (Android's maturation is S3/KV-7 by the standing
decision): the KiteVideoRenderer convert seam stops producing CPU RGBA wherever the platform
can take YUV or a texture. KV-3, the zero-copy spike: CVPixelBuffer through
CVMetalTextureCache onto Skiko's Metal context, attempted on iOS first (Compose there owns a
Metal-backed Skia context); the committed fallback stands if Skiko's API refuses. The macOS
JVM desktop path gets whatever the JNI bridge's working VideoToolbox rows allow, measured,
never promised. KiteVideo `setOverlay` becomes real (the S4.c KiteVideo rider): overlays draw
after the frame in the same draw phase, mapped by VideoGeometry, using the active backend's
rasterizer. KV-1's measurement exits: per-frame cost and dropped frames through
KiteVideoFrameCost on this Mac and the iOS simulator, written in the log beside A3's 128.3 ms
so the three paths (Android software, Apple upload, Apple zero-copy) sit in one honest table.
Gate: compose suites, the KiteVideo device/simulator smokes, Tier 1.
Commit first lines: `Feed KiteVideo from the GPU on Apple`, then
`Composite subtitle overlays in KiteVideo`.

#### S2.e The colour instrument and sustained 4K

The colour instrument: programmatic fixtures at known YUV values in all four corners (601
studio, 601 full, 709 studio, 709 full; 2020 as far as the fixture pipeline allows), decoded
and rendered offscreen through Metal on the macOS host, pixels read back and asserted within
a stated per-channel tolerance, with one falsifying arm proving a deliberately wrong matrix
FAILS. Sustained 4K with thresholds committed NOW, before measurement: on this Mac, the
existing 10-bit 4K HEVC through VideoToolbox and Metal must sustain a looped run with zero
failed frames and dropped frames under one percent; the software-download path is measured
beside it without a threshold; simulator numbers provisional.
Gate: the instrument suite green with its falsifying arm, the numbers in the log.
Commit first line: `Prove colour and hold 4K`.

#### S2.f Stage exit

The matrix re-run on macOS and the iOS simulator through the new default paths; README rows
tell the new truth with measured numbers and provisional labels; the 17.2 register line
amended; every number in section 14; memory updated; nothing pushed. S4 resumes at its
paused point: S4.c's device proofs, then S4.d.
Commit first line: `Close the stage that plays beautifully on Apple`.

Estimates. S2.a 15 to 22, S2.b 12 to 18, S2.c 35 to 50, S2.d 20 to 30, S2.e 10 to 15, S2.f 3
to 5: 95 to 140 focused hours, consistent with 17.3's 105 to 150 once the 10 to 15 hours the
17.4.4 rider already moved into S1.d are subtracted; the table is not re-litigated mid-stage.

### 17.4.9 The wide-profile order, between S2.d and S2.e

Entered 2026-08-13 by owner order: "activate all possible codecs". The narrow read side was
KiteCodec's editor-era subset from its first commit, inherited by the player and never
re-decided, and the format matrix was derived from that same configure line, so a green
matrix could only ever prove that the profile plays the profile. This item is the
re-decision. It sits between S2.d and S2.e on purpose: S2.e's colour and 4K work and S2.f's
matrix re-runs then happen once, on the wide trees, instead of twice.

Owner-fixed points, decided at entry:

1. The read side goes wide BY CLASS, not by list. Decoders, demuxers, parsers and bitstream
   filters compile whole: `--disable-everything` is replaced by class disables for the write
   side (`--disable-encoders`, `--disable-muxers`, `--disable-filters`, `--disable-devices`,
   `--disable-protocols`), and the current curated encoder, muxer, filter and protocol lists
   are re-enabled by name, unchanged. A future FFmpeg bump widens the player automatically;
   the write side stays the editor's deliberate opinion; the protocol list stays the
   security boundary it is.
2. What this order does NOT grant, so nobody reads more into it later: https stays absent
   (the TLS backend decision is its own future item); AV1 on phones stays a typed refusal,
   because FFmpeg's native av1 decoder is a hardware-only wrapper and software AV1 means
   vendoring dav1d, a separate decision; the custom AVIO funnel is not this item; capture
   and playback devices stay off.
3. Sizes are measured, never guessed: per-target static archive totals and the linked
   sample binaries, before and after, in the log. If a target's growth is grotesque the
   owner decides the trade, not the executor.
4. Evidence is red first. The new matrix rows run against the NARROW trees and their
   failures are recorded before the wide trees exist; only then may they go green.
5. Configure's dependency resolver must not resurrect protocols behind the list's back
   (rtsp and its relatives select network members). The configure banner's protocol line is
   checked to say exactly `file pipe data http tcp` per target, or explicit disables are
   added until it does.

#### W.a KiteCodec: the wide profile

`sharedCoreArgs()` in `buildSrc/src/main/kotlin/BuildFFmpegTask.kt` flips as fixed point 1
says; the configure comments and `docs/platforms.md` tell the new truth. Rebuild
macos-arm64, run the C suite variants the changed path calls for, record the banner and the
sizes. The KiteCodec version moves to 0.0.5 at the publish step in W.c, not here.
Gate: C tests green on the wide host tree; banner protocol line exact; sizes in the log.
Commit first line: `Widen the decode profile to everything FFmpeg has`

#### W.b The evidence: fixtures and rows that used to fail

`scripts/testmedia.sh` gains synthesizable real-world-shaped clips: avi (mpeg4 plus mp3),
asf/wmv (msmpeg4v3 plus wmav2), flv (flv1 plus mp3), MPEG-PS vob (mpeg2video plus ac3),
eac3 in mkv, DTS core in mkv (dca, experimental encoder), truehd in mkv, alac in m4a, and
an ass-subbed mkv whose row asserts the subtitle stream is SEEN (cue decode stays S4's).
VC-1 and RealVideo have no FFmpeg encoders, so their rows wait for real sample files; that
absence is named here rather than papered over. The rows land in `FormatMatrix.kt` as
MustPlay, run RED against the narrow trees and the failures are recorded, then the
remaining trees rebuild (ios-arm64, ios-simulator-arm64, android-arm64, android-x64) and
the host matrix goes green.
Gate: host matrix green including every new row; the red-first record in the log.
Commit first line: `Prove the wide profile with rows that used to fail`

#### W.c The consumers and the close

The 0.0.5 publish ritual (klib metadata differential, exported-symbols and signature
baselines, and the Gradle plugin republished pinned at its own 0.0.1 coordinate per the
S2.d infrastructure lesson); KitePlayer relinks; the matrix runs on the Android emulator
and the iOS simulator over the wide trees; README and platform docs updated; the 17.2
register line amended; numbers in section 14; memory updated; nothing pushed.
Gate: three-platform matrix green on the wide trees; consumed versions verified by
extraction; size numbers stated only where measured.
Commit first line: `Consume the wide profile everywhere it plays`

Estimates. W.a 4 to 6, W.b 5 to 8, W.c 4 to 6: 13 to 20 focused hours, most of it FFmpeg
build time and three-platform matrix runs.

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

### 17.7 What this section does not change

Sections 1 to 3 and 9 stand. Evidence levels move for nothing in this section: every CI, device
and web claim above is level 8 until its run exists. The parked network work is 17.8.

### 17.8 Parked: network (old B6)

**UN-PARKED 2026-08-16: D-4 amended by the owner; the work enters as 17.12 phase M1,
Kotlin-first (custom AVIO bridge plus platform TLS), with the paragraph below still binding.**

Everything in section 11's B6 stays specified there, unbuilt by decision D-4. Cost if it never
happens: KitePlayer plays files, not streams; the engine's undocumented URL path remains
unhardened (no interrupt callback, no timeout bounds: draft C-52 to C-54 record the exact holes)
and must not be advertised. First network work re-opens those three items before anything else.

### 17.9 KiteVideo, the Compose-true renderer (D-6)

Decided 2026-08-11 after the owner asked what would be revolutionary about the Compose story.
The interop wrapper (S1.d) is what every player offers; KiteVideo is what none of them can offer:
video as a true Compose primitive.

**Why this project can and a MediaCodec player cannot.** D-1 means FFmpeg decodes into buffers
this project owns. A MediaCodec player decodes into a Surface it can never look inside (and DRM
keeps it that way), so it MUST punch a platform-view hole in the UI. Owning the pixels means a
frame can become a Skia image and draw through Compose's own pipeline (Skiko on iOS, desktop and
web; HWUI on Android). Then `KiteVideo(state, modifier)` is real Compose content: clip, rounded
corners, alpha, rotation, scale animation, shared-element transitions and runtime shader effects
apply to the video itself. On Compose Desktop and Compose for Web there is no AndroidView or
UIKitView, so there KiteVideo is not a luxury: it is the only route to goals 3 and 4 inside
Compose, and no player on the market has it.

**The three per-frame laws.** Violating any one is the difference between smooth and slideshow:

1. **Never recompose per frame.** The frame holder is read ONLY inside the draw phase, so a new
   frame invalidates drawing alone, never composition or layout. Per frame, the UI tree does
   nothing and one drawImage runs.
2. **YUV until the GPU.** Decoded video is YUV420 at 1.5 bytes per pixel (about 3 MB per 1080p
   frame, against 8 MB as RGBA). Planes upload as they are; conversion to RGB happens inside the
   GPU draw (a Skia YUV image where Skiko exposes it, a runtime-effect shader where it does not).
   Full-frame CPU conversion to RGBA is a last-resort fallback, never the design.
3. **Zero-copy where the platform allows.** Apple: VideoToolbox produces a CVPixelBuffer;
   CVMetalTextureCache wraps it as a Metal texture with no copy; Skiko's Metal context draws it
   directly. Where no zero-copy path exists, one upload per frame is paid and measured.

**Physics, stated honestly (ASSUMED, level 8, until S2 measures them).** Naive 1080p30 RGBA is
about 250 MB/s of copying against roughly 50 GB/s of phone memory bandwidth; the YUV path is
about 94 MB/s; the zero-copy path is one GPU quad per frame. Precedent: Chrome draws every video
element through Skia, the same library under Compose, at 4K60 on billions of devices. The exit
numbers that replace these assumptions are measured at S2: per-frame CPU milliseconds and dropped
frames at 1080p30 with modifiers applied, on named devices.

**The honest cost, and why D-6 keeps both paths.** A SurfaceView/CALayer overlay lets the display
controller present video while the GPU idles; KiteVideo keeps the GPU lightly awake. Sustained
fullscreen playback therefore belongs to the baseline wrapper. Video embedded inside UI (cards,
feeds, mini players) loses nothing, because Compose was compositing those pixels anyway.

**The slices and their homes** (each expands into register items at its home stage's entry, per
17.2's ritual; nothing here starts earlier, with one owner-decided exception recorded on the
slice it moves):

- **KV-1, the core** (home S2; the CORE pulled forward into S1.d by owner decision 2026-08-12,
  expansion 17.4.4): `KiteVideo(state, modifier)` in :kiteplayer-compose; a frame holder
  obeying law 1; fed by a VideoRenderer SPI implementation. The engine does not change: this is
  one more SPI consumer and a further proof the SPI is sufficient. The S2 slice keeps what S1.d
  does not land: the measurement exits (per-frame CPU cost, dropped frames, modifiers applied,
  named devices) and law 2's arrival via KV-2.
- **KV-2, the YUV image path** (S2): law 2 end to end, with the shader fallback.
- **KV-3, Apple zero-copy** (S2): law 3 on iOS and macOS, riding S2's VideoToolbox work
  (KiteCodec window 3 already sits there).
- **KV-4, Android software path** (S3, exit rider): frames over the S1.c converter into
  ImageBitmap, one copy per frame; days of work once KV-1 and KV-2 exist. It rides S3 because
  that is where the JVM rendering paths mature; S3's exit carries it as an explicit rider per
  17.1's refinement. Note 2026-08-12: S1.d's pulled-forward core already feeds itself this way
  (RGBA, not yet ImageBitmap-from-YUV), so KV-4's remaining S3 work is the measured maturation
  of that path, not its first existence. Second owner rider, later the same day (17.4.6): the
  maturation CORE moved forward too, so KV-4's cost instrumentation, Android image ring and
  first measured emulator run land before S2, and S3 keeps only the launchable modifier demo
  and the device-grade numbers.
- **KV-5, desktop upload path** (S3): one upload per frame; desktop bandwidth makes this cheap;
  measured anyway.
- **KV-6, web** (S6): the only Compose rendering story on wasm; measured inside the S6 spike.
- **KV-7, Android zero-copy: PARKED as research.** FFmpeg's mediacodec decoder in buffer mode
  outputs CPU NV12 frames, so one copy is already paid inside the decoder; its opaque surface
  mode renders only to a Surface and exposes no HardwareBuffer handle. A true zero-copy route
  needs AImageReader plumbing behind FFmpeg or upstream FFmpeg work. Costs ASSUMED; decided at S3
  entry whether to research it or keep the one-copy path, using KV-4's measurements as the judge.

### 17.10 The piloting surface: typed FFmpeg control, package KD

Authored 2026-08-12 by the planner at the owner's direction, from a verification sweep of both
trees (KitePlayer 4b3580a, KiteCodec 23b8bf4). The owner's ask: maximum FFmpeg piloting through
Kotlin data classes and DSLs, without more C and without touching performance. This section is
the bedstone: the verified funnel inventory, the laws, and the decided register. Slices expand
at their home stage's entry per 17.2's ritual. NOTHING here enters an S1.c or S1.d commit; 18.3
rule 4 applies in full.

**The verified funnel inventory (what the DSL compiles onto, as of the heads above).** Generic
option funnels already exist in C and are passthroughs to `av_opt_set`:
`ffkmp_fmt_set_opt` (kitecodec_helpers.h:321, measured to answer AVERROR(EINVAL) without
crashing on unknown keys) and `ffkmp_codecctx_set_opt` (:251). The four filter-graph builders
(:388, :401, :419, :436) take one FFmpeg description STRING and already carry rename-proofing
(output pinning is appended as an `aformat` stage precisely because option names moved across
FFmpeg 7 to 8 while filter-string syntax did not). Dict iteration for metadata exists (:122 to
:124). On the Kotlin side the typed foundation already exists: `StreamInfo` with typed video and
audio sub-info, disposition, rotation and a metadata map; `VideoEncoderSpec`/`AudioEncoderSpec`
data classes that ALREADY carry an `options: Map<String, String>` escape hatch documented as
`av_opt_set` strings; `Transcoder` with typed arguments and `audioCopy`; `FilterGraph.buildVideo
(description)` with a documented 2048-byte composed-chain bound and Flow processing;
capability queries `hasDecoder`/`hasEncoder`/`hasFilter`; and the whole `PlayerConfig` tree
(HwdecPolicy sealed, FrameDropPolicy, BufferPolicy, AudioConfig). The DSL is therefore mostly a
compilation layer onto surfaces this project already ships.

**The laws.** Binding on every KD slice:

1. **Control plane only.** A DSL construct configures opens, graphs and encoders. It may never
   introduce a per-frame Kotlin-to-C crossing that does not exist today. Anything per-frame is
   KiteVideo's or the engine's business, not KD's.
2. **Curated core plus escape hatch, never a mirror.** FFmpeg exposes on the order of ten
   thousand AVOptions. Typing them all would freeze FFmpeg's option namespace into this API the
   same way the struct layout was almost frozen (C-43's lesson). The typed set is the curated
   few dozen below; everything else flows through `option(key, value)`, which already exists at
   both funnels and on both encoder specs.
3. **Compile to existing funnels.** A KD feature that needs a NEW C entry point is a KiteCodec
   window item with the full S1.a.7-style ritual (signature baseline, export baseline, minor
   version, guards, tests). This section names exactly two such funnel additions (KD-4, KD-5);
   any further one is a register change, not an improvisation.
4. **Values, not magic.** Every DSL produces a plain immutable data class first; builders are
   sugar over constructors. Every compiled result (an option list, a filter string) is
   inspectable and printable, and the S4 diagnostics dump prints exactly what was compiled, so
   a bug report always carries the real FFmpeg-facing configuration.
5. **Deterministic and golden-tested.** DSL compilation is a pure function from data class to
   option pairs or description string. Goldens pin the exact output, including escaping, the
   2048-byte bound behavior and ordering. These are host tests; they cost no device time.
6. **Capability-honest per D-5.** A typed construct that requires a codec or filter outside the
   lean tier queries capability first (`hasFilter`, `hasEncoder`) and fails TYPED when absent,
   naming the tier that carries it. No typed construct may silently no-op.
7. **Dump-governed.** Every KD slice moves API dumps deliberately, with the log entry naming
   each added declaration, exactly like every other surface change in this project.

**The register, decided at design level (expansion at entry):**

- **KD-1, the filter DSL.** Typed builders compiling to the existing description string:
  video `scale`, `crop`, `pad`, `transpose`/`rotate`, `fps`, `format`, `eq`, `yadif`/`bwdif`,
  `drawbox`; audio `volume`, `atempo`, `aresample`, `pan` (downmix matrices), `aformat`,
  `loudnorm`; plus `raw("...")` for any chain the typed set lacks. Escaping is centralised and
  golden-tested against the exact alphabet the fuzz corpus already exercises (16.4 item 5's
  measured character set). Compiles to `FilterGraph.buildVideo`/`buildAudio` unchanged. Sketch:
  `filters { scale(1280, 720); eq(brightness = 0.1); format(Yuv420p) }`. Home: S4, KiteCodec
  side, pure Kotlin. Test: goldens per builder, bound-overflow refusal, capability-honest
  failure for a non-lean filter, and one end-to-end real-media run reusing the existing
  FilterGraph tests.
- **KD-2, decoder options.** `openDecoder` gains an optional typed block compiling through
  `ffkmp_codecctx_set_opt` between context creation and open: `skipLoopFilter`, `skipFrame`
  (the scrubbing pair), `errDetect`, `threads` beyond the existing count (frame versus slice),
  plus the escape hatch. Requires ONE Kotlin-side injection point in the existing open path and
  no new C. Home: S4, KiteCodec side. Test: reproduction-first proof that the option reached
  FFmpeg (a wrong key must produce the measured EINVAL path), plus a scrub-preset decode of the
  conformance clip asserting decode speedup is observed and frames are still delivered.
- **KD-3, the encoder typed layer.** Typed common knobs compiling INTO the existing
  `options` map of `VideoEncoderSpec`/`AudioEncoderSpec`: `crf`, `preset` (enum), `profile`,
  `tune`, rate-control mode (CRF versus ABR versus CBR shapes the bitrate fields), `gop` from
  the existing keyframe interval. Pure sugar, zero C, zero new funnel. Home: S4, KiteCodec
  side. Test: goldens proving each typed knob lands as the exact expected option pair; refusal
  goldens for contradictory combinations (CRF plus CBR).
- **KD-4, pre-open format options, ONE new C funnel.** Today `ffkmp_fmt_open_input` allocates
  and opens in one call, so true pre-open options (`probesize`, `fflags`, format forcing)
  cannot be applied; post-open-pre-find_stream_info options already work through the existing
  funnel. Add `ffkmp_fmt_open_input2(out, path, keys, values, n)` applying pairs between alloc
  and open. Full S1.a.7-style ritual. C half home: the next open KiteCodec window at its stage
  (window 3, S2); Kotlin half home: S4 (`MediaSource.open(path) { probe { ... } }`). Test: the
  guard suite gains its NULL arms; a probesize-shrunk open of a torture clip must measurably
  change probe behavior, reproduction-first.
- **KD-5, chapters and container info, the second new C funnel.** Chapters are not exposed at
  all today (verified: zero hits in the Kotlin tree). Add `ffkmp_fmt_chapter_count` and
  `ffkmp_fmt_chapter_get(i, out fields)` plus reuse of the dict iteration for chapter metadata;
  Kotlin gains `data class Chapter(startMicros, endMicros, metadata)` and a container-level
  `MediaInfo` (duration, container metadata, chapters) beside the existing per-stream info.
  Same window and ritual as KD-4. Home: C in window 3; Kotlin in S4. Test: a chaptered
  conformance fixture (added to 17.5) round-trips exact chapter bounds.
- **KD-6, playback profiles.** Player-side presets as data: `PlaybackProfile.Scrubbing`
  (KD-2's skip pair plus FrameDropPolicy), `.LowLatency` (lowDelay, small buffers),
  `.Battery` (hardware preference, relaxed intervals), compiling into `PlayerConfig` plus
  decoder options. Pure Kotlin, Player side. Home: S4. Test: each profile's compiled
  configuration golden, plus one real-media scrubbing run.
- **KD-7, diagnostics integration.** The S4 diagnostics dump prints every compiled KD artifact
  attached to the session: the filter strings as sent, the option pairs as applied and their
  per-key FFmpeg answer, the active profile. This is law 4 made mechanical. Home: S4, inside
  the existing debuggability register item.
- **KD-8, the goldens suite.** One host-test source set holding every KD compilation golden,
  named in Tier 2's selector the moment it exists (it is buildSrc-adjacent pure Kotlin, so the
  existing Kotlin-source selector already reaches it). Home: S4, first KD commit.

**Non-goals, stated so nobody invents them:** no full AVOption mirror (law 2); no custom
Kotlin AVIO callbacks (that is data plane and network is parked per D-4; revisit only when
17.8 reopens); no runtime filter hot-swap in v1; no DSL constructs for encoding features the
lean and standard tiers cannot carry without a capability check (law 6).

**Cost.** S4 grows by 30 to 45 hours (the 17.3 table carries it); the two C funnels add 3 to 5
hours inside KiteCodec window 3. Nothing else moves.

### 17.13 The phase W expansion, decision complete

Authored 2026-08-17 by Opus 5 at the owner's direction, entering phase W (17.12: REAL ON DESKTOP
AND WEB, which is S3 then S6 minus the rows M4 took). Written against the tree at KitePlayer
47f3799 and KiteCodec 9b33480, from a seven-dimension survey of both repositories that read code
and treated KDoc and this file as hearsay. 17.2's ritual is satisfied by this subsection: every
item below carries Where, Problem, Fix, Sub-phase and Test, and the sub-phases name files, steps,
gate and commit first lines.

**The entry facts, measured on 2026-08-17, not assumed.**

1. The ENGINE already reaches every desktop target. `:kiteplayer-core`, `:kiteplayer-subtitles`
   and `:kiteplayer-rt` compile for linuxX64, linuxArm64 and mingwX64 today, and
   `CompileKiteRtTask` already emits `libkiteplayerrt.a` for `linux_x64`, `linux_arm64` and
   `mingw_x64` from the Kotlin/Native bundled toolchains. Nothing in phase W has to port the
   engine.
2. The BACKEND does not. `:kiteplayer-ffmpeg` and `:kiteplayer-output` declare only macosArm64,
   iosArm64, iosSimulatorArm64, jvm and android. There is no desktop `OutputBackend`, no desktop
   `AudioSink` of any kind in either repository, no desktop `VideoRenderer`, and no desktop
   window.
3. The published JVM variant of `kitecodec-core` is a placeholder BY BUILD WIRING, not by
   omission: `kitecodec-core/build.gradle.kts:525` makes `jvmMain` depend on `unsupportedMain`,
   whose nine files throw `AVERROR_PATCHWELCOME` from every entry point, while the real JNI
   implementation in `jvmAndAndroidMain` is compiled only into the Android target and into an
   unpublished `jniHarness` compilation. The macOS JNI dylib is already produced by
   `linkKiteCodecJniMacosArm64` and already loaded by `JniLibrary.jvm.kt` through
   `System.loadLibrary("kitecodec_jni")` with a `kitecodec.jni.path` override. Desktop JVM
   playback is therefore a PACKAGING problem sitting on finished code, which is why W.1 is first.
4. `BuildFFmpegTask` already carries LinuxX64, LinuxArm64 and MingwX64 configure paths
   (`buildSrc/src/main/kotlin/BuildFFmpegTask.kt:523-533`), and `StaticLinkFlags`, `FFmpegPaths`
   and `ffmpeg.def` already carry their link sides. No tree has ever been built for them:
   `native-libs/lgpl/` holds macos-arm64, ios-arm64, ios-simulator-arm64, android-arm64 and
   android-x64 and nothing else.
5. The web surface is honest and empty. js and wasmJs are real registered targets that publish
   real klibs compiled from `unsupportedMain`. There is no emscripten toolchain, no wasm
   `TargetTriple`, no `@JsExport` anywhere, and `:kiteplayer-compose-video` declares no web target
   at all.
6. Tier 1 was RED on the clean tree at entry, for reasons that predate this phase: three cases in
   KiteCodec's C suites pinned contracts that deliberate changes had already replaced, and one
   tracked file carried an em dash. Repaired in KiteCodec 9b33480 before the phase began, because
   18.2 item 4 does not permit building on an ungated tree.

**Decisions taken for this phase, and why. These are executor judgement calls under the owner's
standing 17.11 rule that homes and order are proposals; each is recorded so the owner can reverse
it.**

- **W-D1, the JVM desktop path goes FIRST, before the Kotlin/Native desktop path.** S3 as written
  in 17.2 names "desktop rendering through the JVM path for Compose Desktop and native paths for
  K/N consumers" in that order, and the entry facts say why the order is also the cheap one: one
  build-wiring change and one packaging step light up macOS, Linux and Windows desktop at once
  through code that already exists and is already proven by the Android target. The K/N desktop
  consumer path needs three FFmpeg trees, two new C device backends and a renderer that does not
  exist. Buying the first outcome first is 17.1's own law.
- **W-D2, desktop JVM audio is `javax.sound.sampled`, not new C.** D-7 says the measure for every
  gap is Kotlin first and a native library only when no Kotlin path can exist. `SourceDataLine`
  is in the JDK, is present on macOS, Linux and Windows, and reaches ALSA, CoreAudio and WASAPI
  through the JVM's own backends. The C WASAPI and ALSA sinks that 17.2 names stay in this
  register (W-08, W-09) for the Kotlin/Native consumer, where no JVM exists to borrow. The cost
  is stated honestly: `SourceDataLine` is a blocking-write sink with a coarser latency floor than
  the C ring's device callback, so the desktop JVM sink writes through the engine's existing
  `KotlinAudioRing` contract and is measured against it rather than promoted to the C ring's
  evidence tier.
- **W-D3, linux and mingw FFmpeg cross-build on this Mac with the Kotlin/Native bundled
  toolchains**, not with Docker and not with Homebrew's mingw. `~/.konan/dependencies` carries
  `x86_64-unknown-linux-gnu-gcc-8.3.0-glibc-2.19`, `aarch64-unknown-linux-gnu-gcc-8.3.0-glibc-2.25`
  and `msys2-mingw-w64-x86_64-2`, which are the exact toolchains Kotlin/Native links against.
  Building FFmpeg with any other toolchain risks a glibc symbol the konan sysroot cannot resolve
  at link time; building with these cannot.
- **W-D4, linux and mingw get a REDUCED desktop profile.** `desktopBaseArgs()` demands a
  third-party stack (x264, svt-av1, opus, libass and more) that has never been cross-built for
  those triples, and building nine dependencies three ways is not phase W's outcome. Linux and
  Windows get the phone software profile plus the desktop demuxer and protocol set, which is
  exactly the 17.6 `standard` tier and plays the whole 17.5 matrix. The GPL desktop stack stays
  available through the consumer plugin, which 17.6 already says is what the plugin is for.
- **W-D5, Linux is RUN, Windows is LINKED.** Docker is present on this machine, so linuxArm64 test
  binaries execute natively in an arm64 Linux container and their results are real measured
  evidence. No Windows machine and no wine exist here, so every mingw claim in this phase is a
  compile-and-link claim and says so; the Windows matrix run is an owner rider exactly like the
  iPhone one.
- **W-D6, the web spike is timeboxed and its verdict is recorded either way**, per 17.2's S6
  sentence. Emscripten is installed for it, which is a new toolchain and therefore named here as
  the register item that authorizes it (W-12) rather than taken silently under 18.3 rule 3.

**The register.**

#### W-01. The published JVM variant of kitecodec-core is a placeholder
- Where: `../KiteCodec/kitecodec-core/build.gradle.kts:525`; `src/unsupportedMain/` (9 files);
  `src/jvmAndAndroidMain/`; `src/jniJvmMain/kotlin/.../JniLibrary.jvm.kt`.
- Problem: `jvmMain` depends on `unsupportedMain`, so every JVM consumer of KiteCodec gets a
  library whose every entry point throws. The working JNI implementation exists and is proven by
  the Android target and by the `jniJvmTest` boundary harness, but no published artifact carries
  it. This is the single reason Compose Desktop cannot play a file today.
- Fix: give the jvm target the real tree. `jvmMain` depends on `jvmAndAndroidMain`, the
  `JniLibrary.jvm.kt` actual moves from the harness source set into the published one, and
  `unsupportedMain` keeps js and wasmJs only. The `jniHarness` compilations stay exactly as they
  are, because they are how the boundary tests get a mutated library to load.
- Sub-phase: W.1. Test: `:kitecodec-core:jvmTest` executing the codec contract suite against the
  real library, proved able to fail by pointing `kitecodec.jni.path` at the corrupt harness dylib.

#### W-02. No desktop JNI library is built or packaged
- Where: `../KiteCodec/kitecodec-core/build.gradle.kts:756-830` (the JNI link tasks);
  `native/kitecodec-jni/`.
- Problem: `linkKiteCodecJniMacosArm64` exists but is described and wired as test-only, and its
  output reaches no artifact. `System.loadLibrary("kitecodec_jni")` therefore fails for every
  desktop consumer unless the consumer sets a system property by hand.
- Fix: the macOS JNI dylib becomes a published resource of the jvm artifact, laid out under
  `native/<os>-<arch>/` inside the jar, and `JniLibrary.jvm.kt` extracts and loads it from there
  when `System.loadLibrary` finds nothing on `java.library.path`. Linux and Windows twins land in
  W.5 once their FFmpeg trees exist; the loader's layout is written once, here, so adding a
  platform is dropping a file in.
- Sub-phase: W.1. Test: a JVM test that loads the library from a jar copied to a scratch
  directory with an empty `java.library.path`, proved able to fail by deleting the resource.

#### W-03. :kiteplayer-ffmpeg's jvm target has no backend
- Where: `kiteplayer-ffmpeg/build.gradle.kts`; `src/jvmMain/` (absent).
- Problem: the module declares a jvm target but its backend actuals exist only for native and
  android, so `KitePlayerPlatform` answers `Unavailable` on the JVM and no `MediaBackend` can be
  constructed there.
- Fix: the jvm target gets the same source tree the android target uses (both are JNI consumers of
  the same KiteCodec JVM API), through a shared `jvmAndAndroidMain` source set in this module.
- Sub-phase: W.2. Test: a jvm test that opens a testmedia file, reads its track list and decodes
  one frame.

#### W-04. There is no desktop OutputBackend, AudioSink or VideoRenderer
- Where: `kiteplayer-output/build.gradle.kts` (no desktop targets, no `jvmMain`);
  `spi/OutputBackend`, `spi/AudioSink`, `spi/VideoRenderer` in `:kiteplayer-core`.
- Problem: the whole output half of the player is Apple and Android only. A desktop consumer has
  a working engine and a working backend and nothing to hear or see it with.
- Fix: a `DesktopOutputBackend` in `kiteplayer-output`'s jvm source set: a `SourceDataLine` audio
  sink writing through the engine's existing `KotlinAudioRing` contract per W-D2, a Skia-backed
  subtitle rasterizer, and the system clock the other backends already share. Video is not a
  renderer here: on desktop the Compose path IS the renderer, which is W-05.
- Sub-phase: W.3. Test: host tests for the sink's lifecycle (open, write, pause, drain, flush,
  close) and for the rasterizer's straight-alpha contract, each proved able to fail.

#### W-05. KiteVideo has no desktop upload path and no desktop measurement (KV-5)
- Where: `kiteplayer-compose-video/src/jvmMain/.../ImageBitmaps.jvm.kt:19-52`.
- Problem: the jvm image body exists and is byte-identical Skia code duplicated from the iOS one,
  but nothing feeds it, `canDrawCommitFencedFrames` is declared true with no GPU completion fence
  behind it, and KV-5's stated exit is a MEASURED per-frame upload cost that has never been taken.
- Fix: the duplicated Skia body collapses into one shared source set, the frame source becomes the
  W-03 jvm backend, the fence claim is corrected to what the desktop path can actually promise,
  and the per-frame upload cost and dropped-frame count are measured at 1080p30 with modifiers
  applied and written into the log.
- Sub-phase: W.4. Test: the existing KiteVideo draw-law tests extended to the jvm target, plus the
  measurement harness.

#### W-06. No FFmpeg tree exists for linux-x64, linux-arm64 or mingw-x64
- Where: `../KiteCodec/native-libs/lgpl/`; `buildSrc/src/main/kotlin/BuildFFmpegTask.kt:523-533`.
- Problem: the configure paths exist and have never run. `LinuxX64` additionally passes
  `--cc=clang` with no sysroot and no `--enable-cross-compile`, which on a macOS host configures a
  darwin build under a linux name.
- Fix: the three desktop triples build from the Kotlin/Native bundled toolchains per W-D3, at the
  reduced profile of W-D4, and their configure lines are pinned by goldens like every other
  triple.
- Sub-phase: W.5. Test: `buildSrc:test` goldens for the three new configure lines, and the object
  format of each produced archive read back with the target's own toolchain.

#### W-07. KiteCodec publishes no desktop variant, so nothing downstream can resolve one
- Where: `../KiteCodec/kitecodec-core/build.gradle.kts` target-scope selectors;
  `settings.gradle.kts` consumption comments in this repository.
- Problem: every existing selector is the five-target stable set or a mac-local or phone-local
  scope. There is no desktop superset, so `:kiteplayer-ffmpeg` cannot resolve a backend for a
  desktop target even after W-06.
- Fix: a `kitecodec.desktopTargetsOnly` scope covering macosArm64, linuxX64, linuxArm64 and
  mingwX64 plus the portable jvm/js/wasm variants, and a local publication that preserves the
  existing Apple and phone variants rather than replacing them.
- Sub-phase: W.5. Test: `publishToMavenLocal` under the new scope followed by a consumer
  resolution from this repository, with the previously published variants still resolvable.

#### W-08. kprt_sink is Apple-only, and its clock is mach-shaped
- Where: `kiteplayer-rt/native/src/kite_rt_coreaudio.c:597-682` (the `#else` refusal arm);
  `kprt_sink_ticks_to_nanos` and the cached `timebase_numer`/`timebase_denom`.
- Problem: every non-Apple target links eight `kprt_sink_*` symbols that return
  `KPRT_SINK_UNSUPPORTED_PLATFORM`, and the sink's tick-to-nanosecond conversion is
  `mach_timebase_info`, so a new backend cannot even express its own deadline.
- Fix: the platform-independent half of the sink (the struct, the stats, the verdicts, the render
  entry) moves into its own translation unit with a per-platform clock, and the CoreAudio file
  keeps only CoreAudio. ALSA and WASAPI backends then land as siblings rather than as edits to a
  file named after another platform.
- Sub-phase: W.6. Test: the existing C suites run unchanged against the split, plus a new case per
  platform clock proved able to fail by returning a constant.

#### W-09. The C audit instruments are Mach-O and CoreAudio shaped
- Where: `kiteplayer-rt/native/scripts/render-audit.sh`, `source-discipline.sh`,
  `build-host.sh`; `../KiteCodec/native/kitecodec-c/scripts/symbol-audit.sh`.
- Problem: `render-audit.sh` is the strongest instrument in the whole gate and it reads Mach-O
  with `nm -m`; `source-discipline.sh` hardcodes CoreAudio symbol names; both C `build-host.sh`
  scripts link a `-dynamiclib` interposer with a Mach-O `__DATA,__interpose` section. A desktop
  device backend would ship with no proof at all.
- Fix: the audits read the object format they are given (Mach-O and ELF and PE), and the
  CoreAudio-named rules become per-backend rule sets keyed by the file under audit.
- Sub-phase: W.6. Test: each audit run against an ELF archive and proved able to fail there, with
  the sixteen existing negative controls still able to fail on Mach-O.

#### W-10. The S3-homed audit rows
- Where: 17.11's open rows whose home is S3: SOL-R9, SOL-R10, SOL-R11, SOL-R12, SOL-R13, SOL-R14's
  remainder, SOL-API6, SOL-API7, SOL-P7, and SOL-C2. SOL-P8 rides here only if audio work lands.
- Problem: each is stated in 17.11 and carries its own [V] or [C] mark.
- Fix: each row's own fix, re-verified against the tree before it is written, since a [C] is a
  debt to check at pickup.
- Sub-phase: W.7. Test: named per row inside the sub-phase.

#### W-11. Nothing outside four surfaces has ever run a test (F-COV1's desktop half)
- Where: 17.11.b's F-COV1; `linuxX64Test`/`linuxArm64Test`/`mingwX64Test` exist as tasks and are
  permanently disabled on a macOS host.
- Problem: a phase-W gate that names those tasks is green by definition, which is worse than
  having no gate.
- Fix: linuxArm64 test binaries are LINKED here and EXECUTED in an arm64 Linux container per
  W-D5, and the gate names the container command, not the disabled Gradle task. mingw stays a
  link claim and the gate says so in the same line.
- Sub-phase: W.8. Test: the container run itself, with its pass count recorded.

#### W-12. The web spike (S6), timeboxed
- Where: 17.2's S6; 17.9's KV-6.
- Problem: web is nine goals' worth of unbuilt surface: no emscripten toolchain, no wasm
  `TargetTriple`, no binding over the `kc_`/`ffkmp_` ABI, no `@JsExport`, no web `AudioSink`, no
  web renderer, and an engine whose renderer worker shape uses `newSingleThreadContext` and
  `runBlocking`, neither of which exists on wasmJs.
- Fix: run the spike S6 asks for and record its verdict either way: FFmpeg-to-wasm size and decode
  throughput, the threads and SIMD question, the JS interop shape over the same C ABI, and the
  single-threaded-engine question, which the survey found is a HARDER blocker than the codec build
  and which S6's own text never anticipated. This item authorizes the emscripten install per
  W-D6.
- Sub-phase: W.9. Test: the spike's own measurements, or the recorded reason a measurement could
  not be taken.

#### W-13. The frame/renderer pairing fails per frame, late, and untyped (SOL-API7, narrowed)
- Where: `kiteplayer-compose-video/src/commonMain/.../ImageBitmaps.kt:35` (the `expect`), its three
  actuals (`ImageBitmaps.jvm.kt:39`, `.ios.kt:48`, `.android.kt:46`), and the call site
  `KiteVideoRenderer.kt:219-230`.
- Problem: SOL-API7 says an unsupported backend/renderer pairing "fails at runtime instead of as a
  typed capability error". Verified 2026-08-17 against the tree, and the row is BROADER than the
  defect. The cast is already caught: `convertPending` wraps `convert(frame)` in a try and calls
  `failFrame(failure.message)`, so a mismatched pairing degrades to black video with a rising
  `failedFrames` count rather than crashing. What is actually wrong is three narrower things.
  (a) The failure is discovered PER FRAME, at draw time, after the session is already running,
  so the cost is paid thirty times a second forever. (b) The detail carried is a
  `ClassCastException` message, which is a compiler implementation detail and differs between
  Kotlin/Native and the JVM, so no consumer can branch on it and no bug report reads the same
  twice. (c) Nothing reaches the typed warning channel that already exists for exactly this
  (`PlaybackWarning.RendererFailed`), so a consumer watching warnings sees silence while the
  picture is black.
- Fix, decided: the converter seam gains a typed refusal instead of an implicit cast. A new
  internal `UnsupportedFrameType` failure carries the frame's actual type name and the converter's
  expectation, each actual throws it rather than letting the cast throw, and `convertPending`
  publishes it ONCE per renderer generation through `RendererFailed` rather than per frame. The
  first refusal also marks the pairing dead for that generation, so the per-frame conversion
  attempt stops instead of repeating. NOT in scope, deliberately: the sealed hardware-surface model
  and full capability negotiation the original row imagines. That is a larger design act with no
  demonstrated defect behind it once (a), (b) and (c) are closed, and inventing it here would be
  exactly the over-building 18.3 rule 2 forbids.
- Sub-phase: W.10. Test: a KiteVideo renderer fed a foreign `VideoFrame` publishes exactly one
  `RendererFailed` naming both type names, stops attempting conversion, and keeps the session
  alive. Proved able to fail by restoring the implicit cast, which produces a per-frame failure and
  no warning.

#### W-14. The desktop upload converts on the CPU, and that is 81% of its cost (KV-2's desktop half)
- Where: `kiteplayer-compose-video/src/jvmMain/.../ImageBitmaps.jvm.kt:39` calling
  `SoftwareConverter.toRgba`, which reaches
  `kiteplayer-ffmpeg/src/commonMain/.../Conversions.kt:111 tightlyPackedToRgba`.
- Problem: W.4 measured the desktop upload at 11.6 ms mean and 13.1 ms p95 per 1080p frame, and
  81% of that is `tightlyPackedToRgba`, a pure-Kotlin per-pixel YUV to RGBA loop over 2.07 million
  pixels. Only 19% is the Skia raster build. That loop is exactly the last-resort fallback 17.9's
  law 2 names, running as the default on every desktop frame, and it also allocates an 8.3 MB RGBA
  array per frame against the 3.1 MB the planes actually occupy, which is roughly 340 MB/s of
  garbage at 30 fps.
- Facts established before deciding, so the fix is not guesswork:
  1. Skiko 0.150.1 exposes `RuntimeEffect` and NO YUV image type (no `YUVAPixmaps`, no
     `makeFromYUVAPixmaps`). Law 2 anticipates exactly this and names the answer: "a Skia YUV image
     where Skiko exposes it, a RUNTIME-EFFECT SHADER where it does not". Desktop takes the shader.
  2. The JVM frame already reaches its planes. `Frame.copyPlanesToByteArray()` is a JVM actual
     today, and it is `av_image_copy_to_buffer` at align 1, so the planes arrive tightly packed,
     plane after plane, in the frame's own pixel format. KV-2 on desktop therefore needs NO
     KiteCodec change and no new C, which is what makes it a KitePlayer-only item.
  3. The CPU path covers 21 pixel-format cases and finishes with
     `HdrToneMap.forColorSpaceOrNull(colorSpace)?.mapInPlace(out)`, which is M3's tone mapping.
     A shader that silently skipped that would wash out HDR, which is the exact defect the M4
     surge closed for the Apple hardware readback.
- Fix, decided: the desktop draw uploads PLANES and converts in a `RuntimeEffect` shader, for the
  four dominant 8-bit planar layouts only: yuv420p, yuv422p, yuv444p and nv12. Each plane becomes a
  single-channel Skia image; the shader samples them and applies the same matrix and range law
  `Conversions.kt` applies, read from the frame's own `ColorSpaceInfo` rather than assumed.
  EVERYTHING ELSE FALLS BACK to the existing CPU converter unchanged, explicitly including every
  10-bit layout and every frame whose colour space asks for tone mapping. That boundary is the
  point: it is the smallest change that moves the measured number (18.3 rule 2), it cannot regress
  HDR because HDR does not enter it, and the fallback is the code that is already proven.
- API availability, verified against `skiko-awt-0.150.1.jar` on 2026-08-17 so the next executor
  does not re-derive it: `RuntimeEffect.Companion.makeForShader(String)` compiles SkSL;
  `RuntimeEffect.makeShader(Data, Shader[], float[])` takes CHILD shaders, which is how the three
  planes reach it; `Image.makeRaster(ImageInfo, ByteArray, rowBytes)` with `ColorType.ALPHA_8` or
  `GRAY_8` uploads a single-channel plane. One better than the plan assumed: `ColorType.R8G8_UNORM`
  exists, so NV12's interleaved UV plane maps DIRECTLY with no CPU de-interleave, which is the
  layout Android and VideoToolbox both hand over.
- **AMENDED 2026-08-17, before any code, under 18.3 rule 5.** The Fix above says "the desktop draw
  uploads PLANES and converts in a RuntimeEffect shader" as though it were a swap of the jvm
  converter. It is not, and the tree says so: the whole pipeline is `convert(frame) -> ByteArray`
  then `makeImage(bytes) -> ImageBitmap`, and `KiteVideo.kt:82` draws that with `drawImage(image =
  frame.image, colorFilter = videoFilter)`. A shader is not an `ImageBitmap`, and the conversion
  cannot happen at convert time anyway, because the GPU context lives on the draw thread and the
  converter runs on the renderer's worker. So the planes have to REACH the draw phase, which means
  `FrameImage` and the draw call change, and both live in commonMain shared with Android and iOS,
  whose paths are working and pinned. That is a change to the shared frame pipeline, not a jvm-only
  edit, and the estimate and the risk both move with it.
- **The revised design, its route verified by compiling it rather than assumed.** `ShaderBrush`
  REFUSES an `org.jetbrains.skia.Shader` on this Compose version (checked: one skiko on the
  classpath, 0.150.1, so this is an API shape and not a version clash). The route that does compile
  is the native canvas: `drawIntoCanvas { it.nativeCanvas.drawRect(rect, paint) }` with an
  `org.jetbrains.skia.Paint` carrying the RuntimeEffect shader. To keep that out of Android's and
  iOS's way, the draw call becomes one new expect/actual (`DrawScope.drawFrameImage`), whose Android
  and iOS actuals are today's `drawImage` unchanged and whose jvm actual branches: planar frames
  take the shader, everything else takes `drawImage` exactly as now. `FrameImage` gains an optional
  planar variant beside its image rather than replacing it.
- **One detail named rather than discovered later**: the picture controls arrive as an
  `androidx.compose.ui.graphics.ColorFilter` on `drawImage`. On the native-canvas path they have to
  become a Skia colour filter on the `Paint`, or the eq controls silently stop applying to desktop
  video. Whichever way that resolves, it needs its own assertion in arm 1.
- **MEASURED 2026-08-17, and one variant is REJECTED.** Before touching the shared pipeline, the
  cheap variant was built and timed: run the shader into a RASTER surface inside the existing
  `convert` seam, which needs no pipeline change at all. One JVM, identical synthetic 1080p
  yuv420p planes, 60 timed iterations after warmup. The scalar Kotlin loop runs at 5.56 ms mean;
  the SkSL raster path runs at 37.56 ms, SIX AND A HALF TIMES SLOWER, because Skia's raster backend
  interprets SkSL on the CPU. That variant is rejected on its number, exactly as this item's exit
  said it would be. The benchmark and its rerun command live in
  `kiteplayer-sample-desktop/MEASUREMENTS.md`. The GPU-surface design is NOT settled by this and
  remains unmeasured, because it cannot be measured without first making the pipeline change; what
  the probe bought is the knowledge that the investment has no cheap shortcut.
- **A third option appeared and is now the recommended next move.** The benchmark's scalar mirror
  runs at 5.56 ms while W.4 measured the real `SoftwareConverter.toRgba` at about 9.4 ms for the
  same frame size. The mirror is not the real function (one format, and it reuses its output buffer
  rather than allocating 8.3 MB per call), so this is a lead rather than a like-for-like claim. But
  it is a large enough gap to cost first: making the existing scalar path cheaper needs no
  architecture change, carries none of the shared-pipeline risk, and would help Android as well as
  desktop. W-14 should not proceed to the pipeline change until that is measured and either taken
  or ruled out.
- **SUPERSEDED 2026-08-17 by W-19, and closed rather than left open.** This item existed because the
  desktop upload cost 11.6 ms per 1080p frame with 81% of it in the CPU conversion. W-19's row
  parallelism took the conversion from 6.33 ms to about 2.1, and the OTHER half was then measured
  directly rather than inferred: the Skia raster image build is **0.44 ms**, not the roughly 2.2 ms
  a percentage split of the loaded W.4 number implied. The whole upload path is therefore about
  2.5 ms now.
  Against a 33 ms budget at 1080p30 that is about 7% of a frame, and the price of taking it is the
  shared frame-pipeline change this item's own amendment describes: `FrameImage` and the draw call
  both move, in commonMain, shared with Android and iOS whose paths are working and pinned. That is
  not a trade worth making for 2.5 ms, so W-14 is CLOSED as superseded rather than parked.
  What it leaves behind is worth keeping and is not lost: the amendment's verified route (the
  native canvas with a Skia Paint, since ShaderBrush refuses a skia Shader on this Compose version),
  the API notes, and the measured fact that SkSL on a RASTER surface is 6.7x slower than the scalar
  loop. If 4K or a high-refresh display ever makes 2.5 ms matter, this item is the starting point
  and none of its findings need rediscovering.
- Sub-phase: W.11. Test, three arms, each proved able to fail:
  1. CORRECTNESS. The shader's output is compared against `tightlyPackedToRgba` for the same
     synthetic frames the colour instrument uses, within the tolerance `ColourInstrumentTest`
     already pins. A wrong matrix or a wrong range must fail it.
  2. THE BOUNDARY. A 10-bit frame and a PQ/HLG frame take the CPU path, asserted by observing the
     converter being called, so a future widening of the shader cannot silently swallow HDR.
  3. THE NUMBER. `KiteVideoUploadProfiler` re-measures the same 320-frame alternating phases W.4
     used, on the same clip, and the mean and p95 are written into
     `kiteplayer-sample-desktop/MEASUREMENTS.md` beside the existing ones. The exit is that the
     CPU cost per frame FALLS; if it does not, the item is recorded as measured-and-rejected
     rather than merged, because a shader that is not faster is only more surface.

#### W-15. The conversion loop is 94% of the desktop upload, and its shape is the cost
- Where: `kiteplayer-ffmpeg/src/commonMain/.../Conversions.kt:111 tightlyPackedToRgba`, reached from
  every software path on every platform.
- Measured first, 2026-08-17, on an UNLOADED machine with a real decoded 1080p frame, by
  `ConversionCostTest` in this module's jvmTest, which is kept as the rerunnable baseline:

  | step | mean | p95 |
  |---|---|---|
  | whole `SoftwareConverter.toRgba` | 6.73 ms | 7.76 ms |
  | JNI `copyPlanesToByteArray` alone | **0.31 ms** | 0.37 ms |
  | the conversion loop alone | **6.33 ms** | 6.82 ms |

- Problem, and TWO EARLIER GUESSES THIS KILLS. The loop is 94% of the cost, so it is the right
  target. But the JNI plane copy was suspected of being several milliseconds and is 0.31 ms, so
  nothing should be spent there. And W-14's note that a one-format mirror ran at 5.56 ms against a
  presumed 9.4 ms real function was wrong twice over: the real function is 6.33 ms, not 9.4 (the
  9.4 was W.4's LOADED host, whose load average it recorded as 5.8 to 9.2), and the mirror is
  therefore only 14% faster, not 1.7 times. Generality is NOT what this loop is paying for, so
  specialising it per format would buy about 14% and is not worth the duplication.
- What the cost actually is, stated as a hypothesis to be tested rather than a conclusion: the loop
  does 2.07 million iterations, each doing Double arithmetic, three `readPackedComponent` calls and
  four `coerceIn` clamps, and it allocates an 8.3 MB output array per frame. Two changes are worth
  measuring, in this order, because they are independent and the first is far cheaper to make:
  1. **Fixed-point integer arithmetic** in place of Double, with the clamps folded into the same
     integer step. No contract changes, no API changes, one function.
  2. **Row parallelism.** The loop is embarrassingly parallel per output row and the machine has 8
     cores, so this is the change with real headroom. It needs a parallel-for seam, which
     commonMain does not have, so it costs an expect/actual and is the reason it is second rather
     than first.
- Fix, decided: take change 1, measure, and take change 2 only if change 1 leaves enough on the
  table to justify a new seam. Every one of the 21 pixel-format cases and the `HdrToneMap` call
  keep working, pinned by the colour tests that already exist.
- **CHANGE 1 IS MEASURED AND REJECTED, 2026-08-17.** It was built (16.16 fixed point, then 32.32 in
  Long) and timed: the conversion loop ran at 6.07 ms against a 6.33 to 6.49 ms baseline measured
  either side of it. Run-to-run variance on this machine is about 0.4 ms, so a 4% difference is
  not a result. The Double law is restored byte for byte and the working tree carries no trace.
  Two hard findings came out of it and are why this is closed rather than retried:
  1. **Byte-exact equivalence with the old law is IMPOSSIBLE, not merely hard.** At 16 bits the
     equivalence walk found bt601 limited range at y=3, cb=0, cr=13, whose true green is 128.4937,
     six thousandths below the rounding boundary. Widening to 32 bits fixed that and found a
     harder one: bt601 FULL range at y=222, cb=3, cr=0, whose true blue is EXACTLY 0.5, because
     1.772 times 125 is exactly 221.5. There the Double oracle's own answer comes from IEEE
     representation artifacts (its 1.772 is 1.77199999999999997, so it lands a hair above the half
     and rounds up), and no finite fixed point reproduces that. Any integer rewrite therefore
     changes some pixels by one, which is a silent picture change this project does not take for
     4%.
  2. **Arithmetic is not what this loop pays for.** 2.07 million pixels in 6.3 ms is about 3
     nanoseconds each, roughly 10 cycles at 3 GHz, for six multiplies, three loads and four stores.
     Ten cycles does not buy six multiplies plus the memory traffic; the loop is load and store
     bound. That is also why change 1 could not have won, and it is a direct argument FOR change 2:
     a throughput-bound loop is exactly what more cores help.
- **The remaining lever is change 2, row parallelism**, and it now has evidence behind it rather
  than a hunch. Whoever takes it should expect the seam (an expect/actual parallel-for in
  commonMain) to be most of the work, and should measure before believing.
- Sub-phase: W.12. Test: `ConversionCostTest` re-run for the number, and the existing colour
  correctness suites for the behaviour, which must not move by a single byte. Proved able to fail
  by perturbing one coefficient, which the colour tests must catch.
- Exit: the loop's mean falls. If it does not, this is recorded as measured-and-rejected like
  W-14's raster variant, and the desktop conversion cost is accepted as the price of software
  decode until the GPU path of W-14 is built.

#### W-16. The JVM jar carries a macOS library only, so the desktop JVM is macOS only
- Where: `../KiteCodec/kitecodec-core/build.gradle.kts` `stageHostJniForJvm`, guarded on an arm64
  Mac host; `buildSrc/src/main/kotlin/BundleHostJniTask.kt`;
  `kitecodec-core/src/jvmMain/.../JniLibrary.jvm.kt`.
- Problem: W-01 and W-02 made the JVM variant real and self-contained, but only for `macos-arm64`.
  A Linux or Windows JVM resolves the artifact and then finds no library for its platform. The
  loader already handles that honestly (it reads `kitecodec-native/<os>-<arch>/manifest.txt` for
  ANY platform and says in one sentence what is missing), so the gap is entirely in what the build
  produces, not in what the runtime can consume.
- **PROVED END TO END BEFORE BEING WRITTEN, 2026-08-17.** The whole path was walked by hand and the
  recipe below is what worked, not what should work:
  1. JNI headers come from a JDK CONTAINER at build time and are never committed:
     `docker run --platform linux/arm64 -v <out>:/out eclipse-temurin:21-jdk sh -c 'cp -r
     $JAVA_HOME/include/. /out/'` yields `jni.h` and `linux/jni_md.h`. A macOS JDK has
     `include/darwin` and cannot supply these. Extracting rather than vendoring keeps an
     OpenJDK-licensed header out of this repository, which is a licence decision the owner has not
     been asked for and does not need to be.
  2. The C helper layer and the JNI adapter cross-compile with konan's clang over konan's linux
     sysroot, exactly as `CompileKiteCodecCTask` already does for other targets: 11 helper objects
     and 9 adapter objects, `-O2 -std=c11 -fvisibility=hidden -fPIC -Wall -Wextra -Werror`, with
     `KC_BUILD_FFMPEG_REF`, `KC_BUILD_FFMPEG_LICENSE` and `KC_BUILD_FFMPEG_DIR` defined.
  3. The link needs `-fuse-ld=lld`, both `-B` directories (konan LLVM and the gcc runtime beside
     the sysroot) and `--version-script=native/kitecodec-jni/exports.map`, against
     `native-libs/lgpl/linux-arm64` plus `-lz -lm -ldl -lpthread`. Output: a 16 MB ELF aarch64
     shared object.
  4. VERIFIED BY RUNNING IT, not by asserting it exists. In an arm64 Linux container with a real
     JVM and the published `kitecodec-core-jvm` jar on the classpath, the library loads and the
     ordinary Kotlin API answers: identity acceptable, avcodec 62.11.100, h264 decoder present,
     on `Linux/aarch64`.
- **A property worth keeping**: because the link uses konan's glibc 2.25 sysroot rather than a
  modern distro's, the result runs on glibc 2.25 and newer. Building it inside a bookworm container
  would have pinned the floor at 2.36 and quietly excluded older distributions.
- **A trap that cost time and is written down so it does not cost it twice**: mounting the output
  directory at `/lib` inside the container shadows the container's own libc and loader, and every
  dynamic binary then fails with `no such file or directory`, which reads like a missing file or an
  architecture mismatch and is neither. Mount anywhere else.
- Fix, decided: a `linkKiteCodecJniLinuxArm64` and `...LinuxX64` beside the macOS and Android arms,
  a header-extraction task feeding them, and `BundleHostJniTask` extended to stage every built
  platform into the jar rather than only the host's. The loader needs no change at all.
- Sub-phase: W.13. Test: the container probe above, promoted from a hand-run to a script, asserting
  the three answers rather than printing them. Proved able to fail by staging a truncated library.
- Not in scope: Windows. A `.dll` needs a Windows JDK's headers and a PE link, and there is no
  Windows machine here to run the resulting library on, so it stays where the rest of Windows is.

#### W-17. The C audit instruments cannot see a non-Apple sink (SOL-API's W-09 half)
- Where: `kiteplayer-rt/native/scripts/render-audit.sh` (657 lines),
  `kiteplayer-rt/native/scripts/source-discipline.sh` (381 lines), and the `build-host.sh`
  interposer in both repositories.
- Problem: 17.11 records these as "Mach-O and CoreAudio shaped". Read closely, 2026-08-17, they
  have TWO DIFFERENT problems and conflating them would produce the wrong fix.
  1. `render-audit.sh` audits the SHIPPED OBJECT, and that half is genuinely format bound. It
     assumes Mach-O's leading underscore in every allowlist (`_memcpy`, `_kprt_render_cb`,
     `_mach_absolute_time`), and it pins the AudioUnit four-character subtype by reading
     `otool -s __TEXT __literal8`, which exists only in Mach-O. Its `nm` calls are NOT a problem:
     Xcode's `nm` is LLVM's and reads ELF, which W.5 already relied on for the FFmpeg cross build.
  2. `source-discipline.sh` reads `kite_rt_coreaudio.c` BY NAME and pins the ordering inside
     `kprt_sink_destroy`, the release store in `kprt_sink_attach_ring`, and so on. That is not
     Mach-O shaped and it is not wrong: those rules are ABOUT the CoreAudio sink, and they should
     stay about it. What is missing is not portability but a SECOND rule set, for whichever backend
     lands next. 17.11's own words for this are "per-backend rule sets keyed by the file under
     audit", which is exactly right and is not the same job as reading ELF.
- Fix, decided, in that order:
  1. `render-audit.sh` learns the object format from the object rather than assuming it: one
     `symbol_prefix` derived once, applied to every allowlist, and the `__literal8` AudioUnit pin
     made conditional on a Mach-O CoreAudio object instead of unconditional. All 43 checks keep
     working on Mach-O and keep being able to fail there; the ELF arm proves the same scan runs on
     the linux_arm64 archive `CompileKiteRtTask` already produces.
  2. `source-discipline.sh` gains nothing until a second sink exists, and this register says so
     rather than inventing a rule set for a backend nobody has written. When ALSA or WASAPI lands,
     its rules arrive WITH it, in the same commit, because a device sink without its own
     source-discipline rules is a sink with no proof.
  3. The `build-host.sh` interposer stays Mach-O. It exists to run the host C suites on this
     machine, and a Linux host running them would build its own; porting it now would be building
     for a machine that does not exist (18.3 rule 4).
- Sub-phase: W.14. Test: `render-audit.sh --prove-it-can-fail` must still refuse all of its planted
  defects on Mach-O, and the ELF arm must run the symbol scan against the linux_arm64 archive and
  be shown able to fail there too, by planting a forbidden symbol in an ELF object.
- Honest bound: this makes the instrument READY for a non-Apple sink. It does not make one exist,
  and it does not prove anything about ALSA or WASAPI, because neither is written.

#### W-18. The ring pointer is on core's PUBLIC ABI, which is the half of SOL-API6 that is wrong
- Where: `kiteplayer-core/src/nativeMain/.../spi/NativeRingAudioSink.kt:91` (`NativeRingHandoff.ring`
  is a `CPointer<kprt_ring>`), its one producer
  `kiteplayer-output/src/appleMain/.../CoreAudioSink.kt:325`, its one consumer
  `kiteplayer-core/src/nativeMain/.../internal/AudioPath.native.kt:35`, and the ratchet
  `kitert-coupling-baseline.txt`.
- Problem, narrowed by reading the ratchet rather than the row. SOL-API6 says the pointer is on the
  core ABI AND that this drags the cinterop klib into core, and asks for an opaque writer owned by
  rt or output. The second half is ALREADY A DECIDED MATTER: the coupling baseline names three core
  files that may spell `cnames.structs.kprt_`, and two of them are deliberate and permanent.
  `NativeAudioRing.kt` is the C ring's Kotlin wrapper, which register item B1-17 requires to exist
  in core, and `AudioRingDifferentialTest.kt` is the oracle that B1-20 says is the only thing
  keeping the C ring and the Kotlin ring from drifting apart. Moving either out would move the
  oracle away from the module it tests and invert the layering, for no gain the row asks for. The
  third file is the only one that is actually wrong, and it is wrong for the FIRST reason only:
  `NativeRingHandoff` puts a cinterop type on core's PUBLIC surface, so every consumer of the SPI
  reads a `CPointer<kprt_ring>` in the API dump whether it wants the raw ring or not.
- Fix, decided: the handoff carries the ring's ADDRESS in an opaque value class rather than a typed
  pointer. `NativeAudioRing`, which is already allowed to name the C type, converts the address back
  to a pointer at the one place that adopts it, and `CoreAudioSink`, in a module the baseline
  excludes by design because it owns the C sink pointer, converts its pointer to an address at the
  one place that produces it. Nothing moves modules, the oracle stays where it is, and no cinterop
  type appears in core's public API dump.
  What this deliberately does NOT claim: an address behind `@RawRingApi` is no safer to misuse than
  a typed pointer was. It was never the type that made this safe; the opt-in marker and the fact
  that exactly one producer and one consumer exist are what make it safe, and both stay true.
- Sub-phase: W.15. Test: `checkKitertCoupling` goes from three allowed files to two, with
  `NativeRingAudioSink.kt` removed from the baseline, which is the mechanical proof the cinterop
  name left that file. `checkKotlinAbi` moves the core klib dump, and the moved declarations are
  named in the log. The existing `AudioRingDifferentialTest` and the CoreAudio host suites must stay
  green, since neither the ring nor the sink changes behaviour. Proved able to fail by restoring the
  typed pointer, which puts the file back in the baseline and fails the ratchet.

#### W-19. Row parallelism is the conversion's only remaining lever, and it MEASURES
- Where: `kiteplayer-ffmpeg/src/commonMain/.../Conversions.kt:111 tightlyPackedToRgba`;
  `ConversionCostTest.measureTheParallelCeiling` in this module's jvmTest.
- Measured first, 2026-08-17, unloaded, using the REAL converter on horizontal slices of a 1080p
  frame rather than a mirror of it, 40 timed iterations after warmup:

  | tasks | mean | p95 | speedup |
  |---|---|---|---|
  | 1 | 6.36 ms | 7.29 ms | baseline |
  | 4 | 1.89 ms | 2.96 ms | **3.36x** |
  | 8 | 1.73 ms | 2.42 ms | **3.68x** |

  This is the first of W-15's three candidate changes to pay, and it pays by a lot. The raster
  shader measured 0.15x and the fixed-point rewrite 1.04x, both rejected on their numbers; this
  one takes the conversion from 6.36 ms to 1.89 ms on four tasks. It also confirms the reason:
  a loop that scales with cores was bound by memory throughput, not by arithmetic, exactly as
  W-15 concluded.
- Problem: `tightlyPackedToRgba` converts one frame on one thread. Every software path on every
  platform pays the whole 6.36 ms serially.
- Fix, decided: the row loop splits into slices converted concurrently, behind ONE new expect/actual
  `parallelFor` in `kiteplayer-ffmpeg`'s commonMain. The work is embarrassingly parallel by
  construction: each slice writes a disjoint range of output rows and reads a disjoint range of
  input rows, so there is no shared mutable state and no ordering between slices.
- The actuals, and the honest asymmetry between them:
  - jvm and android: a small fixed pool, sized from the available processors.
  - native (Apple, linux, mingw): the same, over the platform's threads.
  - **js and wasmJs: SEQUENTIAL, and that is the correct actual, not a stub.** Those targets have
    one thread by construction, so `parallelFor` there runs the slices in order and the conversion
    behaves exactly as it does today. A web port loses nothing it ever had.
- Two costs stated before the work, so neither is discovered as a surprise:
  1. Slice heights must be EVEN for a subsampled layout, or a slice's chroma rows do not line up
     with its luma rows. The measurement already respects this and the implementation must too.
  2. Thread handoff is not free. At 1080p it buys 4.5 ms and is obviously worth it; on a small
     frame it would not be. The implementation takes a threshold below which it stays sequential,
     and the threshold is MEASURED rather than guessed.
- Sub-phase: W.16. Test: `ConversionCostTest` for the number, and the existing colour suites for the
  behaviour, which must not move by a single byte, since slicing changes only WHERE the same
  arithmetic runs. Proved able to fail by forcing one slice to skip its rows, which the colour tests
  must catch.
- Exit: the conversion's mean falls on the JVM and every colour test stays green. If a threshold
  cannot be found that keeps small frames from regressing, the item is recorded as
  measured-and-rejected like its two siblings.
- **DONE 2026-08-17, with three corrections to this item's own text.**
  1. There is no expect/actual. This module compiles for no js or wasmJs target, so the sequential
     web actual this item promised has nothing to be an actual FOR, and every target it does have
     carries a multi-threaded `Dispatchers.Default`. One `runBlocking` plus `coroutineScope` in
     commonMain does the whole job, and the module already used `runBlocking` on this side of the
     code. A seam that buys nothing is surface, so it was not built.
  2. The threshold is on PIXELS, not rows, and the measurement forced that: 640x240 in parallel
     beat 426x238 sequentially despite carrying half again as many pixels, so height alone was the
     wrong axis and a wide short frame would have been stranded on one core.
  3. The threshold's value is measured on both sides rather than assumed. Sequential against four
     slices, in mean milliseconds: 64x64 0.013 against 0.116, 160x120 0.068 against 0.201, 256x144
     0.134 against 0.099, 320x180 0.210 against 0.156, 640x360 0.765 against 0.416. The crossover
     is between 19k and 37k pixels and the constant is 65536, above it with margin.
  Result on a real 1080p frame: the conversion loop falls from 6.33 ms to about 2.1 ms and the whole
  `SoftwareConverter.toRgba` from 6.73 ms to about 2.3 ms. Every colour suite stays green on the JVM
  and on macOS native, and the pins are proved able to catch this exact class of bug: making one
  slice skip its rows fails `ColorPolicyTest`.

#### W-20. The Linux JVM library is proved to LOAD, not to PLAY
- Where: `../KiteCodec/scripts/linux-jni-probe.sh`; the jvm arm of `:kiteplayer-ffmpeg`, whose
  `FormatMatrixTest` runs the whole 17.5 matrix but only ever on this macOS host.
- Problem: W-16 put linux-arm64 and linux-x64 JNI libraries in the published jar and proved them by
  running a real JVM in a container, which is genuinely more than "the file exists". But what it
  asserts is narrow: the identity gate is acceptable, and h264 and hevc are present. Nothing has
  DECODED on Linux through the JVM path. Meanwhile the macOS JVM runs all 27 matrix rows, and the
  Kotlin/Native Linux path runs 86 tests including the matrix in a container. The one combination
  with a real shipped artifact and no real playback evidence is the one a desktop Linux consumer
  would actually use.
- Fix, decided: run the module's own jvm test suite inside the Linux container rather than writing a
  second, weaker probe beside it. That needs the test runtime classpath, which Gradle knows and a
  container does not, so the build gains a task that prints it, the way `:kiteplayer-sample-desktop`
  already prints its run classpath for the KV-5 measurement. The script then mounts the repository,
  the testmedia tree and that classpath into an `eclipse-temurin` container and runs the JUnit
  console over it. Nothing about the tests changes: the same `FormatMatrixTest` that guards macOS
  guards Linux.
- Why not the simpler thing: a hand-written "open each file and decode a frame" probe would be
  quicker and would prove less, and it would drift from the matrix the moment a row was added. The
  matrix is already the project's one definition of "plays all formats" (17.5), and a second
  definition is worse than none.
- Sub-phase: W.17. Test: the container run itself, with its pass count recorded, and a falsification
  arm that stages a truncated JNI library and confirms the run fails rather than skipping.
- Honest bound: a container has no audio device, so this proves DECODE across the matrix on Linux
  and says nothing about the desktop audio sink there. The `javax.sound.sampled` sink stays proved
  against a fake device seam only, exactly as W.3 recorded.
  Result: `scripts/linux-jvm-tests.sh` runs the module's whole jvm suite in an `eclipse-temurin:21-jdk`
  container against the jar's own bundled Linux library, with no `kitecodec.jni.path` override.
  **60 tests green and all 27 matrix rows PASS on linux/arm64, and 60 green with 27 rows on
  linux/amd64 as well**, so both shipped JNI libraries decode, not just the native-speed one. The
  classpath comes from a new `:kiteplayer-ffmpeg:printJvmTestRuntimeClasspath` task and is mounted at
  its own absolute paths, so nothing rewrites it; the script refuses to run if any entry falls
  outside the three mounted roots, and refuses if `FormatMatrixTest` is not among the classes it
  discovers. Falsification: with a 4 KiB zero-filled `libkitecodec_jni.so` handed to
  `-Dkitecodec.jni.path`, the run FAILS rather than skipping, 60 run and 7 failed on
  `invalid ELF header`, `FormatMatrixTest` among them. The x86_64 run also puts a number on emulation
  rather than leaving it as folklore: the same 1080p conversion measures 2.0 ms on arm64 and 8.4 ms
  under qemu.

**Sub-phases, in execution order.**

- **W.1 The JVM variant becomes real** (W-01, W-02). KiteCodec. Commit: "Let the published JVM
  variant carry the JNI it already has".
- **W.2 The player's JVM backend** (W-03). Commit: "Give the JVM target the backend its Android
  twin already runs".
- **W.3 The desktop output backend** (W-04). Commit: "Hear and read the picture on a desktop JVM".
- **W.4 KiteVideo on the desktop, measured** (W-05). Commit: "Draw the desktop frame once per
  upload, and say what it costs".
- **W.5 The Linux and Windows trees, and a desktop publication** (W-06, W-07). Commit: "Build
  FFmpeg for the two desktops this machine can cross-compile".
- **W.6 The device sink splits from its platform, and the audits follow** (W-08, W-09). Commit:
  "Let a sink exist that CoreAudio did not write".
- **W.7 The S3 register rows** (W-10). Commit: one per row group.
- **W.8 The Linux run** (W-11). Commit: "Run the engine's own tests on Linux, and say so".
- **W.9 The web spike** (W-12). Commit: "Measure the web, and record the verdict".
- **W.10 The pairing refuses in one typed sentence** (W-13). Commit: "Refuse a foreign frame
  once, in words a consumer can read".
- **W.11 The desktop converts on the GPU, for the layouts that can** (W-14). CLOSED as superseded
  by W-19; no commit, and the reason is on the item.
- **W.12 The conversion loop stops paying for its shape** (W-15). Commit: "Convert the same
  pixels with less arithmetic".
- **W.13 The jar carries a library for more than one desktop** (W-16). Commit: "Give the jar a
  Linux library, proved by running it".
- **W.14 The render audit reads the object it is given** (W-17). Commit: "Audit the shipped
  object whatever format it is in".
- **W.15 The ring crosses as an address, not as a type** (W-18). Commit: "Take the C ring
  pointer off the public ABI".
- **W.16 The conversion uses the cores it has** (W-19). Commit: "Convert the frame on more
  than one core".
- **W.17 The matrix runs on the Linux JVM** (W-20). Commit: "Decode the whole matrix on a
  Linux JVM".

**Status, verified commit by commit 2026-08-18.** All seventeen sub-phases are accounted for.
Fifteen landed under the titles named above, across both repositories (W.1, W.5 and W.13 are
KiteCodec commits, which is why they do not appear in KitePlayer's log). The two without a commit
are closed rather than missing, and both for the same reason: **W.11** was CLOSED as superseded by
W-19, and **W.12** CONCLUDED into W-19 as well, its three candidate changes measured and two
rejected on their numbers (the raster shader at 0.15x and the fixed-point rewrite at 1.04x, against
the parallel slice's 3.36x on four tasks). No sub-phase is silently unfinished.

**The honest bound on this phase, written before it starts.** 17.3 estimates S3 at 70 to 108
hours and S6 at 80 to 120. Nothing in this expansion changes that arithmetic. What this phase
delivers is bought in the order above, each sub-phase gated and committed on its own, and the
Execution log entry for each says what it measured and what it did not. Two exits are owner-blocked
by construction and are named here so no reader mistakes their absence for an oversight: the
Windows matrix run needs a Windows machine, and the physical-device halves of the desktop
measurements need machines that are not this one.

