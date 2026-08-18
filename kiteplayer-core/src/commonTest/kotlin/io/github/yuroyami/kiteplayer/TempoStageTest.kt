package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.TempoStage
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The tempo stage's three promises, each tested where it can lie:
 *
 * - the RATIO promise: emitted output times speed equals consumed input, within the documented
 *   bound, at every supported speed and under adversarial chunk sizes,
 * - the PITCH promise: speeding up does not raise the pitch, which is the entire difference
 *   between a tempo stage and a resampler,
 * - the BYPASS promise: at 1.0 the stage is not merely inaudible but bit-exact and free.
 */
class TempoStageTest {

    private val rate = 48_000
    private val channels = 2

    /** Interleaved stereo sine at [frequency], [seconds] long. */
    private fun sine(frequency: Double, seconds: Double): FloatArray {
        val frames = (seconds * rate).toInt()
        return FloatArray(frames * channels) { index ->
            val frame = index / channels
            sin(2.0 * PI * frequency * frame / rate).toFloat()
        }
    }

    private fun drive(stage: TempoStage, input: FloatArray, chunkFrames: Int, collect: (FloatArray, Int) -> Unit = { _, _ -> }) {
        var offset = 0
        val totalFrames = input.size / channels
        val chunk = FloatArray(chunkFrames * channels)
        while (offset < totalFrames) {
            val frames = minOf(chunkFrames, totalFrames - offset)
            input.copyInto(chunk, 0, offset * channels, (offset + frames) * channels)
            val produced = stage.process(chunk, frames)
            if (produced > 0) collect(stage.output, produced)
            offset += frames
        }
    }

    /**
     * P0-20. The stage needs two periods of lookahead before it may splice, so at the end of a
     * stream whatever is short of that has nothing to trigger it. `reset` used to be the only exit
     * and it dropped them, which is the end of a clip going missing. `finish` is the exit that
     * keeps them.
     */
    @Test
    fun `finish emits the lookahead the end of a stream leaves stranded`() {
        for (speed in listOf(0.5, 1.25, 2.0, 4.0)) {
            val stage = TempoStage(channels, rate)
            stage.speed = speed
            // A tenth of a second, fed whole, so the stage is left mid-lookahead exactly as the
            // last buffer of a real stream leaves it.
            val input = sine(440.0, 0.1)
            val inputFrames = input.size / channels
            stage.process(input, inputFrames)
            val emittedBeforeFinish = stage.emittedFrames

            val tail = stage.finish()
            assertTrue(tail > 0, "at $speed the stage was holding audio that finish did not emit")
            assertEquals(
                emittedBeforeFinish + tail,
                stage.emittedFrames,
                "the tail must count as emitted at $speed, or the pts law dates the next buffer wrong",
            )
            assertEquals(
                inputFrames.toLong(),
                stage.consumedFrames,
                "after finish every input frame must be accounted for at $speed",
            )
            assertEquals(0, stage.finish(), "a second finish has nothing left to give at $speed")
            assertTrue(!stage.hasQueuedInput, "finish must leave the queue empty at $speed")
        }
    }

    @Test
    fun `the ratio holds at every supported speed under adversarial chunking`() {
        val random = Random(7)
        for (speed in listOf(0.25, 0.5, 0.8, 1.25, 1.5, 2.0, 3.0, 4.0)) {
            val stage = TempoStage(channels, rate)
            stage.speed = speed
            val input = sine(440.0, 5.0)
            var offset = 0
            val totalFrames = input.size / channels
            while (offset < totalFrames) {
                // Chunks from one frame to a fifth of a second, so no convenient buffer size
                // can hide a bookkeeping error.
                val frames = minOf(1 + random.nextInt(9_600), totalFrames - offset)
                val chunk = FloatArray(frames * channels)
                input.copyInto(chunk, 0, offset * channels, (offset + frames) * channels)
                stage.process(chunk, frames)
                offset += frames
            }
            val consumed = stage.consumedFrames.toDouble()
            val emitted = stage.emittedFrames.toDouble()
            assertTrue(consumed > 4.0 * rate, "the stage must have retired most of five seconds")
            val achieved = consumed / emitted
            assertTrue(
                abs(achieved - speed) / speed < 0.02,
                "at $speed the achieved ratio was $achieved from $consumed consumed, $emitted emitted",
            )
        }
    }

    @Test
    fun `doubling the tempo does not double the pitch`() {
        val stage = TempoStage(channels, rate)
        stage.speed = 2.0
        val produced = ArrayList<Float>()
        drive(stage, sine(440.0, 4.0), chunkFrames = 1_024) { output, frames ->
            for (frame in 0 until frames) produced.add(output[frame * channels])
        }
        assertTrue(produced.size > rate, "two seconds in, one second out, give or take lookahead")

        // Count rising zero crossings per second of OUTPUT. A tempo stage keeps them at the
        // input's 440; a resampler masquerading as one would show 880. The window skips the
        // first half second, where splices from the initial transient are densest.
        val start = rate / 2
        val end = start + rate
        var crossings = 0
        for (index in start until minOf(end, produced.size - 1)) {
            if (produced[index] <= 0f && produced[index + 1] > 0f) crossings++
        }
        assertTrue(
            crossings in 396..484,
            "expected about 440 rising crossings per output second at the source pitch, counted $crossings",
        )
    }

    @Test
    fun `unity speed is bit-exact and counts as a pass-through`() {
        val stage = TempoStage(channels, rate)
        val input = sine(313.0, 0.25)
        val frames = input.size / channels
        val produced = stage.process(input, frames)
        assertEquals(frames, produced)
        assertContentEquals(input, stage.output.copyOf(input.size))
        assertEquals(frames.toLong(), stage.consumedFrames)
        assertEquals(frames.toLong(), stage.emittedFrames)
    }

    @Test
    fun `reset drops the queue and the counters`() {
        val stage = TempoStage(channels, rate)
        stage.speed = 2.0
        drive(stage, sine(440.0, 1.0), chunkFrames = 1_024)
        stage.reset()
        assertEquals(0L, stage.consumedFrames)
        assertEquals(0L, stage.emittedFrames)
        assertTrue(!stage.hasQueuedInput, "a reset stage holds nothing from before the seek")
    }

    @Test
    fun `speeds outside the documented range are refused`() {
        val stage = TempoStage(channels, rate)
        assertFailsWith<IllegalArgumentException> { stage.speed = 0.1 }
        assertFailsWith<IllegalArgumentException> { stage.speed = 5.0 }
        assertFailsWith<IllegalArgumentException> { stage.speed = Double.NaN }
    }
}
