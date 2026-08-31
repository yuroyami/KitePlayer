# KitePlayer

A media player for Kotlin Multiplatform, written in Kotlin from the ground up.

It does not wrap ExoPlayer, AVPlayer or libmpv. The playback engine is pure Kotlin in `commonMain`,
so it behaves the same wherever it runs: the same seek logic, the same A/V sync, the same state
machine on every platform. Only three things are platform-specific: the audio device, the video
surface, and the decoder.

[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.10-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue)](LICENSE)

> **This is early software.** It plays real media on macOS, Android and iOS, and it is used in a
> shipping app. What it does not have is broad device qualification, a performance budget, or a
> stable API. Read [What is missing](#what-is-missing) before you plan around it.

## Install

```kotlin
commonMain.dependencies {
    implementation("io.github.yuroyami:kiteplayer-mobile:0.0.21")
}
```

That gives you the default stack on Android and iOS: player, decoders, audio and the native view.
[Other modules](#modules) let you pick a different rendering model, or take the engine alone.

## Playing a file

```kotlin
val player = KitePlayer.create(
    PlayerConfig(
        backends = Backends(
            backend = KiteFFmpegMediaBackend(),  // reads and decodes, over KiteFFmpeg
            output  = AppleOutputBackend,        // the clock and the audio device, paired so they cannot mismatch
        ),
    ),
)

player.attachRenderer(AppKitVideoRenderer(window) { SoftwareConverter.toRgba(it as KiteFFmpegVideoFrame) })
player.open(MediaItem(path))   // suspends, returns paused with the first frame drawn
player.play()
player.seek(5.seconds)         // suspends, completes on the position it landed on
player.closeAndAwait()         // suspends until teardown is final
```

That is the whole playback path. Everything else, meaning the demux pump, both decoders, the audio
feeder, the presentation schedule, the seek machine and the state it publishes, belongs to the
player.

On Android and iOS, `kiteplayer-mobile` builds those backends for you with `mobileBackends()`.

Backends are named rather than discovered. Kotlin/Native has no classpath service lookup, so the
alternative would be a reflective search that fails differently on every platform. Naming them
makes a missing backend a typed configuration error instead of a surprise at the first frame.

## What you can control

Everything below is live: call it during playback and it takes effect. Everything is published on
the state snapshot, so your UI can read it back.

| | |
|---|---|
| **Playback** | `open`, `play`, `pause`, `stop`, `seek`, `stepFrame`, `close` / `closeAndAwait` |
| **Playlists** | `openQueue` with `next` and `previous`, `setLoop` (looping one item or wrapping the whole queue) |
| **Speed** | `setSpeed` from 0.25x to 4x with the pitch preserved, or `setPreservePitch(false)` to let it change like a tape |
| **Sound** | `setVolume`, `setMuted`, `setAudioDelay` |
| **Picture** | `setVideoScale` (fit, fill, stretch), `setVideoAdjustments` (brightness, contrast, saturation, hue), `setVideoTransform` (forced aspect, zoom, pan) |
| **Subtitles** | `selectTrack`, `addExternalSubtitle` (load a `.srt` or `.vtt` mid-playback), `setSubtitleScale`, `setSubtitleDelay`, `setSubtitlePosition` |
| **Looping a section** | `setAbLoop`, which repeats between two points and wraps B back to A |
| **Chapters** | `chapterAt`, `seekToChapter` |
| **Screenshots** | `captureFrame` |
| **Rendering** | `attachRenderer`, `detachRenderer`, swappable while media is playing |
| **Diagnosis** | `diagnosticsDump`, `warningHistory`, `supportBundle`, and `KiteLog` as the one logging seam (silent by default) |

Four `Flow`s publish what is happening: `state`, `progress`, `stats` and `events`. `position()`
reads the current time without collecting anything.

Two seek modes. The default is exact. `SeekMode.KeyframeThenRefine` is genuinely two-phase: the
nearest keyframe appears immediately, then the exact frame replaces it, which is what makes
scrubbing feel responsive on large files.

Set up at open time through `MediaItem`: `videoFilter` for an FFmpeg filter chain,
`startPosition` to begin partway in without showing the beginning, `externalSubtitles`, `headers`
for HTTP requests, and `formatHint` when a container needs naming.

Anything the player cannot honour is refused with a typed error rather than accepted and ignored.

## Where it runs

| | |
|---|---|
| **Plays real media** | macOS arm64, Android (device and emulator), iOS (device and simulator), desktop JVM on macOS arm64, Linux x64 and arm64 |
| **Builds and links, nothing has run** | Windows x64 |
| **Compiles only** | iOS x64, tvOS, watchOS, Android native, `js`, `wasmJs`. No playback path exists |

Android and iOS are the platforms in daily use: KitePlayer is the engine inside a shipping app, and
device work goes down to per-frame GPU timings on real handsets. macOS arm64 is the development
machine and has the deepest automated coverage, including a colour instrument that proves BT.601,
BT.709 and BT.2020 within 2/255 against an independent reference.

Linux plays through Kotlin/Native but has **no audio device sink yet**, so nothing comes out of the
speakers. Windows cross-compiles and links a complete binary; nobody has run it.

Two honest limits on all of it: there is no automated device farm, so device evidence is
hand-verified rather than green on every push, and no platform has a performance budget or a
documented OS support range.

## What is missing

- **Adaptive streaming.** No HLS, no DASH ABR, no caching layer. HTTP and HTTPS playback of a
  single file works; `kiteplayer-network` is written but unpublished while its API settles.
- **Audio resampling is interim quality.** Rate conversion is linear interpolation, which dulls the
  top end of music. libswresample replaces it before 1.0. The downmix has no normalisation either,
  so a source loud in several channels at once can clip.
- **Linux audio output.** The engine plays; there is no ALSA sink.
- **A stable API.** 0.0.x. Public declarations are explicit and checked against committed ABI dumps
  by `checkKotlinAbi`, so a change fails a build here rather than surprising you. That is
  visibility, not a promise.
- **libass subtitles.** `kiteplayer-libass` gives typesetting-grade ASS rendering and is built for
  seven target families, but it needs a native chain a consumer cannot easily obtain, so it stays
  unpublished. The built-in SubStation Alpha support covers dialogue-grade styling.
- **AV1 on the web.** Every native target cross-builds dav1d 1.5.4 with full SIMD, and hardware AV1
  is used where it exists. The wasm build is single-threaded and dav1d requires pthreads, so the
  web has no software AV1.

## Modules

Take the whole default stack, or one piece.

| Module | What it is for |
|---|---|
| `kiteplayer-mobile` | **Start here on Android and iOS.** The default stack: player, decoders, audio, native view, all assembled. `mobileBackends()` builds it. |
| `kiteplayer-compose-interop` | Compose hosting the *native* view through `AndroidView` or `UIKitView`. Keeps the fast native surface path. |
| `kiteplayer-compose-video` | Compose drawing the video pixels *itself*, so Compose clip, alpha, transforms and effects apply to them. |
| `kiteplayer-compose-ui` | Both of the above, chosen at runtime. `KitePlayerVideo(path = ...)` can swap rendering model while media plays. |
| `kiteplayer-view` | The native widgets alone, no Compose: `KitePlayerView` (Android, XML or code) and `KitePlayerUIView` (UIKit). |
| `kiteplayer-core` | The engine on its own: player, clock, sync, queues, seek machine, and the interfaces a backend implements. |
| `kiteplayer-ffmpeg` | The source and decoders over KiteFFmpeg, plus CPU colour conversion. |
| `kiteplayer-output` | Audio sinks and renderers that talk to an operating system. |
| `kiteplayer-subtitles` | SubRip, WebVTT and SubStation Alpha parsers, in pure common Kotlin. |
| `kiteplayer-rt` | The real-time audio core in C: the lock-free ring and the device render callback. |

Every consumable module publishes a JVM variant so a `commonMain` can depend on it even when the
consumer also builds a desktop target. On JVM, JS and Wasm those variants are honest placeholders:
`KitePlayerPlatform.isAvailable` is false and `createOrNull()` returns null, rather than failing at
the first frame.

`kiteplayer-phone` and `kiteplayer-compose` are deprecated 0.0.2 umbrellas. New code should not use
them.

**The dependency arrow never points into `kiteplayer-core`.** The core declares interfaces and
backends implement them, which is what would let a completely different backend, WebCodecs in a
browser for instance, work without the engine noticing.

## Why the engine has no platform code

`kiteplayer-core` depends on kotlinx-coroutines and atomicfu, and nothing else. It calls no
platform API and holds exactly one `expect` declaration: an internal one asking each target for the
threads the player confines its workers to, because a single-thread dispatcher is not something a
target-free source set can build.

Three things follow:

1. It compiles for all 21 targets its build file declares.
2. Its whole behaviour is testable against a clock the test controls, so the engine suite runs in
   milliseconds and runs identically on JVM and Kotlin/Native.
3. A new platform is reached by implementing four interfaces, not by adding an `actual` to the
   engine.

Time enters through one interface, which is what makes the second point possible:

```kotlin
public interface MonotonicClock {
    /** Nanoseconds since an arbitrary fixed origin. Must never go backwards. */
    public fun nanos(): Long
}
```

## Run it here

A development loop, not an installation.

```bash
# Generate test clips. Needs ffmpeg on PATH; no media is committed to this repository.
./scripts/testmedia.sh

# Build the macOS sample and play a clip in a window.
./gradlew :kiteplayer-sample:linkDebugExecutableMacosArm64
BIN=kiteplayer-sample/build/bin/macosArm64/debugExecutable/kiteplayer.kexe
$BIN testmedia/sync1080p30.mp4 --window

# Build the iOS simulator framework, then open the sample app in Xcode.
./gradlew :kiteplayer-sample:linkDebugFrameworkIosSimulatorArm64
open kiteplayer-sample/iosApp/KitePlayerSample.xcodeproj
```

The macOS sample prints position, drift against the master clock, and frame accounting from the
player's own flows. Without `--window` frames go to a counting renderer; `--no-video` plays audio
only; `--seek=<seconds>` seeks once playback is under way.

## Build and test it here

```bash
./gradlew :kiteplayer-core:jvmTest            # the engine, in virtual time
./gradlew :kiteplayer-core:macosArm64Test     # plus platform and real-thread cases
./gradlew :kiteplayer-output:macosArm64Test   # the real audio device and the renderer
./gradlew :kiteplayer-ffmpeg:macosArm64Test   # real decode, real seeking, colour against a reference
./gradlew :kiteplayer-subtitles:jvmTest       # the subtitle parsers
./gradlew :kiteplayer-rt:macosArm64Test       # the C audio ring
./gradlew checkKotlinAbi                      # the public API against its committed dumps
```

The C audio ring has its own build and tests outside Gradle:

```bash
cd kiteplayer-rt/native
./scripts/build-host.sh plain && ./scripts/run-c-tests.sh plain   # also asan, tsan
./scripts/render-audit.sh          # what the real-time render unit is allowed to call
```

## License

Apache-2.0. See [NOTICE](NOTICE).

Decoding is done by [KiteFFmpeg](https://github.com/yuroyami/KiteFFmpeg), which compiles FFmpeg into
its own artifacts under the LGPL. There is no GPL build and nothing to configure. Shipping LGPL code
obliges you to say your app uses FFmpeg and to keep its source available; KiteFFmpeg's `NOTICE`
states this precisely.

Part of the Kite family: [KiteFFmpeg](https://github.com/yuroyami/KiteFFmpeg),
[KiteCore](https://github.com/yuroyami/KiteCore), [KitePDF](https://github.com/yuroyami/KitePDF),
[KiteImage](https://github.com/yuroyami/KiteImage), [KiteQR](https://github.com/yuroyami/KiteQR).
