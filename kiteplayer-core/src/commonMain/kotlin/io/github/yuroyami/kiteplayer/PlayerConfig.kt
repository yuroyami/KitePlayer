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
 * [KitePlayer] instead. This removes a whole class of bug that libmpv has and documents: an option
 * set after initialisation that is silently ignored.
 */
public data class PlayerConfig(
    val syncMode: SyncMode = SyncMode.Auto,
    val hardwareDecode: HwdecPolicy = HwdecPolicy.Auto,
    val frameDrop: FrameDropPolicy = FrameDropPolicy.LateOnly,
    val buffer: BufferPolicy = BufferPolicy(),
    val audio: AudioConfig = AudioConfig(),
    val subtitles: SubtitleConfig = SubtitleConfig(),
    /** How often [KitePlayer.progress] is sampled while playing. */
    val progressInterval: Duration = 200.milliseconds,
    /** How often [KitePlayer.stats] is sampled. */
    val statsInterval: Duration = 1.seconds,
    val logger: PlayerLogger? = null,
    /** The backends to build the pipeline from. Replace them for a test or a new platform. */
    val backends: Backends = Backends(),
)

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

    /** Only these kinds, tried in this order. */
    public data class Prefer(val order: List<HwdecKind>) : HwdecPolicy()
}

/**
 * How much to read ahead, and when to declare that playback can start.
 *
 * The defaults come from the values ffplay and mpv converged on after a decade of bug reports.
 * See KITEPLAYER.md section 17 before changing any of them.
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
    /** How far back a live stream may seek. */
    val liveBackBuffer: Duration = 20.seconds,
    /** Drop to the live edge when further behind than this. */
    val liveMaxLag: Duration = 10.seconds,
)

public data class AudioConfig(
    /** Preferred language tags, best first, matched against the container's track languages. */
    val preferredLanguages: List<String> = emptyList(),
    /** Play at a different rate without changing pitch. False is cheaper and sounds wrong. */
    val preservePitch: Boolean = true,
    /**
     * Latency to assume when the sink reports [LatencyQuality.Unreliable]. A wrong value here is a
     * constant A/V offset, so it is exposed rather than hidden.
     */
    val assumedLatencyWhenUnreliable: Duration = 80.milliseconds,
    /** Start with audio disabled. Useful for a thumbnail scrubber. */
    val startDisabled: Boolean = false,
)

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
 * Leaving these null selects the platform default, which is whatever backend module is on the
 * classpath. Supplying them is how a test injects fakes, and how a new platform is reached without
 * touching the engine.
 */
public data class Backends(
    val source: MediaSourceFactory? = null,
    val videoDecoders: List<VideoDecoderFactory> = emptyList(),
    val audioSink: AudioSinkFactory? = null,
    val clock: MonotonicClock = MonotonicClock.System,
)
