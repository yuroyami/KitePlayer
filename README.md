# KitePlayer

A media player for Kotlin Multiplatform, written in Kotlin from the ground up. It does not wrap
ExoPlayer, AVPlayer or libmpv: the playback engine is pure Kotlin in `commonMain`, so it behaves the
same wherever it runs. KiteCodec and FFmpeg provide the portable media backend and software decoder;
Android can instead couple MediaCodec directly to its renderer. Only the audio device, video surface,
and decoder are platform-specific.

[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.10-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue)](LICENSE)

> **KitePlayer is early and cannot be used as a dependency.** Nothing is publicly published, there is
> no public dependency installation path, and macOS arm64 is the only T3 candidate. One named iOS
> simulator holds a narrower experimental T2 Codec candidate, and one named Android emulator holds
> T2 Codec with provisional output evidence, each backed by a local runnable sample and a
> 27-row format matrix, not a full tier. No physical iPhone or Android device has run any of it:
> that session needs the owner's hardware and signing and is the one open item of the phone stage.
> This file states what has been measured and nothing beyond it. [`KPKMP.md`](KPKMP.md) is the
> full plan, defect register and running execution log: every phase, measured number and decision.

## Playing a file

```kotlin
val player = KitePlayer.create(
    PlayerConfig(
        backends = Backends(
            backend = KiteCodecMediaBackend(),   // reads and decodes, over KiteCodec
            output = AppleOutputBackend,         // the clock and the audio device, paired so they cannot mismatch
        ),
    ),
)

player.attachRenderer(AppKitVideoRenderer(window) { SoftwareConverter.toRgba(it as KiteCodecVideoFrame) })
player.open(MediaItem(path))                     // suspends, and returns paused with the first frame drawn
player.play()
player.seek(5.seconds)                           // suspends, and completes on the position it landed on
println("${player.state.value.status} at ${player.position()}")
player.closeAndAwait()                         // suspends until teardown and its terminal state are final
```

That is the whole playback path. Everything else, meaning the demux pump, both decoders, the audio
feeder, the presentation schedule, the seek machine and the state it publishes, belongs to the player.

Both backends are named rather than discovered. Kotlin/Native has no classpath service lookup, so the
alternative would be a reflective search that fails differently on every platform, and a null backend is
a typed configuration error on every target instead of a surprise at the first frame.

The surface is small on purpose: `open`, `play`, `pause`, `seek`, `seekLater`, `stop`, `setSpeed`,
`setVolume`, `setMuted`, `setLoop`, `selectTrack`, `attachRenderer`, `detachRenderer`, non-suspending
`close` and awaited `closeAndAwait`, plus four flows (`state`, `progress`, `stats`, `events`) and
`position()`. Anything a member cannot honour is refused with a typed error rather than accepted and
ignored.

## Support today

Every platform claim in this project means one of these tiers, and nothing more.

| Tier | Meaning |
|---|---|
| T1 API | Common code compiles for the target. No playback claim of any kind. |
| T2 Codec | A runtime on the target can open, decode, seek, cancel and close real media. |
| T3-Full | Qualified audio plus video output, sync, subtitles and lifecycle on the target. |
| T3-Audio | Qualified audio-only output and lifecycle, with no video or subtitle claim. |
| T4 Product | T3 plus the OS integrations (focus, background, PiP, routes) and clean packaging. |
| T5 Supported | T4 plus real-device qualification, security, performance and release gates, and a documented OS range. |

Today, honestly:

| Platform | Tier | What that means here |
|---|---|---|
| macOS arm64 | Experimental T3-Full candidate | Audio and video decode, play in sync and seek, in a window, on one development machine. Nothing is qualified, and there is no subtitle claim at all. |
| iOS simulator arm64 | Experimental T2 Codec candidate | One named local simulator app opens and decodes real media through the reusable `KitePlayerUIView`, lands a precise seek, reaches Ended through RemoteIO and completes causally awaited teardown. The 27-row format matrix runs green on the same named simulator: every playable row decodes and resumes after a mid-file seek, and AV1 refuses with a typed error because the phone FFmpeg profile vendors no software AV1 codec. Real-media cancellation coverage is still absent, so this stays below the full T2 Codec tier. |
| iOS arm64 | T1 | The same private software-codec, RemoteIO, layer-renderer and sample sources compile and link into an unsigned arm64 app. Nothing was installed or run on a physical iPhone. |
| Android emulator arm64 (API 36, 16 KiB) | T2 Codec, with provisional output evidence | A plain application depends on the one `:kiteplayer-phone` coordinate, builds its player from `phoneBackends()`, and shows it in the reusable `KitePlayerView`. The renderer-coupled AVC MediaCodec path reported `HardwareZeroCopy(MediaCodec)` and the 1080p30 fixture stabilized at 29 to 31 presented FPS after warm-up, with zero renderer failures or superseded frames. A separate smoke landed a precise seek, reached Ended, and tore down causally. The earlier 27-row backend matrix remains 26 of 27 on this emulator; its subtitle cue-decode assertion is still open. A preliminary Compose GPU run reached Ended with zero CPU conversion samples, but predates the final completion-fence hardening and is not a final performance result. Nothing ran on a physical Android device. x86_64 is compile, link, and package qualified only. |
| JVM (desktop) | T1, decode-proven | The FFmpeg backend's JVM arm decodes real media in tests over a test-only local JNI library. No desktop audio or video output path exists yet. |
| iOS x64, tvOS, watchOS, Android native, Linux x64 and arm64, Windows x64, JS, wasmJs | T1 | `kiteplayer-core` compiles for the target. There is no complete platform playback path. |
| macOS x64, and anything else | Not a target | Not declared in any build file yet. |

macOS arm64 remains the only T3 candidate. The named iOS simulator is now a second candidate above T1,
but only for the narrower codec lifecycle described above; neither label grants a tier. No platform here
has real-device qualification, a performance budget or a public installable package, which are among the
requirements that separate these local candidates from product support.

### Android rendering tiers

`KitePlayerView` has an API 29+ direct path from a renderer-owned MediaCodec output buffer to its
SurfaceView. The scheduler releases each buffer at its target monotonic timestamp. Video pixels do not
enter a Kotlin array, CPU colour converter, Canvas video draw, or Bitmap upload on this tier.

The hardware decoder admits only a self-describing configuration whose exact profile and level the
selected MediaCodec advertises:

- AVC Baseline, Constrained Baseline, Main, Extended, High, or Constrained High, all 8-bit.
- HEVC Main, 8-bit, with monochrome or 4:2:0 chroma.
- VP9 Profile 0, 8-bit 4:2:0, with a complete WebM CodecPrivate or `vpcC` record.
- AV1 Main 8, 4:2:0, with a valid `av1C` record and sequence-header OBU.

Malformed, ambiguous, wider, or unsupported declarations are not guessed into hardware. With
`HwdecPolicy.Auto`, a seekable source may start on the renderer MediaCodec path. A decoder failure
closes the failed session, reopens the backend under `HwdecPolicy.Off` at the current position, prerolls,
and resumes the requested play or pause state. An unseekable `Auto` source stays on the backend because
it cannot recover without losing content. `Require` allows the renderer path but makes a runtime failure
terminal. `Prefer` is intentionally not applied to renderer factories until the engine can preserve one
global hardware-kind order across renderer and backend factories. `Off` always uses the backend.

Compose video has a separate API 31+ GPU tier: MediaCodec writes an external-OES SurfaceTexture, one
GLES2 pass writes RGBA_8888 into an ImageReader, and Skia samples the resulting hardware Bitmap. Its
Android state must be bound to the exact host `Window`. The bridge matches the draw to that window's
`FrameMetrics` record and retires the ImageReader lease only after `GPU_DURATION` reports completion for
the matching `VSYNC_TIMESTAMP`. API 26 to 30, unsupported codec declarations, unavailable hardware,
and rejected colour configurations use immutable software images. No physical Android device has
qualified either GPU tier yet; the exact emulator evidence is in `ANDROID_GPU_WORK.baseline.txt`.

**What the vendored FFmpeg can open.** The decode side of the vendored profile is wide by class:
every native FFmpeg decoder, demuxer, parser and bitstream filter is compiled, so the files people
actually have (AVI, WMV, FLV, DVD-shaped MPEG-PS, MKV with AC-3, E-AC-3, DTS, TrueHD, ALAC, and the
rest of FFmpeg's native menu) open and decode. The measured proof is nine matrix rows that failed on
the previous profile and pass now, on the JVM host gate, the named iOS simulator and the named
Android emulator. The named absences: https and every streaming protocol beyond plain http stay out
(the protocol list is exactly file, pipe, data, http, tcp), software AV1 stays out on phones (FFmpeg's
own av1 decoder is a hardware wrapper; vendoring dav1d is a separate decision), and subtitle formats
beyond SubRip and WebVTT are parsed as streams but not yet decoded to cues.

**The local iOS substrate.** `kiteplayer-output` and `kiteplayer-ffmpeg` now declare iosArm64 and
iosSimulatorArm64 alongside macosArm64. The private S1.b.1 software-codec trees feed the FFmpeg backend,
and the same pure-C render callback and lifecycle use RemoteIO while an explicit policy decides whether
KitePlayer or the application owns `AVAudioSession`. An app-hosted native test on one named simulator
proves callback activity, ring movement, clock anchoring and teardown. Its scratch launcher is test
infrastructure rather than the product sample. A separate filtered native test proves that the local
renderer can place a caller-converted software frame in a caller-owned layer. Those pieces now meet in a
private UIKit sample: its normal launch opens the bundled clip paused and offers Play, Pause and Seek 5s;
its bounded smoke launch reaches Ended and causally awaited teardown. Nothing was run on a physical
iPhone, iosX64 was not qualified, and no public artifact or full support tier moved.

**What changed in this run.** The audio device's real-time callback left managed Kotlin. It is now a
`static` C function in `kiteplayer-rt`, installed by C, reading a C ring, with no `StableRef` and no
garbage-collected object anywhere on the device's thread. Alongside it, KiteCodec's 176 FFmpeg helpers
became a compiled and symbol-audited C library with its own tests, sanitizer runs and fuzz targets, and
it now refuses an FFmpeg runtime that does not match the headers it was compiled against.

**What the evidence moved, and what it did not.** macOS arm64 remains an experimental T3-Full candidate
on one development machine. The named iOS simulator gains only the partial T2 Codec candidate above;
iosArm64 and every other target remain T1 or unqualified. Seventeen native targets compile the real-time
C into an architecture-verified archive; the device implementation uses DefaultOutput on macOS and
RemoteIO on iOS, while targets without device glue refuse loudly rather than claiming to work.

**Which audio ring the shipped Apple path uses.** On macOS and the local iOS substrate it is the C one,
and the eighteen
`AudioRingTest` cases in `commonTest` no longer cover it. Sixteen of them are the ones register item
B1-20 was written about; the two added with the C ring test the same portable implementation. Both
rings exist permanently, because `kiteplayer-core`'s `commonMain` targets js and wasmJs, which can
never contain C, and because the Kotlin ring is the only oracle the C ring can be checked against. What covers the shipped path is the
eight C suites and a differential oracle that drives one scripted sequence through both rings and
compares the samples bit for bit and the published clock anchor to the microsecond. Reading
"AudioRingTest passes" as coverage of what a macOS listener hears would be exactly the substitution this
project's evidence rules forbid.

**What the run before this one changed.** Rotation, the most visible thing a container could say
that this player ignored. A display matrix now travels from the container to the stream, from the
stream to every frame of it, and the Core Graphics renderer draws the quarter turn, so a recording
made in portrait is shown the
right way up instead of on its side. Other container metadata is still dropped, and the list is short but
real: chapters, mastering display and content light levels, and any display matrix that is not one of the
four quarter turns. Nothing else about the tiers moved: the same platform has the same backends, and the
run added no target. Alongside it, every library module now carries a checked-in dump of its public API,
so a signature cannot change without the change being visible in a diff.

## What is proven

Measured on one Apple silicon development machine, with local debug binaries, on clips
`scripts/testmedia.sh` generates. This is development evidence: enough to say the engine works, not
enough to call any platform supported.

- 1080p30 for 10 seconds: two quiet controls each decoded and submitted all 300 frames with 0 dropped,
  0 repeated, 0 audio underruns and final audio-to-video drift of 1 ms and 0 ms. One retained loaded
  observation submitted 299, dropped 1 late and ended at -32 ms drift; it was not retried away.
- Real variable frame rate 720p for 8 seconds, frame durations cycling through 16.7, 33.3, 50, 25 and
  41.7 ms: 240 frames decoded, 240 submitted, 0 dropped, 0 repeated, 0 audio underruns.
- 4K HEVC 10-bit with no audio track: 180 frames, with video driving the clock.
- MPEG-TS with a 1400 second start offset: 300 frames decoded, 300 submitted, 0 dropped, 0 repeated, and
  a position that runs from 0 rather than from 23 minutes.
- Seeking in real media: 20 random precise seeks in a 1080p30 file each land within one frame duration
  of the position asked for, a seek past the end finishes the file, and a seek to 0 mid-playback carries
  on from the start. A mid-playback seek to 5 seconds lands on 5.000 and plays through to the end with
  nothing dropped.
- Two long runs, both played to the last frame: 30 minutes of audio with the video track deselected,
  and 11 minutes of 1080p30 with video. 0 audio underruns and 0 rebuffers in either. The video run
  decoded 19800 frames, submitted 19799, dropped 1 late and repeated none, and its audio to video drift
  never left the band of -31 to +5 ms, against the 40 ms the sync law corrects at. Both positions ended
  on the file's own duration. Resident set, sampled once a minute, ended lower than it started in both
  runs: the audio run settled at 41.2 MB after peaking at 79.7 MB while it filled its buffers.
- Colour: BT.709, BT.601, yuv420p10le, SMPTE 240M, centre-sited NV12 and P010 clips decoded,
  converted, and compared pixel by pixel against what the `ffmpeg` command line produces. Mean
  component error under 2 of 255, and measured between 0.14 and 0.62 on the six.
- Audio: a 5.1 file downmixed to stereo and compared sample by sample against `ffmpeg -ac 2` on the
  same file. Mean sample error under 0.0001, so the mix matrix and the channel order are the same
  ones FFmpeg applies.
- Rotation: a clip carrying a quarter turn in its display matrix reports that turn on its stream and on
  every one of its frames, while the stored frame size stays the 320 by 240 the pictures really are.
  Each of the four turns is drawn and read back pixel by pixel, so what is checked is where the picture
  landed and not only that its shape changed, and at that clip's own geometry the drawn picture comes
  out 240 by 320.
- One hundred seeded simulated sessions, each with faults injected on purpose, hold every invariant:
  nothing from a superseded seek is ever shown or heard, timestamps never go backwards, every frame and
  packet is closed exactly once, every command completes exactly once, and every session reaches a
  terminal state. Twenty seeks in one virtual millisecond cost exactly one flush cycle.
- The audio device's callback, measured on a real device for ten minutes while the garbage collector
  was deliberately made to run 88,302 times: the slowest single callback body out of 51,679 was
  9,208 nanoseconds against a budget of 5,333,333, which is under two tenths of one percent of it,
  with nothing over budget, no starvation, no degraded clock reading and no missing ring. Real media
  through the whole shipped path for ten minutes, sixty times through a container with a real
  decoder and the engine's own feeder, played 598.23 seconds of audio in 600 seconds of wall clock
  and zero starvations in that run, on a ring less than half the size. The same test against a
  Kotlin callback, which is the arrangement this run removed, misses the budget with or without
  the manufactured collector pressure: the original ten minute control was over budget on 1,482
  of 51,533 callbacks with a worst body of 57.1 milliseconds under pressure and 159 times with a
  worst body of 81.4 milliseconds without it, and a shorter re-run at the whole-of-B1 review was
  over budget 99 times under pressure and 18 times without, with a worst body of 10.5
  milliseconds while the collector ran only 31 times. Which arm is worse varies between runs;
  what does not vary is that a managed callback misses a hard deadline on its own, which is why
  it was removed rather than tuned around.
  An earlier ten minute run of the same positive case measured 9,083 nanoseconds and 21
  starvations, and those starvations belong to that test's own managed feeder rather than to the
  callback, which is why the starvation count there is bounded and the callback's own numbers are
  asserted exactly. Separately, a symbol and instruction audit of the shipped object shows it has no
  allocator, lock, log or framework symbol to call at all, and five million synthetic callbacks with
  the allocator interposed performed zero allocations of any kind.
- 483 test executions pass across 6 suites, with nothing skipped: 192 engine tests on the JVM,
  201 compiled for macOS arm64, 34 against the real audio device and the renderer,
  36 that decode and seek in real media, 8 for the SubRip parser and 12 over the real-time C
  bindings. Four of the 483 open the audio device and contain supervised ten-minute arms; one
  negative-control case contains two such arms, so they are gated on an environment variable and
  were run separately rather than in the ordinary suite. Beside them sit
  132 C test cases in 8 suites, each run in four modes, and 40 unit tests over the build logic.
  Separately, the app-hosted iOS simulator program passes 28 native tests: 4 session-policy, 9
  real-time sink, 14 sink-lifecycle and 1 RemoteIO fixture. That is named-simulator evidence, not a
  physical-device result or an addition to the six host-suite total.
  Separately again, the filtered iOS simulator renderer suite passes 8 native tests, including a real
  caller-owned `CALayer` receiving non-null `contents`. Those tests are not part of the app-hosted 28
  or the six host-suite total. A separate bounded run of the private UIKit sample opened the bundled
  real-media clip, landed its precise seek, reached Ended, decoded 137 frames, submitted 118, presented
  3 into the layer, recorded 68 audio underruns and completed causally awaited teardown. That run is
  named-simulator lifecycle evidence, not a performance claim, additional test count or physical-device
  result.

## What does not exist yet

- **No queue and no playlist.** One media item at a time. Asking to loop a queue is refused rather than
  quietly treated as looping the item.
- **No tempo stage.** A playback speed other than 1.0 is refused while audio is open, because the
  samples would still reach the device at the device's rate. Video-only speed is legal.
- **The rate conversion is interim quality.** Channel downmixing and rate conversion exist and are
  compared against FFmpeg, but the conversion is linear interpolation, which dulls the top end of
  music. libswresample replaces it before 1.0. There is no normalisation on the downmix either, so a
  source that is loud in several channels at once can clip.
- **Subtitles are one parser.** `kiteplayer-subtitles` reads SubRip. Nothing times, positions or draws
  a cue, and the player never reads a subtitle track. SubRip parsing is not subtitle support.
- **Android's GPU tiers are not physical-device qualified.** API 29+ can decode admitted 8-bit AVC,
  HEVC, VP9, and AV1 configurations directly to `KitePlayerView`'s SurfaceView. API 31+ can instead
  make the picture true Compose content through one OES-to-RGBA GPU pass and an explicit
  GPU-completion lease. API 26 to 28 and configurations the strict hardware gate refuses use the
  FFmpeg software path. The direct path reached the generated clip's 30 FPS rate on one API 36 arm64
  emulator, but there is no physical-device benchmark, release benchmark, power result, or soak result.
  Every non-Android platform still decodes video in software.
- **Rotation is four turns and no more.** The three quarter turns and no rotation are drawn. A display
  matrix that mirrors the picture or skews it by an arbitrary angle is drawn as stored, which keeps the
  picture rather than the exact transform.
- **No tone mapping.** PQ, HLG and BT.2020 constant luminance are converted with the matrix alone, so
  they play and they look wrong. Each says so once per stream through a typed warning.
- **Network input is FFmpeg passthrough, not an application network layer.** The media URI reaches
  FFmpeg unchanged, so only protocols carried by the linked build are reachable. One loopback HTTP
  case has played to completion. There is no protocol allowlist, open or read deadline, or secret
  redaction over that path yet; network hardening is parked in KPKMP section 17.8. Live or adaptive
  streaming and DRM remain unsupported.
- **The real-time audio core is C on macOS and the local iOS substrate.** It uses DefaultOutput on
  macOS and RemoteIO on iOS; managed iOS sinks acquire an explicit process-wide playback-session lease,
  while application-managed sinks make no session call. tvOS, watchOS and the remaining native targets
  still return an unsupported-platform verdict. Callback activity, ring movement and clock anchoring are
  proved specifically by an app-hosted native test on one named simulator; the caller-owned layer
  renderer contract has its own filtered simulator test. Those separately tested pieces now meet in the
  private UIKit sample described above. It is a bounded local end-to-end result on that named simulator,
  not physical-device qualification or reusable UI.
- **No product qualification.** Every runtime number above comes from debug binaries on one machine.
  There is no release-mode benchmark, physical-iPhone run or performance budget in this evidence. The
  long runs are those same local binaries watched with `ps`, so they establish only their measured
  duration; no platform here is above the experimental candidate labels in the table.
- **Nothing is publicly published.** Building this needs a Maven Local KiteCodec publication and an
  FFmpeg on the machine, both set up by hand.

The public API says the same thing about itself: a member that nothing implements carries a marker in
its own documentation, pointing at where it is planned.

## What comes after this, and is not started

The plan is `KPKMP.md` in this repository, and it has two horizons. Horizon A is the work above, and it
is finished. Horizon B is everything that turns this engine into a product, it is sequenced and decided,
and **none of it is done**: a shared C ABI with fuzzing and sanitizers, the rest of the codec layer,
subtitles end to end with libass, swresample and real tempo control, a Metal renderer with hardware
decode and a colour-managed pipeline, network and live sources, published artifacts on every ecosystem,
supply chain and security work, per-platform qualification to T5, a performance constitution with
published distributions, and the remaining product surface. Read the roadmap as a plan and never as a
capability.

## Run it here

This is a development loop, not an installation.

```bash
# 1. KiteCodec has no public publication, so publish it into the local Maven repository first.
cd ../KiteCodec && ./gradlew publishToMavenLocal -Pkitecodec.applePhoneTargetsOnly=true

# 2. Generate the test clips. Needs ffmpeg on PATH; no media is committed to the repository.
cd ../KitePlayer && ./scripts/testmedia.sh

# 3. Build the macOS sample and play a clip in a window.
./gradlew :kiteplayer-sample:linkDebugExecutableMacosArm64
BIN=kiteplayer-sample/build/bin/macosArm64/debugExecutable/kiteplayer.kexe
$BIN testmedia/sync1080p30.mp4 --window

# 4. Build the simulator framework, then open the private app in Xcode.
./gradlew :kiteplayer-sample:linkDebugFrameworkIosSimulatorArm64 \
  -Pkitecodec.ffmpeg.localRoot="$PWD/../KiteCodec/native-libs"
open kiteplayer-sample/iosApp/KitePlayerSample.xcodeproj
```

The macOS sample creates a player, hands it the two backends that platform has, opens a file and plays
it. It prints the position, video drift against the master clock and frame accounting from the player's
own flows. Without `--window` the frames go to a counting renderer, `--no-video` plays audio only, and
`--seek=<seconds>` seeks once playback is under way. The local Xcode scheme builds the static Kotlin
framework first, bundles the same generated sync clip and launches a UIKit host with Play, Pause and
Seek 5s controls. Run the shared `KitePlayerSample` scheme on the named simulator from Xcode; the exact
command-line smoke recipe is in `kiteplayer-sample/iosApp/README.md`. It is a development proof, not an
installable dependency or reusable view package.

## Why the position is exact

Most players work out the playback position by counting the samples they have handed to the audio
device and subtracting an estimate of how much the device is still holding. The estimate is per
platform and usually wrong, so the picture sits at a fixed offset from the sound and nothing ever
corrects it.

KitePlayer does not estimate. The device is asked when the buffer it is filling will be heard, and the
clock is anchored to that instant:

```kotlin
public fun interface AudioRenderCallback {
    // deadlineNanos: when the LAST frame of this buffer becomes audible.
    public fun onRender(destination: AudioSinkBuffer, frames: Int, deadlineNanos: Long): Int
}
```

Every sink also declares how much its own latency figure can be trusted, through `LatencyQuality`.
Acting on that declaration is not implemented yet: today it produces one warning and changes nothing
else.

## Why the colours are right

A renderer that ignores a frame's colour metadata produces a picture that is present and wrong, which
is worse than one that is absent: hues shift, or black turns grey, and nobody notices until they
compare against another player. So the metadata travels with the frame, and correctness is checked
against a reference rather than by eye.

```kotlin
public data class ColorSpaceInfo(
    val matrix: ColorMatrix = ColorMatrix.Bt709,        // BT.601 and BT.709 differ enough to shift every hue
    val primaries: ColorPrimaries = ColorPrimaries.Bt709,
    val transfer: ColorTransfer = ColorTransfer.Bt709,
    val fullRange: Boolean = false,                    // studio range is 16 to 235, and most video uses it
    val chromaLocation: ChromaLocation = ChromaLocation.Left,
)
```

Six clips are decoded through the engine, converted by the software path, and compared per pixel against
the `ffmpeg` command line's output. The mean component error is under 2 units of 255 on all six.

Rotation travels the same way and for the same reason: it is a presentation instruction, so it sits on
the frame next to the colour rather than inside the frame's size. A frame's size is what the pixels are,
which is what every stride in the converter depends on, and a quarter turn changes what is shown without
changing that.

High dynamic range is the current hole in this: PQ and HLG clips are converted with the matrix alone,
with no tone mapping, so they play and they look wrong. BT.2020 constant luminance is the same case.
Both say so, once per stream, through a typed playback warning rather than silently.

## Why the engine has no platform code

`kiteplayer-core` depends on kotlinx-coroutines and atomicfu, and nothing else. It calls no platform
API, and it holds exactly one `expect` declaration: an internal one that asks each target for the
threads the player confines its workers to, because a single-thread dispatcher is not something a
target-free source set can build. Three things follow:

1. It compiles for all 21 targets its build file declares, including `js` and `wasmJs`. That is a
   compile claim only, which is tier T1 above.
2. Its whole behaviour is testable with a clock the test controls. The 192 common engine tests run
   in milliseconds and run identically on the JVM and Kotlin/Native; the macOS suite adds nine
   platform and real-thread cases.
3. A new platform is reached by implementing four interfaces, not by adding an `actual` to the engine.

Time enters through one interface, which is what makes the second point possible:

```kotlin
public interface MonotonicClock {
    /** Nanoseconds since an arbitrary fixed origin. Must never go backwards. */
    public fun nanos(): Long
}
```

## Modules

| Module | Holds | Targets |
|---|---|---|
| `kiteplayer-core` | the engine: the player class, the session loop, clock, synchronisation, queues, buffering, the seek machine, the public API and the service interfaces | every target it declares |
| `kiteplayer-rt` | the real-time audio core in C: the lock-free sample ring, the device glue and the render callback the audio device actually calls | seventeen native targets compile the C; DefaultOutput is exercised on macOS and RemoteIO by an app-hosted native test on one named iOS simulator |
| `kiteplayer-output` | the audio sinks and renderers that talk to an operating system: Apple audio and layers, the macOS AppKit window, Android AudioTrack, the direct Surface renderer, and the OES-to-RGBA hardware-image bridge | macOS arm64, iOS arm64, iOS simulator arm64 and Android; the private simulator sample consumes the iOS path, while the Android sample and Compose module consume the Android paths |
| `kiteplayer-ffmpeg` | the source and the decoders over KiteCodec, and the CPU colour conversion | macOS arm64, iOS arm64, iOS simulator arm64, JVM and Android; the iOS variants consume private local codec trees |
| `kiteplayer-subtitles` | SubRip parsing and nothing else. No cue is timed, laid out or drawn, and it is not connected to playback | every target it declares |
| `kiteplayer-phone` | the phone aggregate: one coordinate carrying the playable stack, `KitePlayerView` for Android, `KitePlayerUIView` for iOS, and `phoneBackends()` | Android, iOS arm64 and iOS simulator arm64; the Android view is exercised by the Android sample's measured smoke, the iOS view compiles and links but nothing measured consumes it yet |
| `kiteplayer-compose` | the optional Compose Multiplatform surface: `KitePlayerSurface`, wrapping the platform view, and `KiteVideo`, the Compose-true renderer whose frames draw through Compose itself | Android, iOS arm64 and iOS simulator arm64; compiles and links on all three and is host-tested off device. Android API 31+ has a Window-bound OES-to-RGBA GPU path with exact FrameMetrics GPU-completion leases; API 26 to 30 uses immutable software images. One API 36 emulator has preliminary real-media Compose evidence, but the final post-fence rerun and every physical-device measurement remain open. The Apple path's cost stays unmeasured until the plan's S2 exit |
| `kiteplayer-sample` | a CLI on macOS plus a private UIKit host with Play, Pause, Seek 5s and a bounded smoke oracle | macOS arm64, iOS arm64 and iOS simulator arm64; the device app is link-only |
| `kiteplayer-sample-android` | the plain Android app: one `KitePlayerView`, three buttons and the smoke oracle, on the one `kiteplayer-phone` dependency | Android arm64-v8a and x86_64; debug and R8 release measured on one named emulator |

The dependency arrow never points into `kiteplayer-core`. The core declares interfaces and the backends
implement them, which is what allows a completely different backend, for example WebCodecs in a
browser, without the engine noticing.

Every library module tracks its own public API in a checked-in dump under `api/`. `updateKotlinAbi`
rewrites the dumps, `checkKotlinAbi` fails the build when the code and the dumps disagree, and the
samples have none because an application has no public API to keep.

## Build and test it here

```bash
./gradlew :kiteplayer-core:jvmTest            # 192 tests, the engine in virtual time
./gradlew :kiteplayer-core:macosArm64Test     # 201: common, platform and real-thread cases
./gradlew :kiteplayer-output:macosArm64Test   # 34 tests against the real audio device and the renderer
./gradlew :kiteplayer-ffmpeg:macosArm64Test   # 36: real decode, real seeking, colour against a reference
./gradlew :kiteplayer-subtitles:jvmTest       # 8 tests, the SubRip parser
./gradlew :kiteplayer-rt:macosArm64Test       # 12 checks over the C binding
./gradlew checkKotlinAbi                      # the public API against its committed dumps
```

The device tests open the default output and play a short quiet tone. They exist because the one thing
a mock cannot confirm is that the engine's clock and the audio device share a time base. The separate
28-test iOS audio proof uses the exact freshly linked Kotlin/Native program inside the minimal simulator
app host recorded in `KPKMP.md`; the ordinary bare-kexe simulator runner has no application audio
context. The filtered 8-test renderer proof runs separately on the same named simulator and needs no
application audio context. The bounded UIKit sample smoke is separate again; it is the measured local
run behind the simulator candidate row and is not included in any of those test totals.

## License

Apache-2.0. See [NOTICE](NOTICE).

Decoding is done by [KiteCodec](https://github.com/yuroyami/KiteCodec), which binds FFmpeg's libav\*
libraries. The FFmpeg build you link carries its own license, and that license decides whether you may
ship your binary. KiteCodec's Gradle plugin makes that choice explicit and fails the build if it is
left unset.

Part of the Kite family: [KiteCodec](https://github.com/yuroyami/KiteCodec),
[KiteCore](https://github.com/yuroyami/KiteCore), [KitePDF](https://github.com/yuroyami/KitePDF),
[KiteImage](https://github.com/yuroyami/KiteImage), [KiteQR](https://github.com/yuroyami/KiteQR).
