# KitePlayer

A media player engine for Kotlin Multiplatform. The clock, audio and video synchronisation, queueing,
seeking and subtitle timing are pure Kotlin in `commonMain`. Only the audio device, the GPU surface and
the hardware decoder are per platform.

[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.10-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue)](LICENSE)

**[The build plan](KITEPLAYER.md)** · the full design, and **[Progress](PROGRESS.md)** · what is built
today.

> **KitePlayer is early and cannot be consumed as a dependency.** Nothing is published, and macOS arm64
> is the only target with backends. [Progress](PROGRESS.md) is the honest list, and it is the first thing
> to read.

## What works today

Video and audio, decoded and kept in sync, verified rather than asserted:

```bash
# In the KiteCodec checkout first, because KiteCodec is not published yet.
./gradlew publishToMavenLocal -Pkitecodec.hostTargetsOnly=true

# Then here.
./gradlew :kiteplayer-sample:linkDebugExecutableMacosArm64
./scripts/testmedia.sh
./kiteplayer-sample/build/bin/macosArm64/debugExecutable/kiteplayer.kexe testmedia/sync1080p30.mp4
```

That demuxes and decodes both streams as independent stages, plays the audio through CoreAudio, and
schedules each video frame against the audio clock. On a 10 second 1080p30 clip: 300 frames decoded,
300 presented, none dropped, none repeated, no audio underruns, and audio to video drift steady at
20 ms. Over three minutes of audio the clock drifts by 0 ms.

What is missing is a window. Frames are scheduled and handed over at the right time, which the sample
measures, but the renderer supplied counts frames instead of drawing them. A Metal renderer is the next
platform piece. There is also no hardware decode and no target other than macOS arm64 yet.
[Progress](PROGRESS.md) says why for each.

## Why the position is exact

Most players work out the playback position by counting the samples they have handed to the audio
device and subtracting an estimate of how much the device is still holding. The estimate is per
platform and usually wrong, so the picture sits at a fixed offset from the sound and nothing ever
corrects it.

KitePlayer does not estimate. The device is asked when the buffer it is filling will be heard, and the
clock is anchored to that instant:

```kotlin
public fun interface AudioRenderCallback {
    /** [deadlineNanos] is when the LAST frame of this buffer becomes audible. */
    public fun onRender(destination: AudioSinkBuffer, frames: Int, deadlineNanos: Long): Int
}
```

Every sink declares how much its answer can be trusted, through `LatencyQuality`, so a platform that
cannot measure honestly says so and the engine widens its tolerances instead of believing a wrong
number.

## Why the colours are right

A renderer that ignores a frame's colour metadata produces a picture that is present and wrong, which
is worse than one that is absent: hues shift, or black turns grey, and nobody notices until they compare
against another player. So the metadata travels with the frame, and correctness is checked against
FFmpeg's own output rather than by eye.

```kotlin
public data class ColorSpaceInfo(
    val matrix: ColorMatrix,          // BT.601 and BT.709 differ enough to shift every hue
    val primaries: ColorPrimaries,
    val transfer: ColorTransfer,
    val fullRange: Boolean,           // studio range is 16 to 235, and most video uses it
    val chromaLocation: ChromaLocation,
)
```

Three clips, BT.709, BT.601 and 10-bit, are decoded through the engine, converted by the software path,
and compared per pixel against what the `ffmpeg` command line produces. The mean component error is
under 2 units of 255. That test found a real defect as it was written, and no amount of watching
playback would have.

## Why the engine has no platform code

`kiteplayer-core` depends on kotlinx-coroutines and atomicfu, and nothing else. It contains no
`expect` declaration and no platform API call. Three things follow:

1. It compiles for every target Kotlin supports, today, including `js` and `wasmJs`.
2. Its whole behaviour is testable with a clock the test controls. The 75 engine tests run in
   milliseconds, and they run identically on the JVM and on Kotlin/Native.
3. A new platform is reached by implementing four interfaces, not by adding an `actual` to the engine.

Time enters through one interface, which is what makes the second point possible:

```kotlin
public interface MonotonicClock {
    /** Nanoseconds from an arbitrary fixed origin. Must never go backwards. */
    public fun nanos(): Long
}
```

## Modules

| Module | Holds | Targets |
|---|---|---|
| `kiteplayer-core` | the engine: clock, synchronisation, queues, buffering, seek state machine, the public API and the four service interfaces | every target Kotlin supports |
| `kiteplayer-output` | audio sinks and video renderers | macOS arm64 today |
| `kiteplayer-ffmpeg` | the source and decoders over KiteCodec, and the software colour conversion | macOS arm64 today |
| `kiteplayer-subtitles` | subtitle parsing and layout, with rasterisation left to the platform | every target |
| `kiteplayer-sample` | a CLI that plays a file's audio | macOS arm64 |

The dependency arrow never points into `kiteplayer-core`. The core declares interfaces and the
backends implement them, which is what allows a completely different backend, for example WebCodecs in
a browser, without the engine noticing.

## Build and test it here

```bash
./gradlew :kiteplayer-core:jvmTest            # 75 tests, the engine in virtual time
./gradlew :kiteplayer-core:macosArm64Test     # the same 75, compiled natively
./gradlew :kiteplayer-output:macosArm64Test   # 7 tests against the real audio device
./gradlew :kiteplayer-ffmpeg:macosArm64Test   # 6 tests: real decode, colour against FFmpeg
```

The device tests open the default output and play a short quiet tone. They exist because the one thing
a mock cannot confirm is that the engine's clock and the audio device share a time base.

`scripts/testmedia.sh` regenerates the test clips with the `ffmpeg` CLI, so no media is committed.

## License

Apache-2.0. See [NOTICE](NOTICE).

Decoding is done by [KiteCodec](https://github.com/yuroyami/KiteCodec), which binds FFmpeg's libav\*
libraries. The FFmpeg build you link carries its own license, and that license decides whether you may
ship your binary. KiteCodec's Gradle plugin makes that choice explicit and fails the build if it is
left unset.

Part of the Kite family: [KiteCodec](https://github.com/yuroyami/KiteCodec),
[KiteCore](https://github.com/yuroyami/KiteCore), [KitePDF](https://github.com/yuroyami/KitePDF),
[KiteImage](https://github.com/yuroyami/KiteImage), [KiteQR](https://github.com/yuroyami/KiteQR).
