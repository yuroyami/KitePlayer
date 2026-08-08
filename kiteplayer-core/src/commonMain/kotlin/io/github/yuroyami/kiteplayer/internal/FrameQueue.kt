package io.github.yuroyami.kiteplayer.internal

import io.github.yuroyami.kiteplayer.Generation
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
 * The frame already shown is retained rather than released. That one decision serves three purposes:
 * redrawing on a resize or an unpause without decoding again, measuring the duration of the frame on
 * screen from the next frame's timestamp, and guaranteeing the lifetime of a buffer a zero-copy
 * consumer may still be reading.
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

    /** The frame currently on screen. Owned here, closed when replaced. */
    private var shown: VideoFrame? = null
    private var closed = false

    private val notEmpty = Channel<Unit>(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val hasSpace = Channel<Unit>(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val size: Int get() = synchronized(lock) { pending.size }
    val isFull: Boolean get() = synchronized(lock) { pending.size >= capacity }

    /** Media duration held ahead of the frame on screen. Feeds the buffering decision. */
    val bufferedUs: Long
        get() = synchronized(lock) {
            val first = pending.firstOrNull() ?: return@synchronized 0L
            val last = pending.lastOrNull() ?: return@synchronized 0L
            (last.pts.micros - first.pts.micros).coerceAtLeast(0L)
        }

    /** Suspends until there is room, then adds. Returns false when the queue was closed. */
    suspend fun send(frame: VideoFrame): Boolean {
        while (true) {
            val outcome = synchronized(lock) {
                when {
                    closed -> SendOutcome.Closed
                    pending.size < capacity -> {
                        pending.addLast(frame)
                        SendOutcome.Accepted
                    }
                    else -> SendOutcome.Full
                }
            }
            when (outcome) {
                SendOutcome.Accepted -> {
                    notEmpty.trySend(Unit)
                    return true
                }
                SendOutcome.Closed -> {
                    frame.close()
                    return false
                }
                SendOutcome.Full -> hasSpace.receive()
            }
        }
    }

    /** The next frame to present, without taking it. Null when none is queued. */
    fun peek(): VideoFrame? = synchronized(lock) { pending.firstOrNull() }

    /** The frame after [peek], used to measure how long [peek] should stay on screen. */
    fun peekNext(): VideoFrame? = synchronized(lock) { pending.getOrNull(1) }

    /** The frame currently on screen, retained so it can be redrawn without decoding again. */
    fun peekShown(): VideoFrame? = synchronized(lock) { shown }

    /**
     * Moves the next frame to the shown position and releases the frame it replaces.
     *
     * @return the frame now shown, or null when the queue was empty.
     */
    fun advance(): VideoFrame? {
        val (nowShown, toClose) = synchronized(lock) {
            val next = pending.removeFirstOrNull() ?: return null
            val previous = shown
            shown = next
            next to previous
        }
        toClose?.close()
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
     * Discards everything, including the frame on screen.
     *
     * Called during a seek. The frame on screen goes too, because the picture must not be a frame
     * from the position the viewer just left.
     */
    fun flush() {
        val toClose = synchronized(lock) {
            val all = pending.toList() + listOfNotNull(shown)
            pending.clear()
            shown = null
            all
        }
        toClose.forEach { it.close() }
        hasSpace.trySend(Unit)
        notEmpty.trySend(Unit)
    }

    /** Suspends until a frame is queued or the queue is closed. */
    suspend fun awaitFrame() {
        if (synchronized(lock) { pending.isNotEmpty() || closed }) return
        notEmpty.receive()
    }

    fun close() {
        val toClose = synchronized(lock) {
            if (closed) return
            closed = true
            val all = pending.toList() + listOfNotNull(shown)
            pending.clear()
            shown = null
            all
        }
        toClose.forEach { it.close() }
        hasSpace.trySend(Unit)
        notEmpty.trySend(Unit)
    }

    private enum class SendOutcome { Accepted, Full, Closed }
}
