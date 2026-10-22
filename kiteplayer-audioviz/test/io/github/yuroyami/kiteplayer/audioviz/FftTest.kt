package io.github.yuroyami.kiteplayer.audioviz

import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FftTest {

    @Test
    fun rejectsSizesThatAreNotPowersOfTwo() {
        assertFailsWith<IllegalArgumentException> { Fft(100) }
        assertFailsWith<IllegalArgumentException> { Fft(0) }
    }

    @Test
    fun constantInputPutsEverythingInTheFirstBin() {
        val size = 64
        val real = FloatArray(size) { 1f }
        val imaginary = FloatArray(size)

        Fft(size).forward(real, imaginary)

        assertEquals(size.toFloat(), real[0], 1e-3f, "a constant is all direct current")
        for (bin in 1 until size) {
            assertTrue(magnitude(real, imaginary, bin) < 1e-3f, "bin $bin should be empty")
        }
    }

    @Test
    fun aSineLandsInItsOwnBin() {
        val size = 64
        val bin = 8
        val real = FloatArray(size) { sin(2.0 * PI * bin * it / size).toFloat() }
        val imaginary = FloatArray(size)

        Fft(size).forward(real, imaginary)

        val loudest = (0 until size / 2).maxByOrNull { magnitude(real, imaginary, it) }
        assertEquals(bin, loudest, "a tone at bin $bin must peak at bin $bin")
        // Real input mirrors, so half the energy sits in each of the two matching bins.
        assertEquals(size / 2f, magnitude(real, imaginary, bin), 1e-2f)
    }

    @Test
    fun energySurvivesTheTransform() {
        val size = 256
        val samples = FloatArray(size) { sin(0.31 * it).toFloat() + 0.4f * sin(2.7 * it).toFloat() }
        val timeEnergy = samples.fold(0.0) { sum, value -> sum + value.toDouble() * value }

        val real = samples.copyOf()
        val imaginary = FloatArray(size)
        Fft(size).forward(real, imaginary)

        var binEnergy = 0.0
        for (bin in 0 until size) {
            val value = magnitude(real, imaginary, bin).toDouble()
            binEnergy += value * value
        }

        // Parseval's theorem for this convention: the bins hold N times the samples' energy.
        assertEquals(timeEnergy * size, binEnergy, timeEnergy * size * 1e-4)
    }

    @Test
    fun theHannWindowStartsAndEndsAtZero() {
        val window = Fft.hannWindow(32)
        assertEquals(0f, window.first(), 1e-6f)
        assertEquals(0f, window.last(), 1e-6f)
        assertEquals(1f, window[window.size / 2], 5e-3f)
    }

    private fun magnitude(real: FloatArray, imaginary: FloatArray, bin: Int): Float =
        sqrt(real[bin] * real[bin] + imaginary[bin] * imaginary[bin])
}
