<p align="center">
  <img src="art/final/kiteplayer-logo.svg" width="200" alt="KitePlayer logo">
</p>

<h1 align="center">KitePlayer</h1>

<p align="center">
  A media playback library for Kotlin Multiplatform apps. Its engine is written in Kotlin and plays
  video, audio and subtitles on Android, iOS, macOS, the desktop JVM and the web, with FFmpeg already
  inside the artifacts through <a href="https://github.com/yuroyami/KiteFFmpeg">KiteFFmpeg</a>. It
  takes mpv and VLC as its models, and aims for their performance and range of features.
</p>

<p align="center">
  <a href="https://central.sonatype.com/artifact/io.github.yuroyami/kiteplayer"><img src="https://img.shields.io/maven-central/v/io.github.yuroyami/kiteplayer?label=Maven%20Central" alt="Maven Central"></a>
  <a href="https://github.com/yuroyami/KitePlayer/actions/workflows/ci.yml"><img src="https://img.shields.io/github/actions/workflow/status/yuroyami/KitePlayer/ci.yml?label=CI" alt="CI"></a>
  <a href="https://kotlinlang.org"><img src="https://img.shields.io/badge/Kotlin-2.4.20-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin 2.4.20"></a>
  <a href="https://www.jetbrains.com/compose-multiplatform/"><img src="https://img.shields.io/badge/Compose%20Multiplatform-1.12-4285F4" alt="Compose Multiplatform 1.12"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache--2.0-blue" alt="License: Apache-2.0"></a>
</p>

<p align="center">
  <b><a href="docs/README.md">Guides</a></b> ·
  <a href="CHANGELOG.md">Changelog</a> ·
  <a href="CONTRIBUTING.md">Contributing</a>
</p>

## What it is

- **A library, not an app.** You add KitePlayer to your app with one Gradle line. Your code gets a
  player object, and your screen shows the video in a native view or a Compose composable. The
  sample apps in this repository only show how to use the library.
- **Its own engine, not a wrapper.** KitePlayer does not put a common API over ExoPlayer, AVPlayer,
  mpv or VLC. The seeking, the audio and video sync, the subtitle timing and the playback state are
  KitePlayer's own Kotlin code, so every platform behaves the same way.
- **FFmpeg inside.** FFmpeg reads and decodes the media, through
  [KiteFFmpeg](https://github.com/yuroyami/KiteFFmpeg). Its libraries come inside the artifacts
  that Gradle downloads.
- **Little from the platform.** Each platform supplies an audio output, a video surface and, where
  the device has one, a hardware video decoder.

## What you get

**Formats**

- Video: H.264, H.265 (HEVC) in 8 and 10 bit, AV1, VP9, MPEG-2, MPEG-4 Part 2 and the rest of
  what FFmpeg decodes.
- Audio: AAC, MP3, Opus, Vorbis, FLAC, ALAC, AC-3, E-AC-3, DTS, TrueHD and PCM.
- Containers: MKV, WebM, MP4, MOV, MPEG-TS, AVI, FLV, VOB, WMV, WAV and raw streams.
- Hardware decoding through MediaCodec on Android and VideoToolbox on Apple, with software decoding
  when the device cannot. AV1 decodes with dav1d where the device has no AV1 hardware.
- The web build carries a smaller set: H.264, HEVC, VP9, AAC, MP3, Opus, Vorbis, FLAC and PCM, in
  MP4, MOV, MKV, WebM, MP3 and FLAC files.

**Picture**

- True HDR: HDR10 and HLG show as HDR on a display that can show it, through Metal on a Mac or an
  iPhone and through `KitePlayerView` on an Android HDR display. Elsewhere they are tone mapped.
- Picture-in-picture on Android, iOS and macOS, and a floating window on the desktop JVM.
- Rotation and mirroring from the file, on every renderer.
- Fit, fill and stretch, zoom, pan and a forced aspect ratio. Brightness, contrast, saturation and
  hue.
- Dithering, debanding and a sharper scaler, on Apple's Metal renderer and Android's GPU renderer.
- A native view on each platform, or Compose with two paths: the platform's video view, or video
  drawn by Compose that takes clipping, alpha and shared element transitions.
- Frame stepping, screenshots to PNG or JPEG, and thumbnails.

**Sound**

- Speed from 0.25x to 4x with the pitch kept. A speed change while it plays makes no pause and no
  gap.
- A ten band equaliser with a preamp, balance, ReplayGain, volume up to twice the normal level
  through a limiter, audio delay, and a sleep timer that fades out.
- Surround folds into the speakers the device has, and mono or stereo can fill a surround device.
- A choice of output device on macOS and the desktop JVM.
- Waveforms, and an optional audio visualiser for media with no picture.

**Subtitles**

- ASS and SSA drawn by libass as authored: signs, karaoke, animated transforms and the fonts the
  file carries.
- SubRip and WebVTT, and Blu-ray (PGS), DVD, DVB and XSUB image subtitles.
- External subtitle files that load during playback, and a second subtitle track at the same time.
- Delay, scale, position, style and a safe area that keeps text out of cutouts and controls.

**Streaming and input**

- HTTP and HTTPS with your headers, through OkHttp on Android and the JVM and NSURLSession on
  Apple.
- HLS: master playlists, a choice of variant, automatic steps down and up with the network rate,
  MPEG-TS and fMP4 segments, AES-128, separate audio and subtitle renditions, and live playlists.
- DASH through the HLS path, for fMP4 and MPEG-TS segments: separate video, audio and WebVTT
  sets, seeking, a variant for each video representation, segment indexes of single files, and
  live manifests.
- Files, memory, bytes that your code pushes, streams, and Android content URIs and assets.
- Recording of what plays into a Matroska file, with no re-encode.

**Playback**

- A gapless queue, shuffle, loops, chapters, an A-B loop and position markers.
- Precise seeks, or the nearest keyframe at once and the exact frame a moment later for fast
  scrubbing.
- Resume: one call saves the item, the position, the tracks and the speed.
- An external clock that playback follows, for watching together.
- Typed errors and warnings, a diagnostics dump, and a trace that Perfetto opens.

**On the device**

- A media notification and lock screen controls on Android and iOS, and playback in the
  background.
- Screen reader labels on the views and both Compose paths.

Not every platform has every feature. [Where it runs](#where-it-runs) and [Limits](#limits) say
what is missing where.

This desktop JVM program plays a song, seeks, and closes the player:

```kotlin
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.MediaItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.seconds

fun main() = runBlocking {
    val player = KitePlayer()
    player.open(MediaItem("/path/to/song.mp3"))   // returns when the item is open and paused
    player.play()
    delay(10.seconds)
    player.seek(60.seconds)                       // returns when the seek has landed
    delay(10.seconds)
    player.closeAndAwait()
}
```

> [!NOTE]
> KitePlayer has not reached 1.0. It plays real media on Android, iOS, macOS and the desktop JVM,
> and it runs inside a shipping app, but the API can still change between versions. Read [Limits](#limits)
> before you plan around it.

## Install

Pick one line. Both pull in the whole playback stack, and Gradle picks the platform pieces for
each target you declare.

```kotlin
commonMain.dependencies {
    implementation("io.github.yuroyami:kiteplayer:0.2.0")            // native views, no Compose
    // or
    implementation("io.github.yuroyami:kiteplayer-compose:0.2.0")    // Compose, plus everything above

    implementation("io.github.yuroyami:kiteplayer-audioviz:0.2.0")   // optional: a visualiser for audio
}
```

You do not install FFmpeg, and there is no Gradle plugin. On Android, every artifact needs
`minSdk` 26 or higher. In an Android-only app, put the line in your usual `dependencies { }` block.

### What else you need

> [!IMPORTANT]
> Some setups need one more step. Without it, the link, the App Store upload, the first call or
> background playback fails.

| If you build | You also need |
| --- | --- |
| An iOS app with a **static** framework (`isStatic = true`) | **Linker flags** in Xcode. See [iOS setup](#ios-setup). |
| Any iOS app | **Two privacy manifest entries**, for boot time and file timestamp APIs. See [iOS setup](#ios-setup). |
| A web app (`wasmJs`) | **Two WebAssembly modules** that the page serves, for FFmpeg and libass, and a third for the worker player. See [Web setup](#web-setup). |
| Playback that goes on in the background on Android | **A service and three permissions** in your manifest. See [Background playback](#background-playback). |

<a name="ios-setup"></a>
<details>
<summary><b>iOS setup</b>: the linker flags and the privacy manifest entries</summary>
<br>

A dynamic framework needs no flags, because Kotlin links it with the system frameworks. A static
framework is linked by Xcode instead, so add this to Other Linker Flags:

```text
-ObjC -lz -framework CoreFoundation -framework CoreMedia -framework CoreVideo -framework VideoToolbox -framework AudioToolbox
```

KitePlayer times playback with `mach_absolute_time`, which Apple lists as a system boot time API.
It also reads file sizes and dates with `stat`, `fstat` and `lstat`, from FFmpeg's file reader, the
libass chain and its own file readers, which Apple lists as file timestamp APIs. App Store Connect
refuses the upload (ITMS-91053) until both are declared in `PrivacyInfo.xcprivacy`. Keep only the
reasons that apply to your app: `35F9.1` is time measured between events inside the app, `C617.1`
is files inside the app container, and `3B52.1` is files that the user picked.

```xml
<key>NSPrivacyAccessedAPITypes</key>
<array>
    <dict>
        <key>NSPrivacyAccessedAPIType</key>
        <string>NSPrivacyAccessedAPICategorySystemBootTime</string>
        <key>NSPrivacyAccessedAPITypeReasons</key>
        <array><string>35F9.1</string></array>
    </dict>
    <dict>
        <key>NSPrivacyAccessedAPIType</key>
        <string>NSPrivacyAccessedAPICategoryFileTimestamp</string>
        <key>NSPrivacyAccessedAPITypeReasons</key>
        <array><string>C617.1</string><string>3B52.1</string></array>
    </dict>
</array>
```

For background audio, declare `UIBackgroundModes` with `audio` in `Info.plist`.

</details>

<a name="web-setup"></a>
<details>
<summary><b>Web setup</b>: the modules a page serves</summary>
<br>

A browser cannot link FFmpeg or libass into the Kotlin binary, so the page serves them as two
WebAssembly modules. Each one comes as a `web` zip beside its artifact on Maven Central:

1. Unpack `kiteffmpeg-wasm-js-<version>-web.zip` beside `index.html`, with the KiteFFmpeg version
   that KitePlayer depends on (0.4.0 for 0.2.0). The page then serves `kite.mjs`, `kite.wasm`
   and `licenses/`.
2. Unpack `kiteplayer-libass-wasm-js-<version>-web.zip` there too, for `kiteass.mjs` and
   `kiteass.wasm`. The first ASS track loads them. Without them, ASS falls back to the built-in
   styling.
3. Call `KiteFFmpegWeb.load()` before you create a player. It fetches `./kite.mjs`. Under a
   bundler, instantiate the module from a plain `<script type="module">` and pass it to
   `KiteFFmpegWeb.attach()` instead.

Serve `.mjs` as `text/javascript` and `.wasm` as `application/wasm`. With gzip, the codec module is
about 1.42 MiB to download, and CI holds it to that. Both modules are single-threaded, so the page
needs no cross-origin isolation headers. A browser starts audio only after the user interacts with
the page, so the position stays at zero until then. A player on the page's own thread does not play
network media, so play files from memory, as [Network](#network) says, or use the worker player.

`KitePlayerWorker.start(canvas)` runs the player in a web worker, so opening, decoding and drawing
leave the page's thread free (#100). The worker draws on the canvas and sends its sound straight to
the page's audio device, and it plays `http`, `https` and `blob` addresses. It loads a third module:
unpack `kiteplayer-wasm-js-<version>-web.zip` beside `index.html` too, for
`kiteplayer-web-worker.mjs` and the three files beside it. With gzip it is about 1.21 MiB to
download, and CI holds it to 1.25 MiB. The worker player has open, play, pause, seek, stop and the
state, progress and events flows so far. An item with its own reader, external subtitles, a filter
or a demux policy cannot cross to the worker yet.

A multi-threaded codec module would need the page served with
`Cross-Origin-Opener-Policy: same-origin` and `Cross-Origin-Embedder-Policy: require-corp`, and
imported without them it hangs rather than failing. `KiteWebModules.codecModuleUrl(threaded = ...)`
names it only on a page that has them, and the single-threaded module otherwise; pass its answer to
`KiteFFmpegWeb.load` or `KitePlayerWorker.start`. KiteFFmpeg publishes only the single-threaded
module today.

</details>

<details>
<summary><b>What each line pulls in</b>: the module tree</summary>
<br>

```text
kiteplayer-compose
├── kiteplayer
│   ├── kiteplayer-core
│   │   └── kiteplayer-rt            native targets only
│   ├── kiteplayer-ffmpeg            decoders over KiteFFmpeg
│   │   └── kiteplayer-subtitles
│   ├── kiteplayer-output            audio output, subtitle rasterisers
│   ├── kiteplayer-view-bindings     adapters for the native views
│   │   └── kiteplayer-view
│   ├── kiteplayer-network           HTTP and HTTPS, not on Linux and Windows native
│   ├── kiteplayer-libass            ASS and SSA typesetting
│   └── kiteplayer-io                input doors for files and streams
└── kiteplayer-compose-ui           KitePlayerVideo
    ├── kiteplayer-compose-interop   Compose hosting the native view, at runtime only
    └── kiteplayer-compose-video     Compose drawing the frames itself, at runtime only

kiteplayer-audioviz                  optional audio visualiser over Kite3D
└── kiteplayer-core
```

[Modules](#modules) says what each one is for.

</details>

## Play something

Three steps: create a player, show it, open something. The order of the last two does not
matter: media may open before the view is on screen.

```kotlin
import io.github.yuroyami.kiteplayer.KitePlayer

val player = KitePlayer()
```

`KitePlayer()` builds the player on this platform's default stack: FFmpeg, and the platform's own
audio output. Where the platform cannot play, it throws a `PlaybackException` that says why;
`KitePlayer.isAvailable` checks that first. Settings go in a block, for example
`KitePlayer { subtitles { preferredLanguages = listOf("ja") } }`.

In Compose, `rememberKitePlayer()` builds the player and closes it when the composable leaves.
`KitePlayerVideo` shows it:

```kotlin
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.compose.KitePlayerVideo
import io.github.yuroyami.kiteplayer.compose.rememberKitePlayer

val player = rememberKitePlayer()
KitePlayerVideo(player, Modifier.fillMaxSize())
LaunchedEffect(Unit) {
    player.open(MediaItem("https://example.com/movie.mkv"))
    player.play()
}
```

For a native view, give the view the player. The views are in `io.github.yuroyami.kiteplayer.view`:
`KitePlayerView` on Android, from XML or code, `KitePlayerUIView` on iOS, and `KitePlayerAwtView` on
the desktop JVM.

```kotlin
view.player = player
```

A player from `KitePlayer()` gives the views their renderer. A player built with `KitePlayer.create`
on backends of your own also needs `view.installMobileRenderer()`, or `installDesktopRenderer()` on
the desktop, from `io.github.yuroyami.kiteplayer.mobile`.

Then open media from a coroutine that you own. A call that takes time suspends until it is done:
`open`, `seek` and `closeAndAwait`. `play`, `pause` and the setters return at once.
`requestSeek` is the seek that does not wait, for a seek bar being dragged.

```kotlin
import io.github.yuroyami.kiteplayer.MediaItem
import kotlin.time.Duration.Companion.seconds

player.open(MediaItem("https://example.com/movie.mkv"))
player.play()
player.seek(90.seconds)

// When the screen goes away, unless rememberKitePlayer owns the player:
player.closeAndAwait()
```

> [!TIP]
> `KitePlayerVideo` draws in one of two ways. `KiteRenderPath.NativeView` hosts the platform's
> video view: the system compositor shows the frames and the GPU stays idle, which suits long
> playback, so it is the default. `KiteRenderPath.ComposeCanvas` draws the frames inside Compose,
> so the video takes clipping, alpha and shared element transitions. You can switch while it plays.

On macOS, a click goes to the topmost native view, so Compose controls drawn over a native view
video are painted but never pressed. Use the canvas path there, or keep the controls beside the
video.

<details>
<summary><b>From Java</b>: listeners, futures and milliseconds</summary>
<br>

The player speaks in coroutines and flows, which Java cannot call. On Android and the desktop JVM,
`KitePlayerJava` adds what Java lacks: listeners called on an executor you name, a
`CompletableFuture` version of every call that suspends, and milliseconds wherever the Kotlin call
takes a `Duration`. `MediaItemBuilder` makes the item, and `PlayerConfigBuilder` the settings.

```java
KitePlayerJava player = KitePlayerJava.create();
player.addListener(new KitePlayerListener() {
    @Override
    public void onState(PlayerSnapshot state) {
        statusView.setText(state.getStatus().name());
    }

    @Override
    public void onProgress(Progress progress) {
        seekBar.setProgress((int) progress.getPositionMillis());
    }
}, ContextCompat.getMainExecutor(context));

player.openAsync(new MediaItemBuilder("https://example.com/movie.mkv").build())
        .thenRun(() -> player.getPlayer().play());
player.seekAsync(90_000);

// When the screen goes away:
player.close();
```

Cancelling a future cancels its call, as cancelling the coroutine does in Kotlin. Every other call,
such as `play()`, `pause()` and `setVolume(float)`, is on `getPlayer()`. A listener hears each event
that happens after it is added, and none from before: the player replays no event.

</details>

## Media that is not a URL

A file path or a URL needs nothing more. `MediaItem("/sdcard/movie.mkv")` goes straight to
FFmpeg's own file reader, which is the fastest way to read a local file.

For anything else, use a **door**: a function that turns what you have into a `MediaIoFactory`
for the item's `io` field. Each open of the item gets a new reader from it, because a track switch,
a loop or a recovery opens the item again.

| You have | Door | Where |
| --- | --- | --- |
| A `ByteArray` | `MediaIo.ofBytes(bytes)` | Everywhere |
| Bytes that your code pushes, from a socket or a decryptor | `PipedMediaIo`, a new one in each open | Everywhere |
| A `File` or a `Path` | `MediaIo.ofFile(file)`, `MediaIo.ofPath(path)` | JVM, Android |
| A `FileChannel` that you keep open | `MediaIo.ofChannel(channel)` | JVM, Android |
| An `InputStream` | `MediaIo.ofStream { openStream() }` | JVM, Android |
| A `content://` URI, such as one from the file picker | `MediaIo.ofUri(contentResolver, uri)` | Android |
| A file in the app's `assets` | `MediaIo.ofAsset(assets, "clip.mp4")` | Android |
| A path that every read must pass through Kotlin | `MediaIo.ofPath("/path/to/clip.mp4")` | Apple, Linux |
| A file URL, such as one from the document picker | `MediaIo.ofUrl(url)` | Apple |

```kotlin
import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.from
import io.github.yuroyami.kiteplayer.io.ofUri

player.open(MediaItem.from(MediaIo.ofUri(contentResolver, uri), label = "picked.mkv"))
player.play()
```

The label names the item in logs and helps FFmpeg guess the format. A stream and a pipe read
forward only, so the player cannot seek in them. `MediaIo.ofBytes` does not copy the array, so keep
it unchanged while playback can read it. The first two doors are in `kiteplayer-core`, and the
others in `kiteplayer-io`, which comes with `kiteplayer`.

<details>
<summary><b>Several settings on one item</b>: headers, probing and low latency</summary>
<br>

Build the item in a block. An empty block gives the same item as `MediaItem(uri)`.

```kotlin
import io.github.yuroyami.kiteplayer.CorruptPackets
import io.github.yuroyami.kiteplayer.ProbeDepth
import io.github.yuroyami.kiteplayer.mediaItem

val item = mediaItem("https://cdn.example.com/live/channel.ts") {
    header("Authorization", "Bearer $token")
    probe(ProbeDepth.Fast)
    corruptPackets(CorruptPackets.Drop)
    lowLatency()
}
```

`probe`, `corruptPackets`, `lowLatency` and the other demux settings say how the container opens,
and they fill the item's `demux` field. Raw FFmpeg options still go in `openOptions`, but an option
that a typed field also sets refuses the open with a typed error. `MediaItem` also carries
`startPosition`, `externalSubtitles`, `videoFilter` for an FFmpeg filter chain, and `formatHint`
when a container needs naming.

</details>

## What you can control

Everything here works during playback, and everything is published on `player.state`, so your UI
can read it back.

| | |
| --- | --- |
| Playback | `open`, `play`, `pause`, `stop`, `seek`, `requestSeek`, `stepFrame`, `close`, `closeAndAwait` |
| Queue | `openQueue`, `next`, `previous`, `setLoop`, and `addToQueue`, `removeFromQueue`, `moveInQueue`, `clearQueue` while it plays. Items follow each other on the same audio device with no gap; `PlayerConfig.queue` turns that off, and [the gapless design](docs/gapless-queue.md) says when an item opens from scratch instead |
| Shuffle | `setShuffle`. The items never move. `queueOrder` tells you what plays next |
| Speed | `setSpeed`, 0.25x to 4x with the pitch kept. `setPreservePitch(false)` lets the pitch change like a tape |
| Sync | `setExternalClock` makes playback follow a clock your app owns, for watching together. A small difference closes through a speed change of at most 0.5 percent with the pitch kept, and a jump is one seek. Play and pause stay with your commands |
| Sound | `setVolume`, `setMuted`, `setBalance`, `setEqualizer` (ten bands and a preamp), `setAudioDelay`, `setSleepTimer` (with a fade), `setVideoEnabled(false)` for audio only |
| Loudness | `PlayerConfig.audio.volumeCeiling` allows volume up to 2.0 through a limiter. `PlayerConfig.audio.replayGain` applies the file's own ReplayGain tags, off by default |
| Surround | Multichannel audio folds into the speakers the device has. `PlayerConfig.audio.upmix = UpmixMode.Surround` also plays mono and stereo from the other speakers of a surround device, off by default |
| Picture | `setVideoScale` (fit, fill, stretch), `setVideoAdjustments` (brightness, contrast, saturation, hue), `setVideoTransform` (forced aspect, zoom, pan) |
| HDR | `setHdrPolicy`. HDR10 and HLG show as HDR on a display that can: through Metal on a Mac or an iPhone with extended range, and through `KitePlayerView` on an Android HDR display. Elsewhere they are tone mapped, and `PlaybackWarning.HdrToneMapped` says so. `HdrPolicy.ToneMap` tone maps everywhere, and `videoDynamicRange` says what the screen shows |
| Subtitles | `selectTrack`, `selectSecondarySubtitle`, `addExternalSubtitle`, `setSubtitleScale`, `setSubtitleDelay`, `setSubtitlePosition`, `setSubtitleStyle`, `setSubtitleSafeArea`, and `subtitleCues` to draw the lines yourself |
| Sections | `setAbLoop` repeats between two points. `setMarkers` fires an event when playback crosses a position |
| Chapters | `chapterAt`, `seekToChapter`, `nextChapter`, `previousChapter` |
| Resume | `memento()` saves the item, position, tracks and speed. `restore(memento)` puts them back |
| Screenshots | `captureFrame`. `kiteplayer-ffmpeg` encodes the frame to PNG or JPEG, and makes thumbnails and waveforms |
| Recording | `startRecording` copies what the player reads into a Matroska file, with no re-encode. `stopRecording` finishes the file. A seek ends a recording |
| Rendering | `attachRenderer`, `detachRenderer`, swappable while media plays. `attachRendererAndAwait` refuses a renderer that cannot show the running decoder's frames and keeps the one before |
| Diagnosis | `diagnosticsDump`, `warningHistory`, `supportBundle`, and `KiteLog` as the one logging seam, silent by default. `KiteTrace` records a timeline that Chrome's trace viewer and Perfetto open, also silent by default |

Five flows tell your UI what is happening: `state`, `progress`, `stats`, `events` and
`subtitleCues`. `position()` reads the current time without collecting anything. Anything the
player cannot do is refused with a typed error, never accepted and ignored, and two players in one
process work.

`SeekMode.Precise` is the default seek. `SeekMode.KeyframeThenRefine` shows the nearest keyframe
at once and replaces it with the exact frame a moment later, which makes scrubbing feel instant on
large files.

<details>
<summary><b>Audio devices and screen readers</b></summary>
<br>

On the desktop JVM and on macOS, you choose the audio output device when you build the player.
`audioOutputDevices()` on `DesktopOutputBackend` or `AppleOutputBackend` lists the devices, and
`withAudioOutputDevice(id)` returns the backend bound to one, for `PlayerConfig.backends`. A bound
player never moves to another device: when its device is gone, the open fails with
`PlaybackError.AudioDeviceUnavailable`, and so does playback when the device disappears.
On Android and iOS the operating system owns the route.

The Android, iOS and desktop views, and both paths of `KitePlayerVideo`, tell a screen reader that
they are the video and what the player is doing, for example "Playing, 1:23 of 4:56".
`accessibilityVideoLabel` and `accessibilityStateFormat` take translated words. `KiteVideo`, the
bare canvas, gets its semantics from the modifier you pass. On the web, the page owns the canvas
and labels it.

</details>

## Subtitles

- SubRip, WebVTT and SubStation Alpha, from the container or from an external file. External files
  load in the middle of playback.
- Blu-ray (PGS), DVB, DVD and XSUB image subtitles from the container, placed on the picture they
  were authored for.
- ASS and SSA tracks are drawn by libass as authored: moving signs, animated transforms, karaoke
  fills, clips and vector drawings. They re-render every video frame while they move.
- Fonts attached to a Matroska file load for the track, and `SubtitleConfig.fonts` adds your own.
  On Android and Linux, a bounded set of system fonts loads too.
- `setSubtitleSafeArea` keeps the built-in text out of a display cutout, rounded corners or a
  control bar. [Subtitle placement](docs/subtitle-placement.md) says where subtitles land on every
  renderer.

<details>
<summary><b>libass details</b>: typesetting choices and the web</summary>
<br>

- `SubtitleConfig.typesetting = false` keeps the built-in Kotlin styling instead of libass.
  `PlayerSnapshot.subtitleTypesetter` says which engine draws.
- A typeset ASS track ignores `SubtitleStyleOverride`. Scale and position still apply. Only the
  primary track is typeset; a secondary track uses the built-in styling at the top of the picture.
- On the web, libass is a separate module (see [Web setup](#web-setup)). A browser has no system
  font, so supply fonts as attachments or through `SubtitleConfig.fonts`.

</details>

## Network

HTTP and HTTPS work as soon as `kiteplayer-network` is on the classpath, and every standard entry
point includes it. You do not build a resolver or a Ktor client.

- Android and the JVM use OkHttp with the platform trust store, and Apple uses NSURLSession.
- `MediaItem.headers` reach whichever transport is selected.
- The Android artifact declares the `INTERNET` permission for you. Cleartext HTTP follows your app's
  own policy.
- In a browser, a player on the page's own thread cannot play network media, because a read cannot
  wait there. `KitePlayerWorker` plays it from a web worker
  ([#100](https://github.com/yuroyami/KitePlayer/issues/100)), see [Web setup](#web-setup). It
  downloads the whole file before it plays, up to 512 MiB, and HLS and DASH do not play there yet.
  On the page's thread, fetch the file and play it from memory with
  `MediaItem.from(MediaIo.ofBytes(bytes), name)`.

HLS plays through the same transport. An address that ends in `.m3u8`, an HLS content type from
the server, or `formatHint = "hls"` marks a playlist.

- A master playlist plays one variant: the one that `DemuxPolicy.variant` names, or else the one
  with the highest bitrate within `DemuxPolicy.maxBitrate` and `DemuxPolicy.maxVideoHeight`.
  `Tracks.variants` lists the variants, and `KitePlayer.selectVariant` plays another one from the
  current position. The stream opens again for that, so the picture holds for a moment.
- The player steps down when the stream reads slower than it plays, or when playback has waited
  4 s for data, and `PlaybackWarning.VariantLowered` says so.
- It steps up when the network carries the next higher variant with half again to spare and the
  buffer is full. The network reader measures that rate on its downloads, and reports it through
  `MediaIo.networkBitsPerSecond`. A reader of your own that answers null never steps up.
- A step up waits 30 s after a step down, and twice as long after each step up that did not last,
  up to 5 minutes.
- Each step opens the stream again, so the picture holds for a moment. A variant that you
  selected stays, and a step up never passes `DemuxPolicy.maxBitrate` or `maxVideoHeight`.
- MPEG-TS and fMP4 segments, byte ranges, AES-128 keys, separate audio and subtitle renditions, and
  live playlists play. A finished playlist can seek.
- A segment that cannot be read is skipped, and `PlaybackWarning.SegmentSkipped` says so. A stream
  that ends while its last segments fail ends with `PlaybackError.SourceUnavailable`.
- `MediaItem.headers` go only to the scheme, host and port of the item's own address, because a
  playlist can name segments on any server.
- Your own `MediaIo` can serve HLS too: report the address it read in `location`, and open the
  addresses the playlist names in `openRelated`.

<details>
<summary><b>Which transport wins</b>, and when a client exists</summary>
<br>

The item's own `io` source wins, then a resolver that you set in `NetworkConfig.ioResolver`, then
the automatic provider. `NetworkConfig.autoResolve = false` turns the automatic provider off. No
HTTP client exists until network media opens, and the reader that created one closes it.

</details>

## Background playback

Android stops a process that plays in the background unless a foreground service holds it.
`kiteplayer` has that service, `KitePlayerMediaService`, and your app declares it:

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
<uses-permission android:name="android.permission.WAKE_LOCK" />

<application>
    <service
        android:name="io.github.yuroyami.kiteplayer.session.KitePlayerMediaService"
        android:exported="false"
        android:foregroundServiceType="mediaPlayback" />
</application>
```

Then attach the media session. With notification options, it shows the media notification and
keeps the app playing in the background. It also takes audio focus, and it closes with the player.
`smallIcon` is your app's monochrome notification icon.

```kotlin
import io.github.yuroyami.kiteplayer.session.MediaNotificationOptions
import io.github.yuroyami.kiteplayer.session.attachMediaSession

player.attachMediaSession(context, MediaNotificationOptions(smallIcon = R.drawable.ic_notification))
```

On iOS, declare `UIBackgroundModes` with `audio` and call `player.attachMediaSession()` for the lock
screen. A desktop app keeps playing without help, and a web page plays while its tab is open.

<details>
<summary><b>What the notification does</b>: buttons, wake locks and timeouts</summary>
<br>

- It shows the title, the artist, previous, play or pause, and next. Set `title`, `artist` and
  `album` on the `MediaItem` to choose them; otherwise they come from the file's tags, then its
  file name. `session.setCustomActions` adds your own buttons, and `session.setArtworkLoader`
  supplies the picture.
- Skip back and skip forward move 15 seconds. Pass `skipInterval` to `attachMediaSession` for
  another interval.
- The session takes audio focus, so a call or another app pauses or ducks the player, and the
  player pauses when the headphones come out. `interruptions = null` turns that off, and
  `background = null` leaves the app's background behaviour alone.
- While the player plays or buffers, the notification keeps the processor and Wi-Fi awake, so a
  stream keeps loading with the screen off. That needs `WAKE_LOCK`. Pick another `wakeLocks` policy
  in `MediaNotificationOptions`, or `WakeLockPolicy.None` to hold nothing.
- After a pause, the service stays in the foreground for ten minutes (`pausedForegroundTimeout`).
  Then the notification can be swiped away, which stops the service and leaves the player paused.
- From Android 12, Android can refuse a start from the background. `onForegroundRefused` tells you,
  and the notification still shows. Android 13 and later need no notification permission for it.
- The library declares no service and no permission for background playback, so your manifest
  carries every entry above. The one entry the library adds is `INTERNET`.

</details>

## Audio visualiser

`kiteplayer-audioviz` draws the sound when the media has no picture. Show it in place of the video
when `isAudioOnly` says so:

```kotlin
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import io.github.yuroyami.kiteplayer.audioviz.KiteAudioViz
import io.github.yuroyami.kiteplayer.audioviz.isAudioOnly
import io.github.yuroyami.kiteplayer.audioviz.rememberAudioVizState
import io.github.yuroyami.kiteplayer.compose.KitePlayerVideo

val viz = rememberAudioVizState(player)
val snapshot by player.state.collectAsState()

if (snapshot.isAudioOnly) {
    KiteAudioViz(viz, Modifier.fillMaxSize())
} else {
    KitePlayerVideo(player = player, modifier = Modifier.fillMaxSize())
}
```

Create the state where you create the player's screen, not inside the audio-only branch, so it is
already listening when a song starts. Album art does not count as a picture.

<details>
<summary><b>Choosing what it draws</b></summary>
<br>

- `viz.drawing`, `viz.palette` and `viz.directed` choose what is drawn. With `directed` on, the
  director changes drawings on the song's phrases. `viz.mutate()` changes the current drawing's
  recipe now.
- `AudioVizBrowser(viz)` shows every drawing live in a searchable grid. `AudioVizSettings(viz)`
  holds the drawing's own settings, the palette and the finishing pass.
- `VizPalette.fromImage` builds a palette from a picture, such as an album cover.
- `viz.reducedMotion` calms the picture; set it from your platform's own setting.
  `viz.framesPerSecond` caps the redraw rate, and `viz.visible = false` draws the background alone
  while the sound plays on.
- The toolkit the drawings are written with is public behind `@AudioVizAuthoringApi`.

</details>

## Where it runs

| Target | What runs, and where |
| --- | --- |
| Android | Plays real media on phones, checked by hand. CI runs the host tests, and an emulator job runs the device tests of five modules and the sample app on every push. A failure there does not fail the run yet. |
| iOS | Plays real media on devices, checked by hand. CI runs the tests of every iOS module on the simulator. |
| macOS arm64, native and desktop JVM | Plays real media. CI runs every module's tests on both, the format matrix included. |
| Web, wasmJs | Plays through the FFmpeg WebAssembly module with browser audio, from memory. `KitePlayerWorker` runs the player in a web worker, which plays single files from the network too. CI runs the web tests under Node and in a headless browser. |
| Linux and Windows, native | No audio output and no HTTPS, so `KitePlayer()` throws and `KitePlayer.isAvailable` is false. Pass `KiteFFmpegMediaBackend()` and your own `OutputBackend` to `KitePlayer.create`. CI runs the media-free tests. |
| Linux and Windows, desktop JVM | The native libraries are linked. The Linux FFmpeg backend decodes in a container, and neither has played sound on a real machine. |
| tvOS, watchOS, iOS x64, Android native | Only the engine modules build there; CI runs the tvOS and watchOS tests on their simulators. |
| js | The facade reports unavailable. |

KitePlayer's JVM and Android classes are Java 11 bytecode, and so is the KiteFFmpeg 0.4.0 jar, so a
desktop app runs on Java 11 or later.

<details>
<summary><b>Which artifact publishes which target</b></summary>
<br>

iOS means `iosArm64` and `iosSimulatorArm64`; core, subtitles, io and rt also publish `iosX64`. The
last column covers `tvosArm64`, `tvosSimulatorArm64`, the four watchOS targets and the four Android
native targets.

| Artifact | Android | iOS | macOS | JVM | Linux | Windows | wasmJs | js | tvOS, watchOS, Android native |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `kiteplayer-core`, `-subtitles`, `-io` | yes | yes | yes | yes | yes | yes | yes | yes | yes |
| `kiteplayer-rt` | no | yes | yes | no | yes | yes | no | no | yes |
| `kiteplayer`, `-libass` | yes | yes | yes | yes | yes | yes | yes | yes | no |
| `kiteplayer-ffmpeg`, `-output` | yes | yes | yes | yes | yes | yes | yes | no | no |
| `kiteplayer-network` | yes | yes | yes | yes | no | no | yes | yes | no |
| `kiteplayer-view` | yes | yes | yes | yes | no | no | yes | no | no |
| `kiteplayer-compose-interop` | yes | yes | no | yes | no | no | yes | yes | no |
| `kiteplayer-compose`, `-compose-ui`, `-compose-video`, `-view-bindings`, `-audioviz` | yes | yes | no | yes | no | no | no | no | no |

`kiteplayer-compose-interop`'s js and wasmJs variants draw an empty surface, so that shared Compose
code compiles for the web; they show no video. Every CI run of the format matrix writes a
conformance table, uploaded as the `conformance-macos-host` artifact and printed in the run
summary.

</details>

## Limits

| Topic | What to expect |
| --- | --- |
| Adaptive streaming | Single-file HTTP and HTTPS work, with an in-memory byte cache, everywhere. In the browser they work only in `KitePlayerWorker`, which downloads the whole file before it plays. HLS plays one variant at a time. `selectVariant` changes it, with a short pause while the stream opens again. The player steps down and up by itself with the measured network rate, and each step holds the picture for a moment. `Dash.mediaItemFor` plays a DASH manifest of fMP4 or MPEG-TS segments through the HLS path, live ones included, with a variant for each video representation. A manifest of WebM segments plays one representation, cannot seek, and is refused when it is live or keeps its audio in a set of its own (#392). A manifest with more than one Period, TTML subtitles and a persistent cache do not work yet. |
| Native Linux and Windows | No audio output and no HTTPS. Use the desktop JVM target, or pass your own `OutputBackend`. |
| Desktop JVM sound | Plays on macOS. Linux and Windows have not played audio on a real machine. |
| AV1 on the web | There is no software AV1, because the web build has one thread and dav1d needs threads. Native targets decode AV1 with dav1d, and in hardware where the device has it. |
| Android devices | The emulator runs the device tests on a software GPU. What needs a real phone, such as frame pacing and GPU cost, is checked by hand. |
| API stability | Any release before 1.0 can change the API. Committed ABI dumps make each change visible in review, but they are not a promise. |

Everything else that is open lives in [GitHub Issues](https://github.com/yuroyami/KitePlayer/issues).

## Modules

| Artifact | What it is |
| --- | --- |
| `kiteplayer-compose` | Everything in `kiteplayer`, plus both Compose video paths and the switch between them. The complete Compose entry point. |
| `kiteplayer` | The default playback stack for native views: engine, FFmpeg decoders, audio output, view adapters, HTTP and HTTPS, libass, input doors. |
| `kiteplayer-audioviz` | Optional. An audio visualiser for files with no picture: presets, palettes, and a director that changes drawings with the music. |
| `kiteplayer-compose-ui` | Compose presentation only: `KitePlayerVideo` and both video paths. No player factory, no network. |
| `kiteplayer-compose-interop` | Compose hosting the platform's native video view: `KitePlayerSurface`. `KitePlayerVideo` uses it at runtime; add it yourself only to call `KitePlayerSurface` directly. |
| `kiteplayer-compose-video` | Video drawn by Compose itself: `KiteVideo`. `KitePlayerVideo` uses it at runtime; add it yourself only to draw with `KiteVideo` directly, for example in a second window. |
| `kiteplayer-view` | The native views: `KitePlayerView` on Android, `KitePlayerUIView` on iOS, `KitePlayerAwtView` on the desktop JVM. |
| `kiteplayer-view-bindings` | The FFmpeg adapters those views need. |
| `kiteplayer-core` | The engine and its service interfaces. Depends on kotlinx.coroutines and atomicfu, and on `kiteplayer-rt` on native targets. |
| `kiteplayer-ffmpeg` | Media source and decoders over KiteFFmpeg, plus snapshots, thumbnails, waveforms and the subtitle parsers. |
| `kiteplayer-network` | HTTP and HTTPS through Ktor. Registers itself. |
| `kiteplayer-io` | Input doors for platform types. Comes with `kiteplayer`. |
| `kiteplayer-libass` | The libass typesetter for ASS and SSA. Registers itself. |
| `kiteplayer-output` | Platform audio output, render support and the subtitle rasterisers. |
| `kiteplayer-subtitles` | SubRip, WebVTT and ASS dialogue parsers, in Kotlin. |
| `kiteplayer-rt` | The real-time audio ring, in C. Comes with `kiteplayer-core` on native targets; never add it yourself. |

To build your own stack, start from `kiteplayer-core` and supply backends through
`KitePlayer.create(PlayerConfig(backends = Backends(backend, output)))`. The
[SPI cookbook](docs/spi-cookbook.md) walks through one, and the
[module contract](docs/module-contract.md) says what each entry point promises.

## Good to know

<details>
<summary><b>How it is tested</b></summary>
<br>

Each push runs the jobs in [`ci.yml`](.github/workflows/ci.yml): the real-media suites on macOS
arm64 (JVM and native), the iOS simulator suites and the iOS sample app, the tvOS and watchOS
simulators, the media-free suites on Linux x64 and Linux arm64, Windows x64 native, wasmJs under
Node and in a headless browser, the C audio ring under AddressSanitizer and ThreadSanitizer, and
the Android device tests on an emulator, whose failure does not fail the run yet. Before a commit,
`scripts/check-gate.sh` runs the local gate that [CONTRIBUTING.md](CONTRIBUTING.md) describes.

</details>

<details>
<summary><b>Sample apps</b>: four apps, none of them published</summary>
<br>

The desktop, Android and iOS apps open on the audio visualiser, playing five songs by Skullbeatz
from the [Newgrounds Audio Portal](https://www.newgrounds.com/audio), under
[CC BY-SA 3.0](https://creativecommons.org/licenses/by-sa/3.0/).
[`kiteplayer-sample-shared/media/README.md`](kiteplayer-sample-shared/media/README.md) credits each
one. To play your own song, set `kiteplayer.sample.song=/path/to/song.mp3` in `local.properties`.
To play a file you pick: on Android, open Other samples and choose Play a file you pick; on iOS,
launch with `--uikit` and tap Open file.

| Module | What it shows | Run it |
| --- | --- | --- |
| `kiteplayer-sample-android` | The shared screen, and behind Other samples the XML view, both Compose paths, a button that swaps them, and a file picker | `./gradlew :kiteplayer-sample-android:installDebug` |
| `kiteplayer-sample-desktop` | The shared screen in a window; with `--modifiers`, the Compose drawn path, clipped and animated | `./gradlew :kiteplayer-sample-desktop:run`, or add `--args='--modifiers'` |
| `kiteplayer-sample` | A macOS window on the native views, and an iOS app on the shared screen, or on the native view with `--uikit` | `./gradlew :kiteplayer-sample:linkDebugExecutableMacosArm64`, then run `kiteplayer.kexe testmedia/sync1080p30.mp4 --window`; for iOS, link `linkDebugFrameworkIosSimulatorArm64` and open `kiteplayer-sample/iosApp/KitePlayerSample.xcodeproj` |
| `kiteplayer-sample-web` | The wasmJs measurement harness, not a demo | `./gradlew :kiteplayer-sample-web:wasmJsBrowserDistribution`, then read `kiteplayer-sample-web/MEASUREMENTS.md` |

`kiteplayer-sample-shared` is the screen that the first three share. The test clips come from
`./scripts/testmedia.sh`, which needs `ffmpeg` on your PATH, and are not committed.

</details>

<details>
<summary><b>Working on KitePlayer</b>: for contributors</summary>
<br>

[CONTRIBUTING.md](CONTRIBUTING.md) has the ground rules, the build prerequisites and the test gate.
The short version:

```bash
./scripts/testmedia.sh          # generate the test clips, needs ffmpeg on PATH
./scripts/check-gate.sh tier1   # the checks every change runs
```

</details>

<details>
<summary><b>Licensing</b>: what an app that ships these artifacts must do</summary>
<br>

KitePlayer is Apache-2.0. Decoding is done by [KiteFFmpeg](https://github.com/yuroyami/KiteFFmpeg),
which embeds FFmpeg (LGPL-2.1-or-later) and dav1d (BSD-2-Clause). `kiteplayer-libass` embeds libass
(ISC), HarfBuzz (MIT), FreeType (FreeType License) and FriBidi (LGPL-2.1-or-later), and its Windows
JVM adapter adds GNU libiconv (LGPL-2.0-or-later). An app that ships them has three LGPL duties for
FFmpeg, FriBidi and libiconv:

| Duty | How to meet it |
| --- | --- |
| Say that the app uses them, under the LGPL | Ship a notice with the licence texts, which the JVM and Android artifacts carry under `META-INF/licenses/`. |
| Make their source available to your users | Point at the source that KiteFFmpeg's `NOTICE` and this repository's [NOTICE](NOTICE) name. |
| Let users relink against a modified copy | The artifacts link them statically, so publish your object files, or give a written offer for them. |

KiteFFmpeg's [licensing guide](https://yuroyami.github.io/KiteFFmpeg/licensing/) explains the static
linking case in detail.

</details>

## License

Apache-2.0. See [NOTICE](NOTICE).

Part of the Kite family: [KiteFFmpeg](https://github.com/yuroyami/KiteFFmpeg),
[Kite3D](https://github.com/yuroyami/Kite3D) and [KitePDF](https://github.com/yuroyami/KitePDF).
