# SALANKE Media3 and ExoPlayer tracker

- Date: 2026-08-26
- Trackers: github.com/androidx/media issues and archived github.com/google/ExoPlayer issues
- Counts: 4781 raw issue hits enumerated across overlapping ranked and component searches before cross-query deduplication, triage-surviving unique issues 300, processed 300
- Verdict totals: 170 IMMUNE, 31 SUSPECT, 95 MISSING-FEATURE, 4 N/A, 0 CONFIRMED
- Severity totals: 11 P0, 214 P1, 75 P2, 0 P3
- Checkpoint: processed ranked batch 14 and completed the stabilized-code semantic audit. The cross-repository component inventory covered playback core, demux and extractors, A/V sync, audio output, subtitles and timing, hardware decode, track selection, timestamps and discontinuities, gapless playback, speed and pitch, chapters, color and HDR, rotation, network, EOF, and queue and playlist behavior. Full-history demux search ran through all 0 androidx/media and 1 google/ExoPlayer results; extractor search through all 6 and 32 results; buffering search through all 41 and 145 results; exact EOS phrase search through all 27 and 137 results; SampleQueue search through all 38 and 118 results; gapless search through all 3 and 17 results; discontinuity search through all 8 and 46 results; sync search through all 11 and 54 results; network and playlist searches were fully paginated in creation-year partitions, with google/ExoPlayer 2014 through 2023 contributing 772 network and 1218 playlist hits and androidx/media 2023 through 2026 contributing 198 network and 384 playlist hits; archived infinite-playback search ran through all 26 results. Every partition stayed below GitHub's 1000-result search ceiling, every page beyond 100 was fetched, and no retained inventory query remains capped at 100 or 1000.
- Access: Authenticated `gh` access was unavailable because the configured token was invalid. Enumeration and issue-detail receipts used the public GitHub API and web paths through approved network access.

### [MEDIA3-1177] `WebvttParser` creates duplicate `CuesWithTiming` when handling cues sharing same start/end timestamps
- Link: https://github.com/androidx/media/issues/1177  State: closed-fixed
- Mechanism: Media3 represented simultaneous WebVTT cues by creating synthetic combined cue states. It accidentally treated two consecutive cues whose end and start timestamps were equal as overlapping, so it emitted a duplicate state at the shared boundary.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/subtitle/CueSelector.kt, activeAt
- Verdict: IMMUNE
- Why: `activeAt` uses a half-open interval. It admits a cue only when `cue.startMicros <= atMicros` and `atMicros < cue.endMicros`, so the earlier cue is already inactive at the exact instant the later cue starts. KitePlayer also returns the active cue list directly and does not manufacture synthetic overlap states.
- Severity if real: P2 quality/perf

### [MEDIA3-1721] New subtitle transcoding feature loads all subtitles when initialising
- Link: https://github.com/androidx/media/issues/1721  State: closed-fixed
- Mechanism: Media3 moved side-loaded subtitle preparation from a lazy single-sample period to a general progressive media period. The new period had to read during prepare to discover tracks, so every declared remote subtitle was fetched before selection.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, parseExternalSubtitles and parseExternalSubtitle
- Verdict: SUSPECT
- Why: Opening an item calls `parseExternalSubtitles` before building the playback session. That function maps every declared subtitle and `parseExternalSubtitle` reads and parses the whole file even when `selectImmediately` is false. Remote custom subtitle IO is not wired, but many large local sidecar files still make open latency and memory scale with every unselected track.
- Severity if real: P2 quality/perf

### [MEDIA3-2309] SSA and SubRip in-progress cue is not rendered when subtitle track is selected after cue's start time
- Link: https://github.com/androidx/media/issues/2309  State: closed-fixed
- Mechanism: Media3's SSA and SubRip parsing path filtered output using cue start time. When a track was enabled in the middle of a cue, the start was before the requested output time even though the end was still ahead, so the active cue was discarded.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSubtitleDecoder.kt, KiteCodecTextSubtitleDecoder.send and KiteCodecAssSubtitleDecoder.send; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/subtitle/CueSelector.kt, activeAt; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, buildSession, inPlaceContainerSubtitleChange, and timeAndPublishCues
- Verdict: IMMUNE
- Why: Both packet decoders create a cue directly from packet PTS and duration and have no selection-time output filter. The session caches packets and parsed cues for every container subtitle lane from open. A selection swaps the target queue, decoder, and cue cache in place, drops packets only before a small current-position lookbehind, and `activeAt` admits every cached cue whose interval spans the current time.
- Severity if real: P1 broken feature

### [MEDIA3-538] STATE_ENDED not sent for very short files, worked in version 1.0.2 and older
- Link: https://github.com/androidx/media/issues/538  State: closed-fixed
- Mechanism: Media3 converted a microsecond playback position back to frames with floor division. A 3,039-frame clip produced 3,038 from the rounded timestamp, so the audio pending-data check stayed true by one frame and the player never declared end of stream.
- KitePlayer code checked: kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/AudioTrackSink.kt, drain; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, handleEof
- Verdict: IMMUNE
- Why: Android output compares the extended playback head directly with `submittedFrames`, both in frame units. It never converts a rounded duration back to frames. The core waits for decoded audio in flight, the tempo tail, ring frames, and the sink drain, and every wait has a deadline, so a one-frame device disagreement cannot hold `Ended` forever.
- Severity if real: P1 broken feature

### [EXOPLAYER-6787] Add option to enable exact (but inefficient) seeking into variable bitrate MP3s
- Link: https://github.com/google/ExoPlayer/issues/6787  State: closed-fixed
- Mechanism: A variable bitrate MP3 without a precise seek table cannot map a requested time to a byte offset by assuming one constant bitrate. ExoPlayer fixed this by building a time-to-byte index while reading and, for an unseen target, scanning forward to extend that index.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/vendor/ffmpeg/libavformat/mp3dec.c, read_xing_toc, mp3_seek, and usetoc option; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, seekToKeyframe
- Verdict: IMMUNE
- Why: Pinned FFmpeg defaults `usetoc` to zero, does not enable fast seek, and refuses the coarse Xing path unless one of those policies is explicitly enabled. The default demuxer therefore falls through to generic frame scanning and its growing index for VBR files instead of making the constant-bitrate estimate that caused this issue.
- Severity if real: P1 broken feature

### [EXOPLAYER-6155] HLS/MP3: SeekTo does not seek to exact location
- Link: https://github.com/google/ExoPlayer/issues/6155  State: closed-fixed
- Mechanism: An HLS seek first lands at the start of a long MP3 segment. Because every MP3 frame is a sync sample, the queue must explicitly discard all frames before the requested time; otherwise playback starts at the segment boundary and may decode tens of seconds of unwanted audio.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/vendor/ffmpeg/libavformat/hls.c, find_timestamp_in_playlist, hls_read_seek, and hls_read_packet; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, seekToKeyframe
- Verdict: IMMUNE
- Why: Pinned FFmpeg's HLS seek chooses the segment containing the request, records the exact target timestamp, and makes `hls_read_packet` discard packets until their DTS reaches that target and a key packet is available. MP3 packets are key packets, so the first accepted frame is at the requested position rather than the segment boundary.
- Severity if real: P1 broken feature

### [EXOPLAYER-7462] Subsample offset and cue timestamp parsing for WebVTT fails when PTS wraps.
- Link: https://github.com/google/ExoPlayer/issues/7462  State: closed-fixed
- Mechanism: HLS maps WebVTT cue time to a 33-bit MPEG timestamp. ExoPlayer converted an absolute cue offset back to PTS without wrapping it first, so after about 26.5 hours the stateful adjuster interpreted subtitles as roughly ten days ahead of video.
- KitePlayer code checked: no corresponding HLS WebVTT timestamp-map subsystem
- Verdict: MISSING-FEATURE
- Why: KitePlayer parses standalone and embedded WebVTT cue bodies, but it has no HLS `X-TIMESTAMP-MAP` parser or 33-bit transport timestamp adjuster. HLS subtitle support will need modular PTS arithmetic before mapping cues onto the engine timeline.
- Severity if real: P1 broken feature

### [EXOPLAYER-8435] Support SSA/ASS styling
- Link: https://github.com/google/ExoPlayer/issues/8435  State: open
- Mechanism: SSA and ASS scripts can carry layout and meaning in vector drawings, timed karaoke, clipping, rotation, shear, and animated transforms. Reducing an event to plain cue text or a small style subset can hide signs, lyrics, and positioned dialogue rather than merely changing decoration.
- KitePlayer code checked: kiteplayer-subtitles/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/subtitle/AssParser.kt, parseOverrideText; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSubtitleDecoder.kt, KiteCodecSubtitleDecoderFactory.create; kiteplayer-libass/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/libass/LibassRenderer.kt, renderDocument
- Verdict: MISSING-FEATURE
- Why: The built-in playback path strips karaoke timing, drops vector drawings, reduces `move` to its start point, and ignores rotation, shear, clipping, and animated transforms. The optional libass module can render a whole document accurately, but no PlaybackCore or embedded-packet decoder path invokes it, so normal ASS track playback still uses the reduced Kotlin cue path.
- Severity if real: P2 quality/perf

### [EXOPLAYER-8260] Support VobSub subtitles
- Link: https://github.com/google/ExoPlayer/issues/8260  State: closed-fixed
- Mechanism: VobSub is a bitmap subtitle stream, not UTF-8 cue text. Supporting it requires the container extractor to expose the binary track and a decoder to turn its palette and run-length encoded images into timed bitmap cues.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSubtitleDecoder.kt, KiteCodecSubtitleDecoderFactory.create
- Verdict: MISSING-FEATURE
- Why: The factory accepts only SubRip, plain text, tx3g text, WebVTT, ASS, and SSA. It returns null for VobSub and the file documentation explicitly says bitmap formats still need real engines, so DVD-derived subtitle tracks are deselected even though the cue model can already carry bitmaps.
- Severity if real: P1 broken feature

### [EXOPLAYER-5896] Allow transition to ended if all tracks except subtitle track have ended
- Link: https://github.com/google/ExoPlayer/issues/5896  State: open
- Mechanism: ExoPlayer treated a side-loaded subtitle timeline as a renderer that must reach its own end. A subtitle file extending past the audio and video therefore delayed the completed event even though the visible media had finished.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, OpenSession.selectedQueues and handleEof
- Verdict: IMMUNE
- Why: `selectedQueues` deliberately contains only video and audio packet queues. `handleEof` tests those queues, the audio and video decoders, video frames, and audio drain state, but never the last cue end time. An external cue table or a future-dated decoded cue cannot hold the player before `Ended`.
- Severity if real: P1 broken feature

### [EXOPLAYER-2926] Allow seamless switching between different audio tracks (e.g. in MKV)
- Link: https://github.com/google/ExoPlayer/issues/2926  State: closed-fixed
- Mechanism: ExoPlayer originally reset the source when enabling another audio track, causing seconds of rebuffering. Its fix retained the existing sample queues and enabled or disabled audio, text, and metadata tracks in place because every sample in those streams is independently decodable.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, buildSession, inPlaceAudioChange, and handleTrackChanges; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, selectStreams
- Verdict: IMMUNE
- Why: `buildSession` selects every audio stream once and gives each a bounded compressed packet cache. `inPlaceAudioChange` verifies that the target cache still covers the presentation point, parks only audio decode and feed, creates the target decoder, installs its lane, and resumes those workers on the unchanged epoch. Demux and video continue, so the source reset and whole-player rebuffering mechanism is absent.
- Severity if real: P1 broken feature

### [EXOPLAYER-3327] Support pre-buffering of next concatenated item.
- Link: https://github.com/google/ExoPlayer/issues/3327  State: open
- Mechanism: Buffering only the current playlist item means a user skip, or the natural boundary between items, must wait for the next source and decoder to prepare. Keeping the start of the next item ready removes that avoidable buffering state.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, handleQueueAdvance and jumpQueue
- Verdict: MISSING-FEATURE
- Why: KitePlayer stores the queue as items plus an index, but owns only one OpenSession. It calls `runOpen` for the next item only after the current item reaches `Ended`, and an explicit next or previous command does the same, so no bytes, decoder, or first frame of the next item are prepared ahead of time.
- Severity if real: P2 quality/perf

### [EXOPLAYER-497] Add support for gapless audio playback
- Link: https://github.com/google/ExoPlayer/issues/497  State: closed-fixed
- Mechanism: True gapless playback needs encoder delay and padding to be trimmed and the next decoded item to continue through one uninterrupted audio output timeline. Starting another player or opening the next source only after the first ends leaves an audible scheduling and device gap.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, handleQueueAdvance and runOpen; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, drain
- Verdict: MISSING-FEATURE
- Why: Queue advance happens only after the old session drains and reaches `Ended`. `runOpen` tears that session down and builds a new audio path, so the next item cannot place samples continuously behind the previous item's encoder-trimmed tail. There is no gapless playlist contract or overlap handoff.
- Severity if real: P1 broken feature

### [EXOPLAYER-10980] WebVTT multi-line subtitles overlapping
- Link: https://github.com/google/ExoPlayer/issues/10980  State: wontfix
- Mechanism: ExoPlayer moved each simultaneous WebVTT cue up by one assumed line while parsing, before it knew the viewport or wrapped height. A cue that actually occupied several lines therefore overlapped the cue above it.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/subtitle/CueSelector.kt, activeAt; kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/AndroidSubtitleRasterizer.kt, rasterize; kiteplayer-output/src/jvmMain/kotlin/io/github/yuroyami/kiteplayer/output/DesktopSubtitleRasterizer.kt, rasterize; kiteplayer-output/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/output/AppleSubtitleRasterizer.kt, rasterize
- Verdict: IMMUNE
- Why: KitePlayer keeps all overlapping cues until render time. Each built-in rasterizer lays out a cue against the real viewport, measures the produced bitmap, and increments `stackedBottom` by that actual bitmap height plus a gap before placing the next implicit cue.
- Severity if real: P2 quality/perf

### [MEDIA3-2328] After running into a stream error with subtitle track, playback gets stuck every time the subtitle cue is empty
- Link: https://github.com/androidx/media/issues/2328  State: closed-fixed
- Mechanism: Media3 stored a subtitle stream error in its text renderer and failed to clear it when the renderer was disabled or given a new stream. A later empty cue checked the stale error, never became ready, and froze otherwise healthy playback.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, OpenSession, handleSubtitles, and teardownSession; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSubtitleDecoder.kt, flush
- Verdict: IMMUNE
- Why: Subtitle decoder state is owned by one OpenSession and is closed with that session. The decoders retain only pending cues, and `flush` clears those cues; there is no persistent stream-error field that can cross disable, track change, or queue item boundaries.
- Severity if real: P1 broken feature

### [MEDIA3-3269] Audiotrack stale playback head on ac3/eac3 audio, after seeking. playback stopped.
- Link: https://github.com/androidx/media/issues/3269  State: open
- Mechanism: On one television, a newly created encoded AC3 or EAC3 AudioTrack inherited a nonzero playback head from the released passthrough track. Writes then returned zero while the stale head never advanced, leaving playback stuck after a seek.
- KitePlayer code checked: kiteplayer-ffmpeg/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/PlatformDecoderSelection.android.kt, platformAudioDecoder; kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/AudioTrackSink.kt, open, stop, and drain
- Verdict: IMMUNE
- Why: KitePlayer does not use Android encoded-audio passthrough or MediaCodec audio decoding. FFmpeg decodes AC3 and EAC3 to float PCM, AudioTrackSink opens a PCM F32 track, and its own `submittedFrames` and extended playback head are reset on stop. The device-specific encoded-track state cannot enter this path.
- Severity if real: P1 broken feature

### [EXOPLAYER-8220] Negative timestamps in TS file causes playback to stay in buffering state forever
- Link: https://github.com/google/ExoPlayer/issues/8220  State: closed-fixed
- Mechanism: The first video DTS established timestamp zero, but the first audio PTS was earlier. Subtracting the video offset made audio negative; audio then moved the master position below zero and the video renderer never became ready against its zero output offset.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/vendor/ffmpeg/libavformat/demux.c, update_stream_timings; /Users/macbook/StudioProjects/#Kite/KiteCodec/native/kitecodec-c/src/helpers_format.c, ffkmp_fmt_start_time; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, TimestampMapper.mapTimestamp
- Verdict: IMMUNE
- Why: The pinned demux core computes the format start as the minimum non-text stream start time. KiteCodec exposes that value, and TimestampMapper subtracts it from both audio and video. When audio begins before video, audio establishes the earlier common origin instead of becoming negative relative to a video-only origin.
- Severity if real: P1 broken feature

### [EXOPLAYER-8678] How to deal with PTS jump
- Link: https://github.com/google/ExoPlayer/issues/8678  State: wontfix
- Mechanism: A transport stream reset PTS from 3569115535 to 1368074847, a backward epoch change rather than the natural 33-bit counter wrap. ExoPlayer passed newly negative adjusted timestamps to the decoder, so video froze or the hardware decoder failed.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/vendor/ffmpeg/libavformat/demux.c, update_wrap_reference and wrap_timestamp; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, TimestampMapper.mapTimestamp and timestampsMayJump; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/SyncLaw.kt, targetDelayUs
- Verdict: SUSPECT
- Why: Pinned FFmpeg detects and corrects a natural 33-bit counter wrap from its first timestamp reference, so that ordinary rollover is covered. This report is a non-wrap reset, and TimestampMapper still applies one fixed session origin with no per-PID epoch reset. Audio and video can therefore remain on incompatible epochs after the jump.
- Severity if real: P1 broken feature

### [EXOPLAYER-11000] Seeking into AC4 audio only content with FMP4 subfragments creates discontinuities
- Link: https://github.com/google/ExoPlayer/issues/11000  State: closed-fixed
- Mechanism: The device AC4 decoder legally emitted output timestamps different from its input sample timestamps. ExoPlayer still matched input and output timestamps to mark decode-only data after a seek, so it misidentified the landing and snapped to a fragment boundary.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, KiteCodecAudioDecoder.receive; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, offerBuffer and runAudioFeed
- Verdict: IMMUNE
- Why: KitePlayer never matches an audio output buffer back to an input packet timestamp. The decoder wrapper maps the decoded frame's own PTS, and precise seek discarding compares that output PTS plus its sample-count duration directly with the target before slicing the surviving buffer.
- Severity if real: P1 broken feature

### [EXOPLAYER-7122] "SubripDecoder" class throws an Exception when an .srt file omits HOURS from its timestamps (ex: 00:00,000)
- Link: https://github.com/google/ExoPlayer/issues/7122  State: closed-fixed
- Mechanism: ExoPlayer's regular expression made the hour group optional, but its conversion function unconditionally parsed that missing group and threw. Real SubRip files often use `MM:SS,mmm` without hours.
- KitePlayer code checked: kiteplayer-subtitles/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/subtitle/SubRipParser.kt, timestampToMicros
- Verdict: IMMUNE
- Why: KitePlayer's timestamp expression also makes hours optional, then explicitly replaces an empty hour group with zero before parsing. The parser documentation names missing hours as an accepted real-world deviation and malformed cues are skipped rather than thrown from playback.
- Severity if real: P1 broken feature

### [MEDIA3-1820] ExoPlayer ignores embedded 608 captions in Dolby Vision streams
- Link: https://github.com/androidx/media/issues/1820  State: closed-fixed
- Mechanism: Media3 classified the video as Dolby Vision rather than its H.264 or H.265 base codec. Its SEI detector checked only the base MIME types, so it never extracted CEA-608 data carried inside Dolby Vision NAL units.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSubtitleDecoder.kt, KiteCodecSubtitleDecoderFactory.create
- Verdict: MISSING-FEATURE
- Why: The subtitle factory has no CEA-608 or CEA-708 decoder at all, whether captions arrive as their own FFmpeg stream or are extracted from video SEI. Broadcast captions in Dolby Vision, AVC, or HEVC therefore have no route to the cue table.
- Severity if real: P1 broken feature

### [MEDIA3-3377] MatroskaExtractor: files with Tracks after Clusters are always reported unseekable, even with valid Cues: every seek restarts from t=0
- Link: https://github.com/androidx/media/issues/3377  State: closed-fixed
- Mechanism: Media3 scheduled both a Tracks detour and a Cues detour at the first Cluster. A short-circuit branch started the Cues detour before TrackEntry parsing completed, so the seek map was built with no primary seek track and permanently returned the start of the file.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, seekToKeyframe; ../KiteCodec/kitecodec-core/src/jvmAndAndroidMain/kotlin/io/github/yuroyami/kitecodec/Playback.jvm.kt, PacketReader.seek; ../KiteCodec/kitecodec-core/src/nativeMain/kotlin/io/github/yuroyami/kitecodec/Playback.native.kt, PacketReader.seek
- Verdict: IMMUNE
- Why: KitePlayer has no Matroska element-detour state machine or primary-track seek-map construction. Both KiteCodec implementations ask libavformat to seek the already-open container through one bounded `avformat_seek_file` call, so the Media3 ordering bug and its unset primary track do not exist in this path.
- Severity if real: P1 broken feature

### [MEDIA3-3117] Inaccurate MP3 VBR seeking with Media3 1.9.x
- Link: https://github.com/androidx/media/issues/3117  State: closed-fixed
- Mechanism: The file's Xing header claimed an audio-data length larger than the actual stream. The coarse Xing table expresses time as fractions of that length, so trusting the bad endpoint stretched every time-to-byte lookup; Media3 reduced the error by clamping the claimed endpoint to the real input length.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/vendor/ffmpeg/libavformat/mp3dec.c, read_xing_toc, mp3_seek, and usetoc option; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, seekToKeyframe
- Verdict: IMMUNE
- Why: Pinned FFmpeg defaults `usetoc` to zero and has no fast-seek policy enabled, so it neither fills nor selects the malformed Xing table for this path. Generic frame scanning and the demuxer's actual-byte index position the seek against media that exists, so the header's overstated data endpoint cannot stretch the selected time-to-byte map.
- Severity if real: P1 broken feature

### [MEDIA3-2965] Early seek after media load may cause crash in DecoderVideoRenderer
- Link: https://github.com/androidx/media/issues/2965  State: closed-fixed
- Mechanism: An early position reset cleared Media3's queue of timestamped output formats before any output buffer had selected one. Because the input format itself did not change, no later callback repopulated the queue, and output processing dereferenced a null format.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, KiteCodecVideoDecoder.receive and flush; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, flushDecoders and runVideoDecode
- Verdict: IMMUNE
- Why: There is no separate timestamped format queue. `receive` takes geometry, pixel format, keyframe state, color, PTS, and duration from the decoded frame currently being wrapped. A seek parks the worker and flushes the decoder, after which the next frame again supplies its own format, so an unchanged input format cannot leave a nullable output-format slot empty.
- Severity if real: P0 crash/dataloss

### [MEDIA3-2327] Seeking to non-buffered position in 24bit 96kHz 6ch FLAC causes infinite buffering and playback stuck exception
- Link: https://github.com/androidx/media/issues/2327  State: closed-fixed
- Mechanism: Media3 treated the mere presence of a FLAC SEEKTABLE block as proof that table seeking was available. A legal zero-length block produced no points, but it still suppressed the binary-search fallback and every non-buffered seek stalled.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, seekable and seekToKeyframe; ../KiteCodec/kitecodec-core/src/jvmAndAndroidMain/kotlin/io/github/yuroyami/kitecodec/Playback.jvm.kt, PacketReader.seek
- Verdict: IMMUNE
- Why: KitePlayer does not branch on FLAC metadata-block presence. Seekability comes from the real FFmpeg input context, and a seek goes through libavformat's demuxer implementation, so an empty table cannot select a KitePlayer table-only seeker or disable a separate fallback branch.
- Severity if real: P1 broken feature

### [MEDIA3-320] Seeking on-growing TS file
- Link: https://github.com/androidx/media/issues/320  State: wontfix
- Mechanism: ExoPlayer built its transport-stream duration and byte seek map from the input length returned at open. Appending bytes later did not replace that immutable map, so seeks past the original end snapped back and an unknown length disabled the duration-based seek UI.
- KitePlayer code checked: kiteplayer-network/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/network/KtorMediaIo.kt, open and size; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, duration and seekable
- Verdict: MISSING-FEATURE
- Why: KtorMediaIo probes one size and Range capability at open, while KiteCodecSource snapshots duration and seekability from the opened format context. There is no growing-file contract, duration refresh, or live replacement of a seek map, so a recording that grows behind one open session cannot expose its new tail for reliable seeking.
- Severity if real: P2 quality/perf

### [MEDIA3-2818] Audio duration shrinks after seeking to end and replaying (MP3)
- Link: https://github.com/androidx/media/issues/2818  State: closed-fixed
- Mechanism: Media3 failed to clear `endPositionOfLastSampleRead` on seek. If synchronization near the end found no valid MP3 frame, duration repair reused that stale pre-seek byte position and shortened the published CBR duration to an arbitrary earlier point.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, duration and seekToKeyframe; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runSeek and publishSnapshot
- Verdict: IMMUNE
- Why: `KiteCodecSource.duration` is a value captured once from the opened FFmpeg source. Seeking moves the packet reader but has no path that writes duration, and snapshot publication keeps reading that same source value, so failed synchronization near EOF cannot replace it with a stale last-packet byte position.
- Severity if real: P1 broken feature

### [MEDIA3-2848] Some MP3 no more have duration and can't be seeked
- Link: https://github.com/androidx/media/issues/2848  State: closed-fixed
- Mechanism: Enabling index seeking made Media3 wait until enough of a metadata-free MP3 had been scanned to finish its time-to-byte index. That choice hid duration and seeking even for ordinary CBR files where an immediate bitrate estimate was preferable; the fix restored CBR estimation first and kept indexing as a fallback.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, duration, seekable, and seekToKeyframe; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerConfig.kt, PlayerConfig
- Verdict: MISSING-FEATURE
- Why: KitePlayer exposes neither an MP3 index-building policy nor the choice between immediate approximate CBR seeking and delayed exact indexing. It accepts the one strategy FFmpeg selected at open, so an application playing arbitrary podcasts cannot request the latency-versus-accuracy trade described by this issue.
- Severity if real: P2 quality/perf

### [MEDIA3-1499] [media3-1.1.0] Creation of multiple tracks in audioflinger while seeking
- Link: https://github.com/androidx/media/issues/1499  State: wontfix
- Mechanism: Media3 1.1 released and recreated its Android AudioTrack on every position reset. Rapid repeated seeks could leave platform release work lagging behind creation until AudioFlinger reached its active-track cap and refused another output.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runSeek; kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/AudioTrackSink.kt, stop and close
- Verdict: IMMUNE
- Why: A seek calls `AudioTrackSink.stop`, which joins the writer, flushes the existing driver, and resets counters without releasing or recreating it. Only session close releases the one driver, so repeated seeks cannot accumulate one AudioFlinger track per reset.
- Severity if real: P0 crash/dataloss

### [MEDIA3-2177] Image freeze after 2 seeks
- Link: https://github.com/androidx/media/issues/2177  State: wontfix
- Mechanism: Android tunneled playback moved decoding, A/V synchronization, and buffer release into a device-specific audio-video tunnel. On the affected set-top boxes, two close seeks left that vendor tunnel advancing audio while video froze and then entered permanent buffering.
- KitePlayer code checked: kiteplayer-ffmpeg/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/PlatformDecoderSelection.android.kt, platformAudioDecoder; kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/AudioTrackSink.kt, open; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runVideoSchedule
- Verdict: IMMUNE
- Why: KitePlayer has no tunneled playback mode. Audio is decoded to PCM and written through an independent AudioTrack, while the engine's own scheduler releases video frames against its master clock. The vendor tunnel that owned and lost synchronization in this report is never created.
- Severity if real: P1 broken feature

### [EXOPLAYER-6704] Support seeking into downloaded fMP4 file without top level sidx box
- Link: https://github.com/google/ExoPlayer/issues/6704  State: closed-fixed
- Mechanism: ExoPlayer originally built fragmented-MP4 seek maps only from a top-level `sidx`. Downloaded files with `moof` fragments and a valid trailing `mfra` and `tfra` index were therefore marked unseekable until the extractor learned to read that random-access table from the known end of the input.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, seekable and seekToKeyframe; ../KiteCodec/kitecodec-core/src/jvmAndAndroidMain/kotlin/io/github/yuroyami/kitecodec/Playback.jvm.kt, PacketReader.seek
- Verdict: IMMUNE
- Why: KitePlayer has no rule requiring `sidx` and does not build an MP4 seek map itself. The FFmpeg mov demuxer owns all MP4 index parsing and the player submits a generic bounded seek to that demuxer, so absence of Media3's one accepted box cannot by itself disable the path.
- Severity if real: P1 broken feature

### [EXOPLAYER-5097] Improve MPEG-TS seeking support
- Link: https://github.com/google/ExoPlayer/issues/5097  State: open
- Mechanism: ExoPlayer searched only a fixed number of transport packets for first, last, and target PCR values. Streams whose programs placed PCR farther apart than that window appeared to have no duration or seek point; increasing the packet window made the supplied files seekable but increased probing cost.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, duration and seekToKeyframe; ../KiteCodec/kitecodec-core/src/jvmAndAndroidMain/kotlin/io/github/yuroyami/kitecodec/Playback.jvm.kt, PacketReader.seek
- Verdict: IMMUNE
- Why: KitePlayer contains no fixed `DURATION_READ_PACKETS` or `TIMESTAMP_SEARCH_PACKETS` window and no TS duration reader. Libavformat performs the format-specific probing and seeking behind the generic packet reader, so the exact ExoPlayer limit cannot suppress a KitePlayer seek map.
- Severity if real: P1 broken feature

### [EXOPLAYER-9408] Apply best seeking strategy for MP3
- Link: https://github.com/google/ExoPlayer/issues/9408  State: open
- Mechanism: An application cannot choose between constant-bitrate estimation and a growing exact index without first knowing whether an arbitrary MP3 is CBR, VBR, or carries a useful seek table. Determining that reliably may itself require scanning the file, so the extractor is the only layer with enough evidence to select or report the trade.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, duration and seekToKeyframe; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerConfig.kt, PlayerConfig
- Verdict: MISSING-FEATURE
- Why: KitePlayer exposes neither the MP3 seek strategy FFmpeg selected nor a policy to request fast approximate versus indexed exact seeking. The generic source answers only duration, seekable, and one keyframe seek operation, which is insufficient for a podcast app to make this choice or explain its current accuracy.
- Severity if real: P2 quality/perf

### [EXOPLAYER-10325] Inclusion of Info tag with TOC in a CBR MP3 file makes seeking less precise
- Link: https://github.com/google/ExoPlayer/issues/10325  State: wontfix
- Mechanism: A CBR MP3 carried an optional Info-header table of contents. Its fractional time-to-byte entries cannot exactly represent the linear CBR map, but ExoPlayer preferred that table over exact bitrate arithmetic, so a seek near the end left audio playing after the reported position reached duration.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/vendor/ffmpeg/libavformat/mp3dec.c, read_xing_toc, mp3_seek, and usetoc option; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, seekToKeyframe
- Verdict: IMMUNE
- Why: Pinned FFmpeg defaults `usetoc` to zero and does not enable fast seek. Its CBR branch never selects the Info table unless `usetoc` was explicitly requested, and the fast-seek TOC branch is restricted to non-CBR input. Default CBR seeking therefore keeps linear or generic frame positioning instead of replacing it with the coarse table.
- Severity if real: P1 broken feature

### [EXOPLAYER-11163] Matroska(mka) files are not seekable if they do not contain Cues information
- Link: https://github.com/google/ExoPlayer/issues/11163  State: wontfix
- Mechanism: Matroska Cues provide a direct timestamp-to-cluster index, but they are recommended rather than mandatory. ExoPlayer refused seeking when they were absent instead of scanning or binary-searching clusters, even though local seekable input could support the slower fallback.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/vendor/ffmpeg/libavformat/matroskadec.c, matroska_read_seek and matroska_resync; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, seekable and seekToKeyframe
- Verdict: IMMUNE
- Why: Pinned FFmpeg does not make Cues a hard seekability prerequisite. When `matroska_read_seek` has no usable index it returns control to libavformat's generic seek path, which scans and grows an index on seekable input; the Matroska demuxer can also resynchronize at later elements. Cue-less local files therefore retain the slower fallback missing from ExoPlayer.
- Severity if real: P1 broken feature

### [EXOPLAYER-1808] Support seeking for FLAC streams without SEEKTABLE.
- Link: https://github.com/google/ExoPlayer/issues/1808  State: closed-fixed
- Mechanism: Early ExoPlayer supported FLAC seeking only when the file supplied a SEEKTABLE. The eventual fix added a search fallback that probes frames to locate the requested sample when that optional metadata block is absent.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, seekable and seekToKeyframe; ../KiteCodec/kitecodec-core/src/nativeMain/kotlin/io/github/yuroyami/kitecodec/Playback.native.kt, PacketReader.seek
- Verdict: IMMUNE
- Why: There is no KitePlayer FLAC branch that requires a SEEKTABLE. The seek is handed to FFmpeg's FLAC demuxer through `avformat_seek_file`, so an absent metadata block does not disable a separate KitePlayer search path or force the core to zero.
- Severity if real: P1 broken feature

### [EXOPLAYER-4548] Support seeking in AAC
- Link: https://github.com/google/ExoPlayer/issues/4548  State: closed-fixed
- Mechanism: Headerless ADTS AAC has no container seek index. ExoPlayer added approximate seeking by measuring frame bitrate and mapping target time to a byte offset under a constant-bitrate assumption, while recommending MP4 for reliable indexed seeking.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, seekable and seekToKeyframe; ../KiteCodec/kitecodec-core/src/jvmAndAndroidMain/kotlin/io/github/yuroyami/kitecodec/Playback.jvm.kt, PacketReader.seek
- Verdict: IMMUNE
- Why: KitePlayer does not maintain a container allowlist that disables raw AAC seeking. FFmpeg owns ADTS duration estimation and byte seeking, and the same generic packet-reader seek is available whenever the real input context reports seekable.
- Severity if real: P2 quality/perf

### [EXOPLAYER-4476] Support reading stream duration and seeking for MPEG-PS streams.
- Link: https://github.com/google/ExoPlayer/issues/4476  State: closed-fixed
- Mechanism: MPEG program streams do not carry a mandatory top-level duration or random-access table. ExoPlayer had to inspect pack timestamps near the input boundaries, then use timestamp-guided byte search to expose duration and seeking.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, duration and seekToKeyframe; ../KiteCodec/kitecodec-core/src/nativeMain/kotlin/io/github/yuroyami/kitecodec/Playback.native.kt, PacketReader.seek
- Verdict: IMMUNE
- Why: KitePlayer has no PS-specific unsupported branch. Duration and seekability come from FFmpeg's format context and seeks return to the same demuxer through a bounded generic call, so the old ExoPlayer extractor's missing SCR probe is not part of this implementation.
- Severity if real: P1 broken feature

### [EXOPLAYER-1522] Some AC3 tracks in passthrough mode have issues with seeking (ShieldTV 6.0)
- Link: https://github.com/google/ExoPlayer/issues/1522  State: wontfix
- Mechanism: After a seek, a new encoded passthrough AudioTrack on one Shield firmware briefly continued the old track's playback-head count before jumping to zero. ExoPlayer interpreted that small backward reset as a 32-bit wrap and advanced the clock by billions of frames.
- KitePlayer code checked: kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/AudioTrackSink.kt, stop, extendedHead, and resetTimestampState; kiteplayer-ffmpeg/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/PlatformDecoderSelection.android.kt, platformAudioDecoder
- Verdict: IMMUNE
- Why: KitePlayer decodes AC3 to PCM instead of opening an encoded passthrough AudioTrack, and a seek keeps the same PCM driver. `stop` resets both raw-head and wrap state, while `extendedHead` counts a wrap only for a backward delta greater than half the 32-bit range, so a short reset to zero cannot add a false wrap.
- Severity if real: P1 broken feature

### [EXOPLAYER-8362] Video drop frames when Spurious audio timestamp (frame position mismatch) occurs at the beginning of the play and after the seek
- Link: https://github.com/google/ExoPlayer/issues/8362  State: wontfix
- Mechanism: A television returned an AudioTimestamp at frame zero while its playback-head counter already showed about five seconds played. Trusting the stale timestamp as the audio clock made video appear five seconds late and caused a burst of drops until the device timestamp recovered.
- KitePlayer code checked: kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/AudioTrackSink.kt, acceptTimestamp and deadlineForBlock; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, anchorLocked
- Verdict: IMMUNE
- Why: The sink rejects a timestamp behind its last accepted frame, behind its last system time, or outside zero through submitted frames, and falls back to the extended playback head for callback deadlines. The audio clock is anchored from ring segments dated with those deadlines, not directly from the raw AudioTimestamp value, so one stale zero cannot move the master clock backward five seconds.
- Severity if real: P1 broken feature

### [EXOPLAYER-8090] Infinite seeking for duration in MPEG-TS with fixed Content-Length
- Link: https://github.com/google/ExoPlayer/issues/8090  State: closed-fixed
- Mechanism: A live server falsely advertised a fixed 900 MB length and Range support, then answered a nonzero range with a full 200 response. One ExoPlayer HTTP path treated that body as the requested offset and another tried to skip almost 900 MB by reading, so TS duration probing never finished.
- KitePlayer code checked: kiteplayer-network/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/network/KtorMediaIo.kt, openAt and open; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/CachingMediaIo.kt, seek
- Verdict: IMMUNE
- Why: KtorMediaIo marks a source seekable only after a 206 probe. Every later nonzero reopen accepts only 206; a lying server's 200 becomes a typed error rather than a read-and-skip loop. The cache forwards far seeks directly and contains no path that consumes bytes until an advertised offset.
- Severity if real: P1 broken feature

### [EXOPLAYER-132] Seeking causes entire video to be downloaded
- Link: https://github.com/google/ExoPlayer/issues/132  State: wontfix
- Mechanism: The old player delegated MP4 extraction and fetching to Android MediaExtractor. When that platform component could not issue an effective range seek, it consumed the remote file from the beginning up to the target, especially during repeated scrubbing.
- KitePlayer code checked: kiteplayer-network/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/network/KtorMediaIo.kt, seek and openAt; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/CachingMediaIo.kt, seek
- Verdict: IMMUNE
- Why: KitePlayer owns the HTTP reader. A far seek reopens at the exact byte with a Range request, a near backward seek stays inside the RAM window, and a server that ignores a nonzero range is rejected. No Android MediaExtractor data-fetch path can silently download all preceding bytes.
- Severity if real: P2 quality/perf

### [EXOPLAYER-564] When paused, the current video frame sometimes takes a long period of time to update after a seek
- Link: https://github.com/google/ExoPlayer/issues/564  State: closed-fixed
- Mechanism: ExoPlayer declared READY before the first post-seek frame was decoded, then ran its render loop at a low paused cadence. Frame-accurate seek needed many decode iterations, so the requested still image could take seconds to appear until playback raised the loop frequency.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runSeek, awaitLanding, and presentFirstFrame
- Verdict: IMMUNE
- Why: A paused seek does not wait on a low-frequency idle renderer. Workers decode forward under the new epoch until `awaitLanding` records a frame, then `presentFirstFrame` puts the scheduler in a dedicated one-frame mode and polls it on the normal worker interval before the seek completes.
- Severity if real: P2 quality/perf

### [EXOPLAYER-3918] ENDED event is not fired by ExoPlayer if seek after pause media
- Link: https://github.com/google/ExoPlayer/issues/3918  State: closed-fixed
- Mechanism: A text renderer remained not-ended after a seek in a multi-track HLS item. ExoPlayer required every renderer to end before publishing ENDED, so the completed audio and video stayed blocked behind caption state.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, OpenSession.selectedQueues and handleEof
- Verdict: IMMUNE
- Why: `selectedQueues` contains only video and audio, and `handleEof` never checks subtitle packet, decoder, or last-cue state. A caption stream that extends past or fails to mark its own end cannot prevent the audio-video session from completing.
- Severity if real: P1 broken feature

### [EXOPLAYER-596] Failed to receive STATE_END after calling seekTo() to seek to end of video
- Link: https://github.com/google/ExoPlayer/issues/596  State: closed-fixed
- Mechanism: Seeking exactly to duration made the extractor signal EOF immediately, but a final decoded audio buffer remained in the pipeline without being played. ExoPlayer kept waiting for the audio renderer's end signal and often never entered STATE_ENDED.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runSeek, atEndOfStream, handleEof, and runAudioFeed
- Verdict: IMMUNE
- Why: A no-frame seek is accepted specifically when all queues and decoders reached end of stream. EOF then waits for decoded audio in flight, flushes the DSP tail, drains the ring and device with bounded deadlines, and only then publishes Ended. The last buffer cannot remain invisible between extractor EOF and renderer completion.
- Severity if real: P1 broken feature

### [EXOPLAYER-2568] Display last frame when seeking to end of stream
- Link: https://github.com/google/ExoPlayer/issues/2568  State: closed-fixed
- Mechanism: A seek to the exact duration finds no sample at or after its target. ExoPlayer entered ENDED without submitting another video buffer, so the surface retained whichever earlier frame was visible instead of the media's actual last frame.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runSeek, presentFirstFrame, and handleEof
- Verdict: SUSPECT
- Why: KitePlayer explicitly treats an EOF landing as legitimate with `landed == null`, calls `presentFirstFrame` even though no frame exists, and documents that the renderer keeps its own prior picture. Seeking from the middle directly to exact duration can therefore leave that middle frame on screen rather than decoding and presenting the last frame.
- Severity if real: P2 quality/perf

### [MEDIA3-2289] Align subtitle output with video frame timestamps
- Link: https://github.com/androidx/media/issues/2289  State: wontfix
- Mechanism: Media3 evaluates text cue edges on its roughly 10 ms general work loop, then crosses to the application thread for drawing. It does not schedule a cue against the video frame PTS, so a cue authored for a frame boundary may appear after that frame was presented and disappear during a later frame.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, timeAndPublishCues, publishOverlay, and runVideoSchedule
- Verdict: SUSPECT
- Why: `timeAndPublishCues` samples the current master position independently of the video frame being presented, and a changed cue starts a separate raster job before the overlay reaches the renderer. No path binds that overlay to a queued frame PTS. Low-frame-rate content with cue edges exactly on frame timestamps can therefore show the same one-frame timing disagreement.
- Severity if real: P2 quality/perf

### [MEDIA3-1976] Allow applications to modify subtitle offsets during playback
- Link: https://github.com/androidx/media/issues/1976  State: open
- Mechanism: A sidecar subtitle can carry a constant timing error, so viewers need to change its offset while playback continues. Applying the shift only while loading or parsing cannot correct an already active cue without rebuilding or reloading subtitle state.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/KitePlayer.kt, setSubtitleDelay; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, timeAndPublishCues and pruneCueHistory
- Verdict: IMMUNE
- Why: `setSubtitleDelay` is a public runtime control. The timing pass subtracts the current delay before every `activeAt` lookup and applies the same shifted position to its next-edge scheduling and pruning, so existing container and external cue tables change immediately without a reopen.
- Severity if real: P2 quality/perf

### [MEDIA3-2667] All image subtitle tracks parsed before selection cause stutter
- Link: https://github.com/androidx/media/issues/2667  State: open
- Mechanism: Media3's new subtitle pipeline decoded all 28 unselected PGS tracks into Bitmap objects during extraction, before track selection. That consumed enough CPU and memory on television devices to alternate several seconds of playback with several seconds of stalls.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, buildSession and handleSubtitles; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, selectStreams; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSubtitleDecoder.kt, KiteCodecSubtitleDecoderFactory.create
- Verdict: IMMUNE
- Why: The session routes compressed packets into a bounded queue for every subtitle stream, but creates and runs a decoder only for the selected subtitle lane. The factory also rejects PGS entirely, so unselected PGS packets remain compressed and cannot become 28 tracks of eager Bitmap allocations. PGS display remains a separate missing feature, but the reported preselection decode storm cannot occur.
- Severity if real: P1 broken feature

### [MEDIA3-3045] Some nonstandard SubRip timestamps are not recognized
- Link: https://github.com/androidx/media/issues/3045  State: wontfix
- Mechanism: The supplied SRT used short hours, a full stop separator, and two fractional digits, `0:00:00.00`. Media3 intentionally required the common `00:00:00,000` grammar and therefore parsed no cue.
- KitePlayer code checked: kiteplayer-subtitles/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/subtitle/SubRipParser.kt, parseTiming and timestampToMicros
- Verdict: IMMUNE
- Why: KitePlayer's timestamp grammar accepts one to three hour digits, either comma or full stop as the separator, and one to three fractional digits. It pads the fraction to milliseconds, so the exact reported timing line parses as zero through twenty seconds.
- Severity if real: P1 broken feature

### [MEDIA3-1722] Remote subtitle load error fails the whole player
- Link: https://github.com/androidx/media/issues/1722  State: closed-fixed
- Mechanism: A failed load or parse in one auxiliary subtitle source propagated as a player error, even though audio and video were healthy and another subtitle could still be selected. The fix suppressed subtitle and metadata source errors while reporting them through diagnostics.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, parseExternalSubtitles, parseExternalSubtitle, and adoptExternalSubtitles
- Verdict: IMMUNE
- Why: Every declared external subtitle is read and parsed before session construction. An unreadable path, unwired custom IO, parser failure, or empty cue result becomes `ExternalSubtitleParse.Failed`, emits a typed track-deselection warning, and is omitted from the track list. None of those outcomes aborts the media open.
- Severity if real: P1 broken feature

### [MEDIA3-466] Allow only forced subtitle tracks with TrackSelector
- Link: https://github.com/androidx/media/issues/466  State: wontfix
- Mechanism: The requested policy hides ordinary default subtitles but still selects a forced track that matches the current audio language. A single ignore-default flag was insufficient unless forced and default dispositions were evaluated separately with the selected audio track.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerConfig.kt, SubtitleConfig; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, pickSubtitle
- Verdict: MISSING-FEATURE
- Why: Turning `autoSelect` off suppresses the ordinary track, but `pickSubtitle` selects a forced track only when a nonempty preferred-language list exists and the audio is not preferred. There is no configuration for the common policy of no normal subtitles plus a forced track matching the understood audio language.
- Severity if real: P2 quality/perf

### [EXOPLAYER-10247] Detect a suppressed subtitle load error through the player API
- Link: https://github.com/google/ExoPlayer/issues/10247  State: wontfix
- Mechanism: ExoPlayer intentionally treated side-loaded subtitle failures as end of stream so video could continue, but the MediaItem API exposed no per-subtitle switch or direct failure result. Applications still needed an analytics load-error callback to distinguish a missing caption from a valid empty track.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, parseExternalSubtitles and parseExternalSubtitle; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlaybackError.kt, PlaybackWarning.TrackDeselected
- Verdict: IMMUNE
- Why: Every read, custom-IO, parser, and empty-cue failure carries a concrete reason into `PlaybackWarning.TrackDeselected` with the synthetic track id. The warning is emitted while the main open continues, so an application can both preserve playback and observe exactly which declared subtitle failed.
- Severity if real: P2 quality/perf

### [EXOPLAYER-5869] Add SAMI subtitle decoder
- Link: https://github.com/google/ExoPlayer/issues/5869  State: open
- Mechanism: SAMI `.smi` files are structured subtitle documents and need their own decoder; treating `smi` as a language code or sending the document to SubRip does not produce timed cues.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSubtitleDecoder.kt, KiteCodecSubtitleDecoderFactory.create; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecMediaBackend.kt, subtitleFileParser
- Verdict: MISSING-FEATURE
- Why: The embedded factory recognizes SubRip, tx3g, WebVTT, ASS, and SSA. The external file parser routes only self-announced ASS, hinted WebVTT, and otherwise SubRip. No SAMI tokenizer, timing model, or MIME route exists, so `.smi` files parse to no usable cues.
- Severity if real: P1 broken feature

### [EXOPLAYER-1649] Add subtitle files during playing
- Link: https://github.com/google/ExoPlayer/issues/1649  State: open
- Mechanism: ExoPlayer required subtitle sources to be supplied when its text renderer was constructed. Adding a user-selected file later meant preparing the player again and visibly rebuffering, rather than attaching and selecting the new cue table in place.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/KitePlayer.kt, addExternalSubtitle; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, addExternalSubtitle, awaitSubtitleAdd, and inPlaceContainerSubtitleChange
- Verdict: IMMUNE
- Why: The file is parsed into a cue table and registered while the current session remains live. Selection then flows through `inPlaceContainerSubtitleChange` even when a container subtitle is already active: it retires only the old subtitle decoder and overlay, installs the external cue table, and publishes the new selection without reopening media, seeking, or stopping audio and video.
- Severity if real: P2 quality/perf

### [EXOPLAYER-3938] Support LRC lyric subtitle format
- Link: https://github.com/google/ExoPlayer/issues/3938  State: open
- Mechanism: LRC stores lyric lines beside bracketed timestamps and may carry multiple timestamps for one line. It is not SubRip, so displaying synchronized lyrics requires a format parser and MIME or extension route rather than renaming the file.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecMediaBackend.kt, subtitleFileParser; kiteplayer-subtitles/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/subtitle/SubRipParser.kt, parse
- Verdict: MISSING-FEATURE
- Why: External text that is neither self-announced ASS nor hinted WebVTT is always passed to SubRipParser, whose only timing line uses an arrow between start and end. There is no LRC bracketed-timestamp parser, so ordinary `.lrc` lyrics produce no cue table.
- Severity if real: P2 quality/perf

### [EXOPLAYER-9813] Add horizontal padding to subtitle background
- Link: https://github.com/google/ExoPlayer/issues/9813  State: open
- Mechanism: Drawing a background exactly to each glyph edge makes captions harder to scan and does not match Android's accessibility preview. Correct padding also has to respect adjacent text spans with different background colors rather than inserting gaps at every span boundary.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/subtitle/SubtitleCue.kt, CueStyle; kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/AndroidSubtitleRasterizer.kt, rasterize; kiteplayer-output/src/jvmMain/kotlin/io/github/yuroyami/kiteplayer/output/DesktopSubtitleRasterizer.kt, rasterize
- Verdict: MISSING-FEATURE
- Why: CueStyle has no background-color or background-padding field, and the built-in rasterizers draw transparent bitmaps containing glyph fill and outline only. Applications therefore cannot request even an unpadded text background, much less horizontal padding that follows mixed background spans.
- Severity if real: P2 quality/perf

### [EXOPLAYER-10137] WebView subtitle outline renders incorrectly
- Link: https://github.com/google/ExoPlayer/issues/10137  State: open
- Mechanism: ExoPlayer's WebView subtitle backend translated Android's outline edge style into CSS whose shape and thickness did not match the Canvas renderer. Selecting the WebView backend fixed bidirectional text but visibly degraded the outline.
- KitePlayer code checked: kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/AndroidSubtitleRasterizer.kt, rasterize; kiteplayer-output/src/jvmMain/kotlin/io/github/yuroyami/kiteplayer/output/DesktopSubtitleRasterizer.kt, rasterize; kiteplayer-output/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/output/AppleSubtitleRasterizer.kt, rasterize
- Verdict: IMMUNE
- Why: KitePlayer has no WebView or CSS subtitle backend. Android draws a stroked StaticLayout before the fill, desktop strokes each glyph outline, and Apple uses CoreText fill-stroke mode, so the affected CSS outline translation is absent on every built-in platform path.
- Severity if real: P2 quality/perf

### [EXOPLAYER-10401] Forced subtitle is hidden when audio language is undefined
- Link: https://github.com/google/ExoPlayer/issues/10401  State: open
- Mechanism: ExoPlayer required the forced text language to match the selected audio language. When the audio language was undefined and the only forced text track declared English, the selector scored no match, hid the track from its control, and selected nothing.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerConfig.kt, SubtitleConfig; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, pickSubtitle
- Verdict: SUSPECT
- Why: With the default empty preferred-language list, `pickSubtitle` never enters its forced-track branch and the ordinary fallback explicitly filters all forced tracks out. An MKV with undefined audio language and one forced English subtitle therefore has no automatic selection unless the application already knew to configure English as preferred.
- Severity if real: P1 broken feature

### [EXOPLAYER-10148] WebView drop shadow is clipped at glyph descenders
- Link: https://github.com/google/ExoPlayer/issues/10148  State: open
- Mechanism: ExoPlayer's WebView output allocated too little vertical paint extent for a configured drop shadow. The shadow below descenders such as `g`, `p`, and `y` was clipped at the cue bitmap boundary.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/subtitle/SubtitleCue.kt, CueStyle; kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/AndroidSubtitleRasterizer.kt, rasterize; kiteplayer-output/src/appleMain/kotlin/io/github/yuroyami/kiteplayer/output/AppleSubtitleRasterizer.kt, rasterize
- Verdict: MISSING-FEATURE
- Why: CueStyle carries shadow color and offset parsed from ASS, but its contract states that every built-in rasterizer ignores both. The exact clipping cannot occur because no shadow is drawn, but the viewer-visible capability at issue is absent on Android, Apple, and desktop.
- Severity if real: P2 quality/perf

### [EXOPLAYER-8504] Gzip-compressed subtitle file is parsed as text
- Link: https://github.com/google/ExoPlayer/issues/8504  State: wontfix
- Mechanism: OpenSubtitles returned a file that was already gzip-compressed but had no HTTP `Content-Encoding: gzip`, so transparent HTTP decompression did not apply. The SubRip decoder received compressed bytes as text; the application had to identify the file and wrap its data source in a gzip decoder.
- KitePlayer code checked: kiteplayer-core/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/internal/ExternalText.android.kt, readExternalTextOrNull; kiteplayer-core/src/nativeMain/kotlin/io/github/yuroyami/kiteplayer/internal/ExternalText.native.kt, readExternalTextOrNull; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, parseExternalSubtitle
- Verdict: MISSING-FEATURE
- Why: Local external subtitles are always decoded directly as text, with no gzip magic-byte or extension check and no decompression layer. A `.srt.gz` path therefore becomes replacement characters or no cues. Remote external subtitle IO is also not wired, so an application cannot inject a streaming gzip reader through SubtitleSource today.
- Severity if real: P2 quality/perf

### [EXOPLAYER-8017] TextRenderer crashes when the first subtitle buffer is end of stream
- Link: https://github.com/google/ExoPlayer/issues/8017  State: closed-fixed
- Mechanism: A CEA-608 stream delivered an end-of-stream buffer on the renderer's first pass. TextRenderer then called its next-event calculation before any current subtitle object existed and asserted that null object, causing an NPE.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, handleSubtitles and handleEof; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSubtitleDecoder.kt, receive
- Verdict: IMMUNE
- Why: There is no nullable current-subtitle event object. The core polls packets, treats an empty decoder result as no cues, and derives the next edge only from the concrete cue list. EOF state is tracked on the selected audio and video queues, so a first-pass subtitle EOF cannot dereference missing text state.
- Severity if real: P0 crash/dataloss

### [EXOPLAYER-2957] Malformed DVB bitmap data crashes the subtitle parser
- Link: https://github.com/google/ExoPlayer/issues/2957  State: closed-fixed
- Mechanism: ExoPlayer's DVB decoder accepted an 8-bit pixel code that indexed beyond a four-entry color lookup table. Enabling the track sent the malformed value into `paint8BitPixelCodeString` and crashed the decoder thread with an array bounds exception.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSubtitleDecoder.kt, KiteCodecSubtitleDecoderFactory.create; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, buildSession
- Verdict: IMMUNE
- Why: KitePlayer has no DVB bitmap decoder or color lookup parser. The factory returns null for the stream, buildSession deselects it with a warning, and no DVB payload reaches Kotlin array indexing. DVB subtitle display remains missing, but this parser crash path does not exist.
- Severity if real: P0 crash/dataloss

### [EXOPLAYER-2408] MP4 edit list drops the final tx3g clear sample
- Link: https://github.com/google/ExoPlayer/issues/2408  State: closed-fixed
- Mechanism: ExoPlayer's MP4 edit-list trimming used an exclusive upper-bound search at an edit ending exactly on the final empty tx3g sample. That removed the sample which cleared the prior cue, so the last subtitle remained visible to the end of the video.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, read and TimestampMapper; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSubtitleDecoder.kt, tx3gText and KiteCodecTextSubtitleDecoder.send
- Verdict: IMMUNE
- Why: KitePlayer has no MP4 `parseStbl` edit-list slicing or binary-search boundary. Libavformat supplies already-timed packets, and the tx3g path limits text to the sample's two-byte declared length while the packet duration defines its cue end. The specific extractor off-by-one cannot drop a KitePlayer-owned clear sample.
- Severity if real: P1 broken feature

### [EXOPLAYER-1712] tx3g style boxes appear as erroneous subtitle characters
- Link: https://github.com/google/ExoPlayer/issues/1712  State: closed-fixed
- Mechanism: ExoPlayer treated an entire tx3g sample as text even though it contains a two-byte text length, that many text bytes, and optional binary styling boxes. Once text exceeded a boundary, bytes from those boxes appeared as stray visible characters.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSubtitleDecoder.kt, tx3gText and KiteCodecSubtitleDecoderFactory.create
- Verdict: IMMUNE
- Why: The `mov_text` decoder reads the big-endian length from the first two bytes, clamps it to the payload size, and decodes only that exact byte range. Optional `styl` and other boxes after the text are never passed to the inline markup parser or renderer.
- Severity if real: P1 broken feature

### [EXOPLAYER-870] Support formatted WebVTT subtitle
- Link: https://github.com/google/ExoPlayer/issues/870  State: closed-fixed
- Mechanism: Early ExoPlayer positioned WebVTT cues but emitted inline markup such as `<i>text</i>` literally or without its style. Correct rendering required parsing the supported tags into styled text runs before painting.
- KitePlayer code checked: kiteplayer-subtitles/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/subtitle/WebVttParser.kt, parse and stripVttOnlyTags; kiteplayer-subtitles/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/subtitle/SubRipParser.kt, InlineMarkup.parse
- Verdict: IMMUNE
- Why: WebVttParser passes cue text through InlineMarkup, which maps bold, italic, underline, strike, and font color into StyledSpan values. Voice and class wrappers keep their text while dropping only unsupported decoration, so the issue's italic example renders as styled text rather than literal tags.
- Severity if real: P2 quality/perf

### [EXOPLAYER-1136] UTF-8 byte order mark loses the first SubRip cue
- Link: https://github.com/google/ExoPlayer/issues/1136  State: closed-fixed
- Mechanism: A UTF-8 BOM remained attached to the first SRT index, turning `1` into a nonnumeric string. ExoPlayer rejected that index and skipped the first cue which followed it.
- KitePlayer code checked: kiteplayer-subtitles/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/subtitle/SubRipParser.kt, parse
- Verdict: IMMUNE
- Why: The parser removes a leading BOM before splitting lines and does not require numeric index lines at all. It scans directly for the next valid timing line, so either defense preserves the first cue.
- Severity if real: P1 broken feature

### [EXOPLAYER-3140] Missing side-loaded subtitle prevents video playback
- Link: https://github.com/google/ExoPlayer/issues/3140  State: closed-fixed
- Mechanism: A 404 from one SingleSample subtitle source propagated through the merged media source and failed the entire player. The fix added a mode that treats the auxiliary source failure as end of stream so the main video continues.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, parseExternalSubtitles, parseExternalSubtitle, and buildSession
- Verdict: IMMUNE
- Why: External subtitle loading finishes before the playback graph is built. Any unreadable source is converted to a track-deselection warning and omitted, after which buildSession opens the main media normally. An auxiliary file failure is never part of the backend session's fatal error path.
- Severity if real: P1 broken feature

### [EXOPLAYER-7020] Support non-UTF SubRip character encodings
- Link: https://github.com/google/ExoPlayer/issues/7020  State: wontfix
- Mechanism: A Romanian SRT was encoded as ISO-8859-16 rather than UTF-8. Reading those bytes with the wrong charset replaces or misdecodes accented letters even though cue timing remains valid.
- KitePlayer code checked: kiteplayer-core/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/internal/ExternalText.android.kt, readExternalTextOrNull; kiteplayer-core/src/jvmMain/kotlin/io/github/yuroyami/kiteplayer/internal/ExternalText.jvm.kt, readExternalTextOrNull; kiteplayer-core/src/nativeMain/kotlin/io/github/yuroyami/kiteplayer/internal/ExternalText.native.kt, readExternalTextOrNull
- Verdict: MISSING-FEATURE
- Why: JVM and Android call `File.readText()` with its default UTF-8 charset, while native reads raw bytes and calls `decodeToString` without charset detection or an override. SubtitleSource exposes no encoding field, so ISO-8859-16 and other legacy sidecars cannot be decoded faithfully.
- Severity if real: P2 quality/perf

### [EXOPLAYER-2956] Switching a track in unseekable HTTP MPEG-TS restarts playback
- Link: https://github.com/google/ExoPlayer/issues/2956  State: closed-fixed
- Mechanism: ExoPlayer implemented track selection by seeking after the change. An HTTP transport stream without a usable seek map could only return to its beginning, so choosing another audio or subtitle track restarted the program.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, buildSession, inPlaceContainerSubtitleChange, inPlaceAudioChange, and handleTrackChanges
- Verdict: IMMUNE
- Why: The source selects every audio and subtitle lane at open and maintains a bounded packet queue for each. Audio and subtitle selection use their respective in-place transactions and do not call `runSeek`, reopen the HTTP source, or change the video lane. An unseekable transport stream therefore does not restart merely because either reported track type changes.
- Severity if real: P1 broken feature

### [EXOPLAYER-2263] Embedded ASS timing is applied twice
- Link: https://github.com/google/ExoPlayer/issues/2263  State: wontfix
- Mechanism: An experimental ASS decoder embedded the container block time into each event while the renderer also treated event times as relative to that packet timestamp. This double application produced nonmonotonic apparent query times and mistimed cues; embedded events need packet-relative timing with a fresh subtitle state per packet.
- KitePlayer code checked: kiteplayer-subtitles/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/subtitle/AssParser.kt, AssTrackParser.parseEvent; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSubtitleDecoder.kt, KiteCodecAssSubtitleDecoder.send and receive
- Verdict: IMMUNE
- Why: The embedded parser ignores any document start and end columns and builds one fresh cue from the packet PTS plus packet duration supplied by the decoder. `receive` drains and clears the pending list each time, so neither absolute time nor prior subtitle state is added a second time.
- Severity if real: P1 broken feature

### [MEDIA3-418] Make audio processors MediaItem aware
- Link: https://github.com/androidx/media/issues/418  State: closed-fixed
- Mechanism: Gapless prebuffering configures the next item's audio before the public track-change callback. ReplayGain or another item-specific effect applied from that callback therefore misses the first samples; processors need the upcoming item and stream timestamp before those samples enter the shared sink.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, open and submitDecoded; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/AudioPipeline.kt, AudioPipeline; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerConfig.kt, PlayerConfig
- Verdict: MISSING-FEATURE
- Why: AudioPipeline is built only from source format, target format, downmix policy, pitch policy, and a warning sink. It has no MediaItem, timeline, metadata, or item-relative timestamp input, and its fixed gain stage receives only the player's global volume and mute. Per-item ReplayGain cannot be installed sample-accurately ahead of a future queue transition.
- Severity if real: P2 quality/perf

### [MEDIA3-415] Android bit-perfect USB audio output
- Link: https://github.com/androidx/media/issues/415  State: open
- Mechanism: Android 14 can route a supported USB device with `MIXER_BEHAVIOR_BIT_PERFECT`, but the player must query that device's mixer attributes and opt into the matching sample rate, channel mask, and format. Ordinary mixed AudioTrack output can resample, process, or attenuate the signal.
- KitePlayer code checked: kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/AudioTrackDriver.kt, PlatformAudioTrackDriver; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/AudioPipeline.kt, AudioPipeline and GainStage
- Verdict: MISSING-FEATURE
- Why: PlatformAudioTrackDriver always opens PCM float with ordinary media attributes and never queries or sets AudioMixerAttributes. Before the device, the pipeline may downmix, resample, stretch, and apply gain. There is no opt-in contract that requires an unchanged source format or rejects a route that cannot be bit-perfect.
- Severity if real: P2 quality/perf

### [MEDIA3-2966] AC3 and AC4 fail when Android exposes no decoder
- Link: https://github.com/androidx/media/issues/2966  State: open
- Mechanism: Media3 normally relies on a platform MediaCodec or encoded passthrough for AC3 and AC4. A device can advertise branded spatial-audio capability without exposing the decoder or output route needed by ExoPlayer, while VLC succeeds through software decoding.
- KitePlayer code checked: kiteplayer-ffmpeg/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/PlatformDecoderSelection.android.kt, platformAudioDecoder; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, newAudioDecoder; kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/AudioTrackSink.kt, open
- Verdict: IMMUNE
- Why: Android platform audio decoding is deliberately disabled. KiteCodec opens FFmpeg's decoder for the selected stream and sends float PCM to AudioTrack, so AC3 or AC4 playback does not depend on a vendor MediaCodec entry or an encoded speaker route. A linked FFmpeg build missing that decoder would fail explicitly at decoder open, not because Android only advertised Atmos.
- Severity if real: P1 broken feature

### [MEDIA3-3103] Noncanonical multichannel layouts route to wrong speakers
- Link: https://github.com/androidx/media/issues/3103  State: wontfix
- Mechanism: Converting channel count to one canonical Android output mask is ambiguous. Layouts such as 3.0, 5.0, 6.0, and 7.0 need their actual speaker positions; using only the count can send center dialogue to a left speaker or swap other channels.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, audioFormat; kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/AudioTrackSink.kt, open; kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/AudioTrackDriver.kt, PlatformAudioTrackDriver
- Verdict: MISSING-FEATURE
- Why: KitePlayer retains FFmpeg's source channel mask for mixing, but Android output accepts only 1, 2, 6, or 8 channels and maps those counts to fixed mono, stereo, 5.1, or 7.1 masks. Every 3.0, 5.0, 6.0, or 7.0 request is downmixed to stereo rather than preserving its native speaker layout.
- Severity if real: P2 quality/perf

### [MEDIA3-1471] Use decoder channel mask rather than channel count for AudioTrack
- Link: https://github.com/androidx/media/issues/1471  State: closed-fixed
- Mechanism: Since Android 13 a MediaCodec output can declare a channel mask that differs from the canonical mask for its count, such as 5.1.2 instead of canonical 7.1 for eight channels. Discarding that mask can fail AudioTrack creation or route and mix speakers incorrectly.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, audioFormat and KiteCodecAudioDecoder.receive; kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/AudioTrackSink.kt, open; kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/AudioTrackDriver.kt, PlatformAudioTrackDriver
- Verdict: SUSPECT
- Why: The decoder correctly retains FFmpeg's actual channel-layout mask, but AudioTrackSink constructs its accepted format from sample rate and count only. PlatformAudioTrackDriver then forces every eight-channel output to `CHANNEL_OUT_7POINT1_SURROUND`. A decoded 5.1.2 or other noncanonical eight-channel layout can therefore be remixed or routed under the wrong speaker mask.
- Severity if real: P1 broken feature

### [MEDIA3-3327] MPEG-PS private-stream header corrupts AC3, DTS, and LPCM
- Link: https://github.com/androidx/media/issues/3327  State: closed-fixed
- Mechanism: Media3 forwarded the four-byte DVD `private_stream_1` substream header into the elementary audio payload. Those bytes landed inside every compressed frame crossing a PES boundary, forcing decoder resynchronization and producing repeated audible corruption.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, read and newAudioDecoder; ../KiteCodec/kitecodec-core/src/jvmAndAndroidMain/kotlin/io/github/yuroyami/kitecodec/Playback.jvm.kt, PacketReader.read
- Verdict: IMMUNE
- Why: KitePlayer has no PsExtractor or PES payload reader. Libavformat's MPEG-PS demuxer strips the substream header before PacketReader exposes a packet, and KiteCodec sends only that demuxed packet to the decoder. The Media3-owned four-byte forwarding error is absent.
- Severity if real: P1 broken feature

### [MEDIA3-2210] Hardware DTS audio gradually loses synchronization
- Link: https://github.com/androidx/media/issues/2210  State: open
- Mechanism: On one Fire TV, the vendor hardware or encoded DTS path drifted by seconds while software output remained synchronized. Disabling hardware fixed timing but also disabled the desired DTS passthrough route.
- KitePlayer code checked: kiteplayer-ffmpeg/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/PlatformDecoderSelection.android.kt, platformAudioDecoder; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, newAudioDecoder; kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/AudioTrackDriver.kt, PlatformAudioTrackDriver
- Verdict: IMMUNE
- Why: KitePlayer never creates an Android hardware audio decoder or encoded DTS passthrough track. FFmpeg decodes DTS to float PCM and the engine dates output by decoded sample count, so the Fire TV's affected hardware and passthrough clock cannot enter this pipeline.
- Severity if real: P1 broken feature

### [MEDIA3-602] Seamless speed changes without a blip or noise
- Link: https://github.com/androidx/media/issues/602  State: open
- Mechanism: Ending one Sonic processor and starting another at a new rate can splice two waveforms at unrelated amplitudes. Even with no inserted silence, that discontinuity is audible as a click or burst, so a seamless change needs a blended transition inside the time stretcher.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, CoreCommand.SetSpeed; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, speed and flush; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/TempoStage.kt, reset
- Verdict: MISSING-FEATURE
- Why: A live change is deliberately implemented as a precise seek to the current position. That flushes the ring and resets TempoStage before the new speed takes effect, producing a brief rebuffer instead of blending the old and new stretch states. It avoids an in-buffer mixed rate but does not provide the seamless continuous transition requested upstream.
- Severity if real: P2 quality/perf

### [MEDIA3-2283] Switching to E-AC3 at 2x leaves audio at 1x
- Link: https://github.com/androidx/media/issues/2283  State: open
- Mechanism: Media3 switched from decoded AAC, where audio processing supported 2x, to an encoded E-AC3 route that supported only 1x. Video retained the requested 2x rate while audio fell back to real time, immediately breaking A/V synchronization.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, handleTrackChanges, inPlaceAudioChange, prepareAudioPath, and CoreCommand.SetSpeed; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, newAudioDecoder; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, open and speed
- Verdict: IMMUNE
- Why: E-AC3 is decoded to PCM rather than switched into an encoded passthrough path. The in-place audio transaction either reuses the current AudioPlayback or prepares a fresh PCM path initialized with the player's stored speed and preserve-pitch setting. The video scheduler receives the same speed, and there is no format-specific 1x audio branch.
- Severity if real: P1 broken feature

### [EXOPLAYER-3751] Support Monkey's Audio APE files
- Link: https://github.com/google/ExoPlayer/issues/3751  State: open
- Mechanism: APE needs both a Monkey's Audio container extractor and an APE decoder. ExoPlayer's FFmpeg extension could expose the decoder, but without an extractor the bytes never reached it.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecMediaBackend.kt, open; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, newAudioDecoder; ../KiteCodec/buildSrc/src/main/kotlin/BuildFFmpegWasmTask.kt, DECODERS and DEMUXERS
- Verdict: MISSING-FEATURE
- Why: Native and JVM builds delegate both extraction and decoding to a general FFmpeg build, so APE can work there. The supported web build explicitly allowlists its demuxers and decoders and contains neither `ape` entry. KitePlayer therefore has no consistent cross-platform APE contract.
- Severity if real: P1 broken feature

### [EXOPLAYER-2147] Support additional encoded passthrough audio formats
- Link: https://github.com/google/ExoPlayer/issues/2147  State: open
- Mechanism: TrueHD, DTS-HD, and other high-bitrate formats need the player to identify the exact bitstream, negotiate receiver capability, and package it for an IEC 61937 or format-specific encoded AudioTrack. Treating Matroska's generic DTS id as plain DTS loses that distinction.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, newAudioDecoder; kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/AudioTrackDriver.kt, PlatformAudioTrackDriver
- Verdict: MISSING-FEATURE
- Why: Every selected audio stream is decoded to float samples, and Android output always uses `ENCODING_PCM_FLOAT`. There is no receiver capability query, encoded AudioTrack mode, IEC 61937 packer, or bitstream passthrough policy, so lossless receiver-side decoding cannot be requested.
- Severity if real: P2 quality/perf

### [EXOPLAYER-9541] Repeated player creation eventually exhausts Android audio resources
- Link: https://github.com/google/ExoPlayer/issues/9541  State: open
- Mechanism: Repeated SimpleExoPlayer construction on some set-top boxes eventually left enough AudioTrack or generated audio-session resources allocated that system-wide audio initialization failed until reboot. The suspected risk was a resource created before callers could provide their own session id.
- KitePlayer code checked: kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/AudioTrackDriver.kt, PlatformAudioTrackDriver; kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/AudioTrackSink.kt, close and recoverIfFailed
- Verdict: IMMUNE
- Why: KitePlayer does not pre-generate or retain an audio session id. It creates one AudioTrack only when the sink opens, and close stops and joins the writer before flushing and releasing that exact track. Recovery also releases the dead driver before opening its replacement, so the reported constructor-time session accumulation has no equivalent owner.
- Severity if real: P0 crash/dataloss

### [EXOPLAYER-10516] Support custom float audio processors
- Link: https://github.com/google/ExoPlayer/issues/10516  State: open
- Mechanism: ExoPlayer's float output used a separate internal processor chain with no way to add application processors. Music players therefore could not run ReplayGain or other custom effects without forcing high-resolution audio into the 16-bit path.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/AudioPipeline.kt, AudioPipeline; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerConfig.kt, PlayerConfig; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/AudioSink.kt, AudioSink
- Verdict: MISSING-FEATURE
- Why: KitePlayer's float pipeline is fixed to ChannelMixer, SincResampler, TempoStage, and GainStage. Neither PlayerConfig nor the audio SPI accepts an application processor or raw-float transform, so a caller cannot inject ReplayGain, equalization, or another sample effect into playback.
- Severity if real: P2 quality/perf

### [EXOPLAYER-5693] Audio discontinuity or channel-count change freezes video
- Link: https://github.com/google/ExoPlayer/issues/5693  State: open
- Mechanism: A UDP transport stream changed audio format from six channels to two and also produced an audio timestamp discontinuity. On affected Amlogic devices the audio renderer stopped advancing its clock, so audio-master video scheduling froze.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, KiteCodecAudioDecoder.receive and TimestampMapper.mapTimestamp; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, submitDecoded and anchorLocked
- Verdict: SUSPECT
- Why: A channel or rate change correctly rebuilds the mixer and rebases synthesized timestamps, but a real TS PTS discontinuity is still mapped by subtracting one fixed container origin. AudioPlayback accepts the new buffer PTS as a ring anchor with no discontinuity epoch. A large jump can move or stall the audio master while video remains on the old timeline.
- Severity if real: P1 broken feature

### [EXOPLAYER-8222] AC4 passthrough assumes constant frames per encoded sample
- Link: https://github.com/google/ExoPlayer/issues/8222  State: open
- Mechanism: ExoPlayer read the AC4 frame count only from the first encoded access unit and reused it forever. Streams aligning audio with fractional-rate video can vary that count per unit, so accumulated written-frame duration and current position drifted.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, KiteCodecAudioDecoder.receive; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, submitDecoded; kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/AudioTrackSink.kt, submittedFrames
- Verdict: IMMUNE
- Why: KitePlayer never estimates duration from an encoded AC4 sample header or reuses a first-sample frame count. FFmpeg decodes each unit, every AudioBuffer exposes that frame's actual sample count, and both ring submission and AudioTrack accounting count the resulting PCM frames.
- Severity if real: P1 broken feature

### [EXOPLAYER-8874] Enabling audio mid-playback briefly freezes video
- Link: https://github.com/google/ExoPlayer/issues/8874  State: open
- Mechanism: ExoPlayer immediately changed the master from its standalone clock to a newly enabled audio renderer whose clock had not started. Video then froze until audio began advancing; a seamless solution needs a deferred clock handoff and later convergence.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, inPlaceAudioChange and masterPosition; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, position and anchorClock
- Verdict: IMMUNE
- Why: Audio enablement is an in-place lane transaction, so video keeps scheduling while the new decoder and output start. `masterPosition` asks the audio path for a position with a nullable read; until the first audio buffer establishes an anchor, it returns null and video remains standalone-paced. The scheduler therefore never reads a half-started audio clock and cannot freeze on it.
- Severity if real: P2 quality/perf

### [EXOPLAYER-5024] Audio plays while the first post-seek TS video frame is frozen
- Link: https://github.com/google/ExoPlayer/issues/5024  State: open
- Mechanism: Seeking a transport stream landed audio before the video decoder reached a matching frame. ExoPlayer started the audio clock immediately and displayed one early video frame, which then remained frozen until video caught up.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runSeek, awaitLanding, and presentFirstFrame; kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/AudioTrackSink.kt, stop and start
- Verdict: IMMUNE
- Why: A seek stops the audio device, parks workers, flushes every queue, and keeps the scheduler idle while decode-forward finds the landing. The core presents the surviving video frame before the normal state loop can restart AudioTrack, so audio cannot audibly advance during the post-seek video search.
- Severity if real: P2 quality/perf

### [EXOPLAYER-6459] A single audio sample keeps the player READY forever
- Link: https://github.com/google/ExoPlayer/issues/6459  State: open
- Mechanism: Android AudioTrack may not start playout below its minimum occupancy. One pending output sample therefore made ExoPlayer's audio renderer report ready forever even though the device would never advance it.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, handleEof; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, drain; kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/AudioTrackSink.kt, drain
- Verdict: IMMUNE
- Why: EOF does not depend on an unbounded renderer-ready flag. It pushes the DSP tail, drains the ring, then AudioTrackSink polls submitted versus played frames only within device-buffer duration plus fixed slack. Even if a subthreshold sample never advances, the bounded drain returns and the core reaches Ended rather than READY forever.
- Severity if real: P1 broken feature

### [EXOPLAYER-10491] Drop early TS audio before the first decodable video frame
- Link: https://github.com/google/ExoPlayer/issues/10491  State: open
- Mechanism: A TS starts with audio samples before its first usable IDR video frame. Playing all that audio while holding the first picture produces a one-to-two-second frozen startup; the requested behavior discards audio before the video landing so both streams begin together.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, awaitInitialFill, runDemux, runAudioFeed, and presentFirstFrame
- Verdict: SUSPECT
- Why: Initial fill accepts audio and video readiness independently, and ordinary open has no shared discard-before boundary derived from the first decoded video PTS. Audio buffers earlier than the first usable video frame can enter the ring and play after the first picture is presented, reproducing the reported startup freeze.
- Severity if real: P2 quality/perf

### [EXOPLAYER-8569] Delayed play loses a fully decoded short audio clip
- Link: https://github.com/google/ExoPlayer/issues/8569  State: open
- Mechanism: With autoplay disabled, ExoPlayer decoded a sub-second sound completely before the user pressed play. After a short delay all output buffers had already passed through renderer state, and starting later produced no audible sample.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, awaitInitialFill and handlePlayPause; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, submitDecoded; kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/AudioTrackSink.kt, setPaused
- Verdict: IMMUNE
- Why: A paused open fills KitePlayer's owned audio ring while the AudioTrack writer remains stopped. Backpressure prevents decode from consuming beyond ring capacity, and resume starts the writer over the retained frames rather than asking a renderer for buffers it already discarded. Waiting before play does not age queued PCM out.
- Severity if real: P1 broken feature

### [EXOPLAYER-10503] MP4 edit list is deferred as gapless trim metadata
- Link: https://github.com/google/ExoPlayer/issues/10503  State: wontfix
- Mechanism: A short MP4 `elst` trim should remove AAC encoder priming sample-accurately without feeding negative timestamps to fragile decoders. ExoPlayer recognized that edit as gapless metadata and applied the trim after decode instead of shifting every compressed sample timestamp.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/vendor/ffmpeg/libavformat/mov.c, mov_fix_index; /Users/macbook/StudioProjects/#Kite/KiteCodec/vendor/ffmpeg/libavformat/demux.c, read_frame_internal; /Users/macbook/StudioProjects/#Kite/KiteCodec/vendor/ffmpeg/libavcodec/decode.c, discard_samples; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, KiteCodecAudioDecoder.receive
- Verdict: IMMUNE
- Why: Pinned FFmpeg converts the edit into `skip_samples`, attaches that count as packet side data in `read_frame_internal`, and `discard_samples` physically removes whole or partial leading PCM samples after decode while updating timestamps. KitePlayer receives the already-trimmed buffer, so it does not need a second player-level gapless field.
- Severity if real: P2 quality/perf

### [EXOPLAYER-7456] AudioTrack position becomes negative after reset
- Link: https://github.com/google/ExoPlayer/issues/7456  State: closed-fixed
- Mechanism: Immediately after startup or seek, ExoPlayer occasionally accepted a negative AudioTrack timestamp into its renderer position. That violated nonnegative timeline assumptions in buffering and renderer logic.
- KitePlayer code checked: kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/AudioTrackSink.kt, acceptTimestamp, extendedHead, and resetTimestampState; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, flush
- Verdict: IMMUNE
- Why: `acceptTimestamp` rejects every negative frame position, while playback-head fallback first masks the raw value to an unsigned 32-bit count. Seek flush resets timestamp and wrap state before the next anchor, so no negative driver position can become the master media clock.
- Severity if real: P1 broken feature

### [EXOPLAYER-899] TS DTS adjustment shifts the first video timestamps
- Link: https://github.com/google/ExoPlayer/issues/899  State: wontfix
- Mechanism: A changed first DTS altered the common TS timestamp offset and shifted initial video samples by roughly two frames. ExoPlayer considered this harmless because the same TimestampAdjuster and offset were applied to audio and video, preserving their relative synchronization.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, TimestampMapper, KiteCodecVideoDecoder, and KiteCodecAudioDecoder
- Verdict: IMMUNE
- Why: One TimestampMapper belongs to the source and is passed to both selected decoders. Every real audio and video PTS subtracts the same container start exactly once, so a different initial origin can move the whole content timeline but cannot introduce an audio-versus-video offset inside KitePlayer.
- Severity if real: P2 quality/perf

### [EXOPLAYER-1755] Audio timestamp predates the most recent resume
- Link: https://github.com/google/ExoPlayer/issues/1755  State: wontfix
- Mechanism: A FireOS AudioTrack repeatedly returned a timestamp associated with device time before the track's latest resume. Trusting that stale timestamp as current position produced periodic playback judder.
- KitePlayer code checked: kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/AudioTrackSink.kt, readTimestamp, acceptTimestamp, deadlineForBlock, and resetTimestampState
- Verdict: SUSPECT
- Why: KitePlayer rejects negative, future-frame, backward-frame, and backward-nanotime timestamps, but after a reset the last accepted nanotime is `Long.MIN_VALUE`. It does not compare the first returned nanotime with the actual resume time or current monotonic clock. A stale but otherwise in-range first timestamp can therefore be accepted and date audio deadlines in the past.
- Severity if real: P1 broken feature

### [EXOPLAYER-10520] Seamlessly branched MKV TrueHD drops out and loses sync
- Link: https://github.com/google/ExoPlayer/issues/10520  State: open
- Mechanism: At branch boundaries in Blu-ray-derived Matroska, ExoPlayer's extractor and encoded TrueHD path delivered a dropout and sometimes resumed out of sync. Maintainers localized any player-owned part to extracting the MKV bitstream before the platform decoder or receiver.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecMediaBackend.kt, open; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, read, newAudioDecoder, and KiteCodecAudioDecoder.receive
- Verdict: IMMUNE
- Why: KitePlayer uses libavformat for Matroska extraction and FFmpeg software decode to PCM, the same implementation family reported to play the supplied files in VLC. It neither uses ExoPlayer's Matroska extractor nor sends the ambiguous encoded TrueHD access units directly to a receiver, so both affected ownership layers are absent.
- Severity if real: P1 broken feature

### [MEDIA3-317] Allow adaptation between MediaCodec and bundled software codec
- Link: https://github.com/androidx/media/issues/317  State: open
- Mechanism: Media3 mapped one adaptive group to one renderer for its whole lifetime. A group containing FLAC handled only by the FFmpeg extension and AC-4 handled only by MediaCodec could not switch between those tracks without recreating the player because the renderer association could not change mid-playback.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, buildSession, inPlaceAudioChange, and handleTrackChanges; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, newAudioDecoder
- Verdict: IMMUNE
- Why: KitePlayer has no permanent audio-renderer association. Every audio stream is cached from open, every selected codec is decoded by the same KiteCodec FFmpeg backend, and `inPlaceAudioChange` opens the newly selected decoder and swaps only the audio lane. FLAC, AAC, and AC-4 therefore need neither a player recreation nor a cross-renderer handoff.
- Severity if real: P1 broken feature

### [MEDIA3-3011] PlayerSurface can pass a dying TextureView Surface to MediaCodec
- Link: https://github.com/androidx/media/issues/3011  State: wontfix
- Mechanism: Rapid Compose attach and detach could race TextureView destruction with `MediaCodec.setOutputSurface`, producing `IllegalArgumentException` when the codec received an invalid or already released Surface. The report was closed for missing reproduction data, while the maintainer identified the released-Surface race as a plausible failure mode.
- KitePlayer code checked: kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/MediaCodecSurfaceTarget.kt, update, snapshot, and withSnapshotCompletion; kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/MediaCodecVideoDecoder.kt, switchTo
- Verdict: IMMUNE
- Why: KitePlayer versions every Surface replacement under one fence, admits presentation only to the current valid version, and switches a detached decoder to a private fallback Surface. If `setOutputSurface` still throws, it invalidates the display target and marks the hardware decoder failed instead of continuing to release buffers to the dying Surface.
- Severity if real: P1 broken feature

### [MEDIA3-698] Released Surface can make a paused codec fail while draining
- Link: https://github.com/androidx/media/issues/698  State: wontfix
- Mechanism: An app released the playback Surface while ExoPlayer was paused, but MediaCodec continued dequeuing and tried to acquire a graphic buffer from that released target. The platform codec then reported error `0x80000000` instead of harmlessly decoding and discarding frames.
- KitePlayer code checked: kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/MediaCodecSurfaceTarget.kt, update and Snapshot.isDisplayable; kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/MediaCodecVideoDecoder.kt, switchTo and drainFallbackImages
- Verdict: IMMUNE
- Why: Removing KitePlayer's display Surface synchronously switches MediaCodec to an owned ImageReader Surface. Decoding can continue while paused, but those fallback images are drained and closed, and queued releases whose Surface version is no longer current are discarded.
- Severity if real: P1 broken feature

### [MEDIA3-1497] Stopping MediaCodec before release avoids immediate reinitialization failure
- Link: https://github.com/androidx/media/issues/1497  State: closed-fixed
- Mechanism: On Android APIs 30 through 32, immediately creating a new codec after releasing the old one could fail because the old resources had not finished unwinding. Media3 replaced an arbitrary 50 ms retry delay by restoring `MediaCodec.stop()` before `release()` on affected versions.
- KitePlayer code checked: kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/MediaCodecVideoDecoder.kt, init failure rollback, close, and abortCodecLocked
- Verdict: IMMUNE
- Why: Every KitePlayer MediaCodec teardown path calls `stop()` before `release()`, including normal close, failed initialization, and fatal Surface switching. It does not rely on an arbitrary sleep before a replacement decoder is opened.
- Severity if real: P1 broken feature

### [MEDIA3-2529] SberBox MediaCodec rejects output Surface replacement
- Link: https://github.com/androidx/media/issues/2529  State: open
- Mechanism: Some SberBox codec implementations throw from `MediaCodec.setOutputSurface` after a delayed screensaver Surface lifecycle. The upstream workaround is to blacklist in-place Surface switching for the device and recreate the codec instead.
- KitePlayer code checked: kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/MediaCodecVideoDecoder.kt, switchTo and abortCodecLocked; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, videoRecoveryFor and reopenWithBackendSoftware
- Verdict: IMMUNE
- Why: KitePlayer has no SberBox blacklist, so the vendor call can still throw, but the exception cannot leave the codec looking healthy on a black Surface. `switchTo` moves back to its private Surface or aborts the codec, and the core reopens a seekable source with software video under the default Auto policy.
- Severity if real: P1 broken feature

### [MEDIA3-1595] Qualcomm AVC decoder initialization fails for a 2048 by 1080 stream at 50 fps
- Link: https://github.com/androidx/media/issues/1595  State: open
- Mechanism: A Huawei device advertised an AVC decoder but rejected configuration of a 2048 by 1080, 50 fps Main Profile stream with vendor error `0xfffffc0e`. A single advertised decoder name was therefore not proof that the exact size, rate, profile, and level could be initialized.
- KitePlayer code checked: kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/MediaCodecVideoDecoder.kt, MediaCodecVideoDecoderFactory.create and findHardwareDecoders; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, createVideoDecoder
- Verdict: IMMUNE
- Why: KitePlayer probes the exact format, filters to hardware decoders whose capabilities cover it, tries every admitted candidate, and treats configuration failure as a declined hardware factory. Under Auto the backend FFmpeg software decoder is then selected instead of making the first vendor failure terminal.
- Severity if real: P1 broken feature

### [MEDIA3-826] Device-independent HDR tone mapping requires an explicit rendering path
- Link: https://github.com/androidx/media/issues/826  State: closed-fixed
- Mechanism: Passing HDR straight to an SDR display produces flat or washed output. Media3 ultimately documented an OpenGL video-effects path that linearizes HDR, maps its gamut and luminance, and emits SDR rather than depending on inconsistent OEM decoder tone mapping.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/HdrToneMap.kt, mapInPlace; kiteplayer-ffmpeg/src/nativeMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/SoftwareConverter.native.kt, convert; kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/AndroidGpuImageVideoRenderer.kt, composeMediaCodecOutputContract
- Verdict: IMMUNE
- Why: KitePlayer has both a device-independent CPU HDR-to-SDR pass and an Android GPU contract that requests and validates decoder-side SDR output before exposing an image. Unsupported hardware conversion falls back to software instead of silently tagging HDR pixels as SDR.
- Severity if real: P1 broken feature

### [MEDIA3-1074] HDR tracks remain selectable when the attached display reports no HDR support
- Link: https://github.com/androidx/media/issues/1074  State: open
- Mechanism: Media3 selected HLG tracks even when `Display.HdrCapabilities` reported no supported HDR types. On a path that passed HDR through unchanged, the non-HDR display showed washed colors despite an SDR variant being available.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, buildSession automatic video choice; kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/MediaCodecVideoDecoder.kt, DIRECT_SURFACE_ADMISSION and mediaCodecFormat
- Verdict: SUSPECT
- Why: KitePlayer's automatic video choice takes the first non-cover-art video stream and never consults Android display HDR capabilities. Its direct Surface admission preserves representable HDR instead of forcing SDR. Compose and software paths tone map safely, but a direct Surface on a non-HDR display can select and pass through the same unsuitable track.
- Severity if real: P1 broken feature

### [MEDIA3-1941] Select a preferred dynamic HDR metadata format
- Link: https://github.com/androidx/media/issues/1941  State: open
- Mechanism: A stream carrying both Dolby Vision and HDR10+ metadata can make a device prioritize Dolby Vision even when the connected display supports only HDR10+. The requested control filters unwanted Dolby Vision NAL units or HDR10+ SEI metadata so the platform receives only the preferred dynamic format.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerConfig.kt; kiteplayer-output/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/output/AnnexB.kt; kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/MediaCodecVideoDecoder.kt, pumpInput and mediaCodecFormat
- Verdict: MISSING-FEATURE
- Why: KitePlayer exposes no preferred dynamic HDR format and does not inspect or filter Dolby Vision or SMPTE ST 2094-40 messages before feeding access units to MediaCodec. Its profile checks distinguish HDR-capable profiles, but they cannot choose between two dynamic metadata systems in one bitstream.
- Severity if real: P1 broken feature

### [MEDIA3-3314] PQ HDR-to-SDR conversion emits negative or NaN color components
- Link: https://github.com/androidx/media/issues/3314  State: closed-fixed
- Mechanism: Media3's OpenGL PQ conversion transformed some BT.2020 colors into negative linear BT.709 components, then applied a gamma `pow` operation. GLSL `pow` on a negative input produced NaN values that appeared as black speckles or blocks until the intermediate values were clamped.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/HdrToneMap.kt, mapInPlace, warp4095, and encode
- Verdict: IMMUNE
- Why: KitePlayer clamps every negative BT.709 component to zero immediately after gamut conversion, and `warp4095` bounds the value again before the gamma lookup. No negative value reaches `pow`, so this exact NaN-producing arithmetic is absent.
- Severity if real: P2 quality/perf

### [EXOPLAYER-3835] MediaCodec initialization fails after a dummy Surface transition
- Link: https://github.com/google/ExoPlayer/issues/3835  State: closed-fixed
- Mechanism: Several Android 6 MediaTek devices could not initialize or switch a secure AVC codec through ExoPlayer's dummy Surface path. Upstream added device workarounds that avoid creating the dummy Surface and recreate the codec rather than switching its output.
- KitePlayer code checked: kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/MediaCodecVideoDecoder.kt, MediaCodecVideoDecoderFactory.create; kiteplayer-ffmpeg/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/PlatformDecoderSelection.android.kt, platformDecoderSelection
- Verdict: N/A
- Why: This issue is specific to secure DRM playback through ExoPlayer's dummy Surface. KitePlayer has no secure DRM session or secure dummy-Surface path, so there is no corresponding product surface to classify as an implemented immunity or a missing general playback feature.
- Severity if real: P1 broken feature

### [EXOPLAYER-8986] MediaCodec maximum dimensions reserve excessive contiguous memory
- Link: https://github.com/google/ExoPlayer/issues/8986  State: wontfix
- Mechanism: Setting `KEY_MAX_WIDTH` and `KEY_MAX_HEIGHT` made MediaCodec reserve buffers for the largest declared adaptive format even while a smaller representation played. On constrained devices that raised the contiguous-memory footprint beyond what an exact non-adaptive format needed.
- KitePlayer code checked: kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/MediaCodecVideoDecoder.kt, mediaCodecFormat; kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/MediaCodecConfiguration.kt, codecInput
- Verdict: IMMUNE
- Why: KitePlayer configures `KEY_WIDTH` and `KEY_HEIGHT` for the selected stream and a bounded `KEY_MAX_INPUT_SIZE`, but never sets `KEY_MAX_WIDTH` or `KEY_MAX_HEIGHT`. It also has no adaptive representation set whose largest dimensions must be preallocated.
- Severity if real: P2 quality/perf

### [EXOPLAYER-7346] Unsupported HEVC Main 10 content produces a flower or blue screen
- Link: https://github.com/google/ExoPlayer/issues/7346  State: wontfix
- Mechanism: Older low-capability phones accepted a 1080p HEVC Main 10 stream even though the Android compatibility definition did not require them to decode that profile and size. Feeding it anyway produced corrupted blue or tiled output rather than a clean unsupported-format decision.
- KitePlayer code checked: kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/MediaCodecVideoDecoder.kt, codecInput and MediaCodecVideoDecoderFactory.create; kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/MediaCodecConfiguration.kt, findHardwareDecoders
- Verdict: IMMUNE
- Why: KitePlayer parses bit depth, profile, level, size, and rate into the probe format and requires a hardware capability match before configuration. If no proved decoder exists, Auto selects FFmpeg software; it does not feed Main 10 merely because a generic HEVC codec name is present.
- Severity if real: P1 broken feature

### [EXOPLAYER-6381] MediaCodec resource reclamation terminates playback
- Link: https://github.com/google/ExoPlayer/issues/6381  State: wontfix
- Mechanism: A Vivo codec sporadically raised `ERROR_RECLAIMED` when the platform resource manager took its decoder for a higher-priority client. ExoPlayer treated the native codec loss as fatal, although the media and player state were otherwise usable.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, videoDecoderSend, videoDecoderReceive, videoRecoveryFor, and reopenWithBackendSoftware
- Verdict: IMMUNE
- Why: KitePlayer wraps send and receive exceptions as hardware decoder failures. Under Auto, one failure on a seekable source tears down the dead codec, reopens the same tracks with FFmpeg software, seeks to the current position, and resumes instead of retaining the reclaimed MediaCodec.
- Severity if real: P1 broken feature

### [EXOPLAYER-8134] Native MediaCodec dequeue calls sporadically throw IllegalStateException
- Link: https://github.com/google/ExoPlayer/issues/8134  State: open
- Mechanism: Across millions of sessions, mainly on Samsung devices, synchronous `native_dequeueInputBuffer` and `native_dequeueOutputBuffer` occasionally threw `IllegalStateException` without a content-specific trigger. The codec could therefore fail after successful initialization and ordinary playback.
- KitePlayer code checked: kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/MediaCodecVideoDecoder.kt, acquireInputOrStageOutput and pumpOutput; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, videoRecoveryFor
- Verdict: SUSPECT
- Why: KitePlayer uses the same synchronous dequeue APIs and has no device workaround or retry around an isolated illegal state. Auto can recover once when the source is seekable, but a non-seekable source, a second codec failure, or a non-Auto hardware policy still turns the vendor exception into terminal playback failure.
- Severity if real: P1 broken feature

### [EXOPLAYER-8990] MediaCodec dequeue failures correlate with profile changes and concurrent codecs
- Link: https://github.com/google/ExoPlayer/issues/8990  State: open
- Mechanism: Sporadic native dequeue failures appeared around H.264 High Profile representation changes and, in some sessions, concurrent ad and content codecs. Maintainers noted that unsupported profiles and multiple simultaneous codecs can exhaust or destabilize vendor codec implementations.
- KitePlayer code checked: kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/MediaCodecConfiguration.kt, profileIsCompatible and findHardwareDecoders; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, buildSession and videoRecoveryFor
- Verdict: IMMUNE
- Why: KitePlayer proves H.264 profile and level compatibility before admission, opens only one selected video decoder in a session, and has no adaptive representation transition or parallel ad player inside the core. The concrete triggers identified upstream are therefore absent, with software recovery still available for an unrelated runtime codec loss.
- Severity if real: P1 broken feature

### [EXOPLAYER-10730] MediaCodec rejects rapid Surface switching on Android 12
- Link: https://github.com/google/ExoPlayer/issues/10730  State: open
- Mechanism: Repeatedly switching video outputs on a Moto G60 eventually made `MediaCodec.setOutputSurface` throw `IllegalStateException`, stopping a sequence of otherwise playable videos.
- KitePlayer code checked: kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/MediaCodecSurfaceTarget.kt, update; kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/MediaCodecVideoDecoder.kt, switchTo and abortCodecLocked; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, videoRecoveryFor
- Verdict: IMMUNE
- Why: KitePlayer serializes every Surface change with decoder releases. A failed switch either restores its private drain Surface or aborts the codec, and the core can reopen seekable media in software under Auto. It does not continue using the codec after an unaccounted partial switch.
- Severity if real: P1 broken feature

### [EXOPLAYER-91] MP4 display rotation metadata is ignored
- Link: https://github.com/google/ExoPlayer/issues/91  State: closed-fixed
- Mechanism: Early ExoPlayer extracted portrait video pixels but ignored the MP4 track display matrix, so a camera recording tagged for a 90 degree turn played sideways. The eventual fix propagated the rotation and applied it in TextureView or by the platform Surface path.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, PlayerStreamInfo conversion; kiteplayer-output/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/output/FrameLayout.kt, quarterTurn and frameLayout; kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/MediaCodecVideoDecoder.kt, mediaCodecFormat
- Verdict: IMMUNE
- Why: KiteCodec extracts the container display matrix as clockwise degrees, KitePlayer carries it on the stream and every frame, software renderers apply the quarter turn in layout, and direct MediaCodec receives `KEY_ROTATION`. The metadata is not discarded between demux and display.
- Severity if real: P1 broken feature

### [EXOPLAYER-11038] Android TV sometimes ignores codec rotation metadata
- Link: https://github.com/google/ExoPlayer/issues/11038  State: closed-fixed
- Mechanism: Android TV API 30 and 31 sometimes failed to apply `MediaFormat` rotation in the direct decoder path, squeezing portrait video. Routing frames through Media3's video-effects pipeline applied rotation outside the faulty platform path and fixed the picture.
- KitePlayer code checked: kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/MediaCodecVideoDecoder.kt, mediaCodecFormat and frameRotationDegrees; kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/AndroidSurfaceVideoRenderer.kt, present
- Verdict: SUSPECT
- Why: KitePlayer's direct Surface factory sets `KEY_ROTATION` and then publishes MediaCodec frames with zero renderer-side rotation, trusting the platform to turn them. On the affected Android TV releases a decoder can silently ignore that key without throwing, so the software recovery path never activates and the same squeezed output is possible.
- Severity if real: P1 broken feature

### [EXOPLAYER-9154] Android 8 framework looks up the wrong MediaCodec rotation key
- Link: https://github.com/google/ExoPlayer/issues/9154  State: wontfix
- Mechanism: Android 8.0 changed its internal lookup from `rotation-degrees` to a different codec key, so the framework reset rotation to zero even though the player supplied the correct metadata. Android 8.1 changed the lookup back, leaving 8.0 device firmware unable to apply decoder rotation.
- KitePlayer code checked: kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/MediaCodecVideoDecoder.kt, MediaCodecVideoDecoderFactory.create; kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/AndroidSurfaceVideoRenderer.kt, drawPending
- Verdict: IMMUNE
- Why: KitePlayer refuses direct MediaCodec output before Android 10. Android 8 therefore uses decoded software frames whose `rotationDegrees` is applied by `FrameLayout` and Canvas, never the broken Android 8 codec key.
- Severity if real: P1 broken feature

### [EXOPLAYER-8928] Malformed MP4 transform matrix loses an intended quarter turn
- Link: https://github.com/google/ExoPlayer/issues/8928  State: wontfix
- Mechanism: One iOS recording declared 90 degree rotation but had the wrong sign in a `tkhd` transform matrix element. ExoPlayer required the canonical matrix and returned no rotation, while more tolerant players still displayed the intended portrait orientation.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/vendor/ffmpeg/libavutil/display.c, av_display_rotation_get; /Users/macbook/StudioProjects/#Kite/KiteCodec/native/kitecodec-c/src/helpers_playback.c, ffkmp_stream_rotation_degrees; kiteplayer-output/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/output/FrameLayout.kt, quarterTurn
- Verdict: IMMUNE
- Why: `av_display_rotation_get` derives each matrix scale with sign-insensitive `hypot` and calculates the angle from matrix elements 1 and 0 after normalization. The malformed sign in the other scale element therefore does not suppress the intended quarter turn, which KitePlayer receives and normalizes normally.
- Severity if real: P2 quality/perf

### [EXOPLAYER-7495] Apply a user-requested rotation after a GL video effect
- Link: https://github.com/google/ExoPlayer/issues/7495  State: wontfix
- Mechanism: Rotating the outer PlayerView did not rotate pixels produced by the GL processor. An editor preview needed to rotate the video quad or texture coordinates inside the video rendering pass, separately deciding whether overlays rotate with it.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerConfig.kt; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/VideoFrame.kt, rotationDegrees; kiteplayer-output/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/output/FrameLayout.kt, frameLayout
- Verdict: MISSING-FEATURE
- Why: KitePlayer applies container-declared rotation but exposes no playback setting for an additional user-authored video rotation. UI code can rotate the whole view, but the core cannot request a separate post-effect pixel turn while leaving controls or subtitles in output space.
- Severity if real: P2 quality/perf

### [EXOPLAYER-9724] Huawei Android 8 ignores otherwise valid video rotation
- Link: https://github.com/google/ExoPlayer/issues/9724  State: wontfix
- Mechanism: ExoPlayer correctly extracted a 90 degree MP4 rotation, but a Huawei P20 Lite decoder ignored it and rendered stretched landscape pixels. The same file worked on devices whose framework honored the format metadata.
- KitePlayer code checked: kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/MediaCodecVideoDecoder.kt, MediaCodecVideoDecoderFactory.create; kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/AndroidSurfaceVideoRenderer.kt, drawPending and draw
- Verdict: IMMUNE
- Why: Direct MediaCodec is disabled below Android 10, so the affected Huawei Android 8 path receives software frames. KitePlayer rotates those pixels itself on Canvas and does not ask the Huawei decoder to apply the ignored metadata.
- Severity if real: P1 broken feature

### [EXOPLAYER-8478] API 29 x86 emulator applies the wrong video rotation
- Link: https://github.com/google/ExoPlayer/issues/8478  State: wontfix
- Mechanism: An Android API 29 x86 emulator reported correct dimensions and container rotation but its platform video path displayed the frame in the wrong orientation. Maintainers classified the behavior as an emulator implementation problem.
- KitePlayer code checked: kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/MediaCodecVideoDecoder.kt, MediaCodecVideoDecoderFactory.create and mediaCodecFormat; kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/AndroidSurfaceVideoRenderer.kt, videoDecoderFactories
- Verdict: SUSPECT
- Why: Android 10 is the first release KitePlayer admits for direct MediaCodec, and API 29 receives `KEY_ROTATION` through that same platform path. A codec that silently applies the turn incorrectly produces no exception, so Auto does not fall back to the correct software renderer.
- Severity if real: P2 quality/perf

### [EXOPLAYER-7113] HEVC prefix and suffix SEI units are assigned to the wrong transport-stream sample
- Link: https://github.com/google/ExoPlayer/issues/7113  State: closed-fixed
- Mechanism: ExoPlayer's H.265 transport-stream reader omitted prefix SEI NAL units from most samples and attached suffix SEI to the wrong sample, so dynamic HDR metadata did not reach MediaCodec. The fix used access-unit boundaries that work even when optional AUD units are absent.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, readPacket and KiteCodecPacket.copyBytes; kiteplayer-output/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/output/AnnexB.kt, toAnnexB; kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/MediaCodecVideoDecoder.kt, pumpInput
- Verdict: IMMUNE
- Why: KitePlayer does not implement ExoPlayer's H265Reader or reconstruct access units by scanning AUD markers. Libavformat supplies complete HEVC packets, and KitePlayer's only rewrite converts length prefixes to start codes without filtering SEI NAL types, so prefix and suffix metadata remain with FFmpeg's packet boundary.
- Severity if real: P1 broken feature

### [MEDIA3-639] Require particular subtitle or caption role flags
- Link: https://github.com/androidx/media/issues/639  State: open
- Mechanism: A preferred text language was allowed to select hearing-impaired captions even when the system caption setting was off. Language preference could not be combined with a required subtitle role, so applications could not express subtitles-or-none while keeping captions available for explicit user selection.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerConfig.kt, SubtitleConfig; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/Tracks.kt, TrackInfo; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, pickSubtitle
- Verdict: MISSING-FEATURE
- Why: KitePlayer exposes language, default, forced, and one collapsed accessibility flag, but no caption-versus-subtitle role and no required role set. `pickSubtitle` can prefer an accessibility track in a language or any track in that language, so an app cannot encode the upstream subtitles-only fallback law.
- Severity if real: P1 broken feature

### [MEDIA3-2777] Require a playable audio track instead of silently accepting none
- Link: https://github.com/androidx/media/issues/2777  State: wontfix
- Mechanism: Media3 could reach Ready and advance a file even when its only meaningful audio track had no decoder, because zero selected media tracks is legal for general players. An audio-book application needed a per-player requirement that missing or unsupported audio be a typed failure.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerConfig.kt; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, buildSession and pickAudio
- Verdict: MISSING-FEATURE
- Why: KitePlayer tries to decode an audio stream that exists and will report decoder refusal, but it deliberately accepts media with no audio candidate. There is no `requireAudio` policy for an audio-only application to reject video-only, metadata-only, or otherwise silent media during open.
- Severity if real: P1 broken feature

### [MEDIA3-1868] Audio selection appears to depend on container track order
- Link: https://github.com/androidx/media/issues/1868  State: wontfix
- Mechanism: The reported comparator seemed to choose a different stereo track after the same tracks were reordered, but the differing track also carried the default-selection flag. Upstream determined that the declared default, not an unstable comparison, explained the result.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, pickAudio; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, PlayerStreamInfo conversion
- Verdict: IMMUNE
- Why: KitePlayer searches every audio track for each preferred language, then searches every track for `isDefault`, and uses list order only as the final fallback. Moving a default track does not change which track wins, while two truly equal unflagged tracks intentionally have no discriminator beyond container order.
- Severity if real: P2 quality/perf

### [EXOPLAYER-6742] System locale unexpectedly overrides audio track order
- Link: https://github.com/google/ExoPlayer/issues/6742  State: wontfix
- Mechanism: ExoPlayer used device locale as a late audio-language preference, so a manifest with no explicit application preference could select a different language after the device locale changed rather than choosing the first or default adaptation set.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerConfig.kt, AudioConfig; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, pickAudio
- Verdict: IMMUNE
- Why: KitePlayer never reads platform locale. Only the explicit `AudioConfig.preferredLanguages` list influences language choice; absent that, the container default flag wins and then the first audio stream. Selection is therefore stable across device locale changes.
- Severity if real: P2 quality/perf

### [EXOPLAYER-9690] Switching audio resets an explicit subtitle selection
- Link: https://github.com/google/ExoPlayer/issues/9690  State: closed-fixed
- Mechanism: A track-selector update replaced the complete override set when changing one media type instead of adding the new override. Selecting an audio track therefore discarded the previously selected German subtitle and restored the default English one.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, handleTrackChanges and inPlaceAudioChange
- Verdict: IMMUNE
- Why: An audio selection request is processed by `inPlaceAudioChange`, which changes only the audio lane and selected audio index. It does not mutate the subtitle stream, subtitle queue, external subtitle selection, or subtitle track choice, so the explicit subtitle survives without being recomputed.
- Severity if real: P1 broken feature

### [EXOPLAYER-3639] Disable automatic selection of sideloaded subtitles before prepare
- Link: https://github.com/google/ExoPlayer/issues/3639  State: wontfix
- Mechanism: Sideloaded text with a nonzero selection flag was selected automatically even though the application wanted every subtitle disabled until a user choice. The upstream answer required neutral flags and no preferred language before preparing the player.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerConfig.kt, SubtitleConfig.autoSelect and preferredLanguages; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, pickSubtitle
- Verdict: IMMUNE
- Why: KitePlayer exposes the policy directly before open. Setting `autoSelect=false`, leaving preferred languages empty, and disabling forced auto-selection makes `pickSubtitle` return null while all tracks remain visible for a later explicit `selectTrack` call.
- Severity if real: P2 quality/perf

### [EXOPLAYER-4711] Audio track selection always prefers the first renderer
- Link: https://github.com/google/ExoPlayer/issues/4711  State: closed-fixed
- Mechanism: When MediaCodec and FFmpeg audio renderers both mapped a group, ExoPlayer kept a lower-scoring selection on the first renderer even when the second renderer satisfied the application's constraints better. The fix compared scores across renderers of the same type.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, pickAudio and createAudioDecoder; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, newAudioDecoder
- Verdict: IMMUNE
- Why: KitePlayer has one audio-decoder family on Android: FFmpeg. `platformAudioDecoder` is null and there is no competing MediaCodec audio renderer, so selection is made once over stream metadata and cannot be distorted by renderer ordering.
- Severity if real: P1 broken feature

### [MEDIA3-2803] Extract QuickTime and Nero chapters from MP4 audio books
- Link: https://github.com/androidx/media/issues/2803  State: closed-fixed
- Mechanism: MP4 audio books commonly store chapters as QuickTime chapter tracks or Nero chapter metadata. Without extractor support, applications had to parse the file a second time, and when both forms existed the more complete QuickTime table needed to take precedence.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/kitecodec-core/src/jvmAndAndroidMain/kotlin/io/github/yuroyami/kitecodec/MediaSource.jvm.kt, readChapters; /Users/macbook/StudioProjects/#Kite/KiteCodec/kitecodec-core/src/nativeMain/kotlin/io/github/yuroyami/kitecodec/MediaSource.native.kt, readChapters; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, chapters
- Verdict: IMMUNE
- Why: Both KiteCodec platform assemblies read libavformat's chapter table, and KitePlayer maps every entry into its public `Chapter` model. MP4 chapter parsing belongs to libavformat rather than a second application parser, so supported QuickTime and Nero metadata arrives through the normal source open.
- Severity if real: P1 broken feature

### [EXOPLAYER-2316] Expose ID3 chapter frames in long-form MP3 audio
- Link: https://github.com/google/ExoPlayer/issues/2316  State: closed-fixed
- Mechanism: Podcasts stored chapter titles and time ranges in ID3 `CHAP` and `CTOC` frames. Treating chapter payload as a text track was wrong; the extractor needed to expose structured metadata that applications could use for navigation.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/kitecodec-core/src/jvmAndAndroidMain/kotlin/io/github/yuroyami/kitecodec/MediaSource.jvm.kt, readChapters; /Users/macbook/StudioProjects/#Kite/KiteCodec/kitecodec-core/src/nativeMain/kotlin/io/github/yuroyami/kitecodec/MediaSource.native.kt, readChapters; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/KitePlayer.kt, chapterAt and seekToChapter
- Verdict: IMMUNE
- Why: KitePlayer consumes FFmpeg's structured source chapter table instead of decoding ID3 chapters as subtitles. The table is published in player state and has direct lookup and seek APIs, so chapter information remains navigation metadata.
- Severity if real: P1 broken feature

### [EXOPLAYER-9225] Support MP4 chapter tracks as chapters, not subtitles
- Link: https://github.com/google/ExoPlayer/issues/9225  State: closed-fixed
- Mechanism: A QuickTime `chap` reference can point at timed title and image tracks. Treating the referenced text as an ordinary tx3g subtitle exposes the wrong selectable track and loses the chapter-list relationship.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/kitecodec-core/src/jvmAndAndroidMain/kotlin/io/github/yuroyami/kitecodec/MediaSource.jvm.kt, readChapters; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, chapters and stream conversion
- Verdict: IMMUNE
- Why: KitePlayer gets chapters from the demuxer's chapter table and publishes them separately from `source.streams`. A chapter entry cannot become a selectable subtitle merely because its container representation uses timed text.
- Severity if real: P1 broken feature

### [EXOPLAYER-3903] Add arbitrary search-result markers to the playback time bar
- Link: https://github.com/google/ExoPlayer/issues/3903  State: wontfix
- Mechanism: An application wanted to mark every transcript search result on the time bar, independently of media chapters and ad groups. ExoPlayer suggested extra ad markers or a custom TimeBar because its chapter model did not represent arbitrary application annotations.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerState.kt, Chapter; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/KitePlayer.kt, chapterAt and seekToChapter; kiteplayer-view/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/view/PlayerViewBinding.kt
- Verdict: MISSING-FEATURE
- Why: KitePlayer publishes real container chapters but has no arbitrary timeline-marker model and no built-in time-bar API to render application positions. A client can build its own UI from player position, but cannot feed searchable markers into a standard KitePlayer control.
- Severity if real: P2 quality/perf

### [MEDIA3-3122] Double-speed seek near a short loop end deadlocks audio and video
- Link: https://github.com/androidx/media/issues/3122  State: open
- Mechanism: Near the end of a short repeated item, sped-up audio was too small to start the AudioTrack, the next repetition was withheld until video finished, and video waited for the audio clock to start. Media3 described this as a three-way deadlock and introduced independent per-stream progression.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, finishDecoded and endOfStream; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, handlePlaybackRestart, handleEof, and handleLoop
- Verdict: IMMUNE
- Why: KitePlayer explicitly flushes the TempoStage tail before declaring audio drained, starts playback when each selected lane has any usable output rather than an AudioTrack minimum-duration gate, and completes the current graph before a loop rebuild. It does not require audio, next-item demux, and unfinished video to unlock one another.
- Severity if real: P1 broken feature

### [MEDIA3-2772] Preserve pitch while changing playback speed
- Link: https://github.com/androidx/media/issues/2772  State: closed-fixed
- Mechanism: Media3's speed-changing processor initially coupled tempo and pitch. The accepted use case required a flag that changes duration while preserving recorded pitch rather than a continuously independent pitch curve.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/KitePlayer.kt, setSpeed and setPreservePitch; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/AudioPipeline.kt, speed and preservePitch; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/TempoStage.kt
- Verdict: IMMUNE
- Why: Preserve-pitch is the default KitePlayer law and can be changed before or during playback. True routes rate through the pitch-synchronous tempo stage; false folds rate into resampling and shifts pitch. Both paths turn over on one flushed epoch so samples from different laws are never spliced.
- Severity if real: P1 broken feature

### [MEDIA3-2905] High maximum speed makes AudioTrack allocation fail for many channels
- Link: https://github.com/androidx/media/issues/2905  State: closed-fixed
- Mechanism: Media3 multiplied PCM buffer size by channel count and its 8x maximum speed, producing a roughly 12 MB AudioTrack request for 12-channel audio. Its fallback size was not frame-aligned, so the retry itself failed Android's buffer-size check.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/TempoStage.kt, MIN_SPEED and MAX_SPEED; kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/AudioTrackDriver.kt, PlatformAudioTrackDriver initialization
- Verdict: IMMUNE
- Why: KitePlayer caps speed at 4x, negotiates only mono, stereo, 5.1, or 7.1 and downmixes other counts, then requests exactly `AudioTrack.getMinBufferSize`. It never multiplies the device buffer by maximum playback speed, so the oversized and misaligned retry mechanism is absent.
- Severity if real: P1 broken feature

### [MEDIA3-2038] Audio offload reports a speed change that hardware ignores
- Link: https://github.com/androidx/media/issues/2038  State: wontfix
- Mechanism: Android 15 QPR beta builds temporarily disabled speed control in the offload path but still reported offload active and retained requested playback parameters. Media advanced at 1x even though application state claimed the requested faster rate.
- KitePlayer code checked: kiteplayer-ffmpeg/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/PlatformDecoderSelection.android.kt, platformAudioDecoder; kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/AudioTrackDriver.kt, PlatformAudioTrackDriver; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/AudioPipeline.kt
- Verdict: IMMUNE
- Why: KitePlayer has no compressed audio offload or passthrough. It always decodes to PCM float and applies rate in its own tempo or resampler stage before AudioTrack, so an OEM offload implementation cannot silently reject the speed.
- Severity if real: P1 broken feature

### [EXOPLAYER-10882] A stale renderer-clock update overwrites the user's playback speed
- Link: https://github.com/google/ExoPlayer/issues/10882  State: closed-fixed
- Mechanism: A renderer clock asynchronously reported that requested speed was unsupported. If audio was disabled before that queued callback ran, the standalone clock became master, but the stale renderer update still reset the new user-selected speed to 1x. The fix discarded pending internal updates when a newer speed was set.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, CoreCommand.SetSpeed handling; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, speed; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/VideoPlayback.kt, speed
- Verdict: IMMUNE
- Why: KitePlayer owns one actor-confined `speed` value and writes the next audio and video epochs from the same command. Neither AudioTrack nor a renderer reports an asynchronous corrected speed back into public state, and changing the selected tracks cannot enqueue a stale rate override.
- Severity if real: P1 broken feature

### [EXOPLAYER-801] Mono MP3 plays too fast on Android 4.2 devices
- Link: https://github.com/google/ExoPlayer/issues/801  State: closed-fixed
- Mechanism: Several Android 4.2 devices played mono MP3 at roughly double speed until ExoPlayer corrected its audio output configuration. The failure correlated with mono input sample rates, indicating that decoded sample format and AudioTrack rate or channel configuration had diverged.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, KiteCodecAudioDecoder.receive; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, submitDecoded; kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/AudioTrackDriver.kt, PlatformAudioTrackDriver initialization
- Verdict: IMMUNE
- Why: Every decoded frame carries its actual sample rate and channel count into AudioPlayback, the pipeline resamples to the negotiated sink rate, and AudioTrack is constructed with that same negotiated rate and mask. Mono input cannot be timed as stereo merely because the device decoder returned it.
- Severity if real: P1 broken feature

### [EXOPLAYER-7683] Vendor frame-rate conversion becomes jumpy after a speed change
- Link: https://github.com/google/ExoPlayer/issues/7683  State: open
- Mechanism: Sony and Sharp Android TVs rendered continuously jumpy video even at 1.1x while audio position stayed smooth. Upstream suspected that the vendor decoder's automatic frame-rate conversion was confused by media presentation timestamps advancing at a non-real-time rate.
- KitePlayer code checked: kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/MediaCodecVideoDecoder.kt, packetPts and processReleaseCommands; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/VideoPlayback.kt, speed and schedule
- Verdict: SUSPECT
- Why: KitePlayer queues original media PTS into MediaCodec and accelerates only the timed Surface release schedule. A vendor decoder that internally interprets the relationship between input timestamps and faster releases can silently judder without throwing, so hardware fallback is not activated.
- Severity if real: P2 quality/perf

### [EXOPLAYER-5885] A 60 fps source at 2x exceeds the display and drops frames
- Link: https://github.com/google/ExoPlayer/issues/5885  State: wontfix
- Mechanism: Playing 60 fps media at 2x asks the pipeline to decode and schedule 120 source frames each second on a 60 Hz display. Maintainers noted that dropping about half is the optimal presentation rather than evidence that all frames should be rendered.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/VideoPlayback.kt, schedule and speed; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runVideoSchedule
- Verdict: IMMUNE
- Why: KitePlayer scales wall deadlines by speed and deliberately drops frames that are already too late, while keeping the media clock continuous. It does not promise impossible 120 Hz presentation on a 60 Hz target, and the expected drops are counted rather than treated as a discontinuity.
- Severity if real: P2 quality/perf

### [EXOPLAYER-10865] Audio sink cannot say whether speed adjustment is supported
- Link: https://github.com/google/ExoPlayer/issues/10865  State: open
- Mechanism: Encoded AC-3 output could not apply live-offset speed correction, but the player learned that only after repeatedly sending adjustments that the sink ignored. A capability query was requested so live-speed control would not chase an offset through a no-op output path.
- KitePlayer code checked: kiteplayer-ffmpeg/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/PlatformDecoderSelection.android.kt, platformAudioDecoder; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/AudioPipeline.kt, speed; kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/AudioTrackSink.kt
- Verdict: IMMUNE
- Why: KitePlayer never sends encoded AC-3 to AudioTrack and has no output-owned playback-rate setting. All selected audio is decoded to PCM and speed is applied before the sink, so output capability cannot veto the rate after the core has published it.
- Severity if real: P1 broken feature

### [EXOPLAYER-4228] MOV PCM sample-table rechunking skips data and plays at the wrong speed
- Link: https://github.com/google/ExoPlayer/issues/4228  State: closed-fixed
- Mechanism: ExoPlayer's MP4 rechunking skipped parts of embedded WAV-style PCM data, producing clicks and timing that sounded too fast. The fix corrected extractor sample-table handling rather than asking AudioTrack to reinterpret the stream.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, readPacket and KiteCodecAudioDecoder.receive; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, submitDecoded
- Verdict: IMMUNE
- Why: KitePlayer delegates MOV packet tables and PCM framing to libavformat and FFmpeg, the implementation family explicitly reported to play the supplied camera files correctly. It does not run ExoPlayer's `AtomParsers` rechunking path.
- Severity if real: P1 broken feature

### [EXOPLAYER-7134] Playback speed processing is disabled for float PCM
- Link: https://github.com/google/ExoPlayer/issues/7134  State: closed-fixed
- Mechanism: ExoPlayer bypassed audio processors when decoder output was float PCM to avoid reducing it to 16-bit precision. That also disabled requested speed adjustment and silence processing, even though changing the signal was more important than preserving a no-processing fast path.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, submitDecoded; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/AudioPipeline.kt, process; kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/AudioTrackDriver.kt
- Verdict: IMMUNE
- Why: Float PCM is KitePlayer's native processing and Android sink format. Channel mixing, resampling, TempoStage, and gain all operate on float arrays, so preserving float precision never disables playback speed.
- Severity if real: P1 broken feature

### [EXOPLAYER-2751] Pitch-preserving speed adjustment distorts music
- Link: https://github.com/google/ExoPlayer/issues/2751  State: closed-fixed
- Mechanism: Sonic's time-domain period splicing audibly distorted music at 1.25x through 2x even on fast devices. Upstream fixed one rate and pitch arithmetic error but also classified some distortion as an inherent quality tradeoff of the algorithm family.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/TempoStage.kt, process, findPeriod, overlapDrop, and overlapRepeat; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/AudioPipeline.kt
- Verdict: SUSPECT
- Why: KitePlayer uses its own implementation, so Sonic's exact arithmetic bug is absent, but it uses the same pitch-period overlap and repeat family with no spectral fallback for polyphonic music. The upstream algorithmic quality limit can therefore still present as audible warble or transient smearing.
- Severity if real: P2 quality/perf

### [MEDIA3-1793] Missing color metadata produces device-dependent default color
- Link: https://github.com/androidx/media/issues/1793  State: open
- Mechanism: When a file omitted its color-standard atom, Media3 passed an unset value to MediaCodec. Quest 3 then defaulted to BT.2020 while Quest 2 and other devices defaulted to BT.709, making the same pixels render differently by device.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/kitecodec-core/src/commonMain/kotlin/io/github/yuroyami/kitecodec/ColorInfo.kt, guessFor; /Users/macbook/StudioProjects/#Kite/KiteCodec/kitecodec-core/src/jvmAndAndroidMain/kotlin/io/github/yuroyami/kitecodec/MediaSource.jvm.kt, stream color mapping; kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/MediaCodecVideoDecoder.kt, mediaCodecFormat
- Verdict: IMMUNE
- Why: KiteCodec replaces wholly unspecified color by a deterministic resolution-based convention: SD uses its matching BT.601 family and HD uses BT.709. KitePlayer then sends those explicit hints to MediaCodec, so a headset cannot choose BT.2020 merely because the atom was absent.
- Severity if real: P2 quality/perf

### [EXOPLAYER-7998] NVIDIA SHIELD flashes green when players swap video Surfaces
- Link: https://github.com/google/ExoPlayer/issues/7998  State: open
- Mechanism: Repeatedly moving two Rec.709 players between visible views triggered a reproducible NVIDIA graphics sync failure and green frames. The codec kept running and emitted no ExoPlayer error, so releasing and rebuilding the player was the only observed recovery.
- KitePlayer code checked: kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/MediaCodecSurfaceTarget.kt, update; kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/MediaCodecVideoDecoder.kt, switchTo; kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/AndroidSurfaceVideoRenderer.kt, attach and present
- Verdict: SUSPECT
- Why: KitePlayer fences Surface versions and catches explicit `setOutputSurface` failures, but it still performs the same in-place MediaCodec Surface switch. A vendor that accepts the call and silently emits green buffers does not set `fatalFailure`, so neither private-Surface recovery nor software fallback is triggered.
- Severity if real: P2 quality/perf

### [MEDIA3-3183] MP3 gapless duration includes encoder delay and padding
- Link: https://github.com/androidx/media/issues/3183  State: closed-fixed
- Mechanism: LAME Xing and Info headers carried encoder delay and padding into decoded-audio trimming, but the seek-map duration still used the untrimmed MPEG frame count. For CBR Info files that exposed a source duration longer than the audio that would actually play and disrupted a gapless transition. Pull request 3198 fixed both duration and average bitrate using the gapless duration.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, handleEof and handleQueueAdvance; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, drain; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, duration and KiteCodecAudioDecoder.receive
- Verdict: MISSING-FEATURE
- Why: KitePlayer has no gapless queue handoff to which a trimmed source duration can be applied. It drains the old audio path to `Ended`, tears down the session, and only then opens the next item. FFmpeg may expose or consume MP3 trim metadata internally, but the next item cannot be queued continuously behind the trimmed tail.
- Severity if real: P1 broken feature

### [MEDIA3-2576] Opus pre-skip still clicks at a gapless item boundary
- Link: https://github.com/androidx/media/issues/2576  State: open
- Mechanism: Opus carries a pre-skip that must be honored at decoded-sample precision, and both tracks must share an uninterrupted output timeline. The report reproduced a short volume glitch with MediaCodec, the FFmpeg extension, and offload, while the LibOpus extension was specifically noted to handle pre-skip information.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, handleQueueAdvance and runOpen; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, finishDecoded and drain; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, KiteCodecAudioDecoder.receive
- Verdict: MISSING-FEATURE
- Why: Each queue item gets a new OpenSession and AudioPlayback path only after the previous path has fully drained. Even if FFmpeg removes Opus pre-skip correctly inside each decode, KitePlayer cannot join the last valid sample of one item to the first valid sample of the next without a device stop and reopen.
- Severity if real: P1 broken feature

### [EXOPLAYER-8594] Aliased C2 MP3 decoder shifts the gapless boundary by 529 samples
- Link: https://github.com/google/ExoPlayer/issues/8594  State: wontfix
- Mechanism: C2 and OMX MP3 decoders removed the first 529 samples but assigned output timestamps differently. Some Android 10 devices exposed a C2 implementation under the OMX alias, so ExoPlayer skipped its C2 timestamp correction, mistook an extra output buffer for the next stream, and drained trimming at the wrong boundary.
- KitePlayer code checked: kiteplayer-ffmpeg/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/PlatformDecoderSelection.android.kt, platformAudioDecoder; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, KiteCodecAudioDecoder
- Verdict: IMMUNE
- Why: Android audio decoding never uses MediaCodec in KitePlayer: `platformAudioDecoder` returns null and every selected audio stream is decoded by FFmpeg. No OMX or C2 decoder name is inspected, and no input-to-output timestamp match is conditioned on that name, so the alias-specific 529-sample correction cannot be omitted.
- Severity if real: P1 broken feature

### [EXOPLAYER-3470] Ogg Vorbis gapless trimming depends on final granule position
- Link: https://github.com/google/ExoPlayer/issues/3470  State: open
- Mechanism: Each completed Ogg page identifies the last decoded PCM sample by granule position. Correct playback must discard inferred samples before zero at the start and decoded samples beyond the final granule at the end, even though the end trim is not known when the initial format is published.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, handleQueueAdvance and runOpen; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, finishDecoded; /Users/macbook/StudioProjects/#Kite/KiteCodec/buildSrc/src/main/kotlin/BuildFFmpegTask.kt, sharedCoreArgs
- Verdict: MISSING-FEATURE
- Why: The wide FFmpeg read side can decode Ogg Vorbis and may apply a container granule trim, but KitePlayer exposes no item-level encoder-delay or end-padding contract and has no continuous next-item audio path. Queue advance waits for a full drain and rebuild, so properly trimmed Vorbis items still cannot meet gaplessly.
- Severity if real: P1 broken feature

### [EXOPLAYER-3475] MP3 gapless click can come from trimming the wrong sample counts
- Link: https://github.com/google/ExoPlayer/issues/3475  State: wontfix
- Mechanism: ExoPlayer kept one AudioTrack and observed nonzero MP3 encoder delay and padding, yet the reporter still heard clicks. Upstream narrowed the remaining mechanism to incorrect start or end trim counts and proposed comparing the exact PCM samples written on both sides of the transition, but could not reproduce it.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, handleQueueAdvance and runOpen; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, drain; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, KiteCodecAudioDecoder.receive
- Verdict: MISSING-FEATURE
- Why: KitePlayer neither publishes MP3 encoder-delay and end-padding counts to its audio core nor preserves one AudioTrack across queue items. There is therefore no player-owned sample-count boundary to verify or correct, and its mandatory drain plus reopen already prevents a gapless transition even when FFmpeg trims each file.
- Severity if real: P1 broken feature

### [EXOPLAYER-7560] TS video needs automatic access-unit and non-IDR keyframe detection
- Link: https://github.com/google/ExoPlayer/issues/7560  State: open
- Mechanism: ExoPlayer's TS extractor used cheap assumptions unless callers enabled `FLAG_DETECT_ACCESS_UNITS` and `FLAG_ALLOW_NON_IDR_KEYFRAMES`. Streams without dependable access-unit delimiters or IDR-marked random access then buffered with a black picture, so maintainers proposed expensive detection until reliable AUDs were observed.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/buildSrc/src/main/kotlin/BuildFFmpegTask.kt, sharedCoreArgs; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, readPacket and KiteCodecVideoDecoder.receive
- Verdict: IMMUNE
- Why: KitePlayer does not packetize H.264 or H.265 TS elementary streams with ExoPlayer's flag-gated `TsExtractor`. Its wide FFmpeg demuxer, parser, and decoder set determines access units and usable frames without an application flag, so neither missing Exo flag can leave its video lane permanently empty.
- Severity if real: P1 broken feature

### [EXOPLAYER-7873] A PMT update adds an audio track after TS playback has opened
- Link: https://github.com/google/ExoPlayer/issues/7873  State: open
- Mechanism: A transport-stream program map changed during playback and introduced an additional audio elementary stream. Treating the initial PMT as the permanent track set left otherwise valid media silent even though Android MediaPlayer detected the later track.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, byIndex, streams, selectStreams, and reselectStreams; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, buildSession
- Verdict: MISSING-FEATURE
- Why: `KiteCodecSource.streams` and its `byIndex` map are snapshots of `source.streams` taken during open. Stream selection resolves only those original indices, and PlayerState has no event or refresh path for a demuxer-discovered stream. A later PMT audio track therefore cannot become visible or selectable.
- Severity if real: P1 broken feature

### [EXOPLAYER-3141] Live FLV changes audio or video format without reconnecting
- Link: https://github.com/google/ExoPlayer/issues/3141  State: wontfix
- Mechanism: A live FLV sent a new AVC sequence header or audio configuration inside the same HTTP response. ExoPlayer's payload readers emitted format only once, so the decoder kept the old parameters and failed after the in-band change.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, KiteCodecVideoDecoder.receive and KiteCodecAudioDecoder.receive; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, submitDecoded; kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/MediaCodecVideoDecoder.kt, processReleaseCommands
- Verdict: IMMUNE
- Why: Software video frames carry their decoded geometry and pixel format rather than reusing a one-time FLV format. Audio receive compares every frame's sample rate, channels, and format, publishes a format change, and `submitDecoded` rebuilds its conversion pipeline on that buffer. Android hardware video also consumes MediaCodec output-format-change notifications.
- Severity if real: P1 broken feature

### [EXOPLAYER-1360] Matroska display dimensions must become pixel aspect ratio
- Link: https://github.com/google/ExoPlayer/issues/1360  State: closed-fixed
- Mechanism: Matroska can declare display width and height separately from coded pixel dimensions, with either display dimension defaulting to its coded counterpart when absent. Ignoring those fields stretches the image because square pixels are assumed.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/kitecodec-core/src/jvmAndAndroidMain/kotlin/io/github/yuroyami/kitecodec/MediaSource.jvm.kt, readStreams; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, StreamInfo.toPlayerStream; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerState.kt, VideoSize
- Verdict: IMMUNE
- Why: FFmpeg's Matroska demuxer derives `sample_aspect_ratio`, KiteCodec reads that rational from the stream, and `toPlayerStream` carries it into the public VideoSize. Rendering therefore receives the display-shape correction rather than assuming the coded width and height are square-pixel display dimensions.
- Severity if real: P2 quality/perf

### [EXOPLAYER-11075] MPEG-TS needs DTS LBR and DTS:X descriptor and frame support
- Link: https://github.com/google/ExoPlayer/issues/11075  State: closed-fixed
- Mechanism: DTS Express and DTS:X transport streams require recognizing their PSI PMT descriptors, finding extension-substream frame boundaries, and parsing the profile and audio format before a decoder can be selected.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/buildSrc/src/main/kotlin/BuildFFmpegTask.kt, sharedCoreArgs; /Users/macbook/StudioProjects/#Kite/KiteCodec/vendor/ffmpeg/libavformat/mpegts.c, DTS descriptor mappings; /Users/macbook/StudioProjects/#Kite/KiteCodec/vendor/ffmpeg/libavcodec/dca_parser.c, LBR and DTS profile parsing
- Verdict: IMMUNE
- Why: KiteCodec pins FFmpeg 8.0 and builds the entire decoder, demuxer, and parser read side. Its MPEG-TS demuxer maps DTS descriptors to `AV_CODEC_ID_DTS`, and the linked DCA parser and decoder include LBR and DTS-HD MA X profile handling. KitePlayer does not maintain a narrower Java TS descriptor list.
- Severity if real: P1 broken feature

### [EXOPLAYER-6406] Core playback needs a FLAC extractor independent of an extension decoder
- Link: https://github.com/google/ExoPlayer/issues/6406  State: closed-fixed
- Mechanism: Android O MR1 exposed a FLAC MediaCodec decoder, but ExoPlayer's only FLAC container reader lived in an extension that also decoded. A Java extraction-only path was needed so a platform decoder could consume native FLAC without bundling the extension decoder.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/buildSrc/src/main/kotlin/BuildFFmpegTask.kt, sharedCoreArgs; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, newAudioDecoder; kiteplayer-ffmpeg/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/PlatformDecoderSelection.android.kt, platformAudioDecoder
- Verdict: IMMUNE
- Why: KiteCodec builds the full FFmpeg demuxer and decoder read side, including native FLAC, and KitePlayer deliberately uses FFmpeg for Android audio instead of selecting a platform decoder. FLAC extraction and decoding are therefore present together without an optional ExoPlayer extension or Android-version gate.
- Severity if real: P1 broken feature

### [EXOPLAYER-1447] Support Opus packets inside an Ogg container
- Link: https://github.com/google/ExoPlayer/issues/1447  State: closed-fixed
- Mechanism: ExoPlayer supported Vorbis and FLAC in Ogg and Opus in Matroska, but format sniffing and packet initialization did not connect Ogg's Opus mapping to the Opus decoder.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/buildSrc/src/main/kotlin/BuildFFmpegTask.kt, sharedCoreArgs; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, readPacket and newAudioDecoder
- Verdict: IMMUNE
- Why: KiteCodec compiles FFmpeg's complete demuxer, parser, and decoder read side. Ogg stream discovery returns the codec id FFmpeg parsed, and `newAudioDecoder` opens that codec generically, so there is no separate KitePlayer container-to-codec compatibility table that can omit Opus-in-Ogg.
- Severity if real: P1 broken feature

### [EXOPLAYER-9332] Invalid 18-byte `nclx` color atom crashes MP4 parsing
- Link: https://github.com/google/ExoPlayer/issues/9332  State: closed-fixed
- Mechanism: Some device cameras wrote an 18-byte MP4 `colr` atom labeled `nclx`, one byte shorter than the format requires. ExoPlayer's Java atom parser read the missing full-range byte without first bounding the box and threw ArrayIndexOutOfBoundsException; it later added a compatibility workaround for these files.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/vendor/ffmpeg/libavformat/mov.c, mov_read_colr; /Users/macbook/StudioProjects/#Kite/KiteCodec/kitecodec-core/src/jvmAndAndroidMain/kotlin/io/github/yuroyami/kitecodec/MediaSource.jvm.kt, readStreams; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, StreamInfo.toPlayerStream
- Verdict: IMMUNE
- Why: KitePlayer never indexes an MP4 atom through ExoPlayer's `ParsableByteArray`. FFmpeg's `mov_read_colr` reads the parameter type and color fields through bounded AVIO operations, so the one-byte-short atom cannot produce the reported Java array overrun. Unusable color values are left unspecified and then follow KiteCodec's deterministic color fallback.
- Severity if real: P0 crash/dataloss

### [EXOPLAYER-9168] MP4 edit list skips to a falsely marked HEVC random-access sample
- Link: https://github.com/google/ExoPlayer/issues/9168  State: wontfix
- Mechanism: Applying an edit list advanced past early samples to the next sample that the MP4 marked as random access. That sample still depended on earlier HEVC data, so the extracted stream began with three corrupt green frames; disabling edit-list processing avoided the bad landing.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/vendor/ffmpeg/libavformat/mov.c, mov_fix_index; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, readPacket and KiteCodecVideoDecoder.receive
- Verdict: IMMUNE
- Why: Pinned FFmpeg's `mov_fix_index` deliberately retains the closest prerequisite keyframe and every consequent sample needed to decode through the edit boundary. It marks frames outside the edit for discard after decode rather than removing their dependencies from the compressed stream, so the falsely declared first in-edit random-access sample is not decoded in isolation.
- Severity if real: P2 quality/perf

### [EXOPLAYER-2120] Amlogic AVC decoder cannot flush after end of stream
- Link: https://github.com/google/ExoPlayer/issues/2120  State: closed-fixed
- Mechanism: `OMX.amlogic.avc.decoder.awesome` entered a vendor EOS state that its flush path could not recover from. Seeking after `STATE_ENDED` flushed that same codec instance, leaving video frozen until ExoPlayer added the decoder to its release-and-recreate EOS workaround list.
- KitePlayer code checked: kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/MediaCodecVideoDecoder.kt, isSupported and flush; kiteplayer-ffmpeg/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/PlatformDecoderSelection.android.kt, platformDecoderSelection; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runSeek
- Verdict: IMMUNE
- Why: The report is confined to Android 4.4, while KitePlayer's direct MediaCodec video path is supported only on Android Q and newer. That device therefore uses FFmpeg software video and never constructs or flushes the affected Amlogic OMX component after EOS.
- Severity if real: P1 broken feature

### [MEDIA3-660] HLS end list never reaches MediaCodec as an EOS buffer
- Link: https://github.com/androidx/media/issues/660  State: open
- Mechanism: After a low-latency HLS broadcast appended `EXT-X-ENDLIST`, Media3 stopped loading but did not add `BUFFER_FLAG_END_OF_STREAM` to the codec input. The renderer remained in its output-drain loop and the public player stayed playing forever instead of reaching ended.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runDemux, runVideoDecode, runAudioDecode, and handleEof; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, readPacket
- Verdict: IMMUNE
- Why: When the source read returns null, KitePlayer marks every selected packet queue EOS. Each decoder worker then retries `send(null)` until accepted and drains output, independently of a Media3 HLS chunk flag. `handleEof` also bounds the audio tail and device drain, so a completed demux cannot leave the player polling one missing codec EOS flag forever.
- Severity if real: P1 broken feature

### [MEDIA3-2544] AAC playlist transition triggers an unexpected audio timestamp exception on API 29 to 33
- Link: https://github.com/androidx/media/issues/2544  State: open
- Mechanism: Reusing one AudioSink across AAC items on older C2 decoder builds exposed an output timestamp jump, including a reported ring-buffer overflow on the API 29 emulator. Media3 compared the new timestamp against the previous item's expected continuous value and raised `UnexpectedDiscontinuityException` mid-playlist.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, handleQueueAdvance, runOpen, and teardownSession; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/KotlinAudioRing.kt, clear; kiteplayer-ffmpeg/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/PlatformDecoderSelection.android.kt, platformAudioDecoder
- Verdict: IMMUNE
- Why: KitePlayer does not reuse decoder or sink timestamp state across queue items. It drains and tears down the prior OpenSession, clears the audio ring and its timestamp segments, and opens the next item with FFmpeg audio rather than C2. No expected timestamp from item N is compared with item N plus one.
- Severity if real: P1 broken feature

### [MEDIA3-2592] HLS discontinuity sequence can drive public media position negative
- Link: https://github.com/androidx/media/issues/2592  State: open
- Mechanism: When old discontinuity tags left a live playlist and `EXT-X-DISCONTINUITY-SEQUENCE` did not preserve the same absolute sequence for retained segments, Media3 associated the wrong timestamp adjustment with new samples. Playback jumped the gap, then reported an increasingly negative media position and could fail behind the live window.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/vendor/ffmpeg/libavformat/hls.c, parse_playlist and hls_read_packet; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, TimestampMapper and timestampsMayJump; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/KotlinAudioRing.kt, recordTimestamp and publishAnchor; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, currentPosition and handlePlaybackTime
- Verdict: SUSPECT
- Why: The pinned HLS parser has no discontinuity-sequence epoch model and `hls_read_packet` forwards timestamps from its playlist subdemuxers. TimestampMapper subtracts only the opening origin, while the audio ring preserves a later backward jump as a new segment and `currentPosition` publishes it without a zero clamp. A refreshed playlist whose retained media resumes below the opening epoch can therefore drive the public position negative.
- Severity if real: P1 broken feature

### [MEDIA3-1483] Two pending non-internal discontinuities crash playback-state publication
- Link: https://github.com/androidx/media/issues/1483  State: closed-fixed
- Mechanism: A renderer error from a future playlist period could request an automatic transition after another seek or transition discontinuity was already pending in the same playback-info update. An assertion allowed only one non-internal reason, so the second update crashed; the landed fix sent pending updates before adding the error transition.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, drainCommands, handleWorkerOutcome, handleQueueAdvance, and emitEvent; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerEvent.kt
- Verdict: IMMUNE
- Why: KitePlayer has no batched PlaybackInfoUpdate or generic position-discontinuity reason slot. One actor serializes commands, worker outcomes, queue advancement, and immediate event emission; a seek completion and an item end remain separate events rather than competing writes to a single asserted field.
- Severity if real: P0 crash/dataloss

### [MEDIA3-323] Alternate rendition waits forever on a discontinuity no master track shares
- Link: https://github.com/androidx/media/issues/323  State: open
- Mechanism: An HLS text or audio rendition contained an extra discontinuity sequence absent from the master timestamp track. Its media chunk called a shared timestamp-adjuster wait, but no master chunk could ever initialize that sequence, so preparation remained in buffering forever.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, selectStreams and readPacket; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, awaitInitialFill and runDemux
- Verdict: IMMUNE
- Why: KitePlayer opens one FFmpeg packet reader for the selected stream set and has no per-rendition `TimestampAdjuster` or master-source initialization barrier. A subtitle-only discontinuity cannot park its loader waiting for a sequence another track will never announce; the demuxer either returns packets, EOF, or an explicit read failure.
- Severity if real: P1 broken feature

### [EXOPLAYER-6671] Android 10 audio decoder slows down after an early flush
- Link: https://github.com/google/ExoPlayer/issues/6671  State: closed-fixed
- Mechanism: An Android 10 platform bug was triggered when an audio MediaCodec was flushed shortly after configuration, as repeated pause and seek activity could do. Output then played slowly until another transition; ExoPlayer worked around affected builds by releasing and recreating the decoder instead of flushing it.
- KitePlayer code checked: kiteplayer-ffmpeg/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/PlatformDecoderSelection.android.kt, platformAudioDecoder; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, KiteCodecAudioDecoder.flush; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, flushDecoders and clearBuffers
- Verdict: IMMUNE
- Why: KitePlayer does not create an Android audio MediaCodec on any API level. Pause leaves the FFmpeg decoder untouched, and seek flushes the FFmpeg StreamDecoder, so the Android 10 codec state that required release instead of flush is unreachable.
- Severity if real: P1 broken feature

### [EXOPLAYER-398] Samsung MP3 decoder repeats audio after timestamp discontinuities
- Link: https://github.com/google/ExoPlayer/issues/398  State: closed-fixed
- Mechanism: Specific Samsung MP3 MediaCodec implementations on API 16 and 17 mishandled an output-buffer path, repeatedly reporting timestamp discontinuities and replaying about one second of audio. ExoPlayer added decoder and API-specific workarounds as affected variants were identified.
- KitePlayer code checked: kiteplayer-ffmpeg/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/PlatformDecoderSelection.android.kt, platformAudioDecoder; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, KiteCodecAudioDecoder; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/KotlinAudioRing.kt, recordTimestamp
- Verdict: IMMUNE
- Why: MP3 is decoded by FFmpeg rather than the named Samsung OMX components, so the vendor output-buffer behavior and its API-specific workaround table do not participate. If packet PTS really jumps, KitePlayer's ring records a new timestamp segment without replaying already-consumed PCM.
- Severity if real: P1 broken feature

### [EXOPLAYER-7030] Unmarked adaptive-stream timestamp jump buffers forever
- Link: https://github.com/google/ExoPlayer/issues/7030  State: wontfix
- Mechanism: Independent adaptive lanes encountered a large PTS jump with no discontinuity marker, leaving one renderer not ready while the loader still reported ample buffered media. Upstream discussed detecting the unexpected sample timestamp, surfacing a typed error, and rebuilding timestamp adjustment, but the remaining recovery design was closed without completion.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, TimestampMapper and timestampsMayJump; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PacketQueue.kt, isReady; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/KotlinAudioRing.kt, recordTimestamp; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/SyncLaw.kt, targetDelayUs
- Verdict: SUSPECT
- Why: KitePlayer tolerates real jumps in the audio ring and stops treating a video offset above ten seconds as ordinary drift, but it neither validates cross-lane sample discontinuities nor starts a fresh demux timestamp epoch. An unmarked jump that advances only one lane can still make compressed queues look ready while audio and video wait on incompatible positions, with no typed recovery trigger.
- Severity if real: P1 broken feature

### [EXOPLAYER-11043] Rockchip decoder reports an unexpected audio timestamp
- Link: https://github.com/google/ExoPlayer/issues/11043  State: wontfix
- Mechanism: A Rockchip platform decoder produced an audio timestamp far enough from the sink's predicted position to trip ExoPlayer's discontinuity check. Maintainers could not reproduce it in the demo and the reporter identified the vendor decoder as the likely source.
- KitePlayer code checked: kiteplayer-ffmpeg/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/PlatformDecoderSelection.android.kt, platformAudioDecoder; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/KotlinAudioRing.kt, driftWithinTolerance and recordTimestamp
- Verdict: IMMUNE
- Why: KitePlayer never selects a Rockchip or other platform audio MediaCodec. FFmpeg output timestamps enter an audio ring that opens a new timestamp segment when continuity differs beyond tolerance rather than throwing an AudioSink exception, so the reported vendor timestamp cannot abort playback through that check.
- Severity if real: P1 broken feature

### [EXOPLAYER-6601] FLAC bit-depth change reuses an incompatible audio sink
- Link: https://github.com/google/ExoPlayer/issues/6601  State: closed-fixed
- Mechanism: A playlist moved from 24-bit FLAC PCM to 16-bit PCM, but ExoPlayer kept the decoder and failed to reconfigure the audio sink for the new bit depth. The sink interpreted samples using the old format, producing persistent static until another format change or player reset.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, submitDecoded; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/AudioPipeline.kt, matches and rebuiltFor; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, handleQueueAdvance and runOpen
- Verdict: IMMUNE
- Why: Across queue items KitePlayer opens a new audio path after tearing down the old one. Within one stream, every AudioBuffer carries its decoded sample format, rate, and channels; `submitDecoded` rebuilds the conversion pipeline on the first differing buffer and always writes the negotiated float format to the sink. Old PCM bit depth is never reused to interpret new samples.
- Severity if real: P1 broken feature

### [MEDIA3-3081] DASH total buffered duration is inflated during a cross-period seek
- Link: https://github.com/androidx/media/issues/3081  State: closed-fixed
- Mechanism: A live DASH seek changed the loading period before the renderer position caught up. Converting that stale renderer position with the new period's offset produced a large negative local position and a phantom positive buffered duration, so LoadControl stopped loading despite empty renderers.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PacketQueue.kt, offer and bufferedUs; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, overBudget and runSeek
- Verdict: IMMUNE
- Why: KitePlayer has no DASH period holder or renderer offset conversion. A packet queue's buffered duration is the sum of durations on packets actually retained in that queue, and seek flushes every queue to a new generation. A stale position from another period cannot manufacture minutes of compressed buffer.
- Severity if real: P1 broken feature

### [MEDIA3-1541] Next or previous item can remain loading after a video geometry change
- Link: https://github.com/androidx/media/issues/1541  State: open
- Mechanism: On Pixel Tensor devices, moving through HLS items whose aspect ratio changes can leave Codec2 returning buffers the client no longer owns while replacement decoders repeatedly initialize. Minimizing the app changes the Surface lifecycle and lets playback resume.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, handleQueueAdvance, runOpen, and teardownSession; kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/MediaCodecSurfaceTarget.kt, update and withSnapshotCompletion; kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/MediaCodecVideoDecoder.kt, close
- Verdict: IMMUNE
- Why: KitePlayer does not adapt one codec across queue items. It reaches Ended, closes the whole prior session, and opens a fresh decoder for the next item. Surface snapshots are versioned, and close fences all outstanding releases before the codec and its private Surface are destroyed, so an old geometry buffer cannot be accepted by the new decoder.
- Severity if real: P1 broken feature

### [MEDIA3-2298] An invalid alternate audio track exhausts the byte target before useful media is buffered
- Link: https://github.com/androidx/media/issues/2298  State: open
- Mechanism: A source with an incomplete second audio track reached the target byte allocation with less than 500 milliseconds of playable media. Loading then stopped on the size threshold even though the selected renderers had too little data, and preparation could terminate before all declared tracks completed.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runDemux, overBudget, relieveInterleaving, and pruneInactiveSwitchCaches
- Verdict: SUSPECT
- Why: Every inactive audio and subtitle cache counts toward KitePlayer's global byte cap, but interleaving relief examines only selected queues. A malformed unselected track can therefore own most retained bytes while no selected queue is eligible to be identified as the hoarder. Demux then waits for selected consumption even when the selected lane has not reached a healthy time buffer.
- Severity if real: P1 broken feature

### [MEDIA3-2611] Skippable decoder outputs can leave playback buffering forever
- Link: https://github.com/androidx/media/issues/2611  State: open
- Mechanism: Media3 counted skipped decoder outputs only when a later non-skipped output arrived. A damaged FLAC stream that yielded only outputs marked `shouldBeSkipped` therefore had compressed input but no renderable output, no accounted skip progression, and no terminal readiness decision.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, KiteCodecAudioDecoder.receive; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, everySelectedStreamReady, runAudioDecode, and handleEof
- Verdict: IMMUNE
- Why: KitePlayer has no deferred `shouldBeSkipped` counter or renderer readiness based only on compressed input. Opening requires decoded audio in the ring, while FFmpeg either emits a real frame, drains at source EOS, or throws a decoder failure. A stream that produces no PCM cannot be declared ready merely because packets were consumed.
- Severity if real: P1 broken feature

### [MEDIA3-3210] Tiny AudioTrack underruns are surfaced as full player buffering
- Link: https://github.com/androidx/media/issues/3210  State: closed-fixed
- Mechanism: Media3 1.9.2 began using AudioTrack underrun state in renderer readiness. With a device buffer near 250 milliseconds, a momentary output underrun changed the whole player to BUFFERING even when upstream data was available; the fix increased the buffer and made readiness more lenient.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, handleBuffering, demuxerRanShort, outputStarved, and publishProgressAndStats; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, underruns
- Verdict: IMMUNE
- Why: KitePlayer reports ring underruns as warnings and statistics, but entering Buffering requires two independent live facts: a selected compressed queue has run empty and decoded output is empty. A device-side underrun by itself cannot change player state while the demux queues remain healthy.
- Severity if real: P2 quality/perf

### [MEDIA3-3052] VOD to low-latency HLS transition churns decoders on timeline updates
- Link: https://github.com/androidx/media/issues/3052  State: closed-fixed
- Mechanism: While a following low-latency HLS item was preprepared, frequent playlist updates changed its estimated default start. Each change invalidated the pending period and repeatedly released and recreated decoders. The fix froze that estimate once reading began.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, handleQueueAdvance, runOpen, and buildSession
- Verdict: IMMUNE
- Why: KitePlayer does not preprepare the following queue item or construct a pending live period. It opens that item only after the current session reaches Ended, so timeline refreshes for an unopened item cannot invalidate a decoder or restart a preparation loop.
- Severity if real: P1 broken feature

### [MEDIA3-3105] Delta HLS updates omit inherited EXT-X-MAP initialization data
- Link: https://github.com/androidx/media/issues/3105  State: wontfix
- Mechanism: The reported leading EXT-X-GAP failure was traced to delta playlist updates whose new chunks did not inherit the applicable `EXT-X-MAP`. Media chunks after the skipped region consequently lacked the fragmented MP4 initialization section needed to extract and decode them.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/vendor/ffmpeg/libavformat/hls.c, parse_playlist and EXT-X-MAP handling; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, readPacket
- Verdict: MISSING-FEATURE
- Why: The pinned HLS parser handles `EXT-X-MAP`, but it has no `EXT-X-SKIP` or `SKIPPED-SEGMENTS` parser at all. It can consume a full playlist, but it cannot merge a delta update or inherit the map from segments omitted by that update. Robust delta HLS is therefore missing rather than an unproved packet-queue bug.
- Severity if real: P1 broken feature

### [MEDIA3-1152] HTTP retry policy delays a terminal timeout for minutes
- Link: https://github.com/androidx/media/issues/1152  State: open
- Mechanism: An eight-second HTTP read timeout did not bound the user-visible failure because the loader retried the request several times with increasing delay. The report eventually received an error only after roughly three minutes.
- KitePlayer code checked: kiteplayer-network/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/network/KtorMediaIo.kt, read and openAt; kiteplayer-ffmpeg/src/jvmAndNativeMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/BlockingMediaIo.blocking.kt, read
- Verdict: IMMUNE
- Why: KitePlayer has no Media3 loader retry or backoff loop. KtorMediaIo returns bytes, EOF, or a failed channel from one request, and a thrown failure reaches the demuxer without being multiplied into minutes of retries. The blocking bridge's handling of a custom zero-byte MediaIo is a different hypothetical mechanism and does not establish this HTTP policy bug.
- Severity if real: P1 broken feature

### [MEDIA3-796] An empty alternate TS audio PID prevents preparation
- Link: https://github.com/androidx/media/issues/796  State: open
- Mechanism: A transport stream declared a second AC-3 audio track in its program map but carried no samples on that PID. Media3 waited for every declared track during preparation, so the source never became ready.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, buildSession, awaitInitialFill, everySelectedStreamReady, and selectedQueues
- Verdict: IMMUNE
- Why: KitePlayer creates caches for alternate audio tracks, but opening and buffering wait only on the selected video and audio queues and their decoded output. An empty unselected PID holds zero bytes and never participates in readiness. It cannot block the selected tracks merely because the PMT declared it.
- Severity if real: P1 broken feature

### [MEDIA3-3362] Partial HLS samples survive a failed variant download
- Link: https://github.com/androidx/media/issues/3362  State: open
- Mechanism: After a segment timed out partway through transfer, a down-switch excluded its media chunk without purging the partial samples already appended to the sample queue. Replacement samples then followed stale partial data, making PTS move backward; related reset logic could also reload from an old live position.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/vendor/ffmpeg/libavformat/hls.c, read_data_continuous; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runDemux and handleTrackChanges
- Verdict: MISSING-FEATURE
- Why: The pinned HLS reader retries or skips a failed segment within the same playlist. PlaybackCore has no automatic video rendition down-switch and no compressed-sample splice transaction, so it never appends a replacement representation behind partially consumed samples in one queue. The exact contamination path is absent because adaptive failed-chunk replacement itself is missing.
- Severity if real: P1 broken feature

### [EXOPLAYER-9553] Detect buffering with no renderable samples despite a reported full buffer
- Link: https://github.com/google/ExoPlayer/issues/9553  State: open
- Mechanism: Inaccurate HLS segment durations accumulated until the source's calculated chunk position led actual sample timestamps by more than the maximum buffer. Loading stopped because the declared buffer looked full, while both renderers had no sample to consume and existing stuck detection saw a nonempty duration.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PacketQueue.kt, offer, bufferedUs, and isReady; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, everySelectedStreamReady and handleBuffering
- Verdict: IMMUNE
- Why: KitePlayer never derives compressed buffer health from HLS `EXTINF` positions. It sums durations attached to packets actually returned by the demuxer and additionally requires decoded audio or video output before declaring readiness. A full playlist-duration estimate cannot hide empty renderers because no such estimate enters the core.
- Severity if real: P1 broken feature

### [EXOPLAYER-6366] Tunneled video with an undisclosed output frame buffers forever
- Link: https://github.com/google/ExoPlayer/issues/6366  State: open
- Mechanism: In tunneling mode the application never dequeued video output buffers, so renderer readiness could not observe a frame already pending inside the platform tunnel. When the source queue emptied, video reported not ready and blocked audio even though the tunneled pair could have continued.
- KitePlayer code checked: kiteplayer-ffmpeg/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/PlatformDecoderSelection.android.kt, platformAudioDecoder; kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/MediaCodecVideoDecoder.kt, pumpOutput; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, everySelectedStreamReady
- Verdict: IMMUNE
- Why: KitePlayer has no tunneled or encoded-passthrough A/V path. Audio is FFmpeg-decoded PCM, and the video worker explicitly dequeues every MediaCodec output and places a frame into its visible schedule before readiness. No undisclosed platform-owned video frame must be inferred from an empty source queue.
- Severity if real: P1 broken feature

### [EXOPLAYER-8952] Demuxed audio initializes the HLS clock before later video
- Link: https://github.com/google/ExoPlayer/issues/8952  State: open
- Mechanism: Separate HLS audio and video playlists advertised the same program date but their samples began about one second apart. Whichever segment first initialized the shared timestamp adjustment chose the origin; if audio won, its early samples played while the first video frame remained frozen.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/vendor/ffmpeg/libavformat/hls.c, hls_read_packet; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, TimestampMapper and KiteCodecAudioDecoder.receive; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, currentPosition and masterClockKind
- Verdict: SUSPECT
- Why: The pinned HLS reader buffers from every needed playlist and returns the packet with the lowest DTS. A separate audio playlist that begins one second earlier is therefore exposed first. KitePlayer has no cross-lane startup clip to the later first sample and makes audio master as soon as PCM arrives, so sound can advance while video waits at its later first PTS.
- Severity if real: P1 broken feature

### [EXOPLAYER-8959] An HLS EXT-X-GAP longer than the buffer target stalls loading
- Link: https://github.com/google/ExoPlayer/issues/8959  State: open
- Mechanism: ExoPlayer calculated buffer extent from the HLS playlist even though a long `EXT-X-GAP` contributed no samples. Once that declared gap exceeded the maximum buffer, loading stopped before reaching the first real sample after it and playback remained buffering.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PacketQueue.kt, offer and bufferedUs; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, overBudget and runDemux
- Verdict: IMMUNE
- Why: An HLS gap cannot consume KitePlayer's duration budget unless the demuxer returns packets with real packet durations for it. With no packets, queue duration and bytes do not grow, so the demux worker remains allowed to read forward to the first post-gap sample or to a source failure. The playlist's declared gap length is not consulted above demux.
- Severity if real: P1 broken feature

### [EXOPLAYER-6896] Empty final HLS media segment leaves subtitles waiting for a timestamp master
- Link: https://github.com/google/ExoPlayer/issues/6896  State: open
- Mechanism: Seeking to the end reached a final TS segment containing no media samples. The media lane therefore never initialized the shared HLS timestamp adjuster, while the longer subtitle lane waited on that initialization forever and kept the player in BUFFERING instead of ENDED.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, selectStreams and readPacket; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runDemux and handleEof
- Verdict: IMMUNE
- Why: KitePlayer has one selected-stream packet reader and no subtitle loader waiting on a media-owned timestamp-adjuster latch. An empty final segment makes FFmpeg continue, return EOF, or throw. EOF marks all selected queues together and `handleEof` drains them to Ended, so a subtitle lane cannot keep a hidden loader alive.
- Severity if real: P1 broken feature

### [EXOPLAYER-10936] A live TS that temporarily omits its selected audio remains buffering
- Link: https://github.com/google/ExoPlayer/issues/10936  State: open
- Mechanism: Windows Miracast omits AAC packets entirely while the sender is silent, then resumes the same PID when sound returns. Requiring every selected renderer to have data makes video wait for a live audio queue that has no samples and no EOS.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, awaitInitialFill, everySelectedStreamReady, demuxerRanShort, and handleBuffering
- Verdict: SUSPECT
- Why: KitePlayer also requires decoded output for every selected lane. A declared and selected live AAC track with no packets is neither ready nor ended, so initial fill times out and later playback enters Buffering when its ring empties even if video packets keep arriving. There is no policy to temporarily demote an absent live audio lane and rejoin it when samples resume.
- Severity if real: P1 broken feature

### [EXOPLAYER-4727] Progressive playlist items need early preparation to publish all durations
- Link: https://github.com/google/ExoPlayer/issues/4727  State: open
- Mechanism: A combined playlist timeline could not show a complete seek range until each progressive item had opened far enough for its extractor to discover duration. Only the current and immediately buffered item were prepared, so later unknown durations disabled the multi-item time bar.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, openQueue, handleQueueAdvance, and runOpen; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerState.kt
- Verdict: MISSING-FEATURE
- Why: KitePlayer stores future MediaItems but does not open or probe them before they become current. PlayerState exposes only the current source duration, with no aggregate playlist timeline or per-item discovered duration table. A full queue seek bar therefore cannot be derived inside the player.
- Severity if real: P2 quality/perf

### [EXOPLAYER-5020] Preserve already buffered future items across a playlist seek
- Link: https://github.com/google/ExoPlayer/issues/5020  State: open
- Mechanism: ExoPlayer could prebuffer a following period, but seeking discarded buffers belonging to other playlist items even when the target item's retained buffer remained valid. Those byte ranges then had to be downloaded again.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runSeek, handleQueueAdvance, runOpen, and OpenSession
- Verdict: MISSING-FEATURE
- Why: KitePlayer owns only one OpenSession and never prebuffers a future queue item. Advancing tears that session down and opens the next source, while seek preserves or flushes queues only inside the current item. There is no future-period cache whose valid bytes could survive a cross-item seek.
- Severity if real: P2 quality/perf

### [MEDIA3-1245] HLS timestamp adjustment drops the first DTS:X sync interval in tunnel mode
- Link: https://github.com/androidx/media/issues/1245  State: open
- Mechanism: HLS fMP4 timestamp adjustment caused the first DTS:X Profile 2 sync frame to be omitted on a tunneled direct-output path. Non-sync frames could not decode until the next sync frame two seconds later, while ordinary MediaCodec decode and DASH did not lose the start.
- KitePlayer code checked: kiteplayer-ffmpeg/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/PlatformDecoderSelection.android.kt, platformAudioDecoder; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, newAudioDecoder and TimestampMapper
- Verdict: IMMUNE
- Why: KitePlayer has no DTS direct passthrough or tunneled audio path. Every DTS packet is decoded to PCM by FFmpeg, and its timestamp mapper shifts reported times without dropping packets based on negativity. The sync frame therefore reaches the software decoder instead of being removed before a receiver sees it.
- Severity if real: P1 broken feature

### [MEDIA3-2249] HE-AAC edit-list trim is not reflected in non-tunneled A/V timing
- Link: https://github.com/androidx/media/issues/2249  State: open
- Mechanism: An MP4 edit instructed the player to discard the first 1,656 decoded HE-AAC samples. Media3's non-tunneled path neither removed those PCM samples nor shifted video scheduling to the trimmed audio origin, so audio played late relative to picture.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/vendor/ffmpeg/libavformat/mov.c, mov_fix_index; /Users/macbook/StudioProjects/#Kite/KiteCodec/vendor/ffmpeg/libavformat/demux.c, read_frame_internal; /Users/macbook/StudioProjects/#Kite/KiteCodec/vendor/ffmpeg/libavcodec/decode.c, discard_samples; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, KiteCodecAudioDecoder.receive
- Verdict: IMMUNE
- Why: Pinned FFmpeg carries the edit's `skip_samples` count as packet side data into the decoder, where `discard_samples` physically removes the specified whole or partial leading PCM samples and updates frame timestamps. KitePlayer's audio pipeline therefore receives data already trimmed to the same origin used for scheduling.
- Severity if real: P1 broken feature

### [MEDIA3-3109] Discontinuous Opus timestamps make the audio clock drop video cyclically
- Link: https://github.com/androidx/media/issues/3109  State: wontfix
- Mechanism: A file with 120 millisecond Opus packets repeatedly jumped its audio timestamps. Because audio was the master clock, each jump moved playback time and the video renderer dropped frames to catch up, producing a cyclic stutter; remuxing the timestamps removed it.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, KiteCodecAudioDecoder.receive; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/KotlinAudioRing.kt, recordTimestamp and publishAnchor; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, currentPosition
- Verdict: SUSPECT
- Why: KitePlayer intentionally records an unexpected audio PTS as a new ring segment and publishes its anchor without validating the jump against packet duration. Audio normally remains master, so each discontinuity can move the clock and make SyncLaw classify queued video as late. No remux or damaged-timestamp smoothing occurs in the core.
- Severity if real: P2 quality/perf

### [MEDIA3-3311] LL-HLS recovery reloads audio from an old seek position
- Link: https://github.com/androidx/media/issues/3311  State: closed-fixed
- Mechanism: After playlist requests recovered, partial low-latency chunks had been replaced by a full segment. Media3 removed those chunks, emptied the buffer, and incorrectly reset loading to the last seek position, so old audio was read again while video remained at the live edge. The fix retained the position where chunks were dropped.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/vendor/ffmpeg/libavformat/hls.c, parse_playlist; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runDemux
- Verdict: MISSING-FEATURE
- Why: The pinned HLS parser has no `EXT-X-PART`, `EXT-X-PRELOAD-HINT`, `EXT-X-SKIP`, or rendition-report handling. It cannot perform the partial-chunk replacement and recovery transaction that triggered this bug. Low-latency HLS recovery is missing, including the position rule needed when parts are replaced by a full segment.
- Severity if real: P1 broken feature

### [EXOPLAYER-6225] Detect DTS-HD inside Matroska before choosing output
- Link: https://github.com/google/ExoPlayer/issues/6225  State: closed-fixed
- Mechanism: Matroska labels core DTS and DTS-HD with the same container codec identifier. Correct passthrough selection therefore has to inspect an audio access unit for the DTS-HD extension rather than trusting the track declaration alone.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/buildSrc/src/main/kotlin/BuildFFmpegTask.kt, sharedCoreArgs; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, newAudioDecoder; kiteplayer-ffmpeg/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/PlatformDecoderSelection.android.kt, platformAudioDecoder
- Verdict: IMMUNE
- Why: KitePlayer never selects an encoded DTS passthrough format from the Matroska track label. Its wide FFmpeg parser and decoder read side inspects and decodes the actual bitstream to PCM, and Android audio MediaCodec selection is disabled. A receiver capability decision cannot be made from the ambiguous container label.
- Severity if real: P1 broken feature

### [EXOPLAYER-9995] The first video DTS normalizes later audio PTS below zero
- Link: https://github.com/google/ExoPlayer/issues/9995  State: open
- Mechanism: A DVB transport stream carried audio about one second earlier than video. The first video decode timestamp initialized the common adjustment, so the later-arriving but numerically earlier audio PTS became negative and early audio was discarded, delaying startup.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/vendor/ffmpeg/libavformat/demux.c, update_stream_timings; /Users/macbook/StudioProjects/#Kite/KiteCodec/native/kitecodec-c/src/helpers_format.c, ffkmp_fmt_start_time; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, TimestampMapper
- Verdict: IMMUNE
- Why: The pinned demux core chooses the minimum start across non-text streams, not the first video DTS. KiteCodec exposes that format start and TimestampMapper applies it to both lanes. Earlier audio therefore maps to zero and video maps later, so the video-based negative-audio normalization mechanism is absent.
- Severity if real: P1 broken feature

### [EXOPLAYER-9588] Audio output capability loss should recover from passthrough to PCM
- Link: https://github.com/google/ExoPlayer/issues/9588  State: open
- Mechanism: Connecting Bluetooth headphones removed DTS passthrough capability while an encoded stream was active. Retrying with the stale capability selected the same now-invalid format, so playback failed until the player was rebuilt or software decoding was forced.
- KitePlayer code checked: kiteplayer-ffmpeg/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/PlatformDecoderSelection.android.kt, platformAudioDecoder; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, newAudioDecoder; kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/AudioTrackSink.kt, open
- Verdict: IMMUNE
- Why: KitePlayer always decodes DTS and other audio to float PCM before opening AudioTrack. It never caches or submits a receiver-specific encoded passthrough format, so connecting a PCM-only Bluetooth route does not invalidate the selected media codec or require track reselection.
- Severity if real: P1 broken feature

### [MEDIA3-3337] HLS program date changes can move playback below zero forever
- Link: https://github.com/androidx/media/issues/3337  State: open
- Mechanism: Overlapping live HLS playlists assigned different `EXT-X-PROGRAM-DATE-TIME` values to the same segment. Media3 trusted each server date as a fresh playlist origin, shifted the timeline backward by about 36 seconds, and remained READY at a negative position instead of reconciling the overlap.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/vendor/ffmpeg/libavformat/hls.c, parse_playlist; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, TimestampMapper and readPacket
- Verdict: IMMUNE
- Why: The pinned HLS parser does not consume `EXT-X-PROGRAM-DATE-TIME`, so a server-only change to that tag cannot replace the media timestamp origin or shift packet PTS. KitePlayer maps only demuxed media timestamps and never derives public position from the program date, which removes the reported association error.
- Severity if real: P1 broken feature

### [MEDIA3-3009] A live HLS audio lane ends while video remains available
- Link: https://github.com/androidx/media/issues/3009  State: wontfix
- Mechanism: Audio stopped producing around a fixed point in a long HLS stream, repeatedly toggling renderer readiness. Because audio remained the clock source and was not marked as having read EOS, Media3 did not switch to its standalone clock and video could not continue.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, masterClockKind, everySelectedStreamReady, demuxerRanShort, and handleBuffering
- Verdict: SUSPECT
- Why: KitePlayer also makes a selected audio lane master until that lane is actually ended. A live audio PID that silently stops has an empty queue but no EOS, so it remains selected, starves the ring, and moves the whole player to Buffering even while video packets continue. No standalone clock takeover is keyed to prolonged audio absence.
- Severity if real: P1 broken feature

### [MEDIA3-2835] A media source publishes its prepared timeline from the wrong thread
- Link: https://github.com/androidx/media/issues/2835  State: wontfix
- Mechanism: A custom source did preparation on a background coroutine but dispatched its final `refreshSourceInfo` callback to the main thread instead of ExoPlayer's playback thread. The cross-thread state mutation intermittently stopped loading while public position interpolation continued.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runOpen, drainCommands, and pass; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/spi/MediaSource.kt
- Verdict: IMMUNE
- Why: KitePlayer's source SPI has no later timeline-refresh callback for an implementation to invoke from an arbitrary dispatcher. `open` is a suspending request that returns one source object, after which one actor owns session construction, command handling, and state publication. The reported callback thread split is absent.
- Severity if real: P0 crash/dataloss

### [MEDIA3-1744] Precise clipped video transitions need decoder prewarming
- Link: https://github.com/androidx/media/issues/1744  State: closed-fixed
- Mechanism: A clipped item starting between keyframes had to initialize a decoder and process decode-only frames during the transition, freezing the prior picture for about half a second. Media3's renderer-prewarming option prepared the replacement codec early and removed that delay.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, handleQueueAdvance, runOpen, createVideoDecoder, and awaitLanding
- Verdict: MISSING-FEATURE
- Why: KitePlayer neither preopens the following item nor prewarms its decoder. It waits for the prior session to reach Ended, tears it down, opens the next source, seeks and decodes forward from a keyframe, then presents. Precise non-keyframe starts therefore necessarily expose decoder and discard latency between items.
- Severity if real: P2 quality/perf

### [MEDIA3-558] Garbage bytes mimic a valid FLAC frame header
- Link: https://github.com/androidx/media/issues/558  State: closed-fixed
- Mechanism: ExoPlayer's FLAC extractor found a valid-looking sync word, frame number, and even CRC inside encoded payload. It treated that false header as a far-future frame, queued a timestamp beyond file duration, and made playback oscillate between READY and BUFFERING. The fix also validated the following subframe header bits.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/buildSrc/src/main/kotlin/BuildFFmpegTask.kt, sharedCoreArgs; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, readPacket and KiteCodecAudioDecoder.receive
- Verdict: IMMUNE
- Why: KitePlayer does not scan FLAC frame headers in a Kotlin extractor. Its complete FFmpeg demuxer, parser, and decoder read side validates framing and either decodes, skips corrupt input with codec diagnostics, or raises a source or decoder failure. The specific Exo header test that accepted payload as frame 1,992 is not present.
- Severity if real: P1 broken feature

### [MEDIA3-1071] HTTP byte range at exact EOF can be mistaken for a fatal 416
- Link: https://github.com/androidx/media/issues/1071  State: open
- Mechanism: Media3's DataSource contract treats a read beginning exactly at resource length as immediate EOF, while HTTP returns 416 for that range. Correct adaptation needs the actual resource length to distinguish exact EOF from a genuinely out-of-range request, which some servers do not include in the response.
- KitePlayer code checked: kiteplayer-network/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/network/KtorMediaIo.kt, open, read, and openAt
- Verdict: SUSPECT
- Why: When `open` learns a resource size, `read` returns minus one at `position >= size` before issuing a ranged request, so known-size exact EOF is safe. When a 206 probe reports an unknown total, a later logical-EOF read can still call `openAt`, reject the server's 416 as an error, and has no length with which to distinguish exact EOF from an invalid overshoot.
- Severity if real: P1 broken feature

### [MEDIA3-188] Close an idle progressive HTTP connection and reopen it on demand
- Link: https://github.com/androidx/media/issues/188  State: open
- Mechanism: Progressive loading filled its target buffer and stopped reading but deliberately kept the HTTP response open for later reuse. On Wear OS that pinned a high-bandwidth network and drained battery, while an unread HTTP/2 response could also block unrelated requests on the connection.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/CachingMediaIo.kt, read and refill; kiteplayer-ffmpeg/src/jvmAndNativeMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/BlockingMediaIo.blocking.kt, read and close; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, close
- Verdict: MISSING-FEATURE
- Why: KitePlayer keeps the opened MediaIo and FFmpeg source for the whole session. Buffer fullness pauses demux reads but starts no idle deadline, closes no upstream response, and provides no reopen-at-current-byte wrapper. Network ownership is released only when the source or session closes.
- Severity if real: P2 quality/perf

### [MEDIA3-869] RTSP disconnect incorrectly returns to READY with an unset buffer
- Link: https://github.com/androidx/media/issues/869  State: open
- Mechanism: After airplane mode broke RTSP, Media3 briefly buffered and then reported READY with an invalid buffered position, no error, and no recovery. The application had to poll the impossible `TIME_UNSET` value to detect the dead session.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/buildSrc/src/main/kotlin/BuildFFmpegTask.kt, sharedCoreArgs; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runOpen and awaitInitialFill
- Verdict: MISSING-FEATURE
- Why: The shipped FFmpeg profile disables UDP and RTP, which drops the dependent RTSP demuxers. KitePlayer cannot open the reported session, so RTSP disconnect recovery and its public buffering contract are missing rather than protected by a readiness guard.
- Severity if real: P1 broken feature

### [MEDIA3-2401] A no-retry load decision swallows the terminal player error
- Link: https://github.com/androidx/media/issues/2401  State: closed-fixed
- Mechanism: Returning `TIME_UNSET` from Media3's load-error policy correctly prohibited another playlist retry but failed to forward the saved IOException as terminal. The loader stopped and the player remained buffering forever until upstream fixed the no-retry branch.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runDemux and handleWorkerOutcome; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecMediaBackend.kt, open
- Verdict: IMMUNE
- Why: KitePlayer exposes no retry-policy sentinel that can mean both stop retrying and suppress failure. A source read exception exits the demux worker and is handled as terminal playback failure. There is no saved load error waiting for a separate policy callback to publish it.
- Severity if real: P1 broken feature

### [MEDIA3-3154] Multiple fragmented MP4 sidx boxes report only the first fragment duration
- Link: https://github.com/androidx/media/issues/3154  State: closed-fixed
- Mechanism: An MP4 assembled from HLS fragments held one `sidx` per three-second fragment. Media3 1.10 read only the first index to avoid overwriting seek history, locking duration to three seconds; the fix progressively merged later indices while preserving earlier seek points.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/vendor/ffmpeg/libavformat/mov.c, mov_read_sidx; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, duration
- Verdict: IMMUNE
- Why: Pinned FFmpeg parses every top-level `sidx` during header opening rather than retaining only the first. Reopening the exact local seekable sample through the pinned stack reports 84.407 seconds before KiteCodecSource snapshots duration, so the immutable player value already contains the full file rather than one three-second fragment.
- Severity if real: P1 broken feature

### [MEDIA3-3287] Cross-thread player state corruption yields an invalid period index
- Link: https://github.com/androidx/media/issues/3287  State: wontfix
- Mechanism: During rapid source replacement, a variable intended for single-thread access was observed in an inconsistent timeline state. The current period UID was absent from a nonempty playlist, producing index minus one and an array crash when stuck detection requested that period. A maintainer linked a fix that restored single-thread ownership.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, drainCommands, pass, handleBuffering, and publishSnapshot
- Verdict: IMMUNE
- Why: One PlaybackCore actor serializes source replacement, buffering decisions, queue transitions, and snapshot publication. Workers return outcomes through mailboxes instead of mutating the playlist or public current item. There is no separate stuck detector indexing a concurrently replaced period timeline.
- Severity if real: P0 crash/dataloss

### [MEDIA3-3254] An inactive HLS variant schedules one more playlist refresh
- Link: https://github.com/androidx/media/issues/3254  State: closed-fixed
- Mechanism: A delayed playlist-refresh task captured that a variant was active, then the player switched variants while it slept. The task did not recheck activity when its deadline arrived, so under fast ABR changes old variants A and B refreshed alongside current variant C. The fix canceled or revalidated the delayed load.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/vendor/ffmpeg/libavformat/hls.c, reload_playlist, recheck_discard_flags, and hls_read_packet; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, readPacket
- Verdict: IMMUNE
- Why: The pinned reader has no delayed refresh task that captures an old active flag. Refresh runs synchronously in packet reading, `reload_playlist` recomputes `playlist_needed` immediately before opening or reloading, and `recheck_discard_flags` closes the input and clears `needed` when a playlist becomes inactive. A sleeping stale task cannot issue the extra refresh.
- Severity if real: P2 quality/perf

### [MEDIA3-1191] Crash on playback in onAudioCapabilitiesChanged
- Link: https://github.com/androidx/media/issues/1191  State: open
- Mechanism: A shared Renderer format-support query on the main Looper initialized DefaultAudioSink there. Playback later registered an AudioTrack routing callback on the playback Looper, and the sink asserted when that callback arrived on a different Looper from the one captured during the capability probe.
- KitePlayer code checked: kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/AudioTrackDriver.kt, PlatformAudioTrackDriver; kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/AudioTrackSink.kt, open
- Verdict: IMMUNE
- Why: KitePlayer exposes no RendererCapabilities or shared format-support probe that can initialize one sink from an unrelated query thread. Its AudioTrack driver and sink also register no routing listener, AudioCapabilitiesReceiver, or captured-Looper assertion. The two-stage cross-Looper lifecycle that caused the crash does not exist.
- Severity if real: P0 crash/dataloss

### [MEDIA3-726] Repeated source replacement leaves OkHttp reads spuriously timed out
- Link: https://github.com/androidx/media/issues/726  State: open
- Mechanism: Repeated `setMediaItem` and prepare calls canceled loaders by interrupting their threads. OkHttp's HTTP/2 read path could retain or observe that interruption as a SocketTimeoutException on a later live request, leaving the same player unable to recover until process restart.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, open and close; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runOpen and teardownSession
- Verdict: IMMUNE
- Why: KitePlayer does not use OkHttpDataSource, Media3 Loader, or Java thread interruption for source replacement. It cancels coroutine workers, closes the old FFmpeg source, and constructs a new source object. No OkHttp stream or interrupted Loader thread is reused across items.
- Severity if real: P1 broken feature

### [EXOPLAYER-10892] HLS preparation waits for samples from every advertised format
- Link: https://github.com/google/ExoPlayer/issues/10892  State: wontfix
- Mechanism: Without chunkless preparation, ExoPlayer read media chunks to discover tracks and waited for samples matching all formats advertised by the master playlist. A stream whose declared track layout did not materialize remained at position zero, while deriving tracks directly from the master avoided that sample barrier.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, streams, selectStreams, and readPacket; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, buildSession and everySelectedStreamReady
- Verdict: IMMUNE
- Why: KitePlayer snapshots FFmpeg's discovered track list and then waits only for the streams actually selected into the session. Advertised but unselected formats do not participate in readiness, and no second HLS preparation mode changes that rule. An absent alternate lane cannot hold opening at zero.
- Severity if real: P1 broken feature

### [EXOPLAYER-9945] Pause network loading indefinitely without converting it to an error
- Link: https://github.com/google/ExoPlayer/issues/9945  State: wontfix
- Mechanism: An application deliberately returned false from LoadControl on mobile data and wanted the empty player to wait until Wi-Fi returned. ExoPlayer interpreted an empty, nonloading buffer as an internal deadlock and failed, requiring a wrapper DataSource or a later prepare call.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerConfig.kt, BufferConfig; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runDemux and handleBuffering
- Verdict: MISSING-FEATURE
- Why: KitePlayer exposes buffer sizes but no application gate for pausing demux reads based on network policy, and Buffering has no explicit waiting-for-network reason. A custom source can suspend or return zero, but there is no resume contract or public state distinguishing deliberate network hold from a stalled source.
- Severity if real: P2 quality/perf

### [EXOPLAYER-9672] The next item's audio renderer starts before its video period
- Link: https://github.com/google/ExoPlayer/issues/9672  State: open
- Mechanism: At a period transition, newly enabled renderers were started immediately as joining. A sparse current video had already read its last samples, so the following item's audio began early, became the media clock, advanced time into the next item, and skipped the remaining sparse video.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, handleQueueAdvance, runOpen, buildSession, and masterClockKind
- Verdict: IMMUNE
- Why: KitePlayer never enables a following item's renderer inside the current session. Only after all current queues, decoders, scheduled frames, audio tail, and sink drain reach Ended does it tear down and open the next item. A future audio clock cannot advance the current item's position.
- Severity if real: P1 broken feature

### [EXOPLAYER-9319] Re-concatenated clips flush a continuous audio decoder
- Link: https://github.com/google/ExoPlayer/issues/9319  State: open
- Mechanism: Consecutive clips from one compressed source were logically continuous, but every period boundary flushed MediaCodec and ramped output down and up. Keeping the decoder without reconfiguration removed the audible glitch, although upstream could not prove that reuse safe for arbitrary sources.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, handleQueueAdvance, teardownSession, and runOpen; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/AudioPlayback.kt, drain
- Verdict: MISSING-FEATURE
- Why: KitePlayer has no clip composition model or continuity token. Even two adjacent ranges of the same URI are separate queue items: the old decoder, AudioPlayback, ring, and sink are drained and closed before a new set opens. The core cannot preserve one decoded PCM timeline across that boundary.
- Severity if real: P1 broken feature

### [EXOPLAYER-3526] Fragmented MP4 gapless metadata and edit lists are incomplete
- Link: https://github.com/google/ExoPlayer/issues/3526  State: open
- Mechanism: ExoPlayer's fragmented MP4 extractor did not parse the `udta` gapless metadata and only partly handled edit-list timeline offsets. It derived negative initial samples and inserted a gap between otherwise continuous fragmented MP4 playlist items.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/vendor/ffmpeg/libavformat/mov.c, fragmented MP4 edit and metadata parsing; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, handleQueueAdvance and runOpen
- Verdict: MISSING-FEATURE
- Why: FFmpeg may parse more of each file's metadata than the old Exo extractor, but KitePlayer still drains and closes the entire output path between queue items. It exposes no encoder-delay or end-padding boundary contract and cannot join valid samples continuously even when both fragments are individually trimmed.
- Severity if real: P1 broken feature

### [EXOPLAYER-10421] Splice a lower DASH representation into a partly consumed segment
- Link: https://github.com/google/ExoPlayer/issues/10421  State: open
- Mechanism: A player can cancel a slow high-quality chunk, but if its decoder already consumed part of that chunk, replacement data must start at an earlier keyframe and discard forward before joining the existing sample queue. ExoPlayer supported this splice for HLS but not DASH or SmoothStreaming.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerConfig.kt; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, streams, selectStreams, and readPacket
- Verdict: MISSING-FEATURE
- Why: KitePlayer has no representation-level ABR controller, in-flight segment cancellation hook, or compressed-sample splice API. FFmpeg exposes the selected elementary stream as one packet sequence, so the core cannot replace the unconsumed tail of a segment with another representation.
- Severity if real: P2 quality/perf

### [EXOPLAYER-10946] RTSP connect needs an application-configurable socket timeout
- Link: https://github.com/google/ExoPlayer/issues/10946  State: wontfix
- Mechanism: Creating a socket directly with host and port used the platform connect timeout, which could block RTSP preparation for about two minutes when the endpoint was unavailable. Supplying a SocketFactory that called `connect` with an explicit timeout surfaced failure promptly.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/buildSrc/src/main/kotlin/BuildFFmpegTask.kt, sharedCoreArgs; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/MediaItem.kt; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecMediaBackend.kt, open
- Verdict: MISSING-FEATURE
- Why: KitePlayer's MediaItem has generic FFmpeg open options but no typed RTSP socket factory or connect-timeout setting, and its core cannot preempt a native open that is blocked inside the platform socket. Applications have no portable way to impose a short RTSP connect deadline.
- Severity if real: P1 broken feature

### [EXOPLAYER-8593] Conflicting H.264 reorder metadata silently wedges the platform decoder
- Link: https://github.com/google/ExoPlayer/issues/8593  State: wontfix
- Mechanism: One stream sent two SPS units with different `num_reorder_frames` values. Android's C2 software decoder reset on the change but then produced no frames and no error, leaving ExoPlayer buffering indefinitely until the platform team diagnosed the inconsistent bitstream.
- KitePlayer code checked: kiteplayer-output/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/output/MediaCodecConfiguration.kt, findHardwareDecoders; kiteplayer-ffmpeg/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/PlatformDecoderSelection.android.kt, platformDecoderSelection; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, KiteCodecVideoDecoder.receive
- Verdict: IMMUNE
- Why: KitePlayer's Android platform path filters for hardware-accelerated decoders and does not select the affected C2 software implementation as its fallback. When hardware is unavailable, the same H.264 packets go through FFmpeg software decode, not the silent Android SPS-reset path.
- Severity if real: P1 broken feature

### [EXOPLAYER-2233] Integer HLS segment durations accumulate minutes of seek error
- Link: https://github.com/google/ExoPlayer/issues/2233  State: open
- Mechanism: Long demuxed HLS playlists rounded every `EXTINF` to an integer. The cumulative playlist time drifted minutes away from ID3 sample time, so a time seek chose a distant segment; without iteratively correcting from the observed timestamp, playback either remained buffering or played A/V badly out of sync.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/vendor/ffmpeg/libavformat/hls.c, parse_playlist EXTINF handling; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, seekToKeyframe
- Verdict: IMMUNE
- Why: The pinned parser reads the fractional `EXTINF` value with `atof` and stores it in microseconds. It does not round every segment to an integer second before accumulating the seek timeline, so the source of the reported minutes-long error is absent. Generic HLS seek accuracy remains dependent on the demuxer, but not on this integer-duration bug.
- Severity if real: P1 broken feature

### [EXOPLAYER-7780] Encoder restart resets DASH PTS inside one period
- Link: https://github.com/google/ExoPlayer/issues/7780  State: wontfix
- Mechanism: Restarting a live encoder reset sample timestamps to zero without starting a new DASH period. The renderer position remained near the old multi-hour epoch while new buffers returned to zero, violating the period's monotonic timeline and leaving playback buffering. Upstream required a new period for the new epoch.
- KitePlayer code checked: kiteplayer-network/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/network/dash/DashMediaIo.kt, Dash.mediaItemFor; kiteplayer-network/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/network/dash/DashManifest.kt, DashManifestParser.segmentPlan
- Verdict: MISSING-FEATURE
- Why: The reported trigger is specifically a dynamic live DASH epoch reset inside one period. KitePlayer's static first-tier DASH adapter rejects dynamic manifests and supports only one fixed period, so it cannot enter that state. This receipt does not establish the same root for TS, HLS, or another demux path, and dynamic epoch reconciliation remains an unsupported DASH feature.
- Severity if real: P1 broken feature

### [MEDIA3-3161] HLS load fallback indexes a shorter runtime selection with a full variant index
- Link: https://github.com/androidx/media/issues/3161  State: closed-fixed
- Mechanism: HLS load-error fallback counted variants using an initialization selection but called `isTrackExcluded` on a shorter runtime selection. A missing media chunk then indexed beyond that shorter array and crashed instead of surfacing the HTTP failure.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, selectStreams and readPacket; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, handleWorkerOutcome
- Verdict: IMMUNE
- Why: KitePlayer has no Java HLS variant-selection arrays or load fallback loop above FFmpeg. A selected stream is resolved by stable stream index, and an HTTP or demux read failure exits the worker as a typed playback failure. No full-variant ordinal is applied to a shorter runtime list.
- Severity if real: P0 crash/dataloss

### [MEDIA3-1574] When tunneling is enabled, seeking to the end causes playback to be stuck, not reaching STATE_ENDED
- Link: https://github.com/androidx/media/issues/1574  State: open
- Mechanism: A seek exactly to duration queued video from the last keyframe but no audio sample or audio EOS. In tunneled playback the video codec needed the audio path to release those frames, so its input slots filled and neither decoder nor player reached end of stream.
- KitePlayer code checked: kiteplayer-ffmpeg/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/PlatformDecoderSelection.android.kt, platformDecoderSelection and platformAudioDecoder; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runSeek, runVideoDecode, and handleEof
- Verdict: IMMUNE
- Why: KitePlayer has no tunneled audio and video path. Android hardware selection covers video only, while audio is decoded to PCM independently. At source EOF each selected decoder worker retries its own in-band null drain signal, and `handleEof` joins the independent results, so video input is never held waiting for an AudioTrack tunnel association.
- Severity if real: P1 broken feature

### [MEDIA3-1062] Audio stops playing, the player does not stop nor report an error
- Link: https://github.com/androidx/media/issues/1062  State: open
- Mechanism: A corrupt MP3 VBRI header declared 3,769,067 bytes although the file contained 13,097,088. Media3's VbriSeeker treated that false boundary near 2:18 as end of input, stopped AudioTrack, and left the public position advancing while valid later frames remained.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, readPacket and KiteCodecAudioDecoder.receive; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runDemux and handleEof
- Verdict: IMMUNE
- Why: KitePlayer has no VBRI byte-boundary seeker in its player layer. Its libavformat reader continues returning packets until the actual reader reaches EOF, and the audio decoder counts the PCM samples that FFmpeg emits. The issue report itself showed ffprobe traversing all 18,290 frames despite the bad VBRI size, which is the implementation family used here.
- Severity if real: P1 broken feature

### [MEDIA3-1301] Progressive MP4 with a tail moov fails when a ranged response ends early
- Link: https://github.com/androidx/media/issues/1301  State: wontfix
- Mechanism: The extractor efficiently reopened HTTP near a tail moov. The server promised 1,906,924 bytes in Content-Range but returned EOF after 1,589, so the required atom read failed instead of falling back to one linear pass through the file.
- KitePlayer code checked: kiteplayer-network/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/network/KtorMediaIo.kt, read and openAt; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/CachingMediaIo.kt, read and seek; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, seekToKeyframe
- Verdict: SUSPECT
- Why: A seekable Ktor reader also reopens at a requested byte with Range, and its streaming body failure is propagated through the pipe. CachingMediaIo only serves bytes it already has and otherwise propagates that read result. There is no retry mode that abandons the short tail range and linearly downloads from byte zero, so the same broken server promise can still make a valid tail moov unavailable.
- Severity if real: P1 broken feature

### [MEDIA3-284] In-progress DASH recording is declared ended before appended segments reach the known duration
- Link: https://github.com/androidx/media/issues/284  State: closed-fixed
- Mechanism: DefaultDashChunkSource assumed that a period with a declared duration was complete. A recorder could keep appending segments to such a dynamic period, so loading the last segment in one manifest snapshot falsely ended the period; the fix also required its last available segment to reach the period duration.
- KitePlayer code checked: kiteplayer-network/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/network/dash/DashMediaIo.kt, Dash.manifest, Dash.mediaItemFor, and DashMediaIo.read; kiteplayer-network/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/network/dash/DashManifest.kt, DashManifestParser.segmentPlan
- Verdict: MISSING-FEATURE
- Why: The current DASH tier fetches exactly one MPD, expands one immutable segment plan, and returns EOF after that finite URL list. It never refreshes a dynamic manifest or appends newly published segments, so an in-progress recording stops at the initial snapshot before the upstream fixed predicate can even be applied.
- Severity if real: P1 broken feature

### [MEDIA3-1441] Dynamic to static DASH transition ends before buffered start-over content
- Link: https://github.com/androidx/media/issues/1441  State: open
- Mechanism: A start-over MPD changed to static and reset Period start plus presentationTimeOffset. That made the same period timestamps name different content, while the player retained its old timestamp mapping; after the current buffer emptied it declared ended instead of flushing and rebasing onto the static timeline.
- KitePlayer code checked: kiteplayer-network/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/network/dash/DashMediaIo.kt, Dash.manifest and Dash.mediaItemFor; kiteplayer-network/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/network/dash/DashManifest.kt, DashManifestParser.parse and segmentPlan
- Verdict: MISSING-FEATURE
- Why: KitePlayer's first DASH tier takes one manifest snapshot and has no update, live-to-static transition, or presentation-time epoch transaction. It cannot play this start-over use case through the transition today. A future refreshing tier must either reject a changed presentationTimeOffset or flush buffered segments and preserve the user's equivalent content position under a new mapping.
- Severity if real: P1 broken feature

### [MEDIA3-2757] HLS declares audio that its media segments do not contain
- Link: https://github.com/androidx/media/issues/2757  State: wontfix
- Mechanism: Chunkless preparation trusted a multivariant playlist that advertised video and AAC. The chunks contained video only, so no audio SampleQueue could bind to the declared group; loading a segment first discovered the actual one-track shape and played video without audio.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/vendor/ffmpeg/libavformat/hls.c, hls_read_header and update_streams_from_subdemuxer; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, selectableStreams
- Verdict: IMMUNE
- Why: The pinned HLS header path probes and opens an actual media segment, then creates main AVStreams only from the subdemuxer's discovered streams. It does not create an AAC queue merely because the master playlist advertises AAC. KiteCodecSource therefore cannot select a phantom audio lane that the segment never contained.
- Severity if real: P1 broken feature

### [MEDIA3-2299] LL-HLS quality switch retains an unresolved preload hint or creates a splice gap
- Link: https://github.com/androidx/media/issues/2299  State: closed-fixed
- Mechanism: An unresolved preload-hint sample survived a format switch and could never be finalized or discarded. A second path switched after non-independent parts had already been read, so splicing the new representation from the segment start left a data gap and froze video.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/buildSrc/src/main/kotlin/BuildFFmpegTask.kt, sharedCoreArgs; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, selectStreams and readPacket; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, handleTrackChanges
- Verdict: MISSING-FEATURE
- Why: KitePlayer delegates a basic HLS URI as one opaque demux source and exposes no representation set, bandwidth adaptation, preload-hint ownership, or compressed-sample splice transaction. It therefore cannot promise LL-HLS adaptive switching. A future tier must discard unresolved hints on format changes and refuse switches whose replacement data can no longer splice before the read cursor.
- Severity if real: P1 broken feature

### [MEDIA3-1002] Pending CMAF emsg metadata uses a negative sample offset
- Link: https://github.com/androidx/media/issues/1002  State: closed-fixed
- Mechanism: Several emsg payloads could be buffered with sampleData before their timestamps were known. When later committing each sampleMetadata, the extractor passed the accumulated trailing-byte offset instead of zero, making the first pending sample start at a negative queue offset and causing an index failure.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, selectableStreams and StreamInfo.toPlayerStream; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, buildSession
- Verdict: MISSING-FEATURE
- Why: `toPlayerStream` exposes only video, audio, and subtitle. Timed metadata and emsg streams are dropped before track assembly, and PlaybackCore has no metadata queue or event output. The reported queue corruption cannot occur, but emsg events are unavailable until a real metadata lane defines zero-offset sample ownership.
- Severity if real: P1 broken feature

### [MEDIA3-2517] A resolved DASH subtitle load error later stalls on empty cues
- Link: https://github.com/androidx/media/issues/2517  State: closed-fixed
- Mechanism: Several transient subtitle-fragment 404 responses set a cached TextRenderer streamError and later recovered. When empty TTML produced no future cue, isReady returned false from the stale error, but a fresh error check threw nothing, so the renderer was neither disabled nor made ready and held playback forever.
- KitePlayer code checked: kiteplayer-network/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/network/dash/DashMediaIo.kt, Dash.mediaItemFor; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, handleSubtitles and handleBuffering
- Verdict: MISSING-FEATURE
- Why: The current DASH door chooses one video representation and does not merge a separate TTML adaptation set. It has no per-subtitle fragment loader, retry state, or cached renderer error to clear. Segmented DASH subtitles are therefore unavailable rather than vulnerable to this stale readiness loop; future support must revalidate a cached error immediately before it can block playback.
- Severity if real: P1 broken feature

### [EXOPLAYER-7909] Render the final MPEG-TS video frame
- Link: https://github.com/google/ExoPlayer/issues/7909  State: closed-fixed
- Mechanism: ExoPlayer's H264Reader used the next frame start as the trigger to publish metadata for the preceding sample. At transport EOF there was no next start, so the final frame was never output; a one-frame TS made the loss absolute and visible.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/buildSrc/src/main/kotlin/BuildFFmpegTask.kt, sharedCoreArgs; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, readPacket and KiteCodecVideoDecoder.receive; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runVideoDecode and handleEof
- Verdict: IMMUNE
- Why: MPEG-TS extraction is libavformat's, not ExoPlayer H264Reader's next-sample state machine. Every packet returned by the demuxer is sent to libavcodec, and after the packet queue reaches EOF the video worker retries a null drain signal until accepted before `handleEof` can finish. A complete one-frame access unit therefore reaches both decode and drain rather than waiting for a second frame to publish it.
- Severity if real: P1 broken feature

### [EXOPLAYER-9969] Out-of-period DASH event metadata freezes the period boundary
- Link: https://github.com/google/ExoPlayer/issues/9969  State: open
- Mechanism: A period lasting 8.72 seconds carried an event timestamp interpreted as about 1.64 billion seconds after its start. ExoPlayer waited to output every period event before advancing, so the impossible future event held audio and video at the first boundary; the proposed fix clipped events past period duration.
- KitePlayer code checked: kiteplayer-network/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/network/dash/DashMediaIo.kt, Dash.mediaItemFor; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, StreamInfo.toPlayerStream
- Verdict: MISSING-FEATURE
- Why: `Dash.mediaItemFor` explicitly refuses more than one Period, and the backend drops metadata streams instead of exposing an event lane. The malformed event cannot block a transition here, but neither multi-period playback nor DASH events work. Their future implementation must clip manifest events to their containing period before making them a completion dependency.
- Severity if real: P1 broken feature

### [EXOPLAYER-3449] Platform MP3 decoders cut the last word on some devices
- Link: https://github.com/google/ExoPlayer/issues/3449  State: closed-fixed
- Mechanism: Several device audio decoders stopped emitting PCM before the final MP3 samples, making the last syllable disappear. ExoPlayer maintainers distinguished that platform-decoder tail loss from ordinary gapless trimming and later considered the original report fixed.
- KitePlayer code checked: kiteplayer-ffmpeg/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/PlatformDecoderSelection.android.kt, platformAudioDecoder; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, KiteCodecAudioDecoder.receive; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runAudioDecode and handleEof
- Verdict: IMMUNE
- Why: Android audio decoding never selects MediaCodec here; `platformAudioDecoder` always returns null, so FFmpeg software decode produces the PCM on every device. The core then waits for decoder drain, decoded buffers in flight, DSP tail flush, ring depletion, and bounded sink drain. A vendor MP3 decoder cannot silently remove the reported final syllable.
- Severity if real: P1 broken feature

### [EXOPLAYER-5063] Changing HLS ID3 timestamp metadata reinitializes the MP3 decoder every segment
- Link: https://github.com/google/ExoPlayer/issues/5063  State: closed-fixed
- Mechanism: Each HLS segment carried a different Apple transport-stream-timestamp ID3 value. ExoPlayer treated that metadata-only difference as a new audio Format, flushed or recreated the decoder every ten seconds, and produced a regular audible cut.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/buildSrc/src/main/kotlin/BuildFFmpegTask.kt, sharedCoreArgs; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, readPacket and KiteCodecAudioDecoder.receive
- Verdict: IMMUNE
- Why: The stream descriptor and one FFmpeg audio decoder live for the opened source. `KiteCodecAudioDecoder.receive` changes its output format only when decoded PCM sample rate, channel count, or channel layout changes. Per-segment ID3 timestamp metadata is not compared as an audio format and cannot trigger the repeated decoder flush mechanism.
- Severity if real: P2 quality/perf

### [EXOPLAYER-7326] CacheWriter rejects allowed short content
- Link: https://github.com/google/ExoPlayer/issues/7326  State: closed-fixed
- Mechanism: A fixed pre-cache request could extend past a shorter resource. CacheWriter still surfaced EOF despite short content being allowed, while CacheDataSource also failed to record the discovered length consistently; the fix normalized out-of-range handling and honored the short-content policy.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/CachingMediaIo.kt, read and seek; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerConfig.kt, IoCachePolicy
- Verdict: MISSING-FEATURE
- Why: KitePlayer has a bounded read-through window but no CacheWriter, offline pre-cache request, caller-specified byte length, or allowShortContent contract. Ordinary EOF simply marks the upstream end. An explicit download or preloading tier will need to distinguish a permitted short object from a truncated response and persist the newly learned total length.
- Severity if real: P2 quality/perf

### [EXOPLAYER-9067] HLS EVENT primary-playlist changes fail to publish the completed timeline
- Link: https://github.com/google/ExoPlayer/issues/9067  State: closed-fixed
- Mechanism: While several media playlists refreshed, maybeSetPrimaryUrl changed the primary selection. A subsequently loaded EVENT snapshot no longer matched the stored URL, so the primary listener missed its ENDLIST-bearing update and never built the completed timeline.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/buildSrc/src/main/kotlin/BuildFFmpegTask.kt, sharedCoreArgs; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecMediaBackend.kt, open; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, readPacket
- Verdict: IMMUNE
- Why: Basic HLS is parsed inside one libavformat reader. KitePlayer has no DefaultHlsPlaylistTracker, mutable primary URL, or listener whose equality check can suppress a snapshot. Duration and EOF arrive from that one demux source, so the reported cross-playlist publication race is absent even though KitePlayer exposes no application-level HLS timeline API.
- Severity if real: P1 broken feature

### [EXOPLAYER-7844] Segmented TTML cue is cut at the next document boundary
- Link: https://github.com/google/ExoPlayer/issues/7844  State: wontfix
- Mechanism: One TTML document declared a cue extending five seconds into the next segment. DASH permits only one active subtitle document, so the next document replaced it at the boundary; a packager must duplicate a cross-boundary cue in every segment where it remains visible.
- KitePlayer code checked: kiteplayer-network/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/network/dash/DashMediaIo.kt, Dash.mediaItemFor; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSubtitleDecoder.kt, KiteCodecSubtitleDecoderFactory.create
- Verdict: MISSING-FEATURE
- Why: The current DASH tier selects only one video representation, and the subtitle decoder factory has no TTML decoder. Segmented TTML is unavailable rather than boundary-clipped. Future support must follow the one-active-document rule and make malformed cross-segment cues disappear at a document boundary unless the later document repeats them.
- Severity if real: P2 quality/perf

### [EXOPLAYER-8408] DASH emsg retry state mixes audio and video chunk end times
- Link: https://github.com/google/ExoPlayer/issues/8408  State: closed-fixed
- Mechanism: One PlayerEmsgHandler stored a single lastLoadedChunkEndTimeUs while audio and video loaded concurrently. Whichever track completed last overwrote it; during an intermittent failure another track could look like a forward seek, force a manifest refresh, and suppress the intended segment retry.
- KitePlayer code checked: kiteplayer-network/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/network/dash/DashMediaIo.kt, DashMediaIo.read and Dash.mediaItemFor; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, StreamInfo.toPlayerStream
- Verdict: MISSING-FEATURE
- Why: The DASH tier chooses one representation and fetches its segments serially, while emsg metadata is not exposed. It has neither concurrent adaptation-set chunk cursors nor manifest-expiry retry state to corrupt. Separate audio and video DASH loading will need per-track loaded-end positions rather than one racing global value.
- Severity if real: P1 broken feature

### [EXOPLAYER-7512] Apple fMP4 I-frame-only byte ranges run past a partial mdat
- Link: https://github.com/google/ExoPlayer/issues/7512  State: closed-fixed
- Mechanism: An I-frame playlist byte-ranged one IDR from a larger moof and mdat pair. FragmentedMp4Extractor parsed the full fragment metadata, tried to read the next declared sample beyond the truncated range, retried with a bad nextLoadPosition, and failed instead of treating the selected IDR as complete.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/buildSrc/src/main/kotlin/BuildFFmpegTask.kt, sharedCoreArgs; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, selectableStreams and readPacket
- Verdict: MISSING-FEATURE
- Why: Although libavformat can open basic HLS, KitePlayer exposes no HLS I-frame variant track, trick-play selector, or byte-range chunk owner at the player layer. A future implementation must reset fragment state at each partial range and treat the one referenced sample as a valid end rather than retrying beyond that range.
- Severity if real: P1 broken feature

### [EXOPLAYER-7308] MP4 declares mvex but stores every sample in moov
- Link: https://github.com/google/ExoPlayer/issues/7308  State: closed-fixed
- Mechanism: The file contained mvex, so ExoPlayer selected its fragmented MP4 path, but it had no moof boxes and described all samples in moov. Treating the fragmented declaration as exclusive made the first extractor read return end of input and exposed no audio.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/buildSrc/src/main/kotlin/BuildFFmpegTask.kt, sharedCoreArgs; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, selectableStreams and readPacket
- Verdict: IMMUNE
- Why: KitePlayer delegates MOV and MP4 interpretation to current libavformat and has no binary choice between ExoPlayer Mp4Extractor and FragmentedMp4Extractor based only on mvex. The source exposes whatever streams libavformat discovers and reads their packets through the same reader, so the stale extractor-selection mechanism is absent.
- Severity if real: P1 broken feature

### [EXOPLAYER-5550] Playlist does not advance when EOS shares an audio buffer result
- Link: https://github.com/google/ExoPlayer/issues/5550  State: wontfix
- Mechanism: On one Xiaomi device the selected audio path returned RESULT_NOTHING_READ while its input buffer already carried end of stream. Checking the result first discarded that EOS fact, so the renderer never drained and a concatenated playlist never advanced.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runAudioDecode, handleEof, and handleQueueAdvance; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PacketQueue.kt, signalEndOfStream
- Verdict: IMMUNE
- Why: Demux EOF is stored on PacketQueue rather than hidden in a sample-read return code. Once the selected audio queue is empty and marked ended, `runAudioDecode` retries `decoder.send(null)` until accepted, then `handleEof` waits for decoder and sink drain before queue advance. There is no result-order branch that can erase an EOS flag attached to a buffer.
- Severity if real: P1 broken feature

### [EXOPLAYER-6895] Corrupt final HLS MP3 segment crashes the decoder
- Link: https://github.com/google/ExoPlayer/issues/6895  State: wontfix
- Mechanism: The final TS segment contained a malformed MP3 header, duplicated encoded bytes, and a misplaced LAME header. Android's MP3 decoder failed on that access unit and subsequent dequeue and cleanup calls threw instead of ending the otherwise playable item.
- KitePlayer code checked: kiteplayer-ffmpeg/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/PlatformDecoderSelection.android.kt, platformAudioDecoder; /Users/macbook/StudioProjects/#Kite/KiteCodec/kitecodec-core/src/commonMain/kotlin/io/github/yuroyami/kitecodec/CorruptData.kt, CorruptData.Skip; /Users/macbook/StudioProjects/#Kite/KiteCodec/kitecodec-core/src/jvmAndAndroidMain/kotlin/io/github/yuroyami/kitecodec/Playback.jvm.kt, noteCorruptData; /Users/macbook/StudioProjects/#Kite/KiteCodec/kitecodec-core/src/nativeMain/kotlin/io/github/yuroyami/kitecodec/Playback.native.kt, noteCorruptData
- Verdict: IMMUNE
- Why: Android platform audio decoding is disabled, so the failing MediaCodec MP3 dequeue and cleanup path is absent. KiteCodec defaults to `CorruptData.Skip`; both JVM and native playback count invalid data, consume it, and continue. A malformed tail unit is skipped instead of poisoning decoder cleanup or preventing already-decoded audio from reaching EOF.
- Severity if real: P1 broken feature

### [EXOPLAYER-5045] Zero-timestamp MP3 EOS buffer makes current position negative
- Link: https://github.com/google/ExoPlayer/issues/5045  State: closed-fixed
- Mechanism: A Samsung decoder emitted its final buffer with both EOS and timestamp zero. DefaultAudioSink treated that timestamp as a genuine discontinuity, moved its media-time origin backward, and published a negative position near the end of a short MP3.
- KitePlayer code checked: kiteplayer-ffmpeg/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/PlatformDecoderSelection.android.kt, platformAudioDecoder; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, KiteCodecAudioDecoder.receive; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runAudioDecode and currentPosition
- Verdict: IMMUNE
- Why: Android audio always uses FFmpeg software decode, and EOS travels as a null packet accepted by the decoder, not as a zero-PTS AudioBuffer submitted to the sink. Only real decoded frames become audio buffers and their absent timestamps are synthesized forward from sample counts, so a device EOS marker cannot re-anchor media time to zero.
- Severity if real: P1 broken feature

### [EXOPLAYER-3847] A no-sample custom renderer prevents repeat transition
- Link: https://github.com/google/ExoPlayer/issues/3847  State: wontfix
- Mechanism: Two application renderers extended BaseRenderer but consumed no samples and never reported hasReadStreamToEnd. Repeat suppresses a final-stream shortcut while preparing the next period, so those renderers held the current item forever instead of allowing the loop transition.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, OpenSession.selectedQueues, handleEof, and handleLoop
- Verdict: IMMUNE
- Why: KitePlayer has no application-pluggable renderer list in its completion quorum. `handleEof` waits only for selected audio and video queues, their decoders, scheduled video frames, and audio drain; subtitle and no-sample helpers cannot join that set. Repeat begins only after this fixed quorum reaches Ended, so a custom renderer cannot hold it.
- Severity if real: P1 broken feature

### [MEDIA3-1863] CEA end marker is discarded as older than the output start
- Link: https://github.com/androidx/media/issues/1863  State: closed-fixed
- Mechanism: CeaDecoder discarded every input timestamp before outputStartTimeUs, including the special end-of-source timestamp. Text playback then waited forever for an EOS it had already thrown away; malformed all-keyframe MP4 metadata also flushed the CEA reorder queue on every video sample.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSubtitleDecoder.kt, KiteCodecSubtitleDecoderFactory.create; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, StreamInfo.toPlayerStream
- Verdict: MISSING-FEATURE
- Why: KitePlayer exposes generic subtitle descriptors but its decoder factory has no CEA-608 or CEA-708 decoder, reorder queue, or end-marker handling. Closed captions are unavailable rather than able to deadlock EOF. Future CEA support must exempt its EOS sentinel from timestamp pruning and must not trust container sync flags to flush reordered caption data.
- Severity if real: P1 broken feature

### [MEDIA3-3334] Large DASH thumbnail presentationTimeOffset breaks backward scrubbing
- Link: https://github.com/androidx/media/issues/3334  State: closed-fixed
- Mechanism: Image samples with a large presentationTimeOffset were reported roughly 56 years before the period. After a backward seek, the image stream retained its old read position and would not load again until the user passed the prior high-water mark.
- KitePlayer code checked: kiteplayer-network/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/network/dash/DashMediaIo.kt, Dash.mediaItemFor; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, StreamInfo.toPlayerStream; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runSeek
- Verdict: MISSING-FEATURE
- Why: The DASH door chooses video or audio only, and `toPlayerStream` drops image streams. KitePlayer has no thumbnail output, thumbnail seek cursor, or image timestamp mapper, so catch-up thumbnails cannot update in either direction. Future support must normalize presentationTimeOffset before publishing samples and reset the image read cursor on every seek.
- Severity if real: P2 quality/perf

### [MEDIA3-2634] Audio tracks not selectable in HLS playlist
- Link: https://github.com/androidx/media/issues/2634  State: open
- Mechanism: An HLS media playlist pointed directly at MPEG-TS segments containing several audio languages. Media3 exposed only the lowest-numbered audio stream because its HLS wrapper modeled one muxed audio lane, even though RFC 8216 says clients should handle multiple tracks of one type.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/buildSrc/src/main/kotlin/BuildFFmpegTask.kt, sharedCoreArgs; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, selectableStreams and toPlayerStream; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, buildSession and inPlaceAudioChange
- Verdict: IMMUNE
- Why: The native profile includes FFmpeg's broad read-side demuxer set, so HLS MPEG-TS is demuxed as a container rather than narrowed by a Kotlin one-audio HLS wrapper. `selectableStreams` maps every audio stream FFmpeg reports, `buildSession` creates a compressed queue for every audio lane, and `inPlaceAudioChange` switches to any cached lane without reopening the presentation.
- Severity if real: P1 broken feature

### [MEDIA3-1988] Playback of an HLS stream with redundant playlists for failover does not work if primary stream is missing
- Link: https://github.com/androidx/media/issues/1988  State: open
- Mechanism: A multivariant HLS manifest offered primary and backup renditions at the same quality. A 404 from the primary playlist or its segments needed to exclude that rendition and retry the equivalent backup, but playback instead terminated on the primary failure.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/vendor/ffmpeg/libavformat/hls.c, hls_read_header, reload_playlist, and read_data_continuous; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runDemux and handleWorkerOutcome
- Verdict: MISSING-FEATURE
- Why: The pinned header can mark an unavailable child playlist broken and continue when another child exists, but ongoing recovery is not equivalent to redundant failover. `read_data_continuous` retries or skips a failed segment in the current playlist, and a reload error propagates from `reload_playlist`; neither path excludes the rendition and activates an equal backup. PlaybackCore has no backup policy above that boundary, so deterministic redundant failover is missing.
- Severity if real: P1 broken feature

### [MEDIA3-1454] HLS: IndexOutOfBoundsException happens if no segment files inside HLS playlist
- Link: https://github.com/androidx/media/issues/1454  State: wontfix
- Mechanism: Media3 accepted an invalid HLS playlist with no URI lines and later indexed element zero of its empty segment list. That unchecked list access produced `IndexOutOfBoundsException` instead of a parser or source error.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/buildSrc/src/main/kotlin/BuildFFmpegTask.kt, sharedCoreArgs; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, readPacket; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runDemux and handleWorkerOutcome
- Verdict: IMMUNE
- Why: KitePlayer has no Kotlin HLS segment list and never indexes `segments[0]`. FFmpeg either rejects the empty playlist while opening or returns a demux error while reading; the worker boundary converts the latter into `SourceUnavailable`, tears the graph down, and publishes a handled player failure. The invalid media can still fail, but not through Media3's unchecked empty-list path.
- Severity if real: P2 quality/perf

### [MEDIA3-2428] HlsPlaylistParser fails to parse when EXT-X-DATERANGE does not have START-DATE
- Link: https://github.com/androidx/media/issues/2428  State: closed-fixed
- Mechanism: A later `EXT-X-DATERANGE` tag may reuse an ID to augment the earlier tag and therefore omit `START-DATE`. Media3 parsed each line in isolation and required that attribute every time instead of consolidating same-ID attributes across playlist updates.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/buildSrc/src/main/kotlin/BuildFFmpegTask.kt, sharedCoreArgs; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, selectableStreams and toPlayerStream
- Verdict: MISSING-FEATURE
- Why: HLS playlist parsing is inside FFmpeg, while KitePlayer exposes only video, audio, and supported subtitle streams from the demuxer. It has no `EXT-X-DATERANGE` consolidation model or date-range metadata output, so the fixed parsing behavior and its resulting events are not part of the player contract.
- Severity if real: P1 broken feature

### [MEDIA3-2440] ExoPlayer live DASH playback crashes in SampleQueue.discardUpstreamFrom's checkArgument
- Link: https://github.com/androidx/media/issues/2440  State: closed-fixed
- Mechanism: When a live DASH manifest became static, clipping tried to discard upstream samples at a timestamp no greater than the queue's largest already-read timestamp. `SampleQueue.discardUpstreamFrom` asserted the opposite ordering and crashed; the fix guards that discard condition.
- KitePlayer code checked: kiteplayer-network/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/network/dash/DashMediaIo.kt, Dash.mediaItemFor; kiteplayer-network/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/network/dash/DashManifest.kt, DashManifestParser.segmentPlan
- Verdict: MISSING-FEATURE
- Why: The first DASH tier fetches one MPD snapshot and `segmentPlan` explicitly refuses every dynamic manifest before packet queues exist. KitePlayer therefore has no live-to-static manifest update or clipped upstream-discard transaction to crash, but live DASH playback covered by the fix is unsupported.
- Severity if real: P1 broken feature

### [MEDIA3-1755] Strange unexplained time jump with SimpleCache
- Link: https://github.com/androidx/media/issues/1755  State: open
- Mechanism: A persistent `SimpleCache` database and its span files could disagree about the bytes associated with a cache key. Playback then consumed FLAC bytes with unexpected timestamps, jumped far ahead without a position-discontinuity event, or played silence, while bypassing the upstream source and reporting no cache error.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/CachingMediaIo.kt, read, seek, and resetWindow
- Verdict: MISSING-FEATURE
- Why: KitePlayer's cache is a single in-memory contiguous window owned by one open reader. It has no persistent files, database, reusable cache key, cross-session spans, or offline cache index that can become inconsistent in this way. Persistent caching and its corruption-detection contract remain absent.
- Severity if real: P1 broken feature

### [MEDIA3-1954] Lack of implementation of the logic "readDiscontinuiny" in "HlsMediaPeriod.java"
- Link: https://github.com/androidx/media/issues/1954  State: wontfix
- Mechanism: A live HLS discontinuity shifted media timestamps and introduced additional `EXT-X-PROGRAM-DATE-TIME` anchors. The player retained only the earlier mapping, so after that anchor left the sliding window its time calculations corrupted and loading stopped; the reporter recovered by restarting on each discontinuity.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/buildSrc/src/main/kotlin/BuildFFmpegTask.kt, sharedCoreArgs; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, readPacket; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runDemux
- Verdict: MISSING-FEATURE
- Why: Basic HLS demuxing is delegated to FFmpeg, but KitePlayer has no player-owned sliding playlist timeline, program-date-time anchor set, discontinuity-sequence reader, or restart policy. It cannot provide the explicit remapping behavior this issue requires or expose when FFmpeg has applied it.
- Severity if real: P1 broken feature

### [MEDIA3-663] Should HLS live manifest refreshing consider http request delay?
- Link: https://github.com/androidx/media/issues/663  State: closed-fixed
- Mechanism: Media3 scheduled the next live playlist load from completion of the previous HTTP request instead of from its start. Network latency accumulated on every refresh, gradually consuming a near-live-edge buffer until repeated rebuffers; the fix subtracts request duration from the delay.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/buildSrc/src/main/kotlin/BuildFFmpegTask.kt, sharedCoreArgs; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, handleBuffering and runDemux
- Verdict: MISSING-FEATURE
- Why: KitePlayer has no player-owned HLS manifest loader or refresh scheduler. FFmpeg may refresh a raw HLS input internally, but the core sees only packets and cannot anchor reload cadence to request start, tune it near the live edge, or diagnose accumulating refresh delay.
- Severity if real: P2 quality/perf

### [MEDIA3-1534] Exoplayer constantly requesting for the same segment in dash live stream
- Link: https://github.com/androidx/media/issues/1534  State: wontfix
- Mechanism: A live DASH segment-number calculation repeatedly selected a future, unavailable segment. The same URL was requested in a loop because the manifest timeline and playback-time inputs did not advance to an available segment.
- KitePlayer code checked: kiteplayer-network/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/network/dash/DashMediaIo.kt, Dash.manifest and Dash.mediaItemFor; kiteplayer-network/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/network/dash/DashManifest.kt, DashManifestParser.segmentPlan
- Verdict: MISSING-FEATURE
- Why: KitePlayer parses the MPD once and `segmentPlan` refuses `type="dynamic"` before producing any URL sequence. It has no live segment-number cursor or availability-time retry loop, so it cannot make this exact repeated future request, but it also cannot play the affected live DASH stream.
- Severity if real: P1 broken feature

### [MEDIA3-3252] Live multi-period DASH: playback stalls after SCTE crash-out (operator early ad end)
- Link: https://github.com/androidx/media/issues/3252  State: wontfix
- Mechanism: An early server-side ad return closed the current DASH period and appended a new open tail period. Media3 remained on the old period with no automatic transition, stopped loading for tens of seconds, and recovered only when live-window eviction removed the head period.
- KitePlayer code checked: kiteplayer-network/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/network/dash/DashMediaIo.kt, Dash.mediaItemFor; kiteplayer-network/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/network/dash/DashManifest.kt, DashManifestParser.parse and segmentPlan
- Verdict: N/A
- Why: This report is specifically an explicit server-side ad insertion transition with an operator-triggered early return. KitePlayer has no ad insertion or ad-period contract, so the scenario is outside its current playback product surface rather than evidence about ordinary multi-period transition behavior.
- Severity if real: P1 broken feature

### [EXOPLAYER-1074] BehindLiveWindowException when playing HLS
- Link: https://github.com/google/ExoPlayer/issues/1074  State: wontfix
- Mechanism: Slow loading or a timeout let the selected HLS media sequence fall behind the playlist's moving live window. The chunk source then raised `BehindLiveWindowException`; recovery required preparing again at a position inside the new window.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/buildSrc/src/main/kotlin/BuildFFmpegTask.kt, sharedCoreArgs; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runDemux and handleWorkerOutcome
- Verdict: MISSING-FEATURE
- Why: KitePlayer delegates raw HLS loading to FFmpeg and owns no media-sequence window, live-edge target, or prepare-inside-window recovery transaction. A demux error becomes a terminal source failure instead of a typed behind-window reposition, so robust recoverable live HLS is missing.
- Severity if real: P1 broken feature

### [EXOPLAYER-537] Player does not recover from segment requests that return a 404
- Link: https://github.com/google/ExoPlayer/issues/537  State: closed-fixed
- Mechanism: A 404 on an HLS variant playlist or segment stopped playback. The fix blacklisted the failing variant and continued with another rendition when one existed, including failures before playback started and during later segments.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/buildSrc/src/main/kotlin/BuildFFmpegTask.kt, sharedCoreArgs; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runDemux and handleWorkerOutcome
- Verdict: MISSING-FEATURE
- Why: KitePlayer has no player-owned HLS variant list, exclusion timer, or retry policy. FFmpeg may perform internal HLS recovery, but if a 404 reaches the source reader the core immediately tears the session down; it cannot deterministically blacklist one rendition and continue from an equivalent alternative.
- Severity if real: P1 broken feature

### [EXOPLAYER-6360] HLS Live stream targetDurationUs is not always a ideal way to calculate time for next playlist load
- Link: https://github.com/google/ExoPlayer/issues/6360  State: wontfix
- Mechanism: A playlist's target duration was much longer than some media segments. Refreshing only at the target-duration cadence could let a near-edge buffer shrink and rebuffer, although maintainers held that starting farther behind the edge and a timely server should keep the cadence safe.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/buildSrc/src/main/kotlin/BuildFFmpegTask.kt, sharedCoreArgs; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, handleBuffering and runDemux
- Verdict: MISSING-FEATURE
- Why: The core receives packets from FFmpeg and has no access to HLS target duration, segment durations, playlist reload instants, or a configurable live-edge offset. It therefore cannot implement or tune either side of this refresh-cadence tradeoff.
- Severity if real: P2 quality/perf

### [EXOPLAYER-3971] Support seamless audio adaptation even if sample presentation timestamps aren't aligned
- Link: https://github.com/google/ExoPlayer/issues/3971  State: wontfix
- Mechanism: Audio-only HLS renditions used the same sample rate but their segment timestamps drifted by about 100 ms. Switching bitrate without accounting for that offset could create a gap or overlap, so ExoPlayer classified the transition as nonseamless.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, buildSession and inPlaceAudioChange; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/AudioPipeline.kt, reset
- Verdict: MISSING-FEATURE
- Why: `inPlaceAudioChange` supports an explicit user track change and resets the audio pipeline onto the target lane, but KitePlayer has no bandwidth estimator or adaptive audio rendition selector. It never automatically crosses bitrate lanes and has no segment-alignment model for making that adaptive transition seamless.
- Severity if real: P2 quality/perf

### [EXOPLAYER-2014] HLS - ExoPlayer doesn't detect multiple tracks of same type muxed in TS chunks
- Link: https://github.com/google/ExoPlayer/issues/2014  State: open
- Mechanism: An HLS transport stream carried seven MPEG audio tracks, but the HLS wrapper exposed only one even though the same transport stream opened directly exposed all seven. Track discovery was being narrowed by the playlist layer rather than the container.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/buildSrc/src/main/kotlin/BuildFFmpegTask.kt, sharedCoreArgs; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, selectableStreams; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, buildSession and inPlaceAudioChange
- Verdict: IMMUNE
- Why: KitePlayer does not place a one-audio Kotlin wrapper between HLS and transport-stream discovery. It maps every audio stream FFmpeg exposes, builds a compressed cache for each one, and changes the selected audio decoder and feed in place.
- Severity if real: P1 broken feature

### [EXOPLAYER-73] Implement multiple audio tracks support for HLS
- Link: https://github.com/google/ExoPlayer/issues/73  State: closed-fixed
- Mechanism: Early ExoPlayer HLS support selected one audio rendition from a multivariant playlist and did not expose the other language tracks. The implementation was extended so the player could enumerate and select multiple HLS audio renditions.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/buildSrc/src/main/kotlin/BuildFFmpegTask.kt, sharedCoreArgs; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, selectableStreams; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, buildSession and inPlaceAudioChange
- Verdict: IMMUNE
- Why: FFmpeg owns HLS rendition discovery and its exposed audio streams all flow through `selectableStreams`. The stabilized session caches every discovered audio lane, and `inPlaceAudioChange` selects one without rebuilding video. KitePlayer has no legacy single-audio HLS list that would hide the alternatives.
- Severity if real: P1 broken feature

### [EXOPLAYER-1975] Audio only HLS/DASH/SS playbacks are not adaptive by default
- Link: https://github.com/google/ExoPlayer/issues/1975  State: closed-fixed
- Mechanism: Audio-only adaptive manifests initially selected a fixed rendition rather than an adaptive group, so playback stayed at its starting bitrate even after bandwidth improved. Later default track selection included compatible audio renditions in adaptive selection.
- KitePlayer code checked: kiteplayer-network/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/network/dash/DashMediaIo.kt, Dash.mediaItemFor; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, buildSession and handleTrackChanges
- Verdict: MISSING-FEATURE
- Why: The DASH tier chooses the highest-bandwidth representation once, and raw HLS adaptation is opaque inside FFmpeg. PlaybackCore has no bandwidth estimator, rendition group, or automatic adaptation command, so it cannot promise audio-only bitrate adaptation across HLS or DASH.
- Severity if real: P2 quality/perf

### [EXOPLAYER-771] DASH: Make use of multiple BaseURL elements, where more than one is defined.
- Link: https://github.com/google/ExoPlayer/issues/771  State: closed-fixed
- Mechanism: A DASH representation may provide alternate `BaseURL` service locations. Playback needed deterministic selection and temporary exclusion of a failed location so segment loading could fall back to another CDN; a later fix also prevented one adaptation set's failure from consuming the other's fallback accounting.
- KitePlayer code checked: kiteplayer-network/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/network/dash/DashManifest.kt, DashManifestParser.resolveBaseUrl and segmentPlan; kiteplayer-network/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/network/dash/DashMediaIo.kt, Dash.mediaItemFor
- Verdict: MISSING-FEATURE
- Why: `resolveBaseUrl` reads only `element.child("BaseURL")`, stores one resolved URL, and `DashMediaIo` fetches the resulting immutable plan. There is no list of alternate service locations, priority, exclusion lifetime, or retry selection, so a failure of the chosen CDN is terminal.
- Severity if real: P1 broken feature

### [EXOPLAYER-420] Generalize/enhance persistent caching functionality
- Link: https://github.com/google/ExoPlayer/issues/420  State: closed-fixed
- Mechanism: The original persistent cache understood only bounded byte ranges for one URL shape. General support required content lengths for unbounded requests, whole-resource caching for servers without ranges, and keys that distinguish separately addressed segments so progressive, HLS, DASH, and SmoothStreaming data could be reused offline.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/CachingMediaIo.kt, read, seek, and resetWindow
- Verdict: MISSING-FEATURE
- Why: KitePlayer holds one contiguous RAM window for one live `MediaIo` and discards it at close. It has no persistent store, content-key index, segment key, unbounded-resource completion record, or offline read mode, so none of the generalized cache contract exists.
- Severity if real: P1 broken feature

### [EXOPLAYER-4078] Seeking ExoPlayer Rapidly with OkHttp with http2 connection causes sockettimeoutexception
- Link: https://github.com/google/ExoPlayer/issues/4078  State: wontfix
- Mechanism: Rapid HLS seeks repeatedly cancelled HTTP/2 requests on a shared OkHttp connection. A pooled multiplexed connection eventually remained half-hung: later requests received headers but no body bytes and timed out, while maintainers recommended the Cronet stack instead of fixing the OkHttp path.
- KitePlayer code checked: gradle/libs.versions.toml, Ktor 3.5.2; kiteplayer-network/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/network/KtorMediaIo.kt, openAt, close, and KtorMediaIoResolver
- Verdict: IMMUNE
- Why: The current Android network stack is Ktor 3.5.2 over OkHttp 5.3.2. That OkHttp generation returns HTTP/2 connection flow-control credit when a response body is canceled, while KtorMediaIo explicitly cancels each replaced body and job. Rapid seeks therefore do not retain the exhausted shared-window state from the old OkHttp path in this issue.
- Severity if real: P1 broken feature

### [EXOPLAYER-3735] Support ICY metadata
- Link: https://github.com/google/ExoPlayer/issues/3735  State: closed-fixed
- Mechanism: Shoutcast and Icecast responses carry station fields in ICY headers and timed title updates in interleaved metadata blocks. ExoPlayer added header parsing and a metadata track so applications could receive both the initial station data and in-stream changes.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, selectableStreams and toPlayerStream; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/PlayerState.kt, PlayerSnapshot
- Verdict: MISSING-FEATURE
- Why: `toPlayerStream` exposes only video, audio, and supported subtitles, so an ICY metadata stream is dropped. `PlayerSnapshot` has no timed metadata output or station-header field. Audio may play through FFmpeg, but clients cannot receive ICY title or station updates.
- Severity if real: P1 broken feature

### [EXOPLAYER-6166] Allow specifying per-item request headers (for playback and downloads)
- Link: https://github.com/google/ExoPlayer/issues/6166  State: open
- Mechanism: Authentication and cookie headers may differ per media item and may need refresh. A factory-wide default header map cannot safely represent simultaneous or queued items, so the request metadata must travel with the item into every manifest, media, and download request.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/MediaItem.kt, headers; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, buildSession; kiteplayer-network/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/network/KtorMediaIo.kt, KtorMediaIoResolver.resolve and KtorMediaIo.open; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, KiteCodecSourceFactory.open
- Verdict: SUSPECT
- Why: Direct FFmpeg HTTP opens translate `MediaItem.headers` into protocol options, but the normal HTTPS resolver is called with only `item.uri`. `KtorMediaIoResolver.resolve` supplies its own factory-wide headers and cannot see the item's map, after which `effectiveItem.io` bypasses FFmpeg networking. Per-item auth is therefore silently lost on that resolver path.
- Severity if real: P1 broken feature

### [EXOPLAYER-1606] Audio stream moved to buffering state immediately on disconnection of internet
- Link: https://github.com/google/ExoPlayer/issues/1606  State: closed-fixed
- Mechanism: A progressive Icecast connection failure made the player enter buffering as soon as the loader noticed the socket error, even though already-buffered audio could still play. The fix let queued media drain before retrying, with an unavoidable discontinuity when the byte stream rejoined.
- KitePlayer code checked: kiteplayer-network/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/network/KtorMediaIo.kt, read and openAt; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runDemux and handleWorkerOutcome
- Verdict: SUSPECT
- Why: Ktor propagates a failed response body into the demux read. Although packet queues may already hold audio, `handleWorkerOutcome` immediately tears down the entire session on a demux exception and publishes `SourceUnavailable`, so buffered samples are not allowed to drain and there is no progressive-stream retry transaction.
- Severity if real: P1 broken feature

### [EXOPLAYER-1706] Add or remove playlist items (MediaSources)
- Link: https://github.com/google/ExoPlayer/issues/1706  State: closed-fixed
- Mechanism: Applications needed to insert or remove media sources from a playing concatenation without releasing and preparing a new player. ExoPlayer added dynamic playlist mutation while preserving the active item and timeline.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/KitePlayer.kt, openQueue, next, and previous; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, queueItems, runOpen, and jumpQueue
- Verdict: MISSING-FEATURE
- Why: KitePlayer can open a fixed queue and move its cursor, but `queueItems` is replaced only by a new `openQueue` or plain open. There is no add, remove, or move operation for an active queue, so changing its membership requires replacing the session-level queue contract.
- Severity if real: P2 quality/perf

### [EXOPLAYER-4915] Update shuffle order in unprepared ConcatenatingMediaSource.
- Link: https://github.com/google/ExoPlayer/issues/4915  State: closed-fixed
- Mechanism: A concatenating source cached the next child under its old shuffle order. Updating the order before preparation therefore played one stale next item before following the new permutation; the player API later accepted shuffle-order updates at any time.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/KitePlayer.kt, openQueue, next, and previous; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, queueItems, queueIndex, and jumpQueue
- Verdict: MISSING-FEATURE
- Why: KitePlayer's queue is always traversed in list order, optionally wrapping under `LoopMode.All`. It has no shuffle permutation or live order-update API, so it cannot exhibit a one-item stale shuffle cache but also cannot provide the feature whose consistency the fix guarantees.
- Severity if real: P2 quality/perf

### [MEDIA3-759] Getting 'DashManifestStaleException' for a dash live stream that is working on other players
- Link: https://github.com/androidx/media/issues/759  State: wontfix
- Mechanism: A server-side-ad-inserted live DASH manifest changed its period structure over time. Media3 either declared the updated MPD stale or indexed an adaptation set that no longer existed, so playback failed after several ad cycles even though web DASH players continued.
- KitePlayer code checked: kiteplayer-network/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/network/dash/DashMediaIo.kt, Dash.manifest and Dash.mediaItemFor; kiteplayer-network/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/network/dash/DashManifest.kt, DashManifestParser.segmentPlan
- Verdict: N/A
- Why: The reproducible report is specifically an explicit server-side ad insertion workflow whose manifest mutates across ad cycles. KitePlayer exposes no ad insertion contract, so that application scenario is outside the current product surface and cannot establish a verdict for ordinary dynamic DASH refresh.
- Severity if real: P1 broken feature

### [MEDIA3-344] PHP progressive download streams each minute disconnect problem
- Link: https://github.com/androidx/media/issues/344  State: wontfix
- Mechanism: A progressive live HTTP response stopped delivering bytes longer than the client's read timeout. Media3 retried after the timeout, making playback appear to disconnect or restart periodically; maintainers treated the configured eight-second timeout as expected and suggested a custom network stack or timeout.
- KitePlayer code checked: kiteplayer-network/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/network/KtorMediaIo.kt, read, openAt, and KtorMediaIoResolver; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runDemux and handleWorkerOutcome
- Verdict: SUSPECT
- Why: The resolver constructs a default Ktor client without a KitePlayer-level read-timeout or progressive retry policy. If its platform engine closes a stalled body, the failure reaches `runDemux` and `handleWorkerOutcome` immediately tears the session down rather than reopening the unbounded stream. Applications can supply a custom client, but the default behavior is not resilient.
- Severity if real: P1 broken feature

### [MEDIA3-312] ExoPlayer does not stick to new media URL after HTTP 302 redirect
- Link: https://github.com/androidx/media/issues/312  State: wontfix
- Mechanism: Every DASH segment range request started from the original URL and followed the same temporary redirect again. Although HTTP treats 302 as changeable, DASH interoperability guidance recommends retaining the redirected media URL to avoid repeated round trips.
- KitePlayer code checked: kiteplayer-network/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/network/KtorMediaIo.kt, open, seek, and openAt; kiteplayer-network/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/network/dash/DashMediaIo.kt, read
- Verdict: SUSPECT
- Why: `KtorMediaIo` stores only the original URI. Every ranged reopen calls `prepareGet(uri)` again, and `DashMediaIo` retains the manifest's original segment URLs, with no field for the final response URL. A Ktor engine may follow each 302, but KitePlayer never promotes the redirect target for later range or segment requests.
- Severity if real: P2 quality/perf

### [MEDIA3-2069] This commit will cause H265 ts to fail
- Link: https://github.com/androidx/media/issues/2069  State: closed-fixed
- Mechanism: A Media3 `H265Reader` change altered access-unit boundary detection for H.265 in MPEG-TS. Certain streams then stopped yielding complete samples and playback stuck; reverting the reader change or merging the parser fix restored them.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/buildSrc/src/main/kotlin/BuildFFmpegTask.kt, sharedCoreArgs; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, readPacket and KiteCodecVideoDecoderFactory.create
- Verdict: IMMUNE
- Why: KitePlayer does not use Media3's Java `H265Reader`. FFmpeg demuxes MPEG-TS and emits HEVC packets, and the chosen FFmpeg or platform decoder consumes those packets. The regressed access-unit parser and its state machine are absent.
- Severity if real: P1 broken feature

### [MEDIA3-2280] Video Renderer Never Gets Ready on Fast Channel Change
- Link: https://github.com/androidx/media/issues/2280  State: wontfix
- Mechanism: Repeated rapid changes between multicast channels sometimes left a hardware video renderer initialized but never ready. Audio and metadata became ready, buffered media accumulated, no first frame appeared, and no terminal player error identified the stuck renderer.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/buildSrc/src/main/kotlin/BuildFFmpegTask.kt, sharedCoreArgs; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runOpen and awaitInitialFill
- Verdict: MISSING-FEATURE
- Why: The shipped native profile explicitly disables UDP and RTP, which causes dependent multicast and RTSP demuxers to be omitted. KitePlayer therefore cannot open the reported multicast channels or exercise their rapid-change renderer lifecycle.
- Severity if real: P1 broken feature

### [MEDIA3-2683] MP2 track in MP4 container is incorrectly identified as MP3, resulting in decoder failure
- Link: https://github.com/androidx/media/issues/2683  State: wontfix
- Mechanism: An MP4 carried MPEG Layer II audio but Media3 labeled it `audio/mpeg` and sent it to an MP3 platform decoder. That decoder failed while queuing input, or the item continued silently when the device lacked MP2 support.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, toPlayerStream and KiteCodecAudioDecoderFactory.create; kiteplayer-ffmpeg/src/androidMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/PlatformDecoderSelection.android.kt, platformAudioDecoder
- Verdict: IMMUNE
- Why: Stream codec identity comes from FFmpeg rather than a Media3 MIME guess, and Android `platformAudioDecoder` always returns null. MP2 audio therefore stays on the FFmpeg audio decoder instead of being routed to `OMX.google.mp3.decoder` under a generic MIME label.
- Severity if real: P1 broken feature

### [MEDIA3-1841] UDP stream is not playing and Exoplayer is in endless buffering state sometime
- Link: https://github.com/androidx/media/issues/1841  State: closed-fixed
- Mechanism: After changing multicast channels, an older Media3 release could leave the UDP source in buffering forever with no new samples. Updating from 1.1.1 to 1.4.1 removed the reporter's failure, though no single change was isolated.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/buildSrc/src/main/kotlin/BuildFFmpegTask.kt, sharedCoreArgs; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runOpen and awaitInitialFill
- Verdict: MISSING-FEATURE
- Why: UDP and RTP are explicitly disabled in the native FFmpeg profile, so multicast inputs are not a supported source. The core's bounded initial fill cannot diagnose a protocol that the backend cannot open.
- Severity if real: P1 broken feature

### [MEDIA3-522] ExoPlayer - RTSP UDP->TCP failover produces error 401
- Link: https://github.com/androidx/media/issues/522  State: wontfix
- Mechanism: An authenticated RTSP camera first negotiated RTP over UDP, then fell back to interleaved TCP after no packets arrived. The repeated `SETUP` lost or mishandled the digest-auth challenge state and returned 401 even though the earlier `DESCRIBE` was authenticated.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/buildSrc/src/main/kotlin/BuildFFmpegTask.kt, sharedCoreArgs; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/MediaItem.kt, headers and openOptions
- Verdict: MISSING-FEATURE
- Why: The profile disables UDP and RTP and consequently drops the dependent RTSP demuxers. KitePlayer has no RTSP session, challenge cache, transport fallback, or authenticated repeated-SETUP transaction, so camera playback itself is missing.
- Severity if real: P1 broken feature

### [MEDIA3-1043] HLS stream is not showing all audio tracks
- Link: https://github.com/androidx/media/issues/1043  State: open
- Mechanism: MPEG-TS segments in an adaptive HLS stream carried several audio tracks. The transport-stream extractor found them, but the HLS sample-stream wrapper grouped same-type queues and exposed only the first, while opening the segment directly exposed every track.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/buildSrc/src/main/kotlin/BuildFFmpegTask.kt, sharedCoreArgs; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, selectableStreams; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, buildSession and inPlaceAudioChange
- Verdict: IMMUNE
- Why: KitePlayer has no HLS same-type grouping wrapper. FFmpeg's demuxer stream list is mapped without collapsing audio lanes, the session creates a compressed queue for each audio stream, and selection changes the decoder and feed in place.
- Severity if real: P1 broken feature

### [MEDIA3-2484] HLS Interstitial support for live sliding window
- Link: https://github.com/androidx/media/issues/2484  State: open
- Mechanism: Live HLS interstitials move as the playlist window refreshes. Correct playback must preserve placeholder ad groups, reconcile their shifted content positions, honor resumption offsets and playout limits, and decide how seeks or skipped ads affect the moving timeline.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/buildSrc/src/main/kotlin/BuildFFmpegTask.kt, sharedCoreArgs; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, selectableStreams and toPlayerStream; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runDemux
- Verdict: N/A
- Why: This request is explicitly for ad insertion through HLS interstitials, including placeholder ad groups and ad-resumption policy. KitePlayer exposes no advertising or interstitial playback contract, so the scenario is outside the current product surface rather than a missing general media-playback mechanism.
- Severity if real: P1 broken feature

### [MEDIA3-621] Low-bitrate ABR adaptation not working very well
- Link: https://github.com/androidx/media/issues/621  State: open
- Mechanism: Initial bandwidth estimates and very small transfer samples stayed above the bitrates in a low-rate HLS ladder. Automatic selection therefore kept the highest rendition and rebuffered on a constrained link instead of gathering a sufficiently conservative estimate and stepping down.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, handleBuffering and handleTrackChanges; kiteplayer-network/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/network/dash/DashMediaIo.kt, Dash.mediaItemFor
- Verdict: MISSING-FEATURE
- Why: PlaybackCore has no throughput estimator or automatic rendition selector, and the DASH tier picks the highest advertised bandwidth once. Raw FFmpeg HLS behavior is not exposed as a KitePlayer ABR contract, so there is no tunable low-bitrate estimation or downshift policy.
- Severity if real: P2 quality/perf

### [MEDIA3-588] HLS: Support TTML/IMSC subtitles in mp4 segments
- Link: https://github.com/androidx/media/issues/588  State: open
- Mechanism: Fragmented-MP4 TTML samples used cue times relative to a long-running live track epoch. HLS playback needed each sample's media timestamp and subsample offset to rebase those large document times; otherwise cues appeared hundreds of hours late or flickered after an ad hoc zero-based rewrite.
- KitePlayer code checked: kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSubtitleDecoder.kt, KiteCodecSubtitleDecoderFactory.create; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, toPlayerStream
- Verdict: MISSING-FEATURE
- Why: The subtitle factory has no TTML or IMSC decoder, and unsupported subtitle streams are omitted from selectable tracks. There is consequently no TTML-in-MP4 sample timestamp, subsample-offset, or live-epoch rebasing path.
- Severity if real: P1 broken feature

### [MEDIA3-288] Flickering subtitles in DASH/H264 with B-frames present
- Link: https://github.com/androidx/media/issues/288  State: closed-fixed
- Mechanism: DASH subtitle segments started on a timeline offset different from video, while H.264 B-frame reordering made sample and presentation order diverge. The old renderer repeatedly cleared and reintroduced cues; parsing subtitles during extraction and carrying timed cue groups removed the flicker.
- KitePlayer code checked: kiteplayer-network/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/network/dash/DashMediaIo.kt, Dash.mediaItemFor; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSubtitleDecoder.kt, KiteCodecSubtitleDecoderFactory.create
- Verdict: MISSING-FEATURE
- Why: The first DASH tier chooses one adaptation set, so a video presentation never merges a separate subtitle representation. TTML is also unsupported. The flickering renderer path is absent only because segmented DASH subtitles do not reach PlaybackCore.
- Severity if real: P1 broken feature

### [MEDIA3-473] HLS Live streaming not combine audio and video
- Link: https://github.com/androidx/media/issues/473  State: open
- Mechanism: An adaptive live HLS input sometimes prepared only audio renditions and sometimes only video renditions from the same URL. Track discovery and grouping completed against an incomplete initial view, so the missing media type never joined the active presentation.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/vendor/ffmpeg/libavformat/hls.c, hls_read_packet and update_streams_from_subdemuxer; kiteplayer-ffmpeg/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/ffmpeg/KiteCodecSource.kt, selectableStreams and byIndex; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, buildSession
- Verdict: SUSPECT
- Why: The pinned HLS reader calls `update_streams_from_subdemuxer` during packet reads, so FFmpeg can add a stream after header preparation. KiteCodecSource instead snapshots `source.streams` once into `selectableStreams` and `byIndex`, and PlaybackCore builds every lane from that snapshot. If the late stream is the missing audio or video type, it cannot become visible or selectable without reopening.
- Severity if real: P1 broken feature

### [MEDIA3-1854] Support to QUERYPARAM HLS (13) tag
- Link: https://github.com/androidx/media/issues/1854  State: closed-fixed
- Mechanism: HLS `EXT-X-DEFINE` with `QUERYPARAM` imports a query parameter from the playlist request for variable substitution into child playlist, key, or segment URLs. Without it, signed or authenticated requests lose required values and fail downstream.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/vendor/ffmpeg/libavformat/hls.c, parse_playlist; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/MediaItem.kt, headers; kiteplayer-network/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/network/KtorMediaIo.kt, KtorMediaIoResolver.resolve
- Verdict: MISSING-FEATURE
- Why: Basic HLS playback exists through pinned FFmpeg, but its `parse_playlist` has no `EXT-X-DEFINE` or `QUERYPARAM` parser. `MediaItem.headers` cannot implement query-to-URL substitution declared inside the playlist, so QUERYPARAM propagation is not provided.
- Severity if real: P1 broken feature

### [MEDIA3-1848] Secure Reliable Transport (SRT) support
- Link: https://github.com/androidx/media/issues/1848  State: open
- Mechanism: SRT provides encrypted, loss-recovering, low-latency delivery over unreliable networks. Supporting an `srt://` item requires the transport library and protocol, connection and latency options, interruptible reads, and handoff of the recovered byte stream to the demuxer.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/buildSrc/src/main/kotlin/BuildFFmpegTask.kt, sharedCoreArgs; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/MediaItem.kt, io
- Verdict: MISSING-FEATURE
- Why: The native profile enables only file, fd, pipe, data, HTTP, and TCP protocols and does not build libsrt. An application could implement a custom `MediaIo` around its own SRT stack, but KitePlayer ships no SRT transport, option surface, or resolver.
- Severity if real: P1 broken feature

### [EXOPLAYER-87] Implement seeking within HLS moving live window in ExoPlayer and its demo app
- Link: https://github.com/google/ExoPlayer/issues/87  State: closed-fixed
- Mechanism: A long HLS playlist represents a moving DVR window. Seeking must map the user's offset behind live to an available media sequence, refresh at the edge, avoid segments already evicted with 404, and keep the displayed offset stable as both ends advance.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/buildSrc/src/main/kotlin/BuildFFmpegTask.kt, sharedCoreArgs; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runSeek and awaitLanding
- Verdict: MISSING-FEATURE
- Why: KitePlayer has no player-owned HLS playlist, live-window bounds, media-sequence mapping, or offset-behind-live timeline. Seeking is a source-level timestamp operation over the opaque FFmpeg input, so it cannot expose or enforce the DVR window contract.
- Severity if real: P1 broken feature

### [EXOPLAYER-565] Connection Pooling and Reuse + Parallel Chunk Loading
- Link: https://github.com/google/ExoPlayer/issues/565  State: wontfix
- Mechanism: Sequential segment requests can leave bandwidth idle between short chunks, while parallel requests or connection reuse can hide round-trip latency. Maintainers deliberately balanced fill and drain for memory, battery, and network cost rather than fetching the whole presentation as fast as possible.
- KitePlayer code checked: kiteplayer-network/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/network/dash/DashMediaIo.kt, read and Dash.mediaItemFor; kiteplayer-network/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/network/KtorMediaIo.kt, KtorMediaIoResolver
- Verdict: MISSING-FEATURE
- Why: `DashMediaIo.read` awaits one complete segment before advancing to the next URL. Its shared Ktor client can reuse connections, but there is no parallel segment prefetch or configurable chunk-loading concurrency, so high-latency segment ladders cannot trade extra memory and traffic for faster fill.
- Severity if real: P2 quality/perf

### [EXOPLAYER-1574] Support xlinks in DASH manifests
- Link: https://github.com/google/ExoPlayer/issues/1574  State: open
- Mechanism: A DASH `Period` may be a remote XLink reference. Parsing only the local XML leaves that period empty, makes subsequent period starts indeterminate, and fails the manifest; support requires fetching and resolving linked XML before timeline construction.
- KitePlayer code checked: kiteplayer-network/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/network/dash/DashManifest.kt, DashManifestParser.parse and resolveBaseUrl; kiteplayer-network/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/network/xml/XmlMini.kt, parse
- Verdict: MISSING-FEATURE
- Why: The parser documentation explicitly lists XLink as out of scope. It reads only local `Period` children, performs no namespace-aware `xlink:href` lookup, and has no recursive manifest-fetch transaction.
- Severity if real: P1 broken feature

### [EXOPLAYER-8941] RTSP Basic Authorization
- Link: https://github.com/google/ExoPlayer/issues/8941  State: closed-fixed
- Mechanism: RTSP URLs containing user information did not generate the `Authorization: Basic` header after a 401 challenge, so authenticated cameras failed at `DESCRIBE`. The session also needed tolerant SDP message-body parsing for real camera responses.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/buildSrc/src/main/kotlin/BuildFFmpegTask.kt, sharedCoreArgs; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/MediaItem.kt, headers and openOptions
- Verdict: MISSING-FEATURE
- Why: The curated protocol profile omits RTP and UDP, which causes RTSP support to be dropped. KitePlayer therefore has no RTSP challenge-response client, URI credential handling, SDP parser, or camera-session transport.
- Severity if real: P1 broken feature

### [EXOPLAYER-4904] Improve support for low-latency DASH live streams
- Link: https://github.com/google/ExoPlayer/issues/4904  State: closed-fixed
- Mechanism: Low-latency DASH requires availability-time-aware chunk loading, a target live offset close to the edge, catch-up playback-speed control, and ABR decisions that remain safe when small low-latency transfers no longer provide good throughput samples.
- KitePlayer code checked: kiteplayer-network/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/network/dash/DashMediaIo.kt, Dash.manifest and Dash.mediaItemFor; kiteplayer-network/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/network/dash/DashManifest.kt, DashManifestParser.segmentPlan
- Verdict: MISSING-FEATURE
- Why: `segmentPlan` rejects every dynamic MPD, while `DashMediaIo` is a forward-only static concatenation. There is no availability time, live edge, manifest refresh, playback-speed catch-up, or adaptive representation loop.
- Severity if real: P1 broken feature

### [EXOPLAYER-2643] Implement full offline support for DASH/HLS/SS/Misc
- Link: https://github.com/google/ExoPlayer/issues/2643  State: closed-fixed
- Mechanism: Full offline support selects adaptive tracks, downloads every required manifest, initialization range, and segment, persists progress, resumes interrupted work, and later resolves playback entirely from the local cache.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/CachingMediaIo.kt, read, seek, and close; kiteplayer-network/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/network/dash/DashMediaIo.kt, Dash.mediaItemFor
- Verdict: MISSING-FEATURE
- Why: The cache is an ephemeral single-open RAM window and the DASH tier fetches segments only while reading. KitePlayer has no download manager, persistent segment index, resume state, offline resolver, or track-download selection.
- Severity if real: P1 broken feature

### [EXOPLAYER-5978] DASH/HLS/SS downloads: Parallelize & merge requests to improve download speed
- Link: https://github.com/google/ExoPlayer/issues/5978  State: closed-fixed
- Mechanism: Sequential offline downloads underuse high-latency links. ExoPlayer added parallel segment requests and merged adjacent byte ranges from one URL while keeping audio and video progress approximately aligned so a partial download remained useful.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/CachingMediaIo.kt, read and append; kiteplayer-network/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/network/dash/DashMediaIo.kt, read
- Verdict: MISSING-FEATURE
- Why: KitePlayer has no offline downloader. Playback reads one DASH segment at a time, and `CachingMediaIo` coalesces small demux reads only inside one upstream stream; it neither schedules parallel URLs nor merges adjacent manifest byte ranges for acquisition.
- Severity if real: P2 quality/perf

### [EXOPLAYER-2403] Allow the app to override the start position when playback transitions to another source
- Link: https://github.com/google/ExoPlayer/issues/2403  State: wontfix
- Mechanism: A concatenated playlist normally starts each next item at its default position. Applications needed to attach an item-specific resume position before automatic transition, rather than wait for callbacks and race a seek after the new source began loading.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/MediaItem.kt, startPosition; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, startPositionTargetUs, runOpen, and handleQueueAdvance
- Verdict: IMMUNE
- Why: Every `MediaItem` carries its own `startPosition`. Queue auto-advance calls `runOpen` with the next item, and `runOpen` resolves and seeks to that target before returning the paused first frame or resuming the queue's play intent. There is no post-transition callback race.
- Severity if real: P1 broken feature

### [EXOPLAYER-9122] Seek to initial position in a live stream using absolute time
- Link: https://github.com/google/ExoPlayer/issues/9122  State: open
- Mechanism: Resuming a live item by UTC time requires mapping an absolute wall-clock instant into the refreshed window before segment loading starts. A relative seek issued after prepare may already fetch initialization and media for the wrong default live position.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/MediaItem.kt, startPosition; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, startPositionTargetUs and runOpen; kiteplayer-network/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/network/dash/DashManifest.kt, DashManifestParser.parse
- Verdict: MISSING-FEATURE
- Why: `startPosition` is relative media time, and the DASH model stores no availability start time or live window because dynamic manifests are rejected. KitePlayer cannot accept a UTC target or resolve it before live segment acquisition.
- Severity if real: P1 broken feature

### [EXOPLAYER-4343] Add option to automatically advance to the next playlist item if a playback error occurs
- Link: https://github.com/google/ExoPlayer/issues/4343  State: open
- Mechanism: A source error in one concatenated item terminates the player instead of advancing to the next playable item. Applications can remove the failed item and prepare again, but that is race-prone under shuffle and can loop if indices move.
- KitePlayer code checked: kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, handleWorkerOutcome, handleEof, handleQueueAdvance, and jumpQueue
- Verdict: MISSING-FEATURE
- Why: Queue advance runs only after normal EOF or an explicit next command. `handleWorkerOutcome` tears the session down, enters failed state, and leaves the queue cursor on the bad item. There is no skip-on-error policy, retry budget, or stable item identity for automatic continuation.
- Severity if real: P1 broken feature

### [EXOPLAYER-11040] Multicast stream get stuck with no error
- Link: https://github.com/google/ExoPlayer/issues/11040  State: open
- Mechanism: Returning to a multicast stream after other channels could leave a device codec stuck across a multi-second audio and video timestamp gap. The player neither relocked across the discontinuity nor surfaced a terminal error, so it remained stalled indefinitely.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/buildSrc/src/main/kotlin/BuildFFmpegTask.kt, sharedCoreArgs; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, runOpen, awaitInitialFill, and handleWorkerOutcome
- Verdict: MISSING-FEATURE
- Why: The native profile disables UDP and RTP, so multicast is not a supported source. KitePlayer cannot exercise the reported device-codec relock path, and adding multicast later will need a bounded timestamp-gap recovery rather than an endless ready wait.
- Severity if real: P1 broken feature

### [EXOPLAYER-5975] Cronet extension: Support cookie storage
- Link: https://github.com/google/ExoPlayer/issues/5975  State: open
- Mechanism: A CDN set authentication cookies on the HLS master response and required them on key, child-playlist, and segment requests. Cronet did not retain `Set-Cookie`, so later requests failed unless the application supplied a cookie store or manually injected the header.
- KitePlayer code checked: kiteplayer-network/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/network/KtorMediaIo.kt, open, openAt, and KtorMediaIoResolver; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/MediaItem.kt, headers
- Verdict: SUSPECT
- Why: A resolver-created default `HttpClient()` is not configured by KitePlayer with an HTTP-cookies plugin, and response cookies are never copied into `requestHeaders`. A caller can supply a client with its own cookie jar, but default authenticated HLS or ranged HTTP can lose server-set cookies on subsequent requests.
- Severity if real: P1 broken feature

### [EXOPLAYER-5011] Improve support for low-latency HLS live streams
- Link: https://github.com/google/ExoPlayer/issues/5011  State: closed-fixed
- Mechanism: Apple low-latency HLS adds partial segments, blocking playlist reloads, preload hints, rendition reports, and a tight live-edge target. The player must join parts without duplicates, refresh promptly, adapt quality, and use speed control or fallback when it drifts.
- KitePlayer code checked: /Users/macbook/StudioProjects/#Kite/KiteCodec/vendor/ffmpeg/libavformat/hls.c, parse_playlist; kiteplayer-core/src/commonMain/kotlin/io/github/yuroyami/kiteplayer/internal/PlaybackCore.kt, handleBuffering and runDemux
- Verdict: MISSING-FEATURE
- Why: Basic HLS playback exists through pinned FFmpeg, but `parse_playlist` has no `EXT-X-PART`, `PRELOAD-HINT`, `RENDITION-REPORT`, or `EXT-X-SKIP` handling. KitePlayer consequently has no part loader, blocking-reload scheduler, rendition report, preload-splice state, live-edge target, or adaptive speed policy.
- Severity if real: P1 broken feature
