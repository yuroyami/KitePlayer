package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.GAIN_MAX
import io.github.yuroyami.kiteplayer.internal.SubtitleEncodings
import io.github.yuroyami.kiteplayer.spi.AudioResamplerFactory
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
     * How much work renderers spend on the picture beyond decoding it correctly.
     *
     * Off by default, which is the plain decoded picture exactly. Change it live with
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
    /** Emit [PlayerEvent.FramePresented] per frame. Off by default: sixty events a second is a cost nobody should pay unasked. */
    val frameEvents: Boolean = false,
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
    /**
     * Whether interlaced video is deinterlaced. See [DeinterlacePolicy]. A deinterlaced stream
     * decodes in software, like any stream with a video filter.
     */
    val deinterlace: DeinterlacePolicy = DeinterlacePolicy.Auto,
    /** How a queue moves from one item to the next, including the gapless handoff. See [QueueConfig]. */
    val queue: QueueConfig = QueueConfig(),
    /**
     * How HDR video reaches the screen. Change it live with [KitePlayer.setHdrPolicy]; this is only
     * the value a fresh player starts at.
     */
    val hdrPolicy: HdrPolicy = HdrPolicy.Auto,
    /** How playback follows a clock set with [KitePlayer.setExternalClock]. See [ExternalClock]. */
    val externalClock: ExternalClockPolicy = ExternalClockPolicy(),
) {
    init {
        // Validated at construction, before a player exists to be wedged by it: a nonpositive
        // interval is a hot publication loop, and each nested policy carries its own checks.
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
 * How media bytes arrive over a network, and how the engine caches them.
 */
public data class NetworkConfig(
    /**
     * Consulted at open for a [MediaItem] with no [MediaItem.io]. An explicit resolver takes
     * precedence over automatic providers, even when it returns null to select the backend.
     * With null, [autoResolve] controls whether installed providers may supply a reader.
     */
    val ioResolver: MediaIoResolver? = null,
    /** The engine-owned byte cache every [MediaIo]-fed open gets. */
    val ioCache: IoCachePolicy = IoCachePolicy(),
    /**
     * Consult installed optional transport providers when [ioResolver] and [MediaItem.io] are
     * absent. Adding kiteplayer-network supplies HTTP/HTTPS without configuring a resolver.
     * False preserves the backend's URI handling. Explicit readers and resolvers still apply.
     */
    val autoResolve: Boolean = true,
)

/**
 * The byte cache: one contiguous RAM window over an [MediaIo]'s bytes. Reads pull
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
 * How much to read ahead, when to declare that playback can start, and how long to wait for a
 * source that stopped answering.
 *
 * The read-ahead defaults come from the values ffplay and mpv converged on after a decade of bug
 * reports. Do not retune them without evidence from real content.
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
     * How long a read may wait without progress before the session ends with
     * [PlaybackError.SourceStalled]. [Duration.INFINITE] waits for ever.
     *
     * The count runs only while the engine waits for the source: a packet read, an open that
     * reads through a [MediaIo], or the read of an external subtitle file. A packet from the source
     * or bytes from the reader start it again, so a slow source that still delivers never stalls.
     * An open through the backend's own protocols is bounded by their timeouts instead.
     * An external subtitle file that stalls is skipped with a warning instead. Ending a read needs
     * a source that can interrupt; the FFmpeg backend can.
     */
    val stallTimeout: Duration = 30.seconds,
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
        // Zero would end every session on its first read.
        require(stallTimeout > Duration.ZERO) { "stallTimeout must be positive, was $stallTimeout" }
    }
}

/**
 * How the player moves from one queue item to the next. See `docs/gapless-queue.md`.
 *
 * With the defaults the next item opens in the background five seconds before the current one
 * ends, and its sound follows the last sample of the current item on the same audio device, with
 * no silence between them. [PlaybackWarning.GaplessFallback] says when an item opened the old way.
 */
public data class QueueConfig(
    /**
     * Open and prime the next item this long before the current one ends, for the gapless
     * handoff. Zero turns preloading off, and with it the handoff.
     */
    val preloadNext: Duration = 5.seconds,
    /**
     * Hand the audio device from one item to the next without stopping it. Needs [preloadNext]
     * above zero. False keeps the old path for every item and preloads nothing: the device stops
     * at the end of an item, and the next item opens from scratch.
     */
    val gapless: Boolean = true,
    /**
     * With shuffle and [LoopMode.All] on, draw a fresh order each time the queue comes round
     * instead of playing the first order again on every lap (#488). The new lap never starts with
     * the item that ended the last one, and it comes from the shuffle's own random source, so a
     * seeded shuffle stays reproducible. False keeps one order for every lap.
     */
    val reshuffleEachLap: Boolean = false,
    /**
     * What the queue does when an item cannot be opened, such as a moved file, an address that
     * answers 404 or a format this build cannot decode (#487). [QueueItemFailure.Stop] keeps the
     * player in [PlaybackStatus.Failed] on that item, for an application that handles the error
     * itself. [QueueItemFailure.Skip] moves on, as mpv does: see there.
     */
    val onItemFailure: QueueItemFailure = QueueItemFailure.Stop,
) {
    init {
        require(!preloadNext.isNegative() && preloadNext.isFinite()) {
            "preloadNext must be zero or a finite positive duration, was $preloadNext"
        }
    }
}

/** What a queue does with an item that cannot be opened. See [QueueConfig.onItemFailure]. */
public enum class QueueItemFailure {
    /** Stay on the item, in [PlaybackStatus.Failed] with its error, as a single open does. */
    Stop,

    /**
     * Move on in the direction the queue was going: forward at the end of an item and on next,
     * back on previous. Each item passed over is reported as [PlaybackWarning.QueueItemSkipped]
     * and listed in [PlayerSnapshot.failedQueueItems] until it opens, and a queue that was playing
     * keeps playing. Every item is tried again each time the queue reaches it, so with
     * [LoopMode.All] a file that comes back plays on the next lap. The player stops in
     * [PlaybackStatus.Failed], with the last error, when the queue has nowhere left to go, or when
     * every item in it has failed in a row, so a queue of broken items does not go round for ever.
     */
    Skip,
}

/**
 * How the player handles sound: which track to pick, the pitch law, the downmix, the volume
 * ceiling, the equaliser, ReplayGain and the resampler. A fresh player starts from these values,
 * and the setters on [KitePlayer] change most of them while it plays.
 */
public data class AudioConfig(
    /**
     * Preferred languages, best first, as ISO 639 codes or BCP 47 tags.
     *
     * A track matches when it names the same language in any spelling: `ja` matches a track tagged
     * `jpn` or `ja-JP`, and `de` one tagged `ger` or `deu`. The first preference any track matches
     * decides. Among its tracks, one whose script and region also agree comes first, so `pt-BR`
     * prefers a `pt-BR` track and takes a `pt-PT` one over nothing.
     */
    val preferredLanguages: List<String> = emptyList(),
    /**
     * Play at a different rate without changing pitch. True stretches the sound in time; false
     * plays it faster or slower like a tape, so pitch moves with the rate. The seed for
     * [KitePlayer.setPreservePitch], which can change it at runtime.
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
    /** The equaliser a fresh player starts at. Change it live with [KitePlayer.setEqualizer]. */
    val equalizer: EqualizerSettings = EqualizerSettings.Flat,
    /**
     * Whether to honour the loudness the encoder measured, and which measurement to use.
     *
     * Off by default: a player changing the level of what it was given, unasked, is a surprise.
     * Turn it on and a quiet album stops playing quiet without the listener touching the volume.
     * The gain is clamped by the file's own peak so it can never clip. A tag without a peak, which
     * is every Opus tag, can lower the level but never raise it, and [volumeCeiling] does not widen
     * the clamp. See [ReplayGainMode].
     */
    val replayGain: ReplayGainMode = ReplayGainMode.Off,
    /** Added to whatever the tag asked for, in dB. The usual taste knob; 0 honours the tag exactly. */
    val replayGainPreampDb: Float = 0f,
    /** Applied when [replayGain] is on and the media carries no usable tag, in dB. */
    val replayGainFallbackDb: Float = 0f,
    /**
     * Replaces the engine's own rate conversion, a windowed sinc written in Kotlin. Null keeps
     * that sinc. `KiteFFmpegResampler` in `kiteplayer-ffmpeg` runs FFmpeg's libswresample
     * instead. A factory that throws leaves the sinc in place; see [AudioResamplerFactory].
     */
    val resampler: AudioResamplerFactory? = null,
    /**
     * Whether mono or stereo audio also plays from the other speakers of a surround device. Off by
     * default, because widening a mix is a matter of taste. See [UpmixMode] for the matrix.
     */
    val upmix: UpmixMode = UpmixMode.Off,
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
 * A ten-band graphic equaliser, in dB per band.
 *
 * The bands are the ISO octave centres from 31 Hz to 16 kHz, the ten every hardware equaliser has
 * had since the 1970s, so a preset written for one of those means the same thing here.
 *
 * All zero is bypass, and bypass is bit-exact: the filters are skipped entirely rather than run
 * with coefficients that happen to be the identity.
 */
public data class EqualizerSettings(
    /** One gain per band, in the order of [Bands]. */
    val gainsDb: List<Float> = List(10) { 0f },
    /**
     * Applied before the bands, in dB.
     *
     * Boosting several bands can push a mix past full scale, and the usual answer is to pull
     * everything down first. Negative values are the useful ones.
     */
    val preampDb: Float = 0f,
) {
    init {
        require(gainsDb.size == Bands.size) { "an equaliser has ${Bands.size} bands, got ${gainsDb.size}" }
        require(gainsDb.all { it.isFinite() && it in -12f..12f }) {
            "every band gain must be finite and within -12 to 12 dB, got $gainsDb"
        }
        require(preampDb.isFinite() && preampDb in -12f..12f) {
            "the preamp must be finite and within -12 to 12 dB, was $preampDb"
        }
    }

    /** True when this changes nothing, in which case the engine skips the filters entirely. */
    public val isFlat: Boolean get() = preampDb == 0f && gainsDb.all { it == 0f }

    /** The band layout, and the setting that changes nothing. */
    public companion object {
        /**
         * The band centres in Hz, in order.
         *
         * FIRST in this companion, and it has to be: [Flat] constructs an instance whose `init`
         * reads this list, and a companion initialises its properties in source order, so
         * declaring it second leaves the check reading null and the whole class fails to load.
         */
        public val Bands: List<Float> =
            listOf(31.25f, 62.5f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)

        /** No change at all. The default. */
        public val Flat: EqualizerSettings = EqualizerSettings()
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
 * Whether interlaced video is deinterlaced, which removes the combing that DVD rips and broadcast
 * captures otherwise show on every moving edge.
 */
public enum class DeinterlacePolicy {
    /**
     * Deinterlaces a stream whose container says it is interlaced, and only its frames that are
     * marked interlaced. A stream of unknown field order plays as it is. The default.
     */
    Auto,

    /** Never deinterlaces. */
    Off,

    /** Deinterlaces every frame of every video stream, whatever it declares. */
    Always,
}

/**
 * Whether a source with fewer channels than the device also fills the device's other speakers.
 */
public enum class UpmixMode {
    /** Only the speakers the source has play. A stereo file on a 5.1 device plays from the front pair. */
    Off,

    /**
     * A mono or stereo source fills every front centre, low-frequency and surround speaker the
     * device has, with one fixed matrix. For a stereo source with channels L and R:
     *
     * - front left and front right: L and R, unchanged;
     * - front centre: 0.707 × (L + R);
     * - low-frequency channel: 0.5 × (L + R), through a 120 Hz low-pass;
     * - each surround pair, side or back: 0.5 × (L − R) on the left and 0.5 × (R − L) on the right.
     *   A 7.1 device plays this from both of its surround pairs.
     *
     * A mono source with channel M plays M at unity from both front speakers, 0.707 × M from the
     * centre, 0.5 × M through the low-pass from the low-frequency channel, and 0.35 × M from every
     * surround speaker. A source with more than two channels is never upmixed, and a device
     * without a front pair changes nothing.
     */
    Surround,
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
     * True folds it in, at -3 dB into each front speaker or at unity into a lone centre, which is
     * FFmpeg with an LFE level of 1. That is for a caller who would rather keep the content than
     * match the reference.
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
    /**
     * Select a subtitle track automatically when one matches these languages, best first, given as
     * ISO 639 codes or BCP 47 tags.
     *
     * Languages match as [AudioConfig.preferredLanguages] describes: `ja` matches a track tagged
     * `jpn`, a region or a script in the preference picks the closer of several tracks in one
     * language, and `zh-Hant` takes a plain Chinese track whose title says Traditional or 繁體.
     */
    val preferredLanguages: List<String> = emptyList(),
    /**
     * Select a forced-subtitles track automatically: one in a preferred language when the audio
     * language is not preferred, and otherwise one matching the audio's own language, which is
     * the audience a forced track is authored for. The choice is made again whenever the audio
     * changes, until a subtitle is chosen by hand (#506).
     */
    val autoSelectForced: Boolean = true,
    /**
     * With no language preference matched, select the container's default-flagged subtitle
     * track, or its first one, rather than none. On, because a viewer who opens subtitled media
     * expects to see the subtitles; a player wanting mpv's stricter no-preference-no-subtitles
     * behaviour turns this off.
     */
    val autoSelect: Boolean = true,
    /** Shift every cue by this much. Positive shows cues later. At most [KitePlayer.DELAY_MAX] either way. */
    val delay: Duration = Duration.ZERO,
    /** Scale applied to the authored font size. */
    val fontScale: Float = 1.0f,
    /** The viewer's style override, applied over every authored style. Null changes nothing. */
    val style: io.github.yuroyami.kiteplayer.subtitle.SubtitleStyleOverride? = null,
    /**
     * Route ASS and SSA tracks through an installed typesetting engine when one is present.
     * Adding `kiteplayer-libass` installs one; the standard entry points include it. False keeps
     * the Kotlin dialogue tier for every track, which draws styles but not animated typesetting.
     * With no engine installed this changes nothing.
     */
    val typesetting: Boolean = true,
    /**
     * Fonts handed to the typesetting engine on top of what the platform and the media supply.
     * Ignored by the Kotlin tier, which uses the platform's own font system.
     */
    val fonts: List<io.github.yuroyami.kiteplayer.subtitle.SubtitleFont> = emptyList(),
    /**
     * What happens to the notes that subtitles for deaf and hard-of-hearing viewers carry, such as
     * `[DOOR SLAMS]`, `(laughs)`, `JOHN:` and lines of `♪` music (#493). [HearingImpairedNotes.Keep]
     * shows them as authored. The setting reads SubRip, WebVTT, MP4 and other text tracks, from the
     * container or from a file; ASS signs, typesetting and karaoke are left alone.
     */
    val hearingImpairedNotes: HearingImpairedNotes = HearingImpairedNotes.Keep,
    /**
     * The encoding an external subtitle file is read in when it has no byte-order mark and is not
     * UTF-8, in place of a guess from its bytes (#515). Null guesses.
     *
     * This is the standing preference a player's settings offer, as VLC's default subtitle encoding
     * and mpv's `sub-codepage` are: a UTF-8 file, which most files now are, is still read as UTF-8.
     * [SubtitleSource.encoding] is the other strength, a choice for one file that is used whatever
     * its bytes say. The names are [SubtitleSource.ENCODINGS] and the labels for them. A file read
     * this way raises no [PlaybackWarning.SubtitleCharsetGuessed], because nothing was guessed. An
     * East Asian encoding the backend has no table for leaves the guess to decide.
     */
    val fallbackEncoding: String? = null,
) {
    init {
        require(fallbackEncoding == null || SubtitleEncodings.canonical(fallbackEncoding) != null) {
            "fallbackEncoding $fallbackEncoding is not an encoding a subtitle file can be read in; " +
                "the names are ${SubtitleSource.ENCODINGS.joinToString()}"
        }
        require(fontScale.isFinite() && fontScale > 0f) { "fontScale must be finite and positive, was $fontScale" }
        require(delay.isFinite() && delay.absoluteValue <= KitePlayer.DELAY_MAX) {
            "delay must be finite and at most ${KitePlayer.DELAY_MAX} either way, was $delay"
        }
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

/** What [SubtitleConfig.hearingImpairedNotes] does with the notes of hearing-impaired subtitles (#493). */
public enum class HearingImpairedNotes {
    /** The notes show as authored. */
    Keep,

    /**
     * Sound descriptions in square brackets, a parenthesis that opens a line or fills it, a
     * speaker's name in capitals before a colon at the start of a line, and lines of `♪` music go.
     * A line left empty goes, and a cue left with no line is not shown.
     */
    Hide,

    /** As [Hide], and every parenthesis within a line goes too. */
    HideStrict,
}
