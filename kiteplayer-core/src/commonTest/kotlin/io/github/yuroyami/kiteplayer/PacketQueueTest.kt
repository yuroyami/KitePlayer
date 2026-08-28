package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.PacketQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class PacketQueueTest {

    @Test
    fun `inactive cache trimming drops only packets completed before the cutoff`() {
        val ledger = LeakLedger()
        val queue = PacketQueue(streamIndex = 7, softLimitUs = 1_000_000)
        val first = FakePacket(7, Pts(0), duration = Pts(100_000), ledger = ledger)
        val second = FakePacket(7, Pts(100_000), duration = Pts(100_000), ledger = ledger)
        val third = FakePacket(7, Pts(200_000), duration = Pts(100_000), ledger = ledger)

        queue.offer(first, Generation.Initial)
        queue.offer(second, Generation.Initial)
        queue.offer(third, Generation.Initial)

        assertEquals(1, queue.dropBefore(100_000, assumedDurationUs = 0))
        assertEquals(2, queue.count)
        assertEquals(100_000, queue.firstTimestampUs)
        assertEquals(300_000, queue.lastTimestampUs)
        assertSame(second, queue.poll())
        second.close()

        queue.close()
        assertEquals(0, ledger.liveCount)
        assertEquals(0, ledger.doubleCloseCount)
    }

    @Test
    fun `a packet with no timestamps at all still stops cache trimming`() {
        val queue = PacketQueue(streamIndex = 7, softLimitUs = 1_000_000)
        val unknown = FakePacket(7, pts = null, dts = null)
        val later = FakePacket(7, pts = Pts(500_000), duration = Pts(100_000))
        queue.offer(unknown, Generation.Initial)
        queue.offer(later, Generation.Initial)

        assertEquals(0, queue.dropBefore(Long.MAX_VALUE, assumedDurationUs = Long.MAX_VALUE))
        assertEquals(2, queue.count)
        queue.close()
    }

    @Test
    fun `an unknown duration is bounded by the assumed duration instead of stopping trimming forever`() {
        // FFmpeg leaves AVPacket.duration at zero routinely, so a lane whose packets carry no
        // duration must still be trimmable, or it grows until it owns the whole byte budget.
        val queue = PacketQueue(streamIndex = 7, softLimitUs = 1_000_000)
        val opaqueEnd = FakePacket(7, pts = Pts(100_000), duration = null)
        val later = FakePacket(7, pts = Pts(200_000), duration = Pts(100_000))
        queue.offer(opaqueEnd, Generation.Initial)
        queue.offer(later, Generation.Initial)

        // Assumed end is 100_000 + 300_000. A cutoff just short of it keeps the packet.
        assertEquals(0, queue.dropBefore(399_999, assumedDurationUs = 300_000))
        assertEquals(2, queue.count)
        // At the assumed end the packet is finally dropped, and the known-end packet behind it too.
        assertEquals(2, queue.dropBefore(400_000, assumedDurationUs = 300_000))
        assertEquals(0, queue.count)
        queue.close()
    }
}
