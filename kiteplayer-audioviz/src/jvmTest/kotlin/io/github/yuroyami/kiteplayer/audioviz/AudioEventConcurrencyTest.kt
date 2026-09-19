package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.Generation
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AudioEventConcurrencyTest {
    @Test
    fun aWrappingProducerAndTwoReadersAccountForEveryEventWithoutDuplicates() {
        val history = AudioEventHistory(Generation.Initial, 0L, capacity = 16)
        val count = 20_000
        val cursors = Array(2) { AudioEventCursor { EventSources(history) }.also { it.sample(0L) } }
        val start = CountDownLatch(1)
        val completed = CountDownLatch(3)
        val failure = AtomicReference<Throwable?>(null)
        val received = LongArray(2)
        fun worker(body: () -> Unit) = Thread {
            try { start.await(); body() } catch (error: Throwable) { failure.compareAndSet(null, error) }
            finally { completed.countDown() }
        }.apply { isDaemon = true; start() }
        worker {
            for (number in 1..count) {
                val time = number.toLong()
                assertTrue(history.publish(AudioDetections(time, time, arrayOf(
                    AudioDetection(AudioEventKind.LowTransient, time, time, 0.3f, 0.8f, 0.2f),
                ))))
                if (number % 7 == 0) Thread.yield()
            }
        }
        for (index in cursors.indices) worker {
            val cursor = cursors[index]
            var lastSequence = -1L
            var observed = 0L
            do {
                observed = history.snapshot.completeThroughMicros ?: 0L
                val delivery = cursor.sample(observed)
                for (eventIndex in 0 until delivery.size) {
                    val event = delivery[eventIndex].event
                    assertTrue(event.sequence > lastSequence, "duplicate or out-of-order event")
                    assertEquals(event.sequence + 1, event.detection.ptsMicros)
                    lastSequence = event.sequence
                    received[index]++
                }
                if (index == 1) Thread.yield()
            } while (observed < count)
            // A bounded read may defer after racing a wrap. The writer has stopped at this point.
            val final = cursor.sample(count.toLong())
            for (eventIndex in 0 until final.size) {
                val event = final[eventIndex].event
                assertTrue(event.sequence > lastSequence)
                lastSequence = event.sequence
                received[index]++
            }
        }
        start.countDown()
        assertTrue(completed.await(30, TimeUnit.SECONDS), "workers did not finish")
        failure.get()?.let { throw it }
        for (index in cursors.indices) {
            assertEquals(count.toLong(), received[index] + cursors[index].lateDiscards + cursors[index].catchUpDiscards,
                "each event must be delivered once or explicitly counted as discarded")
        }
    }
}
