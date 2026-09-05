# KitePlayer

A media player for Kotlin Multiplatform, written in Kotlin from the ground up.

It does not wrap ExoPlayer, AVPlayer or libmpv. The playback engine is pure Kotlin in `commonMain`,
so it behaves the same wherever it runs: the same seek logic, the same A/V sync, the same state
machine on every platform. Platform code supplies the audio devices, video surfaces, decoders
and optional network transport.

[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.10-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue)](LICENSE)

> **This is early software.** It plays real media on macOS, Android and iOS, and it is used in a
> shipping app. What it does not have is broad device qualification, a performance budget, or a
> stable API. Read [What is missing](#what-is-missing) before you plan around it.

## Install

This branch prepares **0.0.23**. It has not been published yet; Maven Central currently serves
0.0.22. The declarations below describe the prepared 0.0.23 module contract.

Choose one entry point under `io.github.yuroyami`. KMP applications put it in
`commonMain.dependencies`; Android-only applications use their normal `dependencies` block.

```kotlin
// Complete Compose setup: player, codecs, audio, HTTP/HTTPS and both video rendering paths.
implementation("io.github.yuroyami:kiteplayer-compose:0.0.23")
```

For native views without Compose, choose one of these alternatives:

```kotlin
// General default playback stack, including native views and HTTP/HTTPS.
implementation("io.github.yuroyami:kiteplayer:0.0.23")
```

```kotlin
// Mobile convenience entry point using that same stack.
implementation("io.github.yuroyami:kiteplayer-mobile:0.0.23")
```

A Compose application can also use `KitePlayerView` in Android XML. A Compose-only application
can use the native-surface path through `AndroidView` without writing XML. Both paths remain
available through `KitePlayerVideo(path = ...)`; choosing an entry point does not force a renderer.
Gradle selects the platform artifacts needed by the application.

Advanced consumers can choose `kiteplayer-core` and supply backends, or combine `kiteplayer`
with `kiteplayer-compose-ui` to declare playback and presentation separately. The UI-only module
includes both renderers and their required frame adapters, but does not include the default player
factory or HTTP transport. Core stays transitively available because its types occur in renderer APIs.

See [Modules](#modules) for the components and [the migration notes](CHANGELOG.md#0023---unreleased)
when upgrading. The published API reference is at https://yuroyami.github.io/KitePlayer/.

## Playing a file

The complete entry points provide the default factory:

```kotlin
val player = requireNotNull(KitePlayerPlatform.createOrNull()) {
    "KitePlayer is unavailable: ${KitePlayerPlatform.availability}"
}
```

In a Compose screen, display that player and open media once its renderer is attached. Opening
and closing are suspend operations and belong to the screen's playback owner, not to recomposition:

```kotlin
KitePlayerVideo(
    player = player,
    onRendererAttached = { attached -> /* Signal the owner that media can now be opened. */ },
)

// In the playback owner's coroutine, after the first attachment:
player.open(MediaItem(pathOrUrl))
player.play()
player.seek(5.seconds)
// When the owner is finished:
player.closeAndAwait()
```

For a native view, assign the player and install the provided view renderer binding before opening
media. The Android binding is `view.installMobileRenderer()`; the view itself can come from XML
or Kotlin. The same public binding names remain available after moving the implementation out of
`kiteplayer-mobile`.

Custom assemblies use `KitePlayer.create(PlayerConfig(backends = Backends(backend, output)))`.
Backend and output selection stay explicit there. Optional transport discovery applies to both
that factory and the default factory.

## Network transport

The standard playback entry points include `kiteplayer-network`. Adding the network module to a
custom core assembly enables HTTP/HTTPS automatically; callers do not need to construct a Ktor
resolver. Android/JVM use the platform trust stack through OkHttp, Apple uses NSURLSession,
and the browser handles web transport. The Android network artifact supplies the normal internet
permission through manifest merging. The embedded FFmpeg binary itself has no TLS backend.

The item's own `io` source takes precedence, then an explicitly configured `NetworkConfig.ioResolver`,
then installed providers when `NetworkConfig.autoResolve` is true. An explicit resolver returning
null leaves the URI to the backend; it does not activate an automatic replacement. Set
`autoResolve = false` to disable discovery. Per-item headers reach the selected transport.

Automatic HTTP readers own and close their clients. No client is created merely by constructing a
player or opening a local file. Custom resolvers retain their documented ownership rules.
Provider discovery uses platform registration rather than a single portable reflection mechanism;
the pinned Kotlin toolchain and optimized consumer checks are part of that contract.

## What you can control

Everything below is live: call it during playback and it takes effect. Everything is published on
the state snapshot, so your UI can read it back.

| | |
|---|---|
| **Playback** | `open`, `play`, `pause`, `stop`, `seek`, `stepFrame`, `close` / `closeAndAwait` |
| **Playlists** | `openQueue` with `next` and `previous`, `setLoop` (looping one item or wrapping the whole queue), and `addToQueue`, `removeFromQueue`, `moveInQueue`, `clearQueue` to edit it while it plays |
| **Shuffle** | `setShuffle`. The items in the queue never move, so the list you show stays the list your user built. What changes is `queueOrder`, which is published so you can show what is coming next |
| **Speed** | `setSpeed` from 0.25x to 4x with the pitch preserved, or `setPreservePitch(false)` to let it change like a tape |
| **Sound** | `setVolume`, `setMuted`, `setBalance`, `setEqualizer` (ten bands and a preamp), `setAudioDelay`, `setSleepTimer` (with a fade), and `setVideoEnabled(false)` to keep only the audio without reopening anything |
| **Volume above 100%** | Raise `PlayerConfig.audio.volumeCeiling` to as much as 2. Past unity every sample is folded through a saturator, so a loud passage compresses instead of squaring off. At or below unity nothing is folded and the samples are untouched, bit for bit |
| **Loudness** | ReplayGain from the container's own tags, off by default because a player changing the level unasked is a surprise. `PlayerConfig.audio.replayGain` turns it on |
| **Picture** | `setVideoScale` (fit, fill, stretch), `setVideoAdjustments` (brightness, contrast, saturation, hue), `setVideoTransform` (forced aspect, zoom, pan) |
| **Subtitles** | `selectTrack`, `addExternalSubtitle` (load a `.srt` or `.vtt` mid-playback), `setSubtitleScale`, `setSubtitleDelay`, `setSubtitlePosition`, and `subtitleCues` to read the lines showing right now and draw them yourself |
| **Looping a section** | `setAbLoop`, which repeats between two points and wraps B back to A |
| **Chapters** | `chapterAt`, `seekToChapter` |
| **Screenshots** | `captureFrame` |
| **Rendering** | `attachRenderer`, `detachRenderer`, swappable while media is playing |
| **Diagnosis** | `diagnosticsDump`, `warningHistory`, `supportBundle`, and `KiteLog` as the one logging seam (silent by default). `KiteLog.installStructured` gives you fields instead of a sentence, and URIs are stripped of their query strings before they reach any sink |

Five `Flow`s publish what is happening: `state`, `progress`, `stats`, `events` and
`subtitleCues`. `position()` reads the current time without collecting anything.

Two seek modes. The default is exact. `SeekMode.KeyframeThenRefine` is genuinely two-phase: the
nearest keyframe appears immediately, then the exact frame replaces it, which is what makes
scrubbing feel responsive on large files.

Set up at open time through `MediaItem`: `videoFilter` for an FFmpeg filter chain,
`startPosition` to begin partway in without showing the beginning, `externalSubtitles`, `headers`
for HTTP requests, and `formatHint` when a container needs naming.

Anything the player cannot honour is refused with a typed error rather than accepted and ignored.

More than one player per process is supported and tested: two players play two files at once,
and closing one leaves the other running.

## Where it runs

| | |
|---|---|
| **Plays real media** | macOS arm64, Android (device and emulator), iOS (device and simulator), desktop JVM on macOS arm64, Linux x64 and arm64 |
| **Builds and links, nothing has run** | Windows x64 |
| **Web implementation** | `wasmJs`: FFmpeg, browser audio and canvas video. Load the codec module before creating a player; browser qualification remains limited. |
| **Compiles only** | iOS x64, tvOS, watchOS, Android native and `js`. These variants have no default playback assembly. |

Android and iOS are the platforms in daily use: KitePlayer is the engine inside a shipping app, and
device work goes down to per-frame GPU timings on real handsets. macOS arm64 is the development
machine and has the deepest automated coverage, including a colour instrument that proves BT.601,
BT.709 and BT.2020 within 2/255 against an independent reference.

Linux plays through Kotlin/Native but has **no audio device sink yet**, so nothing comes out of the
speakers. Windows cross-compiles and links a complete binary; nobody has run it.
The pinned KiteFFmpeg 0.2.0 JVM artifact bundles only macOS arm64 JNI. Other desktop JVM
platforms need a separately supplied native library; the umbrella cannot fill that packaging gap.

Two honest limits on all of it: there is no automated device farm, so device evidence is
hand-verified rather than green on every push, and no platform has a performance budget or a
documented OS support range.

Which clip plays where is measured rather than claimed: every CI run of the format matrix writes a
conformance table, uploaded as the `conformance-macos-host` artifact and printed in the run's
summary. It lists each clip, what was asked of it, and what happened.

## What is missing

- **Adaptive streaming.** HTTP/HTTPS single-file transport and an in-memory byte cache are part
  of the default stack. Complete HLS/DASH adaptive playback and persistent caching are not.
- **Audio resampling is interim quality.** Rate conversion is linear interpolation, which dulls the
  top end of music. libswresample replaces it before 1.0. The downmix has no normalisation either,
  so a source loud in several channels at once can clip.
- **Linux audio output.** The engine plays; there is no ALSA sink.
- **A stable API.** 0.0.x. Public declarations are explicit and checked against committed ABI dumps
  by `checkKotlinAbi`, so a change fails a build here rather than surprising you. That is
  visibility, not a promise.
- **libass subtitles.** The standalone ASS renderer exists, but normal playback does not call it.
  Native packaging is incomplete, desktop JVM and web bindings are missing, and the module stays
  unpublished. The built-in ASS parser supports dialogue styling, not full animated typesetting.
- **AV1 on the web.** Every native target cross-builds dav1d 1.5.4 with full SIMD, and hardware AV1
  is used where it exists. The wasm build is single-threaded and dav1d requires pthreads, so the
  web has no software AV1.
- **Everything else that is left** is in [GitHub Issues](https://github.com/yuroyami/KitePlayer/issues),
  grouped by plan labels. How the project works and how to run its gate: [CONTRIBUTING.md](CONTRIBUTING.md).

## Modules

| Module | What it supplies |
|---|---|
| `kiteplayer` | Default construction, core, FFmpeg, output, native view bindings and network. |
| `kiteplayer-mobile` | Convenience/compatibility alias for that assembly. |
| `kiteplayer-compose` | Complete playback plus both Compose renderers and the runtime switcher. |
| `kiteplayer-compose-ui` | Both Compose renderers and switcher, accepting an existing player. |
| `kiteplayer-compose-interop` | Compose hosting a native video view. |
| `kiteplayer-compose-video` | Video pixels drawn through Compose, including its FFmpeg frame adapters. |
| `kiteplayer-view` | Native view widgets and binding contracts, usable without Compose. |
| `kiteplayer-view-bindings` | FFmpeg/platform adapters for those widgets, without a player factory or networking. |
| `kiteplayer-core` | Engine, clock, synchronization, selection and service contracts. |
| `kiteplayer-ffmpeg` | Media source and decoders over KiteFFmpeg; includes the Kotlin subtitle parsers. |
| `kiteplayer-network` | HTTP/HTTPS byte transport, automatic provider registration and range-based reads. |
| `kiteplayer-output` | Platform audio output, rendering support and subtitle rasterizers. |
| `kiteplayer-subtitles` | Kotlin SRT, WebVTT and dialogue-level ASS parsers. |
| `kiteplayer-rt` | The C real-time audio ring and device callback support. |

`kiteplayer-phone` remains a deprecated compatibility coordinate. `kiteplayer-compose` is the
recommended complete Compose entry point again in 0.0.23, rather than a deprecated alias.

Compose presentation targets Android, iOS arm64/simulator arm64 and desktop JVM. The general
assembly also retains its Wasm implementation and unavailable JavaScript facade. A resolving
variant does not by itself establish playback support. Kotlin/Native Linux and Windows have no
default HTTPS transport in this stack; desktop applications use the JVM target.

## How subtitles reach the picture

The FFmpeg backend extracts embedded text subtitle packets and includes `kiteplayer-subtitles`
for parsing, including external subtitle files. Core selects tracks and schedules the cues.
The output backend rasterizes text; the native or Compose presentation composites the resulting
transparent overlays. Applications using a standard entry point do not add the parser separately.

The optional libass work will supply typesetting-grade ASS images to that same overlay path.
It still needs persistent clock-driven playback integration, seeking, track/font handling and
self-contained artifacts. Adding its current unpublished module does not enable full ASS playback.
That integration is separate from 0.0.23.

## How the core stays independent

The core declares the interfaces implemented by codecs, output and optional transport providers.
It does not depend on FFmpeg, Ktor or Compose. Its target-specific pieces supply worker dispatchers
and transport discovery; playback decisions remain common Kotlin. Native builds also consume the
small real-time C ring module through the existing explicit boundary.

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
