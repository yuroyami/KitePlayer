package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.presets.FluctusSpectrum
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Actual PCM goes through the shared analyser before it reaches the sheet's spectrum adapter. */
class FluctusSpectrumTest {
    @Test
    fun lowMiddleAndHighTonesKeepTheirSurfacePositionsAcrossSourceFormats() {
        val tones = listOf(187.5 to 8, 2_250.0 to 96, 7_031.25 to 300)
        val formats = listOf(48_000 to 1_024, 48_000 to 2_048, 48_000 to 4_096,
            44_100 to 2_048, 96_000 to 4_096)
        for ((rate, fft) in formats) for ((frequency, expectedCell) in tones) {
            val frame = tone(frequency, sampleRate = rate, fftSize = fft)
            assertNotNull(frame.power, "Fixture must exercise raw power rather than synthetic display bands")
            val spectrum = settled(frame)
            assertEquals(432, spectrum.cells.size)
            val peak = spectrum.cells.indices.maxBy { spectrum.cells[it] }
            assertTrue(abs(peak - expectedCell) <= 2,
                "$frequency Hz at $rate Hz with $fft points must stay near surface cell $expectedCell, got $peak")
            assertTrue(spectrum.cells[peak] > 0.1f, "The tone must produce a visible nonzero spectral sample")
            assertTrue(spectrum.cells.all { it.isFinite() && it in 0f..1f })
        }
    }

    @Test
    fun adjacentToneBinsKeepAllThreePackedColourChannelsInOrder() {
        val peaks = listOf(2_203.125, 2_226.5625, 2_250.0).map { frequency ->
            val cells = settled(tone(frequency)).cells
            cells.indices.maxBy { cells[it] }
        }
        assertEquals(listOf(94, 95, 96), peaks,
            "Sequential FFT samples must remain adjacent across the RGB boundary of the 12 by 12 texture")
    }

    @Test
    fun increasingPhysicalToneAmplitudeRaisesTheFixedDecibelResponse() {
        val heights = listOf(0.0001f, 0.0003f, 0.001f, 0.003f, 0.01f).map { amplitude ->
            settled(tone(2_250.0, amplitude)).cells[96]
        }
        assertTrue(heights.first() > 0f && heights.last() < 1f, "Use the unsaturated part of the decibel range")
        heights.zipWithNext().forEach { (quiet, loud) ->
            assertTrue(loud > quiet + 0.05f,
                "A physically louder tone must rise without a private gain normalising it back down: $heights")
        }
    }

    @Test
    fun highFrequencyTailContributesBeyondTheVisibleTexture() {
        val low = settled(tone(187.5))
        val tail = settled(tone(14_062.5))
        assertTrue(tail.cells.max() < 0.01f, "A tone above 10.1 kHz must not be stretched into the first 432 bins")
        assertTrue(tail.mean > 0f, "Overall motion still hears bins outside the visible texture")
        assertTrue(tail.highActivity > low.highActivity + 0.001f,
            "The wireframe driver must hear the real high-frequency tail")
        val lowerRate = settled(tone(3_000.0, sampleRate = 8_000, fftSize = 512))
        assertTrue((172 until lowerRate.cells.size).all { lowerRate.cells[it] == 0f },
            "A lower-rate source cannot invent energy above its Nyquist frequency")
    }

    @Test
    fun rawSilenceWinsOverDisplayBandsAndLoudAudioCanReturnAfterIt() {
        val silence = tone(2_250.0, amplitude = 0f)
        val contradictoryDisplay = SpectrumFrame(
            ptsMicros = silence.ptsMicros, bands = FloatArray(48) { 1f }, peaks = FloatArray(48) { 1f },
            scope = FloatArray(256), level = 1f, bass = 1f, mid = 1f, treble = 1f,
            beat = 0f, pulse = 0f, power = assertNotNull(silence.power),
        )
        val spectrum = settled(contradictoryDisplay)
        assertTrue(spectrum.cells.all { it == 0f }, "Available raw silence must not use the approximate display-band fallback")
        assertEquals(0f, spectrum.mean)
        assertEquals(0f, spectrum.highActivity)
        val loud = tone(2_250.0, amplitude = 0.01f)
        repeat(60) { spectrum.update(loud, 1f / 60f) }
        assertTrue(spectrum.cells[96] > 0.5f, "Loud sound must still attack after silence")
        repeat(120) { spectrum.update(silence, 1f / 60f) }
        assertTrue(spectrum.cells.all { it == 0f }, "The spectrum must settle fully when actual power is silent")
    }

    @Test
    fun heldFramesInvalidTimeAndResetDoNotLeakSpectralHistory() {
        val frame = tone(7_031.25)
        val spectrum = settled(frame)
        val before = spectrum.cells.copyOf()
        val mean = spectrum.mean
        val high = spectrum.highActivity
        val quiet = tone(187.5, amplitude = 0f)
        repeat(30) { spectrum.update(quiet.withPulseHeld(), 1f / 30f) }
        for (delta in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) spectrum.update(quiet, delta)
        assertContentEquals(before, spectrum.cells)
        assertEquals(mean, spectrum.mean)
        assertEquals(high, spectrum.highActivity)
        spectrum.reset()
        assertTrue(spectrum.cells.all { it == 0f })
        assertEquals(0f, spectrum.mean)
        assertEquals(0f, spectrum.highActivity)
        repeat(90) { spectrum.update(frame, 1f / 60f) }
        assertContentEquals(before, spectrum.cells, "Reset must reproduce the same spectral response")
    }

    @Test
    fun magnitudeSmoothingHasTheSameDurationAtDifferentPresentationRates() {
        val frame = tone(2_250.0)
        fun afterHalfASecond(rate: Int): FluctusSpectrum = FluctusSpectrum().also { spectrum ->
            repeat(rate / 2) { spectrum.update(frame, 1f / rate) }
        }
        val normal = afterHalfASecond(60)
        for (rate in listOf(20, 30, 120)) {
            val other = afterHalfASecond(rate)
            assertTrue(normal.cells.indices.all { abs(normal.cells[it] - other.cells[it]) <= 1f / 256f },
                "$rate Hz must retain the same physical smoothing time")
            assertTrue(abs(normal.mean - other.mean) < 0.001f)
        }
    }

    private fun settled(frame: SpectrumFrame): FluctusSpectrum = FluctusSpectrum().also { spectrum ->
        repeat(90) { spectrum.update(frame, 1f / 60f) }
    }

    private fun tone(
        frequency: Double,
        amplitude: Float = 0.002f,
        sampleRate: Int = 48_000,
        fftSize: Int = 2_048,
    ): SpectrumFrame {
        val analyzer = SpectrumAnalyzer(sampleRate = sampleRate, fftSize = fftSize)
        val samples = FloatArray(maxOf(sampleRate / 4, fftSize * 2)) { sample ->
            (amplitude * sin(2.0 * PI * frequency * sample / sampleRate)).toFloat()
        }
        analyzer.feed(samples, samples.size, channels = 1, ptsMicros = 0L)
        return analyzer.latest.also { assertNotNull(it.power) }
    }
}
