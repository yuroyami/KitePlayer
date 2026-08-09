package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.FrameDurationEstimator
import io.github.yuroyami.kiteplayer.internal.FrameQueue
import io.github.yuroyami.kiteplayer.internal.MediaClock
import io.github.yuroyami.kiteplayer.internal.SyncAction
import io.github.yuroyami.kiteplayer.internal.SyncLaw
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import io.github.yuroyami.kiteplayer.spi.VideoRenderer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds

/**
 * The engine's video half: a frame queue, the presentation schedule, and the drop and repeat decision.
 *
 * The full player composes this with [AudioPlayback]. Together they are the synchronisation the whole
 * library exists to get right, and the division of labour between them is the one every serious player
 * settles on: audio runs undisturbed and drives the clock, and video is adjusted to match it. The ear
 * notices a discontinuity in sound immediately; the eye rarely notices a duplicated frame.
 *
 * ### What one [tick] does
 *
 * Reads the queue, works out how long the frame on screen should stay there, and either presents the
 * next frame, waits, or drops it. The rule it applies is [SyncLaw], which is a pure function with its
 * own table-driven tests, so the hard part is decided somewhere it can be checked rather than inside a
 * loop that has to be watched.
 *
 * ### Threading
 *
 * [submit] is called from the video decoder coroutine. [tick] is called from the scheduler coroutine.
 * The queue between them is single producer, single consumer. Nothing else is shared.
 */
public class VideoPlayback(
    private val renderer: VideoRenderer?,
    private val clock: MonotonicClock = MonotonicClock.System,
    /** The container's declared frame rate, used to snap measured durations. */
    containerFrameRate: Double? = null,
    /** True for containers whose timestamps may jump, MPEG-TS above all. */
    timestampsMayJump: Boolean = false,
    queueCapacity: Int = 4,
    private val dropPolicy: FrameDropPolicy = FrameDropPolicy.LateOnly,
) : AutoCloseable {

    private val queue = FrameQueue(queueCapacity + 1)
    private val videoClock = MediaClock(clock)
    private val maxFrameDurationUs =
        if (timestampsMayJump) SyncLaw.MAX_FRAME_DURATION_DISCONTINUOUS_US else SyncLaw.MAX_FRAME_DURATION_NORMAL_US
    private val durations = FrameDurationEstimator(containerFrameRate, maxFrameDurationUs)

    private var generation: Generation = Generation.Initial

    /**
     * The wall time at which the frame currently on screen was nominally shown.
     *
     * One scalar is the whole presentation schedule. Advancing it by each frame's corrected duration
     * is what paces playback, and resetting it when it falls too far behind is what stops a stall from
     * becoming a burst of frames the viewer sees as a fast-forward glitch.
     */
    private var frameTimerNanos: Long = 0
    private var started = false

    private var presented = 0L
    private var droppedLate = 0L
    private var repeated = 0L
    private var lastDriftUs = 0L

    /** Frames presented since the last flush. */
    public val presentedFrames: Long get() = presented

    /** Frames dropped because their time had already passed. */
    public val droppedFrames: Long get() = droppedLate

    /** Frames shown for longer than their own duration, because video was ahead of the clock. */
    public val repeatedFrames: Long get() = repeated

    /**
     * Video clock minus master clock at the last presented frame. Positive means video is ahead.
     *
     * This is the number that says whether synchronisation is working. Over a long file it should stay
     * inside the designed tolerances and show no trend.
     */
    public val drift: Duration get() = lastDriftUs.microseconds

    /** How much decoded video is held ahead of the frame on screen. */
    public val buffered: Duration get() = queue.bufferedUs.microseconds

    public val queuedFrames: Int get() = queue.size

    /** What media timestamp the last presented frame carried. Null before the first one. */
    public fun position(): Pts? = videoClock.nowOrNull()

    /**
     * Hands a decoded frame over, suspending while the queue is full.
     *
     * Suspending here is the backpressure that stops the decoder running ahead of the display. It also
     * bounds how many hardware surfaces are held at once, which matters: a hardware decoder's pool can
     * be as small as four buffers in total, and holding them all stalls decoding completely.
     */
    public suspend fun submit(frame: VideoFrame): Boolean = queue.send(frame)

    /**
     * One step of the presentation schedule.
     *
     * @param masterClock what the master clock reads now, or null when it has no valid reading, which
     *        is normal between a seek and the first audio of the new position. With no master, video
     *        paces itself from its own timestamps.
     * @return how long to wait before calling again. Zero means there is work to do immediately.
     */
    public suspend fun tick(masterClock: Pts?): Duration {
        val next = queue.peek() ?: return IDLE_WAIT

        // A frame from a superseded generation belongs to a position the viewer has left. Discarding
        // it here, at the last hop before the screen, is what makes seeking correct no matter what
        // slipped through the queues.
        if (next.generation != generation) {
            queue.discardStale(generation)
            return Duration.ZERO
        }

        val now = clock.nanos()
        if (!started) {
            // The first frame of a generation establishes the schedule rather than being timed
            // against a frame from before the seek.
            frameTimerNanos = now
            started = true
            return present(next, now, masterClock)
        }

        val shown = queue.peekShown()
        val measuredUs = if (shown != null && shown.generation == next.generation) {
            next.pts.micros - shown.pts.micros
        } else {
            null
        }
        val nominalUs = durations.estimate(measuredUs, shown?.duration?.micros ?: next.duration?.micros)

        val videoNow = videoClock.nowOrNull()
        val delayUs = SyncLaw.targetDelayUs(nominalUs, videoNow, masterClock, maxFrameDurationUs)
        if (SyncLaw.classify(nominalUs, delayUs) == SyncAction.Repeated) repeated++

        val targetNanos = frameTimerNanos + delayUs * 1_000
        if (now < targetNanos) return (targetNanos - now).nanosAsDuration()

        frameTimerNanos += delayUs * 1_000
        // A schedule that has fallen far behind wall time is meaningless. Re-anchoring it is the
        // difference between recovering from a stall and presenting a burst of frames to catch up.
        if (delayUs > 0 && now - frameTimerNanos > SyncLaw.FRAME_TIMER_RESYNC_US * 1_000) {
            frameTimerNanos = now
        }

        // Late drop: only when a frame after this one has also come due, so dropping cannot leave the
        // screen empty.
        if (dropPolicy != FrameDropPolicy.Never) {
            val following = queue.peekNext()
            if (following != null) {
                val followingDueUs = nominalUs
                if (now > frameTimerNanos + followingDueUs * 1_000) {
                    queue.dropNext()
                    droppedLate++
                    return Duration.ZERO
                }
            }
        }

        return present(next, now, masterClock)
    }

    private suspend fun present(frame: VideoFrame, nowNanos: Long, masterClock: Pts?): Duration {
        val shown = queue.advance() ?: return IDLE_WAIT
        videoClock.set(shown.pts, generation)
        masterClock?.let { lastDriftUs = shown.pts.micros - it.micros }

        val renderer = this.renderer
        if (renderer == null) {
            // No renderer attached: audio keeps playing and video frames are accounted as presented
            // so the schedule stays honest. A minimised window must not stop the sound.
            presented++
            return Duration.ZERO
        }
        // The renderer takes ownership from here, including on failure, so the frame must not be
        // touched afterwards.
        if (renderer.present(shown, nowNanos)) presented++ else droppedLate++
        return Duration.ZERO
    }

    /** Marks the end of a generation. Everything queued is dropped and the schedule restarts. */
    public fun flush(newGeneration: Generation) {
        queue.flush()
        videoClock.invalidate()
        durations.reset()
        generation = newGeneration
        started = false
    }

    override fun close() {
        queue.close()
    }

    private fun Long.nanosAsDuration(): Duration = (this / 1_000).microseconds

    private companion object {
        /** How long to wait when there is nothing queued. Short enough to stay responsive. */
        val IDLE_WAIT: Duration = 2_000.microseconds
    }
}
