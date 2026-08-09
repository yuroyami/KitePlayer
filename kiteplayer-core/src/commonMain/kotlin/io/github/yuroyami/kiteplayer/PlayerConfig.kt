package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.spi.MediaBackend
import io.github.yuroyami.kiteplayer.spi.OutputBackend
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Everything that is decided when the player is created.
 *
 * Config is immutable and passed once. Anything genuinely changeable while playing is a method on
 * [KitePlayer] instead. This removes a whole class of bug that libmpv has and documents: an option
 * set after initialisation that is silently ignored.
 *
 * The engine's session core reads these values: the sync mode, the frame drop policy, every buffering
 * threshold, the audio language preference, the two publication intervals, and the backends. The members
 * nothing reads are marked one by one below, each with a pointer to where they are decided.
 */
public data class PlayerConfig(
    val syncMode: SyncMode = SyncMode.Auto,
    /**
     * What to do about hardware decoding.
     *
     * Passed to every video decoder factory. No decoder in this library uses a hardware device, so
     * [HwdecPolicy.Auto], [HwdecPolicy.Off] and [HwdecPolicy.Prefer] all decode in software and behave
     * identically. [HwdecPolicy.Require] is the one value with an effect, and it is honoured the only way
     * it can be: the factory supplies no decoder, so the open fails rather than quietly falling back.
     * Not implemented yet; see the roadmap in KPKMP.md section 11.
     */
    val hardwareDecode: HwdecPolicy = HwdecPolicy.Auto,
    val frameDrop: FrameDropPolicy = FrameDropPolicy.LateOnly,
    val buffer: BufferPolicy = BufferPolicy(),
    val audio: AudioConfig = AudioConfig(),
    /**
     * Subtitle selection, timing and styling.
     *
     * No cue reaches a screen, so none of these values changes anything.
     * Not implemented yet; see the roadmap in KPKMP.md section 11.
     */
    val subtitles: SubtitleConfig = SubtitleConfig(),
    /** How often [KitePlayer.progress] is sampled while playing. */
    val progressInterval: Duration = 200.milliseconds,
    /** How often [KitePlayer.stats] is sampled. */
    val statsInterval: Duration = 1.seconds,
    /**
     * Where the engine's diagnostics go.
     *
     * Nothing in the engine writes a log line, so supplying a logger shows nothing.
     * Not implemented yet; see the roadmap in KPKMP.md section 11.
     */
    val logger: PlayerLogger? = null,
    /** The backends to build the pipeline from. Replace them for a test or a new platform. */
    val backends: Backends = Backends(),
)

/**
 * Whether to decode on a hardware device, and what to do when one is not available.
 *
 * Every decoder in this library decodes in software and no target has a hardware path at all, so the
 * only value that changes anything is [Require], which is refused. The rest name an intent nothing can
 * act on yet. Not implemented yet; see the roadmap in KPKMP.md section 11.
 */
public sealed class HwdecPolicy {
    /** Try hardware, fall back to software with a warning. The right default. */
    public data object Auto : HwdecPolicy()

    /** Never use hardware decoding. */
    public data object Off : HwdecPolicy()

    /**
     * Fail to open rather than fall back to software. For an application that must not decode 4K
     * on a phone's CPU under any circumstances.
     *
     * Honoured, and the only value here that is: no factory can supply a hardware decoder, so every
     * factory refuses the stream and the open fails with [PlaybackError.NoPlayableStream] after a
     * [PlaybackWarning.HardwareDecodeUnavailable]. A caller that asks for this gets a refusal rather than
     * silent software decoding, which is what asking for it means.
     */
    public data object Require : HwdecPolicy()

    /**
     * Only these kinds, tried in this order.
     *
     * Nothing reads the order, because nothing tries a kind.
     * Not implemented yet; see the roadmap in KPKMP.md section 11.
     */
    public data class Prefer(val order: List<HwdecKind>) : HwdecPolicy()
}

/**
 * How much to read ahead, and when to declare that playback can start.
 *
 * The defaults come from the values ffplay and mpv converged on after a decade of bug reports.
 * Do not retune them without evidence from real content.
 */
public data class BufferPolicy(
    /** A stream is ready when it has this much buffered, or this many packets, or has ended. */
    val readyDuration: Duration = 1.seconds,
    val readyPackets: Int = 25,
    /** Per-stream soft target. Exceeding it only marks the stream as well buffered. */
    val softTarget: Duration = 5.seconds,
    /** The demux worker stalls when the total across all streams reaches either of these. */
    val totalBytes: Long = 32L * 1024 * 1024,
    val totalDuration: Duration = 30.seconds,
    /** Decoded video frames held ahead of the one on screen. Bounded by the hardware pool. */
    val videoFrameQueue: Int = 4,
    /**
     * How far back a live stream may seek.
     *
     * There is no live path: no network source, no live window, no seekable range published for one.
     * Not implemented yet; see the roadmap in KPKMP.md section 11.
     */
    val liveBackBuffer: Duration = 20.seconds,
    /**
     * Drop to the live edge when further behind than this.
     *
     * Not implemented yet; see the roadmap in KPKMP.md section 11.
     */
    val liveMaxLag: Duration = 10.seconds,
)

public data class AudioConfig(
    /** Preferred language tags, best first, matched against the container's track languages. */
    val preferredLanguages: List<String> = emptyList(),
    /**
     * Play at a different rate without changing pitch. False is cheaper and sounds wrong.
     *
     * There is no tempo stage, so pitch is never preserved at any speed.
     * Not implemented yet; see the roadmap in KPKMP.md section 11.
     */
    val preservePitch: Boolean = true,
    /**
     * Latency to assume when the sink reports [LatencyQuality.Unreliable]. A wrong value here is a
     * constant A/V offset, so it is exposed rather than hidden.
     *
     * No sink reports that quality and no code reads this value.
     * Not implemented yet; see the roadmap in KPKMP.md section 11.
     */
    val assumedLatencyWhenUnreliable: Duration = 80.milliseconds,
    /**
     * Start with audio disabled. Useful for a thumbnail scrubber.
     *
     * Not implemented yet; see the roadmap in KPKMP.md section 11.
     */
    val startDisabled: Boolean = false,
)

/**
 * Which subtitle track to pick, when to show its cues, and how large to draw them.
 *
 * Nothing in the player reads any of this. The SubRip parser in `kiteplayer-subtitles` is not
 * connected to playback, and no cue is timed, styled or drawn.
 * Not implemented yet; see the roadmap in KPKMP.md section 11.
 */
public data class SubtitleConfig(
    /** Select a subtitle track automatically when one matches these languages. */
    val preferredLanguages: List<String> = emptyList(),
    /** Select a forced-subtitles track when the audio language is not a preferred one. */
    val autoSelectForced: Boolean = true,
    /** Shift every cue by this much. Positive shows cues later. */
    val delay: Duration = Duration.ZERO,
    /** How far ahead cues are parsed and held. */
    val lookahead: Duration = 5.seconds,
    /** Scale applied to the authored font size. */
    val fontScale: Float = 1.0f,
)

/**
 * The two implementations the engine builds its pipeline from.
 *
 * Both are passed in explicitly. Nothing is discovered: Kotlin/Native has no classpath service
 * lookup, so a null here means the pipeline cannot be built, never that a platform default was
 * found. Supplying them is how a test injects fakes, and how a new platform is reached without
 * touching the engine.
 *
 * Two objects rather than a bag of factories, because both groupings are load bearing. A
 * [MediaBackend] hands over a source and the decoder factories that belong to it as one session, so
 * the engine never has to reach a backend's internals to find its decoders. An [OutputBackend] pairs
 * the clock with the sink that reports on it, so a clock and a sink that measure different time bases
 * cannot be assembled at all.
 *
 * [KitePlayer.create] resolves both and refuses to build a player without them, with
 * [PlaybackError.ConfigurationInvalid] naming what to pass. On macOS that pair is
 * `KiteCodecMediaBackend()` from `kiteplayer-ffmpeg` and `AppleOutputBackend` from `kiteplayer-output`.
 */
public data class Backends(
    val backend: MediaBackend? = null,
    val output: OutputBackend? = null,
)
