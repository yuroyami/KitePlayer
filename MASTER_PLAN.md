# MASTER_PLAN

**The one source of truth for what is LEFT to do, in both repositories** (KitePlayer and
`../KiteFFmpeg`; the sibling has no plan file by design). How to work, the traps, and the
decisions live in `GOTCHAS.md`. History lives in git; the old planning documents were distilled
into these two files on 2026-08-29 and deleted; `git log --diff-filter=D` finds them.

**The law of this file:** the same commit that changes the tree updates it. Finished work is
DELETED (the commit message is the record). Discovered work is added. Half-done work is
reduced in place with its remainder named. There is no archive file; done means gone.
Item IDs (SOL-, KC-, KP-, PAR-, X-, RQ-, and the letter-number names from `docs/next-level/`)
are stable handles kept because old commits reference them. They go in brackets after the plain
words, never first.

**How it is grouped: by what blocks the item, not by subject.** Four buckets:

- **OWNER-GATED**: cannot start without the owner's decision, click, account or publish. The
  executor prepares; the owner acts.
- **HARDWARE-GATED**: the code is in, or code is not the point; what is owed is a run on a
  device, an OS, an SDK or a toolchain this machine does not have. DEVICE-DAY lives here.
- **BIG-BITES**: L or XL, or anything that enters through its own written design before code.
  Started deliberately, never in a spare half-session.
- **DOABLES**: S or M, and everything needed is on this machine. Ordered; the top of each list
  is next.

An item sits under its FIRST blocker. An item whose code is doable but whose proof needs a
device sits in DOABLES with the device tail named, and the tail is listed again under
HARDWARE-GATED so a device day sweeps it up.

Sizes: S under half a session, M up to two, L two to five, XL a program entered through its
own written expansion. NEEDS-DESIGN means a public API or contract must be decided in its own
commit before execution; the executor makes that commit unless the item says [owner]. The
written expansions live in `docs/next-level/` (sixty items in seven program files; the README
there carries the execution protocol) and `docs/media-input-doors.md`.

---

## Where things stand (verified 2026-09-03)

The engine is real and plays: one Kotlin core (actor loop, workers, quiesce handshake, sync
law, seek machine), FFmpeg via KiteFFmpeg beneath it, audio with a windowed-sinc resampler,
WSOLA tempo and pitch preservation, a soft limiter, ReplayGain, balance, a ten-band equaliser,
a loudness meter and a sleep timer, subtitles with libass built for seven target families, a
queue that can be edited and shuffled while it plays, outputs on Android, Apple, desktop JVM,
Linux (container-proven), and a web canvas path. About 1,190 Kotlin test functions in
KitePlayer, about 310 in KiteFFmpeg, plus 23 C suites. Both repos have CI (KitePlayer 7 jobs
across macOS, Linux and Windows runners).

Maven Central serves `kiteffmpeg` 0.1.0 (published 2026-08-31), the old `kitecodec-core` line
up to 0.1.3 (which receives nothing further), and, since 2026-09-02, twelve `kiteplayer-*`
modules at 0.0.21 whose POM chain pins `kiteffmpeg` 0.1.0. What nobody has run yet is the proof
the release gate asks for: a machine that never saw this checkout building the README's install
lines green against Central. `kiteplayer-network` and `kiteplayer-libass` are unpublished on
purpose and the README says so.

Honest support today: macOS arm64 is the proving ground (experimental full playback). Android
and iOS play real media on real devices with named open items below. Desktop JVM plays the
whole conformance matrix on this Mac. Web decodes, draws and schedules audio in a browser;
nobody has heard it (worklet run pending) and no matrix has run there. Windows is a link
claim. Every green so far is one-machine evidence unless a device session below says
otherwise.

## The public release gate

A public claim about the pair stays blocked until every box is green.

| # | Box | Status |
|---|---|---|
| 1 | Invalid C pixel formats cannot abort | GREEN |
| 2 | Native FFI calls hold lifetime leases | GREEN |
| 3 | Sink close is a terminal atomic state machine | GREEN |
| 4 | Wasm MediaSource behaviour matches the shared suite | AMBER: the three fixes that had no test now have three, each proven to fail when reverted (2026-08-30). Full parity still waits on an FFmpeg wasm build, since the shared codec-contract suite decodes real media and the fake cannot stand in for that |
| 5 | Web input worker-backed or explicitly small | GREEN since KC-WEB-IO closed; streaming remainder rides the Worker (BIG-BITES) |
| 6 | Custom I/O failures preserve cause, close once | GREEN on JVM/Native; wasm close half landed with KC-WEB-IO |
| 7 | Player EOF waits for every lane | AMBER: subtitle lane still outside the EOF gate (a trailing cue can be cut) |
| 8 | Reopen paths get a fresh MediaIo | GREEN |
| 9 | Commands return truthful results | GREEN |
| 10 | Output capabilities leased; loss recovers or fails typed | AMBER: audio device loss only warns (route recovery, DOABLES) |
| 11 | Every published JVM OS/arch has a runtime artifact | AMBER: `kiteffmpeg-jvm` 0.1.0 and the `kiteplayer-*-jvm` 0.0.21 artifacts exist on Central; which OS/arch runtimes a consumer actually receives has not been checked from a clean machine |
| 12 | Android AAR built on CI, device-tested per ABI | RED: the AAR is on Central, untested on a device per ABI (DEVICE-DAY) |
| 13 | Android Native attaches the JavaVM, MediaCodec device-tested | RED (DEVICE-DAY) |
| 14 | Wasm runtime in a versioned package, browser-tested | RED (the web items in DOABLES and BIG-BITES) |
| 15 | Licence/flags/capabilities agree | GREEN |
| 16 | Prebuilts exist; clean consumer install works (KiteFFmpeg) | GREEN |
| 17 | Every KitePlayer variant resolves one KiteFFmpeg variant | AMBER: every 0.0.21 module pins `kiteffmpeg` 0.1.0 through `kiteplayer-ffmpeg`'s POM; the clean-machine consumer build that proves resolution is unrun (DOABLES) |
| 18 | Player modules and web runtime release together | RED: the player modules shipped 2026-09-02 without the web runtime (box 14) |
| 19 | Licence/SBOM/provenance per bundled native dep | RED: `publish.yml` has no such step (DOABLES) |
| 20 | RC tests run before publication; publication atomic | RED: `publish.yml` runs `checkPublicationReadiness` and stages a USER_MANAGED deployment a human releases; no test suite runs in that workflow (DOABLES) |

---

# OWNER-GATED (executor prepares, owner acts)

## Decisions waiting

Recommended answers are stated. The executor does not guess. Each names what it unblocks.

- [ ] **The media input doors, decisions A to E.** Expansion: `docs/media-input-doors.md`,
  section 8. Refuse two keys rather than allowlist (A); a new `kiteplayer-io` module (B); no
  kotlinx-io (C); change `MediaItem.io`'s type now (D); a raw key colliding with a typed field
  refuses at open (E). The GOTCHAS allowlist sentence moves with A. Today a consumer can hand the
  player a path, a raw descriptor number, or a hand-written byte reader, and nothing else;
  Synkplay wrote its own `content://` resolver because of it. Found while writing the expansion
  and fixed by Task 8: **per-item `headers` never reach https.** `MediaIoResolver.resolve`
  receives only the URI, so the Ktor resolver sends the headers it was constructed with and the
  item's go to FFmpeg's http funnel, which never opens for that item; they come back as an
  unused-option warning. Unblocks the eleven tasks below, plus T4, Q9 and K8's byte route.
  The tasks, in order, one commit each, S unless marked. They become DOABLES the day A to E are
  answered:
  - Task 1: `MediaIoFactory` as a type; `openOptions` refuses `fflags=fastseek` and `usetoc`.
  - Task 2: `MediaIo.ofBytes`, `MediaItem.from`, and the contract test every door passes.
  - Task 3: `PipedMediaIo`, a bounded pipe for sources that push.
  - Task 4 (M): the `kiteplayer-io` module; JVM `ofFile`, `ofPath`, `ofChannel`, `ofStream`.
  - Task 5 (M): posix `ofPath` on Apple and Linux; `ofUrl(NSURL)` with the security scope
    released on close.
  - Task 6 (M): Android `ofUri` and `ofAsset`, the descriptor opened per session and read
    positionally, which retires the shared-offset defect by construction. Device half is
    DEVICE-DAY step 10b.
  - Task 7: `DemuxPolicy`, `ProbeDepth`, `CorruptPackets`, and the `mediaItem { }` builder.
  - Task 8: `MediaIoResolver.resolve(item)`; the Ktor resolver merges item headers over its own.
  - Task 9 (M): the FFmpeg backend translates `DemuxPolicy`; a raw key shadowing a typed field
    refuses typed at open; `PreOpenOptionsTest` moves with it.
  - Task 10 (M, KiteFFmpeg): `DemuxOptions`, the sibling `DecoderOptions` never had. Nothing
    here waits on its publish.
  - Task 11: README "Feeding it media", two cookbook paragraphs; then delete this item.
- [ ] **Where the media session lives** [S7]: `kiteplayer-mobile` (recommended: it already
  depends on everything and is the "start here" module) or a new `kiteplayer-session` module.
  Unblocks S7, media session and now playing on both platforms (M, device proof at the end,
  DEVICE-DAY step 25). Spec: `docs/next-level/session.md`.
- [ ] **Android emulator in CI** [Q1]: allow `reactivecircus/android-emulator-runner`. No
  alternative; without it, Android device tests stay on DEVICE-DAY. Unblocks Q1 (M). Spec:
  `docs/next-level/quality.md`.
- [ ] **The closed-frame exception on the web** (S once answered). JVM, Android and native throw
  `IllegalStateException("Frame is closed, its native buffers are gone")`; the web throws
  `FFmpegException(Internal("this frame is closed"))` from `alive()` in `Frame.wasmJs.kt`, which
  every closed-frame read on that target goes through. `FFmpegException` extends
  `RuntimeException`, not `IllegalStateException`, so a caller cannot catch both with one clause.
  Two of the three agree, the shared contract suite already asserts `IllegalStateException`, and
  commonMain documents nothing either way. Making the web agree is a five-line change; the only
  `catch (FFmpegException)` anywhere near it is the identity gate in `FFmpegRuntimeCheck.kt`, not
  a frame path. It waits because it changes which exception a published public method throws.
- [ ] **The East Asian subtitle tables** (decision S, work M). Shift-JIS, Big5, GBK and EUC-KR.
  Detection already recognises them from byte-pair shape and names them in
  `SubtitleCharsetGuessed`, so a Japanese file is told what it is rather than called
  undetectable; it just cannot decode one yet. When it lands it lands as pure-Kotlin tables,
  about 155 KB together for all four, NOT as platform actuals: Kotlin/Native decodes UTF-8 and
  nothing else, its actual here is one source set spanning `androidNativeArm32` (no iconv below
  API 28) through `mingwX64` (no iconv at all), and the platforms that DO have decoders disagree
  with each other (Windows and Java differ on cp932 and cp950). The 155 KB in a common artifact
  is the decision.
- [ ] **Register codes in the dated notes** [KC-DOCTRUTH remainder]. 28 codes sit in `kiteplayer-sample-web/MEASUREMENTS.md`
  and `docs/spikes/2026-08-17-web-spike.md`, and twelve more key a lookup table in KiteFFmpeg's
  `native/kitecodec-c/README.md`. They are records of what was measured on a day, and rewriting
  them may be falsifying history rather than clearing jargon. Say "leave" or "sweep". If sweep:
  the two-pass sweeper is in the 2026-08-30 sweep commit; it is only safe where a non-space,
  non-comment-marker character precedes the parenthetical (the naive regex left `//.` and `//:`
  on thirty lines); `vendor/` and `native-libs/` are third-party sources that match by
  coincidence and must never be swept; and `X-Ignored`, `X-Raw`, `X-Session` are HTTP headers,
  so require digits after `X-`.
- [ ] **The web tier size.** The gzipped web tier is 1.22 MiB against the original 1.00 MiB
  spike budget. Accept it, or name the budget the web deployment item [X-13] ratchets to.
- [ ] **Native desktop output targets** [PAR-5]: native linux and mingw output targets declare no
  source sets. Recommended close: record as a decision (native desktop targets are engine-only;
  consumers bring output through the SPI; the desktop story is the JVM). Say yes or move it.
- [ ] **Scope calls, each unscheduled until you say otherwise:** audio passthrough and offload
  (needs hardware evidence and your scope) [KP-AUDIO-PASSTHROUGH]; mono and stereo to 5.1 upmix
  (taste) [KP-AUDIO-UPMIX]; x86-32 Android, the day Synkplay ships it; Anime4K go or no-go, after
  RQ-4 lands, on device numbers.
- [ ] **Sign-offs that move a committed baseline:** the API-dump move that fixes the `api` leak of
  a KiteFFmpeg `Frame` (part of the module seams item, BIG-BITES); and every toolchain,
  dependency, plugin or GitHub Action addition an executor asks for (GOTCHAS rule 10).

## Clicks and publishes

- [ ] Every `git push`, both repositories.
- [ ] The next KiteFFmpeg publish, whenever it carries new C entry points (K2, K3, K5, K1 as they
  land). The pin in `gradle/libs.versions.toml` moves with it, and the "after the publish"
  DOABLES open then.
- [ ] The v0.0.21 release page still advertises gapless `next` and `previous`. The tree pauses
  the audio device between items, so it is a gap until preload lands [S3]. Corrected wording is
  in the body of commit `991ea99`. Editing a published release is a click only you have.
  [Q10 remainder]
- [ ] GitHub Pages for the API reference: Settings, Pages, Source "GitHub Actions". The workflow
  (`docs.yml`) exists and builds the site; its deploy job fails until this click. [Q3]
- [ ] Blocked-upstream rechecks, each one command in GOTCHAS: an AGP bump means re-measuring the
  one Gradle 10 deprecation; a Kotlin bump means re-measuring whether `abiValidation` grew an
  Android dump variant.
- [ ] Synkplay's next KitePlayer bump: move its pin to `kiteffmpeg` (its adapter needs no change
  beyond imports). If Synkplay has a desktop build: desktop `Auto` resolves to the native view
  now, so Compose controls drawn over the video stop receiving clicks; move them into an owned
  overlay window or pass `KiteRenderPath.ComposeCanvas` explicitly. Mobile is unaffected. After
  doors Task 6: replace `KiteMediaResolver`'s descriptor dance with `MediaIo.ofUri`, and opt in
  to `KitePlayerLowLevelApi` only if any `openOptions` use remains.

## Deferred by your decision: FFmpeg 9.x (2026-08-29)

Not scheduled, not refused. The research, so nobody repeats it.

**What 9.0 is.** Released 2026, 9.0.1 on 2026-08-12, codename Lei, over 2,200 commits. It bumps
the major of all seven libraries (libavutil 61, libavcodec 63, libavformat 63, libavdevice 63,
libavfilter 12, libswscale 10, libswresample 7), so it is an ABI break across the board.

**What we would gain, and it is one thing:** swscale was rewritten with x86 SIMD, AArch64 NEON
and Vulkan backends. That is the CPU conversion path, hot for the web tier, the desktop JVM and
AWT renderers, Android pre-31 KiteVideo and the Apple CPU fallbacks. A hypothesis to MEASURE,
never a claim to inherit: a rewrite can move output as easily as speed. Secondary: Dolby Vision
Profile 7, SMPTE 2094-50 HDR metadata. Irrelevant to us: Vulkan filters, AMF and CUDA, the ONNX
DNN backend, animated WebP.

**Why it is cheaper than an ABI break sounds, measured against our tree on 2026-08-29.** Zero
exposure to everything 9.0 removed (CELT, Sonic, OpenMAX, NPP filters, packed YUV v308/v408/v410,
old NVENC options): not one reference in the 11 C sources or the build. The C layer is already on
modern APIs: `ch_layout` across five files, send/receive decode, no `av_init_packet`, no
`avcodec_close`, no `avcodec_decode_*`. `avfilter_graph_parse_ptr`, which `helpers_filter.c`
depends on, still exists in release/9.0 and is NOT deprecated (checked in the header itself; blog
claims of a dictionary-based replacement are false). TLS-verified-by-default does not reach us:
our profiles build no TLS and pin protocols to file/fd/pipe/data/http/tcp, with TLS terminated
by Ktor and the OS.

**What it would still cost:** a fixture regeneration, a host ffmpeg series move, and
re-verification of every colour golden against a rewritten swscale.

**Why deferred rather than done.** 9.0.1 is weeks old and the ecosystem is still migrating seven
new sonames. Our evidence system is calibrated on the 8.x line, and this project's measured
failure mode is claims that stopped being true, which is exactly what a simultaneous churn of
goldens, baselines and fixtures invites.

**Revisit when all three hold:** the desktop CPU conversion path can measure the swscale rewrite
instead of assuming it; the gate is green so the bump is one variable and not two; and 9.x has
had a few more point releases. 9.1 and 8.2 were both already in development on 2026-08-29, so
check what the current lines are rather than trusting this paragraph.

---

# HARDWARE-GATED (a run this machine cannot do)

Needs: an Android phone (Main10-capable covers more), an Android TV stick (Fire-TV class,
32-bit), an iPhone or iPad, and for the desktop steps a Windows machine and a Linux desktop.
KiteStats prints the numbers. Build the sample per device first; a failed build is itself the
first finding. Report per step: pass, fail, or not run, plus one line of what you saw;
screenshots for 7 to 9.

## DEVICE-DAY: the hardware run sheet (one afternoon, devices out once)

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
9f. Set a style override with an opaque background box (`setSubtitleStyle`, red box, padding 6)
    over a plain SRT cue. PASS: a box behind every line, padded past the glyphs, text unmoved
    and still its own colour; clearing the override removes the box. Same reason as 9b: Canvas
    is stubs off-device, so the Android box has compile-only proof until this step.
    Steps 9b to 9e are the last open part of the subtitle styling row [SOL-S7]; delete them
    once run.

**Android phone, resume anchor [S23 device half]:**
10. Pause 30+ seconds mid-playback, resume. PASS: no position jump, sync holds (the fix
    rejects pre-pause device timestamps; this confirms it on real silicon).
10b. Only after doors Task 6 has landed: pick a file with the system picker and play it through
    `MediaIo.ofUri`; then play a bundled asset through `MediaIo.ofAsset`. PASS: both play, both
    seek, and the picked file still plays after a track switch (the reopen path).
10c. Change the volume while a file plays. The lag between the change and hearing it was
    measured at 174 ms on a 171 ms ring before the 2026-08-31 fix and tracked the ring depth
    exactly; Android hurt most because its depth is the AudioTrack buffer times eight rather
    than the 200 ms floor. The mechanism is proven on the Kotlin ring Android uses, with a
    differential oracle holding the C ring to the same samples. PASS: the change is heard at
    once. What no laptop can answer is what an AudioTrack buffer actually is on a given
    handset. Synkplay only receives this when its pin moves. [SOL-P8]

**TV stick (only after the 32-bit ABI builds, DOABLES):**
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

**Desktop:**
18. Run the desktop sample's native-view demo on a Windows machine and a Linux desktop:
    z-order (controls above video) and the jank-decoupling toggle, same pass rules as the Mac
    run. Compose documents blending as Metal, DirectX and offscreen only, so Linux is expected
    to fall back to the Compose canvas through `onEffectivePath`; that expectation is
    unverified. Also the full format matrix once on real Windows (it has only ever been a link
    claim).
18b. macOS, drag the window edge during playback for ten seconds, then again while paused.
    PASS: the picture follows the window with no tearing, no blank frame and no wrong aspect,
    and the paused picture redraws at the new size. The resize fix is in and unit tested; what
    no test here can see is the picture itself.

**Android and iPhone, the volume boost [A1 device half]:**
19. Build with `AudioConfig(volumeCeiling = 2f)`, play `sync1080p30.mp4` or any music file, and
    walk the volume from 1.0 to 2.0 and back while it plays. PASS: it gets audibly louder, no
    crackle or buzz on the loud passages, and no click at any step. FAIL: capture the material
    and the volume at which it broke. Then repeat once on a quiet recording, where the boost
    should be a clean lift with nothing folded.

**Any device, the speed-change click [A6]:**
19c. Play `sync1080p30.mp4` on a 120 Hz phone; note dropped and repeated frames from KiteStats,
    then the same clip on the Mac. The refresh-rate plumbing landed 2026-09-03 (the view feeds
    the display's rate, renderers answer their screens, VsyncChanged reaches the running
    schedule) and Android additionally asks the OS for frame-rate matching from the measured
    frame cadence (API 30+); what no laptop can see is whether a 120 Hz panel actually drops to
    30/60 and whether the numbers improve. [V2, step 20 in the specs]
19b. Play a file and change speed repeatedly. Listen. The fade was built on 2026-09-03 and
    reverted: the virtual harness completes the flush and the refill between two device pumps,
    so the device never receives the silence a click would come from, and the test passed
    identically with the fade removed. `AudioPlayback.speed` already documents the change as
    one brief gapless-sounding rebuffer, the same trade mpv makes. The fix costs a real 20 ms on
    every speed change. If a click is real, the fade is the right fix and the code is in the
    reverted commit's diff. If not, delete this step.

**Android phone, the secure surface [V8]:**
21. Set `KitePlayerView.secure = true` in the sample and take a screenshot while a file plays.
    PASS: the video area is black in the screenshot and normal on the glass. Then set it back to
    false and screenshot again. PASS: the picture is in the screenshot.

**Android phone, picture in picture [V9]:**
22. From the sample, call `KitePlayerView.pictureInPictureParams()` and enter picture in picture
    while a file plays, once upright and once on `rotated90ccw.mp4`. PASS: the window has the
    picture's aspect both times and the transition starts from the video area. On API 31 or
    later, background the app while playing. PASS: it enters the window by itself.

Steps 20, 23 to 27 are added by the items that need them as they land (V2, V10, S6, S7, S8, S10,
and T1's step 9f), each spec naming its own step.

## Device halves and runs owed beyond the sheet

- [ ] **The Android GL tone-map tier** [KP-TONEMAP-WARN remainder] (S). Metal, the AWT desktop
  renderer, AppKit and UIKit all raise `RendererEvent.ToneMapEngaged`, so
  `PlaybackWarning.HdrToneMapped` fires there. The Android GL tier's tone mapping is
  MediaCodec's, requested through the output contract (`COLOR_TRANSFER_SDR_VIDEO`) and confirmed
  by reading the codec's output colour back, so engagement is "the codec accepted the request and
  its output really is BT.709", not a converter's answer. That is a different signal from the
  other four and it can only be read on a device. Write it on the day the device is out.
- [ ] **Hardware AV1 positive proof and the 4K verdict** [PAR-6 tail]: one AV1 clip on an A17 Pro
  or M3 or newer, plus the 4K clip that settles the 4K exit criterion. This Mac proves the
  refusal path only (the route itself is in DOABLES).
- [ ] **Apple render-ladder numbers** [KP-RQ]: the Metal halves of dither, deband and kernel have
  no device measurement; defaults stay off until they do. Adreno numbers are in GOTCHAS.
- [ ] **Real surround hardware** [SOL-P8]: the desktop sink asks the mixer whether it takes the
  source's channel count and folds to stereo only when it does not, proven against a scripted
  mixer. Whether a real `AudioSystem.isLineSupported` says yes to 5.1 on a machine with a
  surround device is not something this laptop can answer; its own mixers list mono and stereo
  only.
- [ ] **The NDK pinned by exact version** [KP-B1..B13] (S). It is chosen by STRING SORT in four places:
  three KiteFFmpeg build tasks (`BuildFFmpegTask`, `BuildDav1dTask`, `BuildAssChainTask`) and
  `kiteplayer-libass/build.gradle.kts`. Right for the three NDKs installed here, wrong on a
  two-digit minor, since `29.10` sorts below `29.2`. Not done blind: this machine has no
  `kiteplayer.libass.root`, so `androidChainsReady` is false and no `buildLibassJni` task is even
  registered. Wants a machine with the Android chains.
- [ ] **`watchosSimulatorArm64` and `tvosSimulatorArm64` tests** [F-COV1]: attempted 2026-08-30
  and refused with "Xcode does not support simulator tests for watchos_simulator_arm64. Check
  that requested SDK is installed". `xcrun simctl list runtimes` offers iOS only here, so both
  are a missing SDK rather than a broken target.
- [ ] **Compile-only targets** [F-COV1]: `iosArm64`, `androidNative*`, `macosX64`, `iosX64`,
  `linuxArm64`, each needs hardware or an emulator nobody has wired. Compilation is not support
  and the README says so. Android device tests ride DEVICE-DAY, or Q1 if allowed.

---

# BIG-BITES (L, XL, or design first)

- [ ] **swresample bound; the audio encoder converts; a resampler SPI in the player** [K1, closes
  KC-AENC] (L, Tier 2). A caller holding fltp when the encoder wants s16 is told today to route
  through `FilterGraph.buildAudio` rather than being converted on the way in (a mismatch used to
  segfault; it refuses typed now on both backends). Doing it properly needs swresample through
  the C ABI, which nothing reaches (linked and version-reported, no `swr_*` entry point). New C
  surface across 12 targets plus JNI plus the wasm binding mirror. Spec:
  `docs/next-level/kiteffmpeg.md`. Its player half waits on the publish.
- [ ] **Encode specs carry colour, HDR and exact layout** [KC-SPEC propagation half] (L).
  `MediaSink` specs still carry only codec, size, pixel format, rate, bitrate and keyframe
  interval, so nothing about the SOURCE reaches the output: an HDR encode is flattened, 5.1(side)
  becomes 5.1(back), pixel aspect is dropped. Add typed colour (primaries, transfer, matrix,
  range), mastering metadata, SAR and exact layout to the specs, and propagate from `StreamInfo`
  when the caller did not override. RED: a scripted HDR source carries its colour into the sink
  untouched. Both backends, wasm records its bound, apiDump. Cheap now: `ColorInfo` carries
  per-field provenance, so propagation copies what the source DECLARED and leaves what this
  library guessed.
- [ ] **The filter trio** [KC-FILTER-DIVERGE, KC-FILTER-LOCK, KC-FILTER-SESSION] (L,
  NEEDS-DESIGN). JVM builds graphs eagerly from codecpar, native lazily from the first frame with
  a per-frame `List<Any>` key that omits SAR (which the builder uses) and audio layout, so
  midstream changes behave differently per backend. User callbacks run under the graph lock on
  both backends; native's residual hazard is a callback blocking on another thread needing the op
  lock, and a comment argues the opposite of what the code does. `process` is single-use and the
  type does not say so; `feedInput` returns Unit so multi-input graphs cannot answer
  NeedsInput(pad) and bound retries at two with an untyped error. Design commit: orchestration
  moves to common Kotlin (one build law, eager, keyed including SAR and layout, no per-frame
  allocation); callbacks invoked outside the lock (snapshot under lock, call after release;
  remaining reentrancy documented); one-shot `process` expressed in the type; `feedInput` returns
  a typed result. Then red-first per defect.
- [ ] **The JNI layer's remaining defects** [KC-BRIDGE] (L). The shared handle table is done
  (generation mask, close recursion, kind check). Left, and every one needs a JVM this build's C
  suites cannot reach: no lease on resolved handles; a pending JNI exception during mint leaking
  the context; modified-UTF-8 on two paths with the correct decoder unused; callback exceptions
  collapsed to generic IO; an attached thread never detached; registration erasing every C
  signature. `test_append.c` and `test_handles.c` are the only two things in the C build that
  reach any of it, so the proof has to come from the JVM side.
- [ ] **The C reduction charter** [SOL-C1, SOL-C3] (L). Replace one-line helper C with direct
  cinterop (packet, codecpar, stream, error, trivial frame and codec, most of format and
  playback); 198 exported symbols is the measured baseline and it ratchets DOWN per slice. The
  args-composition C moves to Kotlin with it (it never truncated; the C is just redundant). The
  KEEP list is in GOTCHAS.
- [ ] **KiteFFmpeg performance** [KC-PERF] (XL). The ten hot paths: JVM upload chain of three or
  more copies, output path allocating native then copying to Java, no common zero-copy lease,
  handle-table scaling, per-frame graph keys, a thread-local scaler with no session. Re-measure
  first: the per-byte web half is already dead.
- [ ] **Module seams** [SEAM] (L). Mismatched target graphs across modules; an `api` leak of a
  KiteFFmpeg `Frame` pinned in both committed ABI dumps (the dump move is the owner's sign-off);
  non-transactional source close; 467 hand-written metadata mappings with one test (add a
  generated cross-check); four modules repeating one config block with one missing a flag; two
  version catalogs already drifted.
- [ ] **The bitmap subtitle bridge** (L, NEEDS-DESIGN). `ff_pgssub_decoder`, `ff_dvbsub_decoder`,
  `ff_dvdsub_decoder`, `ff_xsub_decoder` and eight text-format decoders (SAMI, JACOsub, MicroDVD,
  MPL2, RealText, PJS, VPlayer, STL) already ship in the archives, unreachable. The work is a
  KiteFFmpeg decode-subtitle surface (packet in, positioned rects out) plus a routing branch in
  the player's subtitle factory onto the EXISTING `OverlayImage` path. KiteFFmpeg design commit,
  then RED: a PGS fixture decodes to positioned bitmaps on jvm and native; a PGS MKV shows
  subtitles in the desktop sample (manual evidence, recorded); republish, adopt. CEA-608/708 ride
  VIDEO frame side data and stay a named remainder (needs the per-frame side-data channel, its
  own KiteFFmpeg API act; also the one structural blocker under timed metadata).
- [ ] **libass wired and finished** (L). Built and device-proven on seven target families; zero
  call sites outside its own tests. In order, reduce bullet by bullet:
  - Wire: `PlaybackCore` routes ASS cues through the libass renderer when the optional module is
    present (the rasterizer-selection seam); one typeset-heavy fixture renders via libass on
    macosArm64Test.
  - JVM bridge: host .dylib/.so/.dll packaging and resource loading (shared infra with the GPU
    presenter's future shim; build once, document once).
  - The animated hook: a per-frame render callback for animated typesetting (rendering is
    snapshot-per-call today; libass carries the times).
  - Exit: a named typesetting-heavy corpus renders pixel-comparable to mpv (similarity threshold
    documented). The wasm/emscripten build is last and may split into its own item if
    emscripten fights; say so rather than sink time.
- [ ] **Viewport subtitles** [KP-P1-15] (M, NEEDS-DESIGN, own expansion first). Safe-area insets,
  aspect-mismatch placement, scaling policy on resize; cue geometry versus surface geometry
  mismatches fold in [S41, S44, S50]. Overlaps T6 (one overlay geometry law on every renderer, DOABLES); write
  the expansion so the two land as one law, then execute red-first on the rasterizer geometry.
- [ ] **The Worker on the web** [X-08] (L). Nothing runs the player in a Worker; the blocking IO
  design [X-06] is only legal off the main thread; every user-facing item needs a main-thread
  facade that does not block. Worker bootstrap, facade, message protocol, lifecycle. The
  zombie-coroutine caveat on `BlockingMediaIo.wasmJs` retires by construction inside the Worker.
  Exit test: player driven through the facade while a rAF heartbeat on the main thread never
  gaps beyond a stated bound (headless browser).
- [ ] **The IO pass** (L, NEEDS-DESIGN). One expansion covering: `open()` itself is not
  interruptible (no handle exists before it returns), a mid-playback stall waits for the user
  instead of self-aborting at a policy bound, and `Transcoder.transcode` never leaves the calling
  dispatcher [KC-CANCEL]; the three network-side twins (install the interrupt callback on the URL
  path, bound every network wait with timeouts, harden the undocumented URL path, which must not
  be advertised until then); the `fd:` protocol path for anyone still on it after `MediaIo.ofUri`
  lands, and its device proof [PAR-7]; the unused bounded-seek floor (`PacketReader.seek` takes
  `notEarlierThan` and the player never passes it; give it falsifiable behaviour with the
  retry-on-refusal policy); the HLS child-context interrupt trap from GOTCHAS throughout. Design
  commit, then red-first: open interrupted mid-`find_stream_info` returns typed within the
  deadline; a stall self-aborts at the configured bound; an fd item reopened twice never moves
  the caller's descriptor offset (Android host and native).
- [ ] **Streaming resilience** [KP-NET] (XL, own expansion at entry). Today: unvalidated 206 with
  no Content-Range, ETag or If-Range anywhere; a seek that validates nothing and a class with no
  closed flag; no timeout, retry, backoff or reconnect; DASH picks one representation by
  bandwidth, drops audio, refuses live and multi-period, cannot seek; an MPD repeat count taken
  verbatim from XML; `kiteplayer-network` unpublished while the twelve that apply the publish
  plugin do. Scope at entry: response validation, resilience, real DASH ABR (audio, live,
  multi-period, seek), HLS (prerequisite: the IO pass's interrupt seam), bounded prefetch plus
  progressive cache plus resume, publication of `kiteplayer-network`. Rides with it: live media
  cannot switch VIDEO tracks [SOL-P9]; audio and subtitle switches are live in-graph (proven on
  unseekable sources), video still rebuilds the session by design. Exit: the first-afternoon
  script's network legs pass on devices.
- [ ] **Windows and Linux hardware decode** [PAR-WIN-HW, PAR-2] (L, with desktop video output).
  Windows carries 18 D3D11VA/DXVA2 hwaccels compiled and not plumbed; Linux compiles zero (VAAPI
  is the candidate). Needs a hardware device context and a frame download path in KiteFFmpeg;
  proof needs owner glass.
- [ ] **Renderer capability negotiation** [SOL-API7] (L, NEEDS-DESIGN). Unsupported frame and
  renderer pairings refuse TYPED at first frame today; a sealed hardware-surface model plus
  attach-time negotiation moves the refusal to bind time. Coordinates with the renderer-event
  surface the tone-map warning uses.
- [ ] **The render-quality ladder** [KP-RQ]. RQ-4 linear-light scaling (two passes, RGBA16F
  intermediate at source size, then the kernel draw; both shader bodies; the rung that changes
  pipeline shape, so golden-first), then RQ-5 Anime4K (curated built-in port, two tiers, own
  program, owner go or no-go), RQ-6 HDR passthrough (horizon: skip the tone map on capable Apple
  displays, float16 plus extended-range layer). Laws: disabled is bit-exact, nothing defaults on
  without a device measurement, one knob surface (`RenderQuality` on `PlayerConfig`), every rung
  lands on Metal and Android GL together or opens its skip. Apple numbers are owed
  (HARDWARE-GATED).
- [ ] **Preload the next item; hand the audio device over instead of stopping it** [S3] (L,
  NEEDS-DESIGN, Tier 2). Real gapless. Spec: `docs/next-level/session.md`. The release page's
  gapless claim (OWNER-GATED) becomes true here.
- [ ] **Picture in picture on iOS over a sample-buffer layer** [V10] (L, NEEDS-DESIGN). Ships the
  layer path and stops at the device proof, which is the owner's (DEVICE-DAY step 23). Spec:
  `docs/next-level/video.md`.
- [ ] **Coverage-guided fuzzing** (program). Harness, corpus and replay exist; true fuzzing runs
  in Linux CI; the demuxer and decoder byte paths are the security gap. The rule that every new
  string-parsing entry point gets a target stands; the two public-reachable ones without targets
  are in DOABLES.

## Horizon (not scheduled; each enters through its own expansion when wanted)

- Java and Swift adaptation of the API [KP-INTEROP-SURFACE].
- A clickable overlay helper for the desktop native view. The measured answer for controls over
  video is a borderless window owned by the video window; each consumer writes that themselves
  today. Wait for a second consumer to need it before turning it into API. Method and all seven
  arrangements: `kiteplayer-sample-desktop/INTEROP-SPIKE.md`.
- A GPU presenter for the desktop native view [KP-DESK-NV-GPU]: a JAWT presenter owning a
  CAMetalLayer on macOS, D3D on Windows, EGL on Linux, replacing the CPU blit. Nothing measured
  demands it. It does NOT fix the input constraint, which belongs to native view ordering rather
  than to how the surface is painted, and Compose documents that DirectX blending cannot overlay
  another DirectX component, which constrains the Windows half.
- A generated, exhaustive typed option surface for KiteFFmpeg. FFmpeg exposes every open-time
  option through `av_opt_next` on the format context and on each demuxer's and protocol's private
  class, and the wasm binding generator already shows this build can emit Kotlin from a table.
  Hundreds of knobs, differing per build, behind a new C entry point (the same enumeration gap
  as codec enumeration). Wrong size for a player, possibly right for the library one day. Revisit
  only after `DemuxOptions` (doors Task 10) has users asking for fields it lacks.
- `BuildFFmpegTask.kt` at 1,185 lines, the largest file in KiteFFmpeg's `buildSrc` [KC-BUILD]. Splitting it
  cannot be proven here without a full cross-build per target. The target-map prose drift in
  `kiteffmpeg/build.gradle.kts`, `scripts/linux-tests.sh` and `ci.yml` cannot ship a wrong
  artifact on its own and is worth a check only if one bites.

---

# DOABLES (S or M, everything on this machine)

## Next up, in order

The rest of the next-level lane. Each has a full spec block in `docs/next-level/` with files,
contract, red-first tests, gate tier and commit line. When the tree disagrees with a spec, stop
and report rather than improvise. Delete the row here in the commit that lands it.

- [ ] **V3 remainder: the EXACT halves.** The event exists end to end (SPI, engine gate,
  latency against the schedule's remembered target, best-effort emits after the AWT, AppKit and
  Android surface blits, `exact = false`). What is left is the platform's own word: Metal's
  addPresentedHandler on the drawable with the host-time conversion, and MediaCodec's
  setOnFrameRenderedListener mapped back to the frame's pts. S each. `video.md`
- [ ] **V5** Gamma, on Metal and GL together. M. `video.md`
- [ ] **S6** Interruptions, audio focus and noisy routes under one policy. M, device proof (step
  24). `session.md`
- [ ] **S8** Background policy. M, device proof (step 26). `session.md`
- [ ] **S10** Accessibility semantics on the views. S, device proof (step 27). `session.md`
- [ ] **T6** One overlay geometry law on every renderer. M, Android proof on device. Land it as
  one law with viewport subtitles (BIG-BITES) or ahead of it; do not land two. `subtitles.md`
- [ ] **V1** Backward frame step, by landing before the target. M. `video.md`
- [ ] **O3** A trace sink and the Chrome trace format. M. `observability.md`
- [ ] **V7** Snapshots with the subtitles on them. M. `video.md`
- [ ] **Q4** Artifact sizes measured and ratcheted. M. `quality.md`
- [ ] **Q5** A perf gate on the three hot paths, no new dependency by default (the owner may
  prefer kotlinx-benchmark). S. `quality.md`
- [ ] **Q11** The conformance matrix writes a report CI publishes. S. `quality.md`
- [ ] **Q2** The iOS sample builds, launches and shows a frame on the simulator. M. `quality.md`

After the doors land (OWNER-GATED): **T4** subtitle sources through the byte doors, refusing
typed (S, `subtitles.md`); **Q9** file pickers in both samples (S, `quality.md`).

After the next KiteFFmpeg publish (OWNER-GATED): **K6** record the selected streams to a file as
they play (M, after K5); **V4** auto-deinterlace (M, after K2 and K3); **K7**'s player half, the
`inspect` call (after K7); **O1 remainder**, `PlaybackStats.containerBitrate` (after K2; no
backend binds a container-level bitrate today, so the field is honestly documented unbuilt);
**K1**'s player half, the resampler SPI (after K1).

## KiteFFmpeg, in order

The first five add C entry points. Each is small; what makes them one job is the tax around
them: the signature baseline, the generated wasm binding and its CI mirror check, the JNI
wrapper, and a compile on all twelve target trees. Batch them so the tax is paid once, and let
CI prove the trees this Mac cannot build.

- [ ] **K4** The filter DSL says which filters the build lacks, before FFmpeg does. S, Tier 1.
  `kiteffmpeg.md`
- [ ] **K2** Field order and container bitrate bound. S, Tier 2. `kiteffmpeg.md`
- [ ] **K3** The recipe compiles yadif, bwdif, loudnorm, ebur128, alimiter. S plus a rebake.
  `kiteffmpeg.md`
- [ ] **K5** A public packet write on copy streams. S, Tier 1. `kiteffmpeg.md`
- [ ] **K7** A probe in one call (the library half). S. `kiteffmpeg.md`
- [ ] **The wasm model's VP9 field** [KC-WASM-MODEL] (S). The rest of the metadata model is bound
  and proven against the fake; no `ffkmp_*vp9*` entry point exists for wasm at all. New C
  surface; rides K2's pass.
- [ ] **Codec enumeration and a measured build inventory** [KC-CAPS] (S + S).
  `FFmpeg.hasDecoder(name)` exists; enumeration does not (`av_codec_iterate` unbound), and
  `kiteffmpegInfo` prints the DSL toggles, not a measured inventory of the linked tree. Design
  commit for `FFmpeg.decoders(): List<String>`; make the info task measure. RED: the list
  contains h264 everywhere; the wasm fake scripts its list.
- [ ] **A lossless remux keeps identity** [KC-REMUX] (M). Both backends copy codec parameters and
  one time base, then stop: tags, language, title, disposition, rotation and display matrix, side
  data and stream groups are dropped; chapters are readable and never written; no program or
  attachment path. No `ffkmp_stream_set_metadata`, `ffkmp_stream_set_disposition` or chapter
  WRITE exists yet, so this rides the C pass. RED with an `ffprobe` oracle over a fixture
  carrying rotation, language and chapters. Whatever stays out is named here as remainder, not
  skipped silently. Fix the README "bit-exact" wording in the same commit.
- [ ] **Track selection policy and the disposition widening** [KC-TRACKSEL] (M, NEEDS-DESIGN).
  JVM and Native skip attached pictures then take the first video; `primaryAudio` is
  `firstOrNull`; no `TrackSelector` exists. `Disposition` collapses hearing-impaired and
  visual-impaired into one boolean while DESCRIPTIONS and COMMENT are read nowhere, so
  descriptive audio and commentary are indistinguishable at every layer. Design commit: a small
  `TrackSelector` policy (defaults documented, language hook) plus widened `Disposition` flags.
  Then: the cover-art-first fixture picks real video on wasm too; descriptive audio is never
  auto-picked over an ordinary sibling. apiDump.
- [ ] **A cancelled emit in `FilterGraph.process`** (S, NEEDS-DESIGN). The two backends are wrong
  in opposite directions. JVM (`FilterGraph.jvm.kt`, the `emit(out)` site) wraps the emit in
  `catch (Throwable) { out.close(); throw error }`, but `take` and `first` end a flow by throwing
  out of `emit` AFTER the value reached the collector, so `process(input).first()` hands back a
  frame the library then closed. Native (`FilterGraph.native.kt`, same site) has no catch, so an
  emit cancelled from OUTSIDE strands the clone. Nothing at the emit site can tell "the collector
  took it and stopped" from "the scope died before delivery". Decide which loss is preferred, or
  give the frame a reclamation path that makes the question moot, then align both.
- [ ] **The API ratchet watches 3 of 13 targets** [KC-ABI-SCOPE] (M). An iOS-only public API
  change passes today (dumps re-based under the host-only flag). Fix: CI fetches the prebuilt
  static trees (the mechanism the consumer jobs already use) for ios, linux and mingw and dumps
  with `-Pkiteffmpeg.requireAllTargets=true` on the macOS job. Prove it live with a throwaway
  iOS-only public function that must fail the widened check. Reduce to any genuinely unreachable
  targets instead of closing if some tree cannot exist in CI.
- [ ] **The C filter builder's dangling pointers** [KC-CFILTER] (M). `[out]` found by substring;
  sources published progressively then freed on failure leaving earlier entries dangling; four
  unchecked `av_strdup`; a plane index never bounded (and the test asserts the wrong answer; fix
  both); an eight-channel cap on upload only.
- [ ] **DSL leftovers** [KC-DSL]. Raw strings where a typed `SampleFormat` exists (S). And the real
  one, NEEDS-DESIGN: `CodecId` conflates bitstream identity with implementation, so `h264`,
  `libx264` and `h264_videotoolbox` are one type today, which is why a knob check has to read a
  name at all.
- [ ] **Frame access copies** [SOL-P3] (M). Native pays scratch plus a second ByteArray, JVM
  copies before JNI's own copy, nominally zero-copy reads box a plane list per access. One reused
  holder, one copy fewer per backend.
- [ ] **android-x64 SIMD** [PAR-3] (S). The emulator ABI builds with `--disable-asm` (0 SIMD
  symbols against arm64's 1365). Drop the flag, rebake, verify with `llvm-nm`.
- [ ] **Package-level concern scoping inside the one module** (S). `...kiteffmpeg.demux`,
  `.decode`, `.encode`, `.filter` and friends. This buys the clarity a module-per-concern split
  advertises without the per-module publication tax. The split itself was DECLINED 2026-08-29:
  payload weight lives in the native profile rather than in Kotlin modules, shared types drag the
  mass into a base module anyway, and 13 targets times N modules multiplies the config drift the
  seams item already documents. Revisit only if a real external consumer asks for a
  playback-only artifact, and then additively.

## KitePlayer correctness and contracts

- [ ] **Three SPI contract decisions from the old test-debt row** (S each, NEEDS-DESIGN). None is
  a missing test over working behaviour; each asks the code to SAY something it does not, so a
  test first would only pin the silence.
  - **Foreign `StreamInfo` refuses typed.** Four entry points take a caller-supplied stream and
    answer four ways: `selectTrack(TrackId)` validates and throws `IllegalArgumentException`
    (deliberate, and `Tracks` KDoc says so); `selectStreams` uses `mapNotNull`, so `{0, 999}`
    selects 0 and never mentions 999; a decoder factory handed a foreign stream reaches
    `error("no stream at index N")`, untyped, from the bottom of the stack; and
    `StreamChoice.At(missing)` resolves to null, indistinguishable from `None` and with no
    warning. Through the core the third degrades to a typed `NoPlayableStream`, so this is about
    the SPI's own contract. Decide whether `IllegalArgumentException` counts as typed for caller
    mistakes (the existing policy) or whether the SPI owes `PlaybackException` throughout.
  - **Decoder output diverging from codecpar is surfaced** (KiteFFmpeg). `codecpar` announces
    width, height, pixel format, sample rate and channels; the decoder may emit something else.
    Nothing compares them, and there is no channel to report it through: KiteFFmpeg has NO
    logger and NO warning callback in its Kotlin surface. Its whole non-fatal vocabulary is
    pull-style values on objects (`corruptDataSkipped`, `unusedOpenOptions`), and the one place a
    mismatch is checked (encode-side dimensions) throws. Throwing is wrong here: these are files
    that play. Decide the shape of the report; `corruptDataSkipped` is the closest precedent.
  - **A midstream audio format change reaches renegotiation or a typed warning.** The conversion
    half works: `AudioPipeline.matches` is full format equality, so a change in rate, channels,
    sample format or layout rebuilds the pipeline on the buffer that changed. What does not exist
    is observability: `decoder.outputFormat` is read at open and at track switch only, the sink
    and ring keep their negotiated format for the session, and `AudioFormatChanged` is never
    emitted mid-stream. A plain 48 kHz to 44.1 kHz change is completely silent. Decide between
    renegotiating the device and emitting a warning; a test needs a new harness knob, since
    `ScriptedAudioDecoder.outputFormat` is `private set` and every buffer is built from it.
- [ ] **An external master clock** [`SyncMode.ExternalMaster`, SOL-API4] (M, NEEDS-DESIGN). A wall clock
  drives playback and audio resamples to follow. Nothing in the public API can hand the engine an
  external clock, so the seam is the decision. Virtual-clock test driving a scripted external
  clock.
- [ ] **Audio device selection** [SOL-A6 split] (M). Enumerate and select the output device on
  desktop JVM (AudioSystem mixers) and Apple (device UID; coordinate with the CoreAudio move
  below, whichever runs first). Android routes stay OS-owned, documented.
- [ ] **Route recovery on Apple** (M; Tier 3 if teardown ordering is touched). A CoreAudio
  default-device-change listener (today `AudioDeviceChanged` is dead code on Apple: no listener
  exists); rebuild the sink through the recovery shape `DesktopAudioSink` already has. Closes
  gate box 10.
- [ ] **CoreAudio setup to Kotlin** [SOL-C2] (M). Non-realtime setup, session policy, route and
  interruption handling, capability queries and error mapping move from C to Kotlin;
  unsupported-platform C stubs become expect/actual. The C grew since first measured.
- [ ] **Hardware AV1 route by name** [PAR-6] (M). A decoder chosen BY NAME (no path exists
  today): open native `av1` with VideoToolbox attached, fall back to libdav1d when hardware
  refuses (the measured-fallback shape h264 and hevc already have). This Mac proves the refusal
  path only; the positive proof is HARDWARE-GATED.

## Release hygiene and CI

- [ ] **The output module's iOS simulator suite, revived and triaged** (S). It had not COMPILED
  since the tone-map parameter landed (a trailing lambda bound to the new last parameter; fixed
  2026-09-03) and no CI job runs it, which is how it rotted. Green now except 20 standing reds:
  19 are "opening the audio device failed", the simctl-spawn host boundary the simulator is
  known for, and one audio-session lease arm expects an exception the simulator's audio stack
  never throws. Decide guard-or-gate: skip the device-opening arms under the simulator the way
  the known-red list already treats them, or wire the suite into the iOS CI job with those arms
  filtered. Until one of those lands, nothing keeps this suite compiling.
- [ ] **Clean-consumer proof of 0.0.21** [KP-PROD remainder] (S). A container or CI job that has never seen this
  checkout builds the README's three install lines against Central, resolving `kiteffmpeg`
  0.1.0 and the Android AAR. Regrade gate boxes 11 and 17 with the result. Closes the
  consumer-smoke half of the Android AAR row [SOL-B8].
- [ ] **What the release workflow still lacks** (M). `publish.yml` runs
  `checkPublicationReadiness` and stages a USER_MANAGED deployment. Owed: a test run before
  publication and an atomic publish (box 20); licence, SBOM and provenance per bundled native
  dependency (box 19); the web runtime in a versioned package that releases with the player
  modules (boxes 14 and 18; needs the web deployment item below).
- [ ] **Dependency lockfiles or verification metadata** (S) [KP-B1..B13].
- [ ] **armeabi-v7a** [SOL-B5] (M). Owner-ruled: every ABI stays. Add armeabi-v7a to the JNI link
  recipes and the libass adapter. Three gates before the ABI is CLAIMED: (1) the RT ring's
  64-bit positions audited for ARMv7 atomics (LDREXD class) with a compile-time lock-free
  assert; (2) a CI compile lane; (3) one TV-stick smoke (DEVICE-DAY steps 11 and 12).
- [ ] **Web artifact layout and deployment** [X-13] (M). No artifact layout, no deployment story;
  COOP/COEP live only in prose. Ship: the layout, the `self.crossOriginIsolated` detect BEFORE
  importing the threaded module (the failure without it is a hang), an embedder doc, measured web
  tier sizes ratcheted to the budget the owner names. Test: both artifacts from the dual-mode
  server; the detect picks correctly both ways, asserted headless.
- [ ] **The conformance matrix in a real browser** [X-14] (M). The matrix has run under node
  only. Run the project's OWN suite headless in CI's browser job, per-row decoder recorded (wasm
  or WebCodecs) so a silent fallback cannot masquerade as a hardware pass. This also closes the
  wasm metadata model's owed run against a real build [KC-WASM-MODEL]: everything there is
  proven against the fake, which answers "does the Kotlin read the right fields" and nothing
  about "does the built artifact agree". Record one 4K HEVC clip through the hardware path
  against the 4K exit criterion (the decision stays the owner's).

## The C-layer backlog (each S to M)

- [ ] The identity gate inside the C library's constructor helpers: today only Kotlin call sites
  enforce it, so a pure C or JNI consumer reaches FFmpeg ungated.
- [ ] An entry-point audit for `requireCompatibleFFmpeg`: 15 call sites, nothing keeps them
  complete.
- [ ] Fuzz targets for the two public-reachable string parsers without one:
  `ffkmp_fmt_alloc_output2`'s format and the three raw `*_by_name` lookups.
- [ ] Split `test_convert.c` into contract versus baseline counts.
- [ ] Teardown defence with a mock AudioUnit.
- [ ] The two forked C harnesses: decide source-of-truth versus vendored copy.
- [ ] The opaque-migration prototype: one family early, before more C work depends on the
  assumption.

---

# APPENDIX: the parity map (what a mature player still needs, per domain)

A MAP, not a backlog: it asks "which of these does an item buy". Comparison class is libmpv and
libVLC. Parity of what EXISTS beats new feature count; nothing here is scheduled by being listed.

- Session: interruption policy (S6), crash-safe recovery (the memento is the state half),
  documented readiness.
- Clock and sync: route recovery (DOABLES), refresh-rate change (V2), passthrough clocks,
  live-edge policy.
- Queue: gapless and crossfade (S3). The queue itself, shuffle and a memento for persistence exist.
- Rate: reverse and trick play, slow-motion policy, scan and jog.
- Tracks: in-place video switching (rides streaming resilience), ranked language and
  accessibility policy (track selection, DOABLES), multi-angle, stable handles.
- Subtitles: the bitmap bridge and libass (BIG-BITES), then style override (T1), external URL
  sources (T4), accessibility captions.
- Video out: Linux and Windows GPU contexts (BIG-BITES and horizon), WebGL and WebGPU, HDR
  display capability (RQ-6), hotplug. Thumbnails exist.
- Audio out: WASAPI, ALSA and Pulse quality backends, enumeration and hotplug (device selection,
  DOABLES), exclusive mode, passthrough (owner scope). ReplayGain, the limiter and the equaliser
  exist.
- Network: streaming resilience (BIG-BITES). Live: DVR window, low-latency HLS and DASH,
  discontinuity policy.
- Chapters and programs: programs and editions (deleted from the API until built), attachments.
  Chapter navigation and position markers exist.
- Processing: runtime filter rebuild, recording while playing (K6). Thumbnails, waveforms
  and loudness measurement exist.
- Observability: sequenced transitions, trace export (O3). Structured logs with redaction and
  frame timing percentiles exist.
- Platform: media session and lock screen (S7), audio focus (S6), real PiP on iOS (V10), casting
  (decided later horizon), background policy (S8), accessibility semantics (S10).
- Security: DRM and CDM (out of scope until a product decision), secure surfaces (exist on Android),
  credential redaction (exists for URIs in logs), sandboxed parsing (fuzzing, BIG-BITES).
- Extensibility: stable plugin points for protocols, decryptors, subtitle providers, effects;
  none exposing FFmpeg or JNI internals.

# APPENDIX: format conformance and size tiers

The conformance matrix = the clips `scripts/testmedia.sh` generates plus the matrix tests
(MustPlay rows and torture rows live in the tests). "Plays all formats" is one measured claim
against THIS matrix on every platform that advertises playback. Size tiers (lean, standard,
full) are measured per target at release time; no size is promised before it is measured; the
web tier's gzipped size is the one that gates (1.22 MiB today, over the original 1.00 MiB spike
budget; the decision sits under OWNER-GATED).
