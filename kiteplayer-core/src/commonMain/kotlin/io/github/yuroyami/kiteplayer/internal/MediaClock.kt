package io.github.yuroyami.kiteplayer.internal

import io.github.yuroyami.kiteplayer.MonotonicClock
import io.github.yuroyami.kiteplayer.NO_PTS

/**
 * Answers one question: what media timestamp is now.
 *
 * A clock does not tick and does not run a timer. It stores the difference between media time
 * and system time at the moment it was last told a real timestamp, and reads back by adding the
 * elapsed system time. Storing the difference rather than an absolute timestamp is what lets
 * playback speed change without re-anchoring, and what makes a read one addition.
 *
 * Not thread safe. Each clock instance is owned by one component. The audio clock is written by
 * the audio feeder, the video clock by the video scheduler, and both are read by the core
 * through [snapshot], which is the only cross-thread access and is guarded by the atomic
 * publication in [ClockSnapshot].
 */
internal class MediaClock(private val monotonic: MonotonicClock) {

    /** Media time minus system time, in nanoseconds, as of [lastSetNanos]. */
    private var driftNanos: Long = 0

    /** The system time at which this clock was last given a real timestamp. */
    private var lastSetNanos: Long = 0

    /** The timestamp last set, kept so a paused clock can report it without arithmetic. */
    private var lastPtsMicros: Long = NO_PTS

    /**
     * Playback rate. 1.0 is normal. The clock stays correct across a change without being
     * re-anchored, because the elapsed-time term below is scaled rather than the drift.
     */
    var speed: Double = 1.0
        set(value) {
            require(value > 0.0) { "speed must be positive, was $value" }
            // Re-anchor at the current media time so the change applies from here on, not
            // retroactively to the whole elapsed interval.
            val now = monotonic.nanos()
            val current = nowMicrosAt(now)
            field = value
            if (current != NO_PTS) setAt(current, generation, now)
        }

    /**
     * The seek and reconfiguration counter this clock belongs to. A clock from a superseded
     * generation is never read: the core discards it along with the queues.
     */
    var generation: Long = 0
        private set

    /** A paused clock reports the timestamp it was paused at, forever, until resumed. */
    var paused: Boolean = false
        set(value) {
            if (field == value) return
            val now = monotonic.nanos()
            if (value) {
                // Freeze at the current value before the flag changes the read path.
                val current = nowMicrosAt(now)
                field = true
                if (current != NO_PTS) {
                    lastPtsMicros = current
                    driftNanos = current * 1_000L - now
                    lastSetNanos = now
                }
            } else {
                field = false
                // Re-anchor so the pause interval does not count as elapsed media time.
                if (lastPtsMicros != NO_PTS) setAt(lastPtsMicros, generation, now)
            }
        }

    /** True when this clock has never been given a timestamp, or was invalidated by a seek. */
    val isValid: Boolean get() = lastPtsMicros != NO_PTS

    /** Sets the clock to [ptsMicros] as of now. */
    fun set(ptsMicros: Long, generation: Long) = setAt(ptsMicros, generation, monotonic.nanos())

    /**
     * Sets the clock to [ptsMicros] as of [atSystemNanos].
     *
     * Passing a system time explicitly matters for audio: the timestamp that becomes audible is
     * known at the moment a buffer is handed to the device, and the sink's reported latency says
     * how far in the future that is. Anchoring at the right instant is the difference between
     * correct sync and being one device buffer late for the whole session.
     */
    fun setAt(ptsMicros: Long, generation: Long, atSystemNanos: Long) {
        lastPtsMicros = ptsMicros
        lastSetNanos = atSystemNanos
        driftNanos = if (ptsMicros == NO_PTS) 0 else ptsMicros * 1_000L - atSystemNanos
        this.generation = generation
    }

    /** Marks the clock unusable. Reads return [NO_PTS] until the next [set]. */
    fun invalidate() {
        lastPtsMicros = NO_PTS
        driftNanos = 0
    }

    /** The current media timestamp in microseconds, or [NO_PTS] when the clock is not valid. */
    fun nowMicros(): Long = nowMicrosAt(monotonic.nanos())

    private fun nowMicrosAt(systemNanos: Long): Long {
        if (lastPtsMicros == NO_PTS) return NO_PTS
        if (paused) return lastPtsMicros
        // mediaNanos = drift + system - elapsed * (1 - speed)
        //
        // At speed 1.0 the third term is zero and this is a plain addition. At speed 2.0 it
        // subtracts the extra media time that the faster rate has already consumed. Writing it
        // this way keeps one formula for every speed and avoids a separate scaled accumulator,
        // which is where rounding error accumulates over a long session.
        val elapsed = systemNanos - lastSetNanos
        val mediaNanos = driftNanos + systemNanos - (elapsed * (1.0 - speed)).toLong()
        return mediaNanos / 1_000L
    }

    /** An immutable read of this clock, safe to publish to another thread. */
    fun snapshot(): ClockSnapshot = ClockSnapshot(
        ptsMicros = nowMicros(),
        generation = generation,
        speed = speed,
        paused = paused,
    )
}

/** An immutable read of a [MediaClock]. */
internal data class ClockSnapshot(
    val ptsMicros: Long,
    val generation: Long,
    val speed: Double,
    val paused: Boolean,
) {
    val isValid: Boolean get() = ptsMicros != NO_PTS

    companion object {
        val Invalid = ClockSnapshot(NO_PTS, 0L, 1.0, true)
    }
}
