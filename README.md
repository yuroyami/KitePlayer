# KitePlayer

A media player engine for Kotlin Multiplatform. The player, its session loop, the clock, audio and video
synchronisation, the packet and frame queues, the buffering policy and the seek machine are pure Kotlin
in `commonMain`. Only the audio device, the video surface and the decoder are per platform.

[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.10-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue)](LICENSE)

> **KitePlayer is early and cannot be used as a dependency.** Nothing is published to any repository,
> there is no install path, and macOS arm64 is the only target with backends. This file states what has
> been measured and nothing beyond it.

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

## What is proven

Measured on one Apple silicon development machine, with a debug binary, on clips
`scripts/testmedia.sh` generates. This is development evidence: enough to say the engine works, not
enough to call any platform supported.

- 1080p30 for 10 seconds: 300 frames decoded, 300 submitted to the renderer, 0 dropped, 0 repeated,
  0 audio underruns, and a final audio to video drift of 1 ms.
- Real variable frame rate 720p for 8 seconds, frame durations cycling through 16.7, 33.3, 50, 25 and
  41.7 ms: 240 frames decoded, 240 submitted, 0 dropped, 0 repeated, 0 audio underruns.
- 4K HEVC 10-bit with no audio track: 180 frames, with video driving the clock.
- MPEG-TS with a 1400 second start offset: 300 frames decoded, 300 submitted, 0 dropped, 0 repeated.
- Seeking in real media: 20 random precise seeks in a 1080p30 file each land within one frame duration
  of the position asked for, a seek past the end finishes the file, and a seek to 0 mid-playback carries
  on from the start. A mid-playback seek to 5 seconds lands on 5.000 and plays through to the end with
  nothing dropped.
- 3 minutes of audio: 0 ms of clock drift.
- Colour: BT.709, BT.601, yuv420p10le, SMPTE 240M, centre-sited NV12 and P010 clips decoded,
  converted, and compared pixel by pixel against what the `ffmpeg` command line produces. Mean
  component error under 2 of 255, and measured between 0.14 and 0.62 on the six.
- Audio: a 5.1 file downmixed to stereo and compared sample by sample against `ffmpeg -ac 2` on the
  same file. Mean sample error under 0.0001, so the mix matrix and the channel order are the same
  ones FFmpeg applies.
- One hundred seeded simulated sessions, each with faults injected on purpose, hold every invariant:
  nothing from a superseded seek is ever shown or heard, timestamps never go backwards, every frame and
  packet is closed exactly once, every command completes exactly once, and every session reaches a
  terminal state. Twenty seeks in one virtual millisecond cost exactly one flush cycle.
- 409 test executions pass across 5 suites, with nothing skipped: 178 engine tests on the JVM in
  under a second, 179 compiled for macOS arm64, 18 against the real audio device and the renderer,
  26 that decode and seek in real media, and 8 for the SubRip parser.

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
  a cue, and the player never reads a subtitle track.
- **No hardware decode and no GPU renderer.** Frames are converted on the CPU and drawn through Core
  Graphics, which costs milliseconds per frame at 1080p.
- **No rotation.** A video that carries 90 degrees of rotation metadata draws sideways.
- **No network path, no live or adaptive streaming, no DRM.**
- **Nothing is published.** Building this needs a local KiteCodec publication and an FFmpeg on the
  machine, both set up by hand.

The public API says the same thing about itself: a configuration member that nothing implements carries
a marker in its own documentation, pointing at where it is planned. The plan is `KPKMP.md` in this
repository.

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

The sample creates a player, hands it the two backends this platform has, opens a file and plays it.
That is the whole playback path: the demux pump, both decoders, the audio feeder, the presentation
schedule and the seek machine are the player's own. It prints the position, the video drift against the
master clock and the frame accounting as it goes, all read from the player's own flows. Without
`--window` the frames go to a counting renderer that records how far each one landed from its requested
time, `--no-video` plays the audio track only, and `--seek=<seconds>` seeks once playback is under way
and reports where it landed.

```kotlin
val player = KitePlayer.create(
    PlayerConfig(
        backends = Backends(
            backend = KiteCodecMediaBackend(),
            output = AppleOutputBackend,
        ),
    ),
)
player.open(MediaItem(path))
player.play()
player.seek(5.seconds)
```

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

Three clips, BT.709, BT.601 and 10-bit, are decoded through the engine, converted by the software path,
and compared per pixel against the `ffmpeg` command line's output. The mean component error is under 2
units of 255.

High dynamic range is the current hole in this: PQ and HLG clips are converted with the matrix alone,
with no tone mapping, so they play and they look wrong. BT.2020 constant luminance is the same case.
Both now say so, once per stream, through a typed playback warning rather than silently.

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
| `kiteplayer-output` | the CoreAudio sink, the AppKit window and the Core Graphics renderer | macOS arm64 |
| `kiteplayer-ffmpeg` | the source and the decoders over KiteCodec, and the CPU colour conversion | macOS arm64 |
| `kiteplayer-subtitles` | SubRip parsing, with rasterisation left to the platform. Not connected to playback | every target it declares |
| `kiteplayer-sample` | a CLI that creates a player, plays a file and reports what the player says happened | macOS arm64 |

The dependency arrow never points into `kiteplayer-core`. The core declares interfaces and the backends
implement them, which is what allows a completely different backend, for example WebCodecs in a
browser, without the engine noticing.

## Build and test it here

```bash
./gradlew :kiteplayer-core:jvmTest            # 178 tests, the engine in virtual time
./gradlew :kiteplayer-core:macosArm64Test     # 179: the same, plus a real-thread stress test
./gradlew :kiteplayer-output:macosArm64Test   # 18 tests against the real audio device
./gradlew :kiteplayer-ffmpeg:macosArm64Test   # 26: real decode, real seeking, colour against a reference
./gradlew :kiteplayer-subtitles:jvmTest       # 8 tests, the SubRip parser
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
