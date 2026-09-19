package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.SampleFormat
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AudioOwnershipConcurrencyTest {
    @Test
    fun `concurrent producer and consumer preserve owned samples through ring wrap`() {
        val queue = AudioPcmQueue(capacity = 4, samplesPerSlot = 256)
        val format = AudioFormat(48_000, 2, SampleFormat.F32)
        val threads = Executors.newFixedThreadPool(2)
        try {
            val producer = threads.submit {
                val borrowed = FloatArray(256)
                repeat(10_000) { sequence ->
                    borrowed.fill(sequence.toFloat())
                    while (queue.offer(Generation.Initial, 0, sequence.toLong(), borrowed, 128, format) != AudioPcmQueue.Offer.Accepted) {
                        check(!Thread.currentThread().isInterrupted)
                        Thread.yield()
                    }
                    borrowed.fill(-1f)
                }
            }
            val consumer = threads.submit {
                repeat(10_000) { sequence ->
                    var slot = queue.peek()
                    while (slot == null) {
                        check(!Thread.currentThread().isInterrupted)
                        Thread.yield()
                        slot = queue.peek()
                    }
                    val held = assertNotNull(slot)
                    assertEquals(sequence.toLong(), held.ptsMicros)
                    Thread.yield()
                    assertTrue(held.samples.all { it == sequence.toFloat() }, "slot changed while held at $sequence")
                    queue.release(held)
                }
            }
            producer.get(15, TimeUnit.SECONDS)
            consumer.get(15, TimeUnit.SECONDS)
            assertEquals(0, queue.pendingSlots)
            assertEquals(0L, queue.pendingNanos)
        } finally {
            threads.shutdownNow()
            threads.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `simultaneous views retain one worker and last release closes it`() = runBlocking {
        val attached = AtomicInteger()
        val detached = AtomicInteger()
        val sessions = AudioVizSessions<Any>({ _, _ -> attached.incrementAndGet() }, { _, _ -> detached.incrementAndGet() })
        val player = Any()
        val owner = sessions.acquire(player)
        val threads = Executors.newFixedThreadPool(4)
        try {
            val readers = List(4) {
                threads.submit {
                    repeat(500) {
                        val lease = sessions.acquire(player)
                        assertSame(owner.feed, lease.feed)
                        lease.close()
                        lease.close()
                    }
                }
            }
            readers.forEach { it.get(10, TimeUnit.SECONDS) }
            assertFalse(owner.feed.isClosed)
            assertEquals(1, attached.get())
            assertEquals(0, detached.get())
        } finally {
            threads.shutdownNow()
            threads.awaitTermination(5, TimeUnit.SECONDS)
            owner.close()
            owner.feed.awaitClosed()
        }
        assertTrue(owner.feed.isClosed)
        assertEquals(1, detached.get())
    }

    @Test
    fun `resets and close prevent a running worker from publishing retired audio`() = runBlocking {
        val feed = AudioVizFeed()
        val format = AudioFormat(44_100, 2, SampleFormat.F32)
        val samples = FloatArray(8192) { 0.3f }
        try {
            feed.onAudio(Pts.Zero, samples, 4096, format)
            withTimeout(5_000) { while (feed.timeline.newest() == null) delay(1) }
            val retained = assertNotNull(feed.timeline.newest())
            val original = retained.bands.copyOf()
            samples.fill(0.8f)
            repeat(100) { index ->
                val generation = Generation(index + 1L)
                feed.onDiscontinuity(generation)
                assertNull(feed.timeline.newest(), "a retiring analysis survived the reset")
                feed.onAudio(generation, Pts(index * 100_000L), samples, 4096, format)
            }
            withTimeout(5_000) { while (feed.stats.queuedPcmNanos != 0L) delay(1) }
            feed.onAudio(Generation(100), Pts(10_000_000L), samples, 4096, format)
            withTimeout(5_000) {
                while (feed.timeline.newest()?.ptsMicros?.let { it >= 10_000_000L } != true) delay(1)
            }
            assertEquals(Generation(100), assertNotNull(feed.timeline.newest()).generation)
            assertEquals(original.toList(), retained.bands.toList(), "publication reused a reader's sample array")
        } finally {
            feed.close()
            feed.awaitClosed()
        }
        assertNull(feed.timeline.newest())
        assertEquals(0L, feed.stats.queuedPcmNanos)
    }
}
