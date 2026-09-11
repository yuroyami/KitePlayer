package io.github.yuroyami.kiteplayer.audioviz

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A radix-2 Cooley-Tukey FFT built once for one size and reused.
 *
 * Every table it needs is built in the constructor, so [forward] allocates nothing and is safe
 * to call on an audio thread. The size must be a power of two, which is what radix-2 means.
 */
internal class Fft(public val size: Int) {

    init {
        require(size >= 2 && size and (size - 1) == 0) {
            "an FFT size must be a power of two and at least 2, was $size"
        }
    }

    private val bits: Int = run {
        var count = 0
        while (1 shl count < size) count++
        count
    }

    /** Where each input index moves to before the butterflies run. */
    private val reversed = IntArray(size) { index ->
        var result = 0
        for (bit in 0 until bits) {
            if (index shr bit and 1 == 1) result = result or (1 shl (bits - 1 - bit))
        }
        result
    }

    private val cosTable = FloatArray(size / 2) { cos(-2.0 * PI * it / size).toFloat() }
    private val sinTable = FloatArray(size / 2) { sin(-2.0 * PI * it / size).toFloat() }

    /**
     * Transforms in place. Both arrays must be [size] long, and [imaginary] is all zeros for the
     * real audio input this library feeds it.
     */
    public fun forward(real: FloatArray, imaginary: FloatArray) {
        require(real.size == size && imaginary.size == size) {
            "both halves must be $size long, got ${real.size} and ${imaginary.size}"
        }

        for (index in 0 until size) {
            val target = reversed[index]
            if (target > index) {
                var swap = real[index]
                real[index] = real[target]
                real[target] = swap
                swap = imaginary[index]
                imaginary[index] = imaginary[target]
                imaginary[target] = swap
            }
        }

        var span = 2
        while (span <= size) {
            val half = span / 2
            val twiddleStep = size / span
            var blockStart = 0
            while (blockStart < size) {
                var twiddle = 0
                for (lower in blockStart until blockStart + half) {
                    val upper = lower + half
                    val cosine = cosTable[twiddle]
                    val sine = sinTable[twiddle]
                    val productReal = real[upper] * cosine - imaginary[upper] * sine
                    val productImaginary = real[upper] * sine + imaginary[upper] * cosine
                    real[upper] = real[lower] - productReal
                    imaginary[upper] = imaginary[lower] - productImaginary
                    real[lower] += productReal
                    imaginary[lower] += productImaginary
                    twiddle += twiddleStep
                }
                blockStart += span
            }
            span = span shl 1
        }
    }

    public companion object {
        /** A Hann window of [size] points. Reduces the smearing a rectangular cut would leave. */
        public fun hannWindow(size: Int): FloatArray =
            FloatArray(size) { 0.5f * (1f - cos(2.0 * PI * it / (size - 1)).toFloat()) }

        /** How much a Hann window shrinks a steady tone, which the magnitudes divide back out. */
        public const val HANN_COHERENT_GAIN: Float = 0.5f
    }
}
