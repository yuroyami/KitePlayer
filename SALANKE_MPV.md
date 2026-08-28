# SALANKE MPV tracker sweep

- Date: 2026-08-26
- Tracker: https://github.com/mpv-player/mpv/issues
- Access: Public GitHub API and issue timelines worked. The local `gh` token was invalid, so the sweep used read-only public API and web access.
- Counts: 6,312 unique issues listed, 300 triaged in, 300 processed.
- Checkpoint: processed through issue #14650 / full-history mechanism batch 12.
- Counting note: Listed is the completed-inventory union. Sixteen auditable queries were fully paginated: seek 1,259, subtitle 986, audio 2,973, hardware decode 1,358, timestamp 356, demux 489, buffering 158, track switching 124, HDR 640, speed or pitch or tempo or scaletempo 647, chapter or chapters or edition 410, the `core:color-management` label 29, the declared color-management lexical query 605, the `core` label for playback core 155, the declared sync or desync lexical query 763, and the declared EOF or queue lexical query 946. The first thirteen were split into nonoverlapping creation-year partitions through 2026 where necessary; the last three were below GitHub's 1,000-result cap and were paginated directly. Every family has zero incomplete results and its retrieved count equals its raw total. The auxiliary complete inventories are rotation 73, gapless 43, all 378 open `meta:feature-request` issues, and 543 unique non-PR issues from ten tracker pages sorted by comment count. These are 12,935 raw records and 6,312 unique issue IDs after cross-query deduplication. The playback-core, sync/desync, and EOF/queue families contributed 1,864 raw records, 1,624 unique IDs together, and 305 IDs not in the prior union. The new auditable speed, chapter, and two color families supersede the earlier opaque snapshots. Processing follows signal rank, not issue number order.

### [MPV-12369] Cycling audio when playing an online video causes cache to be invalidated and the video has to be redownloaded again
- Link: https://github.com/mpv-player/mpv/issues/12369  State: open
- Mechanism: MPV's track-switch design flushes its demux cache because packets for an unselected track were not cached. Selecting that track later forces a network source to fetch bytes again.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, buildSession and inPlaceAudioChange
- Verdict: IMMUNE
- Why: Session construction selects every audio stream and gives each one a bounded live `PacketQueue`. `inPlaceAudioChange` parks only the audio workers, verifies that the target queue spans the live position, and installs that queue without closing the source, demuxer, or `CachingMediaIo`; an insufficient cache refuses the switch instead of redownloading bytes.
- Severity if real: P1 broken feature

### [MPV-7780] Cached progress disappears on subtitle change
- Link: https://github.com/mpv-player/mpv/issues/7780  State: open
- Mechanism: A demux cache that contains only selected streams cannot immediately supply a newly selected subtitle stream. Reinitializing the demux path discards cached progress and makes a network source read the same region again.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, inPlaceContainerSubtitleChange and handleTrackChanges
- Verdict: IMMUNE
- Why: Session construction routes every container subtitle into its own bounded packet cache. `inPlaceContainerSubtitleChange` installs the target decoder and cached queue while the source, demuxer, video, audio, and `CachingMediaIo` stay live, so enabling or changing subtitles does not invalidate downloaded progress.
- Severity if real: P1 broken feature

### [MPV-8311] Internal subtitles are no longer displayed immediately when switching tracks
- Link: https://github.com/mpv-player/mpv/issues/8311  State: closed-fixed
- Mechanism: While paused, MPV tried to refresh the new subtitle before its demux packet was available. The fix forced reads through the current playback timestamp and retried until a packet arrived.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, handlers, inPlaceContainerSubtitleChange, and handleSubtitles
- Verdict: IMMUNE
- Why: Every subtitle stream is cached before selection. A live change installs that cached queue and decoder, drops only packets far behind the current position, and leaves playback paused; in the same ordered actor pass, `handleSubtitles` runs after `handleTrackChanges`, drains the target queue, selects cues at the unchanged clock position, and publishes the overlay.
- Severity if real: P1 broken feature

### [MPV-6970] New "ass" subtitle decoding breaks subtitles when seeking
- Link: https://github.com/mpv-player/mpv/issues/6970  State: closed-fixed
- Mechanism: Flushing FFmpeg's ASS conversion reset its `ReadOrder` counter. Matroska ASS events retained their original read order, so the reset made events after a seek conflict with decoder state. MPV fixed it by making the ASS read-order reset a no-op on flush.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSubtitleDecoder.kt, KiteCodecAssSubtitleDecoder.flush; kiteplayer-subtitles/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/subtitle/AssParser.kt, AssTrackParser.parseEvent
- Verdict: IMMUNE
- Why: KitePlayer does not send embedded ASS through FFmpeg's stateful ASS converter. Its Kotlin parser ignores the packet's `ReadOrder` field, and flush only clears already parsed pending cues, so no read-order counter can reset or conflict.
- Severity if real: P1 broken feature

### [MPV-7172] ao/coreaudio Audio underflow
- Link: https://github.com/mpv-player/mpv/issues/7172  State: closed-fixed
- Mechanism: MPV sized CoreAudio's player-side device buffer without using the hardware latency. The merged fix based the device buffer on that latency, preventing the engine ring from running dry when the hardware queue was larger than assumed.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, open and underruns; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, startWorkers and publishProgressAndStats
- Verdict: SUSPECT
- Why: KitePlayer sizes its ring from `deviceBufferFrames` and a duration floor, which lowers risk. The sink event itself is ignored, but the ring-level counter is sampled and surfaced as `PlaybackWarning.AudioUnderrun`; there is still no recovery action. The trigger is a sink underreporting its real device period or a feeder stall at high speed or under CPU pressure.
- Severity if real: P1 broken feature

### [MPV-12047] Seeking forward 5 seconds ends up seeking backwards
- Link: https://github.com/mpv-player/mpv/issues/12047  State: closed-fixed
- Mechanism: FFmpeg's MOV demuxer repeatedly backed up its requested timestamp while looking for a seekable sample and could fall all the way to sample zero. FFmpeg fixed the regression in 7.1 by stopping when the next candidate was also unusable instead of continuing to the beginning.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, seekToKeyframe; gradle/libs.versions.toml, kitecodec dependency
- Verdict: IMMUNE
- Why: KitePlayer delegates the backward keyframe seek to KiteCodec 0.1.4, whose shipped backend is pinned to FFmpeg n8.0. That is newer than FFmpeg 7.1, where the cited MOV demux fix shipped.
- Severity if real: P1 broken feature

### [MPV-8202] PGS subtitles showing and hiding unexpectedly
- Link: https://github.com/mpv-player/mpv/issues/8202  State: closed-fixed
- Mechanism: Overlapping PGS display sets had nearly equal floating-point end timestamps. Treating a tiny representation difference as a real ordering difference cleared all active bitmap subtitles early. MPV fixed the timing path and later added an explicit floating-point tolerance.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSubtitleDecoder.kt, KiteCodecSubtitleDecoderFactory.create
- Verdict: MISSING-FEATURE
- Why: KitePlayer's factory accepts text SubRip, WebVTT, tx3g, ASS, and SSA only, and its own documentation says bitmap formats still need real engines. PGS support would make Blu-ray and remux subtitles visible; the overlap tolerance from this issue belongs in that future decoder.
- Severity if real: P1 broken feature

### [MPV-4418] Better scaletempo (on high speed x1.5-x3)
- Link: https://github.com/mpv-player/mpv/issues/4418  State: closed-fixed
- Mechanism: Time stretching quality depends on the period search, overlap window, crossfade, and channel coherence. MPV's original WSOLA settings made speech robotic or echoing at high speed, and the issue led to a different scaler while documenting that no single simple parameter set handles speech and music equally well.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/TempoStage.kt, detectPeriod, emitSkip, and emitInsert
- Verdict: SUSPECT
- Why: KitePlayer uses a small fixed 65 Hz to 400 Hz period search over a mono average and a linear crossfade, with no content mode or quality control. The trigger is 1.5x to 3x speech or tonal music whose best splice is outside that fixed model. There is no listening or signal-quality proof in the virtual-time suite.
- Severity if real: P2 quality/perf

### [MPV-3928] Rubberband: Add support for getLatency() to avoid desync
- Link: https://github.com/mpv-player/mpv/issues/3928  State: wontfix
- Mechanism: Rubber Band buffers a speed-dependent number of input samples internally. MPV estimated that hidden lookahead, so a wrong estimate shifted audio and repeated seeks could accumulate more A/V error.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, submitDecoded and anchorLocked; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/TempoStage.kt, process
- Verdict: IMMUNE
- Why: KitePlayer owns the tempo queue and counts every frame it emits. It dates output from `tempoEmittedFrames`, then anchors the media clock to the sink's actual audible deadline. No external filter latency is guessed, and a seek resets both the tempo queue and its counters.
- Severity if real: P1 broken feature

### [MPV-7160] mpv often skips the last frame in loops.
- Link: https://github.com/mpv-player/mpv/issues/7160  State: open
- Mechanism: MPV considered playback finished as soon as it submitted the final frame. With no later frame or timer to wake the core after that frame's full duration, looping restarted early and shortened the last frame, especially at low frame rates.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/VideoPlayback.kt, tick and present; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, handleEof and handleLoop
- Verdict: SUSPECT
- Why: `present` removes the last frame from the queue when it starts showing. `handleEof` treats `queuedFrames == 0` as video completion and `handleLoop` can immediately seek to zero, with no hold-until deadline for the shown frame. The trigger is a video-only loop or a loop whose audio ends first, most visibly at a low frame rate.
- Severity if real: P1 broken feature

### [MPV-12084] vd_lavc: swdec fallback does not work if codec init fails
- Link: https://github.com/mpv-player/mpv/issues/12084  State: closed-fixed
- Mechanism: MPV could fail at hardware setup, decoder initialization, or frame decode. Its decoder-initialization branch requested fallback only once, but that attempt could still select a nonfunctional hardware decoder, so it had to retry until it reached working software.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/DecoderFallback.kt, openDecoderWithFallback and ReplayFallbackDecoder.demote
- Verdict: IMMUNE
- Why: KitePlayer's demotion does not rerun an automatic chooser. It calls `openSoftware`, which passes a null hardware route explicitly, flushes that software decoder, and replays retained packets. A software refusal becomes a typed terminal failure instead of silently returning to hardware.
- Severity if real: P1 broken feature

### [MPV-11854] Default subtitle tracks without a language tag do not get selected
- Link: https://github.com/mpv-player/mpv/issues/11854  State: closed-fixed
- Mechanism: A stricter language-selection default stopped considering the container's default subtitle when the track had no language tag. MPV restored a fallback that honors default disposition independently of language matching.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, pickSubtitle
- Verdict: IMMUNE
- Why: After preferred-language and forced-track checks, `pickSubtitle` explicitly sorts non-forced tracks by `isDefault` and takes the first one when `autoSelect` is enabled. A null language does not exclude that fallback.
- Severity if real: P1 broken feature

### [MPV-6537] Seeking in large audio streams over http is very slow
- Link: https://github.com/mpv-player/mpv/issues/6537  State: wontfix
- Mechanism: Raw MP3 has no seek index. Libavformat can find a distant timestamp only by reading frames from an estimated byte position or scanning toward the target, which makes a far seek over HTTP slow. Putting the audio in an indexed container avoids the scan.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, seekToKeyframe; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/CachingMediaIo.kt, seek
- Verdict: SUSPECT
- Why: KitePlayer delegates the seek to the FFmpeg packet reader and has no MP3 index builder or fast-seek policy. A seek outside the single cache window resets that window and calls upstream `seek`, so the trigger is a far seek in a long raw MP3 over HTTP.
- Severity if real: P1 broken feature

### [MPV-3022] support for secondary subtitle streams not forced to top | Make secondary subtitle track style editable
- Link: https://github.com/mpv-player/mpv/issues/3022  State: closed-fixed
- Mechanism: Showing two languages at once needs two independently selected and timed subtitle streams, then one composition step with separate positions or styles so their overlays do not collide.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, OpenSession subtitle fields and timeAndPublishCues
- Verdict: MISSING-FEATURE
- Why: `OpenSession` caches packets and cues for every container subtitle, but it installs only one active subtitle stream, decoder, queue, cue table, and selected id. A second concurrently timed path would be valuable for language learning and bilingual releases.
- Severity if real: P2 quality/perf

### [MPV-5161] Notify the screen to change to HDR10 / Dolby Vision mode
- Link: https://github.com/mpv-player/mpv/issues/5161  State: open
- Mechanism: Sending BT.2020 or PQ pixel values is not enough for native HDR output. The renderer must choose an HDR-capable swapchain or surface colorspace and pass mastering metadata so the display switches modes and interprets the signal correctly.
- KitePlayer code checked: kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/MediaCodecVideoDecoder.kt, DIRECT_SURFACE_ADMISSION and mediaCodecFormat; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/HdrToneMap.kt, forColorSpaceOrNull and mapInPlace; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/VideoFrame.kt, ColorSpaceInfo
- Verdict: MISSING-FEATURE
- Why: Android's direct MediaCodec Surface can preserve declared HDR and passes color hints to the decoder, so basic HDR output is not wholly absent. The shared color model has no mastering-display or content-light metadata, and the Metal, software, and composited Android paths reduce HDR to SDR. The missing capability is metadata-aware native HDR output beyond the Android direct-Surface case.
- Severity if real: P2 quality/perf

### [MPV-6376] Closed captions won't play
- Link: https://github.com/mpv-player/mpv/issues/6376  State: closed-fixed
- Mechanism: MPV's hardware copyback path discarded the EIA-608 or EIA-708 caption side data attached to decoded video frames. Preserving that side data let the caption decoder see the same data software decode exposed.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, KiteCodecVideoDecoder.receive; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSubtitleDecoder.kt, KiteCodecSubtitleDecoderFactory.create
- Verdict: MISSING-FEATURE
- Why: KitePlayer turns decoded video into `KiteCodecVideoFrame` without extracting caption side data, and its subtitle factory only accepts declared text subtitle streams. Embedded broadcast captions are therefore unavailable on both software and hardware routes, which is an accessibility gap.
- Severity if real: P1 broken feature

### [MPV-5079] Missing support for R128_TRACK_GAIN
- Link: https://github.com/mpv-player/mpv/issues/5079  State: closed-fixed
- Mechanism: Opus stores loudness normalization in `R128_TRACK_GAIN` and `R128_ALBUM_GAIN`, not only in the older ReplayGain tag names. A player must map those tags to gain values before its audio gain stage.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, metadata; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/AudioPipeline.kt, GainStage use
- Verdict: MISSING-FEATURE
- Why: Source metadata is exposed, but no ReplayGain or R128 reader applies it to the audio pipeline. Automatic loudness normalization would prevent large volume jumps between playlist items and between Opus and FLAC copies.
- Severity if real: P2 quality/perf

### [MPV-6974] Can't seek backwards in Ogg Opus file
- Link: https://github.com/mpv-player/mpv/issues/6974  State: closed-fixed
- Mechanism: MPV's optional timestamp linearizer treated the first packet after a seek as a new discontinuity and rewrote its timeline. The fix disables automatic linearization as soon as a seek is performed.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, TimestampMapper and seekToKeyframe
- Verdict: IMMUNE
- Why: `TimestampMapper` subtracts one constant container start time and never detects or rewrites discontinuities. A seek therefore cannot toggle a linearization state or reinterpret its first Ogg packet.
- Severity if real: P1 broken feature

### [MPV-6006] mpv should not show non-forced subtitles when --sub-forced-only is set
- Link: https://github.com/mpv-player/mpv/issues/6006  State: wontfix
- Mechanism: A subtitle track can mix forced events with ordinary dialogue. Selecting a track flagged as forced is not the same as filtering individual events, and MPV's option only had enough event metadata for some bitmap formats.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, pickSubtitle; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/subtitle/SubtitleCue.kt, SubtitleCue
- Verdict: MISSING-FEATURE
- Why: KitePlayer can select a stream whose disposition is forced, but `SubtitleCue` carries no per-event forced flag and the timing path has no forced-only filter. The feature would show foreign-language lines without showing same-language dialogue when a container mixes both in one track.
- Severity if real: P2 quality/perf

### [MPV-13830] Videos with mkv ProjectionPoseRoll element are rotated in the wrong direction
- Link: https://github.com/mpv-player/mpv/issues/13830  State: closed-fixed
- Mechanism: Matroska `ProjectionPoseRoll` is counter-clockwise, while the display-matrix helper consumes a clockwise angle. Treating both conventions as the same sign rotated projected video in the opposite direction.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, toPlayerStream; ../KiteCodec/native/kitecodec-c/src/helpers_playback.c, ffkmp_stream_rotation_degrees
- Verdict: IMMUNE
- Why: KiteCodec is built on FFmpeg n8.0, whose Matroska demuxer explicitly negates counter-clockwise roll for the clockwise display matrix. KiteCodec then converts FFmpeg's counter-clockwise getter result into the clockwise degrees its renderer contract names.
- Severity if real: P1 broken feature

### [MPV-6400] Add option for rotating subtitles
- Link: https://github.com/mpv-player/mpv/issues/6400  State: open
- Mechanism: Video rotation and subtitle composition happen in different coordinate systems. Rotating only the video leaves captions horizontal, so subtitle cues need their own transform or must be composed before the shared rotation.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, publishOverlay; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/KitePlayer.kt, setVideoTransform
- Verdict: MISSING-FEATURE
- Why: Video transform is sent to the renderer, while subtitle rasterization receives only canvas size, scale, and vertical position. A subtitle rotation control would keep captions aligned with intentionally rotated or sideways content.
- Severity if real: P3 polish

### [MPV-13242] ao/pulse may hang when playing videos
- Link: https://github.com/mpv-player/mpv/issues/13242  State: wontfix
- Mechanism: After an underrun, the first new audio buffer already carried EOF. MPV started the audio output and marked it playing, then the next video waited forever for old audio while PulseAudio's writable allocation kept growing.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, endOfStream and drain; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, handleEof and handleQueueAdvance
- Verdict: IMMUNE
- Why: KitePlayer marks its bounded ring as ending when decode drains, waits for the sink deadline, publishes `Ended`, and tears down the whole session before opening the next item. It has no branch that restarts an underrun output from a buffer already marked EOF, so the stale-playing state and unbounded allocation path do not exist.
- Severity if real: P1 broken feature

### [MPV-6109] audio-stream-silence no longer working
- Link: https://github.com/mpv-player/mpv/issues/6109  State: open
- Mechanism: Keeping a silent stream active through pause and seek retains an HDMI receiver's format lock. When ALSA instead stops on EPIPE, the receiver needs several seconds to reacquire the signal before sound returns.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, pause and flush; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerConfig.kt, AudioConfig
- Verdict: MISSING-FEATURE
- Why: Pausing can stop the sink, every flush stops it, and there is no silence-keepalive policy in `AudioConfig`. Users whose HDMI receivers renegotiate slowly cannot ask KitePlayer to keep the route warm during a pause or seek.
- Severity if real: P2 quality/perf

### [MPV-9992] ao_pipewire: possible slight crackling maybe caused by low pipewire-buffer value?
- Link: https://github.com/mpv-player/mpv/issues/9992  State: closed-fixed
- Mechanism: A 20 ms player-side PipeWire buffer was too shallow for scheduling jitter on the affected system. Raising it to 60 ms eliminated the intermittent crackle.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, open
- Verdict: IMMUNE
- Why: KitePlayer sizes the ring to at least its sink period multiple and a 200 ms duration floor. The cited starvation mechanism needs a buffer less than one third of that floor.
- Severity if real: P2 quality/perf

### [MPV-5891] [ao/alsa] Device underrun detected
- Link: https://github.com/mpv-player/mpv/issues/5891  State: wontfix
- Mechanism: MPV reported an ALSA underrun after the last audio chunk had intentionally drained and playback resumed later. The dry interval at a known logical end was misclassified as a device failure.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, finishDecoded and endOfStream; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, handleEof
- Verdict: IMMUNE
- Why: KitePlayer marks the ring as ending as soon as the decoder drains. Its real-time sink distinguishes that terminal dry state from an underrun, so the known gap after the final chunk does not increment the underrun counter or produce this false warning.
- Severity if real: P3 polish

### [MPV-1367] coreaudio fails when playing 2.0 audio tracks on 5.1 audio device
- Link: https://github.com/mpv-player/mpv/issues/1367  State: wontfix
- Mechanism: CoreAudio exposed a six-description device layout, but MPV could not convert that description to a usable layout tag and therefore failed to choose a channel map for stereo playback.
- KitePlayer code checked: kiteplayer-output/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/output/CoreAudioSink.kt, openWithRing; kiteplayer-rt/native/src/kite_rt_coreaudio.c, kprt_device_channels, kprt_layout_tag_for, and kprt_sink_create
- Verdict: IMMUNE
- Why: The native sink queries the current route's channel count, clamps the accepted stream width, supplies a native layout tag or mask, and lets the engine mixer produce that negotiated format. It does not require converting an arbitrary six-description device layout before opening stereo.
- Severity if real: P1 broken feature

### [MPV-1101] Gapless playback of mp3s
- Link: https://github.com/mpv-player/mpv/issues/1101  State: closed-fixed
- Mechanism: True MP3 gapless playback requires honoring Xing or LAME encoder delay and end padding, then keeping the audio output continuous across adjacent tracks so no stop, reopen, or extra silence occurs at the boundary.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, handleQueueAdvance and runOpen; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, audio decoder construction
- Verdict: MISSING-FEATURE
- Why: The FFmpeg decoder can honor encoded trimming metadata, but KitePlayer waits for `Ended`, tears down the current audio path, and opens the next item as a new session. It has no next-item prefetch or continuous sink handoff, so album tracks cannot be sample-contiguous.
- Severity if real: P1 broken feature

### [MPV-8705] Scaletempo2, the new default for adjusting audio playback speed, sounds noticeably worse in some situations
- Link: https://github.com/mpv-player/mpv/issues/8705  State: open
- Mechanism: MPV's newer time stretcher sounded worse on 5.1 material at small speed offsets, particularly around 0.85x to 1.2x, even though it improved larger offsets. The failure is content and channel-layout dependent.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/TempoStage.kt, detectPeriod, emitSkip, and emitInsert
- Verdict: SUSPECT
- Why: KitePlayer averages all channels to mono for one fixed 65 Hz to 400 Hz period search, then applies that splice to every channel. Surround channels can cancel or present unrelated periodic content in the mono average, making a poor splice plausible at the same small speed offsets. No accepted signal-quality test excludes it.
- Severity if real: P2 quality/perf

### [MPV-12005] An implementation of display-resample that works in tandem with VRR displays?
- Link: https://github.com/mpv-player/mpv/issues/12005  State: wontfix
- Mechanism: Conventional display-resample changes media speed and audio pitch to match a fixed display clock. A VRR-aware mode instead drives the display cadence from content and can avoid repeats or drops without continuously changing playback speed.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/VideoRenderer.kt, vsyncIntervalNanos; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, masterClockKind and runVideoSchedule
- Verdict: MISSING-FEATURE
- Why: The renderer contract exposes only an advisory fixed vsync interval, current Android and Metal renderers return no interval, and the scheduler has no VRR controller. Adding this would improve cadence and power use on adaptive-refresh displays.
- Severity if real: P2 quality/perf

### [MPV-14141] Audio and Video go out of sync when using video-sync=display-resample
- Link: https://github.com/mpv-player/mpv/issues/14141  State: closed-fixed
- Mechanism: Feeding measured D3D11 presentation intervals into display-resample introduced a small refresh estimate error. Continuous speed correction turned that error into cumulative A/V drift after several minutes.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, masterClockKind and runVideoSchedule; kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/AndroidSurfaceVideoRenderer.kt, vsyncIntervalNanos; kiteplayer-output/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/output/MetalVideoRenderer.kt, vsyncIntervalNanos
- Verdict: IMMUNE
- Why: KitePlayer does not feed measured display intervals back into playback speed. Audio is the normal master clock, and both production renderers leave the optional vsync interval unavailable, so the cited accumulating display-resample feedback loop is absent.
- Severity if real: P1 broken feature

### [MPV-14969] audio goes silent after skipping/fast forward/fast backward
- Link: https://github.com/mpv-player/mpv/issues/14969  State: open
- Mechanism: Repeated scrubbing on affected streams could make the demux or audio decoder report EOF immediately after the seek target. MPV accepted that as the real stream end, drained the audio output, and left later playback silent.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, seekToKeyframe; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runSeek and handleEof
- Verdict: SUSPECT
- Why: KitePlayer resets and seeks the same FFmpeg demux and decoder path, then accepts decoder EOS when its packet queue drains. It has no post-seek guard that distinguishes a premature audio EOF from the actual duration. The trigger is repeated precise scrubbing on a stream that reproduces the upstream demux result.
- Severity if real: P1 broken feature

### [MPV-3087] Impossible to watch live HLS WebVTT subtitle streams
- Link: https://github.com/mpv-player/mpv/issues/3087  State: wontfix
- Mechanism: Libavformat's HLS path did not deliver intermediate WebVTT segments as timed packets. It waited for the subtitle resource to complete, which makes an unbounded live subtitle rendition unusable.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/MediaItem.kt, SubtitleSource and externalSubtitles; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, parseExternalSubtitle
- Verdict: MISSING-FEATURE
- Why: KitePlayer has no HLS rendition orchestrator or incremental external-subtitle ingestion path, and network or custom-IO `SubtitleSource` inputs are not wired. A future live HLS implementation must publish each timed-text segment without waiting for playlist completion.
- Severity if real: P1 broken feature

### [MPV-4144] Subtitles no longer autoloaded
- Link: https://github.com/mpv-player/mpv/issues/4144  State: wontfix
- Mechanism: Sidecar discovery is constrained by a subtitle extension allowlist. Removing `.txt` from that list stopped otherwise valid subtitle content from being found automatically.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/MediaItem.kt, externalSubtitles; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, parseExternalSubtitles
- Verdict: MISSING-FEATURE
- Why: KitePlayer parses only subtitle sources explicitly attached to `MediaItem`; it has no sidecar discovery or extension policy. Automatic local subtitle discovery would remove manual wiring for common media-library playback.
- Severity if real: P2 quality/perf

### [MPV-18286] gpu_next: In HDR/Dolby Vision, PGS graphic subtitles inherit the video color space and produce obvious color shift.
- Link: https://github.com/mpv-player/mpv/issues/18286  State: open
- Mechanism: MPV tagged an already decoded BGRA subtitle overlay with the HDR video's BT.2020 and PQ or HLG color space. The compositor then transformed SDR subtitle colors as HDR content, producing conspicuous hue shifts.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSubtitleDecoder.kt, KiteCodecSubtitleDecoderFactory.create; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/SubtitleRasterizer.kt, rasterize
- Verdict: MISSING-FEATURE
- Why: KitePlayer does not yet decode PGS, so this exact bad composition cannot occur. Its future bitmap subtitle path must carry an overlay color-space tag independently from the video and normally treat decoded subtitle graphics as SDR.
- Severity if real: P1 broken feature

### [MPV-11580] Finer control of horizontal subtitle position (sub-pos-x)
- Link: https://github.com/mpv-player/mpv/issues/11580  State: open
- Mechanism: A global vertical subtitle position does not help when centered SRT cues need to move around content horizontally. Without an explicit X anchor or horizontal offset, side margins do not move centered text.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/SubtitleRasterizer.kt, rasterize; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerConfig.kt, SubtitleConfig
- Verdict: MISSING-FEATURE
- Why: Rasterization accepts a vertical `position` fraction, font scale, and canvas dimensions, but no global horizontal offset. Adding X positioning would help avoid signs, faces, and embedded on-screen text.
- Severity if real: P3 polish

### [MPV-10783] [ASS Subtitle] Normalize timestamps like Aegisub
- Link: https://github.com/mpv-player/mpv/issues/10783  State: wontfix
- Mechanism: Subtitle authoring tools normalize a video's nonzero first timestamp to zero, while a player using absolute container timestamps can place an external ASS cue on a different frame. Both streams need one consistent timeline origin.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, TimestampMapper; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, parseExternalSubtitle and timeAndPublishCues
- Verdict: IMMUNE
- Why: `TimestampMapper` subtracts the container start exactly once from demuxed audio and video timestamps, while parsed external cues already use the zero-relative media timeline. A nonzero container origin therefore does not shift video away from external subtitle time.
- Severity if real: P2 quality/perf

### [MPV-7408] Extremely slow cache when playing from cifs shares in 0.30, 0.31, 0.32, master
- Link: https://github.com/mpv-player/mpv/issues/7408  State: wontfix
- Mechanism: Removing MPV's byte-stream cache exposed libavformat's many small synchronous reads directly to a high-latency CIFS share, collapsing throughput from tens of megabytes per second to a few hundred kilobytes per second.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/CachingMediaIo.kt, read; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, buildSession
- Verdict: SUSPECT
- Why: `CachingMediaIo` coalesces reader-fed input into 256 KiB reads, but a mounted filesystem path is opened directly by FFmpeg and bypasses that cache. The trigger is passing a high-latency CIFS mount as a normal local path, where the same small-read amplification can recur.
- Severity if real: P2 quality/perf

### [MPV-13393] Loading super slow at fragmented video streaming platforms
- Link: https://github.com/mpv-player/mpv/issues/13393  State: wontfix
- Mechanism: URL extraction was fast, but FFmpeg's HLS demuxer fetched short media fragments sequentially. Server round-trip latency then dominated each fragment, while concurrent fragment fetching achieved several times the throughput.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, buildSession and runDemux; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/MediaItem.kt, MediaItem.uri and MediaItem.io
- Verdict: MISSING-FEATURE
- Why: KitePlayer has no HLS manifest or segment scheduler and therefore no bounded parallel fragment fetch. That capability will matter for high-bitrate playback on fragmented platforms with slow per-request service.
- Severity if real: P2 quality/perf

### [MPV-1774] Subtitles language == Audio language
- Link: https://github.com/mpv-player/mpv/issues/1774  State: wontfix
- Mechanism: Automatic selection needs to compare the selected audio language with subtitle candidates, suppressing same-language subtitles while retaining foreign-language or forced translations.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, pickAudio and pickSubtitle
- Verdict: MISSING-FEATURE
- Why: `pickSubtitle` considers the chosen audio language only in its forced-track branch. A normal preferred or default subtitle can still be auto-selected when its language matches the audio, and no policy option requests otherwise.
- Severity if real: P3 polish

### [MPV-3777] Auto-loaded external audio tracks should not take precedence
- Link: https://github.com/mpv-player/mpv/issues/3777  State: wontfix
- Mechanism: External audio discovery must rank a sidecar as an available alternative without treating its mere presence as user preference over the container's default audio track.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/MediaItem.kt, MediaItem and SubtitleSource; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, pickAudio
- Verdict: MISSING-FEATURE
- Why: `MediaItem` supports external subtitles but has no external audio sources or ranking policy. Sidecar audio would be useful for commentary, alternate dubs, and separate lossless tracks, with the cited precedence rule built in.
- Severity if real: P2 quality/perf

### [MPV-13670] When setting subtitle id with --sid the option becomes ignored after switching subtitles once and then switching files
- Link: https://github.com/mpv-player/mpv/issues/13670  State: open
- Mechanism: A persistent user subtitle-selection intent was overwritten by runtime auto-selection state. After one manual change, advancing to another playlist item no longer honored the originally configured subtitle id.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, handleTrackChanges, runOpen, buildSession, and handleQueueAdvance
- Verdict: SUSPECT
- Why: `runOpen` creates fresh selection state and `buildSession` calls automatic pickers, while queue advance carries no persistent manual track intent into the next item. The trigger is selecting a subtitle manually and then advancing to another item with compatible track identities.
- Severity if real: P2 quality/perf

### [MPV-14388] mpv ignoring 'slang' option set on 'mpv.conf' in favor of external subs
- Link: https://github.com/mpv-player/mpv/issues/14388  State: open
- Mechanism: An auto-loaded external subtitle with absent or weak language metadata was ranked above an embedded subtitle that matched the user's preferred language.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, pickSubtitle and parseExternalSubtitles; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/MediaItem.kt, SubtitleSource.selectImmediately
- Verdict: IMMUNE
- Why: External sources do not enter KitePlayer's embedded-track picker. They displace the container selection only when the caller explicitly sets `selectImmediately`, so an unrequested external file cannot outrank the preferred embedded language through this mechanism.
- Severity if real: P2 quality/perf

### [MPV-4016] match decomposed/precomposed unicode strings with sub-auto=fuzzy
- Link: https://github.com/mpv-player/mpv/issues/4016  State: closed-fixed
- Mechanism: Canonically equivalent Unicode text can have different byte sequences in the media filename and subtitle filename. Fuzzy sidecar matching must normalize both names before comparing them.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/MediaItem.kt, externalSubtitles; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, parseExternalSubtitles
- Verdict: MISSING-FEATURE
- Why: KitePlayer has no automatic sidecar filename search. A future discovery implementation should normalize composed and decomposed Unicode names so matching works on files copied between filesystems with different normalization conventions.
- Severity if real: P2 quality/perf

### [MPV-6405] HDR tonemapping producing subpar results with default config
- Link: https://github.com/mpv-player/mpv/issues/6405  State: closed-fixed
- Mechanism: Tone mapping based on a fixed or poorly estimated source peak can lose saturated detail, while dynamic peak adaptation can also pump visible brightness between scenes. Correct mastering data and a stable scene-aware policy are needed.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/HdrToneMap.kt, mapInPlace and eetfNits; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/VideoFrame.kt, ColorSpaceInfo
- Verdict: SUSPECT
- Why: KitePlayer's software path hard-codes a 1000 nit source peak and carries no mastering-display or content-light metadata. It avoids dynamic-peak pumping, but HDR mastered materially above or below 1000 nits can still be mapped with the wrong knee and lose highlight or color detail. There is no accepted image-quality test for those masters.
- Severity if real: P2 quality/perf

### [MPV-2815] Black crush and color deviations with some ICC profiles
- Link: https://github.com/mpv-player/mpv/issues/2815  State: closed-fixed
- Mechanism: Complex display ICC transfer curves cannot be represented safely by a simple power approximation. A proper profile transform or three-dimensional LUT is needed to preserve near-black detail and hue.
- KitePlayer code checked: kiteplayer-output/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/output/MetalVideoRenderer.kt, render path; kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/AndroidSurfaceVideoRenderer.kt, render path; kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/AndroidGpuImageVideoRenderer.kt, render path
- Verdict: MISSING-FEATURE
- Why: KitePlayer has no ICC-profile loader, profile transform, or display-calibration LUT in either renderer. Color-managed output would benefit calibrated and wide-gamut displays and should not approximate complex profiles with a single gamma.
- Severity if real: P2 quality/perf

### [MPV-4248] Colour in mpv is dimmed compared to QuickTime Player
- Link: https://github.com/mpv-player/mpv/issues/4248  State: wontfix
- Mechanism: Correct Rec.709 decoding alone does not guarantee a match with a color-managed application. The decoded image must also pass through the actual display ICC profile, as QuickTime does through ColorSync.
- KitePlayer code checked: kiteplayer-output/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/output/MetalVideoRenderer.kt, shader and presentation configuration; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/VideoFrame.kt, ColorSpaceInfo
- Verdict: MISSING-FEATURE
- Why: The Metal path handles source matrix and transfer characteristics but does not query ColorSync or apply the active display profile. On a calibrated or non-sRGB display, its output can therefore differ in brightness and color from system-managed playback.
- Severity if real: P2 quality/perf

### [MPV-2823] gapless playback doesn't work with opus
- Link: https://github.com/mpv-player/mpv/issues/2823  State: closed-fixed
- Mechanism: Correct Ogg Opus gapless playback needs both container or codec delay trimming and an audio output that stays continuous across item boundaries. Upstream fixes made FFmpeg propagate Opus trimming correctly, after which mpv could join consecutive files without duplicate or missing samples.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, handleQueueAdvance and runOpen
- Verdict: MISSING-FEATURE
- Why: KitePlayer benefits from current FFmpeg trimming inside one file, but queue advance calls `runOpen`, which tears down the whole session and audio sink before opening the next item. A sample-contiguous handoff would remove the boundary gap for albums split into Opus tracks.
- Severity if real: P2 quality/perf

### [MPV-11739] Vulkan Video Decoding: Usage Guide and FAQ
- Link: https://github.com/mpv-player/mpv/issues/11739  State: closed-fixed
- Mechanism: Vulkan Video decode is a cross-vendor zero-copy path only when the driver exposes the codec extensions, FFmpeg creates compatible Vulkan hardware frames, and the renderer imports their exact coded geometry. The long validation thread fixed scaling-list, plane-alignment, cropping, and AV1-extension problems across those layers.
- KitePlayer code checked: kiteplayer-ffmpeg/src/linuxMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/PlatformDecoderSelection.linux.kt, platformDecoderSelection; kiteplayer-ffmpeg/src/mingwMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/PlatformDecoderSelection.mingw.kt, platformDecoderSelection
- Verdict: MISSING-FEATURE
- Why: Both Linux and Windows explicitly select a null hardware route, so KitePlayer cannot request Vulkan decode or carry Vulkan frames into a matching renderer. The feature would offer one hardware path across AMD, Intel, and NVIDIA where the complete driver stack supports it.
- Severity if real: P2 quality/perf

### [MPV-5237] native 10 bit output for MS Windows 10 1709 and above (or possibly Windows 7 and above)
- Link: https://github.com/mpv-player/mpv/issues/5237  State: closed-fixed
- Mechanism: Decoding ten-bit video is not enough for ten-bit display output. The Windows renderer must detect a capable display and create a DXGI swapchain with a ten-bit format such as RGB10A2 instead of the ordinary eight-bit backbuffer.
- KitePlayer code checked: kiteplayer-output/src/jvmMain/kotlin/io/github/yuroyami/kiteplayer/output/DesktopOutputBackend.kt, videoRenderer; kiteplayer-output/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/output/MetalFrameComposer.kt, targetFormat
- Verdict: MISSING-FEATURE
- Why: The desktop JVM backend supplies no video renderer, and the only native GPU compositor in the library defaults to an eight-bit BGRA target. There is no Windows DXGI swapchain or display-depth negotiation, so native ten-bit Windows output is absent.
- Severity if real: P2 quality/perf

### [MPV-15919] Add the functionality to automatically toggle the WIN HDR switch on and off
- Link: https://github.com/mpv-player/mpv/issues/15919  State: wontfix
- Mechanism: Native HDR playback on Windows may require changing the operating system HDR state when PQ or HLG content starts and restoring the previous state when it ends. The thread documents doing that through Windows display configuration APIs or an external helper, while mpv declined to own the side effect.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/VideoFrame.kt, ColorSpaceInfo.isHdr; kiteplayer-output/src/jvmMain/kotlin/io/github/yuroyami/kiteplayer/output/DesktopOutputBackend.kt, videoRenderer
- Verdict: MISSING-FEATURE
- Why: KitePlayer can identify PQ and HLG frames, but it has no Windows renderer or system display-mode bridge that can toggle and later restore OS HDR state. An opt-in policy would reduce manual display switching for native HDR output.
- Severity if real: P3 polish

### [MPV-7326] Dolby Vision with wrong colors
- Link: https://github.com/mpv-player/mpv/issues/7326  State: closed-fixed
- Mechanism: Treating Dolby Vision as ordinary base-layer HEVC ignores its dynamic RPU metadata and can produce purple or green output. The fixed path obtains Dolby Vision metadata from FFmpeg and lets the GPU color pipeline apply the per-frame mapping.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/VideoFrame.kt, ColorSpaceInfo; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/HdrToneMap.kt, forColorSpaceOrNull and mapInPlace
- Verdict: MISSING-FEATURE
- Why: KitePlayer carries only static matrix, primaries, transfer, range, and chroma location. It neither transports Dolby Vision RPU metadata nor applies its dynamic mapping, so Dolby Vision correctness beyond a usable base layer is absent.
- Severity if real: P1 broken feature

### [MPV-17850] scRGB output clamped to sRGB color gamut when SDR ACM is enabled
- Link: https://github.com/mpv-player/mpv/issues/17850  State: closed-fixed
- Mechanism: A floating-point scRGB swapchain represents wide-gamut colors as out-of-range BT.709 coordinates. The Windows advanced-color path must preserve and tag those values correctly, or desktop color management interprets the surface like ordinary sRGB and clamps the extra gamut.
- KitePlayer code checked: kiteplayer-output/src/jvmMain/kotlin/io/github/yuroyami/kiteplayer/output/DesktopOutputBackend.kt, videoRenderer; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/VideoFrame.kt, ColorSpaceInfo
- Verdict: MISSING-FEATURE
- Why: KitePlayer has no Windows video renderer, floating-point scRGB swapchain, or advanced-color metadata handoff. Wide-gamut output under Windows SDR advanced color therefore has no corresponding implementation.
- Severity if real: P2 quality/perf

### [MPV-967] External audio autoload
- Link: https://github.com/mpv-player/mpv/issues/967  State: closed-fixed
- Mechanism: Sidecar audio needs filename discovery, a ranking rule such as exact-name matching, and language preference so it becomes an available track without displacing a better container default accidentally.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/MediaItem.kt, MediaItem.externalSubtitles; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, pickAudio
- Verdict: MISSING-FEATURE
- Why: `MediaItem` can attach external subtitles but has no external audio source list or discovery path. External commentary, alternate dubs, and separately stored lossless audio cannot be loaded as selectable tracks.
- Severity if real: P2 quality/perf

### [MPV-5793] Reconnect if disconnected on network stream.
- Link: https://github.com/mpv-player/mpv/issues/5793  State: closed-fixed
- Mechanism: Reconnecting HTTP media needs a finite network timeout and protocol-aware retry. Range-capable files can reopen at a byte offset, while unseekable radio streams need streamed reconnect semantics and HLS should not blindly use the same policy.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerConfig.kt, NetworkConfig; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runDemux; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/MediaItem.kt, openOptions
- Verdict: SUSPECT
- Why: The default network configuration has no timeout or retry policy, and `runDemux` converts a terminal null packet to end of stream while a read exception fails the worker. Callers can manually pass FFmpeg protocol options, but an ordinary HTTP item has no automatic recovery. The trigger is a transient disconnect after playback has started.
- Severity if real: P1 broken feature

### [MPV-634] Default buffer/cache too small?
- Link: https://github.com/mpv-player/mpv/issues/634  State: closed-fixed
- Mechanism: With no effective read-ahead, short disk or optical-media stalls reach the audio output directly and cause skips. Reporters eliminated the problem by reserving a larger RAM cache between the slow source and playback.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerConfig.kt, BufferPolicy and IoCachePolicy; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runDemux and overBudget
- Verdict: IMMUNE
- Why: The demux worker reads ahead for every source until the selected packet queues hold 32 MiB or 30 seconds by default. Custom readers also get a 32 MiB byte window with 256 KiB upstream pulls, so the exact shallow or absent default-cache mechanism is guarded.
- Severity if real: P2 quality/perf

### [MPV-2357] Glitch of showing asian language subtitles on slower machines
- Link: https://github.com/mpv-player/mpv/issues/2357  State: wontfix
- Mechanism: The first CJK subtitle can make CoreText load and construct a large fallback font set. On a slow disk that cold operation takes hundreds of milliseconds and blocks playback if subtitle layout runs on the playback thread.
- KitePlayer code checked: kiteplayer-output/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/output/AppleSubtitleRasterizer.kt, rasterizeText; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, publishOverlay
- Verdict: IMMUNE
- Why: KitePlayer also uses CoreText and may show the first cue late, but `publishOverlay` cancels superseded work and launches rasterization on the dedicated `dispatchers.raster` lane. Font fallback cannot block the actor, audio feeder, or video scheduler through this mechanism.
- Severity if real: P2 quality/perf

### [MPV-12076] Reading into cache leads to 100% cpu usage on 1 core (glibc bug)
- Link: https://github.com/mpv-player/mpv/issues/12076  State: closed-fixed
- Mechanism: A glibc 2.38 regression made repeated aligned allocations used by cache reads consume a full CPU core. Replacing that allocator avoided the spike, and the subsequent glibc maintenance release fixed the regression.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/CachingMediaIo.kt, read and append
- Verdict: IMMUNE
- Why: KitePlayer's byte cache stores ordinary Kotlin `ByteArray` chunks and never calls glibc `posix_memalign` or an equivalent aligned allocator per read. The allocator path responsible for the upstream cache spin is absent.
- Severity if real: P2 quality/perf

### [MPV-5978] Neither CUDA nor NVDEC will enable on Windows
- Link: https://github.com/mpv-player/mpv/issues/5978  State: closed-fixed
- Mechanism: FFmpeg's NVIDIA hardware path was compiled against mismatched or incomplete nv-codec-headers, so required decode symbols were missing at runtime. Updating the headers and rebuilding restored CUDA or NVDEC initialization.
- KitePlayer code checked: kiteplayer-ffmpeg/src/mingwMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/PlatformDecoderSelection.mingw.kt, platformDecoderSelection
- Verdict: MISSING-FEATURE
- Why: KitePlayer does not offer any Windows hardware decoder route, including CUDA and NVDEC, so this exact initialization failure cannot occur. Adding a measured NVIDIA route would reduce CPU load and power use on Windows, with header and runtime compatibility checks from this issue.
- Severity if real: P2 quality/perf

### [MPV-8884] meson-vdec: poor playback of h264
- Link: https://github.com/mpv-player/mpv/issues/8884  State: wontfix
- Mechanism: The Amlogic V4L2 decoder stack depended on an immature stateful kernel driver, closed firmware, FFmpeg integration, and AFBC surfaces for enough 4K memory bandwidth. Missing pieces caused artifacts, crashes, or slow playback outside mpv's control.
- KitePlayer code checked: kiteplayer-ffmpeg/src/linuxMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/PlatformDecoderSelection.linux.kt, platformDecoderSelection
- Verdict: IMMUNE
- Why: Linux explicitly has no hardware route and therefore cannot enter the Meson V4L2 decoder or its firmware and surface path. Software playback may be too slow on the same device, but that is a different mechanism.
- Severity if real: P2 quality/perf

### [MPV-2280] Will it support 360 degree videos?
- Link: https://github.com/mpv-player/mpv/issues/2280  State: wontfix
- Mechanism: Equirectangular and cubemap video need a nonlinear projection stage that samples a flat texture onto a navigable view with yaw, pitch, field of view, and edge wrapping. Ordinary flat-quad scaling and rotation cannot produce that view.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/VideoTransform.kt, VideoTransform; kiteplayer-output/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/output/MetalVideoSupport.kt, quadUniformsFor
- Verdict: MISSING-FEATURE
- Why: KitePlayer's transform supports aspect override, zoom, and linear pan on a flat quad only. A projection-aware renderer would make 360-degree and VR source videos navigable.
- Severity if real: P2 quality/perf

### [MPV-11050] VAAPI can support video engine based rotation, flip, crop, scaling now. MPV should use hardware acceleration to handle these jobs.
- Link: https://github.com/mpv-player/mpv/issues/11050  State: wontfix
- Mechanism: VAAPI video processing can perform rotation, flipping, cropping, scaling, and color conversion in a low-power fixed-function block. It trades the GPU renderer's higher-quality filters for lower memory traffic and power use.
- KitePlayer code checked: kiteplayer-ffmpeg/src/linuxMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/PlatformDecoderSelection.linux.kt, platformDecoderSelection; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/VideoTransform.kt, VideoTransform
- Verdict: MISSING-FEATURE
- Why: KitePlayer has no Linux VAAPI route or video-processing pipeline, and its transform contract is consumed by ordinary renderers. A low-power fixed-function option would help constrained Linux devices where efficiency matters more than scaling quality.
- Severity if real: P2 quality/perf

### [MPV-3405] Apply Replaygain from tags of individual tracks in multitrack media files
- Link: https://github.com/mpv-player/mpv/issues/3405  State: closed-fixed
- Mechanism: ReplayGain can live on each audio stream rather than only in container-level metadata. Selection must read the chosen stream's own track or album tags, with a defined fallback when one gain kind is absent.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, toPlayerStream and metadata; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/GainStage.kt, apply
- Verdict: MISSING-FEATURE
- Why: `PlayerStreamInfo` receives language, title, disposition, and audio format facts but no per-stream metadata, and `GainStage` applies only caller volume and mute. Multitrack files therefore cannot normalize the selected track from its own ReplayGain tags.
- Severity if real: P2 quality/perf

### [MPV-7341] utilise macOS HDR/EDR functionality
- Link: https://github.com/mpv-player/mpv/issues/7341  State: closed-fixed
- Mechanism: Native macOS HDR output requires an extended-range render target plus correct colorspace and HDR metadata hints so the window server grants EDR headroom. Rendering HDR values into an ordinary SDR layer instead forces clipping or tone mapping.
- KitePlayer code checked: kiteplayer-output/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/output/MetalVideoRenderer.kt, drawPending; kiteplayer-output/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/output/MetalFrameComposer.kt, targetFormat
- Verdict: MISSING-FEATURE
- Why: The Metal renderer always calls the HDR-to-SDR path and the compositor defaults to an eight-bit BGRA target. It does not configure an EDR layer colorspace, extended-range format, or mastering metadata for native macOS HDR output.
- Severity if real: P2 quality/perf

### [MPV-8249] Separate --target-peak-sdr for SDR content
- Link: https://github.com/mpv-player/mpv/issues/8249  State: open
- Mechanism: An HDR display target peak and the reference white used for SDR content are different controls. Reusing one value makes SDR video and subtitles too bright or dim when they are composited into an HDR output surface.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/HdrToneMap.kt, mapInPlace and eetfNits; kiteplayer-output/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/output/MetalVideoSupport.kt, packToneUniforms
- Verdict: MISSING-FEATURE
- Why: Both CPU and Metal tone mapping use a fixed 203-nit SDR reference and expose no separate reference-white or display-peak controls. Such controls will be needed when native HDR output and SDR overlay composition are added.
- Severity if real: P2 quality/perf

### [MPV-2572] HDR video / SMPTE-ST-2084 support
- Link: https://github.com/mpv-player/mpv/issues/2572  State: closed-fixed
- Mechanism: Automatic HDR handling depends on the demuxer mapping Matroska or WebM color elements to BT.2020 primaries and the SMPTE ST 2084 transfer instead of leaving the stream untagged. The renderer can then select its HDR path without manual overrides.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/Conversions.kt, toPlayerColorSpace; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/HdrToneMap.kt, forColorSpaceOrNull
- Verdict: IMMUNE
- Why: KitePlayer maps current FFmpeg color metadata into explicit BT.2020 and PQ values, and `forColorSpaceOrNull` automatically selects tone mapping for PQ or HLG frames. It uses FFmpeg n8.0, long after the cited Matroska metadata support landed.
- Severity if real: P1 broken feature

### [MPV-11862] dither-depth=auto is broken on vo=gpu-next.
- Link: https://github.com/mpv-player/mpv/issues/11862  State: closed-fixed
- Mechanism: Automatic dithering needs the real display target bit depth. When the Vulkan output API could not report it, mpv assumed eight bits and applied the wrong dither quantization to a ten-bit display until output-depth detection was added.
- KitePlayer code checked: kiteplayer-output/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/output/MetalFrameComposer.kt, targetFormat; kiteplayer-output/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/output/MetalVideoSupport.kt, packQualityUniforms; kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/AndroidGpuImageVideoRenderer.kt, setRenderQuality
- Verdict: IMMUNE
- Why: KitePlayer's shipping GPU composition targets are explicitly eight-bit, and both Metal and Android calculate one dither step for that known target. It lacks native ten-bit output, but it cannot misdetect a ten-bit target as eight-bit through the cited auto-depth mechanism.
- Severity if real: P2 quality/perf

### [MPV-9800] Tone mapping: improve handling of very bright scenes/movies
- Link: https://github.com/mpv-player/mpv/issues/9800  State: closed-fixed
- Mechanism: A too-narrow spline knee clipped very bright highlights, and a later dynamic-peak path retained the wrong scene peak through a cut. Widening the knee and correcting scene-change hysteresis preserved highlight detail without adapting from stale measurements.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/HdrToneMap.kt, mapInPlace and eetfNits
- Verdict: IMMUNE
- Why: KitePlayer uses a fixed analytic BT.2390 curve and has no histogram, dynamic peak, or scene-change state. The exact stale-scene adaptation bug cannot occur, while the separate quality risk from its fixed 1000-nit assumption is tracked under MPV-6405.
- Severity if real: P2 quality/perf

### [MPV-10558] under gpu-next mapping SDR content into PQ makes the image look washed out and raises the black point
- Link: https://github.com/mpv-player/mpv/issues/10558  State: closed-fixed
- Mechanism: Inverse tone mapping modeled SDR's BT.1886 black as nonzero and then encoded it into PQ, visibly lifting black and washing out the image. The corrected path preserved the intended black point while mapping SDR into an HDR output space.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/HdrToneMap.kt, forColorSpaceOrNull and mapInPlace; kiteplayer-output/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/output/MetalVideoSupport.kt, packToneUniforms
- Verdict: IMMUNE
- Why: KitePlayer only maps PQ or HLG down to SDR; `forColorSpaceOrNull` returns null for SDR. It has no SDR-to-PQ inverse tone-mapping path in which this black-point error can fire.
- Severity if real: P2 quality/perf

### [MPV-3073] Matroska hard linking (NextUID/PrevUID) unsupported
- Link: https://github.com/mpv-player/mpv/issues/3073  State: wontfix
- Mechanism: Matroska hard links use `PrevUID` and `NextUID` to join separate segment files into one logical timeline. A player must resolve those UIDs, open neighboring files, and translate their timestamps and chapters across the segment graph.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, chapters and toPlayerStream; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, buildSession
- Verdict: MISSING-FEATURE
- Why: KitePlayer exposes chapters from one source but carries no Matroska segment UIDs, neighboring-source graph, or joined timeline. Supporting hard-linked disc splits would make them play and seek as one title.
- Severity if real: P2 quality/perf

### [MPV-4736] Hardware decoding fails on HEVC encoded video even though FFmpeg supports it (videotoolbox)
- Link: https://github.com/mpv-player/mpv/issues/4736  State: closed-fixed
- Mechanism: FFmpeg's early VideoToolbox HEVC path omitted prefix and suffix SEI NAL units while assembling the hardware bitstream, which made otherwise valid streams corrupt or fail. The fix preserved those NAL units before submitting the access unit.
- KitePlayer code checked: kiteplayer-ffmpeg/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/PlatformDecoderSelection.apple.kt, videoToolboxRoute; gradle/libs.versions.toml, kitecodec dependency
- Verdict: IMMUNE
- Why: KitePlayer selects VideoToolbox for HEVC through KiteCodec 0.1.4, whose embedded FFmpeg n8.0 is many releases newer than the cited HEVC SEI fix. The obsolete bitstream assembly code is not in its dependency.
- Severity if real: P1 broken feature

### [MPV-819] mpv doesn't reach the end of a video file when seeking
- Link: https://github.com/mpv-player/mpv/issues/819  State: closed-fixed
- Mechanism: Seeking to or beyond a nominal duration cannot directly identify the last displayed frame because that timestamp is past the final frame start. MPV fixed keep-open behavior by seeking near the end and decoding through EOF to locate and retain the actual last frame.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/SeekRequest.kt, resolve; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runSeek and presentFirstFrame
- Verdict: SUSPECT
- Why: `resolve` clamps an overshoot to the exact duration. If decode finds no landing there, `runSeek` accepts drained EOF and publishes the target without an explicit search for the final frame, leaving the renderer's previously retained picture. The trigger is a paused or ended player seeking past the end when the expected result is the last frame.
- Severity if real: P1 broken feature

### [MPV-11060] Add support for NVIDIA Optical Flow Accelerator (NVOFA) FRUC
- Link: https://github.com/mpv-player/mpv/issues/11060  State: open
- Mechanism: NVIDIA's optical-flow accelerator can synthesize intermediate frames in hardware. A player must import the decoded surfaces into the FRUC API, choose a target cadence, and schedule the returned frames between the originals.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/RenderQuality.kt, RenderQuality and VideoScaler; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/VideoPlayback.kt, tick
- Verdict: MISSING-FEATURE
- Why: KitePlayer offers spatial scaling, debanding, and ordered dithering, while `tick` can only present, hold, repeat, or drop decoded frames. It has no optical-flow import, interpolation stage, or synthesized-frame ownership path. Hardware frame-rate conversion would improve motion on high-refresh displays.
- Severity if real: P2 quality/perf

### [MPV-6137] Frame doubling (to better support FreeSync/G-Sync)
- Link: https://github.com/mpv-player/mpv/issues/6137  State: open
- Mechanism: Adaptive-sync displays without low-frame-rate compensation fall out of their variable-refresh range on 24 fps video. Repeating every source frame an integer number of times raises the presentation cadence, such as 24 fps to 48 fps, without changing media speed.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/VideoPlayback.kt, tick; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/VideoRenderer.kt, vsyncIntervalNanos
- Verdict: MISSING-FEATURE
- Why: `tick` follows source timestamps and its repeat action extends a frame in media time rather than submitting it on several display refreshes. All shipping renderers report no refresh interval, so the engine cannot choose a safe integer multiplier for a variable-refresh window.
- Severity if real: P2 quality/perf

### [MPV-6797] Playback mode that automatically skips silent parts.
- Link: https://github.com/mpv-player/mpv/issues/6797  State: open
- Mechanism: Silence-skipping playback needs a detector over decoded samples, a threshold and minimum-duration policy, and controlled timeline jumps across silent spans. The audio and video clocks must be moved together so the skip does not create drift.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/AudioPipeline.kt, process; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runSeek
- Verdict: MISSING-FEATURE
- Why: The audio pipeline mixes channels, resamples, changes tempo, and applies gain, but never measures silence or emits skip spans. PlaybackCore only seeks on an explicit command or loop transition. Automatic silence skipping would be useful for lectures and recordings with long pauses.
- Severity if real: P2 quality/perf

### [MPV-1945] Facilitate HDMI frame packing for 3D output
- Link: https://github.com/mpv-player/mpv/issues/1945  State: open
- Mechanism: HDMI 1.4 frame-packed 3D places two 1920 by 1080 eye images in a 1920 by 2205 output with a 45-line blank interval, then selects that display mode so the television enters 3D mode. The renderer must preserve the exact packing geometry and restore the prior display mode afterward.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/VideoTransform.kt, VideoTransform; kiteplayer-output/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/output/FrameLayout.kt, frameLayout
- Verdict: MISSING-FEATURE
- Why: KitePlayer can fit, zoom, pan, and rotate one flat picture, but it has no stereo layout metadata, two-eye packing pass, blanking interval, or display-mode controller. Native frame-packed output would let compatible televisions switch into 3D automatically.
- Severity if real: P2 quality/perf

### [MPV-2583] Facilitate 3D frame sequential/alternating frames for 3D output
- Link: https://github.com/mpv-player/mpv/issues/2583  State: open
- Mechanism: Frame-sequential 3D alternates left-eye and right-eye pictures at a high display cadence, commonly 120 Hz. Correct output needs stereo-eye identification, a frame alternator, and presentation timing locked to the display refresh.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/VideoPlayback.kt, tick; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/VideoRenderer.kt, present and vsyncIntervalNanos
- Verdict: MISSING-FEATURE
- Why: Video frames carry no eye identity and the schedule submits one decoded frame per media timestamp. Shipping renderers do not report a refresh interval, so KitePlayer has neither the stereo alternator nor the display cadence contract needed by sequential 3D projectors.
- Severity if real: P2 quality/perf

### [MPV-4460] Total time of files in playlist
- Link: https://github.com/mpv-player/mpv/issues/4460  State: open
- Mechanism: Reporting the duration of every playlist entry requires probing noncurrent items and retaining their metadata without replacing the active playback session. Summing only the current item's duration cannot answer a queue-wide runtime query.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerState.kt, PlayerSnapshot; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, openQueue and publishSnapshot
- Verdict: MISSING-FEATURE
- Why: `PlayerSnapshot` publishes one current duration and a queue of bare `MediaItem` values. PlaybackCore opens only the selected queue entry and stores no per-entry duration or probe result, so a UI cannot show total queue time without opening every item itself.
- Severity if real: P3 polish

### [MPV-12222] Support for cropping videos with hardware decoding enabled
- Link: https://github.com/mpv-player/mpv/issues/12222  State: closed-fixed
- Mechanism: Cropping after hardware decode does not require downloading the decoded surface to the CPU. MPV added renderer-level crop coordinates, so the GPU samples only the requested rectangle while the hardware frame remains on the device.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/MediaItem.kt, videoFilter; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/VideoTransform.kt, VideoTransform
- Verdict: MISSING-FEATURE
- Why: A `crop` video filter forces KitePlayer onto software frames, and `VideoTransform` has aspect, zoom, and pan but no crop rectangle. A renderer-level crop would preserve zero-copy hardware decode for users removing encoded borders or selecting a region.
- Severity if real: P2 quality/perf

### [MPV-3056] Add support for external cover art
- Link: https://github.com/mpv-player/mpv/issues/3056  State: closed-fixed
- Mechanism: MPV can attach a named image or discover conventional files such as `cover.jpg` and treat that image as the displayed cover-art track for audio playback. The external image must join track selection without being muxed into the audio file.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/MediaItem.kt, MediaItem; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, buildSession
- Verdict: MISSING-FEATURE
- Why: `MediaItem` accepts external subtitle sources but no external image or cover-art source. PlaybackCore only discovers cover art already marked as an attached picture by the container, so folder artwork cannot be displayed without remuxing.
- Severity if real: P3 polish

### [MPV-346] Put subtitles just under movie
- Link: https://github.com/mpv-player/mpv/issues/346  State: closed-fixed
- Mechanism: Subtitle placement can use the actual video rectangle rather than the full output surface. MPV's margin policy can put text in the letterbox area immediately below the picture while still adapting to each video's aspect ratio.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, publishOverlay; kiteplayer-output/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/output/FrameLayout.kt, frameLayout
- Verdict: MISSING-FEATURE
- Why: PlaybackCore rasterizes one overlay in video-display coordinates and exposes a global vertical anchor, but it has no policy that targets the lower edge of the fitted picture or its adjacent margin. Automatic under-picture placement would keep subtitles out of the image without per-title tuning.
- Severity if real: P3 polish

### [MPV-9415] [vo=gpu-next] Add error diffusion dithering
- Link: https://github.com/mpv-player/mpv/issues/9415  State: closed-fixed
- Mechanism: Error-diffusion dithering propagates quantization error into neighboring pixels instead of applying a fixed ordered pattern. The compute pass reduces visible regular patterns and needs a selectable kernel plus target bit-depth awareness.
- KitePlayer code checked: kiteplayer-output/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/output/MetalVideoSupport.kt, kp_dither and packQualityUniforms; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/RenderQuality.kt, RenderQuality
- Verdict: MISSING-FEATURE
- Why: KitePlayer's quality flag selects only its small ordered dither pattern and carries no error-diffusion kernel choice. Error diffusion would improve smooth gradients on renderers with compute support, especially when high-bit-depth video is written to an 8-bit target.
- Severity if real: P2 quality/perf

### [MPV-14989] secondary-sub-scale option?
- Link: https://github.com/mpv-player/mpv/issues/14989  State: closed-fixed
- Mechanism: Two simultaneous subtitle tracks need independent layout state. MPV added a separate scale for the secondary track so bilingual text can use different sizes without changing the primary track.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, handleSubtitles and timeAndPublishCues; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerConfig.kt, SubtitleConfig
- Verdict: MISSING-FEATURE
- Why: KitePlayer owns one selected subtitle lane, one active cue set, and one global scale. It cannot render a secondary track at all, much less scale it independently. Dual subtitles are useful for language learning and accessibility combinations.
- Severity if real: P2 quality/perf

### [MPV-231] Support new VA-API video postprocessing API [was: Hardware deinterlace with VA-API]
- Link: https://github.com/mpv-player/mpv/issues/231  State: closed-fixed
- Mechanism: VA-API video postprocessing performs deinterlacing on device surfaces, avoiding a GPU-to-CPU transfer that would defeat hardware decoding. The implemented filter shares the VA display and preserves generated field-frame timestamps for bob deinterlacing.
- KitePlayer code checked: kiteplayer-ffmpeg/src/linuxMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/PlatformDecoderSelection.linux.kt, platformDecoderSelection; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/RenderQuality.kt, RenderQuality
- Verdict: MISSING-FEATURE
- Why: KitePlayer's Linux build declares no VA-API hardware route and exposes no video-postprocessing or deinterlace control. Hardware deinterlacing would lower CPU use and avoid copies on Linux systems that can scan out VA surfaces.
- Severity if real: P2 quality/perf

### [MPV-8569] PipeWire support
- Link: https://github.com/mpv-player/mpv/issues/8569  State: closed-fixed
- Mechanism: A native PipeWire audio output removes PulseAudio compatibility layers from the playback path and talks directly to PipeWire's stream and timing API. This avoids inheriting compatibility-server stutter and exposes native buffering and device behavior.
- KitePlayer code checked: kiteplayer-output/src/jvmMain/kotlin/io/github/yuroyami/kiteplayer/output/DesktopAudioSink.kt, open; kiteplayer-output/src/jvmMain/kotlin/io/github/yuroyami/kiteplayer/output/SourceDataLineDriver.kt, PlatformSourceDataLineDriver
- Verdict: MISSING-FEATURE
- Why: Desktop audio always uses `javax.sound.sampled.SourceDataLine`, with no PipeWire driver, node selection, or native latency contract. A direct sink would give Linux applications a predictable modern audio path rather than relying on the JDK mixer and its host bridge.
- Severity if real: P2 quality/perf

### [MPV-3373] Support SOCKS5 proxies
- Link: https://github.com/mpv-player/mpv/issues/3373  State: wontfix
- Mechanism: FFmpeg's HTTP protocol has no SOCKS proxy support, while piping bytes through a separate SOCKS-aware downloader makes the stream forward-only and loses seeking. A seekable solution must let the proxy-aware client issue ranged requests itself.
- KitePlayer code checked: kiteplayer-network/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/network/KtorMediaIo.kt, KtorMediaIoResolver.resolve and KtorMediaIo.seek; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/MediaItem.kt, io
- Verdict: IMMUNE
- Why: KitePlayer is not locked to FFmpeg networking. An application can inject a SOCKS-configured `HttpClient` into `KtorMediaIoResolver`, or provide its own `MediaItem.io`; `KtorMediaIo` preserves seeking with ranged GETs. The architecture therefore avoids the unseekable-pipe limitation even though proxy policy stays with the caller.
- Severity if real: P1 broken feature

### [MPV-12071] d3d12va hwdec and d3d12 gpu-context
- Link: https://github.com/mpv-player/mpv/issues/12071  State: wontfix
- Mechanism: A D3D12 video path needs a D3D12VA decoder device, hardware-frame allocation, synchronization, and direct interop with a D3D12 render context. Adding only the decoder name is insufficient because decoded surfaces must reach presentation without a readback.
- KitePlayer code checked: kiteplayer-ffmpeg/src/mingwMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/PlatformDecoderSelection.mingw.kt, platformDecoderSelection; kiteplayer-output/src/jvmMain/kotlin/io/github/yuroyami/kiteplayer/output/DesktopOutputBackend.kt, videoRenderer
- Verdict: MISSING-FEATURE
- Why: The Windows FFmpeg selection declares no hardware route and the default desktop output supplies no video renderer. KitePlayer has no D3D12 device, surface-import, or presentation layer, so efficient Windows hardware decode remains absent.
- Severity if real: P2 quality/perf

### [MPV-9818] Add scalable linear light texture for shaders
- Link: https://github.com/mpv-player/mpv/issues/9818  State: open
- Mechanism: A custom scaler that expects linear light must receive a texture after the player's authoritative transfer-function conversion, then return it before output color conversion. Letting a shader guess that the texture is sRGB causes artifacts when the real working color space differs.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/RenderQuality.kt, linearLight and VideoScaler; kiteplayer-output/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/output/MetalVideoRenderer.kt, qualityUniformsFor
- Verdict: MISSING-FEATURE
- Why: KitePlayer exposes only two built-in spatial kernels, and its `linearLight` field documents a future intermediate rather than a custom shader texture contract. Applications cannot insert a scaler at a known linear-light stage or hand its result back for output conversion.
- Severity if real: P2 quality/perf

### [MPV-8137] Shader stage expansion for temporal shaders/algorithms?
- Link: https://github.com/mpv-player/mpv/issues/8137  State: open
- Mechanism: Temporal shaders need decoded motion vectors, access to earlier or later frame planes, and textures whose lifetime spans presentations. Those inputs enable multi-image super-resolution, temporal denoising, and learned interpolation that a one-frame shader cannot implement.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/VideoPlayback.kt, tick and present; kiteplayer-output/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/output/MetalVideoRenderer.kt, pending and drawPending
- Verdict: MISSING-FEATURE
- Why: The scheduler retains only timing metadata after handing off a frame, and the Metal renderer owns a newest-frame slot with no public history, motion-vector plane, or persistent custom texture. Temporal video algorithms have no safe data or ownership contract to plug into.
- Severity if real: P2 quality/perf

### [MPV-8910] Support for AVSampleBufferDisplayLayer format (iOS)
- Link: https://github.com/mpv-player/mpv/issues/8910  State: open
- Mechanism: Native iOS sample-buffer presentation requires wrapping decoded image buffers and timing into `CMSampleBuffer` values accepted by `AVSampleBufferDisplayLayer`. That path avoids deprecated OpenGL embedding and lets AVFoundation own display scheduling.
- KitePlayer code checked: kiteplayer-output/src/iosMain/kotlin/io/github/yuroyami/kiteplayer/output/UIKitVideoRenderer.kt, UIKitVideoRenderer and present; kiteplayer-output/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/output/MetalVideoRenderer.kt, MetalVideoRenderer
- Verdict: MISSING-FEATURE
- Why: KitePlayer can convert frames into `CGImage` values for a `CALayer` or draw them through Metal, but it has no `CMSampleBuffer` adapter or `AVSampleBufferDisplayLayer` renderer. That missing output option matters to applications built around AVFoundation layers.
- Severity if real: P2 quality/perf

### [MPV-2797] vaapi rotation
- Link: https://github.com/mpv-player/mpv/issues/2797  State: open
- Mechanism: VA-API can rotate a hardware surface through `VADisplayAttribRotation`, but 90-degree and 270-degree turns also swap the output width and height. The postprocessing and presentation geometry must agree on both the rotation attribute and the transposed dimensions.
- KitePlayer code checked: kiteplayer-ffmpeg/src/linuxMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/PlatformDecoderSelection.linux.kt, platformDecoderSelection; kiteplayer-output/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/output/FrameLayout.kt, frameLayout
- Verdict: MISSING-FEATURE
- Why: KitePlayer has no Linux VA-API decode or postprocessing route. Its software-frame renderers understand rotation, but there is no fixed-function hardware rotation path that could keep a Linux frame on the device.
- Severity if real: P2 quality/perf

### [MPV-11379] vo_dmabuf_wayland: Support for buffer pre-rotation
- Link: https://github.com/mpv-player/mpv/issues/11379  State: open
- Mechanism: A Wayland compositor can advertise an output transform. If the client pre-rotates the buffer and sets the matching `wl_surface` transform hint, the compositor can often scan the buffer out on a hardware plane instead of spending power on another composition pass.
- KitePlayer code checked: kiteplayer-output/src/jvmMain/kotlin/io/github/yuroyami/kiteplayer/output/DesktopOutputBackend.kt, videoRenderer; kiteplayer-ffmpeg/src/linuxMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/PlatformDecoderSelection.linux.kt, platformDecoderSelection
- Verdict: MISSING-FEATURE
- Why: The desktop JVM backend supplies no native Wayland renderer and the Linux FFmpeg build supplies no hardware surfaces. KitePlayer therefore has no output-transform event, buffer pre-rotation, dmabuf export, or `wl_surface` hint path.
- Severity if real: P2 quality/perf

### [MPV-16593] set_property sub-text
- Link: https://github.com/mpv-player/mpv/issues/16593  State: open
- Mechanism: Editing the currently displayed subtitle needs a mutable cue layer between decode and rasterization. Writing a read-only text projection cannot change the decoder's stored event, so the next render restores the original cue unless an explicit override is retained.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, timeAndPublishCues and publishOverlay; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/KitePlayer.kt, addExternalSubtitle
- Verdict: MISSING-FEATURE
- Why: KitePlayer publishes cues directly from the selected decoder and exposes timing, scale, and position controls, but no cue-text transform or live override API. Applications cannot correct typography, translate, or redact an active cue without replacing an entire external track.
- Severity if real: P3 polish

### [MPV-18081] New add-subtitle command for inserting individual subtitle lines in memory
- Link: https://github.com/mpv-player/mpv/issues/18081  State: open
- Mechanism: On-the-fly speech recognition needs a mutable in-memory subtitle track whose cues can be inserted, edited, removed, and cleared without rewriting and reloading a file. Each change must enter the same timed-cue and rasterization path as demuxed subtitles.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/KitePlayer.kt, addExternalSubtitle; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, handleSubtitles and timeAndPublishCues
- Verdict: MISSING-FEATURE
- Why: KitePlayer can add a complete local subtitle file, but it exposes no synthetic mutable track or individual cue operations. Live transcription clients must currently build an out-of-band overlay because they cannot feed recognized lines into the player timeline.
- Severity if real: P2 quality/perf

### [MPV-6437] Buffer next videos in the playlist
- Link: https://github.com/mpv-player/mpv/issues/6437  State: open
- Mechanism: Queue prebuffering opens the next item's network source and fills its byte or packet cache while the current item still plays. The prefetched session must remain isolated so its timestamps, tracks, and decoder state cannot alter the current item.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, openQueue, handleEof, and buildSession; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerState.kt, PlayerSnapshot.queue
- Verdict: MISSING-FEATURE
- Why: PlaybackCore stores queue items but calls `buildSession` for only the current item, then opens the next after EOF. It owns no standby source or cache, so network queues can pause between entries even when bandwidth was idle beforehand.
- Severity if real: P2 quality/perf

### [MPV-14642] Watch-later using m3u
- Link: https://github.com/mpv-player/mpv/issues/14642  State: open
- Mechanism: Portable watch-later state stores each item's position and playback settings in a playlist format rather than one opaque hash-named file per item. A player needs a persistence schema, stable item identity, and restore rules separate from the live queue.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerState.kt, PlayerSnapshot; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/MediaItem.kt, MediaItem
- Verdict: MISSING-FEATURE
- Why: KitePlayer publishes live queue, position, speed, and volume state but owns no watch-later store, serializer, or restore hook. Applications must design persistence themselves, so there is no portable audiobook or playlist-resume format at the engine layer.
- Severity if real: P3 polish

### [MPV-12948] Proposal: set `hwdec` to `auto-safe` by default
- Link: https://github.com/mpv-player/mpv/issues/12948  State: open
- Mechanism: Safe automatic hardware decoding enables only explicitly trusted decoder routes, then falls back to software when a route is absent or fails. That obtains power and performance benefits without probing every hardware API indiscriminately.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerConfig.kt, hardwareDecode and HwdecPolicy.Auto; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/PlatformDecoderSelection.kt, decoderSelection
- Verdict: IMMUNE
- Why: KitePlayer already defaults to `HwdecPolicy.Auto`. Each platform first maps a supported codec to one declared route, and the shared selection table tries only that route with software fallback; it never scans arbitrary hardware decoders. That is the proposed safe-default mechanism.
- Severity if real: P2 quality/perf

### [MPV-1272] Synchronized playback over a network
- Link: https://github.com/mpv-player/mpv/issues/1272  State: open
- Mechanism: A master player sends its current file position in a UDP datagram before each frame. Slave players compare that position with their own clocks and seek or correct drift when it exceeds a threshold, allowing several low-latency LAN displays to remain aligned.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerState.kt, SyncMode.ExternalMaster; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, masterClockKind
- Verdict: MISSING-FEATURE
- Why: KitePlayer declares `ExternalMaster` but documents it as not implemented. PlaybackCore still selects only its local audio or video clock and has no transport, remote-position sample, drift estimator, or correction threshold, so coordinated video walls require an application-owned synchronization layer.
- Severity if real: P2 quality/perf

### [MPV-18068] Feature Request: sr_amf + amf_hqscaler + amf_vqenhance
- Link: https://github.com/mpv-player/mpv/issues/18068  State: open
- Mechanism: FFmpeg's AMF filters keep video on an AMD hardware surface while applying driver super-resolution scaling or video-quality enhancement. A player must create the AMF device context, negotiate compatible hardware frames, expose the filter controls, and preserve the surface chain into rendering.
- KitePlayer code checked: kiteplayer-ffmpeg/src/mingwMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/PlatformDecoderSelection.mingw.kt, platformDecoderSelection
- Verdict: MISSING-FEATURE
- Why: KitePlayer's Windows decoder selection returns no hardware route, so frames enter the software path before rendering. There is no AMF device context, hardware filter graph, or control for `sr_amf` or `vqenhance`, making this post-processing path unavailable.
- Severity if real: P2 quality/perf

### [MPV-10867] Average bitrate
- Link: https://github.com/mpv-player/mpv/issues/10867  State: open
- Mechanism: The player exposes the stable, container-reported bitrate of a video stream instead of a continuously changing network throughput sample. Clients can display the same per-track metadata value used by media inspectors.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/Tracks.kt, TrackInfo.bitrate; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, toPlayerStream
- Verdict: IMMUNE
- Why: `toPlayerStream` copies `StreamInfo.bitrateBps` into the public `TrackInfo.bitrate` field. Applications can therefore read the stable stream bitrate directly without deriving it from instantaneous cache or download statistics.
- Severity if real: P3 polish

### [MPV-2380] input commands like keyframe-next keyframe-prev
- Link: https://github.com/mpv-player/mpv/issues/2380  State: open
- Mechanism: Dedicated next-keyframe and previous-keyframe commands ask the demux index for the adjacent random-access timestamp, then seek exactly to that boundary. This differs from seeking an arbitrary time with a keyframe snapping mode because the adjacent timestamp must first be discovered.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/KitePlayer.kt, seek and seekLater; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runSeek
- Verdict: MISSING-FEATURE
- Why: KitePlayer can seek to a caller-provided timestamp and choose a keyframe seek mode, but it exposes no keyframe index or adjacent-keyframe operation. A client cannot reliably implement frame-analysis navigation without separately parsing the media index.
- Severity if real: P3 polish

### [MPV-12464] Subtitles: Add support for `directory_mode=recursive`
- Link: https://github.com/mpv-player/mpv/issues/12464  State: open
- Mechanism: Subtitle autoload recursively walks configured directories, matches discovered filenames to the current media, and adds the candidates without requiring each file to be named explicitly. The traversal needs bounds and deduplication because a media library may contain deep directory trees.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/MediaItem.kt, externalSubtitles; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, parseExternalSubtitles
- Verdict: MISSING-FEATURE
- Why: PlaybackCore maps only the explicit `externalSubtitles` list supplied on the media item. It has no directory scanner, recursive mode, filename matcher, or duplicate policy, so applications must discover every subtitle before opening the item.
- Severity if real: P3 polish

### [MPV-2398] Allow cycle of audio devices
- Link: https://github.com/mpv-player/mpv/issues/2398  State: open
- Mechanism: The output backend enumerates currently available audio device identifiers and lets a cycle command reopen the sink on the next compatible device. Enumeration must reflect hot-plug changes so configuration does not hardcode a stale list.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/OutputBackend.kt, OutputBackend.audioSink; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/AudioSink.kt, AudioSinkFactory.create
- Verdict: MISSING-FEATURE
- Why: An output backend supplies one immutable `AudioSinkFactory`, whose create operation accepts only an audio format. The public SPI has no device enumeration, device identifier, selection setter, or sink-cycle operation, so changing a live output device is not expressible.
- Severity if real: P2 quality/perf

### [MPV-17140] Support for Nvidia Smooth Motion on Windows 11
- Link: https://github.com/mpv-player/mpv/issues/17140  State: open
- Mechanism: NVIDIA Smooth Motion is a driver frame-generation path that must be explicitly enabled for the player's Windows presentation chain. It synthesizes intermediate frames before presentation to reduce low-frame-rate judder on high-refresh or variable-refresh displays.
- KitePlayer code checked: kiteplayer-ffmpeg/src/mingwMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/PlatformDecoderSelection.mingw.kt, platformDecoderSelection
- Verdict: MISSING-FEATURE
- Why: KitePlayer has no Windows hardware decoder route, NVIDIA device integration, or frame-generation control. Its current Windows media path cannot request Smooth Motion or retain the driver surface and presentation metadata that such integration requires.
- Severity if real: P2 quality/perf

### [MPV-13989] Dual Audio Device support with spdif and no 3rd party apps
- Link: https://github.com/mpv-player/mpv/issues/13989  State: open
- Mechanism: One playback session sends audio to two or more device sinks at once, with at least one sink able to retain encoded S/PDIF passthrough and with optional independent gain. Each sink needs its own buffering and clock accommodation so a slow device does not stall the others.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, buildSession
- Verdict: MISSING-FEATURE
- Why: `buildSession` creates one audio candidate, one sink, one `AudioPlayback`, and one audio lane. There is no output fan-out, passthrough branch, per-device gain, or multi-clock policy, so an application needs an external mixer or virtual device.
- Severity if real: P2 quality/perf

### [MPV-9600] Support Human Readable Chapter Files (The Ones You See Everyday on YouTube)
- Link: https://github.com/mpv-player/mpv/issues/9600  State: open
- Mechanism: The player discovers a sidecar `.chp` or `.chap` file beside the media and parses lines containing a human-readable timestamp followed by a title. Parsed entries join the normal chapter timeline without remuxing the source.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/MediaItem.kt, MediaItem; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerState.kt, Chapter
- Verdict: MISSING-FEATURE
- Why: `MediaItem` has no external chapter source or chapter-file discovery option, and `Chapter` is only the output model for chapters already returned by the media source. No parser accepts the timestamp-title sidecar format.
- Severity if real: P3 polish

### [MPV-15107] HDR screenshots are unimplemented (gpu/gpu-next)
- Link: https://github.com/mpv-player/mpv/issues/15107  State: open
- Mechanism: A rendered screenshot must preserve the renderer's HDR primaries, transfer function, bit depth, and intensity metadata rather than converting the captured image to sRGB. Capturing after the active GPU color pipeline is also necessary when the screenshot is expected to match displayed output.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/CapturedFrame.kt, CapturedFrame.of
- Verdict: MISSING-FEATURE
- Why: KitePlayer explicitly defines capture as a decoded-frame copy before renderer transforms and states that the pixels are not display-oriented or color-managed. It exposes planes and source color metadata but no post-render HDR image capture or HDR image encoder path.
- Severity if real: P2 quality/perf

### [MPV-2084] Ability to extend cache with the cache file
- Link: https://github.com/mpv-player/mpv/issues/2084  State: open
- Mechanism: Once the RAM cache reaches its configured limit, a streaming cache spills older or additional bytes to a file while maintaining a seekable index. The disk tier extends rewind range without keeping the whole stream resident in memory.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/CachingMediaIo.kt, CachingMediaIo; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerConfig.kt, IoCachePolicy
- Verdict: MISSING-FEATURE
- Why: `CachingMediaIo` owns one contiguous in-memory `ArrayDeque` window governed by byte budgets. `IoCachePolicy` provides no cache-file path or disk budget, and the implementation has no spill file or offset index, so evicted streaming data cannot be retained on disk.
- Severity if real: P2 quality/perf

### [MPV-5433] make --sub-auto and --audio-file-auto possible to autoload but do not prefer external tracks
- Link: https://github.com/mpv-player/mpv/issues/5433  State: open
- Mechanism: Automatic sidecar discovery adds external subtitle and audio tracks to the candidate set but ranks them like internal tracks, instead of always preferring any external match. Explicitly supplied sidecars can retain stronger priority than automatically discovered files.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/MediaItem.kt, externalSubtitles; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, parseExternalSubtitles and pickSubtitle
- Verdict: MISSING-FEATURE
- Why: KitePlayer accepts only explicitly listed external subtitles and has no external-audio field, sidecar discovery mode, or explicit-versus-automatic ranking signal. It therefore cannot express autoloading a sidecar while leaving normal language and default-track selection in control.
- Severity if real: P3 polish

### [MPV-4209] Add a "--secondary-slang" option
- Link: https://github.com/mpv-player/mpv/issues/4209  State: open
- Mechanism: A preferred language selects the secondary subtitle lane after tracks are discovered, avoiding dependence on unstable track IDs from online sources. The secondary decoder and renderer then remain timed alongside the primary subtitle.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/Tracks.kt, Tracks; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, pickSubtitle
- Verdict: MISSING-FEATURE
- Why: `Tracks` exposes only one selected subtitle and `pickSubtitle` returns one candidate. Session construction owns one subtitle decoder and overlay path, so there is neither a secondary language preference nor a second subtitle lane to select.
- Severity if real: P2 quality/perf

### [MPV-675] Multiple (different language) audio outputs
- Link: https://github.com/mpv-player/mpv/issues/675  State: open
- Mechanism: The player decodes multiple audio tracks concurrently and routes each track to a different device, letting viewers hear different languages from the same video. Each track needs an independent decoder, sink, buffer, gain, and synchronization relationship to the shared media clock.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, buildSession
- Verdict: MISSING-FEATURE
- Why: Session construction chooses one audio track and creates one audio decoder, lane, playback buffer, and sink. There is no track-to-device routing graph or concurrent audio-lane collection, so this topology cannot be configured.
- Severity if real: P2 quality/perf

### [MPV-10810] Support for libplacebo (gpu-next) in the rendering API
- Link: https://github.com/mpv-player/mpv/issues/10810  State: open
- Mechanism: An embedded rendering API exposes a libplacebo-backed GPU pipeline so hosts can use advanced `gpu-next` color management, scaling, dithering, and frame-mixing features while retaining control of the destination surface.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/VideoRenderer.kt, VideoRenderer; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/RenderQuality.kt, RenderQuality
- Verdict: MISSING-FEATURE
- Why: The SPI hands a final decoded frame to an abstract renderer and offers a fixed set of quality hints. KitePlayer ships no libplacebo render graph, backend selector, shader graph controls, or embedded `gpu-next` equivalent for a host to request.
- Severity if real: P2 quality/perf

### [MPV-6575] Add vulkan output to embedded rendering API (libmpv)
- Link: https://github.com/mpv-player/mpv/issues/6575  State: open
- Mechanism: An embedded player accepts a host-provided Vulkan device, queue, image target, and synchronization primitives, then renders directly into that target without an intermediate window or CPU copy.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/VideoRenderer.kt, VideoRenderer; kiteplayer-output/src/jvmMain/kotlin/io/github/yuroyami/kiteplayer/output/DesktopOutputBackend.kt, videoRenderer
- Verdict: MISSING-FEATURE
- Why: A custom renderer can consume frames, but the shipped desktop backend supplies no video renderer and the SPI has no Vulkan device, image, semaphore, or embedded-surface contract. Applications must build the entire Vulkan integration outside KitePlayer.
- Severity if real: P2 quality/perf

### [MPV-9283] libmpv: Get audio frame?
- Link: https://github.com/mpv-player/mpv/issues/9283  State: open
- Mechanism: An embedding API publishes decoded audio samples, format, channel layout, and timestamps to a client callback or pull queue. This supports visualization, analysis, recording, and application-owned output without scraping an audio device.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, submitDecoded; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/KitePlayer.kt, KitePlayer
- Verdict: MISSING-FEATURE
- Why: `submitDecoded` receives internal `FloatArray` blocks and immediately places them in the private playback ring. The public player has no audio-frame callback, sample capture flow, or pull API, so clients cannot observe decoded audio.
- Severity if real: P2 quality/perf

### [MPV-9989] Give access to previous and next frames in shaders
- Link: https://github.com/mpv-player/mpv/issues/9989  State: open
- Mechanism: Temporal shaders receive neighboring decoded frames and their timestamps, enabling motion-vector estimation, temporal upscaling, denoising, and interpolation. The scheduler must retain frame history and delay presentation enough to make a future frame available.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/VideoPlayback.kt, tick and present; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/RenderQuality.kt, RenderQuality
- Verdict: MISSING-FEATURE
- Why: The scheduler releases one decoded frame at a time and retains timing state rather than a shader-visible frame history. There is no shader API, neighbor-frame bundle, or lookahead contract, so temporal processing cannot be implemented through the renderer controls.
- Severity if real: P2 quality/perf

### [MPV-14235] Support images as chapter marks
- Link: https://github.com/mpv-player/mpv/issues/14235  State: open
- Mechanism: Enhanced podcast containers associate images with chapter entries. At a chapter boundary, the player resolves that image attachment and displays it as the current artwork while keeping the normal chapter title and timing.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerState.kt, Chapter; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, chapters
- Verdict: MISSING-FEATURE
- Why: `Chapter` contains only index, start, end, and title, and the source mapper copies only those fields. Image attachment references and chapter metadata are discarded, with no artwork event at chapter changes.
- Severity if real: P3 polish

### [MPV-15748] Allow interpolation to function as frame-doubling, tripling etc
- Link: https://github.com/mpv-player/mpv/issues/15748  State: open
- Mechanism: Frame interpolation targets an integer multiple of source cadence, such as 24 to 48 or 72 fps, rather than always filling the display's maximum refresh rate. This reduces render cost and avoids uneven cadence while still smoothing low-frame-rate motion on adaptive-sync displays.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/VideoPlayback.kt, tick and present
- Verdict: MISSING-FEATURE
- Why: VideoPlayback schedules decoded frames with hold, drop, and repeat decisions but never synthesizes intermediate images. There is no interpolation engine or target cadence multiplier, so the player cannot perform either display-rate interpolation or integer frame multiplication.
- Severity if real: P2 quality/perf

### [MPV-16751] Dynamic subtitle generation via FFmpeg's new filter
- Link: https://github.com/mpv-player/mpv/issues/16751  State: open
- Mechanism: FFmpeg's Whisper filter transcribes decoded audio during playback and emits timed text that enters the normal subtitle cue and rendering pipeline. A usable player integration needs filter configuration, incremental cue ingestion, latency handling, and timeline resets on seek.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/MediaItem.kt, videoFilter; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, handleSubtitles and timeAndPublishCues
- Verdict: MISSING-FEATURE
- Why: Media items expose a video-frame filter only, with no audio filter graph or Whisper configuration. PlaybackCore consumes cues from a selected subtitle decoder and has no live in-memory cue ingress, so generated transcription cannot join its timed overlay path.
- Severity if real: P2 quality/perf

### [MPV-16936] Chapter language selection
- Link: https://github.com/mpv-player/mpv/issues/16936  State: open
- Mechanism: For containers with several localized names on each chapter, a preferred chapter language chooses the matching title while retaining fallback rules when that language is absent. The demuxer must preserve all localized names rather than flattening them early.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, chapters; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerState.kt, Chapter
- Verdict: MISSING-FEATURE
- Why: The chapter mapper exposes one title string and `Chapter` has no language or alternate-title collection. Localization metadata is not retained, and there is no chapter-language preference to apply.
- Severity if real: P3 polish

### [MPV-14372] Support rotating subtitles along with video
- Link: https://github.com/mpv-player/mpv/issues/14372  State: open
- Mechanism: Subtitle rendering splits dialogue from typesetting so video-attached signs can inherit video rotation while dialogue remains oriented to the screen. The two passes need distinct transforms and stable compositing order.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, publishOverlay; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/VideoRenderer.kt, SubtitleOverlay
- Verdict: MISSING-FEATURE
- Why: PlaybackCore publishes one surface-space overlay containing rasterized images and viewport dimensions. The overlay model has no dialogue-versus-typesetting classification or rotation transform, so all subtitle elements must share one orientation.
- Severity if real: P3 polish

### [MPV-18169] Add a property to get video bit depth
- Link: https://github.com/mpv-player/mpv/issues/18169  State: open
- Mechanism: A stable public property reports bits per color component for the selected video stream, independent of an average bits-per-pixel estimate. The value comes from codec parameters or decoded pixel format and is useful for display, rules, and shader selection.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/Tracks.kt, TrackInfo; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, toPlayerStream
- Verdict: MISSING-FEATURE
- Why: Public `TrackInfo` exposes codec, dimensions, frame rate, bitrate, and color metadata but no generic component bit depth. A typed VP9 codec configuration can carry bit depth internally, yet `toPlayerStream` does not surface a cross-codec value to clients.
- Severity if real: P3 polish

### [MPV-14048] DSD output as DoP
- Link: https://github.com/mpv-player/mpv/issues/14048  State: open
- Mechanism: DSD over PCM packs DSD bits into PCM-looking frames with the required marker bytes so a capable DAC reconstructs the original DSD stream. The output path must preserve those encoded payload bits and negotiate a DoP-capable sink rather than converting them to ordinary PCM samples.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/Decoders.kt, AudioFormat and SampleFormat; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, submitDecoded
- Verdict: MISSING-FEATURE
- Why: KitePlayer supports only signed and floating-point PCM sample formats, and decoded blocks enter a `FloatArray` processing and resampling pipeline. There is no DSD payload type, DoP marker packing, passthrough negotiation, or bit-preserving sink path.
- Severity if real: P2 quality/perf

### [MPV-11390] RTX Video Enhancement support
- Link: https://github.com/mpv-player/mpv/issues/11390  State: closed-fixed
- Mechanism: NVIDIA Video Super Resolution is invoked through the D3D11 video processor on compatible RTX hardware. The player keeps frames on D3D11 surfaces, selects the vendor scaling mode, supplies an enlarged output size, and passes the processed surface to presentation without a CPU download.
- KitePlayer code checked: kiteplayer-ffmpeg/src/mingwMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/PlatformDecoderSelection.mingw.kt, platformDecoderSelection; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/RenderQuality.kt, RenderQuality
- Verdict: MISSING-FEATURE
- Why: The Windows backend declares no hardware decoder route, and render quality offers only built-in bilinear or Catmull-Rom scaling. KitePlayer has no D3D11 video-processor surface chain, NVIDIA scaling mode, or VSR output-size control.
- Severity if real: P2 quality/perf

### [MPV-1124] 3D movie playback with subtitle support
- Link: https://github.com/mpv-player/mpv/issues/1124  State: closed-fixed
- Mechanism: Stereo metadata selects a conversion between side-by-side, top-and-bottom, and mono layouts. For stereo output, the OSD and subtitle image are duplicated and transformed into both eye regions; for 2D output, one eye is cropped and expanded.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/MediaSource.kt, PlayerStreamInfo; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/VideoRenderer.kt, SubtitleOverlay
- Verdict: MISSING-FEATURE
- Why: The stream model carries size, rotation, frame rate, and color but no stereoscopic layout. SubtitleOverlay represents one surface-space image list with no eye mapping, so KitePlayer can neither convert stereo video to mono nor duplicate overlays for a 3D display.
- Severity if real: P2 quality/perf

### [MPV-568] pixel shader support
- Link: https://github.com/mpv-player/mpv/issues/568  State: closed-fixed
- Mechanism: User pixel shaders hook named stages of the GPU render graph, receive source textures and per-frame uniforms, and return an image that continues through scaling and color conversion. Stable resource lifetime and format negotiation let shaders work without modifying the player.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/RenderQuality.kt, RenderQuality; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/VideoRenderer.kt, VideoRenderer
- Verdict: MISSING-FEATURE
- Why: KitePlayer exposes a small built-in quality ladder and an abstract final-frame renderer, but no shader source, hook stage, texture binding, uniform, or custom GPU-pass API. An application must replace the renderer to run even one custom shader.
- Severity if real: P2 quality/perf

### [MPV-3613] render 10 bit video as native 10 bit
- Link: https://github.com/mpv-player/mpv/issues/3613  State: closed-fixed
- Mechanism: The renderer keeps decoded 10-bit samples in a high-precision intermediate and creates a 10-bit or floating-point output surface when the OS and display expose deep color. It dithers only when the final target is actually 8-bit.
- KitePlayer code checked: kiteplayer-output/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/output/MetalVideoRenderer.kt, setRenderQuality; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/RenderQuality.kt, dither
- Verdict: MISSING-FEATURE
- Why: The shipping Metal renderer targets `BGRA8Unorm`, and the quality contract explicitly describes 10-bit inputs being reduced at that 8-bit write. There is no deep-color target negotiation or 10-bit swapchain path, so source precision can be dithered but not presented natively.
- Severity if real: P2 quality/perf

### [MPV-13313] add an argument to select chroma downscaler
- Link: https://github.com/mpv-player/mpv/issues/13313  State: closed-fixed
- Mechanism: When luma is reduced below chroma resolution by a shader or unusual graph, a separate plane-downscaler chooses the kernel used to reduce chroma. Keeping this distinct from chroma upscaling avoids silently reusing a kernel optimized for the opposite direction.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/RenderQuality.kt, RenderQuality and VideoScaler
- Verdict: MISSING-FEATURE
- Why: RenderQuality has one `scaler` for the final picture and no independent luma, chroma-upscale, or chroma-downscale kernel. The plane-specific condition and control exposed upstream cannot be represented.
- Severity if real: P3 polish

### [MPV-8037] mpv ignores matroska video crop metadata
- Link: https://github.com/mpv-player/mpv/issues/8037  State: wontfix
- Mechanism: Matroska's per-edge crop values must survive demux metadata, frame geometry, rendering, screenshots, and hardware decode. Applying an ordinary software crop filter alone is insufficient because hardware surfaces and post-render consumers need the same visible rectangle.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, toPlayerStream; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/MediaSource.kt, PlayerStreamInfo
- Verdict: SUSPECT
- Why: The source mapper copies coded width, height, pixel aspect, and rotation, while PlayerStreamInfo has no crop edges or clean-aperture rectangle. If KiteCodec does not pre-apply Matroska crop to its reported dimensions, the renderer has no metadata with which to remove those pixels; no existing red test proves the end-to-end behavior.
- Severity if real: P2 quality/perf

### [MPV-14923] Please add support for multi-threads libavfilter processing
- Link: https://github.com/mpv-player/mpv/issues/14923  State: wontfix
- Mechanism: A CPU-heavy FFmpeg filter can divide slices or channels among worker threads when that filter advertises threading support. The graph must expose its thread count and type, while filters without slice support remain serial regardless of player settings.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/MediaItem.kt, videoFilter; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/AudioPipeline.kt, AudioPipeline
- Verdict: MISSING-FEATURE
- Why: KitePlayer can attach one raw software video graph at open, but exposes no audio filter graph at all. Its audio pipeline is a fixed serial chain of conversion, mixing, gain, tempo, and resampling, so applications cannot attach or thread the upmix and binaural filters that motivated the issue.
- Severity if real: P2 quality/perf

### [MPV-201] Add per-chapter metadata [was: mkv tags (metadata) not parsed if ChapterUID is specified]
- Link: https://github.com/mpv-player/mpv/issues/201  State: closed-fixed
- Mechanism: Matroska tags targeted at a ChapterUID become active metadata only while that chapter is current, then restore the surrounding metadata at the next boundary. The demuxer must retain the target association rather than flattening all tags globally.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, chapters; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerState.kt, Chapter
- Verdict: MISSING-FEATURE
- Why: KitePlayer maps each chapter to index, bounds, and one title, discarding other chapter-targeted tags and their names. Chapter changes therefore cannot publish per-chapter artist, title, or arbitrary metadata.
- Severity if real: P3 polish

### [MPV-6926] Provide option for large, round-robbin decode buffer.
- Link: https://github.com/mpv-player/mpv/issues/6926  State: closed-fixed
- Mechanism: A bounded decoded-frame queue lets a decoder that is faster on average build enough lead to survive short high-complexity sections. Separate byte, duration, packet, and frame limits prevent a large requested lead from turning into uncontrolled memory use.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerConfig.kt, BufferPolicy; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, buildSession
- Verdict: IMMUNE
- Why: BufferPolicy exposes an arbitrary decoded `videoFrameQueue` capacity plus total compressed-byte and duration budgets. Session construction passes that capacity to VideoPlayback, whose decoder worker fills the queue ahead, providing the same configurable complexity cushion without manufacturing decoder threads.
- Severity if real: P2 quality/perf

### [MPV-173] Seeking shouldn't skip through chapter start/end
- Link: https://github.com/mpv-player/mpv/issues/173  State: wontfix
- Mechanism: A relative seek detects chapter boundaries between the old and requested positions and snaps to the nearest crossed boundary before continuing farther on a later command. A threshold prevents a chapter edge immediately behind the playhead from trapping backward navigation.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/KitePlayer.kt, seek and seekToChapter; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runSeek
- Verdict: MISSING-FEATURE
- Why: KitePlayer offers ordinary timestamp seeks and an explicit chapter-start seek as separate operations. `runSeek` resolves only the requested timestamp and mode, with no chapter-boundary interception or threshold, so repeated coarse seeks can pass over a chapter edge.
- Severity if real: P3 polish

### [MPV-518] [Request] --chapter-skip or --chapter=comma separated list
- Link: https://github.com/mpv-player/mpv/issues/518  State: wontfix
- Mechanism: A chapter-selection policy turns chosen chapter ranges into an edit timeline, automatically jumping across openings, endings, or other excluded spans while preserving playlist continuity. The skip list must be re-evaluated for every item because chapter tables differ.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/KitePlayer.kt, chapterAt and seekToChapter; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, handlePlaybackTime
- Verdict: MISSING-FEATURE
- Why: KitePlayer reports chapter crossings and lets a caller seek to one chapter, but has no included-range list, excluded-chapter policy, or automatic boundary jump. An application must listen for every change and implement its own edit timeline.
- Severity if real: P3 polish

### [MPV-3647] new arbitrary video rotate filter
- Link: https://github.com/mpv-player/mpv/issues/3647  State: closed-fixed
- Mechanism: Arbitrary rotation applies a continuous affine transform, normalizes negative and over-360 values, and optionally scales or crops the result to remove black triangles. Fractional angles cannot be represented as a quarter-turn orientation flag.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/VideoFrame.kt, rotationDegrees; kiteplayer-output/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/output/FrameLayout.kt, quarterTurn
- Verdict: MISSING-FEATURE
- Why: Although frames carry an integer degree value, the shared renderer geometry explicitly draws only 0, 90, 180, and 270 and converts every other angle to 0. No public transform adds user rotation, fractional angles, or crop-to-fill behavior.
- Severity if real: P3 polish

### [MPV-7852] API to render video output to a buffer in memory
- Link: https://github.com/mpv-player/mpv/issues/7852  State: closed-fixed
- Mechanism: A software render backend writes each completed frame into a caller-owned memory buffer in a negotiated pixel format and invokes a callback when that buffer is ready. This supports toolkits that expose image memory but no portable GPU context.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/VideoRenderer.kt, VideoRenderer.present; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/VideoFrame.kt, SoftwareReadableFrame
- Verdict: IMMUNE
- Why: A custom VideoRenderer is already the per-frame callback and receives ownership of each presented frame. For software-readable frames it can copy every plane, with explicit stride and height, into any caller-owned buffer; an application needing that path can select software decode rather than an opaque hardware surface.
- Severity if real: P2 quality/perf

### [MPV-1171] Custom playback positions
- Link: https://github.com/mpv-player/mpv/issues/1171  State: wontfix
- Mechanism: User bookmarks are persistent virtual chapters with a timestamp and description. A player needs create, rename, delete, navigate, serialize, and restore operations that are distinct from the immutable chapter table carried by the media.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerState.kt, Chapter and PlayerSnapshot; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/MediaItem.kt, MediaItem
- Verdict: MISSING-FEATURE
- Why: The snapshot exposes only container chapters and live position, and MediaItem has no bookmark collection or sidecar. KitePlayer owns no bookmark mutation or persistence schema, so every client must build this navigation layer independently.
- Severity if real: P3 polish

### [MPV-7975] Add a command to set multiple ab-loop of the same file
- Link: https://github.com/mpv-player/mpv/issues/7975  State: wontfix
- Mechanism: Multiple named A-B regions form a sequence or shuffled playlist of media spans, each with its own repeat rule. Advancing a region changes both loop boundaries without losing the remaining set.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/KitePlayer.kt, setAbLoop; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerState.kt, PlayerSnapshot.abLoopA and abLoopB
- Verdict: MISSING-FEATURE
- Why: The API and snapshot store exactly one A and one B value. Setting another loop replaces the first, with no collection, name, ordering, shuffle, or active-region index.
- Severity if real: P3 polish

### [MPV-552] Implement changing refresh rates based on the video's framerate
- Link: https://github.com/mpv-player/mpv/issues/552  State: wontfix
- Mechanism: The output enumerates display modes, chooses a refresh rate that is an integer multiple of source cadence, switches modes before playback, and restores the prior mode afterward. Variable-frame-rate media and window moves require an explicit fallback policy.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/VideoRenderer.kt, vsyncIntervalNanos and RendererEvent.VsyncChanged; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/OutputBackend.kt, OutputBackend
- Verdict: MISSING-FEATURE
- Why: Renderers can report the current refresh interval and changes, but neither renderer nor output backend can enumerate or set display modes. KitePlayer has no cadence-to-mode policy or restoration transaction.
- Severity if real: P2 quality/perf

### [MPV-10554] Add a way to play multiple audio tracks at once without the need of the launch option
- Link: https://github.com/mpv-player/mpv/issues/10554  State: wontfix
- Mechanism: Runtime track selection adds several decoded audio streams to a mixing graph, controls their individual gains, and feeds the mixed result to one output. The graph is rebuilt when tracks are added or removed without restarting video.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, OpenSession.audioLane and inPlaceAudioChange; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/Tracks.kt, Tracks.selectedAudio
- Verdict: MISSING-FEATURE
- Why: KitePlayer caches every audio stream but installs exactly one decoded `AudioLane`, and Tracks publishes one selected audio ID. A live change replaces that lane rather than adding it to a mixer, so simultaneous commentary or language tracks are unavailable.
- Severity if real: P2 quality/perf

### [MPV-9252] Investigate support for spatial audio on Apple platforms
- Link: https://github.com/mpv-player/mpv/issues/9252  State: closed-fixed
- Mechanism: Apple's spatial-audio path queues compressed or decoded timed samples through AVSampleBufferAudioRenderer so the system can apply device-aware binaural or multichannel rendering. Its output latency must be folded back into the media clock.
- KitePlayer code checked: kiteplayer-output/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/output/CoreAudioSink.kt, openWithRing; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, open
- Verdict: MISSING-FEATURE
- Why: The Apple sink opens a conventional AudioUnit and accepts interleaved float PCM through KitePlayer's ring. It never constructs AVSampleBufferAudioRenderer, preserves spatial object metadata, or obtains that renderer's latency, so system spatial audio is not engaged.
- Severity if real: P2 quality/perf

### [MPV-3548] Implement adaptive track switching for HLS/DASH
- Link: https://github.com/mpv-player/mpv/issues/3548  State: wontfix
- Mechanism: Adaptive streaming estimates sustainable throughput and buffer health, chooses an HLS or DASH variant, and switches on compatible segment boundaries without resetting the playback clock. Hysteresis prevents quality from oscillating around one bitrate threshold.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, handleTrackChanges; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerConfig.kt, BufferPolicy
- Verdict: MISSING-FEATURE
- Why: BufferPolicy controls readiness and memory but contains no bandwidth estimator, quality ladder, or hysteresis. A video selection still tears down and rebuilds the session, so there is no automatic or segment-aligned variant transaction.
- Severity if real: P2 quality/perf

### [MPV-8334] Option to constrain HLS content by resolution
- Link: https://github.com/mpv-player/mpv/issues/8334  State: wontfix
- Mechanism: Before opening an HLS variant, track selection filters the master playlist by minimum or maximum dimensions and then ranks only eligible streams. Selecting before download avoids the rebuffer and excess bandwidth caused by starting the largest variant first.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/MediaItem.kt, MediaItem.openOptions; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, buildSession
- Verdict: MISSING-FEATURE
- Why: MediaItem has no typed resolution bound or video-track predicate, and automatic session construction picks the first non-cover video stream. A caller can select a known track later, but cannot ask KitePlayer to constrain initial HLS variant discovery by dimensions.
- Severity if real: P2 quality/perf

### [MPV-7674] Smooth-motion interpolation without display-resample
- Link: https://github.com/mpv-player/mpv/issues/7674  State: wontfix
- Mechanism: A low-cost cadence converter blends only frames needed to fit source cadence into the display rate, avoiding full audio resampling and a new render on every vertical blank. The blend coefficient comes from the source and display clock phase.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/VideoPlayback.kt, tick and present; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/RenderQuality.kt, RenderQuality
- Verdict: MISSING-FEATURE
- Why: The scheduler can hold, drop, repeat, or present decoded frames, while RenderQuality has no temporal pass. KitePlayer never retains two presentation candidates for blending and has no cadence-conversion mode.
- Severity if real: P2 quality/perf

### [MPV-129] VAAPI support
- Link: https://github.com/mpv-player/mpv/issues/129  State: closed-fixed
- Mechanism: FFmpeg decodes into VAAPI surfaces and the renderer imports those surfaces directly, avoiding CPU decode and pixel copies. Route selection must fall back to software when the codec, driver, or surface interop is unavailable.
- KitePlayer code checked: kiteplayer-ffmpeg/src/linuxMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/PlatformDecoderSelection.linux.kt, platformDecoderSelection
- Verdict: MISSING-FEATURE
- Why: The Linux implementation explicitly supplies no hardware route and documents that its build contains no VAAPI hwaccel. Every Linux codec therefore decodes in software even when the driver and application renderer could import VA surfaces.
- Severity if real: P2 quality/perf

### [MPV-1241] A B Loop (Continuously Loop a Section of a Video)
- Link: https://github.com/mpv-player/mpv/issues/1241  State: closed-fixed
- Mechanism: The player stores an A and B timestamp, detects B while playing, and performs a precise seek back to A. A missing B can mean the end of media, and clearing the points returns to ordinary playback.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/KitePlayer.kt, setAbLoop; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, handlePlaybackTime
- Verdict: IMMUNE
- Why: `setAbLoop` exposes the same one-region contract, validates its bounds, publishes both points, and supports A-to-end looping. `handlePlaybackTime` watches the B crossing while playing and queues a precise seek to A.
- Severity if real: P2 quality/perf

### [MPV-2779] dts-ma  96k 7.1 m2ts a/v desync
- Link: https://github.com/mpv-player/mpv/issues/2779  State: closed-fixed
- Mechanism: FFmpeg's raw DTS demuxer considered only the core stream when deriving timestamps for an extracted DTS-HD track. The resulting packet timestamps advanced at the wrong rate, causing several seconds of A/V drift; FFmpeg corrected the demux timestamp calculation.
- KitePlayer code checked: gradle/libs.versions.toml, kitecodec dependency; ../KiteCodec/SECURITY.md, pinned FFmpeg disclosure
- Verdict: IMMUNE
- Why: KitePlayer resolves KiteCodec 0.1.4, whose published artifacts embed FFmpeg n8.0. That is many major releases newer than the 2016 FFmpeg fix, so the obsolete raw-DTS timestamp calculation is not shipped.
- Severity if real: P1 broken feature

### [MPV-7440] Audible periodic click with 1fps video
- Link: https://github.com/mpv-player/mpv/issues/7440  State: closed-fixed
- Mechanism: At very low frame rates, the video packet queue naturally empties between frames. MPV mistook that harmless gap for cache underflow, paused and immediately resumed audio once per new frame, producing a click; the fix required actual output starvation before entering buffering.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, handleBuffering and outputStarved
- Verdict: IMMUNE
- Why: KitePlayer requires both a sticky demux shortage and current output starvation. When audio is selected, `outputStarved` consults the audio ring rather than an empty low-rate video queue, so the one-frame-per-second packet gap cannot pause healthy audio through this mechanism.
- Severity if real: P1 broken feature

### [MPV-11055] Audio crackling for vast majority of my .mp3 files
- Link: https://github.com/mpv-player/mpv/issues/11055  State: closed-fixed
- Mechanism: PipeWire detached mpv's direct output node from a running driver when the node was not marked `always-process`. At a 44.1 kHz source rate feeding a 192 kHz graph, the intermittent driver attachment corrupted resampling cadence and crackled; PipeWire fixed the node scheduling bug.
- KitePlayer code checked: kiteplayer-output/src/jvmMain/kotlin/io/github/yuroyami/kiteplayer/output/DesktopAudioSink.kt, open; kiteplayer-output/src/jvmMain/kotlin/io/github/yuroyami/kiteplayer/output/SourceDataLineDriver.kt, PlatformSourceDataLineDriver
- Verdict: IMMUNE
- Why: KitePlayer's built-in Linux desktop path opens a Java Sound `SourceDataLine`; it does not create a native PipeWire stream or set PipeWire node properties. The direct-node `always-process` scheduling bug therefore is not in this output path.
- Severity if real: P1 broken feature

### [MPV-1183] Seeking in large webm (audio) file can be slow when using GnuTLS instead of OpenSSL on ARM
- Link: https://github.com/mpv-player/mpv/issues/1183  State: closed-fixed
- Mechanism: On a slow ARM system, GnuTLS spent several seconds hashing or decrypting each HTTPS reconnection made by a range seek, while the OpenSSL build completed the same reconnect quickly. The reporter and maintainers isolated the delay to the TLS backend rather than demux seeking.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/MediaItem.kt, MediaIoResolver; kiteplayer-network/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/network/KtorMediaIo.kt, open and openAt
- Verdict: IMMUNE
- Why: KitePlayer's mobile HTTPS route uses a caller-supplied or Ktor `MediaIo`, whose platform engine owns TLS, and a seek becomes one ranged request through `openAt`. Its bundled FFmpeg deliberately contains no TLS backend, so it cannot select the affected GnuTLS path.
- Severity if real: P2 quality/perf

### [MPV-1341] mp.commandv("seek", 0.0, "absolute", "exact") seeks to second keyframe directly after loading
- Link: https://github.com/mpv-player/mpv/issues/1341  State: closed-fixed
- Mechanism: Initial B-frames had timestamps before the first I-frame, but mpv copied codec headers into a new decoder context without copying libavformat's `has_b_frames` value. Libavcodec then failed to emit the first reordered frames. The fix copied that field before opening the decoder.
- KitePlayer code checked: ../KiteCodec/kitecodec-core/src/nativeMain/kotlin/io/github/yuroyami/kitecodec/Playback.native.kt, StreamDecoder.open; ../KiteCodec/kitecodec-core/src/jvmAndAndroidMain/kotlin/io/github/yuroyami/kitecodec/Playback.jvm.kt, StreamDecoder.open
- Verdict: SUSPECT
- Why: Both KiteCodec implementations initialize a fresh decoder only with `avcodec_parameters_to_context`; neither explicitly transfers `has_b_frames`, which is not an `AVCodecParameters` field. Modern FFmpeg may infer the reorder depth, but no fixture proves an exact seek at initial pre-I B-frames, so the old failure shape remains unexcluded.
- Severity if real: P1 broken feature

### [MPV-4555] Wrong color while playing 4K HDR video with vaapi
- Link: https://github.com/mpv-player/mpv/issues/4555  State: closed-fixed
- Mechanism: Mesa's VAAPI HEVC path reused VDPAU scaling-list ordering even though VAAPI expects a different order. Ten-bit blocks were reconstructed with the wrong coefficients, producing extreme false colors; Mesa corrected the ordering and reporters confirmed the fix.
- KitePlayer code checked: kiteplayer-ffmpeg/src/linuxMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/PlatformDecoderSelection.linux.kt, platformDecoderSelection
- Verdict: IMMUNE
- Why: KitePlayer's Linux backend supplies no VAAPI hardware route and decodes every codec in software. No VA surface reaches the affected Mesa reconstruction path.
- Severity if real: P1 broken feature

### [MPV-8981] 4k Video Frame Drops (video stuttering)
- Link: https://github.com/mpv-player/mpv/issues/8981  State: closed-fixed
- Mechanism: Dynamic HDR peak detection used global GPU atomics. On affected Intel GPUs those atomics serialized per workgroup, turning a normally sub-millisecond render into periodic 60 ms spikes and dropped frames. Disabling peak computation removed the expensive pass.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/HdrToneMap.kt, mapInPlace and SRC_PEAK
- Verdict: IMMUNE
- Why: KitePlayer does no frame histogram or dynamic peak computation. Its software tone mapper uses a fixed 1000-nit source peak and independent lookup-table operations per pixel, so no shared GPU atomic can serialize through this mechanism.
- Severity if real: P2 quality/perf

### [MPV-13439] Lip-sync broken with AC3 TruHD
- Link: https://github.com/mpv-player/mpv/issues/13439  State: closed-fixed
- Mechanism: A new Matroska still-image probe inspected only the first 100 blocks. Files with many dense audio blocks before the second video block were falsely classified as a still image, disabling normal video timing and appearing as TrueHD lip-sync failure. The fix raised the probe bound to 1000 blocks.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, toPlayerStream; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, OpenSession.isStillImage
- Verdict: IMMUNE
- Why: KitePlayer does not infer still images by probing a bounded number of Matroska blocks. KiteCodec maps only the container's attached-picture disposition, and the session otherwise treats sparse video as still only when a backend explicitly marks it; this backend never does.
- Severity if real: P1 broken feature

### [MPV-13513] Bug: Playback seeks backwards when speed is increased, and seeks forwards when speed is decreased
- Link: https://github.com/mpv-player/mpv/issues/13513  State: closed-fixed
- Mechanism: During a live speed change, audio resampling or tempo-filter setup lagged behind the requested rate. MPV kept applying A/V corrections from transient, invalid delay estimates, so it repeated or skipped frames and samples as if it had sought. The mitigation suspended correction until the new audio epoch was active.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, CoreCommand.SetSpeed and runSeek; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, flush and speed
- Verdict: IMMUNE
- Why: KitePlayer never splices a rate into a live audio epoch. A speed change sets the wanted rate and queues a precise seek to the current position; that seek parks scheduling, flushes the device and DSP state, installs one new rate, and resumes only after selected output is ready. No transient mixed-rate delay is fed to A/V correction.
- Severity if real: P1 broken feature

### [MPV-3610] Immediate A-V desync disables video-sync
- Link: https://github.com/mpv-player/mpv/issues/3610  State: closed-fixed
- Mechanism: Video scheduling began before a delayed audio output had actually started. The initial clock gap looked like a large permanent desynchronization, so MPV disabled display-resample; the fix waited for audio startup before evaluating sync.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, everySelectedStreamReady and handlePlaybackRestart
- Verdict: IMMUNE
- Why: The start rendezvous requires both compressed readiness and decoded output from every selected stream. `handlePlaybackRestart` does not start the scheduler or publish Playing until the audio ring has data and the device can start, so an absent initial audio clock is never evaluated as playback drift.
- Severity if real: P1 broken feature

### [MPV-16053] HDR video appears dull and incorrect when unpaused or switched to fullscreen
- Link: https://github.com/mpv-player/mpv/issues/16053  State: closed-fixed
- Mechanism: Native HDR output handed the display a signal-peak value that did not describe the content, causing the display's own tone mapper to compress highlights differently across swapchain presentation modes. Overriding `sig-peak` to the effective peak made windowed and fullscreen output match.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/HdrToneMap.kt, SRC_PEAK and eetfNits
- Verdict: SUSPECT
- Why: KitePlayer always assumes a 1000-nit source peak because mastering and content-light metadata are not plumbed, and exposes no per-item override. The trigger is HDR mastered or measured at a materially different peak; its SDR roll-off can then compress the picture using the wrong knee. No visual fixture covers that metadata mismatch.
- Severity if real: P2 quality/perf

### [MPV-17265] Nvidia true hdr flag not working
- Link: https://github.com/mpv-player/mpv/issues/17265  State: closed-fixed
- Mechanism: NVIDIA RTX Video HDR required D3D11 video-processor output in a ten-bit format, followed by PQ and BT.2020 metadata that described the transformed frames. The original flag omitted part of that format and metadata handoff; the fixed VPP path keeps the frame on the GPU and publishes the transformed color space.
- KitePlayer code checked: kiteplayer-ffmpeg/src/mingwMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/PlatformDecoderSelection.mingw.kt, platformDecoderSelection
- Verdict: MISSING-FEATURE
- Why: The Windows build contains D3D11VA symbols but deliberately exposes no hardware route, device context, VPP filter, or D3D11 frame path. KitePlayer cannot invoke RTX Video HDR or carry its ten-bit PQ output to a renderer.
- Severity if real: P2 quality/perf

### [MPV-8082] MPV doesn't allow manually setting an ICC contrast, leading to greyish blacks when using color management
- Link: https://github.com/mpv-player/mpv/issues/8082  State: closed-fixed
- Mechanism: BT.1886 derives its near-black curve from display contrast. An inaccurate ICC black point made MPV infer too little contrast and lift shadows; changing `icc-contrast` from a ceiling into an explicit value let the user supply the measured ratio.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/RenderQuality.kt, RenderQuality; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/HdrToneMap.kt, HdrToneMap
- Verdict: MISSING-FEATURE
- Why: KitePlayer has no ICC-profile loader, display black-point measurement, BT.1886 target transform, or contrast override. RenderQuality controls scaling, debanding, dithering, and linear-light scaling only, while the HDR fallback targets a fixed gamma 2.2 SDR output.
- Severity if real: P2 quality/perf

### [MPV-16933] SDR display incorrectly detected as HDR resulting in poor tone-mapping / black clipping
- Link: https://github.com/mpv-player/mpv/issues/16933  State: closed-fixed
- Mechanism: Windows advanced-color feedback described an SDR desktop with HDR-like luminance fields. Treating that hint as a strict HDR target selected BT.2020 or PQ output and used a nonzero display black level, crushing or lifting near-black detail. The revised target selection separates hints, transfer choice, and contrast overrides.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/HdrToneMap.kt, forColorSpaceOrNull; kiteplayer-output/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/output/MetalVideoRenderer.kt, present
- Verdict: IMMUNE
- Why: Built-in KitePlayer renderers do not query Windows advanced-color target hints or choose an HDR swapchain. The shared converter tone maps only when the source itself declares PQ or HLG, and the Metal path targets SDR, so an SDR display report cannot reclassify source or target through this mechanism.
- Severity if real: P1 broken feature

### [MPV-17170] [Regression] Darker/crushed colors on Gnome Wayland (Fedora 43) - Nightly build > Dec 20
- Link: https://github.com/mpv-player/mpv/issues/17170  State: closed-fixed
- Mechanism: Mutter returned a preferred Wayland color description with no luminance data. MPV treated zero maximum luminance as a real non-SDR value and changed the preferred transfer to PQ, making ordinary SDR output dark. The compatibility fix preserves unknown luminance rather than promoting it to HDR.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/OutputBackend.kt, OutputBackend; kiteplayer-ffmpeg/src/linuxMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/PlatformDecoderSelection.linux.kt, platformDecoderSelection
- Verdict: MISSING-FEATURE
- Why: KitePlayer ships no Linux Wayland renderer or color-management protocol integration. A future backend must distinguish missing compositor luminance from numeric zero before selecting its output transfer, but today there is no built-in Wayland picture path at all.
- Severity if real: P2 quality/perf

### [MPV-16972] Plasma 6.5 seems to squash Wayland Vulkan HDR mode into SDR color range
- Link: https://github.com/mpv-player/mpv/issues/16972  State: closed-fixed
- Mechanism: Vulkan HDR presentation needs the driver to expose `VK_EXT_hdr_metadata` so mastering and luminance metadata reach the compositor and display. The NVIDIA driver did not advertise the extension, leaving mpv unable to establish the requested HDR signal.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/OutputBackend.kt, OutputBackend; kiteplayer-ffmpeg/src/linuxMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/PlatformDecoderSelection.linux.kt, platformDecoderSelection
- Verdict: MISSING-FEATURE
- Why: KitePlayer has neither a Vulkan renderer nor a Wayland HDR negotiation path, and its Linux decoder selection is software-only. It cannot request or validate `VK_EXT_hdr_metadata`; implementing native Linux HDR will need an explicit capability refusal when that extension is absent.
- Severity if real: P2 quality/perf

### [MPV-737] No output when using multichannel coreaudio ao for anything more than 2 channels
- Link: https://github.com/mpv-player/mpv/issues/737  State: closed-fixed
- Mechanism: A 5.1-configured HDMI device reported eight channel descriptions whose last two labels were unknown. MPV rejected the entire layout when any label was unknown and fell back to a null output. The fix retained the known map, represented unknown channels, and added the rear 7.1 layout.
- KitePlayer code checked: kiteplayer-rt/native/src/kite_rt_coreaudio.c, kprt_layout_tag_for and kprt_sink_create; kiteplayer-output/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/output/CoreAudioSink.kt, openWithRing
- Verdict: IMMUNE
- Why: KitePlayer does not reject the device after enumerating its description labels. It bounds the accepted channel count from the device, assigns a known CoreAudio layout tag for one through six channels, and reports the accepted mask to the mixer; unused descriptions beyond the requested 5.1 width are never treated as fatal.
- Severity if real: P1 broken feature

### [MPV-8463] Linked MKVs support
- Link: https://github.com/mpv-player/mpv/issues/8463  State: open
- Mechanism: Matroska ordered chapters can reference a segment UID stored in another file. Correct playback resolves that UID, opens the linked segment, maps its chapter span into one virtual timeline, and returns to the original file without resetting visible playback state.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/MediaItem.kt, MediaItem; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, chapters
- Verdict: MISSING-FEATURE
- Why: One MediaItem names one source and KiteCodecSource maps only the chapters returned for that open. KitePlayer carries no Matroska segment UID, linked-file resolver, ordered-edition graph, or virtual cross-file timestamp map.
- Severity if real: P2 quality/perf

### [MPV-14176] Allow  notify subtilte to upper app through event
- Link: https://github.com/mpv-player/mpv/issues/14176  State: open
- Mechanism: A client-facing subtitle callback publishes each fully timed text or bitmap overlay immediately before presentation. An embedding application can then draw captions in its own view even when the video output cannot composite them.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/VideoRenderer.kt, VideoRenderer.setOverlay and SubtitleOverlay
- Verdict: IMMUNE
- Why: The application-supplied VideoRenderer is already the presentation boundary, and `setOverlay` delivers each complete timed bitmap overlay independently of `present`. A renderer backed by MediaCodec Surface can forward that overlay to a TextView, TextureView, or application event instead of compositing it itself.
- Severity if real: P1 broken feature

### [MPV-5879] Pause or quit on I/O errors
- Link: https://github.com/mpv-player/mpv/issues/5879  State: open
- Mechanism: A transient-source policy distinguishes retryable read failure from end of file, drains already buffered media, pauses at the last valid position, and periodically reopens or seeks once a removable or network source returns. A terminal policy instead saves position and stops without skipping later playlist items.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, handleWorkerOutcome and fail; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlaybackError.kt, PlaybackError.SourceUnavailable
- Verdict: MISSING-FEATURE
- Why: A demux worker read failure becomes SourceUnavailable, tears down the session, and enters Failed. There is no retry classification, buffered-drain pause, reopen timer, saved resume point, or per-item stop-on-error policy.
- Severity if real: P1 broken feature

### [MPV-15581] Add Fade-In and Fade-Out Options 
- Link: https://github.com/mpv-player/mpv/issues/15581  State: open
- Mechanism: Transport fades ramp gain to zero before pausing or changing tracks, then ramp from zero after the new transport epoch has audible data. The transition must complete against the audio device clock so buffered old-gain samples do not defeat it.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/GainStage.kt, apply; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, applyPause and handlePlaybackRestart
- Verdict: MISSING-FEATURE
- Why: GainStage smooths an explicit volume change, but pause stops the sink immediately and restart resumes at the stored gain. There is no fade duration, transport-owned ramp completion, or cross-item fade policy.
- Severity if real: P3 polish

### [MPV-18087] Decklink Video Support
- Link: https://github.com/mpv-player/mpv/issues/18087  State: open
- Mechanism: Professional SDI output negotiates a DeckLink device, display mode, pixel format, range, link topology, and frame cadence, then schedules decoded frames on the device clock without an operating-system color transform. Focus loss also needs an explicit device-release policy.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/VideoRenderer.kt, VideoRenderer.present; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/OutputBackend.kt, videoRenderer
- Verdict: MISSING-FEATURE
- Why: A custom VideoRenderer can receive frames, but KitePlayer ships no DeckLink SDK adapter, device enumeration, SDI format negotiation, reference-output clock, or focus-release transaction. OutputBackend's renderer factory is itself not implemented.
- Severity if real: P2 quality/perf

### [MPV-3612] Overlay video window on macOS Sierra (PiP)
- Link: https://github.com/mpv-player/mpv/issues/3612  State: open
- Mechanism: System picture-in-picture hands a timed video layer to the platform controller, which owns a resizable always-on-top window across Spaces and coordinates play, pause, close, and restore actions with the player.
- KitePlayer code checked: kiteplayer-output/src/macosArm64Main/kotlin/io/github/yuroyami/kiteplayer/output/AppKitWindow.kt, AppKitWindow
- Verdict: MISSING-FEATURE
- Why: AppKitWindow creates an ordinary titled, closable, miniaturizable, resizable NSWindow. It has no AVPictureInPictureController, cross-Space window behavior, system PiP controls, or restore callback.
- Severity if real: P3 polish

### [MPV-12978] RTL display for subtitles
- Link: https://github.com/mpv-player/mpv/issues/12978  State: open
- Mechanism: Correct mixed right-to-left subtitles require Unicode bidi paragraph resolution and script shaping before glyph placement; reversing code points or aligning the line right is insufficient when Latin words are embedded in Arabic or Hebrew text.
- KitePlayer code checked: kiteplayer-output/src/jvmMain/kotlin/io/github/yuroyami/kiteplayer/output/DesktopSubtitleRasterizer.kt, rasterize; kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/AndroidSubtitleRasterizer.kt, rasterize; kiteplayer-output/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/output/AppleSubtitleRasterizer.kt, rasterize
- Verdict: IMMUNE
- Why: Every built-in text rasterizer delegates line layout, bidi resolution, and shaping to its platform text engine: AWT on JVM, StaticLayout on Android, and CoreText on Apple. KitePlayer does not manually reorder mixed-direction strings.
- Severity if real: P1 broken feature

### [MPV-9654] GUI embedding for Wayland in libmpv
- Link: https://github.com/mpv-player/mpv/issues/9654  State: open
- Mechanism: Direct Wayland embedding binds video presentation to a caller-owned `wl_surface`, tracks configure, scale, and destruction events, and imports or copies frames without creating an independent top-level window. Surface loss must not tear down audio playback.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/VideoRenderer.kt, VideoRenderer; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/OutputBackend.kt, videoRenderer
- Verdict: MISSING-FEATURE
- Why: KitePlayer's renderer SPI can host an application implementation and already defines surface-loss behavior, but no Linux Wayland renderer or `wl_surface` adapter is provided. The dormant OutputBackend renderer factory cannot create one.
- Severity if real: P2 quality/perf

### [MPV-12898] Frame insertion (BFI) with advanced settings: HDR brightness boost, adjustable grey/dark shade color variants insertion for reduced flicker/CRT Simulation
- Link: https://github.com/mpv-player/mpv/issues/12898  State: open
- Mechanism: Black-frame insertion alternates each source frame with a configurable dark or gray synthetic frame, optionally compensating HDR brightness, to reduce sample-and-hold blur. Cadence must derive from refresh rate and disable when the display cannot sustain the multiplied rate.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/VideoPlayback.kt, tick and present; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/RenderQuality.kt, RenderQuality
- Verdict: MISSING-FEATURE
- Why: The scheduler presents, repeats, or drops decoded frames only, and RenderQuality contains no temporal insertion mode, shade, duty cycle, brightness compensation, or minimum-refresh guard.
- Severity if real: P2 quality/perf

### [MPV-16645] SABR streaming support
- Link: https://github.com/mpv-player/mpv/issues/16645  State: open
- Mechanism: YouTube SABR playback needs a protocol client that exchanges streaming requests, obtains media chunks, follows server-directed representation changes, and feeds continuous audio and video segment streams while adapting to throughput and buffer state.
- KitePlayer code checked: kiteplayer-network/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/network/dash/DashMediaIo.kt, DashMediaIo and Dash.mediaItemFor; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/MediaItem.kt, MediaIo
- Verdict: MISSING-FEATURE
- Why: KitePlayer's adaptive network module parses ordinary DASH manifests and reads one preselected representation as a forward segment stream. It has no SABR request protocol, server-message parser, dual-stream merger, or live representation switcher; custom MediaIo is the only extension point.
- Severity if real: P2 quality/perf

### [MPV-12973] ffmpeg 6.1 breaks MPV streaming radio
- Link: https://github.com/mpv-player/mpv/issues/12973  State: closed-fixed
- Mechanism: FFmpeg 6.1 reduced HLS playlist reload tolerance through its `max_reload` default. A live radio playlist that skipped expired segments exhausted the small retry count and ended, while passing `max_reload=1000` kept it alive.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/MediaItem.kt, openOptions; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, open
- Verdict: SUSPECT
- Why: KitePlayer forwards an item's explicit demux options but supplies no HLS reload policy of its own, so an item without `max_reload` inherits the linked FFmpeg build's default. A live playlist with more consecutive stale or failed reloads than that default can terminate as source EOF or failure.
- Severity if real: P1 broken feature

### [MPV-8396] forced-track flag is not respected
- Link: https://github.com/mpv-player/mpv/issues/8396  State: closed-fixed
- Mechanism: MPV's automatic selection considered forced subtitles only through its subtitle-language preference path. With no `slang` configured, the forced disposition was ignored; the fix added a forced-track fallback independent of a language match.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, pickSubtitle; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerConfig.kt, SubtitleConfig
- Verdict: SUSPECT
- Why: `pickSubtitle` also enters its forced branch only when `preferredLanguages` is nonempty. With the default empty preference list, a forced-only track is excluded from the ordinary fallback and no subtitle is selected.
- Severity if real: P1 broken feature

### [MPV-9979] Unexpected audio clipping when using audio filters
- Link: https://github.com/mpv-player/mpv/issues/9979  State: closed-fixed
- Mechanism: A high-gain filter produced float samples above full scale, then MPV's planar audio conversion clipped them before the later master-volume stage could attenuate them. Keeping the chain interleaved and floating preserved the headroom until final volume application.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/AudioPipeline.kt, process; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/GainStage.kt, apply
- Verdict: IMMUNE
- Why: KitePlayer has no arbitrary audio filter graph. Its decoded samples stay in interleaved `FloatArray` buffers through mixing, resampling, and tempo processing, and GainStage applies volume last without an intermediate float-to-integer or planar conversion.
- Severity if real: P1 broken feature

### [MPV-9353] DolbyE Audio Decoding in MPV (FFMpeg supports it)
- Link: https://github.com/mpv-player/mpv/issues/9353  State: open
- Mechanism: Dolby E may be carried as SMPTE ST 337 data bursts inside an MXF audio essence that otherwise looks like PCM. Without detecting the burst payload and selecting the Dolby E decoder, a player sends the encoded words to the speakers as loud noise.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, newAudioDecoder and openDecoder
- Verdict: SUSPECT
- Why: The backend opens the decoder named by the demuxed stream and contains no SMPTE ST 337 probe or Dolby E override. An MXF whose stream is exposed as PCM rather than Dolby E can therefore follow the ordinary PCM decode path; no accepted failing fixture proves it here.
- Severity if real: P1 broken feature

### [MPV-16278] CLAP Atom Support for MP4 Files (via `AV_PKT_DATA_FRAME_CROPPING`)
- Link: https://github.com/mpv-player/mpv/issues/16278  State: open
- Mechanism: An MP4 `clap` atom defines a clean aperture smaller than the coded frame. FFmpeg exposes that aperture as frame-cropping packet side data, which a player must carry through decode and apply before display.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/VideoFrame.kt, VideoFrame and VideoSize; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, KiteCodecVideoFrame
- Verdict: MISSING-FEATURE
- Why: VideoFrame models coded size, pixel aspect, rotation, format, and color, but no clean-aperture rectangle. KiteCodecVideoFrame copies only those fields, so an MP4 aperture cannot reach a renderer unless the native decoder has already destructively cropped the pixels.
- Severity if real: P2 quality/perf

### [MPV-14811] Alternate HDR10 metadata handling
- Link: https://github.com/mpv-player/mpv/issues/14811  State: open
- Mechanism: Seamless-branching files can alternate HDR10 mastering metadata between adjacent frames. Passing every change to an HDR output makes it rebuild the swapchain repeatedly; a passthrough policy that freezes the first valid metadata avoids those presentation stalls.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/VideoFrame.kt, ColorSpaceInfo; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/HdrToneMap.kt, HdrToneMap
- Verdict: IMMUNE
- Why: KitePlayer does not plumb mastering-display or content-light metadata at all; its frame color model carries only matrix, primaries, transfer, range, and chroma location, and its fallback tone mapper uses a fixed 1000-nit source peak. Alternating mastering metadata therefore cannot trigger per-frame output reconfiguration, although dropping it is a separate quality gap.
- Severity if real: P2 quality/perf

### [MPV-12730] Implement BT.2408-6 tone mapping
- Link: https://github.com/mpv-player/mpv/issues/12730  State: open
- Mechanism: BT.2408-6 Annex 5 maps HDR with an explicit black point, display peak, and knee, and can pair that luminance curve with perceptual ITP gamut mapping to reduce hue shifts on limited displays.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/HdrToneMap.kt, eetfNits and mapInPlace
- Verdict: MISSING-FEATURE
- Why: KitePlayer's software fallback implements a fixed BT.2390 EETF into 203 nits and a fixed BT.2020-to-BT.709 matrix. It exposes no BT.2408 curve, black point, knee, target peak, or ITP gamut-map policy.
- Severity if real: P2 quality/perf

### [MPV-15158] change the HLS stream quality during playback
- Link: https://github.com/mpv-player/mpv/issues/15158  State: open
- Mechanism: A live HLS quality change selects another rendition at a segment boundary, aligns its media timestamp with the active rendition, and continues through the same playback timeline. Automatic adaptation makes that selection from measured throughput and buffered duration.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/MediaItem.kt, openOptions; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, open and reselectStreams
- Verdict: MISSING-FEATURE
- Why: HLS is delegated to FFmpeg at open, and its demux options are immutable MediaItem values. Live stream reselection changes only already exposed track indices; KitePlayer has no HLS master-playlist model, rendition switch command, segment alignment, or throughput controller.
- Severity if real: P1 broken feature

### [MPV-16023] Incremental subtitle display support (Karoake) with webvtt
- Link: https://github.com/mpv-player/mpv/issues/16023  State: open
- Mechanism: WebVTT inline timestamp tags split one cue into timed text phases. A karaoke renderer reveals or highlights each following span when its timestamp crosses while retaining the rest of the cue's authored timing.
- KitePlayer code checked: kiteplayer-subtitles/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/subtitle/WebVttParser.kt, stripVttOnlyTags and KARAOKE_TAG
- Verdict: MISSING-FEATURE
- Why: The parser explicitly strips every inline karaoke timestamp and returns one static span list for the whole cue. No phase timing survives into SubtitleCue or the rasterizers, so incremental display cannot be reconstructed later.
- Severity if real: P2 quality/perf

### [MPV-16990] Option to mute audio while seeking
- Link: https://github.com/mpv-player/mpv/issues/16990  State: open
- Mechanism: A scrub-scoped mute begins when a held key or seek-bar drag starts and ends only after the final seek has landed. Muting each individual seek is insufficient because short decoded fragments can become audible between repeated requests.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runSeek and clearBuffers; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/KitePlayer.kt, seek and setMuted
- Verdict: MISSING-FEATURE
- Why: Each seek stops the sink and flushes the ring, and callers can mute manually, but the player has no begin-scrub or end-scrub transaction. A series of independently completed seeks may restart sound between requests unless the application coordinates mute state itself.
- Severity if real: P3 polish

### [MPV-5133] automatic track selection in addition to alang and slang
- Link: https://github.com/mpv-player/mpv/issues/5133  State: open
- Mechanism: Rich automatic track selection scores candidates after language by properties such as channel layout, bit depth, accessibility disposition, and descriptive title or external filename. This distinguishes several tracks that share one coarse language tag.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, pickAudio and pickSubtitle; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerConfig.kt, AudioConfig and SubtitleConfig
- Verdict: MISSING-FEATURE
- Why: Audio selection uses language, then default flag, then container order. Subtitle selection adds accessibility, forced, and default flags, but neither policy accepts channel-count, bit-depth, title, or filename preferences, so same-language variants cannot be chosen automatically by those traits.
- Severity if real: P2 quality/perf

### [MPV-8579] Audio won't reinitialize when driver initialization failed
- Link: https://github.com/mpv-player/mpv/issues/8579  State: open
- Mechanism: After an explicitly selected audio device fails to initialize, a later switch to a valid device must rerun device discovery and output initialization rather than leaving the audio chain latched in its failed state.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/KitePlayer.kt, public controls; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/OutputBackend.kt, audioSink
- Verdict: MISSING-FEATURE
- Why: Built-in sinks can reopen their current platform output after a write failure, but KitePlayer exposes no runtime audio-device enumeration or selection command at all. An application cannot switch from a refused device to another device without supplying and reopening a different output backend.
- Severity if real: P1 broken feature

### [MPV-9253] OSD bar that shows only from --start to --end, and prevent seeking outside that range
- Link: https://github.com/mpv-player/mpv/issues/9253  State: open
- Mechanism: A playable subrange gives one item a logical start, logical end, and rebased duration. Every absolute, relative, and seek-bar target is clamped to that interval, and reaching its end stops instead of entering adjacent content in the same container.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/MediaItem.kt, startPosition; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/KitePlayer.kt, setAbLoop
- Verdict: MISSING-FEATURE
- Why: MediaItem can start at an offset but has no end bound or rebased duration. A-B loop can jump from B back to A, not stop and clamp at B, so one episode inside a shared Blu-ray timeline cannot be exposed as a bounded item.
- Severity if real: P2 quality/perf

### [MPV-11306] Implement ISpatialAudioClient support on Windows
- Link: https://github.com/mpv-player/mpv/issues/11306  State: open
- Mechanism: Windows spatial output negotiates an `ISpatialAudioClient`, activates a spatial stream, and submits channel-bed plus dynamic-object buffers with per-object positions instead of one fixed interleaved speaker layout.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/AudioSink.kt, AudioFormat and AudioSink; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/ChannelMixer.kt, ChannelMixer
- Verdict: MISSING-FEATURE
- Why: The sink contract carries only sample rate, channel count, sample format, and an optional fixed speaker mask. It has no spatial-object identity, coordinates, object lifetime, or Windows spatial sink implementation, and the mixer reduces audio to an ordinary device layout.
- Severity if real: P2 quality/perf

### [MPV-13735] Converting libavcodec frame to mpv frame failed (ambisonic)
- Link: https://github.com/mpv-player/mpv/issues/13735  State: open
- Mechanism: FFmpeg introduced ambisonic channel layouts that MPV's internal speaker-channel map could not represent. Conversion rejected every decoded frame, so the player repeatedly logged the mapping error and produced no audio.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, audioFormat; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/ChannelMixer.kt, sourceLayout and passThrough
- Verdict: IMMUNE
- Why: An unknown channel mask is not a hard conversion failure here. The backend drops an unusable mask, keeps up to eight decoded channels, and ChannelMixer warns once then passes the first channels through, so audio continues instead of rejecting every frame. Correct ambisonic spatial rendering is still absent.
- Severity if real: P1 broken feature

### [MPV-10690] Render ass subtitles in advance
- Link: https://github.com/mpv-player/mpv/issues/10690  State: open
- Mechanism: Complex ASS animation can take longer than one frame interval to rasterize. A lookahead worker renders upcoming subtitle frames into a bounded cache during simpler scenes so presentation does not stall when the complex cue becomes visible.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerConfig.kt, SubtitleConfig.lookahead; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, timeAndPublishCues and publishOverlay
- Verdict: MISSING-FEATURE
- Why: SubtitleConfig documents that lookahead is unread. The actor schedules rasterization only when the active cue set changes, at the cue edge, and retains only the resulting current overlay; there is no future-frame render worker or bounded overlay cache.
- Severity if real: P2 quality/perf

### [MPV-13215] Feature request: prefer SDH/non-forced subtitles
- Link: https://github.com/mpv-player/mpv/issues/13215  State: open
- Mechanism: Disposition-aware selection exposes ordered preferences such as hearing-impaired first or non-forced first, then scores same-language tracks by those preferences before falling back to default and container order.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, pickSubtitle; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerConfig.kt, SubtitleConfig
- Verdict: MISSING-FEATURE
- Why: KitePlayer hardcodes accessibility-first within a preferred language and excludes forced tracks only in its no-language fallback. There is no caller-selectable disposition order, so a viewer cannot request non-forced over forced, or disable the built-in accessibility preference while keeping language selection.
- Severity if real: P2 quality/perf

### [MPV-14756] Support for timed-metadata in HLS streams
- Link: https://github.com/mpv-player/mpv/issues/14756  State: open
- Mechanism: HLS audio can carry timestamped ID3 or `emsg` records in media segments. The demuxer must expose each record on the media timeline and the player must publish metadata changes, such as the current artist and title, when their timestamps arrive.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/MediaSource.kt, PlayerMediaSource.metadata and PlayerPacket; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerState.kt, PlayerSnapshot; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerEvent.kt, PlayerEvent
- Verdict: MISSING-FEATURE
- Why: `PlayerMediaSource` has one static metadata map and `PlayerPacket` carries no timed-metadata side data. Neither `PlayerSnapshot` nor `PlayerEvent` publishes a changing title or artist when an in-band HLS event reaches its timestamp.
- Severity if real: P2 quality/perf

### [MPV-17698] How can I reload and play a video after a network connection is lost without clearing the existing cache data?
- Link: https://github.com/mpv-player/mpv/issues/17698  State: open
- Mechanism: Cache-preserving reconnect keeps verified buffered byte ranges after a transport failure, reopens the source at the first missing byte, and resumes demux from the retained window instead of downloading earlier media again.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, handleWorkerOutcome and teardownSession; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/CachingMediaIo.kt, CachingMediaIo
- Verdict: MISSING-FEATURE
- Why: A demux failure tears down the whole session, which closes its CachingMediaIo and discards the RAM window before entering Failed. There is no reconnect state that transfers cached ranges into a replacement reader.
- Severity if real: P1 broken feature

### [MPV-18040] [feature request] add DeepFIlterNet3 as Audio Denoise filter
- Link: https://github.com/mpv-player/mpv/issues/18040  State: open
- Mechanism: A real-time denoiser frames decoded PCM into the model's required windows, preserves recurrent state across buffers, compensates algorithmic latency in the audio clock, and resets that state on seek or track change.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/AudioPipeline.kt, AudioPipeline and process
- Verdict: MISSING-FEATURE
- Why: The audio pipeline is a fixed mixer, resampler, tempo stage, and gain stage. It has no extensible audio-filter slot, neural model runtime, denoiser state, or latency report, so DeepFilterNet or an equivalent filter cannot be inserted.
- Severity if real: P3 polish

### [MPV-18072] ISO 21496-1 / Ultra HDR gain map rendering support
- Link: https://github.com/mpv-player/mpv/issues/18072  State: open
- Mechanism: Ultra HDR carries an SDR base image, a second gain-map image, and boost metadata. Rendering upsamples the map and applies its logarithmic gain in linear light using the current display headroom, before the ordinary HDR output or tone-map pass.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/VideoFrame.kt, VideoFrame and ColorSpaceInfo; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/HdrToneMap.kt, mapInPlace
- Verdict: MISSING-FEATURE
- Why: VideoFrame has no secondary image or gain-map metadata, and HdrToneMap receives only base RGBA plus transfer and primaries. The decoder-to-renderer path cannot carry, blend, or scale a gain map against display headroom.
- Severity if real: P2 quality/perf

### [MPV-18380] Support MVC 3D (Blu-ray frame-packed) playback
- Link: https://github.com/mpv-player/mpv/issues/18380  State: open
- Mechanism: H.264 MVC stores one base view plus a dependent eye view. Playback must decode both access units, pair them by timestamp, and present a stereo frame or convert the pair to side-by-side or top-bottom output.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/VideoFrame.kt, VideoFrame; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, KiteCodecVideoDecoder.receive
- Verdict: MISSING-FEATURE
- Why: One decoder receive yields one VideoFrame with one set of planes, and the scheduler has no left-eye and right-eye pairing model. There is no MVC decoder override, dependent-view side data, or stereo presentation mode.
- Severity if real: P2 quality/perf

### [MPV-18411] Pick forced subs for audio tagged as multiple languages
- Link: https://github.com/mpv-player/mpv/issues/18411  State: open
- Mechanism: Audio tagged `mul` may contain mostly one language plus a few foreign lines. A forced subtitle track for those lines should be selected even though its concrete language cannot equal the audio track's multiple-language tag.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, pickSubtitle; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerConfig.kt, SubtitleConfig
- Verdict: SUSPECT
- Why: With an explicit preferred subtitle language, KitePlayer treats `mul` audio as not preferred and can select a matching forced track. With the default empty preference list, however, the forced branch is skipped and the ordinary fallback excludes forced tracks, so the common unconfigured case shows none.
- Severity if real: P1 broken feature

### [MPV-8625] [Feature] Add CLI/config option to autoselect audio track based on channel count/layout
- Link: https://github.com/mpv-player/mpv/issues/8625  State: open
- Mechanism: After honoring a language preference, automatic selection can score audio candidates by requested speaker layout or channel count, such as stereo over 5.1 for headphones, before default flag and container order.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, pickAudio; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerConfig.kt, AudioConfig
- Verdict: MISSING-FEATURE
- Why: TrackInfo reports channel count, but AudioConfig accepts only language and downmix policy. `pickAudio` never reads channels or layout, so two same-language tracks cannot be auto-selected for the active output topology.
- Severity if real: P2 quality/perf

### [MPV-9442] Add percent threshold to determine when to start buffering
- Link: https://github.com/mpv-player/mpv/issues/9442  State: open
- Mechanism: A cache duty-cycle policy lets storage sleep by delaying the next read burst until retained data falls below a low-water percentage, then reading up to a high-water cap. Continuously topping up after every consumed packet keeps an external disk spinning.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerConfig.kt, BufferPolicy; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runDemux and overBudget
- Verdict: MISSING-FEATURE
- Why: The demuxer stalls only at the total byte or duration ceiling and resumes as soon as it drops below that ceiling. BufferPolicy has readiness and soft-target values but no low-water percentage or read-burst hysteresis for offline storage.
- Severity if real: P3 polish

### [MPV-9745] Synchronous hook for frame change
- Link: https://github.com/mpv-player/mpv/issues/9745  State: open
- Mechanism: A frame-synchronous hook runs after the next frame and its timestamp are known but before that frame is drawn, allowing per-frame crop, pan, or aspect metadata to affect the exact intended picture.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/VideoRenderer.kt, VideoRenderer.present and VideoFrame
- Verdict: IMMUNE
- Why: The application-supplied renderer is already called once for each concrete frame with that frame's PTS and target presentation time, before drawing it. A custom renderer can read its own metadata and apply geometry in that call without an asynchronous playback-position observer.
- Severity if real: P2 quality/perf

### [MPV-9965] mpv properties `video-playable-start-time` and `video-playable-end-time`
- Link: https://github.com/mpv-player/mpv/issues/9965  State: open
- Mechanism: Some containers expose valid pictures only inside a narrower interval than their nominal duration. A player reports those playable bounds and clamps attempts outside them to the first or last decodable video timestamp.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/MediaSource.kt, PlayerStreamInfo.startTime and MediaSource.duration; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, TimestampMapper
- Verdict: MISSING-FEATURE
- Why: KitePlayer normalizes stream start timestamps onto a zero-based timeline and exposes only one overall duration. It carries no playable start or end bound, so state and seek clamping cannot distinguish nominal container time from the decodable video interval.
- Severity if real: P2 quality/perf

### [MPV-2515] Clicks when pausing audio with low frequency content
- Link: https://github.com/mpv-player/mpv/issues/2515  State: open
- Mechanism: Stopping an audio device while a low-frequency waveform is far from zero creates a discontinuity that sounds like a click. A millisecond-scale fade to zero before pause, and back from zero after resume, removes the step.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, applyPause; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/GainStage.kt, GainStage
- Verdict: SUSPECT
- Why: `applyPause` anchors the clock and pauses the sink immediately. GainStage has a five-millisecond anti-click ramp for volume and mute changes, but pause never asks it to ramp to zero, so a low-frequency sample can still be cut at a nonzero value. No accepted failing audio fixture proves the click here.
- Severity if real: P2 quality/perf

### [MPV-8995] Add a property to read container average bitrate
- Link: https://github.com/mpv-player/mpv/issues/8995  State: open
- Mechanism: Container average bitrate is total media bytes divided by timeline duration, or an explicit demuxer-level value. It differs from a stream's instantaneous or per-track bitrate and is useful for stable quality classification.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerState.kt, PlaybackStats.containerBitrate; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/MediaSource.kt, PlayerMediaSource
- Verdict: MISSING-FEATURE
- Why: PlaybackStats explicitly documents `containerBitrate` as always null, and PlayerMediaSource exposes stream bitrates but no total container byte size or aggregate bitrate. Applications cannot obtain the requested stable value from player state.
- Severity if real: P3 polish

### [MPV-17953] A/B Loop range should reset on new file
- Link: https://github.com/mpv-player/mpv/issues/17953  State: open
- Mechanism: A-B points describe positions on one item's timeline. Reset-on-open policy clears them before the next item adopts its own duration, while an opt-in persistence policy preserves the specialist workflow that reuses the same range.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/KitePlayer.kt, setAbLoop; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runOpen
- Verdict: MISSING-FEATURE
- Why: The public contract deliberately makes both points player-scoped and says they survive the next open. `runOpen` does not clear them, and there is no reset-on-open option, so a range from one item remains armed for the next until the caller clears it.
- Severity if real: P3 polish

### [MPV-12842] Show Bit Depth of Audio Files
- Link: https://github.com/mpv-player/mpv/issues/12842  State: open
- Mechanism: Reporting source audio bit depth requires carrying the demuxed sample format or bits-per-sample declaration into public track metadata, separately from compressed bitrate and decoded float output format.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/Tracks.kt, TrackInfo; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/MediaSource.kt, PlayerStreamInfo
- Verdict: MISSING-FEATURE
- Why: Both public stream records expose bitrate, sample rate, and channels but no audio bit depth or source sample format. Decoding to float does not recover whether the file was authored as 16, 24, or 32 bit, so a track menu cannot show it.
- Severity if real: P3 polish

### [MPV-17236] An event for when ab-loop-b occurs?
- Link: https://github.com/mpv-player/mpv/issues/17236  State: open
- Mechanism: Counting A-B repetitions needs one observable occurrence exactly when playback crosses B and queues the seek back to A. A monotonic counter is safer than a lossy event stream when every crossing matters.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, handlePlaybackTime; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerEvent.kt, PlayerEvent; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerState.kt, PlaybackStats
- Verdict: MISSING-FEATURE
- Why: `handlePlaybackTime` queues the wrap but increments no statistic and emits no occurrence. PlayerEvent intentionally reserves countable facts for PlaybackStats, yet that class has no A-B loop counter, so a client must infer crossings from sampled position and can miss them.
- Severity if real: P3 polish

### [MPV-9955] Support for embedded lyrics in Ogg Vorbis audio tracks
- Link: https://github.com/mpv-player/mpv/issues/9955  State: open
- Mechanism: Timed lyrics stored in Vorbis comments must be recognized, parsed into timestamped lines, and exposed as a selectable subtitle-like timeline rather than left as one opaque metadata string.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/MediaSource.kt, PlayerMediaSource.metadata and PlayerPacket; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, buildSession and handleSubtitles
- Verdict: MISSING-FEATURE
- Why: Container tags remain a static metadata map, packets carry no timed metadata, and subtitle tracks come only from declared subtitle streams or caller-supplied files. No path converts embedded Ogg lyrics into cues.
- Severity if real: P2 quality/perf

### [MPV-12139] Hope to change subtitle peak luminosity throught  `--hdr-compute-peak` when playing pq transfer video
- Link: https://github.com/mpv-player/mpv/issues/12139  State: open
- Mechanism: HDR subtitle brightness can track scene luminance by deriving a reference-white level from the same rolling peak estimate used for tone mapping, then encoding the overlay into the output transfer function at that level.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/VideoRenderer.kt, SubtitleOverlay and OverlayImage; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/HdrToneMap.kt, HdrToneMap
- Verdict: MISSING-FEATURE
- Why: SubtitleOverlay carries only premultiplied RGBA images and viewport coordinates, with no luminance, transfer, or scene-peak field. The tone mapper uses a fixed source peak and 203-nit SDR target, so no dynamic estimate exists for subtitle composition.
- Severity if real: P2 quality/perf

### [MPV-13088] Scale up/down image subtitle resolution when using sub-scale
- Link: https://github.com/mpv-player/mpv/issues/13088  State: open
- Mechanism: Scaling a bitmap subtitle must resize both its authored placement rectangle and its pixel image, preferably with a quality resampler. Moving only the rectangle leaves the original low-resolution pixels unchanged and ignores a user scale factor.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/SubtitleRasterizer.kt, rasterize contract; kiteplayer-output/src/jvmMain/kotlin/io/github/yuroyami/kiteplayer/output/DesktopSubtitleRasterizer.kt, bitmap cue branch
- Verdict: MISSING-FEATURE
- Why: Bitmap regions scale their x and y placement from the authored canvas, but the original RgbaBitmap passes through untouched and `fontScale` is ignored on that branch. A bitmap cue therefore has no independent high-quality size control.
- Severity if real: P2 quality/perf

### [MPV-16351] Add possibility to edit the playlist
- Link: https://github.com/mpv-player/mpv/issues/16351  State: open
- Mechanism: Live playlist editing needs indexed insert, remove, and move operations that update the cursor atomically, preserving the identity of the currently playing item while indices around it change.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/KitePlayer.kt, openQueue, next, and previous; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, queueItems and jumpQueue
- Verdict: MISSING-FEATURE
- Why: A queue is installed as one immutable List and can only be replaced wholesale by another open. Public controls move to the next or previous item but expose no insertion, removal, reorder, or cursor-preserving replacement operation.
- Severity if real: P2 quality/perf

### [MPV-16945] [UX] Seek length should respect the playback speed value
- Link: https://github.com/mpv-player/mpv/issues/16945  State: open
- Mechanism: An effective-watch-time seek converts a requested wall-time interval to media time by multiplying it by playback speed. Five seconds of viewer time is 0.25 seconds of media at 0.05x and 10 seconds at 2x.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, seekByLater; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/SeekRequest.kt, SeekTarget.Relative and resolve
- Verdict: MISSING-FEATURE
- Why: Relative seeks add the supplied duration directly to the media position and never read playback speed. An application can calculate the scaled offset itself, but the player exposes no speed-aware seek command or policy.
- Severity if real: P3 polish

### [MPV-7982] Add way to modify external file stream paths before they are loaded
- Link: https://github.com/mpv-player/mpv/issues/7982  State: open
- Mechanism: MPV needs a pre-load hook because its own automatic external-file loader discovers a path and opens it before a client can rewrite unsupported schemes or escaping. Giving the client the typed source before any load provides the same control without a mutable global property.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/MediaItem.kt, SubtitleSource; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/KitePlayer.kt, addExternalSubtitle
- Verdict: IMMUNE
- Why: KitePlayer performs no hidden external-file discovery. Every external subtitle is a SubtitleSource the application constructs, either on MediaItem or in `addExternalSubtitle`, so the application can decode, normalize, proxy, or replace the URI before the engine sees it.
- Severity if real: P2 quality/perf

### [MPV-8488] mpv don't show audio tracks' titles in mp4 files
- Link: https://github.com/mpv-player/mpv/issues/8488  State: open
- Mechanism: The MP4 stream title tag must survive demux metadata mapping into the public track record used by track menus. Dropping the tag during that conversion leaves distinguishable tracks with only codec and language labels.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, StreamInfo.toPlayerStream; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/Tracks.kt, TrackInfo.label
- Verdict: IMMUNE
- Why: The FFmpeg adapter copies `StreamInfo.title` into PlayerStreamInfo, session assembly copies it into TrackInfo, and the public label renders title first. MP4 audio titles are not discarded at the player boundary.
- Severity if real: P3 polish

### [MPV-15340] hdr tonemapping: target-peak + hdr-compute-peak=yes desaturates and breaks the image in dark scenes (saturation, contrast, brightness)
- Link: https://github.com/mpv-player/mpv/issues/15340  State: open
- Mechanism: Combining a rolling computed peak, very low target contrast, and an ST 2094 tone curve can drive dark-scene luminance mapping close to the achromatic axis, visibly washing out saturation as the computed target changes.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/HdrToneMap.kt, HdrToneMap; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/VideoFrame.kt, ColorSpaceInfo
- Verdict: IMMUNE
- Why: KitePlayer has no computed-peak mode, target-contrast control, or ST 2094 operator. Its software and Metal paths use one fixed 1000-nit-to-203-nit BT.2390 curve, so the reported dynamic option interaction cannot occur, although the fixed policy is less adaptable.
- Severity if real: P2 quality/perf

### [MPV-4407] Constant twitching with -video-sync=display-resample
- Link: https://github.com/mpv-player/mpv/issues/4407  State: open
- Mechanism: Display-resample continuously retimes media to a measured refresh clock and chooses a cadence when source frames outnumber refresh intervals. An unstable refresh estimate or cadence around 120 fps produces alternating correction and visible twitching.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/SyncLaw.kt, targetDelayUs; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerState.kt, SyncMode
- Verdict: IMMUNE
- Why: No renderer reports display refresh timing to the core, and there is no display-resample mode. SyncLaw adjusts video only against the audio or video media clock, so the specific refresh-retiming feedback loop cannot form.
- Severity if real: P1 broken feature

### [MPV-11338] vo_gpu-next: `--hdr-compute-peak=yes` should ignore the dovi metadata.
- Link: https://github.com/mpv-player/mpv/issues/11338  State: open
- Mechanism: A compute-peak override must select pixel-derived luminance instead of Dolby Vision RPU values. Consulting both lets dynamic metadata continue to steer tone mapping even after the user explicitly requests measured peaks.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/VideoFrame.kt, ColorSpaceInfo; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/HdrToneMap.kt, HdrToneMap
- Verdict: IMMUNE
- Why: VideoFrame carries neither Dolby Vision RPU data nor a computed luminance statistic, and HdrToneMap reads neither. Its fixed curve cannot accidentally retain Dolby Vision metadata while a compute override is active.
- Severity if real: P2 quality/perf

### [MPV-12756] Brightness fluctuations with gpu-next, HDR10+ Profile B
- Link: https://github.com/mpv-player/mpv/issues/12756  State: open
- Mechanism: HDR10+ Profile B supplies scene or frame-level dynamic tone-map metadata. Misinterpreting its windows or repeatedly applying changing parameters makes output luminance pump between dark and bright states.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/VideoFrame.kt, ColorSpaceInfo; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/HdrToneMap.kt, HdrToneMap
- Verdict: IMMUNE
- Why: KitePlayer does not carry HDR10+ dynamic metadata into VideoFrame, and its tone-map parameters never change per frame. That makes the exact metadata-driven pumping impossible, while also leaving Profile B scene optimization unsupported.
- Severity if real: P2 quality/perf

### [MPV-14800] --sub-ass-style-overrides not applied when --sub-ass-override is set to force
- Link: https://github.com/mpv-player/mpv/issues/14800  State: open
- Mechanism: Subtitle override precedence must apply the user style map after selecting a force or strip policy. Applying force by replacing the parsed style object later in the pipeline silently erases the explicit override.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerConfig.kt, SubtitleConfig; kiteplayer-subtitles/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/subtitle/AssParser.kt, AssDocument and parseOverrideText
- Verdict: MISSING-FEATURE
- Why: The parser honors authored ASS styles and a subset of inline tags, but SubtitleConfig exposes no global style map and no yes, scale, force, or strip override policy. The precedence bug cannot appear until that requested override layer exists.
- Severity if real: P2 quality/perf

### [MPV-11936] During still frame of variable frame rate (VFR) video playback: OSD + Subtitles are not refreshed
- Link: https://github.com/mpv-player/mpv/issues/11936  State: open
- Mechanism: Tying OSD or subtitle invalidation to video-frame presentation stops updates when a variable-frame-rate stream intentionally holds one picture for seconds or minutes. Cue timing needs its own clock wake at every text boundary.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, timeAndPublishCues and publishOverlay
- Verdict: IMMUNE
- Why: Subtitle selection reads the master media clock independently of frame arrival, and schedules the next actor wake from the next cue edge. A long-held video frame therefore does not prevent a cue clear, change, or overlay publication.
- Severity if real: P1 broken feature

### [MPV-15679] Timing discrepancy with bd:// playback after seeking
- Link: https://github.com/mpv-player/mpv/issues/15679  State: open
- Mechanism: A Blu-ray title is a virtual timeline assembled from transport-stream clips. Seeking must translate title time through play-item boundaries while keeping external subtitle time on that same normalized axis; using the raw clip timestamp shifts cues after a seek.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/MediaItem.kt, MediaItem; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, TimestampMapper
- Verdict: IMMUNE
- Why: KitePlayer has no Blu-ray title or play-item layer and ships no disc timeline resolver. TimestampMapper normalizes one ordinary demux timeline, so the specific `bd://` virtual-title translation bug is unreachable, although optical-disc playback is absent.
- Severity if real: P1 broken feature

### [MPV-14729] audio stops playing for 3-5s when using seek backward (#LEFT  seek -5) with external audio track (.mka)
- Link: https://github.com/mpv-player/mpv/issues/14729  State: open
- Mechanism: A separately demuxed audio file needs its own seek, decode flush, and timestamp remap to the main video's landing. If that source refills later than the container video, playback resumes with seconds of silence.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/MediaItem.kt, MediaItem; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/Tracks.kt, TrackInfo
- Verdict: MISSING-FEATURE
- Why: MediaItem can attach external subtitles but has no external audio source, and Tracks contains only streams exposed by the one opened container. There is no second demux cursor or cross-source seek rendezvous to provide this capability.
- Severity if real: P1 broken feature

### [MPV-10396] Caching with backward seeking is not optimized
- Link: https://github.com/mpv-player/mpv/issues/10396  State: open
- Mechanism: Preserving several disjoint cached byte ranges prevents a backward fill from evicting already downloaded middle data. A single moving window instead creates a hole and later downloads bytes that were previously cached.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/CachingMediaIo.kt, CachingMediaIo; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerState.kt, Progress.bufferedRanges
- Verdict: MISSING-FEATURE
- Why: CachingMediaIo explicitly owns one contiguous RAM window; a seek outside it clears every chunk and starts a new window. Progress likewise documents one range, so the multi-range retention strategy from the issue is absent.
- Severity if real: P2 quality/perf

### [MPV-16660] frame-back-step not working for audio files
- Link: https://github.com/mpv-player/mpv/issues/16660  State: open
- Mechanism: Audio-only stepping needs a defined time quantum and a seek or sample-release operation in both directions. Reusing video-frame stepping leaves no frame queue to advance, and reverse stepping additionally needs its own backward command.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/KitePlayer.kt, stepFrame; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, stepOneFrame
- Verdict: MISSING-FEATURE
- Why: `stepFrame` requires a selected video track and releases exactly one decoded video frame. There is no audio-step command, configurable audio quantum, or backward frame-step API, so audio-only media is explicitly refused.
- Severity if real: P2 quality/perf

### [MPV-12322] wrong audio pts on push-based audio outs due to incorrect driver delay calc
- Link: https://github.com/mpv-player/mpv/issues/12322  State: open
- Mechanism: Deriving audible PTS by subtracting an estimated push-driver delay from submitted samples can be wrong by whole buffers. Near EOF or a loop that error makes remaining time nonzero or carries a negative position into the next iteration.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, anchorLocked and position; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/AudioSink.kt, AudioRenderCallback
- Verdict: IMMUNE
- Why: The media clock is anchored to the timestamp and `audibleAtNanos` deadline reported for the actual rendered buffer. It does not subtract `AudioSink.latencyNanos` or estimate a push queue delay, so the issue's faulty-delay arithmetic is absent.
- Severity if real: P1 broken feature

### [MPV-5032] mpv does not support video rotation metadata in OpenCamera-recorded MP4 video
- Link: https://github.com/mpv-player/mpv/issues/5032  State: open
- Mechanism: Phone recordings store portrait orientation in the MP4 display matrix rather than rotating coded pixels. The demuxed rotation must travel with every frame and be applied by output geometry.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, StreamInfo.toPlayerStream and KiteCodecVideoFrame; kiteplayer-output/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/output/FrameLayout.kt, frameLayout
- Verdict: IMMUNE
- Why: The adapter copies the container display-matrix rotation into PlayerStreamInfo and every decoded VideoFrame. FrameLayout normalizes quarter turns and swaps the displayed footprint before every built-in renderer draws, so 90-degree phone metadata is not dropped.
- Severity if real: P1 broken feature

### [MPV-16451] [FEATURE REQUEST] Automatic .CUE handling
- Link: https://github.com/mpv-player/mpv/issues/16451  State: open
- Mechanism: A matching CUE sheet can be parsed into chapter boundaries for one audio file, while opening the CUE itself can expand its referenced files into a playlist. Both forms require resolving relative paths and track indexes.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/MediaItem.kt, MediaItem; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerState.kt, Chapter; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/KitePlayer.kt, openQueue
- Verdict: MISSING-FEATURE
- Why: Chapters come only from the opened container, MediaItem has no external chapter or CUE field, and openQueue accepts already constructed MediaItems rather than parsing a playlist document. No automatic sidecar discovery or CUE parser exists.
- Severity if real: P2 quality/perf

### [MPV-16488] HDR Video unable to match original output
- Link: https://github.com/mpv-player/mpv/issues/16488  State: wontfix
- Mechanism: The sample encoded studio-range values while declaring full range, so correct range expansion was skipped and much of the luminance signal disappeared. Explicitly overriding the source to limited range restored the intended output.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/MediaItem.kt, videoFilter; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/Conversions.kt, PackedCoefficients.of
- Verdict: IMMUNE
- Why: KitePlayer honors the declared range by default, but MediaItem can attach a raw FFmpeg `setparams=range=tv` filter before presentation. The converted frame metadata then drives the limited-range offsets and scales in PackedCoefficients, providing the same explicit repair for a falsely tagged file.
- Severity if real: P2 quality/perf

### [MPV-10129] HDR signal peak luminance metadata not sent to the display
- Link: https://github.com/mpv-player/mpv/issues/10129  State: closed-fixed
- Mechanism: HDR passthrough requires source mastering and MaxCLL values to reach the platform swapchain metadata API. Libplacebo added input-derived color-space hints, and the reporter confirmed that MaxCLL reached the display in fullscreen after the merge.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/VideoFrame.kt, ColorSpaceInfo; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/HdrToneMap.kt, HdrToneMap
- Verdict: MISSING-FEATURE
- Why: ColorSpaceInfo carries matrix, primaries, transfer, range, and chroma location but no mastering display, MaxCLL, or MaxFALL metadata. Built-in software conversion instead uses a fixed 1000-nit to 203-nit curve, so no HDR output path can signal the source peak to a capable display.
- Severity if real: P1 broken feature

### [MPV-16808] mpv doesn't respect manually provided target-prim=dci-p3 flag anymore
- Link: https://github.com/mpv-player/mpv/issues/16808  State: closed-fixed
- Mechanism: An output-primary override must survive target color-space negotiation and be labeled consistently for the compositor. Sending pixels transformed for one gamut while hinting another expands or compresses saturation; the merged fix restored an explicit non-strict target-hint route.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerConfig.kt, PlayerConfig; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/HdrToneMap.kt, HdrToneMap
- Verdict: MISSING-FEATURE
- Why: PlayerConfig has no output primaries or color-space-hint policy. HDR conversion always folds BT.2020 into BT.709 and the built-in target is fixed, so an application cannot request DCI-P3 or choose strict versus advisory target labeling.
- Severity if real: P2 quality/perf

### [MPV-13794] Audio passthrough broken on newer builds >22.03.2024
- Link: https://github.com/mpv-player/mpv/issues/13794  State: closed-fixed
- Mechanism: An FFmpeg API change broke construction of the encoded SPDIF stream, and one test build also omitted the SPDIF muxer entirely. Correcting codec-parameter initialization and enabling the muxer restored AC3, EAC3, DTS, and TrueHD bitstream playback.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, KiteCodecAudioDecoder and KiteCodecAudioBuffer; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/AudioPipeline.kt, process
- Verdict: MISSING-FEATURE
- Why: Every compressed audio packet is decoded into interleaved F32 samples before it enters AudioPipeline. There is no SPDIF or IEC61937 muxer, encoded-buffer type, or sink capability negotiation, so receiver-side compressed passthrough is absent rather than exposed to this regression.
- Severity if real: P1 broken feature

### [MPV-7787] WASAPI broken
- Link: https://github.com/mpv-player/mpv/issues/7787  State: closed-fixed
- Mechanism: Synchronous audio-output control held the pull-buffer lock while waiting for work on the audio thread. That thread tried to acquire the same lock while feeding WASAPI, producing a deadlock during volume or exclusive-output control.
- KitePlayer code checked: kiteplayer-output/src/jvmMain/kotlin/io/github/yuroyami/kiteplayer/output/DesktopAudioSink.kt, lock discipline and joinWriterOutsideLock
- Verdict: IMMUNE
- Why: The Windows-facing JVM sink uses SourceDataLine rather than WASAPI. Its writer takes only the position lock, lifecycle methods release the lifecycle lock before joining, and control never synchronously dispatches onto a writer that must reacquire the caller's lock.
- Severity if real: P1 broken feature

### [MPV-2654] MPV crashes when sending DSD audio through PulseAudio
- Link: https://github.com/mpv-player/mpv/issues/2654  State: closed-fixed
- Mechanism: PulseAudio's format validator accepted a DSD-derived sample rate above its actual hard maximum, then stream creation failed inside the output path. The upstream discussion fixed validation and raised the limit, while documenting that a player can instead resample to an accepted rate.
- KitePlayer code checked: kiteplayer-output/src/jvmMain/kotlin/io/github/yuroyami/kiteplayer/output/DesktopAudioSink.kt, open; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/AudioPipeline.kt, AudioPipeline
- Verdict: IMMUNE
- Why: KitePlayer ships no PulseAudio sink and never submits DSD directly. DesktopAudioSink negotiates a SourceDataLine PCM format, and AudioPipeline resamples decoded float PCM to the accepted device rate before any write.
- Severity if real: P0 crash/dataloss

### [MPV-3434] [Feature request] arbitrary rotation
- Link: https://github.com/mpv-player/mpv/issues/3434  State: wontfix
- Mechanism: Arbitrary-angle rotation needs an affine texture transform before final scaling; doing it through a CPU filter is slower and can enlarge or crop the canvas unless output geometry accounts for the rotated corners.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/VideoFrame.kt, rotationDegrees; kiteplayer-output/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/output/FrameLayout.kt, quarterTurn and frameLayout
- Verdict: MISSING-FEATURE
- Why: VideoFrame documents only 0, 90, 180, and 270 degree presentation, and quarterTurn draws every other angle unrotated. FrameLayout therefore has no affine footprint or crop policy for arbitrary rotation.
- Severity if real: P2 quality/perf

### [MPV-5386] auto-rotate full screen videos
- Link: https://github.com/mpv-player/mpv/issues/5386  State: wontfix
- Mechanism: Container display-matrix rotation is an instruction, not transformed pixels, so a player needs a per-file policy that can apply, negate, or ignore it. MPV's `video-rotate=no` solved the report by suppressing the authored rotation.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/VideoFrame.kt, rotationDegrees; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/VideoTransform.kt, VideoTransform; kiteplayer-output/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/output/FrameLayout.kt, frameLayout
- Verdict: MISSING-FEATURE
- Why: KitePlayer always forwards and applies the frame's quarter-turn metadata. VideoTransform exposes aspect, zoom, and pan only, so callers cannot disable or offset an incorrect or unwanted container rotation.
- Severity if real: P2 quality/perf

### [MPV-6728] when i rotate a video,the color is changed
- Link: https://github.com/mpv-player/mpv/issues/6728  State: closed-fixed
- Mechanism: The old GPU path mishandled 90 and 270 degree rotation of VAAPI hardware frames, producing colored stripes or monochrome output. Disabling hardware decode avoided it, and the maintainer identified the merged hardware-frame rotation fix.
- KitePlayer code checked: kiteplayer-ffmpeg/src/linuxMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/PlatformDecoderSelection.linux.kt, platformDecoderSelection; kiteplayer-output/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/output/FrameLayout.kt, frameLayout
- Verdict: IMMUNE
- Why: The Linux backend explicitly advertises no VAAPI route and decodes into software frames. Rotation is applied as output geometry after conversion, so the VAAPI texture-coordinate and chroma-plane path that corrupted color is absent.
- Severity if real: P1 broken feature

### [MPV-5869] Rotation and mirroring while playing the video does not work properly
- Link: https://github.com/mpv-player/mpv/issues/5869  State: wontfix
- Mechanism: Runtime orientation needs two independent operations: a cumulative quarter-turn and horizontal or vertical reflection. MPV exposed them through `video-rotate` and hflip or vflip filters, with key bindings cycling the values.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/VideoTransform.kt, VideoTransform; kiteplayer-output/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/output/FrameLayout.kt, frameLayout
- Verdict: MISSING-FEATURE
- Why: VideoTransform has aspect, zoom, and pan but no user rotation or reflection axes. FrameLayout consumes only the frame's authored rotation, so callers cannot mirror a picture or rotate it interactively.
- Severity if real: P2 quality/perf

### [MPV-11721] gpu-next causes video to freeze after a while on Android (Qualcomm Adreno only?)
- Link: https://github.com/mpv-player/mpv/issues/11721  State: closed-fixed
- Mechanism: A libplacebo sync-object resource leak accumulated file descriptors until Adreno failed buffer dequeue and sync-object creation with `Too many open files`, freezing video. The reporter confirmed the cited libplacebo fix.
- KitePlayer code checked: kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/MediaCodecBufferFrame.kt, renderAt and close; kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/AndroidGpuImageVideoRenderer.kt, close
- Verdict: IMMUNE
- Why: KitePlayer does not embed libplacebo. Each MediaCodec output frame has an atomic exactly-once release path, and the GPU-image renderer explicitly closes acquired images, output queues, SurfaceTexture state, and its bridge, so the leaking sync-object allocator is not present.
- Severity if real: P1 broken feature

### [MPV-4738] How to prevent autoloading next file (for playlists)
- Link: https://github.com/mpv-player/mpv/issues/4738  State: wontfix
- Mechanism: End-of-file policy must be separable from explicit next-item navigation. MPV's keep-open mode holds the completed item instead of consuming the next playlist entry, while still allowing the user to advance later.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/KitePlayer.kt, openQueue; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, handleQueueAdvance
- Verdict: MISSING-FEATURE
- Why: An open queue always advances when the current item reaches Ended, except when LoopMode.One repeats it. There is no hold-at-end or manual-advance-only queue policy.
- Severity if real: P2 quality/perf

### [MPV-12492] Incorrect duration of ASS subtitles
- Link: https://github.com/mpv-player/mpv/issues/12492  State: open
- Mechanism: FFmpeg normalized a negative standalone ASS duration to the gap before the next event. A later empty event therefore stretched the prior visible cue, and mpv no longer had the original end timestamp with which to correct it.
- KitePlayer code checked: kiteplayer-subtitles/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/subtitle/AssParser.kt, AssParser.parse; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, parseExternalSubtitle
- Verdict: IMMUNE
- Why: Local external ASS files bypass FFmpeg's subtitle demux queue and go through the common parser, which reads each Dialogue start and end directly and drops nonpositive intervals. The faulty duration substitution never occurs on the implicated standalone-file path.
- Severity if real: P1 broken feature

### [MPV-11612] [FramedropBug] mpv always framedrops with specific subtitle on?
- Link: https://github.com/mpv-player/mpv/issues/11612  State: open
- Mechanism: Large ASS vector drawings with extreme blur require expensive rasterization, and moving subpixel positions limit bitmap-cache reuse. The libass analysis found the cost expected, with certain scales becoming especially unlucky rather than exposing a scheduler fault.
- KitePlayer code checked: kiteplayer-subtitles/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/subtitle/AssParser.kt, parseOverrideText; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/SubtitleRasterizer.kt, rasterize
- Verdict: MISSING-FEATURE
- Why: The current ASS tier drops vector drawings and ignores unknown tags such as blur, so it cannot reproduce the expensive authored effect. Full libass-grade vector, animated, blurred typesetting is the missing capability; the platform rasterizer only receives the reduced cue model.
- Severity if real: P2 quality/perf

### [MPV-5419] playback lagging when watching stream for long time
- Link: https://github.com/mpv-player/mpv/issues/5419  State: open
- Mechanism: A delayed live segment adds its download stall to the viewer's distance behind the live edge. Repeated stalls accumulate latency unless playback temporarily speeds up or skips forward once the buffered distance crosses a target.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, handleBuffering and handlePlaybackRestart; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/KitePlayer.kt, setSpeed
- Verdict: MISSING-FEATURE
- Why: Rebuffer recovery resumes from the same media position and speed. Callers can set a fixed speed, but no live-edge estimate, target latency, automatic catch-up rate, or forward-skip policy exists.
- Severity if real: P1 broken feature

### [MPV-17338] Duplicate subtitles when seeking HLS/m3u8 URLs
- Link: https://github.com/mpv-player/mpv/issues/17338  State: open
- Mechanism: HLS and DASH subtitle packets often have no byte position. When seek did not clear libass state, mpv's seen-packet check ignored those packets and re-added events from the new demux pass; using PTS as a fallback identity or clearing subtitle state prevented stacking.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runSeek and clearBuffers; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSubtitleDecoder.kt, flush
- Verdict: IMMUNE
- Why: Every seek advances the generation, flushes every subtitle packet queue and decoder, clears all accumulated cue tables, and publishes an empty overlay before refilling. The old event set cannot remain resident for new HLS packets to duplicate.
- Severity if real: P1 broken feature

### [MPV-9535] [Windows][Audio] ao-wasapi driver sometimes cutting audio files off before the end of the file
- Link: https://github.com/mpv-player/mpv/issues/9535  State: open
- Mechanism: Pull outputs marked their shared `playing` state false when no more samples were available, even though submitted samples were still queued in the hardware device. Shutdown then stopped WASAPI or AudioTrack before the last roughly 100 milliseconds became audible.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, finishDecoded and drain; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, handleEof; kiteplayer-output/src/jvmMain/kotlin/io/github/yuroyami/kiteplayer/output/DesktopAudioSink.kt, drain; kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/AudioTrackSink.kt, drain
- Verdict: IMMUNE
- Why: EOF first flushes every DSP tail into the ring, waits for decoded handoff and ring depletion, then calls the sink's own drain. Both push-device wrappers submit the final short block, join outside their lifecycle locks, and let the platform queue play out before stopping.
- Severity if real: P1 broken feature

### [MPV-3739] Choppy playback/framedrops with videos with fps close to the display refresh rate
- Link: https://github.com/mpv-player/mpv/issues/3739  State: closed-fixed
- Mechanism: macOS OpenGL used a single buffered surface and issued `glFlush` too early. Near refresh-rate cadence, that forced synchronization before the next drawable was ready; removing the early flush fixed the stutter and the reporter confirmed the patch.
- KitePlayer code checked: kiteplayer-output/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/output/MetalFrameComposer.kt, render; kiteplayer-output/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/output/MetalVideoRenderer.kt, present
- Verdict: IMMUNE
- Why: Apple output is a Metal command-buffer and CAMetalDrawable path, not the Cocoa OpenGL single-buffer path. It contains no `glFlush` or equivalent early OpenGL synchronization point.
- Severity if real: P1 broken feature

### [MPV-4478] Coreaudio_exclusive only work with audio-format=s16 on a 24bit external DAC
- Link: https://github.com/mpv-player/mpv/issues/4478  State: open
- Mechanism: Exclusive 24-bit output must distinguish packed three-byte samples from 24 valid bits in a four-byte container, set CoreAudio's alignment flags correctly, and make callback stride match physical bytes per frame. The wrong combination produced silence or sharp noise on real DACs.
- KitePlayer code checked: kiteplayer-output/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/output/CoreAudioSink.kt, openWithRing; kiteplayer-rt/native/src/kite_rt_coreaudio.c, kprt_sink_create
- Verdict: MISSING-FEATURE
- Why: KitePlayer configures DefaultOutput or RemoteIO for interleaved F32 and has no exclusive, integer, or physical-format selection route. Packed 24-bit DAC output is absent, so its necessary alignment and stride contract is absent too.
- Severity if real: P1 broken feature

### [MPV-6942] "frame-step" skips extra frames upon unpausing proportional to times tapped
- Link: https://github.com/mpv-player/mpv/issues/6942  State: wontfix
- Mechanism: MPV implemented each step by temporarily unpausing and accumulating a step counter. WASAPI retained a sliver of audio on every step, so ordinary unpause had to discard or catch up and skipped video in proportion to the number of taps.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, stepOneFrame
- Verdict: IMMUNE
- Why: stepOneFrame releases exactly one already-decoded video frame while status remains Paused. It does not start audio, alter play intent, or accumulate a deferred step counter, so repeated taps cannot queue audio or a later catch-up burst.
- Severity if real: P1 broken feature

### [MPV-4019] mpv step forward is fast but step back is slow
- Link: https://github.com/mpv-player/mpv/issues/4019  State: wontfix
- Mechanism: Inter-frame codecs cannot decode a previous frame in isolation. Smooth reverse stepping needs a cache of decoded frames, plus a seek to an earlier keyframe and forward refill when that cache empties; changing direction also invalidates reversal state.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/KitePlayer.kt, stepFrame; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, stepOneFrame
- Verdict: MISSING-FEATURE
- Why: The public command and core implementation step forward only. There is no reverse command, decoded reversal cache, backward keyframe refill, or direction state.
- Severity if real: P2 quality/perf

### [MPV-1173] PulseAudio automute
- Link: https://github.com/mpv-player/mpv/issues/1173  State: closed-fixed
- Mechanism: PulseAudio's `module-role-cork` muted streams tagged with the video media role whenever phone audio was active, and reopening an audio track could reapply the cork. MPV fixed it by removing that role tag.
- KitePlayer code checked: kiteplayer-output/src/jvmMain/kotlin/io/github/yuroyami/kiteplayer/output/DesktopAudioSink.kt, open; kiteplayer-output/src/jvmMain/kotlin/io/github/yuroyami/kiteplayer/output/SourceDataLineDriver.kt, PlatformSourceDataLineDriver
- Verdict: IMMUNE
- Why: Desktop output talks directly to Java SourceDataLine and sets no PulseAudio media role or cork policy. Track changes therefore cannot trigger `module-role-cork` through player-supplied metadata.
- Severity if real: P1 broken feature

### [MPV-2039] VideoToolbox based hwaccel decoder?
- Link: https://github.com/mpv-player/mpv/issues/2039  State: closed-fixed
- Mechanism: FFmpeg's VideoToolbox hwaccel returns CVPixelBuffer-backed frames. MPV reused its Apple zero-copy mapper and preferred VideoToolbox over deprecated VDA after the FFmpeg merge and successful testing.
- KitePlayer code checked: kiteplayer-ffmpeg/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/PlatformDecoderSelection.apple.kt, platformDecoderSelection; kiteplayer-ffmpeg/src/nativeMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/MetalFrameAccess.kt, corePixelBufferOrNull; kiteplayer-output/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/output/MetalFrameComposer.kt, render
- Verdict: IMMUNE
- Why: Eligible Apple codecs already select the VideoToolbox hwaccel. CVPixelBuffer-backed frames expose their planes to Metal directly, so the capability and zero-copy mechanism requested upstream are present.
- Severity if real: P2 quality/perf

### [MPV-11365] MPV can't play non-standard M3U8 stream with faked video parts as 1x1 pixel images
- Link: https://github.com/mpv-player/mpv/issues/11365  State: closed-fixed
- Mechanism: A nonstandard HLS segment placed a valid 1x1 PNG before its MPEG-TS payload. Lavf committed to the PNG probe and never searched for the later transport-stream sync bytes; the cited FFmpeg fix and current-build tests restored playback.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, KiteCodecSourceFactory.open; gradle/libs.versions.toml, kitecodec dependency
- Verdict: IMMUNE
- Why: KitePlayer delegates HLS probing to the KiteCodec 0.1.4 backend pinned to FFmpeg n8.0, which is newer than the cited lavf probe fix. It does not carry the affected 2022 demux implementation.
- Severity if real: P1 broken feature

### [MPV-11674] mpv handling of streamlink hls seems broken with  mpv-x86_64-v3-20230507-git-a1580b6 version
- Link: https://github.com/mpv-player/mpv/issues/11674  State: closed-fixed
- Mechanism: An FFmpeg HLS detection regression rejected redirected streams or URLs without a conventional extension and exposed only one Twitch DVR segment. An upstream FFmpeg change plus mpv's forced-lavf workaround restored the tested streams.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, KiteCodecSourceFactory.open; gradle/libs.versions.toml, kitecodec dependency
- Verdict: IMMUNE
- Why: The source opens through KiteCodec 0.1.4 and FFmpeg n8.0, well after the 2023 HLS detection fix. KitePlayer therefore does not ship the affected probe version or mpv's old pre-probe chain.
- Severity if real: P1 broken feature

### [MPV-12221] mid playback "[vo/gpu-next/libplacebo] Spent 98.917 ms translating GLSL to SPIR-V" cause frames to drop
- Link: https://github.com/mpv-player/mpv/issues/12221  State: wontfix
- Mechanism: The first PGS cue needed a different alpha shader from text overlays, so its first use compiled GLSL to SPIR-V on the presentation path and blocked for roughly 70 ms. A later Dolby Vision peak change could similarly specialize a new tone-mapping shader after playback had started.
- KitePlayer code checked: kiteplayer-output/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/output/MetalVideoSupport.kt, MetalPipelines.of; kiteplayer-output/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/output/MetalFrameComposer.kt, compose
- Verdict: IMMUNE
- Why: `MetalPipelines.of` creates the picture and overlay pipelines when the renderer is built, and `compose` only switches between those cached states. KitePlayer has no PGS decoder or Dolby Vision RPU specialization that could introduce a new cue-time or mid-frame shader variant.
- Severity if real: P2 quality/perf

### [MPV-17626] vo=gpu-next has severe performance regression with 8K AV1 10-bit HDR content compared to vo=gpu
- Link: https://github.com/mpv-player/mpv/issues/17626  State: wontfix
- Mechanism: Without D3D11VA zero-copy, each 8K 10-bit frame paid a large GPU upload, and dynamic HDR peak computation added another full-frame compute pass. Those costs together exceeded the frame deadline; enabling zero-copy and disabling compute peak restored smooth playback.
- KitePlayer code checked: kiteplayer-ffmpeg/src/mingwMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/PlatformDecoderSelection.mingw.kt, platformDecoderSelection; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/HdrToneMap.kt, HdrToneMap
- Verdict: IMMUNE
- Why: The Windows backend deliberately offers no hardware decode route, and KitePlayer has no gpu-next renderer or frame-histogram peak computation. It can still be too slow for 8K software decode, but the cited zero-copy loss plus dynamic compute-peak regression cannot occur.
- Severity if real: P2 quality/perf

### [MPV-5861] Color banding with HDR
- Link: https://github.com/mpv-player/mpv/issues/5861  State: wontfix
- Mechanism: A Windows D3D11 video-processing path accepted a 10-bit P010 PQ surface but emitted 8-bit NV12. Quantizing before HDR conversion discarded code values, producing bright clipping and visible bands.
- KitePlayer code checked: kiteplayer-ffmpeg/src/mingwMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/PlatformDecoderSelection.mingw.kt, platformDecoderSelection
- Verdict: IMMUNE
- Why: KitePlayer does not plumb D3D11VA frames or D3D11 video processing on Windows. Its selection file always chooses software decode, so there is no P010-to-NV12 VPP stage that can silently reduce HDR precision.
- Severity if real: P2 quality/perf

### [MPV-13033] mpv + yt-dlp + sponsorblock + seek = no new video frames
- Link: https://github.com/mpv-player/mpv/issues/13033  State: closed-fixed
- Mechanism: Seeking into an uncached VP9 HLS rendition made FFmpeg report EOF while audio continued and video stopped. The thread reproduced it in ffplay, isolated it to VP9 over HLS rather than SponsorBlock, and records that the FFmpeg defect was fixed upstream.
- KitePlayer code checked: gradle/libs.versions.toml, kitecodec version; ../KiteCodec/vendor/ffmpeg/RELEASE, pinned FFmpeg release
- Verdict: IMMUNE
- Why: KitePlayer resolves KiteCodec 0.1.4, whose embedded backend is pinned to FFmpeg 8.0, well after the upstream repair reported in the issue. The affected old HLS seek implementation is not in the pinned demuxer.
- Severity if real: P1 broken feature

### [MPV-13030] Upmix 2.0 to 5.1
- Link: https://github.com/mpv-player/mpv/issues/13030  State: wontfix
- Mechanism: A useful stereo-to-surround upmix is not just channel duplication. FFmpeg's surround filter performs RDFT-based sound-field extraction, while a pan filter can only form linear channel combinations; the higher-quality transform also introduces about 200 ms of latency that the clock must report.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/ChannelMixer.kt, matrixFor and passThrough; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/AudioPipeline.kt, AudioPipeline
- Verdict: MISSING-FEATURE
- Why: `ChannelMixer` explicitly leaves target speakers absent from the source silent, and `AudioPipeline` has no surround extraction or extensible audio-filter stage. A real upmixer would add spatial value for stereo content and must surface its transform latency to A/V sync.
- Severity if real: P2 quality/perf

### [MPV-9071] mpv has wrong colors with any tone mapping
- Link: https://github.com/mpv-player/mpv/issues/9071  State: wontfix
- Mechanism: After BT.2020-to-BT.709 conversion, independently clipping negative or over-range RGB components changes the direction of the color vector, not just its magnitude. Bright saturated HDR colors therefore shift hue, such as red becoming pink; disabling gamut clipping avoided the regression.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/HdrToneMap.kt, mapInPlace; kiteplayer-output/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/output/MetalVideoSupport.kt, kp_tone_map
- Verdict: SUSPECT
- Why: Both KitePlayer tone mappers multiply BT.2020 RGB by the BT.709 matrix, clamp negative components independently, and later clamp encoded channels independently. Bright saturated HDR primaries outside BT.709 can take the same hue-changing path; no accepted pixel-oracle test proves the result yet.
- Severity if real: P2 quality/perf

### [MPV-5960] The hdr-compute-peak changes the brightness unaturally.
- Link: https://github.com/mpv-player/mpv/issues/5960  State: wontfix
- Mechanism: Dynamic peak detection treated a tiny but extremely bright static logo as the frame's exposure reference. The logo dimmed the rest of the picture, and its fast fade also crossed the scene-change threshold, making the simulated eye adaptation jump.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/HdrToneMap.kt, mapInPlace; kiteplayer-output/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/output/MetalVideoSupport.kt, kp_tone_map
- Verdict: IMMUNE
- Why: KitePlayer computes no histogram, scene history, or local peak. Both paths use a fixed 1000-nit source peak and a fixed curve, so a small bright overlay cannot change the exposure applied to its neighbors or to a later frame.
- Severity if real: P2 quality/perf

### [MPV-2091] Keep panning behavior consistent regardless of video orientation
- Link: https://github.com/mpv-player/mpv/issues/2091  State: wontfix
- Mechanism: Pan direction becomes ambiguous if some paths apply pan in content coordinates before rotation while others apply it in screen coordinates after rotation. A stable contract needs one transform order and one documented sign convention.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/VideoTransform.kt, VideoTransform; kiteplayer-output/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/output/FrameLayout.kt, frameLayout; kiteplayer-output/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/output/MetalVideoSupport.kt, quadUniformsFor
- Verdict: IMMUNE
- Why: `VideoTransform` documents rotation before fit, zoom, and screen-space pan. Both shared frame layout and Metal fold rotation into the content shape first, then apply positive-right and positive-down pan, so orientation cannot reverse the public control's meaning.
- Severity if real: P3 polish

### [MPV-266] --ad-spdif-dtshd=yes not working
- Link: https://github.com/mpv-player/mpv/issues/266  State: closed-fixed
- Mechanism: DTS-HD and TrueHD passthrough filled a default 64 KiB ALSA buffer in about 21 ms, but mpv's single playback loop could sleep up to 50 ms for video timing. Moving audio output to its own thread stopped repeated compressed-stream underruns.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/Decoders.kt, AudioFormat; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, open and submit
- Verdict: MISSING-FEATURE
- Why: KitePlayer decodes every audio stream to PCM and has no IEC 61937 passthrough format. If passthrough is added, it should keep the current dedicated audio feeder and ring instead of feeding a small device buffer from the video scheduler; that preserves the timing lesson behind mpv's fix.
- Severity if real: P2 quality/perf

### [MPV-594] Color Management (OS X): Use system configured display profile automatically
- Link: https://github.com/mpv-player/mpv/issues/594  State: closed-fixed
- Mechanism: Correct color management must discover the ICC profile for the display that contains the video window, build the display transform, and refresh it when the window changes displays or the user changes profiles. A profile captured only at launch becomes wrong after either event.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/RenderQuality.kt, RenderQuality; kiteplayer-output/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/output/MetalVideoSupport.kt, kp_tone_map
- Verdict: MISSING-FEATURE
- Why: KitePlayer has no ICC-profile field, operating-system display-profile observer, or 3D display LUT. Adding automatic profile selection would preserve calibrated color across multiple monitors and live profile changes.
- Severity if real: P2 quality/perf

### [MPV-2685] Make "sphinx" the default for 'tscale' interpolation!
- Link: https://github.com/mpv-player/mpv/issues/2685  State: wontfix
- Mechanism: Temporal convolution kernels trade smooth motion against blur and ringing. Mitchell was smoother but visibly blurred motion, while triangle and sphinx-windowed choices preserved more detail, so one fixed interpolation kernel cannot suit every cadence and scene.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/VideoPlayback.kt, tick; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/RenderQuality.kt, VideoScaler
- Verdict: MISSING-FEATURE
- Why: `tick` can present, hold, repeat, or drop decoded frames, and `VideoScaler` controls only spatial resampling. There is no temporal interpolation stage or kernel policy, so the player cannot offer the upstream smoothness-versus-sharpness choice.
- Severity if real: P2 quality/perf

### [MPV-2230] Implement NNEDI3 with GPU backend
- Link: https://github.com/mpv-player/mpv/issues/2230  State: closed-fixed
- Mechanism: Running edge-directed neural upscaling through an offline VapourSynth pipe is too slow for interactive playback. A GPU hook keeps the decoded frame on device and evaluates the learned neighborhood filter as part of rendering.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/RenderQuality.kt, VideoScaler; kiteplayer-output/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/output/MetalVideoSupport.kt, kp_picture
- Verdict: MISSING-FEATURE
- Why: KitePlayer exposes only bilinear and Catmull-Rom spatial scaling, and its Metal fragment program has no custom shader-hook or NNEDI3 pass. A GPU neural scaler would improve low-resolution animation and line art without an offline transcode.
- Severity if real: P2 quality/perf

### [MPV-6210] AUDIO EQUALIZER
- Link: https://github.com/mpv-player/mpv/issues/6210  State: wontfix
- Mechanism: A parametric or graphic equalizer applies independent gains over frequency bands in decoded PCM. It belongs before final volume and must retain filter history between buffers so band edges do not reset at every packet.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/AudioPipeline.kt, AudioPipeline and process
- Verdict: MISSING-FEATURE
- Why: The fixed audio chain contains channel mixing, resampling, tempo, and gain only, with no equalizer stage or caller-supplied audio filter. Per-band correction would help speakers, headphones, and difficult recordings without changing the media file.
- Severity if real: P2 quality/perf

### [MPV-731] Smooth motion like madvr?
- Link: https://github.com/mpv-player/mpv/issues/731  State: closed-fixed
- Mechanism: Smooth-motion playback retains neighboring source frames and blends or interpolates at display presentation times, converting a 23.976 fps cadence into regular output on a 60 Hz display instead of repeating frames in a visible 3:2 pattern.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/VideoPlayback.kt, tick; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/RenderQuality.kt, RenderQuality
- Verdict: MISSING-FEATURE
- Why: KitePlayer schedules one decoded frame at a time and can only present, hold, repeat, or drop it. It has no neighboring-frame ownership or temporal blend pass, so low-frame-rate pans still use repeated-frame cadence on a fixed high-refresh display.
- Severity if real: P2 quality/perf

### [MPV-8991] Stop caching beyond `--end`
- Link: https://github.com/mpv-player/mpv/issues/8991  State: open
- Mechanism: A logical playback end should also bound network read-ahead. If only presentation stops at the end point while the demux cache keeps filling, the player downloads bytes the user explicitly said will never play.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/MediaItem.kt, MediaItem; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/CachingMediaIo.kt, read
- Verdict: MISSING-FEATURE
- Why: `MediaItem` has a start position but no end position, and `CachingMediaIo.read` has no logical byte or time boundary beyond upstream EOF. A bounded playback segment could save substantial bandwidth on long remote media.
- Severity if real: P2 quality/perf

### [MPV-13290] Auto reselect the subtitle after manual audio track switch
- Link: https://github.com/mpv-player/mpv/issues/13290  State: open
- Mechanism: Automatic subtitle selection depends on the active audio language: a forced translation may be right for one dub and wrong for another. Changing audio should optionally rerun the subtitle language and forced-track policy as one coordinated track transaction.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, pickSubtitle and inPlaceAudioChange
- Verdict: MISSING-FEATURE
- Why: `pickSubtitle` considers the selected audio only during automatic session selection. `inPlaceAudioChange` swaps audio and publishes it without rerunning that policy, so the previous subtitle remains selected until the caller changes it manually.
- Severity if real: P2 quality/perf

### [MPV-7465] possible to implement auto copyback for yadif with nvdec?
- Link: https://github.com/mpv-player/mpv/issues/7465  State: closed-fixed
- Mechanism: A software deinterlacer cannot read an opaque hardware surface. Auto-copyback downloads decoded frames only when the filter is enabled, preserving hardware decode while making CPU filters such as yadif legal.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/MediaItem.kt, videoFilter; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, KiteCodecVideoDecoderFactory.create
- Verdict: MISSING-FEATURE
- Why: Attaching any video filter makes KitePlayer's Auto or Prefer policy stand down from hardware decode for the entire stream, while Require refuses the track. It has no surface-download transition that could keep hardware decode and copy back only for a software deinterlacer.
- Severity if real: P2 quality/perf

### [MPV-7214] wishlist: vtt: support styling
- Link: https://github.com/mpv-player/mpv/issues/7214  State: closed-fixed
- Mechanism: WebVTT presentation depends on cue position, line, alignment, regions, stylesheet rules, inline classes, and voice spans. Preserving only timestamps and plain text makes translated signs and speaker layout differ from the authored captions.
- KitePlayer code checked: kiteplayer-subtitles/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/subtitle/WebVttParser.kt, parse and parseTiming
- Verdict: MISSING-FEATURE
- Why: The parser skips `STYLE` and `REGION` blocks, drops class and voice decoration, and records only horizontal alignment from cue settings. Full WebVTT styling would preserve positioned captions and authored emphasis rather than merely keeping the words visible.
- Severity if real: P2 quality/perf

### [MPV-11666] XSPF playlist support
- Link: https://github.com/mpv-player/mpv/issues/11666  State: open
- Mechanism: XSPF is an XML playlist whose ordered locations and per-entry metadata must be parsed into queue items before playback. Treating the file as one media stream loses the playlist graph.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/KitePlayer.kt, openQueue; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/MediaItem.kt, MediaItem
- Verdict: MISSING-FEATURE
- Why: `openQueue` accepts only a list of `MediaItem` objects that the caller already assembled, and the engine has no XSPF parser. Native XSPF loading would make shared and exported playlists usable without application-specific parsing.
- Severity if real: P2 quality/perf

### [MPV-18288] Magic Kernel Sharp (Classic, 2013, and 2021) resampling filter for upscaling filter
- Link: https://github.com/mpv-player/mpv/issues/18288  State: open
- Mechanism: Magic Kernel Sharp reconstructs enlarged samples with a defined mathematical kernel and sharpening response rather than a generic bilinear or cubic approximation. Different revisions trade edge acuity against ringing.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/RenderQuality.kt, VideoScaler; kiteplayer-output/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/output/MetalVideoSupport.kt, kp_bicubic
- Verdict: MISSING-FEATURE
- Why: `VideoScaler` offers only Bilinear and CatmullRom, and the Metal path implements only its fixed bicubic kernel. Another explicit kernel would give viewers a sharper mathematical upscale for low-resolution sources on large displays.
- Severity if real: P2 quality/perf

### [MPV-15657] about "--sub-auto=<no|exact|fuzzy|all>"
- Link: https://github.com/mpv-player/mpv/issues/15657  State: open
- Mechanism: Subtitle discovery for a directory or season must tokenize title, season, and episode identifiers, then rank matching files without attaching subtitles from neighboring episodes. Simple filename prefix matching is insufficient for mixed naming conventions.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/MediaItem.kt, externalSubtitles; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, parseExternalSubtitles
- Verdict: MISSING-FEATURE
- Why: KitePlayer reads only subtitle sources the caller explicitly places in `MediaItem.externalSubtitles`; it never scans sibling directories or matches names. Safe season and episode discovery would reduce manual wiring for local libraries.
- Severity if real: P3 polish

### [MPV-14647] Subtitles support for livestream 
- Link: https://github.com/mpv-player/mpv/issues/14647  State: open
- Mechanism: A live subtitle source is an append-only stream, so the parser must retain partial input, emit newly completed cues, and continue reading without reopening or requiring the whole file at once.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, parseExternalSubtitle; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/MediaItem.kt, SubtitleSource
- Verdict: MISSING-FEATURE
- Why: `parseExternalSubtitle` rejects `SubtitleSource.io`, reads a local file to completion, and parses one immutable cue list. It cannot follow a named pipe or HTTP caption stream as cues arrive.
- Severity if real: P2 quality/perf

### [MPV-12856] Enhanced Non-Linear Stretching (NLS) implementation
- Link: https://github.com/mpv-player/mpv/issues/12856  State: open
- Mechanism: Nonlinear stretch preserves the center of a picture while progressively stretching its edges, often after detecting black bars, so mixed-aspect content can fill a fixed screen with less central distortion than one uniform scale.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/VideoTransform.kt, VideoTransform; kiteplayer-output/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/output/FrameLayout.kt, frameLayout
- Verdict: MISSING-FEATURE
- Why: KitePlayer fits one rectangular quad and applies a uniform zoom, aspect override, and pan. It has no black-bar detector or position-dependent horizontal warp, so it cannot preserve the center while stretching only the edges.
- Severity if real: P3 polish

### [MPV-13773] Match Display Refresh Rate to Video when in Full Screen.
- Link: https://github.com/mpv-player/mpv/issues/13773  State: open
- Mechanism: Fullscreen cadence matching switches the display to the source frame rate or a clean integer multiple at playback start, then restores the previous mode on exit. This removes repeated-frame judder without continuously resampling audio.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/VideoRenderer.kt, vsyncIntervalNanos; kiteplayer-output/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/output/MetalVideoRenderer.kt, vsyncIntervalNanos; kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/AndroidSurfaceVideoRenderer.kt, vsyncIntervalNanos
- Verdict: MISSING-FEATURE
- Why: Both production renderers return no display interval, and the renderer contract has no display-mode selection or restoration operation. The engine cannot inspect candidate refresh modes, choose one for the content, or put the user's mode back afterward.
- Severity if real: P2 quality/perf

### [MPV-11521] Option to set --ab-loop-a to the beginning of file if unset, and --ab-loop-b to the end of file if unset (old behavior)
- Link: https://github.com/mpv-player/mpv/issues/11521  State: open
- Mechanism: Treating an omitted A as zero and an omitted B as media end makes either one-sided loop marker meaningful, once duration is known. The engine must distinguish that from both markers being absent, which means no loop.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/KitePlayer.kt, setAbLoop; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerState.kt, abLoopA and abLoopB
- Verdict: MISSING-FEATURE
- Why: KitePlayer already treats a null B as the media end, but explicitly rejects B without A. Supporting the symmetric one-sided case would let callers mark only a loop end and default A to the beginning.
- Severity if real: P3 polish

### [MPV-6037] Small skip every 9 seconds playback
- Link: https://github.com/mpv-player/mpv/issues/6037  State: open
- Mechanism: On Xorg, periodic display power-management activity can make an atomic connector commit while page flipping. The reported skip followed mpv's screensaver-inhibition DPMS call and disappeared when that behavior was disabled.
- KitePlayer code checked: kiteplayer-output/src/jvmMain/kotlin/io/github/yuroyami/kiteplayer/output/DesktopOutputBackend.kt, videoRenderer
- Verdict: IMMUNE
- Why: KitePlayer's built-in desktop backend provides no video renderer and makes no X11, DRM, gamma, DPMS, or screensaver-inhibition calls. An application-supplied desktop renderer could add such a conflict, but this trigger is absent from the shipped player path.
- Severity if real: P2 quality/perf

### [MPV-4754] Preserve buffer cache on network streams
- Link: https://github.com/mpv-player/mpv/issues/4754  State: open
- Mechanism: Returning to a network playlist entry should reuse bytes already cached for that item. Reopening the URL with only a per-open cache throws those bytes away and downloads the same region again.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, handleQueueAdvance, runOpen, and buildSession; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/CachingMediaIo.kt, close
- Verdict: MISSING-FEATURE
- Why: Every queue advance calls `runOpen`, which builds a fresh source and `CachingMediaIo`; teardown closes the preceding cache, and `close` clears its byte window. Looping a remote queue back to an earlier item therefore cannot reuse that item's downloaded bytes.
- Severity if real: P2 quality/perf

### [MPV-10523] Support for #EXTVLCOPT:http-referrer in M3U Playlists
- Link: https://github.com/mpv-player/mpv/issues/10523  State: open
- Mechanism: An M3U parser must associate `#EXTVLCOPT:http-referrer` with the following entry and apply it as that entry's HTTP Referer header, rather than as global state or media payload.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/MediaItem.kt, headers; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/KitePlayer.kt, openQueue
- Verdict: MISSING-FEATURE
- Why: `MediaItem.headers` can already carry a per-item Referer, but KitePlayer has no M3U parser and `openQueue` accepts only items assembled by the caller. It cannot translate the playlist directive into the existing header seam itself.
- Severity if real: P2 quality/perf

### [MPV-10916] Pause playlist instead of skipping file on audio driver lost
- Link: https://github.com/mpv-player/mpv/issues/10916  State: open
- Mechanism: Losing an audio device during playlist playback should preserve the current item and timeline, pause presentation, and retry or reopen the device instead of treating the media as finished or unrecoverable.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, startAudioEventCollector and handleWorkerOutcome; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/AudioSink.kt, AudioSinkEvent.DeviceLost
- Verdict: MISSING-FEATURE
- Why: A `DeviceLost` event produces only `PlaybackWarning.AudioDeviceChanged`; it does not pause, reopen, or retry the sink. If the output failure terminates a worker, `handleWorkerOutcome` tears down the session and publishes `Failed`, so there is no device-loss recovery state that preserves the current item.
- Severity if real: P1 broken feature

### [MPV-11853] Add subtitle background radius
- Link: https://github.com/mpv-player/mpv/issues/11853  State: open
- Mechanism: A rounded subtitle background must be laid out after text wrapping so one geometry-aware box can follow the final cue bounds, padding, and corner radius without clipping glyphs or neighboring lines.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/subtitle/SubtitleCue.kt, CueStyle; kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/AndroidSubtitleRasterizer.kt, rasterizeText; kiteplayer-output/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/output/AppleSubtitleRasterizer.kt, rasterizeText; kiteplayer-output/src/jvmMain/kotlin/io/github/yuroyami/kiteplayer/output/DesktopSubtitleRasterizer.kt, rasterizeText
- Verdict: MISSING-FEATURE
- Why: `CueStyle` has no background color, padding, or corner-radius fields, and none of the three built-in rasterizers draws a cue background box. The styling and post-layout geometry seam are both absent.
- Severity if real: P3 polish

### [MPV-13596] Pause when audio output changes
- Link: https://github.com/mpv-player/mpv/issues/13596  State: open
- Mechanism: A default-output change can invalidate a sink's format, latency, or route. A conservative policy pauses playback at the transition until the new device is established, preventing muted or incorrectly timed media from continuing unseen.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, startAudioEventCollector; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/AudioSink.kt, AudioSinkEvent.DeviceChanged
- Verdict: MISSING-FEATURE
- Why: KitePlayer turns `DeviceChanged` into a warning and explicitly keeps the current sink. It has no configurable pause-on-route-change policy or transaction that reopens the audio path before resuming.
- Severity if real: P3 polish

### [MPV-18320] allow tone-mapping-max-boost in vo=gpu-next
- Link: https://github.com/mpv-player/mpv/issues/18320  State: open
- Mechanism: A tone-mapping maximum-boost control caps how far dark HDR detail may be lifted, trading visibility against overexposure instead of baking one luminance-lift policy into every title.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/HdrToneMap.kt, mapInPlace and eetfNits; kiteplayer-output/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/output/MetalVideoSupport.kt, kp_tone_map
- Verdict: MISSING-FEATURE
- Why: Both CPU and Metal paths use a fixed BT.2390 mapping from a nominal 1000-nit source to 203-nit SDR, with no user-visible boost limit. Viewers cannot tune shadow lift for unusually dark masters.
- Severity if real: P2 quality/perf

### [MPV-294] A/V desync when switching audio tracks
- Link: https://github.com/mpv-player/mpv/issues/294  State: closed-fixed
- Mechanism: Enabling a new audio track without clearing old output latency or aligning the first new samples to the current video position leaves hundreds of milliseconds of stale or early sound. MPV fixed the transition by resynchronizing the newly enabled audio path and supplying earlier packets.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, inPlaceAudioChange and runAudioFeed
- Verdict: IMMUNE
- Why: The in-place transaction anchors the audible position, parks both audio workers, flushes the ring, installs a queue that already covers the commit point, and records that point in `audioSwitchDiscardBeforeUs`. `runAudioFeed` then discards whole early buffers and sample-exactly trims the one buffer that straddles the boundary.
- Severity if real: P1 broken feature

### [MPV-6001] mpv drops frames at VO for specific AV1 WebM
- Link: https://github.com/mpv-player/mpv/issues/6001  State: wontfix
- Mechanism: The apparent video-output drops were caused by libaom decoding too slowly; decoder-side dropping changed where mpv counted them. The thread reports that dav1d removed the frame drops rather than a presentation-scheduler fix.
- KitePlayer code checked: ../KiteCodec/buildSrc/src/main/kotlin/BuildFFmpegTask.kt, configureArguments; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, KiteCodecVideoDecoderFactory.create
- Verdict: IMMUNE
- Why: Every KiteCodec FFmpeg build makes libdav1d mandatory, and the software decoder path uses FFmpeg's selected decoder. The slow libaom implementation that established this issue's trigger is not KitePlayer's AV1 software path.
- Severity if real: P2 quality/perf

### [MPV-624] Interlaced file on interlaced display may or may not work properly
- Link: https://github.com/mpv-player/mpv/issues/624  State: closed-fixed
- Mechanism: Sending interlaced output to an interlaced display requires field-aware conversion at twice the frame cadence. FFmpeg's working `tinterlace=interlacex2` path combines fields from consecutive frames and assigns midpoint timestamps so each output field lands in order.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/MediaItem.kt, videoFilter; ../KiteCodec/buildSrc/src/main/kotlin/BuildFFmpegTask.kt, sharedCoreArgs
- Verdict: MISSING-FEATURE
- Why: KitePlayer exposes a raw video-filter string, but its vendored FFmpeg profile disables filters and re-enables a curated set that excludes `tinterlace`. The field-doubling mechanism documented by the fix is unavailable in the shipped backend.
- Severity if real: P2 quality/perf

### [MPV-10531] mpv cannot switch correctly between programs in a Transport Stream (TS) file.
- Link: https://github.com/mpv-player/mpv/issues/10531  State: closed-fixed
- Mechanism: Separate MPEG-TS programs can use unrelated timestamp origins. Switching programs while seeking the old program's absolute timestamp into the new one can land at EOF; the fix models programs as selectable groups and coordinates the streams and timeline belonging to one program.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/KitePlayer.kt, programs; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/Tracks.kt, TrackInfo; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, handleTrackChanges
- Verdict: SUSPECT
- Why: `programs()` is explicitly unsupported, `TrackInfo` carries no program identity, and a video selection rebuild seeks the replacement graph to the previous absolute player position. The trigger is a TS whose selected streams move between programs with different timestamp origins; no accepted virtual-time test covers it.
- Severity if real: P1 broken feature

### [MPV-11990] seeking before the cache in an unterminated hls stream
- Link: https://github.com/mpv-player/mpv/issues/11990  State: closed-fixed
- Mechanism: Seeking an unfinished HLS EVENT playlist needs a complete segment-duration timeline, subtraction of skipped-segment duration from the first timestamp, reset of PTS-wrap state, and correct distinction between a midstream cache range and true beginning of file.
- KitePlayer code checked: gradle/libs.versions.toml, kitecodec dependency; ../KiteCodec/vendor/ffmpeg/RELEASE, release version; ../KiteCodec/vendor/ffmpeg/libavformat/hls.c, hls_read_header and hls_read_seek; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, seekToKeyframe
- Verdict: SUSPECT
- Why: KitePlayer delegates HLS seeking to KiteCodec 0.1.4 and its pinned FFmpeg n8 tree, which predates the cited 2026 FFmpeg timeline fix, while its player cache has no HLS-specific beginning-of-file correction. The trigger is an unterminated EVENT playlist after old segments have fallen outside the current cache; there is no accepted virtual-time reproduction.
- Severity if real: P1 broken feature

### [MPV-9314] support opencl filters from libavfilter
- Link: https://github.com/mpv-player/mpv/issues/9314  State: open
- Mechanism: OpenCL libavfilters require an OpenCL-enabled FFmpeg build plus explicit hardware-frame upload, device context, filter execution, and download or renderer handoff. A raw filter expression alone cannot create that interop path.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/MediaItem.kt, videoFilter; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, KiteCodecVideoDecoderFactory.create; ../KiteCodec/buildSrc/src/main/kotlin/BuildFFmpegTask.kt, sharedCoreArgs
- Verdict: MISSING-FEATURE
- Why: Attaching any video filter forces KitePlayer's FFmpeg path to software frames, and the vendored profile neither enables OpenCL nor includes OpenCL filters in its curated filter set. No hardware-frame upload or OpenCL-device configuration surface exists.
- Severity if real: P2 quality/perf

### [MPV-15925] Add a property to show the active vf filters
- Link: https://github.com/mpv-player/mpv/issues/15925  State: open
- Mechanism: Configured filters and actually active filters differ when a conditional, disabled, incompatible, or failed filter is omitted. A runtime property must report the instantiated graph rather than merely echo its requested text.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/MediaItem.kt, videoFilter; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerState.kt, PlayerSnapshot; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, diagnosticsDump
- Verdict: MISSING-FEATURE
- Why: `MediaItem.videoFilter` is an open-time raw string and the support bundle reports only that attached string. `PlayerSnapshot` exposes neither the configured graph nor the filters that FFmpeg actually instantiated, so a client cannot distinguish requested from active processing.
- Severity if real: P3 polish

### [MPV-2820] Subtitle antialiasing (or independent scaling)
- Link: https://github.com/mpv-player/mpv/issues/2820  State: open
- Mechanism: Subtitle rasterization must be independent of video scaling. If text is first burned into a video-sized image, a later blur or resize chosen for the picture also softens the glyph edges.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, publishOverlay; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/VideoRenderer.kt, setOverlay
- Verdict: IMMUNE
- Why: KitePlayer rasterizes active cues at the output surface size and publishes a separate `SubtitleOverlay` after video layout. The video scaler never resamples the text bitmap, so subtitle antialiasing stays independent of the picture kernel.
- Severity if real: P2 quality/perf

### [MPV-3888] Sub-pos only moves up from where the set position is?
- Link: https://github.com/mpv-player/mpv/issues/3888  State: open
- Mechanism: A subtitle position control should be able to cross the default safe-margin baseline. If placement always subtracts the authored bottom margin, the maximum position merely reaches that margin and cannot move a cue closer to the physical edge.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/KitePlayer.kt, setSubtitlePosition; kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/AndroidSubtitleRasterizer.kt, rasterizeText; kiteplayer-output/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/output/AppleSubtitleRasterizer.kt, rasterizeText; kiteplayer-output/src/jvmMain/kotlin/io/github/yuroyami/kiteplayer/output/DesktopSubtitleRasterizer.kt, rasterizeText
- Verdict: SUSPECT
- Why: The public value is limited to 0.1 through 1.0, while all three rasterizers subtract the vertical margin even at 1.0. The trigger is a caller trying to place implicit bottom subtitles below the authored safe margin; no accepted virtual-time rendering test proves the visible limit.
- Severity if real: P3 polish

### [MPV-15284] Font size of OSD, subtitles, ... should depend on window width instead of window height
- Link: https://github.com/mpv-player/mpv/issues/15284  State: open
- Mechanism: Height-relative text becomes disproportionately large in a tall portrait viewport. A width-aware scale, or a function of both dimensions, keeps the apparent subtitle size bounded across orientation changes.
- KitePlayer code checked: kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/AndroidSubtitleRasterizer.kt, rasterizeText; kiteplayer-output/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/output/AppleSubtitleRasterizer.kt, rasterizeText; kiteplayer-output/src/jvmMain/kotlin/io/github/yuroyami/kiteplayer/output/DesktopSubtitleRasterizer.kt, rasterizeText
- Verdict: SUSPECT
- Why: Every built-in rasterizer derives the default font size solely from `viewportHeight / 20f`. The trigger is a portrait or unusually tall surface without an authored font size; no accepted visual or virtual-time test establishes the resulting usability defect.
- Severity if real: P2 quality/perf

### [MPV-16115] WebSocket (wss) media streaming support
- Link: https://github.com/mpv-player/mpv/issues/16115  State: open
- Mechanism: Playing a WebSocket media stream requires a persistent ws or wss transport that converts message payloads into one ordered byte stream, applies backpressure, and propagates close and error frames to the demuxer.
- KitePlayer code checked: kiteplayer-network/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/network/KtorMediaIo.kt, KtorMediaIoResolver.resolve; ../KiteCodec/buildSrc/src/main/kotlin/BuildFFmpegTask.kt, sharedCoreArgs
- Verdict: MISSING-FEATURE
- Why: The built-in resolver accepts only HTTP and HTTPS, and the vendored FFmpeg protocol profile contains only file, fd, pipe, data, HTTP, and TCP. There is no WebSocket framing or wss transport seam for this source class.
- Severity if real: P2 quality/perf

### [MPV-5413] Sync from another app via MTC/MMC/OSC
- Link: https://github.com/mpv-player/mpv/issues/5413  State: open
- Mechanism: External transport sync consumes timecode and locate, play, pause, and shuttle commands from MTC, MMC, or OSC, maps them to the media timeline, and continuously corrects local playback against that external master.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/MonotonicClock.kt, MonotonicClock; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/MediaClock.kt, MediaClock; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerState.kt, SyncMode.ExternalMaster
- Verdict: MISSING-FEATURE
- Why: The engine clock accepts only monotonic local nanoseconds, and `SyncMode.ExternalMaster` explicitly says no external clock drives playback and no audio resampling follows one. There is also no MTC, MMC, or OSC command adapter.
- Severity if real: P2 quality/perf

### [MPV-4141] A/V desync while using wireless sound device
- Link: https://github.com/mpv-player/mpv/issues/4141  State: closed-fixed
- Mechanism: A wireless CoreAudio route adds downstream stream latency beyond the callback buffer. The issue's fixes query `kAudioStreamPropertyLatency` and refresh latency after output changes; otherwise the video clock can lead sound by the unreported transport delay.
- KitePlayer code checked: kiteplayer-output/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/output/CoreAudioSink.kt, latencyNanos; kiteplayer-rt/native/src/kite_rt_coreaudio.c, kprt_render_cb
- Verdict: SUSPECT
- Why: KitePlayer anchors to CoreAudio's callback host time plus the current buffer span, and `latencyNanos` only reads that deadline. It never queries the output stream's downstream latency. The trigger is an AirPlay-like route whose callback timestamp omits transport latency; no accepted virtual-time test models that device behavior.
- Severity if real: P1 broken feature

### [MPV-11717] Add option to stop if playlist item missing
- Link: https://github.com/mpv-player/mpv/issues/11717  State: open
- Mechanism: A stop-on-error playlist policy must prevent automatic queue advance when opening the current item fails, instead of treating the failure like normal EOF before an observer can intervene.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runOpen, fail, and handleQueueAdvance
- Verdict: IMMUNE
- Why: An open failure publishes `PlaybackStatus.Failed`. `handleQueueAdvance` runs only from `Ended`, so a missing or unreadable queue item stops the queue without racing an error callback or silently opening the next item.
- Severity if real: P2 quality/perf

### [MPV-16737] live stream indicator
- Link: https://github.com/mpv-player/mpv/issues/16737  State: open
- Mechanism: A reliable live indicator is source or demux state, not a guess from unknown duration or seekability: finite event streams, growing files, and seekable DVR windows break those heuristics in opposite directions.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerState.kt, PlayerSnapshot
- Verdict: MISSING-FEATURE
- Why: `PlayerSnapshot` exposes nullable duration and a seekable flag but no explicit live-source classification. Applications must guess from the same ambiguous signals the issue documents.
- Severity if real: P3 polish

### [MPV-17081] Increase read timeout for appending files
- Link: https://github.com/mpv-player/mpv/issues/17081  State: open
- Mechanism: Following a file that is still being appended needs a configurable wait and retry policy at temporary EOF. Treating the first zero-byte read as permanent EOF drains the player before the writer produces the next chunk.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/MediaItem.kt, openOptions; ../KiteCodec/vendor/ffmpeg/libavformat/file.c, file_read; ../KiteCodec/kitecodec-core/src/nativeMain/kotlin/io/github/yuroyami/kitecodec/Playback.native.kt, PacketReader.read
- Verdict: MISSING-FEATURE
- Why: FFmpeg's file protocol defaults `follow` to zero, so a temporary zero-byte read becomes EOF. A caller can set `follow=1`, but then `file_read` returns EAGAIN and KiteCodec's packet reader treats every negative value except EOF as an exception. There is no configurable append wait in either path.
- Severity if real: P2 quality/perf

### [MPV-17087] prefer h265 and av1 when multiple tracks are available in a mpeg-dash xml
- Link: https://github.com/mpv-player/mpv/issues/17087  State: open
- Mechanism: DASH representation selection should filter by decoder capability and codec preference before comparing bitrate, otherwise the numerically highest-bandwidth representation can choose an unwanted or unsupported codec.
- KitePlayer code checked: kiteplayer-network/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/network/dash/DashMediaIo.kt, mediaItemFor; kiteplayer-network/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/network/dash/DashManifest.kt, DashRepresentation.codecs
- Verdict: MISSING-FEATURE
- Why: The basic DASH tier takes the first video adaptation set and selects its maximum-bandwidth representation. Although the manifest model retains `codecs`, `mediaItemFor` never consults it or the decoder inventory, so callers cannot prefer HEVC or AV1 over another representation.
- Severity if real: P2 quality/perf

### [MPV-17709] [gpu-next] Provide control over internal texture bit depth
- Link: https://github.com/mpv-player/mpv/issues/17709  State: open
- Mechanism: Configurable internal render precision prevents rounding and banding from accumulating across tone mapping, color adjustment, scaling, and shaders before the final display conversion.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/RenderQuality.kt, RenderQuality; kiteplayer-output/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/output/MetalFrameComposer.kt, targetFormat
- Verdict: MISSING-FEATURE
- Why: The Metal composer fixes its render target to `BGRA8Unorm`, and `RenderQuality` offers dither and deband controls but no target-precision selector. A higher-bit-depth internal target cannot be requested for processing chains that would benefit from it.
- Severity if real: P2 quality/perf

### [MPV-14855] gpu-next: orthogonal antiringing should be made less aggressive
- Link: https://github.com/mpv-player/mpv/issues/14855  State: open
- Mechanism: Antiringing constrains an interpolated scaler result to a neighborhood envelope; its strength controls the tradeoff between suppressing halos and flattening valid edge detail, so one fixed aggressiveness does not suit every kernel and source.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/RenderQuality.kt, VideoScaler; kiteplayer-output/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/output/MetalVideoSupport.kt, kp_bicubic
- Verdict: MISSING-FEATURE
- Why: KitePlayer exposes only Bilinear or a fixed Catmull-Rom shader and has no antiringing stage or aggressiveness parameter. A caller cannot tune halo suppression separately from choosing the scaling kernel.
- Severity if real: P2 quality/perf

### [MPV-14650] There needs to be a way to elevate black levels
- Link: https://github.com/mpv-player/mpv/issues/14650  State: open
- Mechanism: Raising black level is an additive brightness bias after contrast, not merely multiplying RGB values. A multiplicative gain leaves exact black unchanged and therefore cannot lift crushed shadow detail.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/VideoAdjustments.kt, toColorMatrix; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/KitePlayer.kt, setVideoAdjustments
- Verdict: IMMUNE
- Why: `VideoAdjustments.toColorMatrix` computes an additive offset of `0.5 * (1 - contrast) + brightness`, and the public setter applies it live. Positive brightness therefore raises zero-valued black instead of only scaling nonzero samples.
- Severity if real: P3 polish
