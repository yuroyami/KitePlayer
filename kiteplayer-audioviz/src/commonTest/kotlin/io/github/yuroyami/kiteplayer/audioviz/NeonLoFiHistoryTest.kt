package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.shader.NeonLoFiHistory
import kotlin.test.*

internal fun neonFrame(t: Long, value: Float = 0.5f, held: Boolean = false,
    available: Boolean = true, revision: Long = 0): SpectrumFrame {
    val bands = FloatArray(64) { value }
    return SpectrumFrame(t, bands, bands, FloatArray(0), value, value, value, value, 0f, 0f,
        energy = value, loudLong = value, held = held, analysisRevision = revision,
        availability = if (available) AnalysisAvailability.Ready else AnalysisAvailability.Unavailable)
}

class NeonLoFiHistoryTest {
    @Test fun exactMediaRowsDoNotDependOnDisplayRateOrPlaybackRate() {
        for (fps in listOf(15, 30, 60, 120)) for (rate in listOf(0.5, 1.0, 2.0)) {
            val h = NeonLoFiHistory()
            for (i in 0..(8 * fps / rate).toInt()) h.record(neonFrame((i * rate * 1_000_000 / fps).toLong()))
            assertEquals(33, h.count, "$fps fps at $rate")
            assertEquals(8_000_000L, h.newestMicros)
            assertEquals(0.5f, h.valueAt(4_000_000L, 31), 0.00001f)
        }
    }
    @Test fun lateEntryPauseGapsAndSeekNeverInventHistory() {
        val h = NeonLoFiHistory()
        h.record(neonFrame(42_100_000))
        assertEquals(0, h.count)
        assertTrue(h.valueAt(40_000_000, 0).isNaN())
        h.record(neonFrame(42_250_000))
        assertEquals(1, h.count)
        repeat(100) { h.record(neonFrame(42_250_000, held = true)) }
        assertEquals(1, h.count)
        h.record(neonFrame(42_500_000, available = false))
        h.record(neonFrame(42_750_000))
        assertTrue(h.valueAt(42_500_000, 0).isNaN())
        h.record(neonFrame(43_000_000, 0f))
        assertEquals(0f, h.valueAt(43_000_000, 0))
        h.record(neonFrame(2_000_000, revision = 1))
        assertEquals(1, h.count)
        assertTrue(h.valueAt(43_000_000, 0).isNaN())
    }
    @Test fun ninetySecondRingAndSparseCatchupRemainBounded() {
        val h = NeonLoFiHistory()
        for (i in 0..400) h.record(neonFrame(i * 250_000L, i / 400f))
        assertEquals(360, h.count)
        assertTrue(h.valueAt(10_000_000, 0).isNaN())
        assertEquals(0.1025f, h.valueAt(10_250_000, 0), 0.00001f)
        h.record(neonFrame(600_000_000))
        assertEquals(360, h.count)
        assertTrue(h.valueAt(550_000_000, 0).isNaN())
        assertEquals(0.5f, h.valueAt(600_000_000, 0))
    }
    @Test fun aggregationRetainsEveryInputBandWithoutPrivateGain() {
        for (count in listOf(8, 40, 48, 64, 128)) for (band in 0 until count) {
            val source = FloatArray(count).apply { this[band] = 0.8f }
            val out = FloatArray(16)
            NeonLoFiHistory.resample(source, out)
            assertTrue(out.any { it > 0f }, "Lost input $band / $count")
            assertTrue(out.all { it in 0f..0.8f })
        }
    }
}
