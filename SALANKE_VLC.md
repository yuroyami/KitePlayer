# SALANKE VLC tracker

- Date: 2026-08-26
- Tracker: VideoLAN VLC GitLab issues, using the public REST API and indexed public issue pages
- Counts: listed 5423 raw API list records across the initial searches, all 6 Component::Core: Input pages, all 2 Core Audio Output pages, all 4 Component::Subtitles pages, all 5 Component::Core: Video output pages, all 9 Component::Decoders pages, all 10 Component::Demuxers pages, and all 23 pages of the full-history track selection, speed, pitch, chapter, HDR, color, rotation, and orientation searches; cross-search duplicates are not removed; triaged in 304; processed 304; verdicts 0 CONFIRMED, 37 SUSPECT, 169 IMMUNE, 75 MISSING-FEATURE, and 23 N/A; severities 44 P0, 168 P1, 86 P2, and 6 P3
- Checkpoint: 304 processed; strict post-audit adjudication completed for pinned-backend color metadata, capture semantics, and URI cache coverage
- Access note: public issue listing, search, and detail endpoints worked. The anonymous issue notes endpoint returned HTTP 401, so no private or unavailable discussion content is inferred below.

### [VLC-549] Gapless playback
- Link: https://code.videolan.org/videolan/vlc/-/issues/549  State: open
- Mechanism: Gapless playback requires the next input to be opened and buffered while the current input is still draining. Opening only after the first input reaches its terminal state necessarily leaves teardown and startup work between the two audio timelines.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, handleQueueAdvance
- Verdict: MISSING-FEATURE
- Why: handleQueueAdvance waits for PlaybackStatus.Ended and then calls runOpen for the next item. There is only one OpenSession, so KitePlayer cannot overlap the next item's demux, decode, and audio preroll with the current item's tail.
- Severity if real: P1 broken feature

### [VLC-3135] Decoders should accept 0 as a valid PTS
- Link: https://code.videolan.org/videolan/vlc/-/issues/3135  State: open
- Mechanism: Code that uses timestamp zero as the sentinel for an absent timestamp rejects a valid first PTS. Absence needs a representation distinct from every numeric timeline value.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/Pts.kt, Pts; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, TimestampMapper.mapTimestamp and KiteCodecPacket.pts
- Verdict: IMMUNE
- Why: KitePlayer represents an absent timestamp as Pts? null and explicitly makes Pts.Zero valid. TimestampMapper maps any non-null Long, including zero, into a Pts, and KiteCodecPacket passes that nullable result onward without a zero check.
- Severity if real: P1 broken feature

### [VLC-7485] Display chapters stored in MP3 files (id3-tags)
- Link: https://code.videolan.org/videolan/vlc/-/issues/7485  State: closed-fixed
- Mechanism: MP3 chapter markers live in ID3 CHAP metadata and are easy to miss if the demux boundary exposes only audio streams and flat tags. A player must import the demuxer's chapter table independently of stream selection.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, KiteCodecSource.chapters; /Users/macbook/StudioProjects/#Kite/KiteCodec/kitecodec-core/src/nativeMain/kotlin/io/github/yuroyami/kitecodec/MediaSource.native.kt, readChapters
- Verdict: IMMUNE
- Why: KiteCodec enumerates every chapter exposed by the FFmpeg format context, including its bounds and metadata, without filtering by container or stream kind. KiteCodecSource maps that table onto KitePlayer's zero-based timeline, so ID3 chapters do not depend on the audio packet path.
- Severity if real: P2 quality/perf

### [VLC-20645] When multiple audio streams and forced subtitles available, select forced subtitles based on selected language
- Link: https://code.videolan.org/videolan/vlc/-/issues/20645  State: open
- Mechanism: A forced subtitle is paired with the language of the active audio track. If audio changes from one language to another while the old forced track stays selected, foreign-language fragments are translated for the wrong audience.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, pickSubtitle, inPlaceAudioChange, and handleTrackChanges
- Verdict: SUSPECT
- Why: pickSubtitle considers the initially selected audio language when automatic forced selection runs. A later inPlaceAudioChange replaces the audio lane without rerunning pickSubtitle, so handleTrackChanges preserves the current subtitle by exact stream index and can leave a forced track chosen for the previous audio language selected.
- Severity if real: P1 broken feature

### [VLC-25056] Stutter in audio when changing playback speed (+ / -)
- Link: https://code.videolan.org/videolan/vlc/-/issues/25056  State: open
- Mechanism: A live rate change cannot splice already-buffered samples at the old rate directly into newly processed samples at the new rate. VLC's report shows the audio clock becoming late, the output flushing, and inserted silence during the transition.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, execute CoreCommand.SetSpeed; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, speed and flush
- Verdict: SUSPECT
- Why: KitePlayer correctly creates an epoch boundary, but every live speed change does so by queueing a precise seek to the current position. That path stops the sink, flushes the ring and decoders, seeks, and prerolls again, so a user can still hear a short interruption even though old-rate and new-rate samples cannot mix.
- Severity if real: P2 quality/perf

### [VLC-18862] Display subtitles instantly when seeking
- Link: https://code.videolan.org/videolan/vlc/-/issues/18862  State: open
- Mechanism: Seeking into the middle of a cue must reconstruct subtitle state from a packet whose start time is before the target. Starting subtitle demux at a later video keyframe can omit that packet, leaving no cue until the next subtitle event.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runSeek, clearBuffers, handleSubtitles, and timeAndPublishCues; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, seekToKeyframe
- Verdict: SUSPECT
- Why: clearBuffers deletes container cues and seekToKeyframe performs one backward container seek keyed to the requested media position. handleSubtitles can rebuild a spanning cue only if its packet is returned after that landing. A long cue that began before the demux landing can therefore be absent even though CueSelector.activeAt would display it if decoded.
- Severity if real: P1 broken feature

### [VLC-14257] No video or audio after PMT update
- Link: https://code.videolan.org/videolan/vlc/-/issues/14257  State: closed-fixed
- Mechanism: An MPEG-TS program map can replace its elementary streams and PCR source during playback. Keeping the old stream identities and clock reference after the PMT update makes the demux path wait on streams that no longer exist.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, streams, selectStreams, and reselectStreams; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runDemux
- Verdict: SUSPECT
- Why: KiteCodecSource snapshots one immutable stream table at open. Its reader can reselect indices only within that original table, and buildSession initially routes the selected video plus every original audio and subtitle stream. runDemux has no program-change event or stream-table refresh, so a PMT that replaces identities can still strand playback.
- Severity if real: P1 broken feature

### [VLC-18813] Dolby Vision HDR support
- Link: https://code.videolan.org/videolan/vlc/-/issues/18813  State: open
- Mechanism: Dolby Vision uses codec signaling and dynamic enhancement metadata beyond ordinary HDR10 transfer characteristics. Correct playback needs to recognize the Dolby Vision profile and route or map its metadata through decode and display.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/VideoFrame.kt, ColorSpaceInfo; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/Conversions.kt, toPlayerColorSpace
- Verdict: MISSING-FEATURE
- Why: KitePlayer models static primaries, transfer, matrix, and range but has no Dolby Vision profile, RPU, or dynamic metadata representation. It can decode a compatible HEVC base layer, but it cannot promise Dolby Vision output or metadata-aware mapping.
- Severity if real: P2 quality/perf

### [VLC-25694] 4.0 regression: seek with hardware decoding not immediate (slow)
- Link: https://code.videolan.org/videolan/vlc/-/issues/25694  State: closed-fixed
- Mechanism: VLC reset its output clocks while the hardware decoder was still flushing, then continued using those clocks during the flush. The lost sync points delayed new frames and allowed old frames to remain visible; moving the clock reset until after decoder flush was one required fix.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runSeek and clearBuffers; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/VideoPlayback.kt, flush
- Verdict: IMMUNE
- Why: runSeek parks the scheduler and quiesces every worker before it flushes either decoder. Only after decoder flush does clearBuffers call VideoPlayback.flush, which drops the frame queue and invalidates the video clock, and workers are released only after all of that is complete. No scheduler can read the clock during the reset window.
- Severity if real: P1 broken feature

### [VLC-27204] core:  aout_TimingReport() uses a vlc_mutex
- Link: https://code.videolan.org/videolan/vlc/-/issues/27204  State: closed-fixed
- Mechanism: A real-time audio callback must never wait for a lower-priority thread. Even a normally fast mutex can block if its owner is preempted, making the callback miss its device deadline and producing a pop or dropout.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/KotlinAudioRing.kt, render and publishAnchor; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/AudioSink.kt, AudioRenderCallback contract
- Verdict: SUSPECT
- Why: the portable ring takes no mutex, but publishAnchor retries without a bound while the feeder's segment sequence is odd or changing. If the feeder is preempted midway through publication, a real-time callback can spin behind it. The C CoreAudio ring avoids this ordering, but other callback sinks still use the portable ring.
- Severity if real: P2 quality/perf

### [VLC-27571] Discontinuities propagation and effects changes
- Link: https://code.videolan.org/videolan/vlc/-/issues/27571  State: open
- Mechanism: Packet loss, a truncated access unit, a timeline jump, and an explicit seek are different discontinuities. Collapsing them loses the information needed to choose between ignoring, dropping, draining, resetting timestamps, and flushing a decoder, which can make a damaged stream unwatchable until a later random-access point.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/MediaSource.kt, PlayerPacket; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, KiteCodecPacket
- Verdict: SUSPECT
- Why: PlayerPacket carries timestamps, duration, keyframe status, size, and byte position, but no corrupted or discontinuity classification. KiteCodecPacket therefore cannot propagate FFmpeg packet flags into the engine, and the decoder path has no signal telling it whether to drop, drain, or reset after transport loss or an in-stream splice.
- Severity if real: P1 broken feature

### [VLC-26515] VLC deadlock when STOP a media without audio
- Link: https://code.videolan.org/videolan/vlc/-/issues/26515  State: open
- Mechanism: Stopping a stalled source deadlocks when teardown waits for the input worker but the worker is blocked inside a device or network read that cancellation does not interrupt. The absence of audio changes which worker or clock wakes the shutdown in VLC's report, but the core trap is an unbounded input read joined before the source is closed.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runDemux and releaseSession
- Verdict: SUSPECT
- Why: runDemux can suspend inside session.source.readPacket. releaseSession requests quiescence, cancels each job, and then joins every job with no local timeout before it closes backendSession. A native or custom HTTP read that ignores coroutine cancellation can therefore keep stop waiting and prevent the close that could unblock the read.
- Severity if real: P1 broken feature

### [VLC-9231] VLC Does not switch to new audio device while open on OSX
- Link: https://code.videolan.org/videolan/vlc/-/issues/9231  State: closed-fixed
- Mechanism: The system default audio route can change while media is playing, for example when a USB interface is connected. Following it requires migrating or reopening the sink during the live session; keeping the original device makes the new route silent until playback is reopened.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, startWorkers audio event collector; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/AudioSink.kt, events
- Verdict: MISSING-FEATURE
- Why: KitePlayer collects DeviceChanged and DeviceLost, but both only publish PlaybackWarning.AudioDeviceChanged. No handler pauses, rebuilds, or migrates the AudioSink, and the SPI documentation explicitly says device recovery is not implemented.
- Severity if real: P1 broken feature

### [VLC-7766] Rotate changes image but not layout
- Link: https://code.videolan.org/videolan/vlc/-/issues/7766  State: closed-fixed
- Mechanism: Rotating pixels by 90 or 270 degrees without swapping the presentation geometry leaves a portrait picture fitted into a landscape rectangle. The result is stretched or squashed even though the pixels face the correct direction.
- KitePlayer code checked: kiteplayer-output/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/output/FrameLayout.kt, frameLayout; kiteplayer-output/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/output/MetalVideoSupport.kt, pictureQuadUniforms
- Verdict: IMMUNE
- Why: both layout laws detect a quarter turn before fitting. frameLayout swaps the content width and height and also swaps the pre-turn drawing rectangle, while pictureQuadUniforms applies the same post-turn display geometry on Metal.
- Severity if real: P2 quality/perf

### [VLC-30020] Slow mkv/webm seek times over SMB
- Link: https://code.videolan.org/videolan/vlc/-/issues/30020  State: open
- Mechanism: VLC's linked regression commit makes the Matroska demuxer read blocks until it reaches a known timestamp when seeking a slow source. On SMB, that sequential scan turns a nominal random seek into prolonged network reads.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, seekToKeyframe; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/CachingMediaIo.kt, seek
- Verdict: SUSPECT
- Why: KitePlayer has no VLC Matroska block scanner, but it delegates the whole container seek to FFmpeg and waits for that call without an I/O or wall-time budget. CachingMediaIo bounds retained RAM and makes in-window seeks free, yet a far landing still lets the demuxer perform however many upstream reads its index search chooses. A different demuxer does not prove the slow-source mechanism closed.
- Severity if real: P1 broken feature

### [VLC-17620] VLC failed after PCR clock wrap
- Link: https://code.videolan.org/videolan/vlc/-/issues/17620  State: closed-fixed
- Mechanism: MPEG-TS PCR wraps after its finite counter range. Treating the wrapped value as an ordinary later timestamp creates a jump of roughly 95,443 seconds, which makes clock drift recovery and packet scheduling fail.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/buildSrc/src/main/kotlin/BuildFFmpegTask.kt, DEFAULT_SOURCE_REF; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, TimestampMapper
- Verdict: IMMUNE
- Why: KiteCodec pins FFmpeg n8.0. Its MPEG-TS demux propagates the 33-bit wrap reference into elementary streams, then libavformat update_timestamps applies wrap_timestamp to packet DTS and PTS before av_read_frame returns; correct_ts_overflow defaults to enabled. TimestampMapper therefore receives an already unwrapped timeline rather than the raw transport counter.
- Severity if real: P1 broken feature

### [VLC-16913] Drops pictures when decoding VFR video
- Link: https://code.videolan.org/videolan/vlc/-/issues/16913  State: closed-fixed
- Mechanism: A variable-frame-rate stream can legitimately skip a nominal frame interval. Scheduling every picture at the declared constant rate makes the next picture appear late and can trigger a cascade of unnecessary drops around the longer interval.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/FrameDurationEstimator.kt, estimate; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/VideoPlayback.kt, tick
- Verdict: IMMUNE
- Why: VideoPlayback measures each displayed interval from adjacent PTS values. FrameDurationEstimator abandons its declared-rate snap as soon as a measurement differs beyond the rounding tolerance, and late-drop timing uses the following frame's own PTS interval, so a legal VFR gap is not forced through the previous constant duration.
- Severity if real: P2 quality/perf

### [VLC-21024] Failure to automatically demux mpegts stream: 3.0.3 fails whereas 2.2.6 works
- Link: https://code.videolan.org/videolan/vlc/-/issues/21024  State: closed-fixed
- Mechanism: The public report establishes a VLC 3.0.3 regression for one OBS-generated AVC plus AAC MPEG-TS over UDP, but supplies no minimized packet shape, failing source branch, or root-cause commit that can be transferred to another demuxer.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecMediaBackend.kt, open; /Users/macbook/StudioProjects/#Kite/KiteCodec/buildSrc/src/main/kotlin/BuildFFmpegTask.kt, DEFAULT_SOURCE_REF
- Verdict: N/A
- Why: KitePlayer does not run VLC 3.0.3's UDP access or TS demux modules. It delegates the URI to pinned FFmpeg n8.0, and the issue receipt identifies only an observed VLC-version regression, not an equivalent algorithm or malformed input rule in that backend.
- Severity if real: P1 broken feature

### [VLC-19170] vlc can't play an mp4 fragmented video
- Link: https://code.videolan.org/videolan/vlc/-/issues/19170  State: closed-fixed
- Mechanism: The sample is an initialization fragment concatenated with a fragmented MP4 media segment. VLC's native MP4 parser rejected that fragment layout even though FFmpeg could demux it, and remuxing through FFmpeg made it playable.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, open and selectStreams; /Users/macbook/StudioProjects/#Kite/KiteCodec/kitecodec-core/src/nativeMain/kotlin/io/github/yuroyami/kitecodec/MediaSource.native.kt, MediaSource.open
- Verdict: IMMUNE
- Why: The upstream report itself establishes FFmpeg as the working demux path. KitePlayer reaches FFmpeg through KiteCodec rather than using VLC's native MP4 parser, so the parser that rejected this fragmented layout is outside its pipeline.
- Severity if real: P1 broken feature

### [VLC-26929] MediaCodec: Banding on 10bit Rec.2020 SDR video
- Link: https://code.videolan.org/videolan/vlc/-/issues/26929  State: wontfix
- Mechanism: A 10-bit Rec.2020 SDR picture loses its gradient advantage if the decoder or surface path negotiates an 8-bit or narrow-gamut output. The resulting quantization appears as visible bands even though the stream is not HDR.
- KitePlayer code checked: kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/MediaCodecConfiguration.kt, parseMediaCodecConfiguration; kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/MediaCodecVideoDecoder.kt, mediaCodecProfileForColor and configuredMediaFormat
- Verdict: SUSPECT
- Why: KitePlayer parses Main10 depth, selects the corresponding MediaCodec profile, and sends BT.2020 color hints, but its surface route cannot inspect the compositor's actual component precision. Output-format validation covers reported color fields, not whether a vendor surface silently quantized the 10-bit SDR picture, so the final link remains unproved on affected devices.
- Severity if real: P2 quality/perf

### [VLC-26933] MediaCodec: x265 colors washed out with Google TV (SDR/HDR issue)
- Link: https://code.videolan.org/videolan/vlc/-/issues/26933  State: closed-fixed
- Mechanism: Sending HEVC HDR through a hardware decoder without matching the codec profile, transfer request, primaries, matrix, and range lets the device choose an SDR interpretation. PQ values then reach the display under the wrong transfer and colors look washed out.
- KitePlayer code checked: kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/MediaCodecVideoDecoder.kt, mediaCodecProfileForColor, applyColorHints, and validateOutputColor
- Verdict: IMMUNE
- Why: The Android path selects HDR-specific HEVC, VP9, and AV1 profiles from declared transfer metadata, writes the exact color standard, transfer, and range into MediaFormat, and checks the decoder's reported output contract. A vendor that rejects or changes that contract triggers fallback instead of silently presenting it as the requested color space.
- Severity if real: P2 quality/perf

### [VLC-26180] HDR causes black subtitles / menues on Blu Ray movies
- Link: https://code.videolan.org/videolan/vlc/-/issues/26180  State: closed-fixed
- Mechanism: Blu-ray menus and PGS subtitles are bitmap subpictures. Compositing their SDR-coded colors directly in an HDR output space can map white graphics to black or nearly black, making both navigation and dialogue unreadable.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSubtitleDecoder.kt, KiteCodecSubtitleDecoderFactory; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/subtitle/SubtitleCue.kt, Bitmap
- Verdict: MISSING-FEATURE
- Why: KitePlayer's container subtitle decoder accepts text, mov_text, WebVTT, ASS, and SSA only, and its own comment says bitmap formats still need real engines. It also has no Blu-ray navigation path, so it cannot reach the HDR composition bug because the affected menu and PGS feature is absent.
- Severity if real: P1 broken feature

### [VLC-27645] Dual Distorted Pinkish Video Output With Hardware Accelerated Decoding Enabled in HEVC Main10
- Link: https://code.videolan.org/videolan/vlc/-/issues/27645  State: closed-fixed
- Mechanism: The Windows Direct3D hardware path interpreted an HEVC Main10 output surface with the wrong layout or format, producing two copies of the picture and a pink-green tint. Disabling hardware decoding avoided that surface contract.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/PlatformDecoderSelection.kt, platformDecoderSelection; kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/MediaCodecVideoDecoder.kt, MediaCodecVideoDecoder
- Verdict: N/A
- Why: KitePlayer has no Direct3D or Windows hardware-video renderer. Its hardware routes are platform-specific VideoToolbox and MediaCodec paths, so the faulty D3D11 surface interpretation named by this issue is not in the current implementation.
- Severity if real: P2 quality/perf

### [VLC-25181] FLAC playback terminates before playing all audio (final <250ms clipped) (all platforms Linux, Android, Windows)
- Link: https://code.videolan.org/videolan/vlc/-/issues/25181  State: closed-fixed
- Mechanism: Declaring playback ended when demux reaches EOF, before the decoder and output pipeline drain, discards compressed frames, resampler history, tempo lookahead, or queued device samples at the tail.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, handleEof and runAudioFeed; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/AudioPipeline.kt, finish; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, endOfStream
- Verdict: IMMUNE
- Why: EOF is a staged drain. KitePlayer sends decoder EOS, waits until decoded audio and in-flight handoff are empty, calls AudioPipeline.finish for resampler and tempo tails, writes that tail into the ring, and waits for the sink to consume the ring before it publishes Ended.
- Severity if real: P1 broken feature

### [VLC-26634] Missing ass_set_storage_size can lead to distorted ASS subtitles
- Link: https://code.videolan.org/videolan/vlc/-/issues/26634  State: closed-fixed
- Mechanism: libass needs both the rendered frame size and the video's original storage size. Without ass_set_storage_size it guesses the latter, and anamorphic scaling or 3D rotation tags can be transformed against the wrong geometry.
- KitePlayer code checked: kiteplayer-libass/src/nativeMain/kotlin/io/github/yuroyami/kiteplayer/libass/LibassRenderer.kt, renderDocument; kiteplayer-libass/native/src/libass_jni.c, Java_io_github_yuroyami_kiteplayer_libass_LibassNative_renderPacked
- Verdict: SUSPECT
- Why: Both KitePlayer libass bridges call ass_set_frame_size but neither calls ass_set_storage_size. The render API receives only the output frame width and height, so it cannot provide the distinct encoded storage geometry required for the upstream anamorphic and 3D transform case.
- Severity if real: P2 quality/perf

### [VLC-22317] Performance issues with PGS subtitles and SAR/PAR
- Link: https://code.videolan.org/videolan/vlc/-/issues/22317  State: open
- Mechanism: A PGS bitmap attached to every video frame can require a scale and composite at frame rate. If subtitle storage geometry and video sample aspect ratio differ, doing that conversion on the hot presentation path can make pictures miss their deadlines.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSubtitleDecoder.kt, KiteCodecSubtitleDecoderFactory; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/subtitle/SubtitleCue.kt, Bitmap
- Verdict: MISSING-FEATURE
- Why: The current factory explicitly supports only text subtitle codecs and says bitmap formats still need real engines. PGS packets are deselected because no SubtitleDecoder accepts them, so KitePlayer lacks the affected feature rather than having a tuned PGS scale and composition path.
- Severity if real: P1 broken feature

### [VLC-26194] Matroska: turn special projection values into horizontal/vertical flip
- Link: https://code.videolan.org/videolan/vlc/-/issues/26194  State: open
- Mechanism: Matroska can encode special yaw, roll, and pitch combinations that mean horizontal flip, vertical flip, or a 180-degree orientation. Reducing the display transform to rotation alone loses the reflection component.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/VideoFrame.kt, rotationDegrees; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, KiteCodecVideoFrame
- Verdict: MISSING-FEATURE
- Why: VideoFrame documents only quarter-turn rotation and explicitly says mirrored display matrices are not modeled. KiteCodecVideoFrame therefore carries rotationDegrees but no horizontal or vertical reflection flag, so these Matroska projection values cannot reach a renderer.
- Severity if real: P2 quality/perf

### [VLC-26239] Rotated videos playing with wrong proportions in D3D11
- Link: https://code.videolan.org/videolan/vlc/-/issues/26239  State: open
- Mechanism: Applying a quarter-turn to pixels while fitting with the unrotated display dimensions stretches or squeezes the result. Correct fitting must swap display width and height before it computes the destination rectangle.
- KitePlayer code checked: kiteplayer-output/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/output/FrameLayout.kt, frameLayout; kiteplayer-output/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/output/MetalVideoSupport.kt, pictureQuadUniforms
- Verdict: IMMUNE
- Why: Both output layout laws detect 90 and 270 degrees before aspect fitting, swap the content dimensions, and map the drawing rectangle back into pre-rotation coordinates. The Direct3D implementation named upstream is absent, while KitePlayer's current renderers perform the required geometry swap.
- Severity if real: P2 quality/perf

### [VLC-10745] rotating a movie by 90 degrees is faulty
- Link: https://code.videolan.org/videolan/vlc/-/issues/10745  State: closed-fixed
- Mechanism: A 90-degree transform changes both axis order and presentation bounds. Rotating texture coordinates without applying the matching output geometry breaks the picture even if the decoded pixels are valid.
- KitePlayer code checked: kiteplayer-output/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/output/FrameLayout.kt, frameLayout; kiteplayer-output/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/output/MetalVideoSupport.kt, pictureQuadUniforms
- Verdict: IMMUNE
- Why: KitePlayer normalizes quarter turns, swaps the fitted dimensions for 90 and 270 degrees, and supplies rotation-specific texture coordinates. The geometry and sampling transformations are computed together rather than as independent effects.
- Severity if real: P2 quality/perf

### [VLC-16302] Subtitle not shown after rewinding
- Link: https://code.videolan.org/videolan/vlc/-/issues/16302  State: open
- Mechanism: Rewinding into the middle of an active subtitle interval requires retaining or re-reading the cue whose start lies before the new position. If seek reconstruction begins after that cue packet, it remains invisible until the next cue starts.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runSeek, clearBuffers, and timeAndPublishCues; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, seekToKeyframe
- Verdict: SUSPECT
- Why: A seek clears all container cue state and rebuilds it only from subtitle packets returned after the common demux landing. CueSelector can display a spanning interval, but it never receives a long cue whose packet predates that landing, matching the upstream rewind condition.
- Severity if real: P1 broken feature

### [VLC-26379] 2 errors: Audio under 2 seconds will not be output as audio and Interruption after 1.5 seconds
- Link: https://code.videolan.org/videolan/vlc/-/issues/26379  State: wontfix
- Mechanism: A short file can reach input EOF before an output's startup threshold is filled. If the player requires that threshold rather than treating a drained stream as ready and then consuming its tail, it can finish without ever starting the device.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, everySelectedStreamReady and handleEof; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, drain and endOfStream
- Verdict: IMMUNE
- Why: Stream readiness accepts end of stream as an alternative to the ordinary packet and duration targets. The audio EOF state then waits for decoder output, the feeder, DSP tail, ring, and device drain, so a sub-two-second file does not need to satisfy a one-second preroll before its samples can leave.
- Severity if real: P1 broken feature

### [VLC-25532] Audio defects after seeking/jumping. Multiple codecs, other player is OK, playback way too early (-numberhere): playing silence
- Link: https://code.videolan.org/videolan/vlc/-/issues/25532  State: closed-fixed
- Mechanism: After a seek, audio dated against an old device or media clock can appear far too early. An output may insert silence repeatedly while it waits for that stale clock relationship to catch up.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runSeek and clearBuffers; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, flush and anchorClock
- Verdict: IMMUNE
- Why: The seek stops the sink, quiesces every producer and consumer, flushes the decoder and ring under a new generation, invalidates the old audio clock, and anchors the new epoch from samples that survive the landing. No old device anchor remains for new-position audio to be judged against.
- Severity if real: P1 broken feature

### [VLC-28541] Audio device set but not available - VLC-Player crashes
- Link: https://code.videolan.org/videolan/vlc/-/issues/28541  State: open
- Mechanism: Persisting a specific output-device identifier and assuming it still resolves on the next launch turns ordinary device removal into a null or failed native output open. An unchecked failure can crash instead of selecting a fallback or reporting the unavailable route.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, buildSession and runOpen; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, open
- Verdict: IMMUNE
- Why: KitePlayer itself stores no persisted device identifier. If the supplied AudioSink cannot open, buildSession runs its reverse-order rollback and runOpen converts the failure into the player's failed state and a PlaybackException rather than allowing it to escape an unchecked native callback or crash the process.
- Severity if real: P0 crash/dataloss

### [VLC-20612] HTTP client seeking with a maximum size in Range HTTP header
- Link: https://code.videolan.org/videolan/vlc/-/issues/20612  State: wontfix
- Mechanism: The reporter inferred that an open-ended bytes=N- request caused one observed OOM, but the public description does not establish that VLC kept buffering unread response data or connect the memory growth to the Range syntax. The issue was closed invalid.
- KitePlayer code checked: kiteplayer-network/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/network/KtorMediaIo.kt, openAt; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/CachingMediaIo.kt, read and append; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerConfig.kt, NetworkConfig
- Verdict: N/A
- Why: KtorMediaIo streams its response through a bounded channel, cancels the prior body on seek, and CachingMediaIo evicts beyond its configured window. Direct URI handling uses FFmpeg's separate HTTP access layer, not VLC's implementation, and the issue provides no concrete accumulation mechanism to test against that path.
- Severity if real: P2 quality/perf

### [VLC-11873] network stream caching to disk
- Link: https://code.videolan.org/videolan/vlc/-/issues/11873  State: closed-fixed
- Mechanism: A small startup cache smooths brief jitter but cannot preserve minutes of already downloaded media across a long pause, bandwidth collapse, or server disconnect. A bounded disk spool can retain far more forward data without proportional RAM use.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/CachingMediaIo.kt, CachingMediaIo; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerConfig.kt, IoCachePolicy
- Verdict: MISSING-FEATURE
- Why: KitePlayer has one contiguous RAM window with a default 32 MB total budget and no prefetch worker. It exposes no disk-cache location, lifetime, or eviction policy, so it cannot provide the long interruption tolerance requested upstream.
- Severity if real: P2 quality/perf

### [VLC-26341] Weird seeking in MP4 files without base media decode time
- Link: https://code.videolan.org/videolan/vlc/-/issues/26341  State: closed-fixed
- Mechanism: VLC's fragmented MP4 path correctly calculated a start time from sidx, then an unconditional second branch overwrote it because tfdt was absent. That branch also mixed global and track timescales, so displayed media and reported position diverged after seek.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, TimestampMapper and seekToKeyframe; /Users/macbook/StudioProjects/#Kite/KiteCodec/kitecodec-core/src/nativeMain/kotlin/io/github/yuroyami/kitecodec/Playback.native.kt, PacketReader.read and seek
- Verdict: N/A
- Why: The unconditional overwrite and mixed-timescale expressions are specific to VLC's native MP4 demuxer. KitePlayer consumes timestamps from FFmpeg's separate MP4 implementation, and the receipt identifies no equivalent absent-tfdt fallback there. Trusting a backend timestamp does not by itself inherit a source branch that the backend does not contain.
- Severity if real: P1 broken feature

### [VLC-3019] MKV Chapter seeking does not work properly
- Link: https://code.videolan.org/videolan/vlc/-/issues/3019  State: closed-fixed
- Mechanism: Next and previous chapter commands were based on the file start or the last chapter jump rather than on the current timeline position. Repeated navigation therefore selected the wrong marker after ordinary playback advanced.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/KitePlayer.kt, chapterAt and seekToChapter; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerState.kt, chapterHolding
- Verdict: IMMUNE
- Why: seekToChapter indexes the immutable published chapter table and seeks to that chapter's absolute start. chapterAt independently derives the current chapter from the current published position and half-open chapter spans, so navigation has no mutable reference point that can drift from playback.
- Severity if real: P1 broken feature

### [VLC-28966] demux: mp4 demux stops working after seek
- Link: https://code.videolan.org/videolan/vlc/-/issues/28966  State: open
- Mechanism: VLC's native MP4 demuxer left video stopped and restarted audio at zero after a seek, while forcing its avformat demuxer made the same file resume correctly. That comparison isolates the failure to VLC's MP4 seek state rather than the media or codecs.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, seekToKeyframe; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runSeek
- Verdict: IMMUNE
- Why: KitePlayer always uses the FFmpeg demux route that the upstream reproducer identifies as working. After that seek it also flushes all selected decoders and waits for a generation-matched landing before completing, so no VLC native MP4 seek state is retained.
- Severity if real: P1 broken feature

### [VLC-28778] es events incorrectly unselect es out when switching tracks
- Link: https://code.videolan.org/videolan/vlc/-/issues/28778  State: open
- Mechanism: When an adaptive representation replaces old elementary streams, deleting an obsolete stream must not clear the selected identifier for its already active replacement. VLC emitted a deselection for the deleted old video and left public track state at no selection even while the new decoder played.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, handleTrackChanges and runDemux; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/MediaSource.kt, PlayerMediaSource.streams
- Verdict: MISSING-FEATURE
- Why: KitePlayer snapshots one stream list and one selected index set when a session opens. runDemux has no elementary-stream added, deleted, or representation-replacement event, so it cannot encounter the stale-delete bookkeeping bug, but it also cannot support the adaptive live transition that exposes it.
- Severity if real: P1 broken feature

### [VLC-29145] Video playback freezes if play/pause toggled 4 times
- Link: https://code.videolan.org/videolan/vlc/-/issues/29145  State: open
- Mechanism: Repeated pause and resume can corrupt scheduling when each pause leaves wall-clock time accumulating against the frame timer. On resume the queued pictures all look late, causing mass dropping, stutter, or a schedule that never catches up.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, applyPause and runVideoSchedule; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/VideoPlayback.kt, pauseSchedule and resumeSchedule
- Verdict: IMMUNE
- Why: Pause parks the only video scheduler and freezes its media clock. Resume shifts frameTimerNanos by exactly the paused wall interval before scheduling continues, so toggling pause repeatedly cannot charge any stopped interval to queued frames or accumulate a late-frame debt.
- Severity if real: P1 broken feature

### [VLC-29487] Burst of vlc_player_NextVideoFrame could hang
- Link: https://code.videolan.org/videolan/vlc/-/issues/29487  State: closed-fixed
- Mechanism: A burst of frame-step requests can outnumber frames remaining before EOF. If rejected steps do not each receive a terminal answer, callers wait forever after the final frame has already been consumed.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, stepOneFrame and presentFirstFrame; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/KitePlayer.kt, stepFrame
- Verdict: IMMUNE
- Why: Every step is a serialized actor command with its own CompletableDeferred. It either releases one queued frame and completes, or reaches a bounded STEP_DEADLINE and completes exceptionally. A request past EOF therefore cannot remain unanswered, even when many callers queue steps concurrently.
- Severity if real: P1 broken feature

### [VLC-27644] Playback does not actually pause if you have changed playback speed.
- Link: https://code.videolan.org/videolan/vlc/-/issues/27644  State: open
- Mechanism: At a non-unit rate, a paused media clock that keeps extrapolating from wall time advances even though output is stopped. Resuming then jumps by the pause duration multiplied by playback speed, exactly as if playback had continued invisibly.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, applyPause; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, pause; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/VideoPlayback.kt, pauseSchedule and resumeSchedule
- Verdict: IMMUNE
- Why: applyPause stops the device, freezes the audio media clock, and parks the video schedule. VideoPlayback resumes from the frozen reading and shifts its wall timer over the pause, while AudioPlayback keeps the epoch speed but not elapsed wall time, so 2x does not turn one paused minute into a two-minute jump.
- Severity if real: P1 broken feature

### [VLC-28396] decoder: first Closed Captions blocks are always skipped
- Link: https://code.videolan.org/videolan/vlc/-/issues/28396  State: open
- Mechanism: Embedded caption services can be discovered only when the master video decoder emits its first caption block. Creating the caption decoder after processing that same block loses the discovery payload and often several following blocks before the new route exists.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/Decoders.kt, VideoDecoder and SubtitleDecoder; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSubtitleDecoder.kt, KiteCodecSubtitleDecoderFactory
- Verdict: MISSING-FEATURE
- Why: KitePlayer exposes no side-data or caption-block channel from VideoDecoder to a dynamically created subtitle stream. Its subtitle factory sees only container subtitle tracks, so embedded CEA caption services extracted from video cannot be discovered or decoded at any block, including the first.
- Severity if real: P1 broken feature

### [VLC-14320] VLC missing a keyframe when unpausing (because the external HDD went in sleep mode)
- Link: https://code.videolan.org/videolan/vlc/-/issues/14320  State: open
- Mechanism: After a long pause lets storage sleep, consuming the remaining compressed data before the next keyframe while new reads stall can feed an incomplete reference chain. The decoder then displays corruption until another random-access point arrives.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, handleBuffering and applyPause; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PacketQueue.kt, isReady
- Verdict: IMMUNE
- Why: A pause retains the compressed queues and decoded frame queue instead of flushing their reference state. When the demuxer runs short and output becomes starved, handleBuffering parks video and pauses audio without deleting queued packets or resetting the decoder, then resumes only after every selected stream reaches readiness again.
- Severity if real: P2 quality/perf

### [VLC-2407] Add option to select video track on multi video track input
- Link: https://code.videolan.org/videolan/vlc/-/issues/2407  State: open
- Mechanism: Opening every video elementary stream at once creates multiple decoders and outputs when the application wanted one chosen view. A selectable single-video policy must be applied before packet routing and decoder construction.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, buildSession and handleTrackChanges; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/Tracks.kt, selectedVideo
- Verdict: IMMUNE
- Why: buildSession chooses exactly one videoStream and creates only its decoder. selectStreams routes that one video index plus every audio and subtitle index needed for bounded alternate-track caches, but no second video index. The public API switches video through a serialized rebuild, and Tracks exposes one selectedVideo.
- Severity if real: P1 broken feature

### [VLC-2951] repeated use of "next frame" leads to freeze
- Link: https://code.videolan.org/videolan/vlc/-/issues/2951  State: closed-fixed
- Mechanism: Frame stepping freezes when a request waits indefinitely for a decoder or renderer transition that cannot produce another frame, preventing playback, close, and later commands from acquiring the same player control path.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, stepOneFrame and presentFirstFrame; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/FrameQueue.kt, advance
- Verdict: IMMUNE
- Why: One step asks the already-filled queue to release one frame and waits only until STEP_DEADLINE. It performs no decoder flush or seek, and its actor command completes exceptionally when no frame arrives, so a failed step cannot hold the control loop forever or block stop.
- Severity if real: P1 broken feature

### [VLC-28027] Pitch out of control
- Link: https://code.videolan.org/videolan/vlc/-/issues/28027  State: closed-fixed
- Mechanism: Repeated seeks can splice old resampler or tempo-filter history into samples from the new position. The discontinuous waveform and stale rate state are heard as wrong pitch, slowing, or interference until the filter converges again.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runSeek and clearBuffers; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/AudioPipeline.kt, reset; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, flush
- Verdict: IMMUNE
- Why: Every seek that reaches the pipeline forms a new generation, stops the sink, empties the ring, resets both resampler and tempo stage, and re-anchors from new-position samples. Pending rapid requests coalesce, while a request arriving during a landing supersedes it and then repeats the same complete reset, so no old filter history crosses either boundary.
- Severity if real: P2 quality/perf

### [VLC-27956] Fast forward and slow, sound and picture are out of sync
- Link: https://code.videolan.org/videolan/vlc/-/issues/27956  State: closed-fixed
- Mechanism: Playback rate changes desynchronize A/V when audio samples, the master clock, and video frame waits are scaled under different laws. One side then advances in media time while the other advances in wall time.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, speed and anchorLocked; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/VideoPlayback.kt, speed and tick; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, masterPosition
- Verdict: IMMUNE
- Why: Audio dates its ring on a media-time divided by rate axis, multiplies anchors back to media time, and extrapolates the master at the epoch speed. Video divides its media delay by the same applied speed and compares against that audio master, with both values promoted only across the same seek flush.
- Severity if real: P1 broken feature

### [VLC-20243] Regressions: Playback issues after update from 2.2.8 to 3.0 with speed change.
- Link: https://code.videolan.org/videolan/vlc/-/issues/20243  State: closed-fixed
- Mechanism: A speed change that resets buffering and decoder state can leave seconds of silence or damaged video if restart waits for only one stream, reuses pre-change packets, or resumes decoding without a clean random-access landing.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, execute CoreCommand.SetSpeed and runSeek; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, flush
- Verdict: SUSPECT
- Why: KitePlayer closes stale-data and mixed-rate paths with a full generation flush and waits for all selected streams, but every live rate change is implemented as a precise seek and complete preroll. That deliberately creates a playback interruption, so the upstream seconds-long symptom should be smaller but an audible or visible transition remains plausible without a device test.
- Severity if real: P2 quality/perf

### [VLC-3152] VLC does not render the beginning of audio files
- Link: https://code.videolan.org/videolan/vlc/-/issues/3152  State: closed-fixed
- Mechanism: Dropping the first decoded PCM while an output is still opening removes real leading samples, especially obvious when a file begins with a sharp transient. Startup buffering must retain data until the sink is ready rather than decode and discard it.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, awaitInitialFill, runAudioDecode, and runAudioFeed; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, submitDecoded
- Verdict: IMMUNE
- Why: The sink opens before selected packet routing and workers start. Decoded buffers then wait in a bounded handoff and the audio ring while awaitInitialFill collects preroll; no startup branch consumes or trims them, and playback starts the already-filled ring rather than reopening the device after decode.
- Severity if real: P1 broken feature

### [VLC-25051] frame by frame feature stops working for short videos
- Link: https://code.videolan.org/videolan/vlc/-/issues/25051  State: closed-fixed
- Mechanism: A frame-step implementation based on estimated duration or percentage can land repeatedly on the same timestamp in short media, so the apparent cursor stops advancing well before EOF.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, stepOneFrame; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/FrameQueue.kt, advance
- Verdict: IMMUNE
- Why: KitePlayer does not convert a step to time or duration. It removes exactly the next decoded frame from FrameQueue and publishes that frame's PTS, so media length, nominal frame rate, and seek-bar precision cannot make a mid-file step resolve to the current frame again.
- Severity if real: P1 broken feature

### [VLC-18869] Next Frame hotkey malfunctioning
- Link: https://code.videolan.org/videolan/vlc/-/issues/18869  State: closed-fixed
- Mechanism: Repeated next-frame commands fail when the stepping path loses its one-frame acknowledgement or reuses a stale clock position to request the same picture. The queue can then hold frames while the control path believes none advanced.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, stepOneFrame and reportFirstFrame; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/VideoPlayback.kt, tick
- Verdict: IMMUNE
- Why: The one-frame scheduler mode is cleared only after VideoPlayback reports a released frame, including a renderer refusal, and stepOneFrame publishes the resulting video PTS before acknowledging the command. Every next call therefore begins after the preceding queue advance, not from a guessed clock target.
- Severity if real: P1 broken feature

### [VLC-18659] 3.0 regression: invalid channel count
- Link: https://code.videolan.org/videolan/vlc/-/issues/18659  State: open
- Mechanism: A decoder output with zero channel count cannot configure a valid mixer or audio output and must be rejected before it becomes the player-visible audio format.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/buildSrc/src/main/kotlin/BuildFFmpegTask.kt, DEFAULT_SOURCE_REF; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, audioFormat and KiteCodecAudioDecoder.outputFormat and receive
- Verdict: IMMUNE
- Why: The shipped backend pins FFmpeg n8.0, whose decode frame_validate rejects an invalid channel layout or nonpositive sample rate before returning a frame. KiteCodecSource additionally clamps every initial channel count into 1 through 8 and adopts a later rate or count only when both are positive, so zero cannot reach the shipped AudioFormat path.
- Severity if real: P1 broken feature

### [VLC-20682] deadlock by timeshift timing
- Link: https://code.videolan.org/videolan/vlc/-/issues/20682  State: closed-fixed
- Mechanism: VLC's input timeshift layer reset rate while EOF teardown waited for decoder FIFOs to empty. At sufficiently slow speed those mutually dependent transitions left the input polling empty forever.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, handleEof; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, endOfStream
- Verdict: N/A
- Why: KitePlayer has no input timeshift layer or automatic EOF rate reset. Its rate is an epoch value and EOF drains the existing decoder, handoff, DSP, ring, and sink state without changing speed, so the mutually dependent VLC timeshift transition does not exist.
- Severity if real: P1 broken feature

### [VLC-27499] VLC crashes near end of playback (assert when draining and seeking)
- Link: https://code.videolan.org/videolan/vlc/-/issues/27499  State: closed-fixed
- Mechanism: A seek near EOF races the old generation's decoder drain. If the seek flushes or relabels the decoder while the terminal path still asserts it is draining, either side can observe an impossible mixed state and crash.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, handleEof, runSeek, quiesceWorkers, and clearBuffers
- Verdict: IMMUNE
- Why: Both seek and EOF decisions are serialized on one actor. The seek resets the EOF state, parks every worker, flushes decoders into a new generation, and only then releases them; stale EOS packets and frames are rejected by generation, so no decoder can be draining the old epoch concurrently with its seek flush.
- Severity if real: P0 crash/dataloss

### [VLC-27508] input/decoder.c: vlc_aout_stream Flush and Play can be called in //
- Link: https://code.videolan.org/videolan/vlc/-/issues/27508  State: wontfix
- Mechanism: Calling audio output flush and play concurrently lets one thread discard or reset buffers while another starts the device over them. The race can expose freed state, replay old samples, or leave the output clock inconsistent.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runSeek, handlePlaybackRestart, and releaseSession; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, play and flush
- Verdict: IMMUNE
- Why: Play and flush are session-owner operations reached from the same actor. A seek also sets the scheduler idle, stops the sink, and waits for every worker before AudioPlayback.flush, and handlePlaybackRestart cannot run while seekPhase is active, structurally excluding a parallel play.
- Severity if real: P0 crash/dataloss

### [VLC-26716] 4.0 regression: live input crashes pseudo-randomly
- Link: https://code.videolan.org/videolan/vlc/-/issues/26716  State: closed-fixed
- Mechanism: VLC attached an input-clock listener after that clock already held a reference, violating its one-listener state invariant and firing an assertion on V4L or RTP live inputs.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/MediaClock.kt, MediaClock; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, masterPosition and runDemux
- Verdict: IMMUNE
- Why: KitePlayer has no input PCR listener attachment API. Its audio and video clocks are session-owned values with one writer each, and runDemux cannot install or replace a listener based on whether an input can pace, so the asserted double-attach state is absent.
- Severity if real: P0 crash/dataloss

### [VLC-26621] Discontinue after resume from rebuffering
- Link: https://code.videolan.org/videolan/vlc/-/issues/26621  State: open
- Mechanism: VLC recovered from underflow by resetting PCR and flushing decoders. That discarded already buffered packets and reference frames, so playback resumed several seconds later at the next usable GOP rather than where output paused.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, handleBuffering and handlePlaybackRestart; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PacketQueue.kt, PacketQueue
- Verdict: IMMUNE
- Why: Rebuffer entry only parks the video scheduler and pauses audio. It does not increment generation, flush a decoder, clear a packet queue, reset timestamps, or seek. Restart uses the same queued packets and decoder reference state after every selected stream refills, preserving the paused media position.
- Severity if real: P1 broken feature

### [VLC-26487] core: remove polling for Drain/IsEmpty
- Link: https://code.videolan.org/videolan/vlc/-/issues/26487  State: open
- Mechanism: Polling output drain state at a fixed interval delays terminal notification by as much as one interval and wakes the input thread repeatedly while nothing changes. A completion signal removes both latency and idle wakeups.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, handleEof and scheduleNextPass; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, buffered
- Verdict: SUSPECT
- Why: KitePlayer uses explicit atomic drain flags, but the actor still observes decoder, handoff, ring, and sink completion on repeated passes separated by WORKER_POLL. It avoids VLC's 100 ms interval, yet terminal latency and wakeup overhead remain polling rather than event driven.
- Severity if real: P3 polish

### [VLC-26476] DemuxLoop controls triggering buffering starvation/PCR_RESET
- Link: https://code.videolan.org/videolan/vlc/-/issues/26476  State: open
- Mechanism: If demux processes a synchronous control before it re-enters buffering, output can consume the last queued media first. By the time underflow is recognized the clock is already behind its synchronization point and requires a disruptive reset.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runDemux, handleBuffering, demuxerRanShort, and outputStarved
- Verdict: SUSPECT
- Why: KitePlayer never resets PCR or flushes on rebuffer, which limits damage, but it declares rebuffering only after both demux shortage and actual output starvation. That second condition means a sink callback may already have emitted silence or a video queue may already be empty before the actor parks output.
- Severity if real: P2 quality/perf

### [VLC-26159] CEA-708 subtitles not detected when service does not start in first frame
- Link: https://code.videolan.org/videolan/vlc/-/issues/26159  State: wontfix
- Mechanism: Caption-service detection performed only during initial video frames permanently classifies a stream as CEA-608 when its first DTVCC packet arrives later. Service discovery must remain live or be revised when later caption side data identifies CEA-708.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/Decoders.kt, VideoDecoder and SubtitleDecoderFactory; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSubtitleDecoder.kt, KiteCodecSubtitleDecoderFactory
- Verdict: MISSING-FEATURE
- Why: No VideoDecoder result carries caption side data, and no dynamic caption service becomes a PlayerStreamInfo. KitePlayer therefore cannot misclassify a late CEA-708 service as 608 because it cannot expose either embedded service at all.
- Severity if real: P1 broken feature

### [VLC-23717] desync audio with video due to subtitle delay shift when using shortcut keys
- Link: https://code.videolan.org/videolan/vlc/-/issues/23717  State: closed-fixed
- Mechanism: Updating subtitle delay through a shared input timing control can accidentally perturb the audio or master-clock offset, turning a text-only adjustment into A/V drift.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, execute CoreCommand.SetSubtitleDelay, timeAndPublishCues, and masterPosition
- Verdict: IMMUNE
- Why: SetSubtitleDelay writes only subtitleDelay and invalidates the published cue key. Cue selection subtracts that value from currentPosition, while masterPosition reads only audioDelay; no audio buffer, video clock, speed, or seek state is touched.
- Severity if real: P1 broken feature

### [VLC-28214] In VLC media player, when the video is paused, the subtitles still move a small amount forward
- Link: https://code.videolan.org/videolan/vlc/-/issues/28214  State: open
- Mechanism: Subtitle timing drifts after pause when it reads an extrapolating clock that is frozen later than the picture or not frozen at all. A cue boundary can then pass while the displayed frame remains still.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, applyPause and timeAndPublishCues; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/MediaClock.kt, pause
- Verdict: IMMUNE
- Why: applyPause parks video, consumes the final audio anchor, stops the device, and freezes the media clock before it publishes Paused. Subtitle selection reads that frozen currentPosition on later actor passes, so no cue boundary advances during the paused interval.
- Severity if real: P2 quality/perf

### [VLC-28308] Properly timed WebVTT/TTML now broken
- Link: https://code.videolan.org/videolan/vlc/-/issues/28308  State: open
- Mechanism: A subtitle slave that is already authored on the master's timeline must not receive the automatic offset used for zero-based sidecar files. Applying that offset twice shifts otherwise correct WebVTT or TTML timing away from the video.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, parseExternalSubtitle and timeAndPublishCues; kiteplayer-subtitles/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/subtitle/WebVttParser.kt, parse
- Verdict: SUSPECT
- Why: KitePlayer parses local external WebVTT cue times directly and performs no conditional normalization against the media's first and last PTS. That is correct for a zero-based sidecar but exposes no policy for a file authored against a nonzero master timeline, so one of the two alignment conventions can be shifted.
- Severity if real: P1 broken feature

### [VLC-28521] Random input deadlock when switching or starting media
- Link: https://code.videolan.org/videolan/vlc/-/issues/28521  State: open
- Mechanism: A decoder blocked waiting for a hardware picture-pool slot can prevent input teardown from destroying it, while the output holding those slots waits for that same teardown. Track switch and close then deadlock across the decoder and renderer ownership cycle.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, handOverVideoFrame, releaseSession, and handleTrackChanges; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/VideoPlayback.kt, trySubmit
- Verdict: SUSPECT
- Why: KitePlayer's frame-queue handoff retries in bounded steps and closes a retained hardware frame on interruption. A backend decoder send or receive can still block on its surface pool, and releaseSession joins canceled workers before decoder close. That seam remains during a video-track rebuild, a switch batch that includes video, or session close; standalone audio and subtitle switches avoid it through in-place transactions.
- Severity if real: P1 broken feature

### [VLC-28968] [rub.de/06] Possible stack-based buffer overflow in `aout_ChannelReorder` (audio output)
- Link: https://code.videolan.org/videolan/vlc/-/issues/28968  State: closed-fixed
- Mechanism: VLC built a fixed nine-channel stack buffer but indexed it through a reorder table whose unused entries could remain uninitialized. A malformed WAV channel declaration then made those values exceed the buffer and write outside its lifetime.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/ChannelMixer.kt, ChannelMixer and mix; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/Decoders.kt, AudioFormat
- Verdict: IMMUNE
- Why: ChannelMixer constructs a fully initialized matrix whose length is exactly targetChannels times sourceChannels, rejects nonpositive counts, checks both input and output array sizes, and indexes only explicit 0-until-channel loops. There is no partially filled reorder table or fixed native stack buffer.
- Severity if real: P0 crash/dataloss

### [VLC-27541] audiotrack: make it non-blocking
- Link: https://code.videolan.org/videolan/vlc/-/issues/27541  State: closed-fixed
- Mechanism: Calling a blocking AudioTrack write from the decoder or player control path lets a full device buffer stall demux, seeking, pause, and shutdown. The output needs a separate FIFO and writer or a callback that never blocks the owner.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/AudioSink.kt, AudioSink and AudioRenderCallback; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, submit; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runAudioFeed
- Verdict: IMMUNE
- Why: Decoding hands PCM to a bounded channel and a dedicated feeder, then AudioPlayback writes a ring in short retry steps. Android's push-model sink is required by the SPI to wrap device writes behind its own writer, so neither the decoder worker nor actor performs a blocking AudioTrack write.
- Severity if real: P1 broken feature

### [VLC-27340] Bluray Infinite audio drain
- Link: https://code.videolan.org/videolan/vlc/-/issues/27340  State: open
- Mechanism: Blu-ray navigation requested a drain whose GET_EMPTY condition never became true, so the access module printed Draining forever instead of completing the title or transition.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, handleEof; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, drain
- Verdict: N/A
- Why: KitePlayer has no Blu-ray access, navigation VM, title transition, or GET_EMPTY control. Its ordinary file drain is relevant to other rows, but the disc-specific caller and state loop described here do not exist.
- Severity if real: P1 broken feature

### [VLC-29714] Lag and audio stutter occur when resuming playback after pause on VLC (Debian 12 stable).
- Link: https://code.videolan.org/videolan/vlc/-/issues/29714  State: open
- Mechanism: Pause and resume stutter when the device, buffered PCM, and master clock do not preserve the same position. Repeated stop and play transitions can inject silence while video alternately catches up and waits.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, pause and play; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, applyPause and handlePlaybackRestart
- Verdict: SUSPECT
- Why: The clock is frozen and the ring is retained, which closes the stale-time path, but AudioPlayback falls back from an unsupported sink pause by calling sink.stop. Samples already consumed from the ring into a device buffer may then be discarded, and the generic contract provides no restoration proof for that platform-specific interval on resume.
- Severity if real: P2 quality/perf

### [VLC-29705] aout: Ambisonic assertion on fomat update
- Link: https://code.videolan.org/videolan/vlc/-/issues/29705  State: open
- Mechanism: An Opus stream can update to an ambisonic layout during decode. Rebuilding output filters under assumptions from the previous channel geometry violates format invariants and can assert.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, submitDecoded; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/AudioPipeline.kt, matches and rebuiltFor; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/ChannelMixer.kt, sourceLayout
- Verdict: IMMUNE
- Why: Every AudioBuffer carries its own AudioFormat. submitDecoded compares that format with the active pipeline and constructs a fresh mixer, resampler, and tempo chain before processing a changed buffer. An unmodeled ambisonic mask warns and uses bounded channel pass-through instead of asserting a known layout.
- Severity if real: P0 crash/dataloss

### [VLC-29491] [3.0] pipewire audio output debug: too early to start, silence
- Link: https://code.videolan.org/videolan/vlc/-/issues/29491  State: open
- Mechanism: Audio-track switching can leave the output judging new samples against the prior track's clock, repeatedly classifying them as too early and substituting silence forever.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, inPlaceAudioChange and resetAudioAfterTrackChange; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, anchorClock and flush
- Verdict: IMMUNE
- Why: The in-place transaction parks audio workers, anchors the audible old position, drains decoded old-track buffers, flushes the ring and invalidates its media clock, then atomically installs the prepared lane and resets audio status. New timing anchors only from new-track ring segments, while video and demux remain live.
- Severity if real: P1 broken feature

### [VLC-27537] alsa: make it non-blocking
- Link: https://code.videolan.org/videolan/vlc/-/issues/27537  State: closed-fixed
- Mechanism: A blocking ALSA write holds the producer when the device buffer is full. If that producer also owns decoder or input progress, control operations and teardown wait behind device pacing.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/AudioSink.kt, AudioSink contract; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, submit; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runAudioFeed
- Verdict: IMMUNE
- Why: The SPI standardizes every push backend as a private writer that pulls from the engine ring. The feeder suspends only on that bounded ring and polls quiescence every 2 ms, while the actor and decoder have separate jobs, so an ALSA device wait cannot occupy the control owner.
- Severity if real: P1 broken feature

### [VLC-28824] [Discussion] Audio output design: recursion in vlc_aout_stream_Play
- Link: https://code.videolan.org/videolan/vlc/-/issues/28824  State: open
- Mechanism: VLC's play function calls discontinuity handling, which iterates buffered blocks by calling play again. A block that still satisfies the discontinuity condition recursively enters the same pair until stack exhaustion.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, submitDecoded and flush; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runAudioFeed
- Verdict: IMMUNE
- Why: Audio submission and discontinuity handling are separate nonrecursive paths. The feeder iteratively processes one decoded buffer, while a seek or track change parks it and resets the pipeline from the actor. No submit function can call flush and then call itself over a retained buffer list.
- Severity if real: P0 crash/dataloss

### [VLC-27918] Audio glitch (underflow) when starting playback with a negative audio delay
- Link: https://code.videolan.org/videolan/vlc/-/issues/27918  State: open
- Mechanism: Implementing negative audio delay by withholding or redating audio can start the device before it has samples, producing an underflow while the requested offset is established.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, execute CoreCommand.SetAudioDelay and masterPosition; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, submitDecoded
- Verdict: IMMUNE
- Why: KitePlayer never changes PCM delivery for audio delay. It adds the delay only to the master reading used by the video scheduler, so a negative value holds or extends video relative to continuously filled audio rather than creating a hole in the audio ring.
- Severity if real: P2 quality/perf

### [VLC-27737] 22.2 surround sound (or handle more than 7.1)
- Link: https://code.videolan.org/videolan/vlc/-/issues/27737  State: wontfix
- Mechanism: Playing 22.2 correctly requires representing all speaker identities, negotiating a capable sink, and applying downmix matrices when the device accepts fewer channels. A mere channel count cannot preserve that routing.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/Decoders.kt, ChannelLayout and AudioFormat; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/ChannelMixer.kt, MixLayout
- Verdict: MISSING-FEATURE
- Why: Public ChannelLayout stops at Surround71 and MixLayout has matrices only through eight channels. A larger count becomes Unknown and passes only the first channels that fit the target, so KitePlayer cannot preserve or correctly downmix a 22.2 speaker bed.
- Severity if real: P1 broken feature

### [VLC-26829] The audio output might take too long to initialize the master clock
- Link: https://code.videolan.org/videolan/vlc/-/issues/26829  State: wontfix
- Mechanism: A high-latency device may not expose a usable audio clock during the initial input burst. Video starts without its master, and when the delayed master finally appears several queued frames can suddenly be judged late and dropped.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, anchorClock and position; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/VideoPlayback.kt, tick; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, masterPosition
- Verdict: SUSPECT
- Why: KitePlayer uses the render callback's audible deadline rather than polling device latency, but masterPosition is still null until the first ring anchor exists. Video self-paces during that window, then begins correction when the delayed audio anchor arrives. No startup law bounds the resulting first correction on a slow Bluetooth device.
- Severity if real: P2 quality/perf

### [VLC-28467] Audio delay and video freeze after pause
- Link: https://code.videolan.org/videolan/vlc/-/issues/28467  State: wontfix
- Mechanism: A long pause can make resumed audio wait one or two seconds for a device or buffer restart, after which video freezes briefly to repair the newly exposed clock gap.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, pause and play; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/VideoPlayback.kt, resumeSchedule; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, handlePlaybackRestart
- Verdict: SUSPECT
- Why: Media clocks and the video frame timer exclude paused wall time, but the fallback for a sink that cannot pause is stop followed later by start. The SPI does not state how quickly that device restart becomes audible, so a long-pause platform can still expose a silent startup interval and a subsequent SyncLaw correction.
- Severity if real: P2 quality/perf

### [VLC-28336] aout flushing in loop with --master-clock=input, when the audio device has a latency > 200ms
- Link: https://code.videolan.org/videolan/vlc/-/issues/28336  State: open
- Mechanism: With input rather than audio as master, a device latency above the correction threshold makes audio look perpetually early. Flushing and restarting the output does not remove physical latency, so the same comparison triggers an endless flush loop.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerConfig.kt, SyncMode; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, masterPosition; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, anchorClock
- Verdict: IMMUNE
- Why: KitePlayer has AudioMaster and VideoMaster modes, not an input PCR master that flushes audio on drift. In VideoMaster, masterPosition deliberately returns null and the video paces itself; no audio-timing comparison calls AudioPlayback.flush, so fixed device latency cannot create this feedback loop.
- Severity if real: P1 broken feature

### [VLC-27023] clock: audio/video synchronisation issues
- Link: https://code.videolan.org/videolan/vlc/-/issues/27023  State: closed-fixed
- Mechanism: VLC estimated a clock coefficient from irregular bursts of audio buffers. Its long-term average took seconds to converge and depended on buffer size, so Bluetooth latency and bursty input delayed the master offset and caused startup frame drops.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/MediaClock.kt, setAt and nowOrNull; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, anchorLocked; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/KotlinAudioRing.kt, anchor
- Verdict: IMMUNE
- Why: KitePlayer estimates no coefficient from buffer arrival cadence. The real-time callback records which media PTS becomes audible at a monotonic deadline, MediaClock stores that single affine anchor, and later readings extrapolate at the configured rate. Irregular producer bursts do not enter the clock equation.
- Severity if real: P1 broken feature

### [VLC-22478] Forward an on_device_hotplugged to aout player callback
- Link: https://code.videolan.org/videolan/vlc/-/issues/22478  State: open
- Mechanism: Detecting device hotplug only inside the audio module leaves the application unable to refresh routes or tell the user why output changed. The event and device identity must cross the sink boundary.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/AudioSink.kt, events and AudioSinkEvent.DeviceChanged; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, audio event collector
- Verdict: IMMUNE
- Why: AudioSink exposes a Flow of device events and PlaybackCore collects DeviceChanged and DeviceLost into typed PlaybackWarning.AudioDeviceChanged notifications. Automatic route recovery is still absent and recorded separately, but the forwarding mechanism requested here exists.
- Severity if real: P2 quality/perf

### [VLC-24828] Audio skips at beginning of playback -- pulseaudio/jack
- Link: https://code.videolan.org/videolan/vlc/-/issues/24828  State: closed-fixed
- Mechanism: Starting output before its stream is fully created makes the first writes race backend readiness. PCM is then skipped or stuttered until the PulseAudio-to-JACK route finishes initialization.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, buildSession, awaitInitialFill, and handlePlaybackRestart; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, open and play
- Verdict: IMMUNE
- Why: buildSession awaits AudioSink.open before packet routing starts, then fills the ring while the sink remains stopped. handlePlaybackRestart calls play only after every selected stream reaches readiness, so no decoded sample is submitted to a half-created output and the first samples are already buffered at start.
- Severity if real: P1 broken feature

### [VLC-27690] pulse aout fails to enter drain
- Link: https://code.videolan.org/videolan/vlc/-/issues/27690  State: closed-fixed
- Mechanism: If a sink never acknowledges entry into drain, decoder FIFO teardown waits indefinitely even after all PCM has been handed over. Tiny clips make the race frequent because startup and drain occur almost together.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, drain; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, handleEof
- Verdict: IMMUNE
- Why: handleEof gives the buffered ring a finite grace and then wraps AudioPlayback.drain in DRAIN_DEADLINE. A sink that never acknowledges drain produces AudioDrainIncomplete, marks the drain failed, and still advances to Ended instead of holding the actor indefinitely.
- Severity if real: P1 broken feature

### [VLC-21663] Using the arrow keys (-/+ 10 secs) whilst playing a .mp3 file causes the audio to temporarily play slower
- Link: https://code.videolan.org/videolan/vlc/-/issues/21663  State: closed-fixed
- Mechanism: Seeking without resetting tempo or resampler state can interpolate new-position samples from old-position history, briefly altering cadence or pitch after each jump.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runSeek and clearBuffers; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/AudioPipeline.kt, reset
- Verdict: IMMUNE
- Why: Every seek that reaches decode quiesces the feeder, empties the ring, and resets resampler and tempo state before new packets are decoded. Pending relative seeks coalesce, and one arriving during a landing supersedes that attempt before the next complete reset, so no old-position filter history reaches the accepted epoch.
- Severity if real: P2 quality/perf

### [VLC-25001] Pitch change when clicking on progress bar
- Link: https://code.videolan.org/videolan/vlc/-/issues/25001  State: closed-fixed
- Mechanism: Repeated seeks that reuse filter delay lines accumulate a small pitch error on every discontinuity. Clicking the same target makes the error grow even though the requested playback rate never changed.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runSeek; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, flush; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/TempoStage.kt, reset
- Verdict: IMMUNE
- Why: A seek creates a fresh speed epoch and resets both conversion stages under feeder quiescence. Repeated clicks either coalesce into one target or repeat the same complete reset, so no fractional pitch state survives and accumulates across requests.
- Severity if real: P2 quality/perf

### [VLC-22737] The sound disappears when the adjustment speed is too slow or too fast
- Link: https://code.videolan.org/videolan/vlc/-/issues/22737  State: closed-fixed
- Mechanism: A tempo filter can stop producing samples beyond its supported stretch range while video continues, yielding silence or a frozen picture instead of rejecting the unsupported rate.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/TempoStage.kt, speed, MIN_SPEED, and MAX_SPEED; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, speed
- Verdict: IMMUNE
- Why: TempoStage explicitly supports only 0.25x through 4.0x and both its setter and AudioPlayback reject any rate outside that range before it reaches live pipeline state. Inside the range, process and finish retain queued samples rather than using a silent unsupported branch.
- Severity if real: P1 broken feature

### [VLC-24311] EOF reached (VLC_DEMUXER_EOF) shortly before file end locks actions
- Link: https://code.videolan.org/videolan/vlc/-/issues/24311  State: open
- Mechanism: Demux EOF can precede audible EOF by the amount already buffered. If control setters stop affecting that queued tail, a successful late fade or volume command changes state but not the final hundreds of milliseconds the listener hears.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, handleEof and execute CoreCommand.SetVolume; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/GainStage.kt, apply; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, volume
- Verdict: SUSPECT
- Why: KitePlayer keeps processing commands until sink drain completes, but volume is applied when the feeder converts a buffer. Samples already in the ring or device cannot be revised, so a late command can report success while up to the buffered tail remains at the previous gain.
- Severity if real: P2 quality/perf

### [VLC-27807] Audio sample rate arbitrary lower limit prevents playback of valid narrowband audio
- Link: https://code.videolan.org/videolan/vlc/-/issues/27807  State: open
- Mechanism: Rejecting every sample rate below an arbitrary 4 kHz threshold excludes valid 2 kHz Morse or RTTY audio even though it satisfies Nyquist for its intended signal.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/Decoders.kt, AudioFormat; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/SincResampler.kt, SincResampler; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, open
- Verdict: IMMUNE
- Why: AudioFormat imposes no 4 kHz floor and SincResampler accepts every positive source rate. A device may negotiate a conventional output rate, after which the sinc stage converts the 2 kHz source instead of rejecting it at the player core.
- Severity if real: P1 broken feature

### [VLC-27544] aout: make play non-blocking
- Link: https://code.videolan.org/videolan/vlc/-/issues/27544  State: open
- Mechanism: Every audio-output play implementation must avoid blocking the decoder owner on device capacity. A large buffer or a dedicated thread and FIFO separates media progress from hardware pacing.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/AudioSink.kt, AudioSink contract; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/AudioRingHandle.kt, AudioRingHandle; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runAudioFeed
- Verdict: IMMUNE
- Why: The architecture always places an audio ring between decoded PCM and the device. A dedicated feeder owns potentially suspending ring writes, every push sink wraps its device writer privately, and the real-time callback path may return silence but may not block.
- Severity if real: P1 broken feature

### [VLC-27258] Multiple Audio Gaps After Pause
- Link: https://code.videolan.org/videolan/vlc/-/issues/27258  State: closed-fixed
- Mechanism: On resume, samples dated against a pre-pause clock appear too early and the output fills the perceived gap with silence several times until timestamps converge.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, pause, play, and anchorLocked; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/MediaClock.kt, pause and resume
- Verdict: SUSPECT
- Why: The media clock correctly freezes and re-anchors, so stale wall time is excluded. If a sink cannot pause, however, AudioPlayback stops it after the callback may already have consumed samples into the device buffer. Those discarded samples are no longer in the ring to replay, leaving a platform-dependent resume gap even with correct timestamps.
- Severity if real: P2 quality/perf

### [VLC-23009] MP3 detuning during playback
- Link: https://code.videolan.org/videolan/vlc/-/issues/23009  State: open
- Mechanism: Repeated backward skips can carry MP3 decoder or rate-conversion state across discontinuities, producing a persistent pitch shift that does not affect uncompressed WAV.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, flushDecoders and clearBuffers; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, flush
- Verdict: IMMUNE
- Why: Backward seeks flush the compressed decoder on its owning dispatcher and reset every player-side conversion stage before the new generation begins. The MP3-specific decoder history and common resampler history are both retired rather than reused.
- Severity if real: P2 quality/perf

### [VLC-26956] Audio delay not working properly
- Link: https://code.videolan.org/videolan/vlc/-/issues/26956  State: closed-fixed
- Mechanism: An audio-delay setter that writes only transient UI or clock state can be overwritten on the next update, visibly snapping back to the previous value.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, execute CoreCommand.SetAudioDelay, audioDelay, and publishSnapshot
- Verdict: IMMUNE
- Why: The actor owns one persistent audioDelay value, updates it atomically with the command, uses it on every subsequent masterPosition reading, and publishes that same value in snapshots. No device callback or timing update writes it back.
- Severity if real: P2 quality/perf

### [VLC-25120] Audio lost for a second in fast forward
- Link: https://code.videolan.org/videolan/vlc/-/issues/25120  State: open
- Mechanism: Entering and leaving a faster playback rate can empty the old-rate output before enough new-rate samples are processed, creating a roughly one-second mute at each transition.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, execute CoreCommand.SetSpeed and runSeek; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, speed and flush
- Verdict: SUSPECT
- Why: KitePlayer prevents rate mixing by turning every live change into a full precise seek, sink stop, flush, and preroll. That bounds state correctly but deliberately empties the output, so a transition mute remains possible and its duration depends on source seek, decode, and device restart latency.
- Severity if real: P2 quality/perf

### [VLC-24607] audio breaks after delaying audio track for a certain amount
- Link: https://code.videolan.org/videolan/vlc/-/issues/24607  State: open
- Mechanism: Implementing a large audio delay by buffering or discarding PCM can exceed an internal delay window, after which audio disappears and cannot recover when the value returns to zero.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, execute CoreCommand.SetAudioDelay and masterPosition; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, submitDecoded
- Verdict: IMMUNE
- Why: Audio delay never buffers, discards, or redates PCM in KitePlayer. It is an unbounded Duration bias applied only when video reads the audio master, so even a 30-second value leaves the audio ring and device delivery unchanged and clearing it immediately removes the bias.
- Severity if real: P1 broken feature

### [VLC-29331] [Coverity 1666126] Out-of-bounds access in cea708.c
- Link: https://code.videolan.org/videolan/vlc/-/issues/29331  State: closed-fixed
- Mechanism: A CEA-708 window cursor at the last legal row can be advanced into row 15 after writing a character, and a later array access then reads outside the fixed 15-row table.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSubtitleDecoder.kt, KiteCodecSubtitleDecoderFactory.create
- Verdict: MISSING-FEATURE
- Why: KitePlayer creates subtitle decoders only for SubRip, MP4 timed text, WebVTT, ASS, and SSA. It has no CEA-708 decoder, so the vulnerable window model is absent but embedded CEA-708 captions are not available either.
- Severity if real: P0 crash/dataloss

### [VLC-29330] [Coverity 1419825] Out-of-bounds write in cea708.c
- Link: https://code.videolan.org/videolan/vlc/-/issues/29330  State: closed-fixed
- Mechanism: Scrolling a full top-to-bottom CEA-708 window copies the last row to index 15 before adjusting the window bounds, writing one pointer beyond the 15-element row array.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSubtitleDecoder.kt, KiteCodecSubtitleDecoderFactory.create
- Verdict: MISSING-FEATURE
- Why: No KitePlayer subtitle decoder accepts CEA-708, and no local cue type owns a mutable 15-row caption window. The exact overwrite is absent because the whole caption format is unsupported.
- Severity if real: P0 crash/dataloss

### [VLC-29325] [oss-fuzz 4626780574253056]  Heap-buffer-overflow READ 1 in cvdsub.c
- Link: https://code.videolan.org/videolan/vlc/-/issues/29325  State: closed-fixed
- Mechanism: A malformed CVD subtitle packet reaches metadata parsing with fewer bytes than its fields require, and the native decoder reads one byte beyond the reassembled allocation.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSubtitleDecoder.kt, KiteCodecSubtitleDecoderFactory.create; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/subtitle/SubtitleCue.kt, SubtitleCue.Bitmap
- Verdict: MISSING-FEATURE
- Why: The shipped factory has no CVD subtitle decoder and emits no Bitmap cues from container packets. That removes this native parser but also means CVD subtitles never render.
- Severity if real: P0 crash/dataloss

### [VLC-29286] [oss-fuzz 4889684824358912]  Heap-buffer-overflow in cvdsub Reassemble
- Link: https://code.videolan.org/videolan/vlc/-/issues/29286  State: closed-fixed
- Mechanism: CVD packet reassembly trusts metadata layout beyond a 32-byte allocation and lets ParseMetaInfo read immediately after the allocated buffer.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSubtitleDecoder.kt, KiteCodecSubtitleDecoderFactory.create
- Verdict: MISSING-FEATURE
- Why: KitePlayer has no CVD packetizer or decoder. Its supported container subtitle path handles only text codecs, so this reassembly bug is absent together with CVD subtitle support.
- Severity if real: P0 crash/dataloss

### [VLC-28960] [rub.de/02] Heap out-of-bounds write in spudec/parse.c `ParseRLE` (SPU decoder)
- Link: https://code.videolan.org/videolan/vlc/-/issues/28960  State: closed-fixed
- Mechanism: DVD SPU RLE decoding stops by declared width and height instead of by the compressed payload boundary, so crafted dimensions make it consume command bytes as pixels and write beyond the RLE output allocation.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSubtitleDecoder.kt, KiteCodecSubtitleDecoderFactory.create; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/subtitle/SubtitleCue.kt, BitmapRegion
- Verdict: MISSING-FEATURE
- Why: KitePlayer defines a safe bitmap cue boundary but has no DVD SPU decoder that can produce one. The unsafe RLE loop is absent, while DVD bitmap subtitles remain unsupported.
- Severity if real: P0 crash/dataloss

### [VLC-29392] [oss-fuzz 5829271106158592]  Stack-overflow in ClearCSSStyles
- Link: https://code.videolan.org/videolan/vlc/-/issues/29392  State: closed-fixed
- Mechanism: Clearing CSS from a deeply nested WebVTT node tree recursively descends one native stack frame per node until the process stack is exhausted.
- KitePlayer code checked: kiteplayer-subtitles/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/subtitle/WebVttParser.kt, parse and stripVttOnlyTags; kiteplayer-subtitles/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/subtitle/SubRipParser.kt, InlineMarkup.parse
- Verdict: IMMUNE
- Why: WebVttParser skips STYLE blocks and never builds a CSS or DOM tree. Inline markup is scanned by one iterative index loop with an explicit style deque, so nesting depth does not recurse on the call stack.
- Severity if real: P0 crash/dataloss

### [VLC-29321] [oss-fuzz 5489399745019904]  Floating-point-exception in ParseJSS
- Link: https://code.videolan.org/videolan/vlc/-/issues/29321  State: closed-fixed
- Mechanism: A malformed JACOsub timing expression reaches integer arithmetic with an invalid divisor and raises a floating-point exception while probing the subtitle file.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, parseExternalSubtitle; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecMediaBackend.kt, subtitleFileParser
- Verdict: MISSING-FEATURE
- Why: External files are routed only to ASS, WebVTT, or SubRip parsing. JACOsub is not detected or parsed, so this arithmetic path is absent but valid JACOsub files produce no cues.
- Severity if real: P1 broken feature

### [VLC-27145] Incorrect behavior for tts:origin and tts:extent (ttml) in pixmaps
- Link: https://code.videolan.org/videolan/vlc/-/issues/27145  State: open
- Mechanism: TTML percentage origin and extent must be resolved against the current display region on every resize. Treating them as fixed bitmap coordinates makes a cue move outside a larger window.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSubtitleDecoder.kt, KiteCodecSubtitleDecoderFactory.create; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/subtitle/SubtitleCue.kt, CueLayout
- Verdict: MISSING-FEATURE
- Why: CueLayout can carry fractional positions, but the decoder factory does not support TTML and no parser maps tts:origin or tts:extent into that model. TTML cues therefore do not reach layout at all.
- Severity if real: P1 broken feature

### [VLC-29533] [oss-fuzz 6711609568591872] NULL dereference in webvtt_FillStyleFromCssDeclaration()
- Link: https://code.videolan.org/videolan/vlc/-/issues/29533  State: closed-fixed
- Mechanism: A malformed WebVTT CSS declaration leaves a property name null, and the style application path passes it to a case-insensitive string comparison.
- KitePlayer code checked: kiteplayer-subtitles/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/subtitle/WebVttParser.kt, parse; kiteplayer-subtitles/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/subtitle/SubRipParser.kt, InlineMarkup.applyTag
- Verdict: IMMUNE
- Why: WebVttParser deliberately skips complete STYLE blocks. The remaining inline style scanner compares non-null Kotlin strings extracted from bounded substrings and never calls a CSS declaration engine.
- Severity if real: P0 crash/dataloss

### [VLC-29789] VLC : Signed Integer Overflow in DVB Subtitle Region Allocation
- Link: https://code.videolan.org/videolan/vlc/-/issues/29789  State: open
- Mechanism: Multiplying attacker-controlled 16-bit DVB region width and height in signed Int can overflow before allocation, creating a short buffer that a much larger region clear then overwrites.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSubtitleDecoder.kt, KiteCodecSubtitleDecoderFactory.create; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/subtitle/SubtitleCue.kt, RgbaBitmap.init
- Verdict: MISSING-FEATURE
- Why: There is no DVB subtitle decoder. RgbaBitmap would validate width times height in Long before admitting a pixel buffer, but no current container path converts DVB regions into that checked type.
- Severity if real: P0 crash/dataloss

### [VLC-29620] SPU offset wrong on rescaling inside display
- Link: https://code.videolan.org/videolan/vlc/-/issues/29620  State: open
- Mechanism: Scaling a subtitle region for a display-size change without scaling its x and y offsets separates the bitmap from its authored placement.
- KitePlayer code checked: kiteplayer-output/src/jvmMain/kotlin/io/github/yuroyami/kiteplayer/output/DesktopSubtitleRasterizer.kt, rasterize Bitmap branch; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/subtitle/SubtitleCue.kt, BitmapRegion
- Verdict: MISSING-FEATURE
- Why: The rasterizer correctly scales both bitmap-region offsets from authored canvas to viewport, but the shipped decoder factory cannot create SPU Bitmap cues. The placement law exists while DVD SPU ingestion does not.
- Severity if real: P1 broken feature

### [VLC-29617] Invalid text SPU positioning on horizontal shrink
- Link: https://code.videolan.org/videolan/vlc/-/issues/29617  State: open
- Mechanism: Caching a subtitle's pixel placement across a horizontal window shrink leaves the old x coordinate and wrap width attached to a newly sized surface.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, timeAndPublishCues and publishOverlay; kiteplayer-output/src/jvmMain/kotlin/io/github/yuroyami/kiteplayer/output/DesktopSubtitleRasterizer.kt, rasterizeText
- Verdict: IMMUNE
- Why: A renderer output-size change is part of the published cue key and forces a fresh raster. The new viewport width is used to recompute safe width, line wrapping, x placement, and margins rather than reusing cached pixels.
- Severity if real: P2 quality/perf

### [VLC-29230] D_WEBVTT/SUBTITLES don't show second line
- Link: https://code.videolan.org/videolan/vlc/-/issues/29230  State: closed-fixed
- Mechanism: Treating an embedded WebVTT packet as one line, or terminating its payload at the first newline, drops the remainder of a multi-line cue.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSubtitleDecoder.kt, KiteCodecTextSubtitleDecoder.send; kiteplayer-subtitles/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/subtitle/WebVttParser.kt, parseCueBody
- Verdict: IMMUNE
- Why: The decoder copies and decodes the complete packet body, and parseCueBody preserves newline characters inside StyledSpan text. The platform rasterizers receive the whole multi-line string.
- Severity if real: P1 broken feature

### [VLC-29112] Subtitles rendered with libaribcaption disappear early than expected
- Link: https://code.videolan.org/videolan/vlc/-/issues/29112  State: open
- Mechanism: ARIB caption lifetime depends on decoder-defined replacement and clear timing. Applying a generic block duration makes captions disappear before their signalled end.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSubtitleDecoder.kt, KiteCodecSubtitleDecoderFactory.create
- Verdict: MISSING-FEATURE
- Why: No ARIB caption codec is accepted by the factory, so KitePlayer neither applies the wrong lifetime nor renders ARIB captions at all.
- Severity if real: P1 broken feature

### [VLC-28563] Support for .vtt subtitles embedded in .mp4 file
- Link: https://code.videolan.org/videolan/vlc/-/issues/28563  State: open
- Mechanism: MP4 WebVTT is a timed sample entry, so supporting external .vtt files does not help unless the container exposes wvtt samples and the subtitle decoder accepts their packet codec.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSubtitleDecoder.kt, KiteCodecSubtitleDecoderFactory.create and KiteCodecTextSubtitleDecoder.send
- Verdict: IMMUNE
- Why: The FFmpeg-backed stream path explicitly accepts codec webvtt and decodes each complete timed packet through WebVttParser.parseCueBody. It does not depend on the external-file loader for embedded MP4 cues.
- Severity if real: P1 broken feature

### [VLC-28575] SPU regression: palette assertion failed
- Link: https://code.videolan.org/videolan/vlc/-/issues/28575  State: open
- Mechanism: A subtitle or OSD region with more palette entries than the fixed YUV palette can represent reaches conversion and aborts on a hard assertion.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/subtitle/SubtitleCue.kt, RgbaBitmap; kiteplayer-output/src/jvmMain/kotlin/io/github/yuroyami/kiteplayer/output/DesktopSubtitleRasterizer.kt, premultipliedRgba
- Verdict: IMMUNE
- Why: KitePlayer overlays are premultiplied RGBA byte arrays with no indexed palette or YUV palette conversion. The checked bitmap constructor and output path contain no palette-entry ceiling or equivalent assertion.
- Severity if real: P0 crash/dataloss

### [VLC-28574] Regression: SPU assertion while seeking
- Link: https://code.videolan.org/videolan/vlc/-/issues/28574  State: open
- Mechanism: A seek can leave a subtitle region carrying sentinel coordinates, and placing that stale region aborts when x or y still equals INT_MAX.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, clearBuffers and publishOverlay; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/subtitle/SubtitleCue.kt, CueLayout
- Verdict: IMMUNE
- Why: Seek epoch clearing removes queued subtitle packets, every decoded cue cache, and the pending packet, then invalidates the published cue key before repreroll. Text positions are nullable fractions rather than INT_MAX sentinels, so stale sentinel placement has no local representation.
- Severity if real: P0 crash/dataloss

### [VLC-27463] Subtitles won't move based on selected position.
- Link: https://code.videolan.org/videolan/vlc/-/issues/27463  State: wontfix
- Mechanism: A subtitle-position preference is ineffective if it updates only saved state while the active raster keeps using its old bottom anchor.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/KitePlayer.kt, setSubtitlePosition; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, CoreCommand.SetSubtitlePosition and publishOverlay
- Verdict: IMMUNE
- Why: The command changes the actor-owned position, invalidates the published cue key, and rerasterizes active implicit-position cues on the next pass. Every built-in rasterizer receives the new 0.1 to 1.0 viewport anchor.
- Severity if real: P1 broken feature

### [VLC-25003] View all subtitle text and navigate by subtitle
- Link: https://code.videolan.org/videolan/vlc/-/issues/25003  State: open
- Mechanism: Subtitle navigation requires exposing the cue table as searchable text and mapping a selected cue back to a media seek target, separately from the transient overlay API.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/KitePlayer.kt, public subtitle controls; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, OpenSession.subtitleCues and timeAndPublishCues
- Verdict: MISSING-FEATURE
- Why: PlaybackCore owns a cue table internally, but KitePlayer exposes track selection, timing delay, scale, and position only. There is no cue-list, text-search, or seek-by-cue API for a client to build this view.
- Severity if real: P1 broken feature

### [VLC-28659] vlc should detect language on external subtitle from filename for all codec
- Link: https://code.videolan.org/videolan/vlc/-/issues/28659  State: open
- Mechanism: External subtitle auto-selection needs a language tag even when the format carries none, so players commonly infer an ISO language token from names such as movie.en.vtt.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/MediaItem.kt, SubtitleSource; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, parseExternalSubtitle
- Verdict: MISSING-FEATURE
- Why: The synthetic TrackInfo copies SubtitleSource.language exactly and uses only the final path component for its title. No filename token is parsed, so callers must supply the language themselves for every external format.
- Severity if real: P2 quality/perf

### [VLC-27488] subtitles for audio only
- Link: https://code.videolan.org/videolan/vlc/-/issues/27488  State: open
- Mechanism: Tying subtitle composition to decoded video frames makes timed text invisible for audio-only media unless a synthetic canvas or independent overlay surface exists.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, buildSession, timeAndPublishCues, publishOverlay, and DEFAULT_SUBTITLE_CANVAS_WIDTH
- Verdict: IMMUNE
- Why: Every session owns an AttachableRenderer even without a video stream, subtitle timing is actor-driven rather than frame-driven, and publishOverlay falls back to a 1280 by 720 canvas when neither surface nor video reports a size.
- Severity if real: P1 broken feature

### [VLC-28564] Inaccurate handling of .ttml / .tx3g subtitles in .mp4 container
- Link: https://code.videolan.org/videolan/vlc/-/issues/28564  State: open
- Mechanism: Container subtitle support must preserve packet timing across seeks and understand each timed-text sample format; treating TTML and tx3g as interchangeable plain text loses either the cue or its structure.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSubtitleDecoder.kt, KiteCodecSubtitleDecoderFactory.create and tx3gText
- Verdict: MISSING-FEATURE
- Why: KitePlayer correctly strips the tx3g length prefix and retains its packet PTS and duration, but ignores optional tx3g style boxes and has no TTML decoder at all. The combined upstream feature is therefore incomplete.
- Severity if real: P1 broken feature

### [VLC-27796] subtitles dont pause with video
- Link: https://code.videolan.org/videolan/vlc/-/issues/27796  State: wontfix
- Mechanism: Driving cue expiration from wall time instead of the paused media clock lets the subtitle selector advance while the picture is frozen.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, CoreCommand.Pause, currentPosition, and timeAndPublishCues
- Verdict: IMMUNE
- Why: Pause freezes the selected media clock, and subtitle visibility is recomputed only from currentPosition minus subtitle delay. Actor wakeups can continue while paused, but the time passed to CueSelector does not advance.
- Severity if real: P1 broken feature

### [VLC-19411] tx3g subtitles doesn't respect RTL
- Link: https://code.videolan.org/videolan/vlc/-/issues/19411  State: open
- Mechanism: Neutral punctuation in a right-to-left tx3g cue needs a paragraph direction derived from the track language or explicit writing metadata. Plain text alone can choose the wrong bidi base direction.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSubtitleDecoder.kt, tx3gText and KiteCodecTextSubtitleDecoder.send; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/subtitle/SubtitleCue.kt, CueLayout
- Verdict: SUSPECT
- Why: tx3gText extracts only the UTF-8 string, and SubtitleCue carries neither language nor paragraph direction. Platform layout may infer RTL from strong Hebrew characters, but KitePlayer cannot force the correct base direction from the selected track when punctuation is ambiguous.
- Severity if real: P2 quality/perf

### [VLC-27130] [Android] Video stutters when subtitles enabled
- Link: https://code.videolan.org/videolan/vlc/-/issues/27130  State: closed-fixed
- Mechanism: Rendering and uploading a new subtitle image on the same critical path as video presentation can create a frame-time spike at every cue boundary.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, publishOverlay; kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/AndroidSubtitleRasterizer.kt, rasterizeText
- Verdict: SUSPECT
- Why: Raster work runs on a separate serial dispatcher and superseded jobs are cancelled, which protects the actor. Each cue edge still builds a StaticLayout and bitmap and calls renderer.setOverlay, with no measured upload-time budget or proof that an Android renderer cannot contend with frame presentation.
- Severity if real: P2 quality/perf

### [VLC-27950] VLC not showing final subtitle
- Link: https://code.videolan.org/videolan/vlc/-/issues/27950  State: open
- Mechanism: A final SRT cue is lost when a parser requires a following blank line or next cue to commit the current block at end of file.
- KitePlayer code checked: kiteplayer-subtitles/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/subtitle/SubRipParser.kt, parse; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/subtitle/CueSelector.kt, activeAt
- Verdict: IMMUNE
- Why: SubRipParser explicitly accumulates through lines.size and emits the current cue without a trailing separator. The resulting half-open interval is selected normally, including when it is the only remaining cue before media duration.
- Severity if real: P1 broken feature

### [VLC-26509] Subtitle starts at right time, but then doesn't go away
- Link: https://code.videolan.org/videolan/vlc/-/issues/26509  State: closed-fixed
- Mechanism: A subtitle renderer that keeps the last region until a replacement arrives ignores the cue's explicit end time, so a lone cue remains visible indefinitely.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/subtitle/CueSelector.kt, activeAt and nextChangeAfter; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, timeAndPublishCues and publishOverlay
- Verdict: IMMUNE
- Why: Cue windows are half-open, nextChangeAfter wakes at the cue end, and an empty active set immediately publishes an empty overlay. Removal does not depend on a following subtitle.
- Severity if real: P1 broken feature

### [VLC-25615] Performance of complex ASS/SSA subtitles has regressed in VLC 4.0
- Link: https://code.videolan.org/videolan/vlc/-/issues/25615  State: closed-fixed
- Mechanism: Complex ASS events with many styled spans, outlines, motion, or drawings can turn a cue boundary into a multi-second parse and raster workload that stalls video.
- KitePlayer code checked: kiteplayer-subtitles/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/subtitle/AssParser.kt, parseOverrideText; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, publishOverlay
- Verdict: SUSPECT
- Why: KitePlayer strips drawings and unsupported animation, and raster work is off the actor, but there is no event-size, span-count, or raster-time budget. A large supported subset can still monopolize the serial raster lane and deliver the cue late.
- Severity if real: P2 quality/perf

### [VLC-27743] Subrip positioning isn't supported
- Link: https://code.videolan.org/videolan/vlc/-/issues/27743  State: open
- Mechanism: Extended SubRip timing lines can carry X1, X2, Y1, and Y2 coordinates after the end timestamp. Ignoring that suffix renders the text at the default bottom position.
- KitePlayer code checked: kiteplayer-subtitles/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/subtitle/SubRipParser.kt, parseTiming; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/subtitle/SubtitleCue.kt, CueLayout
- Verdict: MISSING-FEATURE
- Why: parseTiming finds the timestamps but discards every trailing token and constructs SubtitleCue.Text with the default layout. The cue model can express positions, but the SubRip parser never maps these coordinates into it.
- Severity if real: P2 quality/perf

### [VLC-22397] Premultiplied RGBA support
- Link: https://code.videolan.org/videolan/vlc/-/issues/22397  State: open
- Mechanism: Video outputs that composite premultiplied alpha need subtitle RGB channels already multiplied by alpha. Feeding straight RGBA produces dark or bright fringes around translucent glyphs.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/subtitle/SubtitleCue.kt, RgbaBitmap; kiteplayer-output/src/jvmMain/kotlin/io/github/yuroyami/kiteplayer/output/DesktopSubtitleRasterizer.kt, premultipliedRgba
- Verdict: IMMUNE
- Why: RgbaBitmap's public contract requires premultiplied RGBA, validates the complete byte buffer, and the desktop, Android, and Apple producers emit that representation directly for raw-copy consumers.
- Severity if real: P2 quality/perf

### [VLC-26965] Subtitle lines with same time code are displayed in the wrong order
- Link: https://code.videolan.org/videolan/vlc/-/issues/26965  State: open
- Mechanism: When two cues have identical timing, stacking each successive cue above the previous one reverses their authored top-to-bottom reading order.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/subtitle/CueSelector.kt, activeAt; kiteplayer-output/src/jvmMain/kotlin/io/github/yuroyami/kiteplayer/output/DesktopSubtitleRasterizer.kt, rasterize
- Verdict: SUSPECT
- Why: Stable cue ordering preserves the file order, but the rasterizer places the first implicit cue at the bottom and increments stackedBottom before placing the second above it. The visible top-to-bottom order is therefore second then first for equal-time lines.
- Severity if real: P2 quality/perf

### [VLC-26865] 3.0.17.4 crashes when playing styled ASS
- Link: https://code.videolan.org/videolan/vlc/-/issues/26865  State: closed-fixed
- Mechanism: A styled ASS event can reach native subtitle parsing or rendering state that was corrupted by a regression, crashing only while the subtitle track is enabled.
- KitePlayer code checked: kiteplayer-subtitles/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/subtitle/AssParser.kt, AssTrackParser.parseEvent and parseOverrideText; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSubtitleDecoder.kt, KiteCodecAssSubtitleDecoder.send
- Verdict: IMMUNE
- Why: The playback path parses ASS into Kotlin strings, data classes, and bounded substring searches, and unknown style tags are ignored. It does not invoke VLC's native ASS integration or share that corrupted state.
- Severity if real: P0 crash/dataloss

### [VLC-26467] SPU decoding locks up if no video
- Link: https://code.videolan.org/videolan/vlc/-/issues/26467  State: open
- Mechanism: A bitmap subtitle decoder can wait forever for a video-output owner that was never created because the video codec is unsupported, preventing playlist teardown and advance.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSubtitleDecoder.kt, KiteCodecSubtitleDecoderFactory.create; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, buildSession and releaseSession
- Verdict: MISSING-FEATURE
- Why: KitePlayer has no SPU decoder, so it cannot enter this wait but cannot show the subtitle either. Supported text cues publish through an independent AttachableRenderer and do not require a decoded video stream.
- Severity if real: P1 broken feature

### [VLC-17315] Ungraceful display of overlapping subtitles
- Link: https://code.videolan.org/videolan/vlc/-/issues/17315  State: wontfix
- Mechanism: Treating a tiny overlap between consecutive cues as two independent simultaneous regions makes the second line jump above the first instead of replacing or merging it.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/subtitle/CueSelector.kt, activeAt; kiteplayer-output/src/jvmMain/kotlin/io/github/yuroyami/kiteplayer/output/DesktopSubtitleRasterizer.kt, rasterize
- Verdict: SUSPECT
- Why: Every half-open interval containing the instant is active, with no tolerance, identity merge, or overlap repair. Both cues are then stacked, so even a two-millisecond authoring overlap visibly changes vertical placement.
- Severity if real: P3 polish

### [VLC-26128] Subtitle Text Flickers Continuously with Advanced Substation Alpha (.ASS) Subtitles
- Link: https://code.videolan.org/videolan/vlc/-/issues/26128  State: open
- Mechanism: ASS karaoke changes style state within a cue over time. Rebuilding or replacing regions inconsistently at each syllable boundary can flicker the entire line.
- KitePlayer code checked: kiteplayer-subtitles/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/subtitle/AssParser.kt, parseOverrideText; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, timeAndPublishCues
- Verdict: MISSING-FEATURE
- Why: The Kotlin ASS tier deliberately ignores karaoke tags and keeps only static text, so it cannot produce this flicker but also cannot animate karaoke. Cue publication has only start and end edges, not syllable timing.
- Severity if real: P1 broken feature

### [VLC-24961] 4.0 regression: subtitles not visible when paused
- Link: https://code.videolan.org/videolan/vlc/-/issues/24961  State: wontfix
- Mechanism: A pause path that clears or stops the subtitle output without immediately recomposing the cue at the frozen media time leaves the paused picture without its text.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, CoreCommand.Pause, timeAndPublishCues, and publishOverlay
- Verdict: IMMUNE
- Why: Pause freezes the clock and scheduler but does not clear subtitle cues or the renderer overlay. The active cue remains attached to the held picture, and later cue recomputation uses the same frozen position.
- Severity if real: P1 broken feature

### [VLC-21945] WebVTT cues that begin at 0 do not display
- Link: https://code.videolan.org/videolan/vlc/-/issues/21945  State: wontfix
- Mechanism: Using timestamp zero as a no-time sentinel discards a valid WebVTT cue that starts at the beginning of media.
- KitePlayer code checked: kiteplayer-subtitles/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/subtitle/WebVttParser.kt, timestampToMicros and parse; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/subtitle/CueSelector.kt, activeAt
- Verdict: IMMUNE
- Why: WebVTT zero parses to Long 0 without a sentinel check, and activeAt includes any cue whose start is less than or equal to the current time. A cue beginning at zero is active on the first timeline instant.
- Severity if real: P1 broken feature

### [VLC-19377] add support for WebVTT cue settings
- Link: https://code.videolan.org/videolan/vlc/-/issues/19377  State: open
- Mechanism: WebVTT line, position, size, vertical, region, and alignment settings control placement. Reading only timing and text collapses authored captions onto the default bottom stack.
- KitePlayer code checked: kiteplayer-subtitles/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/subtitle/WebVttParser.kt, parseTiming; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/subtitle/SubtitleCue.kt, CueLayout
- Verdict: MISSING-FEATURE
- Why: parseTiming maps align only. It records neither line nor position, size, vertical flow, or regions even though CueLayout can carry a subset of those concepts.
- Severity if real: P1 broken feature

### [VLC-24227] .srt that not in time order cause sub dont show
- Link: https://code.videolan.org/videolan/vlc/-/issues/24227  State: open
- Mechanism: A streaming subtitle selector that assumes monotonically increasing file order can pass a later cue and then never reconsider an earlier timestamp that appears afterward.
- KitePlayer code checked: kiteplayer-subtitles/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/subtitle/SubRipParser.kt, parse; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/subtitle/CueSelector.kt, activeAt
- Verdict: IMMUNE
- Why: The complete external cue list is stably sorted by startMicros before selection. Container insertions also take a stable merge cold path when packets arrive out of timestamp order.
- Severity if real: P1 broken feature

### [VLC-25383] Captions stacked in reverse
- Link: https://code.videolan.org/videolan/vlc/-/issues/25383  State: open
- Mechanism: Bottom-anchored placement that adds each later caption above the prior one reverses source order when several captions share a timestamp.
- KitePlayer code checked: kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/AndroidSubtitleRasterizer.kt, rasterize; kiteplayer-output/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/output/AppleSubtitleRasterizer.kt, rasterize
- Verdict: SUSPECT
- Why: Android and Apple mirror the desktop bottom-stack rule: the first active caption uses stackedBottom zero and every later one is placed higher. There is no option to reverse the draw list for top-to-bottom reading order.
- Severity if real: P2 quality/perf

### [VLC-21280] Some WebVTT files don't show because they are treated as ts stream
- Link: https://code.videolan.org/videolan/vlc/-/issues/21280  State: closed-fixed
- Mechanism: Generic demux probing can mistake arbitrary WebVTT bytes for MPEG-TS sync patterns and commit to the wrong demuxer before the subtitle extension is considered.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, parseExternalSubtitle; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecMediaBackend.kt, subtitleFileParser
- Verdict: IMMUNE
- Why: External subtitles bypass media demux probing entirely. A .vtt suffix or WEBVTT signature selects WebVttParser directly, so subtitle bytes are never offered to an MPEG-TS demuxer.
- Severity if real: P1 broken feature

### [VLC-17602] 3.0 regression: UTF-16 subtitles no longer work
- Link: https://code.videolan.org/videolan/vlc/-/issues/17602  State: closed-fixed
- Mechanism: Subtitle text must detect a UTF-16 byte order mark before line reading. Decoding the raw bytes as UTF-8 corrupts timing lines and yields no cues.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/ExternalText.kt, readExternalTextOrNull contract; kiteplayer-core/src/nativeMain/kotlin/io/github/yuroyami/kiteplayer/internal/ExternalText.native.kt, readExternalTextOrNull
- Verdict: MISSING-FEATURE
- Why: The external-text contract explicitly accepts UTF-8 with an optional BOM only, and native reads bytes with decodeToString without UTF-16 BOM conversion. UTF-16 SRT, VTT, or ASS files are unsupported.
- Severity if real: P1 broken feature

### [VLC-11908] Excessive allocation of  memory with some SSA subtitle
- Link: https://code.videolan.org/videolan/vlc/-/issues/11908  State: closed-fixed
- Mechanism: Eagerly parsing an untrusted SSA file without input-size or event-count limits can allocate memory proportional to pathological text before playback begins.
- KitePlayer code checked: kiteplayer-core/src/jvmMain/kotlin/io/github/yuroyami/kiteplayer/internal/ExternalText.jvm.kt, readExternalTextOrNull; kiteplayer-subtitles/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/subtitle/AssParser.kt, parse and AssDocument
- Verdict: SUSPECT
- Why: JVM readText loads the complete file, AssDocument splits and stores its lines, and parse constructs the complete cue list before open continues. There is no byte, line, cue, or styled-span ceiling.
- Severity if real: P0 crash/dataloss

### [VLC-2604] VLC 1.0.0 doesn't play PGS subtitles from M2TS Blu-ray files
- Link: https://code.videolan.org/videolan/vlc/-/issues/2604  State: closed-fixed
- Mechanism: Blu-ray PGS is a bitmap presentation stream inside M2TS, so detecting the track is insufficient unless a decoder turns palette and object segments into positioned bitmap regions.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSubtitleDecoder.kt, KiteCodecSubtitleDecoderFactory.create; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/subtitle/SubtitleCue.kt, SubtitleCue.Bitmap
- Verdict: MISSING-FEATURE
- Why: The cue model can carry bitmap regions, but the only shipped subtitle factory accepts text codecs. PGS streams therefore have no decoder and cannot produce those regions.
- Severity if real: P1 broken feature

### [VLC-7082] Copy subtitle text
- Link: https://code.videolan.org/videolan/vlc/-/issues/7082  State: open
- Mechanism: Copying visible subtitles requires exposing the active cue's plain text to the client or offering a dedicated clipboard action instead of publishing only raster images.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/subtitle/SubtitleCue.kt, SubtitleCue.Text.plainText; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/KitePlayer.kt, public subtitle controls
- Verdict: MISSING-FEATURE
- Why: The internal cue type can derive plainText, but no public state or event exposes active cues and KitePlayer has no copy action. A host application cannot obtain the currently rendered text through the player API.
- Severity if real: P3 polish

### [VLC-29613] SPU rendering quality regression
- Link: https://code.videolan.org/videolan/vlc/-/issues/29613  State: open
- Mechanism: Choosing only the smaller source-sized composition surface prevents high-quality subtitle layout for a larger display, while choosing display size blindly can also mishandle authored bitmap regions.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, publishOverlay; kiteplayer-output/src/jvmMain/kotlin/io/github/yuroyami/kiteplayer/output/DesktopSubtitleRasterizer.kt, rasterize
- Verdict: MISSING-FEATURE
- Why: Text is freshly rasterized at renderer output size, and BitmapRegion placement preserves its authored canvas. However, no SPU decoder exists to supply the bitmap content at all, so the issue's subtitle class remains unavailable.
- Severity if real: P2 quality/perf

### [VLC-18113] Teletext subtitles do not disappear in time
- Link: https://code.videolan.org/videolan/vlc/-/issues/18113  State: open
- Mechanism: Teletext captions often signal replacement or erasure rather than a simple fixed duration. Keeping the first page until another generic timeout leaves old text on screen.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSubtitleDecoder.kt, KiteCodecSubtitleDecoderFactory.create
- Verdict: MISSING-FEATURE
- Why: The factory has no teletext decoder or page-state model. KitePlayer cannot retain a teletext cue too long because it cannot render teletext captions at all.
- Severity if real: P1 broken feature

### [VLC-14847] VobSub subtitles not rendered correctly when subtitle dimensions differ from video dimensions
- Link: https://code.videolan.org/videolan/vlc/-/issues/14847  State: open
- Mechanism: DVD bitmap coordinates refer to the original subtitle canvas. Scaling them directly to a cropped video's dimensions changes aspect and squashes the authored glyphs unless crop and original canvas metadata are reconciled.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/subtitle/SubtitleCue.kt, BitmapRegion; kiteplayer-output/src/jvmMain/kotlin/io/github/yuroyami/kiteplayer/output/DesktopSubtitleRasterizer.kt, Bitmap branch
- Verdict: MISSING-FEATURE
- Why: BitmapRegion preserves an authored canvas and scales offsets, but there is no VobSub decoder or IDX parser to populate the correct canvas and crop metadata. Embedded or external VobSub is unsupported.
- Severity if real: P1 broken feature

### [VLC-2040] SSA subtitles wrong rotation
- Link: https://code.videolan.org/videolan/vlc/-/issues/2040  State: closed-fixed
- Mechanism: SSA rotation and vertical writing need glyph-level transforms and vertical metrics. Rotating an already laid-out horizontal line gives incorrect placement and character orientation.
- KitePlayer code checked: kiteplayer-subtitles/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/subtitle/AssParser.kt, parseOverrideText; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/subtitle/SubtitleCue.kt, CueLayout
- Verdict: MISSING-FEATURE
- Why: The documented Kotlin ASS tier ignores rotation and has no vertical-writing or transform field in CueLayout. It keeps the text readable but cannot reproduce authored rotated SSA layout.
- Severity if real: P2 quality/perf

### [VLC-17403] codec/substtml: infinite loop on missing <styling>
- Link: https://code.videolan.org/videolan/vlc/-/issues/17403  State: closed-fixed
- Mechanism: A TTML header walk that advances only after finding a styling element never changes its cursor when the optional element is absent, spinning forever during open.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSubtitleDecoder.kt, KiteCodecSubtitleDecoderFactory.create; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecMediaBackend.kt, subtitleFileParser
- Verdict: MISSING-FEATURE
- Why: No TTML parser is shipped for either container or external subtitles. The infinite loop is absent because the feature is absent, and a valid TTML document also cannot render.
- Severity if real: P0 crash/dataloss

### [VLC-2007] VLC 0.9.2 crashes when using a SRT subtitles file
- Link: https://code.videolan.org/videolan/vlc/-/issues/2007  State: closed-fixed
- Mechanism: Activating the first SRT cue enters a faulty native text subtitle rendering path and crashes even with ordinary default settings.
- KitePlayer code checked: kiteplayer-subtitles/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/subtitle/SubRipParser.kt, parse and InlineMarkup.parse; kiteplayer-output/src/jvmMain/kotlin/io/github/yuroyami/kiteplayer/output/DesktopSubtitleRasterizer.kt, rasterizeText
- Verdict: IMMUNE
- Why: SRT parsing and desktop layout use managed Kotlin and AWT data rather than VLC's native subtitle objects. Malformed lines are skipped or kept literal, and activation contains no corresponding raw pointer path.
- Severity if real: P0 crash/dataloss

### [VLC-7210] incorrect memmove length param
- Link: https://code.videolan.org/videolan/vlc/-/issues/7210  State: closed-fixed
- Mechanism: Prepending subtitle text with memmove using the old string length but without guaranteeing a larger destination allocation writes past the end of the C buffer.
- KitePlayer code checked: kiteplayer-subtitles/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/subtitle/SubRipParser.kt, parse and InlineMarkup.parse
- Verdict: IMMUNE
- Why: Subtitle text assembly uses StringBuilder, immutable String substrings, and managed collections. There is no manual overlapping byte move or destination-capacity arithmetic in the parser.
- Severity if real: P0 crash/dataloss

### [VLC-3067] Some SSA in MKV files crash VLC
- Link: https://code.videolan.org/videolan/vlc/-/issues/3067  State: closed-fixed
- Mechanism: A particular embedded SSA event reaches a corrupt or unchecked native subtitle state a few seconds into playback and crashes only when the track is decoded.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSubtitleDecoder.kt, KiteCodecAssSubtitleDecoder.send; kiteplayer-subtitles/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/subtitle/AssParser.kt, AssTrackParser.parseEvent
- Verdict: IMMUNE
- Why: The complete packet is decoded into a Kotlin String, split with a nine-field limit, and parsed into managed cue data. Short or invisible events return null, while unknown override tags are ignored, so VLC's native SSA crash state is not shared.
- Severity if real: P0 crash/dataloss

### [VLC-29775] [Regression] Screenshot broken with hw-dec
- Link: https://code.videolan.org/videolan/vlc/-/issues/29775  State: closed-fixed
- Mechanism: A direct hardware decoder can present an opaque GPU or codec surface but cannot satisfy a screenshot path that expects CPU-readable planes unless it downloads or copies the frame first.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/CapturedFrame.kt, CapturedFrame.of; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/VideoFrame.kt, SoftwareReadableFrame and HwSurfaceKind
- Verdict: MISSING-FEATURE
- Why: captureFrame explicitly rejects a hardware-opaque frame with UnsupportedOperationException. Software and hardware-with-download frames capture, but the direct MediaCodec and other opaque tiers have no readback fallback.
- Severity if real: P1 broken feature

### [VLC-29402] VLC4: Aspect Ratio broken after cropping the video
- Link: https://code.videolan.org/videolan/vlc/-/issues/29402  State: open
- Mechanism: Crop and aspect override must compose in one geometry law. Applying the override against pre-crop dimensions stretches or offsets the already cropped picture.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, KiteCodecVideoDecoder.nextDecodedFrame; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, CoreCommand.SetVideoTransform; kiteplayer-output/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/output/FrameLayout.kt, frameLayout
- Verdict: IMMUNE
- Why: The exact report combines a static crop with a later aspect change, not a resolution transition. KitePlayer's crop filter produces the cropped frame and its new geometry before wrapping it, while SetVideoTransform sends each live aspect override to the renderer and FrameLayout recomputes presentation from that post-crop frame. The unrelated dynamic-geometry premise is not needed for this interaction.
- Severity if real: P1 broken feature

### [VLC-28339] Corrupted sample led to long vout wait
- Link: https://code.videolan.org/videolan/vlc/-/issues/28339  State: open
- Mechanism: Treating one corrupted MPEG-TS PTS jump as a legitimate frame duration parks video presentation until the far-future timestamp instead of rejecting the discontinuity.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, timestampsMayJump; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/FrameDurationEstimator.kt, estimate
- Verdict: IMMUNE
- Why: MPEG-TS, RTP, and RTSP select the discontinuous-timestamp profile, whose estimator rejects any measured frame interval above 10 seconds. The reported 70-second jump therefore falls back to a decoder, declared, or conventional duration rather than becoming a wait.
- Severity if real: P1 broken feature

### [VLC-27272] Vlc cannot show video with changing resolution?
- Link: https://code.videolan.org/videolan/vlc/-/issues/27272  State: open
- Mechanism: A decoder and output that cache the opening width and height cannot reallocate textures or surfaces when a later frame changes resolution, producing black video or repeated output rebuilds.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, KiteCodecVideoFrame.size; kiteplayer-output/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/output/MetalFrameComposer.kt, frame configuration
- Verdict: IMMUNE
- Why: Every decoded frame derives VideoSize from its own FrameInfo, and Metal's reusable plane textures are replaced when frame geometry or format changes. Android MediaCodec also updates outputSize on INFO_OUTPUT_FORMAT_CHANGED instead of keeping only the opening size.
- Severity if real: P1 broken feature

### [VLC-27148] video lockup when seeking backward
- Link: https://code.videolan.org/videolan/vlc/-/issues/27148  State: open
- Mechanism: A backward seek can leave the video-output schedule waiting on the old future deadline or holding hardware frames from the previous timeline, so no frame from the new position is presented.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runSeek and clearBuffers; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/VideoPlayback.kt, flush
- Verdict: IMMUNE
- Why: Seek quiesces the schedule and decoder, advances the generation, empties the frame queue, invalidates the video clock, resets duration history, and marks the next frame as the first schedule anchor. Old hardware frames cannot retain the prior deadline.
- Severity if real: P1 broken feature

### [VLC-27025] vout: VOUT_REDISPLAY_DELAY wait not interruptible by the clock
- Link: https://code.videolan.org/videolan/vlc/-/issues/27025  State: wontfix
- Mechanism: If video sleeps on a provisional deadline while the first audio callback establishes a better master-clock anchor, failing to wake video immediately can make its next frames late and dropped.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/VideoPlayback.kt, tick; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runLoop, wakeIn, and awaitWork
- Verdict: SUSPECT
- Why: Video recalculates against the audio master every actor pass, and the 50 ms wake floor is shorter than VLC's reported 80 ms wait. Audio clock anchoring does not itself signal the actor, so a newly valid anchor can still wait nearly 50 ms, enough to miss a frame at common rates.
- Severity if real: P2 quality/perf

### [VLC-26761] Subtitle stride alignment bug
- Link: https://code.videolan.org/videolan/vlc/-/issues/26761  State: closed-fixed
- Mechanism: Treating a glyph bitmap's padded row stride as its visible width causes bytes at a multi-line boundary to wrap to the start of the row, especially with wide punctuation.
- KitePlayer code checked: kiteplayer-output/src/jvmMain/kotlin/io/github/yuroyami/kiteplayer/output/DesktopSubtitleRasterizer.kt, rasterizeText and premultipliedRgba; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/subtitle/SubtitleCue.kt, RgbaBitmap
- Verdict: IMMUNE
- Why: AWT lays each authored paragraph into a BufferedImage, and premultipliedRgba copies the complete raster into a tightly documented RGBA array. No subtitle consumer is told that glyph width equals an internal font stride.
- Severity if real: P2 quality/perf

### [VLC-26706] Hi10/Hi12 pixel formats have insufficient definition
- Link: https://code.videolan.org/videolan/vlc/-/issues/26706  State: open
- Mechanism: High-bit-depth formats need explicit component packing, endian, and significance rules. An ambiguous 10- or 12-bit name lets decoder and renderer disagree about byte order and channel placement.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/VideoFrame.kt, PlayerPixelFormat; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/Conversions.kt, PixelFormat.toPlayerFormat
- Verdict: MISSING-FEATURE
- Why: The modeled software formats explicitly name little-endian 10-bit layouts, avoiding VLC's ambiguity, but there are no 12-bit software formats. FFmpeg 12-bit output maps to Opaque and needs a matching renderer that KitePlayer does not ship for that software layout.
- Severity if real: P1 broken feature

### [VLC-26647] Snapshot has different colour than in playback
- Link: https://code.videolan.org/videolan/vlc/-/issues/26647  State: open
- Mechanism: Capturing decoded samples before the display's matrix, range conversion, tone map, and subtitle composition produces pixels that differ from what the viewer saw.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/CapturedFrame.kt, CapturedFrame documentation and of; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/VideoPlayback.kt, present
- Verdict: MISSING-FEATURE
- Why: captureFrame explicitly promises decoded, pre-render planes and preserves colorSpace so a caller can convert them correctly. Treating those planes as ready-made SDR RGB violates that contract rather than exposing a player defect. KitePlayer does not offer the separate WYSIWYG capability that would capture display-managed, tone-mapped, rotated, and subtitle-composited output.
- Severity if real: P2 quality/perf

### [VLC-26184] Implement VSYNC and VFR into the video output
- Link: https://code.videolan.org/videolan/vlc/-/issues/26184  State: open
- Mechanism: Smooth presentation needs feedback about actual display refreshes or target intervals so scheduling does not wait once in the player and again in a swap interval.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/VideoRenderer.kt, vsyncIntervalNanos and present; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/VideoPlayback.kt, tick and present
- Verdict: MISSING-FEATURE
- Why: The renderer SPI exposes an advisory vsync interval and accepts targetNanos, but VideoPlayback never reads vsyncIntervalNanos or receives actual-present feedback. Timing remains PTS and monotonic-clock based, with platform renderers free to honor or ignore the target.
- Severity if real: P2 quality/perf

### [VLC-25482] Assertion Error when using PlayPaused or SetTime while Paused
- Link: https://code.videolan.org/videolan/vlc/-/issues/25482  State: closed-fixed
- Mechanism: Reapplying pause or seeking while already paused can send an inconsistent pause transition into video output and violate its internal paused-state assertion.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, CoreCommand.Pause and runSeek; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/VideoPlayback.kt, pauseSchedule and flush
- Verdict: IMMUNE
- Why: Pause is idempotent at actor state, while a paused seek uses the ordinary quiesce, generation flush, landing, and held-frame path. VideoPlayback has no asserted duplicate-pause transition and resets scheduling only through its explicit epoch boundary.
- Severity if real: P0 crash/dataloss

### [VLC-25479] display lock starvation: non-responsive resizing, mouse, keyboard, 360
- Link: https://code.videolan.org/videolan/vlc/-/issues/25479  State: closed-fixed
- Mechanism: Holding a display lock while waiting for decoded pictures starves resize and input events that require the same lock.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/VideoRenderer.kt, threading contract; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, setRenderer
- Verdict: IMMUNE
- Why: Decode, scheduling, and renderer work have separate ownership lanes, and the SPI forbids synchronous callbacks into the player from present. Renderer swaps park the scheduler with a two-second deadline rather than holding an event lock while waiting for a picture.
- Severity if real: P1 broken feature

### [VLC-25457] Frame by Frame not advancing
- Link: https://code.videolan.org/videolan/vlc/-/issues/25457  State: closed-fixed
- Mechanism: Implementing frame step as a time seek can land on the same decoded picture repeatedly in short or variable-rate media.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, stepOneFrame and presentFirstFrame
- Verdict: IMMUNE
- Why: A paused step releases exactly one already decoded frame from the queue, independent of nominal duration or timestamp arithmetic. The wait is bounded to one second and answers typed if no frame can reach the output.
- Severity if real: P1 broken feature

### [VLC-24303] 4.0 regression: hardware decoder leaked on vout error
- Link: https://code.videolan.org/videolan/vlc/-/issues/24303  State: wontfix
- Mechanism: If video-output creation fails after a hardware decoder was acquired, returning along an error branch without closing the decoder leaks its surfaces and native context.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, buildSession rollback ledger, createVideoDecoder, and releaseSession
- Verdict: IMMUNE
- Why: Every acquired decoder is added to the open rollback ledger before later output or assembly work can suspend, and an installed session closes the decoder through releaseSession. Abandoned cross-context acquisition also has its own non-cancellable close.
- Severity if real: P2 quality/perf

### [VLC-23223] Frames greater than 8K treated as impossible
- Link: https://code.videolan.org/videolan/vlc/-/issues/23223  State: closed-fixed
- Mechanism: A hard-coded 8192-pixel validation ceiling rejects otherwise decodable scientific, panoramic, or production frames before asking the actual output capability.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerState.kt, VideoSize; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/VideoFrame.kt, VideoFrame.size
- Verdict: IMMUNE
- Why: VideoSize and the engine frame contract impose positive dimensions but no 8K ceiling. A platform texture or decoder can still refuse its real hardware limit, but core does not declare every larger frame impossible.
- Severity if real: P1 broken feature

### [VLC-22784] spu_prerender: sub date data-race
- Link: https://code.videolan.org/videolan/vlc/-/issues/22784  State: open
- Mechanism: A prerender thread reading mutable subtitle start dates while the display thread retimes the same object creates a data race and invites lock-order deadlock when patched locally.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, timeAndPublishCues and publishOverlay; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/subtitle/SubtitleCue.kt, SubtitleCue
- Verdict: IMMUNE
- Why: Subtitle cue dates are immutable values owned by the actor. The raster coroutine receives an immutable active-list snapshot, and retiming is a different selector position rather than mutation of cue start or end fields.
- Severity if real: P0 crash/dataloss

### [VLC-22273] spu: link clocks to its SPU channel
- Link: https://code.videolan.org/videolan/vlc/-/issues/22273  State: closed-fixed
- Mechanism: Assigning a subtitle clock globally after channel creation can attach the wrong timeline to a channel or race its first regions. The channel and its clock must be registered atomically.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, currentPosition and timeAndPublishCues; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/subtitle/CueSelector.kt, activeAt
- Verdict: IMMUNE
- Why: KitePlayer has no independently mutable SPU-channel clock. The actor chooses one master position for the session and passes its numeric reading directly to the pure selector on every cue decision.
- Severity if real: P1 broken feature

### [VLC-22249] vout thread data race
- Link: https://code.videolan.org/videolan/vlc/-/issues/22249  State: wontfix
- Mechanism: One thread destroying a picture while another tests or releases the same reference races refcount and payload lifetime, allowing use after free.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/VideoPlayback.kt, present; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/VideoRenderer.kt, ownership contract
- Verdict: IMMUNE
- Why: The frame queue is the sole owner until advance, then ownership moves permanently to the renderer even when present returns false. The scheduler does not inspect or release a handed-over frame, so the two-thread double owner in the report is structurally excluded.
- Severity if real: P0 crash/dataloss

### [VLC-21872] Suspend video decoding when the VLC window is minimized or hidden in taskbar
- Link: https://code.videolan.org/videolan/vlc/-/issues/21872  State: open
- Mechanism: Continuing to decode and pace every video frame while no surface is visible wastes CPU, battery, and hardware-decoder bandwidth.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/VideoPlayback.kt, present; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackWorkers.kt, AttachableRenderer.present
- Verdict: MISSING-FEATURE
- Why: Headless presentation deliberately closes each frame and keeps the schedule and decoder running so audio never stops. There is no visibility policy that parks video decode while preserving audio and timeline state.
- Severity if real: P2 quality/perf

### [VLC-19713] The vout is not recreated when switching transfer format
- Link: https://code.videolan.org/videolan/vlc/-/issues/19713  State: wontfix
- Mechanism: Reusing an output solely because pixel format matches carries stale transfer metadata from PQ into HLG or the reverse, producing the wrong tone response.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/VideoFrame.kt, VideoFrame.colorSpace; kiteplayer-output/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/output/MetalFrameComposer.kt, frame configuration
- Verdict: IMMUNE
- Why: Transfer, matrix, primaries, and range ride on every VideoFrame rather than on a reused stream-level output. Renderer configuration keys include the current frame metadata, so equal pixel format does not imply equal color treatment.
- Severity if real: P1 broken feature

### [VLC-16024] ThreadControl error leads to deadlock
- Link: https://code.videolan.org/videolan/vlc/-/issues/16024  State: closed-fixed
- Mechanism: When a video worker exits after a control error, a separate waiter can block forever for that dead worker to empty a control queue.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackWorkers.kt, Worker.markFinished and quiesce; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runLoop failure funnel
- Verdict: IMMUNE
- Why: A worker exit marks itself finished and satisfies quiescence, while quiesce also has an explicit deadline. Worker failure is delivered to the actor's typed teardown or recovery funnel rather than leaving an unowned queue waiter.
- Severity if real: P1 broken feature

### [VLC-11669] Resolution change crashes due to late pictures
- Link: https://code.videolan.org/videolan/vlc/-/issues/11669  State: closed-fixed
- Mechanism: Recreating video output for a resolution change while late decoder frames still reference the old output lets their release callback dereference a destroyed vout.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, KiteCodecVideoFrame.size; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/VideoPlayback.kt, frame ownership
- Verdict: IMMUNE
- Why: Size belongs to each frame and does not require replacing an engine-owned vout object. Once a frame reaches present, only its renderer owns its release, so a later frame's different resolution cannot redirect an old release through a destroyed core output pointer.
- Severity if real: P0 crash/dataloss

### [VLC-12155] Video filters drain
- Link: https://code.videolan.org/videolan/vlc/-/issues/12155  State: open
- Mechanism: Delayed video filters such as deinterlacers retain output frames after decoder drain. Flushing them as discard at EOF loses the tail.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, KiteCodecVideoDecoder.nextDecodedFrame and isDrained
- Verdict: IMMUNE
- Why: Once the decoder is drained, KitePlayer calls filterGraph.flushInput, copies every emitted tail frame into filteredPending, and does not report isDrained until that queue is empty. Seek flush separately discards and rebuilds filter state.
- Severity if real: P1 broken feature

### [VLC-5483] Frame by frame hangs
- Link: https://code.videolan.org/videolan/vlc/-/issues/5483  State: closed-fixed
- Mechanism: A frame-step command can wait forever for a decoder or display event that will never arrive, then leave teardown waiting on the same stuck path.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, stepOneFrame, presentFirstFrame, and STEP_DEADLINE
- Verdict: IMMUNE
- Why: Step waits for one frame-release counter change under a one-second deadline and reports failure if none arrives. It does not leave a persistent step mode or an unbounded waiter for close to inherit.
- Severity if real: P1 broken feature

### [VLC-5169] apply 'rotate' during pause
- Link: https://code.videolan.org/videolan/vlc/-/issues/5169  State: closed-fixed
- Mechanism: Changing a runtime rotation filter while paused must redraw the held frame immediately; waiting for the next decoded frame makes the picture vanish until resume.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/VideoTransform.kt, VideoTransform; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/MediaItem.kt, videoFilter
- Verdict: MISSING-FEATURE
- Why: Container quarter-turn rotation is carried on every frame and remains visible while paused, but KitePlayer exposes no runtime rotation control. Arbitrary rotate filters are fixed at MediaItem open and cannot be enabled on a paused session.
- Severity if real: P2 quality/perf

### [VLC-5050] VLC do not play perfectly the firsts and lasts frames
- Link: https://code.videolan.org/videolan/vlc/-/issues/5050  State: closed-fixed
- Mechanism: Declaring EOF when demux ends rather than after decoder, filter, frame queue, audio pipeline, and device tails finish cuts off the final pictures and samples.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, handleEof; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, KiteCodecVideoDecoder.isDrained
- Verdict: IMMUNE
- Why: Ended requires selected queues empty, both decoders drained, the video frame queue empty, audio handoff and DSP tail complete, and the sink drained or explicitly timed out. Video-filter tail frames also count against decoder drain.
- Severity if real: P1 broken feature

### [VLC-3999] Regression: use after free in video filters chain
- Link: https://code.videolan.org/videolan/vlc/-/issues/3999  State: closed-fixed
- Mechanism: Deleting a video-filter chain before the video thread finishes flushing it lets the worker read freed filter state during stop.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, KiteCodecVideoDecoder.dropFilterState, flush, and close; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, quiesceWorkers
- Verdict: IMMUNE
- Why: The filter graph belongs to the decoder lane. Seek parks that worker before flush closes the graph, and teardown joins workers before decoder close, so no schedule or actor path can delete the chain while receive is using it.
- Severity if real: P0 crash/dataloss

### [VLC-29968] [oss-fuzz 6638233386811392] assert in libavcodec H265 decoder dimensions
- Link: https://code.videolan.org/videolan/vlc/-/issues/29968  State: wontfix
- Mechanism: A malformed HEVC stream with mismatched luma and chroma bit depths reaches video-output negotiation with an invalid visible height and offset. Trusting that geometry trips VLC's assertion that the visible rectangle fits inside the coded frame.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/buildSrc/src/main/kotlin/BuildFFmpegTask.kt, DEFAULT_SOURCE_REF; /Users/macbook/StudioProjects/#Kite/KiteCodec/native/kitecodec-c/src/helpers_codec.c, ffkmp_codecctx_receive_frame; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, KiteCodecVideoFrame.size
- Verdict: IMMUNE
- Why: The receipt aborts inside VLC's decoder_UpdateVideoOutput assertion invoked by VLC's custom lavc_GetFrame allocation callback. KiteCodec has neither callback nor assertion and uses ordinary avcodec_receive_frame. Pinned FFmpeg n8.0 validates HEVC coded size and output-window crop, checks buffer geometry with av_image_check_size2, and validates the returned frame and crop bounds before KiteCodec wraps it.
- Severity if real: P0 crash/dataloss

### [VLC-29914] [oss-fuzz 6084399048491008] stack overflow in libfaad reconstruct_single_channel()
- Link: https://code.videolan.org/videolan/vlc/-/issues/29914  State: open
- Mechanism: A malformed AAC configuration drives libfaad's reconstruct_single_channel path into unbounded stack use while rebuilding spectral coefficients.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, KiteCodecAudioDecoderFactory; kiteplayer-ffmpeg/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/PlatformDecoderSelection.apple.kt, platformAudioDecoder
- Verdict: IMMUNE
- Why: KitePlayer has no libfaad decoder path. AAC is decoded by FFmpeg's native decoder, or by the explicitly named AudioToolbox decoder on Apple, so the faad2 reconstruct_single_channel routine in the upstream stack is never entered.
- Severity if real: P0 crash/dataloss

### [VLC-29724] [oss-fuzz 6222270147395584] integer overflow in webvtt MakeTime()
- Link: https://code.videolan.org/videolan/vlc/-/issues/29724  State: closed-fixed
- Mechanism: VLC parsed an arbitrarily large WebVTT hour field into an Int and multiplied it by 3600, so a hostile cue timestamp overflowed before conversion to the media time base.
- KitePlayer code checked: kiteplayer-subtitles/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/subtitle/WebVttParser.kt, TIMESTAMP and timestampToMicros
- Verdict: IMMUNE
- Why: The parser admits at most three hour digits and converts every component to Long before doing the hour, minute, second, and microsecond arithmetic. The multi-million-hour values from the fuzz receipts do not match its timestamp grammar.
- Severity if real: P0 crash/dataloss

### [VLC-29641] [oss-fuzz 5208132317151232] bogus visible dimensions in HEVC lavc decoder
- Link: https://code.videolan.org/videolan/vlc/-/issues/29641  State: closed-fixed
- Mechanism: A malformed HEVC coded-frame size survives decoder allocation far enough for video-output setup to combine a bogus visible height with its offset and abort when the rectangle exceeds the coded height.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/buildSrc/src/main/kotlin/BuildFFmpegTask.kt, DEFAULT_SOURCE_REF; /Users/macbook/StudioProjects/#Kite/KiteCodec/native/kitecodec-c/src/helpers_codec.c, ffkmp_codecctx_receive_frame; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, KiteCodecVideoFrame.size
- Verdict: IMMUNE
- Why: The public stack fails in VLC's decoder_UpdateVideoOutput visible-rectangle assertion while its custom lavc_GetFrame callback is allocating the HEVC frame. KiteCodec does not install that callback. Pinned FFmpeg n8.0 validates HEVC coded size and output-window offsets, checks allocation geometry, then rejects nonpositive returned dimensions and invalid crop sums before the Kotlin wrapper reads width and height.
- Severity if real: P0 crash/dataloss

### [VLC-29191] VLC 4 freezes and crashes on switching audio on certain videos
- Link: https://code.videolan.org/videolan/vlc/-/issues/29191  State: open
- Mechanism: An audio-track switch installs a path reporting sample frequency zero, while disturbing video references and making every following picture late. The input then cannot close because the broken switch leaves decoder work stuck.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, inPlaceAudioChange; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, KiteCodecAudioDecoder.receive
- Verdict: IMMUNE
- Why: The audio transaction parks only audio decode and feed workers, so it cannot flush or replace the running video decoder. The audio wrapper also adopts a new frame format only when both rate and channel count are positive, retaining its prior valid format when a decoder reports zero.
- Severity if real: P1 broken feature

### [VLC-29092] AV1 video with spatial layers: enhancement layer sometimes not shown
- Link: https://code.videolan.org/videolan/vlc/-/issues/29092  State: open
- Mechanism: An AV1 SVC decoder can output pictures from multiple spatial layers, but presentation must select only the highest active layer. Presenting every output frame makes playback alternate resolutions and can expose an enhancement layer as a transparent picture.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/MediaSource.kt, PlayerStreamInfo; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/VideoFrame.kt, VideoFrame
- Verdict: MISSING-FEATURE
- Why: Neither stream metadata nor a decoded VideoFrame carries an AV1 spatial-layer identifier, and the scheduler presents every frame the decoder emits. KitePlayer therefore has no place to enforce the highest-layer output rule if its decoder exposes more than one layer.
- Severity if real: P1 broken feature

### [VLC-28763] AV1: Colours incorrect on yuv444p10le format
- Link: https://code.videolan.org/videolan/vlc/-/issues/28763  State: wontfix
- Mechanism: Ten-bit 4:4:4 AV1 needs a conversion path that preserves three full-resolution high-depth planes. Treating it as a supported lower-depth or subsampled layout produces wrong colours.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/VideoFrame.kt, PlayerPixelFormat; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/Conversions.kt, PixelFormat.toPlayerFormat and tightlyPackedToRgba
- Verdict: MISSING-FEATURE
- Why: PlayerPixelFormat models 8-bit 4:4:4 and ten-bit 4:2:0 or 4:2:2, but not yuv444p10le. That FFmpeg format becomes Opaque, and the software conversion path explicitly refuses unusual opaque formats instead of rendering them.
- Severity if real: P1 broken feature

### [VLC-27808] Opus failing on 5.1 (channel mapping family 1)
- Link: https://code.videolan.org/videolan/vlc/-/issues/27808  State: closed-fixed
- Mechanism: VLC's Opus-header parser sent mapping family 1, the Vorbis multichannel layout, through its custom matrix-family branch. It then interpreted ordinary header bytes as matrix dimensions and rejected valid 5.1 audio.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, KiteCodecAudioDecoderFactory and KiteCodecAudioDecoder.receive
- Verdict: IMMUNE
- Why: KitePlayer has no copy of VLC's Opus header switch or matrix-size check. FFmpeg parses the Opus packet and exposes the decoded frame's channel count and layout mask, which the wrapper maps into AudioFormat after decode.
- Severity if real: P1 broken feature

### [VLC-27532] Regressions in decoders: fmt_in becomes invalid before the end of the decoders and race against decoder update
- Link: https://code.videolan.org/videolan/vlc/-/issues/27532  State: wontfix
- Mechanism: VLC cleaned a decoder's input format before destroying the threaded decoder. A frame worker still inside output-format update then observed UNKNOWN_ES and aborted while the close path waited for that worker.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, releaseSession; /Users/macbook/StudioProjects/#Kite/KiteCodec/kitecodec-core/src/nativeMain/kotlin/io/github/yuroyami/kitecodec/Playback.native.kt, StreamDecoder send, receive, and close
- Verdict: IMMUNE
- Why: PlayerStreamInfo is immutable and is not cleared during teardown. PlaybackCore cancels and joins every worker before closing its decoder, and KiteCodec additionally serializes send, receive, flush, and close on the decoder lock, so native state cannot be freed under an output callback.
- Severity if real: P0 crash/dataloss

### [VLC-27235] VLC for Windows not displaying ffv1 10/12/16-bit grayscale videos
- Link: https://code.videolan.org/videolan/vlc/-/issues/27235  State: closed-fixed
- Mechanism: FFV1 can decode high-bit-depth grayscale frames, but the player output must accept gray10le, gray12le, and gray16le rather than failing buffer allocation or assuming an 8-bit YUV layout.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/VideoFrame.kt, PlayerPixelFormat; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/Conversions.kt, PixelFormat.toPlayerFormat and tightlyPackedToRgba
- Verdict: MISSING-FEATURE
- Why: KitePlayer models no grayscale pixel format at any bit depth. These FFmpeg frames become Opaque, and because FFV1 software frames have no matching hardware renderer, the built-in converter refuses them.
- Severity if real: P1 broken feature

### [VLC-25387] 4.0 regression: abort on vout failure
- Link: https://code.videolan.org/videolan/vlc/-/issues/25387  State: closed-fixed
- Mechanism: Selecting a nonexistent video-output module left VLC running long enough for a later stream-format update to cross an invalid output state and fail an ES-category assertion.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/OutputBackend.kt, OutputBackend; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackWorkers.kt, AttachableRenderer
- Verdict: N/A
- Why: KitePlayer has no string-based video-output module selector comparable to VLC's invalid `-Vfoobar,none` input. A caller attaches a typed VideoRenderer object, and detaching it makes AttachableRenderer consume frames headlessly rather than mutating a media-stream category.
- Severity if real: P0 crash/dataloss

### [VLC-22248] avcodec data race on close()
- Link: https://code.videolan.org/videolan/vlc/-/issues/22248  State: open
- Mechanism: Closing libavcodec while a frame worker is still allocating or writing decode state lets the teardown thread free memory concurrently with the codec's internal worker.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, releaseSession; /Users/macbook/StudioProjects/#Kite/KiteCodec/kitecodec-core/src/jvmAndAndroidMain/kotlin/io/github/yuroyami/kitecodec/Playback.jvm.kt, StreamDecoder close
- Verdict: IMMUNE
- Why: PlaybackCore joins its decode jobs before decoder close. The JVM decoder also holds one operation lock across every native send, receive, flush, and context free, so even an external concurrent close must wait for the active codec call to return.
- Severity if real: P0 crash/dataloss

### [VLC-22226] Decoder resolution switch no longer resize window
- Link: https://code.videolan.org/videolan/vlc/-/issues/22226  State: open
- Mechanism: A bitstream can change decoded resolution inside one track. Updating texture or surface storage is not enough for a windowed player: the host also needs a new visible-size notification to resize its layout.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerEvent.kt, VideoSizeChanged; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, buildSession
- Verdict: MISSING-FEATURE
- Why: PlaybackCore emits VideoSizeChanged once from the selected stream's declared size during open. Decoded frames carry their current size and renderers can reconfigure internally, but no later frame-size change is published to the host window.
- Severity if real: P2 quality/perf

### [VLC-21909] Add support for HDR10+ metadata
- Link: https://code.videolan.org/videolan/vlc/-/issues/21909  State: open
- Mechanism: HDR10+ supplies dynamic scene or frame metadata in addition to the static transfer, primaries, and matrix. A renderer needs that changing metadata to apply the intended tone mapping instead of one static curve for the whole title.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/VideoFrame.kt, ColorSpaceInfo; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/HdrToneMap.kt, HdrToneMap
- Verdict: MISSING-FEATURE
- Why: ColorSpaceInfo carries only matrix, primaries, transfer, range, and chroma location. HdrToneMap derives one curve from those static fields, with no HDR10+ dynamic-metadata payload on VideoFrame or input to the renderer.
- Severity if real: P2 quality/perf

### [VLC-21670] VLC doesn't display 12-bit AV1 content with dav1d decoder
- Link: https://code.videolan.org/videolan/vlc/-/issues/21670  State: closed-fixed
- Mechanism: AV1 Main and High profiles can produce 12-bit grayscale, 4:2:0, 4:2:2, or 4:4:4 pictures. Decoding can succeed while display stays black if the output vocabulary and converter stop at ten bits.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/VideoFrame.kt, PlayerPixelFormat; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/Conversions.kt, PixelFormat.toPlayerFormat
- Verdict: MISSING-FEATURE
- Why: PlayerPixelFormat has no 12-bit format, so every 12-bit software AV1 frame maps to Opaque. The software renderer rejects Opaque, and no hardware surface accompanies a software dav1d frame to supply another display path.
- Severity if real: P1 broken feature

### [VLC-21390] Flipping HDR metadata vout updates
- Link: https://code.videolan.org/videolan/vlc/-/issues/21390  State: open
- Mechanism: VLC updated video output once before reading a frame's side data and again after it, alternately clearing and restoring HDR metadata. Output negotiation therefore oscillated even though the decoded picture had one final metadata state.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/kitecodec-core/src/nativeMain/kotlin/io/github/yuroyami/kitecodec/Frame.native.kt, Frame.buildInfo and readColorInfo; kiteplayer-output/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/output/MetalFrameComposer.kt, encode
- Verdict: IMMUNE
- Why: KiteCodec snapshots colour fields from the completed AVFrame into one FrameInfo. KitePlayer then carries that immutable ColorSpaceInfo on the frame, and the Metal compositor derives its uniforms once from that frame rather than issuing a pre-side-data format update.
- Severity if real: P2 quality/perf

### [VLC-20740] VLC does not play variable frame rate mp4
- Link: https://code.videolan.org/videolan/vlc/-/issues/20740  State: closed-fixed
- Mechanism: Sparse VFR screen recordings can leave long gaps between pictures and seeks can land where no nearby keyframe exists. A scheduler that assumes the container's nominal rate instead of adjacent presentation timestamps shows black or waits for a later keyframe.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/VideoPlayback.kt, tick; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/FrameDurationEstimator.kt, estimate
- Verdict: IMMUNE
- Why: The schedule measures each displayed interval from adjacent PTS values. It snaps to the declared rate only after repeated agreement and immediately unsnaps when a real VFR interval differs, while a seek begins a fresh generation whose first decoded frame establishes the new schedule.
- Severity if real: P1 broken feature

### [VLC-20303] rawvideo: y4m with odd width have broken chroma
- Link: https://code.videolan.org/videolan/vlc/-/issues/20303  State: closed-fixed
- Mechanism: An odd-width planar frame has chroma rows whose size is rounded for subsampling and whose pitch need not equal the luma width. Deriving chroma offsets from width alone skews or crosses rows.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/VideoFrame.kt, SoftwareReadableFrame; kiteplayer-ffmpeg/src/nativeMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/SoftwareConverter.native.kt, convertPlanarYuv and chromaColumns
- Verdict: IMMUNE
- Why: Every readable plane exposes its real byte stride. The native converter indexes Y, U, and V from their separate decoder strides and rounds the chroma column count for subsampling, so a 175-pixel luma row does not imply a 175-byte chroma row.
- Severity if real: P1 broken feature

### [VLC-20216] VLC deinterlaces HEVC Top First interlaced content as Bottom First
- Link: https://code.videolan.org/videolan/vlc/-/issues/20216  State: open
- Mechanism: Deinterlacing must follow the decoded frame's top-field-first flag. Applying a fixed or reversed field order swaps temporal samples and produces combing or backward motion on top-first HEVC.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/VideoFrame.kt, VideoFrame; /Users/macbook/StudioProjects/#Kite/KiteCodec/kitecodec-core/src/commonMain/kotlin/io/github/yuroyami/kitecodec/StreamInfo.kt, FrameInfo
- Verdict: MISSING-FEATURE
- Why: Neither FrameInfo nor VideoFrame exposes whether a picture is interlaced or which field comes first. KitePlayer therefore cannot select a per-frame field order for a deinterlacer, and an optional filter graph is fixed when the media item opens.
- Severity if real: P1 broken feature

### [VLC-19938] VLC ignores crop information located in H264 elementary stream
- Link: https://code.videolan.org/videolan/vlc/-/issues/19938  State: open
- Mechanism: H.264 SPS crop offsets define a visible rectangle smaller than the coded frame. A player that carries only coded width and height cannot remove the cropped columns or place the visible picture correctly.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/buildSrc/src/main/kotlin/BuildFFmpegTask.kt, DEFAULT_SOURCE_REF; /Users/macbook/StudioProjects/#Kite/KiteCodec/kitecodec-core/src/nativeMain/kotlin/io/github/yuroyami/kitecodec/Frame.native.kt, Frame.buildInfo; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, KiteCodecVideoFrame.size
- Verdict: IMMUNE
- Why: KiteCodec pins FFmpeg n8.0, where apply_cropping defaults to enabled. The H.264 decoder places SPS crop offsets on AVFrame, generic decode validates the sums, and av_frame_apply_cropping adjusts the returned frame dimensions and plane origins before Frame.buildInfo reads them. The exact right-side crop in the reported samples is therefore already reflected in KitePlayer's VideoSize.
- Severity if real: P1 broken feature

### [VLC-19013] 3.0 regression : resume video playback broken after pause & seek with VT
- Link: https://code.videolan.org/videolan/vlc/-/issues/19013  State: closed-fixed
- Mechanism: The public report identifies a VLC 3.0 macOS pause, seek, and resume regression with VideoToolbox, but contains no stack, failing wrapper state, or linked source mechanism beyond the observed frozen video.
- KitePlayer code checked: kiteplayer-ffmpeg/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/PlatformDecoderSelection.apple.kt, platformDecoderSelection; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runSeek and flushDecoders; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, KiteCodecVideoDecoder.flush
- Verdict: N/A
- Why: KitePlayer does not use VLC's VideoToolbox decoder and video-output integration. Its hardware route is FFmpeg's VideoToolbox accelerator behind a separately fenced seek path. Sharing the platform accelerator does not establish that VLC's unidentified wrapper regression exists in this implementation.
- Severity if real: P1 broken feature

### [VLC-18887] 709 full/partial color range videos are not displaying correctly on specific samples.
- Link: https://code.videolan.org/videolan/vlc/-/issues/18887  State: open
- Mechanism: BT.709 full-range and studio-range samples need different luma offsets and scales. Reusing one range across files or treating an explicitly full-range frame as limited makes blacks and whites visibly wrong.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/kitecodec-core/src/nativeMain/kotlin/io/github/yuroyami/kitecodec/Frame.native.kt, readColorInfo; kiteplayer-output/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/output/MetalFrameComposer.kt, encode
- Verdict: IMMUNE
- Why: The decoder snapshots AVFrame range, matrix, primaries, and transfer for every frame. That ColorSpaceInfo travels with the frame into the compositor, so neither a playlist transition nor a new frame has to reuse the previous picture's full-range decision.
- Severity if real: P1 broken feature

### [VLC-18423] Linux: NVIDIA: VDPAU long video freeze after seeking
- Link: https://code.videolan.org/videolan/vlc/-/issues/18423  State: closed-fixed
- Mechanism: After seek, VLC's VDPAU path failed hardware-picture allocation for several seconds, lost H.264 references, and dropped late frames until a new usable keyframe arrived.
- KitePlayer code checked: kiteplayer-ffmpeg/src/linuxMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/PlatformDecoderSelection.linux.kt, platformDecoderSelection
- Verdict: N/A
- Why: The Linux backend deliberately exposes no hardware decoder route and decodes every codec in software. It cannot enter VDPAU allocation or retain a VDPAU picture pool across seek, so this vendor-specific path is outside the current implementation.
- Severity if real: P1 broken feature

### [VLC-10317] A52 channel layout changes introduce audio drops
- Link: https://code.videolan.org/videolan/vlc/-/issues/10317  State: open
- Mechanism: Recreating an audio decoder when an A52 stream changes channel layout can discard queued samples at the transition. The output path instead needs to adopt the new decoded layout on the first buffer that uses it.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, KiteCodecAudioDecoder.receive; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, submitDecoded
- Verdict: IMMUNE
- Why: The decoder remains open and updates AudioBuffer.format from each decoded frame's positive rate, channel count, and mask. AudioPlayback compares that buffer format before processing it and rebuilds only the mixer and resampler, carrying volume and mute state across without dropping the buffer.
- Severity if real: P1 broken feature

### [VLC-9552] VLC appears to decode some RGB24 windows frames with wrong stride
- Link: https://code.videolan.org/videolan/vlc/-/issues/9552  State: closed-fixed
- Mechanism: Windows RGB24 rows are padded to a DWORD boundary. Advancing each row by width times three instead of the supplied pitch makes padding bytes shift the next row diagonally.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/VideoFrame.kt, SoftwareReadableFrame.planeStride; kiteplayer-ffmpeg/src/nativeMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/SoftwareConverter.native.kt, copyPacked
- Verdict: IMMUNE
- Why: Row pitch is mandatory frame metadata, and the native packed converter starts every RGB24 row at row times the decoder-provided stride. It uses width times three only for pixels within that row, so padding is skipped rather than rendered.
- Severity if real: P1 broken feature

### [VLC-8393] AAC audio breaks when channel format changes from stereo to 3F2R/LFE (5.1)
- Link: https://code.videolan.org/videolan/vlc/-/issues/8393  State: open
- Mechanism: A live AAC stream can change from stereo to 5.1 without changing track. Continuing to read the new interleave with the old channel layout sends surrounds to the wrong speakers, while rebuilding too late causes cuts and A/V desynchronization.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, KiteCodecAudioDecoder.receive and KiteCodecAudioBuffer; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, submitDecoded
- Verdict: IMMUNE
- Why: Each decoded frame can update outputFormat before its AudioBuffer is created, and sample extraction uses that same buffer format's channel stride. AudioPlayback rebuilds its channel mixer on that buffer, not one later, while media PTS remains attached to the buffer through the transition.
- Severity if real: P1 broken feature

### [VLC-7306] H.264 with resolution change do not work with -mt
- Link: https://code.videolan.org/videolan/vlc/-/issues/7306  State: closed-fixed
- Mechanism: A threaded H.264 decoder can change frame dimensions while old-format pictures are still being drained. Caching one size for the decoder lifetime misinterprets the new picture or refuses it.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, KiteCodecVideoFrame.size; kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/AndroidGpuImageVideoRenderer.kt, configure
- Verdict: IMMUNE
- Why: Every decoded frame snapshots its own width, height, format, and generation. The Android GPU renderer compares each incoming source size with its configured size and rebuilds its output when it changes, so it does not read a new-resolution frame through the old geometry.
- Severity if real: P1 broken feature

### [VLC-4250] AAC-ADTS supports changing the number of audio channels on the fly
- Link: https://code.videolan.org/videolan/vlc/-/issues/4250  State: open
- Mechanism: AAC ADTS may alternate two and six channels inside one stream. A fixed audio-output format rejects the new count or reads six-channel interleave with a two-channel stride.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, KiteCodecAudioDecoder.receive; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/AudioPipeline.kt, matches and rebuiltFor
- Verdict: IMMUNE
- Why: The decoder publishes a new AudioFormat on the first frame whose count changes, and the feeder keys AudioPipeline on the buffer's current format before reading it. It rebuilds the mixer and resampler for two-to-six and six-to-two transitions instead of reopening the device or retaining the old stride.
- Severity if real: P1 broken feature

### [VLC-29803] [Submitted] Integer overflow to infinite loop in ID3v2.3 frame parser
- Link: https://code.videolan.org/videolan/vlc/-/issues/29803  State: closed-fixed
- Mechanism: VLC added ten bytes to an untrusted ID3 frame size before checking it against the remaining tag. Integer wrap made the parser advance by a non-progressing size and loop forever on a crafted MP3.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecMediaBackend.kt, open; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, readPacket
- Verdict: N/A
- Why: KitePlayer does not compile or call VLC's ID3Tag.h parser. Metadata and elementary MP3 framing are owned by the FFmpeg MediaSource below KiteCodec, so this exact size-addition loop is not in the current codebase.
- Severity if real: P0 crash/dataloss

### [VLC-29791] VLC : Heap Buffer Overflow in HEIF Grid Tile Composition via Unbounded dimg Reference Count
- Link: https://code.videolan.org/videolan/vlc/-/issues/29791  State: closed-fixed
- Mechanism: VLC allocated an HEIF grid for rows times columns tiles but copied once for every unbounded dimg reference. Extra references advanced the destination beyond the RGBA allocation and wrote attacker-controlled tile pixels out of bounds.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecMediaBackend.kt, open; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, readPacket
- Verdict: N/A
- Why: There is no HEIF grid assembler or dimg-reference loop in KitePlayer. Any HEIF acceptance and derived-image composition occurs inside FFmpeg, not VLC's modules/demux/mp4/heif.c routine named by the receipt.
- Severity if real: P0 crash/dataloss

### [VLC-29010] [oss-fuzz 42503720] Integer-overflow in vlc_tick_from_samples
- Link: https://code.videolan.org/videolan/vlc/-/issues/29010  State: closed-fixed
- Mechanism: A hostile FLAC total-sample count was multiplied by one million in signed 64-bit arithmetic before division by the sample rate, overflowing while the demuxer derived a duration.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/native/kitecodec-c/src/helpers_error.c, ffkmp_rescale_q; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, TimestampMapper
- Verdict: IMMUNE
- Why: KiteCodec rescales container and stream times through av_rescale_q, whose intermediate is 128-bit, before KitePlayer sees microseconds. TimestampMapper only shifts already-rescaled timeline points and never repeats the samples-times-one-million expression.
- Severity if real: P0 crash/dataloss

### [VLC-28716] VLC can fail to play very short audio files with around 0:00s duration
- Link: https://code.videolan.org/videolan/vlc/-/issues/28716  State: closed-fixed
- Mechanism: A very short clip can reach demux EOF while decoded buffers, DSP carry, and device samples still represent a large fraction of the whole sound. Ending on nominal zero-second duration or demux completion cuts that tail in half.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, handleEof; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, finishDecoded
- Verdict: IMMUNE
- Why: Duration is not an EOF condition. KitePlayer waits for decoder drain and every decoded buffer in flight, asks the feeder to emit its DSP tail, and then drains the device before publishing Ended, which preserves short clips where the tail is most of the media.
- Severity if real: P1 broken feature

### [VLC-24842] No audio in an MPEG-TS stream with ADTS sent as LATM
- Link: https://code.videolan.org/videolan/vlc/-/issues/24842  State: closed-fixed
- Mechanism: VLC recognized AAC carried as LATM but its mpeg4audio packetizer explicitly rejected LATM subframes, so the TS track existed while no audio packets reached decode.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecMediaBackend.kt, open; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, readPacket
- Verdict: N/A
- Why: KitePlayer has no VLC mpeg4audio packetizer or LATM subframe branch. MPEG-TS packetization is performed by FFmpeg below the PlayerPacket boundary, so this exact unimplemented VLC path cannot be assessed as a KitePlayer feature seam.
- Severity if real: P1 broken feature

### [VLC-23666] block->pts update with pcr discontinuity
- Link: https://code.videolan.org/videolan/vlc/-/issues/23666  State: closed-fixed
- Mechanism: VLC changed a PCR discontinuity's sequence offset after calculating the first following PGS subtitle PTS. That packet retained the old segment offset and was discarded as being on the wrong timeline.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSubtitleDecoder.kt, KiteCodecSubtitleDecoderFactory
- Verdict: N/A
- Why: The current subtitle factory supports SubRip, tx3g, WebVTT, ASS, and SSA text packets only. It has no PGS decoder or bitmap-subtitle timeline, so the affected first PGS packet has no corresponding presentation path yet.
- Severity if real: P1 broken feature

### [VLC-23032] When using A-B looping feature on audio, after looping, the loop point(s) slip(s) a short distance UNLESS the playback marker is fiddled with.
- Link: https://code.videolan.org/videolan/vlc/-/issues/23032  State: open
- Mechanism: Reusing a running audio clock or retained pre-loop samples after the B-to-A jump makes each pass begin later than A, so the loop boundary accumulates extra beats until another manual seek resets it.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, handlePlaybackTime, runSeek, and runAudioFeed
- Verdict: IMMUNE
- Why: Crossing B queues the same precise seek used by a manual position change. That transaction invalidates the audio clock and ring, flushes the decoder generation, discards whole pre-A buffers, and sample-trims the one buffer straddling A before submitting it.
- Severity if real: P1 broken feature

### [VLC-21512] Windows Television Video (.wtv) Files (and derivatives) fail to seek properly once 'Next Frame' Feature is Used
- Link: https://code.videolan.org/videolan/vlc/-/issues/21512  State: open
- Mechanism: Implementing frame step by perturbing demux or seek state can leave the WTV cursor and decoder in a special single-frame mode. Later seeks then land incorrectly and teardown can wait on state that never resets.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, stepOneFrame and runSeek
- Verdict: IMMUNE
- Why: Frame step never touches the source cursor or seek machine. It releases exactly one already-decoded queued frame, while a later seek performs the ordinary park, flush, clear, source-seek, and new-generation sequence.
- Severity if real: P1 broken feature

### [VLC-20730] Fast Seek has no effect for FLV files
- Link: https://code.videolan.org/videolan/vlc/-/issues/20730  State: open
- Mechanism: FLV files commonly lack a time index. A fast seek that still asks the demuxer for a timestamp forces a linear scan, while a byte-offset seek can jump immediately and accept approximate time.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/MediaSource.kt, seekToKeyframe; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, seekToKeyframe
- Verdict: MISSING-FEATURE
- Why: The source contract accepts only a media PTS, and KiteCodec always calls a backward time seek. SeekMode.Keyframe changes decode-forward behavior above the source but cannot request FFmpeg's byte-seek mode for an unindexed FLV.
- Severity if real: P2 quality/perf

### [VLC-18511] MP3 accurate seeking broken
- Link: https://code.videolan.org/videolan/vlc/-/issues/18511  State: closed-fixed
- Mechanism: A regression discarded the MP3 MLLT table, removing the byte-to-time landmarks used for accurate seeks in variable-sized MPEG audio frames.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, seekToKeyframe; /Users/macbook/StudioProjects/#Kite/KiteCodec/vendor/ffmpeg/libavformat/mp3dec.c, mp3_seek; /Users/macbook/StudioProjects/#Kite/KiteCodec/vendor/ffmpeg/libavformat/seek.c, seek_frame_generic
- Verdict: IMMUNE
- Why: The pinned MP3 demuxer defaults `usetoc` to zero, KiteCodec does not enable fast seek, and `mp3_seek` returns minus one to select FFmpeg's generic index path. `seek_frame_generic` scans frames through the target, builds index entries while reading, and repositions to the selected entry, so missing MLLT landmarks affect seek cost rather than remove accurate seeking.
- Severity if real: P1 broken feature

### [VLC-17684] Using seek or "jump to time" in FLAC files works incorrectly
- Link: https://code.videolan.org/videolan/vlc/-/issues/17684  State: closed-fixed
- Mechanism: The FLAC seek lands a deterministic 15 to 30 seconds after the requested point, so accepting the demux cursor's answer presents audio from a later position instead of the target.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runSeek, firstDecodedVideo, and firstAudio
- Verdict: IMMUNE
- Why: KitePlayer judges the first decoded timestamp rather than trusting the cursor. A landing beyond tolerance repeats the seek with progressively earlier aims, then precise mode discards decoded output before the exact requested PTS.
- Severity if real: P1 broken feature

### [VLC-17137] Chapter markers truncated
- Link: https://code.videolan.org/videolan/vlc/-/issues/17137  State: closed-fixed
- Mechanism: MP4 chapter text was both truncated during metadata conversion and exposed as a subtitle track, letting chapter labels appear as bottom-of-screen captions.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, chapters and StreamInfo.toPlayerStream; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerState.kt, Chapter
- Verdict: IMMUNE
- Why: Chapters come from the container's chapter table and retain the full nullable title as a String. TrackKind is derived independently from each real stream's MediaType, so a chapter entry cannot become a selectable subtitle stream.
- Severity if real: P2 quality/perf

### [VLC-16952] hls: incorrect ES after seek
- Link: https://code.videolan.org/videolan/vlc/-/issues/16952  State: closed-fixed
- Mechanism: After an HLS seek, VLC failed to reuse the video elementary stream and dropped audio entirely, leaving the adaptive demuxer's post-seek stream identity disconnected from existing decoders.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/buildSrc/src/main/kotlin/BuildFFmpegTask.kt, DEFAULT_SOURCE_REF; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, byIndex and seekToKeyframe
- Verdict: IMMUNE
- Why: KiteCodec pins FFmpeg n8.0. Its hls_read_seek finds the existing playlist through stable main_streams identities, resets each playlist's I/O and subdemuxer queues in place, and never replaces those main AVStreams. update_streams_from_subdemuxer only appends newly discovered streams, so KitePlayer's retained indices remain attached to the same audio and video identities across the seek.
- Severity if real: P1 broken feature

### [VLC-16950] dash: can't seek to start
- Link: https://code.videolan.org/videolan/vlc/-/issues/16950  State: closed-fixed
- Mechanism: VLC's DASH segment search returned failure when the target preceded the first segment because its upper-bound branch treated `segments.begin()` as no valid predecessor, even for a seek to zero.
- KitePlayer code checked: kiteplayer-network/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/network/dash/DashMediaIo.kt, seekable and seek; kiteplayer-network/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/network/dash/DashManifest.kt, DashManifestParser.segmentPlan
- Verdict: MISSING-FEATURE
- Why: KitePlayer's basic static DASH tier builds a segment plan for playback, but DashMediaIo declares itself forward-only and every seek throws. It therefore cannot hit VLC's exact predecessor-search bug, but it also cannot provide the corrected capability of seeking to zero or to any other presentation time.
- Severity if real: P1 broken feature

### [VLC-14250] WMA audio tracks 500 ms silence gap at start or after seek
- Link: https://code.videolan.org/videolan/vlc/-/issues/14250  State: closed-fixed
- Mechanism: VLC classified the first decoded WMA buffer as late, dropped it, then classified the next as early and inserted about half a second of zero samples, producing a drop-silence splice after every seek.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, flush and submitDecoded; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runSeek
- Verdict: IMMUNE
- Why: Seek invalidates the audio clock and clears the ring before the new generation starts. The first surviving buffer anchors that empty epoch from its own PTS; the player does not compare it with the pre-seek clock or synthesize a compensating silence block.
- Severity if real: P1 broken feature

### [VLC-11162] Memory Corruption in TrackCreateSamplesIndex
- Link: https://code.videolan.org/videolan/vlc/-/issues/11162  State: closed-fixed
- Mechanism: A crafted MP4 declared tens of millions of samples and drove VLC's TrackCreateSamplesIndex bookkeeping beyond the allocation it had built from the sample tables, corrupting memory while opening the track.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecMediaBackend.kt, open; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, streams
- Verdict: N/A
- Why: KitePlayer does not build an MP4 sample index or compile VLC's mp4.c. FFmpeg owns sample-table validation and presents only its completed StreamInfo list through KiteCodec, so the named TrackCreateSamplesIndex routine has no local equivalent.
- Severity if real: P0 crash/dataloss

### [VLC-10394] Seek errors with MP3 files when paused
- Link: https://code.videolan.org/videolan/vlc/-/issues/10394  State: open
- Mechanism: Paused mono MP3 seeks land seconds or minutes beyond the request, implying the paused path uses stale clock or byte-position state that the playing path refreshes.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runSeek and applyPause
- Verdict: IMMUNE
- Why: Playing and paused requests enter the same seek transaction and overshoot ladder. Paused state only keeps the scheduler idle after the landing; it does not change source positioning, decoder flush, target discard, or the timestamp used to publish the result.
- Severity if real: P1 broken feature

### [VLC-10323] WAV demuxer has issue with the latest packets for a file
- Link: https://code.videolan.org/videolan/vlc/-/issues/10323  State: closed-fixed
- Mechanism: VLC's native WAV demuxer mishandled the final packets and emitted audible garbage at the tail, while the same file through the avformat demuxer played correctly.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecMediaBackend.kt, open; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, handleEof
- Verdict: IMMUNE
- Why: KitePlayer always opens WAV through FFmpeg, the implementation the upstream receipt says works, and it drains rather than fabricates packets at EOF. VLC's native WAV tail parser is not selected.
- Severity if real: P1 broken feature

### [VLC-10104] Missing frames at end of an AVI sample
- Link: https://code.videolan.org/videolan/vlc/-/issues/10104  State: open
- Mechanism: VLC's AVI demuxer failed to read a final packet header, disabled the track, and declared EOF while late pictures that should have completed the sample were still missing.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecMediaBackend.kt, open; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, readPacket
- Verdict: N/A
- Why: The track-disable decision in the receipt belongs to VLC's AVI demuxer, which KitePlayer does not use. FFmpeg determines whether those final AVI packets exist before the player-level drain begins, so the exact parser failure has no local branch to inspect.
- Severity if real: P1 broken feature

### [VLC-9906] Seeking in ASF file causes hard lock-up in VLC 2.1.1
- Link: https://code.videolan.org/videolan/vlc/-/issues/9906  State: closed-fixed
- Mechanism: Repeating several ASF seeks enters a demux operation that never returns, drives CPU high, and prevents quit from joining the input thread.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runSeek; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, seekToKeyframe
- Verdict: SUSPECT
- Why: Worker parking and landing waits are bounded, but the call to PacketReader.seek itself has no timeout or cancellation boundary around the native demux operation. A wedged ASF seek could therefore still hold the demux dispatcher and delay teardown.
- Severity if real: P1 broken feature

### [VLC-9334] Opus seeking doesnt work
- Link: https://code.videolan.org/videolan/vlc/-/issues/9334  State: closed-fixed
- Mechanism: Ordinary time seeking worked, but next, previous, and menu chapter seeks failed for an Opus file whose chapter markers were stored as Vorbis-style metadata.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/buildSrc/src/main/kotlin/BuildFFmpegTask.kt, DEFAULT_SOURCE_REF; /Users/macbook/StudioProjects/#Kite/KiteCodec/kitecodec-core/src/nativeMain/kotlin/io/github/yuroyami/kitecodec/MediaSource.native.kt, readChapters; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, chapters
- Verdict: IMMUNE
- Why: Pinned FFmpeg n8.0 sends OpusTags through ff_vorbis_stream_comment, whose Ogg chapter parser converts CHAPTERnnn and CHAPTERnnnNAME pairs into AVChapter entries. KiteCodec readChapters imports every AVChapter and KiteCodecSource maps that table, so KitePlayer does not need to synthesize the tags a second time.
- Severity if real: P1 broken feature

### [VLC-9176] VLC 2.0.8 Sound off when seeking backward avi file
- Link: https://code.videolan.org/videolan/vlc/-/issues/9176  State: closed-fixed
- Mechanism: A backward AVI seek left the MS ADPCM audio path silent while forward seeks still worked, consistent with compressed audio or output state surviving a direction-changing cursor jump.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, flushDecoders and clearBuffers; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, flush
- Verdict: IMMUNE
- Why: Seek direction does not change the reset sequence. Every seek parks audio decode and feed, flushes the compressed decoder, empties packet and decoded-buffer queues, clears the ring and DSP pipeline, and starts a new generation from its first PTS.
- Severity if real: P1 broken feature

### [VLC-7884] New Matroska elements CueRelativePosition and CueDuration cause unseekability
- Link: https://code.videolan.org/videolan/vlc/-/issues/7884  State: closed-fixed
- Mechanism: VLC's Matroska parser aborted the cues table when it met newly standardized CueRelativePosition and CueDuration elements instead of skipping or understanding them, so files lost their seek index.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecMediaBackend.kt, open; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, seekToKeyframe
- Verdict: N/A
- Why: KitePlayer has no EBML element parser and does not link VLC's Matroska module. Cue parsing belongs to the current FFmpeg demuxer, so the old libmatroska unknown-element policy in the receipt is not a local branch.
- Severity if real: P1 broken feature

### [VLC-6082] Audio track switching broken
- Link: https://code.videolan.org/videolan/vlc/-/issues/6082  State: wontfix
- Mechanism: Selecting another Matroska language left the original audio elementary stream feeding the output, so the UI selection changed without the demux and decoder route committing to the new track.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, inPlaceAudioChange; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PacketQueue.kt, dropBefore
- Verdict: IMMUNE
- Why: Every alternate audio stream has a bounded compressed cache. The transaction prepares the target decoder, parks only audio workers, flushes the ring, atomically installs the new lane, drops target packets before the audible switch point, and then publishes the selection.
- Severity if real: P1 broken feature

### [VLC-5586] Change of sample rate causes flv files to become silent
- Link: https://code.videolan.org/videolan/vlc/-/issues/5586  State: open
- Mechanism: An FLV audio stream changes sample rate in place, but a decoder or output pipeline fixed to the opening rate becomes silent from the first changed frame onward.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, KiteCodecAudioDecoder.receive; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, submitDecoded
- Verdict: IMMUNE
- Why: The decoder updates AudioBuffer.format when a frame reports a new positive sample rate. The feeder compares that exact buffer format before processing it and rebuilds its resampler into the unchanged device rate on the first changed buffer.
- Severity if real: P1 broken feature

### [VLC-5366] FLV/h264 dynamic resolution play stop
- Link: https://code.videolan.org/videolan/vlc/-/issues/5366  State: open
- Mechanism: H.264 changes decoded width and height inside one FLV stream, but the video path remains configured for the opening geometry and either stops or places new pixels against the old surface extent.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, KiteCodecVideoFrame.size; kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/AndroidGpuImageVideoRenderer.kt, configure
- Verdict: IMMUNE
- Why: Size is read from every completed decoded frame, not cached from the FLV stream header. The Android GPU renderer compares that per-frame size and rebuilds its surface and output configuration when either dimension changes.
- Severity if real: P1 broken feature

### [VLC-4501] MKV Ordered Chapters
- Link: https://code.videolan.org/videolan/vlc/-/issues/4501  State: closed-fixed
- Mechanism: Matroska ordered editions can define playback as a sequence of chapter segments, including linked content from other segment UIDs. Treating the entries as labels on one linear file makes playback restart or lose audio when an ordered segment boundary is reached.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerState.kt, Chapter; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, chapters
- Verdict: MISSING-FEATURE
- Why: Chapter models only index, start, end, and title, and the backend maps a flat AVChapter table. There is no edition flag, segment UID, ordered playback graph, or mechanism to open linked Matroska segments at a chapter boundary.
- Severity if real: P1 broken feature

### [VLC-3376] Regression: Theora offset and crop not handled
- Link: https://code.videolan.org/videolan/vlc/-/issues/3376  State: closed-fixed
- Mechanism: Theora can store a coded frame larger than the visible picture and specify horizontal and vertical picture offsets. Ignoring that rectangle exposes padding or places the image at the wrong origin.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/kitecodec-core/src/commonMain/kotlin/io/github/yuroyami/kitecodec/StreamInfo.kt, FrameInfo; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/VideoFrame.kt, VideoFrame
- Verdict: MISSING-FEATURE
- Why: FrameInfo and VideoFrame carry only width and height, with no visible rectangle or crop offsets. Unless FFmpeg destructively applies the crop before exposing the AVFrame, the player has no representation from which a renderer could place it.
- Severity if real: P1 broken feature

### [VLC-2633] Division by zero when seeking MPEG-PS
- Link: https://code.videolan.org/videolan/vlc/-/issues/2633  State: closed-fixed
- Mechanism: An early MPEG-PS seek reached VLC's demux Control calculation before it had a usable duration or byte-rate denominator and raised SIGFPE on integer division by zero.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecMediaBackend.kt, open; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, seekToKeyframe
- Verdict: N/A
- Why: KitePlayer does not compile VLC's PS Control function or calculate a PS byte seek. It forwards a microsecond target to FFmpeg's seek API, so the exact zero-denominator expression is absent from the current implementation.
- Severity if real: P0 crash/dataloss

### [VLC-1880] VLC freezes on seeking too fast
- Link: https://code.videolan.org/videolan/vlc/-/issues/1880  State: closed-fixed
- Mechanism: Several consecutive forward seeks can repeatedly reset decode before any new picture reaches presentation, leaving video frozen while audio continues from the latest cursor.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, queueSeek, shouldHold, and handleQueuedSeek
- Verdict: IMMUNE
- Why: New requests coalesce into one pending target, superseded callers complete explicitly, and the bounded hold lets a frame from the previous seek reach presentation before another reset. Every applied request advances a generation shared by audio and video.
- Severity if real: P1 broken feature

### [VLC-1810] Crash when switching to specific audio track
- Link: https://code.videolan.org/videolan/vlc/-/issues/1810  State: closed-fixed
- Mechanism: A DVD navigation audio-stream change crashed while the menu-driven access module was changing physical stream identities and decoder output state.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecMediaBackend.kt, open; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/MediaItem.kt, MediaItem
- Verdict: N/A
- Why: KitePlayer has no DVD navigation, menu event, physical DVD stream, or dvdnav access module. MediaItem opens an ordinary URI through FFmpeg, so this menu-driven stream-change path is outside the current feature set.
- Severity if real: P0 crash/dataloss

### [VLC-1404] ASF demux seeks too far and stops early
- Link: https://code.videolan.org/videolan/vlc/-/issues/1404  State: closed-fixed
- Mechanism: VLC's ASF demuxer calculated a file offset beyond signed 64-bit range, broke the input stream with that seek, and then declared playback finished before the real end.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecMediaBackend.kt, open; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, readPacket and seekToKeyframe
- Verdict: N/A
- Why: KitePlayer contains no ASF offset calculation and does not use VLC's mmap or ASF demux modules. File-offset arithmetic is confined to FFmpeg below PacketReader, so the cited greater-than-Long seek expression is not locally present.
- Severity if real: P1 broken feature

### [VLC-29435] Playback with --start-paused starts late after resume
- Link: https://code.videolan.org/videolan/vlc/-/issues/29435  State: wontfix
- Mechanism: A start-paused path that exposes the paused state before input and output preroll are ready makes the first later play command pay hidden startup work, appearing stuck before playback really begins.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runOpen and handlePlaybackRestart; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/KitePlayer.kt, open
- Verdict: IMMUNE
- Why: Every KitePlayer open follows the same paused startup path. runOpen starts workers, waits for the initial fill, presents the first frame, and only then publishes Paused and completes open; play therefore releases an already primed pipeline rather than a separate uninitialised start-paused branch.
- Severity if real: P1 broken feature

### [VLC-28358] input_item_UpdateTracksInfo() can replace an es format with the format of a different es
- Link: https://code.videolan.org/videolan/vlc/-/issues/28358  State: wontfix
- Mechanism: Matching elementary streams only by a non-unique numeric ES id can update one track with another track's format. A stable identity that is unique within the live input must key both the public track table and selection.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, selectableStreams and byIndex; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/Tracks.kt, TrackId
- Verdict: IMMUNE
- Why: KiteCodecSource constructs one immutable canonical table from FFmpeg's format-context stream indices and keys its selection map by those same indices. TrackId is that index for the lifetime of the open, and there is no later metadata update path that searches by VLC-style ES id or replaces another row's format.
- Severity if real: P1 broken feature

### [VLC-27901] Skipping time backwards too far jumps to the end of the song in VLC 4.0
- Link: https://code.videolan.org/videolan/vlc/-/issues/27901  State: wontfix
- Mechanism: Repeated negative relative seeks must saturate at timeline zero. Treating an underflow or negative target as a wrapped position turns another backward press at the beginning into a jump to the media end.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/SeekRequest.kt, resolve and merge; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, seekByLater
- Verdict: IMMUNE
- Why: Relative requests merge by adding their offsets, then resolve explicitly replaces every negative result with Pts.Zero before applying the optional upper duration clamp. No modulo or unsigned conversion can wrap a backward request to the end.
- Severity if real: P1 broken feature

### [VLC-22942] Audio track with a negative delay cuts off significantly larger portion at the beginning
- Link: https://code.videolan.org/videolan/vlc/-/issues/22942  State: open
- Mechanism: The public report observes that VLC discards about 280 ms for tracks tagged with delays as small as minus 1 ms, but supplies no source path or root cause showing where that fixed-size cut is introduced.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, TimestampMapper and KiteCodecAudioDecoder.receive; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runAudioFeed
- Verdict: N/A
- Why: KitePlayer contains no VLC initial-delay or preroll-discard routine and applies no fixed 280 ms cut. It consumes FFmpeg's demuxed timestamps on one container-relative origin. The shared container shape and absence of a local fixture do not establish an equivalent bug without a transferable upstream mechanism.
- Severity if real: P1 broken feature

### [VLC-22434] VLC3: Autodetection of external audio tracks does not work
- Link: https://code.videolan.org/videolan/vlc/-/issues/22434  State: open
- Mechanism: External audio discovery needs a naming policy and a second media source that is opened, timestamped, and exposed as selectable audio alongside the primary container. Subtitle sidecar discovery alone does not supply that path.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/MediaItem.kt, MediaItem; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, parseExternalSubtitles and buildSession
- Verdict: MISSING-FEATURE
- Why: MediaItem accepts externalSubtitles but has no external-audio collection or slave input. buildSession exposes audio streams only from the primary PlayerMediaSource, so a same-basename AC3, M4A, AAC, or DTS file cannot be discovered or selected.
- Severity if real: P1 broken feature

### [VLC-21868] Play videos audio-only (disable video track for all)
- Link: https://code.videolan.org/videolan/vlc/-/issues/21868  State: wontfix
- Mechanism: Disabling video for audio-only listening must be a persistent selection policy, not only a null selection on the current input, or the next media open automatically chooses video again.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/Tracks.kt, Tracks; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerConfig.kt, PlayerConfig; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, buildSession and selectTrack handling
- Verdict: MISSING-FEATURE
- Why: selectTrack can set the current video selection to null, but PlayerConfig has no persistent video-disabled policy and every later automatic buildSession picks the first non-cover video stream again.
- Severity if real: P2 quality/perf

### [VLC-21440] Video seek causes video track to lag when the "Hurry up" setting is not enabled in VLC 3.X
- Link: https://code.videolan.org/videolan/vlc/-/issues/21440  State: open
- Mechanism: A post-seek decode path that depends on an optional hurry-up mode can leave ordinary full-quality decoding perpetually late, producing a repeated pause and resume cadence until the video track is rebuilt.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlaybackProfile.kt, decoderOptions; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runSeek; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, newVideoDecoder
- Verdict: IMMUNE
- Why: The normal backend decoder has no VLC hurry-up toggle and receives no frame-skip options. runSeek flushes, clears, reanchors, and decodes the new generation normally; only the explicitly chosen Scrubbing profile asks FFmpeg to skip non-keyframes, so full-quality seek recovery does not depend on a hidden hurry-up flag.
- Severity if real: P2 quality/perf

### [VLC-21330] VLC hangs on subtitles synchronisation with large delays
- Link: https://code.videolan.org/videolan/vlc/-/issues/21330  State: open
- Mechanism: Applying a large subtitle delay by walking or repeatedly retiming all queued cues can make work proportional to the offset and stall the control loop. Timing should shift the lookup point without rewriting the cue table.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, SetSubtitleDelay and timeAndPublishCues; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/subtitle/CueSelector.kt, activeAt and nextChangeAfter
- Verdict: IMMUNE
- Why: SetSubtitleDelay stores one Duration and invalidates the published cue key. Each timing pass subtracts that value from the current position and performs CueSelector lookups over the unchanged sorted table, so a minus 20 second value does not create 20 seconds of work or a retiming loop.
- Severity if real: P1 broken feature

### [VLC-20879] VLC crashes when resume after using frame by frame until eof
- Link: https://code.videolan.org/videolan/vlc/-/issues/20879  State: open
- Mechanism: Frame stepping can consume the final decoded picture while normal playback still assumes another frame or live decoder state exists. Resuming at that boundary must converge on EOF rather than dereference exhausted state.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, stepOneFrame, handleEof, and handlePlaybackRestart
- Verdict: IMMUNE
- Why: stepOneFrame asks the same bounded presentation gate for one frame and returns an error when none arrives. While paused, handleEof deliberately retains the last frame and does not publish Ended; a later play lets the six EOF conditions finish and enters Ended without reading a nonexistent next frame.
- Severity if real: P0 crash/dataloss

### [VLC-20833] Problem with --stop-time in vlc-3.0.2
- Link: https://code.videolan.org/videolan/vlc/-/issues/20833  State: closed-fixed
- Mechanism: A transcoding run with a stop-time must compare output progress with the requested cutoff and terminate the conversion there. Treating the option only as a playback control lets the encoder consume the whole file.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/KitePlayer.kt, public playback API; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/MediaItem.kt, MediaItem
- Verdict: N/A
- Why: The upstream report is specifically VLC's conversion path. KitePlayer is a playback library and exposes neither a transcoder nor a stop-time conversion option, so this encoder termination mechanism is outside the implementation under review.
- Severity if real: P1 broken feature

### [VLC-20167] Starting in 3.0.0 can't play multiple videos in sync using --input-slave
- Link: https://code.videolan.org/videolan/vlc/-/issues/20167  State: open
- Mechanism: Synchronous comparison of several video inputs needs multiple active demux and decode lanes tied to one master clock, with an independently rendered output for each stream.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, OpenSession and buildSession; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/Tracks.kt, Tracks
- Verdict: MISSING-FEATURE
- Why: OpenSession owns one PlayerMediaSource, one selected video lane, one VideoPlayback, and one renderer delegate. Other video tracks are selectable alternatives, not simultaneously decoded slave inputs with separate outputs.
- Severity if real: P1 broken feature

### [VLC-19984] Play multiple audio tracks at the same time
- Link: https://code.videolan.org/videolan/vlc/-/issues/19984  State: wontfix
- Mechanism: Simultaneous audio tracks require each selected stream to remain decoded and a mixer to align and sum their PCM onto one device timeline. A selector that swaps one lane cannot provide the mix.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, AudioLane and inPlaceAudioChange; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/Tracks.kt, selectedAudio
- Verdict: MISSING-FEATURE
- Why: Tracks holds one selectedAudio id and OpenSession installs one AudioLane. Alternate compressed queues exist only to make a later single-lane switch fast; there is no set of active audio decoders or PCM mixer for concurrent tracks.
- Severity if real: P1 broken feature

### [VLC-18542] Add fast/accurate seek global option
- Link: https://code.videolan.org/videolan/vlc/-/issues/18542  State: open
- Mechanism: A player needs to distinguish a fast random-access-point landing from decode-forward exact landing, because the former gives immediate visual feedback while the latter pays extra decode cost for temporal accuracy.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/MediaItem.kt, SeekMode; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/KitePlayer.kt, seek and seekLater; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runSeek
- Verdict: IMMUNE
- Why: KitePlayer exposes Keyframe, Precise, and KeyframeThenRefine on each seek instead of one global preference. runSeek implements the random-access landing and exact discard phases separately, including the two-phase immediate-picture refinement mode.
- Severity if real: P2 quality/perf

### [VLC-17845] Cannot seek when audio is shifted
- Link: https://code.videolan.org/videolan/vlc/-/issues/17845  State: open
- Mechanism: A seek that rebuilds clocks but loses the user's audio delay makes the shifted stream silent or wrongly scheduled until the delay control is reset. The delay belongs to player state and must survive every timeline generation.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, SetAudioDelay, runSeek, and masterPosition; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerState.kt, audioDelay
- Verdict: IMMUNE
- Why: audioDelay is actor-owned state outside OpenSession. runSeek flushes and reanchors the session but never clears that value, and the video scheduler reads the same delay bias from masterPosition after the new generation lands.
- Severity if real: P1 broken feature

### [VLC-17190] input: don't override "time" and "position" when user seeks
- Link: https://code.videolan.org/videolan/vlc/-/issues/17190  State: open
- Mechanism: The requested seek target and the last physically observed position are different facts while a seek is in flight. Publishing both through one mutable variable makes clock updates overwrite the target and the UI thumb snap back.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, maskedSeekTargetMicros, queueSeek, handlePlaybackTime, and runSeek; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerEvent.kt, SeekCompleted
- Verdict: IMMUNE
- Why: queueSeek publishes the resolved target into a dedicated atomic mask while the actual clock reading remains separate. position reports the mask until the request drains, and runSeek emits SeekCompleted only after a landing, so an intervening clock sample cannot overwrite the user's target.
- Severity if real: P2 quality/perf

### [VLC-15055] different clock base used for first pts after buffering
- Link: https://code.videolan.org/videolan/vlc/-/issues/15055  State: open
- Mechanism: Sending a first decoded frame before the common post-buffer clock reference exists dates that frame on a different system-time base. More buffering then moves the first picture farther into the future than later frames.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, awaitInitialFill and handlePlaybackRestart; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/VideoPlayback.kt, tick; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/MediaClock.kt, set
- Verdict: IMMUNE
- Why: Decoded frames remain in bounded media-time queues while initial fill completes. The first frame of each generation establishes VideoPlayback's schedule at the current monotonic instant, and MediaClock anchors directly from that frame's media PTS; no frame is preconverted through a provisional system clock.
- Severity if real: P1 broken feature

### [VLC-13979] Input thread deadlocks when stopped quickly
- Link: https://code.videolan.org/videolan/vlc/-/issues/13979  State: closed-fixed
- Mechanism: Stopping immediately after open can tear down the input owner while its access or demux worker is still alive, then wait forever on the half-killed graph.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runStop, teardownSession, and releaseSession; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackWorkers.kt, Worker.quiesce
- Verdict: SUSPECT
- Why: releaseSession orders stop, cooperative quiescence, job cancellation and joins before closing decoders and the backend, which avoids VLC's half-killed ownership. However runStop uses the unbounded NonCancellable release path, so a demux job stuck inside a non-cooperative native read can still keep the stop call waiting indefinitely.
- Severity if real: P1 broken feature

### [VLC-13341] No sound when moving back from 8x to slower speed
- Link: https://code.videolan.org/videolan/vlc/-/issues/13341  State: open
- Mechanism: If audio is disabled above a high-rate threshold, the downward rate transition must explicitly recreate or re-enable it. Merely changing the clock rate leaves the muted path dormant.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/KitePlayer.kt, setSpeed and SPEED_MAX; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, speed; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, SetSpeed
- Verdict: IMMUNE
- Why: KitePlayer supports 0.25x through 4x and rejects 8x before any pipeline state changes. Within the supported range it never disables the audio lane for speed; a live change sets both wanted rates and rides a precise seek and flush boundary, so there is no separate muted-above-threshold state to forget.
- Severity if real: P1 broken feature

### [VLC-11826] vlc keeps flushing & rebuffering on failed SET_POSITION
- Link: https://code.videolan.org/videolan/vlc/-/issues/11826  State: open
- Mechanism: A failed seek must not be treated as a discontinuity that repeatedly flushes and restarts buffered output, especially on an unseekable source. Failure needs one terminal or rejected outcome.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, seekRejection, handlePendingSeek, and runSeek
- Verdict: IMMUNE
- Why: The actor rejects an unseekable source before runSeek mutates its queues. If a seekable source throws after mutation, handlePendingSeek tears down the unusable session and publishes one failure; it does not retry SET_POSITION or enter a flush and rebuffer loop.
- Severity if real: P1 broken feature

### [VLC-11689] Looping a video between two points fails if "point B" is at the very end
- Link: https://code.videolan.org/videolan/vlc/-/issues/11689  State: open
- Mechanism: When B equals the final media boundary, normal EOF handling can win before the ordinary B-crossing check and suppress the jump to A. EOF must treat that armed A-B loop as its own repeat case.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, handlePlaybackTime, handleEof, and handleLoop
- Verdict: IMMUNE
- Why: Playback crosses an in-range B through an ordinary precise seek, while handleLoop separately handles an armed A after Ended whenever B is absent or lies at or past the duration. It restarts from A before ordinary LoopMode handling, covering the exact end boundary.
- Severity if real: P1 broken feature

### [VLC-10346] Cycling audio tracks will never eventually disable audio track
- Link: https://code.videolan.org/videolan/vlc/-/issues/10346  State: wontfix
- Mechanism: Track selection needs a real no-audio state in addition to concrete stream ids. Otherwise a cycling control can only wrap from the last audio track to the first.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/KitePlayer.kt, selectTrack; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, selectTrack validation and inPlaceAudioChange; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/Tracks.kt, selectedAudio
- Verdict: IMMUNE
- Why: selectTrack accepts a null audio TrackId and inPlaceAudioChange installs a null AudioLane while preserving video playback. It refuses only the audio-only case where disabling the sole timeline stream would leave no playable output, and reports that refusal explicitly.
- Severity if real: P2 quality/perf

### [VLC-9253] Broken stats on ES_OUT_RESET_PCR
- Link: https://code.videolan.org/videolan/vlc/-/issues/9253  State: open
- Mechanism: Resetting the program clock reference must not reset only one side of a rolling bitrate calculation, or a byte delta divided by a mismatched time interval produces a wildly false rate.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerState.kt, PlaybackStats; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, publishProgressAndStats
- Verdict: N/A
- Why: KitePlayer does not compute a rolling input bitrate and PlaybackStats.containerBitrate is explicitly always null. Its monotonic frame and underrun counters have no PCR-derived denominator, so the reported bitrate statistic that this issue corrupts does not exist.
- Severity if real: P3 polish

### [VLC-9063] MP4 video started with start-time displays the first frame before going to the start time
- Link: https://code.videolan.org/videolan/vlc/-/issues/9063  State: open
- Mechanism: Performing an initial seek only after the normal open has already presented a frame leaks content from time zero before the requested start position appears.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/MediaItem.kt, startPosition; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, startPositionTargetUs and runOpen
- Verdict: IMMUNE
- Why: runOpen seeks the source before workers start whenever startPosition is valid, so initial decode begins at the preceding keyframe near the target rather than at zero. Its later precise phase refines that same landing; no beginning frame is presented first.
- Severity if real: P1 broken feature

### [VLC-8917] [feature request] Auto-skipping predefined segments/chapters in  files
- Link: https://code.videolan.org/videolan/vlc/-/issues/8917  State: open
- Mechanism: Automatic segment skipping needs rules that match chapter names, detect natural chapter crossings, and seek to the next boundary while distinguishing continuous playback from a user's deliberate seek into the segment.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerState.kt, Chapter; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, handlePlaybackTime; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerEvent.kt, ChapterChanged
- Verdict: MISSING-FEATURE
- Why: KitePlayer publishes the chapter table and ChapterChanged events but has no name-matching skip policy or automatic seek at selected chapter boundaries. Applications can build the policy from events, but the player itself does not implement it.
- Severity if real: P2 quality/perf

### [VLC-7012] Configurable in-memory cache for playing media
- Link: https://code.videolan.org/videolan/vlc/-/issues/7012  State: open
- Mechanism: A bounded RAM window with configurable read-ahead and retained history can absorb latency and let the underlying disk or connection rest, but only if every relevant byte path actually passes through it.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerConfig.kt, IoCachePolicy and NetworkConfig; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/CachingMediaIo.kt, CachingMediaIo; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, buildSession
- Verdict: MISSING-FEATURE
- Why: MediaIo-fed opens correctly receive configurable chunk, retained-history, and total-window budgets. Direct backend URIs intentionally bypass CachingMediaIo when no resolver or custom reader is supplied. No cache contract is violated, but KitePlayer lacks the requested transparent configurable cache that covers every local-file and protocol URI path.
- Severity if real: P2 quality/perf

### [VLC-6985] Next frame button have no effect on secondary video track
- Link: https://code.videolan.org/videolan/vlc/-/issues/6985  State: open
- Mechanism: Frame stepping with several active video tracks must advance each decoder to the same next timeline boundary and refresh every independent output, not just the primary display.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, OpenSession and stepOneFrame; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/Tracks.kt, selectedVideo
- Verdict: MISSING-FEATURE
- Why: KitePlayer selects one video track, owns one VideoPlayback lane, and steps one presentation queue. It exposes other video streams only as alternatives, with no simultaneous secondary output for stepOneFrame to refresh.
- Severity if real: P1 broken feature

### [VLC-3252] Miss-selects default audio stream (BBC HD, DVD multi-lingual narration)
- Link: https://code.videolan.org/videolan/vlc/-/issues/3252  State: closed-fixed
- Mechanism: Default audio choice must distinguish a normal programme track from narration or audio description when the container order or grouping places the accessibility stream first.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, pickAudio; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, StreamInfo.toPlayerStream
- Verdict: SUSPECT
- Why: KiteCodec exposes the visual-impaired and hearing-impaired dispositions as isAccessibility, but pickAudio never reads that flag. With no preferred-language match or reliable default disposition it selects the first audio stream, which can be the narration track described upstream.
- Severity if real: P1 broken feature

### [VLC-3713] vlc selects visual impaired commentary audio-track if it's the first one
- Link: https://code.videolan.org/videolan/vlc/-/issues/3713  State: closed-fixed
- Mechanism: Automatic selection needs to rank an accessibility commentary below an ordinary audio track unless the user explicitly requests the accessibility service.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, pickAudio; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/MediaSource.kt, PlayerStreamInfo.isAccessibility
- Verdict: SUSPECT
- Why: PlayerStreamInfo carries the accessibility flag, but pickAudio considers only preferred language, default disposition, and container order. A first visual-impaired stream can therefore win exactly as in the DVB sample when no preferred language matches the normal track.
- Severity if real: P1 broken feature

### [VLC-8118] Automatically select audio- and subtitle- tracks with multi-lingual videos
- Link: https://code.videolan.org/videolan/vlc/-/issues/8118  State: wontfix
- Mechanism: Locale-driven automatic selection needs a platform locale source, language-tag normalisation, and a policy that seeds both audio and subtitle preferences when the user has configured none.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerConfig.kt, AudioConfig and SubtitleConfig; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, pickAudio and pickSubtitle
- Verdict: MISSING-FEATURE
- Why: KitePlayer honours explicit preferredLanguages lists, but neither config nor the session reads the operating-system locale. Empty preferences fall back to container dispositions and order rather than the device country or language.
- Severity if real: P2 quality/perf

### [VLC-8987] Track and subtitle selections are not saved
- Link: https://code.videolan.org/videolan/vlc/-/issues/8987  State: closed-fixed
- Mechanism: Resuming an item needs to persist the selected audio, video, and subtitle identities alongside its playback position, then reconcile them against the reopened track table.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/MediaItem.kt, MediaItem; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/Tracks.kt, Tracks; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runOpen
- Verdict: MISSING-FEATURE
- Why: MediaItem can persist a startPosition but carries no remembered track identities. runOpen always makes automatic StreamChoice selections for a new session, and no player-owned per-media history restores the previous exact tracks.
- Severity if real: P2 quality/perf

### [VLC-12970] VLC does not display vobsub subtitle tracks beyond the first
- Link: https://code.videolan.org/videolan/vlc/-/issues/12970  State: open
- Mechanism: A multi-language VobSub sidecar needs its IDX stream table parsed into distinct selectable tracks and each selection routed to the corresponding bitmap packet ids in the shared SUB file.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/MediaItem.kt, externalSubtitles; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, parseExternalSubtitles; kiteplayer-subtitles/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/subtitle/AssParser.kt, parseAss
- Verdict: MISSING-FEATURE
- Why: KitePlayer's external sidecar path parses text SubRip, WebVTT, and ASS forms, not IDX/SUB bitmap pairs. It cannot expose even the first VobSub sidecar track, much less route several language indices correctly.
- Severity if real: P1 broken feature

### [VLC-17529] 3.0 regression: MKV active-by-default subtitles no longer automatically selected
- Link: https://code.videolan.org/videolan/vlc/-/issues/17529  State: closed-fixed
- Mechanism: When automatic subtitles are enabled and no language preference overrides them, the container's default subtitle disposition must survive demux metadata mapping and win selection.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, StreamInfo.toPlayerStream; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, pickSubtitle; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerConfig.kt, SubtitleConfig.autoSelect
- Verdict: IMMUNE
- Why: KiteCodec maps FFmpeg's default disposition into PlayerStreamInfo.isDefault. With autoSelect enabled, pickSubtitle excludes forced-only tracks and sorts the remaining candidates by that flag before choosing the first, independent of an empty preference list.
- Severity if real: P1 broken feature

### [VLC-17682] HLS not select previous audio track after PCR Error
- Link: https://code.videolan.org/videolan/vlc/-/issues/17682  State: wontfix
- Mechanism: A program-clock discontinuity must reset timing without rerunning automatic audio choice, or the currently selected alternate language falls back to the first stream.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, OpenSession, runSeek, and tracks; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, timestampsMayJump
- Verdict: IMMUNE
- Why: Discontinuous HLS and TS timestamps alter scheduling tolerance but do not rebuild the source or track table. The selected AudioLane and Tracks.selectedAudio remain session state across timestamp jumps and runSeek, so there is no PCR-reset hook that reruns pickAudio.
- Severity if real: P1 broken feature

### [VLC-18877] When a subtitle language is specified, the full subtitles track is not selected. forced ones are still used
- Link: https://code.videolan.org/videolan/vlc/-/issues/18877  State: wontfix
- Mechanism: An explicit preferred subtitle language normally asks for the full dialogue track. A forced track of the same language must not win merely because it appears first.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, pickSubtitle; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/MediaSource.kt, PlayerStreamInfo.isForced
- Verdict: SUSPECT
- Why: The preferred-language branch filters only by language and then sorts by default disposition. It does not exclude isForced, so a first or default forced French track can still beat the full French track exactly as the report describes.
- Severity if real: P1 broken feature

### [VLC-19129] Displays 2 video windows when switching to 2nd MKV video track
- Link: https://code.videolan.org/videolan/vlc/-/issues/19129  State: open
- Mechanism: Replacing a video selection must retire the old decoder and output before installing the new one. Adding the new output without removing the first leaves both tracks playing at once.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, handleTrackChanges, teardownSession, and buildSession; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/Tracks.kt, selectedVideo
- Verdict: IMMUNE
- Why: A video selection tears down the current OpenSession before building its replacement and Tracks has one selectedVideo id. The old VideoPlayback and decoder are joined and closed before the new single renderer lane becomes live, so two video windows cannot accumulate from the switch.
- Severity if real: P1 broken feature

### [VLC-27267] LibVLC 4.x is there a definitive event raised when completely valid track list information is available?
- Link: https://code.videolan.org/videolan/vlc/-/issues/27267  State: open
- Mechanism: Applications need one readiness boundary after probing has produced the complete stable track table; incremental events before all metadata arrives make an early query return incomplete rows.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, buildSession and runOpen; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerEvent.kt, Opened; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerState.kt, PlayerSnapshot.tracks
- Verdict: IMMUNE
- Why: buildSession obtains the source's complete immutable streams list and constructs Tracks before runOpen emits Opened or completes. The same finished table is already in the state snapshot at that boundary, so callers do not have to infer readiness from a sequence of ES-added events.
- Severity if real: P2 quality/perf

### [VLC-116] Enable scale tempo filter
- Link: https://code.videolan.org/videolan/vlc/-/issues/116  State: closed-fixed
- Mechanism: Audio should remain enabled at non-unit playback rates by changing tempo while preserving pitch, rather than muting sound whenever the rate differs from 1x.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, submitDecoded and preservePitch; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/TempoStage.kt, TempoStage
- Verdict: IMMUNE
- Why: AudioPlayback sends every supported non-unit rate through TempoStage when preservePitch is true, and the audio lane remains selected from 0.25x through 4x. The alternative preservePitch false path deliberately resamples with pitch movement rather than disabling audio.
- Severity if real: P1 broken feature

### [VLC-1734] VLC 0.9.x crashes when playing sound in 4x speed
- Link: https://code.videolan.org/videolan/vlc/-/issues/1734  State: wontfix
- Mechanism: The reported 4x crash reached VLC's float-to-S16 converter with corrupt mixer buffer pointers after the high-rate audio transition.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, submitDecoded and speed; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/TempoStage.kt, speed and process
- Verdict: IMMUNE
- Why: KitePlayer's 4x path uses owned Kotlin FloatArray buffers through a bounded TempoStage and validates the rate before mutation. It does not compile VLC's float32 mixer or pass aliased input and output buffer pointers to the converter where this crash occurred.
- Severity if real: P0 crash/dataloss

### [VLC-2637] Crash when seeking audio file using avformat at speed higher then normal
- Link: https://code.videolan.org/videolan/vlc/-/issues/2637  State: wontfix
- Mechanism: Seeking at a non-unit rate must stop the output and prevent conversion from touching buffers while the mixer, clock, and decoder are flushed into the new timeline.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runSeek, quiesceWorkers, and flushDecoders; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, flush
- Verdict: IMMUNE
- Why: runSeek stops the sink, parks audio decode and feed workers, and only then flushes the decoder, ring, DSP pipeline, and generation. The new speed becomes the audio epoch inside that quiescent flush, so no converter retains an old buffer across the seek.
- Severity if real: P0 crash/dataloss

### [VLC-3651] ability to preserve the user set playback speed on playlist advance
- Link: https://code.videolan.org/videolan/vlc/-/issues/3651  State: closed-fixed
- Mechanism: Playback rate is player intent, not media metadata. Advancing a queue item must seed the replacement audio and video paths from the existing rate rather than reset them to 1x.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, speed, buildSession, and handleQueueAdvance; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, speed
- Verdict: IMMUNE
- Why: speed lives on PlaybackCore outside OpenSession. Every buildSession applies it to the fresh AudioPlayback and VideoPlayback before output starts, and handleQueueAdvance opens the next MediaItem without resetting that actor-owned value.
- Severity if real: P2 quality/perf

### [VLC-6509] Changing playback speed results in audio distortion for DTS audio
- Link: https://code.videolan.org/videolan/vlc/-/issues/6509  State: closed-fixed
- Mechanism: Time stretching compressed or framed DTS bytes instead of decoded PCM destroys the signal even though video pacing remains correct. Rate processing must occur after decode in a codec-independent sample domain.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, KiteCodecAudioDecoder.receive; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runAudioFeed; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, submitDecoded
- Verdict: IMMUNE
- Why: DTS is decoded to planar float AudioBuffer samples before runAudioFeed interleaves them. AudioPlayback applies tempo, resampling, channel mixing, and gain only to those PCM samples, so its speed path is independent of the original compressed codec framing.
- Severity if real: P1 broken feature

### [VLC-7723] Playback speed increase after seeking
- Link: https://code.videolan.org/videolan/vlc/-/issues/7723  State: closed-fixed
- Mechanism: Repeated seeks must reanchor the current playback rate, not multiply an already scaled clock or resampler ratio again on every generation.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, speed, flush, and anchorLocked; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/MediaClock.kt, speed; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runSeek
- Verdict: IMMUNE
- Why: AudioPlayback stores one wantedSpeed and copies that scalar into each fresh epoch during flush. The media clock is invalidated and assigned the same rate rather than multiplied, while anchorLocked converts the scaled audio axis back to media time exactly once.
- Severity if real: P2 quality/perf

### [VLC-12111] Playing videos in high speed distorts pitch (chipmunk voices)
- Link: https://code.videolan.org/videolan/vlc/-/issues/12111  State: wontfix
- Mechanism: Raising sample playback rate without a pitch-synchronous tempo stage raises speech pitch with it. Mobile and desktop outputs need the same time-stretch path.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerConfig.kt, AudioConfig.preservePitch; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, preservePitch and submitDecoded; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/TempoStage.kt, TempoStage
- Verdict: IMMUNE
- Why: preservePitch defaults true in common code and AudioPlayback feeds TempoStage on every platform at non-unit speed. The pitch-moving resampler path exists only when the caller explicitly sets preservePitch false.
- Severity if real: P2 quality/perf

### [VLC-13787] VLC skips some audio when speed is changed more that .2x
- Link: https://code.videolan.org/videolan/vlc/-/issues/13787  State: open
- Mechanism: Changing rate by flushing queued old-rate audio and restarting from an imprecise clock sample can discard an audible interval, especially when each adjustment crosses an internal speed bucket.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, SetSpeed and runSeek; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, flush and submitDecoded
- Verdict: SUSPECT
- Why: KitePlayer has no 0.2x bucket, but every live rate change deliberately discards the ring and DSP history through a precise seek at currentPosition. The landing is sample-trimmed, yet no cited end-to-end fixture proves the device tail discarded before the sampled anchor joins the new epoch without an audible hole.
- Severity if real: P2 quality/perf

### [VLC-17516] VLC cannot correctly display karaoke subtitling in ass files when the speed is not normal
- Link: https://code.videolan.org/videolan/vlc/-/issues/17516  State: open
- Mechanism: ASS karaoke changes style within a cue as media time advances. A renderer that treats the whole dialogue as one static cue cannot keep the syllable highlight synchronized at any rate.
- KitePlayer code checked: kiteplayer-subtitles/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/subtitle/AssParser.kt, parseAss; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/subtitle/SubtitleCue.kt, SubtitleCue.Text; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, timeAndPublishCues
- Verdict: MISSING-FEATURE
- Why: AssParser explicitly strips karaoke timing tags while retaining their text, and SubtitleCue has no within-cue animation timeline. Playback speed changes media-time cue edges correctly, but there is no karaoke highlight to synchronize even at 1x.
- Severity if real: P1 broken feature

### [VLC-19304] audio cuts out when adjusting playback speed
- Link: https://code.videolan.org/videolan/vlc/-/issues/19304  State: open
- Mechanism: A live speed change that tears down old-rate buffered samples needs a continuous splice into the first new-rate samples; otherwise the output runs empty for the transition and audibly cuts out.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, SetSpeed, runSeek, and handlePlaybackRestart; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, flush
- Verdict: SUSPECT
- Why: KitePlayer chooses a full precise-seek epoch boundary, stops and clears the sink, then waits for selected streams to refill before restart. That prevents mixed-rate corruption, but it still creates an intentional rebuffer with no crossfade or retained old-rate tail, so a momentary cutout remains plausible.
- Severity if real: P2 quality/perf

### [VLC-808] MP4 chapter support
- Link: https://code.videolan.org/videolan/vlc/-/issues/808  State: closed-fixed
- Mechanism: MP4 and QuickTime can store chapters separately from ordinary media stream selection. A player that only enumerates audio, video, and subtitle streams never exposes that chapter table.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/buildSrc/src/main/kotlin/BuildFFmpegTask.kt, DEFAULT_SOURCE_REF; /Users/macbook/StudioProjects/#Kite/KiteCodec/kitecodec-core/src/nativeMain/kotlin/io/github/yuroyami/kitecodec/MediaSource.native.kt, readChapters; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, chapters
- Verdict: IMMUNE
- Why: Pinned FFmpeg n8.0's MOV demuxer parses Nero chpl atoms with mov_read_chpl and QuickTime chapter tracks with mov_read_chapters, and both paths create AVChapter entries. KiteCodec imports the entire AVChapter table independently of media streams, then KiteCodecSource maps it without filtering either variant.
- Severity if real: P2 quality/perf

### [VLC-1069] "Next/Previous Chapter" shortcut may execute a "Jump Forward/Backward" if no chapter has been found
- Link: https://code.videolan.org/videolan/vlc/-/issues/1069  State: wontfix
- Mechanism: A remote-control chapter command needs a defined fallback when media has no chapters. Mapping it to a bounded time jump keeps the same control useful.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/KitePlayer.kt, chapterAt and seekToChapter; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerState.kt, Chapter
- Verdict: MISSING-FEATURE
- Why: KitePlayer exposes chapter lookup and seek by explicit index, but has no nextChapter or previousChapter command and therefore no no-chapter fallback. An application can build that policy from state and seek, but the facade does not provide it.
- Severity if real: P3 polish

### [VLC-1183] Show chapter name in status line .MKV
- Link: https://code.videolan.org/videolan/vlc/-/issues/1183  State: open
- Mechanism: A chapter label shown during playback and immediately after a seek must be recomputed from the published position, rather than only advanced by a next-chapter command.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, handlePlaybackTime; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerEvent.kt, ChapterChanged; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/KitePlayer.kt, chapterAt
- Verdict: IMMUNE
- Why: handlePlaybackTime resolves the chapter holding every published position and emits ChapterChanged whenever that result changes, including after seek. The facade also exposes chapterAt, so a UI has push and pull paths for the current title without depending on a built-in status line.
- Severity if real: P3 polish

### [VLC-10542] With MKV multi-editions, only the chapters of the first edition can be selected
- Link: https://code.videolan.org/videolan/vlc/-/issues/10542  State: closed-fixed
- Mechanism: Matroska editions are alternative ordered chapter trees. Flattening them loses the default-edition flag and leaves no way to switch edition before selecting its chapters.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerState.kt, Chapter and PlayerSnapshot.chapters; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, chapters
- Verdict: MISSING-FEATURE
- Why: Chapter contains only index, start, end, and title, and PlayerSnapshot exposes one flat list. KiteCodecSource maps no edition identity, hierarchy, default flag, or edition-selection API, so multiple editions cannot be represented faithfully.
- Severity if real: P1 broken feature

### [VLC-11243] [MKV] Multilanguage Chaptertitles
- Link: https://code.videolan.org/videolan/vlc/-/issues/11243  State: open
- Mechanism: A Matroska chapter may carry several display strings tagged by language. Choosing one requires preserving those alternatives and applying a language preference.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerState.kt, Chapter; /Users/macbook/StudioProjects/#Kite/KiteCodec/kitecodec-core/src/nativeMain/kotlin/io/github/yuroyami/kitecodec/MediaSource.native.kt, readChapters
- Verdict: MISSING-FEATURE
- Why: KitePlayer Chapter has one nullable title and no language-tagged title collection. The public model has nowhere to preserve or select multilingual chapter displays.
- Severity if real: P2 quality/perf

### [VLC-19381] Chapter durations are off by one
- Link: https://code.videolan.org/videolan/vlc/-/issues/19381  State: closed-fixed
- Mechanism: Chapter APIs become off by one when each duration is stored in the next array slot or derived from the wrong adjacent start. Every entry must retain its own bounds.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/kitecodec-core/src/nativeMain/kotlin/io/github/yuroyami/kitecodec/MediaSource.native.kt, readChapters; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, chapters
- Verdict: IMMUNE
- Why: readChapters obtains start and end for the same native index in one call, and KiteCodecSource maps that pair directly into the same Chapter. There is no shifted duration array or next-entry lookup.
- Severity if real: P2 quality/perf

### [VLC-23074] Chapters on video with only one at 0.0s are not recognized
- Link: https://code.videolan.org/videolan/vlc/-/issues/23074  State: open
- Mechanism: Treating timestamp zero as a sentinel or suppressing a one-entry chapter table hides a valid whole-program chapter.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/kitecodec-core/src/nativeMain/kotlin/io/github/yuroyami/kitecodec/MediaSource.native.kt, readChapters; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, chapters
- Verdict: IMMUNE
- Why: The issue's ffprobe receipt shows FFmpeg exposes the one chapter. KiteCodec accepts any positive count and iterates index zero, while KiteCodecSource maps a zero start as Pts.Zero and has no single-entry suppression.
- Severity if real: P2 quality/perf

### [VLC-24848] Audio book: Only the first 255 chapters are shown
- Link: https://code.videolan.org/videolan/vlc/-/issues/24848  State: wontfix
- Mechanism: Storing a chapter count or index in an unsigned byte truncates a large audiobook at 255 entries even when the demuxer found more.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/kitecodec-core/src/nativeMain/kotlin/io/github/yuroyami/kitecodec/MediaSource.native.kt, readChapters; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerState.kt, PlayerSnapshot.chapters
- Verdict: IMMUNE
- Why: KiteCodec reads the count as Int and iterates the complete range into a Kotlin List. PlayerSnapshot retains that List and chapter indices are Int values, so no 8-bit boundary exists.
- Severity if real: P2 quality/perf

### [VLC-26802] libvlc_media_player_get_full_chapter_descriptions only returns chapters when media player is playing
- Link: https://code.videolan.org/videolan/vlc/-/issues/26802  State: open
- Mechanism: Tying chapters to an actively running decoder makes metadata disappear while the same media is paused or ended. Chapters should belong to the open session.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runOpen and publishSnapshot; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerState.kt, PlayerSnapshot.chapters
- Verdict: IMMUNE
- Why: runOpen completes into Paused after preroll, and publishSnapshot reads chapters from the open source without checking PlaybackStatus. The session remains available in Paused and Ended, so chapters are not gated on Playing. Explicit stop releases the media by design.
- Severity if real: P1 broken feature

### [VLC-28748] [Feature Request] Add a way for the Matroska demuxer to use an external chapter file
- Link: https://code.videolan.org/videolan/vlc/-/issues/28748  State: open
- Mechanism: Authoring workflows need a sidecar chapter document to replace or augment embedded Matroska chapters without remuxing after every edit.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/MediaItem.kt, MediaItem; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, chapters
- Verdict: MISSING-FEATURE
- Why: MediaItem accepts external subtitles but no external chapter source, and KiteCodecSource constructs chapters only from the container. There is no XML parser, sidecar discovery policy, or API for a replacement chapter list.
- Severity if real: P2 quality/perf

### [VLC-18088] Better HDR support
- Link: https://code.videolan.org/videolan/vlc/-/issues/18088  State: open
- Mechanism: Complete HDR output must carry mastering and dynamic metadata, choose between display passthrough and tone mapping, and configure output for the display's capabilities.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/VideoFrame.kt, ColorSpaceInfo; kiteplayer-output/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/output/MetalVideoSupport.kt, packToneUniforms; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlaybackError.kt, HdrToneMapped
- Verdict: MISSING-FEATURE
- Why: KitePlayer recognizes PQ, HLG, and BT.2020 and its built-in paths tone-map HDR to SDR, but ColorSpaceInfo carries no mastering peak, content-light level, or dynamic HDR metadata. Metal assumes a fixed 1000 nit peak, and no built-in HDR display passthrough is negotiated.
- Severity if real: P2 quality/perf

### [VLC-21575] VLC's screenshot feature on HDR content produces a washed-color picture
- Link: https://code.videolan.org/videolan/vlc/-/issues/21575  State: wontfix
- Mechanism: Raw PQ or HLG samples interpreted as ordinary SDR produce a dull or washed capture. A screenshot path must tone-map or encode an HDR-aware image with the proper color declaration.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/CapturedFrame.kt, CapturedFrame and of; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/KitePlayer.kt, captureFrame
- Verdict: MISSING-FEATURE
- Why: CapturedFrame intentionally copies decoded source planes before renderer color management. It preserves ColorSpaceInfo for caller conversion, but KitePlayer supplies no screenshot encoder or tone-mapped capture option. Treating those planes as SDR reproduces the reported result.
- Severity if real: P2 quality/perf

### [VLC-22447] direct3d11 shaders are not mapping subpicture to HDR
- Link: https://code.videolan.org/videolan/vlc/-/issues/22447  State: open
- Mechanism: Compositing an SDR subtitle directly onto an HDR output surface without mapping both into one luminance domain makes the overlay extremely dark or otherwise mis-scaled.
- KitePlayer code checked: kiteplayer-output/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/output/MetalFrameComposer.kt, encode; kiteplayer-output/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/output/MetalVideoRenderer.kt, present; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlaybackError.kt, HdrToneMapped
- Verdict: IMMUNE
- Why: KitePlayer's built-in display contract is SDR. Metal tone-maps the video into the SDR target first and then draws the subtitle overlay into that target, so both share one output domain. There is no built-in HDR swapchain onto which an unmapped SDR overlay can be written.
- Severity if real: P2 quality/perf

### [VLC-26631] No tone-mapping for HDR content on non-HDR monitor
- Link: https://code.videolan.org/videolan/vlc/-/issues/26631  State: wontfix
- Mechanism: Sending PQ or HLG values through an SDR transfer clips highlights and compresses contrast. SDR display needs transfer decoding, highlight rolloff, gamut mapping, and SDR output encoding.
- KitePlayer code checked: kiteplayer-output/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/output/MetalVideoSupport.kt, kp_tone_map and packToneUniforms; kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/AndroidGpuImageVideoRenderer.kt, composeMediaCodecOutputContract
- Verdict: IMMUNE
- Why: Metal has separate PQ and HLG decoding, highlight rolloff, BT.2020 to BT.709 conversion, and SDR encoding. The Android GPU path requests decoder-side HDR-to-SDR output where supported and validates limited-range BT.709 SDR before exposing a frame.
- Severity if real: P2 quality/perf

### [VLC-26709] AV1 SDR Videos decoded as HDR Videos in playlist in macOS
- Link: https://code.videolan.org/videolan/vlc/-/issues/26709  State: open
- Mechanism: Reusing retained output color state across playlist items can apply the previous item's HDR transfer to new SDR media, or keep an SDR curve for the next HDR item.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, KiteCodecVideoFrame.colorSpace; kiteplayer-output/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/output/MetalFrameComposer.kt, encode; kiteplayer-output/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/output/MetalVideoSupport.kt, packToneUniforms
- Verdict: IMMUNE
- Why: Every decoded frame derives ColorSpaceInfo from its own native metadata. Metal repacks its tone-map mode from the current frame for every encode, and an SDR transfer returns the disabled block. No playlist-global HDR mode is carried into the next source.
- Severity if real: P1 broken feature

### [VLC-26999] VLC ignores MKV color transfer tag and sets it to HD content transfer function for SD content
- Link: https://code.videolan.org/videolan/vlc/-/issues/26999  State: open
- Mechanism: Ignoring Matroska transfer metadata and guessing BT.709 changes SD color. Tagged SMPTE 170M content must retain its declared matrix, primaries, and transfer through decode and render.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/buildSrc/src/main/kotlin/BuildFFmpegTask.kt, DEFAULT_SOURCE_REF; /Users/macbook/StudioProjects/#Kite/KiteCodec/kitecodec-core/src/nativeMain/kotlin/io/github/yuroyami/kitecodec/MediaSource.native.kt, DecoderState.open and readCodecParameterColor; /Users/macbook/StudioProjects/#Kite/KiteCodec/kitecodec-core/src/nativeMain/kotlin/io/github/yuroyami/kitecodec/Frame.native.kt, readColorInfo; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/Conversions.kt, toPlayerColorSpace
- Verdict: IMMUNE
- Why: Pinned FFmpeg n8.0 parses Matroska TransferCharacteristics into codecpar color_trc, KiteCodec copies those parameters into the decoder context, and FFmpeg fill_frame_props supplies that transfer to any returned frame that left it unspecified. Frame.readColorInfo preserves declared value 6 ahead of its height guess, while toPlayerColorSpace maps SMPTE 170M to the distinct SD Bt601 transfer rather than Bt709.
- Severity if real: P2 quality/perf

### [VLC-27694] HDR curve setup issues when switching clips
- Link: https://code.videolan.org/videolan/vlc/-/issues/27694  State: open
- Mechanism: A tone curve cached from one clip becomes wrong when the next uses another HDR transfer, such as switching between PQ and HLG, unless output parameters are rebuilt from new frame metadata.
- KitePlayer code checked: kiteplayer-output/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/output/MetalFrameComposer.kt, encode; kiteplayer-output/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/output/MetalVideoSupport.kt, packToneUniforms; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, KiteCodecVideoFrame.colorSpace
- Verdict: IMMUNE
- Why: Metal does not retain one clip-level curve. Each encode selects PQ, HLG, or disabled SDR from the current frame, and KiteCodecVideoFrame reads current native frame metadata. Switching clips cannot inherit the old curve through this path.
- Severity if real: P2 quality/perf

### [VLC-28664] DASH: implement HDR signaling
- Link: https://code.videolan.org/videolan/vlc/-/issues/28664  State: open
- Mechanism: An adaptive DASH player may need manifest-level HDR properties to classify or select a representation before decoded frames exist. Ignoring that signaling can choose an incompatible representation or lose its transfer declaration.
- KitePlayer code checked: kiteplayer-network/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/network/dash/DashManifest.kt, DashAdaptationSet, DashRepresentation, and parseRepresentation; kiteplayer-network/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/network/dash/DashMediaIo.kt, mediaItemFor
- Verdict: MISSING-FEATURE
- Why: KitePlayer has a basic static DASH parser and segment MediaIo, but DashAdaptationSet and DashRepresentation model only content type, MIME type, codec, dimensions, bandwidth, and addressing. parseRepresentation ignores HDR properties, while mediaItemFor selects the highest-bandwidth video representation, so it cannot classify or choose representations using the requested manifest-level HDR signaling.
- Severity if real: P1 broken feature

### [VLC-2882] Support Rotation flag in MP4
- Link: https://code.videolan.org/videolan/vlc/-/issues/2882  State: closed-fixed
- Mechanism: Phone recordings store landscape pixels plus an MP4 display matrix. Ignoring that presentation metadata shows portrait video on its side.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/native/kitecodec-c/src/helpers_playback.c, ffkmp_stream_rotation_degrees; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, StreamInfo.toPlayerStream and KiteCodecVideoFrame.rotationDegrees; kiteplayer-output/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/output/FrameLayout.kt, frameLayout
- Verdict: IMMUNE
- Why: KiteCodec reads FFmpeg display-matrix side data and converts it to normalized clockwise degrees. KiteCodecSource attaches that value to every frame, and shared output geometry applies quarter turns while swapping presentation aspect.
- Severity if real: P1 broken feature

### [VLC-8905] After using `Video Rotate` filter for first time every following file is also rotate, no matter, what setting is
- Link: https://code.videolan.org/videolan/vlc/-/issues/8905  State: wontfix
- Mechanism: A conversion pipeline that keeps a rotate filter instance or angle after one job can silently transform every later output even when the next job did not request rotation.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/MediaItem.kt, videoFilter; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/KitePlayer.kt, public facade
- Verdict: N/A
- Why: The report concerns VLC's Media Convert workflow. KitePlayer exposes no transcode, save, or converted-output API. Its per-item videoFilter affects decoded playback frames only, so no corresponding conversion-job sequence is in scope.
- Severity if real: P1 broken feature

### [VLC-21531] Snapshot of a video with orientation metadata is not rotated
- Link: https://code.videolan.org/videolan/vlc/-/issues/21531  State: closed-fixed
- Mechanism: A snapshot is expected to contain displayed orientation. Copying stored planes without applying the display matrix produces a sideways image even when playback is upright.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/CapturedFrame.kt, CapturedFrame.of; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/VideoFrame.kt, rotationDegrees
- Verdict: MISSING-FEATURE
- Why: CapturedFrame.of copies raw decoded planes and merely carries rotationDegrees alongside them. KitePlayer has no screenshot function or capture option that rotates those pixels, so callers must apply orientation themselves.
- Severity if real: P2 quality/perf

### [VLC-23645] Rotation during a playback
- Link: https://code.videolan.org/videolan/vlc/-/issues/23645  State: open
- Mechanism: User-controlled rotation during playback needs a runtime geometry parameter delivered to the active renderer, independent of immutable container orientation.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/VideoTransform.kt, VideoTransform; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/KitePlayer.kt, setVideoTransform; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/VideoRenderer.kt, setTransform
- Verdict: MISSING-FEATURE
- Why: VideoTransform contains aspect override, zoom, and pan only. Container rotation travels read-only on VideoFrame, and neither the facade nor renderer transform SPI offers a user rotation field.
- Severity if real: P2 quality/perf

### [VLC-26575] lib: Add orientation/flip parameter to the output API
- Link: https://code.videolan.org/videolan/vlc/-/issues/26575  State: open
- Mechanism: Texture callback clients sometimes need an explicit output flip to reconcile coordinate conventions without copying or reprocessing every frame.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/VideoFrame.kt, VideoFrame.rotationDegrees; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/VideoTransform.kt, VideoTransform; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/VideoRenderer.kt, setTransform
- Verdict: MISSING-FEATURE
- Why: VideoFrame exposes clockwise degrees only, and VideoTransform has no horizontal or vertical mirror flag. A custom renderer can impose its own convention, but KitePlayer's API cannot request or describe a flip.
- Severity if real: P2 quality/perf

### [VLC-26877] vaapi linux Opengl: first frame seems to appear in the incorrect orientation
- Link: https://code.videolan.org/videolan/vlc/-/issues/26877  State: open
- Mechanism: If orientation is configured only after the first picture initializes output, that picture can draw with default geometry before later frames correct it.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, KiteCodecVideoDecoder.receive and KiteCodecVideoFrame.rotationDegrees; kiteplayer-output/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/output/FrameLayout.kt, frameLayout
- Verdict: IMMUNE
- Why: KiteCodecVideoDecoder wraps every frame, including the first, with stream rotation before it reaches the scheduler or renderer. Layout computes geometry from that frame value on each presentation, so no later orientation event can race the first frame.
- Severity if real: P2 quality/perf

### [VLC-29101] Videos that require to be rotate and flipped ignore the flip
- Link: https://code.videolan.org/videolan/vlc/-/issues/29101  State: wontfix
- Mechanism: The eight rectangular display-matrix orientations include mirrored forms as well as four rotations. Reducing the matrix to an angle collapses distinct reflected orientations.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/native/kitecodec-c/src/helpers_playback.c, ffkmp_stream_rotation_degrees; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/VideoFrame.kt, rotationDegrees; kiteplayer-output/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/output/FrameLayout.kt, quarterTurn
- Verdict: MISSING-FEATURE
- Why: KiteCodec calls av_display_rotation_get and retains only an integer angle. VideoFrame has no reflection or full affine-matrix field, and FrameLayout draws only four non-mirrored quarter turns. The reflected orientations cannot survive this model.
- Severity if real: P1 broken feature
