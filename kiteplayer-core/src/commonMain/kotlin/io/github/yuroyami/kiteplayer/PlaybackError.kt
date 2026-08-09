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

    /** Every candidate decoder for a required track refused to open. */
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

    /** No audio device could be opened, and the media has no video to fall back to. */
    public data class AudioDeviceUnavailable(val detail: String) : PlaybackError() {
        override val message: String get() = "no audio device: $detail"
    }

    /** The engine hit a state it does not know how to leave. This is always a bug here. */
    public data class Internal(val detail: String, override val cause: Throwable? = null) : PlaybackError() {
        override val message: String get() = "internal error: $detail"
    }
}

/**
 * Something went wrong and playback continued.
 *
 * Every warning names a degradation the developer would otherwise have to discover by measurement.
 */
public sealed class PlaybackWarning {
    public abstract val message: String

    /** A hardware decoder was requested and refused. Playback continues in software. */
    public data class HardwareDecodeUnavailable(val codec: String, val reason: String) : PlaybackWarning() {
        override val message: String get() = "hardware decode unavailable for $codec: $reason"
    }

    /** Frames are being dropped to keep up with the clock. */
    public data class FrameDropping(val droppedInLastSecond: Int) : PlaybackWarning() {
        override val message: String get() = "dropping frames: $droppedInLastSecond in the last second"
    }

    /** The audio device restarted, changed or was replaced. Playback recovered. */
    public data class AudioDeviceChanged(val detail: String) : PlaybackWarning() {
        override val message: String get() = "audio device changed: $detail"
    }

    /** The audio device ran out of data. */
    public data class AudioUnderrun(val totalSoFar: Long) : PlaybackWarning() {
        override val message: String get() = "audio underrun, $totalSoFar so far"
    }

    /**
     * The sink cannot report its latency usefully, so the audio clock is counted rather than
     * measured and synchronisation is approximate. Emitted once per sink.
     */
    public data class AudioLatencyUnreliable(val detail: String) : PlaybackWarning() {
        override val message: String get() = "audio latency is not measurable: $detail"
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
     * The file is interleaved so badly that one stream had to be truncated to keep the other
     * playing.
     */
    public data class PathologicalInterleaving(val starvedTrack: TrackId, val droppedPackets: Int) : PlaybackWarning() {
        override val message: String
            get() = "badly interleaved file: dropped $droppedPackets packets to keep $starvedTrack fed"
    }

    /** The renderer lost its surface. Audio continues and video frames are discarded. */
    public data class NoRenderSurface(val detail: String) : PlaybackWarning() {
        override val message: String get() = "no render surface: $detail"
    }
}
