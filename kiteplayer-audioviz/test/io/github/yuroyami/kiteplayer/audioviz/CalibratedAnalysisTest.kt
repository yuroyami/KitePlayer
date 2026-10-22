package io.github.yuroyami.kiteplayer.audioviz

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.ChannelLayout
import io.github.yuroyami.kiteplayer.spi.SampleFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class CalibratedAnalysisTest {
    @Test
    fun defaultsUseTenMillisecondHopsAndWindowsNoLongerThanFiftyMilliseconds() {
        for (rate in listOf(8_000, 16_000, 22_050, 44_100, 48_000, 96_000, 192_000)) {
            val analyzer = SpectrumAnalyzer(sampleRate = rate)
            assertTrue(analyzer.fftSize.toDouble() / rate <= 0.05)
            assertTrue(abs(analyzer.hop - rate / 100.0) <= 0.5)
            assertEquals(40, analyzer.bandCount)
            if (rate == 44_100 || rate == 48_000) assertEquals(2048, analyzer.fftSize)
        }
    }

    @Test
    fun completePowerWindowsHaveIndependentTimesAndOwnTheirStorage() {
        val analyzer = SpectrumAnalyzer(sampleRate = 48_000)
        assertEquals(AnalysisAvailability.Unavailable, analyzer.latest.availability)
        analyzer.feed(FloatArray(analyzer.hop), analyzer.hop, 1, -1_000_000L)
        assertEquals(AnalysisAvailability.WarmingUp, analyzer.latest.availability)
        assertNull(analyzer.latest.power)
        val tone = FloatArray(4800) { sin(2 * PI * 1000 * it / 48_000).toFloat() }
        analyzer.feed(tone, tone.size, 1)
        val power = assertNotNull(analyzer.latest.power)
        assertEquals(AnalysisAvailability.Ready, analyzer.latest.availability)
        assertTrue(power.window.hasTimestamp)
        assertEquals(analyzer.latest.ptsMicros, power.window.referenceMicros)
        assertEquals(analyzer.fftSize, power.window.sampleCount)
        assertTrue(abs(assertNotNull(power.window.endMicros) - assertNotNull(power.window.startMicros) - analyzer.fftSize * 1_000_000L / 48_000) <= 1L)
        assertTrue(abs(power.totalMeanSquare - 0.5f) < 1e-5f)
        val bins = power.copyBinPowers()
        val bands = power.copyBandPowers()
        power.copyBinPowers().fill(900f)
        power.copyBandPowers().fill(900f)
        power.copyBandEdgesHz().fill(900.0)
        analyzer.feed(FloatArray(4800), 4800, 1)
        assertEquals(bins.toList(), power.copyBinPowers().toList())
        assertEquals(bands.toList(), power.copyBandPowers().toList())
        assertTrue(abs(power.bandLowHz(0) - 30.0) < 1e-6)
        assertEquals(0f, assertNotNull(analyzer.latest.power).totalMeanSquare)
    }

    @Test
    fun oppositeStereoPolarityDoesNotEraseTheMeasuredSpectrumOrLevel() {
        val analyzer = SpectrumAnalyzer(sampleRate = 48_000)
        val samples = FloatArray(9600) {
            val value = (0.4 * sin(2 * PI * 1000 * (it / 2) / 48_000)).toFloat()
            if (it % 2 == 0) value else -value
        }
        analyzer.feed(samples, 4800, 2, 0L)
        val power = assertNotNull(analyzer.latest.power)
        assertTrue(abs(power.totalMeanSquare - 0.08f) < 1e-5f)
        assertTrue(analyzer.latest.level > 0.1f)
        assertEquals(2, power.channelCount)
    }

    @Test
    fun explicitLayoutAndUnknownTimesSurviveWithoutInventingMeasurements() {
        val analyzer = SpectrumAnalyzer(sampleRate = 48_000)
        val format = AudioFormat(48_000, 6, SampleFormat.F32, ChannelLayout.Surround51, 0x60fL)
        analyzer.feed(FloatArray(4800 * 6), 4800, format)
        val power = assertNotNull(analyzer.latest.power)
        assertEquals(AnalysisAvailability.Ready, analyzer.latest.availability)
        assertNull(power.window.referenceMicros)
        assertNull(power.window.startMicros)
        assertNull(power.window.endMicros)
        assertEquals(ChannelLayout.Surround51, power.channelLayout)
        assertEquals(0x60fL, power.channelLayoutMask)
        assertEquals(6, power.channelCount)
        assertFailsWith<IllegalArgumentException> {
            analyzer.feed(FloatArray(4800 * 6), 4800, format.copy(channelLayoutMask = 0x3fL))
        }
        analyzer.reset()
        analyzer.feed(FloatArray(4800 * 6), 4800, format.copy(channelLayoutMask = 0x3fL), 0L)
        assertEquals(0x3fL, assertNotNull(analyzer.latest.power).channelLayoutMask)
    }

    @Test
    fun directInputSanitisesInvalidSamplesAndRejectsOverflowingFrameCounts() {
        val analyzer = SpectrumAnalyzer(sampleRate = 48_000)
        val samples = FloatArray(4800) { 0.1f }
        samples[0] = Float.NaN
        samples[1] = Float.POSITIVE_INFINITY
        samples[2] = Float.MAX_VALUE
        analyzer.feed(samples, samples.size, 1, 0L)
        assertEquals(3L, analyzer.sanitizedSamples)
        assertTrue(assertNotNull(analyzer.latest.power).copyBinPowers().all { it.isFinite() })
        assertTrue(analyzer.latest.level.isFinite())
        assertFailsWith<IllegalArgumentException> { analyzer.feed(FloatArray(1), Int.MAX_VALUE, 64) }
    }
}
