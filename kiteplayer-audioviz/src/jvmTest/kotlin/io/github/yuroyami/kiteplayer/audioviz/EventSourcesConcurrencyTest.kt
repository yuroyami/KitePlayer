package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.Generation
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertTrue

/** Structural confirmations and resets race readers without mixing identities or repeating events. */
class EventSourcesConcurrencyTest {
    @Test
    fun lateConfirmationsAndResetsNeverMixIdentitiesOrRepeatEvents() {
        val timeline = SpectrumTimeline(256)
        val stop = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>(null)
        val start = CountDownLatch(1)
        val completed = CountDownLatch(4)
        val newest = java.util.concurrent.atomic.AtomicLong(0L)
        fun worker(body: () -> Unit) = Thread {
            try { start.await(); body() } catch (error: Throwable) { failure.compareAndSet(null, error) }
            finally { completed.countDown() }
        }.apply { isDaemon = true; start() }

        worker {
            var generation = timeline.generation
            var publisher = timeline.publisher()
            var at = 0L
            var structureThrough: Long? = null
            repeat(40_000) { step ->
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
                if (step % 13 == 0) Thread.yield()
            }
            stop.set(true)
        }
        worker {
            var generation = Generation.Initial
            while (!stop.get()) {
                Thread.sleep(3)
                generation = generation.next()
                timeline.reset(generation)
            }
        }
        repeat(2) {
            worker {
                val cursor = timeline.eventCursor()
                val seen = HashSet<String>()
                while (!stop.get()) {
                    val delivery = cursor.sample(newest.get())
                    var lastTime = Long.MIN_VALUE
                    for (index in 0 until delivery.size) {
                        val event = delivery[index].event
                        check(event.generation == delivery.generation && event.analysisRevision == delivery.analysisRevision) {
                            "an event crossed histories"
                        }
                        check(event.detection.ptsMicros >= lastTime) { "a delivery was out of time order" }
                        lastTime = event.detection.ptsMicros
                        val key = "${event.source}/${event.generation.value}/${event.analysisRevision}/${event.sequence}"
                        check(seen.add(key)) { "event $key was delivered twice" }
                    }
                }
            }
        }
        start.countDown()
        assertTrue(completed.await(60, TimeUnit.SECONDS), "workers did not finish")
        failure.get()?.let { throw it }
    }
}
