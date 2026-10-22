package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds

/**
 * Following an external clock at either end of the speed range never fails playback (#413): the
 * clock's correction saturates at the end of the range instead of asking for a speed outside it.
 */
class ExternalClockSpeedRangeTest {

    /** Plays at [speed] while a clock [offsetUs] off the position moves at that speed. */
    private suspend fun kotlinx.coroutines.test.TestScope.followAt(speed: Double, offsetUs: Long): PlaybackStatus {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 60_000_000))
        harness.core.setSpeed(speed)
        harness.openWithRenderer()
        harness.core.play()
        harness.run(500.milliseconds)
        val origin = harness.clock.nanos()
        val position = harness.core.position().inWholeMicroseconds
        harness.core.setExternalClock(
            ExternalClock { at -> (position + offsetUs + ((at - origin) * speed / 1000).toLong()).microseconds },
        )
        harness.run(500.milliseconds)
        // A change of speed while the trim is pulling must not fail either.
        harness.core.setSpeed(speed)
        harness.run(500.milliseconds)
        val status = harness.core.snapshots.value.status
        val error = harness.core.snapshots.value.error
        harness.close()
        check(status != PlaybackStatus.Failed) { "failed at ${speed}x with the clock $offsetUs us off: $error" }
        return status
    }

    @Test
    fun fourTimesWithTheClockAheadKeepsPlaying() = runTest {
        assertEquals(PlaybackStatus.Playing, followAt(4.0, 20_000))
    }

    @Test
    fun aQuarterWithTheClockBehindKeepsPlaying() = runTest {
        assertEquals(PlaybackStatus.Playing, followAt(0.25, -20_000))
    }

    @Test
    fun justInsideEachEndKeepsPlaying() = runTest {
        assertEquals(PlaybackStatus.Playing, followAt(3.99, 20_000))
        assertEquals(PlaybackStatus.Playing, followAt(0.251, -20_000))
    }

    @Test
    fun theClockPullingInwardsAtEachEndKeepsPlaying() = runTest {
        assertEquals(PlaybackStatus.Playing, followAt(4.0, -20_000))
        assertEquals(PlaybackStatus.Playing, followAt(0.25, 20_000))
    }

    @Test
    fun normalSpeedStaysTheControl() = runTest {
        assertEquals(PlaybackStatus.Playing, followAt(1.0, 20_000))
    }
}
