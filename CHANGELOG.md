# Changelog

All notable changes to KitePlayer are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

**Versioning policy:** KitePlayer is pre-1.0. During 0.x, minor versions may contain breaking API changes; they are called out here when they happen. From 1.0 on, breaking changes only land in major versions.

The entries under a version are drafted by `scripts/release-notes.sh`, which groups the commits since the previous tag by their prefix. `publish.yml` refuses a version that has no section here.

## [Unreleased]

The 0.0.23 changes below are prepared but have not been published.

## [0.0.23] - Unreleased

The default playback packages now include HTTP/HTTPS transport. Compose applications can use
one complete dependency, while custom applications can select presentation independently.
KiteFFmpeg stays at 0.2.0. No libass integration or publication is part of this version.

### Upgrading from 0.0.22

- For complete Compose playback, use `io.github.yuroyami:kiteplayer-compose:0.0.23`.
  `kiteplayer-compose-ui` now supplies presentation only. Existing consumers that also use the
  default factory must switch to `kiteplayer-compose` or add `kiteplayer` alongside the UI module.
- `kiteplayer-mobile` remains a convenience alias. Default factory and renderer binding package
  names are preserved even though their implementations moved into dedicated modules.
- `NetworkConfig` gains `autoResolve`, default true, changing generated data-class method
  signatures. Recompile consumers. Set it false to preserve backend-only URI handling when no
  explicit resolver is configured.
- `MediaIoResolver` keeps its original abstract method and gains a default overload accepting
  per-item headers. Existing Kotlin implementations remain source compatible; recompile them.
- Installed transport providers are selected automatically by both default and direct core
  factories. Explicit byte sources and resolvers retain precedence. Native/web discovery depends
  on the pinned Kotlin toolchain and its initialization behavior.

### Added

- `kiteplayer`, the complete non-Compose playback entry point, and `kiteplayer-view-bindings`,
  which supplies renderer adapters without depending on playback construction or networking.
- Publishable `kiteplayer-network` artifacts and automatic HTTP/HTTPS provider registration.
- An opt-out for automatic transport discovery and per-item header forwarding to resolvers.

### Changed

- `kiteplayer-compose` is the recommended complete Compose entry point, including playback,
  networking, both renderers and their switcher. Android apps can still use XML views alongside it.
- `kiteplayer-compose-ui` no longer pulls in the default player factory or network stack.
- Automatic HTTP readers own their clients, including cleanup after a failed open.
- Installation examples identify alternative entry points and explain the subtitle dependency path.
- API documentation builds on push; deployment requires an explicit manual workflow dispatch.

### Fixed

- URLs without a path hide their hostname and embedded credentials in diagnostic output (#115).
- Replacing a sleep timer during its fade restores normal volume (#121).
- SRT/WebVTT files with many zero or reversed duration cues avoid quadratic processing while
  preserving their repaired cue timing (#120).
- Per-item HTTP headers reach the automatically selected HTTPS transport (#49).

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
