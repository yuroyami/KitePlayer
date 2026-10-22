package io.github.yuroyami.kiteplayer.audioviz

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log2

/**
 * What the sound is made of, beyond how loud it is: two cheap readings from the transient spectrum.
 *
 * The centroid is where the weight of the spectrum sits. Bright music has it high. The flatness
 * says how tone-like the sound is: a chord is peaky and low, a cymbal or a distorted guitar is
 * spread out and high. Pitch classes and key come from [KeyTracker]'s separate long window.
 */
internal class Timbre(
    binCount: Int,
    sampleRate: Int,
    fftSize: Int,
) {
    private val hzPerBin = sampleRate.toFloat() / fftSize
    private val lowestBin = (LOWEST_HZ / hzPerBin).toInt().coerceAtLeast(1)
    private val highestBin = (HIGHEST_HZ / hzPerBin).toInt().coerceAtMost(binCount - 1)
    private val lowestOctave = log2(LOWEST_HZ)
    private val octaveSpan = log2(HIGHEST_HZ) - lowestOctave

    /** Where the weight of the spectrum sits, 0 at the bottom of hearing and 1 at the top. */
    var centroid: Float = 0f
        private set

    /** How spread out the spectrum is. A pure tone is near 0, white noise is near 1. */
    var flatness: Float = 0f
        private set

    fun feed(magnitudes: FloatArray) {
        var weighted = 0f
        var total = 0f
        var logSum = 0f
        var counted = 0
        for (bin in lowestBin..highestBin) {
            val magnitude = magnitudes[bin]
            weighted += magnitude * (log2(bin * hzPerBin) - lowestOctave)
            total += magnitude
            logSum += ln(magnitude + 1e-9f)
            counted++
        }
        if (total > 1e-7f && counted > 0) {
            centroid = (weighted / total / octaveSpan).coerceIn(0f, 1f)
            val geometric = exp(logSum / counted)
            val arithmetic = total / counted
            flatness = (geometric / (arithmetic + 1e-9f)).coerceIn(0f, 1f)
        } else {
            centroid = 0f
            flatness = 0f
        }
    }

    fun reset() {
        centroid = 0f
        flatness = 0f
    }

    private companion object {
        const val LOWEST_HZ = 60f
        const val HIGHEST_HZ = 10_000f
    }
}
