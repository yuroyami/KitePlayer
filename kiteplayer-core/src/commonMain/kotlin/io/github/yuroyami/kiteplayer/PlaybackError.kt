package io.github.yuroyami.kiteplayer

/**
 * A failure that stopped playback.
 *
 * The rule that keeps this honest: **an error always stops playback and a warning never does.**
 * There is no third category and no silent degradation. If the engine can continue, it emits a
 * [PlaybackWarning] and continues. A viewer must never lose playback because one subtitle track
 * was malformed, and a developer must never discover a software-decode fallback by noticing the
 * fan.
 */
public sealed class PlaybackError {
    public abstract val message: String
    public open val cause: Throwable? = null

    /** The bytes could not be reached at all: no such file, refused connection, permission denied. */
    public data class SourceUnavailable(
        val uri: String,
        override val cause: Throwable?,
        val detail: String? = null,
    ) : PlaybackError() {
        override val message: String get() = "cannot open $uri" + (detail?.let { ": $it" } ?: "")
    }

    /** The bytes were reached and are not media the demuxer recognises. */
    public data class NotMedia(val uri: String, val detail: String? = null) : PlaybackError() {
        override val message: String get() = "not a recognised media format: $uri"
    }

    /** The container was read and holds nothing this build can play. */
    public data class NoPlayableStream(val streams: List<TrackInfo>) : PlaybackError() {
        override val message: String
            get() = "no playable stream among ${streams.size}: " +
                streams.joinToString { "${it.kind}/${it.codec}" }
    }

    /**
     * Every candidate decoder for a required track refused to open.
     *
     * Never produced. A track whose every candidate refuses is deselected with a warning, and an open
     * fails only when nothing playable is left, which reports [NoPlayableStream] and names every stream.
     * This is the shape for a caller that asks for one specific track and must be told why it cannot have
     * it. Not implemented yet; see the roadmap in KPKMP.md section 11.
     */
    public data class DecoderUnavailable(val codec: String, val kind: TrackKind) : PlaybackError() {
        override val message: String get() = "no decoder for $kind stream in $codec"
    }

    /** A decoder opened and then failed in a way that cannot be recovered from. */
    public data class DecoderFailed(
        val codec: String,
        val detail: String,
        override val cause: Throwable? = null,
    ) : PlaybackError() {
        override val message: String get() = "decoder $codec failed: $detail"
    }

    /**
     * No audio device could be opened, and the media has no video to fall back to.
     *
     * Never produced. A device that refuses during an open fails that open, and the failure is reported
     * as [SourceUnavailable] with the device's own message inside it, because nothing distinguishes the
     * two at the point the open unwinds. Telling them apart, and falling back to a silent picture when
     * there is a picture, needs the device-loss handling in the roadmap.
     * Not implemented yet; see the roadmap in KPKMP.md section 11.
     */
    public data class AudioDeviceUnavailable(val detail: String) : PlaybackError() {
        override val message: String get() = "no audio device: $detail"
    }

    /**
     * A shutdown did not complete inside its deadline, so part of the pipeline may still be running.
     *
     * A native call that has wedged cannot be killed from inside the process. When teardown exceeds its
     * bound the honest answer is this, not a successful close: the caller learns that the runtime is
     * compromised and that resources may still be held, which is information it can act on. Reporting
     * success and leaking a thread is what leaves an application with a mystery instead.
     */
    public data class RuntimeCompromised(val detail: String) : PlaybackError() {
        override val message: String get() = "shutdown did not complete: $detail"
    }

    /**
     * The player was asked to exist without something it cannot invent, or against a runtime it cannot
     * use.
     *
     * Two cases today. A missing backend: Kotlin/Native has no classpath service lookup, so there is no
     * such thing as finding the platform's decoder or its audio device at runtime, and whoever creates
     * the player passes both in. Saying so as a typed error is the alternative to reflection, and it
     * names what to pass rather than failing later with a null.
     *
     * The second is an FFmpeg runtime that does not match the headers the native layer was compiled
     * against. Nothing about that failure depends on the media: every file fails, the next file will fail
     * too, and retrying is pointless, which is what separates it from [SourceUnavailable]. The detail
     * carries both version columns for all six libraries and one actionable sentence.
     */
    public data class ConfigurationInvalid(val detail: String) : PlaybackError() {
        override val message: String get() = "the player cannot be built as configured: $detail"
    }

    /** The engine hit a state it does not know how to leave. This is always a bug here. */
    public data class Internal(val detail: String, override val cause: Throwable? = null) : PlaybackError() {
        override val message: String get() = "internal error: $detail"
    }
}

/**
 * What a suspending player command throws when playback failed.
 *
 * The value is the whole of the information; this class exists because a suspending function that
 * cannot deliver a result has to throw, and because a caller writing `try` around `open` wants one
 * type to catch. Nothing else in the engine throws it.
 *
 * Cancellation is never converted into one of these. A caller that cancels its own coroutine gets a
 * [kotlin.coroutines.cancellation.CancellationException], because cancellation is not a playback
 * failure and turning it into one breaks every structured-concurrency rule the caller relies on.
 */
public class PlaybackException(public val error: PlaybackError) : Exception(error.message, error.cause)

/**
 * Something went wrong and playback continued.
 *
 * Every warning names a degradation the developer would otherwise have to discover by measurement.
 */
public sealed class PlaybackWarning {
    public abstract val message: String

    /**
     * The attached renderer reported an unrecoverable failure through its own event feed (S4.d).
     * Playback continues; the schedule keeps pacing and the renderer keeps refusing, so the
     * degradation is a black or frozen picture, which is exactly why it is worth a warning.
     */
    public data class RendererFailed(val detail: String) : PlaybackWarning() {
        override val message: String get() = "the renderer failed: $detail"
    }

    /**
     * Open options the demuxer never consumed (S4.e): a typo'd key, or one this protocol does
     * not take. The open succeeded; the option did nothing, and pretending otherwise is how a
     * configuration bug survives for months.
     */
    public data class OptionsUnused(val keys: List<String>) : PlaybackWarning() {
        override val message: String get() = "open options not consumed by the demuxer: $keys"
    }

    /** A hardware decoder was unavailable or failed and policy allowed playback to continue in software. */
    public data class HardwareDecodeUnavailable(val codec: String, val reason: String) : PlaybackWarning() {
        override val message: String get() = "hardware decode unavailable for $codec: $reason"
    }

    /**
     * Frames are being dropped to keep up with the clock.
     *
     * Never emitted: the drops are counted as `PlaybackStats.droppedFramesLate`, and a rate over the last
     * second is something a caller works out by diffing two samples. A warning would have to be rate
     * limited to be useful, which is a policy nothing has decided.
     * Not implemented yet; see the roadmap in KPKMP.md section 11.
     */
    public data class FrameDropping(val droppedInLastSecond: Int) : PlaybackWarning() {
        override val message: String get() = "dropping frames: $droppedInLastSecond in the last second"
    }

    /**
     * The audio device restarted, changed or was replaced. Playback recovered.
     *
     * Never emitted: the engine does not collect `AudioSink.events`, so a device that changes underneath
     * playback is not noticed and nothing recovers from it.
     * Not implemented yet; see the roadmap in KPKMP.md section 11.
     */
    public data class AudioDeviceChanged(val detail: String) : PlaybackWarning() {
        override val message: String get() = "audio device changed: $detail"
    }

    /**
     * The audio device ran out of data.
     *
     * Never emitted: an underrun is counted as `PlaybackStats.audioUnderruns`, which is a number a caller
     * can diff, and the sink's own event feed that would carry the occurrence has no reader.
     * Not implemented yet; see the roadmap in KPKMP.md section 11.
     */
    public data class AudioUnderrun(val totalSoFar: Long) : PlaybackWarning() {
        override val message: String get() = "audio underrun, $totalSoFar so far"
    }

    /**
     * The device never reported its buffer empty at the end of the media, so the drain was bounded out.
     *
     * What that means in practice is a device that went away while it still held sound: unplugged,
     * taken by another application, or a session interrupted. Playback is over either way, and the last
     * fraction of a second may not have been heard. Polling a lost device for ever instead is how a
     * player ends up never finishing a file.
     */
    public data class AudioDrainIncomplete(val detail: String) : PlaybackWarning() {
        override val message: String get() = "the audio drain did not complete: $detail"
    }

    /**
     * The sink cannot report its latency usefully, so the audio clock is counted rather than
     * measured and synchronisation is approximate. Emitted once per sink.
     */
    public data class AudioLatencyUnreliable(val detail: String) : PlaybackWarning() {
        override val message: String get() = "audio latency is not measurable: $detail"
    }

    /**
     * The stream carries HDR transfer characteristics and there is no tone mapping, so it is
     * converted with the matrix alone: highlights clip and the picture looks flat next to a
     * tone-mapped one. Emitted once per stream.
     *
     * This is the documented behaviour and not a failure. A real tone-mapped path is Horizon B
     * (KPKMP.md section 11, B5), after which approximate output becomes something a caller asks for
     * rather than the only thing on offer.
     */
    public data class TonemappingUnavailable(val detail: String) : PlaybackWarning() {
        override val message: String get() = "no tone mapping: $detail"
    }

    /**
     * The source's channel layout could not be identified well enough to mix by speaker, so the
     * layout was guessed from the channel count or the channels were passed through in source order.
     * Emitted once per audio format.
     */
    public data class ChannelLayoutUnknown(val channels: Int, val detail: String) : PlaybackWarning() {
        override val message: String get() = "unknown channel layout for $channels channels: $detail"
    }

    /** Timestamps in the stream are broken and the engine is compensating. */
    public data class BadTimestamps(val detail: String) : PlaybackWarning() {
        override val message: String get() = "compensating for bad timestamps: $detail"
    }

    /** A non-essential track failed and was deselected. */
    public data class TrackDeselected(val track: TrackId, val detail: String) : PlaybackWarning() {
        override val message: String get() = "deselected $track: $detail"
    }

    /**
     * Open completed before the initial fill reached readiness: the source is slow, so playback
     * will begin in Buffering instead of with a ready pipeline. Opened is still truthful about
     * the session existing; this says the pipeline behind it is not yet primed.
     */
    public data class StartupIncomplete(val detail: String) : PlaybackWarning() {
        override val message: String get() = "opened before the pipeline was primed: $detail"
    }

    /**
     * The file is interleaved so badly that one stream had to be truncated to keep the other
     * playing.
     */
    public data class PathologicalInterleaving(val starvedTrack: TrackId, val droppedPackets: Int) : PlaybackWarning() {
        override val message: String
            get() = "badly interleaved file: dropped $droppedPackets packets to keep $starvedTrack fed"
    }

    /**
     * The renderer lost its surface. Audio continues and video frames are discarded.
     *
     * Never emitted: the engine does not collect `VideoRenderer.events`. A renderer that refuses a frame
     * still costs nothing more than that frame, because a refusal is counted as a drop and playback
     * carries on. Not implemented yet; see the roadmap in KPKMP.md section 11.
     */
    public data class NoRenderSurface(val detail: String) : PlaybackWarning() {
        override val message: String get() = "no render surface: $detail"
    }
}
