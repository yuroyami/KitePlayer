# Changelog

All notable changes to KitePlayer are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

**Versioning policy:** KitePlayer is pre-1.0. During 0.x, minor versions may contain breaking API changes; they are called out here when they happen. From 1.0 on, breaking changes only land in major versions.

The entries under a version are drafted by `scripts/release-notes.sh`, which groups the commits since the previous tag by their prefix. `publish.yml` refuses a version that has no section here.

## [Unreleased]

### Upgrading from 0.2.0

- `KitePlayer()` builds a player on the default stack (#383). It replaces
  `KitePlayerPlatform.createOrNull()`, which is deprecated with the other members of
  `KitePlayerPlatform`. `KitePlayer()` throws `PlaybackException` with `ConfigurationInvalid` where
  `createOrNull` returned null, so check `KitePlayer.isAvailable` first where that can happen. It
  also keeps a backend that `PlayerConfig.backends` names, which `createOrNull` replaced.
  `KitePlayer.availability`, `KitePlayer.isAvailable` and `KitePlayer.supportsPictureInPicture`
  replace the members of the same names. `rememberKitePlayer()` in `kiteplayer-compose` builds a
  player that closes when its composition leaves.
- A `KitePlayerView`, `KitePlayerUIView` or `KitePlayerAwtView` with no `rendererFactory` of its
  own now uses `PlayerViewDefaults.rendererFactory`, which `KitePlayer()` sets (#384). So
  `view.player = player` is the whole setup, and `installMobileRenderer()` or
  `installDesktopRenderer()` is needed only for a player built with `KitePlayer.create`. A view that
  relied on staying headless with no factory now shows the picture of a default player.
- Media may open before the view or the Compose video exists (#384). A player that decoded without
  a renderer moves to the renderer's own decoder when one arrives, once per item, by rebuilding the
  video path at the current position. Before, it kept the backend path, which copies every frame,
  for the whole item. A rebuild while paused now shows the picture at the current position also at
  the very start, where it used to show nothing until play.
- `player.attachMediaSession(...)` creates the media session and owns the rest of the system
  integration (#385). On Android it takes a `Context` and adds background handling and audio focus
  by default, and the media notification when `MediaNotificationOptions` are given. On iOS it adds
  background and interruption handling. Closing the session closes its parts in the right order,
  and the session closes itself when the player closes. `KitePlayerPlatform.attachMediaNotification`,
  `attachBackgroundHandling` and `attachInterruptionHandling` are deprecated. A session built with
  the `KitePlayerMediaSession` constructor behaves as before.
- `Dash.mediaItemFor` plays a manifest of fMP4 or MPEG-TS segments through the HLS path (#295).
  Separate video and audio sets now play together instead of being refused, the item seeks, and a
  live manifest plays. The item's reader therefore serves an HLS master playlist, not the segment
  bytes, so code that read `item.io` directly sees a playlist. The manifest model gains fields with
  defaults, so a constructor or `copy` call compiled against 0.2.0 must be compiled again.
- A DASH address plays through the default stack with no call to `Dash.mediaItemFor` (#400). The
  automatic transport, and `KtorMediaIoResolver`, recognise a manifest by its `application/dash+xml`
  type, by a path that ends in `.mpd`, or by its root element when the server sends it as text or
  bytes, and play it as `Dash.mediaItemFor` plays it, with `DashUrlPolicy.Default`. The item's
  headers reach the manifest and the segments of its origin. The reader these resolvers return for
  such an address serves an HLS master playlist, not the manifest.
- A WebM DASH manifest plays through the HLS path, as fragmented MP4 does (#401). Separate VP9 or
  AV1 and Opus or Vorbis sets now play together instead of being refused, the item seeks, a live
  WebM manifest plays, and a single WebM file plays as segments that its `Cues` name, read by byte
  range. The one-stream reader is left for containers other than fragmented MP4, MPEG-TS and WebM.
- A DASH subtitle set of TTML, or of TTML or WebVTT in MP4 segments (`stpp`, `wvtt`), plays as a
  subtitle rendition (#402). Such sets used to be left out. The DASH reader serves them to FFmpeg as
  WebVTT, with their text, line breaks, italic, bold and underline, on the picture's timeline.
- A DASH manifest of several Periods plays as one presentation, as ad insertion and chapters
  stitch them (#403). It used to be refused with `DashUnsupportedException`, which now happens only
  when its segments are in a container the HLS path does not take. Time runs on across each
  boundary although each Period's media time starts again, an fMP4 Period of another picture size
  decodes at its own, and a live manifest that a refresh gives a new Period plays on into it.
- A live DASH manifest counts its window on the time of day that its `UTCTiming` names, by
  `direct`, `http-xsdate`, `http-iso` or `http-head`, instead of the device's clock, which could be
  seconds off and ask for segments that did not exist yet (#404). With none that answers, the
  device's clock stands and the log says so. A refresh fetches the manifest from its `Location`,
  and a segment template stops at its `endNumber`.
- DASH audio and subtitle tracks carry their set's `Label` as their title, and their roles reach
  the player: `main` sound is the default, `forced-subtitle` is forced, and `caption` and
  `description` are marked as accessibility tracks (#404). An encrypted manifest is refused with
  `DashUnsupportedException` instead of decoding to noise, and an encrypted set beside clear ones
  is left out.
- KitePlayer builds on KiteFFmpeg 0.5.0. Its web module carries FFmpeg's HLS reader and the new
  readers beside it, from the Dolby Vision RPU to Matroska editions, which makes `kite.wasm` and
  `kite.mjs` 49,039 bytes bigger after gzip than 0.4.0's, 1,536,396 bytes in all, so the web size
  check's budget for them rises from 1.42 MiB to 1.47 MiB (#523). KiteFFmpeg's own changelog lists
  its changes.
- A WebM DASH presentation that has been read to its end seeks again. FFmpeg's Matroska reader
  used to answer end of file for ever after, so a seek returned and no packet followed, with one
  Period or several. KiteFFmpeg 0.5.0 carries the FFmpeg fix.
- The first cue of a DASH or HLS subtitle set arrives. FFmpeg starts a subtitle rendition where
  the reading has got to and used to drop every cue that began before that moment, so the cue at
  zero was lost even while it was still on screen. KiteFFmpeg 0.5.0 carries the FFmpeg fix, which
  keeps a cue that is still showing.
- A length FFmpeg guessed from the bit rate, for an ADTS AAC file or an MP3 without its Xing
  header, no longer cuts seeks, start positions or the external clock, and once playback passes it
  or the item ends, the snapshot's length is what really played (#422). `PlayerSnapshot` gains
  `durationIsEstimate`, so a constructor or `copy` call compiled against 0.2.0 must be compiled
  again, and `PlayerMediaSource` gains `durationIsEstimate`, false unless a source says otherwise,
  so a source of your own keeps compiling. It needs KiteFFmpeg 0.5.0, which reads where the length
  came from.
- `PlaybackError` gains `SchemeUnsupported`, for an address whose scheme the build has no way to
  open, so a `when` that lists every error needs the new branch (#395). `PlayerMediaSource` gains
  `realTime`, which is false unless a source says otherwise, so a source of your own keeps
  compiling.
- An address plays live over `rtsp`, `rtmp`, `udp` and `rtp`, a `udp` or `rtp` multicast group
  included, and a `.sdp` file plays the RTP session it describes, on every platform but the web
  (#395). RTSP tries UDP and falls back to TCP, and `rtsp_transport` in `openOptions` chooses one.
  A sender that goes silent fails the open within ten seconds, and the playback within ten over UDP
  or twenty over a TCP connection, on which FFmpeg waits twice, instead of holding either for ever.
  An address whose scheme FFmpeg here has no protocol for, such as `srt`, `rtmps` or `rtsps`, and
  on the web every address with no reader, fails with `SchemeUnsupported` before anything goes
  over the network, where it used to fail inside FFmpeg.
- A live sender over `rtsp`, `rtmp`, `udp` or `rtp`, or a `.sdp` session, is followed at a bounded
  delay (#395). The open starts the player as far behind as FFmpeg's stream discovery took, about
  two seconds over RTSP, and a stall that the network later makes good leaves it that much
  further behind for good. Now, whenever the delay is more than half a second past the buffer
  policy's ready duration, the player plays 1.1 times faster, keeping the pitch, until it is
  within a tenth of a second of it, so each second of extra delay clears in ten. It never does so
  for a file or an HLS or DASH stream, at a speed the caller chose, with the pitch correction off,
  or while it follows an external clock.
- An HLS track with no title of its own takes its rendition's `NAME` as its title, unless the name
  only repeats its language (#404). FFmpeg files that name under the stream's `comment`, where
  `PlayerStreamInfo.metadata` still shows it.
- An HLS playlist that nothing marks is recognised by its first bytes (#400). A reader whose bytes
  start with `#EXTM3U` plays through the HLS path when no format hint, HLS content type or `.m3u8`
  address says so, which an address with no extension sent as text or bytes never did. A format
  hint still wins.
- An HLS playlist's variables play (#452). KiteFFmpeg 0.5.0 puts in what `EXT-X-DEFINE` gives by
  `NAME` and `VALUE`, by `IMPORT` from the master playlist and by `QUERYPARAM` from the playlist's
  own address, so a token in the master playlist's address reaches every address that names it.
  The player now tells FFmpeg where a redirected playlist came from instead of rewriting the
  playlist's addresses itself, which made a variable that holds a whole address resolve against
  the redirect too and left `QUERYPARAM` reading the address before the redirect. A redirected
  playlist therefore reaches FFmpeg exactly as its server sent it. A reference to a variable that
  nothing defined fails the open with `NotMedia`, and the error names it.
- An open that FFmpeg refuses as invalid data fails with `PlaybackError.NotMedia`, carrying FFmpeg's
  reason as its detail, instead of `SourceUnavailable`, which said the bytes could not be reached
  and invited a retry that fails the same way (#452). `NotMedia`'s message now ends with its detail.
  Thumbnails, waveforms and loudness still throw FFmpeg's own exception.
- The player tells the platform whether an item is music, speech or a film (#446), which some
  devices use to pick their equaliser, their virtual surround or their dialogue processing. Every
  item used to say film. `MediaItem.audioContent` is `AudioContent.Automatic` by default, which
  says film when the item shows a picture and music when it plays sound alone or under cover art,
  and `Music`, `Speech` or `Movie` says what the item is whatever it shows. On Android it is the
  content type of the audio track and of the audio focus request, and on iOS it is the audio
  session's mode: the default mode for music, `spokenAudio` for speech and `moviePlayback` for a
  film. It is fixed when the device opens, so it follows each item and a change of audio track.
  `PlayerSnapshot.audioContent` gives the answer, for an application that sets up its own audio
  session, and an `AudioSink` hears it through its new `setContent`, which does nothing by default.
- Opening or playing something silent leaves another app's music playing (#436). On Android an
  item with no audio track selected, such as a muted preview, asks for no audio focus, and gives
  back the focus an item before it held. On iOS the audio session is active from the open but
  mixes with other apps until something first plays, so opening a paused video no longer stops
  the Music app; the first play does. An item with no audio track already opened no device there.
- `AudioConfig.matchOutputChannels` chooses between two mixes of one language by the speakers
  that play them (#466). Off by default. When it is on, the track the usual rules choose gives way
  to one in the same language and of the same accessibility whose channel count is closer to the
  output's, so headphones get a film's stereo mix rather than its 5.1 folded down, and a 5.1
  receiver gets the 5.1. A commentary never takes the main mix's place. The output's count comes
  from the new `AudioSinkFactory.outputChannelCount`, null by default: iOS reads the route, the
  desktop the widest line its mixer opens, the web says stereo, and Android reads the media route
  once its backend has a `Context`, through `AndroidOutputBackend.withContext` or
  `AudioTrackSinkFactory(context)`. `PlayerStreamInfo.isCommentary` carries the container's
  commentary flag.
- Pausing, resuming, seeking and stopping no longer click on Apple, the desktop and the web (#486).
  The sound fades out over 5 ms before the device stops and fades in again after it starts, the way
  a volume change already walks, and the paused position is where the fade ended, so the resume
  carries on from the next sample. A volume or mute change made while paused is in place from the
  first resumed sample. A sink declares that it needs this through the new
  `AudioSink.cutsSoundOnStop`, false by default; Android's mixer fades a paused track itself.
- Sound past full scale is turned down smoothly instead of being clamped by the device (#504). A
  loud surround mix folded to stereo, or an equaliser boost, can add up past full scale, and the
  device then squares off each such peak, which is heard as crackle. The output now lowers the gain
  over 5 ms ahead of such a passage and gives it back gradually after it, reading ahead in the audio
  it already holds, so it adds no delay. Audio that never passes full scale is untouched, sample for
  sample. `PlaybackStats.audioLimitedFrames` counts the frames it lowered, and the diagnostics
  report shows it beside the underruns.
- A keyframe seek can land on the keyframe after its target, the nearer one, or the one in the
  seek's direction, not only the one before it (#496). In a file whose keyframes are far apart, such
  as a screen recording, a ten second skip forward used to land where it started or earlier.
  `KeyframeChoice` picks the rule, set with `PlayerConfig.keyframeChoice` or live with
  `KitePlayer.setKeyframeChoice`; `InSeekDirection` is what a skip button wants. The default stays
  the keyframe before, and the precise modes are unchanged. A source receives the choice
  through a new `seekToKeyframe` overload, whose default keeps the keyframe before.
- `KitePlayerWorker` runs the player in a web worker, so opening, decoding and drawing leave the
  page's thread free, and it plays `http`, `https` and `blob` addresses, which the page's own player
  cannot (#100). It is opt-in: the page's own `KitePlayer` is unchanged. The page serves a third
  module, the worker binary, whose `main` calls `runKitePlayerWorker()`. `kiteplayer-output` gains
  `WebWorkletAudio` and `workerOutputBackend`, the two halves of its sound.
- `KitePlayerWorker` has the rest of the player's calls now: the queue, frame steps, chapters,
  tracks, variants, external subtitles, every setter, `stats`, and the diagnostics dump, support
  bundle and warning history, which suspend there (#100). Each has the name and defaults of the
  `KitePlayer` member and throws what it throws. A setter is checked once, by the player in the
  worker, so a value it refuses arrives on `events` as `CommandRefused` instead of throwing at the
  call. An item crosses whole, filters, demux policy and external subtitles included, unless it or
  one of its subtitles has a reader of its own. `state.media` and `state.queue` hold the caller's
  own items.
- The worker player draws ASS subtitles with libass (#100). `KitePlayerWorker.start` gains
  `libassUrl`, `./kiteass.mjs` by default, which the worker starts loading at once without waiting
  for it, so the libass web zip a page unpacks beside `index.html` serves both players. A relative
  `codecUrl` is now read against the page's address, as `workerUrl` and `libassUrl` are, where it
  was read against the worker's. In `kiteplayer-libass`, a background load that lands after
  `KiteLibassWeb.load` has attached its module is set aside, where it used to send the tracks
  waiting for the module to the built-in styling.
- `KitePlayerWorker.pictureInPictureOrNull()` puts the canvas the worker draws on in a picture in
  picture window, with the two browser features `KitePlayerPictureInPicture` uses for the page's
  own player: the canvas moves into a document window, or a video element's window plays a live
  capture of it (#100). `KitePlayerPictureInPicture.createOrNull` gains an overload that takes the
  canvas with functions for its size, play and pause instead of a renderer and a player.
- A Java app on Android or the desktop can use the player without writing Kotlin (#394).
  `KitePlayerJava` in `kiteplayer` adds listeners called on an `Executor`, a `CompletableFuture`
  version of every suspending call, and millisecond versions of the calls that take a `Duration`.
  In `kiteplayer-core`, `KitePlayer.create` is static on the JVM, the config builders have public
  constructors and a public `build()`, and `Progress`, `PlayerSnapshot`, `PlayerEvent.SeekCompleted`
  and `Tracks` gain Java-readable `positionMillis`, `bufferedAheadMillis`, `durationMillis`,
  `landedAtMillis` and `selectedTrack(kind)`. `MediaItemBuilder`, the builder behind
  `mediaItem(uri) { }`, builds the item whose constructor Java cannot call: it has a public
  constructor and a public `build()`, each of its calls returns the builder so that Java can chain
  them, and it gains `headers(Map)`, `externalSubtitles(List)`, `startPositionMillis(Long)` and
  `demux(DemuxPolicy)`, while `title`, `artist`, `album` and `formatHint` take null. A
  `mediaItem { }` block compiles unchanged, but code compiled against 0.2.0 that calls the builder
  must be compiled again, because its calls returned nothing there (#420).
- The worker player's binary ships as `kiteplayer-wasm-js-<version>-web.zip` beside the wasmJs
  artifact, for a page to unpack beside `index.html` (#58). `KiteWebModules.codecModuleUrl` picks a
  multi-threaded codec module only on a cross-origin isolated page, before it is imported.
- `KitePlayer.awaitClose()` suspends until the player is asked to close, for helpers that go away
  with it (#385).
- `KitePlayer.requestSeek` replaces `seekLater`, which is deprecated (#386). It asks for a seek and
  returns at once, as `seekLater` did. The `KitePlayer` documentation now states which calls wait.
- `kiteplayer-compose-ui`, and so `kiteplayer-compose`, no longer puts `KitePlayerSurface`,
  `KiteVideo`, `rememberKiteVideoState` and `KiteVideoState` on an app's compile classpath (#387).
  `KitePlayerVideo` still uses both paths at runtime. An app that calls one of them directly adds
  `kiteplayer-compose-interop` or `kiteplayer-compose-video` to its dependencies.
- The deprecated `kiteplayer-phone` module is no longer built or published (#387). Its released
  versions stay on Maven Central. Depend on `kiteplayer` or `kiteplayer-compose` instead.
- `PlayerConfig { }` builds a config in a block, with nested `audio { }`, `subtitles { }`,
  `network { }`, `buffer { }` and `queue { }` blocks, so a nested setting does not need its type
  named (#388). `KitePlayer { }` builds a player from the same block. The data classes and their
  constructors stay as they were.
- HLS plays through the default stack (#209). An address that ends in `.m3u8`, an HLS content
  type, or `formatHint = "hls"` marks a playlist. A master playlist plays one variant, the one with
  the highest bitrate within the new `DemuxPolicy.maxBitrate` and `DemuxPolicy.maxVideoHeight`.
  The README's Network section lists what plays.
- `MediaIo` gains `location`, `contentType` and `openRelated`, each with a default that changes
  nothing. A reader that wraps another one by hand must pass the three on, or an HLS stream read
  through it cannot open its segments. Kotlin's `by` delegation passes them on already.
- `DemuxPolicy` gains `maxBitrate` and `maxVideoHeight`, which change its generated data-class
  methods, so recompile. `PlayerMemento.FORMAT_VERSION` is 5 because the memento stores them, and
  a build older than this one refuses a memento that this one wrote.
- `PlaybackWarning` gains `SegmentSkipped`, so a `when` that lists every warning needs the new
  branch.
- `Progress.bufferedRanges` is empty for an HLS stream, and `PlaybackStats.ioBytesTotal` counts the
  bytes of its segments and keys.
- `PlaybackWarning.HdrToneMapped` now fires on the Android GPU path of the Compose video, on the
  Android software surface and on the Compose canvas too (#23). A renderer repeats its report at
  most once a second, so every open of HDR media on the same renderer warns, not only the first.
  `AndroidSurfaceVideoRenderer` gains a `toneMapped` parameter, which changes its compiled
  constructor, so recompile.
- HDR10 and HLG show as HDR on a display that can show them (#68): through Metal on a Mac or an
  iPhone with extended range, and through `KitePlayerView` on an Android HDR display. Elsewhere they
  are tone mapped, as before. `HdrPolicy.ToneMap`, through `PlayerConfig.hdrPolicy` or
  `KitePlayer.setHdrPolicy`, keeps the old behaviour everywhere, and `PlayerSnapshot` gains
  `hdrPolicy` and `videoDynamicRange`, which says what the screen shows. `PlayerConfig`,
  `PlayerSnapshot` and `PlayerMemento` change their generated data-class methods, so recompile.
- `VideoRenderer` gains `setHdrPolicy`, with a default that does nothing, and `RendererEvent` gains
  `HdrShown`, so a `when` that lists every renderer event needs the new branch.
  `AndroidPlayerViewRenderer` gains `setDisplayHdr`, which `KitePlayerView` calls with what its
  display can show.
- A `setRenderQuality` call while media plays now reaches the renderer. Before, only the value in
  force when the renderer was attached arrived (#377).
- HDR tone mapping rolls off from the content's own peak: its MaxCLL, or its mastering display's
  peak, from the new `HdrStaticMetadata` on `PlayerStreamInfo.hdr` and `VideoFrame.hdr`. Before,
  every PQ picture was assumed to be mastered at 1000 nits, which flattened the highlights of a
  brighter master. On Android the stream's metadata also reaches MediaCodec as
  `KEY_HDR_STATIC_INFO` (#378). `PlayerStreamInfo` changes its generated data-class methods, so
  recompile.
- The Metal renderer turns a quarter-turned picture clockwise, as the display matrix says. Before,
  it turned 90 and 270 degrees the other way, so a portrait phone recording played upside down on
  macOS and iOS (#379).
- A mirrored video plays mirrored (#233). Before, a left-right mirror played upside down and an
  upside-down mirror played unflipped. `VideoFrame.mirrored` and `PlayerStreamInfo.mirrored` are
  new, and every renderer mirrors the picture left to right before it turns it. A renderer of your
  own that reads `rotationDegrees` should read `mirrored` too. MediaCodec cannot mirror what it
  writes to a Surface, so on the direct Surface a mirrored stream decodes in software.
  `AndroidGpuImageFrame` gains `mirrored`, which the Compose video applies.
- Picture in picture on Apple shows a turned or mirrored video the right way round, in a window of
  the turned shape. Before, `SampleBufferVideoRenderer` showed every picture as it is stored, so a
  portrait phone recording lay on its side in the small window. A turned or mirrored picture now
  takes one GPU pass for each frame, as text on screen already did (#380).
- On the desktop JVM, a player bound to one output mixer with `withAudioOutputDevice(id)` fails
  with `PlaybackError.AudioDeviceUnavailable` when that mixer disappears during playback, as on
  macOS. Before, it stayed Playing with no sound. The sink checks once a second that the mixer is
  still listed, so the failure comes within about a second. A player on the system default is
  unchanged (#308).
- A subtitle track whose decoder cannot open is dropped with `PlaybackWarning.TrackDeselected`, and
  the file plays without it. Before, the whole open failed. The web build has no subtitle decoders
  of the media library, so on the web a file whose default track was picture subtitles or captions
  did not open at all (#381).
- On macOS, when the last output device goes away, the `AudioDeviceChanged` warning says that no
  output is left. Before, it said that playback follows "device 0".
- A video decoder can declare its frames with `VideoDecoder.output`, a new `FrameShape`, and a
  renderer answers `VideoRenderer.accepts` (#102). At open the engine passes over a decoder whose
  frames the attached renderer cannot show. At attach it refuses a renderer that cannot show the
  running decoder's frames, keeps the renderer attached before, and warns `CommandRefused`.
  `KitePlayer.attachRendererAndAwait` returns once the attach is done and throws the new
  `PlaybackError.RendererIncompatible`, so a `when` that lists every error needs the new branch.
  Both new members have defaults, so existing decoders and renderers keep compiling and behave as
  before. The MediaCodec decoders of `kiteplayer-output` declare their frames.
- Playback can follow a clock the application owns (#91). `KitePlayer.setExternalClock` takes an
  `ExternalClock`, which answers the media position that should be audible at an instant. A small
  difference closes through a speed change of at most `PlayerConfig.externalClock.maxTrim`, 0.5
  percent by default, with the pitch kept. A jump is one precise seek. A clock that falls silent or
  stops moving leaves playback running on its own, and `PlaybackWarning.ExternalClockSilent` says so
  once. Play and pause stay with the commands. `KitePlayer.create` accepts `SyncMode.ExternalMaster`
  now. `PlayerConfig` gains `externalClock`, which changes its generated data-class methods, so
  recompile, and a `when` that lists every warning needs the new branch.
- The variants of an HLS master playlist are listed in `Tracks.variants`, as `StreamVariant`s with
  their bitrate, size, frame rate and codecs, and `Tracks.selectedVariant` says which one plays.
  `KitePlayer.selectVariant` plays another one from the current position, by opening the stream
  again on it, and keeps playing or stays paused. The choice is kept as the new
  `DemuxPolicy.variant`, which a memento stores too. `PlayerMediaSource` gains `variants` and
  `selectedVariant`, both with defaults. `Tracks` and `DemuxPolicy` change their generated
  data-class methods, so recompile (#376).
- An HLS stream changes its variant by itself (#376). It steps down when the stream reads slower
  than it plays, or when playback has waited 4 s for data, long before the 30 s stall timeout
  ends the session. It steps up when the network carries the next higher variant with half again
  to spare and the buffer is full. A step up waits 30 s after a step down, and twice as long after
  each step up that did not last. The new `PlaybackWarning.VariantLowered` reports a step down, so
  a `when` that lists every warning needs the new branch. A variant that the caller selected
  stays, and a step up never passes `DemuxPolicy.maxBitrate` or `maxVideoHeight`.
- `MediaIo.networkBitsPerSecond` reports how fast the network delivers a reader's bytes. The
  default answers null. `KtorMediaIo` measures it on its downloads, with the time a response
  waits for the player left out, and the step up reads it (#376).
- A TTML subtitle document resolves each named style once (#408). A style that named other styles
  was followed nine levels deep for every element that used it, so a style naming itself ten times
  took six seconds for one cue, in 155 bytes of input. A loop of style names is now cut at its first
  repeat, as TTML2 counts it an error, and one document follows at most 100,000 style names.
- `openQueue` and `addToQueue` take a copy of the list they are given (#409). The player kept the
  caller's own list, so an edit to it afterwards changed the queue and every snapshot already
  published, and `next()` then read past the end of the shorter list and failed the session.
  `KitePlayerJava.openQueueAsync` and `addToQueueAsync` copy on the caller's thread, before the
  call runs on another one. A snapshot's `queue` now refuses an edit, also through the
  `java.util.List` a Java caller sees.
- A long or multi-Period DASH presentation keeps every segment its playlists list (#405). The
  reader dropped the oldest of its converted subtitle segments past 4,096 and of its joined-Period
  segments past 8,192, counting every track together, while the playlists still listed them, so
  two subtitle languages of 70 minutes lost the start of the first, and a one-hour two-Period
  presentation lost a whole video bitrate. A static presentation now keeps every address for the
  reader's life, and a live one keeps every address that a track's current or previous playlist
  lists, and lets the rest go.
- The DASH reader lets go of the initializations it read for joined Periods and MP4 subtitles
  (#407). It kept every one until the reader was gone, close included, so a live session that kept
  adding Periods grew without end. An initialization now goes once nothing still listed refers to
  it, the oldest go when all of them pass 32 MB, one that went is read again when it is asked for,
  and closing the reader lets go of all of them.
- `RenderQuality.animationUpscaler` enlarges animation with a network trained on line art and flat
  colour (#67): Anime4K v3.2's CNN x2 networks by bloc97, MIT, ported as two tiers. `Fast` runs the
  small network, for phones, and `Quality` the medium one, for tablets and desktops. The Android
  GPU renderer runs it when the picture is drawn at more than 1.2 times its own size, doubling the
  picture before the scaler takes it the rest of the way, and skips it, with one log line, on a GPU
  that cannot draw into half floats. It is off by default and costs memory, about 50 MB for a 720p
  film with `Fast`. The Metal renderer does not run it yet (#421). `AnimationUpscaleDeviceTest`
  checks both tiers against a CPU reference and logs what a frame costs on the device.
- `PlayerStreamInfo` and `TrackInfo` gain `dolbyVision`, null for a stream that is not Dolby
  Vision, so their constructors and `copy` change (#470). Its `DolbyVisionInfo` holds the profile,
  the level and what the base layer is on its own, so an application can show "Dolby Vision 8.1"
  and tell from `baseLayerPlaysAlone` whether every frame must be composed. `VideoFrame` gains
  `sceneMaxNits`, null by default, so a frame class of your own keeps compiling, and the
  `toneMapPeakNits` extension gives the peak a tone mapper should roll off from.
- A Dolby Vision profile 5 or 10.0 picture plays in its real colours instead of green and purple
  (#470). Its base layer is coded in Dolby's IPT colour space, so the FFmpeg decoder composes each
  frame with its RPU into HDR10 on the processor, in bands of rows across the converter's threads,
  before a filter or a renderer sees it, and every renderer that shows HDR10 shows it. That costs
  about 70 ms of one core for a 1080p frame, so 4K waits for composition on the GPU (#459). A frame
  decoded by VideoToolbox or D3D11VA is downloaded first. MediaCodec hands back the base layer
  without its RPU, so FFmpeg's MediaCodec route stands down for such a stream with a
  `HardwareDecodeUnavailable` warning and the direct MediaCodec decoder refuses it, and the
  software decoder plays it. A profile 8, 7 or 10 stream whose base layer plays alone plays it as
  before, hardware included. The HLS variant choice still prefers any other variant to profile 5.
  This needs KiteFFmpeg 0.5.0 (yuroyami/KiteFFmpeg#137).
- The software converter, which Compose's canvas and the desktop views draw through, and the Metal
  renderer tone map each Dolby Vision scene from its own peak, the RPU's level 1 metadata, held at
  most at the title's peak, instead of the title's peak alone, so a dark scene is not dimmed for
  highlights it does not have (#470). Profile 8 streams get this with no composition. A renderer
  that leaves HDR to the platform, such as Android's GPU renderer, is unchanged.
- A DASH Period whose initialization is `dvh1`, `dvhe`, `dva1` or `dvav` carries its parameter sets
  in band when Periods are joined, as `hvc1`, `hev1`, `avc1` and `avc3` already did (#470).
- A recording never costs the media it records (#471). `startRecording` used to empty the file
  at its path at once, so recording into the file playing, or into a link or another spelling of
  it, destroyed that file. A file already at the path is now left whole until the first packet is
  written, and the FFmpeg sink refuses to write over the file its source reads, so such a recording
  ends at its first packet with `RecordingStopped` and the file plays on. This needs KiteFFmpeg
  0.5.0 (yuroyami/KiteFFmpeg#146).
- `close` answers within its deadline even while a recording's file is still being written
  (#473). Finishing the recording, releasing a preloaded next item and waiting for preload builds
  still unwinding used to run on the player's own thread before the deadline started, each build
  with a deadline of its own, so a slow disk could hold `closeAndAwait` for as long as it took.
  They now run in the release the one deadline covers, after the session's workers are joined, and
  a close past the deadline reports `RuntimeCompromised` while the release goes on. A release step
  that refuses, such as a renderer that cannot take the subtitles down, is a `ResourcesNotReleased`
  warning and the rest of the session is still released (#472).
- The last closed caption of a file is shown (#480). FFmpeg's caption decoder gives a caption only
  when the screen next changes, so the caption on screen at the end of a stream stayed inside it.
  The engine now sends every subtitle decoder the null packet the SPI defines once its track has
  run out at the end of the stream, waits for what it gives before it ends the media, and offers it
  again after a seek; the FFmpeg backend's caption and image decoders pass it to KiteFFmpeg's new
  `SubtitleDecoder.drain` (yuroyami/KiteFFmpeg#149). A decoder that refuses it eight times running
  counts as drained, so it cannot hold the end of the media.
- A repeated item follows its own end with no gap (#467). Under `LoopMode.One`, or `LoopMode.All`
  with a queue of one, the item's next pass opens in the background as a gapless queue item does,
  with the tracks the viewer chose, and its first sample follows the last one on the same device.
  The status stays `Playing` and the device never stops, where each turn used to drain it, pass
  through `Ended` and `Buffering`, seek back and refill. `PlayerEvent.Ended` still fires at each
  turn, `Opened` does not, and the external subtitle files are not read again. A repeat that
  cannot take this road warns `GaplessFallback` with the item's own queue position, or -1 outside
  queue playback, and seeks back as before. An A-B loop takes the same road: each turn opens at A
  in the background and its first sample follows the last one before B in the ring, so the section
  repeats with no silence, no `Buffering` and nothing heard or shown from past B, and `Ended` fires
  only when a loop with no B wraps at the item's end. A seek inside the section, a new B or
  clearing the loop drops the waiting turn quietly, a turn that starts too near B for its next pass
  to open in time goes back to A by the seek once, and a loop that cannot take this road seeks back
  to A as before. `docs/gapless-queue.md` has the details.
- An item with video and no sound repeats, loops from A to B and joins the next silent queue item
  with no gap too (#524). With no ring to join, the picture times it: the next pass's first
  picture shows one frame period after the last picture of the one before, so a looping background
  video no longer stands still, passes through `Buffering` and seeks back at every turn. A silent
  item next to one with sound still falls back with `GaplessFallback`, as does a preload not ready
  when the pictures run out.
- A precise seek skips the frames nothing is predicted from on its way to the target, as mpv does
  (#468). They are decoded only to be thrown away, and in a stream with B-frames they are most of
  the run up from the keyframe: 12 s of H.264 with three B-frames between references decoded 78
  frames instead of 285 to reach 9.5 s, in 28 ms instead of 58. The picture at the target and every one
  after it are the same, byte for byte, and a backward step keeps decoding the frames near its
  target so its landing is still the frame before. A decoder takes the request through the new
  `VideoDecoder.skipNonReferenceFrames`, whose default skips nothing, so a platform codec decodes as
  before; the FFmpeg decoder honours it in software, and not while it decodes in hardware or runs a
  video filter.
- A transport stream with several channels, such as a DVB recording or an IPTV multiplex, plays
  one channel's picture, sound and subtitles together (#505). The player picks every track from one
  channel, the first with a picture unless the item's new `DemuxPolicy.program` names another, so
  a preferred language no longer pairs one channel's picture with another channel's sound.
  `Tracks.programs` lists the channels as `MediaProgram`s, with their number, their name, their
  provider and their tracks, `Tracks.selectedProgram` names the one that plays, and
  `KitePlayer.selectProgram` switches channel, choosing every track again from the new one. Media
  that can seek opens again at the same position, and a live sender, such as an IPTV multicast,
  joins the new channel where it is now. Media with one channel or none chooses as before. A
  `PlayerMediaSource` lists its channels through the new `programs`, empty by default, and a
  memento carries the item's channel, which raises its format version to 7.
- A `selectVariant` call that a stop or a failed recovery ended while the reopened stream refilled
  now ends with `IllegalStateException` instead of never returning (#525).
- A sound or subtitles that a transport stream starts carrying after the open join the tracks and
  play (#509), as on a UDP multicast, an IPTV channel joined between programmes or a tuner
  recording, and a channel that moves its sound to a new stream at a programme boundary keeps
  playing it. A picture does the same, as the next entry says. The new
  `PlayerEvent.TracksAdded` names the tracks that appeared. When nothing of its
  kind plays, the player chooses one by the open's rules, as mpv does, and it follows a sound that
  ran out to the stream that carries on, once the old one has played its last sample, so the
  switch cuts nothing. A viewer who turned the sound or the subtitles off keeps them off. The new
  `PlayerEvent.TrackChosenByPlayer` says each time the player chooses on its own. A
  `PlayerMediaSource` announces such a change through the new `PlayerPacket.newStreams` and
  `newPrograms`, null by default, so media whose streams never change plays exactly as before. A
  sound whose rate FFmpeg does not know yet, such as AAC listed before its first packet, opens
  instead of failing, and plays at the rate its decoder finds.
- A picture that a transport stream starts carrying after the open plays (#527), as when a radio
  service adds a slideshow or a channel joined during a break with no picture starts its programme,
  and a channel that moves its picture to a new stream at a programme boundary keeps showing it, as
  mpv does. The player chooses a picture by the open's rules when none plays, or when the one that
  plays has shown its last frame while another carries on, and plays it in place from its keyframe
  at the position, without opening the item again, so the sound and the subtitles play on untouched.
  A seek either way across such a move shows the picture that played there. A viewer who turned the
  picture off keeps it off, and choosing a picture that appeared after the open switches in place
  too. Turning the picture off while none plays now answers at once instead of opening the item
  again, so a live source, which refused it before, takes it as well.
- A file whose sound starts after its picture shows that picture, with silence, as mpv and VLC do
  (#526). Before, it opened on the picture and then jumped to the first sound sample on play, so a
  recording whose sound starts two seconds in lost its first two seconds of picture, and a seek into
  that stretch landed where the sound starts. The sound then plays in step with the picture. A sound
  that starts later than the point an in-place track change switches to is padded the same way, so
  the clock does not jump there either.
- A picture whose file says that some of its edges are not part of the image shows only the part
  that is left (#497). Matroska states this with its `PixelCrop` elements, most often to hide the
  eight padding rows of a 1088-line coded picture, and FFmpeg leaves applying it to the player, so
  such a file used to show those rows and report itself as 1920 by 1088 rather than 16:9. The
  snapshot's `videoSize`, the size event and the track list now report the size that is left, and
  every renderer draws only that part: the Android canvas, MediaCodec straight into the view's
  Surface, the GPU image path, the desktop, the web canvas, Compose, Metal, the sample buffer layer
  and the AppKit and UIKit images. The new `PictureCrop` names the hidden edges in stored pixels.
  `PlayerStreamInfo` gains `crop` and `visibleVideoSize`, so a constructor or `copy` call compiled
  against 0.2.0 must be compiled again, and `VideoFrame` gains `crop`, null by default, with
  `visibleSize` beside it, so a frame of your own keeps compiling and its picture is shown whole.
  A frame keeps its stored `size`. `KiteFFmpegVideoFrame` and `CapturedFrame` carry the crop, and a
  captured frame lays its burned-in text out for the part that was on screen. A crop that leaves
  nothing of the decoded picture is ignored with the new `PlaybackWarning.CropIgnored`, once each
  time the stream is opened. `AndroidPlayerViewRendererFactory.create` and the
  `AndroidSurfaceVideoRenderer` constructor take a geometry callback with a third parameter, the
  crop, because a decoder that writes into the Surface leaves only the view able to hide the edges,
  so a factory or callback of your own needs that parameter. This needs KiteFFmpeg 0.5.0, which
  reads the crop (yuroyami/KiteFFmpeg#147).

### Removed

- The Fireworks, Musical Spectrum and Muser drawings are gone from the audioviz catalogue, with
  their licences. The catalogue has 21 drawings. A name saved for one of them falls back to the first
  drawing (#390).

## [0.2.0] - 2026-09-29

### Upgrading from 0.0.27

- KitePlayer builds on KiteFFmpeg 0.4.0, which is not binary compatible with 0.3.0. Code that
  also calls KiteFFmpeg directly must be compiled against 0.4.0. KiteFFmpeg's own changelog lists
  its changes.
- `kiteplayer-libass` links the libass chain from KiteFFmpeg's `ass-chain-r2` release, which
  builds HarfBuzz 14.5.0 instead of 14.2.1. HarfBuzz's own notes for 14.4.0 and 14.5.0 list fixes
  for crashes and hangs with malformed fonts, which matters because a Matroska file can carry its
  own fonts. HarfBuzz 14.3.0 also changes where marks sit in some scripts, so a typeset ASS line
  can shift by a pixel from the previous release. The API is unchanged.
- Queue items now follow each other without a gap. The next item opens five seconds before the
  current one ends, and its sound follows the last sample on the same audio device, which is not
  stopped, paused or drained between the items. `PlayerEvent.Ended` and then `PlayerEvent.Opened`
  still fire per item, but the status stays Playing, with no Opening between the items.
  `PlayerConfig` gains `queue`, a `QueueConfig`: `QueueConfig(gapless = false)` keeps the old path.
  `PlayerSnapshot` gains `preloadedIndex`. Both change the generated data-class methods, so
  recompile. `PlaybackWarning` gains `GaplessFallback`, which says when an item opened the old way
  and why, so a `when` that lists every warning needs the new branch. On Android a video item
  follows too: its decoder starts at the join, so the picture holds its last frame for a moment
  (#304). `next()` near the end of an item, and the old path after a fallback for the audio
  format, play the preloaded item without opening it again (#306). `docs/gapless-queue.md` has
  the design (#75).
- A seek that was queued while an item reached its end now runs before the end is declared. The
  queue could move to the next item and drop the seek.
- On macOS, a player bound to one output device with `withAudioOutputDevice(id)` now fails with
  `PlaybackError.AudioDeviceUnavailable` when that device disappears during playback. Before, it
  went silent and stayed Playing. A bound player also no longer reports changes of the system
  default output, which it does not follow. `AudioSinkEvent` gains `Failed`, which a sink sends
  when it cannot play again, so a `when` that lists every event needs the new branch (#93).
- A screen reader now finds the video on the Compose canvas path of `KitePlayerVideo`: it says
  "Video", and its state reads as the platform views' state does, for example "Playing, 1:23 of
  4:56". `KitePlayerView` and `KitePlayerUIView` now update that state by themselves when the
  player's status or duration changes, while they are on screen. Before, the state stayed as it
  was when the player was set, unless the application called `updateAccessibilityState()`. None
  of them follows the position on every tick, so a screen reader does not speak continuously
  (#307).
- `KitePlayerVideo` and `KitePlayerSurface` gain `accessibilityVideoLabel` and
  `accessibilityStateFormat`, the same two settings the platform views have, so an app that is
  not in English can translate what a screen reader says about the video. Null keeps the English
  default. The new parameters change the compiled signatures of both composables, so recompile
  (#309).
- On the desktop JVM, `KitePlayerAwtView` now tells a screen reader that it is the video, as its
  accessible name, and what the player is doing, as its accessible description. It gains
  `accessibilityVideoLabel`, `accessibilityStateFormat` and `updateAccessibilityState()`, as the
  Android and iOS views have, and refreshes the state by itself while it is shown. Before, a
  screen reader found an unnamed canvas, and so did a Compose desktop app on the default path of
  `KitePlayerVideo` (#310).
- Swift and Objective-C: every `KitePlayer` call that can fail now declares `@Throws`, so a
  failure arrives as an error instead of ending the app (#204). The suspend calls convert any
  `Exception`. The setters, `play`, `pause`, `seekLater` and the renderer and tap calls throw on a
  closed player or a bad argument, so Swift now calls them with `try`. Java callers of
  `KitePlayer.create` handle the checked `PlaybackException`.
- `attachMediaNotification` no longer refuses a manifest without `android.permission.WAKE_LOCK`.
  It holds no wake lock then and says so through `KiteLog`, which is now public (#205).
- ReplayGain no longer raises the level of a track whose tags carry no peak, which includes
  every Opus file, and `AudioConfig.volumeCeiling` no longer widens the ReplayGain clamp. A tag
  can still lower the level. Raise the volume instead to make such a track louder (#203).
- `PlayerMemento` gains `queueOrder`, which changes the generated data-class methods. Recompile.
  `PlayerMemento.FORMAT_VERSION` is 4, and a build older than this one refuses a memento that this
  one wrote. The text form now also carries each item's title, artist and album (#214).
- `PlayerSnapshot` gains `playRequested`, which changes the generated data-class methods.
  Recompile. The media session guards now pause or duck a player that is buffering, or that is
  opening the next queue item while playing, as well as one that is playing (#226).
- A guard that paused for a call or for the screen going off no longer resumes a player that the
  listener or the application played, paused or moved on in the meantime. `KitePlayer.transportMark`
  is the count such a guard compares (#278).
- `InterruptionPolicy.duckVolume` is now a factor on the listener's volume rather than an absolute
  volume, and a duck no longer writes the volume: 0.2 plays at a fifth of whatever the listener
  set, so a duck can only make the sound quieter. `KitePlayer.setDuckLevel` is the new call behind
  it (#280).
- SubRip text in braces that starts with a backslash is now read as ASS override tags. The first
  `{\anN}` places the cue, `{\b}`, `{\i}`, `{\u}` and `{\s}` style it, and other such runs are
  no longer shown. `SubRipParser.parseCue` returns a cue with that placement, for a caller that
  used `parseCueBody` on container packets (#231).
- A WebVTT `line` percentage now anchors the cue's top edge unless its line alignment names the
  centre or the end, and a positioned cue is kept inside the picture (#229).
- `Dash.mediaItemFor` refuses a manifest whose audio sits in an adaptation set of its own with
  the new `DashUnsupportedException`, instead of playing its video silent. Live and multi-period
  manifests are refused with the same exception, which extends `IllegalArgumentException`. A
  representation that is one file now streams with range requests and seeks, and the segment
  ceiling no longer applies to it (#240).
- `RendererEvent` gains `ColorApproximated`, which a renderer publishes for colour it can only
  approximate. The engine turns it into `PlaybackWarning.ColorApproximated`. A `when` over
  `RendererEvent` that lists every case needs the new branch. The web canvas now uses it to say
  that it shows HDR without tone mapping and converts YCgCo and FCC with a guessed matrix (#228).
- `kiteplayer-ffmpeg` no longer exposes the media library's `Frame`: `KiteFFmpegVideoFrame.frame`
  and `AudioSamples` are internal. A web renderer that drew `frame` through `WebRgbaConverter`
  uses `KiteFFmpegWebPainter` instead (#97).
- `KitePlayer.create` refuses `SyncMode.ExternalMaster` with `ConfigurationInvalid`, because
  nothing follows an external clock yet. Members that nothing produced or called are removed:
  `MasterClock.External`, `PlaybackError.DecoderUnavailable`, `OutputBackend.videoRenderer` and
  `AudioSinkBuffer.writePlane`. A custom output backend or audio sink deletes its override of the
  last two, and a `when` deletes its branch for the other two. `PlaybackError.AudioDeviceUnavailable`
  was removed here too, and came back with `device` and `detail` for bound output devices (#218,
  #92).
- `setSleepTimer` throws `IllegalArgumentException` at the call for a negative fade or an `After`
  that is not in the future, and a refused A-B loop is published as `CommandRefused` (#217).
- `setAudioDelay` was documented the wrong way round. A positive value presents the picture
  earlier, which suits sound that reaches the ear early, as with mpv's `audio-delay`. Sound that
  arrives late, such as Bluetooth latency, needs a negative value. The behaviour is unchanged.
  Both delays, `SubtitleConfig.delay` and `restore` now refuse a value that is not finite or is
  more than `KitePlayer.DELAY_MAX`, one hour, either way (#219).
- `AudioPlayback.submit` is internal. It wrote straight into the ring and skipped the channel
  mix, the rate conversion, the tempo stage, the equaliser, the balance and the ReplayGain. Feed
  decoded audio through `submitDecoded`, which runs all of them (#220).
- `openQueue` now refuses to replace a session that is not Idle, Ended or Failed, with
  `IllegalStateException`, as `open` always did. Call `stop()` first. `restore` already does (#256).
- `Thumbnail` and `Waveform` compare their arrays by content, so two results with the same bytes
  or buckets are equal and hash the same (#264).
- Every JVM and Android class is Java 11 bytecode, where it was Java 21, and the JVM main code is
  checked against the Java 11 API. The API is unchanged. The KiteFFmpeg 0.4.0 jar is Java 11
  bytecode too, so the desktop JVM also runs on Java 11 (#268).
- The fixed English text an app could not change is now configurable, with the same English as
  the default. `MediaNotificationOptions.labels` names the notification buttons.
  `accessibilityVideoLabel` and `accessibilityStateFormat` on `KitePlayerView` and
  `KitePlayerUIView` build what a screen reader says. `FloatingWindowOptions.labels` names the
  desktop floating window's menu. Each options class changes its generated data-class methods, so
  recompile (#266).
- `XmlMini` and `XmlElement` in `kiteplayer-network` are internal. They are the DASH parser's own
  reader and were never meant as a general XML API. `XmlException` stays public, because
  `DashManifestParser.parse` throws it for a malformed manifest (#274).
- An external subtitle at an http or https address now gets the item's `headers` only when the
  address has the same scheme, host and port as the item's `uri`. A subtitle on another server
  gets no item header. To send headers to it, give its `SubtitleSource` its own `io`.
- The DASH door now applies `DashUrlPolicy` to every redirect before it follows it, and a request
  follows at most five redirects. Under `DashUrlPolicy.SameOrigin`, a redirect to another scheme,
  host or port fails with `DashUrlRefusedException`, and in a browser every redirect fails. Use
  `DashUrlPolicy.Default` for a CDN that redirects to another host.
- `PlaybackWarning` gains `SubtitlesNotDrawn`, so a `when` that lists every warning needs the new
  branch. The engine now holds every subtitle rasteriser to the limits in the `SubtitleRasterizer`
  companion object, such as 256 cues and four viewports of pixels in one overlay. Cues past a limit
  are not drawn, and the warning says so. When a rasteriser or a renderer throws, the subtitles are
  cleared, the warning names the failure, and playback continues. A custom rasteriser gets only
  what `SubtitleRasterizer.limitCues` keeps, and it can throw `SubtitleOverlayLimitException` to
  show part of an overlay.
- `AudioConfig` gains `upmix`, which changes the generated data-class methods, and `AudioPlayback`
  takes it as a new last constructor parameter. Recompile. `UpmixMode.Surround` plays a mono or
  stereo source from every speaker of a surround device, with the matrix its KDoc gives.
  `UpmixMode.Off`, the default, keeps the sound as it was (#181).
- `KitePlayerVideo`, `KitePlayerSurface`, `KitePlayerView` and `KitePlayerUIView` gain
  `keepDisplayAwake`, true by default: while the player plays video and the view is on screen,
  the display does not dim or lock. Android uses `keepScreenOn` and iOS the idle timer, with one
  count of holds for the app. `KitePlayerAwtView` accepts it and does nothing. The composables'
  signatures change, so recompile. Pass false to keep managing the display yourself (#238).
- Interlaced video is deinterlaced by default. `PlayerConfig` gains `deinterlace`, a
  `DeinterlacePolicy`: `Auto` runs FFmpeg's bwdif on a stream the container calls interlaced,
  `Always` on every stream and `Off` on none. A deinterlaced stream decodes in software, like any
  stream with a video filter, so under `Auto` an interlaced stream no longer takes a hardware
  decoder; pass `Off` to keep the old behaviour. `PlayerStreamInfo` gains `fieldOrder`, and both
  classes' generated methods change, so recompile. `VideoDecoderFactory` gains a `create` overload
  with the policy, which the engine calls and which defaults to the old `create`.
  `PlaybackWarning` gains `DeinterlaceUnavailable`, which the web build emits because it has no
  filters, so a `when` that lists every warning needs the new branch (#71).
- `MediaItem` gains `audioFilter`, an FFmpeg audio filter chain such as `volume=0.5` or
  `loudnorm` that every decoded audio buffer runs through, which changes the generated data-class
  methods, so recompile. It is low-level API, like `videoFilter`. The web build has no filter
  graphs, so there an item with one fails to open with `ConfigurationInvalid` (#239).
- `SoftwareReadableFrame` gains `planeFormat`, the pixel format of the planes that `copyPlane`
  reads. It defaults to `pixelFormat`, so a custom frame needs no change. A VideoToolbox frame
  reports the format of its downloaded copy there, so `captureFrame` and `encode` now work while
  VideoToolbox decodes, and such a capture holds NV12 or P010 planes (#299).
- On macOS the desktop JVM now decodes H.264 and HEVC with VideoToolbox under `HwdecPolicy.Auto`,
  as the Apple native targets do, and AV1 on a Mac with AV1 hardware. `PlaybackStats.hardwareDecode`
  names it. Such a frame reports `PlayerPixelFormat.Opaque` with a `CoreVideoPixelBuffer` surface,
  and the desktop renderers, `captureFrame` and `encode` read it through its downloaded copy. A
  custom renderer that reads planes itself uses `planeFormat`, or passes `HwdecPolicy.Off` to keep
  software frames (#237).
- `DesktopOutputBackend` and `AppleOutputBackend` gain `audioOutputDevices()`, which lists the
  devices a player can play through as `AudioOutputDevice` values, and `withAudioOutputDevice(id)`,
  which returns the backend bound to one of them. On the desktop JVM the id is the mixer name. On
  macOS it is CoreAudio's device UID, and the player stays on that device when the system default
  changes. A device that has gone away fails the open with the new
  `PlaybackError.AudioDeviceUnavailable` instead of falling back to the default. On iOS the audio
  session owns the route, so the list holds the current route and nothing else can be chosen.
  Android has no list for the same reason (#92).

## [0.0.27] - 2026-09-25

### Upgrading from 0.0.26

- On Android, replace your own content-resolver code with one call:
  `MediaItem.from(MediaIo.ofUri(contentResolver, uri), label)`. Each open gets a new descriptor
  and closes it. Bundled assets play through `MediaIo.ofAsset(assets, name)`. Both are in the new
  `kiteplayer-io` module, which comes with `kiteplayer`.
- Reading `MediaItem.openOptions`, or calling `openOption` in a `mediaItem { }` block, needs
  `@OptIn(KitePlayerLowLevelApi::class)`. Building an item with `openOptions = ...` does not. If
  the Android door replaces your raw `fd` option, you need neither.
- A raw option that a typed field also sets now refuses the open with
  `PlaybackError.ConfigurationInvalid`. Before, the raw key won without a word. The typed fields
  are `headers`, `formatHint` and the new `demux`. Set each option in one place.
- `MediaItem` gains `demux`, which changes the generated data-class methods. Recompile.
- `PlayerMemento.FORMAT_VERSION` is 3. A build older than this one refuses a memento that this one
  wrote.
- `KitePlayer.stepFrame` takes a `StepDirection`, which defaults to `Forward`. Calls in source
  compile unchanged, but the compiled signature changed. Recompile.
- `AudioConfig` gains `resampler`, which changes the generated data-class methods. The
  `AudioPlayback` constructor gains the same parameter. Calls in source compile unchanged.
  Recompile.
- `PlaybackWarning.SubtitleCharsetGuessed.detected` names one encoding, such as `Shift_JIS` or
  `EUC-KR`, where it used to say `Shift-JIS` or "a EUC or Big5 family encoding". If you match on
  it, match the new names. A custom backend can read these files by implementing the new
  `SubtitleFileParser.decode`; the FFmpeg backend already does.
- `HttpReaderPolicy` gains `maxReconnects`, `initialBackoff` and `maxBackoff`, which changes the
  generated data-class methods. Recompile. The HTTP reader now reconnects up to five times in one
  read when a connection drops. Set `maxReconnects = 0` to fail on the first drop, as before.
- `kiteplayer-audioviz` has no categories any more. `VizFamily`, `Visualization.family`,
  `VizCatalog.byFamily()` and the category argument of the drawing constructors are gone. Use
  `VizCatalog.create()`, which returns every drawing in one list. Twenty-six drawings were
  removed, so an app that saved the name of one of them finds no match and needs a fallback.

- `BufferPolicy` gains `stallTimeout`, 30 seconds by default, which changes the generated
  data-class methods. Recompile. A session whose source sends nothing for that long now ends with
  the new `PlaybackError.SourceStalled`, where it used to wait in Buffering until closed. Set
  `Duration.INFINITE` for the old wait, and handle `SourceStalled` in a `when` without `else`.
- `VideoAdjustments` gains `gamma`, which changes the generated data-class methods. Recompile.
- `MediaItem` gains `title`, `artist` and `album`, and each `KitePlayerMediaSession` gains
  `skipInterval`. Both change generated methods or signatures. Recompile.
- `attachMediaNotification` now holds wake locks while the player plays or buffers, and refuses a
  manifest without `android.permission.WAKE_LOCK`. Declare the permission, or pass
  `wakeLocks = WakeLockPolicy.None` to hold nothing, as before.
- `MediaItem.label` drops the query and the fragment, and `diagnosticsDump()` now redacts URIs and
  option values as `supportBundle()` does. A printed `PlaybackError`, `PlaybackWarning` or
  `MediaItem` shows no URL query and no header value. Read `uri` for the full URI.
- KitePlayer builds on KiteFFmpeg 0.3.0. Code that also calls KiteFFmpeg directly names a decoder
  with `DecoderId` and an encoder with `EncoderId`, apart from the format's `CodecId`.

### Added

- The `kiteplayer-io` module of input doors: files and streams on the JVM, file paths on Apple and
  Linux, content URIs and bundled assets on Android, and a bounded pipe for media that arrives by
  push.
- `MediaItem.demux`, typed settings for opening a container, and the `mediaItem { }` builder.
- `MediaItem.title`, `artist` and `album`, which the lock screen, the notification and the car show
  before the file's own tags.
- Background playback on Android with the library's own `KitePlayerMediaService` and media
  notification. The notification shows the session's custom actions, and holds the processor and
  Wi-Fi awake while the player plays or buffers (`WakeLockPolicy`).
- `skipInterval` on `KitePlayerMediaSession`, for the skip buttons on Android, iOS and the web.
- Picture in picture on the desktop JVM, in the browser and on macOS, in step with playback.
- `BufferPolicy.stallTimeout`, which ends a session whose source stops answering. Every network wait
  is bounded, and an interrupt ends a read that waits in a reader.
- `AudioConfig.resampler`, and `KiteFFmpegResampler`, which runs FFmpeg's libswresample.
- Japanese, Chinese and Korean subtitle files are read in their own encodings.
- Subtitles are placed over the whole output on every renderer, inside an optional safe area.
- `VideoAdjustments.gamma`, and stepping a paused player back one frame.
- Recording what the player reads into a Matroska file, and a timeline a profiler can open.
- Scaling in linear light on both GPU renderers, and each Metal frame's presented time.
- A warning when the default audio output changes on macOS.
- The HTTP reader reconnects a dropped read at the byte it reached, up to five times.
- 32-bit ARM Android phones get every native library KitePlayer needs, because KiteFFmpeg 0.3.0
  adds one to its Android AAR. Not yet run on a 32-bit device.
- The audio visualiser has one flat catalogue with Glitch, Fluctus, Twin Bloom and ten credited
  ports: Silk, Honeycomb, Fracture, Lines, Threads, Fireworks, Muser, Wavy Spiral, Musical Spectrum
  and Iris. A stills tool renders any drawing at chosen seconds of a real song.

### Fixed

- The position no longer jumps ahead by the paused time after a resume, and each forward step
  shows exactly the next frame.
- Video coded as RGB plays, and Identity video converts as its planes.
- A caller's descriptor is read by position, so no open moves its offset.
- VideoToolbox attaches to FFmpeg's own AV1 decoder on Apple.
- Every part of an FFmpeg source closes even when one part fails.
- A stop or a cancelled open ends an open whose reader hangs.
- The audio ring refuses to build where its counters would take a lock.
- The iOS audio session turns on again when playback resumes after a phone call.
- A signed URL's query and a request header's value no longer reach a label, a message, a
  printout or the diagnostics dump.
- The Android libass libraries are aligned for 16 KB pages whatever NDK builds them, and the
  newest NDK is picked by its version, not by its folder name.

### Changed

- KitePlayer builds on KiteFFmpeg 0.3.0, with FFmpeg 9.0.2.
- The visualiser drawings Kaleidoscope, Ocean Mist, Contour, Glitch, Nebula Field, Pipe, Alchemy,
  Neon Lo-Fi and Odyssey were rebuilt. Shatter and Ripple Well became Thin Ice, Reaction Diffusion
  and Smoke Rise became Marble, and Aurora and Aurora Field left the catalogue.
- CI builds and launches the iOS sample app, runs the tvOS, watchOS and Linux arm64 tests, and
  fails when a published artifact grows past its baseline or a hot path gets ten times slower. CI
  also has an Android emulator job for the device tests. Its emulator has not finished booting yet
  (#81), so no Android device test has run in CI.

## [0.0.26] - 2026-09-22

### Added

- `AudioVizState.framesPerSecond` caps how often the visualiser redraws; zero, the default, draws
  every display frame. Both surfaces and the director pace by it. Fixes #146.

- `KitePlayer.scanAudio` decodes an audio track a second time, without playing it, and hands
  the samples to an `AudioScanSink`. A range limits it to part of the track.
- `KitePlayer.audioClock` reports the audible position, its rate and its generation in one
  read, so a tap and a picture agree on what is playing now.
- The visualiser scans a song in the background, on half the cores and at most four, and builds
  a song map: sections, drops, breakdowns and the key. `SongMapStore.inDirectory` keeps a
  finished map between runs, so a song played before is mapped from its first note. `SongScanPolicy`
  turns the scan off. Fixes #140.
- Sections, drops and breakdowns are also detected live, and the key is estimated live, for a
  song with no map yet. Late confirmations and map events are delivered on time only.
- `VizDirector.maximumHoldSeconds` lets a long wait change the drawing on the next beat. Zero,
  the default, still waits for a musical boundary however long that takes.
- The sample carries five songs, arrows and a drag across the picture to change the drawing,
  and a random button. Fixes #141.
- `Odyssey` is one distance field made of districts. Each district is a recipe of eight fold
  steps, composed on the host from five templates and a seed drawn at launch, so two runs show
  two different worlds. The shader holds no trigonometry: the host passes cosine and sine pairs
  and unit normals. A Kotlin copy of the field screens every recipe before it is shown. See #147.
- `Neon Lo-Fi` replaces `Terrain March`: live spectrum terraces, a media-time musical horizon and
  four continuous regions. A caller that saved the name `Terrain March` still resolves to it, and
  the browser's search finds it under either name.
- `Bars`, `Mandala`, `Ocean Mist`, `Pipe`, `Plasma` and `Smoke Vortices` are rewritten as one
  drawing each, with their own controls. `Ocean Mist` keeps its live waveform outside the feedback
  buffer and owns the stereo traces `Scope` used to show.
- A control can be a switch or a named choice, and a control that does not apply is hidden.
- The browser's tiles follow the settings of the drawing they preview, and obey a lower
  `AudioVizState.framesPerSecond` cap.

### Fixed

- A flying drawing kept travelling while the player was paused, and drifted on through a
  silence. A frame now says how much is audible (`SpectrumFrame.audible`, zero while paused or
  silent), `motionRate` follows it, and every camera, ground and scrolling field moves by
  `VizRenderState.stepSeconds`, the audible share of the frame, instead of the wall clock.
  Fixes #144.
- A video track change answered before the snapshot showed the new selection, so a caller that
  read `KitePlayer.state` on return saw the track it had just turned off still selected. The
  snapshot is published before the reply, as the audio and subtitle changes already did.
  Fixes #142.
- Every drawing is driven from the music rather than from its own clock, the flash counter is
  calibrated, and quiet music stays visible.
- On Android, a hardware frame counts as presented only when the display showed it, frame
  releases are served as soon as the schedule hands them over, the Compose video view no longer
  redraws on every display refresh, and the display is asked for the stream's real frame rate.
  Fixes #137 and #138.
- The sample no longer crashes on the song buttons or on a drag off the seek bar's left edge.

### Changed

- The visualiser opens on a drawing chosen at random and the director walks a different sequence
  on every run. `VizDirector`'s seed is random unless one is given. Fixes #145.

- `VizDirector.current` is Compose snapshot state, so a control that names the drawing on
  screen follows the director. The signature is unchanged. Fixes #143.
- Breaking: `rememberAudioVizState` takes a `songScan` policy and a `songMapStore`, both with
  defaults, and `SpectrumAnalyzer.feed` and the `VisualizerSurface` composables are gone. The
  analysis runs on calibrated power with one shared gain and timed events; the numbers a
  drawing reads changed meaning, and a drawing written against 0.0.25 needs its thresholds read
  again.
- The catalogue is 46 drawings, down from 78. These 34 are gone: Acid Tunnel, Blackout,
  Cathedral, Dance of the Freq, Drain, Equaliser, Fire Storm, Hex Shaft, Hyperdrive, Implosion,
  Ionizer, Lock On, Mandelbox, Menger, Neon City, Piston, Plasma Field, Radar, Rainbow Bar, Riot,
  Ripple, Scope, Spikes, Stable Fluids, Starfield, Strands, Strobe Web, Terrain, Terrain March,
  Tunnel, Vortex, Wave, Wireframe and Wormhole. Only `Terrain March` has a replacement that
  answers to its old name. A caller that stored any other name finds nothing and must fall back
  to the first drawing in the catalogue. Every removed class was internal, so no published symbol
  changed.
- The sample opens on `Alchemy` or `Bars`, chosen at random, because `Menger` is gone.

## [0.0.25] - 2026-09-15

### Added

- On the web, `KitePlayerMediaSession` mirrors the player into the browser's media session, so the
  media keys, a headset and the system's media overlay can control playback. Browsers show it only
  while a page plays through an audio or video element, and KitePlayer plays through Web Audio.
- On the desktop JVM, `KitePlayerMediaSession` exists and reports `isAvailable` as false, so the
  same session code compiles on every platform.
- Both samples build the media session and attach the interruption and background handling. The
  Android sample keeps the song playing when the screen locks. Fixes #77.

### Fixed

- On Android 13 and newer, the audio visualiser crashed with "Software rendering doesn't support
  RuntimeShader" when a warp drawing or a shader drawing rendered into its echo buffer. On a
  bitmap canvas those drawings now take the path Android 12 and older take: the warp becomes a
  plain echo, and a shader drawing shows its fallback. Fixes #135.
- On Android, a stalled stream showed as paused on the lock screen. It now shows buffering, and
  the playback state is written only when the lock screen would otherwise be wrong.
- On iOS, the now playing card was rebuilt five times a second. Its text and artwork are written
  when they change, a live stream is marked live and gets no scrub bar, and a toggle while
  buffering pauses.
- Two CoreAudio sink tests failed in CI when the audio device started late. Fixes #133.

### Changed

- Breaking: `MediaSessionState` takes a `phase` (`Playing`, `Paused`, `Buffering` or `Stopped`)
  in place of its playing flag, plus a new `hasVideo`. Code that builds or copies one needs the
  new parameters. Reading `playing` works as before.
- Code comments explain what the old planning codes meant instead of citing them. Fixes #134.

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
  on the pinned Kotlin toolchain and its initialisation behaviour.
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
