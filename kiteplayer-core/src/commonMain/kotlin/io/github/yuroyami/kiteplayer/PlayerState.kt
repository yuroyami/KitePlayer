package io.github.yuroyami.kiteplayer

import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO

/**
 * Everything about the player that changes rarely, as one immutable value.
 *
 * Position is deliberately not here. A position field would make this snapshot change sixty times
 * a second, and every consumer collecting it would recompose or redraw at that rate for a value
 * most of them wanted at 4 Hz. Read the position from [KitePlayer.position] when you need it now, or
 * collect [KitePlayer.progress] when you want it on a timer.
 */
public data class PlayerSnapshot(
    val status: PlaybackStatus = PlaybackStatus.Idle,
    val media: MediaItem? = null,
    /** Null when the duration is genuinely unknown, for example a live stream. */
    val duration: Duration? = null,
    val seekable: Boolean = false,
    val videoSize: VideoSize? = null,
    val tracks: Tracks = Tracks.Empty,
    /**
     * The chapters of the current media item, in container order (S4.b read them, S4.e surfaced
     * them). Empty for media with no chapter table.
     */
    val chapters: List<Chapter> = emptyList(),
    val speed: Double = 1.0,
    val volume: Float = 1.0f,
    val muted: Boolean = false,
    val loop: LoopMode = LoopMode.Off,
    /** How the picture occupies its surface. The engine's choice; renderers honour it. */
    val videoScale: VideoScale = VideoScale.Fit,
    /** The live picture controls (brightness, contrast, saturation, hue). Renderers honour them. */
    val videoAdjustments: VideoAdjustments = VideoAdjustments.Identity,
    /** The render-quality passes in force (17.21). */
    val renderQuality: RenderQuality = RenderQuality.Off,
    /** The live framing controls (aspect override, zoom, pan). Renderers honour them. */
    val videoTransform: VideoTransform = VideoTransform.Identity,
    /** The runtime subtitle timing shift. Positive shows cues later. */
    val subtitleDelay: Duration = Duration.ZERO,
    /** The runtime subtitle size multiplier over the authored size. */
    val subtitleScale: Float = 1.0f,
    /** Where the implicit subtitle stack anchors, as a fraction of the height. 1.0 is the bottom. */
    val subtitlePosition: Float = 1.0f,
    /** The runtime audio timing shift. Positive presents video earlier to meet late sound. */
    val audioDelay: Duration = Duration.ZERO,
    /** The A of the armed A-B loop, or null when none is armed. */
    val abLoopA: Duration? = null,
    /** The B of the armed A-B loop. Null with a non-null [abLoopA] wraps at the end of the media. */
    val abLoopB: Duration? = null,
    /** Whether [speed] keeps pitch. False plays rate through the resampler, pitch moving with it. */
    val preservePitch: Boolean = true,
    /**
     * The last failure, retained until another one replaces it or the player is opened again.
     *
     * Set when [status] becomes [PlaybackStatus.Failed], and deliberately not cleared when the status
     * moves on. An event stream replays nothing to a consumer that subscribes late, so if the snapshot
     * forgot the error too, a fatal failure would have no record at all for anyone who was not already
     * listening. State conflates and is therefore the right place for the one fact a consumer must not
     * miss.
     */
    val error: PlaybackError? = null,
    /** Increments on every seek and every stream reconfiguration. */
    val generation: Generation = Generation.Initial,
    /** The open queue's items (S4.e). Empty outside queue playback. */
    val queue: List<MediaItem> = emptyList(),
    /** The index of [media] inside [queue], or -1 outside queue playback. */
    val queueIndex: Int = -1,
)

/**
 * What the player is doing.
 *
 * [Buffering] is a status of its own rather than a flag on [Playing], because every application
 * shows a spinner for it and every application gets the flag version wrong. The player is
 * [Buffering] when the user asked for playback and the engine cannot supply it. It is [Paused]
 * when the user asked for that. The two are never confused.
 */
public enum class PlaybackStatus {
    Idle,
    Opening,
    Buffering,
    Playing,
    Paused,
    Ended,
    Failed,
    ;

    /** True when the engine is trying to advance the timeline, whether or not it can. */
    public val isActive: Boolean get() = this == Playing || this == Buffering
}

/** Position and buffered extent, sampled on a timer. */
public data class Progress(
    val position: Duration = ZERO,
    /** How far ahead of [position] the demuxer has read, as a duration of media. */
    val bufferedAhead: Duration = ZERO,
    /**
     * Contiguous ranges held by the engine's byte cache (KPKMP 17.12 M5). One entry today: the
     * cache keeps a single window over the bytes, so one range covers the free seek-back span
     * plus what has been read ahead. Byte positions map to time PROPORTIONALLY (byte fraction
     * times duration), which is exact for constant bitrate and approximate for variable.
     *
     * Empty for opens that read through no [MediaIo] (a local file on FFmpeg's own path), when
     * the cache is disabled, or when the source declares no size or duration to map against.
     */
    val bufferedRanges: List<ClosedRange<Duration>> = emptyList(),
)

/**
 * Diagnostics, for an overlay or a bug report.
 *
 * The counters here are monotonic totals, not per-interval values, and that is deliberate. A
 * consumer that wants a rate diffs two snapshots. Publishing occurrences as events instead would
 * be wrong: a state feed coalesces by design, so an event stream of "a frame was dropped" is
 * exactly the kind of thing that silently loses entries. libmpv learned this the hard way and had
 * to add three logical timestamps per observer to compensate.
 *
 * ### Scheduler counters, not renderer counters
 *
 * Every frame figure here is a decision the engine's schedule made or an outcome it can see with its
 * own eyes: [submittedFrames] means a renderer accepted the frame, [headlessFrames] means there was
 * no renderer to accept it, and [droppedFramesLate] and [repeatedFrames] are the schedule's own
 * choices about pacing. None of them says that a pixel reached a display.
 *
 * What was drawn is the renderer's own count, and it is deliberately not mirrored here. A renderer
 * that takes a frame may still supersede it with a newer one or fail to draw it, and it counts those
 * outcomes itself: the Core Graphics renderer in `kiteplayer-output` publishes `presentedFrames`,
 * `supersededFrames` and `failedFrames`, whose sum is what the engine submitted to it. Read the pair
 * of numbers side by side: submitted against drawn is the difference between the engine keeping up
 * and the output keeping up, and copying the second group into this class would only invent a figure
 * the engine cannot know, because no member of the renderer interface reports one. Per-submission
 * terminal feedback is the roadmap item that would change that; see KPKMP-PAST.md section 11 (B5).
 */
public data class PlaybackStats(
    val decodedVideoFrames: Long = 0,
    /**
     * Frames a renderer accepted from the schedule.
     *
     * Accepted is not drawn, and the two are different numbers. A renderer may take a frame and then
     * supersede it with a newer one, or fail to draw it at all, and it counts those outcomes itself:
     * `presentedFrames`, `supersededFrames` and `failedFrames` on the renderer are the drawing truth.
     * This is what the engine handed over, which is the only part the engine can know.
     */
    val submittedFrames: Long = 0,
    /**
     * Frames the schedule presented with no renderer attached.
     *
     * A detached renderer must not stop playback, so these frames were paced and closed exactly like
     * the others and were simply never drawn anywhere. Counted apart so a zero picture with a healthy
     * sound is distinguishable from a broken pipeline.
     */
    val headlessFrames: Long = 0,
    /** Frames the schedule decided were too late to show. A scheduler decision, not a renderer one. */
    val droppedFramesLate: Long = 0,
    /**
     * Frames a renderer was handed and refused, for example because its surface is gone.
     *
     * Counted apart from [droppedFramesLate], which it used to be folded into. They are different
     * diagnoses with different fixes: a late drop says the pipeline could not keep up, a refusal
     * says the output could not draw, and one number for both made a dead surface read as a slow
     * decoder. A run with a healthy [decodedVideoFrames] and a rising figure here
     * is a picture problem and nothing else.
     */
    val refusedFrames: Long = 0,
    /**
     * Packets dropped before they were decoded.
     *
     * Always zero: nothing drops a packet before decoding it, which is what [FrameDropPolicy.LateAndDecode]
     * would need. Not implemented yet; see the roadmap in KPKMP-PAST.md section 11.
     */
    val droppedFramesDecode: Long = 0,
    /** Frames the schedule showed for longer than their own duration, counted once each. */
    val repeatedFrames: Long = 0,
    val audioUnderruns: Long = 0,
    val rebuffers: Long = 0,
    /**
     * Events [KitePlayer.events] could not take because its buffer was full.
     *
     * A collector slower than the session makes this rise, and every one of those is an occurrence
     * a consumer counting on the event stream never saw. It was silent before: the engine ignored
     * the answer `tryEmit` gives it, so a lost seek completion looked exactly like one that never
     * happened. Anything above zero means the event feed is not a complete record
     * for this session, and the fix is a faster collector.
     *
     * Not the same thing as an event nobody was listening for. This flow replays nothing to a late
     * collector by design, and that case is undetectable and deliberate; it is also why every
     * warning is additionally kept in [KitePlayer.warningHistory].
     */
    val droppedEvents: Long = 0,
    /**
     * Video clock minus the master clock at the last presented frame. Positive means video is AHEAD
     * of the master clock.
     *
     * That sign convention is the same one everywhere in this project, including
     * `VideoPlayback.drift`, which is where this figure comes from. One convention, chosen once, so
     * no reader has to work out which way round a drift number runs.
     */
    val avDrift: Duration = ZERO,
    val videoDecodeFps: Double = 0.0,
    val videoQueueDepth: Duration = ZERO,
    val audioQueueDepth: Duration = ZERO,
    /**
     * What the audio sink reports as handed over but not yet audible.
     *
     * Always zero. The audio clock is anchored to the instant the device says a specific frame becomes
     * audible, so no latency figure is needed to keep time, and `AudioSink.latencyNanos` has no reader.
     * Not implemented yet; see the roadmap in KPKMP-PAST.md section 11.
     */
    val audioLatency: Duration = ZERO,
    val audioLatencyQuality: LatencyQuality = LatencyQuality.Unreliable,
    val hardwareDecode: HwdecStatus = HwdecStatus.Software,
    /**
     * The container's overall bitrate.
     *
     * Always null: a source reports a bitrate per stream and none reports one for the container.
     * Not implemented yet; see the roadmap in KPKMP-PAST.md section 11.
     */
    val containerBitrate: Long? = null,
    val syncMode: SyncMode = SyncMode.Auto,
    val masterClock: MasterClock = MasterClock.None,
)

public data class VideoSize(
    val width: Int,
    val height: Int,
    /** Pixel aspect ratio numerator over denominator. 1 to 1 for square pixels. */
    val pixelAspectNumerator: Int = 1,
    val pixelAspectDenominator: Int = 1,
) {
    /** The size to lay out at, after applying a non-square pixel aspect. */
    public val displayWidth: Int
        get() {
            if (pixelAspectDenominator == 0) return width
            // In Long: container SAR values are unvalidated Ints, and width * numerator can wrap
            // before the divide. Clamped rather than thrown, because this is display layout of
            // hostile metadata, not an allocation.
            val scaled = width.toLong() * pixelAspectNumerator.toLong() / pixelAspectDenominator.toLong()
            return scaled.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        }

    public val displayAspect: Float
        get() = if (height == 0) 0f else displayWidth.toFloat() / height.toFloat()
}

public enum class LoopMode {
    /** Play once and stop. */
    Off,

    /** Repeat the current media item. */
    One,

    /**
     * Repeat the whole queue: reaching the end of the last item wraps round to the first.
     *
     * With a queue of one item, or plain opened media, this repeats the current item exactly like
     * [One], because a whole queue of one IS the current item. A source that cannot seek refuses
     * the repeat with a typed warning and stays ended, rather than failing the session.
     */
    All,
}

public enum class SyncMode {
    /** Audio drives the clock when there is audio, video otherwise. The right default. */
    Auto,
    AudioMaster,
    VideoMaster,

    /**
     * A wall clock drives playback and audio is resampled to follow it.
     *
     * Nothing drives playback from an external clock, and nothing resamples audio to follow one.
     * Not implemented yet; see the roadmap in KPKMP-PAST.md section 11.
     */
    ExternalMaster,
}

/**
 * Which clock is actually in charge right now, as opposed to which was requested.
 *
 * [External] is never reported, because [SyncMode.ExternalMaster] is not implemented.
 * Not implemented yet; see the roadmap in KPKMP-PAST.md section 11.
 */
public enum class MasterClock { None, Audio, Video, External }

/**
 * How much a sink's latency figure can be trusted.
 *
 * A sink reports one of these and the engine's tolerances do not change. The only response today is
 * a single warning when a sink says [Unreliable], and the one sink that exists says [Estimated].
 * Not implemented yet; see the roadmap in KPKMP-PAST.md section 11.
 */
public enum class LatencyQuality {
    /** The platform reports a real measured figure. */
    Exact,

    /** A figure that moves around and needs low-pass filtering before it is used. */
    Estimated,

    /** No usable figure at all. */
    Unreliable,
}

/**
 * How the current video track is being decoded.
 *
 * This is current decoder state, not the policy requested at open. It can change from a hardware
 * value to [Software] when a recoverable decoder failure causes a runtime fallback.
 */
public sealed class HwdecStatus {
    /** Decoding in software. */
    public data object Software : HwdecStatus()

    /** Hardware decoding, with frames staying in GPU memory all the way to the screen. */
    public data class HardwareZeroCopy(val kind: HwdecKind) : HwdecStatus()

    /** Hardware decoding, but frames are downloaded to main memory before presentation. */
    public data class HardwareWithDownload(val kind: HwdecKind) : HwdecStatus()
}

/**
 * A hardware decoding API.
 *
 * Backends use these values for ordered preference and report the active kind through [HwdecStatus].
 * A kind unsupported on the current platform is skipped; naming one does not make its platform API
 * available.
 */
public enum class HwdecKind {
    VideoToolbox,
    MediaCodec,
    Vaapi,
    D3d11va,
    Nvdec,
    /** The browser decodes and the engine never sees pixels. */
    WebCodecs,
}

public enum class FrameDropPolicy {
    /** Never drop. The picture stays complete and sync may drift on slow hardware. */
    Never,

    /** Drop frames whose presentation time has already passed. The default. */
    LateOnly,

    /**
     * Also drop before decoding, when the decoder cannot keep up. Needed for 4K on weak hardware.
     *
     * Nothing drops a packet before decoding it, so this behaves like [LateOnly].
     * Not implemented yet; see the roadmap in KPKMP-PAST.md section 11.
     */
    LateAndDecode,
}

/**
 * One chapter of the current media item, read from the container's own table (S4.b) and
 * surfaced on the facade with [io.github.yuroyami.kiteplayer.KitePlayer.chapterAt],
 * seekToChapter and [PlayerEvent.ChapterChanged] (S4.e).
 */
public data class Chapter(
    val index: Int,
    val start: Duration,
    val end: Duration?,
    val title: String?,
) {
    /**
     * True when [positionMicros] falls inside this chapter: at or after [start], and before [end].
     *
     * The half-open reading is what makes a boundary belong to exactly one chapter. A null [end]
     * means the chapter runs to the end of the media, which is what a container writes for its last
     * one and sometimes for all of them.
     */
    internal fun holds(positionMicros: Long): Boolean =
        start.inWholeMicroseconds <= positionMicros &&
            (end == null || positionMicros < end.inWholeMicroseconds)
}

/**
 * The chapter whose span holds [positionMicros], or null when none does.
 *
 * The one place this question is answered, because it used to be answered twice and both answers
 * ignored [Chapter.end]: a position in a GAP between chapters reported the expired one, so a file
 * with real gaps announced a chapter that had already finished. Searched from the
 * end so overlapping tables resolve to the latest chapter that starts at or before the position,
 * which is the same tie-break the previous readings used.
 */
internal fun List<Chapter>.chapterHolding(positionMicros: Long): Chapter? =
    lastOrNull { it.holds(positionMicros) }
