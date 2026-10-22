package io.github.yuroyami.kiteplayer

import kotlin.time.TimeSource

/**
 * The source of wall time for the engine.
 *
 * Every timing decision in KitePlayer reads time through this interface and never through a
 * platform clock directly. That is what makes synchronisation, seeking, buffering and frame
 * dropping testable: a test supplies a clock it controls and drives a whole playback session
 * through hours of media in a few milliseconds, deterministically.
 *
 * Implementations must be monotonic. A clock that can go backwards, such as a wall calendar
 * clock adjusted by NTP, will make the engine present frames in the wrong order.
 */
public interface MonotonicClock {

    /**
     * Nanoseconds since an arbitrary fixed origin. Only differences are meaningful.
     *
     * Must never return a smaller value than a previous call. Must be cheap: the engine calls
     * this once per frame at least, and several times per frame while scheduling.
     */
    public fun nanos(): Long

    public companion object {
        /** The platform's own monotonic clock. This is the production implementation. */
        public val System: MonotonicClock = SystemMonotonicClock
    }
}

private object SystemMonotonicClock : MonotonicClock {
    private val origin = TimeSource.Monotonic.markNow()
    override fun nanos(): Long = origin.elapsedNow().inWholeNanoseconds
}
