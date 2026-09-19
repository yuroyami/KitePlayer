package io.github.yuroyami.kiteplayer.audioviz

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpectralPowerTest {
    @Test
    fun packedComplexBinsRecoverThePowerOfTwoIndependentRealSignals() {
        val size = 128
        val left = FloatArray(size) { (0.1 + 0.3 * sin(2 * PI * 5.37 * it / size)).toFloat() }
        val right = FloatArray(size) { (0.2 * cos(2 * PI * 19.7 * it / size) + if (it % 2 == 0) 0.07 else -0.07).toFloat() }
        val real = left.copyOf()
        val imaginary = right.copyOf()
        Fft(size).forward(real, imaginary)
        for (bin in 0..size / 2) {
            fun reference(samples: FloatArray): Double {
                var re = 0.0
                var im = 0.0
                for (index in samples.indices) {
                    val angle = 2 * PI * bin * index / size
                    re += samples[index] * cos(angle)
                    im -= samples[index] * sin(angle)
                }
                return re * re + im * im
            }
            val expected = reference(left) + reference(right)
            val mirror = (size - bin) % size
            closeTo(expected, packedChannelPower(real[bin], imaginary[bin], real[mirror], imaginary[mirror]),
                2e-5 * maxOf(1.0, expected))
        }
    }

    @Test
    fun unrelatedChannelGroupsAndAnOddTailMatchIndependentWindowedTransforms() {
        val size = 128
        val oldest = 43
        val signals = Array(6) { channel -> FloatArray(size) { index ->
            (0.03 * channel + 0.3 * sin(2 * PI * (3.17 + channel * 7) * index / size) +
                if (index % 2 == 0) 0.05 else -0.05).toFloat()
        } }
        val window = DoubleArray(size) { 0.5 * (1 - cos(2 * PI * it / (size - 1))) }
        val energy = window.sumOf { it * it }
        val references = signals.map { samples -> DoubleArray(size / 2 + 1) { bin ->
            var re = 0.0
            var im = 0.0
            for (index in samples.indices) {
                val angle = 2 * PI * bin * index / size
                re += samples[index] * window[index] * cos(angle)
                im -= samples[index] * window[index] * sin(angle)
            }
            (re * re + im * im) * (if (bin == 0 || bin == size / 2) 1 else 2) / (size * energy)
        } }
        val power = SpectralPower(size, 8_000)
        for (count in 1..6) {
            val rings = Array(count) { channel -> FloatArray(size).also { ring ->
                signals[channel].forEachIndexed { index, value -> ring[(index + oldest) % size] = value }
            } }
            power.measure(rings, oldest)
            for (bin in power.bins.indices) {
                closeTo((0 until count).sumOf { references[it][bin] } / count, power.bins[bin].toDouble(), 2e-8)
            }
        }
    }

    @Test
    fun dcNyquistAndABinToneHaveTheirDeclaredMeanSquarePower() {
        val size = 2048
        val power = SpectralPower(size, 48_000)
        power.measure(arrayOf(FloatArray(size) { 1f }), 0)
        closeTo(1.0, power.totalPower.toDouble())
        power.measure(arrayOf(FloatArray(size) { if (it % 2 == 0) 1f else -1f }), 0)
        closeTo(1.0, power.totalPower.toDouble())
        power.measure(arrayOf(tone(size, 31.0)), 0)
        closeTo(0.5, power.totalPower.toDouble())
        power.measure(arrayOf(tone(size, 31.0, 0.5)), 0)
        closeTo(0.125, power.totalPower.toDouble())
    }

    @Test
    fun stereoMeasuresMeanPowerWithoutPolarityCancellation() {
        val size = 2048
        val power = SpectralPower(size, 48_000)
        val left = tone(size, 44.3, 0.4)
        power.measure(arrayOf(left), 0)
        val mono = power.bins.copyOf()
        closeTo(0.08, power.totalPower.toDouble())
        power.measure(arrayOf(left, left), 0)
        compare(mono, power.bins)
        power.measure(arrayOf(left, FloatArray(size) { -left[it] }), 0)
        compare(mono, power.bins)
        power.measure(arrayOf(left, FloatArray(size)), 0)
        compare(FloatArray(mono.size) { mono[it] * 0.5f }, power.bins)
    }

    @Test
    fun fftPowerMatchesAnIndependentDirectTransformIncludingRingWrap() {
        val size = 128
        val samples = FloatArray(size) {
            (0.1 + 0.3 * sin(2 * PI * 15 * it / size) + 0.125 * cos(2 * PI * 26.77872 * it / size)).toFloat()
        }
        val power = SpectralPower(size, 8_000)
        val oldest = 43
        val ring = FloatArray(size)
        samples.forEachIndexed { index, value -> ring[(index + oldest) % size] = value }
        power.measure(arrayOf(ring), oldest)
        val window = DoubleArray(size) { 0.5 * (1 - cos(2 * PI * it / (size - 1))) }
        val energy = window.sumOf { it * it }
        val expected = DoubleArray(size / 2 + 1) { bin ->
            var real = 0.0
            var imaginary = 0.0
            for (index in samples.indices) {
                val phase = 2 * PI * bin * index / size
                val sample = samples[index] * window[index]
                real += sample * cos(phase)
                imaginary -= sample * sin(phase)
            }
            (real * real + imaginary * imaginary) *
                (if (bin == 0 || bin == size / 2) 1 else 2) / (size * energy)
        }
        expected.forEachIndexed { index, value -> closeTo(value, power.bins[index].toDouble(), 2e-8) }
        closeTo(expected.sum(), power.totalPower.toDouble())
    }

    @Test
    fun bandCountDoesNotChangeRepresentedPowerAndEdgesUseErbSpacing() {
        val size = 2048
        val samples = tone(size, 43.27, 0.7)
        val sums = mutableListOf<Double>()
        for (count in listOf(32, 40, 64)) {
            val power = SpectralPower(size, 48_000, count)
            power.measure(arrayOf(samples), 0)
            sums += power.bands.sumOf { it.toDouble() }
            closeTo(30.0, power.edgesHz.first())
            closeTo(16_000.0, power.edgesHz.last())
            val step = erb(power.edgesHz[1]) - erb(power.edgesHz[0])
            for (index in 1 until count) closeTo(step, erb(power.edgesHz[index + 1]) - erb(power.edgesHz[index]))
        }
        assertTrue(sums.all { it > 0.24 }, "a calibrated 0.7-amplitude tone lost its energy")
        sums.forEach { closeTo(sums.first(), it) }
    }

    @Test
    fun fractionalBinEdgesMatchASeparateOverlapIntegral() {
        val size = 128
        val rate = 8_000
        val power = SpectralPower(size, rate, 64)
        power.measure(arrayOf(FloatArray(size) { if (it == 63) 1f else 0f }), 0)
        val spacing = rate.toDouble() / size
        for (band in power.bands.indices) {
            var expected = 0.0
            for (bin in power.bins.indices) {
                val low = maxOf(0.0, (bin - 0.5) * spacing)
                val high = minOf(rate / 2.0, (bin + 0.5) * spacing)
                val overlap = (minOf(high, power.edgesHz[band + 1]) - maxOf(low, power.edgesHz[band])).coerceAtLeast(0.0)
                expected += power.bins[bin] * overlap / (high - low)
            }
            closeTo(expected, power.bands[band].toDouble(), 1e-9)
        }
        assertTrue(power.bands.all { it > 0f }, "narrow bands receive a fraction of a bin, not an invented whole bin")
    }

    @Test
    fun silenceClearsPreviousPowerAndLowRatesCapTheTopEdge() {
        val power = SpectralPower(512, 16_000)
        power.measure(arrayOf(tone(512, 32.0)), 0)
        assertTrue(power.totalPower > 0f)
        power.measure(arrayOf(FloatArray(512)), 0)
        assertEquals(0f, power.totalPower)
        assertTrue(power.bins.all { it == 0f } && power.bands.all { it == 0f })
        closeTo(7_600.0, power.edgesHz.last())
    }

    private fun tone(size: Int, cycles: Double, amplitude: Double = 1.0): FloatArray =
        FloatArray(size) { (amplitude * sin(2 * PI * cycles * it / size)).toFloat() }

    private fun erb(hz: Double): Double = 21.4 * ln(1 + 0.00437 * hz) / ln(10.0)

    private fun compare(expected: FloatArray, actual: FloatArray) {
        assertEquals(expected.size, actual.size)
        expected.indices.forEach { closeTo(expected[it].toDouble(), actual[it].toDouble()) }
    }

    private fun closeTo(expected: Double, actual: Double, tolerance: Double = 2e-6) {
        assertTrue(abs(actual - expected) <= tolerance, "expected $expected but was $actual (tolerance $tolerance)")
    }
}
