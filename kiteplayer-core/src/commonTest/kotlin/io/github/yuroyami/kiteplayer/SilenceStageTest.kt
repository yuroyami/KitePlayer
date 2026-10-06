package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.OutputRuns
import io.github.yuroyami.kiteplayer.internal.SilenceStage
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Skip silence (#429) on its own: a pause longer than a fifth of a second is cut down to a fifth of
 * a second with no jump at the cut, a shorter one plays whole, nothing is held at the end, and the
 * runs it reports trace every output frame back to the frame of the source it came from.
 */
class SilenceStageTest {

    private val rate = 48_000
    private val channels = 2

    /** A stereo sound of [seconds], a tone at 0.5 where [loud] says so and a hum at 0.02 elsewhere. */
    private fun sound(seconds: Double, loud: (Double) -> Boolean): FloatArray {
        val frames = (seconds * rate).toInt()
        val out = FloatArray(frames * channels)
        for (frame in 0 until frames) {
            val time = frame.toDouble() / rate
            val value = if (loud(time)) 0.5 * sin(2 * PI * 440 * time) else 0.02 * sin(2 * PI * 100 * time)
            out[frame * 2] = value.toFloat()
            out[frame * 2 + 1] = value.toFloat()
        }
        return out
    }

    private class Played(val samples: FloatArray, val sources: DoubleArray)

    /** Feeds [input] in buffers of [block] frames, each a run reading the source on from its first frame. */
    private fun play(stage: SilenceStage, input: FloatArray, block: Int = 1024, end: Boolean = true): Played {
        val frames = input.size / channels
        val samples = ArrayList<Float>()
        val sources = ArrayList<Double>()
        val runs = OutputRuns()
        var at = 0
        while (at < frames) {
            val count = minOf(block, frames - at)
            runs.clear()
            runs.add(0, at.toDouble(), 1.0, 1.0)
            val last = at + count >= frames
            val made = stage.process(input.copyOfRange(at * channels, (at + count) * channels), count, runs, ending = end && last)
            collect(stage, made, samples, sources)
            at += count
        }
        return Played(samples.toFloatArray(), sources.toDoubleArray())
    }

    private fun collect(stage: SilenceStage, made: Int, samples: MutableList<Float>, sources: MutableList<Double>) {
        for (index in 0 until made * channels) samples += stage.output[index]
        for (frame in 0 until made) {
            var run = stage.runs.count - 1
            while (run > 0 && stage.runs.start(run) > frame) run--
            sources += stage.runs.source(run) + (frame - stage.runs.start(run)) * stage.runs.slope(run)
        }
    }

    @Test
    fun aLongPauseIsCutToAFifthOfASecondWithNoJump() {
        val stage = SilenceStage(channels, rate).also { it.set(true) }
        val input = sound(13.0) { it < 5.0 || it >= 8.0 }
        val played = play(stage, input)
        val seconds = played.samples.size / channels / rate.toDouble()
        assertEquals(10.2, seconds, 0.005, "13 s with a 3 s pause played as $seconds s")
        assertEquals(13.0 * rate - 1, played.sources.last(), 1.0, "the last frame out is not the last frame in")
        // Inside the hum, a sample moves by at most the hum's own step plus the fade's.
        val humStep = 0.02 * 2 * PI * 100 / rate
        val fadeStep = 0.02 / (SilenceStage.FADE_SECONDS * rate)
        val cutFrom = (5.0 * rate).toInt() + 100
        val cutTo = (5.2 * rate).toInt() - 100
        var largest = 0f
        for (frame in cutFrom until cutTo) {
            largest = maxOf(largest, abs(played.samples[(frame + 1) * 2] - played.samples[frame * 2]))
        }
        assertTrue(largest <= humStep + fadeStep + 1e-6, "a sample at the cut jumped by $largest")
        // The cut is dated where it is heard: the source jumps once, by the 2.8 s taken out.
        var jumps = 0
        for (frame in 1 until played.sources.size) {
            val step = played.sources[frame] - played.sources[frame - 1]
            if (abs(step - 1.0) > 1e-6) {
                jumps++
                assertEquals(2.8 * rate + 1, step, 0.002 * rate, "the source jumped by $step frames")
            }
        }
        assertEquals(1, jumps, "the source did not jump once at the cut")
    }

    @Test
    fun aShortPausePlaysWholeSampleForSample() {
        val stage = SilenceStage(channels, rate).also { it.set(true) }
        val input = sound(2.0) { it < 0.8 || it >= 0.95 }
        val played = play(stage, input)
        assertContentEquals(input, played.samples)
        played.sources.forEachIndexed { frame, source -> assertEquals(frame.toDouble(), source, 1e-6) }
    }

    @Test
    fun aShortPauseAtTheEndIsNotHeldBack() {
        val stage = SilenceStage(channels, rate).also { it.set(true) }
        val input = sound(1.1) { it < 1.0 }
        assertContentEquals(input, play(stage, input).samples)
    }

    @Test
    fun aLongPauseAtTheEndEndsOnTheLastFrame() {
        val stage = SilenceStage(channels, rate).also { it.set(true) }
        val input = sound(2.0) { it < 1.0 }
        val played = play(stage, input)
        assertEquals(1.2, played.samples.size / channels / rate.toDouble(), 0.005)
        assertEquals(2.0 * rate - 1, played.sources.last(), 1e-6)
    }

    @Test
    fun turnedOffItHandsOnWhatItHeldAndThenStandsAside() {
        val stage = SilenceStage(channels, rate).also { it.set(true) }
        val input = sound(1.0) { it < 0.5 }
        // Half a second of sound and a tenth of a second of quiet, which is held.
        val first = play(stage, input.copyOfRange(0, (0.6 * rate).toInt() * channels), end = false)
        val out = first.samples.size / channels
        assertEquals((0.5 * rate).toInt(), out, 4)
        stage.set(false)
        assertTrue(!stage.isIdentity, "the held pause was dropped")
        val runs = OutputRuns().also { it.add(0, 0.6 * rate, 1.0, 1.0) }
        val rest = input.copyOfRange((0.6 * rate).toInt() * channels, input.size)
        val made = stage.process(rest, rest.size / channels, runs, ending = false)
        assertEquals(rate - out, made, "off, the held pause and the rest came out as $made frames")
        assertContentEquals(input.copyOfRange(out * channels, input.size), stage.output.copyOf(made * channels))
        assertTrue(stage.isIdentity)
    }

    @Test
    fun aResetDropsThePauseItHeld() {
        val stage = SilenceStage(channels, rate).also { it.set(true) }
        play(stage, sound(0.6) { it < 0.5 }, end = false)
        stage.reset()
        val runs = OutputRuns().also { it.add(0, 0.0, 1.0, 1.0) }
        val tone = sound(0.1) { true }
        assertEquals(tone.size / channels, stage.process(tone, tone.size / channels, runs, ending = true))
        assertContentEquals(tone, stage.output.copyOf(tone.size))
    }

    private fun assertEquals(expected: Int, actual: Int, tolerance: Int) =
        assertTrue(abs(expected - actual) <= tolerance, "expected $expected, was $actual")
}
