<p align="center">
  <img src="art/readme/kiteplayer-banner.webp" width="100%" alt="KitePlayer. Media playback. Built with Kotlin/Native. One Kotlin engine with bundled FFmpeg for Android, iOS, macOS, Desktop JVM and Web.">
</p>

<p align="center">
  A media player for Kotlin Multiplatform, with its own engine written in Kotlin and FFmpeg already inside.<br>
  It plays video, audio and subtitles on Android, iOS, macOS, the desktop JVM and the web, with one API in <code>commonMain</code>.<br>
  It takes mpv and VLC as its models, and aims for their performance and range of features.
</p>

<p align="center">
  <sub>AI tools wrote much of the code, tests and documentation in this project, under the direction
  and review of its author.</sub>
</p>

<p align="center">
  <a href="https://central.sonatype.com/artifact/io.github.yuroyami/kiteplayer"><img src="https://img.shields.io/maven-central/v/io.github.yuroyami/kiteplayer?label=Maven%20Central&color=7F52FF&style=flat-square" alt="Maven Central"></a>
  <a href="https://github.com/yuroyami/KitePlayer/actions/workflows/ci.yml"><img src="https://img.shields.io/github/actions/workflow/status/yuroyami/KitePlayer/ci.yml?label=CI&style=flat-square" alt="CI"></a>
  <a href="https://kotlinlang.org"><img src="https://img.shields.io/badge/Kotlin-2.4.21-7F52FF?logo=kotlin&logoColor=white&style=flat-square" alt="Kotlin 2.4.21"></a>
  <a href="https://www.jetbrains.com/compose-multiplatform/"><img src="https://img.shields.io/badge/Compose%20Multiplatform-1.13.0--alpha02-C518CB?logo=jetpackcompose&logoColor=white&style=flat-square" alt="Compose Multiplatform 1.13.0-alpha02"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache--2.0-ED464C?style=flat-square" alt="License: Apache-2.0"></a>
</p>

<p align="center">
  <b><a href="docs/README.md">Guides</a></b>&ensp;·&ensp;<b><a href="https://yuroyami.github.io/KitePlayer/">API reference</a></b>&ensp;·&ensp;<a href="CHANGELOG.md">Changelog</a>
</p>

## Why KitePlayer

A Kotlin Multiplatform player is usually a common API over ExoPlayer on Android and AVPlayer on
Apple, which leaves your app with two engines that open different files and behave differently
around them. KitePlayer doesn't wrap any platform player. It is one engine written in Kotlin with
FFmpeg underneath, which is why an MKV file with ASS subtitles plays the same way on a phone, on a
desktop and in a browser.

Since the seeking, the audio and video sync and the subtitle timing are all Kotlin code in
`commonMain`, a platform only has to supply the audio output, the video surface and (where the
device has one) a hardware decoder. Here is what each platform gives you today:

| | Android | iOS | macOS | Desktop JVM | Web |
| --- | :---: | :---: | :---: | :---: | :---: |
| **Hardware decoding** | H.264, HEVC | H.264, HEVC, AV1 | H.264, HEVC, AV1 | On a Mac | |
| **HDR on an HDR screen** | In `KitePlayerView` | ✓ | ✓ | | |
| **HLS and DASH** | ✓ | ✓ | ✓ | ✓ | In a web worker |
| **Video in** | Compose, `View` | Compose, `UIView` | AppKit | Compose, AWT | `<canvas>` |
| **Background playback** | ✓ | ✓ | ✓ | ✓ | While the tab is open |
| **Picture-in-picture** | ✓ | ✓ | ✓ | A floating window | ✓ |

An empty cell means the player falls back to something else: software decoding, or HDR tone mapped
for a standard screen. The engine alone also builds for Linux and Windows native, tvOS and watchOS,
where your app supplies the missing parts ([Platforms](#platforms) lists them).

> [!NOTE]
> KitePlayer has not reached 1.0. It already runs inside a shipping app, but the API can still
> change between versions.

## Install

```kotlin
commonMain.dependencies {
    // Native views, no Compose
    implementation("io.github.yuroyami:kiteplayer:0.3.0")

    // Or Compose, with everything that the line above has
    implementation("io.github.yuroyami:kiteplayer-compose:0.3.0")

    // Optional: a visualizer for media with no picture
    implementation("io.github.yuroyami:kiteplayer-audioviz:0.3.0")
}
```

You don't install FFmpeg (it comes inside the artifacts), and there is no Gradle plugin to apply.
On Android, every artifact needs `minSdk` 26. To build a smaller stack of your own, the
[module contract](docs/module-contract.md) explains what each module brings in.

### What else you need

> [!IMPORTANT]
> Some setups need one more step. If you skip it, the build, the App Store upload or the playback
> fails.

| If you build | You also need |
| --- | --- |
| An iOS app with a static framework (`isStatic = true`) | Linker flags in Xcode ([iOS setup](#ios-setup)) |
| Any iOS app | Two entries in the privacy manifest ([iOS setup](#ios-setup)) |
| A web app (`wasmJs`) | The WebAssembly modules of FFmpeg and libass beside the page, and a third one for the worker player ([Web setup](#web-setup)) |
| An Android app that plays in the background | A service and three permissions in the manifest ([Background playback](#background-playback)) |

<a name="ios-setup"></a>
<details name="setup">
<summary><b>iOS setup</b>: the linker flags and the privacy manifest entries</summary>
<br>

A dynamic framework needs no flags, because Kotlin links it with the system frameworks. Xcode links
a static framework instead, which needs these Other Linker Flags:

```text
-ObjC -lz -framework CoreFoundation -framework CoreMedia -framework CoreVideo -framework VideoToolbox -framework AudioToolbox
```

KitePlayer times playback with `mach_absolute_time`, which Apple lists as a system boot time API.
It also reads file sizes and dates with `stat`, `fstat` and `lstat` (in FFmpeg's file reader, the
libass chain and its own file readers), which Apple lists as file timestamp APIs. App Store Connect
refuses the upload (ITMS-91053) until `PrivacyInfo.xcprivacy` declares both. Keep only the reasons
that apply to your app: `35F9.1` is time measured between events inside the app, `C617.1` is files
inside the app container, and `3B52.1` is files that the user picked.

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

</details>

<a name="web-setup"></a>
<details name="setup">
<summary><b>Web setup</b>: the modules that a page serves</summary>
<br>

A browser cannot link FFmpeg or libass into the Kotlin binary, which is why the page serves them as
WebAssembly modules (each one comes as a `web` zip beside its artifact on Maven Central):

1. Unpack `kiteffmpeg-wasm-js-<version>-web.zip` beside `index.html`. Use the KiteFFmpeg version
   that KitePlayer depends on (0.5.1 for KitePlayer 0.3.0).
2. Unpack `kiteplayer-libass-wasm-js-<version>-web.zip` beside it. Without this module, KitePlayer
   draws ASS with its built-in styling.
3. Call `KiteFFmpegWeb.load()` before you create a player. Under a bundler, instantiate the module
   from a plain `<script type="module">` and pass it to `KiteFFmpegWeb.attach()` instead.

Serve `.mjs` as `text/javascript` and `.wasm` as `application/wasm`. Both modules are
single-threaded, which means the page needs no cross-origin isolation headers. A browser starts
audio only after the user interacts with the page, and the position stays at zero until then.

A player on the page's own thread cannot play network media, because a read cannot wait there (it
can still play bytes that the page has fetched). `KitePlayerWorker.start(canvas)` runs the player in
a web worker instead, which plays `http`, `https` and `blob` addresses (HLS and DASH included) and
draws on your canvas while the page's thread stays free. The worker needs a third module from
`kiteplayer-wasm-js-<version>-web.zip`, which goes beside `index.html` as well. It has the calls
and flows of `KitePlayer` apart from the few that its KDoc lists (such as recording), and it takes
an address rather than an item with a reader of its own.

</details>

## Usage

### Play a video in Compose

`rememberKitePlayer()` creates a player that closes when the composable leaves, and
`KitePlayerVideo` shows its video, here with the default controls on top:

```kotlin
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.compose.KitePlayerControls
import io.github.yuroyami.kiteplayer.compose.KitePlayerVideo
import io.github.yuroyami.kiteplayer.compose.rememberKitePlayer

@Composable
fun MovieScreen() {
    val player = rememberKitePlayer()

    KitePlayerVideo(player, Modifier.fillMaxSize()) {
        // Play, pause, seek, volume, tracks and speed
        KitePlayerControls(player)
    }

    LaunchedEffect(player) {
        // Returns once the movie is open
        player.open(MediaItem("https://example.com/movie.mkv"))
        player.play()
    }
}
```

`KitePlayerVideo` hosts the platform's own video view by default, which leaves the frames to the
system compositor and keeps the GPU idle during long playback. Pass
`path = KiteRenderPath.ComposeCanvas` when the video has to clip, fade or join a shared element
transition, since that path draws the frames inside Compose (you can switch paths while the video
plays).

> [!WARNING]
> On the desktop JVM, the native view takes every click. Controls that you draw over it do not
> receive clicks. Use `KiteRenderPath.ComposeCanvas` there, or put the controls beside the video.

On Android, a rotation recreates the activity, which closes a player that `rememberKitePlayer()`
holds. To play on through a rotation, keep the player in a `ViewModel` instead and close it in
`onCleared`.

The parts of `KitePlayerControls` are public too, which lets you build controls of your own from
the same state holders (`rememberSeekBarState`, `rememberTrackMenuState` and the others). The
default controls speak English unless you pass your own `KitePlayerControlsLabels`.

### Without Compose

Each platform has a native view that takes the player: `KitePlayerView` on Android (from XML or
code), `KitePlayerUIView` on iOS and `KitePlayerAwtView` on the desktop JVM, all in
`io.github.yuroyami.kiteplayer.view`. A view can also draw default controls with its own toolkit:

```kotlin
view.player = player
view.showsControls = true
```

A native macOS app draws into an `AppKitWindow` through `MetalVideoRenderer` instead (the macOS
sample in `kiteplayer-sample` shows the setup), and a web page without Compose draws on a
`<canvas>`, as the [web setup](#web-setup) describes.

<details>
<summary><b>From Java</b></summary>
<br>

On Android and the desktop JVM, `KitePlayerJava` wraps the player for Java code, with listeners on
an executor that you name, a `CompletableFuture` for every call that suspends, and milliseconds
wherever Kotlin takes a `Duration`:

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

Canceling a future cancels its call. The other calls (such as `play()`, `pause()` and
`setVolume(float)`) are on `getPlayer()`, and a listener hears only the events that happen after
you add it.

</details>

### Control it and read its state

Calls such as `open`, `seek` and `selectTrack` suspend until they are done, while `play`, `pause`
and the setters return at once:

```kotlin
import io.github.yuroyami.kiteplayer.TrackKind
import kotlin.time.Duration.Companion.seconds

player.seek(90.seconds)
player.setSpeed(1.5)
player.setVolume(0.8f)

// Every audio, video and subtitle track of the open item
val tracks = player.state.value.tracks
val french = tracks.audio.first { it.language == "fr" }
player.selectTrack(TrackKind.Audio, french.id)
```

Collect `player.state` for the status, the tracks and every setting, and `player.progress` for the
position and the buffer. `player.events` reports what happens, warnings and errors included. When
the player cannot honor a call, it fails the call with a typed error rather than ignoring it.

The player can also run a gapless queue (or crossfade between songs), shuffle and loop, apply a
ten-band equalizer and ReplayGain, follow an external clock for watching together, step frame by
frame, take screenshots, and record what plays into a Matroska file with no re-encode.
[Controlling the player](docs/player-api.md) lists every call by area.

### Open local and picked files

A file path or an address goes straight to FFmpeg, and so does the `Res.getUri` address of a
Compose resource on Android, iOS and the desktop JVM. Anything else reaches the player through a
`MediaIo`, which is how an Android app plays a file that the user picked:

```kotlin
import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.from
import io.github.yuroyami.kiteplayer.io.ofUri

val io = MediaIo.ofUri(contentResolver, uri)
player.open(MediaItem.from(io, label = "picked.mkv"))
```

`MediaIo` can also read a `ByteArray`, a `File`, an `InputStream`, an Android asset or an Apple
file URL, and [Opening media](docs/media-sources.md) lists each one with the platforms it works on.
The same guide covers request headers, files that are still being written, and an audio file that
plays beside a video (such as a dub).

### Subtitles

KitePlayer reads ASS and SSA (which libass draws exactly as authored, signs and karaoke included),
SubRip, WebVTT and TTML, the image subtitles of Blu-ray, DVD and DVB, DVB teletext, the closed
captions inside broadcast video, and synced lyrics. Each one is a track of the item, and a subtitle
file that you add while the video plays becomes one too:

```kotlin
import io.github.yuroyami.kiteplayer.SubtitleSource
import io.github.yuroyami.kiteplayer.TrackKind

val id = player.addExternalSubtitle(
    SubtitleSource("https://example.com/movie.en.srt"),
)
player.selectTrack(TrackKind.Subtitle, id)
```

A second track can show at the same time as the first. Delay, scale, position and style each have
their own call, and a safe area keeps the text clear of a camera cutout or your controls.
[Subtitles](docs/subtitles.md) covers the rest, from hearing-impaired notes to forced tracks and
fonts.

### Streams

An HTTP, HTTPS, HLS or DASH address plays as it is, because every standard entry point includes
`kiteplayer-network` (OkHttp on Android and the JVM, NSURLSession on Apple). An adaptive stream
picks the variant that fits the screen, steps down when the network slows and back up when it
recovers, and downloads only the audio language that you hear. A radio station's PLS or M3U link
plays as it is too, with each song's title as the station announces it.

Each open downloads the segments of an HLS or DASH presentation again, unless the player has a
segment store that keeps them on disk for the next session:

```kotlin
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.NetworkConfig
import io.github.yuroyami.kiteplayer.PlayerConfig
import io.github.yuroyami.kiteplayer.network.fileSegmentStore

val store = fileSegmentStore(
    "$cacheDirectory/kite-segments",
    maxBytes = 512L * 1024 * 1024,
)
val player = KitePlayer(
    PlayerConfig(network = NetworkConfig(segmentStore = store)),
)
```

[Segment store](docs/segment-cache.md) explains what the store keeps, and
[Network streams](docs/streaming.md) covers headers, live streams, DASH Periods, radio and the
recovery after a network drop. In a browser, network media plays through the worker player of the
[web setup](#web-setup).

### Background playback

Android can stop an app that plays in the background unless a foreground service keeps it running.
KitePlayer ships that service (`KitePlayerMediaService`), which your manifest declares with its
permissions:

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

Attaching a media session with notification options then shows the media notification and keeps
the app playing in the background. The session also takes audio focus, and it closes with the
player:

```kotlin
import io.github.yuroyami.kiteplayer.session.MediaNotificationOptions
import io.github.yuroyami.kiteplayer.session.attachMediaSession

// smallIcon is your monochrome notification icon
val options = MediaNotificationOptions(smallIcon = R.drawable.ic_notification)
player.attachMediaSession(context, options)
```

On iOS, add `audio` to `UIBackgroundModes` in `Info.plist`, then call
`player.attachMediaSession()` for the lock screen. A native Mac app calls it too, which puts the
player in Now Playing and on the media keys. [Background playback](docs/background-playback.md)
covers the notification's buttons, artwork, wake locks and timeouts.

<a name="audio-visualiser"></a>
### Music and the visualizer

`kiteplayer-audioviz` draws the music when the media has no picture, and `isAudioOnly` tells you
when to show it in place of the video (a song with cover art counts as audio only):

```kotlin
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.audioviz.KiteAudioViz
import io.github.yuroyami.kiteplayer.audioviz.isAudioOnly
import io.github.yuroyami.kiteplayer.audioviz.rememberAudioVizState
import io.github.yuroyami.kiteplayer.compose.KitePlayerVideo

@Composable
fun NowPlaying(player: KitePlayer) {
    val viz = rememberAudioVizState(player)
    val snapshot by player.state.collectAsState()

    if (snapshot.isAudioOnly) {
        KiteAudioViz(viz, Modifier.fillMaxSize())
    } else {
        KitePlayerVideo(player, Modifier.fillMaxSize())
    }
}
```

Create the state next to the player rather than inside the audio-only branch, which keeps it
listening from the first note of a song. `viz.drawing` and `viz.palette` choose the picture,
`viz.directed = true` lets it change with the music, and `AudioVizBrowser` shows every drawing in a
live grid.

<a name="modules"></a>
## Platforms

| | `kiteplayer` | `kiteplayer-compose` | `kiteplayer-audioviz` |
| --- | :---: | :---: | :---: |
| **Android** | ✓ | ✓ | ✓ |
| **iOS** | ✓ | ✓ | ✓ |
| **Desktop JVM** | ✓ | ✓ | ✓ |
| **macOS** (native) | ✓ | | |
| **Web** (`wasmJs`) | ✓ | ✓ | |
| **Linux and Windows** (native) | No audio output | | |

- On iOS, the artifacts target `iosArm64` and `iosSimulatorArm64`, with no `iosX64`.
- The desktop JVM build carries FFmpeg and libass for macOS on Apple silicon, Linux (x64 and
  arm64) and Windows (x64). It runs on Java 11 or later.
- On Linux and Windows native, KitePlayer has no audio output and no HTTPS. `KitePlayer()` throws
  there, and `KitePlayer.isAvailable` is false. Pass `KiteFFmpegMediaBackend()` and your own
  `OutputBackend` to `KitePlayer.create`.
- On the web, `kiteplayer-compose` draws the picture inside Compose's own WebGL context, so it
  needs a browser with WebGL2, and Compose content, clipping and alpha apply to it as on the other
  platforms. It draws the player on the page's own thread; the worker player draws on its own
  canvas.
- `kiteplayer` also compiles for `js`, where `KitePlayer()` reports that the target is not
  available.
- The engine modules (`kiteplayer-core`, `kiteplayer-io` and `kiteplayer-subtitles`) also build
  for tvOS, watchOS and Android Native.

## App size and licensing

KitePlayer puts FFmpeg and libass into your app as native code:

- On Android, they add about 22 MB per ABI before compression (18.8 MB for FFmpeg and 3.4 MB for
  libass, measured on `arm64-v8a`).
- On the desktop JVM, the KiteFFmpeg jar is about 39 MB, because it carries FFmpeg for four
  desktop platforms. The libass jar adds about 6 MB.
- On the web, the page downloads about 1.5 MB for the codec module and 0.7 MB for the worker
  player, with gzip.

KitePlayer itself is Apache-2.0. Three of the libraries that the artifacts link statically use the
LGPL: FFmpeg and FriBidi (LGPL-2.1-or-later), and GNU libiconv in the Windows JVM build of
`kiteplayer-libass` (LGPL-2.0-or-later). An app that ships them has three duties:

| Duty | How to meet it |
| --- | --- |
| Say that the app uses them, under the LGPL | Ship a notice with the license texts. The JVM and Android artifacts carry the texts under `META-INF/licenses/`. For a native app, take the texts from the places that [NOTICE](NOTICE) names. |
| Make their source available to your users | Point at the source that KiteFFmpeg's `NOTICE` and this repository's [NOTICE](NOTICE) name. |
| Let users relink against a modified copy | Publish your object files, or give a written offer for them. |

The other components (dav1d, libass, HarfBuzz and FreeType) use permissive licenses that only ask
for their notices, which [NOTICE](NOTICE) lists. KiteFFmpeg's
[licensing guide](https://yuroyami.github.io/KiteFFmpeg/licensing/) explains the static linking
case in detail.

<a name="limits"></a>
## A few limitations to know about

- KitePlayer does not play media that has DRM (digital rights management). The player leaves out a
  DASH set that has encryption. It refuses a DASH manifest in which every picture and sound set
  has encryption, with `DashUnsupportedException`.
- The web build has no software AV1 decoder. The AV1 decoder (dav1d) needs threads, and the web
  build runs on one thread.
- KitePlayer composes Dolby Vision profile 5 and 10.0 on the processor. The processor keeps up at
  1080p, but not at 4K.
- No one has played sound with the desktop JVM build on a real Linux or Windows computer.

Missing features and known bugs are [open issues](https://github.com/yuroyami/KitePlayer/issues).

## Sample apps

The desktop, Android and iOS sample apps open on the audio visualizer with five songs by Skullbeatz
(under CC BY-SA 3.0, credited in [the media folder](kiteplayer-sample-shared/media/README.md)):

```bash
./gradlew :kiteplayer-sample-desktop:run
```

```bash
./gradlew :kiteplayer-sample-android:installDebug
```

For iOS, open `kiteplayer-sample/iosApp/KitePlayerSample.xcodeproj`, which builds the Kotlin
framework itself. The same module also links a native macOS app
(`./gradlew :kiteplayer-sample:linkDebugExecutableMacosArm64`, then run `kiteplayer.kexe` with a
media file and `--window`), while `kiteplayer-sample-web` plays a clip through `KiteVideo` on a
Compose web page and, opened with `?probe`, measures what a frame costs there. To work on KitePlayer itself, start with
[CONTRIBUTING.md](CONTRIBUTING.md).

## License

Apache-2.0. [NOTICE](NOTICE) lists each third-party component with its license.

Part of the Kite family: [KiteFFmpeg](https://github.com/yuroyami/KiteFFmpeg),
[Kite3D](https://github.com/yuroyami/Kite3D),
[KitePDF](https://github.com/yuroyami/KitePDF).
