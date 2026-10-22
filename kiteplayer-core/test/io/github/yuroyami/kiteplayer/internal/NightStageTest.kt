package io.github.yuroyami.kiteplayer.internal

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The night mode's compressor (#442), on stereo at 48 kHz in blocks of 512 frames, as the feeder
 * hands them over. Speech is a 1 kHz tone at -33 dBFS, which is -30 dBFS in a 5.1 centre folded
 * into two speakers, and an effect the same tone at -6 dBFS in the fronts.
 */
class NightStageTest {

    private val rate = 48_000

    private fun tone(seconds: Double, db: Double): FloatArray {
        val frames = (seconds * rate).toInt()
        val amplitude = 10.0.pow(db / 20.0)
        return FloatArray(frames * 2) { at -> (amplitude * sin(2 * PI * 1_000 * (at / 2) / rate)).toFloat() }
    }

    private fun NightStage.run(samples: FloatArray): FloatArray {
        val out = samples.copyOf()
        var at = 0
        while (at < out.size) {
            val frames = minOf(512, (out.size - at) / 2)
            val block = out.copyOfRange(at, at + frames * 2)
            apply(block, frames)
            block.copyInto(out, at)
            at += frames * 2
        }
        return out
    }

    /** The peak of the last half second of [samples], in decibels. */
    private fun tailPeakDb(samples: FloatArray): Double {
        val from = samples.size - rate
        return 20 * log10(samples.copyOfRange(from, samples.size).maxOf { abs(it) }.toDouble())
    }

    @Test
    fun offLeavesEverySampleAsItWas() {
        val stage = NightStage(2, rate)
        assertTrue(stage.isIdentity)
        val speech = tone(1.0, -33.0)
        assertTrue(speech.contentEquals(stage.run(speech)))
    }

    @Test
    fun theGapBetweenSpeechAndEffectsShrinks() {
        val stage = NightStage(2, rate).apply { set(true) }
        val speech = stage.run(tone(2.0, -33.0))
        val effects = stage.run(tone(2.0, -6.0))
        val speechDb = tailPeakDb(speech)
        val effectsDb = tailPeakDb(effects)
        assertTrue(speechDb > -33.0 + 6.0, "the speech rose only to $speechDb dB")
        assertTrue(effectsDb < -6.0 - 4.0, "the effects fell only to $effectsDb dB")
        val gap = effectsDb - speechDb
        assertTrue(gap < 27.0 - 12.0, "the 27 dB gap shrank only to $gap dB")
    }

    @Test
    fun theOutputNeverPassesFullScale() {
        val stage = NightStage(2, rate).apply { set(true) }
        stage.run(tone(1.0, -33.0))
        // A full-scale explosion straight after quiet speech, while the gain still lifts the speech.
        val input = tone(0.5, -0.1)
        val loud = stage.run(input)
        // Nothing louder than the ceiling, unless the input itself was: the stage raises none past it.
        val raised = loud.indices.filter { abs(loud[it]) > maxOf(NightStage.CEILING, abs(input[it])) + 1e-6f }
        assertTrue(raised.isEmpty(), "${raised.size} samples were raised past the ceiling, the first to ${raised.firstOrNull()?.let { loud[it] }}")
        assertTrue(loud.all { abs(it) <= 1f }, "the loudest sample was ${loud.maxOf { abs(it) }}")
    }

    @Test
    fun silenceAndHissAreNotRaised() {
        val stage = NightStage(2, rate).apply { set(true) }
        val hiss = stage.run(tone(1.0, -80.0))
        assertEquals(-80.0, tailPeakDb(hiss), absoluteTolerance = 0.1)
    }

    /** On a steady level the gain is what each frame was multiplied by, so a step in it is a click. */
    @Test
    fun turningTheModeOnOrOffNeverClicks() {
        val stage = NightStage(2, rate)
        val level = 0.25f
        val gains = ArrayList<Float>()
        fun play(seconds: Double) {
            val frames = (seconds * rate).toInt()
            var left = frames
            while (left > 0) {
                val count = minOf(512, left)
                val block = FloatArray(count * 2) { level }
                stage.apply(block, count)
                for (frame in 0 until count) gains += block[frame * 2] / level
                left -= count
            }
        }
        play(0.2)
        stage.set(true)
        play(1.0)
        stage.set(false)
        play(2.0)
        val largestStep = gains.zipWithNext { a, b -> abs(b - a) }.maxOrNull() ?: 0f
        assertTrue(largestStep < 0.01f, "the gain stepped by $largestStep in one frame")
        // A level of -12 dB is 18 dB over the threshold: a third of that, less the make-up, is -3 dB.
        assertTrue(gains.minOrNull()!! < 0.75f, "the mode never acted: ${gains.minOrNull()}")
        assertTrue(stage.isIdentity, "the stage never settled back to nothing once off")
        assertEquals(1f, gains.last())
    }
}
