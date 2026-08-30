# GOTCHAS

**Admission test, one line: if reading the code or running the gate would teach you this, it
does not belong here.** This file holds only what CANNOT be learned from the tree: machine and
toolchain traps that were paid for, decisions with their reasons, invariants whose violation
looks fine until a device burns you, and the working rules of this project. It covers BOTH
repositories (KitePlayer and its sibling `../KiteFFmpeg`). The work that is LEFT lives in
`MASTER_PLAN.md`. History lives in git.

---

## 1. Working rules

1. Both repos work on `main`. Never create a branch. Commit locally, never push; the owner
   pushes. The owner also runs every publish and every release step.
2. Commit style: first line is one imperative sentence describing the outcome. Short prose
   body. No Co-Authored-By, no trailers of any kind.
3. No em dashes in ANY file: code, comments, Markdown, commit messages. The Tier 1 scan
   catches them (section 3).
4. `MASTER_PLAN.md` is updated BY THE SAME COMMIT that changes the tree: finished work is
   DELETED from it (the commit message is the record), discovered work is added, half-done
   work is reduced in place with its remainder named. A commit that changes behaviour and does
   not touch MASTER_PLAN is incomplete unless it genuinely answers and opens nothing.
5. This file grows only when something bites that the code could not have told you. It shrinks
   when a trap stops existing (toolchain moved on, code deleted). Trivia dies at review.
6. Talking to the owner: plain words, no register codes, no jargon walls. Say what a thing
   MEANS, not what it is. A decision request must be answerable by someone who has read
   nothing. If an ID appears at all, it goes in brackets after the plain words.
7. Every behavioural fix: write the failing test FIRST, watch it fail at the predicted line,
   fix, watch it pass, then falsify (revert or mutate the fix; the test must go red again).
   A test never seen red proves nothing.
8. Evidence honesty, compressed from the old evidence ladder: a claim carries the strength of
   its evidence and no more. Compilation is not support. A source-set declaration is not
   support. Laptop green is not device green. Simulator green is not device green. A cached
   UP-TO-DATE Gradle run proves only that the cache is not red; gates rerun for real. When
   code, artifact, docs and measurement disagree, the weakest result is the truth.
9. Size estimates rot exactly like claims. An estimate made behind a blocker is a guess about
   what the blocker hides; re-size when the blocker falls.
10. No new dependencies without an owner decision: not a library, not a plugin, not a
    toolchain or Gradle bump. C or shader source we author ourselves is fine.
11. `explicitApi()` is on in every KitePlayer module. Any public API change regenerates the
    ABI dumps in the same commit.
12. When the tree contradicts MASTER_PLAN or this file, STOP and report. Never improvise the
    plan back into truth; prose drifting from the tree is this project's measured failure mode.
13. Design acts are their own commits. Deciding a public API shape and executing it never
    happen in the same breath.
14. After any task: re-read every changed file once, run the selected gate tier, commit.
15. House Kotlin style when a task leaves a choice: sealed transactional outcomes over thrown
    control flow, structured finalizer scopes over hand-paired cleanup, ownership-aware lease
    APIs over raw handles, inline plane iteration over per-pixel calls, checked-size helpers
    over bare arithmetic, resource ledgers over close-and-hope. Style is guidance, never a
    task of its own.

## 2. Legality

- KitePlayer ships Apache-2.0. mpv, VLC, ffplay, QMPlay2 and libplacebo (gitignored clones in
  `vendor/`) are GPL/LGPL: STUDY ONLY. Designs, algorithms and thresholds are facts and may be
  restated; source text is expression, and a Kotlin transliteration inherits the licence.
  Never transliterate their code. Never name a study-only source in a code comment as the
  origin of an implementation.
- Android Media3 and ExoPlayer are Apache-2.0: may be ported directly with credit in NOTICE.
- `ffmpeg` and `ffprobe` binaries as test oracles: always fine. Differential testing compares
  outputs, never source.
- KiteFFmpeg's NOTICE names ffmpeg-n8.0 as the LGPL source offer for versions Maven Central can
  never withdraw; that release tag is kept forever (binaries live at ffmpeg-n8.0-r2).

## 3. The gate

Three tiers, selected by CHANGED PATH, never by confidence. Every log or commit that claims a
gate names the tier and the rule that selected it.

**Tier 1, every change without exception, seconds:**

```bash
cd ../KiteFFmpeg
./gradlew checkCinteropCoupling
./gradlew :kiteffmpeg-core:checkFFmpegRecipes
./native/kitecodec-c/scripts/check-deleted-surface.sh
./native/kitecodec-c/scripts/run-c-tests.sh plain

cd ../KitePlayer
./gradlew checkKitertCoupling
./gradlew checkKotlinAbi
./gradlew :kiteplayer-core:jvmTest :kiteplayer-subtitles:jvmTest
kiteplayer-rt/native/scripts/run-c-tests.sh plain
kiteplayer-rt/native/scripts/render-audit.sh
kiteplayer-rt/native/scripts/source-discipline.sh

# Em dash scan, both repos, must print NOTHING. The pattern is the escape text backslash-u2014
# (expanded by the shell) so no literal em dash exists in the repos, this file included. grep
# exit 1 IS the passing outcome; do not wrap in set -e and read that exit as failure.
cd ../KiteFFmpeg  && git ls-files -z | xargs -0 grep -n $'\u2014'
cd ../KitePlayer && git ls-files -z | xargs -0 grep -n $'\u2014'
```

What Tier 1 cannot catch: data races (tsan), wrong-architecture archives, cinterop surface
changes, real-media regressions, anything about a target it did not build. It DOES catch a
vendored FFmpeg tree baked from a different recipe than the checkout describes.

**Tier 2, roughly 10 to 15 minutes.** Selected by ANY of: files under `native/` or `buildSrc/`
in either repo, `kiteffmpeg-gradle-plugin/src/`, any `*.def` or `build.gradle.kts` or version
catalog, any Kotlin under a platform source set (nativeMain/Test, jvmMain/Test,
jvmAndAndroidMain/Test, androidMain, androidHostTest, androidDeviceTest, appleMain/Test,
macos*/ios*/linux*/mingw* Main or Test, realBackendTest), or the completion of any major
program item. Contents: Tier 1 plus, in KiteFFmpeg: host cinterop + apiCheck (both need
`-Pkiteffmpeg.hostTargetsOnly=true` on this machine), buildSrc and plugin tests, asan + tsan +
interpose C runs, corpus replay, symbol audit, klib metadata diff, macosArm64Test, jvmTest,
`./scripts/linux-tests.sh`; then the three-flag publish (section 4) when KitePlayer must see
the change. In KitePlayer: `./scripts/testmedia.sh` FIRST (fixtures are gitignored and
generated; a clean clone has none), buildSrc test, macosArm64 suites (core, output, ffmpeg),
`:kiteplayer-view:iosSimulatorArm64Test`, asan + tsan + interpose C runs, desktop jvm suites
(output, mobile, ffmpeg), `:kiteplayer-output:wasmJsNodeTest`, `./scripts/linux-tests.sh`,
`./scripts/linux-jvm-tests.sh`, the mingw link
(`:kiteplayer-ffmpeg:linkDebugTestMingwX64 -Pkiteffmpeg.ffmpeg.localRoot="$PWD/../KiteFFmpeg/native-libs"`),
cross-compile spot checks (js, wasmJs, android), and the sample runs over
`sync1080p30.mp4` / `truevfr720.mp4` / `hevc4k10.mp4` / a nonexistent path. A sample miss on a
loaded machine that passes on two quiet reruns is recorded as a load observation, not rerun
until green.

**Tier 3, about 50 minutes, supervised.** Selected by: any change to
`kiteplayer-rt/native/src/kite_rt_render.c`, to `kprt_render_cb` or the ring handoff and
teardown ordering in `kite_rt_coreaudio.c`, to the ordering of AudioPlayback submit/flush/close
or PlaybackCore teardown, any support-tier promotion, or a release artifact. Contents: Tier 2
plus the supervised ten-minute device run and its negative control. Its numbers are one
machine, one operator, a manual observation; never present them as automated qualification.

**Ratchets move by procedure, in the same commit as the change, with the old and new numbers
in the commit message.** The baselines: KiteFFmpeg API dumps (`apiDump` with the target flags),
KitePlayer API dumps (`updateKotlinAbi`), `kitert-coupling-baseline.txt`, KiteFFmpeg's
`coupling-baseline.txt`, `klib-metadata-baseline.txt` (`klib-metadata-diff.sh --update`, paste
the SUMMARY block), `deleted-surface.txt` (status becomes `resurrected-in-<item>`),
`exported-symbols-baseline.txt` and `signature-baseline.txt`
(`symbol-audit.sh --write-signature-baseline`), and `ALLOWED_UNDEFINED` in `symbol-audit.sh`.
Never move one silently.

## 4. Build and toolchain traps, each one paid for

- **The atomicfu Gradle plugin is BANNED in every module.** Its bytecode transform registers a
  task depending on `androidMainClasses`, which AGP 9's KMP library plugin does not create, so
  applying it breaks the Android target. The library dependency is fine. This is the single
  most likely trap to re-trigger by "cleaning up" a build file.
- **Publishing KiteFFmpeg for KitePlayer needs ALL THREE flags:**
  `./gradlew publishToMavenLocal -Pkiteffmpeg.phoneTargetsOnly=true -Pkiteffmpeg.withDesktopTargets=true -Pkiteffmpeg.jni.linux=true`.
  A publish regenerates the root module metadata, so `-Pkiteffmpeg.hostTargetsOnly=true` alone
  DELETES the ios, linux and mingw variants from it and four unrelated gate steps fail at
  once. Found the hard way 2026-08-17.
- On this machine every KiteFFmpeg `apiDump`, `apiCheck` and cinterop invocation needs
  `-Pkiteffmpeg.hostTargetsOnly=true`: only macosArm64 has an FFmpeg tree here.
- mavenLocal is OPT-IN in KitePlayer, behind `-Pkiteplayer.useMavenLocal=true`, and it says so
  when on. Never re-add it unconditionally: same version string with different bytes is
  indistinguishable from Central's, and it nearly bit once.
- **FFmpeg's configure cannot handle a `#` anywhere in its path**, and this repo lives under
  `#Kite`. Build tasks therefore build in the system temp dir, never in the project tree.
  Gradle's `temporaryDir` is INSIDE the project; it fails.
- FFmpeg n8.0: `--disable-postproc` does not exist (configure fails), and `--disable-asm`
  silently kills SIMD (a "simd" build carrying it is a base build and its measurement a lie).
- **This machine's Android SDK is at `/Users/macbook/WORKSTATION/AndroidSDK`, not a standard
  path**, and KiteFFmpeg's native build only probes `ANDROID_NDK_HOME`/`ANDROID_NDK_ROOT`/
  `ANDROID_NDK_LATEST_HOME` then `~/Library/Android/sdk/ndk` and `~/Android/Sdk/ndk`. It never
  reads `sdk.dir` from `local.properties`, so any FFmpeg or dav1d build for an Android target
  fails here with "Android NDK not found" unless you export it first:
  `export ANDROID_NDK_HOME=/Users/macbook/WORKSTATION/AndroidSDK/ndk/29.0.14206865`. 29.0.14206865
  is the version CI uses, readable in any Android tree's own `ffmpeg-configure.txt`.
- **A rebaked FFmpeg tree DOES invalidate its consumers, and an earlier edition of this file
  said the opposite.** Measured three ways on 2026-08-29, each restored afterwards: changing a
  byte in a version header re-ran the C compile, the cinterop AND the link; changing a byte in
  `libavutil.a` re-ran the C compile and the cinterop; restoring it re-ran them again. The
  wiring is deliberate and documented at length in `kiteffmpeg-core/build.gradle.kts` beside the
  cinterop block, because cinterop's own up-to-date check covers headers and not the libraries
  its def merely names, so the archives are declared with `inputs.files`. `CompileKiteFFmpegCTask`
  additionally tracks the six version headers by CONTENT and carries the FFmpeg ref in its
  build defines. **Do not "fix" this**; the claim it was broken came from reading a SECOND
  invocation's UP-TO-DATE, which is simply the first invocation having already rebuilt
  everything, and it briefly reached a commit message.
- Publishing what a consumer actually gets is still worth one cheap check, on its own merits
  rather than because the build is untrustworthy: `unzip` the published
  `*-cinterop-ffmpeg.klib` and grep it for the expected `n8.x.y`. It is the only check that
  reads the bytes rather than the build's opinion of them.
- **mavenLocal accumulates per-target artifacts across publishes with DIFFERENT flags, and a
  narrower publish neither refreshes nor removes the others.** After a
  `-Pkiteffmpeg.phoneTargetsOnly=true -Pkiteffmpeg.withDesktopTargets=true` publish, the
  `androidnative*` variants sitting in `~/.m2` were two days old, simply because that flag
  combination does not publish them. Their age is not evidence of a stale build; judge freshness
  only for the variants the run actually published, which the log names as
  `publish<Target>PublicationToMavenLocal`.
- `-Pkiteffmpeg.jni.linux=true` needs a running Docker daemon (it extracts JDK headers from a
  container). Without Docker, publish without that flag and accept that the jar carries no Linux
  JNI libraries, which also means `linux-jvm-tests.sh` cannot run.
- **Moving or renaming a checkout directory breaks the prebuilt C test binaries.** They carry an
  absolute rpath to their interpose dylib from link time, so after a move every suite aborts with
  `Library not loaded: @rpath/libkc_interpose_alloc.dylib` naming the OLD path, which reads like
  a broken test and is a stale binary. Re-run the variant's `build-host.sh` and they pass again.
  Tier 1 says to rebuild the C suites only when a C file changed; a directory move counts too.
- **A backtick test name containing a COMMA compiles on JVM and breaks every Kotlin/Native
  target**, with `Name contains illegal characters: ","`. It has bitten twice: once in
  `AudioSinkEventTest`, where it had been red since the test landed, and again the same week in a
  new file, because a `jvmTest` run is green and says nothing. Any commonTest addition needs one
  native compile before it is believed.
- **`./gradlew ... | tail` hides the build's exit code**, because the pipeline reports the exit
  of `tail`. A background bake reported success while `BUILD FAILED` sat in its own log. Pipe to
  a file and echo `$?`, or check the log for BUILD FAILED; never read a piped gradle run's
  status as the build's.
- A clean clone needs `local.properties` (or `ANDROID_HOME`) before `assembleAndroidMain` has
  a task graph, and `./scripts/testmedia.sh` before any real-media suite (fixtures are
  gitignored). `testmedia.sh` REFUSES an ffmpeg outside its pinned major.minor; when Homebrew
  bumps ffmpeg, CI goes red on purpose; the fix is the pin line plus regeneration.
- Kotlin/Native creates and then permanently disables `linuxX64Test`/`linuxArm64Test` tasks on
  a macOS host, so naming them is green by definition; Linux evidence is
  `./scripts/linux-tests.sh` (container) or the CI Linux job. Windows native evidence on this
  machine is a LINK claim only.
- (SOL-B7) The one Gradle 10 deprecation in both repos ("Project object as a dependency notation")
  belongs to AGP 9.2.1's KMP library plugin, proven by Gradle's own problems report. Nothing
  here is workable. Re-measure at the NEXT AGP bump, then never again until it moves.
- (F-ABI1) Kotlin's `abiValidation` on 2.4.10 emits ONLY jvm and klib dumps: Android public API
  (`KitePlayerView` and friends) is in NO dump and ships unguarded. A hand-rolled checker was
  refused as overbuild. Re-measure at each Kotlin bump.
- The generated wasm binding has two copies (generator output and the committed
  `wasmJsMain/.../KiteFFmpegWasm.kt`); `checkWasmBindingMirror` keeps them identical. If it
  fires, regenerate and commit both; do not hand-edit the committed copy.
- KiteFFmpeg CI fetches this repo's own prebuilt static FFmpeg trees (checksum-verified). A
  distro FFmpeg CANNOT be linked by Kotlin/Native on modern Ubuntu (glibc 2.29/2.34 refs vs
  konan's 2.19 sysroot). BtbN builds are shared-only and useless for the static embed.
- The two macOS CI jobs deliberately both build FFmpeg on a cold cache: one runs ratchets, the
  other builds the REAL reduced LGPL profile users get (every other desktop job links the
  runner's full GPL brew FFmpeg). Chaining or merging them was refused: independent failure
  signal is the point.
- FFmpeg bumps its library majors (the sonames) only at MAJOR releases, so moving along a
  minor or point line (8.0 to 8.1.2) is ABI-safe while 8.x to 9.x breaks all seven at once.
  Two pins exist and they are INDEPENDENT decisions: the vendored library we link
  (`BuildFFmpegTask.DEFAULT_SOURCE_REF`, bound to two more sites by the `FFmpegRefSite` check)
  and the host binary that generates test fixtures (`EXPECTED_FFMPEG_SERIES` in
  `scripts/testmedia.sh`). They do not have to match, and conflating them once already
  produced a wrong recommendation.
- The macOS deployment floor is one constant, `BuildFFmpegTask.MACOS_DEPLOYMENT_TARGET = "12.0"`,
  read by both macOS FFmpeg branches and both macOS C targets. 12.0 because konan imposes it.
- `recipeFingerprint` must stay IDEMPOTENT: `CheckFFmpegRecipesTask` fingerprints an
  already-fingerprinted set on the way back out. A non-idempotent synthetic token silently
  drops from the expected side and every tree reports stale. Pinned by its own test; do not
  weaken it.
- Never apply `AVFMT_FLAG_FAST_SEEK`, and never pass demuxer options from a config map without
  an allowlist. MP3 seeking correctness rests on `usetoc=0` AND fast-seek unset together.

## 5. Kotlin/Wasm and browser traps

- Kotlin/Wasm has NO bulk typed-array bridge: naive per-byte JS crossings run at roughly
  96,000 calls per second of audio and killed the first web IO path. Cross per CHUNK (one
  `@JsFun` per chunk; the tight loop lives in JS). The latin1 pack trick corrupts bytes over
  0x7F if anything UTF-8-encodes the string in transit; the 0..255 ramp test exists for
  exactly that, never weaken it to ASCII.
- Building a Skia raster from a Kotlin `ByteArray` costs 107 to 153 ms per 1080p frame (the
  GC-heap to Skia-memory crossing, 55 to 85 MB/s). The web renderer therefore keeps pixels in
  the codec module and uses canvas `putImageData` (2.5 to 2.9 ms). Never route web video
  pixels through the Kotlin heap.
- A Kotlin per-pixel conversion loop on wasm is about 5x slower than the same loop in JS and
  about 10x slower than FFmpeg's own sws_scale in the module. Convert in C, beside the decoder.
- `int64_t` across `@JsFun` needs `WASM_BIGINT` and arrives as a JS BigInt; emscripten's
  `ccall` has no type spelling for it, call the export directly. A silent truncation there
  corrupts every timestamp.
- Exporting all 196 binding entry points defeats emscripten dead-code elimination: raw module
  4x bigger, gzipped only ~6% bigger. Judge wasm size gzipped.
- Webpack rewrites `import(url)` at BUILD time, so `KiteFFmpegWeb.load()` fails inside a
  bundler ("Cannot find module") even though the file serves; bundled apps use `attach()`.
- Without COOP and COEP headers, importing the THREADED wasm artifact HANGS, it does not
  error. Feature-detect `self.crossOriginIsolated` before import. The default artifact stays
  single-threaded for exactly this reason.
- Every browser `AudioContext` starts suspended until a user gesture: the queue fills, the
  feeder backs off, the audio-mastered clock sits at zero. Correct behaviour, not a hang; the
  embedder must know.
- A hidden browser pane never fires `requestAnimationFrame` and suspends audio under the
  autoplay policy: an FPS readout from a hidden pane means nothing; measure per-frame COST
  spans instead.
- C structs are read from JS by byte offset: offsets come ONLY from the committed
  `ReportLayout.kt`, generated by the compiler via `wasm-report-offsets.sh` (it has a check
  mode). A wrong offset reads the neighbouring field and answers something plausible.
- emscripten warns `-fPIC` is meaningless for wasm; drop it there.

## 6. Engine invariants that bite (violating any of these caused a real bug)

- ALL session mutation happens on the actor (command execute or a pass handler). Never mutate
  session fields from another coroutine.
- A decoder belongs to its worker's dispatcher. Park the worker (`quiesce(deadline)`), mutate,
  `release(epoch)`. A refusal to park means FALL BACK, never force.
- Epochs: for an IN-PLACE track swap do NOT bump the epoch (video work must stay valid); a
  fresh queue is flushed TO the current epoch or the demux worker's offers are rejected. A
  fresh component must be aligned to the epoch the world is already at (the 0.0.12 lesson:
  every open after the first sat dead 10 s because one reset was missed).
- Any path that retires subtitle state must WITHDRAW the drawn overlay itself (publish an
  empty overlay with a bumped generation): the renderer is shared across sessions, the
  "did I publish" key is per-session, and the last text otherwise stays on screen forever.
- Every command reply completes exactly once (Applied, Discarded or Superseded). A
  `SelectTrack` can sit in `heldCommands` while a seek runs and execute one pass later; never
  assume same-pass execution.
- Frames and packets are AutoCloseable, closed exactly once, on the worker that owns them.
  The ring is freed only after the feeder is joined; flush requires both ring sides quiescent.
  A leaked 1080p frame is 3.11 MB; 4K is 24.9 MB.
- The cue alpha contract is PREMULTIPLIED end to end (both platform rasterizers naturally
  produce it; consumers upload unconverted). Premultiplying twice renders white text grey.
- `PacketQueue.dropBefore` trims by a packet's END and stops at a packet with no timestamps;
  callers pass an assumed duration for the missing-duration case. That single behaviour is
  the whole long-cue-survives-a-switch immunity; do not "optimize" it away.
- Interleaving relief runs only while some selected queue is held UNDER readiness by the
  budget; it cuts the fattest INACTIVE lane first. Cutting on any over-budget state eats the
  switch caches of every healthy paused session (broke five tests the first time).
- The downmix normalize policy is OFF by default (FFmpeg and mpv parity): merged surrounds sum
  unnormalized; `DownmixConfig(normalize = true)` bounds them. Tested both ways; do not flip
  the default.
- Pause consumes the final device anchor BEFORE freezing clocks (a late callback must not
  re-anchor a frozen clock), and resume re-arms a timestamp floor so a pre-pause device
  timestamp can never anchor the clock after resume (the S23 fix).
- The C real-time island stays C. The device callback has no allocator, lock, log or
  framework call, proven by disassembly (`render-audit.sh`); nothing managed ever runs on the
  device thread. `KotlinAudioRing` is the C ring's differential oracle; never delete the
  portable implementation.
- `BlockingMediaIo`'s `runBlocking` is safe because close never queues behind the demux lane;
  that reasoning is load-bearing, re-check it before touching either side.
- FFmpeg's `fd:` protocol dups but never rewinds, and a POSIX dup SHARES the file offset:
  reopening an fd item mutates the caller's descriptor. Both backend doors rewind before every
  open as the stopgap; the real cure is positional reads (planned).
- FFmpeg polls the interrupt callback only inside `find_stream_info` and the URL protocol
  loop; KiteFFmpeg's read/seek entry helpers and both custom-AVIO callbacks poll it too. And
  `hls_read_header` leaves `interrupt_callback` ZEROED on its SAMPLE-AES and
  `init_subtitle_context` branches: child contexts need the callback copied explicitly.
- `avcodec_find_decoder(AV_CODEC_ID_AV1)` returns libdav1d ahead of the native av1 decoder in
  every consumer build, so VideoToolbox AV1 can never engage without a by-name decoder choice.
  Hardware AV1 on Apple is a policy problem, not a hardware problem.

## 7. Platform truths measured on real hardware

- Android `FilterQuality` above None collapses to one `isFilterBitmap` flag: the drawing step
  cannot resample better than bilinear no matter what it is asked. Scaling quality on Android
  can only live in the GL blit (device-proven).
- Adreno 610 (Redmi Note 8), per 1080p draw over a 6.83 ms plain blit: dither +1.30 ms,
  deband +7.72 ms, Catmull-Rom kernel +22.01 ms. Dither is affordable on floor hardware, the
  kernel is not a default there. Every render-ladder rung ships bit-exact-off and opt-in
  until an owner decision on device numbers.
- Render-ladder passes have a characteristic failure: a pass that compiles, costs every tap
  and DOES NOTHING. Three of four ladder findings were exactly that. A test that only asks
  whether the code runs cannot catch it; golden-image deltas can.
- This Mac is an M2: no AV1 silicon, so it can only ever prove the AV1 hardware REFUSAL path.
  Positive proof needs an A17 Pro / M3 or newer.
- The four red `iosSimulatorArm64Test` cases are simctl-spawn host-boundary artifacts, not
  bugs.
- tvOS simulator tests cannot run here at all (missing simulator RUNTIME, not SDK), so
  `:kiteplayer-core:allTests` can never pass on this machine; name targets explicitly.
- iPhone feel-testing is Release-only: a Debug shared framework collapses the software frame
  path about 30x and invalidates any perceived-latency judgment.
- The proven device-debug workflow: reproduce the device bug in the virtual-time harness
  (`CoreHarness`, `MediaScript`) on the JVM first; every real device bug of the 2026-08-25/26
  sessions reproduced there before it was fixed. The exposing fixture class: a long-GOP anime
  MKV with a dense ASS track (~70,000 cues); short clean files hide these bugs.
- Synkplay pulls logs from an iPhone with
  `xcrun devicectl device copy from --device <id> --domain-type appDataContainer --domain-identifier com.yuroyami.syncplay.iosApp --source Documents/logs --destination <dir>`,
  and the in-room setting "Engine statistics logging" adds one stats line per tick.

## 8. Decisions with reasons. Do not reopen without new evidence

- **No platform demuxers or decoders as source of truth.** FFmpeg is the one media truth;
  hardware acceleration only as FFmpeg-internal decoders/hwaccels with software fallback.
- **No new mandatory native libraries.** Kotlin (or shader source we author) first; a native
  library only as an OPTIONAL module when no Kotlin path can exist (dav1d) or correctness
  parity demands it (libass). Applied verdicts: libxml2 NEVER (manifests parse in Kotlin);
  mbedtls/curl REJECTED (vendored crypto is a recurring CVE duty; TLS comes from the OS via
  Ktor engines: OkHttp, NSURLSession, browser fetch); libplacebo REJECTED as a dependency
  (its viewer-visible value is ~150 lines of shader we author; its correctness core is already
  shipped; it cannot follow the engine to wasm).
- **Native Linux/Windows https stays absent**: those targets have no OS TLS to delegate to and
  no output backend; desktop rides the JVM, which has https.
- **Every Android ABI stays supported, owner-ruled.** "minSdk 26 excludes 32-bit" is true for
  phones and FALSE for TV: Fire-TV-class sticks are 32-bit-only, budget boxes ship 32-bit
  userspace, Synkplay ships Android TV first. armeabi-v7a is engineering plus three gates, not
  a debate. minSdk stays 26.
- **The two Compose paths are both permanent by design**: the interop platform view is the
  sustained-playback default (OS compositor presents, GPU idles), KiteVideo is the
  Compose-true primitive (clip, alpha, shared elements). Neither replaces the other.
- **Subtitle overlays composite in OUTPUT space on every renderer**, not fitted-video space.
- **The Core Graphics renderer is the permanent correctness reference**; Metal is the
  qualifying renderer.
- **MSE plus a video element was rejected** for the web: cannot serve the format matrix (mkv,
  subtitle formats) and surrenders frame-level control. WebCodecs hybrid is the plan instead:
  demux stays FFmpeg-in-wasm, decode goes hardware where the browser allows, wasm decode is
  the fallback, chosen per stream behind the decoder SPI.
- **The default web artifact is single-threaded, no SIMD, no cross-origin isolation**: threads
  need COOP/COEP on the embedder and the failure mode without them is a hang. A player that
  hangs on an embedder's site is worse than one 3x slower.
- **4K is a hardware question, permanently.** Software 4K stays a non-goal by decision; the
  4K verdict is an exit criterion of the hardware AV1/VideoToolbox work, decided on a
  measured 4K clip.
- **DRM is out of scope until a product decision** (typed `DRMUnsupported`). Casting is a
  remote-target abstraction, later horizon. Optical-disc menus are out entirely.
- **Anime4K ships as a curated built-in port, two quality tiers; mpv user-shader
  compatibility is explicitly out of scope.**
- **`fd:`/content:// gets positional reads, not documentation and not an engine-side dup**
  (a dup shares the offset, which IS the bug). Deferred deliberately to ride the IO pass with
  cancellation and network work so the read path is reshaped once.
- **The engine's C surface**: replace one-line helper C with direct cinterop, KEEP the JNI
  adapter, the ABI/identity probe, the get_format callback, FFmpeg itself, and the whole
  real-time island. The goal is no redundant C, not no C.
- **Composite Gradle build was declined**; the twin repos resolve via Central pins (or
  explicit opt-in mavenLocal).
- **A diagnostic bypass of the FFmpeg identity gate exists on purpose**: opt-in only, warns
  once naming the exact mismatch, records itself in diagnostics. An unbypassable gate makes a
  false rejection our outage in a consumer's product.
- **The interop hole is accepted**: platform-view video takes no Compose clip/alpha/shader;
  that trade IS the sustained-playback path. Stated in KDoc, not fought.
- **Owner reads plainly**: register codes are for agents and the tree, never for reporting.

## 9. Cross-repo facts a reader cannot infer

- KiteFFmpeg has no plan of its own BY DESIGN; one product, two repos, one plan file here.
- **There is no KiteFFmpeg Gradle plugin, and consumers need no build script.** It died when
  FFmpeg moved INSIDE the published klibs, so a dependency line is the whole integration. Any
  doc or memory saying a plugin supplies the link-time search path predates that and is wrong;
  this bullet used to say exactly that.
- KiteFFmpeg release tags carry ALL 22 prebuilts (11 triples x 2 flavours); macOS uses the
  portable profile. Deleted release PAGES keep their TAGS.
- Synkplay (the consumer, `../../syncplay-mobile`) pins KitePlayer in
  `gradle/libs.versions.toml`; the adapter is `shared/src/commonMain/.../player/kite/KiteImpl.kt`;
  mpv is the Android default engine, KitePlayer is picked on the home-screen wheel.
- **The two names, and which one Central serves.** Maven Central serves the OLD coordinates only:
  `io.github.yuroyami:kitecodec-core` at 0.1.0, 0.1.1 and 0.1.3 (0.1.2 was cut and superseded the
  same day, never deployed). The repository renamed itself to KiteFFmpeg on 2026-08-29 and its
  artifact is `kiteffmpeg-core`, which Central has NEVER served: it exists on this machine's
  mavenLocal at **0.1.0** and nowhere else until the owner publishes. So KitePlayer cannot build
  without `-Pkiteplayer.useMavenLocal=true` today, and that is expected rather than broken.
- **The version went backwards on purpose.** `kiteffmpeg-core` 0.1.0 is strictly NEWER than
  `kitecodec-core` 0.1.3: a new artifactId is a new artifact to Central, so the line restarted
  with the name. It carries the n8.1.2 trees plus the interrupt-seam and disposition work that
  never shipped under the old name. Never "fix" the number by bumping past the old line, and say
  this in the README so a stranger reading two version numbers does not read a regression.
- `PacketReader.reselect` (KiteFFmpeg) is a committed, tested primitive with NO KitePlayer SPI
  caller, on purpose: the engine's all-lanes subtitle cache made the SPI member unnecessary
  and it was deleted; the primitive stays for a future low-memory or network mode. Do not
  re-add the SPI half without its caller.
- Two `kiteffmpeg-gradle-plugin` functional tests fail on a clean checkout from before all of
  this work (`kiteffmpegDslConfiguredAfterKotlinBlockIsSeenByTasks`,
  `missingLicenseChoiceFailsConfigurationWithInstructions`). Ignore them, fix nothing about
  them, never let them block a gate.
