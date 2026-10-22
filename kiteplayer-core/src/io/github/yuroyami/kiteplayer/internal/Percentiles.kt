package io.github.yuroyami.kiteplayer.internal

/**
 * The last [capacity] samples, answering a percentile by sorting a copy when asked.
 *
 * Adding is a store and an increment. Sorting happens only in [p], which the stats tick calls
 * once a second, so a hot path pays nothing for keeping the window. Written on one worker and
 * read on the actor without a lock, like every other stats counter: a torn read is a figure one
 * sample stale, never a crash.
 */
internal class Percentiles(private val capacity: Int = 240) {

    private val ring = LongArray(capacity)
    private var count = 0
    private var next = 0

    fun add(value: Long) {
        ring[next] = value
        next = (next + 1) % capacity
        if (count < capacity) count++
    }

    /** The value at [fraction] of the sorted window (0.5 is the median). Zero with fewer than two samples. */
    fun p(fraction: Double): Long {
        val n = count
        if (n < 2) return 0L
        val sorted = ring.copyOf(n)
        sorted.sort()
        return sorted[((n - 1) * fraction).toInt().coerceIn(0, n - 1)]
    }
}
