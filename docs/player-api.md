# Controlling the player

This page lists what a `KitePlayer` can do, grouped by area. Every call works while media plays,
and the player publishes the result on `player.state`, which lets a UI read it back. The
[API reference](https://yuroyami.github.io/KitePlayer/) has the full signatures.

| Area | What to call |
| --- | --- |
| **Playback** | `open`, `play`, `pause`, `stop`, `seek`, `requestSeek`, `stepFrame`, `close`, `closeAndAwait` |
| **Queue** | `openQueue`, `next`, `previous` and `setLoop`. `addToQueue`, `removeFromQueue`, `moveInQueue` and `clearQueue` work while the queue plays. Items follow each other on the same audio device with no gap. `QueueConfig.gapless = false` turns that off, and [Gapless queue playback](gapless-queue.md) says when an item opens from scratch instead. `QueueConfig.crossfade` fades one song into the next. `QueueConfig.onItemFailure` makes the queue skip an item that does not open, instead of stopping on it |
| **Playlists** | `openPlaylist` opens an M3U, PLS or XSPF file as the queue. It also opens an album's cue sheet as the album's tracks, which play from one open of the file with no sample lost or repeated. `readPlaylist` gives you the items first, so you can filter or reorder them |
| **Shuffle** | `setShuffle`. The items stay in their places, and `PlayerSnapshot.queueOrder` tells you what plays next. `QueueConfig.reshuffleEachLap` draws a new order on each lap under `LoopMode.All` |
| **Speed** | `setSpeed`, from 0.25x to 4x with the pitch kept. `setPreservePitch(false)` lets the pitch change with the speed, as on a tape. `setPitch` moves the pitch by up to 12 semitones, and the speed stays the same |
| **Sync** | `setExternalClock` makes playback follow a clock that your app owns, for watching together. The player closes a small difference with a speed change of at most 0.5 percent, with the pitch kept. `PlayerConfig.externalClock` sets that limit. The player closes a large difference with one seek. Play and pause stay with your commands |
| **Sound** | `setVolume`, `setMuted`, `setBalance`, `setStereoMode` (mono, one side only, or swapped), `setNightMode` (quiet speech up, loud effects down), `setDialogueLevel` (the center of a downmix up or down), `setSkipSilence` (every pause longer than a fifth of a second cut down to that, on media with no picture), `setEqualizer` (ten bands and a preamp), `setAudioDelay`, `setSleepTimer` (with a fade), `setVideoEnabled(false)` for audio only |
| **Loudness** | `PlayerConfig.audio.volumeCeiling` lets the volume go up to 2.0. Above 1.0, the player softens loud peaks instead of clipping them. `PlayerConfig.audio.replayGain` applies the file's own ReplayGain tags. ReplayGain is off by default |
| **Surround** | Multichannel audio folds into the speakers the device has. `PlayerConfig.audio.upmix = UpmixMode.Surround` also plays mono and stereo from the other speakers of a surround device. Upmix is off by default |
| **Tracks** | `selectTrack` picks an audio, video or subtitle track. `selectVariant` picks an HLS or DASH variant, `selectProgram` picks a channel of a transport stream, and `selectEdition` picks an edition of a Matroska file, such as another cut of a film (see [Editions and linked files](media-sources.md#editions-and-linked-files)) |
| **Picture** | `setVideoScale` (fit, fill, stretch), `setVideoAdjustments` (brightness, contrast, saturation, hue), `setVideoTransform` (forced aspect, zoom, pan, quarter turns, mirrors), `setRenderQuality` (dithering, debanding, the scaler and the animation upscaler), `setFlashGuard` (dims flashing video, see [Dimming flashing video](video-flash-guard.md)) |
| **360 degree video** | `TrackInfo.projection` says whether the video is a 360 degree one. `setSphericalView` turns the view into it and sets how wide the view is, or shows the whole picture flat. `KiteVideo` draws the view on the desktop JVM, iOS, Android 13 and later and the web; the native views draw the picture flat for now and warn `PlaybackWarning.ProjectionUnavailable`. See [360 degree video](spherical-video.md) |
| **HDR** | `setHdrPolicy`. `HdrPolicy.ToneMap` tone maps everywhere. `PlaybackWarning.HdrToneMapped` tells you when the player tone maps, and `PlayerSnapshot.videoDynamicRange` tells you what the screen shows. `TrackInfo.dolbyVision` names the profile of a Dolby Vision track |
| **Subtitles** | `selectSecondarySubtitle`, `addExternalSubtitle`, `seekToSubtitleLine` (the line showing, the previous or the next), `stepSubtitleDelay` (a line forward or back), `setSubtitleScale`, `setSubtitleDelay`, `setSubtitlePosition`, `setSubtitleStyle`, `setSubtitleSafeArea`, `setForcedPicturesOnly`, and `subtitleCues` to draw the lines yourself. [Subtitles](subtitles.md) describes the formats and the choices |
| **Sections** | `setAbLoop` repeats between two points. `setMarkers` fires an event when playback crosses a position |
| **Chapters** | `chapterAt`, `seekToChapter`, `nextChapter`, `previousChapter` |
| **Resume** | `memento()` saves the item, position, tracks and speed. `restore(memento)` puts them back |
| **Screenshots** | `captureFrame`. `kiteplayer-ffmpeg` encodes the frame to PNG or JPEG, and makes thumbnails and waveforms |
| **Recording** | `startRecording` copies what the player reads into a Matroska file, with no re-encode. `stopRecording` finishes the file. A seek ends a recording. The web build cannot record |
| **Picture-in-picture** | `KitePlayerPictureInPicture.createOrNull` on iOS, macOS, the desktop JVM and the web, then `start` and `stop`. On the desktop JVM it opens a floating window. On Android, call `KitePlayerView.enterPictureInPicture(activity)` |
| **Rendering** | `attachRenderer`, `detachRenderer`, swappable while media plays. `attachRendererAndAwait` refuses a renderer that cannot show the running decoder's frames, and keeps the renderer that was there before |
| **Diagnosis** | `diagnosticsDump`, `warningHistory`, `supportBundle`, and `KiteLog` as the one logging seam, silent by default. `KiteTrace` records a timeline that Chrome's trace viewer and Perfetto open, also silent by default |

## State and events

Flows tell your UI what is happening: `state`, `progress`, `stats`, `events`, `subtitleCues` and
`coverArt`. `events` can drop an event when one collector falls 64 events behind, so collect
`losslessEvents` when you must see every event. `position()` reads the current time without
collecting anything.

The player refuses anything it cannot do with a typed error, instead of accepting the call and
ignoring it. Two players in one process work.

## Seeking

`seek` suspends until the seek has landed. `requestSeek` returns at once, which suits a seek bar
that the user drags.

`SeekMode.Precise` is the default. `SeekMode.KeyframeThenRefine` shows the nearest keyframe at once
and replaces it with the exact frame a moment later, which makes scrubbing feel instant on large
files.

## Audio devices

On the desktop JVM and on macOS, you choose the audio output device when you build the player.
`audioOutputDevices()` on `DesktopOutputBackend` or `AppleOutputBackend` lists the devices, and
`withAudioOutputDevice(id)` returns the backend bound to one, for `PlayerConfig.backends`. A bound
player never moves to another device. When its device is gone, the open fails with
`PlaybackError.AudioDeviceUnavailable`, and so does playback when the device disappears. On Android
and iOS, the operating system owns the route.

## The display

While a video plays on screen, the display stays awake. Every view, `KitePlayerVideo`, the Mac's
`AppKitVideoRenderer` and the web's canvas renderers hold it, and let it sleep at a pause, at the
end, or with sound only. Pass `keepDisplayAwake = false` to turn that off. The desktop JVM has no
way to hold its display, so there it does nothing.

## Screen readers

The Android, iOS and desktop views, and both paths of `KitePlayerVideo`, tell a screen reader that
they are the video and what the player is doing, for example "Playing, 1:23 of 4:56".
`accessibilityVideoLabel` and `accessibilityStateFormat` take translated words. `KiteVideo`, the
bare canvas, gets its semantics from the modifier you pass. On the web, the page owns the canvas and
labels it.
