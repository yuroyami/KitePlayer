package io.github.yuroyami.kiteplayer.audioviz.viz.presets

import io.github.yuroyami.kiteplayer.audioviz.PowerSpectrum
import io.github.yuroyami.kiteplayer.audioviz.SpectrumFrame
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Frequency layout for the floating sheet in Jordan Machado's Fluctus experiment.
 * Reference: https://github.com/JordanMachado/fluctus/blob/master/app/Webgl.js
 *
 * This independent adapter keeps the reference's sequential RGB packing, fixed decibel range
 * and magnitude smoothing. It reads the existing immutable FFT powers, without another analyser
 * or a drawing-specific adaptive gain. The reference grid is 2048 points at 48 kHz, so changing
 * source sample rate or FFT size never moves a particular frequency to another surface cell.
 *
 * KitePlayer measures Hann-windowed channel powers; Web Audio uses a Blackman-windowed mono
 * signal. Coherent-gain correction matches steady tones, but cannot recreate different window
 * leakage or phase cancellation from powers alone. This is intentionally an adaptation of those
 * measurements, not a claim that the two analysers publish identical values.
 */
internal class FluctusSpectrum {
    val cells = FloatArray(12 * 12 * 3)
    var mean = 0f
        private set
    var highActivity = 0f
        private set

    private val magnitudes = FloatArray(REFERENCE_BINS)

    fun update(frame: SpectrumFrame, deltaSeconds: Float) {
        if (frame.held || !deltaSeconds.isFinite() || deltaSeconds <= 0f) return
        // .8 of the previous magnitude at 60 Hz, made independent of presentation cadence.
        val retain = exp(ln(0.8) * deltaSeconds * 60.0).toFloat()
        sample(frame, retain)
    }

    /** Populate a newly selected paused sheet from its held measurement, without advancing time. */
    fun seed(frame: SpectrumFrame) {
        sample(frame, retain = 0f)
    }

    private fun sample(frame: SpectrumFrame, retain: Float) {
        val power = frame.power
        val size = power?.fftSize ?: 0
        // P[k] = d[k] * |FFT(x*w)[k]|^2 / (N * sum(w^2)). A symmetric Hann
        // has sum(w^2)=3(N-1)/8 and sum(w)=(N-1)/2. First recover |FFT|/N,
        // then replace its coherent gain with Blackman's .42; d is applied per source bin.
        val amplitudeScale = if (size >= 4) {
            val hannMean = 0.5 * (size - 1) / size
            sqrt(0.375 * (size - 1) / size) * (0.42 / hannMean)
        } else 0.0
        var total = 0f
        var high = 0f
        for (index in magnitudes.indices) {
            val frequency = index * REFERENCE_SPACING
            val target = if (power != null && size >= 4 && power.window.sampleRate > 0) {
                magnitudeAt(power, frequency, amplitudeScale)
            } else {
                fallbackMagnitude(frame, index, frequency)
            }
            val value = magnitudes[index] * retain + target * (1f - retain)
            magnitudes[index] = value
            // getByteFrequencyData uses -100..-30 dB by default, then Fluctus divides by256.
            val code = if (value > 0f) {
                floor(255.0 * ((20.0 * log10(value.toDouble()) + 100.0) / 70.0)
                    .coerceIn(0.0, 1.0)).toFloat() / 256f
            } else 0f
            if (index < cells.size) cells[index] = code
            total += code
            if (index >= HIGH_START) high += code
        }
        mean = total / REFERENCE_BINS
        // The reference toggles when tailSum/4 >65. Its equivalent threshold here is260/854.
        highActivity = high / (REFERENCE_BINS - HIGH_START)
    }

    fun reset() {
        cells.fill(0f)
        magnitudes.fill(0f)
        mean = 0f
        highActivity = 0f
    }

    private fun magnitudeAt(power: PowerSpectrum, frequency: Double, scale: Double): Float {
        val nyquist = power.window.sampleRate * 0.5
        if (frequency > nyquist || power.binCount == 0) return 0f
        val position = frequency * power.fftSize / power.window.sampleRate
        val first = floor(position).toInt().coerceIn(0, power.binCount - 1)
        val second = minOf(first + 1, power.binCount - 1)
        val mix = (position - first).coerceIn(0.0, 1.0)
        val lower = nativeMagnitude(power, first, scale)
        val upper = nativeMagnitude(power, second, scale)
        return (lower + (upper - lower) * mix).toFloat()
    }

    private fun nativeMagnitude(power: PowerSpectrum, index: Int, scale: Double): Double {
        val value = power.binMeanSquare(index)
        if (!value.isFinite() || value <= 0f) return 0.0
        val folded = if (index == 0 || index == power.fftSize / 2) 1.0 else 2.0
        return sqrt(value / folded) * scale
    }

    private fun fallbackMagnitude(frame: SpectrumFrame, index: Int, frequency: Double): Float {
        // Synthetic catalogue frames may have display bands but no raw power. Their ERB heights
        // cannot reconstruct FFT detail. Place the available heights at their approximate source
        // frequencies instead of stretching the whole low-to-high spectrum into the first432 bins.
        if (index == 0 || frame.bands.isEmpty() || frequency > 16_000.0) return 0f
        val low = ln(1.0 + 0.00437 * 30.0)
        val high = ln(1.0 + 0.00437 * 16_000.0)
        val position = ((ln(1.0 + 0.00437 * frequency.coerceAtLeast(30.0)) - low) /
            (high - low) * frame.bands.size - 0.5).coerceIn(0.0, frame.bands.lastIndex.toDouble())
        val first = floor(position).toInt()
        val second = minOf(first + 1, frame.bands.lastIndex)
        fun safe(index: Int): Double = frame.bands[index].let {
            if (it.isFinite()) it.coerceIn(0f, 1f).toDouble() else 0.0
        }
        val lower = safe(first)
        val height = lower + (safe(second) - lower) * (position - first)
        return if (height <= 0.0) 0f else 10.0.pow((height * 70.0 - 100.0) / 20.0).toFloat()
    }

    private companion object {
        const val REFERENCE_BINS = 1024
        const val REFERENCE_SPACING = 48_000.0 / 2048.0
        const val HIGH_START = 170
    }
}
