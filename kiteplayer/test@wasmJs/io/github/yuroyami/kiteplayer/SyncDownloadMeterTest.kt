package io.github.yuroyami.kiteplayer

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The rate a worker's reader reports, which lets an HLS stream step up by itself there (#546). */
class SyncDownloadMeterTest {

    @Test
    fun noRateIsStatedBeforeHalfAMebibyteWasMeasured() {
        val meter = SyncDownloadMeter()
        assertNull(meter.bitsPerSecond())
        meter.add(400_000, 1.seconds)
        assertNull(meter.bitsPerSecond(), "one small playlist and a key say nothing of the link")
        meter.add(0, 5.seconds)
        meter.add(200_000, 500.milliseconds)
        // Both arrived at 3.2 Mbit/s.
        val rate = assertNotNull(meter.bitsPerSecond())
        assertTrue(rate in 3_190_000L..3_210_000L, "$rate")
    }

    @Test
    fun recentBytesWeighMost() {
        val meter = SyncDownloadMeter()
        // Four mebibytes at 8 Mbit/s, then four at 1 Mbit/s.
        repeat(4) { meter.add(1 shl 20, 1.seconds) }
        val fast = assertNotNull(meter.bitsPerSecond())
        assertTrue(fast in 8_300_000L..8_500_000L, "$fast")
        repeat(4) { meter.add(1 shl 20, 8.seconds) }
        val slow = assertNotNull(meter.bitsPerSecond())
        assertTrue(slow in 1_000_000L..1_200_000L, "the old speed has almost gone: $slow")
    }
}
