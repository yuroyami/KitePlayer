package io.github.yuroyami.kiteplayer.audioviz

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DisplayScaleTest {
    @Test
    fun fixedPowerCurveHasDeclaredStepsAndAmplitudeUsesSquaredGain() {
        assertEquals(1.0, powerHeight(1.0), 1e-12)
        assertEquals(0.536263, powerHeight(0.1), 1e-6)
        assertEquals(0.275485, powerHeight(0.01), 1e-6)
        assertEquals(0.0, powerHeight(1e-5), 1e-12)
        assertEquals(0.0, powerHeight(0.0))
        assertEquals(1.0, powerHeight(99.0))
        assertTrue(powerHeight(0.25 * 0.25) < powerHeight(0.25))
    }

    @Test
    fun referenceUsesElapsedTimeAndDoesNotLearnFromSilence() {
        val scale = DisplayScale()
        assertEquals(100.0, scale.gain, 1e-12)
        repeat(100) { scale.advance(0.5, false, 0.01) }
        val expected = 0.5 + (0.01 - 0.5) * exp(-1.0)
        assertEquals(expected, scale.referencePower, 1e-12)
        val beforeSilence = scale.referencePower
        repeat(1000) { scale.advance(1e-100, true, 0.01) }
        assertEquals(beforeSilence, scale.referencePower)
        repeat(100) { scale.advance(0.01, false, 0.01) }
        assertEquals(0.01 + (beforeSilence - 0.01) * exp(-1.0 / 15), scale.referencePower, 1e-12)
        val otherCadence = DisplayScale()
        repeat(50) { otherCadence.advance(0.5, false, 0.02) }
        assertEquals(expected, otherCadence.referencePower, 1e-12)
    }

    @Test
    fun gainIsBoundedAndMapHandoverIsLogarithmicAndFinite() {
        val scale = DisplayScale()
        assertFailsWith<IllegalArgumentException> { scale.setReference(0.0) }
        assertFailsWith<IllegalArgumentException> { scale.setReference(Double.NaN) }
        scale.setReference(0.5)
        repeat(100) { scale.advance(null, true, 0.01) }
        assertEquals(exp((ln(100.0) + ln(2.0)) / 2), scale.gain, 1e-10)
        assertTrue(scale.handingOver)
        repeat(100) { scale.advance(null, true, 0.01) }
        assertEquals(2.0, scale.gain, 1e-10)
        assertFalse(scale.handingOver)
        repeat(3000) { scale.advance(1e-12, false, 0.01) }
        assertEquals(2.0, scale.gain, 1e-10)
        scale.setReference(1e-12)
        scale.advance(null, true, 2.0)
        assertEquals(10.0.pow(2.4), scale.gain, 1e-10)
        assertTrue(scale.gainLimited)
        scale.setReference(1e6)
        scale.advance(null, true, 2.0)
        assertEquals(10.0.pow(-2.4), scale.gain, 1e-10)
    }

    @Test
    fun fastSlowAndPeakFollowAnalyticMediaTimeResponses() {
        fun raised(step: Double): EnergyEnvelope {
            val envelope = EnergyEnvelope()
            repeat((0.1 / step + 0.5).toInt()) { envelope.advance(1.0, step) }
            assertEquals(1 - exp(-0.1 / 0.01), envelope.fast, 1e-12)
            assertEquals(1 - exp(-0.1 / 0.1), envelope.slow, 1e-12)
            return envelope
        }
        val envelope = raised(0.01)
        val other = raised(0.02)
        repeat(60) { envelope.advance(0.0, 0.01) }
        repeat(30) { other.advance(0.0, 0.02) }
        assertEquals((1 - exp(-10.0)) * exp(-0.6 / 0.12), envelope.fast, 1e-12)
        assertEquals((1 - exp(-1.0)) * exp(-0.6 / 2), envelope.slow, 1e-12)
        assertEquals(powerHeight(10.0.pow(-1.2 / 10)), envelope.peak, 1e-12)
        assertEquals(envelope.fast, other.fast, 1e-12)
        assertEquals(envelope.slow, other.slow, 1e-12)
        assertEquals(envelope.peak, other.peak, 1e-12)
    }

    @Test
    fun aQuietPassageRetainsContrastForTenSecondsAfterLoud() {
        val scale = DisplayScale()
        repeat(1000) { scale.advance(0.5, false, 0.01) }
        val loud = powerHeight(0.25 * scale.gain)
        val envelope = EnergyEnvelope()
        repeat(100) { envelope.advance(0.25 * scale.gain, 0.01) }
        val quiet = 0.25 * 10.0.pow(-1.2)
        repeat(1000) { index ->
            scale.advance(0.5 * 10.0.pow(-1.2), false, 0.01)
            envelope.advance(quiet * scale.gain, 0.01)
            if (index >= 100) assertTrue(envelope.fast <= loud * 0.7, "quiet contrast at $index")
        }
    }
}
