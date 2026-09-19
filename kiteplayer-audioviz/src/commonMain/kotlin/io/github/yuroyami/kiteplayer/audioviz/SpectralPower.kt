package io.github.yuroyami.kiteplayer.audioviz

import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * One analyser-owned spectral work area. Published frames copy its results.
 *
 * Bins contain one-sided mean-square power: d[k] * |FFT(x*w)[k]|^2 / (N * sum(w^2)).
 * d is one for DC/Nyquist and two elsewhere. This is PSD times the FFT bin spacing, not the
 * coherent-gain amplitude spectrum. Every channel has weight 1/channelCount, including LFE;
 * programme K-weighting and its LFE exclusion are separate measurements.
 * Two real channels share each complex FFT. Summing the conjugate-frequency pair recovers their
 * separate powers without mixing the waveforms; an odd final channel has a zero imaginary input.
 */
internal class SpectralPower(val size: Int, val sampleRate: Int, val bandCount: Int = 40) {
    init {
        require(size >= 4 && size and (size - 1) == 0)
        require(sampleRate >= 64)
        require(bandCount in 1..512)
    }

    val bins = FloatArray(size / 2 + 1)
    val bands = FloatArray(bandCount)
    val edgesHz = DoubleArray(bandCount + 1)
    var totalPower = 0f
        private set

    private val fft = Fft(size)
    private val window = Fft.hannWindow(size)
    private val windowEnergy = window.sumOf { it.toDouble() * it }
    private val coherentSum = window.sumOf { it.toDouble() }
    private val real = FloatArray(size)
    private val imaginary = FloatArray(size)
    private val sums = DoubleArray(bins.size)
    private val starts = IntArray(bandCount)
    private val weights: Array<DoubleArray>

    init {
        val lowErb = erb(30.0)
        val highErb = erb(minOf(16_000.0, 0.95 * sampleRate / 2))
        for (edge in edgesHz.indices) {
            edgesHz[edge] = (10.0.pow((lowErb + (highErb - lowErb) * edge / bandCount) / 21.4) - 1) / 0.00437
        }
        val spacing = sampleRate.toDouble() / size
        weights = Array(bandCount) { band ->
            val low = edgesHz[band]
            val high = edgesHz[band + 1]
            val first = floor(low / spacing + 0.5).toInt().coerceIn(0, size / 2)
            val last = floor(high / spacing + 0.5).toInt().coerceIn(first, size / 2)
            starts[band] = first
            DoubleArray(last - first + 1) { offset ->
                val bin = first + offset
                val left = maxOf(0.0, (bin - 0.5) * spacing)
                val right = minOf(sampleRate / 2.0, (bin + 0.5) * spacing)
                (minOf(high, right) - maxOf(low, left)).coerceAtLeast(0.0) / (right - left)
            }
        }
    }

    /** Inputs are sanitised channel rings, with [oldest] pointing at their oldest sample. */
    fun measure(channels: Array<FloatArray>, oldest: Int) {
        require(channels.size in 1..64 && channels.all { it.size == size })
        require(oldest in 0 until size)
        sums.fill(0.0)
        val scale = 1.0 / (size * windowEnergy * channels.size)
        for (channelIndex in channels.indices step 2) {
            val first = channels[channelIndex]
            val second = channels.getOrNull(channelIndex + 1)
            var read = oldest
            for (index in 0 until size) {
                real[index] = first[read] * window[index]
                imaginary[index] = (second?.get(read) ?: 0f) * window[index]
                if (++read == size) read = 0
            }
            fft.forward(real, imaginary)
            for (bin in sums.indices) {
                val mirror = if (bin == 0) 0 else size - bin
                val folded = if (bin == 0 || bin == size / 2) 1 else 2
                sums[bin] += packedChannelPower(real[bin], imaginary[bin], real[mirror], imaginary[mirror]) * scale * folded
            }
        }
        var total = 0.0
        for (bin in bins.indices) {
            bins[bin] = sums[bin].toFloat()
            total += sums[bin]
        }
        totalPower = total.toFloat()
        for (band in bands.indices) {
            var power = 0.0
            val bandWeights = weights[band]
            for (offset in bandWeights.indices) power += sums[starts[band] + offset] * bandWeights[offset]
            bands[band] = power.toFloat()
        }
    }

    private fun erb(hz: Double): Double = 21.4 * log10(1 + 0.00437 * hz)

    /** Fixed range integration using the same clipped frequency cells as the ERB bands. */
    fun integratedPower(lowHz: Double, highHz: Double): Double {
        val low = lowHz.coerceIn(0.0, sampleRate / 2.0)
        val high = highHz.coerceIn(low, sampleRate / 2.0)
        val spacing = sampleRate.toDouble() / size
        val first = floor(low / spacing + 0.5).toInt().coerceIn(0, size / 2)
        val last = floor(high / spacing + 0.5).toInt().coerceIn(first, size / 2)
        var result = 0.0
        for (bin in first..last) {
            val left = maxOf(0.0, (bin - 0.5) * spacing)
            val right = minOf(sampleRate / 2.0, (bin + 0.5) * spacing)
            result += bins[bin] * (minOf(high, right) - maxOf(low, left)).coerceAtLeast(0.0) / (right - left)
        }
        return result
    }

    /** Coherent tone amplitude for legacy detectors, separate from broadband/bin power. */
    fun toneAmplitude(bin: Int): Float {
        val folded = if (bin == 0 || bin == size / 2) 1.0 else 2.0
        return (sqrt(bins[bin] * size * windowEnergy / folded) * folded / coherentSum).toFloat()
    }
}

/** For Z = FFT(a + i*b), (|Z[k]|^2 + |Z[-k]|^2)/2 = |FFT(a)[k]|^2 + |FFT(b)[k]|^2. */
internal fun packedChannelPower(real: Float, imaginary: Float, mirrorReal: Float, mirrorImaginary: Float): Double =
    (real.toDouble() * real + imaginary.toDouble() * imaginary +
        mirrorReal.toDouble() * mirrorReal + mirrorImaginary.toDouble() * mirrorImaginary) * 0.5
