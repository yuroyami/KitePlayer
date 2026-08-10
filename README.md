# KitePlayer

A media player for Kotlin Multiplatform, written in Kotlin from the ground up. It does not wrap
ExoPlayer, AVPlayer or libmpv: everything that makes it a player is pure Kotlin in `commonMain`, so it
behaves the same wherever it runs, and FFmpeg (through KiteCodec) does the decoding. Only the audio
device, the video surface and the decoder are per platform.

[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.10-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue)](LICENSE)

> **KitePlayer is early and cannot be used as a dependency.** Nothing is published to any repository,
> there is no install path, and macOS arm64 is the only target with backends. This file states what has
> been measured and nothing beyond it. [`KPKMP.md`](KPKMP.md) in this repository is the full plan, the
> defect register, and the running execution log: every phase, every measured number, and every decision
> taken along the way.

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
player.close()
```

That is the whole playback path. Everything else, meaning the demux pump, both decoders, the audio
feeder, the presentation schedule, the seek machine and the state it publishes, belongs to the player.

Both backends are named rather than discovered. Kotlin/Native has no classpath service lookup, so the
alternative would be a reflective search that fails differently on every platform, and a null backend on
a target with no default is a typed configuration error instead of a surprise at the first frame.

The surface is small on purpose: `open`, `play`, `pause`, `seek`, `seekLater`, `stop`, `setSpeed`,
`setVolume`, `setMuted`, `setLoop`, `selectTrack`, `attachRenderer`, `detachRenderer`, `close`, plus four
flows (`state`, `progress`, `stats`, `events`) and `position()`. Anything a member cannot honour is
refused with a typed error rather than accepted and ignored.

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
| JVM, Android, iOS, tvOS, watchOS, Android native, Linux x64 and arm64, Windows x64, JS, wasmJs | T1 | `kiteplayer-core` compiles for the target. There is no audio device, no renderer and no decoder for it, so there is no playback. |
| macOS x64, and anything else | Not a target | Not declared in any build file yet. |

macOS arm64 is still the only candidate above T1, and it is still a candidate rather than a tier: no
platform here has real-device qualification, a performance budget, or a packaged consumer build, and
those are what T4 and T5 mean.

**What changed in this run.** The audio device's real-time callback left managed Kotlin. It is now a
`static` C function in `kiteplayer-rt`, installed by C, reading a C ring, with no `StableRef` and no
garbage-collected object anywhere on the device's thread. Alongside it, KiteCodec's 176 FFmpeg helpers
became a compiled and symbol-audited C library with its own tests, sanitizer runs and fuzz targets, and
it now refuses an FFmpeg runtime that does not match the headers it was compiled against.

**No tier moved, and that is deliberate.** This run added no target, no backend and no playback
capability. macOS arm64 is still an experimental T3-Full candidate on one development machine and
everything else is still T1. What changed is how much of the audio path is provable, not what it can
play. Seventeen native targets compile the real-time C into an architecture-verified archive, which is
compilation and nothing more: the device implementation exists for macOS only, and every other target's
audio entry points refuse loudly rather than claiming to work.

**Which audio ring the shipped path uses.** On macOS it is the C one, and the eighteen
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

Measured on one Apple silicon development machine, with a debug binary, on clips
`scripts/testmedia.sh` generates. This is development evidence: enough to say the engine works, not
enough to call any platform supported.

- 1080p30 for 10 seconds: 300 frames decoded, 300 submitted to the renderer, 0 dropped, 0 repeated,
  0 audio underruns, and a final audio to video drift inside 1 ms on every run of it.
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
  with zero starvations, on a ring less than half the size. The same ten minutes against a Kotlin
  callback, which is the arrangement this run removed, was outside the budget on 1,482 of 51,533
  callbacks with a worst body of 57.1 milliseconds; removing the manufactured collector pressure
  from that control left it outside the budget 159 times with a worst body of 81.4 milliseconds,
  which is worse rather than better, so what fails is a managed callback and not a collector pause.
  An earlier ten minute run of the same positive case measured 9,083 nanoseconds and 21
  starvations, and those starvations belong to that test's own managed feeder rather than to the
  callback, which is why the starvation count there is bounded and the callback's own numbers are
  asserted exactly. Separately, a symbol and instruction audit of the shipped object shows it has no
  allocator, lock, log or framework symbol to call at all, and five million synthetic callbacks with
  the allocator interposed performed zero allocations of any kind.
- 454 test executions pass across 6 suites, with nothing skipped: 181 engine tests on the JVM in
  under a second, 189 compiled for macOS arm64, 28 against the real audio device and the renderer,
  36 that decode and seek in real media, 8 for the SubRip parser and 12 over the real-time C
  bindings. Four of the 454 open the audio device and take ten minutes each, so they are gated on an
  environment variable and were run separately rather than in the ordinary suite. Beside them sit
  121 C test cases in 8 suites, each run in four modes, and 11 unit tests over the build logic.

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
- **No hardware decode and no GPU renderer.** Frames are converted on the CPU and drawn through Core
  Graphics, which costs milliseconds per frame at 1080p.
- **Rotation is four turns and no more.** The three quarter turns and no rotation are drawn. A display
  matrix that mirrors the picture or skews it by an arbitrary angle is drawn as stored, which keeps the
  picture rather than the exact transform.
- **No tone mapping.** PQ, HLG and BT.2020 constant luminance are converted with the matrix alone, so
  they play and they look wrong. Each says so once per stream through a typed warning.
- **No network path, no live or adaptive streaming, no DRM.**
- **The real-time audio core is C on macOS and nowhere else.** Every audio entry point exists on the
  other sixteen native targets and refuses with an unsupported-platform verdict, because iOS, tvOS and
  watchOS need a different audio unit and an activated audio session, and neither can be tested here.
  A refusal is deliberate: claiming support that nothing measured would be worse than saying no.
- **No qualification of any kind.** Every number above comes from a debug binary on one machine. There
  is no release-mode benchmark, no real-device run and no performance budget in this evidence. The two
  long runs are that same debug binary watched with `ps`, so they say the engine holds together for half
  an hour and nothing more, and no platform here is above the experimental candidate in the table.
- **Nothing is published.** Building this needs a local KiteCodec publication and an FFmpeg on the
  machine, both set up by hand.

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
# 1. KiteCodec is not published anywhere, so publish it into the local Maven repository first.
cd ../KiteCodec && ./gradlew publishToMavenLocal -Pkitecodec.hostTargetsOnly=true

# 2. Generate the test clips. Needs ffmpeg on PATH; no media is committed to the repository.
cd ../KitePlayer && ./scripts/testmedia.sh

# 3. Build the sample and play a clip in a window.
./gradlew :kiteplayer-sample:linkDebugExecutableMacosArm64
BIN=kiteplayer-sample/build/bin/macosArm64/debugExecutable/kiteplayer.kexe
$BIN testmedia/sync1080p30.mp4 --window
```

The sample creates a player, hands it the two backends this platform has, opens a file and plays it. It
prints the position, the video drift against the master clock and the frame accounting as it goes, all
read from the player's own flows. Without `--window` the frames go to a counting renderer that records
how far each one landed from its requested time, `--no-video` plays the audio track only, and
`--seek=<seconds>` seeks once playback is under way and reports where it landed.

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
2. Its whole behaviour is testable with a clock the test controls. The 178 engine tests run in
   milliseconds, and they run identically on the JVM and on Kotlin/Native.
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
| `kiteplayer-rt` | the real-time audio core in C: the lock-free sample ring, the device glue and the render callback the audio device actually calls | seventeen native targets compile the C; the device implementation is macOS only |
| `kiteplayer-output` | the CoreAudio sink, the AppKit window and the Core Graphics renderer | macOS arm64 |
| `kiteplayer-ffmpeg` | the source and the decoders over KiteCodec, and the CPU colour conversion | macOS arm64 |
| `kiteplayer-subtitles` | SubRip parsing and nothing else. No cue is timed, laid out or drawn, and it is not connected to playback | every target it declares |
| `kiteplayer-sample` | a CLI that creates a player, plays a file and reports what the player says happened | macOS arm64 |

The dependency arrow never points into `kiteplayer-core`. The core declares interfaces and the backends
implement them, which is what allows a completely different backend, for example WebCodecs in a
browser, without the engine noticing.

Every library module tracks its own public API in a checked-in dump under `api/`. `updateKotlinAbi`
rewrites the dumps, `checkKotlinAbi` fails the build when the code and the dumps disagree, and the
sample has neither because an executable has no public API to keep.

## Build and test it here

```bash
./gradlew :kiteplayer-core:jvmTest            # 178 tests, the engine in virtual time
./gradlew :kiteplayer-core:macosArm64Test     # 179: the same, plus a real-thread stress test
./gradlew :kiteplayer-output:macosArm64Test   # 20 tests against the real audio device and the renderer
./gradlew :kiteplayer-ffmpeg:macosArm64Test   # 29: real decode, real seeking, colour against a reference
./gradlew :kiteplayer-subtitles:jvmTest       # 8 tests, the SubRip parser
./gradlew checkKotlinAbi                      # the public API against its committed dumps
```

The device tests open the default output and play a short quiet tone. They exist because the one thing
a mock cannot confirm is that the engine's clock and the audio device share a time base.

## License

Apache-2.0. See [NOTICE](NOTICE).

Decoding is done by [KiteCodec](https://github.com/yuroyami/KiteCodec), which binds FFmpeg's libav\*
libraries. The FFmpeg build you link carries its own license, and that license decides whether you may
ship your binary. KiteCodec's Gradle plugin makes that choice explicit and fails the build if it is
left unset.

Part of the Kite family: [KiteCodec](https://github.com/yuroyami/KiteCodec),
[KiteCore](https://github.com/yuroyami/KiteCore), [KitePDF](https://github.com/yuroyami/KitePDF),
[KiteImage](https://github.com/yuroyami/KiteImage), [KiteQR](https://github.com/yuroyami/KiteQR).
