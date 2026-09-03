# Changelog

All notable changes to KitePlayer are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

**Versioning policy:** KitePlayer is pre-1.0. During 0.x, minor versions may contain breaking API changes; they are called out here when they happen. From 1.0 on, breaking changes only land in major versions.

The entries under a version are drafted by `scripts/release-notes.sh`, which groups the commits since the previous tag by their prefix. `publish.yml` refuses a version that has no section here.

## [Unreleased]

### docs

- the install section spoke only to mobile, and the release notes to nobody
- the tree and its prose agree again
- the feature table had fallen ten calls behind the code
- the feature table gains the shuffle row
- the API reference publishes to GitHub Pages

### audio

- volume boosts to 2.0 through a limiter that lives with the gain
- the Android audio session id reaches the application
- the loudness the encoder measured is honoured, and cannot clip
- stereo balance, the last thing the facade called absent
- a ten-band equaliser, free when it is flat
- a loudness meter, held to the standard's own numbers

### core

- video decoding parks and resumes in place, no reopen
- a sleep timer that fades, pauses, then gives the level back
- the subtitle cues showing now are published to the application
- the queue can be edited while it plays
- shuffle, as an order over the queue rather than a reorder of it

### subtitles

- the cue lookup binary searches instead of scanning
- the parsers survive two thousand mutations of every fixture

### diagnostics

- a structured log sink, and URIs redacted by default

### stats

- bytes read and bytes per second, from the cache that already saw them

### ci

- dependabot watches actions and gradle

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
