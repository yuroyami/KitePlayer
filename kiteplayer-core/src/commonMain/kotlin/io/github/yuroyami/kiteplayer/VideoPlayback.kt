package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.Percentiles
import io.github.yuroyami.kiteplayer.internal.FrameDurationEstimator
import io.github.yuroyami.kiteplayer.internal.FrameOffer
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
 *
 * ### Ownership
 *
 * A frame has exactly one owner at every instant: the queue until it is advanced, then the renderer
 * from the moment [VideoRenderer.present] is called, including when it fails. With no renderer
 * attached this class closes the frame itself. Nothing here holds a frame it has handed over, so
 * nothing here can close one twice or read one a pool has already taken back.
 */
public class VideoPlayback(
    private val renderer: VideoRenderer?,
    private val clock: MonotonicClock = MonotonicClock.System,
    /** The container's declared frame rate, used to snap measured durations. */
    containerFrameRate: Double? = null,
    /** True for containers whose timestamps may jump, MPEG-TS above all. */
    timestampsMayJump: Boolean = false,
    /**
     * How many decoded frames are held ahead of the screen. Four by default.
     *
     * The frame on screen takes no slot of its own. Once it is presented the renderer owns it and the
     * schedule keeps only its timestamps, so the total the engine holds is this capacity plus one
     * metadata record.
     */
    queueCapacity: Int = 4,
    private val dropPolicy: FrameDropPolicy = FrameDropPolicy.LateOnly,
) : AutoCloseable {

    private val queue = FrameQueue(queueCapacity)
    private val videoClock = MediaClock(clock)
    private val maxFrameDurationUs =
        if (timestampsMayJump) SyncLaw.MAX_FRAME_DURATION_DISCONTINUOUS_US else SyncLaw.MAX_FRAME_DURATION_NORMAL_US
    private val durations = FrameDurationEstimator(containerFrameRate, maxFrameDurationUs)

    private var generation: Generation = Generation.Initial

    /**
     * The rate the NEXT generation will pace at. Like the audio path, a speed change rides a
     * flush: the engine spells a live change as a precise seek, whose flush is the only moment
     * the schedule is quiescent, so [appliedSpeed] can never tear mid-tick.
     */
    private var wantedSpeed: Double = 1.0

    /**
     * The rate of the current generation. Read by [tick] on the scheduler; written by [flush]
     * while the scheduler is quiescent, which is the whole synchronisation story.
     */
    private var appliedSpeed: Double = 1.0

    /**
     * The playback rate as a multiplier of real time. Frame durations and sync corrections are
     * computed in media time and divided by this into wall time, and the video clock
     * extrapolates at the same rate, so a video-only file paces correctly with no audio to
     * follow.
     *
     * Before the schedule's first frame the value applies immediately, which is what a freshly
     * built playback needs. Once frames are flowing it applies from the next [flush], because
     * the flush is the one moment the scheduler is quiescent and nothing can tear.
     */
    public var speed: Double
        get() = wantedSpeed
        set(value) {
            require(value.isFinite() && value > 0.0) { "speed must be finite and positive, was $value" }
            wantedSpeed = value
            if (!started) {
                appliedSpeed = value
                videoClock.speed = value
            }
        }

    /**
     * The wall time at which the frame currently on screen was nominally shown.
     *
     * One scalar is the whole presentation schedule. Advancing it by each frame's corrected duration
     * is what paces playback, and resetting it when it falls too far behind is what stops a stall from
     * becoming a burst of frames the viewer sees as a fast-forward glitch.
     */
    /**
     * The display's refresh interval, seeded at renderer attach and moved by
     * [io.github.yuroyami.kiteplayer.spi.RendererEvent.VsyncChanged]. Advisory, exactly as the
     * SPI says: null costs smoothness on a high-refresh display and nothing else. Held here
     * because the schedule is the one consumer pacing against a display.
     */
    internal var vsyncIntervalNanos: Long? = null

    private var frameTimerNanos: Long = 0
    private var started = false

    private var submitted = 0L
    private var headless = 0L
    private var droppedLate = 0L
    private var refused = 0L
    private var repeated = 0L
    private var lastDriftUs = 0L

    /** How late each accepted frame reached the renderer; sorted only when the stats tick asks. */
    private val lateness = Percentiles(240)

    /**
     * Frames a renderer accepted.
     *
     * Accepted is not drawn. A renderer may take a frame and then supersede it with a newer one, or
     * fail to draw it at all, and it counts those outcomes itself. This number is what the schedule
     * handed over, which is the only thing the schedule can know.
     */
    public val submittedFrames: Long get() = submitted

    /**
     * Frames the schedule presented with no renderer attached.
     *
     * A detached renderer must not stop playback: the sound keeps going and the schedule keeps its
     * pacing, so a minimised window changes nothing but the picture. These frames were never drawn
     * anywhere and are counted apart from the ones that were submitted.
     */
    public val headlessFrames: Long get() = headless

    /**
     * Frames the SCHEDULE dropped because their time had passed.
     *
     * A renderer refusal is [refusedFrames] and is deliberately not counted here. The two are
     * different diagnoses with different fixes: a late drop means the pipeline could not keep up,
     * a refusal means the output could not draw, and folding them together made a dead surface
     * look like a slow decoder.
     */
    public val droppedFrames: Long get() = droppedLate

    /**
     * Frames a renderer was handed and refused, for example because its surface is gone.
     *
     * The schedule did its job for each of these: the frame was decoded, paced and offered on
     * time, and the output said no. Playback continues, which is why this is a counter and not a
     * failure.
     */
    public val refusedFrames: Long get() = refused

    /**
     * Every frame that left the schedule, whatever became of it: submitted, headless or refused.
     *
     * What the engine's one-frame push waits for. Waiting on submissions alone means a refusing
     * renderer never satisfies the wait and burns the whole budget instead.
     */
    public val releasedFrames: Long get() = submitted + headless + refused

    /** How late accepted frames reached the renderer, 95th percentile over the last 240, in nanoseconds. */
    internal fun presentLatenessP95Nanos(): Long = lateness.p(0.95)

    /**
     * Frames the schedule held on screen for a second period, because video was ahead of the master
     * clock.
     *
     * A scheduler decision, counted once for each frame it applies to, at the moment the frame after
     * it is presented. A renderer that shows the same frame twice because it had nothing newer for a
     * display refresh is a different measurement and not this one.
     */
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
     * Hands a decoded frame over without suspending.
     *
     * For a caller that must stay able to answer something else while the queue is full, which is what the
     * engine's decoder worker is: it has to be able to park for a seek, and it cannot do that from inside a
     * wait. Retrying this is safe in a way that wrapping [submit] in a timeout is not, because a handover
     * that has already happened cannot be reported as not having happened. A timeout around [submit] can
     * discard the result of a completed handover, and the caller then offers the queue a frame it already
     * holds, which is closed twice.
     *
     * @return false when the queue is full: the caller still owns the frame and should try again. True when
     *         the frame is no longer the caller's, whether the queue took it or the queue is closed and the
     *         frame was released.
     */
    public fun trySubmit(frame: VideoFrame): Boolean = when (queue.offer(frame)) {
        FrameOffer.Accepted -> true
        FrameOffer.Full -> false
        FrameOffer.Closed -> {
            frame.close()
            true
        }
    }

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
            return present(frameTimerNanos, masterClock)
        }

        val shown = queue.shown
        val measuredUs = if (shown != null && shown.generation == next.generation) {
            next.pts.micros - shown.pts.micros
        } else {
            null
        }
        val nominalUs = durations.estimate(measuredUs, shown?.duration?.micros ?: next.duration?.micros)

        val videoNow = videoClock.nowOrNull()
        val delayUs = SyncLaw.targetDelayUs(nominalUs, videoNow, masterClock, maxFrameDurationUs)

        // The one media-to-wall conversion in the schedule. SyncLaw thinks entirely in media
        // time; at 2x a 40ms frame occupies the screen for 20ms of wall time, so the wall wait
        // is the media delay divided by the rate.
        val delayNanos = (delayUs * 1_000L / appliedSpeed).toLong()
        val targetNanos = frameTimerNanos + delayNanos
        if (now < targetNanos) return (targetNanos - now).nanosAsDuration()

        frameTimerNanos += delayNanos
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
                // How long the candidate would itself stay on screen, measured from the frame after
                // it. The frame already shown has the same duration at a constant frame rate and a
                // different one as soon as timestamps vary, which is exactly when drops happen, so
                // using it decided the drop from the wrong frame's length.
                val candidateUs = (following.pts.micros - next.pts.micros)
                    .takeIf { following.generation == generation && it > 0 && it <= maxFrameDurationUs }
                    ?: nominalUs
                if (now > frameTimerNanos + (candidateUs * 1_000L / appliedSpeed).toLong()) {
                    queue.dropNext()
                    droppedLate++
                    return Duration.ZERO
                }
            }
        }

        // The repeat is counted here, once, because this is the instant the frame it applies to
        // leaves the screen after its second period. Classifying before the wait counted the same
        // repeat again on every scheduler wake-up.
        if (SyncLaw.classify(nominalUs, delayUs) == SyncAction.Repeated) repeated++

        return present(frameTimerNanos, masterClock)
    }

    /**
     * Hands the next frame over, aimed at [targetNanos].
     *
     * [targetNanos] is the schedule's own instant for the frame, never the moment the scheduler woke
     * up to hand it over. The two differ by however late the wake-up was, and a renderer that can aim
     * at a time (a display link, a present-time extension) needs the intended one: given the current
     * instant it draws every frame as late as the scheduler happened to be.
     */
    /** One-shot: the next presented frame is plane-copied for captureFrame (S4.e). */
    internal val captureRequest: kotlinx.atomicfu.AtomicRef<kotlinx.coroutines.CompletableDeferred<CapturedFrame>?> =
        kotlinx.atomicfu.atomic(null)

    private suspend fun present(targetNanos: Long, masterClock: Pts?): Duration {
        val frame = queue.advance() ?: return IDLE_WAIT
        videoClock.set(frame.pts, generation)
        // The capture copies BEFORE ownership moves: from the renderer's first instruction the
        // frame may be recycled into a decoder pool, and a headless present closes it outright.
        captureRequest.getAndSet(null)?.let { request ->
            runCatching { CapturedFrame.of(frame) }
                .onSuccess(request::complete)
                .onFailure(request::completeExceptionally)
        }
        masterClock?.let { lastDriftUs = frame.pts.micros - it.micros }

        val renderer = this.renderer
        if (renderer == null) {
            // No renderer attached: audio keeps playing and the schedule keeps pacing, so a minimised
            // window must not stop the sound. This function is the frame's only owner here, so it
            // closes it.
            frame.close()
            headless++
            return Duration.ZERO
        }
        // The renderer owns the frame from here, including on failure, so it must not be touched
        // afterwards.
        if (renderer.present(frame, targetNanos)) {
            submitted++
            // Read after present returns: a renderer that blocks while it draws is late by that much.
            lateness.add(clock.nanos() - targetNanos)
        } else {
            refused++
        }
        return Duration.ZERO
    }

    /**
     * Freezes the schedule's own sense of time, because playback stopped advancing.
     *
     * The video clock is frozen at its current reading, so the frame on screen keeps reporting the
     * position it belongs to instead of one that moves on while nothing is being shown. The frame timer
     * is left alone here and shifted by [resumeSchedule], which is the only place that can know how long
     * the wait lasted.
     */
    internal fun pauseSchedule() {
        videoClock.pause()
    }

    /**
     * Resumes the schedule after a [pauseSchedule], charging the caller nothing for the interval.
     *
     * The frame timer moves forward by exactly the paused interval and the clock is re-anchored at the
     * reading it was frozen at. Without the shift, the wait counts as time already spent on the frame on
     * screen, so every frame behind it is late the instant playback resumes: the schedule drops one and
     * then holds the next for a second period to get back into phase, which is a visible stutter at the
     * start of every file and after every pause. Measured on a ten second clip: one frame dropped, one
     * repeated and a residual offset of one frame period without this, and none of the three with it.
     */
    internal fun resumeSchedule() {
        if (!videoClock.paused) return
        val frozenAtNanos = videoClock.lastUpdatedNanos
        videoClock.resume()
        if (started) frameTimerNanos += clock.nanos() - frozenAtNanos
    }

    /** Marks the end of a generation. Everything queued is dropped and the schedule restarts. */
    public fun flush(newGeneration: Generation) {
        queue.flush()
        videoClock.invalidate()
        durations.reset()
        generation = newGeneration
        started = false
        // The speed epoch turns over with the generation, while the scheduler is quiescent and
        // the clock is invalid anyway; see [speed].
        appliedSpeed = wantedSpeed
        videoClock.speed = wantedSpeed
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
