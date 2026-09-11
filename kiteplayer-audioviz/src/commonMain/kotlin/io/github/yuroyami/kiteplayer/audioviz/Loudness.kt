package io.github.yuroyami.kiteplayer.audioviz

import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * How loud a spectrum sounds, rather than how big its numbers are.
 *
 * The ear is far less sensitive below 100 Hz and above 10 kHz, so a bass-heavy track measured by
 * raw energy reads much louder than it sounds. This weights every bin by the standard A curve
 * before adding it up, which is the curve a sound level meter uses.
 */
internal object Loudness {

    /** Linear gain per FFT bin. Built once per analyser: it never changes. */
    fun weightsFor(binCount: Int, sampleRate: Int, fftSize: Int): FloatArray {
        val hzPerBin = sampleRate.toFloat() / fftSize
        return FloatArray(binCount) { bin ->
            val hz = bin * hzPerBin
            if (hz < 10f) 0f else linearGainAt(hz)
        }
    }

    /** The A curve at [hz], as a multiplier rather than in decibels. */
    private fun linearGainAt(hz: Float): Float {
        val squared = hz.toDouble() * hz
        val numerator = 12194.0 * 12194.0 * squared * squared
        val denominator = (squared + 20.6 * 20.6) *
            sqrt((squared + 107.7 * 107.7) * (squared + 737.9 * 737.9)) *
            (squared + 12194.0 * 12194.0)
        // The +2 dB puts the curve at unity near 1 kHz, which is where it is defined to be.
        val decibels = 20.0 * log10(numerator / denominator) + 2.0
        return 10.0.pow(decibels / 20.0).toFloat()
    }

    /** Weighted energy of one spectrum, in decibels relative to full scale. */
    fun decibels(magnitudes: FloatArray, weights: FloatArray, binCount: Int): Float {
        var sum = 0f
        for (bin in 0 until binCount) {
            val weighted = magnitudes[bin] * weights[bin]
            sum += weighted * weighted
        }
        return 10f * log10(sum + 1e-12f)
    }
}
