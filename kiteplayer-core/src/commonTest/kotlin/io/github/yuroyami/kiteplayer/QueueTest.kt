package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.FrameQueue
import io.github.yuroyami.kiteplayer.internal.PacketQueue
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PacketQueueTest {

    private fun queue(softLimitUs: Long = 5_000_000) = PacketQueue(streamIndex = 0, softLimitUs = softLimitUs)

    @Test
    fun `packets come out in order`() = runTest {
        val q = queue()
        repeat(3) { q.offer(FakePacket(0, pts(it * 40L)), Generation.Initial) }

        assertEquals(pts(0), q.receive()?.pts)
        assertEquals(pts(40), q.receive()?.pts)
        assertEquals(pts(80), q.receive()?.pts)
    }

    @Test
    fun `accounting tracks count bytes and duration`() = runTest {
        val q = queue()
        q.offer(FakePacket(0, pts(0), duration = Pts(40_000), sizeBytes = 500), Generation.Initial)
        q.offer(FakePacket(0, pts(40), duration = Pts(40_000), sizeBytes = 700), Generation.Initial)

        assertEquals(2, q.count)
        assertEquals(1_200, q.bytesBuffered)
        assertEquals(80_000, q.bufferedUs)

        q.receive()
        assertEquals(1, q.count)
        assertEquals(700, q.bytesBuffered)
        assertEquals(40_000, q.bufferedUs)
    }

    @Test
    fun `a packet from a superseded generation is dropped and closed rather than returned`() = runTest {
        val ledger = LeakLedger()
        val q = queue()

        val stale = FakePacket(0, pts(0), ledger = ledger)
        q.offer(stale, Generation.Initial)
        q.flushTo(Generation(1))

        // The flush already closed it. Now a late arrival from the old generation.
        val late = FakePacket(0, pts(40), ledger = ledger)
        q.offer(late, Generation.Initial)
        assertTrue(late.closed, "a packet offered under an old generation must be closed at once")

        val fresh = FakePacket(0, pts(1_000), ledger = ledger)
        q.offer(fresh, Generation(1))
        assertSame(fresh, q.receive(), "only the current generation survives")
        assertEquals(0, ledger.doubleCloseCount)
    }

    @Test
    fun `flush closes everything it drops`() = runTest {
        val ledger = LeakLedger()
        val q = queue()
        repeat(10) { q.offer(FakePacket(0, pts(it * 40L), ledger = ledger), Generation.Initial) }

        assertEquals(10, ledger.liveCount)
        q.flushTo(Generation(1))
        assertEquals(0, ledger.liveCount, "a flush must not leak the packets it drops")
        assertEquals(0, q.count)
        assertEquals(0, q.bytesBuffered)
        assertEquals(0, q.bufferedUs)
    }

    @Test
    fun `end of stream reads as null and does not block`() = runTest {
        val q = queue()
        q.offer(FakePacket(0, pts(0)), Generation.Initial)
        q.signalEndOfStream(Generation.Initial)

        assertEquals(pts(0), q.receive()?.pts)
        assertNull(q.receive(), "after the last packet, end of stream reads as null")
        assertNull(q.receive(), "and keeps reading as null")
    }

    @Test
    fun `a flush clears the end of stream flag`() = runTest {
        val q = queue()
        q.signalEndOfStream(Generation.Initial)
        assertTrue(q.isEndOfStream)

        // Seeking backwards out of the end of the file must make the stream live again. Without
        // this, a seek after playback ends leaves the pipeline permanently finished.
        q.flushTo(Generation(1))
        assertFalse(q.isEndOfStream)

        q.offer(FakePacket(0, pts(0)), Generation(1))
        assertEquals(pts(0), q.receive()?.pts)
    }

    @Test
    fun `readiness is duration or count or end of stream`() = runTest {
        val q = queue()
        assertFalse(q.isReady(readyUs = 1_000_000, readyPackets = 25))

        // Duration alone is enough.
        q.offer(FakePacket(0, pts(0), duration = Pts(1_100_000)), Generation.Initial)
        assertTrue(q.isReady(readyUs = 1_000_000, readyPackets = 25))

        // So is count, for a stream whose packets carry no durations.
        val q2 = queue()
        repeat(25) { q2.offer(FakePacket(0, pts(it * 40L), duration = null), Generation.Initial) }
        assertTrue(q2.isReady(readyUs = 1_000_000, readyPackets = 25))

        // And so is having nothing more to give. A one-frame stream must not stall the start.
        val q3 = queue()
        q3.signalEndOfStream(Generation.Initial)
        assertTrue(q3.isReady(readyUs = 1_000_000, readyPackets = 25))
    }

    @Test
    fun `offering never blocks because a per-stream stall deadlocks a badly interleaved file`() = runTest {
        // The video queue of a file that front-loads 5 seconds of video before any audio must be
        // allowed to grow. If offering blocked here, the demux worker would stop, the audio decoder
        // would starve, the audio clock would stop, and the video would never be consumed.
        val q = queue(softLimitUs = 1_000_000)
        repeat(10_000) { q.offer(FakePacket(0, pts(it * 40L), duration = Pts(40_000)), Generation.Initial) }

        assertEquals(10_000, q.count)
        assertTrue(q.isWellBuffered)
    }

    @Test
    fun `dropping from the tail keeps the oldest packets`() = runTest {
        // The pathological interleaving escape hatch. What is dropped is the newest video, because
        // the oldest is what the decoder needs next.
        val ledger = LeakLedger()
        val q = queue()
        repeat(100) { q.offer(FakePacket(0, pts(it * 40L), sizeBytes = 1_000, ledger = ledger), Generation.Initial) }

        val dropped = q.dropFromTail(targetBytes = 50_000)
        assertEquals(50, dropped)
        assertEquals(50, q.count)
        assertEquals(50, ledger.liveCount)
        assertEquals(pts(0), q.receive()?.pts, "the oldest packet must still be first")
    }

    @Test
    fun `close closes everything and reads as null`() = runTest {
        val ledger = LeakLedger()
        val q = queue()
        repeat(5) { q.offer(FakePacket(0, pts(it * 40L), ledger = ledger), Generation.Initial) }

        q.close()
        assertEquals(0, ledger.liveCount)
        assertNull(q.receive())
    }
}

class FrameQueueTest {

    @Test
    fun `a queue needs at least two slots`() {
        // Timing a frame requires the next frame's timestamp, so one slot cannot work.
        assertFailsWith<IllegalArgumentException> { FrameQueue(1) }
        FrameQueue(2)
    }

    @Test
    fun `advance moves a frame to the shown position and keeps it there`() = runTest {
        val ledger = LeakLedger()
        val q = FrameQueue(4)
        val a = FakeVideoFrame(pts(0), ledger = ledger)
        val b = FakeVideoFrame(pts(40), ledger = ledger)
        q.send(a)
        q.send(b)

        assertSame(a, q.peek())
        assertSame(b, q.peekNext())
        assertNull(q.peekShown())

        assertSame(a, q.advance())
        assertSame(a, q.peekShown())
        assertFalse(a.closed, "the shown frame is retained, so a resize or unpause can redraw it")
        assertSame(b, q.peek())

        assertSame(b, q.advance())
        assertTrue(a.closed, "the previously shown frame is released when it is replaced")
        assertSame(b, q.peekShown())
    }

    @Test
    fun `peekNext is what makes a duration measurable`() = runTest {
        val q = FrameQueue(4)
        q.send(FakeVideoFrame(pts(0)))
        assertNull(q.peekNext(), "with one frame queued there is nothing to measure against")

        q.send(FakeVideoFrame(pts(41)))
        assertEquals(pts(41), q.peekNext()?.pts)
    }

    @Test
    fun `send suspends when the queue is full and resumes when a slot frees`() = runTest {
        val q = FrameQueue(2)
        assertTrue(q.send(FakeVideoFrame(pts(0))))
        assertTrue(q.send(FakeVideoFrame(pts(40))))
        assertTrue(q.isFull)

        // A hardware decoder's surface pool is small, so this bound is what stops the decoder from
        // starving its own pool and stalling completely.
        var sent = false
        val job = launch {
            q.send(FakeVideoFrame(pts(80)))
            sent = true
        }
        yield()
        assertFalse(sent, "a full queue must hold the producer")

        q.advance()
        job.join()
        assertTrue(sent)
    }

    @Test
    fun `stale frames are discarded and closed`() = runTest {
        val ledger = LeakLedger()
        val q = FrameQueue(8)
        q.send(FakeVideoFrame(pts(0), Generation.Initial, ledger = ledger))
        q.send(FakeVideoFrame(pts(40), Generation(1), ledger = ledger))
        q.send(FakeVideoFrame(pts(80), Generation.Initial, ledger = ledger))
        q.send(FakeVideoFrame(pts(120), Generation(1), ledger = ledger))

        assertEquals(2, q.discardStale(Generation(1)))
        assertEquals(2, q.size)
        assertEquals(2, ledger.liveCount)
        assertEquals(pts(40), q.peek()?.pts, "the surviving frames keep their order")
    }

    @Test
    fun `flush drops the shown frame too`() = runTest {
        val ledger = LeakLedger()
        val q = FrameQueue(4)
        q.send(FakeVideoFrame(pts(0), ledger = ledger))
        q.send(FakeVideoFrame(pts(40), ledger = ledger))
        q.advance()

        q.flush()
        assertEquals(
            0,
            ledger.liveCount,
            "a seek must clear the picture too: the frame on screen is from the position the viewer left",
        )
        assertNull(q.peekShown())
        assertNull(q.peek())
    }

    @Test
    fun `dropNext discards without showing`() = runTest {
        val ledger = LeakLedger()
        val q = FrameQueue(4)
        val late = FakeVideoFrame(pts(0), ledger = ledger)
        q.send(late)
        q.send(FakeVideoFrame(pts(40), ledger = ledger))

        assertTrue(q.dropNext())
        assertTrue(late.closed)
        assertNull(q.peekShown(), "a dropped frame was never shown")
        assertEquals(pts(40), q.peek()?.pts)
        assertFalse(FrameQueue(2).dropNext(), "dropping from an empty queue is a no-op")
    }

    @Test
    fun `close releases everything`() = runTest {
        val ledger = LeakLedger()
        val q = FrameQueue(4)
        repeat(3) { q.send(FakeVideoFrame(pts(it * 40L), ledger = ledger)) }
        q.advance()

        q.close()
        assertEquals(0, ledger.liveCount)
        assertEquals(0, ledger.doubleCloseCount)
        assertFalse(q.send(FakeVideoFrame(pts(999))), "a closed queue rejects and closes what it is given")
    }
}
