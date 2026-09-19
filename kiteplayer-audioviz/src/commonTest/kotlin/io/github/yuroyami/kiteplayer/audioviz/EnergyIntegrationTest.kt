package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.ChannelLayout
import io.github.yuroyami.kiteplayer.spi.SampleFormat
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnergyIntegrationTest {
    @Test
    fun interpolationKeepsRichDriversConsistentWithCompatibilityHeights() {
        fun frame(time: Long, value: Float): SpectrumFrame {
            val driver = EnergyDriver(value, value, value)
            val drivers = EnergyDrivers(driver, driver, driver, driver, arrayOf(driver), 1.0, 1.0, false, false, 0)
            return SpectrumFrame(time, floatArrayOf(value), floatArrayOf(value), FloatArray(2),
                value, value, value, value, 0f, 0f, drivers = drivers)
        }
        val mixed = frame(0L, 0.2f).blend(frame(10_000L, 0.8f), 0.5f)
        val drivers = assertNotNull(mixed.drivers)
        assertEquals(0.5f, drivers.overall.fast, 1e-6f)
        assertEquals(mixed.level, drivers.overall.fast)
        assertEquals(mixed.bands[0], drivers.band(0).fast)
        assertEquals(0.5f, drivers.bass.slow, 1e-6f)
    }

    @Test
    fun programmeUsesItsOwnFourHundredMillisecondWindow() {
        val analyzer = SpectrumAnalyzer(sampleRate = 8_000)
        analyzer.feed(FloatArray(800), 800, 1, -1_000_000L)
        val warming = assertNotNull(analyzer.latest.programme)
        assertEquals(AnalysisAvailability.WarmingUp, warming.availability)
        assertNull(warming.meanSquare)
        assertNull(warming.window)
        analyzer.feed(FloatArray(3200), 3200, 1)
        val programme = assertNotNull(analyzer.latest.programme)
        assertEquals(AnalysisAvailability.Ready, programme.availability)
        val window = assertNotNull(programme.window)
        assertEquals(-900_000L, window.startMicros)
        assertEquals(-500_000L, window.endMicros)
        assertEquals(-700_000L, window.referenceMicros)
        assertTrue(window.referenceMicros != analyzer.latest.power?.window?.referenceMicros)
        assertEquals(0.0, programme.meanSquare)
        assertNull(programme.lufs)
        assertTrue(programme.digitalSilence)
        val unknown = SpectrumAnalyzer(sampleRate = 8_000)
        unknown.feed(FloatArray(4000 * 6), 4000, AudioFormat(8_000, 6, SampleFormat.F32, ChannelLayout.Unknown))
        assertEquals(AnalysisAvailability.Unavailable, unknown.latest.programme?.availability)
        assertNotNull(unknown.latest.power)
    }

    @Test
    fun everyLegacyLevelAliasUsesTheSameGainAndQuietPassagesStayQuiet() {
        val analyzer = SpectrumAnalyzer(sampleRate = 8_000)
        analyzer.setSongReferencePower(1.0)
        feed(analyzer, 8_000, 250.0, 0.5, 3.0)
        val loud = analyzer.latest
        val drivers = assertNotNull(loud.drivers)
        assertEquals(1.0, drivers.powerGain, 1e-10)
        assertEquals(loud.level, loud.levelRel)
        assertEquals(loud.bass, loud.bassRel)
        assertEquals(loud.mid, loud.midRel)
        assertEquals(loud.treble, loud.trebleRel)
        assertEquals(drivers.overall.fast, loud.level)
        assertEquals(loud.bands.toList(), loud.bandsRel.toList())
        for (band in loud.bands.indices) {
            assertEquals(drivers.band(band).fast, loud.bands[band])
            assertEquals(drivers.band(band).peak, loud.peaks[band])
        }
        feed(analyzer, 8_000, 250.0, 0.5 * 10.0.pow(-12.0 / 20), 2.0)
        assertTrue(analyzer.latest.level < loud.level * 0.7)
        assertTrue(analyzer.latest.bands.max() < loud.bands.max() * 0.7)
        val retained = drivers.overall.fast
        analyzer.reset()
        feed(analyzer, 8_000, 250.0, 0.5, 0.5)
        assertEquals(1.0, analyzer.latest.drivers?.powerGain)
        assertEquals(retained, drivers.overall.fast)
        analyzer.setSongReferencePower(null)
        analyzer.reset()
        analyzer.feed(FloatArray(80), 80, 1)
        assertEquals(100.0, analyzer.latest.drivers?.powerGain)
    }

    @Test
    fun transientBassAndBandDriversRetainOppositePolarityStereo() {
        fun analyzed(opposite: Boolean): SpectrumFrame {
            val analyzer = SpectrumAnalyzer(sampleRate = 8_000)
            analyzer.setSongReferencePower(1.0)
            val pcm = FloatArray(8_000 * 3 * 2) { index ->
                val value = (0.4 * sin(2 * PI * 70 * (index / 2) / 8_000)).toFloat()
                if (opposite && index % 2 == 1) -value else value
            }
            analyzer.feed(pcm, pcm.size / 2, 2, 0L)
            return analyzer.latest
        }
        val same = analyzed(false)
        val opposite = analyzed(true)
        assertTrue(same.bass > 0.1f)
        assertEquals(same.bass, opposite.bass, 1e-6f)
        assertEquals(same.bands.toList(), opposite.bands.toList())
        assertEquals(same.programme?.meanSquare, opposite.programme?.meanSquare)
    }

    private fun feed(analyzer: SpectrumAnalyzer, rate: Int, hz: Double, amplitude: Double, seconds: Double) {
        val count = (rate * seconds).toInt()
        val pcm = FloatArray(count) { (amplitude * sin(2 * PI * hz * it / rate)).toFloat() }
        analyzer.feed(pcm, count, 1)
    }
}
