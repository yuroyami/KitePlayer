package io.github.yuroyami.kiteplayer.audioviz

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.roundToInt

/**
 * What the sound is made of, beyond how loud it is.
 *
 * Three readings, all cheap, all taken from the spectrum that was going to be computed anyway.
 *
 * The chroma folds every bin onto the twelve notes of an octave, so a C played anywhere on the
 * keyboard lands in the same slot. Held for a couple of seconds it says what key the music is in,
 * which is a colour a drawing can use.
 *
 * The centroid is where the weight of the spectrum sits. Bright music has it high. The flatness
 * says how tone-like the sound is: a chord is peaky and low, a cymbal or a distorted guitar is
 * spread out and high.
 */
internal class Timbre(
    binCount: Int,
    sampleRate: Int,
    fftSize: Int,
) {
    private val hzPerBin = sampleRate.toFloat() / fftSize
    private val lowestBin = (LOWEST_HZ / hzPerBin).toInt().coerceAtLeast(1)
    private val highestBin = (HIGHEST_HZ / hzPerBin).toInt().coerceAtMost(binCount - 1)
    private val chromaTopBin = (CHROMA_TOP_HZ / hzPerBin).toInt().coerceAtMost(highestBin)

    /** Which of the twelve notes each bin belongs to. Built once. */
    private val pitchClass = IntArray(binCount) { bin ->
        val hz = bin * hzPerBin
        if (hz < LOWEST_HZ) -1 else {
            val semitonesFromA4 = 12f * log2(hz / 440f)
            (((semitonesFromA4.roundToInt() + 69) % 12) + 12) % 12
        }
    }

    private val lowestOctave = log2(LOWEST_HZ)
    private val octaveSpan = log2(HIGHEST_HZ) - lowestOctave

    /** Energy per note of the octave, smoothed over a couple of seconds. */
    val chroma: FloatArray = FloatArray(12)

    /** This analysis's notes, before smoothing. Reused so nothing is allocated per frame. */
    private val nowChroma = FloatArray(12)

    /** The key placed on the circle of fifths, 0 to 1. Neighbouring keys get neighbouring values. */
    var keyHue: Float = 0f
        private set

    /** How clearly one note stands out, 0 to 1. Noise and drums score low. */
    var keyConfidence: Float = 0f
        private set

    /** Where the weight of the spectrum sits, 0 at the bottom of hearing and 1 at the top. */
    var centroid: Float = 0f
        private set

    /** How spread out the spectrum is. A pure tone is near 0, white noise is near 1. */
    var flatness: Float = 0f
        private set

    fun feed(magnitudes: FloatArray, deltaSeconds: Float) {
        var weighted = 0f
        var total = 0f
        var logSum = 0f
        var counted = 0

        nowChroma.fill(0f)
        for (bin in lowestBin..highestBin) {
            val magnitude = magnitudes[bin]
            val hz = bin * hzPerBin
            weighted += magnitude * (log2(hz) - lowestOctave)
            total += magnitude
            logSum += ln(magnitude + 1e-9f)
            counted++

            val note = pitchClass[bin]
            if (note >= 0 && bin <= chromaTopBin) nowChroma[note] += magnitude
        }

        // Held for a couple of seconds, so an arpeggio builds into a chord rather than flickering
        // one note at a time.
        val fade = (deltaSeconds / CHROMA_SECONDS).coerceIn(0f, 1f)
        for (note in chroma.indices) chroma[note] += (nowChroma[note] - chroma[note]) * fade

        if (total > 1e-7f && counted > 0) {
            centroid = (weighted / total / octaveSpan).coerceIn(0f, 1f)
            val geometric = exp(logSum / counted)
            val arithmetic = total / counted
            flatness = (geometric / (arithmetic + 1e-9f)).coerceIn(0f, 1f)
        } else {
            centroid = 0f
            flatness = 0f
        }

        readKey()
    }

    /**
     * Turns the twelve note weights into one colour position.
     *
     * The circle of fifths is used rather than the keyboard order, because keys a fifth apart
     * share most of their notes and sound related. Neighbouring positions therefore mean
     * neighbouring music, which is what a colour ramp wants.
     */
    private fun readKey() {
        var strongest = 0
        var sum = 0f
        for (note in chroma.indices) {
            sum += chroma[note]
            if (chroma[note] > chroma[strongest]) strongest = note
        }
        if (sum <= 1e-7f) {
            keyConfidence = 0f
            return
        }
        // One note out of twelve carrying an twelfth of the weight is no information at all.
        keyConfidence = ((chroma[strongest] / sum * 12f - 1f) / 3f).coerceIn(0f, 1f)
        keyHue = CIRCLE_OF_FIFTHS.indexOf(strongest) / 12f
    }

    fun reset() {
        chroma.fill(0f)
        keyHue = 0f
        keyConfidence = 0f
        centroid = 0f
        flatness = 0f
    }

    private companion object {
        const val LOWEST_HZ = 60f
        const val HIGHEST_HZ = 10_000f
        const val CHROMA_TOP_HZ = 5_000f
        const val CHROMA_SECONDS = 2f

        /** Note numbers in circle of fifths order, starting at C. */
        val CIRCLE_OF_FIFTHS = intArrayOf(0, 7, 2, 9, 4, 11, 6, 1, 8, 3, 10, 5)
    }
}
