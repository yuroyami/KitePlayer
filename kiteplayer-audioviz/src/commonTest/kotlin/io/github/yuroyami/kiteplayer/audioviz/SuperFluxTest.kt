package io.github.yuroyami.kiteplayer.audioviz

import kotlin.math.abs
import kotlin.math.log10
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SuperFluxTest {
    @Test
    fun filterResolutionMatchesTheDeclaredQuarterToneGrid() {
        for ((rate, size, count) in listOf(
            Triple(44_100, 2048, 138), Triple(48_000, 2048, 135),
            Triple(16_000, 512, 99), Triple(8_000, 256, 75),
        )) {
            val bank = LogFrequencyFilterbank(rate, size)
            assertEquals(count, bank.size, "rate=$rate")
            for (index in 0 until bank.size) {
                assertTrue(bank.lowerBin(index) < bank.centreBin(index))
                assertTrue(bank.centreBin(index) < bank.upperBin(index))
                if (index > 0) assertTrue(bank.centreHz(index) > bank.centreHz(index - 1))
            }
        }
    }

    @Test
    fun trianglesHaveUnitHeightWithoutAreaNormalisation() {
        val bank = LogFrequencyFilterbank(48_000, 2048)
        val magnitudes = FloatArray(1025) { 0.25f }
        bank.measure(magnitudes)
        // Sum of discrete unit-height triangle weights equals half its base width.
        for (index in 0 until bank.size) {
            val area = (bank.upperBin(index) - bank.lowerBin(index)) * 0.5
            val expected = log10(1.0 + 512.0 * 0.25 * area)
            assertTrue(abs(bank.values[index] - expected) < 1e-6, "filter=$index")
        }
        val filter = bank.size / 2
        magnitudes.fill(0f)
        magnitudes[bank.centreBin(filter)] = 0.25f
        bank.measure(magnitudes)
        assertEquals(log10(129.0).toFloat(), bank.values[filter], 1e-6f)
    }

    @Test
    fun dcAndOutOfBandValuesCannotPolluteFlux() {
        val bank = LogFrequencyFilterbank(48_000, 2048)
        val magnitudes = FloatArray(1025)
        magnitudes[0] = 1f
        magnitudes[1024] = 1f
        bank.measure(magnitudes)
        assertTrue(bank.values.all { it == 0f })
        magnitudes.fill(Float.NaN)
        bank.measure(magnitudes)
        assertTrue(bank.values.all { it == 0f })
        magnitudes.fill(0.1f)
        bank.measure(magnitudes)
        assertTrue(bank.values.all { it.isFinite() && it > 0f })
    }

    @Test
    fun tinyWindowsAndTheSupportedCadenceLimitsStayFinite() {
        val bank = LogFrequencyFilterbank(1_000, 4)
        bank.measure(floatArrayOf(0f, 0.25f))
        assertEquals(1, bank.size)
        assertEquals(log10(129.0).toFloat(), bank.values.single(), 1e-6f)
        assertEquals(8192, superFluxLag(32768, 1))
        for (hop in listOf(1.0 / 768_000, 32.768)) {
            val picker = CausalFluxPeakPicker(hop, offset = 0.1f)
            assertTrue(picker.feed(1f))
            assertTrue(picker.confidence.isFinite())
            assertTrue(picker.surprise.isFinite())
        }
    }

    @Test
    fun movingEnergyToANeighbourIsSuppressedButNewEnergySurvives() {
        val flux = MaximumFilteredFlux(7, 1)
        val first = floatArrayOf(0f, 0f, 2f, 0f, 0f, 0f, 0f)
        flux.feed(first)
        assertTrue(flux.growth.all { it == 0f }, "attachment primes instead of inventing an onset")
        flux.feed(floatArrayOf(0f, 0f, 0f, 2f, 0f, 0f, 0f))
        assertTrue(flux.growth.all { it == 0f }, "one-filter pitch movement is not an attack")
        flux.feed(floatArrayOf(0f, 0f, 0f, 2.5f, 0f, 1f, 0f))
        assertEquals(listOf(0f, 0f, 0f, 0.5f, 0f, 1f, 0f), flux.growth.toList())
        // Inputs are borrowed. Mutating one after feed cannot rewrite retained history.
        flux.reset()
        flux.feed(first)
        first.fill(0f)
        flux.feed(floatArrayOf(0f, 0f, 2f, 0f, 0f, 0f, 0f))
        assertTrue(flux.growth.all { it == 0f })
    }

    @Test
    fun lagAndResetUseTheCorrectPastObservation() {
        assertEquals(1, superFluxLag(2048, 441))
        assertEquals(1, superFluxLag(2048, 480))
        assertEquals(2, superFluxLag(2048, 221))
        val flux = MaximumFilteredFlux(3, 2)
        flux.feed(floatArrayOf(1f, 1f, 1f))
        flux.feed(floatArrayOf(9f, 9f, 9f))
        assertTrue(flux.growth.all { it == 0f })
        flux.feed(floatArrayOf(2f, 2f, 2f))
        assertEquals(listOf(1f, 1f, 1f), flux.growth.toList())
        flux.reset()
        flux.feed(floatArrayOf(8f, 8f, 8f))
        assertTrue(flux.growth.all { it == 0f })
    }

    @Test
    fun theCausalPickerKeepsDoubleHitsAndRejectsPlateausAndTails() {
        val picker = CausalFluxPeakPicker(0.01, offset = 0.05f)
        val hits = ArrayList<Int>()
        for (index in 0..60) {
            val value = when (index) {
                20, 26 -> 1f
                in 35..40 -> 1f
                41 -> 0.9f
                42 -> 0.8f
                else -> 0f
            }
            if (picker.feed(value)) hits += index
        }
        assertEquals(listOf(20, 26, 35), hits)
    }

    @Test
    fun thePickerUsesAPastMeanAndStrictOffsetWithACombinationInterval() {
        val picker = CausalFluxPeakPicker(0.01, offset = 0.1f)
        assertTrue(picker.feed(5f))
        repeat(9) { assertFalse(picker.feed(0f)) }
        assertFalse(picker.feed(0.45f), "a median would ignore the large past observation")
        picker.reset()
        assertFalse(picker.feed(0.1f), "equality with the offset is not an onset")
        assertTrue(picker.feed(1f))
        assertFalse(picker.feed(0f))
        assertFalse(picker.feed(2f), "a new maximum inside the combination interval is suppressed")
        picker.reset()
        assertFalse(picker.feed(Float.NaN))
        assertFalse(picker.feed(Float.POSITIVE_INFINITY))
        assertTrue(picker.feed(1f), "invalid input does not poison subsequent history")
    }
}
