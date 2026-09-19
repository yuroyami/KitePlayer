package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.SampleFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AudioPcmQueueTest {
    private val stereo = AudioFormat(48_000, 2, SampleFormat.F32)

    @Test
    fun `borrowed input is copied and a held slot cannot be overwritten`() {
        val queue = AudioPcmQueue(capacity = 2, samplesPerSlot = 8)
        val borrowed = FloatArray(8) { it / 10f }
        val expected = borrowed.copyOf()
        assertEquals(AudioPcmQueue.Offer.Accepted, queue.offer(Generation(7), 3, -1000, borrowed, 4, stereo))
        val held = assertNotNull(queue.peek())
        borrowed.fill(-1f)
        assertEquals(expected.toList(), held.samples.toList())
        assertEquals(Generation(7), held.generation)
        assertEquals(3L, held.revision)
        assertEquals(-1000L, held.ptsMicros)

        assertEquals(AudioPcmQueue.Offer.Accepted, queue.offer(Generation(7), 3, 0, borrowed, 4, stereo))
        repeat(100) {
            assertEquals(AudioPcmQueue.Offer.Full, queue.offer(Generation(7), 3, 0, borrowed, 4, stereo))
        }
        assertEquals(expected.toList(), held.samples.toList(), "producer overwrote a consumer-owned slot")
        assertEquals(2, queue.pendingSlots)
        queue.release(held)
        assertEquals(AudioPcmQueue.Offer.Accepted, queue.offer(Generation(7), 3, 0, borrowed, 4, stereo))
    }

    @Test
    fun `split blocks preserve sample order and timestamps from sample counts`() {
        val queue = AudioPcmQueue(capacity = 4, samplesPerSlot = 6)
        val format = AudioFormat(44_100, 2, SampleFormat.F32)
        val borrowed = FloatArray(20) { it.toFloat() }
        assertEquals(AudioPcmQueue.Offer.Accepted, queue.offer(Generation(2), 9, 1_000_000L, borrowed, 10, format))
        val copied = mutableListOf<Float>()
        var offset = 0
        while (true) {
            val slot = queue.peek() ?: break
            assertEquals(1_000_000L + offset * 1_000_000L / 44_100L, slot.ptsMicros)
            assertEquals(format, slot.format)
            repeat(slot.frames * 2) { copied += slot.samples[it] }
            offset += slot.frames
            queue.release(slot)
        }
        assertEquals(10, offset)
        assertEquals(borrowed.toList(), copied)
        assertEquals(0, queue.pendingSlots)
        assertEquals(0L, queue.pendingNanos)
    }

    @Test
    fun `time budget is enforced independently of available bytes`() {
        val queue = AudioPcmQueue(capacity = 8, samplesPerSlot = 1024, maxQueuedNanos = 20_000_000L)
        val format = AudioFormat(8_000, 1, SampleFormat.F32)
        val data = FloatArray(80)
        repeat(2) { assertEquals(AudioPcmQueue.Offer.Accepted, queue.offer(Generation.Initial, 0, 0, data, 80, format)) }
        assertEquals(20_000_000L, queue.pendingNanos)
        assertEquals(AudioPcmQueue.Offer.TooMuchTime, queue.offer(Generation.Initial, 0, 0, data, 80, format))
        queue.release(assertNotNull(queue.peek()))
        assertEquals(AudioPcmQueue.Offer.Accepted, queue.offer(Generation.Initial, 0, 0, data, 80, format))
        assertEquals(20_000_000L, queue.pendingNanos)
    }

    @Test
    fun `oversize blocks are rejected whole and invalid sizes cannot overflow`() {
        val queue = AudioPcmQueue(capacity = 2, samplesPerSlot = 8)
        assertEquals(AudioPcmQueue.Offer.Full, queue.offer(Generation.Initial, 0, 0, FloatArray(24), 12, stereo))
        assertNull(queue.peek(), "a rejected block must not leave a partial prefix queued")
        assertEquals(AudioPcmQueue.Offer.Invalid, queue.offer(Generation.Initial, 0, 0, FloatArray(1), Int.MAX_VALUE, stereo))
        assertEquals(AudioPcmQueue.Offer.Invalid, queue.offer(Generation.Initial, 0, 0, FloatArray(1), 1, stereo.copy(channels = 0)))
        assertEquals(AudioPcmQueue.Offer.Invalid, queue.offer(Generation.Initial, 0, 0, FloatArray(2), 1, stereo.copy(sampleRate = 0)))
        assertEquals(64L, queue.reservedBytes)
        assertTrue(queue.pendingNanos <= queue.maxQueuedNanos)
    }
}
