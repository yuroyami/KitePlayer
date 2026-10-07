package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.spi.AudioResamplerFactory
import io.github.yuroyami.kiteplayer.subtitle.SubtitleFont
import io.github.yuroyami.kiteplayer.subtitle.SubtitleStyleOverride
import kotlin.time.Duration

/** Keeps a block of one config builder from setting the fields of the builder around it. */
@DslMarker
public annotation class PlayerConfigDsl

/**
 * Builds a [PlayerConfig] in a block, so a nested setting does not need its type named.
 *
 * ```kotlin
 * val config = PlayerConfig {
 *     hdrPolicy = HdrPolicy.ToneMap
 *     subtitles { preferredLanguages = listOf("ja") }
 *     audio { replayGain = ReplayGainMode.Track }
 * }
 * ```
 *
 * Every field starts at the default of [PlayerConfig], and the data classes check their values
 * when the block ends, exactly as their constructors do.
 */
public fun PlayerConfig(build: PlayerConfigBuilder.() -> Unit): PlayerConfig =
    PlayerConfigBuilder(PlayerConfig()).apply(build).build()

/**
 * The fields of a [PlayerConfig], set in a `PlayerConfig { }` block. Each one is the field of that name.
 *
 * Java, which has no such block, makes one directly and calls [build] (#394). Every builder here
 * works the same way, starting from the defaults or from the config it is given:
 *
 * ```java
 * PlayerConfigBuilder builder = new PlayerConfigBuilder();
 * builder.setVideoEnabled(false);
 * PlayerConfig config = builder.build();
 * ```
 */
@PlayerConfigDsl
public class PlayerConfigBuilder(from: PlayerConfig = PlayerConfig()) {
    /** See [PlayerConfig.renderQuality]. */
    public var renderQuality: RenderQuality = from.renderQuality

    /** See [PlayerConfig.syncMode]. */
    public var syncMode: SyncMode = from.syncMode

    /** See [PlayerConfig.hardwareDecode]. */
    public var hardwareDecode: HwdecPolicy = from.hardwareDecode

    /** See [PlayerConfig.frameDrop]. */
    public var frameDrop: FrameDropPolicy = from.frameDrop

    /** See [PlayerConfig.buffer], and [buffer] for the block form. */
    public var buffer: BufferPolicy = from.buffer

    /** See [PlayerConfig.audio], and [audio] for the block form. */
    public var audio: AudioConfig = from.audio

    /** See [PlayerConfig.subtitles], and [subtitles] for the block form. */
    public var subtitles: SubtitleConfig = from.subtitles

    /** See [PlayerConfig.progressInterval]. */
    public var progressInterval: Duration = from.progressInterval

    /** See [PlayerConfig.statsInterval]. */
    public var statsInterval: Duration = from.statsInterval

    /** See [PlayerConfig.frameEvents]. */
    public var frameEvents: Boolean = from.frameEvents

    /** See [PlayerConfig.backends]. */
    public var backends: Backends = from.backends

    /** See [PlayerConfig.network], and [network] for the block form. */
    public var network: NetworkConfig = from.network

    /** See [PlayerConfig.videoEnabled]. */
    public var videoEnabled: Boolean = from.videoEnabled

    /** See [PlayerConfig.deinterlace]. */
    public var deinterlace: DeinterlacePolicy = from.deinterlace

    /** See [PlayerConfig.queue], and [queue] for the block form. */
    public var queue: QueueConfig = from.queue

    /** See [PlayerConfig.hdrPolicy]. */
    public var hdrPolicy: HdrPolicy = from.hdrPolicy

    /** See [PlayerConfig.externalClock]. */
    public var externalClock: ExternalClockPolicy = from.externalClock

    /** See [PlayerConfig.keyframeChoice]. */
    public var keyframeChoice: KeyframeChoice = from.keyframeChoice

    /** See [PlayerConfig.flashGuard]. */
    public var flashGuard: FlashGuard = from.flashGuard

    /** Changes [audio] field by field. */
    public fun audio(build: AudioConfigBuilder.() -> Unit) {
        audio = AudioConfigBuilder(audio).apply(build).build()
    }

    /** Changes [subtitles] field by field. */
    public fun subtitles(build: SubtitleConfigBuilder.() -> Unit) {
        subtitles = SubtitleConfigBuilder(subtitles).apply(build).build()
    }

    /** Changes [network] field by field. */
    public fun network(build: NetworkConfigBuilder.() -> Unit) {
        network = NetworkConfigBuilder(network).apply(build).build()
    }

    /** Changes [buffer] field by field. */
    public fun buffer(build: BufferPolicyBuilder.() -> Unit) {
        buffer = BufferPolicyBuilder(buffer).apply(build).build()
    }

    /** Changes [queue] field by field. */
    public fun queue(build: QueueConfigBuilder.() -> Unit) {
        queue = QueueConfigBuilder(queue).apply(build).build()
    }

    /** The [PlayerConfig] these fields describe, checked as its constructor checks it. */
    public fun build(): PlayerConfig = PlayerConfig(
        renderQuality = renderQuality,
        syncMode = syncMode,
        hardwareDecode = hardwareDecode,
        frameDrop = frameDrop,
        buffer = buffer,
        audio = audio,
        subtitles = subtitles,
        progressInterval = progressInterval,
        statsInterval = statsInterval,
        frameEvents = frameEvents,
        backends = backends,
        network = network,
        videoEnabled = videoEnabled,
        deinterlace = deinterlace,
        queue = queue,
        hdrPolicy = hdrPolicy,
        externalClock = externalClock,
        keyframeChoice = keyframeChoice,
        flashGuard = flashGuard,
    )
}

/** The fields of an [AudioConfig], set in an `audio { }` block. Each one is the field of that name. */
@PlayerConfigDsl
public class AudioConfigBuilder(from: AudioConfig = AudioConfig()) {
    /** See [AudioConfig.preferredLanguages]. */
    public var preferredLanguages: List<String> = from.preferredLanguages

    /** See [AudioConfig.preservePitch]. */
    public var preservePitch: Boolean = from.preservePitch

    /** See [AudioConfig.downmix]. */
    public var downmix: DownmixConfig = from.downmix

    /** See [AudioConfig.volumeCeiling]. */
    public var volumeCeiling: Float = from.volumeCeiling

    /** See [AudioConfig.equalizer]. */
    public var equalizer: EqualizerSettings = from.equalizer

    /** See [AudioConfig.replayGain]. */
    public var replayGain: ReplayGainMode = from.replayGain

    /** See [AudioConfig.replayGainPreampDb]. */
    public var replayGainPreampDb: Float = from.replayGainPreampDb

    /** See [AudioConfig.replayGainFallbackDb]. */
    public var replayGainFallbackDb: Float = from.replayGainFallbackDb

    /** See [AudioConfig.resampler]. */
    public var resampler: AudioResamplerFactory? = from.resampler

    /** See [AudioConfig.upmix]. */
    public var upmix: UpmixMode = from.upmix

    /** See [AudioConfig.matchOutputChannels]. */
    public var matchOutputChannels: Boolean = from.matchOutputChannels

    /** The [AudioConfig] these fields describe, checked as its constructor checks it. */
    public fun build(): AudioConfig = AudioConfig(
        preferredLanguages = preferredLanguages,
        preservePitch = preservePitch,
        downmix = downmix,
        volumeCeiling = volumeCeiling,
        equalizer = equalizer,
        replayGain = replayGain,
        replayGainPreampDb = replayGainPreampDb,
        replayGainFallbackDb = replayGainFallbackDb,
        resampler = resampler,
        upmix = upmix,
        matchOutputChannels = matchOutputChannels,
    )
}

/** The fields of a [SubtitleConfig], set in a `subtitles { }` block. Each one is the field of that name. */
@PlayerConfigDsl
public class SubtitleConfigBuilder(from: SubtitleConfig = SubtitleConfig()) {
    /** See [SubtitleConfig.preferredLanguages]. */
    public var preferredLanguages: List<String> = from.preferredLanguages

    /** See [SubtitleConfig.autoSelectForced]. */
    public var autoSelectForced: Boolean = from.autoSelectForced

    /** See [SubtitleConfig.autoSelect]. */
    public var autoSelect: Boolean = from.autoSelect

    /** See [SubtitleConfig.delay]. */
    public var delay: Duration = from.delay

    /** See [SubtitleConfig.fontScale]. */
    public var fontScale: Float = from.fontScale

    /** See [SubtitleConfig.style]. */
    public var style: SubtitleStyleOverride? = from.style

    /** See [SubtitleConfig.typesetting]. */
    public var typesetting: Boolean = from.typesetting

    /** See [SubtitleConfig.fonts]. */
    public var fonts: List<SubtitleFont> = from.fonts

    /** See [SubtitleConfig.assColorMatching]. */
    public var assColorMatching: Boolean = from.assColorMatching

    /** See [SubtitleConfig.hearingImpairedNotes]. */
    public var hearingImpairedNotes: HearingImpairedNotes = from.hearingImpairedNotes

    /** See [SubtitleConfig.fallbackEncoding]. */
    public var fallbackEncoding: String? = from.fallbackEncoding

    /** See [SubtitleConfig.withMatchingAudio]. */
    public var withMatchingAudio: MatchingAudioSubtitles = from.withMatchingAudio

    /** See [SubtitleConfig.forcedPicturesOnly]. */
    public var forcedPicturesOnly: Boolean = from.forcedPicturesOnly

    /** See [SubtitleConfig.forcedPicturesWhenOff]. */
    public var forcedPicturesWhenOff: Boolean = from.forcedPicturesWhenOff

    /** See [SubtitleConfig.secondaryLanguages]. */
    public var secondaryLanguages: List<String> = from.secondaryLanguages

    /** See [SubtitleConfig.secondaryPlacement]. */
    public var secondaryPlacement: SecondarySubtitlePlacement = from.secondaryPlacement

    /** The [SubtitleConfig] these fields describe, checked as its constructor checks it. */
    public fun build(): SubtitleConfig = SubtitleConfig(
        preferredLanguages = preferredLanguages,
        autoSelectForced = autoSelectForced,
        autoSelect = autoSelect,
        delay = delay,
        fontScale = fontScale,
        style = style,
        typesetting = typesetting,
        fonts = fonts,
        assColorMatching = assColorMatching,
        hearingImpairedNotes = hearingImpairedNotes,
        fallbackEncoding = fallbackEncoding,
        withMatchingAudio = withMatchingAudio,
        forcedPicturesOnly = forcedPicturesOnly,
        forcedPicturesWhenOff = forcedPicturesWhenOff,
        secondaryLanguages = secondaryLanguages,
        secondaryPlacement = secondaryPlacement,
    )
}

/** The fields of a [NetworkConfig], set in a `network { }` block. Each one is the field of that name. */
@PlayerConfigDsl
public class NetworkConfigBuilder(from: NetworkConfig = NetworkConfig()) {
    /** See [NetworkConfig.ioResolver]. */
    public var ioResolver: MediaIoResolver? = from.ioResolver

    /** See [NetworkConfig.ioCache], and [ioCache] for the block form. */
    public var ioCache: IoCachePolicy = from.ioCache

    /** See [NetworkConfig.autoResolve]. */
    public var autoResolve: Boolean = from.autoResolve

    /** See [NetworkConfig.recovery]. */
    public var recovery: NetworkRecovery? = from.recovery

    /** Changes [ioCache] field by field. */
    public fun ioCache(build: IoCachePolicyBuilder.() -> Unit) {
        ioCache = IoCachePolicyBuilder(ioCache).apply(build).build()
    }

    /** The [NetworkConfig] these fields describe, checked as its constructor checks it. */
    public fun build(): NetworkConfig = NetworkConfig(
        ioResolver = ioResolver,
        ioCache = ioCache,
        autoResolve = autoResolve,
        recovery = recovery,
    )
}

/** The fields of an [IoCachePolicy], set in an `ioCache { }` block. Each one is the field of that name. */
@PlayerConfigDsl
public class IoCachePolicyBuilder(from: IoCachePolicy = IoCachePolicy()) {
    /** See [IoCachePolicy.enabled]. */
    public var enabled: Boolean = from.enabled

    /** See [IoCachePolicy.readChunkBytes]. */
    public var readChunkBytes: Int = from.readChunkBytes

    /** See [IoCachePolicy.backWindowBytes]. */
    public var backWindowBytes: Long = from.backWindowBytes

    /** See [IoCachePolicy.forwardWindowBytes]. */
    public var forwardWindowBytes: Long = from.forwardWindowBytes

    /** The [IoCachePolicy] these fields describe, checked as its constructor checks it. */
    public fun build(): IoCachePolicy = IoCachePolicy(
        enabled = enabled,
        readChunkBytes = readChunkBytes,
        backWindowBytes = backWindowBytes,
        forwardWindowBytes = forwardWindowBytes,
    )
}

/** The fields of a [BufferPolicy], set in a `buffer { }` block. Each one is the field of that name. */
@PlayerConfigDsl
public class BufferPolicyBuilder(from: BufferPolicy = BufferPolicy()) {
    /** See [BufferPolicy.readyDuration]. */
    public var readyDuration: Duration = from.readyDuration

    /** See [BufferPolicy.readyPackets]. */
    public var readyPackets: Int = from.readyPackets

    /** See [BufferPolicy.softTarget]. */
    public var softTarget: Duration = from.softTarget

    /** See [BufferPolicy.totalBytes]. */
    public var totalBytes: Long = from.totalBytes

    /** See [BufferPolicy.totalDuration]. */
    public var totalDuration: Duration = from.totalDuration

    /** See [BufferPolicy.videoFrameQueue]. */
    public var videoFrameQueue: Int = from.videoFrameQueue

    /** See [BufferPolicy.stallTimeout]. */
    public var stallTimeout: Duration = from.stallTimeout

    /** The [BufferPolicy] these fields describe, checked as its constructor checks it. */
    public fun build(): BufferPolicy = BufferPolicy(
        readyDuration = readyDuration,
        readyPackets = readyPackets,
        softTarget = softTarget,
        totalBytes = totalBytes,
        totalDuration = totalDuration,
        videoFrameQueue = videoFrameQueue,
        stallTimeout = stallTimeout,
    )
}

/** The fields of a [QueueConfig], set in a `queue { }` block. Each one is the field of that name. */
@PlayerConfigDsl
public class QueueConfigBuilder(from: QueueConfig = QueueConfig()) {
    /** See [QueueConfig.preloadNext]. */
    public var preloadNext: Duration = from.preloadNext

    /** See [QueueConfig.gapless]. */
    public var gapless: Boolean = from.gapless

    /** See [QueueConfig.reshuffleEachLap]. */
    public var reshuffleEachLap: Boolean = from.reshuffleEachLap

    /** See [QueueConfig.onItemFailure]. */
    public var onItemFailure: QueueItemFailure = from.onItemFailure

    /** See [QueueConfig.crossfade]. */
    public var crossfade: Duration = from.crossfade

    /** The [QueueConfig] these fields describe, checked as its constructor checks it. */
    public fun build(): QueueConfig = QueueConfig(
        preloadNext = preloadNext,
        gapless = gapless,
        reshuffleEachLap = reshuffleEachLap,
        onItemFailure = onItemFailure,
        crossfade = crossfade,
    )
}
