package io.github.yuroyami.kiteplayer

import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO

/**
 * Everything about the player that changes rarely, as one immutable value.
 *
 * Position is deliberately not here. A position field would make this snapshot change sixty times
 * a second, and every consumer collecting it would recompose or redraw at that rate for a value
 * most of them wanted at 4 Hz. The player reads the position on demand, and publishes it on a timer
 * as [Progress], as two separate things.
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
     * The chapters of the current media item.
     *
     * Always empty: no source reads a chapter list out of a container.
     * Not implemented yet; see the roadmap in KPKMP.md section 11.
     */
    val chapters: List<Chapter> = emptyList(),
    val speed: Double = 1.0,
    val volume: Float = 1.0f,
    val muted: Boolean = false,
    val loop: LoopMode = LoopMode.Off,
    /** Set only when [status] is [PlaybackStatus.Failed]. */
    val error: PlaybackError? = null,
    /** Increments on every seek and every stream reconfiguration. */
    val generation: Generation = Generation.Initial,
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
    /** Contiguous ranges held in the read cache. One entry for a plain linear read. */
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
 */
public data class PlaybackStats(
    val decodedVideoFrames: Long = 0,
    val presentedFrames: Long = 0,
    val droppedFramesLate: Long = 0,
    val droppedFramesDecode: Long = 0,
    val repeatedFrames: Long = 0,
    val audioUnderruns: Long = 0,
    val rebuffers: Long = 0,
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
    /** What the audio sink reports as handed over but not yet audible. */
    val audioLatency: Duration = ZERO,
    val audioLatencyQuality: LatencyQuality = LatencyQuality.Unreliable,
    val hardwareDecode: HwdecStatus = HwdecStatus.Software,
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
        get() = if (pixelAspectDenominator == 0) width
        else width * pixelAspectNumerator / pixelAspectDenominator

    public val displayAspect: Float
        get() = if (height == 0) 0f else displayWidth.toFloat() / height.toFloat()
}

public enum class LoopMode {
    /** Play once and stop. */
    Off,

    /** Repeat the current media item. */
    One,

    /**
     * Repeat the whole queue.
     *
     * There is no queue and no playlist, so there is nothing for this to repeat.
     * Not implemented yet; see the roadmap in KPKMP.md section 11.
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
     * Not implemented yet; see the roadmap in KPKMP.md section 11.
     */
    ExternalMaster,
}

/**
 * Which clock is actually in charge right now, as opposed to which was requested.
 *
 * [External] is never reported, because [SyncMode.ExternalMaster] is not implemented.
 * Not implemented yet; see the roadmap in KPKMP.md section 11.
 */
public enum class MasterClock { None, Audio, Video, External }

/**
 * How much a sink's latency figure can be trusted.
 *
 * A sink reports one of these and the engine's tolerances do not change. The only response today is
 * a single warning when a sink says [Unreliable], and the one sink that exists says [Estimated].
 * Not implemented yet; see the roadmap in KPKMP.md section 11.
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
 * Only [Software] is ever reported: no decoder in this library uses a hardware device.
 * Not implemented yet; see the roadmap in KPKMP.md section 11.
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
 * This is naming for a capability nothing has: no target decodes in hardware.
 * Not implemented yet; see the roadmap in KPKMP.md section 11.
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
     * Not implemented yet; see the roadmap in KPKMP.md section 11.
     */
    LateAndDecode,
}

/**
 * One chapter of the current media item.
 *
 * Nothing produces one: no source reads a container's chapter list, and no boundary is ever crossed.
 * Not implemented yet; see the roadmap in KPKMP.md section 11.
 */
public data class Chapter(
    val index: Int,
    val start: Duration,
    val end: Duration?,
    val title: String?,
)
