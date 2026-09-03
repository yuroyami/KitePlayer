package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.audio.LoudnessMeter
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The loudness meter, against the standard's own reference values.
 *
 * ITU-R BS.1770 is testable in a way most DSP is not: it states what specific signals must measure.
 * A full-scale 997 Hz sine reads -3.01 LUFS, and the same sine 20 dB down reads 20 LU lower. Those
 * are not values taken from this implementation and pinned; they are the numbers the standard
 * exists to produce, so a filter designed wrongly cannot pass them by agreeing with itself.
 *
 * 997 Hz rather than 1000 is the standard's own choice, and it is not fussiness: 1000 divides
 * evenly into common sample rates, so a 1 kHz tone lands on the same few phases over and over and
 * measures something slightly other than a real signal would.
 */
class LoudnessMeterTest {

    private fun sine(hz: Double, seconds: Double, rate: Int, amplitude: Float, channels: Int = 1): FloatArray {
        val frames = (rate * seconds).toInt()
        return FloatArray(frames * channels) { i ->
            val frame = i / channels
            amplitude * sin(2.0 * PI * hz * frame / rate).toFloat()
        }
    }

    private fun measure(samples: FloatArray, rate: Int, channels: Int = 1): Double {
        val meter = LoudnessMeter(rate, channels)
        meter.feed(samples, samples.size / channels)
        return meter.result().integratedLufs
    }

    @Test
    fun `a full-scale tone reads what the standard says it must`() {
        // The reference: a 997 Hz sine at full scale on one channel is -3.01 LUFS.
        val measured = measure(sine(997.0, 5.0, 48_000, 1f), 48_000)
        assertTrue(
            abs(measured - (-3.01)) < 0.15,
            "a full-scale 997 Hz tone must read about -3.01 LUFS, measured $measured",
        )
    }

    @Test
    fun `twenty decibels down reads twenty units lower`() {
        // Loudness is a ratio, so halving the amplitude ten times must move the answer by exactly
        // 20 LU. This is the arm that catches a filter with the right shape and the wrong gain.
        val loud = measure(sine(997.0, 5.0, 48_000, 1f), 48_000)
        val quiet = measure(sine(997.0, 5.0, 48_000, 0.1f), 48_000)
        assertTrue(
            abs((loud - quiet) - 20.0) < 0.1,
            "a 20 dB drop must move the reading by 20 LU, moved ${loud - quiet}",
        )
    }

    @Test
    fun `the answer does not depend on the sample rate`() {
        // The filter coefficients are derived from the rate. A table of 48 kHz numbers used at
        // 44.1 would pass every other case here and fail this one.
        val at48 = measure(sine(997.0, 5.0, 48_000, 0.5f), 48_000)
        val at44 = measure(sine(997.0, 5.0, 44_100, 0.5f), 44_100)
        val at96 = measure(sine(997.0, 5.0, 96_000, 0.5f), 96_000)
        assertTrue(abs(at48 - at44) < 0.15, "44.1 kHz measured $at44 against 48 kHz's $at48")
        assertTrue(abs(at48 - at96) < 0.15, "96 kHz measured $at96 against 48 kHz's $at48")
    }

    @Test
    fun `two identical channels are three units louder than one`() {
        // Sum of power, not of amplitude: two channels carrying the same signal double the power,
        // which is +3.01 LU. Averaging the channels instead would report no change at all.
        val mono = measure(sine(997.0, 5.0, 48_000, 0.5f), 48_000)
        val stereo = measure(sine(997.0, 5.0, 48_000, 0.5f, channels = 2), 48_000, channels = 2)
        assertTrue(
            abs((stereo - mono) - 3.01) < 0.1,
            "two channels must read about 3 LU louder than one, difference was ${stereo - mono}",
        )
    }

    @Test
    fun `silence measures nothing rather than zero`() {
        // Zero LUFS is full scale. Reporting it for silence would be the loudest possible answer
        // for the quietest possible signal.
        val meter = LoudnessMeter(48_000, 2)
        val silence = FloatArray(48_000 * 2)
        meter.feed(silence, 48_000)
        val result = meter.result()
        assertEquals(Double.NEGATIVE_INFINITY, result.integratedLufs)
        assertEquals(0, result.blocksMeasured)
        assertEquals(0f, result.samplePeak)
    }

    @Test
    fun `quiet passages do not drag the answer down`() {
        // The gate, and the reason the standard has one. Three seconds of tone followed by three
        // of near-silence must measure as the tone: a film with long quiet stretches would
        // otherwise read far quieter than anyone hears it.
        val rate = 48_000
        val tone = sine(997.0, 3.0, rate, 0.5f)
        val nearSilence = sine(997.0, 3.0, rate, 0.00002f)
        val toneOnly = measure(tone, rate)

        val meter = LoudnessMeter(rate, 1)
        meter.feed(tone, tone.size)
        meter.feed(nearSilence, nearSilence.size)
        val gated = meter.result().integratedLufs

        assertTrue(
            abs(gated - toneOnly) < 0.5,
            "the quiet half dragged the answer from $toneOnly to $gated",
        )
    }

    @Test
    fun `a passage well below the rest is left out of the answer`() {
        // The RELATIVE gate, which the absolute one cannot stand in for: this quiet half is far
        // above -70 LUFS, so only the 10 LU rule excludes it. Without that rule the answer is
        // dragged about 3 LU down by material nobody would say they were listening to.
        val rate = 48_000
        val loud = sine(997.0, 4.0, rate, 0.5f)
        val quiet = sine(997.0, 4.0, rate, 0.05f)
        val loudOnly = measure(loud, rate)

        val meter = LoudnessMeter(rate, 1)
        meter.feed(loud, loud.size)
        meter.feed(quiet, quiet.size)
        val gated = meter.result().integratedLufs

        assertTrue(
            abs(gated - loudOnly) < 0.5,
            "the relative gate did not exclude the quiet half: $gated against $loudOnly for the loud part alone",
        )
    }

    @Test
    fun `the peak is reported even when nothing is loud enough to measure`() {
        // A single click in an otherwise silent file has a peak and no measurable loudness, and
        // both facts are worth having.
        val meter = LoudnessMeter(48_000, 1)
        val samples = FloatArray(4_800)
        samples[10] = 0.8f
        meter.feed(samples, samples.size)
        val result = meter.result()
        assertEquals(0.8f, result.samplePeak)
        assertEquals(Double.NEGATIVE_INFINITY, result.integratedLufs)
    }

    @Test
    fun `material shorter than one block measures nothing`() {
        // 400 ms is the standard's block. There is no honest answer for less than one.
        val rate = 48_000
        val measured = measure(sine(997.0, 0.2, rate, 1f), rate)
        assertEquals(Double.NEGATIVE_INFINITY, measured)
    }

    @Test
    fun `the LFE channel is not counted`() {
        // The standard excludes it, because a subwoofer's contribution to perceived loudness is
        // already carried by the channels that feed it.
        val rate = 48_000
        val frames = rate * 5
        val quietFronts = FloatArray(frames * 6)
        val loudLfe = FloatArray(frames * 6)
        for (frame in 0 until frames) {
            val value = 0.5f * sin(2.0 * PI * 997.0 * frame / rate).toFloat()
            // A real signal in the LFE, not a constant. A constant is 0 Hz, and the meter's own
            // 38 Hz high pass removes it entirely, so a DC LFE would look excluded even if it were
            // being counted. The first version of this case made exactly that mistake and could
            // not fail.
            val lfe = sin(2.0 * PI * 997.0 * frame / rate).toFloat()
            for (channel in 0 until 6) {
                quietFronts[frame * 6 + channel] = if (channel == 3) 0f else value
                loudLfe[frame * 6 + channel] = if (channel == 3) lfe else value
            }
        }
        val without = measure(quietFronts, rate, channels = 6)
        val with = measure(loudLfe, rate, channels = 6)
        assertTrue(
            abs(without - with) < 0.01,
            "a full-scale LFE changed the reading from $without to $with",
        )
    }

    @Test
    fun `a meter refuses impossible construction and use after reading`() {
        assertFailsWith<IllegalArgumentException> { LoudnessMeter(0, 2) }
        assertFailsWith<IllegalArgumentException> { LoudnessMeter(48_000, 0) }
        assertFailsWith<IllegalArgumentException> { LoudnessMeter(48_000, 9) }
        val meter = LoudnessMeter(48_000, 1)
        meter.result()
        assertFailsWith<IllegalStateException> { meter.feed(FloatArray(10), 10) }
    }
}
