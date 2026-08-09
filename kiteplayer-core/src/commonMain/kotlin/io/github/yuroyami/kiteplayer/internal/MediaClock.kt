package io.github.yuroyami.kiteplayer.internal

import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.MonotonicClock
import io.github.yuroyami.kiteplayer.Pts

/**
 * Answers one question: what media timestamp is now.
 *
 * A clock does not tick and does not run a timer. It stores the difference between media time and
 * system time as of the last real timestamp it was given, and reads back by adding elapsed system
 * time. Two consequences, both wanted: a read is one addition, and there is no timer thread whose
 * period bounds the accuracy.
 *
 * An invalid clock reads as null rather than as a sentinel value. ffplay uses `NaN` here and it
 * works only because every use site has an explicit guard, since every comparison against `NaN` is
 * false. A missing guard there fails silently. `Pts?` makes the compiler ask for the guard.
 *
 * A clock is invalid in three routine situations: after a seek and before the first frame of the new
 * generation, at the very start, and when its generation has been superseded. None of those is an
 * error.
 *
 * Not thread safe. Each clock has one owner: the audio clock is written by the audio feeder, the
 * video clock by the video scheduler. Cross-thread reads go through [snapshot].
 */
internal class MediaClock(private val monotonic: MonotonicClock) {

    /** Media nanoseconds minus system nanoseconds, as of [lastSetNanos]. */
    private var driftNanos: Long = 0
    private var lastSetNanos: Long = 0
    private var anchor: Pts? = null

    /**
     * The system time of the last anchoring.
     *
     * This is what resuming needs. Pausing freezes the clock at its current reading, so the interval
     * spent paused is `now - lastUpdatedNanos` when playback resumes. The video scheduler's frame
     * timer is shifted forward by exactly that interval, and the clock is re-anchored at the reading
     * it was frozen at. Without the shift the paused interval counts as time already waited, and
     * every frame buffered at the moment of the pause is late the instant playback resumes.
     */
    val lastUpdatedNanos: Long get() = lastSetNanos

    var generation: Generation = Generation.Initial
        private set

    var speed: Double = 1.0
        set(value) {
            require(value > 0.0) { "speed must be positive, was $value" }
            // Re-anchor at the current media time first, so the new rate applies from here onward
            // and not retroactively to the whole elapsed interval.
            val now = monotonic.nanos()
            val current = readAt(now)
            field = value
            if (current != null) setAt(current, generation, now)
        }

    var paused: Boolean = false
        private set

    val isValid: Boolean get() = anchor != null

    fun set(pts: Pts, generation: Generation) = setAt(pts, generation, monotonic.nanos())

    /**
     * Anchors the clock: [pts] is the media time at [atSystemNanos].
     *
     * Passing the instant explicitly is what audio needs. The timestamp that becomes audible is
     * known when a buffer is handed to the device, and the device's reported latency says how far in
     * the future that is. Anchoring at the right instant, rather than at the moment the engine
     * noticed, is the difference between correct sync and being one device buffer late for the whole
     * session.
     */
    fun setAt(pts: Pts, generation: Generation, atSystemNanos: Long) {
        anchor = pts
        lastSetNanos = atSystemNanos
        driftNanos = pts.micros * 1_000L - atSystemNanos
        this.generation = generation
    }

    /** Marks the clock unusable until the next anchoring. */
    fun invalidate() {
        anchor = null
        driftNanos = 0
    }

    /** The current media timestamp, or null when this clock has no valid reading. */
    fun nowOrNull(): Pts? = readAt(monotonic.nanos())

    fun pause() {
        if (paused) return
        val now = monotonic.nanos()
        val frozen = readAt(now)
        paused = true
        if (frozen != null) setAt(frozen, generation, now)
    }

    fun resume() {
        if (!paused) return
        val frozen = anchor
        paused = false
        // Re-anchor so the paused interval does not count as elapsed media time.
        if (frozen != null) setAt(frozen, generation, monotonic.nanos())
    }

    private fun readAt(systemNanos: Long): Pts? {
        val current = anchor ?: return null
        if (paused) return current
        // mediaNanos = drift + system - elapsed * (1 - speed)
        //
        // At speed 1.0 the last term is zero and this is a plain addition. At speed 2.0 it subtracts
        // the extra media time the faster rate has already consumed. One formula for every speed,
        // with no separately scaled accumulator to drift over a long session.
        val elapsed = systemNanos - lastSetNanos
        val mediaNanos = driftNanos + systemNanos - ((elapsed * (1.0 - speed)).toLong())
        return Pts(mediaNanos / 1_000L)
    }

    /** An immutable read, safe to hand to another thread. */
    fun snapshot(): ClockSnapshot =
        ClockSnapshot(nowOrNull(), generation, speed, paused)
}

/** An immutable read of a [MediaClock]. */
internal data class ClockSnapshot(
    val pts: Pts?,
    val generation: Generation,
    val speed: Double,
    val paused: Boolean,
) {
    val isValid: Boolean get() = pts != null

    companion object {
        val Invalid = ClockSnapshot(null, Generation.Initial, 1.0, true)
    }
}
