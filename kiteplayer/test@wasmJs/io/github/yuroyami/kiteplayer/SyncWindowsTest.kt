package io.github.yuroyami.kiteplayer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** A worker's request fits in its time limit on a slow link that still delivers (#896). */
class SyncWindowsTest {

    private val stall = 30.seconds

    @Test
    fun anUnmeasuredLinkGrowsTheWindowAsBefore() {
        assertEquals(2L shl 20, SyncWindows.next(1L shl 20, forward = true, bitsPerSecond = null, stall = stall))
        assertEquals(16L shl 20, SyncWindows.next(16L shl 20, forward = true, bitsPerSecond = null, stall = stall))
        assertEquals(SyncWindows.FIRST_BYTES, SyncWindows.next(16L shl 20, forward = false, bitsPerSecond = null, stall = stall))
        assertEquals(stall, SyncWindows.timeout(1L shl 20, null, stall))
    }

    @Test
    fun aFastLinkKeepsTheLargestWindow() {
        // 50 Mbit/s delivers 16 MiB in under 3 s, well inside half of 30 s.
        assertEquals(16L shl 20, SyncWindows.next(16L shl 20, forward = true, bitsPerSecond = 50_000_000, stall = stall))
    }

    @Test
    fun aSlowLinkGetsAWindowItDeliversInHalfTheStallTimeout() {
        // The issue's viewer: 3 Mbit/s, where a 16 MiB window took about 45 s and failed at 30 s.
        val rate = 3_000_000L
        val window = SyncWindows.next(16L shl 20, forward = true, bitsPerSecond = rate, stall = stall)
        val seconds = window * 8.0 / rate
        assertTrue(seconds <= 15.0 + 1e-9, "the window takes $seconds s at 3 Mbit/s")
        assertTrue(window >= 5_000_000L, "and still carries most of what fits: $window")
        val limit = SyncWindows.timeout(window, rate, stall)
        assertTrue(limit >= stall + (seconds * 2).seconds - 1.seconds, "$limit")
        assertTrue(limit > (seconds).seconds, "the request has time for its own bytes: $limit")
    }

    @Test
    fun aVerySlowLinkStopsAtTheSmallestWindow() {
        val window = SyncWindows.next(1L shl 20, forward = true, bitsPerSecond = 8_000, stall = stall)
        assertEquals(SyncWindows.SMALLEST_BYTES, window)
        // 64 KiB at 8 kbit/s is about 65 s, and the request is given that twice over on top of the stall timeout.
        assertTrue(SyncWindows.timeout(window, 8_000, stall) > 160.seconds)
    }

    @Test
    fun aTimedOutWindowIsAskedAgainAtAQuarterUntilTheSmallest() {
        // #853: 16 MiB at an unmeasured 3 Mbit/s timed out; 4 MiB finishes in about 11 s.
        assertEquals(4L shl 20, SyncWindows.retry(16L shl 20))
        assertEquals(SyncWindows.SMALLEST_BYTES, SyncWindows.retry(128L shl 10))
        assertEquals(null, SyncWindows.retry(SyncWindows.SMALLEST_BYTES))
    }
}
