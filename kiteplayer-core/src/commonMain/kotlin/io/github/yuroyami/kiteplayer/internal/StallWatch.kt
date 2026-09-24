package io.github.yuroyami.kiteplayer.internal

import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.MonotonicClock
import kotlinx.atomicfu.atomic
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/**
 * How long the engine has waited for a source without progress.
 *
 * The demux worker marks each packet read with [begin] and [end], and the session's reader calls
 * [progress] when bytes arrive. The actor reads [stalledFor] and ends the session at
 * `BufferPolicy.stallTimeout`. The demux lane writes and the actor reads, so the fields are atomic.
 */
internal class StallWatch(private val clock: MonotonicClock) {
    private val waits = atomic(0)
    private val sinceNanos = atomic(0L)

    /** A source call starts to wait. */
    fun begin() {
        sinceNanos.value = clock.nanos()
        waits.incrementAndGet()
    }

    /** The source call returned. */
    fun end() {
        waits.decrementAndGet()
    }

    /** Bytes arrived, so the wait counts again from now. */
    fun progress() {
        sinceNanos.value = clock.nanos()
    }

    /** How long the current wait has gone without progress, or null when nothing waits. */
    fun stalledFor(): Duration? =
        if (waits.value > 0) (clock.nanos() - sinceNanos.value).coerceAtLeast(0L).nanoseconds else null
}

/** The session's reader around the item's own. It tells [watch] each time bytes arrive. */
internal class ProgressReportingMediaIo(
    private val upstream: MediaIo,
    private val watch: StallWatch,
) : MediaIo {
    override val size: Long? get() = upstream.size
    override val seekable: Boolean get() = upstream.seekable

    override suspend fun read(into: ByteArray, offset: Int, length: Int): Int =
        upstream.read(into, offset, length).also { if (it > 0) watch.progress() }

    override suspend fun seek(position: Long) = upstream.seek(position)

    override fun close() = upstream.close()
}
