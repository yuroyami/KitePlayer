package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.Generation
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class AudioEventConcurrencyTest {
    @Test
    fun aWrappingProducerAndTwoReadersAccountForEveryEventWithoutDuplicates() {
        val history = AudioEventHistory(Generation.Initial, 0L, capacity = 16)
        val count = 20_000
        val cursors = Array(2) { AudioEventCursor { EventSources(history) }.also { it.sample(0L) } }
        val start = CountDownLatch(1)
        val completed = CountDownLatch(3)
        val failure = AtomicReference<Throwable?>(null)
        // Where each reader got to, shared so that a report after a timeout can say where it stopped.
        val lastObserved = AtomicLongArray(2)
        val received = AtomicLongArray(2)
        val workers = ArrayList<Thread>()
        fun worker(name: String, body: () -> Unit) = Thread({
            try { start.await(); body() } catch (error: Throwable) { failure.compareAndSet(null, error) }
            finally { completed.countDown() }
        }, name).apply { isDaemon = true; workers += this; start() }
        worker("producer") {
            for (number in 1..count) {
                val time = number.toLong()
                assertTrue(history.publish(AudioDetections(time, time, arrayOf(
                    AudioDetection(AudioEventKind.LowTransient, time, time, 0.3f, 0.8f, 0.2f),
                ))))
                if (number % 7 == 0) Thread.yield()
            }
        }
        for (index in cursors.indices) worker("reader $index") {
            val cursor = cursors[index]
            var lastSequence = -1L
            var observed = 0L
            // A failed worker also ends the loop: a producer that died never reaches count.
            do {
                observed = history.snapshot.completeThroughMicros ?: 0L
                lastObserved.set(index, observed)
                val delivery = cursor.sample(observed)
                for (eventIndex in 0 until delivery.size) {
                    val event = delivery[eventIndex].event
                    assertTrue(event.sequence > lastSequence, "duplicate or out-of-order event")
                    assertEquals(event.sequence + 1, event.detection.ptsMicros)
                    lastSequence = event.sequence
                    received.incrementAndGet(index)
                }
                // Reader 1 always yields, so it falls behind the wrap. Reader 0 reads again at once
                // and yields only when it found nothing new, so it does not hold a core while it waits.
                if (index == 1 || delivery.size == 0) Thread.yield()
            } while (observed < count && failure.get() == null)
            if (failure.get() != null) return@worker
            // A bounded read may defer after racing a wrap. The writer has stopped at this point.
            val final = cursor.sample(count.toLong())
            for (eventIndex in 0 until final.size) {
                val event = final[eventIndex].event
                assertTrue(event.sequence > lastSequence)
                lastSequence = event.sequence
                received.incrementAndGet(index)
            }
        }
        start.countDown()
        val finished = completed.await(30, TimeUnit.SECONDS)
        // A worker's own error comes first, so it is reported as itself even while another worker runs.
        failure.get()?.let { throw it }
        if (!finished) fail(buildString {
            append("workers did not finish in 30 seconds. The producer published ")
            append(history.snapshot.completeThroughMicros ?: 0L).append(" of ").append(count).append('.')
            for (index in cursors.indices) {
                append(" Reader ").append(index).append(" last read at ").append(lastObserved[index])
                append(" and received ").append(received[index]).append('.')
            }
            for (thread in workers) {
                append('\n').append(thread.name).append(" is ").append(thread.state)
                thread.stackTrace.forEach { append("\n    at ").append(it) }
            }
        })
        for (index in cursors.indices) {
            assertEquals(count.toLong(), received[index] + cursors[index].lateDiscards + cursors[index].catchUpDiscards,
                "each event must be delivered once or explicitly counted as discarded")
        }
    }
}
