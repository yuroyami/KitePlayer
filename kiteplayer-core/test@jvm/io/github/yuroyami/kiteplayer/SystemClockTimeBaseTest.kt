package io.github.yuroyami.kiteplayer

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The default clock reads the time base the JVM's audio sink dates its anchors on (#829).
 * `DesktopAudioSink` dates on `System.nanoTime()`, and `MonotonicClock.System` counted from its own
 * first reading, so `AudioPlayback(DesktopAudioSink())` was off by about the machine's uptime.
 */
class SystemClockTimeBaseTest {

    @Test
    fun theDefaultClockReadsTheTimeBaseTheDesktopSinkDatesOn() {
        val before = System.nanoTime()
        val clock = MonotonicClock.System.nanos()
        val after = System.nanoTime()
        assertTrue(
            clock in before..after,
            "MonotonicClock.System read $clock, outside System.nanoTime()'s $before..$after, off by ${abs(clock - before)} ns",
        )
    }
}
