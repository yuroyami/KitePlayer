package io.github.yuroyami.kiteplayer.internal

import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.SampleFormat
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The pitch on its own (#465), through the whole pipeline: a 440 Hz tone a pitch of 2 plays at
 * 880 Hz and lasts as long as the speed says, the runs the clock is dated from report the speed and
 * not the pitch, and a change while playing has no seam.
 */
class PitchShiftTest {

    private val rate = 48_000
    private val stereo = AudioFormat(rate, 2, SampleFormat.F32)

    /**
     * What a pipeline played: the samples, the rate of each run it reported, and the frames each
     * run covered, so the media the runs add up to can be checked against the media played.
     */
    private class Played(val samples: FloatArray, val rates: List<Double>, val lengths: List<Int>, val sources: List<Double>) {
        /** Where in the source the last run ends: the media the clock says has played. */
        val mediaFrames: Double get() = sources.last() + rates.last() * lengths.last()
        fun framesAt(rate: Double): Int = rates.indices.filter { abs(rates[it] - rate) < 1e-9 }.sumOf { lengths[it] }
    }

    /** [seconds] of a 440 Hz tone at half scale through a pipeline, in blocks of 1024, its tail included. */
    private fun play(seconds: Double, configure: AudioPipeline.() -> Unit, between: (AudioPipeline, Int) -> Unit = { _, _ -> }): Played {
        val pipeline = AudioPipeline(stereo, stereo).apply(configure)
        val frames = (seconds * rate).toInt()
        val out = ArrayList<Float>()
        val rates = ArrayList<Double>()
        val lengths = ArrayList<Int>()
        val sources = ArrayList<Double>()
        fun runs(made: Int) {
            for (piece in 0 until pipeline.pieceCount) {
                rates += pipeline.pieceRate(piece)
                sources += pipeline.pieceSource(piece)
                val end = if (piece + 1 < pipeline.pieceCount) pipeline.pieceStart(piece + 1) else made
                lengths += end - pipeline.pieceStart(piece)
            }
        }
        var at = 0
        var block = 0
        while (at < frames) {
            between(pipeline, block++)
            val count = minOf(1024, frames - at)
            val input = FloatArray(count * 2) { index -> (0.5 * sin(2 * PI * 440 * (at + index / 2) / rate)).toFloat() }
            val made = pipeline.process(input, count)
            for (value in 0 until made * 2) out += pipeline.output[value]
            runs(made)
            at += count
        }
        val tail = pipeline.finish()
        for (value in 0 until tail * 2) out += pipeline.output[value]
        runs(tail)
        return Played(out.toFloatArray(), rates, lengths, sources)
    }

    /** The tone's frequency over the left channel's frames [from] to [to], by its rising zero crossings. */
    private fun frequency(samples: FloatArray, from: Int, to: Int): Double {
        var crossings = 0
        for (frame in from + 1 until to) if (samples[(frame - 1) * 2] < 0f && samples[frame * 2] >= 0f) crossings++
        return crossings.toDouble() * rate / (to - from)
    }

    private fun middle(played: Played): Double {
        val frames = played.samples.size / 2
        return frequency(played.samples, frames / 4, frames * 3 / 4)
    }

    @Test
    fun anOctaveUpPlaysAtTwiceTheFrequencyForAsLong() {
        val played = play(2.0, { pitch = 2.0 })
        assertEquals(880.0, middle(played), absoluteTolerance = 6.0)
        assertEquals(2.0 * rate, played.samples.size / 2.0, absoluteTolerance = 0.03 * rate, message = "the pitch moved the length")
        assertTrue(played.rates.all { abs(it - 1.0) < 1e-9 }, "the clock was told rates ${played.rates.distinct()}")
    }

    @Test
    fun anOctaveUpAtASpeedOfOneAndAHalfKeepsTheSpeedsLength() {
        val played = play(3.0, { speed = 1.5; pitch = 2.0 })
        assertEquals(880.0, middle(played), absoluteTolerance = 6.0)
        assertEquals(2.0 * rate, played.samples.size / 2.0, absoluteTolerance = 0.03 * rate)
        assertTrue(played.rates.all { abs(it - 1.5) < 1e-9 }, "the clock was told rates ${played.rates.distinct()}")
    }

    @Test
    fun anOctaveDownWithoutPitchCorrectionAddsToTheSpeedsOwnPitch() {
        val played = play(3.0, { speed = 1.5; preservePitch = false; pitch = 0.5 })
        assertEquals(440.0 * 1.5 * 0.5, middle(played), absoluteTolerance = 6.0)
        assertEquals(2.0 * rate, played.samples.size / 2.0, absoluteTolerance = 0.03 * rate)
    }

    @Test
    fun theWidestSpeedAndPitchStillPlay() {
        val played = play(4.0, { speed = 4.0; pitch = 0.5 })
        assertEquals(220.0, middle(played), absoluteTolerance = 8.0)
        assertEquals(1.0 * rate, played.samples.size / 2.0, absoluteTolerance = 0.04 * rate)
    }

    /** A click is a jump far steeper than the tone's own: at 880 Hz and half scale it moves at most 0.058 a frame. */
    @Test
    fun aChangeWhilePlayingHasNoSeamAndNoGap() {
        val played = play(3.0, {}) { pipeline, block ->
            when (block) {
                40 -> pipeline.pitch = 2.0
                90 -> pipeline.pitch = 1.0
            }
        }
        val left = FloatArray(played.samples.size / 2) { played.samples[it * 2] }
        val steepest = left.toList().zipWithNext { a, b -> abs(b - a) }.max()
        assertTrue(steepest < 0.15f, "the sound jumped by $steepest in one frame")
        assertEquals(3.0 * rate, left.size.toDouble(), absoluteTolerance = 0.03 * rate, message = "a change lost or added time")
        val quiet = left.toList().windowed(240).count { window -> window.all { abs(it) < 0.05f } }
        assertEquals(0, quiet, "the sound fell silent for 5 ms or more")
        // The runs end where the media does, so the clock lands where the sound is. At a change the
        // stretch takes the new rate a moment before the fold does, and the runs say so, briefly.
        assertEquals(3.0 * rate, played.mediaFrames, absoluteTolerance = 0.01 * rate, message = "the clock drifted from the sound")
        val off = played.lengths.sum() - played.framesAt(1.0)
        assertTrue(off < 0.06 * rate, "the runs left the speed for $off frames")
    }

    @Test
    fun noPitchGivesTheSameSamplesAsBefore() {
        val plain = play(1.0, { speed = 1.25 })
        val undone = play(1.0, { speed = 1.25; pitch = 2.0; pitch = 1.0 })
        assertTrue(plain.samples.contentEquals(undone.samples), "a pitch set back to none changed the samples")
        val bypass = play(0.5, {})
        val tone = FloatArray(bypass.samples.size) { index -> (0.5 * sin(2 * PI * 440 * (index / 2) / rate)).toFloat() }
        assertTrue(bypass.samples.contentEquals(tone), "at speed 1 with no pitch the pipeline changed the sound")
    }
}
