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
     * is what would make this event mean scanout, needs the renderer protocol in MASTER_PLAN.md
     * (B5).
     */
    public data class FirstFrameRendered(val latency: Duration) : PlayerEvent

    /**
     * A frame was presented. Emitted per frame only when [PlayerConfig.frameEvents] is on.
     *
     * [latency] is the presentation instant minus the schedule's target for that frame, zero when
     * the schedule no longer remembers the target. [exact] carries the renderer's own claim: true
     * means the platform reported the pixels reaching glass, false means the renderer's clock
     * right after its blit, which is the closest that path can observe.
     */
    public data class FramePresented(
        val pts: Pts,
        val atNanos: Long,
        val latency: Duration,
        val exact: Boolean,
    ) : PlayerEvent

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

    /**
     * Playback crossed [marker] while advancing.
     *
     * Fires when the published position moves from before the marker to at or past it while
     * playing. A seek that lands past a marker does not fire it; a seek back behind one, or a
     * loop, re-arms it for the next pass. Each marker fires at most once per pass.
     */
    public data class MarkerReached(val marker: Marker) : PlayerEvent
}

