package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.audioviz.viz.asFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration

class EventLookaheadTest {
    init { useSkiaGraphics() }

    private fun frame(at: Long, through: Long, vararg events: AudioDetection): SpectrumFrame = SpectrumFrame(
        at, FloatArray(4), FloatArray(4), FloatArray(4), 0.5f, 0f, 0f, 0f, 0f, 0f,
        detections = AudioDetections(through, through, arrayOf(*events)),
    )

    private fun hit(kind: AudioEventKind, at: Long, strength: Float) =
        AudioDetection(kind, at, at + 10_000L, strength, 0.8f, 0.2f)

    @Test
    fun eventLookaheadPreservesKindIdentityAndStrengthAcrossFeatureTimes() {
        val timeline = SpectrumTimeline(4)
        timeline.push(frame(0L, 0L))
        timeline.push(frame(20_000L, 100_000L,
            hit(AudioEventKind.BodyTransient, 30_000L, 0.2f),
            hit(AudioEventKind.LowTransient, 50_000L, 0.35f),
            hit(AudioEventKind.Onset, 80_000L, 0.7f)))
        val next = assertNotNull(timeline.nextEvent(20_000L, AudioEventKind.LowTransient))
        assertEquals(1L, next.sequence)
        assertEquals(50_000L, next.detection.ptsMicros)
        assertEquals(60_000L, next.detection.availableMicros)
        assertEquals(0.35f, next.detection.strength)
        assertEquals(AudioEventKind.LowTransient, next.detection.kind)
        assertNull(timeline.nextEvent(50_000L, AudioEventKind.LowTransient), "the interval is strictly future")
        assertNull(timeline.nextEvent(0L, AudioEventKind.HighTransient))

        val future = timeline.asFuture { 20_000L }
        val upcoming = assertNotNull(future.nextEvent(AudioEventKind.LowTransient))
        assertEquals(next, upcoming.event)
        assertEquals(0.03f, upcoming.secondsUntil, 1e-6f)
        assertEquals(0.06f, future.nextOnsetSeconds, 1e-6f)
        timeline.clear()
        assertNull(future.nextEvent(AudioEventKind.LowTransient))
    }

    @Test
    fun playerEventLookaheadScalesByPlaybackRateAndRejectsPauseResetAndDistantEvents() {
        val worker = ManualVizDispatcher()
        val feed = AudioVizFeed(worker)
        var rate = 2.0
        try {
            val view = AudioVizState(feed) { VizClockReading(50_000L, rate) }
            view.displayDelay = Duration.ZERO
            feed.timeline.push(frame(0L, 0L))
            feed.timeline.push(frame(100_000L, 400_000L,
                hit(AudioEventKind.LowTransient, 150_000L, 0.4f),
                hit(AudioEventKind.HighTransient, 300_000L, 0.2f)))
            view.nextFrame()
            assertEquals(0.05f, assertNotNull(view.future.nextEvent(AudioEventKind.LowTransient)).secondsUntil, 1e-6f)
            assertNull(view.future.nextEvent(AudioEventKind.HighTransient), "presentation lookahead is capped at 100 ms")
            rate = 0.5
            view.nextFrame()
            assertNull(view.future.nextEvent(AudioEventKind.LowTransient), "100 ms of media is 200 ms at half speed")
            rate = 0.0
            view.nextFrame()
            assertNull(view.future.nextEvent(AudioEventKind.LowTransient))
            rate = 2.0
            view.nextFrame()
            feed.timeline.reset(Generation(1))
            assertNull(view.future.nextEvent(AudioEventKind.LowTransient), "the sampled view may not expose a retired generation")
        } finally {
            feed.close()
            worker.runAll()
        }
    }
}
