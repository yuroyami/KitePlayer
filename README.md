# KitePlayer

A media player engine for Kotlin Multiplatform. The clock, audio and video synchronisation, queueing,
seeking and subtitle timing are pure Kotlin in `commonMain`. Only the audio device, the GPU surface and
the hardware decoder are per platform.

[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.10-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue)](LICENSE)

**[The build plan](KITEPLAYER.md)** · the full design, and **[Progress](PROGRESS.md)** · what is built
today.

> **KitePlayer is early and cannot be consumed as a dependency.** Nothing is published. Today it plays
> a file's audio on macOS arm64 and nothing else. [Progress](PROGRESS.md) is the honest list, and it is
> the first thing to read.

## What works today

One thing, and it is verified rather than asserted:

```bash
# In the KiteCodec checkout first, because KiteCodec is not published yet.
./gradlew publishToMavenLocal -Pkitecodec.hostTargetsOnly=true

# Then here.
./gradlew :kiteplayer-sample:linkDebugExecutableMacosArm64
./scripts/testmedia.sh
./kiteplayer-sample/build/bin/macosArm64/debugExecutable/kiteplayer.kexe testmedia/sync1080p30.mp4
```

That decodes the file's AAC track through KiteCodec, plays it through CoreAudio, and prints the
position taken from the audio clock. Over three minutes of continuous playback it drifts by 0 ms and
underruns 0 times.

There is no video, no seeking, and no target other than macOS arm64 yet. [Progress](PROGRESS.md) says
why for each.

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
| `kiteplayer-ffmpeg` | the source and decoders, over KiteCodec | macOS arm64 today |
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
