@file:OptIn(ExperimentalAtomicApi::class)

package io.github.yuroyami.kiteplayer.network

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * How fast the network delivered the bytes of one reader and of every reader it opened, such as
 * the segments of an HLS playlist (#376).
 *
 * Recent bytes weigh more: each [HALF_LIFE_BYTES] halves the weight of what came before. Downloads
 * can run at the same time, one per rendition, so [add] may come from several threads.
 */
internal class DownloadMeter {
    private class Sums(val bytes: Double, val nanos: Double, val counted: Long)

    private val sums = AtomicReference(Sums(0.0, 0.0, 0L))

    /** [bytes] arrived after a wait of [took] for the network. */
    fun add(bytes: Int, took: Duration) {
        if (bytes <= 0) return
        val nanos = took.inWholeNanoseconds.coerceAtLeast(0L).toDouble()
        val keep = 0.5.pow(bytes / HALF_LIFE_BYTES)
        while (true) {
            val old = sums.load()
            val new = Sums(old.bytes * keep + bytes, old.nanos * keep + nanos, old.counted + bytes)
            if (sums.compareAndSet(old, new)) return
        }
    }

    /** The bytes measured so far, whatever their weight now. */
    fun measuredBytes(): Long = sums.load().counted

    /** Bits per second, recent bytes weighing most, or null before [MIN_BYTES] were measured. */
    fun bitsPerSecond(): Long? {
        val now = sums.load()
        if (now.counted < MIN_BYTES || now.nanos <= 0.0) return null
        return (now.bytes * 8.0 * 1_000_000_000.0 / now.nanos).toLong()
    }

    private companion object {
        /** Bytes after which an earlier byte counts half as much. */
        const val HALF_LIFE_BYTES = 1_048_576.0

        /** Bytes to measure before the figure means anything: about one segment of a low variant. */
        const val MIN_BYTES = 524_288L
    }
}

/**
 * Copies [body] into [pipe] and tells [meter] how fast the bytes arrived, counting from [since],
 * when the request went out. [since] must come from [clock].
 *
 * A write that waits for the reader ends the measure for this response. While it waits, the bytes
 * pile up in the client and the system below, and they would then arrive at once and read as a
 * faster network than the real one.
 */
internal suspend fun copyMeasured(
    body: ByteReadChannel,
    pipe: ByteChannel,
    meter: DownloadMeter,
    since: TimeMark,
    clock: TimeSource = TimeSource.Monotonic,
) {
    val chunk = ByteArray(COPY_CHUNK_BYTES)
    var waitStarted = since
    var measuring = true
    while (true) {
        val count = body.readAvailable(chunk, 0, chunk.size)
        if (count < 0) return
        if (count == 0) continue
        if (measuring) meter.add(count, waitStarted.elapsedNow())
        val writeStarted = clock.markNow()
        pipe.writeFully(chunk, 0, count)
        if (writeStarted.elapsedNow() > PIPE_WAIT) measuring = false
        waitStarted = clock.markNow()
    }
}

private const val COPY_CHUNK_BYTES = 65_536

/** A write that takes longer than this waited for the reader. A busy machine only ends a measure early. */
private val PIPE_WAIT: Duration = 10.milliseconds
