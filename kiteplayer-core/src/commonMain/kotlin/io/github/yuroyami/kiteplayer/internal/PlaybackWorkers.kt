package io.github.yuroyami.kiteplayer.internal

import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.spi.HwSurfaceKind
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.spi.RendererEvent
import io.github.yuroyami.kiteplayer.spi.SubtitleOverlay
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import io.github.yuroyami.kiteplayer.spi.VideoRenderer
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Where each of the engine's coroutines runs.
 *
 * One context per worker, and each of those contexts is expected to be a single thread. That is not a
 * style choice: a libavcodec decoding context must be touched by exactly one thread, the demuxer cursor
 * is single threaded for the same reason, and the audio ring is a single-producer single-consumer
 * structure whose producer is the feeder. Handing the engine a multithreaded dispatcher for any of
 * these is how a player gets corruption that only shows up on some machines.
 *
 * Common code cannot build such a set, because `newSingleThreadContext` does not exist on every target
 * this module compiles for. So the set is passed in: a platform builds the real one, and a virtual-time
 * test passes [sharing] with the test dispatcher, which keeps every worker on one cooperative thread
 * and makes a whole session deterministic.
 */
internal interface PlaybackDispatchers : AutoCloseable {
    /** The session actor. Owns all playback state. */
    val session: CoroutineContext
    val demux: CoroutineContext
    val videoDecode: CoroutineContext
    val audioDecode: CoroutineContext
    val audioFeed: CoroutineContext
    val videoSchedule: CoroutineContext

    /** SOL-P5: subtitle rasterisation, off the actor. Serial like every lane. */
    val raster: CoroutineContext

    /**
     * Where a session's release runs at terminal close, so the actor can BOUND its wait for it.
     *
     * A lane of its own and not the session's, because the point is that a release which wedges
     * must not take the coroutine that is timing it with it. The default answers
     * with the session lane, which is right for the single-threaded runtimes and for tests, where
     * every lane is one cooperative dispatcher and there is no second thread to escape to.
     */
    val release: CoroutineContext get() = session

    companion object {
        /**
         * Every worker on one context.
         *
         * Correct whenever that context is a single thread or a cooperative one, which is what a test
         * dispatcher is. It is the shape virtual-time tests use, and it is the only shape common code
         * can construct on every target.
         */
        fun sharing(context: CoroutineContext): PlaybackDispatchers = Shared(context)
    }

    private class Shared(private val context: CoroutineContext) : PlaybackDispatchers {
        override val session: CoroutineContext get() = context
        override val demux: CoroutineContext get() = context
        override val videoDecode: CoroutineContext get() = context
        override val audioDecode: CoroutineContext get() = context
        override val audioFeed: CoroutineContext get() = context
        override val videoSchedule: CoroutineContext get() = context
        override val raster: CoroutineContext get() = context
        override fun close() = Unit
    }
}

/**
 * One worker, and the handshake that makes seeking safe.
 *
 * Generations are defence in depth at every hop and they are not a substitute for this. A generation
 * tag says "this work is stale" at the moment something looks at it; it says nothing about whether a
 * worker is currently inside a decoder, a queue or a device callback. Clearing a queue that a worker is
 * reading, or flushing a decoder a worker is sending to, is a race whatever the tags say. So the actor
 * asks each worker to stop at a boundary of its own choosing, waits for the acknowledgement, and only
 * then touches what that worker owns.
 *
 * The acknowledgement is bounded on both sides. Every worker loop reaches a [checkpoint] within one
 * poll interval, because none of its waits is unbounded, and a worker that has already finished
 * acknowledges from its own exit path. So [quiesce] either gets its answer or reports that it did not,
 * and never hangs.
 */
internal class Worker(val name: String) {

    private val pauseRequested = atomic(false)
    private val finished = atomic(false)
    private val parkedNow = atomic(false)
    private val releaseCount = atomic(0)
    private val epochValue = atomic(0L)

    /** Conflated, so an acknowledgement sent before the actor waits for it is kept rather than lost. */
    private val acked = Channel<Unit>(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val released = Channel<Unit>(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** The epoch this worker is running under. Changed by the actor only while the worker is parked. */
    val epoch: Generation get() = Generation(epochValue.value)

    /** True while the actor is asking for quiescence. A worker holding work gives it up rather than wait. */
    val quiesceRequested: Boolean get() = pauseRequested.value

    val isParked: Boolean get() = parkedNow.value

    val isFinished: Boolean get() = finished.value

    /**
     * How many times the actor has restarted this worker. Every release increments it, epoch or no epoch.
     *
     * A worker holds local state that summarises what it has already done: the demuxer remembers that it
     * reached the end of the container, a decoder remembers that it has sent its drain signal. All of that
     * is void after a restart, and comparing epochs is not enough to notice, because one seek can flush and
     * restart the pipeline several times under the SAME epoch: the overshoot ladder does exactly that when
     * the first attempt lands late. A worker that only watched the epoch kept its stale "I am done" and
     * never read another packet, which is a player that stops for good.
     */
    val releases: Int get() = releaseCount.value

    /** Worker side. Parks here when the actor asked, and returns when it is released. */
    suspend fun checkpoint() {
        if (!pauseRequested.value) return
        try {
            while (true) {
                // parkedNow drops BEFORE the flag's deciding re-read. The old
                // shape read the flag first and dropped the park in a finally, so a quiesce that
                // landed in that gap saw a parked worker whose decision to run was already made.
                // With this order, a quiesce that observes parkedNow true wrote pauseRequested
                // true beforehand, and this worker's next deciding read must see it.
                parkedNow.value = false
                if (!pauseRequested.value) return
                parkedNow.value = true
                acked.trySend(Unit)
                // Bounded, so a release that raced the park is noticed even if its token was consumed.
                withTimeoutOrNull(PARK_POLL) { released.receive() }
            }
        } finally {
            parkedNow.value = false
        }
    }

    /** Worker side, from its exit path. A finished worker is quiescent by definition. */
    fun markFinished() {
        finished.value = true
        acked.trySend(Unit)
    }

    /**
     * Actor side. Asks the worker to stop and waits for it.
     *
     * @return false when the acknowledgement did not arrive inside [timeout], which means the worker is
     *         wedged in something the engine cannot interrupt. The caller reports that rather than
     *         carrying on as if the pipeline were quiescent.
     */
    suspend fun quiesce(timeout: Duration): Boolean {
        if (finished.value) return true
        // A stale acknowledgement from the previous handshake must not satisfy this one.
        do {
            val drained = acked.tryReceive()
        } while (drained.isSuccess)
        pauseRequested.value = true
        if (parkedNow.value) return true
        return withTimeoutOrNull(timeout) { acked.receive() } != null || parkedNow.value || finished.value
    }

    /** Actor side. Puts the worker into [newEpoch], voids its local state, and lets it run again. */
    fun release(newEpoch: Generation) {
        epochValue.value = newEpoch.value
        releaseCount.incrementAndGet()
        pauseRequested.value = false
        released.trySend(Unit)
    }

    private companion object {
        /** How long a parked worker waits before re-reading the request flag. */
        val PARK_POLL: Duration = 50.milliseconds
    }
}

/**
 * The renderer the scheduler always has, wrapping the one the application may or may not have attached.
 *
 * Attaching and detaching are legal at any time, including while playing, and the scheduler is handed
 * one object for the life of the session so nothing about its own state depends on that. With no
 * delegate the wrapper is the frame's only owner and closes it, which is exactly what the scheduler
 * does when it has no renderer at all, so a detached player keeps its pacing and its sound and loses
 * only the picture.
 *
 * [delegate] is written by the session actor while the video scheduler is quiesced. That is the fence
 * detach promises: when the call returns, no submission for the old renderer is outstanding.
 */
internal class AttachableRenderer : VideoRenderer {

    private val submittedCount = atomic(0L)
    private val headlessCount = atomic(0L)

    var delegate: VideoRenderer? = null

    /** Frames a real renderer accepted. Not frames drawn: a renderer counts that itself. */
    val submittedFrames: Long get() = submittedCount.value

    /** Frames presented with nothing attached to draw them. */
    val headlessFrames: Long get() = headlessCount.value

    override fun supportedHardwareSurfaces(): Set<HwSurfaceKind> =
        delegate?.supportedHardwareSurfaces() ?: emptySet()

    override fun supports(format: PlayerPixelFormat): Boolean = delegate?.supports(format) ?: true

    override suspend fun present(frame: VideoFrame, targetNanos: Long): Boolean {
        val target = delegate
        if (target == null) {
            frame.close()
            headlessCount.incrementAndGet()
            // True, because the schedule did its job: there is simply nothing attached to draw on.
            return true
        }
        // Counted only after the renderer says yes: submittedFrames documents "frames a real
        // renderer accepted", and counting before the answer contradicted that on every refusal.
        val accepted = target.present(frame, targetNanos)
        if (accepted) submittedCount.incrementAndGet()
        return accepted
    }

    override fun vsyncIntervalNanos(): Long? = delegate?.vsyncIntervalNanos()

    override val outputSize: io.github.yuroyami.kiteplayer.VideoSize?
        get() = delegate?.outputSize

    override fun setViewport(width: Int, height: Int, scale: Float) {
        delegate?.setViewport(width, height, scale)
    }

    override fun setScaleMode(mode: io.github.yuroyami.kiteplayer.VideoScale) {
        delegate?.setScaleMode(mode)
    }

    override fun setAdjustments(adjustments: io.github.yuroyami.kiteplayer.VideoAdjustments) {
        delegate?.setAdjustments(adjustments)
    }

    override fun setTransform(transform: io.github.yuroyami.kiteplayer.VideoTransform) {
        delegate?.setTransform(transform)
    }

    override suspend fun setOverlay(overlay: SubtitleOverlay?) {
        delegate?.setOverlay(overlay)
    }

    override val events: Flow<RendererEvent> = emptyFlow()

    /** Closes nothing: the application owns what it attached, and the engine never took it over. */
    override fun close() = Unit
}
