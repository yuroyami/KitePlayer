package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.Generation
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.fail

/** Structural confirmations and resets race readers without mixing identities or repeating events. */
class EventSourcesConcurrencyTest {
    @Test
    fun lateConfirmationsAndResetsNeverMixIdentitiesOrRepeatEvents() {
        val timeline = SpectrumTimeline(256)
        val steps = 40_000
        val stop = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>(null)
        val start = CountDownLatch(1)
        val completed = CountDownLatch(4)
        val newest = AtomicLong(0L)
        // Progress of each worker, shared so that a report after a timeout can say where each one stopped.
        val pushed = AtomicLong(0L)
        val resets = AtomicLong(0L)
        val lastRead = AtomicLongArray(2)
        val received = AtomicLongArray(2)
        val workers = ArrayList<Thread>()
        fun running() = !stop.get() && failure.get() == null
        fun worker(name: String, body: () -> Unit) = Thread({
            try { start.await(); body() } catch (error: Throwable) { failure.compareAndSet(null, error) }
            finally { completed.countDown() }
        }, name).apply { isDaemon = true; workers += this; start() }

        worker("producer") {
            var generation = timeline.generation
            var publisher = timeline.publisher()
            var at = 0L
            var structureThrough: Long? = null
            for (step in 0 until steps) {
                // A failed reader ends the run; the producer has nothing left to prove.
                if (failure.get() != null) return@worker
                if (timeline.revision != publisher.revision || timeline.generation != generation) {
                    generation = timeline.generation
                    publisher = timeline.publisher()
                    structureThrough = null
                }
                at += 10_000L
                val onsets = if (step % 5 == 0) arrayOf(AudioDetection(AudioEventKind.Onset, at, at, 0.5f, 0.8f, 0.2f)) else emptyArray()
                // Every half second, confirm a boundary one second back.
                val confirm = step % 50 == 0 && at > 1_000_000L && (structureThrough ?: Long.MIN_VALUE) < at - 1_000_000L
                val structural = if (confirm) {
                    structureThrough = at - 1_000_000L
                    arrayOf(AudioDetection(AudioEventKind.SectionBoundary, at - 1_000_000L, at, 0.5f, 0.9f, 0.5f))
                } else emptyArray()
                val frame = SpectrumFrame(at, FloatArray(4), FloatArray(4), FloatArray(4), 0f, 0f, 0f, 0f, 0f, 0f,
                    generation = generation, analysisRevision = publisher.revision,
                    detections = AudioDetections(at, at, onsets),
                    structure = structureThrough?.let { AudioDetections(at, it, structural, AudioEventSource.LiveStructure) })
                publisher.push(frame)
                newest.set(at)
                pushed.set(step + 1L)
                if (step % 13 == 0) Thread.yield()
            }
            stop.set(true)
        }
        worker("resetter") {
            var generation = Generation.Initial
            // A failed producer never sets stop, so every loop here also ends on a failure.
            while (running()) {
                Thread.sleep(3)
                generation = generation.next()
                timeline.reset(generation)
                resets.incrementAndGet()
            }
        }
        for (index in 0 until 2) worker("reader $index") {
            val cursor = timeline.eventCursor()
            val seen = HashSet<String>()
            while (running()) {
                val observed = newest.get()
                lastRead.set(index, observed)
                val delivery = cursor.sample(observed)
                var lastTime = Long.MIN_VALUE
                for (eventIndex in 0 until delivery.size) {
                    val event = delivery[eventIndex].event
                    check(event.generation == delivery.generation && event.analysisRevision == delivery.analysisRevision) {
                        "an event crossed histories"
                    }
                    check(event.detection.ptsMicros >= lastTime) { "a delivery was out of time order" }
                    lastTime = event.detection.ptsMicros
                    val key = "${event.source}/${event.generation.value}/${event.analysisRevision}/${event.sequence}"
                    check(seen.add(key)) { "event $key was delivered twice" }
                    received.incrementAndGet(index)
                }
                // A reader that found nothing new gives its core back rather than asking again at once.
                if (delivery.size == 0) Thread.yield()
            }
        }
        start.countDown()
        val finished = completed.await(60, TimeUnit.SECONDS)
        // A worker's own error comes first, so it is reported as itself even while another worker runs.
        failure.get()?.let { throw it }
        if (!finished) fail(buildString {
            append("workers did not finish in 60 seconds. The producer pushed ").append(pushed.get())
            append(" of ").append(steps).append(" frames, up to ").append(newest.get()).append(" us.")
            append(" The resetter reset ").append(resets.get()).append(" times.")
            for (index in 0 until 2) {
                append(" Reader ").append(index).append(" last read at ").append(lastRead[index])
                append(" us and received ").append(received[index]).append(" events.")
            }
            for (thread in workers) {
                append('\n').append(thread.name).append(" is ").append(thread.state)
                thread.stackTrace.forEach { append("\n    at ").append(it) }
            }
        })
    }
}
