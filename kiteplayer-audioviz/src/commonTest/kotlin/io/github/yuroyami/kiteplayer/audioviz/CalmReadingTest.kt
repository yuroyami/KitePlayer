package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.CalmReading
import kotlin.test.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
    fun theWaveformTheHitsAndTheEventsAreTheNewestFramesOwn() {
        val reading = CalmReading()
        var withEvents = 0
        for (frame in heard(240)) {
            val shown = reading.of(frame, calm, step)
            assertSame(frame.scope, shown.scope)
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
    fun thePowerSpectrumGlidesToo() {
        val reading = CalmReading()
        var previous: SpectrumFrame? = null
        var glided = 0
        for (frame in heard(300)) {
            val shown = reading.of(frame, calm, step)
            val before = previous
            val newest = frame.power
            val old = before?.power
            if (old != null && newest != null && before.hasTimestamp && frame.hasTimestamp &&
                abs(newest.totalMeanSquare - old.totalMeanSquare) > 1e-5f
            ) {
                val low = minOf(old.totalMeanSquare, newest.totalMeanSquare)
                val high = maxOf(old.totalMeanSquare, newest.totalMeanSquare)
                val power = assertNotNull(shown.power)
                assertTrue(power.totalMeanSquare in low..high && power.totalMeanSquare != newest.totalMeanSquare,
                    "the power should move part of the way: ${old.totalMeanSquare} to ${newest.totalMeanSquare}, shown ${power.totalMeanSquare}")
                assertEquals(newest.window.referenceMicros, power.window.referenceMicros, "the window stays the newest one")
                assertEquals(newest.binCount, power.binCount)
                glided++
            }
            previous = shown
        }
        assertTrue(glided > 0, "the fixture never changed power enough to test the glide")
    }

    @Test
    fun aFrameThatRepeatsOnNewStepsStillGlidesToItsValues() {
        val reading = CalmReading()
        val frames = heard(300).filter { it.hasTimestamp }
        val loud = frames.maxBy { it.level }
        val quiet = frames.minBy { it.level }
        val gap = loud.level - quiet.level
        println("calm reading fixture: loud ${loud.level}, quiet ${quiet.level}")
        assertTrue(gap > 0.03f, "the fixture needs a loud and a quiet frame, gap $gap")
        // The analysis has ended on the quiet frame, so every step gets that one object again.
        reading.of(loud, calm, step)
        val first = reading.of(quiet, calm, step)
        assertTrue(first.level > quiet.level + gap * 0.5f, "one step should only start the glide: ${first.level}")
        var shown = first
        repeat(120) { shown = reading.of(quiet, calm, step) }
        assertTrue(abs(shown.level - quiet.level) < gap * 0.1f,
            "two seconds of the same frame should reach its level ${quiet.level}, was ${shown.level}")
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
