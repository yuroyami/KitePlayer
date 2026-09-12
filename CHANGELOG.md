# Changelog

All notable changes to KitePlayer are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

**Versioning policy:** KitePlayer is pre-1.0. During 0.x, minor versions may contain breaking API changes; they are called out here when they happen. From 1.0 on, breaking changes only land in major versions.

The entries under a version are drafted by `scripts/release-notes.sh`, which groups the commits since the previous tag by their prefix. `publish.yml` refuses a version that has no section here.

## [0.0.24] - 2026-09-12

### Added

- `MediaIo.ofBytes(bytes)` opens media held in memory without copying the array. Each open gets
  an independent, seekable reader. `MediaItem.from(io, label)` builds the item in one call. Fixes #43.
- `kiteplayer-audioviz` is a new, optional audio visualiser for files with no picture: 78 drawings
  in 11 families, palettes, and a director that changes drawings on the song's phrases. Show
  `KiteAudioViz` in place of the video when `PlayerSnapshot.isAudioOnly` says so;
  `rememberAudioVizState(player)` feeds it from `KitePlayer.attachAudioTap`, so the picture follows
  the sound through seeks and track changes. `AudioVizBrowser` and `AudioVizSettings` are ready-made
  panels for picking and tuning drawings. It draws on Android, iOS and the desktop JVM, and the
  toolkit the drawings are written with is public behind `@AudioVizAuthoringApi`.
- `KitePlayer.attachAudioTap` hands every block of decoded audio to an `AudioTap` on its way to the
  speaker, with the time it plays at, so a level meter or a visualiser can show the sound that is
  actually playing. A seek, an audio track change or a new audio path arrives as
  `onDiscontinuity`, and a tap that throws is detached and reported as
  `PlaybackWarning.AudioTapFailed`; playback carries on.
- Playback now behaves when the platform takes the sound away. A call, another app, or the
  headphones coming out pauses or lowers the volume, and playback resumes afterwards only when
  this policy was the one that paused it. Attach it with
  `KitePlayerPlatform.attachInterruptionHandling`.
- Video decoding stops while the application is in the background, through
  `KitePlayerPlatform.attachBackgroundHandling`. Three policies: keep the sound and park the
  picture, pause everything, or do nothing.
- `KitePlayerMediaSession` mirrors the player into the platform's own media session, so the lock
  screen, the headset buttons, the car and the iOS now playing card all work. Android hands out a
  session token for the application's own notification.
- iOS picture in picture works. `SampleBufferVideoRenderer` draws into the layer a controller can
  take, and `KitePlayerPictureInPicture` drives the small window.
- On Android, `keepPictureInPictureParamsCurrent` keeps the window's parameters matching what is
  playing, and `enterPictureInPicture` opens it. Auto-enter used to stay off for a whole session
  when the parameters were built while paused.
- `inspect(media, backend)` reads a file's length, tracks, chapters and metadata without opening
  playback, and without an output device.
- `captureFrame(withSubtitles = true)` returns a screenshot carrying the subtitles that were on
  screen, laid out for the frame's own size. `SubtitleOverlay.drawOver` burns them in.
- A memento now carries balance, the equaliser, and every picture and subtitle setting. Its text
  form is version 2 and still reads version 1.
- The playback stats report the bit rate the container declares, beside the measured throughput.
- The player says when a container's header disagrees with its own decoder, naming the stream, the
  field, and both values.
- An external subtitle can be read from an http or https address, or through a reader the caller
  supplies as `SubtitleSource.io`. The parent item's headers travel with the request.

### Fixed

- Backward seeks initially ask FFmpeg to land within two seconds of the target. If the container
  refuses that window, one unrestricted retry preserves playback across sparse keyframes. Fixes #35.
- A stereo file played silent on a mono device, and 5.1 content lost its dialogue on a quad
  device. Every channel now reaches a speaker when folding to a smaller layout.
- YCgCo video was converted with the BT.709 matrix, so its colours were wrong on every path.
- On Metal, turning debanding on also moved the chroma planes, and turning it off applied no
  siting correction at all. Chroma is now placed from the container's declared siting.
- The last subtitle of a file is no longer cut off: the session waits for a cue that outlives the
  last frame, bounded at ten seconds.
- WebVTT `position`, `line` and `size` settings are honoured instead of being read and discarded.
- On Android, a playing `KitePlayerView` could freeze the app long enough for Android to report it
  as not responding when the view changed size, for example as the next file opened. A new or
  resized Surface no longer waits for the frame being drawn, and a destroyed one waits one second
  at most.
- On Android, `KitePlayerView` ignored brightness, contrast, saturation and hue when the video was
  decoded in software. They now apply to every frame it draws.

### Changed

- The audio visualiser reuses scene pixels, texture uploads and drawing buffers, and adapts trail
  resolution to frame cost. Bloom uses a filtered image pyramid. The measured desktop results and
  their limits are recorded in `kiteplayer-audioviz/PERFORMANCE.md`.
- Breaking: `kiteplayer-mobile` is gone. It held no code of its own and only re-exported
  `kiteplayer`, so two coordinates named one artifact. Depend on `kiteplayer` instead; the
  package `io.github.yuroyami.kiteplayer.mobile` and everything in it are unchanged and still
  resolve. The versions already on Maven Central stay there.

- The Compose artifacts depend on Compose Multiplatform 1.12.0 instead of 1.12.0-rc01. The
  build moves to Gradle 9.7.1 and the Android Gradle Plugin 9.4.0.

- Breaking: `MediaItem.io` is typed `MediaIoFactory` rather than a bare lambda. A lambda at the
  call site still converts, so existing code compiles unchanged.

- Breaking: an item carrying the `fflags=fastseek` or `usetoc` demuxer options is refused where it
  is built. Both break exact seeking on MP3, and the engine owns that strategy.

- An external subtitle that cannot be read now warns `SubtitleSourceUnreadable`, naming the
  address and the reason, instead of arriving as a track deselection with a sentence in it.

## [0.0.23] - 2026-09-06

The default playback packages now include HTTP/HTTPS transport and the libass subtitle
typesetter. Compose applications can use one complete dependency, while custom applications can
select presentation independently. KiteFFmpeg stays at 0.2.0.

ASS and SSA subtitles now render through libass on Android, iOS, macOS, Linux, Windows, the
desktop JVM and the web: moving signs, animated transforms, karaoke fills, clips and drawings all
draw as authored, and they re-render every video frame while they move. Fonts attached to a
Matroska file are loaded for the track, and an application can add its own. The built-in Kotlin
styling remains the fallback where no typesetter is installed.

### Upgrading from 0.0.22

- For complete Compose playback, use `io.github.yuroyami:kiteplayer-compose:0.0.23`.
  `kiteplayer-compose-ui` now supplies presentation only. Existing consumers that also use the
  default factory must switch to `kiteplayer-compose` or add `kiteplayer` alongside the UI module.
- `kiteplayer-mobile` remains a convenience alias. Default factory and renderer binding package
  names are preserved even though their implementations moved into dedicated modules.
- `NetworkConfig` gains `autoResolve`, default true, changing generated data-class method
  signatures. Recompile consumers. Set it false to preserve backend-only URI handling when no
  explicit resolver is configured.
- `AndroidPlayerViewRendererFactory.create` takes a third callback, `onScaleMode`, so a renderer
  adapter can tell the view which scale mode is ruling. A custom Android adapter must add the
  parameter; the bundled one already does.
- `MediaIoResolver` keeps its original abstract method and gains a default overload accepting
  per-item headers. Existing Kotlin implementations remain source compatible; recompile them.
- Installed transport providers are selected automatically by both default and direct core
  factories. Explicit byte sources and resolvers retain precedence. Native/web discovery depends
  on the pinned Kotlin toolchain and its initialization behavior.
- `kiteplayer-libass` is now published and included by `kiteplayer`, `kiteplayer-mobile` and
  `kiteplayer-compose`. An ASS or SSA track is typeset by libass when the module is present.
  `SubtitleConfig.typesetting = false` keeps the built-in styling. `SubtitleConfig` also gains
  `fonts`, and `PlayerSnapshot` gains `subtitleTypesetter`; both change generated data-class
  method signatures, so recompile consumers.
- `SubtitleStyleOverride` does not apply to a typeset ASS track. The authored typesetting is drawn
  as written; the viewer's size (`setSubtitleScale`) and position (`setSubtitlePosition`) still apply.
- The desktop JVM jar bundles the libass adapter for macOS arm64, Linux x64, Linux arm64 and
  Windows x64. On another desktop the player warns once with `TypesetterUnavailable` and keeps
  the built-in styling.
- On the web, libass is a separate module, `kiteass.mjs` beside `kiteass.wasm`, hosted by the
  page the way the codec module is. Both files come as the `web` zip attached to the wasmJs
  artifact (`kiteplayer-libass-wasm-js-0.0.23-web.zip`); unpack it beside `index.html`. The first
  ASS track loads `./kiteass.mjs` on its own; `KiteLibassWeb.load(url)` or
  `KiteLibassWeb.attach(module)` covers a page that hosts it elsewhere. There is no system font in
  a browser: supply fonts as container attachments or through `SubtitleConfig.fonts`.

### Added

- `kiteplayer`, the complete non-Compose playback entry point, and `kiteplayer-view-bindings`,
  which supplies renderer adapters without depending on playback construction or networking.
- Publishable `kiteplayer-network` artifacts and automatic HTTP/HTTPS provider registration.
- An opt-out for automatic transport discovery and per-item header forwarding to resolvers.
- `kiteplayer-libass`, published for Android (arm64-v8a, armeabi-v7a, x86_64), iOS, macOS,
  Linux, Windows, the desktop JVM and the web, with libass 0.17.4, HarfBuzz, FreeType and FriBidi
  linked in. Typeset ASS tracks re-render per video frame, so animated typesetting moves (#37,
  #38, #39, #124).
- `SubtitleTypesetter`, `SubtitleTypesetterProvider` and `SubtitleTypesetters` in the core
  service interfaces, so another typesetting engine can be installed the same way.
- `PlayerMediaSource.attachments` and `MediaAttachment`: the FFmpeg source exposes Matroska
  attachments, and attached fonts reach the typesetter before the track opens.
- `SubtitleConfig.typesetting`, `SubtitleConfig.fonts` and `SubtitleFont` for opting out of
  typesetting and for supplying fonts from the application.
- `PlayerSnapshot.subtitleTypesetter` names the engine drawing the selected subtitle track.
- A typesetting corpus test that renders each script whole and streamed and requires identical
  bytes, the exit criterion of the libass work (#40).

### Changed

- `kiteplayer-compose` is the recommended complete Compose entry point, including playback,
  networking, both renderers and their switcher. Android apps can still use XML views alongside it.
- `kiteplayer-compose-ui` no longer pulls in the default player factory or network stack.
- Automatic HTTP readers own their clients, including cleanup after a failed open.
- Installation examples identify alternative entry points and explain the subtitle dependency path.
- The libass module's C driver is shared by every binding, so the Kotlin/Native, JNI and web
  paths cannot disagree about a pixel; the change detection now also notices a picture that
  emptied or came back, which libass' own verdict does not report after a cleared track.
- The web renderer uploads a subtitle overlay in one crossing per image instead of one JavaScript
  call per byte, which is what makes per-frame typesetting affordable there.
- API documentation still builds on push; website deployment now requires an explicit workflow run.

### Fixed

- URLs without a path hide their hostname and embedded credentials in diagnostic output (#115).
- Replacing a sleep timer during its fade restores normal volume (#121).
- SRT/WebVTT files with many zero or reversed duration cues avoid quadratic processing while
  preserving their repaired cue timing (#120).
- Per-item HTTP headers reach the automatically selected HTTPS transport (#49).
- `setVideoScale` changes the picture on the Android `KitePlayerView`. The view sized its
  Surface from the video's own shape, so fill and stretch looked like fit, and with a hardware
  decoder writing straight into that Surface there was nothing else to change. The view now
  sizes the Surface from the mode and crops the overhang, and subtitles stay inside the view.

## [0.0.22] - 2026-09-04

The first release after the first one. Audio grew an equaliser, a loudness meter, balance and
ReplayGain. The queue can be edited while it plays. Subtitles gained a second track and a style
override. There is a sleep timer, chapter navigation, position markers, and a way to save where
playback was and put it back.

```kotlin
implementation("io.github.yuroyami:kiteplayer-mobile:0.0.22")
implementation("io.github.yuroyami:kiteplayer-compose-ui:0.0.22")
implementation("io.github.yuroyami:kiteplayer-core:0.0.22")
```

This release needs [KiteFFmpeg 0.2.0](https://github.com/yuroyami/KiteFFmpeg/releases). Gradle
pulls it in for you.

### Upgrading from 0.0.21

- **On desktop, the default render path is the native view, and Compose content drawn over the
  video does not receive clicks there.** This has been true since 0.0.21 and was never written
  down. macOS routes a click to the topmost native view, so a control that overlaps the picture is
  painted and never pressed. Either put those controls in a borderless window owned by the video
  window, or ask for `KiteRenderPath.ComposeCanvas` explicitly, which takes input normally at the
  cost of following the UI's frame rate. Controls beside the video are unaffected. Android and iOS
  are unaffected.
- **`AudioConfig`, `PlayerConfig` and `PlaybackStats` gained fields**, so their generated `copy()`
  signatures moved. Named arguments keep working. A positional `copy()` on any of the three needs
  a recompile, and probably an edit.
- **Nothing else asks anything of you.** KiteFFmpeg moves to 0.2.0 underneath, and nothing here
  calls the one API it broke.

### Added

Audio:

- **A ten band equaliser.** `EqualizerSettings` with per band gains and a preamp, set live through
  `player.equalizer` or up front in `AudioConfig`. A flat setting costs nothing: the stage is
  skipped entirely.
- **Stereo balance**, through `player.balance`, from full left to full right.
- **ReplayGain.** `AudioConfig.replayGain` honours the loudness the encoder already measured, with
  a preamp and a fallback for files that carry no tag. It cannot clip: the gain goes through the
  same limiter as the volume boost.
- **Volume above 1.0**, up to 2.0, through a limiter that lives with the gain rather than after it.
  `AudioConfig.volumeCeiling` sets the ceiling.
- **A loudness meter.** `LoudnessMeter` answers integrated loudness to ITU-R BS.1770-4, the same
  number EBU R128 and ReplayGain 2.0 are defined against, plus the sample peak and how many blocks
  survived the standard's two gates. `AudioAnalysis.measureLoudness(item)` measures a whole file in
  one call, for a normalise pass before playback starts.
- **The Android audio session id** reaches the application through `player.platformSessionId`, so
  the platform equaliser and visualiser APIs can attach to it.
- **A warning when a decoder changes format mid stream**, as
  `PlaybackWarning.AudioSourceFormatChanged`, carrying the old and new sample rate and channel
  count.

Playback and the queue:

- **Edit the queue while it plays.** `addToQueue`, `removeFromQueue`, `moveInQueue` and
  `clearQueue`, all safe against the item currently playing.
- **Shuffle**, as an order laid over the queue rather than a reorder of it, so turning it off puts
  the original order back. `setShuffle(enabled, seed)`.
- **Chapter navigation**, `nextChapter()` and `previousChapter()`.
- **Markers that fire on crossing.** `setMarkers(list)` and `PlayerEvent.MarkerReached`, for
  chapter art, ad breaks or anything else pinned to a position.
- **A sleep timer.** `SleepTimer.After`, `SleepTimer.At` or `SleepTimer.EndOfItem`. It fades the
  volume down, pauses, then gives the level back, so resuming is not silent.
- **Save and restore where playback was.** `player.memento()` returns a `PlayerMemento` with the
  item, position, tracks and speed; `player.restore(memento)` puts it all back. Serialise it and
  you have resume across app launches.
- **Turn video off without closing the file.** `setVideoEnabled(false)` parks video decoding in
  place and resumes it where it was, with no reopen and no seek. Use it for audio only playback of
  a video file, or when the window is hidden.
- **Two players in one process**, proven by a test rather than assumed.

Subtitles:

- **A second subtitle track**, drawn at the top of the frame, through
  `selectSecondarySubtitle(trackId)`. For a translation over the original, or dialogue over signs.
- **A style override**, `SubtitleStyleOverride`, with a background box, on all three rasterisers.
- **The cues showing right now**, published as `player.subtitleCues`, so an application can render
  them itself or show them somewhere other than over the video.

Snapshots and analysis, in `kiteplayer-ffmpeg`:

- **Encode a captured frame** to PNG or JPEG in one call: `frame.encode(SnapshotFormat.Png)`.
- **Thumbnails at positions**, scaled and encoded in one call, through `Thumbnails`. For a seek bar
  preview strip or a chapter grid.
- **A waveform of any item**, peaks and RMS per bucket, through `Waveforms`.
- **A typed filter chain** attaches to a media item without the low level opt in.

View and platform:

- **A secure surface flag** on the Android view, `KitePlayerView.secure`, which blocks screenshots
  and screen recording of the video.
- **Picture in picture parameters**, `KitePlayerView.pictureInPictureParams(...)`, and an honest
  capability answer from `KitePlayerPlatform.supportsPictureInPicture`.
- **The video announces itself to screen readers**, through `accessibilityStateText(...)` and
  `DEFAULT_VIDEO_ACCESSIBILITY_LABEL`.
- **Renderers report the display's refresh interval**, and the Android renderer asks the display to
  match the video's frame rate.
- **A frame presented event**, `PlayerEvent.FramePresented`, best effort on every platform that can
  observe one. Off by default; turn it on with `PlayerConfig.frameEvents`.

Diagnostics:

- **A structured log sink.** `KiteLog.installStructured(sink)` delivers events as a name and a map
  of fields instead of a formatted line, so they can go straight into an existing logger. URIs are
  redacted by default; `KiteLog.redactUris` turns that off.
- **Five new numbers on `PlaybackStats`**: `ioBytesTotal`, `ioBytesPerSecond`, `decodeTimeP50`,
  `decodeTimeP95` and `presentLatenessP95`.

### Changed

- **KiteFFmpeg 0.1.0 to 0.2.0.** The binary break in the three `copy()` signatures above is
  deliberate on a 0.x library.

### Fixed

- **Seeks are roughly twice as fast.** A paused seek used to spend almost all of its time waiting
  rather than reading: five workers parked one after another, each sleeping out its own 50 ms poll,
  and the landed frame was then noticed at another 50 ms interval. Now every worker is asked to
  park before any acknowledgement is awaited, and the waiters are woken rather than polled.
  Measured on real media, p50: keyframe seek 207 ms to 86, precise 257 to 102, keyframe then
  refine 425 to 199.
- **An idle worker wakes on the park request** instead of sleeping out its poll interval first.
- **A zero frame request silences the audio buffer** on Apple output. CoreAudio can ask for zero
  frames, and the buffer was handed back untouched, so a host that renders it anyway replayed the
  previous period of audio.
- **The cue lookup binary searches instead of scanning from the start.** Free on a film with a few
  hundred lines, not free on a dense typeset ASS track running to about seventy thousand cues,
  where every subtitle pass near the end cost seventy thousand comparisons.
- **The subtitle parsers survive two thousand mutations of every fixture.**

## [0.0.21] - 2026-08-31

KitePlayer's first public release.

KitePlayer is a media player for Kotlin Multiplatform, written in Kotlin from the ground up. It does not wrap ExoPlayer, AVPlayer or libmpv: the engine is pure Kotlin in `commonMain`, so seeking, A/V sync and state behave identically on every platform. Decoding is FFmpeg, through [KiteFFmpeg](https://github.com/yuroyami/KiteFFmpeg), and FFmpeg is compiled into the artifacts, so there is nothing to install and no build setup.

All coordinates are `io.github.yuroyami`, all at `0.0.21`, all added to `commonMain.dependencies`:

```kotlin
// Android + iOS, the default stack: player, decoders, audio, native video view.
implementation("io.github.yuroyami:kiteplayer-mobile:0.0.21")

// Building your UI in Compose? This adds the video composable, and lets you
// switch between native-surface and Compose-drawn rendering at runtime.
implementation("io.github.yuroyami:kiteplayer-compose-ui:0.0.21")

// Just the engine, bring your own decoder and output. Depends only on coroutines.
implementation("io.github.yuroyami:kiteplayer-core:0.0.21")
```

Twelve modules are published in total, two of them the deprecated umbrellas; the [README](https://github.com/yuroyami/KitePlayer#modules) maps each one to what it is for.

### Added

- Playback: open, play, pause, seek (exact, or keyframe-then-refine so scrubbing feels instant), frame stepping, A-B looping. Playlists with next and previous: each item opens as the last one ends; a gapless handoff is not there yet.
- Speed from 0.25x to 4x with pitch preserved, or unpreserved for the tape effect.
- Picture control: fit, fill, stretch, brightness, contrast, saturation, hue, forced aspect, zoom and pan, all live.
- Subtitles: SubRip, WebVTT and SubStation Alpha, styled, positioned and delayed live; external files loadable mid-playback.
- Tracks and chapters: selection, chapter navigation, container metadata.
- Screenshots, live renderer swapping, typed errors instead of silent failures, and four `Flow`s (`state`, `progress`, `stats`, `events`).
- Hardware decode where the platform has it: VideoToolbox on Apple, MediaCodec on Android, with software fallback proven on real files.

### Where it runs

Plays real media on Android, iOS, macOS arm64, desktop JVM (macOS arm64 host) and Linux. Windows x64 builds and links but has not been run.
