package io.github.yuroyami.kiteplayer.internal

import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel

/**
 * Decoded video frames, between the video decoder and the scheduler.
 *
 * Small on purpose. A 4K hardware surface can be 12 MB, and hardware surface pools are small and
 * fixed: Android MediaCodec often has four output buffers in total. Holding many decoded frames buys
 * nothing, because presentation is paced by the clock and not by queue depth, and it risks starving
 * the decoder's own pool, which stalls decoding completely. That failure looks like random freezing
 * and is hard to diagnose, so the bound is explicit and the backend may lower it.
 *
 * [capacity] is how many frames the queue holds, and that is every frame it is responsible for. What
 * it keeps of the frame already on screen is a [ShownFrame], three numbers rather than a picture, so
 * the pipeline's total is this capacity plus one metadata slot that costs nothing.
 *
 * Ownership is one owner at every instant, never shared. The queue owns a frame from [send] until
 * [advance], [dropNext], [discardStale], [flush] or [close] disposes of it, and whoever takes it from
 * [advance] owns it afterwards. The queue closes exactly what it still owns and never touches what it
 * handed over, which is what a retained shown frame made impossible: that frame was closed once by
 * the queue and once by the renderer it was given to. Redrawing on a resize is the renderer's
 * concern, and it keeps its own last image for it.
 *
 * Single producer, single consumer. Every read is non-suspending, because the scheduler must be able
 * to look at the queue and decide without yielding.
 */
internal class FrameQueue(private val capacity: Int) {

    init {
        require(capacity >= 2) {
            "a frame queue needs at least 2 slots: timing a frame requires the next frame's timestamp"
        }
    }

    private val lock = SynchronizedObject()
    private val pending = ArrayDeque<VideoFrame>()

    /** What the frame currently on screen is. Metadata only: the frame itself left with [advance]. */
    private var lastShown: ShownFrame? = null
    private var closed = false

    private val notEmpty = Channel<Unit>(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val hasSpace = Channel<Unit>(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val size: Int get() = synchronized(lock) { pending.size }
    val isFull: Boolean get() = synchronized(lock) { pending.size >= capacity }

    /**
     * Media duration held ahead of the frame on screen. Feeds the buffering decision.
     *
     * Measured to the END of the last queued frame, not to its start. The difference between the
     * two readings is one frame every time, so a queue holding a single frame used to report zero
     * media buffered while holding a real fortieth of a second, and every reading was one frame
     * short of the truth (audit, FrameQueue). A frame with no declared duration contributes only
     * its start, which is the same conservative answer as before for that frame alone.
     */
    val bufferedUs: Long
        get() = synchronized(lock) {
            val first = pending.firstOrNull() ?: return@synchronized 0L
            val last = pending.lastOrNull() ?: return@synchronized 0L
            val lastEnd = last.duration?.let { duration ->
                // Saturating: a decoder that reports a nonsense duration must not wrap the answer
                // into a negative one, which would read as an empty queue and start a rebuffer.
                val end = last.pts.micros + duration.micros
                if (duration.micros > 0 && end < last.pts.micros) Long.MAX_VALUE else end
            } ?: last.pts.micros
            (lastEnd - first.pts.micros).coerceAtLeast(0L)
        }

    /**
     * Adds without suspending.
     *
     * The non-suspending form exists because a caller that has to stay interruptible cannot afford the
     * suspending one. Wrapping a suspending handover in a timeout looks equivalent and is not: a handover
     * that completed can still have its result thrown away by a cancellation that arrives at the same
     * instant, and the caller then offers a frame the queue already holds. That frame is closed twice,
     * which on a real thread with a real hardware pool returns a buffer that is still in use.
     *
     * @return [FrameOffer.Accepted] when the queue took it and owns it from here, [FrameOffer.Full] when
     *         the caller still owns it and should try again, [FrameOffer.Closed] when the queue is gone and
     *         the caller must dispose of it.
     */
    fun offer(frame: VideoFrame): FrameOffer {
        val outcome = synchronized(lock) {
            when {
                closed -> FrameOffer.Closed
                pending.size < capacity -> {
                    pending.addLast(frame)
                    FrameOffer.Accepted
                }
                else -> FrameOffer.Full
            }
        }
        if (outcome == FrameOffer.Accepted) notEmpty.trySend(Unit)
        return outcome
    }

    /** Suspends until there is room, then adds. Returns false when the queue was closed. */
    suspend fun send(frame: VideoFrame): Boolean {
        while (true) {
            when (offer(frame)) {
                FrameOffer.Accepted -> return true
                FrameOffer.Closed -> {
                    frame.close()
                    return false
                }
                FrameOffer.Full -> hasSpace.receive()
            }
        }
    }

    /** The next frame to present, without taking it. Null when none is queued. */
    fun peek(): VideoFrame? = synchronized(lock) { pending.firstOrNull() }

    /** The frame after [peek], used to measure how long [peek] should stay on screen. */
    fun peekNext(): VideoFrame? = synchronized(lock) { pending.getOrNull(1) }

    /** What is on screen, as numbers. Null before the first [advance] and after a flush. */
    val shown: ShownFrame? get() = synchronized(lock) { lastShown }

    /**
     * Hands the next frame to the caller and keeps a record of what it was.
     *
     * The frame leaves the queue outright: from here the caller is its only owner and closes it, or
     * gives it to a renderer that does. What stays behind is [shown], which is all the schedule needs
     * to time the frame after this one.
     *
     * @return the frame now on screen, or null when the queue was empty.
     */
    fun advance(): VideoFrame? {
        val nowShown = synchronized(lock) {
            val next = pending.removeFirstOrNull() ?: return null
            lastShown = ShownFrame(next.pts, next.duration, next.generation)
            next
        }
        hasSpace.trySend(Unit)
        return nowShown
    }

    /** Discards the next frame without showing it. Used by the late drop. */
    fun dropNext(): Boolean {
        val dropped = synchronized(lock) { pending.removeFirstOrNull() } ?: return false
        dropped.close()
        hasSpace.trySend(Unit)
        return true
    }

    /** Discards frames whose generation is not [generation]. Returns how many went. */
    fun discardStale(generation: Generation): Int {
        val stale = mutableListOf<VideoFrame>()
        synchronized(lock) {
            val keep = ArrayDeque<VideoFrame>(pending.size)
            for (frame in pending) {
                if (frame.generation == generation) keep.addLast(frame) else stale += frame
            }
            pending.clear()
            pending.addAll(keep)
        }
        stale.forEach { it.close() }
        if (stale.isNotEmpty()) hasSpace.trySend(Unit)
        return stale.size
    }

    /**
     * Discards everything the queue holds and forgets what is on screen.
     *
     * Called during a seek. The record of the shown frame goes too, because a duration measured
     * across a seek boundary is nonsense and the frame it describes belongs to the position the
     * viewer just left.
     */
    fun flush() {
        val toClose = synchronized(lock) {
            val all = pending.toList()
            pending.clear()
            lastShown = null
            all
        }
        toClose.forEach { it.close() }
        hasSpace.trySend(Unit)
        notEmpty.trySend(Unit)
    }

    /**
     * Suspends until a frame is queued or the queue is closed.
     *
     * Nothing in the engine calls this, and the reason is the quiescence handshake: a worker suspended
     * here cannot reach its own checkpoint, so a seek would wait out its acknowledgement deadline on an
     * empty queue. The video scheduler therefore waits in bounded steps and re-reads, which costs one
     * short wake-up per empty pass and keeps the worker interruptible. This stays because a consumer that
     * is not a parkable worker is the natural user of it.
     */
    suspend fun awaitFrame() {
        if (synchronized(lock) { pending.isNotEmpty() || closed }) return
        notEmpty.receive()
    }

    fun close() {
        val toClose = synchronized(lock) {
            if (closed) return
            closed = true
            val all = pending.toList()
            pending.clear()
            lastShown = null
            all
        }
        toClose.forEach { it.close() }
        hasSpace.trySend(Unit)
        notEmpty.trySend(Unit)
    }

}

/** What [FrameQueue.offer] did with a frame, which is also who owns it afterwards. */
internal enum class FrameOffer { Accepted, Full, Closed }

/**
 * What the frame on screen is, once the frame itself has moved on to the renderer.
 *
 * Three numbers are everything the schedule needs from it. [pts] measures the next frame's duration,
 * [duration] is the decoder's own figure for when that measurement is unusable, and [generation] says
 * whether measuring across the pair means anything at all. Keeping this instead of the frame is what
 * gives every frame exactly one owner.
 */
internal data class ShownFrame(
    val pts: Pts,
    val duration: Pts?,
    val generation: Generation,
)
