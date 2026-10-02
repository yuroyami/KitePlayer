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

/**
 * The session's reader around the item's own. It tells [watch] each time bytes arrive, and so does
 * every reader that [openRelated] makes, such as the segments of an HLS playlist. Those readers add
 * their bytes to [related] too.
 */
internal class ProgressReportingMediaIo(
    private val upstream: MediaIo,
    private val watch: StallWatch,
    val related: RelatedTraffic = RelatedTraffic(),
    /** True for a reader that [openRelated] made, whose bytes count in [related]. */
    private val isRelated: Boolean = false,
) : MediaIo {
    override val size: Long? get() = upstream.size
    override val seekable: Boolean get() = upstream.seekable
    override val location: String? get() = upstream.location
    override val contentType: String? get() = upstream.contentType

    override suspend fun read(into: ByteArray, offset: Int, length: Int): Int =
        upstream.read(into, offset, length).also { count ->
            if (count > 0) {
                watch.progress()
                if (isRelated) related.bytes.addAndGet(count.toLong())
            }
        }

    override suspend fun seek(position: Long) = upstream.seek(position)

    override suspend fun openRelated(uri: String): MediaIo? {
        val opened = upstream.openRelated(uri) ?: return null
        related.opens.incrementAndGet()
        watch.progress()
        return ProgressReportingMediaIo(opened, watch, related, isRelated = true)
    }

    override fun close() = upstream.close()
}

/**
 * What the readers that a session's reader opened for its media have done: how many opened, and
 * how many bytes they delivered. A session whose media reads other addresses, such as an HLS
 * playlist and its segments, has a main reader whose bytes do not map onto the timeline.
 */
internal class RelatedTraffic {
    val opens = atomic(0)
    val bytes = atomic(0L)
}
