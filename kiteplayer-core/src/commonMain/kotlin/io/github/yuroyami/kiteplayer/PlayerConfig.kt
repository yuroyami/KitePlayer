package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.GAIN_MAX
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
    /**
     * How much work renderers spend on the picture beyond decoding it correctly (17.21).
     *
     * Off by default, which is the pre-17.21 pipeline exactly. Change it live with
     * [KitePlayer.setRenderQuality]; this is only the value a fresh player starts at.
     */
    val renderQuality: RenderQuality = RenderQuality.Off,

    val syncMode: SyncMode = SyncMode.Auto,
    /**
     * What to do about hardware decoding.
     *
     * Passed to every video decoder factory. A backend may select a platform decoder, fall back to
     * software when policy permits, or refuse the stream when hardware is required. The decoder's
     * current path is reported separately by [PlaybackStats.hardwareDecode].
     */
    val hardwareDecode: HwdecPolicy = HwdecPolicy.Auto,
    val frameDrop: FrameDropPolicy = FrameDropPolicy.LateOnly,
    val buffer: BufferPolicy = BufferPolicy(),
    val audio: AudioConfig = AudioConfig(),
    /**
     * Subtitle selection, timing and styling. The session core reads the language preferences and
     * the forced-track rule when it picks a track, applies [SubtitleConfig.delay] when it times
     * cues, and passes [SubtitleConfig.fontScale] to the platform rasterizer.
     */
    val subtitles: SubtitleConfig = SubtitleConfig(),
    /** How often [KitePlayer.progress] is sampled while playing. */
    val progressInterval: Duration = 200.milliseconds,
    /** How often [KitePlayer.stats] is sampled. */
    val statsInterval: Duration = 1.seconds,
    /** The backends to build the pipeline from. Replace them for a test or a new platform. */
    val backends: Backends = Backends(),
    /**
     * Network byte supply and the engine's byte cache. The resolver
     * turns URIs into [MediaIoResolver]-supplied readers at open; the cache wraps every
     * [MediaIo]-fed open with a forward window and a RAM seek-back window.
     */
    val network: NetworkConfig = NetworkConfig(),
    /**
     * False opens media with video decoding parked: audio plays, no video frame is decoded.
     *
     * For an application that knows from the start it is only playing sound. Change it while
     * playing with [KitePlayer.setVideoEnabled], which parks and resumes in place without
     * reopening anything.
     */
    val videoEnabled: Boolean = true,
) {
    init {
        // Validated at construction, before a player exists to be wedged by it: a nonpositive
        // interval is a hot publication loop, and each nested policy carries its own checks
        // (audit P1-19).
        require(progressInterval > Duration.ZERO) { "progressInterval must be positive, was $progressInterval" }
        require(statsInterval > Duration.ZERO) { "statsInterval must be positive, was $statsInterval" }
    }
}

/**
 * Whether to decode on a hardware device, and what to do when one is not available.
 *
 * A backend applies this policy when it creates a video decoder. The policy controls whether hardware
 * may be tried and whether a software fallback is legal; [PlaybackStats.hardwareDecode] reports what
 * the decoder is actually using after open and after any runtime fallback.
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
     * A backend that cannot open an eligible hardware decoder refuses the stream rather than silently
     * opening its software decoder. Runtime decoder failures are likewise not demoted to software.
     */
    public data object Require : HwdecPolicy()

    /**
     * Only these kinds, tried in this order.
     *
     * A backend tries only kinds it supports and preserves this order. Kinds belonging to another
     * platform are skipped rather than treated as permission to call that platform's APIs.
     */
    public data class Prefer(val order: List<HwdecKind>) : HwdecPolicy()
}

/**
 * How media bytes arrive over a network, and how the engine caches them (M1, M5).
 */
public data class NetworkConfig(
    /**
     * Consulted at open for a [MediaItem] that carries a URI and no [MediaItem.io]. Null (the
     * default) means URIs go to the backend untouched. kiteplayer-network ships the Ktor
     * resolver that makes http and https play with the OS supplying TLS.
     */
    val ioResolver: MediaIoResolver? = null,
    /** The engine-owned byte cache every [MediaIo]-fed open gets. */
    val ioCache: IoCachePolicy = IoCachePolicy(),
)

/**
 * The M5 byte cache: one contiguous RAM window over an [MediaIo]'s bytes. Reads pull
 * [readChunkBytes] at a time and append to the window; a seek that lands inside the window is
 * served from RAM without touching the source, which is what makes a small seek-back free on a
 * network stream. The window keeps at least [backWindowBytes] behind the cursor before anything
 * is evicted, and [forwardWindowBytes] in total; [Progress.bufferedRanges] reports the window,
 * time-mapped.
 */
public data class IoCachePolicy(
    val enabled: Boolean = true,
    /** How much one upstream read pulls. Bigger means fewer network round trips. */
    val readChunkBytes: Int = 256 * 1024,
    /** How many bytes behind the cursor stay in RAM, at least, for free backward seeks. */
    val backWindowBytes: Long = 8L * 1024 * 1024,
    /** The whole window's byte budget, back window included. */
    val forwardWindowBytes: Long = 32L * 1024 * 1024,
) {
    init {
        require(readChunkBytes > 0) { "readChunkBytes must be positive, was $readChunkBytes" }
        require(backWindowBytes >= 0) { "backWindowBytes must not be negative, was $backWindowBytes" }
        require(forwardWindowBytes > backWindowBytes) {
            "forwardWindowBytes ($forwardWindowBytes) must exceed backWindowBytes ($backWindowBytes)"
        }
    }
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
) {
    init {
        // A budget of zero or less never admits a packet and wedges the demuxer before the first
        // read; an empty frame queue can never present. Refused here, before any native resource
        // is acquired.
        require(readyDuration >= Duration.ZERO) { "readyDuration must not be negative, was $readyDuration" }
        require(readyPackets > 0) { "readyPackets must be positive, was $readyPackets" }
        require(softTarget > Duration.ZERO) { "softTarget must be positive, was $softTarget" }
        require(totalBytes > 0) { "totalBytes must be positive, was $totalBytes" }
        require(totalDuration > Duration.ZERO) { "totalDuration must be positive, was $totalDuration" }
        // Two, not one: timing a frame needs the NEXT frame's timestamp, which is FrameQueue's
        // own bound. One slot passed here and crashed the first open instead.
        require(videoFrameQueue >= 2) { "videoFrameQueue must hold at least two frames, was $videoFrameQueue" }
    }
}

public data class AudioConfig(
    /** Preferred language tags, best first, matched against the container's track languages. */
    val preferredLanguages: List<String> = emptyList(),
    /**
     * Play at a different rate without changing pitch. True runs the tempo stage; false folds
     * the rate into the resampler, which is cheaper and shifts pitch with the rate. The seed
     * for [KitePlayer.setPreservePitch], which can change it at runtime.
     */
    val preservePitch: Boolean = true,
    /** How multichannel audio is folded down when the device has fewer speakers. */
    val downmix: DownmixConfig = DownmixConfig(),
    /**
     * The loudest [KitePlayer.setVolume] accepts. 1 is unity and refuses any boost, which is the
     * default because amplifying without being asked to is not a player's decision to make.
     *
     * Raise it to offer a boost, up to 2. Above unity the ring folds each sample through a
     * saturator instead of letting it clip, so a loud passage compresses rather than squaring off;
     * at or below unity nothing is folded and the samples are what they always were, bit for bit.
     *
     * Last in the list on purpose: inserting it earlier would move `downmix`'s position and break
     * every caller that passed it positionally.
     */
    val volumeCeiling: Float = 1f,
    /**
     * Whether to honour the loudness the encoder measured, and which measurement to use.
     *
     * Off by default: a player changing the level of what it was given, unasked, is a surprise.
     * Turn it on and a quiet album stops playing quiet without the listener touching the volume.
     * The gain is clamped by the file's own peak so it can never clip; see [ReplayGainMode].
     */
    val replayGain: ReplayGainMode = ReplayGainMode.Off,
    /** Added to whatever the tag asked for, in dB. The usual taste knob; 0 honours the tag exactly. */
    val replayGainPreampDb: Float = 0f,
    /** Applied when [replayGain] is on and the media carries no usable tag, in dB. */
    val replayGainFallbackDb: Float = 0f,
) {
    init {
        require(volumeCeiling.isFinite() && volumeCeiling >= 1f && volumeCeiling <= GAIN_MAX) {
            "volumeCeiling must be between 1 and $GAIN_MAX, was $volumeCeiling"
        }
        require(replayGainPreampDb.isFinite() && replayGainPreampDb in -30f..30f) {
            "replayGainPreampDb must be between -30 and 30, was $replayGainPreampDb"
        }
        require(replayGainFallbackDb.isFinite() && replayGainFallbackDb in -30f..30f) {
            "replayGainFallbackDb must be between -30 and 30, was $replayGainFallbackDb"
        }
    }
}

/**
 * Which loudness measurement to honour, when the container carries one.
 *
 * Almost every music file has one: FLAC and Ogg write it as a Vorbis comment, MP3 as an ID3v2
 * frame, Opus as `R128_TRACK_GAIN` in its own unit. Honouring it is what stops a quiet album
 * playing quiet and a loud one being painful, without touching the volume the user set.
 */
public enum class ReplayGainMode {
    /** Ignore the tags. The default: a player should not change what it was given unasked. */
    Off,

    /** Level each track to itself. Right for shuffled listening. */
    Track,

    /** Level each album as a whole, so its own quiet and loud tracks keep their relationship. */
    Album,
}

/**
 * How a multichannel mix is folded into fewer speakers.
 *
 * Both defaults follow the reference the rest of this project follows, which is FFmpeg's own
 * resampler, and both were previously neither implemented nor decided: the mixer applied the
 * standard coefficients raw, so a source loud in several channels at once summed past full scale
 * and clipped at the device.
 */
public data class DownmixConfig(
    /**
     * Scale the matrix so a full-scale input can never sum past full scale.
     *
     * False by default, which is FFmpeg's own behaviour for float output and therefore what this
     * engine's reference recordings are made of: `ReferencePcmTest` compares the whole audio path
     * against `ffmpeg -ac 2`, and normalising by default would put the engine a fixed 7 dB below
     * its own oracle. It is also what a listener expects, because it is what mpv and VLC sound
     * like.
     *
     * The risk that leaves is real and worth naming: the coefficients can sum above full scale on
     * a passage loud in several channels at once. The engine's own pipeline is float and does not
     * clip there, so this only bites at the conversion into a device that takes integer samples.
     *
     * True removes that risk by arithmetic rather than by luck: the whole matrix is divided by its
     * largest row sum, so the balance between speakers is untouched and only the level moves. It
     * costs about 7 dB on a 5.1 film. Choose it for an integer output you cannot afford to clip.
     */
    val normalize: Boolean = false,
    /**
     * Fold the low-frequency effects channel into the stereo mix.
     *
     * False by default, which drops it. This is not a preference, it is a measurement: a 5.1 clip
     * carrying a 60 Hz tone in its LFE and silence in every other speaker comes out of
     * `ffmpeg -ac 2` as EXACT silence, and `ReferencePcmTest` pins that. ITU-R BS.775 says the
     * same thing: the LFE is a separate effects feed for a subwoofer and not a bass instrument,
     * and summing it into two full-range speakers makes film audio boom and eats the headroom the
     * dialogue needs.
     *
     * The engine used to fold it in at -3 dB, and the surround fixtures kept their LFE silent so
     * that the disagreement with FFmpeg never showed up in a test.
     *
     * True folds it in at the same -3 dB every other non-front channel gets, for a caller who
     * would rather keep the content than match the reference.
     */
    val includeLfe: Boolean = false,
)

/**
 * Which subtitle track to pick, when to show its cues, and how large to draw them.
 *
 * Read by the session core: track selection uses the language preferences and the forced rule,
 * cue timing applies [delay], and the platform rasterizer receives [fontScale]. Decoded cues are
 * held for the session and pruned on flush.
 */
public data class SubtitleConfig(
    /** Select a subtitle track automatically when one matches these languages. */
    val preferredLanguages: List<String> = emptyList(),
    /**
     * Select a forced-subtitles track automatically: one in a preferred language when the audio
     * language is not preferred, and otherwise one matching the audio's own language, which is
     * the audience a forced track is authored for.
     */
    val autoSelectForced: Boolean = true,
    /**
     * With no language preference matched, select the container's default-flagged subtitle
     * track, or its first one, rather than none. On, because a viewer who opens subtitled media
     * expects to see the subtitles; a player wanting mpv's stricter no-preference-no-subtitles
     * behaviour turns this off.
     */
    val autoSelect: Boolean = true,
    /** Shift every cue by this much. Positive shows cues later. */
    val delay: Duration = Duration.ZERO,
    /** Scale applied to the authored font size. */
    val fontScale: Float = 1.0f,
) {
    init {
        require(fontScale.isFinite() && fontScale > 0f) { "fontScale must be finite and positive, was $fontScale" }
    }
}

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
 * `KiteFFmpegMediaBackend()` from `kiteplayer-ffmpeg` and `AppleOutputBackend` from `kiteplayer-output`.
 */
public data class Backends(
    val backend: MediaBackend? = null,
    val output: OutputBackend? = null,
)
