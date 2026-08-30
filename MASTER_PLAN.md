# MASTER_PLAN

**The one source of truth for what is LEFT to do, in both repositories** (KitePlayer and
`../KiteFFmpeg`; the sibling has no plan file by design). How to work, the traps, and the
decisions live in `GOTCHAS.md`. History lives in git; the old planning documents
(KPKMP-FUTURE.md, KPKMP-PAST.md, the SALANKE set, HANDOFF.md) were distilled into these two
files on 2026-08-29 and deleted; `git log --diff-filter=D` finds them.

**The law of this file:** the same commit that changes the tree updates it. Finished work is
DELETED (the commit message is the record). Discovered work is added. Half-done work is
reduced in place with its remainder named. There is no archive file; done means gone.
Item IDs (SOL-, KC-, KP-, PAR-, X-, RQ-) are stable handles kept because code comments and
old commits reference them; new items get plain names.

Sizes: S under half a session, M up to two, L two to five, XL a program entered through its
own written expansion. NEEDS-DESIGN means a public API or contract must be decided in its own
commit before execution. [owner] means it needs the owner's hardware, account, or decision.

---

## Where things stand (verified 2026-08-29)

The engine is real and plays: one Kotlin core (actor loop, workers, quiesce handshake, sync
law, seek machine), FFmpeg via KiteFFmpeg beneath it, audio with a windowed-sinc resampler,
WSOLA tempo and pitch preservation, subtitles with libass built for seven target families,
outputs on Android, Apple, desktop JVM, Linux (container-proven), and a web canvas path.
About 880 Kotlin test functions in KitePlayer, about 260 in KiteFFmpeg, plus nineteen C
suites. Both repos have CI (KitePlayer 7 jobs on 4 OSes). Maven Central serves only the
sibling's OLD coordinates, `kitecodec-core` up to 0.1.3; the renamed `kiteffmpeg-core` 0.1.0 is
mavenLocal-only, so KitePlayer builds need `-Pkiteplayer.useMavenLocal=true` until the owner
publishes. KitePlayer itself has never been published.

Honest support today: macOS arm64 is the proving ground (experimental full playback). Android
and iOS play real media on real devices with named open items below. Desktop JVM plays the
whole conformance matrix on this Mac. Web decodes, draws and schedules audio in a browser;
nobody has heard it (worklet run pending) and no matrix has run there. Windows is a link
claim. Every green so far is one-machine evidence unless a device session below says
otherwise.

## The public release gate

A public claim about the pair stays blocked until every box is green. (Correctness half nearly
done; distribution half is Phase 8.)

| # | Box | Status |
|---|---|---|
| 1 | Invalid C pixel formats cannot abort | GREEN |
| 2 | Native FFI calls hold lifetime leases | GREEN |
| 3 | Sink close is a terminal atomic state machine | GREEN |
| 4 | Wasm MediaSource behaviour matches the shared suite | code written, tests are Phase 2 (KC-EVIDENCE-WASM) |
| 5 | Web input worker-backed or explicitly small | GREEN since KC-WEB-IO closed; streaming remainder rides X-08 |
| 6 | Custom I/O failures preserve cause, close once | GREEN on JVM/Native; wasm close half landed with KC-WEB-IO |
| 7 | Player EOF waits for every lane | AMBER: subtitle lane still outside the EOF gate (a trailing cue can be cut) |
| 8 | Reopen paths get a fresh MediaIo | GREEN |
| 9 | Commands return truthful results | GREEN |
| 10 | Output capabilities leased; loss recovers or fails typed | AMBER: audio device loss only warns (Phase 5) |
| 11 | Every published JVM OS/arch has a runtime artifact | RED (Phase 8) |
| 12 | Android AAR built on CI, device-tested per ABI | RED (Phase 8 + DEVICE-DAY) |
| 13 | Android Native attaches the JavaVM, MediaCodec device-tested | RED (DEVICE-DAY) |
| 14 | Wasm runtime in a versioned package, browser-tested | RED (Phase 6) |
| 15 | Licence/flags/capabilities agree | GREEN |
| 16 | Prebuilts exist; clean consumer install works (KiteFFmpeg) | GREEN |
| 17 | Every KitePlayer variant resolves one KiteFFmpeg variant | RED (Phase 8) |
| 18 | Player modules and web runtime release together | RED (Phase 8) |
| 19 | Licence/SBOM/provenance per bundled native dep | RED (Phase 8) |
| 20 | RC tests run before publication; publication atomic | RED (Phase 8) |

---

# PHASE 0: THE RENAME REMAINDERS

### 0.1 The rename remainders. Size S each

The rename LANDED 2026-08-29 (KiteFFmpeg `d03c9c5`, KitePlayer adoption alongside it): modules,
packages, artifacts, properties, class names, the shipped licence directory and the local
directory are all `kiteffmpeg`, the internals (`kc_`, `ffkmp_`, `libkitecodec.a`,
`native/kitecodec-*`) deliberately are not, and the version line restarted at 0.1.0. What is
left of it:

- [ ] **Package-level concern scoping inside the one module**: `...kiteffmpeg.demux`, `.decode`,
  `.encode`, `.filter` and friends. This buys the clarity a module-per-concern split advertises
  without the per-module publication tax. The split itself was considered and DECLINED
  2026-08-29: payload weight lives in the native profile rather than in Kotlin modules, shared
  types drag the mass into a base module anyway, and 13 targets times N modules multiplies the
  config drift the SEAM item already documents. Revisit only if a real external consumer asks
  for a playback-only artifact, and then additively.
- [ ] **Synkplay** moves its pin to `kiteffmpeg-core` whenever it next bumps KitePlayer. Nothing
  blocks on it, and its adapter needs no change beyond imports. **One thing to check on that bump
  if Synkplay has a desktop build:** desktop `Auto` now resolves to the native view, so Compose
  controls drawn over the video stop receiving clicks. Either move them into an owned overlay
  window or pass `KiteRenderPath.ComposeCanvas` explicitly. Mobile is unaffected.
- [ ] **[owner] The GitHub repository rename** (KiteCodec to KiteFFmpeg; GitHub redirects old
  URLs, and the CI badge and the checksum-pinned companion-release fetch URLs already name the
  new one, so they go live with the rename).
- [ ] **[owner] Publish `kiteffmpeg-core` 0.1.0 to Central**, spot-checking one published klib
  for `n8.1.2` before releasing (see GOTCHAS section 4; the check reads the bytes rather than
  the build's opinion of them, which is worth doing on a first release under a new name). The old
  `kitecodec-core` line stays on Central untouched and receives nothing further.

---

# PHASE 1: DESKTOP NATIVE VIEW (owner-ordered 2026-08-29, BUILT 2026-08-30)

**What exists now.** Desktop has a real platform video view: `KitePlayerAwtView`, an AWT canvas
driven by the same `PlayerViewBinding` Android and iOS use, painted by `AwtCanvasVideoRenderer`
through a `BufferStrategy` on the thread that presents rather than on the Compose frame clock.
`kiteplayer-mobile` supplies the adapter, since it is the one module allowed to depend on both a
codec and an output, and `KitePlayerVideo` resolves `Auto` to it on JVM as of
2026-08-30, owner-decided, so a desktop consumer gets the steady picture without asking.

**Why it was worth building, measured rather than argued.** With the UI choked to 4.7 frames a
second, the native view kept painting about 29 frames a second of real 1080p30 while the Compose
canvas path drew the picture at the UI's own rate. That is the complaint that opened this phase,
answered.

**The constraint the default now carries.** Compose content drawn over the native
view cannot receive mouse input, because macOS routes a click to the topmost NATIVE view and
painting over it afterwards does not change that. Controls that must be clickable over video
belong in a borderless window owned by the video window, which is the one arrangement measured to
work; controls beside the video need nothing special. That sentence lives in the KDoc of the view,
the Compose surface and `KiteRenderPath`, because a consumer who has not read it ships a dead play
button. The full method and all seven arrangements are in
`kiteplayer-sample-desktop/INTEROP-SPIKE.md`.

### 1.1 What the desktop native view still owes. [owner] and small

**Items 1.2 to 1.5 LANDED 2026-08-30** (KitePlayerAwtView `7e3457b`, the AWT renderer and its
adapter `d0a6a91`, the Compose seam `4d13404`, the end-to-end comparison in this commit). The
path exists end to end: an AWT canvas painted off the Compose clock, a renderer that owns frame
lifetime, an adapter in the one module that may depend on both a codec and an output, and a
Compose seam that honours an explicit `NativeView` request.

**Measured end to end on real video**, one arm per process, in
`kiteplayer-sample-desktop/INTEROP-SPIKE.md`: with the UI choked to 4.7 frames a second, the
native view kept painting about 29 frames a second of 1080p30 while the Compose canvas path drew
the picture at the UI's own 4.7. The engine submitted about 445 frames and dropped none in every
arm, which is correct and is why engine counters alone cannot separate the paths.

What is left:

- [ ] **A clickable overlay helper, if consumers keep needing it.** The measured answer for
  controls over video is a borderless window owned by the video window. Today each consumer
  writes that themselves. Wait for a second consumer to need it before turning it into API.
- [ ] **[owner] Windows and Linux runs.** Both spikes and the comparison have only run on macOS.
  Compose documents blending as Metal, DirectX and offscreen only, so Linux is expected to fall
  back to the Compose canvas through `onEffectivePath`; that expectation is unverified.
- [ ] **Resize behaviour under playback** was not measured; the harness never resized a window.
- [ ] **KP-DESK-NV-GPU**, later and only if wanted: a JAWT presenter owning a CAMetalLayer on
  macOS, D3D on Windows, EGL on Linux, replacing the CPU blit. Nothing measured so far demands
  it, and note before starting that it does NOT fix the input constraint, which belongs to native
  view ordering rather than to how the surface is painted. Compose also documents that DirectX
  blending cannot overlay another DirectX component, which constrains the Windows half.

# PHASE 2: EVIDENCE BUY-BACK (make the unprovable provable)

### 2.1 KC-EVIDENCE-WASM: the three wasm fixes get their tests. Size M

Three fixes in `MediaSource.wasmJs` have no test that could fail if reverted: live
`corruptDataSkipped` accumulation in `decodeStreams`; the decoder leak where
`openPacketReader` sat outside the owning `try`; the reader/decoder leak in `extractFrame`.
The seam is `FakeCodecModule` (wasmJsTest); it needs the packet/decoder entry points a decode
drives (~40 entry points consumed by that file: a fake demuxer, hence M).

- [ ] Extend the fake (scripted packets, EOF, corrupt arm, fail-at-open arm); three RED tests,
  each falsified by reverting its fix; `:kiteffmpeg-core:wasmJsNodeTest` green.
- [ ] Commit: `Prove the three wasm fixes can fail`. Release-gate box 4 re-grades.

### 2.2 The test-debt row: ten owed regressions + one warning test. Size M-L total

Each RED first against a revert or scripted fault; cluster commits.

- [ ] cached `Frame.info` after close refuses (KC)
- [ ] filter-callback frame retention detected or documented refused (KC)
- [ ] failed quiescence during renderer replacement falls back without leaking (KP core)
- [ ] cancellation after partial audio submission keeps ring/counters consistent (KP)
- [ ] device-sleep clock epochs: no pre-sleep timestamp re-anchors after a long pause (KP,
  reuses the resume-floor machinery)
- [ ] negative start times normalize exactly once (KP ffmpeg)
- [ ] foreign `StreamInfo` from a backend refuses typed (KP core)
- [ ] decoder output diverging from codecpar is surfaced (KC)
- [ ] empty-output `MediaSink` finalization is clean or typed (KC)
- [ ] midstream audio format change reaches renegotiation or typed warning (KP)
- [ ] `FrameDropping` warning: force five late drops in one stats interval under the virtual
  clock (the one warning of the F-WRN1 four with no pin)

### 2.3 The last red wasm build row. Size M

- [ ] **KP-WEBPACK-CONTEXT**: `:kiteplayer-network:wasmJsBrowserTest` dies in webpack
  (`RangeError: Invalid array length` timestamping a context directory; deterministic; node
  half fine). Bisect the webpack context scope. If the fix is a toolchain bump, STOP: owner
  decision.

### 2.4 F-COV1 recounted 2026-08-30. What is left is what cannot run here

**The old "six of twenty" claim predated CI and is retired.** Counted off the workflow rather
than remembered, NINE surfaces execute tests on every push: JVM on macOS and again on Linux,
macosArm64, iosSimulatorArm64, Android host tests on Linux, linuxX64 executed on a real Linux
kernel, mingwX64 executed on Windows, and wasmJs in both node and a headless browser. The C
suites run in four variants beside them. Two more surfaces execute locally and not yet in CI:
the compose-ui and compose-interop JVM suites, the second of which only started existing today.

**What genuinely cannot run, and why, so nobody re-attempts it blind:**

- [ ] `watchosSimulatorArm64` and `tvosSimulatorArm64`: attempted here on 2026-08-30 and refused
  with "Xcode does not support simulator tests for watchos_simulator_arm64. Check that requested
  SDK is installed". `xcrun simctl list runtimes` offers iOS only on this machine, so both are a
  missing SDK rather than a broken target. They would need the SDKs installed, on a machine or a
  runner, before the claim can move.
- [ ] `iosArm64`, `androidNative*`, `macosX64`, `iosX64`, `linuxArm64`: compile-only, since each
  needs hardware or an emulator nobody has wired. Compilation is not support and the README says
  so.
- [ ] `js`: a deliberate placeholder, not a gap.
- [ ] Android device tests: hardware, and they ride DEVICE-DAY.

---

# PHASE 3: CORRECTNESS AND CONTRACTS (KiteFFmpeg first, republish, then adoption)

### 3.1 KC-POISON-SCOPE: the backends disagree about a poisoned sink. Size S + design line

JVM `setMetadata` goes through `checkOpen` and refuses after poison; native checks only
`headerWritten`/`closed` and accepts, and takes no `muxLock` there. Decide the contract
(recommended: JVM behaviour wins; refusing after poison is the conservative reading), align
native, RED via the `MuxFaults` seam, falsify.

### 3.2 KC-SPEC: output specs carry no colour, HDR, pixel aspect or exact layout. Size L

`MediaSink` specs carry codec/size/pixfmt/rate/bitrate/keyint plus an untyped map; nothing
propagates from the source, so an encode silently flattens HDR and 5.1(side) becomes
5.1(back). Also: the untyped map beats the typed field beside it (`options["b"]` wins over
`bitrateBps`, video and audio both).

- [ ] Typed colour (primaries/transfer/matrix/range), mastering metadata, SAR, exact layout
  on the specs; propagate from `StreamInfo` when not overridden; colliding option keys refuse
  typed (silent precedence in either direction is the defect). RED: scripted HDR source
  carries its colour into the sink untouched; collision refuses. Both backends; wasm records
  its bound. apiDump.

### 3.3 KC-REMUX: a "lossless" remux drops identity. Size M

Both backends copy codec parameters and one time base, then stop: tags, language, title,
disposition, rotation/display matrix, side data, stream groups dropped; chapters readable and
never written; no program or attachment path.

- [ ] Carry per-stream metadata, disposition, rotation, side data; write chapters. RED with
  an `ffprobe` oracle over a fixture carrying rotation + language + chapters. Whatever stays
  out is named here as remainder, not skipped silently. Fix the README "bit-exact" wording in
  the same commit.

### 3.4 KC-AENC: the audio encoder validates and converts nothing. Size M

Both backends read `if (audio) 0L else conversionFor(...)`: video gets validation and pixel
conversion, audio gets neither, so rate/format/layout/frame-size mismatches fail late and
cryptic. Mirror the video shape: validate against the encoder, convert through the existing
resample machinery, refuse typed when impossible. RED: float-planar into an s16-only encoder.

### 3.5 KC-COLOR-PROV: a guessed colour cannot be told from a declared one. Size M

All four write sites overwrite Unspecified with a guess; only RANGE carries a provenance flag
(`rangeSpecified`); wasm preserves Unspecified so backends disagree. Extend provenance to
primaries/transfer/matrix; align backends. RED: Unspecified-everything reports
specified=false per field everywhere; declared BT.709 reports true. apiDump.

### 3.6 KC-TRACKSEL + the disposition widening. Size M, NEEDS-DESIGN

JVM/Native skip attached pics then take first video; `primaryAudio` is `firstOrNull`; no
`TrackSelector` exists. And `Disposition` collapses hearing-impaired and visual-impaired into
one boolean while DESCRIPTIONS and COMMENT are read nowhere, so descriptive audio and
commentary are indistinguishable at every layer.

- [ ] Design commit: small `TrackSelector` policy (defaults documented, language hook) +
  widened `Disposition` flags. Then: cover-art-first fixture picks real video on wasm too;
  descriptive audio never auto-picked over an ordinary sibling. apiDump.

### 3.7 KC-WASM-MODEL: the wasm probe answers plausible emptiness. Size M

Container metadata and chapters hardcoded empty; stream read omits metadata, disposition
extras, start time, extradata, colour, VP9 profile, layout mask; non-AV types collapse to
`Data` (erasing Attachment/Unknown). Populate via the dictionary walk + struct reads; RED per
field against the fake; one real-browser integration run recorded as manual evidence.

### 3.8 The filter trio. Size L, NEEDS-DESIGN

- KC-FILTER-DIVERGE: JVM builds graphs eagerly from codecpar, native lazily from the first
  frame with a per-frame `List<Any>` key that omits SAR (which the builder uses) and audio
  layout; midstream changes behave differently per backend.
- KC-FILTER-LOCK: user callbacks run under the graph lock on BOTH backends now; native's
  residual hazard is a callback blocking on another thread needing the op lock; a comment
  argues the opposite of what the code does.
- KC-FILTER-SESSION: `process` is single-use and the type does not say so; `feedInput`
  returns Unit so multi-input graphs cannot answer NeedsInput(pad) and bound retries at two
  with an untyped error.

- [ ] Design commit: orchestration moves to common Kotlin (one build/rebuild law, eager,
  keyed including SAR + layout, no per-frame allocation); callbacks invoked outside the lock
  (snapshot under lock, call after release; remaining reentrancy documented); one-shot
  `process` expressed in the type; `feedInput` returns a typed result. Then red-first per
  defect: midstream change identical on both backends; blocking callback cannot deadlock;
  second collection refuses at the type.

### 3.9 KC-FRAME-FLOW: a buffered frame Flow strands native frames. Size M

The defect is stated in public KDoc (known, still a leak). Close-on-cancel wrapper: a
`Flow<Frame>` variant that closes undelivered frames when the collector cancels. RED: cancel
mid-stream with buffered frames; leak ledger shows zero live after. Delete the KDoc caveat.

### 3.10 KC-CAPS: enumeration + measured build inventory. Size S + S

`FFmpeg.hasDecoder(name)` exists; ENUMERATION does not (`av_codec_iterate` unbound), and
`kiteffmpegInfo` prints the dsl TOGGLES, not a measured inventory of the linked tree. Design
commit for `FFmpeg.decoders(): List<String>`; make the info task measure. RED: list contains
h264 everywhere; wasm fake scripts its list.

### 3.11 KC-DOCTRUTH remainder: register codes in shipped sources. Size M, mechanical

128 mentions of 35 internal codes across 40 shipped files (counted 08-24). A stranger cannot
resolve them. Replace each with the sentence it stood for; codes stay only in MASTER_PLAN,
GOTCHAS and git history. Sweep with
`rg -n "SOL-|KC-[A-Z]|KP-[A-Z]|F-[A-Z]+\d|PAR-|X-\d|AGW-"` over shipped sources, both repos,
until zero.

### 3.12 KC-ABI-SCOPE: the API ratchet watches 3 of 13 targets. Size M

An iOS-only public API change passes today (dumps re-based under the host-only flag).
Fix: CI fetches the prebuilt static trees (the mechanism the consumer jobs already use) for
ios/linux/mingw and dumps with `-Pkiteffmpeg.requireAllTargets=true` on the macOS job. Prove
live with a throwaway iOS-only public function that must fail the widened check. Reduce to
any genuinely unreachable targets instead of closing if some tree cannot exist in CI.

### 3.13 KP-TONEMAP-WARN remainder: renderers publish engagement. Size M

The split shipped: `HdrToneMapped` maps from `RendererEvent.ToneMapEngaged`, latched once per
open; `ColorApproximated` carries the BT.2020-CL truth; the old lying warning is deprecated
and sited nowhere. Remainder: NO built-in renderer publishes the event yet, so the notice is
silent. Wire the Metal composer path, the software-converter consumers (jvm + native), and
the Android GL tier; emission where tone mapping ENGAGES, never from metadata (the no-convert
test arm pins that); interop tiers that never touch pixels emit nothing by construction.

---

# PHASE 4: THE SUBTITLE PROGRAM

### 4.1 Charset detection and conversion. Size M

Every external subtitle is read as UTF-8 with only a BOM strip; Windows-1256, Shift-JIS,
Big5, GBK, EUC-KR files render as replacement characters with no warning, on every platform.
Decided: detection in common Kotlin (BOM, UTF-8 validation, small frequency heuristic we
author; no new dependency), conversion via platform actuals (platform charset APIs;
`TextDecoder` on wasm). Undetectable input warns typed and falls back.

- [ ] RED: Windows-1256 / Shift-JIS / Big5 fixtures decode to their real text; garbage warns.
  Commit: `Read the subtitle encodings the world actually uses`

### 4.2 The FFmpeg subtitle-decoder bridge (bitmap subs). Size L, NEEDS-DESIGN

Nobody has to write a subtitle engine: `ff_pgssub_decoder`, `ff_dvbsub_decoder`,
`ff_dvdsub_decoder`, `ff_xsub_decoder` and eight text-format decoders (SAMI, JACOsub,
MicroDVD, MPL2, RealText, PJS, VPlayer, STL) already ship in the archives, unreachable. The
work is a KiteFFmpeg decode-subtitle surface (packet in, positioned rects out) plus a routing
branch in the player's subtitle factory onto the EXISTING `OverlayImage` path. CEA-608/708
ride VIDEO frame side data and stay a named remainder here (needs the per-frame side-data
channel, its own KiteFFmpeg API act; also the one structural blocker under timed metadata).

- [ ] KiteFFmpeg design commit, then RED: a PGS fixture decodes to positioned bitmaps on jvm +
  native. Player adoption: PGS MKV shows subtitles in the desktop sample (manual evidence,
  recorded). Republish, adopt.

### 4.3 The libass row (L): wired and finished. Size L

Built and device-proven on seven target families; zero call sites outside its own tests.
Bullets, in order; reduce this item bullet by bullet:

- [ ] Wire: `PlaybackCore` routes ASS cues through the libass renderer when the optional
  module is present (the rasterizer-selection seam); one typeset-heavy fixture renders via
  libass on macosArm64Test.
- [ ] JVM bridge: host .dylib/.so/.dll packaging + resource loading (shared infra with
  KP-DESK-NV-GPU's future shim; build once, document once).
- [ ] The animated hook: per-frame render callback for animated typesetting (rendering is
  snapshot-per-call today; libass carries the times).
- [ ] Exit: a named typesetting-heavy corpus renders pixel-comparable to mpv (similarity
  threshold documented). Wasm/emscripten build is last and may split into its own item if
  emscripten fights; say so rather than sink time.

### 4.4 SOL-S7: the styling features the type stopped promising. Size M-L

Measured truth table (2026-08-25): per-span colour/bold/italic/underline/strike work
everywhere; `fontSizePx`/`outline*` apply first-span-whole-cue; `shadowColor/Offset` drawn
NOWHERE (default was inert); `CueLayout.wrap` ignored; `fontFamily` desktop-only. Features,
each red-first on all three rasterizers (or a named skip): per-span size and outline, the
shadow pass (bitmap grown by the offset, placement moved with it), `CueWrap`, `fontFamily`
on Apple and Android (platform font lookup with fallback). N31's reverse stacking
(`Collisions:`) lands here too.

### 4.5 KP-P1-15: viewport subtitles. Size M, NEEDS-DESIGN

Viewport-aware rasterisation: safe-area insets, aspect-mismatch placement, scaling policy on
resize. Thin by inheritance; write its expansion first (the three SALANKE suspects S41, S44
and S50's geometry half fold in: cue geometry vs surface geometry mismatches). Then execute
red-first on the rasterizer geometry.

---

# PHASE 5: PLAYER API AND AUDIO TRUTH

### 5.1 KP-API remainder. Size M total, cluster commits

- [ ] `editions()`/`programs()` throw unconditionally (a test pins it): delete from the
  surface (subtraction precedent; deprecate if Synkplay references them). ABI dumps.
- [ ] The default factory that compiles then throws: `create()` refuses at CONFIG time with
  the typed configuration error the facade contract already demands.
- [ ] Public models mutable through arrays: defensive copies or immutable lists; identity
  equality documented where it stays.
- [ ] Raw FFmpeg option strings and filter chains at the public edge: mark with an explicit
  low-level opt-in annotation (mirror KiteFFmpeg's `@KiteFFmpegLowLevelApi` seam).
- [ ] The process-wide logger beside the per-player sink nothing reads: delete.
- [ ] `Pts` prints garbage at `Long.MIN_VALUE`: `toString` names NOPTS.
- [ ] `CapturedFrame` unchecked geometry: validate in the constructor.
- [ ] Java/Swift adaptation: NOT built here; stays on this list as a feature item
  (KP-INTEROP-SURFACE, unscheduled).

### 5.2 SOL-API4: the five stats features. Size M-L

Declared, honestly KDoc'd unbuilt, and they are features, not cleanup: `droppedFramesDecode`
(decoder-side counter through the stats pass), `audioLatency` (sink already measures;
surface it), `containerBitrate` (format context), `SyncMode.ExternalMaster` and
`LateAndDecode` (sync-law modes; the SyncLaw seam exists). Virtual-clock test each;
ExternalMaster drives a scripted external clock.

### 5.3 The audio program (SOL-A6 split honestly). Size M + M, rest re-filed

- [ ] Device selection (M): enumerate + select output device on desktop JVM (AudioSystem
  mixers) and Apple (device UID; coordinate with the CoreAudio-setup-to-Kotlin move in 9.1,
  whichever runs first). Android routes stay OS-owned, documented.
- [ ] Route recovery (M): CoreAudio default-device-change listener (today `AudioDeviceChanged`
  is dead code on Apple: no listener exists); rebuild the sink through the recovery shape
  `DesktopAudioSink` has. In the same commit, guard that desktop recovery path itself: its
  reopen is currently unguarded, so a failed open leaks the fresh driver and points at a
  closed one (the open() path guards; recovery forgot). RED with a scripted fail-on-reopen
  driver. Tier 3 if teardown ordering is touched.
- [ ] Passthrough + offload: re-filed as a feature item (KP-AUDIO-PASSTHROUGH, unscheduled,
  needs hardware evidence + owner scope), not pretended to be a bug.

### 5.4 SOL-P8 remainder: desktop multichannel. Size S-M

JVM desktop output is stereo-only. Open the `SourceDataLine` with the source's channel count
when the mixer supports 6/8; fold only when it does not. RED: 5.1 fixture on a scripted
6-channel line keeps 6 channels. Upmix (mono/stereo to 5.1) is a policy feature: re-filed
(KP-AUDIO-UPMIX, unscheduled, owner taste).

### 5.5 Container tags reach the public surface. Size S

`PlayerMediaSource.metadata` has zero consumers; title/artist are in memory and no
application can reach them. Expose read-only container + per-stream tag maps through the
snapshot/track surface. RED: tagged fixture surfaces title/artist via the facade. ABI dumps.

---

# PHASE 6: THE WEB ORGAN, FINISHED

### 6.1 X-08: the Worker. Size L

Nothing runs the player in a Worker; the blocking IO design (X-06) is only legal off the main
thread; every user-facing item needs a main-thread facade that does not block. Worker
bootstrap + facade + message protocol + lifecycle. The zombie-coroutine caveat on
`BlockingMediaIo.wasmJs` retires by construction inside the Worker. Exit test: player driven
through the facade while a rAF heartbeat on the main thread never gaps beyond a stated bound
(headless browser).

### 6.2 X-13: artifact layout and deployment. Size M

No artifact layout, no deployment story; COOP/COEP live only in prose. Ship: layout, the
`self.crossOriginIsolated` detect BEFORE importing the threaded module (the failure without it
is a hang), embedder doc, measured web tier sizes. Test: both artifacts from the dual-mode
server; the detect picks correctly both ways, asserted headless.

### 6.3 X-14: the conformance matrix in a real browser. Size M

The matrix has run under node only. Run the project's OWN suite headless in CI's browser job,
per-row decoder recorded (wasm vs WebCodecs) so a silent fallback cannot masquerade as a
hardware pass. Record one 4K HEVC clip through the hardware path against the 4K exit
criterion (decision stays the owner's).

---

# PHASE 7: STREAMING AND IO (one deliberate pass; these rows reshape the read path together)

### 7.1 The IO pass, designed then executed. Size L, NEEDS-DESIGN

One expansion covering:

- **KC-CANCEL remainder**: `open()` itself is not interruptible (no handle exists before it
  returns); a mid-playback stall waits for the user instead of self-aborting at a policy
  bound; `Transcoder.transcode` never leaves the calling dispatcher.
- **The three network-side twins**: install the interrupt callback on the URL path, bound
  every network wait with timeouts, harden the undocumented URL path (it must not be
  advertised until then).
- **PAR-7**: `fd:`/content:// gets a positional-read MediaIo (`pread`/`FileChannel.read`),
  removing the shared-offset mutation and silent unseekable degradation by construction.
  User-facing: this is the Android content:// route.
- **The unused bounded-seek floor**: `PacketReader.seek` takes `notEarlierThan` and the
  player never passes it; give it falsifiable behaviour with the retry-on-refusal policy.
- The HLS child-context interrupt trap from GOTCHAS applies throughout.

- [ ] Design commit, then red-first: open interrupted mid-`find_stream_info` returns typed
  within the deadline; a stall self-aborts at the configured bound; an fd item reopened twice
  never moves the caller's descriptor offset (Android host + native).

### 7.2 KP-NET: the streaming resilience program. Size XL, own expansion at entry

Today: unvalidated 206 with no Content-Range/ETag/If-Range anywhere; a seek that validates
nothing and a class with no closed flag; no timeout/retry/backoff/reconnect; DASH picks one
representation by bandwidth, drops audio, refuses live and multi-period, cannot seek; an MPD
repeat count taken verbatim from XML; module unpublished while all eleven others publish.
Scope at entry: response validation, resilience, real DASH ABR (audio, live, multi-period,
seek), HLS (prerequisite: 7.1's interrupt seam), bounded prefetch + progressive cache +
resume, publication. Exit: the first-afternoon script's network legs pass on devices.

### 7.3 SOL-P9 remainder: live media cannot switch VIDEO tracks. rides 7.2

Audio and subtitle switches are live in-graph (proven to unseekable sources); video still
rebuilds the session by design, so live/network media cannot switch video. Lands with the
network machinery; keep reduced until then.

---

# PHASE 8: DISTRIBUTION (KitePlayer's install story)

### 8.1 KP-B1..B13 release half: the hygiene cluster. Size M-L

- [ ] Release build not debuggable, release-signed (executor wires config, owner holds keys).
- [ ] Wrapper checksum, dependency lockfiles or verification metadata, NDK pinned by exact
  version (today: chosen by string sort).
- [ ] `checkPublicationReadiness` grows real checks: developers block, licence, SCM, signing,
  Sonatype coordinates (today it reads generated POM XML and nothing else).
- [ ] The two unpublished optional modules (`kiteplayer-network`, `kiteplayer-libass`)
  publish, or the README states their absence.
- [ ] SOL-B8: the ordinary Android AAR publication, proven by a consumer smoke resolving it
  from a staging repo.

### 8.2 SOL-B5 engineering: armeabi-v7a. Size M

Owner-ruled: every ABI stays. Add armeabi-v7a to the JNI link recipes and the libass adapter.
Three gates before the ABI is CLAIMED: (1) the RT ring's 64-bit positions audited for ARMv7
atomics (LDREXD class) with a compile-time lock-free assert; (2) a CI compile lane; (3) one
TV-stick smoke (owner lane). x86-32 the day Synkplay ships it.

### 8.3 KitePlayer to Maven Central (closes the KP-PROD install phase). Size L, owner executes the publish

Replay the KiteFFmpeg playbook: publish/release workflow trio, staging first, artifacts for
every advertised target or the target is not advertised. Exit: a machine that has never seen
this checkout builds `implementation("io.github.yuroyami:kiteplayer-mobile:0.0.x")` green
against Central, resolving `kiteffmpeg-core` 0.1.x (the rename lands first). Release-gate
boxes 11, 17, 18, 19, 20 re-grade here; 12/13/14 get their device/browser halves from
DEVICE-DAY and Phase 6. The codec-side publish already happened at 0.3; nothing else waits on
it.

---

# PHASE 9: LONG TAILS (each enters through its own expansion)

- [ ] **9.1 SOL-C2, CoreAudio setup to Kotlin** (M): non-realtime setup, session policy,
  route/interruption handling, capability queries and error mapping move from C to Kotlin;
  unsupported-platform C stubs become expect/actual. (The C grew since first measured.)
- [ ] **9.2 SOL-C1 + SOL-C3, the C-reduction charter** (L): replace one-line helper C with direct cinterop
  (packet, codecpar, stream, error, trivial frame/codec, most of format/playback); 198
  exported symbols is the measured baseline and it ratchets DOWN per slice. The
  args-composition C moves to Kotlin with it (it never truncated; the C is just redundant).
  KEEP list is in GOTCHAS.
- [ ] **9.3 SOL-P3, frame access copies** (M): native pays scratch + second ByteArray, JVM copies
  before JNI's own copy, nominally zero-copy reads box a plane list per access. One reused
  holder, one copy fewer per backend.
- [ ] **9.4 KC-PERF** (XL): the ten hot paths (JVM upload chain of 3+ copies, output path
  allocating native then copying to Java, no common zero-copy lease, handle-table scaling,
  per-frame graph keys, thread-local scaler with no session). Re-measure first: the per-byte
  web half is already dead.
- [ ] **9.5 KC-BRIDGE** (L): 14 JNI defects, led by the generation mask/compare mismatch
  (masked at 31 bits, stored and compared at 32: a slot past 0x7FFFFFFF stops resolving;
  RED with a forced high slot), no lease on resolved handles, O(n) close scan over a table
  that never shrinks with caller-controlled recursion, kind bits never validated, a pending
  JNI exception during mint leaking the context, modified-UTF-8 on two paths with the correct
  decoder unused, callback exceptions collapsed to generic IO, an attached thread never
  detached, registration erasing every C signature.
- [ ] **9.6 KC-CFILTER** (M): `[out]` found by substring; sources published progressively
  then freed on failure leaving earlier entries dangling; four unchecked `av_strdup`; a plane
  index never bounded (and the test asserts the wrong answer; fix both); an eight-channel cap
  on upload only.
- [ ] **9.7 KC-DSL** (L): untyped steps, no `@DslMarker`, six of seven types unvalidated, raw
  strings where typed `SampleFormat` exists, the raw map applied after typed keys so it wins
  (same collision law as 3.2), CBR emitted as VBV without `nal-hrd`, the x264 preset ladder
  emitted for every codec including VideoToolbox, `CodecId` conflating bitstream identity
  with implementation.
- [ ] **9.8 KC-BUILD** (L): 23 defects, led by `/usr/lib/include` on Linux (simply wrong),
  target truth duplicated across five hand-synced representations, a cache key recording a
  URL it never compares, redirects auto-followed inside the loop validating them manually,
  filename-only validation of local trees, two plugin tests excluded with no CI running any,
  unescaped `-D` values, unconditional `dllexport` for a static build, cache keys hashing one
  file, a 1,286-line build script.
- [ ] **9.9 SEAM** (L): mismatched target graphs across modules; an `api` leak of a KiteFFmpeg
  `Frame` pinned in both committed ABI dumps (breaking fix: owner sign-off on the dump move);
  non-transactional source close; 467 hand-written metadata mappings with one test (add a
  generated cross-check); four modules repeating one config block with one missing a flag;
  two version catalogs already drifted.
- [ ] **9.10 PAR-6, hardware AV1 route** (M): a decoder chosen BY NAME (no path exists today), policy
  = open native `av1` with VideoToolbox attached, fall back to libdav1d when hardware
  refuses (the measured-fallback shape h264/hevc already have). This Mac proves the refusal
  path only; positive proof + the 4K verdict are owner hardware.
- [ ] **9.11 PAR-3, android-x64 SIMD** (S): the emulator ABI builds with `--disable-asm` (0 SIMD
  symbols vs arm64's 1365). Drop the flag, rebake, verify with `llvm-nm`.
- [ ] **9.12 PAR-WIN-HW + PAR-2, Windows/Linux hardware decode** (L, with desktop video output): Windows carries
  18 D3D11VA/DXVA2 hwaccels compiled and not plumbed (correct behaviour, stale prose fixed);
  Linux compiles zero (VAAPI is the candidate). Needs a hardware device context + frame
  download path in KiteFFmpeg; proof needs owner glass.
- [ ] **9.13 SOL-API7, renderer capability negotiation** (L, NEEDS-DESIGN): unsupported frame/renderer
  pairings refuse TYPED at first frame today; a sealed hardware-surface model plus attach-time
  negotiation moves the refusal to bind time. Coordinates with the renderer-event surface
  3.13 touched.
- [ ] **9.14 KP-RQ, the render-quality ladder remainder**: RQ-4 linear-light scaling (two passes,
  RGBA16F intermediate at source size, then the kernel draw; both shader bodies; the rung
  that changes pipeline shape, so golden-first), then RQ-5 Anime4K (curated built-in port,
  two tiers, own program), RQ-6 HDR passthrough (horizon: skip tone map on capable Apple
  displays, float16 + extended-range layer). Laws: disabled is bit-exact, nothing defaults on
  without a device measurement, one knob surface (`RenderQuality` on `PlayerConfig`), every
  rung lands on Metal + Android GL together or opens its skip. Apple device numbers owed
  (owner lane); Adreno numbers are in GOTCHAS.
- [ ] **9.15 The C-layer backlog, the old B-horizon** (harvested; each S-M):
  gate call inside the C library's constructor helpers (today only Kotlin call sites enforce
  the identity gate, a pure C/JNI consumer reaches FFmpeg ungated); an entry-point audit for
  `requireCompatibleFFmpeg` (15 call sites, nothing keeps them complete); a C ABI version
  ratchet (surface grows, claims 1.0 forever); the fuzz rule (every new string-parsing entry
  point gets a target; two public-reachable ones have none: `ffkmp_fmt_alloc_output2`'s
  format and the three raw `*_by_name` lookups); split `test_convert.c` contract vs baseline
  counts; derive C suite lists from files on disk (six hardcoded literals; a suite added to
  build and not to run is compiled and never run, green); the ring write asymmetry + two
  dead accessors + the misattached ring KDoc; the four early returns in the device callback
  that skip zeroing and the OutputIsSilence flag; teardown defence with a mock AudioUnit;
  the two forked C harnesses (decide source-of-truth vs vendored copy); the opaque-migration
  prototype (one family early, before more C work depends on the assumption);
  `check-deleted-surface.sh` failing on an empty prose set; the coverage-guided fuzzing
  program itself (harness, corpus and replay exist; true fuzzing runs in Linux CI; demuxer
  and decoder byte paths are the security gap).
- [ ] **9.16 FFmpeg 9.x: DEFERRED INDEFINITELY, owner-decided 2026-08-29.** Not scheduled, not
  refused. The research is written down here so nobody repeats it.

  **What 9.0 is.** Released 2026, 9.0.1 on 2026-08-12, codename Lei. Over 2,200 commits. It
  bumps the major of all seven libraries (libavutil 61, libavcodec 63, libavformat 63,
  libavdevice 63, libavfilter 12, libswscale 10, libswresample 7), so it is an ABI break
  across the board.

  **What we would gain, and it is one thing:** swscale was rewritten with x86 SIMD, AArch64
  NEON and Vulkan backends. That is the CPU conversion path, which is hot for the web tier,
  the desktop JVM and AWT renderers, Android pre-31 KiteVideo and the Apple CPU fallbacks.
  Treat it as a hypothesis to MEASURE, never a claim to inherit: a rewrite can move output as
  easily as speed. Secondary and not urgent: Dolby Vision Profile 7, SMPTE 2094-50 HDR
  metadata. Irrelevant to us: Vulkan filters, AMF and CUDA work, the ONNX DNN backend,
  animated WebP.

  **Why it is cheaper than an ABI break sounds, measured against our tree on 2026-08-29.**
  Zero exposure to everything 9.0 removed (CELT, Sonic, OpenMAX, NPP filters, packed YUV
  v308/v408/v410, old NVENC options): not one reference in the 11 C sources or the build.
  The C layer is already on modern APIs: `ch_layout` across five files, send/receive decode,
  no `av_init_packet`, no `avcodec_close`, no `avcodec_decode_*`. `avfilter_graph_parse_ptr`,
  which `helpers_filter.c` depends on, still exists in release/9.0 and is NOT deprecated
  (checked in the header itself; blog claims of a dictionary-based replacement are false).
  TLS-verified-by-default does not reach us: our profiles build no TLS and pin protocols to
  file/fd/pipe/data/http/tcp, with TLS terminated by Ktor and the OS.

  **What it would still cost:** everything in item 0.2 plus a fixture regeneration, a host
  ffmpeg series move, and re-verification of every colour golden against a rewritten
  swscale.

  **Why deferred rather than done.** 9.0.1 is weeks old and the ecosystem is still migrating
  seven new sonames. Our evidence system is calibrated on the 8.x line, and this project's
  measured failure mode is claims that stopped being true, which is exactly what a
  simultaneous churn of goldens, baselines and fixtures invites.

  **Revisit when all three hold:** the desktop work of Phase 1 has landed, so its CPU
  conversion path can measure the swscale rewrite instead of assuming it; item 0.2 is done and
  the gate is green, so the bump is one variable and not two; and 9.x has had a few more point
  releases. Note when picking it up that 9.1 and 8.2 were both already in development on
  2026-08-29, so check what the current lines are rather than trusting this paragraph.

---

# THE OWNER LANE (executor prepares, owner acts)

### DEVICE-DAY: the hardware run sheet (one afternoon, devices out once)

Needs: an Android phone (Main10-capable covers more), an Android TV stick (Fire-TV class,
32-bit), an iPhone or iPad. KiteStats prints the numbers. Build the sample per device first;
a failed build is itself the first finding.

**Android phone, GPU truth:**
1. Play `sync1080p30.mp4` 30 s; write down frames presented/dropped and frame time.
2. Compare against `ANDROID_GPU_WORK.baseline.txt`. PASS: not slower, no extra drops.
3. Scrub hard for a minute. PASS: picture always returns, nothing needs a pause to recover.
4. Background and return five times. PASS: picture every time.
5. 30-minute soak watching graphics memory in the profiler. PASS: settles flat.
6. Play `hevc4k10.mp4` if the phone does Main10. Note plays or refuses (hardware-AV1 row
   input).

**Android phone, pixels on glass (screenshot each):**
7. Subtitles over a bright scene. PASS: clean edges, no grey box (double-premultiply tell).
8. A portrait-rotation clip. PASS: picture AND subtitles upright together.
9. A positioned cue plus ordinary bottom cues. PASS: placed one sits where authored,
   ordinary ones stay at the bottom.

**Android phone, resume anchor (the S23 fix's device half):**
10. Pause 30+ seconds mid-playback, resume. PASS: no position jump, sync holds (the fix
    rejects pre-pause device timestamps; this confirms it on real silicon).

**TV stick (only after the 32-bit ABI builds, Phase 8.2):**
11. Sideload, play 2 minutes. PASS: video + audio in sync, no crash on open.
12. Listen for clicks/drift. PASS: none (the ring's 32-bit atomics question).

**iPhone/iPad:**
13. Play, seek, background, return (as steps 3-4). PASS: picture returns every time.
14. Read KiteStats once at the end; write the numbers down.

**iPhone, the dense-subtitle acceptance run (Release build only; Debug invalidates feel):**
15. The dense Kaguya ASS file vs its `-sn` remux, back to back: scrub hard, mash pause/play a
    minute each. PASS: identical feel, every command immediate on both. FAIL: capture the
    KiteStats window around one delayed command.
16. Track cycles while the dense file plays (subtitle A/off/on/B; audio A/B/off/on). PASS:
    correct text and sound each step, no picture hitch, no status flash, presented frames
    keep climbing.
17. Repeat 16 after a precise seek and while paused; on a live/unseekable source too if one
    is handy.

**Desktop, the W riders (from Phase 1):**
18. Run the desktop sample's native-view demo on a Windows machine and a Linux desktop:
    z-order (controls above video) and the jank-decoupling toggle, same pass rules as the Mac
    run. Also the full format matrix once on real Windows (it has only ever been a link
    claim).

Report per step: pass, fail, or not run, plus one line of what you saw; screenshots for 7-9.

### Standing owner items

- [ ] **Publishes**: every `git push`; KiteFFmpeg 0.1.4 to Central; KitePlayer's first Central
  release (Phase 8.3); any toolchain bump an executor requests.
- [ ] **PAR-5 decision**: native linux/mingw output targets declare no source sets;
  recommended close = record as decision (native desktop targets are engine-only; consumers
  bring output through the SPI; the desktop story is the JVM). Say yes or move it.
- [ ] **KP-DESK-NV-AUTO**: flip desktop Auto to the native view, or keep Compose canvas, on
  Phase 1.5's numbers.
- [ ] **Anime4K go/no-go** after RQ-4 lands, on device numbers.
- [ ] **Blocked-upstream rechecks**: AGP bump = re-measure the one Gradle 10 deprecation;
  Kotlin bump = re-measure whether `abiValidation` grew an Android dump variant. Both in
  GOTCHAS with their measurement commands.
- [ ] **AV1 positive proof**: one AV1 clip on an A17 Pro / M3 or newer after 9.10, plus the
  4K clip that settles the 4K exit criterion.
- [ ] **Apple render-ladder numbers**: the Metal halves of dither/deband/kernel have no
  device measurement yet; defaults stay off until they do.

---

# APPENDIX: the parity map (what a mature player still needs, per domain)

A MAP, not a backlog: stage entries ask "which of these does this phase buy". Comparison
class is libmpv/libVLC. Parity of what EXISTS beats new feature count; nothing here is
scheduled by being listed.

- Session: interruption policy, crash-safe recovery, documented readiness.
- Clock/sync: route recovery (5.3), refresh-rate change, passthrough clocks, live-edge
  policy.
- Queue: playlist/queue itself, gapless, crossfade, shuffle (does not exist today),
  persistence.
- Rate: reverse/trick play, slow-motion policy, scan/jog.
- Tracks: in-place video switching (7.3), ranked language/accessibility policy (3.6),
  multi-angle, stable handles.
- Subtitles: Phase 4, then style override, external URL sources, accessibility captions.
- Video out: Linux/Windows GPU contexts (9.12, KP-DESK-NV-GPU), WebGL/WebGPU, HDR display
  capability (RQ-6), hotplug, thumbnails.
- Audio out: WASAPI/ALSA/Pulse quality backends, enumeration/hotplug (5.3), exclusive mode,
  passthrough (re-filed), replaygain, limiter, equalizer.
- Network: Phase 7. Live: DVR window, low-latency HLS/DASH, discontinuity policy.
- Chapters/programs: end-aware chapters, programs/editions (deleted from API until built),
  attachments.
- Processing: typed filter plans, runtime rebuild, recording-while-playing, thumbnails,
  waveforms.
- Observability: sequenced transitions, structured logs with redaction, per-stage latency
  metrics, trace export.
- Platform: media session, lock screen, audio focus, real PiP, casting (decided later
  horizon), background policy, accessibility semantics.
- Security: DRM/CDM (out of scope until product decision), secure surfaces, credential
  redaction, sandboxed parsing.
- Extensibility: stable plugin points for protocols, decryptors, subtitle providers, effects;
  none exposing FFmpeg or JNI internals.

# APPENDIX: format conformance and size tiers

The conformance matrix = the clips `scripts/testmedia.sh` generates plus the matrix tests
(MustPlay rows and torture rows live in the tests). "Plays all formats" is one measured claim
against THIS matrix on every platform that advertises playback. Size tiers (lean, standard,
full) are measured per target at release time; no size is promised before it is measured; the
web tier's gzipped size is the one that gates (1.22 MiB today, over the original 1.00 MiB
spike budget, tier decision open).
