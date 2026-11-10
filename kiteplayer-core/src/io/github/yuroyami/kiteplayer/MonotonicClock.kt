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
        /**
         * The platform's own monotonic clock, on the time base the platform's audio sinks date
         * their anchors on: `System.nanoTime()` on the JVM and Android, as `DesktopAudioSink` and
         * `AudioTrackSink` use, and the host time on Apple platforms, as `CoreAudioSink` uses, so
         * `AudioPlayback` with its default clock and a shipped sink reads one clock (#829). It used
         * to count from its own first reading, and the position was then off by about the machine's
         * uptime. Elsewhere it counts from its first reading, which no shipped sink depends on.
         */
        public val System: MonotonicClock = SystemMonotonicClock
    }
}

private object SystemMonotonicClock : MonotonicClock {
    override fun nanos(): Long = systemMonotonicNanos()
}

/** The platform's monotonic nanoseconds, on its audio sinks' time base where it has one. */
internal expect fun systemMonotonicNanos(): Long

/** For the platforms with no shipped audio sink: nanoseconds since this clock's first reading. */
internal object FirstReadingClock {
    private val origin = TimeSource.Monotonic.markNow()
    fun nanos(): Long = origin.elapsedNow().inWholeNanoseconds
}
