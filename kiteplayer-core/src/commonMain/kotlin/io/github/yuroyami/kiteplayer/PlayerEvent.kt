package io.github.yuroyami.kiteplayer

import kotlin.time.Duration

/**
 * Something that happened, as opposed to something that is.
 *
 * The engine's session core emits these and [KitePlayer.events] carries them. The division of labour
 * between this and [PlayerSnapshot] is strict, and it is the fix for the single most common mistake
 * made against player APIs.
 *
 * - **State** goes in the snapshot. A snapshot conflates: a consumer that misses an intermediate
 *   value still ends up correct, because the latest value is the truth.
 * - **Occurrences** go here, and only when missing one is harmless or when the occurrence itself is
 *   the information. Nothing a consumer must count is delivered as an event. Counters live in
 *   [PlaybackStats] instead, because a consumer can diff two snapshots and cannot recover a lost
 *   event.
 *
 * libmpv publishes everything as events, coalesces them, and documents that one change event does
 * not mean one change. Clients then write incremental logic that is subtly wrong and only fails on
 * slow machines. This split makes that impossible to express.
 */
public sealed interface PlayerEvent {

    /** Loading finished and the first frame is ready. Carries the media that opened. */
    public data class Opened(val media: MediaItem, val tracks: Tracks) : PlayerEvent

    /**
     * A seek finished and the target frame is ready. [generation] identifies which seek, so a
     * consumer that issued several can tell which one completed.
     */
    public data class SeekCompleted(val generation: Generation, val landedAt: Duration) : PlayerEvent

    /** The video's size or pixel aspect changed, at the start or mid-stream. */
    public data class VideoSizeChanged(val size: VideoSize) : PlayerEvent

    /** The audio output format changed, at the start or after a device change. */
    public data class AudioFormatChanged(val sampleRate: Int, val channels: Int) : PlayerEvent

    /**
     * The first frame of this media item left the schedule. [latency] is measured from the open.
     *
     * What "left the schedule" means is the strongest signal this build has: a renderer accepted the
     * frame, or, with nothing attached, the schedule presented and released it. It is not a report that
     * a pixel reached a display. A renderer that accepts a frame may still supersede it with a newer one
     * or fail to draw it, and it counts those outcomes itself. Per-submission terminal feedback, which
     * is what would make this event mean scanout, needs the renderer protocol in KPKMP.md section 11
     * (B5).
     */
    public data class FirstFrameRendered(val latency: Duration) : PlayerEvent

    /** Playback reached the end. Emitted once, before the status becomes Ended. */
    public data object Ended : PlayerEvent

    /** Something degraded and playback continued. */
    public data class Warning(val warning: PlaybackWarning) : PlayerEvent

    /** Playback stopped. The same error is on the snapshot. */
    public data class Failed(val error: PlaybackError) : PlayerEvent

    /**
     * A chapter boundary was crossed (S4.e).
     *
     * Emitted whenever the published position moves from one chapter's span into another's,
     * whether playback carried it there or a seek did. Null means the position sits before the
     * first chapter of a chaptered file. Media with no chapter table emits nothing.
     */
    public data class ChapterChanged(val chapter: Chapter?) : PlayerEvent
}

/**
 * Where the engine will send its diagnostics.
 *
 * Nothing calls it. No engine code writes a log line, so one supplied through [PlayerConfig] stays
 * silent, and the diagnostics that do exist are [KitePlayer.stats] and [KitePlayer.events].
 * Not implemented yet; see the roadmap in KPKMP.md section 11.
 */
public fun interface PlayerLogger {
    public fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?)
}

public enum class LogLevel { Trace, Debug, Info, Warn, Error }
