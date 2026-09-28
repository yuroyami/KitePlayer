package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.CalmReading
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * What the reading a drawing sees keeps of the music while reduced motion is on.
 *
 * Only the smooth values may be slowed down. The trace, the hits, the delivered events and the
 * pause must stay the newest frame's own, or a drawing shows a frozen picture.
 */
class CalmReadingTest {

    private val step = 1f / 60f
    private val calm = 0.15f

    private fun heard(count: Int): List<SpectrumFrame> {
        val player = SongPlayer(SyntheticSong.drumLoop(8f))
        return List(count) { player.next(step) }
    }

    @Test
    fun theTraceTheHitsAndTheEventsAreTheNewestFramesOwn() {
        val reading = CalmReading()
        var withEvents = 0
        for (frame in heard(240)) {
            val shown = reading.of(frame, calm, step)
            assertSame(frame.scope, shown.scope)
            assertSame(frame.power, shown.power)
            assertSame(frame.events, shown.events)
            assertEquals(frame.kick, shown.kick)
            assertEquals(frame.snare, shown.snare)
            assertEquals(frame.hat, shown.hat)
            if (frame.events != null && frame.hasTimestamp) withEvents++
        }
        assertTrue(withEvents > 0, "the fixture must deliver events after its warm-up")
    }

    @Test
    fun aPauseIsStillSeen() {
        val reading = CalmReading()
        val frames = heard(120)
        for (frame in frames) reading.of(frame, calm, step)
        val paused = frames.last().withPulseHeld()
        assertTrue(paused.held)
        val shown = reading.of(paused, calm, step)
        assertTrue(shown.held, "a paused player must stay paused for the drawing")
        assertEquals(0f, shown.audible)
    }

    @Test
    fun theSmoothValuesStillGlide() {
        val reading = CalmReading()
        var previous: SpectrumFrame? = null
        var glided = 0
        for (frame in heard(300)) {
            val shown = reading.of(frame, calm, step)
            val before = previous
            if (before != null && before.hasTimestamp && frame.hasTimestamp && kotlin.math.abs(frame.level - before.level) > 0.02f) {
                val low = minOf(before.level, frame.level)
                val high = maxOf(before.level, frame.level)
                assertTrue(shown.level != frame.level && shown.level in low..high,
                    "the level should move part of the way: ${before.level} to ${frame.level}, shown ${shown.level}")
                glided++
            }
            previous = shown
        }
        assertTrue(glided > 0, "the fixture never changed level enough to test the glide")
    }

    @Test
    fun aRedrawOfTheSameStepGetsTheSameFrame() {
        val reading = CalmReading()
        val frames = heard(120)
        for (frame in frames.dropLast(1)) reading.of(frame, calm, step)
        val first = reading.of(frames.last(), calm, step)
        assertSame(first, reading.of(frames.last(), calm, 0f))
    }

    @Test
    fun fullMotionPassesTheFrameThroughUntouched() {
        val reading = CalmReading()
        for (frame in heard(60)) assertSame(frame, reading.of(frame, 1f, step))
    }
}
