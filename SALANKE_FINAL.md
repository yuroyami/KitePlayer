# SALANKE final ledger

- Date: 2026-08-26
- Inputs: `SALANKE_MPV.md`, `SALANKE_VLC.md`, and `SALANKE_MEDIA3.md`
- Scope: local file and HTTP playback, FFmpeg demux and decode, platform hardware decode and output, subtitles, seeking, track selection, speed, chapters, playlists, basic FFmpeg HLS, and basic static one-period one-representation DASH
- Excluded by the source protocol: GUI, packaging, build systems, scripting, DRM, casting, ad insertion, editing, transcoding, dynamic or live DASH, SmoothStreaming, and player-owned HLS adaptation

## Coverage and method

This is the full relevant-tracker sweep defined by `SALANKE.md`, not a claim that unrelated GUI, packaging, or build issues were analyzed. Every relevant full-history query and component inventory was paginated to completion, then the triage rules selected the highest-signal mechanisms for code-level fact checking. Checkpoint lines in the three source ledgers are resume cursors only, never completion gates.

| Tracker | Inventory coverage | Processed | SUSPECT | MISSING-FEATURE | IMMUNE | N/A | CONFIRMED |
|---|---:|---:|---:|---:|---:|---:|---:|
| mpv | 12,935 query records, 6,312 unique issues | 300 | 25 | 190 | 85 | 0 | 0 |
| VLC | 5,423 overlapping API and search records | 304 | 37 | 75 | 169 | 23 | 0 |
| Media3 and ExoPlayer | 4,781 overlapping query hits | 300 | 31 | 95 | 170 | 4 | 0 |
| Total | 23,139 raw query records | 904 | 93 | 360 | 424 | 27 | 0 |

Every processed row has a direct source link, an upstream state, a concrete mechanism, a KitePlayer file and function receipt, one verdict, a reason, and a severity. Final audits found no duplicate tracker IDs, no ID-to-link mismatch, no missing receipt path, no invalid taxonomy value, and no Unicode em dash or en dash.

## CONFIRMED

None. The user required a research-only run with no code editing. Under the SALANKE taxonomy, a defect becomes CONFIRMED only when a failing virtual-time test reproduces it. No such test was authored, so all plausible defects remain SUSPECT.

## SUSPECT

The 93 source rows below dedupe into 50 mechanisms. Combined source severity is 1 P0, 49 P1, 40 P2, and 3 P3. Each source issue appears exactly once in this section.

### S01. Unbounded eager external subtitle ingestion

- Severity: P0
- Sources: [VLC-11908 P0](https://code.videolan.org/videolan/vlc/-/issues/11908), [MEDIA3-1721 P2](https://github.com/androidx/media/issues/1721)
- Mechanism and receipt: `MediaItem.kt, SubtitleSource`, `PlaybackCore.parseExternalSubtitles/parseExternalSubtitle`, and `AssParser.kt, AssTrackParser.parseEvent` parse complete files without a byte, cue-count, or wall-time admission budget.

### S02. Forced subtitle fallback is coupled to language preferences

- Severity: P1
- Sources: [VLC-20645 P1](https://code.videolan.org/videolan/vlc/-/issues/20645), [VLC-18877 P1](https://code.videolan.org/videolan/vlc/-/issues/18877), [EXOPLAYER-10401 P1](https://github.com/google/ExoPlayer/issues/10401), [MPV-8396 P1](https://github.com/mpv-player/mpv/issues/8396), [MPV-18411 P1](https://github.com/mpv-player/mpv/issues/18411)
- Mechanism and receipt: `PlaybackCore.pickSubtitle` and `PlayerConfig.SubtitleConfig` enter the forced branch only when preferred languages are nonempty, and do not fully retarget policy after audio changes.

### S03. No first-class discontinuity epoch

- Severity: P1
- Sources: [VLC-27571 P1](https://code.videolan.org/videolan/vlc/-/issues/27571), [EXOPLAYER-8678 P1](https://github.com/google/ExoPlayer/issues/8678), [EXOPLAYER-5693 P1](https://github.com/google/ExoPlayer/issues/5693), [MEDIA3-2592 P1](https://github.com/androidx/media/issues/2592), [EXOPLAYER-7030 P1](https://github.com/google/ExoPlayer/issues/7030), [MEDIA3-3109 P2](https://github.com/androidx/media/issues/3109)
- Mechanism and receipt: `KiteCodecPacket`, `TimestampMapper`, `PlaybackCore.runDemux/runSeek`, `KotlinAudioRing`, `SyncLaw`, and `currentPosition` share one opening origin and lack a typed per-PID or midstream epoch reset.

### S04. Startup lacks a shared first-audio-anchor readiness law

- Severity: P1
- Sources: [EXOPLAYER-10491 P2](https://github.com/google/ExoPlayer/issues/10491), [EXOPLAYER-8952 P1](https://github.com/google/ExoPlayer/issues/8952), [VLC-26829 P2](https://code.videolan.org/videolan/vlc/-/issues/26829), [VLC-27025 P2](https://code.videolan.org/videolan/vlc/-/issues/27025)
- Mechanism and receipt: `PlaybackCore.awaitInitialFill`, `handlePlaybackRestart`, `masterPosition/masterClockKind`, `AudioPlayback.anchorClock`, and `VideoPlayback.tick` do not establish one joint landing law for the first usable audio anchor and video frame.

### S05. Native or unindexed seek has no I/O or time budget

- Severity: P1
- Sources: [VLC-30020 P1](https://code.videolan.org/videolan/vlc/-/issues/30020), [VLC-9906 P1](https://code.videolan.org/videolan/vlc/-/issues/9906), [MPV-6537 P1](https://github.com/mpv-player/mpv/issues/6537)
- Mechanism and receipt: `KiteCodecSource.seekToKeyframe`, `CachingMediaIo.seek`, and `PlaybackCore.runSeek` cannot preempt a blocking or long-running FFmpeg scan.

### S06. Transient progressive read failure destroys the session

- Severity: P1
- Sources: [EXOPLAYER-1606 P1](https://github.com/google/ExoPlayer/issues/1606), [MEDIA3-344 P1](https://github.com/androidx/media/issues/344), [MPV-5793 P1](https://github.com/mpv-player/mpv/issues/5793)
- Mechanism and receipt: `PlaybackCore.runDemux`, `handleWorkerOutcome/fail/teardownSession`, and `PlayerConfig.NetworkConfig` have no player-owned retry, buffered-drain, or resumable reconnect transaction.

### S07. Audio starvation is surfaced without a recovery law

- Severity: P1
- Sources: [VLC-26476 P2](https://code.videolan.org/videolan/vlc/-/issues/26476), [MPV-7172 P1](https://github.com/mpv-player/mpv/issues/7172)
- Mechanism and receipt: `AudioPlayback.open/underruns` and `PlaybackCore.handleBuffering`, `handlePlaybackRestart`, and `publishProgressAndStats` count and warn after starvation but do not prescribe refill or device reopen recovery.

### S08. Runtime stream inventory cannot absorb late elementary streams

- Severity: P1
- Sources: [VLC-14257 P1](https://code.videolan.org/videolan/vlc/-/issues/14257), [MEDIA3-473 P1](https://github.com/androidx/media/issues/473)
- Mechanism and receipt: `KiteCodecSource.streams/selectableStreams/select` and `PlaybackCore.buildSession/runDemux` construct queues from one open-time stream snapshot, even though a demuxer can discover or replace streams later.

### S09. Seek to end has no last-frame landing pass

- Severity: P1
- Sources: [EXOPLAYER-2568 P2](https://github.com/google/ExoPlayer/issues/2568), [MPV-819 P1](https://github.com/mpv-player/mpv/issues/819)
- Mechanism and receipt: `SeekRequest.resolve` and `PlaybackCore.runSeek/presentFirstFrame` can reach exact-duration EOF without decoding through the tail to identify and retain the actual final frame.

### S10. HTTP reopen loses request identity or representation headers

- Severity: P1
- Sources: [EXOPLAYER-6166 P1](https://github.com/google/ExoPlayer/issues/6166), [EXOPLAYER-5975 P1](https://github.com/google/ExoPlayer/issues/5975), [MEDIA3-312 P2](https://github.com/androidx/media/issues/312)
- Mechanism and receipt: `MediaItem.headers`, `KtorMediaIo.open/openAt`, the resolver, `DashMediaIo`, and `KiteCodecSourceFactory` do not carry a first-class request identity through every redirect, cookie, and representation-specific reopen.

### S11. Automatic audio choice ignores accessibility traits

- Severity: P1
- Sources: [VLC-3252 P1](https://code.videolan.org/videolan/vlc/-/issues/3252), [VLC-3713 P1](https://code.videolan.org/videolan/vlc/-/issues/3713)
- Mechanism and receipt: `PlaybackCore.pickAudio` and `AudioConfig` rank language, default flag, and container order, but not descriptive or accessibility dispositions.

### S12. Automatic format choice ignores output capability

- Severity: P1
- Sources: [MEDIA3-1471 P1](https://github.com/androidx/media/issues/1471), [MEDIA3-1074 P1](https://github.com/androidx/media/issues/1074)
- Mechanism and receipt: `PlaybackCore.pickAudio/buildSession` chooses a track before `AudioSinkFactory.create` or the display path establishes the output's channel-layout and HDR capabilities.

### S13. Direct platform rotation metadata is not independently verified

- Severity: P1
- Sources: [EXOPLAYER-11038 P1](https://github.com/google/ExoPlayer/issues/11038), [EXOPLAYER-8478 P2](https://github.com/google/ExoPlayer/issues/8478)
- Mechanism and receipt: platform decoder selection, `VideoFrame.rotationDegrees`, and `FrameLayout.quarterTurn/frameLayout` cannot detect a direct-surface platform that silently ignores or misapplies rotation.

### S14. HTTP range and EOF fallback semantics are incomplete

- Severity: P1
- Sources: [MEDIA3-1071 P1](https://github.com/androidx/media/issues/1071), [MEDIA3-1301 P1](https://github.com/androidx/media/issues/1301)
- Mechanism and receipt: `KtorMediaIo.open/read/openAt` guards exact EOF for known sizes, but unknown-size 416 responses and truncated tail ranges lack a complete semantic fallback.

### S15. Selected live audio can remain a clock dependency while absent

- Severity: P1
- Sources: [EXOPLAYER-10936 P1](https://github.com/google/ExoPlayer/issues/10936), [MEDIA3-3009 P1](https://github.com/androidx/media/issues/3009)
- Mechanism and receipt: `PlaybackCore.handleBuffering/masterPosition/masterClockKind`, selected queues, and `AudioPlayback` have no explicit policy to demote a selected live audio lane that temporarily produces no packets or silently ends.

### S16. Subtitle seek reconstruction is not bounded to target state

- Severity: P1
- Sources: [VLC-18862 P1](https://code.videolan.org/videolan/vlc/-/issues/18862), [VLC-16302 P1](https://code.videolan.org/videolan/vlc/-/issues/16302)
- Mechanism and receipt: `PlaybackCore.runSeek/flushDecoders/handleSubtitles` and subtitle decoder `flush` rely on packets returned around the shared landing rather than a bounded replay contract that reconstructs every cue active at the target.

### S17. Teardown joins a worker before closing its blocked read

- Severity: P1
- Sources: [VLC-26515 P1](https://code.videolan.org/videolan/vlc/-/issues/26515), [VLC-13979 P1](https://code.videolan.org/videolan/vlc/-/issues/13979)
- Mechanism and receipt: `PlaybackCore.teardownSession/stopWorkers` can cancel and join before `MediaIo.close`, even when that close is what would unblock native or network I/O.

### S18. CoreAudio clock omits downstream stream latency

- Severity: P1
- Sources: [MPV-4141 P1](https://github.com/mpv-player/mpv/issues/4141)
- Mechanism and receipt: `CoreAudioSink.latencyNanos` and `kite_rt_coreaudio.c::kprt_render_cb` use callback host time plus the buffer span, but do not query downstream `kAudioStreamPropertyLatency` for wireless routes.

### S19. Decoder reopen omits explicit B-frame reorder depth

- Severity: P1
- Sources: [MPV-1341 P1](https://github.com/mpv-player/mpv/issues/1341)
- Mechanism and receipt: KiteCodec native and JVM `StreamDecoder.open` rely on `avcodec_parameters_to_context`, which does not carry `AVCodecContext.has_b_frames` as explicit reopen state.

### S20. Decoder-pool teardown can block the control path

- Severity: P1
- Sources: [VLC-28521 P1](https://code.videolan.org/videolan/vlc/-/issues/28521)
- Mechanism and receipt: platform video decoder close/release paths and `PlaybackCore.teardownSession` synchronously release decoder and renderer resources from the owning transaction, so a pool wait can stall close or track change.

### S21. Dolby E bursts can be opened as ordinary PCM

- Severity: P1
- Sources: [MPV-9353 P1](https://github.com/mpv-player/mpv/issues/9353)
- Mechanism and receipt: `KiteCodecSource.newAudioDecoder/openDecoder` has no SMPTE ST 337 burst probe or Dolby E decoder override before an MXF essence that looks like PCM reaches output.

### S22. External subtitle timing has an ambiguous origin convention

- Severity: P1
- Sources: [VLC-28308 P1](https://code.videolan.org/videolan/vlc/-/issues/28308)
- Mechanism and receipt: `PlaybackCore.parseExternalSubtitle`, `TimestampMapper`, and `SubtitleCue` do not declare whether a sidecar is zero-based or already authored on the master timeline.

### S23. First AudioTrack timestamp after resume is not reconciled

- Severity: P1
- Sources: [EXOPLAYER-1755 P1](https://github.com/google/ExoPlayer/issues/1755)
- Mechanism and receipt: `PlatformAudioTrackDriver`, `AudioTrackSink`, and `AudioPlayback.anchorClock` accept the first post-resume device anchor without comparing it to resume time and the first submitted sample.

### S24. HLS EVENT seek lacks the post-cache timeline correction

- Severity: P1
- Sources: [MPV-11990 P1](https://github.com/mpv-player/mpv/issues/11990)
- Mechanism and receipt: pinned `vendor/ffmpeg/libavformat/hls.c::hls_read_header/hls_read_seek` and `KiteCodecSource.seekToKeyframe` predate the cited 2026 skipped-segment and wrap-state correction.

### S25. HLS live reload tolerance inherits a fixed demux default

- Severity: P1
- Sources: [MPV-12973 P1](https://github.com/mpv-player/mpv/issues/12973)
- Mechanism and receipt: `MediaItem.openOptions`, `KiteCodecSource.open`, and pinned `hls.c` expose no player-owned reload-tolerance policy for live playlists.

### S26. Inactive packet lanes can exhaust the global byte budget

- Severity: P1
- Sources: [MEDIA3-2298 P1](https://github.com/androidx/media/issues/2298)
- Mechanism and receipt: `PlaybackCore.runDemux/overBudget` caches every audio and subtitle lane under one session-global byte ceiling, so an invalid inactive lane can consume the budget needed by selected media.

### S27. Looping can restart before the final frame duration elapses

- Severity: P1
- Sources: [MPV-7160 P1](https://github.com/mpv-player/mpv/issues/7160)
- Mechanism and receipt: `VideoPlayback.present/tick` and `PlaybackCore.handleEof/handleLoop` dequeue the last frame before its full display interval is represented in terminal state.

### S28. Platform dequeue exception is terminal

- Severity: P1
- Sources: [EXOPLAYER-8134 P1](https://github.com/google/ExoPlayer/issues/8134)
- Mechanism and receipt: the Android `MediaCodecVideoDecoder` dequeue path and `PlaybackCore.handleWorkerOutcome` convert a sporadic platform exception into worker failure without decoder-local recovery.

### S29. Post-seek premature audio EOF is accepted as stream end

- Severity: P1
- Sources: [MPV-14969 P1](https://github.com/mpv-player/mpv/issues/14969)
- Mechanism and receipt: `KiteCodecSource.seekToKeyframe` and `PlaybackCore.runSeek/handleEof` have no guard that distinguishes immediate post-seek EOS from the actual stream duration.

### S30. Transport-stream program changes have no grouped timeline

- Severity: P1
- Sources: [MPV-10531 P1](https://github.com/mpv-player/mpv/issues/10531)
- Mechanism and receipt: `KitePlayer.programs` is unsupported, `Tracks.TrackInfo` has no program identity, and `PlaybackCore.handleTrackChanges` reuses the prior absolute position when streams move between programs with unrelated epochs.

### S31. Fixed WSOLA search and mono analysis degrade tempo quality

- Severity: P2
- Sources: [EXOPLAYER-2751 P2](https://github.com/google/ExoPlayer/issues/2751), [MPV-4418 P2](https://github.com/mpv-player/mpv/issues/4418), [MPV-8705 P2](https://github.com/mpv-player/mpv/issues/8705)
- Mechanism and receipt: `TempoStage.detectPeriod/emitSkip/emitInsert` uses a fixed 65-400 Hz search over a mono average with linear crossfade, which can distort polyphonic or multichannel material.

### S32. Hardware decode degradation can remain silent

- Severity: P2
- Sources: [EXOPLAYER-7998 P2](https://github.com/google/ExoPlayer/issues/7998), [EXOPLAYER-7683 P2](https://github.com/google/ExoPlayer/issues/7683), [VLC-26929 P2](https://code.videolan.org/videolan/vlc/-/issues/26929)
- Mechanism and receipt: platform decoder selection and `PlaybackCore.runVideoDecode` lack one user-visible degradation and fallback contract for green frames, vendor judder, or silent surface quantization.

### S33. Speed change performs a full seek, flush, and preroll

- Severity: P2
- Sources: [VLC-25056 P2](https://code.videolan.org/videolan/vlc/-/issues/25056), [VLC-20243 P2](https://code.videolan.org/videolan/vlc/-/issues/20243), [VLC-25120 P2](https://code.videolan.org/videolan/vlc/-/issues/25120), [VLC-13787 P2](https://code.videolan.org/videolan/vlc/-/issues/13787), [VLC-19304 P2](https://code.videolan.org/videolan/vlc/-/issues/19304)
- Mechanism and receipt: `PlaybackCore.setSpeed/runSeek/quiesce/flush/landing` and `TempoStage` prevent mixed-rate samples by restarting the whole epoch, but that deliberately permits an audible transition gap.

### S34. Bottom subtitle stacking reverses or overlaps cues

- Severity: P2
- Sources: [VLC-26965 P2](https://code.videolan.org/videolan/vlc/-/issues/26965), [VLC-25383 P2](https://code.videolan.org/videolan/vlc/-/issues/25383), [VLC-17315 P3](https://code.videolan.org/videolan/vlc/-/issues/17315)
- Mechanism and receipt: `CueLayout`, `PlaybackCore.publishOverlay`, and the built-in rasterizers share limited bottom placement logic and no authored-order or tiny-overlap repair policy.

### S35. Pause fallback discards device-buffer continuity

- Severity: P2
- Sources: [VLC-29714 P2](https://code.videolan.org/videolan/vlc/-/issues/29714), [VLC-28467 P2](https://code.videolan.org/videolan/vlc/-/issues/28467), [VLC-27258 P2](https://code.videolan.org/videolan/vlc/-/issues/27258)
- Mechanism and receipt: `PlaybackCore.applyPause` and `AudioPlayback.pause/flush` can fall back from device pause to stop and flush, discarding continuity that cannot be reconstructed on resume.

### S36. HDR tone mapping hard-codes a 1000-nit source peak

- Severity: P2
- Sources: [MPV-6405 P2](https://github.com/mpv-player/mpv/issues/6405), [MPV-16053 P2](https://github.com/mpv-player/mpv/issues/16053)
- Mechanism and receipt: `HdrToneMap.SRC_PEAK/eetfNits/mapInPlace` uses a fixed peak while `ColorSpaceInfo` carries no mastering-display or content-light maximum.

### S37. Subtitle rasterization has no complexity budget

- Severity: P2
- Sources: [VLC-27130 P2](https://code.videolan.org/videolan/vlc/-/issues/27130), [VLC-25615 P2](https://code.videolan.org/videolan/vlc/-/issues/25615)
- Mechanism and receipt: `PlaybackCore.publishOverlay` and `SubtitleRasterizer.rasterize` run off the actor but have no glyph, vector, bitmap-size, or raster-time ceiling.

### S38. Audio anchor publication can spin

- Severity: P2
- Sources: [VLC-27204 P2](https://code.videolan.org/videolan/vlc/-/issues/27204)
- Mechanism and receipt: `AudioPlayback.anchorClock/masterPosition` uses retry and polling behavior without a bounded anchor-publication rendezvous for the real-time callback.

### S39. Crop and clean-aperture metadata do not reach the renderer

- Severity: P2
- Sources: [MPV-8037 P2](https://github.com/mpv-player/mpv/issues/8037)
- Mechanism and receipt: `KiteCodecSource.toPlayerStream` and `PlayerStreamInfo` model size, aspect, and rotation but not crop edges or clean aperture.

### S40. Cue publication is not frame-bound

- Severity: P2
- Sources: [MEDIA3-2289 P2](https://github.com/androidx/media/issues/2289)
- Mechanism and receipt: `PlaybackCore.handleSubtitles/timeAndPublishCues/publishOverlay` treats cue changes and raster completion as actor events rather than transactions tied to a renderer frame PTS.

### S41. Default subtitle size is height-only

- Severity: P2
- Sources: [MPV-15284 P2](https://github.com/mpv-player/mpv/issues/15284)
- Mechanism and receipt: Android, Apple, and desktop subtitle rasterizers derive the default font size solely from `viewportHeight / 20f`, so portrait surfaces can produce disproportionate text.

### S42. Independent gamut clipping can shift HDR hue

- Severity: P2
- Sources: [MPV-9071 P2](https://github.com/mpv-player/mpv/issues/9071)
- Mechanism and receipt: `HdrToneMap.mapInPlace` and `MetalVideoSupport.kp_tone_map` independently clamp BT.2020-to-BT.709 components, which can rotate the color vector of saturated highlights.

### S43. Late gain changes cannot revise the queued tail

- Severity: P2
- Sources: [VLC-24311 P2](https://code.videolan.org/videolan/vlc/-/issues/24311)
- Mechanism and receipt: `GainStage.apply` changes future writes, but samples already in the `AudioPlayback` ring or device retain their previous gain.

### S44. libass rendering lacks storage geometry

- Severity: P2
- Sources: [VLC-26634 P2](https://code.videolan.org/videolan/vlc/-/issues/26634)
- Mechanism and receipt: `AssParser`, `SubtitleCue`, and `SubtitleRasterizer` do not provide the authored storage resolution as a complete first-class rendering input.

### S45. Manual track intent is not persisted across queue items

- Severity: P2
- Sources: [MPV-13670 P2](https://github.com/mpv-player/mpv/issues/13670)
- Mechanism and receipt: `PlaybackCore.handleTrackChanges/runOpen/buildSession/handleQueueAdvance` rebuilds automatic selection state for every open rather than retaining the user's selection intent.

### S46. Mounted high-latency paths bypass byte-read coalescing

- Severity: P2
- Sources: [MPV-7408 P2](https://github.com/mpv-player/mpv/issues/7408)
- Mechanism and receipt: `CachingMediaIo.read` coalesces reader-fed media, but `PlaybackCore.buildSession` lets direct filesystem URIs reach FFmpeg without that 256 KiB cache, including CIFS and SMB mounts.

### S47. Pause has no transport-owned fade to zero

- Severity: P2
- Sources: [MPV-2515 P2](https://github.com/mpv-player/mpv/issues/2515)
- Mechanism and receipt: `PlaybackCore.applyPause` stops the sink without invoking the short ramp in `GainStage`, so low-frequency content can stop far from a zero crossing.

### S48. tx3g text lacks base-direction resolution

- Severity: P2
- Sources: [VLC-19411 P2](https://code.videolan.org/videolan/vlc/-/issues/19411)
- Mechanism and receipt: `KiteCodecSubtitleDecoder`, tx3g parsing, and `SubtitleCue` carry neither paragraph base-direction metadata nor an explicit Unicode direction-resolution step.

### S49. EOF handling relies on actor polling

- Severity: P3
- Sources: [VLC-26487 P3](https://code.videolan.org/videolan/vlc/-/issues/26487)
- Mechanism and receipt: `PlaybackCore.handleEof` samples drain completion from the actor loop rather than receiving one terminal output rendezvous.

### S50. Subtitle position cannot cross the authored safe margin

- Severity: P3
- Sources: [MPV-3888 P3](https://github.com/mpv-player/mpv/issues/3888)
- Mechanism and receipt: `KitePlayer.setSubtitlePosition` and all three built-in `rasterizeText` implementations still subtract the authored bottom margin when the public position is 1.0.

## MISSING-FEATURE

The 360 source rows below dedupe into 40 capability areas, ordered by safety, accessibility, playback reach, and user value. Combined source severity is 7 P0, 114 P1, 196 P2, and 43 P3. Each source issue appears exactly once in this section.

### M01. Bitmap subtitle engines

- Sources: [EXOPLAYER-8260 P1](https://github.com/google/ExoPlayer/issues/8260), [VLC-26180 P1](https://code.videolan.org/videolan/vlc/-/issues/26180), [VLC-22317 P1](https://code.videolan.org/videolan/vlc/-/issues/22317), [VLC-29325 P0](https://code.videolan.org/videolan/vlc/-/issues/29325), [VLC-29286 P0](https://code.videolan.org/videolan/vlc/-/issues/29286), [VLC-28960 P0](https://code.videolan.org/videolan/vlc/-/issues/28960), [VLC-29789 P0](https://code.videolan.org/videolan/vlc/-/issues/29789), [VLC-29620 P1](https://code.videolan.org/videolan/vlc/-/issues/29620), [VLC-26467 P1](https://code.videolan.org/videolan/vlc/-/issues/26467), [VLC-2604 P1](https://code.videolan.org/videolan/vlc/-/issues/2604), [VLC-29613 P2](https://code.videolan.org/videolan/vlc/-/issues/29613), [VLC-14847 P1](https://code.videolan.org/videolan/vlc/-/issues/14847), [VLC-12970 P1](https://code.videolan.org/videolan/vlc/-/issues/12970), [MPV-8202 P1](https://github.com/mpv-player/mpv/issues/8202), [MPV-18286 P1](https://github.com/mpv-player/mpv/issues/18286), [MPV-13088 P2](https://github.com/mpv-player/mpv/issues/13088)
- Gap and receipt: `KiteCodecSubtitleDecoderFactory.create` can feed `SubtitleCue.Bitmap/RgbaBitmap`, but no PGS, VobSub, DVB, SPU, or CVD subtitle engine exists. The P0 source rows define mandatory bounds checks for any future decoder.

### M02. Broadcast caption extraction and decoding

- Sources: [MEDIA3-1820 P1](https://github.com/androidx/media/issues/1820), [MEDIA3-1863 P1](https://github.com/androidx/media/issues/1863), [VLC-28396 P1](https://code.videolan.org/videolan/vlc/-/issues/28396), [VLC-26159 P1](https://code.videolan.org/videolan/vlc/-/issues/26159), [VLC-29331 P0](https://code.videolan.org/videolan/vlc/-/issues/29331), [VLC-29330 P0](https://code.videolan.org/videolan/vlc/-/issues/29330), [VLC-29112 P1](https://code.videolan.org/videolan/vlc/-/issues/29112), [VLC-18113 P1](https://code.videolan.org/videolan/vlc/-/issues/18113), [MPV-6376 P1](https://github.com/mpv-player/mpv/issues/6376)
- Gap and receipt: `KiteCodecVideoDecoder.receive` and the subtitle factory expose no video-frame side-data extraction or CEA-608, CEA-708, ARIB, or Teletext decoder. The P0 rows define safe parser requirements.

### M03. HLS live playlist and LL-HLS

- Sources: [MEDIA3-3105 P1](https://github.com/androidx/media/issues/3105), [MEDIA3-3362 P1](https://github.com/androidx/media/issues/3362), [MEDIA3-3311 P1](https://github.com/androidx/media/issues/3311), [MEDIA3-2299 P1](https://github.com/androidx/media/issues/2299), [MEDIA3-1988 P1](https://github.com/androidx/media/issues/1988), [MEDIA3-1954 P1](https://github.com/androidx/media/issues/1954), [MEDIA3-663 P2](https://github.com/androidx/media/issues/663), [EXOPLAYER-1074 P1](https://github.com/google/ExoPlayer/issues/1074), [EXOPLAYER-537 P1](https://github.com/google/ExoPlayer/issues/537), [EXOPLAYER-6360 P2](https://github.com/google/ExoPlayer/issues/6360), [MEDIA3-1854 P1](https://github.com/androidx/media/issues/1854), [EXOPLAYER-87 P1](https://github.com/google/ExoPlayer/issues/87), [EXOPLAYER-5011 P1](https://github.com/google/ExoPlayer/issues/5011), [EXOPLAYER-7512 P1](https://github.com/google/ExoPlayer/issues/7512)
- Gap and receipt: pinned `vendor/ffmpeg/libavformat/hls.c::parse_playlist/reload_playlist/hls_read_seek` supplies basic HLS, but lacks the complete LL-HLS and delta tag family. `PlaybackCore.runDemux/handleBuffering` owns no live-window transaction.

### M04. Adaptive variants and segment scheduling

- Sources: [EXOPLAYER-10421 P2](https://github.com/google/ExoPlayer/issues/10421), [EXOPLAYER-3971 P2](https://github.com/google/ExoPlayer/issues/3971), [EXOPLAYER-1975 P2](https://github.com/google/ExoPlayer/issues/1975), [MEDIA3-621 P2](https://github.com/androidx/media/issues/621), [MPV-13393 P2](https://github.com/mpv-player/mpv/issues/13393), [MPV-3548 P2](https://github.com/mpv-player/mpv/issues/3548), [MPV-8334 P2](https://github.com/mpv-player/mpv/issues/8334), [MPV-16645 P2](https://github.com/mpv-player/mpv/issues/16645), [MPV-15158 P1](https://github.com/mpv-player/mpv/issues/15158), [MPV-17087 P2](https://github.com/mpv-player/mpv/issues/17087)
- Gap and receipt: `DashManifest`, `DashMediaIo.mediaItemFor`, `BufferPolicy`, and `PlaybackCore.handleTrackChanges` select one representation and provide no bandwidth estimator, ladder hysteresis, segment-aligned switch, SABR, or bounded parallel scheduler.

### M05. Dynamic DASH periods and live timeline

- Sources: [EXOPLAYER-7780 P1](https://github.com/google/ExoPlayer/issues/7780), [MEDIA3-284 P1](https://github.com/androidx/media/issues/284), [MEDIA3-1441 P1](https://github.com/androidx/media/issues/1441), [EXOPLAYER-9969 P1](https://github.com/google/ExoPlayer/issues/9969), [EXOPLAYER-8408 P1](https://github.com/google/ExoPlayer/issues/8408), [MEDIA3-2440 P1](https://github.com/androidx/media/issues/2440), [MEDIA3-1534 P1](https://github.com/androidx/media/issues/1534), [EXOPLAYER-771 P1](https://github.com/google/ExoPlayer/issues/771), [EXOPLAYER-1574 P1](https://github.com/google/ExoPlayer/issues/1574), [EXOPLAYER-4904 P1](https://github.com/google/ExoPlayer/issues/4904), [EXOPLAYER-9122 P1](https://github.com/google/ExoPlayer/issues/9122)
- Gap and receipt: `DashManifestParser.segmentPlan/resolveBaseUrl` and `DashMediaIo.read/mediaItemFor` implement only static, one-period, one-representation DASH, with no refresh, period graph, XLink, multi-BaseURL failover, or absolute live time.

### M06. RTSP, UDP, multicast authentication and failover

- Sources: [MEDIA3-869 P1](https://github.com/androidx/media/issues/869), [EXOPLAYER-10946 P1](https://github.com/google/ExoPlayer/issues/10946), [MEDIA3-1841 P1](https://github.com/androidx/media/issues/1841), [MEDIA3-522 P1](https://github.com/androidx/media/issues/522), [EXOPLAYER-8941 P1](https://github.com/google/ExoPlayer/issues/8941), [EXOPLAYER-11040 P1](https://github.com/google/ExoPlayer/issues/11040)
- Gap and receipt: `BuildFFmpegTask.sharedCoreArgs`, `KtorMediaIoResolver`, and `PlaybackCore.runDemux` provide no player-owned RTSP session, authentication, UDP-to-TCP fallback, or multicast liveness layer.

### M07. Source transports and connection control

- Sources: [MEDIA3-1848 P1](https://github.com/androidx/media/issues/1848), [MEDIA3-188 P2](https://github.com/androidx/media/issues/188), [EXOPLAYER-9945 P2](https://github.com/google/ExoPlayer/issues/9945), [MPV-16115 P2](https://github.com/mpv-player/mpv/issues/16115), [MPV-17081 P2](https://github.com/mpv-player/mpv/issues/17081)
- Gap and receipt: `KtorMediaIoResolver.resolve`, `KtorMediaIo`, the vendored protocol list, and `MediaItem.openOptions` lack SRT and WSS framing, a loader pause/resume transaction, idle connection close policy, and temporary-EOF follow state for growing sources.

### M08. Reconnect and failure recovery

- Sources: [MPV-5879 P1](https://github.com/mpv-player/mpv/issues/5879), [MPV-17698 P1](https://github.com/mpv-player/mpv/issues/17698)
- Gap and receipt: `PlaybackCore.handleWorkerOutcome/fail/teardownSession` and `CachingMediaIo.close` enter Failed and clear the cache, with no failure classification, retry, preserved resume point, or cache-transfer reopen.

### M09. Persistent disk cache and offline acquisition

- Sources: [EXOPLAYER-7326 P2](https://github.com/google/ExoPlayer/issues/7326), [MEDIA3-1755 P1](https://github.com/androidx/media/issues/1755), [EXOPLAYER-420 P1](https://github.com/google/ExoPlayer/issues/420), [EXOPLAYER-2643 P1](https://github.com/google/ExoPlayer/issues/2643), [EXOPLAYER-5978 P2](https://github.com/google/ExoPlayer/issues/5978), [VLC-11873 P2](https://code.videolan.org/videolan/vlc/-/issues/11873), [MPV-2084 P2](https://github.com/mpv-player/mpv/issues/2084)
- Gap and receipt: `CachingMediaIo`, `IoCachePolicy`, and `DashMediaIo` provide RAM-only contiguous windows, not a disk index, persistent namespace, offline manifest, resumable downloader, or merged parallel acquisition.

### M10. Cache window, fetch, and live latency policy

- Sources: [EXOPLAYER-565 P2](https://github.com/google/ExoPlayer/issues/565), [VLC-7012 P2](https://code.videolan.org/videolan/vlc/-/issues/7012), [MPV-10396 P2](https://github.com/mpv-player/mpv/issues/10396), [MPV-4754 P2](https://github.com/mpv-player/mpv/issues/4754), [MPV-8991 P2](https://github.com/mpv-player/mpv/issues/8991), [MPV-9442 P3](https://github.com/mpv-player/mpv/issues/9442), [MPV-5419 P1](https://github.com/mpv-player/mpv/issues/5419)
- Gap and receipt: `CachingMediaIo.read/seek/close`, `BufferPolicy`, and `PlaybackCore.handleBuffering/runDemux` have one RAM window and no cross-session reuse, backward-read optimization, logical clip bounds, low-water hysteresis, connection policy, or live-edge catch-up.

### M11. Gapless handoff and next-item prefetch

- Sources: [EXOPLAYER-3327 P2](https://github.com/google/ExoPlayer/issues/3327), [EXOPLAYER-497 P1](https://github.com/google/ExoPlayer/issues/497), [MEDIA3-3183 P1](https://github.com/androidx/media/issues/3183), [MEDIA3-2576 P1](https://github.com/androidx/media/issues/2576), [EXOPLAYER-3470 P1](https://github.com/google/ExoPlayer/issues/3470), [EXOPLAYER-3475 P1](https://github.com/google/ExoPlayer/issues/3475), [MEDIA3-1744 P2](https://github.com/androidx/media/issues/1744), [EXOPLAYER-9319 P1](https://github.com/google/ExoPlayer/issues/9319), [EXOPLAYER-3526 P1](https://github.com/google/ExoPlayer/issues/3526), [VLC-549 P1](https://code.videolan.org/videolan/vlc/-/issues/549), [MPV-1101 P1](https://github.com/mpv-player/mpv/issues/1101), [MPV-2823 P2](https://github.com/mpv-player/mpv/issues/2823), [MPV-6437 P2](https://github.com/mpv-player/mpv/issues/6437)
- Gap and receipt: `PlaybackCore.handleQueueAdvance/runOpen/buildSession` and `AudioPlayback` tear down the one `OpenSession` and sink before the next opens, with no standby decoder, prefetch cache, sample-contiguous handoff, or cross-item trim transaction.

### M12. Queue editing, persistence, formats, and advance policy

- Sources: [EXOPLAYER-4727 P2](https://github.com/google/ExoPlayer/issues/4727), [EXOPLAYER-5020 P2](https://github.com/google/ExoPlayer/issues/5020), [EXOPLAYER-1706 P2](https://github.com/google/ExoPlayer/issues/1706), [EXOPLAYER-4915 P2](https://github.com/google/ExoPlayer/issues/4915), [EXOPLAYER-4343 P1](https://github.com/google/ExoPlayer/issues/4343), [MPV-14642 P3](https://github.com/mpv-player/mpv/issues/14642), [MPV-16351 P2](https://github.com/mpv-player/mpv/issues/16351), [MPV-4738 P2](https://github.com/mpv-player/mpv/issues/4738), [MPV-11666 P2](https://github.com/mpv-player/mpv/issues/11666), [MPV-10523 P2](https://github.com/mpv-player/mpv/issues/10523)
- Gap and receipt: `KitePlayer.openQueue/next/previous`, `PlaybackCore.openQueue/handleQueueAdvance`, and `PlayerSnapshot.queue` accept fixed caller-built items. There is no edit or shuffle transaction, future-duration map, error-skip policy, persistence, XSPF or M3U parser, or per-entry directive translation.

### M13. Seek indexing, growing media, and reverse step

- Sources: [MEDIA3-320 P2](https://github.com/androidx/media/issues/320), [MEDIA3-2848 P2](https://github.com/androidx/media/issues/2848), [EXOPLAYER-9408 P2](https://github.com/google/ExoPlayer/issues/9408), [VLC-20730 P2](https://code.videolan.org/videolan/vlc/-/issues/20730), [VLC-16950 P1](https://code.videolan.org/videolan/vlc/-/issues/16950), [MPV-2380 P3](https://github.com/mpv-player/mpv/issues/2380), [MPV-4019 P2](https://github.com/mpv-player/mpv/issues/4019), [MPV-16660 P2](https://github.com/mpv-player/mpv/issues/16660)
- Gap and receipt: `KiteCodecSource.seekToKeyframe/duration`, `PlaybackCore.runSeek/stepOneFrame`, and public `stepFrame` provide a generic forward path only, not MP3 policy/index building, growing-duration refresh, adjacent-keyframe navigation, reverse decoded cache, or audio back-step.

### M14. Chapters, editions, linked segments, and sidecars

- Sources: [VLC-4501 P1](https://code.videolan.org/videolan/vlc/-/issues/4501), [VLC-8917 P2](https://code.videolan.org/videolan/vlc/-/issues/8917), [VLC-1069 P3](https://code.videolan.org/videolan/vlc/-/issues/1069), [VLC-10542 P1](https://code.videolan.org/videolan/vlc/-/issues/10542), [VLC-11243 P2](https://code.videolan.org/videolan/vlc/-/issues/11243), [VLC-28748 P2](https://code.videolan.org/videolan/vlc/-/issues/28748), [MPV-3073 P2](https://github.com/mpv-player/mpv/issues/3073), [MPV-8463 P2](https://github.com/mpv-player/mpv/issues/8463), [MPV-9600 P3](https://github.com/mpv-player/mpv/issues/9600), [MPV-14235 P3](https://github.com/mpv-player/mpv/issues/14235), [MPV-16936 P3](https://github.com/mpv-player/mpv/issues/16936), [MPV-201 P3](https://github.com/mpv-player/mpv/issues/201), [MPV-173 P3](https://github.com/mpv-player/mpv/issues/173), [MPV-518 P3](https://github.com/mpv-player/mpv/issues/518), [MPV-16451 P2](https://github.com/mpv-player/mpv/issues/16451)
- Gap and receipt: `KiteCodecSource.chapters`, `Chapter`, and `PlaybackCore.seekToChapter/chapterAt` expose one flat start/end/title list, not editions, linked UID graphs, alternate titles or images, external chapter and CUE files, skip ranges, or chapter-aware coarse seek.

### M15. Text subtitle formats, encoding, and timing

- Sources: [EXOPLAYER-7462 P1](https://github.com/google/ExoPlayer/issues/7462), [EXOPLAYER-5869 P1](https://github.com/google/ExoPlayer/issues/5869), [EXOPLAYER-3938 P2](https://github.com/google/ExoPlayer/issues/3938), [EXOPLAYER-8504 P2](https://github.com/google/ExoPlayer/issues/8504), [EXOPLAYER-7020 P2](https://github.com/google/ExoPlayer/issues/7020), [MEDIA3-2517 P1](https://github.com/androidx/media/issues/2517), [EXOPLAYER-7844 P2](https://github.com/google/ExoPlayer/issues/7844), [MEDIA3-588 P1](https://github.com/androidx/media/issues/588), [MEDIA3-288 P1](https://github.com/androidx/media/issues/288), [VLC-29321 P1](https://code.videolan.org/videolan/vlc/-/issues/29321), [VLC-27145 P1](https://code.videolan.org/videolan/vlc/-/issues/27145), [VLC-28564 P1](https://code.videolan.org/videolan/vlc/-/issues/28564), [VLC-19377 P1](https://code.videolan.org/videolan/vlc/-/issues/19377), [VLC-17602 P1](https://code.videolan.org/videolan/vlc/-/issues/17602), [VLC-17403 P0](https://code.videolan.org/videolan/vlc/-/issues/17403), [MPV-9955 P2](https://github.com/mpv-player/mpv/issues/9955)
- Gap and receipt: `KiteCodecSubtitleDecoderFactory`, `WebVttParser`, `SubRipParser`, tx3g, and `AssParser` lack SAMI, LRC, embedded lyrics, gzip and non-UTF input, full TTML and WebVTT settings, segmented timing, and a hardened future TTML/JACOsub parser.

### M16. Subtitle styling, animation, and prerender

- Sources: [EXOPLAYER-8435 P2](https://github.com/google/ExoPlayer/issues/8435), [EXOPLAYER-9813 P2](https://github.com/google/ExoPlayer/issues/9813), [EXOPLAYER-10148 P2](https://github.com/google/ExoPlayer/issues/10148), [VLC-26128 P1](https://code.videolan.org/videolan/vlc/-/issues/26128), [VLC-17516 P1](https://code.videolan.org/videolan/vlc/-/issues/17516), [MPV-16023 P2](https://github.com/mpv-player/mpv/issues/16023), [MPV-14800 P2](https://github.com/mpv-player/mpv/issues/14800), [MPV-11612 P2](https://github.com/mpv-player/mpv/issues/11612), [MPV-7214 P2](https://github.com/mpv-player/mpv/issues/7214), [MPV-11853 P3](https://github.com/mpv-player/mpv/issues/11853), [MPV-10690 P2](https://github.com/mpv-player/mpv/issues/10690)
- Gap and receipt: `AssParser.parseOverrideText`, `WebVttParser`, `CueStyle`, `SubtitleConfig.lookahead`, and the built-in rasterizers provide no full libass vector, blur, and karaoke animation, WebVTT STYLE and REGION, rounded background geometry, or advance-render cache.

### M17. Subtitle discovery and selection policy

- Sources: [MEDIA3-466 P2](https://github.com/androidx/media/issues/466), [MEDIA3-639 P1](https://github.com/androidx/media/issues/639), [VLC-28659 P2](https://code.videolan.org/videolan/vlc/-/issues/28659), [MPV-6006 P2](https://github.com/mpv-player/mpv/issues/6006), [MPV-4144 P2](https://github.com/mpv-player/mpv/issues/4144), [MPV-1774 P3](https://github.com/mpv-player/mpv/issues/1774), [MPV-4016 P2](https://github.com/mpv-player/mpv/issues/4016), [MPV-12464 P3](https://github.com/mpv-player/mpv/issues/12464), [MPV-5433 P3](https://github.com/mpv-player/mpv/issues/5433), [MPV-13215 P2](https://github.com/mpv-player/mpv/issues/13215), [MPV-13290 P2](https://github.com/mpv-player/mpv/issues/13290), [MPV-15657 P3](https://github.com/mpv-player/mpv/issues/15657)
- Gap and receipt: `MediaItem.externalSubtitles` and `PlaybackCore.parseExternalSubtitles/pickSubtitle/inPlaceAudioChange` have no sidecar scan, Unicode filename normalization, recursive mode, per-event forced filtering, configurable role order, same-audio-language suppression, or policy rerun after audio change.

### M18. Concurrent subtitle lanes and live cue API

- Sources: [VLC-25003 P1](https://code.videolan.org/videolan/vlc/-/issues/25003), [VLC-7082 P3](https://code.videolan.org/videolan/vlc/-/issues/7082), [MPV-3022 P2](https://github.com/mpv-player/mpv/issues/3022), [MPV-3087 P1](https://github.com/mpv-player/mpv/issues/3087), [MPV-14989 P2](https://github.com/mpv-player/mpv/issues/14989), [MPV-4209 P2](https://github.com/mpv-player/mpv/issues/4209), [MPV-16593 P3](https://github.com/mpv-player/mpv/issues/16593), [MPV-18081 P2](https://github.com/mpv-player/mpv/issues/18081), [MPV-16751 P2](https://github.com/mpv-player/mpv/issues/16751), [MPV-14647 P2](https://github.com/mpv-player/mpv/issues/14647)
- Gap and receipt: `OpenSession` subtitle fields, `timeAndPublishCues`, and `KitePlayer.addExternalSubtitle` expose one active decoder and overlay, not a secondary lane, public cue browse/copy flow, mutable timed cue ingress, transcription lane, or streaming `SubtitleSource.io`.

### M19. Subtitle placement, rotation, and composition

- Sources: [VLC-27743 P2](https://code.videolan.org/videolan/vlc/-/issues/27743), [VLC-2040 P2](https://code.videolan.org/videolan/vlc/-/issues/2040), [MPV-6400 P3](https://github.com/mpv-player/mpv/issues/6400), [MPV-11580 P3](https://github.com/mpv-player/mpv/issues/11580), [MPV-346 P3](https://github.com/mpv-player/mpv/issues/346), [MPV-14372 P3](https://github.com/mpv-player/mpv/issues/14372)
- Gap and receipt: `SubtitleRasterizer.rasterize`, `SubtitleConfig`, and `PlaybackCore.publishOverlay` offer vertical position and font scale but no horizontal offset, below-picture region, or overlay rotation and classification.

### M20. Dynamic track inventory, selection, and persistence

- Sources: [MEDIA3-2777 P1](https://github.com/androidx/media/issues/2777), [EXOPLAYER-7873 P1](https://github.com/google/ExoPlayer/issues/7873), [MEDIA3-2280 P1](https://github.com/androidx/media/issues/2280), [VLC-28778 P1](https://code.videolan.org/videolan/vlc/-/issues/28778), [VLC-22226 P2](https://code.videolan.org/videolan/vlc/-/issues/22226), [VLC-8118 P2](https://code.videolan.org/videolan/vlc/-/issues/8118), [VLC-8987 P2](https://code.videolan.org/videolan/vlc/-/issues/8987), [MPV-5133 P2](https://github.com/mpv-player/mpv/issues/5133), [MPV-8625 P2](https://github.com/mpv-player/mpv/issues/8625)
- Gap and receipt: `KiteCodecSource.streams`, `PlaybackCore.buildSession/pickAudio/pickSubtitle/handleTrackChanges`, and `Tracks` freeze inventory and expose a fixed preference schema, not PMT-added tracks, persisted selections, required-audio fallback, richer predicates, or dynamic resolution identity.

### M21. External sources and simultaneous media lanes

- Sources: [VLC-22434 P1](https://code.videolan.org/videolan/vlc/-/issues/22434), [VLC-21868 P2](https://code.videolan.org/videolan/vlc/-/issues/21868), [VLC-19984 P1](https://code.videolan.org/videolan/vlc/-/issues/19984), [VLC-6985 P1](https://code.videolan.org/videolan/vlc/-/issues/6985), [MPV-3777 P2](https://github.com/mpv-player/mpv/issues/3777), [MPV-967 P2](https://github.com/mpv-player/mpv/issues/967), [MPV-10554 P2](https://github.com/mpv-player/mpv/issues/10554), [MPV-14729 P1](https://github.com/mpv-player/mpv/issues/14729)
- Gap and receipt: `MediaItem.externalSubtitles`, `PlaybackCore.buildSession/OpenSession.audioLane`, and `Tracks` have no external-audio source, simultaneous audio or video lane graph, per-lane output, or external-audio seek continuity.

### M22. Audio device routing, recovery, and multi-output

- Sources: [VLC-9231 P1](https://code.videolan.org/videolan/vlc/-/issues/9231), [MPV-6109 P2](https://github.com/mpv-player/mpv/issues/6109), [MPV-2398 P2](https://github.com/mpv-player/mpv/issues/2398), [MPV-13989 P2](https://github.com/mpv-player/mpv/issues/13989), [MPV-675 P2](https://github.com/mpv-player/mpv/issues/675), [MPV-8579 P1](https://github.com/mpv-player/mpv/issues/8579), [MPV-10916 P1](https://github.com/mpv-player/mpv/issues/10916), [MPV-13596 P3](https://github.com/mpv-player/mpv/issues/13596)
- Gap and receipt: `OutputBackend.audioSink`, `AudioSinkFactory.create`, and `PlaybackCore.startAudioEventCollector` expose one immutable sink factory. Device change or loss is mostly warning-only, with no enumeration, selection, keepalive, retry, pause policy, or fan-out clock law.

### M23. Audio codecs, bit-perfect passthrough, and spatial output

- Sources: [MEDIA3-415 P2](https://github.com/androidx/media/issues/415), [MEDIA3-3103 P2](https://github.com/androidx/media/issues/3103), [EXOPLAYER-3751 P1](https://github.com/google/ExoPlayer/issues/3751), [EXOPLAYER-2147 P2](https://github.com/google/ExoPlayer/issues/2147), [VLC-27737 P1](https://code.videolan.org/videolan/vlc/-/issues/27737), [MPV-14048 P2](https://github.com/mpv-player/mpv/issues/14048), [MPV-9252 P2](https://github.com/mpv-player/mpv/issues/9252), [MPV-11306 P2](https://github.com/mpv-player/mpv/issues/11306), [MPV-4478 P1](https://github.com/mpv-player/mpv/issues/4478), [MPV-13794 P1](https://github.com/mpv-player/mpv/issues/13794), [MPV-266 P2](https://github.com/mpv-player/mpv/issues/266)
- Gap and receipt: `Decoders.AudioFormat/SampleFormat`, `AudioPipeline`, `ChannelMixer`, and `AudioSink` turn content into ordinary float PCM. There is no APE tier, DSD or DoP, IEC 61937, exclusive integer path, noncanonical high-channel layout, or Apple and Windows spatial-object contract.

### M24. Audio processing, loudness, and transport ramps

- Sources: [MEDIA3-418 P2](https://github.com/androidx/media/issues/418), [MEDIA3-602 P2](https://github.com/androidx/media/issues/602), [EXOPLAYER-10516 P2](https://github.com/google/ExoPlayer/issues/10516), [MPV-5079 P2](https://github.com/mpv-player/mpv/issues/5079), [MPV-3405 P2](https://github.com/mpv-player/mpv/issues/3405), [MPV-6797 P2](https://github.com/mpv-player/mpv/issues/6797), [MPV-15581 P3](https://github.com/mpv-player/mpv/issues/15581), [MPV-16990 P3](https://github.com/mpv-player/mpv/issues/16990), [MPV-18040 P3](https://github.com/mpv-player/mpv/issues/18040), [MPV-13030 P2](https://github.com/mpv-player/mpv/issues/13030), [MPV-6210 P2](https://github.com/mpv-player/mpv/issues/6210)
- Gap and receipt: the fixed `AudioPipeline.process` chain, `GainStage`, and `TempoStage` provide no MediaItem-aware or caller filter slot, ReplayGain or R128, equalizer, upmixer, denoiser, silence detector, seamless rate transaction, transport fade, or scrub mute.

### M25. HDR metadata, Dolby Vision, and native HDR output

- Sources: [MEDIA3-1941 P1](https://github.com/androidx/media/issues/1941), [VLC-18813 P2](https://code.videolan.org/videolan/vlc/-/issues/18813), [VLC-21909 P2](https://code.videolan.org/videolan/vlc/-/issues/21909), [VLC-18088 P2](https://code.videolan.org/videolan/vlc/-/issues/18088), [VLC-28664 P1](https://code.videolan.org/videolan/vlc/-/issues/28664), [MPV-5161 P2](https://github.com/mpv-player/mpv/issues/5161), [MPV-7341 P2](https://github.com/mpv-player/mpv/issues/7341), [MPV-15919 P3](https://github.com/mpv-player/mpv/issues/15919), [MPV-7326 P1](https://github.com/mpv-player/mpv/issues/7326), [MPV-17265 P2](https://github.com/mpv-player/mpv/issues/17265), [MPV-16972 P2](https://github.com/mpv-player/mpv/issues/16972), [MPV-10129 P1](https://github.com/mpv-player/mpv/issues/10129)
- Gap and receipt: `ColorSpaceInfo`, `HdrToneMap`, `DashManifestParser`, and `MediaCodecVideoDecoder` carry no mastering and content-light metadata, HDR10+ or Dolby Vision RPU, native desktop HDR presentation, display-mode transaction, or output peak handoff.

### M26. Display color management and profiles

- Sources: [MPV-2815 P2](https://github.com/mpv-player/mpv/issues/2815), [MPV-4248 P2](https://github.com/mpv-player/mpv/issues/4248), [MPV-17850 P2](https://github.com/mpv-player/mpv/issues/17850), [MPV-8082 P2](https://github.com/mpv-player/mpv/issues/8082), [MPV-17170 P2](https://github.com/mpv-player/mpv/issues/17170), [MPV-16808 P2](https://github.com/mpv-player/mpv/issues/16808), [MPV-594 P2](https://github.com/mpv-player/mpv/issues/594)
- Gap and receipt: `MetalVideoRenderer/MetalVideoSupport`, Android rendering, and `RenderQuality` provide no ICC or ColorSync loader, display observer, 3D LUT, target contrast or primaries override, Windows ACM scRGB path, or Wayland color-management protocol.

### M27. Tone mapping, gain maps, and overlay luminance

- Sources: [MPV-8249 P2](https://github.com/mpv-player/mpv/issues/8249), [MPV-12730 P2](https://github.com/mpv-player/mpv/issues/12730), [MPV-18072 P2](https://github.com/mpv-player/mpv/issues/18072), [MPV-12139 P2](https://github.com/mpv-player/mpv/issues/12139), [MPV-18320 P2](https://github.com/mpv-player/mpv/issues/18320)
- Gap and receipt: `HdrToneMap.eetfNits/mapInPlace`, `MetalVideoSupport.kp_tone_map`, and `SubtitleOverlay` implement a fixed BT.2390-like 1000-to-203-nit path, not BT.2408, white and boost controls, Ultra HDR gain maps, or subtitle luminance metadata.

### M28. Deep-color pixel precision and output depth

- Sources: [VLC-26706 P1](https://code.videolan.org/videolan/vlc/-/issues/26706), [VLC-28763 P1](https://code.videolan.org/videolan/vlc/-/issues/28763), [VLC-27235 P1](https://code.videolan.org/videolan/vlc/-/issues/27235), [VLC-21670 P1](https://code.videolan.org/videolan/vlc/-/issues/21670), [MPV-5237 P2](https://github.com/mpv-player/mpv/issues/5237), [MPV-9415 P2](https://github.com/mpv-player/mpv/issues/9415), [MPV-3613 P2](https://github.com/mpv-player/mpv/issues/3613), [MPV-17709 P2](https://github.com/mpv-player/mpv/issues/17709)
- Gap and receipt: `PlayerPixelFormat`, `KiteCodecSource.mapPixelFormat`, `MetalFrameComposer.targetFormat`, and `RenderQuality.dither` have incomplete high-bit, chroma, and grayscale vocabulary and an 8-bit BGRA shipping target, without native 10-bit output, internal precision control, or error-diffusion dithering.

### M29. Frame geometry, spatial layers, crop, and field order

- Sources: [VLC-29092 P1](https://code.videolan.org/videolan/vlc/-/issues/29092), [VLC-20216 P1](https://code.videolan.org/videolan/vlc/-/issues/20216), [VLC-3376 P1](https://code.videolan.org/videolan/vlc/-/issues/3376), [MPV-16278 P2](https://github.com/mpv-player/mpv/issues/16278), [MPV-624 P2](https://github.com/mpv-player/mpv/issues/624)
- Gap and receipt: `VideoFrame/VideoSize`, `PlayerStreamInfo`, and `KiteCodecVideoFrame` expose no AV1 spatial-layer selection, explicit field-order and interlaced-output path, or clean-aperture and crop rectangle through rendering.

### M30. Video crop, rotation, reflection, and nonlinear transforms

- Sources: [EXOPLAYER-7495 P2](https://github.com/google/ExoPlayer/issues/7495), [VLC-26194 P2](https://code.videolan.org/videolan/vlc/-/issues/26194), [VLC-5169 P2](https://code.videolan.org/videolan/vlc/-/issues/5169), [VLC-23645 P2](https://code.videolan.org/videolan/vlc/-/issues/23645), [VLC-26575 P2](https://code.videolan.org/videolan/vlc/-/issues/26575), [VLC-29101 P1](https://code.videolan.org/videolan/vlc/-/issues/29101), [MPV-12222 P2](https://github.com/mpv-player/mpv/issues/12222), [MPV-3647 P3](https://github.com/mpv-player/mpv/issues/3647), [MPV-3434 P2](https://github.com/mpv-player/mpv/issues/3434), [MPV-5386 P2](https://github.com/mpv-player/mpv/issues/5386), [MPV-5869 P2](https://github.com/mpv-player/mpv/issues/5869), [MPV-12856 P3](https://github.com/mpv-player/mpv/issues/12856)
- Gap and receipt: `VideoTransform`, `FrameLayout.quarterTurn/frameLayout`, and `VideoFrame.rotationDegrees` provide aspect, zoom, pan, and authored quarter turns, but no user affine rotation, reflection, renderer crop, authored-rotation override, or nonlinear stretch.

### M31. Immersive 360 and stereoscopic video

- Sources: [MPV-2280 P2](https://github.com/mpv-player/mpv/issues/2280), [MPV-1945 P2](https://github.com/mpv-player/mpv/issues/1945), [MPV-2583 P2](https://github.com/mpv-player/mpv/issues/2583), [MPV-1124 P2](https://github.com/mpv-player/mpv/issues/1124), [MPV-18380 P2](https://github.com/mpv-player/mpv/issues/18380)
- Gap and receipt: `VideoFrame`, `PlayerStreamInfo`, `VideoTransform`, `FrameLayout`, and `SubtitleOverlay` model one flat picture, with no projection, eye identity, stereo packing, MVC dependency, display-mode controller, or per-eye subtitle mapping.

### M32. Refresh matching, temporal interpolation, and cadence

- Sources: [VLC-26184 P2](https://code.videolan.org/videolan/vlc/-/issues/26184), [MPV-12005 P2](https://github.com/mpv-player/mpv/issues/12005), [MPV-11060 P2](https://github.com/mpv-player/mpv/issues/11060), [MPV-6137 P2](https://github.com/mpv-player/mpv/issues/6137), [MPV-17140 P2](https://github.com/mpv-player/mpv/issues/17140), [MPV-15748 P2](https://github.com/mpv-player/mpv/issues/15748), [MPV-7674 P2](https://github.com/mpv-player/mpv/issues/7674), [MPV-12898 P2](https://github.com/mpv-player/mpv/issues/12898), [MPV-552 P2](https://github.com/mpv-player/mpv/issues/552), [MPV-13773 P2](https://github.com/mpv-player/mpv/issues/13773), [MPV-2685 P2](https://github.com/mpv-player/mpv/issues/2685), [MPV-731 P2](https://github.com/mpv-player/mpv/issues/731)
- Gap and receipt: `VideoPlayback.tick/present`, `VideoRenderer.vsyncIntervalNanos`, and `RenderQuality` receive no shipping display interval and provide no display-mode transaction, VRR control, frame history, temporal or optical-flow synthesis, frame multiplier, or black-frame insertion policy.

### M33. Hardware decode routes and fixed-function processing

- Sources: [MPV-11739 P2](https://github.com/mpv-player/mpv/issues/11739), [MPV-5978 P2](https://github.com/mpv-player/mpv/issues/5978), [MPV-11050 P2](https://github.com/mpv-player/mpv/issues/11050), [MPV-12071 P2](https://github.com/mpv-player/mpv/issues/12071), [MPV-231 P2](https://github.com/mpv-player/mpv/issues/231), [MPV-2797 P2](https://github.com/mpv-player/mpv/issues/2797), [MPV-129 P2](https://github.com/mpv-player/mpv/issues/129), [MPV-7465 P2](https://github.com/mpv-player/mpv/issues/7465)
- Gap and receipt: Linux and MinGW `PlatformDecoderSelection`, `KiteCodecVideoDecoderFactory.create`, and `MediaItem.videoFilter` have no Vulkan Video, CUDA or NVDEC, D3D12VA, or VAAPI route, fixed-function rotate, deinterlace and VPP, or selective copyback.

### M34. Shader, filter, and scaler extensibility

- Sources: [MPV-9818 P2](https://github.com/mpv-player/mpv/issues/9818), [MPV-8137 P2](https://github.com/mpv-player/mpv/issues/8137), [MPV-18068 P2](https://github.com/mpv-player/mpv/issues/18068), [MPV-10810 P2](https://github.com/mpv-player/mpv/issues/10810), [MPV-6575 P2](https://github.com/mpv-player/mpv/issues/6575), [MPV-9989 P2](https://github.com/mpv-player/mpv/issues/9989), [MPV-11390 P2](https://github.com/mpv-player/mpv/issues/11390), [MPV-568 P2](https://github.com/mpv-player/mpv/issues/568), [MPV-13313 P3](https://github.com/mpv-player/mpv/issues/13313), [MPV-14923 P2](https://github.com/mpv-player/mpv/issues/14923), [MPV-2230 P2](https://github.com/mpv-player/mpv/issues/2230), [MPV-18288 P2](https://github.com/mpv-player/mpv/issues/18288), [MPV-14855 P2](https://github.com/mpv-player/mpv/issues/14855), [MPV-9314 P2](https://github.com/mpv-player/mpv/issues/9314)
- Gap and receipt: `RenderQuality/VideoScaler`, `VideoRenderer`, Metal shaders, and `MediaItem.videoFilter` form a fixed bilinear or Catmull-Rom tier, not a shader-history hook, libplacebo or Vulkan graph, AMF, RTX or NNEDI kernels, plane-specific scaling, threaded or OpenCL filter graph, or antiringing control.

### M35. Output backends, embedding, and lifecycle

- Sources: [VLC-21872 P2](https://code.videolan.org/videolan/vlc/-/issues/21872), [MPV-8569 P2](https://github.com/mpv-player/mpv/issues/8569), [MPV-8910 P2](https://github.com/mpv-player/mpv/issues/8910), [MPV-11379 P2](https://github.com/mpv-player/mpv/issues/11379), [MPV-18087 P2](https://github.com/mpv-player/mpv/issues/18087), [MPV-3612 P3](https://github.com/mpv-player/mpv/issues/3612), [MPV-9654 P2](https://github.com/mpv-player/mpv/issues/9654)
- Gap and receipt: `OutputBackend.videoRenderer`, `VideoRenderer`, and `AppKitWindow` provide no Linux Wayland renderer or surface adapter, PipeWire, DeckLink or AVSampleBuffer output, dmabuf pre-rotation, PiP controller, or hidden-window decode-suspend policy.

### M36. Post-render capture and screenshots

- Sources: [VLC-29775 P1](https://code.videolan.org/videolan/vlc/-/issues/29775), [VLC-26647 P2](https://code.videolan.org/videolan/vlc/-/issues/26647), [VLC-21575 P2](https://code.videolan.org/videolan/vlc/-/issues/21575), [VLC-21531 P2](https://code.videolan.org/videolan/vlc/-/issues/21531), [MPV-15107 P2](https://github.com/mpv-player/mpv/issues/15107)
- Gap and receipt: `CapturedFrame.of`, `VideoFrame.captureFrame`, and `KiteCodecVideoFrame.download` capture decoded pre-render pixels, not hardware-surface-safe, display-oriented, tone-mapped, color-managed, or HDR-encoded output.

### M37. Timed metadata, markers, thumbnails, and artwork

- Sources: [EXOPLAYER-3903 P2](https://github.com/google/ExoPlayer/issues/3903), [MEDIA3-1002 P1](https://github.com/androidx/media/issues/1002), [MEDIA3-2428 P1](https://github.com/androidx/media/issues/2428), [EXOPLAYER-3735 P1](https://github.com/google/ExoPlayer/issues/3735), [MEDIA3-3334 P2](https://github.com/androidx/media/issues/3334), [MPV-3056 P3](https://github.com/mpv-player/mpv/issues/3056), [MPV-14756 P2](https://github.com/mpv-player/mpv/issues/14756)
- Gap and receipt: `PlayerMediaSource.metadata`, `PlayerPacket`, `PlayerSnapshot`, and `Chapter` expose static metadata only, with no timed emsg, date-range, ICY or HLS event lane, arbitrary markers, DASH thumbnail map, or external cover-art source.

### M38. Public diagnostics and decoded media introspection

- Sources: [MPV-4460 P3](https://github.com/mpv-player/mpv/issues/4460), [MPV-9283 P2](https://github.com/mpv-player/mpv/issues/9283), [MPV-18169 P3](https://github.com/mpv-player/mpv/issues/18169), [MPV-8995 P3](https://github.com/mpv-player/mpv/issues/8995), [MPV-12842 P3](https://github.com/mpv-player/mpv/issues/12842), [MPV-15925 P3](https://github.com/mpv-player/mpv/issues/15925), [MPV-16737 P3](https://github.com/mpv-player/mpv/issues/16737)
- Gap and receipt: `PlayerSnapshot`, `TrackInfo`, `PlaybackStats`, `diagnosticsDump`, and private `AudioPlayback.submitDecoded` expose no aggregate queue duration, decoded-audio callback, cross-codec bit depth, container bitrate, active-filter list, or explicit live classification.

### M39. Bounded playback, bookmarks, and loop controls

- Sources: [MPV-1171 P3](https://github.com/mpv-player/mpv/issues/1171), [MPV-7975 P3](https://github.com/mpv-player/mpv/issues/7975), [MPV-9253 P2](https://github.com/mpv-player/mpv/issues/9253), [MPV-9965 P2](https://github.com/mpv-player/mpv/issues/9965), [MPV-17953 P3](https://github.com/mpv-player/mpv/issues/17953), [MPV-17236 P3](https://github.com/mpv-player/mpv/issues/17236), [MPV-16945 P3](https://github.com/mpv-player/mpv/issues/16945), [MPV-11521 P3](https://github.com/mpv-player/mpv/issues/11521)
- Gap and receipt: `MediaItem.startPosition`, `KitePlayer.setAbLoop`, and `PlayerSnapshot.abLoopA/B` provide one player-scoped pair, not bounded item end and rebased duration, bookmarks, multiple named loops, reset-on-open, loop events and counts, speed-aware seek distance, or symmetric one-sided loop defaults.

### M40. External clock and synchronized playback

- Sources: [VLC-20167 P1](https://code.videolan.org/videolan/vlc/-/issues/20167), [MPV-1272 P2](https://github.com/mpv-player/mpv/issues/1272), [MPV-5413 P2](https://github.com/mpv-player/mpv/issues/5413)
- Gap and receipt: `SyncMode.ExternalMaster`, `MonotonicClock`, `MediaClock`, and `PlaybackCore.masterClockKind` document external master as unimplemented, with no remote samples, drift law and resampling, network coordinator, or MTC, MMC, and OSC adapter.

## IMMUNE honor roll

These examples document load-bearing design choices that should not be simplified away.

1. [MEDIA3-2965 P0](https://github.com/androidx/media/issues/2965): frame-owned format metadata plus seek flush excludes the nullable format-queue crash. Receipt: `KiteCodecVideoDecoder.receive`; `PlaybackCore.runSeek`.
2. [MEDIA3-1499 P0](https://github.com/androidx/media/issues/1499): seeks reuse one AudioTrack driver instead of leaking a new AudioFlinger track. Receipt: `AudioTrackSink.stop`; platform driver lifecycle.
3. [EXOPLAYER-6155 P1](https://github.com/google/ExoPlayer/issues/6155): pinned `hls_read_seek/hls_read_packet` discards through the exact target, and MP3 packets are key packets.
4. [EXOPLAYER-6787 P1](https://github.com/google/ExoPlayer/issues/6787): pinned MP3 defaults `usetoc=0`, so generic frame scanning and indexing avoid the coarse-TOC mechanism.
5. [MEDIA3-2309 P1](https://github.com/androidx/media/issues/2309): every subtitle lane is cached; in-place selection plus `activeAt` publishes an already-started cue without rebuild. Receipt: subtitle decoders and `PlaybackCore.inPlaceContainerSubtitleChange/handleSubtitles`.
6. [VLC-28541 P0](https://code.videolan.org/videolan/vlc/-/issues/28541): an unavailable audio device becomes typed open failure and reverse-order rollback, not a native crash. Receipt: `PlaybackCore.buildSession/runOpen`.
7. [VLC-27499 P0](https://code.videolan.org/videolan/vlc/-/issues/27499): actor serialization and generation rejection exclude concurrent old-epoch drain plus seek flush.
8. [VLC-27508 P0](https://code.videolan.org/videolan/vlc/-/issues/27508): play and flush are session-owner actor operations; seek quiesces workers before `AudioPlayback.flush`.
9. [VLC-26716 P0](https://code.videolan.org/videolan/vlc/-/issues/26716): there is no PCR listener attachment API or double-attach state; clocks are session-owned single-writer values.
10. [VLC-25181 P1](https://code.videolan.org/videolan/vlc/-/issues/25181): staged EOF drains decoder, DSP tail, ring, and sink before Ended. Receipt: `PlaybackCore.handleEof`; `AudioPipeline.finish`.
11. [MPV-2654 P0](https://github.com/mpv-player/mpv/issues/2654): the PulseAudio DSD crash path is absent; desktop output submits negotiated PCM and the pipeline resamples before the sink.
12. [MPV-12369 P1](https://github.com/mpv-player/mpv/issues/12369): every audio stream has a live packet cache and `inPlaceAudioChange` preserves source and network progress.
13. [MPV-7780 P1](https://github.com/mpv-player/mpv/issues/7780): every container subtitle is cached and `inPlaceContainerSubtitleChange` preserves demux and byte cache.
14. [MPV-3928 P1](https://github.com/mpv-player/mpv/issues/3928): tempo latency is counted from owned emitted frames and anchored to the sink deadline, not guessed from an external filter.
15. [MPV-12084 P1](https://github.com/mpv-player/mpv/issues/12084): hardware demotion explicitly opens software and replays retained packets without automatic reselection back to hardware.

## Proposed work order

1. Bound external subtitle admission by bytes, cues, wall time, and cancellation. It is the only P0 SUSPECT and has a direct untrusted-input memory seam.
2. Decouple forced-subtitle fallback from language preferences. Three trackers repeat the same P1 selection-law defect, and the change is tightly scoped to `pickSubtitle` plus tests.
3. Introduce first-class timestamp epochs, discontinuity resets, and transport-program identity. This unifies six repeated risks plus program and HLS EVENT cases.
4. Define one startup, readiness, and master-clock contract around the first audible anchor, delayed device latency, absent live audio, and anchor publication.
5. Add resumable network failure handling with typed retry classes, timeout and backoff, range reopen identity, close-before-join cancellation, and cache preservation.
6. Make seeking bounded and terminal-state-aware with cancellable native I/O, exact last-frame landing, post-seek premature-EOF rejection, and HLS EVENT correction.
7. Support dynamic track inventory and persist explicit user intent across queue items while preserving the current in-place audio and subtitle cache architecture.
8. Build memory-safe bitmap and broadcast subtitle engines first. The 25-row capability cluster includes six of the seven P0 missing receipts and major accessibility value.
9. Add true next-item prefetch and sample-contiguous gapless handoff. Thirteen cross-tracker rows converge on one session and sink lifecycle gap.
10. Add a player-owned adaptive-streaming layer above pinned FFmpeg: HLS and LL-HLS manifest state, dynamic DASH periods, ABR, failover, live window, and segment cache policy.
