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
     * it. Not implemented yet; see the roadmap in KPKMP-PAST.md section 11.
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
     * Not implemented yet; see the roadmap in KPKMP-PAST.md section 11.
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
     * Emitted from the stats tick when 5 or more frames were dropped since the previous one, so the
     * rate limit is `PlayerConfig.statsInterval`, one second by default. The running total is also in
     * `PlaybackStats.droppedFramesLate` for a caller who would rather diff two samples.
     */
    public data class FrameDropping(val droppedInLastSecond: Int) : PlaybackWarning() {
        override val message: String get() = "dropping frames: $droppedInLastSecond in the last second"
    }

    /**
     * The audio device restarted, changed or was replaced.
     *
     * Emitted when the sink reports `AudioSinkEvent.DeviceLost` (detail prefixed "device lost: ") or
     * `AudioSinkEvent.DeviceChanged`. **It reports; it does not mean the engine recovered.** Nothing
     * rebuilds the sink or reopens the device yet, so treat this as an observation rather than a
     * repair. `AudioSinkEventTest` pins exactly which events reach here and which are dropped.
     */
    public data class AudioDeviceChanged(val detail: String) : PlaybackWarning() {
        override val message: String get() = "audio device changed: $detail"
    }

    /**
     * The audio device ran out of data.
     *
     * Emitted from the stats tick on the RISING EDGE of the player-level underrun total, so a reopen
     * cannot silently re-baseline it, and [totalSoFar] is that whole-player total rather than this
     * session's. The same number is in `PlaybackStats.audioUnderruns`. Note the sink's own
     * `AudioSinkEvent.Underrun` is a different path and is currently dropped unread.
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
     * HDR was rolled off to standard dynamic range so this display can show it. Once per open.
     *
     * Not a defect and not a fallback: PQ and HLG carry more range than an SDR panel can present,
     * so a player either tone maps or shows a flat, dull picture. This says which happened, because
     * a viewer comparing two devices deserves to know the picture was CHANGED for one of them.
     *
     * **Emitted where tone mapping ENGAGES, never from the stream's metadata.** That distinction is
     * the whole point of the type: a path that hands HDR straight to a display which can show it
     * (the Android MediaCodec interop tier today, HDR passthrough when it lands) must stay SILENT,
     * and metadata-based emission cannot tell those apart. It arrives as
     * [io.github.yuroyami.kiteplayer.spi.RendererEvent.ToneMapEngaged] from the renderer that did it.
     */
    public data class HdrToneMapped(val transfer: String, val streamIndex: Int) : PlaybackWarning() {
        override val message: String
            get() = "HDR ($transfer) tone mapped to standard dynamic range for this display on " +
                "stream $streamIndex"
    }

    /**
     * The colour of this stream is APPROXIMATED, and it is shown anyway. Emitted once per stream.
     *
     * BT.2020 constant luminance encodes luma AFTER the transfer function rather than before it, so
     * the non-constant luminance matrix every conversion path here runs is the wrong inverse for it
     * and chroma-heavy areas shift. Unlike HDR, this is not fixable by rolling off a curve; it needs
     * the transfer function inside the conversion loop, which is the colour-managed pipeline this
     * engine does not have.
     *
     * Metadata-based on purpose, unlike [HdrToneMapped]: the approximation is a property of the
     * conversion the engine WILL do, it is known at open, and it is true of every path that
     * converts at all.
     */
    public data class ColorApproximated(val detail: String) : PlaybackWarning() {
        override val message: String get() = "colour approximated: $detail"
    }

    /**
     * DEPRECATED 2026-08-25 and NEVER EMITTED (KP-TONEMAP-WARN, spec 17.22.A).
     *
     * It said two things at once and one of them was false. The engine has tone mapped HDR since
     * 2026-08-16, `HdrToneMap` on both software conversion paths and `kp_tone_map` in the Metal
     * shader, so "no tone mapping" was wrong for every built-in display path. It stayed true only
     * for BT.2020 constant luminance, which now has its own type.
     *
     * Handle [HdrToneMapped] and [ColorApproximated] instead. The one case where "not tone mapped"
     * is still true, a caller taking RAW frames and converting them itself, is documented on the
     * frame-access surface where that caller will meet it, rather than as a warning every viewer
     * sees.
     *
     * Kept rather than deleted because consumers pin 0.x source compatibility; removal is a
     * version decision.
     */
    @Deprecated(
        "The engine tone maps HDR. Handle HdrToneMapped and ColorApproximated instead.",
        ReplaceWith("ColorApproximated(detail)"),
    )
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
     * A control the engine could not honour, named so a fire-and-forget caller still finds out
     * (2026-08-17 audit, F-API1). The suspending form of the same member throws instead; this
     * warning is how the refusal reaches [KitePlayer.events] and the warning history when the
     * caller never awaited a reply: a refused loop repeat on an unseekable source, a speed or
     * pitch-law change such a source cannot anchor, or a renderer swap whose scheduler never
     * quiesced.
     */
    public data class CommandRefused(val member: String, val detail: String) : PlaybackWarning() {
        override val message: String get() = "$member refused: $detail"
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
     * Tearing a session down did not release everything cleanly.
     *
     * Teardown runs every close in its own `runCatching` so that one failure cannot strand the
     * resources after it, which is right. What was wrong is that the failures then vanished: a
     * decoder or device that refused to close left no trace anywhere, and the next open met a
     * machine in a state nothing had reported (audit KP-P1-08). The session is gone either way,
     * which is why this is a warning and not a failure, but it is now a warning that exists.
     */
    public data class ResourcesNotReleased(val detail: String) : PlaybackWarning() {
        override val message: String get() = "the session did not release cleanly: $detail"
    }

    /**
     * The item asked to start somewhere the open could not take it: an unseekable source, or a
     * position past the end. Playback starts where the container does instead, and this says so
     * rather than leaving the caller to notice the position report.
     */
    public data class StartPositionIgnored(val requested: kotlin.time.Duration, val detail: String) : PlaybackWarning() {
        override val message: String get() = "the start position $requested was ignored: $detail"
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
     * Emitted when an attached renderer reports `RendererEvent.SurfaceLost`. A renderer that merely
     * refuses a frame does not raise this: a refusal costs that frame, is counted as a drop, and
     * playback carries on.
     */
    public data class NoRenderSurface(val detail: String) : PlaybackWarning() {
        override val message: String get() = "no render surface: $detail"
    }
}
