package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.Generation
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration

class AudioEventIntegrationTest {
    init { useSkiaGraphics() }

    private fun frame(at: Long, complete: Long = at, events: Array<AudioDetection> = emptyArray(),
        generation: Generation = Generation.Initial, revision: Long = 0L): SpectrumFrame = SpectrumFrame(
        at, FloatArray(4), FloatArray(4), FloatArray(4), 0.5f, 0f, 0f, 0f, 0f, 0f,
        generation = generation, analysisRevision = revision,
        detections = AudioDetections(at + 30_000L, complete, events),
    )

    private fun hit(at: Long) = AudioDetection(AudioEventKind.LowTransient, at, at + 20_000L, 0.3f, 0.8f, 0.2f)

    @Test
    fun aFrameCannotJoinEventsFromADifferentGenerationOrLocalRevision() {
        val measured = frame(10_000L, generation = Generation(1), revision = 2L)
        for ((generation, revision) in listOf(Generation(2) to 2L, Generation(1) to 3L)) {
            val history = AudioEventHistory(generation, revision)
            val cursor = AudioEventCursor { history }
            cursor.sample(0L)
            history.publish(AudioDetections(30_000L, 10_000L, arrayOf(hit(5_000L))))
            val delivery = cursor.sample(10_000L)
            assertEquals(1, delivery.size, "the fixture must contain an event from the other history")
            val combined = measured.withDeliveredEvents(delivery)
            assertFalse(combined.hasTimestamp, "a reset between feature sampling and event sampling must invalidate the join")
            assertEquals(0f, combined.kick)
        }
    }

    @Test
    fun aLongHostSuspensionDiscardsPastBurstsEvenAtSlowPlaybackRates() {
        val worker = ManualVizDispatcher()
        val feed = AudioVizFeed(worker)
        var at = 0L
        try {
            val view = AudioVizState(feed) { VizClockReading(at, 0.5) }
            view.displayDelay = Duration.ZERO
            feed.timeline.push(frame(0L))
            view.nextFrame(0L)
            feed.timeline.push(frame(100_000L, events = arrayOf(hit(50_000L), hit(100_000L))))
            feed.timeline.push(frame(200_000L, events = arrayOf(hit(200_000L))))
            at = 125_000L
            assertEquals(0, assertNotNull(view.nextFrame(1_000_000_000L).events).size)
            assertEquals(2L, view.catchUpEventDiscards)
            at = 210_000L
            assertEquals(1, assertNotNull(view.nextFrame(1_016_666_667L).events).size)
        } finally {
            feed.close()
            worker.runAll()
        }
    }

    @Test
    fun theSharedGainChangesEventEnergyWithoutChangingDetectorConfidence() {
        fun run(reference: Double): List<AudioDetection> {
            val analyzer = SpectrumAnalyzer(sampleRate = 8_000)
            analyzer.setSongReferencePower(reference)
            val events = mutableListOf<AudioDetection>()
            analyzer.onAnalysis = { reading ->
                reading.detections?.let { batch -> for (index in 0 until batch.size) {
                    val item = batch[index]
                    if (item.kind == AudioEventKind.Onset && item.ptsMicros > 2_000_000L) events.add(item)
                } }
            }
            val audio = FloatArray(8_000 * 4) { index ->
                val t = index / 8_000.0
                (0.05 * exp(-(t % 0.5) / 0.04) * sin(2 * PI * 70 * t)).toFloat()
            }
            analyzer.feed(audio, audio.size, 1, 0L)
            return events
        }
        val normal = run(0.01)
        val lower = run(1.0)
        assertTrue(normal.isNotEmpty())
        assertEquals(normal.size, lower.size)
        for (index in normal.indices) {
            assertEquals(normal[index].ptsMicros, lower[index].ptsMicros)
            assertEquals(normal[index].confidence, lower[index].confidence)
            assertEquals(normal[index].surprise, lower[index].surprise)
            assertTrue(lower[index].strength < normal[index].strength * 0.6f)
        }
    }

    @Test
    fun acceptedFramesPopulateAnIndependentEventHistoryAndResetRetiresItsPublisher() {
        val timeline = SpectrumTimeline(4)
        val cursor = timeline.eventCursor()
        cursor.sample(0L)
        val publisher = timeline.publisher()
        assertTrue(publisher.push(frame(20_000L, events = arrayOf(hit(5_000L), hit(15_000L)))))
        val delivery = cursor.sample(20_000L)
        assertEquals(2, delivery.size)
        assertEquals(2, timeline.eventStats.retainedEvents)
        assertEquals(128L, timeline.eventStats.retainedPayloadBytes)
        timeline.reset(Generation(1))
        assertFalse(publisher.push(frame(30_000L, events = arrayOf(hit(30_000L)))))
        assertEquals(0, timeline.eventStats.retainedEvents)
        assertEquals(0, cursor.sample(30_000L).size)
        assertNull(timeline.eventStats.completeThroughMicros)
    }

    @Test
    fun thePlayerViewDeliversDoubleHitsAndLateConfirmationsOnce() {
        val worker = ManualVizDispatcher()
        val feed = AudioVizFeed(worker)
        var at = 0L
        try {
            val view = AudioVizState(feed) { VizClockReading(at) }
            view.displayDelay = Duration.ZERO
            feed.timeline.push(frame(0L))
            view.nextFrame()
            feed.timeline.push(frame(10_000L, complete = 5_000L))
            at = 10_000L
            assertEquals(0, assertNotNull(view.nextFrame().events).size)
            feed.timeline.push(frame(30_000L, complete = 20_000L,
                events = arrayOf(hit(8_000L), hit(15_000L), hit(20_000L))))
            at = 25_000L
            val received = view.nextFrame()
            val delivery = assertNotNull(received.events)
            assertEquals(3, delivery.size)
            assertEquals(17_000L, delivery[0].lateByMicros)
            assertEquals(0.3f, received.kick)
            assertEquals(0, assertNotNull(view.nextFrame().events).size)
            assertEquals(0f, view.nextFrame().kick)
        } finally {
            feed.close()
            worker.runAll()
        }
    }

    @Test
    fun analyserPublishesEnergyStrengthAndHonestAvailabilityOnlyForCompleteKnownWindows() {
        val analyzer = SpectrumAnalyzer(sampleRate = 8_000)
        analyzer.setSongReferencePower(0.01)
        analyzer.feed(FloatArray(analyzer.hop), analyzer.hop, 1, -1_000_000L)
        assertNull(analyzer.latest.detections)
        val publications = mutableListOf<SpectrumFrame>()
        analyzer.onAnalysis = { publications.add(it) }
        val audio = FloatArray(8_000 * 3) { index ->
            val t = index / 8_000.0
            val since = t % 0.5
            (0.05 * exp(-since / 0.04) * sin(2 * PI * 70 * t)).toFloat()
        }
        analyzer.feed(audio, audio.size, 1)
        val ready = publications.filter { it.detections != null }
        assertTrue(ready.isNotEmpty())
        var hits = 0
        for (reading in ready) {
            val batch = assertNotNull(reading.detections)
            val eventReference = batch.availableThroughMicros - analyzer.hop * 500_000L / 8_000
            assertEquals(eventReference, batch.completeThroughMicros)
            assertEquals(assertNotNull(reading.power).window.endMicros, batch.availableThroughMicros)
            for (index in 0 until batch.size) {
                val event = batch[index]
                assertEquals(eventReference, event.ptsMicros)
                assertTrue(event.ptsMicros > reading.ptsMicros)
                assertEquals(batch.availableThroughMicros, event.availableMicros)
                assertTrue(event.strength in 0f..1f)
                if (event.kind == AudioEventKind.Onset) {
                    val power = assertNotNull(reading.power).totalMeanSquare
                    assertTrue(kotlin.math.abs(event.strength - powerHeight(power * assertNotNull(reading.drivers).powerGain)) < 1e-6)
                    hits++
                }
            }
        }
        assertTrue(hits > 0, "fixture must exercise actual detected events")
        analyzer.reset()
        analyzer.feed(audio, audio.size, 1)
        assertNull(analyzer.latest.detections, "unknown media time cannot enter a player event history")
    }
}
