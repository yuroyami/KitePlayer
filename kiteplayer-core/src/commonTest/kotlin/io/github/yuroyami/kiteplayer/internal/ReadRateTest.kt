package io.github.yuroyami.kiteplayer.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReadRateTest {
    /** Reads [seconds] of media in 40 ms packets, each taking [nanosPerPacket], from [fromUs] on. */
    private fun ReadRate.readMedia(fromUs: Long, seconds: Int, nanosPerPacket: Long): Long {
        var pts = fromUs
        repeat(seconds * 25) {
            pts += 40_000
            read(nanosPerPacket, pts)
        }
        return pts
    }

    @Test
    fun noRateUntilEnoughMediaWasRead() {
        val rate = ReadRate()
        rate.readMedia(0, 5, 20_000_000)
        assertNull(rate.mediaPerReadSecond(6_000_000))
        rate.readMedia(5_000_000, 2, 20_000_000)
        assertNotNull(rate.mediaPerReadSecond(6_000_000))
    }

    @Test
    fun aSteadyLinkReadsItsOwnRate() {
        val rate = ReadRate()
        // 40 ms of media in 20 ms of reading is two seconds of media per second.
        rate.readMedia(0, 10, 20_000_000)
        assertEquals(2.0, assertNotNull(rate.mediaPerReadSecond(6_000_000)), 0.01)
    }

    @Test
    fun aFasterLinkShowsWithinSeconds() {
        val rate = ReadRate()
        val end = rate.readMedia(0, 20, 80_000_000)
        rate.readMedia(end, 24, 4_000_000)
        // Three half-lives later the slow media weighs an eighth, and the figure is over six times the slow rate.
        assertTrue(assertNotNull(rate.mediaPerReadSecond(6_000_000)) > 3.0)
    }

    @Test
    fun aRestartForgetsTheMeasure() {
        val rate = ReadRate()
        rate.readMedia(0, 10, 20_000_000)
        rate.restart()
        assertNull(rate.mediaPerReadSecond(1))
        rate.readMedia(60_000_000, 10, 80_000_000)
        assertEquals(0.5, assertNotNull(rate.mediaPerReadSecond(6_000_000)), 0.01)
    }

    @Test
    fun aTimestampJumpIsNotMediaRead() {
        val rate = ReadRate()
        val end = rate.readMedia(0, 3, 80_000_000)
        rate.read(80_000_000, end + 3_600_000_000)
        assertNull(rate.mediaPerReadSecond(6_000_000), "the hour of the jump does not count")
        rate.readMedia(end + 3_600_000_000, 4, 80_000_000)
        assertEquals(0.5, assertNotNull(rate.mediaPerReadSecond(6_000_000)), 0.01)
    }

    @Test
    fun reorderedPacketsCountTheirMediaOnce() {
        val rate = ReadRate()
        // Decode order with B-frames: 0, 120, 40, 80, 240, 160, 200 ms and so on, each read 20 ms.
        rate.read(20_000_000, 0)
        var base = 0L
        repeat(84) {
            for (offset in longArrayOf(120_000, 40_000, 80_000)) rate.read(20_000_000, base + offset)
            base += 120_000
        }
        // Each 120 ms of media took three reads of 20 ms, so two media seconds a second.
        assertEquals(2.0, assertNotNull(rate.mediaPerReadSecond(6_000_000)), 0.02)
    }

    @Test
    fun theOtherStreamsWaitJoinsTheNextStep() {
        val rate = ReadRate()
        var pts = 0L
        repeat(250) {
            // An audio read with no timestamp of the measured stream, then a video read.
            rate.read(10_000_000, null)
            pts += 40_000
            rate.read(10_000_000, pts)
        }
        assertEquals(2.0, assertNotNull(rate.mediaPerReadSecond(6_000_000)), 0.01)
    }
}
