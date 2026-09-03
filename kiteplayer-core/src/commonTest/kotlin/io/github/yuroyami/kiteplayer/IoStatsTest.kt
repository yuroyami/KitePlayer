@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * How fast the bytes are arriving.
 *
 * A network player that cannot say this cannot explain a rebuffer: the picture stops, and the only
 * question worth asking is whether the bytes stopped too, and neither the application nor a bug
 * report could tell. The engine's byte cache sees every read from the source and was counting none
 * of them.
 *
 * The figure is what came over the WIRE. A seek served out of the cache's own window adds nothing,
 * because nothing was fetched, and that is the number a consumer diagnosing a stall actually wants.
 */
class IoStatsTest {

    /** A reader that hands back as much as it is asked for, and remembers how much that was. */
    private class CountingIo(private val total: Long) : MediaIo {
        var handedOut = 0L
            private set
        private var position = 0L

        override val size: Long get() = total
        override val seekable: Boolean get() = true

        override suspend fun read(into: ByteArray, offset: Int, length: Int): Int {
            if (position >= total) return -1
            val count = minOf(length.toLong(), total - position).toInt()
            for (i in 0 until count) into[offset + i] = (position + i).toByte()
            position += count
            handedOut += count
            return count
        }

        override suspend fun seek(position: Long) {
            this.position = position
        }

        override fun close() = Unit
    }

    @Test
    fun `bytes pulled from the source are counted and reported`() = runTest {
        val io = CountingIo(4L * 1024 * 1024)
        val harness = CoreHarness(
            this,
            script = MediaScript(durationUs = 4_000_000),
            config = PlayerConfig(statsInterval = 200.milliseconds),
        )
        harness.openThroughIo { io }
        harness.core.play()
        harness.run(1.seconds)

        val stats = harness.core.stats.value
        assertTrue(io.handedOut > 0, "the reader was never asked for anything, so nothing is measurable")
        assertEquals(
            io.handedOut,
            stats.ioBytesTotal,
            "the reported total disagrees with what the reader actually handed over",
        )
        harness.close()
    }

    @Test
    fun `a rate is reported while bytes are arriving`() = runTest {
        val io = CountingIo(8L * 1024 * 1024)
        val harness = CoreHarness(
            this,
            script = MediaScript(durationUs = 8_000_000),
            config = PlayerConfig(statsInterval = 200.milliseconds),
        )
        harness.openThroughIo { io }
        harness.core.play()

        // Sampled ACROSS the run rather than at the end. The rate is a per-interval difference, and
        // the demuxer stops pulling once its buffers are full, so a reading taken afterwards is
        // legitimately zero: the bytes stopped because nothing needed any. What matters is that the
        // rate was non-zero while they were actually arriving.
        var highest = 0L
        repeat(10) {
            harness.run(200.milliseconds)
            val rate = harness.core.stats.value.ioBytesPerSecond
            if (rate > highest) highest = rate
        }

        assertTrue(highest > 0, "bytes were arriving and the rate never rose above zero")
        harness.close()
    }

    @Test
    fun `an item read through no cache reports zero rather than a wrong number`() = runTest {
        // A plain path goes to the backend's own protocol and the engine's cache never sees a byte,
        // so there is nothing honest to report. Zero here means "not measurable", which the field's
        // own documentation says, and inventing a figure from packet sizes would be worse.
        val harness = CoreHarness(
            this,
            script = MediaScript(durationUs = 2_000_000),
            config = PlayerConfig(statsInterval = 200.milliseconds),
        )
        harness.openWithRenderer()
        harness.core.play()
        harness.run(600.milliseconds)

        assertEquals(0L, harness.core.stats.value.ioBytesTotal)
        assertEquals(0L, harness.core.stats.value.ioBytesPerSecond)
        harness.close()
    }

    @Test
    fun `the total never goes backwards`() = runTest {
        // It is a total for the PLAYER, not for the current session, so a reopen must not reset it:
        // a consumer plotting it would see a cliff that never happened.
        val first = CountingIo(2L * 1024 * 1024)
        val harness = CoreHarness(
            this,
            script = MediaScript(durationUs = 4_000_000),
            config = PlayerConfig(statsInterval = 200.milliseconds),
        )
        harness.openThroughIo { first }
        harness.core.play()
        harness.run(800.milliseconds)
        val afterFirst = harness.core.stats.value.ioBytesTotal
        assertTrue(afterFirst > 0, "nothing was read in the first session")

        harness.core.stop()
        harness.run(200.milliseconds)
        // Deliberately TINY, so the second session cannot on its own reach what the first read.
        // With a source as large as the first, a total that had forgotten the retired bytes would
        // still climb past the old figure and the case would pass while being wrong.
        harness.openThroughIo { CountingIo(8 * 1024) }
        harness.core.play()
        harness.run(800.milliseconds)

        assertTrue(
            harness.core.stats.value.ioBytesTotal >= afterFirst,
            "a reopen reset the total from $afterFirst to ${harness.core.stats.value.ioBytesTotal}",
        )
        harness.close()
    }
}
