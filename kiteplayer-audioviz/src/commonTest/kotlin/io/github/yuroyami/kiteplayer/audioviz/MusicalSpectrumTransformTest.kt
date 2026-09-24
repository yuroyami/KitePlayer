package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.shader.BassCutStream
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.ConstantQBars
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.PeakingBiquad
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The constant-Q transform and the bass cut behind Musical Spectrum, the port of ShowCQTBar. */
class MusicalSpectrumTransformTest {

    private companion object {
        /** The page's bar and brightness knobs, 19 dB and 25 dB. */
        const val BAR = 8.912509f
        const val COLOUR = 17.782795f
    }

    @Test
    fun theFftAndTheAttackFollowTheSampleRate() {
        val at44 = ConstantQBars(44_100, 64)
        assertEquals(16_384, at44.fftSize)
        assertEquals(1_456, at44.attackSize)
        val at48 = ConstantQBars(48_000, 64)
        assertEquals(16_384, at48.fftSize)
        assertEquals(1_584, at48.attackSize)
        assertEquals(16_384 / 2 + 1_584, at48.span)
        assertEquals(32_768, ConstantQBars(96_000, 64).fftSize)
        assertEquals(128, at48.binCount)
    }

    @Test
    fun theColumnsSpanTenOctavesFromE0MinusFiftyCents() {
        val bins = 3_840
        assertEquals(20.01523126408007475 * 1024.0.pow(0.5 / bins), ConstantQBars.centreHz(0, bins), 1e-9)
        assertEquals(20.01523126408007475 * 1024.0.pow((bins - 0.5) / bins), ConstantQBars.centreHz(bins - 1, bins), 1e-6)
        // The middle of semitone 53 from E0 is A4, 440 Hz, on either side of the bin pair around it.
        val a4 = 53.5 * bins / 120.0
        val below = ConstantQBars.centreHz(a4.toInt() - 1, bins)
        val above = ConstantQBars.centreHz(a4.toInt(), bins)
        assertTrue(below < 440.0 && above > 440.0, "440 Hz lies between $below and $above")
        // The page's time windows: about 0.31 s at 20 Hz, 0.1 s at 1 kHz and 16 ms at 20 kHz.
        assertEquals(0.309, ConstantQBars.timeWindow(20.0), 0.002)
        assertEquals(0.100, ConstantQBars.timeWindow(1_000.0), 0.002)
        assertEquals(0.0157, ConstantQBars.timeWindow(20_000.0), 0.0005)
        assertEquals(1.0, ConstantQBars.nuttall(0.0), 1e-6)
        assertEquals(0.0, ConstantQBars.nuttall(PI), 1e-6)
    }

    @Test
    fun theBassCutIsAPeakingFilterAtTenHertzWithAQOfAThirdAndThirtyDecibelsDown() {
        assertEquals(10.0, BassCutStream.CUT_HZ)
        assertEquals(0.33, BassCutStream.CUT_Q)
        assertEquals(-30.0, BassCutStream.CUT_DECIBELS)
        // The analogue peaking response the Web Audio filter is built from, at each frequency.
        for ((frequency, expected) in listOf(10.0 to -30.0, 20.0 to -20.6, 30.0 to -16.0, 100.0 to -6.0, 1_000.0 to -0.12)) {
            val measured = gainDecibels(frequency)
            assertEquals(expected, measured, 0.3, "the cut at $frequency Hz")
        }
    }

    @Test
    fun aLeftToneIsAmberARightToneIsAzureAndACentredToneIsWhite() {
        // Ten columns a semitone, so A4 is columns 530 to 539.
        val bars = ConstantQBars(48_000, 1_200)
        val tone = FloatArray(bars.span) { 0.02f * sin(2.0 * PI * 440.0 * it / 48_000).toFloat() }
        val silence = FloatArray(bars.span)
        val out = FloatArray(1_200 * 4)

        bars.transform(tone, silence, 1f, BAR, COLOUR, out)
        val left = loudest(out)
        assertTrue(left in 530..539, "440 Hz peaks in the A4 semitone, at column $left")
        val (red, green, blue) = colourAt(out, left)
        // Green is the square root of the mean of both sides' power, so a tone on one side gives 2^-0.25 of it.
        assertTrue(red in 0.2f..0.99f, "red below saturation, was $red")
        assertEquals(0.8409f, green / red, 0.01f)
        assertTrue(blue < 0.02f, "a left tone has no blue, had $blue")

        bars.transform(silence, tone, 1f, BAR, COLOUR, out)
        val (redR, greenR, blueR) = colourAt(out, loudest(out))
        assertEquals(0.8409f, greenR / blueR, 0.01f)
        assertTrue(redR < 0.02f, "a right tone has no red, had $redR")

        bars.transform(tone, tone, 1f, BAR, COLOUR, out)
        val (redC, greenC, blueC) = colourAt(out, loudest(out))
        assertEquals(redC, greenC, 0.002f)
        assertEquals(redC, blueC, 0.002f)
    }

    @Test
    fun aBarsHeightIsTheBarKnobTimesTheMeanAmplitudeOfBothSides() {
        val bars = ConstantQBars(48_000, 1_200)
        val tone = FloatArray(bars.span) { 0.01f * sin(2.0 * PI * 1_000.0 * it / 48_000).toFloat() }
        val out = FloatArray(1_200 * 4)
        bars.transform(tone, tone, 1f, BAR, COLOUR, out)
        val column = loudest(out)
        // Red is the square root of the colour knob times the amplitude, so both come from one amplitude.
        val amplitude = out[column * 4].let { it * it } / COLOUR
        assertEquals(BAR * amplitude, out[column * 4 + 3], 0.02f * BAR * amplitude)
        // A sine read through the transform keeps most of its amplitude; the 33 ms attack cuts the rest.
        assertTrue(amplitude in 0.004f..0.0101f, "a 0.01 sine reads $amplitude")
    }

    private fun loudest(out: FloatArray): Int {
        var best = 0
        for (column in 0 until out.size / 4) if (out[column * 4 + 3] > out[best * 4 + 3]) best = column
        return best
    }

    private fun colourAt(out: FloatArray, column: Int): Triple<Float, Float, Float> =
        Triple(out[column * 4], out[column * 4 + 1], out[column * 4 + 2])

    /** Steady-state gain of the page's bass cut at [frequency], by running a sine through it. */
    private fun gainDecibels(frequency: Double): Double {
        val rate = 48_000
        val filter = PeakingBiquad(rate, BassCutStream.CUT_HZ, BassCutStream.CUT_Q, BassCutStream.CUT_DECIBELS)
        val settle = 6 * rate
        val measure = maxOf(rate, (4 * rate / frequency).toInt())
        var input = 0.0
        var output = 0.0
        for (n in 0 until settle + measure) {
            val x = sin(2.0 * PI * frequency * n / rate).toFloat()
            val y = filter.process(x)
            if (n >= settle) {
                input += x.toDouble() * x
                output += y.toDouble() * y
            }
        }
        return 10.0 * log10(output / input)
    }
}
