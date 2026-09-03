package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.EqualizerStage
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The equaliser's filters, measured rather than inspected.
 *
 * A biquad is easy to write and easy to write wrongly: a sign flipped in the feedback taps still
 * produces plausible-looking audio, and a coefficient normalised by the wrong term produces a
 * filter that is simply a different filter. So nothing here reads the coefficients. Every case
 * feeds a real sine at a real frequency and measures what comes out, which is the only thing that
 * distinguishes the intended filter from a nearby one.
 *
 * The measurement is the steady-state amplitude: a biquad takes a few dozen samples to settle, so
 * the first chunk of every run is discarded before anything is measured.
 */
class EqualizerStageTest {

    private val rate = 48_000

    /** A mono sine at [hz], amplitude 0.5, long enough to settle and then be measured. */
    private fun sine(hz: Float, frames: Int = 8192): FloatArray =
        FloatArray(frames) { 0.5f * sin(2.0 * PI * hz * it / rate).toFloat() }

    /** The largest magnitude after the filter has settled. */
    private fun steadyPeak(samples: FloatArray, settleFrames: Int = 4096): Float {
        var peak = 0f
        for (i in settleFrames until samples.size) {
            val magnitude = abs(samples[i])
            if (magnitude > peak) peak = magnitude
        }
        return peak
    }

    private fun gainAt(hz: Float, settings: EqualizerSettings): Float {
        val stage = EqualizerStage(channels = 1, sampleRate = rate)
        stage.set(settings)
        val samples = sine(hz)
        stage.apply(samples, samples.size)
        return steadyPeak(samples) / 0.5f
    }

    private fun bandGains(vararg pairs: Pair<Int, Float>): EqualizerSettings {
        val gains = MutableList(10) { 0f }
        pairs.forEach { (band, db) -> gains[band] = db }
        return EqualizerSettings(gains)
    }

    @Test
    fun `a flat equaliser leaves every sample exactly as it was`() {
        val stage = EqualizerStage(channels = 2, sampleRate = rate)
        stage.set(EqualizerSettings.Flat)
        val samples = floatArrayOf(0.1f, -0.2f, 0.3f, -0.4f)
        val original = samples.copyOf()
        stage.apply(samples, frames = 2)
        assertContentEquals(original, samples, "a flat equaliser filtered anyway")
        assertTrue(stage.isFlat)
    }

    @Test
    fun `a band boosts its own centre frequency by what it was asked for`() {
        // +6 dB is a factor of two, which is why it is the number to test with: the assertion reads
        // as a fact about the sound rather than about a logarithm.
        val gain = gainAt(1000f, bandGains(5 to 6f))
        assertTrue(abs(gain - 2f) < 0.1f, "expected about twice the amplitude at 1 kHz, measured $gain")
    }

    @Test
    fun `a band cuts its own centre frequency too`() {
        val gain = gainAt(1000f, bandGains(5 to -6f))
        assertTrue(abs(gain - 0.5f) < 0.05f, "expected about half the amplitude at 1 kHz, measured $gain")
    }

    @Test
    fun `a band leaves distant frequencies where they are`() {
        // The whole point of a peaking filter over a shelf: moving 1 kHz must not move the bass.
        val settings = bandGains(5 to 12f)
        val atBass = gainAt(125f, settings)
        val atTreble = gainAt(8000f, settings)
        assertTrue(abs(atBass - 1f) < 0.2f, "the 1 kHz band moved 125 Hz by a factor of $atBass")
        assertTrue(abs(atTreble - 1f) < 0.2f, "the 1 kHz band moved 8 kHz by a factor of $atTreble")
    }

    @Test
    fun `every band affects its own centre`() {
        // A coefficient table indexed wrongly still boosts SOMETHING, so each band is checked at
        // its own frequency. The lowest and highest are the ones a wrong index would expose.
        for (band in EqualizerSettings.Bands.indices) {
            val centre = EqualizerSettings.Bands[band]
            if (centre * 2 >= rate) continue
            val gain = gainAt(centre, bandGains(band to 6f))
            assertTrue(
                gain > 1.5f,
                "band $band at ${centre}Hz was asked for +6 dB and measured a factor of $gain",
            )
        }
    }

    @Test
    fun `the preamp scales everything`() {
        val gain = gainAt(1000f, EqualizerSettings(List(10) { 0f }, preampDb = -6f))
        assertTrue(abs(gain - 0.5f) < 0.02f, "the preamp did not halve the level, measured $gain")
    }

    @Test
    fun `channels are filtered independently`() {
        // One shared history would let the left channel's samples drive the right channel's
        // filters, which sounds like a phasing artefact rather than an equaliser.
        val stage = EqualizerStage(channels = 2, sampleRate = rate)
        stage.set(bandGains(5 to 6f))
        val frames = 8192
        val interleaved = FloatArray(frames * 2)
        val mono = sine(1000f, frames)
        for (i in 0 until frames) {
            interleaved[i * 2] = mono[i]
            interleaved[i * 2 + 1] = 0f
        }
        stage.apply(interleaved, frames)
        var rightPeak = 0f
        for (i in 4096 until frames) {
            val magnitude = abs(interleaved[i * 2 + 1])
            if (magnitude > rightPeak) rightPeak = magnitude
        }
        assertEquals(0f, rightPeak, absoluteTolerance = 1e-6f, message = "a silent channel picked up the other one")
    }

    @Test
    fun `a band above the Nyquist frequency is skipped rather than degenerate`() {
        // At 32 kHz the 16 kHz band sits exactly on the Nyquist frequency, where the cookbook's
        // arithmetic falls apart. Skipping it is honest; producing NaN is not.
        val stage = EqualizerStage(channels = 1, sampleRate = 32_000)
        stage.set(bandGains(9 to 12f))
        val samples = FloatArray(2048) { 0.5f * sin(2.0 * PI * 1000f * it / 32_000).toFloat() }
        stage.apply(samples, samples.size)
        assertTrue(samples.all { it.isFinite() }, "a band at the Nyquist frequency produced NaN")
    }

    @Test
    fun `a reset clears the tail so a seek carries nothing across`() {
        val stage = EqualizerStage(channels = 1, sampleRate = rate)
        stage.set(bandGains(5 to 12f))
        val loud = sine(1000f, 4096)
        stage.apply(loud, loud.size)
        stage.reset()
        val silence = FloatArray(512)
        stage.apply(silence, silence.size)
        assertTrue(
            silence.all { abs(it) < 1e-6f },
            "the filter rang on into silence after a reset: ${silence.maxOf { abs(it) }}",
        )
    }

    @Test
    fun `impossible settings are refused at construction`() {
        assertFailsWith<IllegalArgumentException> { EqualizerSettings(List(9) { 0f }) }
        assertFailsWith<IllegalArgumentException> { EqualizerSettings(List(10) { 20f }) }
        assertFailsWith<IllegalArgumentException> { EqualizerSettings(List(10) { Float.NaN }) }
        assertFailsWith<IllegalArgumentException> { EqualizerSettings(List(10) { 0f }, preampDb = 30f) }
        assertFailsWith<IllegalArgumentException> { EqualizerStage(channels = 0, sampleRate = rate) }
        assertFailsWith<IllegalArgumentException> { EqualizerStage(channels = 2, sampleRate = 0) }
    }
}
