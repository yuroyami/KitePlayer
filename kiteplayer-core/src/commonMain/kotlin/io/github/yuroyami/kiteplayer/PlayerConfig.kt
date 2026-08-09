package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.spi.AudioSinkFactory
import io.github.yuroyami.kiteplayer.spi.MediaSourceFactory
import io.github.yuroyami.kiteplayer.spi.VideoDecoderFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Everything that is decided when the player is created.
 *
 * Config is immutable and passed once. Anything genuinely changeable while playing is a method on
 * the player itself instead. This removes a whole class of bug that libmpv has and documents: an
 * option set after initialisation that is silently ignored.
 *
 * The player class this configures is not written yet, so nothing reads these values today. The
 * members that will still be unimplemented once it lands are marked one by one below.
 */
public data class PlayerConfig(
    val syncMode: SyncMode = SyncMode.Auto,
    /**
     * What to do about hardware decoding.
     *
     * No decoder in this library uses a hardware device, so every value behaves the same way.
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
    /** How often the player's progress flow is sampled while playing. */
    val progressInterval: Duration = 200.milliseconds,
    /** How often the player's statistics flow is sampled. */
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
 * Nothing here is honoured. Every decoder in this library decodes in software, and no target has a
 * hardware path at all. Not implemented yet; see the roadmap in KPKMP.md section 11.
 */
public sealed class HwdecPolicy {
    /** Try hardware, fall back to software with a warning. The right default. */
    public data object Auto : HwdecPolicy()

    /** Never use hardware decoding. */
    public data object Off : HwdecPolicy()

    /**
     * Fail to open rather than fall back to software. For an application that must not decode 4K
     * on a phone's CPU under any circumstances.
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
 * The implementations the engine builds its pipeline from.
 *
 * Every backend is passed in explicitly. Nothing is discovered: Kotlin/Native has no classpath
 * service lookup, so a null here means the pipeline cannot be built, never that a platform default
 * was found. Supplying them is how a test injects fakes, and how a new platform is reached without
 * touching the engine.
 *
 * This shape cannot build a generic session yet: it has no audio decoder factory and no subtitle
 * decoder factory, and it lets a clock be paired with a sink that does not use it. The
 * session-shaped replacement is decided in KPKMP.md (defect D34) and lands with the player class.
 */
public data class Backends(
    val source: MediaSourceFactory? = null,
    val videoDecoders: List<VideoDecoderFactory> = emptyList(),
    val audioSink: AudioSinkFactory? = null,
    val clock: MonotonicClock = MonotonicClock.System,
)
