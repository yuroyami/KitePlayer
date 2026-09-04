# KitePlayer, for whoever works in this tree

One Kotlin engine: an actor loop, worker lanes, a quiesce handshake, a sync law and a seek
machine. Containers, codecs and platform output all arrive through the service interface in
`kiteplayer-core`'s `spi` package. The media library lives in the sibling checkout,
`../KiteFFmpeg`, and is its own repository with its own issue tracker.

`CONTRIBUTING.md` has the ground rules, the gate and the build prerequisites. This file has only
what reading the code or running the gate would not teach you.

## How work happens here

- Work on `main`. Never create a branch without asking. Commit locally, never push. The owner
  pushes, publishes and cuts every release.
- Commit subject is one imperative sentence about the outcome. Short prose body. No trailers.
- Every change starts with an issue, and the commit that closes one says `Fixes #n` in its body.
- Talk to the owner in plain words. No internal codes, no jargon walls. Say what a thing means,
  not what it is. A question must be answerable by someone who has read nothing.

## Gotchas

Each line is something that bit someone. Delete a line when it stops being true.

### The gate and the tools around it

- `run-c-tests.sh` never builds anything, so on its own it proves nothing about a source change;
  run the variant's build script first, every time.
- A Gradle compile task with no sources prints `NO-SOURCE` and exits zero, so "the target compiles
  now" can mean "there was never anything there to compile". Grep the log for that word against the
  exact task name, or check that the run reports a test count rather than a build result.
- `./gradlew ... | tail` reports the exit code of `tail`. A background build once reported success
  with BUILD FAILED sitting in its own log.
- Moving or renaming the checkout breaks the prebuilt C test binaries: they carry an absolute path
  to their interpose library from link time, so every suite aborts naming the old path, which reads
  like a broken test and is a stale binary. Rebuild them; a directory move counts as a C change.
- Adding a dependency can poison Kotlin's incremental-compilation cache, and the failure names a
  standard library function and reads like a compiler bug in your own code. Delete the module's
  `build/kotlin` and build again. The same failure on the web target wants
  `build/classes/kotlin/wasmJs` deleted and a rerun with tasks forced.
- Scraping every Gradle configuration gives a load-dependent answer, because which configurations
  are realised depends on the rest of the task graph. The publication readiness check passed alone
  and failed inside a full gate run, reporting that a publishing module depended on the sample,
  which no build file says. Only `api`, `implementation`, `compileOnly` and `runtimeOnly` can reach
  a POM.
- The local Maven repository is opt-in here, behind a flag, and the build says so when it is on.
  Never re-enable it unconditionally: the same version string with different bytes is
  indistinguishable from the published one.
- Neither `ci.yml` nor `publish.yml` passes that flag, so both resolve kiteffmpeg from Maven
  Central only. Raising the catalog's kiteffmpeg version to one Central does not serve yet turns
  CI red on the next push and makes the publish fail. The sibling reaches Central first, then this
  repository is pushed and published.

### Tests that fail for reasons that are not bugs

- The real-media suites fail under load with messages that read like correctness bugs, for example
  a seek landing 170 milliseconds off, or a status being Buffering when Playing was expected. They
  drive real files and wait on real time, so a busy machine samples the player before it settles.
  Two giveaways: the error changes between runs even though the seed is fixed, and a different test
  in the class fails each time. Re-run the suite alone on an idle machine before chasing one. It
  has come back green every time.
- The browser half dies under concurrent load and reports that the test process exited
  unexpectedly, naming whichever test was in flight, which reads like that test crashed. Same rule:
  re-run it alone first.
- A browser test that runs longer than two seconds is killed rather than failed, because the test
  runner's per-test default is 2000 ms and Kotlin does not raise it. Every module that runs browser
  tests needs a timeout config file; copy the one that has it. Wiring a module's browser half into
  CI without it is how a green suite becomes an intermittent red.
- A no-replay shared flow drops what it emits before anyone subscribes. Every renderer's event flow
  is one, so a test that launches a collector and then makes the renderer emit is racing its own
  subscription: it passes on a quiet machine and times out under a full suite. Wait for the
  subscription to complete before triggering the emit, and collect into a channel rather than a
  list two threads share.
- A test whose name contains a comma compiles on the JVM and breaks every Kotlin/Native target.
  Tier 1 compiles only the JVM half, so the comma ships and the host gate finds it a commit later.
  No commas in test names.
- A state flow's `first { }` samples the current element before it waits, so a test that seeks and
  then waits for "the position advanced" can match the reading from before the seek and return
  instantly, proving nothing. Wait for a reading that reflects the new position first.
- Four iOS simulator test failures are artifacts of how the simulator spawns a process, not bugs.
- Television simulator tests cannot run on a developer Mac at all, for a missing runtime rather
  than a missing SDK, so the aggregate all-tests task can never pass there. Name targets
  explicitly.
- Kotlin/Native creates and then permanently disables the Linux test tasks on a macOS host, so
  naming them is green by definition. Linux evidence is the container script or the CI Linux job.
  Windows native evidence on a Mac is a link claim only.

### Language and toolchain

- A property named `field` is unreachable by that name inside any accessor of the same class,
  because `field` is the backing-field keyword there. The compiler then reports "Property must be
  initialized" on a completely different property.
- The atomicfu Gradle plugin is banned in every module: its bytecode transform registers a task
  depending on a class-compilation task the Android plugin's multiplatform library variant does not
  create. The library dependency itself is fine. This is the trap most likely to be re-triggered by
  tidying a build file.
- The one remaining Gradle deprecation in both repositories belongs to the Android plugin, proven
  by Gradle's own problems report. Nothing here is workable. Re-measure at the next Android plugin
  bump and not before.
- Kotlin's ABI validation currently emits only JVM and klib dumps, so the Android public API is in
  no dump and ships unguarded. A hand-rolled checker was refused as overbuild. Re-measure at each
  Kotlin bump.

### Engine invariants, each of which caused a real bug when violated

- **The audio submit call bypasses the whole pipeline. The decoded-submit call is the real door.**
  Submit writes its floats straight to the ring: no channel mix, no resample, no tempo. A caller who
  reaches for it because the format already matches gets audio that skipped every stage. The first
  version of the volume latency test used it, set a volume, and measured nothing at all.
- **Volume and mute belong to the ring, not to the pipeline.** A gain applied as samples are
  written cannot reach audio that is already buffered, so a change stays inaudible for the ring's
  whole depth: at least 200 ms, and 300 to 600 ms on Android where the audio track buffer sets it.
  Measured at 174 ms of lag on a 171 ms ring. Moving the gain back into the pipeline would be a
  regression that looks like a simplification.
- **What stops a paused player aging is the freeze at pause, not the re-anchor at resume.** Checked
  by mutation: deleting the resume re-anchor changes nothing, because the audio ring publishes its
  own anchor as the device comes back up. Neuter the freeze instead and a one-minute pause moves
  the position from 1.3 seconds to one minute 1.3. So the ring's anchor is the authority while the
  device runs, and the frozen clock is the authority when it does not. Do not simplify either on the
  reasoning that the other covers it.
- All session mutation happens on the actor, in a command execution or a pass handler. Never mutate
  session fields from another coroutine.
- A decoder belongs to its worker's dispatcher. Park the worker, mutate, release. A refusal to park
  means fall back, never force.
- Epochs: an in-place track swap does not bump the epoch, because video work must stay valid. A
  fresh queue is flushed to the current epoch or the demux worker's offers are rejected. A fresh
  component is aligned to the epoch the world is already at. Missing one of those resets is why
  every open after the first once sat dead for ten seconds.
- Any path that retires subtitle state must withdraw the drawn overlay itself, by publishing an
  empty overlay with a bumped generation. The renderer is shared across sessions and the "did I
  publish" key is per session, so the last text otherwise stays on screen forever.
- Every command reply completes exactly once, as applied, discarded or superseded. A track
  selection can sit held while a seek runs and execute a pass later; never assume same-pass
  execution.
- Frames and packets are closeable, closed exactly once, on the worker that owns them. The ring is
  freed only after the feeder is joined, and a flush needs both ring sides quiescent. A leaked
  1080p frame is 3.11 MB; a 4K one is 24.9 MB.
- The cue alpha contract is premultiplied end to end. Both platform rasterizers produce it
  naturally and consumers upload it unconverted. Premultiplying twice renders white text grey.
- The packet queue's trim-before call trims by a packet's end and stops at a packet with no
  timestamps, with callers passing an assumed duration for that case. That single behaviour is the
  whole reason a long cue survives a track switch. Do not optimise it away.
- Interleaving relief runs only while some selected queue is held under readiness by the budget,
  and it cuts the fattest inactive lane first. Cutting on any over-budget state eats the switch
  caches of every healthy paused session, which broke five tests the first time.
- The downmix normalize policy is off by default, matching FFmpeg and other players: merged
  surrounds sum without normalising unless a caller asks. Tested both ways. Do not flip the
  default.
- Pause consumes the final device anchor before freezing clocks, so a late callback cannot
  re-anchor a frozen clock, and resume re-arms a timestamp floor so a pre-pause device timestamp
  can never anchor the clock afterwards.
- The C real-time island stays C. The device callback has no allocator, lock, log or framework
  call, proven by disassembly through the render check script, and nothing managed ever runs on the
  device thread. The Kotlin ring is the C ring's differential oracle; never delete the portable
  implementation.
- The blocking web reader's use of a blocking call is safe only because close never queues behind
  the demux lane. That reasoning is load bearing; re-check it before touching either side.

### The web target

- Kotlin/Wasm has no bulk typed-array bridge: naive per-byte crossings run at roughly 96,000 calls
  per second of audio and killed the first web input path. Cross per chunk, with the tight loop
  living in JavaScript.
- The latin1 pack trick corrupts bytes over 0x7F if anything encodes the string as UTF-8 in
  transit. The 0 to 255 ramp test exists for exactly that and must never be weakened to ASCII.
- Building a raster image from a Kotlin byte array costs 107 to 153 ms per 1080p frame, which is 55
  to 85 MB per second across the managed heap boundary. The web renderer therefore keeps pixels in
  the codec module and draws through the canvas image call, at 2.5 to 2.9 ms. Never route web video
  pixels through the Kotlin heap.
- A per-pixel conversion loop on the web is about 5 times slower than the same loop in JavaScript
  and about 10 times slower than the media library's own scaler inside the module. Convert in C,
  beside the decoder.
- A 64-bit integer across a JavaScript function boundary needs the big-integer build flag and
  arrives as a JavaScript big integer. The convenience call helper has no type spelling for it, so
  call the export directly; a silent truncation there corrupts every timestamp.
- Without cross-origin isolation headers, importing the threaded artifact hangs rather than
  erroring. Feature-detect isolation before the import. The default artifact stays single-threaded
  for exactly this reason.
- Every browser audio context starts suspended until a user gesture: the queue fills, the feeder
  backs off, and the audio-mastered clock sits at zero. That is correct behaviour, not a hang, and
  an embedder has to know it.
- A hidden browser tab never fires its frame callback and suspends audio under the autoplay policy,
  so a frame-rate readout from a hidden tab means nothing. Measure per-frame cost spans instead.
- C struct fields are read from JavaScript by byte offset, and those offsets come only from the
  committed generated layout file. A wrong offset reads the neighbouring field and answers
  something plausible.
- `runBlocking` does not exist on the web target because there is no thread to block, so a shared
  test written with it will not compile there. The fix is the test-coroutine builder, not moving the
  test into a narrower source set: narrowing silently removes it from every target that no longer
  sees it. Moving two files out of the common test set here would have dropped 32 tests from the
  Android host run with nothing going red to say so. Count tests per target before and after any
  source-set move.

### Platform truths, measured on real hardware

- Android's filter quality setting above none collapses to one boolean flag, so the drawing step
  cannot resample better than bilinear no matter what it is asked. Scaling quality on Android can
  only live in the GL blit. Device proven.
- On a low-end phone chip, per 1080p draw over a 6.83 ms plain blit: dither costs 1.30 ms,
  debanding costs 7.72 ms, and a better scaling kernel costs 22.01 ms. Dither is affordable on floor
  hardware; the kernel is not a default there.
- Render passes have a characteristic failure: a pass that compiles, costs every tap, and does
  nothing. Three of four findings in that area were exactly that. A test that only asks whether the
  code ran cannot catch it; golden-image deltas can.
- A developer Mac with an older Apple chip has no AV1 silicon, so it can only ever prove the AV1
  hardware refusal path. Positive proof needs newer silicon.
- Feel-testing on iPhone is release-build only: a debug shared framework collapses the software
  frame path by roughly 30 times and invalidates any judgment about responsiveness.
- The proven device-debug workflow is to reproduce the device bug in the virtual-time harness on
  the JVM first. Every real device bug of the past sessions reproduced there before it was fixed.
  The exposing fixture class is a long-GOP animation file with a dense subtitle track of roughly
  70,000 cues; short clean files hide these bugs.

## Decisions already made

Do not reopen these without new evidence.

- FFmpeg is the one media truth. No platform demuxers or decoders as a source of truth; hardware
  acceleration only as decoders and acceleration paths inside it, with software fallback.
- No new mandatory native libraries. Kotlin, or shader source we author, first. A native library
  only as an optional module when no Kotlin path can exist, or when correctness parity demands it.
  Verdicts already given: an XML library never, because manifests parse in Kotlin; a TLS library
  and an HTTP library rejected, because vendored crypto is a recurring security duty and TLS comes
  from the operating system through the HTTP client's engines; a GPU video processing library
  rejected as a dependency, because its viewer-visible value is roughly 150 lines of shader we can
  author, its correctness core already ships, and it cannot follow the engine to the web.
- Native Linux and Windows have no https: those targets have no operating-system TLS to delegate to
  and no output backend. Desktop rides the JVM, which has https.
- Every Android ABI stays supported, and the minimum SDK stays where it is. "The minimum SDK
  excludes 32-bit" is true for phones and false for television: the common streaming sticks are
  32-bit only and budget boxes ship a 32-bit userspace.
- Both Compose video paths are permanent by design. The interop platform view is the sustained
  playback default, because the system compositor presents and the GPU idles. The Compose-native
  primitive is what allows clipping, alpha and shared-element transitions. Neither replaces the
  other, and the trade is stated in KDoc rather than fought: platform-view video takes no clip,
  alpha or shader.
- Subtitle overlays composite in output space on every renderer, not in fitted-video space.
- The Core Graphics renderer is the permanent correctness reference. Metal is the qualifying
  renderer.
- The browser's own media source extension with a video element was rejected for the web: it cannot
  serve the format matrix and it surrenders frame-level control. The plan is a hybrid, where
  demuxing stays in the media library compiled to the web and decoding goes to the browser's codec
  API where allowed, with the library as the fallback, chosen per stream behind the decoder
  interface.
- The default web artifact is single-threaded, with no vector instructions and no cross-origin
  isolation, because a player that hangs on an embedder's site is worse than one three times
  slower.
- 4K is a hardware question, permanently. Software 4K is a non-goal by decision, and the 4K verdict
  is an exit criterion of the hardware decode work, decided on a measured clip.
- Digital rights management is out of scope until a product decision, and reports a typed
  unsupported error. Casting is a remote-target abstraction for a later horizon. Optical disc menus
  are out entirely.
- The animation upscaler ships as a curated built-in port with two quality tiers. Compatibility
  with that upscaler's wider ecosystem of user shaders is explicitly out of scope.
- A descriptor-backed source gets positional reads, not documentation and not an engine-side
  duplicate. A duplicate shares the file offset, which is the bug itself.
- A composite Gradle build was declined. The twin repositories resolve through published pins, or
  through an explicit opt-in to the local Maven repository.
- Gradle artifact checksum verification is off, and the reason is measured rather than assumed.
  Generating the metadata recorded 486 components from one JVM compile and the next task failed on
  a detached configuration that Kotlin/Native and the Node setup resolve through and the generator
  never sees. CI also runs on three operating systems, each resolving its own toolchain artifacts,
  so a file written on one host cannot carry the other two. A dependency hygiene script guards what
  can be guarded instead.

## Facts about the pair of repositories

- One product, two repositories. The media library is `../KiteFFmpeg`. Each has its own issue
  tracker and neither has a private planning file.
- The media library's artifact was renamed, and its version went backwards on purpose: a new
  artifact id is a new artifact to the public repository, so the line restarted with the name. The
  new number is strictly newer than the old line's. Never "fix" it by bumping past the old line.
- The reader primitive in the media library that reselects streams has no caller here on purpose:
  the engine's all-lanes subtitle cache made the interface member unnecessary and it was deleted.
  Do not re-add the interface half without its caller.
- The consumer application pins this project in its own version catalog, adapts it in one file, and
  offers it on a home-screen selector; another engine is its default on Android.
- Pulling logs off an iPhone for that application uses the device control command with the
  application data container domain, and an in-room setting adds one statistics line per tick.
