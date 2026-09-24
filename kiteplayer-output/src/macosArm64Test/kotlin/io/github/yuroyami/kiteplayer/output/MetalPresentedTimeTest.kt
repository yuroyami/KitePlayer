package io.github.yuroyami.kiteplayer.output

import platform.QuartzCore.CACurrentMediaTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Metal renderer reports a frame as presented at the time the drawable says the display
 * showed it. That time is in seconds of the host clock, and the engine compares it with
 * [AppleHostClock], so the two must be one clock.
 */
class MetalPresentedTimeTest {

    @Test
    fun aDrawableThatWasNeverShownReportsNoTime() {
        assertNull(presentedNanos(0.0))
        assertNull(presentedNanos(-1.0))
        assertNull(presentedNanos(Double.NaN))
        assertEquals(1_500_000_000L, presentedNanos(1.5))
    }

    @Test
    fun theDrawableClockIsTheHostClock() {
        val before = AppleHostClock.nanos()
        val media = checkNotNull(presentedNanos(CACurrentMediaTime()))
        val after = AppleHostClock.nanos()
        assertTrue(
            media in (before - 1_000_000)..(after + 1_000_000),
            "the media clock read $media, outside the host clock's $before..$after",
        )
    }
}
