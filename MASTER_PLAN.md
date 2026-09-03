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
sibling under both names: the old `kitecodec-core` up to 0.1.3, which receives nothing further,
and `kiteffmpeg` 0.1.0, published 2026-08-31. KitePlayer itself has never been published.

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
| 4 | Wasm MediaSource behaviour matches the shared suite | AMBER: the three fixes that had no test now have three, each proven to fail when reverted (2026-08-30). Full parity still waits on an FFmpeg wasm build, since the shared codec-contract suite decodes real media and the fake cannot stand in for that |
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
- [ ] **Synkplay** moves its pin to `kiteffmpeg` whenever it next bumps KitePlayer. Nothing
  blocks on it, and its adapter needs no change beyond imports. **One thing to check on that bump
  if Synkplay has a desktop build:** desktop `Auto` now resolves to the native view, so Compose
  controls drawn over the video stop receiving clicks. Either move them into an owned overlay
  window or pass `KiteRenderPath.ComposeCanvas` explicitly. Mobile is unaffected. **And once
  5.5's Task 6 has landed:** replace `KiteMediaResolver`'s descriptor dance with `MediaIo.ofUri`,
  and opt in to `KitePlayerLowLevelApi` only if any `openOptions` use remains.

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
**Resize was fixed rather than measured (2026-08-30)**, because looking at it found two real
defects and neither needed a window to prove. A `BufferStrategy` owns buffers of a FIXED size and
AWT never reports one as stale: after a resize the renderer drew the new frame into the old
buffers, so the picture stayed clipped or stretched until something else happened to rebuild it.
The presenter now tracks the size its strategy was built for and rebuilds when the canvas
disagrees. Separately, a resize while PAUSED had no frame arriving to trigger a repaint at all,
so the renderer now listens to its canvas, and lets go of that listener when the canvas changes
and at close: one left behind keeps the renderer alive with a canvas the view already discarded.

- [ ] The remaining half is a real window: what these arms cannot see is the picture. A run that
  drags a window edge during playback and watches for tearing, a blank frame or a wrong aspect
  belongs on the desktop half of DEVICE-DAY, with the Windows and Linux runs above.
- [ ] **KP-DESK-NV-GPU**, later and only if wanted: a JAWT presenter owning a CAMetalLayer on
  macOS, D3D on Windows, EGL on Linux, replacing the CPU blit. Nothing measured so far demands
  it, and note before starting that it does NOT fix the input constraint, which belongs to native
  view ordering rather than to how the surface is painted. Compose also documents that DirectX
  blending cannot overlay another DirectX component, which constrains the Windows half.

# PHASE 2: EVIDENCE BUY-BACK (make the unprovable provable)

### 2.1 The test-debt row: what is left needs a DECISION, not a test

Seven of the eleven were written on 2026-08-30 and are gone. The three below are still open for
the same reason: each one asks the code to SAY something it currently does not say, and what it
should say is a public API choice. None of them is a missing test over working behaviour, so
writing a test first would only pin the silence.

- [ ] **NEEDS-DESIGN. Foreign `StreamInfo` refuses typed (KP core).** Four entry points take a
  caller-supplied stream and answer four different ways: `selectTrack(TrackId)` validates and
  throws `IllegalArgumentException` (deliberate, and `Tracks` KDoc says so); `selectStreams` uses
  `mapNotNull`, so `{0, 999}` selects 0 and never mentions 999; a decoder factory handed a foreign
  stream reaches `error("no stream at index N")`, untyped, from the bottom of the stack; and
  `StreamChoice.At(missing)` resolves to null, indistinguishable from `None` and with no warning.
  Through the core the third degrades to a typed `NoPlayableStream`, so this is about the SPI's own
  contract. Decide first whether `IllegalArgumentException` counts as "typed" for caller mistakes
  (the existing policy) or whether the SPI owes `PlaybackException` throughout.
- [ ] **NEEDS-DESIGN. Decoder output diverging from codecpar is surfaced (KC).** A stream's
  `codecpar` announces width, height, pixel format, sample rate and channels; the decoder may emit
  something else. Nothing compares them, and there is no channel to report it through: KiteFFmpeg
  has NO logger and NO warning callback anywhere in its Kotlin surface. Its whole non-fatal
  vocabulary is pull-style values on objects (`corruptDataSkipped`, `unusedOpenOptions`), and the
  one place a mismatch is checked (encode-side dimensions) throws. Throwing is wrong here: these
  are files that play. So the decision is which shape the report takes, and `corruptDataSkipped` is
  the closest precedent.
- [ ] **NEEDS-DESIGN. Midstream audio format change reaches renegotiation or a typed warning (KP).**
  The conversion half already works: `AudioPipeline.matches` is full format equality, so a change in
  rate, channels, sample format or layout rebuilds the pipeline on the buffer that changed. What
  does not exist is observability. `decoder.outputFormat` is read at open and at track switch only,
  the sink and ring keep their negotiated format for the session, and `AudioFormatChanged` is never
  emitted mid-stream. A plain 48 kHz to 44.1 kHz change is completely silent. Decide between
  renegotiating the device and emitting a warning, then note that a test also needs a new harness
  knob: `ScriptedAudioDecoder.outputFormat` is `private set` and every buffer is built from it, so
  the scripted decoder cannot currently change format mid-stream at all.

**Found while writing the `Frame.info` pin, 2026-08-30. NEEDS-DESIGN, size S.** A closed frame
refuses on every target, but not with the same exception. JVM, Android and native throw
`IllegalStateException("Frame is closed, its native buffers are gone")`; the web throws
`FFmpegException(Internal("this frame is closed"))`, from `alive()` in `Frame.wasmJs.kt`, which
every closed-frame read on that target goes through. `FFmpegException` extends
`RuntimeException`, not `IllegalStateException`, so a caller cannot catch both with one clause.
Two of the three agree, the shared contract suite already asserts `IllegalStateException`, and
commonMain documents nothing either way. Making the web agree is a five-line change and cheap to
land: the only `catch (FFmpegException)` anywhere near this is the identity gate in
`FFmpegRuntimeCheck.kt`, not a frame path. It is listed rather than done because it changes which
exception a published public method throws, which is the owner's call, not the executor's.

### 2.2 F-COV1 recounted 2026-08-30. What is left is what cannot run here

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

> **Four rows below wait on ONE thing: new C entry points.** Checked 2026-08-30 rather than
> assumed. `native/kitecodec-c` has no `swr_*` binding at all (so 3.4 cannot CONVERT audio), no
> `av_codec_iterate` (3.10 cannot enumerate), no `ffkmp_stream_set_metadata`,
> `ffkmp_stream_set_disposition` or any chapter WRITE (3.3 cannot carry identity through a remux),
> and no `ffkmp_*vp9*` for wasm (3.7's last field). Each is a small C function; what makes them one
> job is the tax around them: the signature baseline, the generated wasm binding and its CI mirror
> check, the JNI wrapper, and a compile on all twelve target trees. A machine with one FFmpeg tree
> can write them and cannot prove them, so they want one deliberate pass on a machine that can
> build every target, not four separate half-verified ones.


### 3.2 KC-SPEC remainder: an encode still flattens colour, HDR and exact layout. Size L

The collision half landed 2026-08-30: an option that duplicates a typed field is refused, naming
both, on both backends. `ch_layout` is deliberately NOT refused, because it says something a
channel count cannot.

- [ ] The propagation half. `MediaSink` specs still carry only codec, size, pixel format, rate,
  bitrate and keyframe interval, so nothing about the SOURCE reaches the output: an HDR encode is
  flattened, 5.1(side) becomes 5.1(back), and pixel aspect is dropped. Add typed colour
  (primaries/transfer/matrix/range), mastering metadata, SAR and exact layout to the specs, and
  propagate from `StreamInfo` when the caller did not override. RED: a scripted HDR source carries
  its colour into the sink untouched. Both backends, wasm records its bound, apiDump.
  **Newly cheap:** `ColorInfo` now carries per-field provenance (3.5), so propagation can copy what
  the source DECLARED and leave what this library guessed, rather than declaring a guess as fact.

### 3.3 KC-REMUX: a "lossless" remux drops identity. Size M

Both backends copy codec parameters and one time base, then stop: tags, language, title,
disposition, rotation/display matrix, side data, stream groups dropped; chapters readable and
never written; no program or attachment path.

- [ ] Carry per-stream metadata, disposition, rotation, side data; write chapters. RED with
  an `ffprobe` oracle over a fixture carrying rotation + language + chapters. Whatever stays
  out is named here as remainder, not skipped silently. Fix the README "bit-exact" wording in
  the same commit.

### 3.4 KC-AENC remainder: the audio encoder still cannot CONVERT. Size M

The validation half landed 2026-08-30, and the row understated the defect: a channel-count or
sample-format mismatch did not "fail late and cryptic", it **segfaulted the process**, because
FFmpeg reads the frame using the ENCODER's channel count and format. A rate mismatch was accepted
silently and encoded at the wrong speed. All three are refused typed now, on both backends, from
one rule in commonMain.

- [ ] What is left is the conversion the video side has and audio does not: a caller holding
  fltp when the encoder wants s16 is told to route through `FilterGraph.buildAudio` rather than
  being converted on the way in. Doing it properly needs **swresample bound through the C ABI**,
  which nothing reaches today (it is linked and version-reported, but no `swr_*` entry point
  exists). That is a new C surface across 12 targets plus JNI plus the wasm binding mirror, so it
  wants a machine that can build every tree, not a one-target check.

### 3.5 KC-COLOR-PROV remainder: carry the provenance into KitePlayer. Size S

KiteFFmpeg side landed 2026-08-30: `ColorInfo` carries `matrixSpecified`, `primariesSpecified`
and `transferSpecified` beside the existing `rangeSpecified`, all five guess sites go through one
`resolveDeclaredColor`, and the web frame reader stopped ignoring matrix, primaries and transfer
entirely. Both directions tested against the web fake.

KitePlayer side landed 2026-08-30 and this row is CLOSED. `ColorSpaceInfo` carries all four
flags plus `allSpecified`, `Conversions.kt` maps the three new ones across, and `guessFor` and
`Unspecified` admit they are guesses.

**The open question is answered NO: the tone-map warning does not need to say which of the two
it acted on.** A guess can never be HDR, because every guess in the pair is the
standard-versus-high-definition rule and that answers BT.601 or BT.709 and nothing else, so a
tone map is always acting on the file's own declaration. That is a property of the guess rule
rather than an accident, so `ColorProvenanceTest` pins it: if a future guess rule ever learns to
answer PQ or HLG, it goes red and the warning has to grow a field.

### 3.6 KC-TRACKSEL + the disposition widening. Size M, NEEDS-DESIGN

JVM/Native skip attached pics then take first video; `primaryAudio` is `firstOrNull`; no
`TrackSelector` exists. And `Disposition` collapses hearing-impaired and visual-impaired into
one boolean while DESCRIPTIONS and COMMENT are read nowhere, so descriptive audio and
commentary are indistinguishable at every layer.

- [ ] Design commit: small `TrackSelector` policy (defaults documented, language hook) +
  widened `Disposition` flags. Then: cover-art-first fixture picks real video on wasm too;
  descriptive audio never auto-picked over an ordinary sibling. apiDump.

### 3.7 KC-WASM-MODEL remainder: VP9, and a run against a real wasm build. Size S

Landed 2026-08-30: container, stream and chapter metadata, the chapter table, start time
(rescaled from stream ticks, which the first attempt got wrong), extradata, declared colour,
channel layout mask, and Attachment/Unknown as themselves rather than collapsed to `Data`. One
test per field against the fake, two falsified.

- [ ] **VP9 codec info** is the one field still absent, and unlike the rest it is not a matter of
  calling something that already exists: no `ffkmp_*vp9*` entry point is bound for wasm at all.
  That is new C surface, so it rides whatever pass adds the others.
- [ ] **[owner] One run against a REAL FFmpeg wasm build in a browser**, recorded as manual
  evidence. Everything above is proven against the fake, which is the right tool for "does the
  Kotlin read the right fields" and no tool at all for "does the built artifact agree".

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

### 3.9 `FilterGraph.process` handles a cancelled emit differently per backend. Size S, NEEDS-DESIGN

Found while closing the buffered-flow leak, 2026-08-30, and NOT fixed with it because the two
backends are wrong in opposite directions and the right answer is a contract choice.

- JVM (`FilterGraph.jvm.kt`, the `emit(out)` site) wraps the emit in `catch (Throwable) {
  out.close(); throw error }`. But `take` and `first` end a flow by throwing out of `emit` AFTER
  the value reached the collector, so `process(input).first()` hands back a frame the library then
  closed. Every read on it refuses.
- Native (`FilterGraph.native.kt`, same site) has no catch, so an emit cancelled from OUTSIDE
  strands the clone instead.

Both failure modes look identical at the emit site: nothing there can tell "the collector took it
and stopped" from "the scope died before delivery". Decide which loss is preferred, or give the
frame a reclamation path that makes the question moot, then align both.

### 3.10 KC-CAPS: enumeration + measured build inventory. Size S + S

`FFmpeg.hasDecoder(name)` exists; ENUMERATION does not (`av_codec_iterate` unbound), and
`kiteffmpegInfo` prints the dsl TOGGLES, not a measured inventory of the linked tree. Design
commit for `FFmpeg.decoders(): List<String>`; make the info task measure. RED: list contains
h264 everywhere; wasm fake scripts its list.

### 3.11 KC-DOCTRUTH remainder: register codes in shipped sources. Size M

A stranger cannot resolve `F-WRN1` or `audit P1-05`. They stay in MASTER_PLAN, GOTCHAS and git
history; the tree should read without them.

Swept 2026-08-30 in two passes: 349 parentheticals sitting MID-sentence, then 92 more where the
code OPENED the comment (`// SOL-R1: text` becomes `// Text`) plus `PlaybackCore.kt`'s 34 by hand.
Compiled and tested green after each. Counted honestly, **569 remain**:

**Dead pointers are gone entirely (2026-08-30).** 96 references to `KPKMP.md`, `KPKMP-PAST.md`,
`KPKMP-FUTURE.md`, `HANDOFF.md` and `SUPREME.md` pointed at files deleted on 2026-08-29, plus 82
bare `KPKMP`/`SALANKE` program names. Every one now points at `MASTER_PLAN.md`, at KiteFFmpeg's
`PLANNING.md`, or at nothing because the sentence stood alone. That count is zero and should stay
zero. `KiteFFmpeg/PLANNING.md` still names them, deliberately: it is the note explaining what they
were and where they went.

**Counted again 2026-08-30 with a tighter pattern, and three more mechanical shapes swept.**
KitePlayer went from 362 to 291. What the earlier passes missed: a code opening a BLOCK comment
(`/* SOL-A2: text` and `/** RQ-2: text`, not just `//`), a possessive opener (`SOL-A2's recovery
arm:` becomes `The recovery arm:`), and parentheticals spelled `(register item B1-17)` rather
than `(B1-17)`. Two capitalization traps, both caught in preview rather than in the tree: a
camelCase identifier must not be capitalized (`mavenLocal`, `getLongFramePosition`) and neither
must a one-letter name followed by `=` (`r="-1"`).

**KitePlayer's sources are at ZERO (2026-08-30), counted rather than estimated.** Every register
code is gone from every Kotlin, C, header, shell, Gradle and workflow file in this repository.
Three that were user-visible went with them: a browser tab reading "KV-6 probe", a measurement
report headed "=== KV-5 desktop upload measurement ===" and a CoreAudio exception quoting
"register item B1-17" at somebody who cannot look it up.

**KiteFFmpeg went 222 to 23 on the same day.** The four sweepers applied unchanged, plus one
rule for its own commonest code: `KC-EMBED` was 40 of the 222 and is not a defect row at all but
a NAMED EVENT used as a date marker, so those became the date.

What is left there is 23, and each is somewhere it belongs or somewhere not to touch:
`native/kitecodec-c/README.md` has 12 in a table keyed BY row identifier (rewriting a lookup key
deletes the table); an FFmpeg patch file has 3 in header metadata git reads; the four ratchet
baselines have 5; and 3 sit elsewhere where no reader has to resolve them. Treat that as done
unless the owner wants the README table re-keyed.

This row is CLOSED for both repositories except the measurement notes below.
- [ ] DECIDE separately what to do about the 28 codes in `MEASUREMENTS.md` and the web spike note.
  Those are dated records of what was measured on a day, and a measurement that says "this is
  what W-05 asked for" is describing its own occasion rather than the tree. Rewriting them may be
  falsifying history rather than clearing jargon.

  A reusable two-pass sweeper is in the commit that did this: pass one takes mid-sentence
  parentheticals, pass two takes codes that OPEN a comment and capitalizes what follows. Watch the
  capitalization: it turned `close()` into `Close()` on three lines.

**Two traps for whoever does the rest, both hit on 2026-08-30:**

- The naive regex ate the space after `//` and left `//.` and `//:` on 30-odd lines. Removing a
  parenthetical is only safe when a non-space, non-comment-marker character precedes it.
- `vendor/` and `native-libs/` are third-party FFmpeg, VLC and fribidi sources and BUILT headers.
  They match the pattern by coincidence (`W-...`, `I-12`) and must never be swept. `native-libs`
  is gitignored, so damage there would not even show in `git status`.

Not every match is a code: `X-Ignored`, `X-Raw` and `X-Session` are HTTP headers and `X-macro` is
the C idiom. Requiring digits after `X-` excludes all four.

### 3.12 KC-ABI-SCOPE: the API ratchet watches 3 of 13 targets. Size M

An iOS-only public API change passes today (dumps re-based under the host-only flag).
Fix: CI fetches the prebuilt static trees (the mechanism the consumer jobs already use) for
ios/linux/mingw and dumps with `-Pkiteffmpeg.requireAllTargets=true` on the macOS job. Prove
live with a throwaway iOS-only public function that must fail the widened check. Reduce to
any genuinely unreachable targets instead of closing if some tree cannot exist in CI.

### 3.13 KP-TONEMAP-WARN remainder: the Android GL tier. Size S

The split shipped, and the renderers now publish (2026-08-30): Metal, the AWT desktop
renderer, AppKit and UIKit all raise `RendererEvent.ToneMapEngaged` once per renderer, so
`PlaybackWarning.HdrToneMapped` can finally fire. `SoftwareConverter.toneMapsHdr(colorSpace)`
is the CPU tier's answer, the same decision `toRgba` makes, and `willToneMap()` is the Metal
tier's, pinned against the shader's own uniforms by a test. Emission is never from metadata: a
converter that hands HDR through untouched answers false and its renderer stays silent, which
is its own test arm. The engine fills in the stream index, because a renderer is handed frames
and has no index to quote.

- [ ] The Android GL tier. Its tone mapping is MediaCodec's, requested through the output
  contract (`COLOR_TRANSFER_SDR_VIDEO`) and confirmed by reading the codec's output colour
  back, so engagement is "the codec accepted the request and its output really is BT.709",
  not a converter's answer. That is a different signal from the other four and it can only be
  read on a device.

---

# PHASE 4: THE SUBTITLE PROGRAM

### 4.1 Charset remainder: the multi-byte East Asian encodings. Size M

Landed 2026-08-30. Detection and the ten single-byte tables are pure common Kotlin, so the same
file reads the same on all 21 targets. Arabic, Cyrillic, Greek, Hebrew, Turkish, Baltic and both
Central and Western European subtitles decode; UTF-16 files decode at all for the first time.

**The row's "conversion via platform actuals" was overturned, deliberately.** Kotlin/Native decodes
UTF-8 and nothing else, and its actual here is ONE source set spanning `androidNativeArm32` (no
iconv below API 28) through `mingwX64` (no iconv at all). The platforms that DO have decoders
disagree with each other besides, Windows and Java differing on cp932 and cp950, so the row's own
test (one fixture, every target) is unpassable with them. `TextDecoder` on wasm was moot from the
start: that actual cannot read a local file and answers null.

- [ ] Shift-JIS, Big5, GBK and EUC-KR. Detection already RECOGNIZES them from byte-pair shape and
  names them in `SubtitleCharsetGuessed`, so a Japanese file is told what it is rather than called
  undetectable; it just cannot decode one yet. When it lands it should land the same way, as
  pure-Kotlin tables (about 155 KB together for all four), NOT as platform actuals. That size is
  the decision to make, and it is a separate one from this row.

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
NOWHERE (default was inert); `CueLayout.wrap` ignored; `fontFamily` desktop-only.

**The shadow pass landed 2026-08-30 on all three.** The bitmap grows by the offset and the
placement keeps measuring the TEXT box, so switching a shadow on never moves the words. The
default is live now, which means every plain SRT and WebVTT cue carries the one pixel shadow
the type has always described; a transparent shadow colour or a zero offset turns it off and
costs nothing. Desktop and Apple proved red-first (Apple uses CG's own shadow, one call
covering the fill and the stroke together); Android rides DEVICE-DAY with the wrap work.

**`CueWrap` landed 2026-08-30 on all three.** The rule is one shared function
(`wrapWidthFor`): the three modes differ only in the width handed to the platform line
breaker, so shaping and bidi stay AWT's, CoreText's and StaticLayout's. `Balanced` binary
searches for the narrowest width that still fits the same vertical extent, and skips the
search entirely when nothing wrapped, which is most cues. `Never` lets the bitmap grow past
the safe area to the viewport edge and no further. Proved red-first on desktop and Apple;
Android is the named skip (no Robolectric, and android.jar is stubs on the host), so it rides
DEVICE-DAY step 9b.

**Per-span size and outline landed 2026-08-30 on all three.** A cue whose spans disagree no
longer flattens to the first one's size or outline. AWT sizes each run and clips the line's
stroke to that run's own columns (a one-run line, which is nearly every line, skips the clip);
CoreText moved from one context-wide `kCGTextFillStroke` to per-run `kCTStrokeWidth` and
`kCTStrokeColor` attributes, so the outline is a property of the text rather than of the draw
(its stroke joins are CoreText's miter now, not the round join the context used to set);
Android sizes spans with `RelativeSizeSpan` and strokes them with a `CharacterStyle` that sets
the paint per run. The SHADOW stays first-span-whole-cue on purpose: it changes the bitmap's
SIZE, so two spans wanting different shadows would be two different layouts of one cue.

**`fontFamily` landed 2026-08-30 on Apple and Android**, so all three honour it per span.
CoreText looks the family up with auto-activation OFF and then CHECKS the family name it got
back, because it will otherwise hand out a lookalike for a name it does not know; a miss falls
back to the system face deliberately. Android needs no such check: `TypefaceSpan` already
resolves an unknown family to the default. Apple proved red-first (Helvetica changes the
pixels, a nonsense family does not); Android rides DEVICE-DAY.

**N31's reverse stacking landed 2026-08-30**, which closes this row. `CueLayout.stacking`
carries the script-wide `Collisions:` field the same way `authoredHeight` carries PlayResY; the
ASS parser reads it, and all three rasterizers build the pile from the end of the list and turn
the images back the right way round, so only the stack offsets move and the draw order does
not. Desktop and Apple proved red-first, and the parse has its own arm. `kiteplayer-core`'s ABI
baselines moved with it: klib 2924 to 2938 lines, jvm 2362 to 2372.

ROW CLOSED. Delete it once the DEVICE-DAY steps 9b to 9e are run.

### 4.5 KP-P1-15: viewport subtitles. Size M, NEEDS-DESIGN

Viewport-aware rasterisation: safe-area insets, aspect-mismatch placement, scaling policy on
resize. Thin by inheritance; write its expansion first (the three SALANKE suspects S41, S44
and S50's geometry half fold in: cue geometry vs surface geometry mismatches). Then execute
red-first on the rasterizer geometry.

---

# PHASE 5: PLAYER API AND AUDIO TRUTH

### 5.1 KP-API remainder. Size M total, cluster commits

Done 2026-08-30: `editions()`/`programs()` deleted (they threw unconditionally and only a test
referenced them), `Pts.toString` names the absent-timestamp value instead of printing
"-2562047:-47:-16.-854", and `CapturedFrame` validates its plane geometry.

**One item was WRONG and is dropped rather than done: "the process-wide logger beside the
per-player sink nothing reads: delete".** `KiteLog` is read (`PlaybackCore` sends every warning
through it), tested (`DiagnosticsTest`), and used by the iOS sample. There is no second per-player
sink; `PlayerConfig` has no logging field. Nothing to delete.

Also done 2026-08-30: `PlayerStreamInfo` compares its extradata by CONTENT (a data class holding a
`ByteArray` compared it by reference, so a `Set` of tracks held duplicates), and
`@KitePlayerLowLevelApi` now marks the two members carrying raw FFmpeg syntax,
`MediaItem.videoFilter` and `PlaybackProfile.decoderOptions`.

**A second row item was already DONE and is dropped: "the default factory that compiles then
throws".** `KitePlayer.create` has been refusing at config time with
`PlaybackException(ConfigurationInvalid)` for both a missing backend and a missing output, and
`KitePlayerTest` pins both. Nothing to fix.

- [ ] Java/Swift adaptation: NOT built here; stays on this list as a feature item
  (KP-INTEROP-SURFACE, unscheduled).

### 5.2 SOL-API4: the five stats features. Size M-L

Declared, honestly KDoc'd unbuilt, and they are features, not cleanup.

`audioLatency` is DONE (2026-08-30): every sink implemented `latencyNanos` and nobody read it, so
the field was documented "always zero" with the number one field access away. It travels beside
`audioLatencyQuality`, because a figure without its confidence reads like a measured zero.

`droppedFramesDecode` and `FrameDropPolicy.LateAndDecode` are DONE (2026-08-30), and they were
one job: the counter had nothing to count because nothing dropped a packet before decoding it.
The rule is `skipVideoPacketBeforeDecode`, a pure function beside `SyncLaw`: a packet already
half a second behind the published position starts a skip, and the skip runs to the next
keyframe, because dropping one packet out of a group of pictures makes garbage rather than
saving work. Keyframes and packets a precise seek still needs are never dropped. Proved twice,
the rule exhaustively and the wiring through a session whose scripted decoder is four times too
slow, and falsified both ways.

- [ ] `containerBitrate`. Blocked on the C surface, grouped with the others at the top of Phase 3:
  no backend binds an entry point for a container-level bitrate.
- [ ] `SyncMode.ExternalMaster` (a wall clock drives playback and audio resamples to follow).
  NEEDS-DESIGN before execution: nothing in the public API can hand the engine an external clock,
  so the seam is the decision. Virtual-clock test driving a scripted external clock.

### 5.3 The audio program (SOL-A6 split honestly). Size M + M, rest re-filed

- [ ] Device selection (M): enumerate + select output device on desktop JVM (AudioSystem
  mixers) and Apple (device UID; coordinate with the CoreAudio-setup-to-Kotlin move in 9.1,
  whichever runs first). Android routes stay OS-owned, documented.
- [ ] Route recovery (M): CoreAudio default-device-change listener (today `AudioDeviceChanged`
  is dead code on Apple: no listener exists); rebuild the sink through the recovery shape
  `DesktopAudioSink` has. Tier 3 if teardown ordering is touched.
  The desktop half of this is DONE (2026-08-30): the recovery reopen was unguarded, so a refused
  reopen leaked the fresh line and left `driver` pointing at the dead one it had just closed. It
  now applies open()'s law and leaves itself honestly unopened, so the next start retries.
- [ ] Passthrough + offload: re-filed as a feature item (KP-AUDIO-PASSTHROUGH, unscheduled,
  needs hardware evidence + owner scope), not pretended to be a bug.

### 5.4 SOL-P8 remainder: one unproven claim about desktop multichannel. Size S, [owner]

Done 2026-08-30: the desktop sink ASKS the mixer whether it takes the source's channel count and
opens that many when it does, folding to stereo only when it does not. Three tests: six channels
open six, a mixer that refuses six still folds, and mono and stereo never reach the probe at all.

- [ ] **[owner] One volume change on a real Android device.** Reported from Synkplay on
  2026-08-31 and fixed the same day: the gain was applied as audio entered the ring, so a change
  could not reach anything already buffered and stayed inaudible for the ring's whole depth. The
  lag was measured at 174 ms on a 171 ms ring, and it tracked the depth exactly at every depth
  tried. Android is the platform that hurt most, because there the depth is the AudioTrack buffer
  times eight rather than the 200 ms floor.
  What is proven here is the mechanism, on the Kotlin ring Android actually uses: an automated
  measurement of when the change is heard, and a differential oracle holding the C ring to the
  same samples. What no laptop can answer is what an AudioTrack buffer actually is on a given
  handset, so the before-and-after a user would feel is still owed. Synkplay only receives any of
  this when its KitePlayer pin moves.

- [ ] **[owner] One run on real surround hardware.** Every test above drives a scripted mixer, so
  what is proven is that the sink asks and honours the answer. Whether a real
  `AudioSystem.isLineSupported` says yes to 5.1 on a machine with a surround device attached is
  not something this laptop can answer: its own mixers list mono and stereo only, which is the
  measurement the old unconditional fold was built on.

Upmix (mono/stereo to 5.1) stays re-filed as KP-AUDIO-UPMIX, unscheduled, owner taste.

### 5.5 Media input doors and typed open options. Size L total, cluster commits

Written expansion: `docs/media-input-doors.md`. One seam (`MediaIo`), named doors per platform,
typed container-open knobs with a builder, and a raw escape hatch behind the low-level opt-in.
Today a consumer can hand the player a path, a raw descriptor number, or a hand-written byte
reader, and nothing else; Synkplay wrote its own `content://` resolver because of it.

Found while writing the expansion, fixed by its Task 8: **per-item `headers` never reach https.**
`MediaIoResolver.resolve` receives only the URI, so the Ktor resolver sends the headers it was
constructed with and the item's go to FFmpeg's http funnel, which never opens for that item. They
come back as an unused-option warning.

- [ ] **[owner] Decisions A to E** (expansion section 8, recommended answers stated there):
  refuse two keys rather than allowlist (A); a new `kiteplayer-io` module (B); no kotlinx-io (C);
  change `MediaItem.io`'s type now (D); a raw key colliding with a typed field refuses at open (E).
  The GOTCHAS allowlist sentence moves with A. Nothing below starts before these are answered.
- [ ] Task 1: `MediaIoFactory` as a type; `openOptions` refuses `fflags=fastseek` and `usetoc`. S
- [ ] Task 2: `MediaIo.ofBytes`, `MediaItem.from`, and the contract test every door passes. S
- [ ] Task 3: `PipedMediaIo`, a bounded pipe for sources that push. S
- [ ] Task 4: the `kiteplayer-io` module; JVM `ofFile`, `ofPath`, `ofChannel`, `ofStream`. M
- [ ] Task 5: posix `ofPath` on Apple and Linux; `ofUrl(NSURL)` with security scope released on close. M
- [ ] Task 6: Android `ofUri` and `ofAsset`, the descriptor opened per session and read
  positionally, which retires the shared-offset defect by construction. M; device half is
  DEVICE-DAY step 10b
- [ ] Task 7: `DemuxPolicy`, `ProbeDepth`, `CorruptPackets`, and the `mediaItem { }` builder. S
- [ ] Task 8: `MediaIoResolver.resolve(item)`; the Ktor resolver merges item headers over its own. S
- [ ] Task 9: the FFmpeg backend translates `DemuxPolicy`; a raw key shadowing a typed field
  refuses typed at open; `PreOpenOptionsTest` moves with it. M
- [ ] Task 10: `DemuxOptions` in KiteFFmpeg, the sibling `DecoderOptions` never had. M, other
  repo; nothing here waits on its publish
- [ ] Task 11: README "Feeding it media", two cookbook paragraphs; then delete this section. S


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
- **PAR-7**: the Android content:// route. Lands as `MediaIo.ofUri` in 5.5, Task 6: the
  descriptor is opened per session and read positionally, so the shared-offset mutation cannot
  occur. What stays here is the `fd:` protocol path itself for anyone still on it, and the
  device proof.
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
repeat count taken verbatim from XML; module unpublished while the twelve that apply the publish plugin do.
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

Two of these landed 2026-08-30.

**The release build is wired** (executor half done; owner supplies keys). The sample's release
type follows the keystore: with the four `kiteplayer.release.*` properties it is signed with the
real key and NOT debuggable, without them it stays debug-signed and debuggable, which is what the
run-as smoke oracle needs. Proved both ways here, the keyed one against a throwaway keystore that
was deleted after. A correction found while doing it: the old comment claimed R8 still ran on the
keyless build, and AGP says on every run that optimisation and obfuscation are DISABLED for a
debuggable build, so it never did. Only the keyed build is shrunk.

**`checkPublicationReadiness` grew its real checks, and one of its old ones was fixed.** The
sibling-publishability check read EVERY Gradle configuration, so its answer depended on which
ones the task graph happened to realise: alone it saw 21 edges and passed, inside a full gate run
it saw 24 and reported `:kiteplayer-compose` depending on `:kiteplayer-sample`, which no build
file says. It now reads only the scopes a POM can come from and answers 22 either way. A real
violation is still caught, proved by adding one and watching it fail. It already covered licence and SCM (the row
understated it); it now also refuses a POM with no developers entry, which Maven Central requires
and this project did not have at all, and refuses an `io.github.<user>` group that disagrees with
the GitHub account in the SCM URL, because Central grants that namespace on proof of owning the
account. Signing is checked too, but only on a run that says it must publish
(`-Pkiteplayer.requireSigning=true`): an ordinary local build has no key and has to stay green.
The developers block itself was added to the shared POM config, identity only and no address.

**The wrapper checksum landed 2026-08-30**, taken from gradle.org's own
`gradle-9.6.0-bin.zip.sha256` and matching the value KiteFFmpeg already pins for the identical
distribution. `BuildLibassJniTask.locateNdk()` went with it: it was dead (the live resolver is
`kiteplayer-libass/build.gradle.kts`'s own `resolveNdk()`), and two copies of one rule is how
they drift.

- [ ] Dependency lockfiles or verification metadata.
- [ ] NDK pinned by exact version. It is chosen by STRING SORT in four places: three KiteFFmpeg
  build tasks (`BuildFFmpegTask`, `BuildDav1dTask`, `BuildAssChainTask`) and
  `kiteplayer-libass/build.gradle.kts`. That is right for the three NDKs installed here and wrong
  on a two-digit minor, since `29.10` sorts below `29.2`. NOT done blind: this machine has no
  `kiteplayer.libass.root`, so `androidChainsReady` is false, no `buildLibassJni` task is even
  registered, and the NDK path cannot be exercised here at all. It wants a machine with the
  Android chains, like the C pass at the top of Phase 3.
The two unpublished optional modules are STATED rather than published (2026-08-30), which was
the row's own second option and is the honest one for both. `kiteplayer-network` waits on the
streaming work in Phase 7 settling its API; `kiteplayer-libass` needs a native chain a consumer
cannot obtain, and it has no call sites outside its own tests yet. Both now have README rows
saying so in as many words. Revisit `kiteplayer-network` when Phase 7 lands.

Found while doing it: the README's `kiteplayer-subtitles` row still said "SubRip parsing and
nothing else. No cue is timed, laid out or drawn, and it is not connected to playback", which
had been wrong for a long time. Corrected in the same commit.
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
against Central, resolving `kiteffmpeg` 0.1.x. Release-gate
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
- [ ] **9.5 KC-BRIDGE** (L): 13 JNI defects left. **The lead one is FIXED (2026-08-30): the
  generation mask/compare mismatch.** The token carries 31 generation bits and the slot's counter
  was a full uint32_t, so once a slot passed 2^31 the encode truncated and the compare did not:
  that slot resolved nothing ever again and every token minted from it was dead on arrival. The
  counter is masked where it MOVES now, which keeps the two identical for the process lifetime and
  preserves the odd-is-live rule, because the dropped bit's weight is even. A new `test_handles.c`
  compiles `kc_handles.c` INTO itself so the counter can be wound by hand: two billion mint/close
  pairs is not a test. Seven cases, falsified by restoring the truncation. Because the table is the
  SHARED one, the web binding was carrying the same defect and is fixed by the same commit.

  **Two more fixed the same day, also in the shared table.** The close path RECURSED once per
  level of borrowing, and that depth is the caller's: nothing stops an application borrowing from
  a borrowed handle. It iterates to a fixed point now, with a constant frame, and the sweep is
  skipped entirely when no live handle records a parent, which is most closes; an ordinary close
  went from scanning the whole table to scanning nothing. And the token's own kind field was
  written at mint and never read, so two tokens differing only in those bits resolved the same
  object; resolve checks it now, which is what makes a token name one handle rather than one slot.

  Left, and every one of them needs a JVM this machine does not have in the build: no lease on
  resolved handles, a pending JNI exception during mint leaking the context, modified-UTF-8 on two
  paths with the correct decoder unused, callback exceptions collapsed to generic IO, an attached
  thread never detached, registration erasing every C signature. The table-level defects are done;
  what remains is the JNI layer proper, and `test_append.c` plus `test_handles.c` are the only two
  things in this build that can reach any of it.
- [ ] **9.6 KC-CFILTER** (M): `[out]` found by substring; sources published progressively
  then freed on failure leaving earlier entries dangling; four unchecked `av_strdup`; a plane
  index never bounded (and the test asserts the wrong answer; fix both); an eight-channel cap
  on upload only.
- [ ] **9.7 KC-DSL** (L). **Three of its items are DONE 2026-08-30, and the preset one was worse
  than the row said.** `preset`, `tune` and `crf` are x264-family options with no generic FFmpeg
  equivalent, and the tuning emitted them for every codec, so asking `h264_videotoolbox` for
  `preset=slow` set nothing: dropped at open, encode at the hardware defaults, one line in an
  unused-option report nobody reads. Worse, the recipes build LGPL FFmpeg with NO external
  encoders, so the shipped video encoders are `mpeg4`, `mjpeg`, `png` and the platform hardware
  ones, and not one of them has a preset, a tune or a crf. **A preset in this library was always a
  no-op.** It refuses now, naming the encoders that do accept each knob, and `profile` is left
  alone because it is an AVCodecContext field rather than an x264 option. CBR also carries
  `nal-hrd=cbr` on x264 now, which is what turns a capped pipe into conformant CBR; everywhere
  else the KDoc says plainly that the floor is a request the encoder cannot honour. And the
  unvalidated types are validated: blank profile or tune, and a zero or negative bitrate in either
  tuning, all refuse. Two of these changed the golden suite, which had been pinning the no-op.

  **Two items were already false and are dropped.** "The raw map applied after typed keys so it
  wins" cannot happen: `applyTo` has always refused a collision outright. And "no `@DslMarker`"
  does not apply to this shape at all, because the encoder tuning is a data class, not a builder
  scope; the FILTER DSL is the one with a builder, and it is a single receiver with nothing to
  leak into.

  Left: raw strings where a typed `SampleFormat` exists, and `CodecId` conflating bitstream
  identity with implementation. The second is the real one and it is NEEDS-DESIGN: `h264`,
  `libx264` and `h264_videotoolbox` are one type today, which is why a knob check has to read a
  name at all. `kiteffmpeg` ABI moved with the compile signature: klib and jvm both stay at
  1910 and 1529 lines, one declaration changed in place.
- [ ] **9.8 KC-BUILD** (S, was L): most of this row was written against a module that no longer
  exists. Each item was checked against the tree rather than against the prose.
  - DONE, filename-only validation of local trees. `verifyInstall` asked only whether six files
    named `lib*.a` exist. Every way a cross build goes wrong leaves that answer yes: an install
    prefix the make ignored, a directory left over from another target, a truncated copy. It now
    reads the first object out of each archive and refuses a tree whose machine is not the
    target's. Proven against real archives from three toolchains, in both the BSD layout macOS
    `ar` writes and the GNU layout `llvm-ar` writes.
  - STALE, `/usr/lib/include`. Absent from every `.kt`, `.kts` and `.sh` in the repo.
  - STALE, the URL cache key, the redirect-validating loop, and the two excluded plugin tests.
    All three belonged to the Gradle plugin that KC-EMBED deleted on 2026-08-22. Neither repo has
    a downloader now, so there is no URL to compare and no redirect to follow.
  - FALSE, unescaped `-D` values. Every `-D` is a list element handed to a process builder, so no
    shell ever sees it. The one built into a string is the literal `-D__USE_MINGW_ANSI_STDIO=1`.
  - FALSE, cache keys hashing one file. The only digest in the build hashes EVERY patch, and it
    writes an evidence file rather than a cache key.
  - FALSE, and removing it would be the bug: `dllexport` for a static build. Measured here with
    the konan mingw toolchain. With it, the object carries
    `-export:ffkmp_exported -exclude-symbols:ffkmp_unmarked`. Without it, `-fvisibility=hidden`
    emits `-exclude-symbols:` for BOTH, so the whole helper surface drops out of the export table.
    It is the COFF spelling of what `visibility("default")` does on the ELF side.
  - DONE, the half of target-truth drift that had teeth. `checkReleaseTargetMirror` compares
    `release-binaries.yml` to the `TargetTriple` enum in both directions, pairing `triple:` with
    `task:` so a job wired to a task that does not exist fails too. It is in the CI gate. This was
    the drift that could actually ship: a release is one prebuilt per triple per flavour, so a
    forgotten triple is a binary nobody builds and no gate reports.
  - OPEN, what is left of target truth: `kiteffmpeg/build.gradle.kts` builds its own target
    map per scope, and `scripts/linux-tests.sh` and `ci.yml` name targets in prose. None of those
    can ship a wrong artifact on their own, so they are worth a check only if one bites.
  - OPEN: `BuildFFmpegTask.kt` is 1,185 lines, down from 1,286 but still the largest file in
    `buildSrc`. Splitting it cannot be proven here without a full cross-build per target.
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
  `requireCompatibleFFmpeg` (15 call sites, nothing keeps them complete); (the C ABI version
  ratchet is DONE: both baselines now carry the version they were written at, `symbol-audit.sh`
  check 8 holds the stamps equal to the header, and a rewrite that changes records refuses
  unless the version rose. The row said the surface "claims 1.0 forever"; it actually claimed
  2.6, but the point stood, because nothing made 2.6 true.); the fuzz rule (every new string-parsing entry
  point gets a target; two public-reachable ones have none: `ffkmp_fmt_alloc_output2`'s
  format and the three raw `*_by_name` lookups); split `test_convert.c` contract vs baseline
  counts; (the C suite lists are DONE: both KiteFFmpeg scripts read `tests/*.c` since
  2026-08-30, so adding a suite is adding the file); the ring write asymmetry + two
  dead accessors + the misattached ring KDoc; the four early returns in the device callback
  that skip zeroing and the OutputIsSilence flag; teardown defence with a mock AudioUnit;
  the two forked C harnesses (decide source-of-truth vs vendored copy); the opaque-migration
  prototype (one family early, before more C work depends on the assumption);
  (`check-deleted-surface.sh` is DONE, and the row had it backwards: it did not FAIL on an
  empty set, it PASSED. Three greens over zero work when no name is marked deleted, and a
  note-not-a-failure when an allowlisted path no longer exists, which the KitePlayer
  `PLANNING.md` entry had been doing since the 2026-08-29 docs reset. Both fail now.);
  the coverage-guided fuzzing
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
- [ ] **9.17 A generated, exhaustive typed option surface for KiteFFmpeg.** Horizon, not
  scheduled. FFmpeg exposes every open-time option through `av_opt_next` on the format context
  and on each demuxer's and protocol's private class, and the wasm binding generator already
  shows this build can emit Kotlin from a table. Doing the same for options would give a typed
  knob for every option of the linked recipe: hundreds, differing per build, behind a new C
  entry point (the same enumeration gap as 3.10). Wrong size for a player, possibly right for
  the library one day. Revisit only after `DemuxOptions` (5.5, Task 10) has users asking for
  fields it lacks.

---

# PHASE 10: NEXT LEVEL (written expansions in `docs/next-level/`)

Sixty items, each with its files, contract, red-first tests, gate tier and commit line, grouped
into seven program files under `docs/next-level/`. `README.md` there carries the execution
protocol (one executor per repository, never two in one tree), the global order, and the six
owner decisions. Every item was grounded against the tree on the day it was written; when the
tree disagrees with an item, stop and report rather than improvise. Delete a row here in the
commit that lands it.

**Audio (`audio.md`)**
- [ ] A1 remainder: **the device half only.** The code landed 2026-09-02. `AudioConfig.volumeCeiling`
  (1 by default, up to 2) is what a consumer raises to allow a boost; above unity both rings fold
  each sample through a saturator that is identity below a 0.75 knee and never reaches full scale,
  so a boosted loud passage compresses instead of squaring off. At or below unity nothing is
  folded and every sample is bit for bit what it was. Proven by a new nine-case C suite, by two new
  differential-oracle cases holding the C and Kotlin rings to the same raw bits at four rates and
  three channel counts, and by seven engine tests; each arm falsified. The render audit still finds
  the device callback calling nothing but memcpy, memset and bzero, so the saturator is legal where
  it lives. **[owner]** DEVICE-DAY step 19 is what is left: nothing on a laptop can say whether a
  boost sounds right on a handset.
  A2 is DONE (2026-09-03). `AudioSink.platformSessionId` defaults to null so no other sink
  changed, the Android sink answers its `AudioTrack.audioSessionId` through the existing driver
  seam, and `PlayerSnapshot.audioSessionId` carries it to the application. Read live on every
  publish rather than cached, so it goes back to null when the device closes and an application
  knows to let its effect go. Four host tests and three engine tests, the snapshot copy falsified.
  A3 is DONE (2026-09-03). `AudioConfig.replayGain` is `Off` by default; `Track` and `Album` honour
  the container's own measurement, in both vocabularies (ReplayGain's decibels and Opus's R128
  fixed point, converted across the five decibels between their reference levels). The gain is
  clamped by the file's own peak against the volume ceiling, so a tag can never make a player clip.
  Applied on the way IN by a new per-channel `TrimStage`, which is where a per-track constant
  belongs and where balance will join it; the user's volume stays on the ring's read side.
  `PlayerSnapshot.appliedReplayGainDb` reports what was applied rather than what was asked for.
  25 tests, the pipeline application falsified.
  A4 is DONE (2026-09-03). `setBalance` from -1 to 1, an attenuation of the channel being turned
  away from and never a boost of the other, so a balanced track can never be louder than a centred
  one. It rides A3's per-channel stage and multiplies with the replay gain. Only the first two
  channels move. A live change is heard after the ring drains, at least 200 ms, which is stated on
  both public members: that is the cost of applying it on the way in, and the right trade for a
  setting nobody sweeps. 6 tests, the application falsified. With it the facade's list of absent
  features loses its last stale entry.
  A5 is DONE (2026-09-03). `setVideoEnabled(false)` discards video packets before the decoder and
  freezes the picture; audio and subtitles carry on because the container is still being read, and
  nothing is reopened. Resuming waits for a keyframe, and on a seekable source seeks precisely to
  where playback already is so the picture returns at the right frame. `PlayerConfig.videoEnabled`
  opens parked for an audio-only application. Parked packets are counted as nothing: a drop counter
  means the engine could not keep up, and this is a decision. 5 tests, the park falsified.
  S8's background policy is the consumer this was built for.
- [ ] A6 **ATTEMPTED 2026-09-03 AND REVERTED. Needs device evidence before it is worth doing.**
  The premise was that the flush behind a live speed change leaves a step in the waveform and
  therefore a click. Two things say otherwise. The fade was built and could not be shown to help:
  the virtual harness completes the flush and the refill between two device pumps, so the device
  never receives the silence the click would come from, and the test passed identically with the
  fade removed. And `AudioPlayback.speed` already documents the change as "one brief,
  gapless-sounding rebuffer, the same trade mpv makes", which is a judgement someone made with
  ears. Cost of the fix is a real 20 ms delay on every speed change. So: do not rebuild it from the
  plan. Play a file, change speed repeatedly, and listen first. If a click is real, the fade is the
  right fix and the code is in the reverted commit's diff.
  A7 is DONE (2026-09-03). `setSleepTimer` takes `After`, `At` or `EndOfItem`, fades the sound down
  over a configurable stretch, pauses, and puts the level back. The fade is the engine's own
  multiplier on the ring gain, so the published volume never moves and the next play is not silent,
  which is the bug every hand-written sleep timer has. `After` counts only while playback is
  advancing, so a player paused overnight does not sleep through its own timer, and the level is
  computed from the time remaining rather than stepped, so a late pass cannot strand it. 6 tests,
  the firing and the fade falsified separately. The pass-order ratchet moved with a stated reason.
- [ ] A8 remainder: **the file-measuring call.** The METER is DONE (2026-09-03):
  `LoudnessMeter` implements ITU-R BS.1770-4 in pure common Kotlin, both weighting filters derived
  from the sample rate rather than tabulated at 48 kHz, 400 ms blocks at 75 percent overlap, the
  absolute and relative gates, LFE excluded and surrounds weighted 1.41. Held to the standard's own
  reference values: a full-scale 997 Hz tone reads -3.01 LUFS and a 20 dB drop moves it 20 LU.
  11 tests; the rate derivation, the relative gate and the LFE exclusion each falsified.
  What is LEFT is `AudioAnalysis.measureLoudness(item)` in `kiteplayer-ffmpeg`: decode the primary
  audio stream, convert `Frame` bytes to interleaved floats per `SampleFormat`, feed the meter.
  That needs the sample-conversion helper K9's waveforms also want, so write it once for both. The
  oracle test compares against the host `ffmpeg` binary's `ebur128` filter, which is on PATH and
  does not wait for K3.
  A9 is DONE (2026-09-03). Ten ISO octave bands from 31 Hz to 16 kHz, the set every hardware
  equaliser has had since the 1970s, so a preset written for one means the same here. Each band is
  a cookbook peaking biquad whose gain at its own centre is exactly what was asked for, state per
  band per channel, direct form 1. Flat is skipped entirely rather than run with identity
  coefficients, so a player nobody has equalised pays nothing. A band whose centre reaches the
  Nyquist frequency is dropped rather than allowed to degenerate. 15 tests: the filters measured
  against real sines, the wiring through a session, and both falsified.

**Video (`video.md`)**
- [ ] V1 Backward frame step, by landing before the target. M
- [ ] V2 Refresh-rate awareness on every renderer; Android frame-rate matching. M
- [ ] V3 A real frame-presented event, exact on Metal and MediaCodec. M
- [ ] V4 Auto-deinterlace. M, after K2 and K3 publish
- [ ] V5 Gamma, on Metal and GL together. M
- [ ] V6 PNG and JPEG snapshots from a captured frame. S
- [ ] V7 Snapshots with the subtitles on them. M
- [ ] V8 Secure surface flag on the Android view. S, device proof
- [ ] V9 Picture-in-picture parameters on Android, and an honest capability answer. S, device proof
- [ ] V10 Picture in picture on iOS over a sample-buffer layer. L, NEEDS-DESIGN, owner device
- [ ] V11 Typed filter chains attach to a media item without the opt-in. XS

**Session and platform (`session.md`)**
- [ ] S1 Queue editing while it plays. M
- [ ] S2 Shuffle. S
- [ ] S3 Preload the next item; hand the audio device over instead of stopping it. L, NEEDS-DESIGN
- [ ] S4 Markers that fire on crossing; next and previous chapter. S
- [ ] S5 A memento of where playback was, and restore. M
- [ ] S6 Interruptions, audio focus and noisy routes under one policy. M, device proof
- [ ] S7 Media session and now playing, both platforms. M, device proof; [owner] module placement
- [ ] S8 Background policy. M, device proof
- [ ] S9 Two players in one process, proven. S
- [ ] S10 Accessibility semantics on the views. S, device proof

**Subtitles (`subtitles.md`)**
- [ ] T1 Style override with a background box on all three rasterisers. M
- [ ] T2 The active cues published to the app. S
- [ ] T3 A secondary subtitle track at the top. M
- [ ] T4 Subtitle sources through the byte doors, refusing typed. S, after the doors expansion
  T5 is DONE (2026-09-03). `CueIndex` answers the same questions `CueSelector` defines, with a
  binary search for the last cue that could have started and a backward walk that terminates on a
  prefix of maximum end times. Near the end of a seventy-thousand-cue ASS track the old rule
  visited every cue on every timing edge; this visits a handful. `CueSelector` is untouched and
  stays pure, which is what lets it be the oracle: 7,200 randomised lookups over overlapping,
  duplicate-start, zero-length and lecture-length cues compare the two. The index is a cache the
  session owns, extended on an append and rebuilt after a prune, a merge or a clear. 10 tests, the
  prefix maxima and the append guard falsified separately.
- [ ] T6 One overlay geometry law on every renderer. M, Android proof on device

**Observability (`observability.md`)**
- [ ] O1 Bytes read and bytes per second in the stats; bitrate after K2. S
- [ ] O2 Decode time and presentation lateness percentiles. S
- [ ] O3 A trace sink and the Chrome trace format. M
- [ ] O4 A structured log sink; URIs redacted by default. S

**KiteFFmpeg (`kiteffmpeg.md`, the sibling repository)**
- [ ] K1 swresample bound; the audio encoder converts; a resampler SPI in the player. L
- [ ] K2 Field order and container bitrate bound. S
- [ ] K3 The recipe compiles yadif, bwdif, loudnorm, ebur128, alimiter. S plus a rebake
- [ ] K4 The filter DSL says which filters the build lacks, before FFmpeg does. S
- [ ] K5 A public packet write on copy streams. S
- [ ] K6 Record the selected streams to a file as they play. M, after K5 publishes
- [ ] K7 A probe in one call; the player's inspect. S plus S
- [ ] K8 Thumbnails at positions. S
- [ ] K9 Waveforms, peaks and RMS per bucket. M

**Quality, CI and docs (`quality.md`)**
- [ ] Q1 Android device tests on an emulator in CI. M; [owner] allows the action
- [ ] Q2 The iOS sample builds, launches and shows a frame on the simulator. M
- [ ] Q3 The API reference on GitHub Pages. S; [owner] enables Pages
- [ ] Q4 Artifact sizes measured and ratcheted. M
- [ ] Q5 A perf gate on the three hot paths, no new dependency. S; [owner] may prefer kotlinx-benchmark
- [ ] Q6 The subtitle parsers survive two thousand mutations of every fixture. S
- [ ] Q7 Dependabot. XS
- [ ] Q8 Release notes from commits; a changelog that gates the publish. S
- [ ] Q9 File pickers in both samples. S, after the doors expansion
- [ ] Q10 remainder: **the release page, which is the owner's click.** The tree half is DONE
  (2026-09-03): the facade no longer lists chapters, the queue, frame stepping, external subtitles,
  filter chains and the option escape hatch as absent when all six exist; `MediaItem` stopped
  pointing at a `SubtitleSource.io` that was never built; the renderer SPI stopped saying the
  engine collects nothing from a feed it acts on four ways; the Android picture-in-picture flag
  says what it actually answers; the stale pipeline comment claiming the gain multiplies there
  went with it; the unread `SubtitleConfig.lookahead` field is deleted; and the three module
  counts agree at twelve. KiteFFmpeg was checked and needs nothing: its changelog entries for the
  deleted Gradle plugin are correct history and the file records the removal higher up.
  **[owner]** The v0.0.21 release page still advertises gapless `next`/`previous`. The tree pauses
  the audio device between items, so it is a gap until S3 lands. Corrected wording is in the
  commit body for 2026-09-03's doc sweep; editing a published release is a click only you have.
- [ ] Q11 The conformance matrix writes a report CI publishes. S

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
9b. A long one-line cue, once per wrap mode (the sample can set it). PASS: `Balanced` splits
    into near-equal lines, `None` fills the first line and leaves a short second, `Never`
    stays on one line and runs off both edges. Nothing here can test StaticLayout: the host
    JVM's android.jar is stubs, and there is no Robolectric.
9c. Same cue with a big coloured shadow (`shadowOffsetPx` 8, red). PASS: the shadow falls
    down-right, is not clipped at the bitmap edge, and the text sits exactly where it sat
    with the shadow off. Same reason as 9b: Canvas and Bitmap are stubs off-device.
9d. A two-span cue whose spans disagree on size and outline colour. PASS: both sizes show,
    both outline colours show, and the taller span sets the line height.
9e. A cue with `fontFamily` set to a font the device HAS (say "serif") and one it does not.
    PASS: the first changes the face, the second reads normally in the default face.

**Android phone, resume anchor (the S23 fix's device half):**
10. Pause 30+ seconds mid-playback, resume. PASS: no position jump, sync holds (the fix
    rejects pre-pause device timestamps; this confirms it on real silicon).
10b. Only after 5.5's Task 6 has landed: pick a file with the system picker and play it through
    `MediaIo.ofUri`; then play a bundled asset through `MediaIo.ofAsset`. PASS: both play, both
    seek, and the picked file still plays after a track switch (the reopen path).

**TV stick (only after the 32-bit ABI builds, Phase 8.2):**
11. Sideload, play 2 minutes. PASS: video + audio in sync, no crash on open.
12. Listen for clicks/drift. PASS: none (the ring's 32-bit atomics question).

**iPhone/iPad:**
13. Play, seek, background, return (as steps 3-4). PASS: picture returns every time.
14. Read KiteStats once at the end; write the numbers down.

**Android and iPhone, the volume boost (A1's device half):**
19. Build with `AudioConfig(volumeCeiling = 2f)`, play `sync1080p30.mp4` or any music file, and
    walk the volume from 1.0 to 2.0 and back while it plays. PASS: it gets audibly louder, no
    crackle or buzz on the loud passages, and no click at any step. FAIL: capture the material and
    the volume at which it broke. Then repeat once on a quiet recording, where the boost should be
    a clean lift with nothing folded.

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
18b. macOS, drag the window edge during playback for ten seconds, then again while paused.
    PASS: the picture follows the window with no tearing, no blank frame and no wrong aspect,
    and the paused picture redraws at the new size. The code fix is in and unit tested; what no
    test here can see is the picture itself.
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
