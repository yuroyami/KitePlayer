package io.github.yuroyami.kiteplayer.audioviz.viz

import io.github.yuroyami.kiteplayer.audioviz.SpectrumFrame
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The readings a browser's `AnalyserNode` gives, rebuilt from a [SpectrumFrame].
 *
 * The drawings ported from web pages were written against `getByteFrequencyData` and
 * `getByteTimeDomainData`: a row of linear frequency bins from 0 Hz to half the sample rate,
 * smoothed over time and mapped from a decibel range onto 0 to 255, and a row of the latest
 * samples mapped onto 0 to 255 with silence at 128. Their look depends on that layout, on the
 * smoothing and on the decibel range, so a port reads this instead of the library's own bands and
 * keeps the original's numbers.
 *
 * [fftSize], [smoothing], [minDecibels] and [maxDecibels] take the page's values (the browser
 * defaults are 2048, 0.8, -100 and -30). [sampleRate] is the rate the page assumed, which sets
 * which frequency each bin stands for; the frame's own rate and window are read and resampled.
 * The frame's power spectrum is mean-square power per bin from a Hann window; the browser reports
 * a Blackman-windowed magnitude, so the decibel offset here is a judgement that puts a full-scale
 * sine near where Chrome puts it, about -6 dB, rather than a measured match.
 *
 * The arrays are reused; read them after [update] and do not keep them.
 */
internal class WebAudioAnalyser(
    val fftSize: Int = 2048,
    val smoothing: Float = 0.8f,
    val minDecibels: Float = -100f,
    val maxDecibels: Float = -30f,
    val sampleRate: Int = 44_100,
) {
    init {
        require(fftSize >= 32 && fftSize and (fftSize - 1) == 0) { "fftSize must be a power of two, was $fftSize" }
        require(smoothing in 0f..1f) { "smoothing must be 0..1" }
        require(maxDecibels > minDecibels)
    }

    /** How many frequency bins there are: half the FFT size, as in the browser. */
    val binCount: Int = fftSize / 2

    /** The smoothed magnitude spectrum on the page's bin layout. */
    private val magnitude = FloatArray(binCount)

    /** `getByteFrequencyData`: 0 to 255 per bin, after smoothing and the decibel mapping. */
    val frequencyBytes: IntArray = IntArray(binCount)

    /** `getFloatFrequencyData`: decibels per bin, after smoothing. */
    val frequencyDecibels: FloatArray = FloatArray(binCount) { minDecibels }

    private var lastPts = Long.MIN_VALUE
    private var lastRevision = Long.MIN_VALUE
    private var warmed = false

    /**
     * Reads [frame] and refreshes the frequency rows. The same frame read twice is smoothed
     * once, so a paused player holds its picture rather than fading it.
     */
    fun update(frame: SpectrumFrame) {
        if (frame.ptsMicros == lastPts && frame.analysisRevision == lastRevision && warmed) return
        lastPts = frame.ptsMicros
        lastRevision = frame.analysisRevision
        val power = frame.power
        val keep = if (warmed) smoothing else 0f
        warmed = true
        if (power == null) {
            for (bin in 0 until binCount) magnitude[bin] *= keep
        } else {
            val sourceBins = power.binCount
            val sourceRate = power.window.sampleRate
            // Where each of the page's bins sits in the frame's own bins: both are linear in
            // frequency from zero, so the map is one ratio.
            val ratio = (sampleRate.toFloat() / fftSize) / (sourceRate.toFloat() / power.fftSize)
            for (bin in 0 until binCount) {
                val at = bin * ratio
                val lower = floor(at).toInt()
                val mix = at - lower
                val a = if (lower < sourceBins) power.binMeanSquare(lower) else 0f
                val b = if (lower + 1 < sourceBins) power.binMeanSquare(lower + 1) else 0f
                val magnitudeNow = sqrt((a + (b - a) * mix).coerceAtLeast(0f))
                magnitude[bin] = keep * magnitude[bin] + (1f - keep) * magnitudeNow
            }
        }
        val span = maxDecibels - minDecibels
        for (bin in 0 until binCount) {
            val decibels = if (magnitude[bin] <= 0f) minDecibels else 20f * log10(magnitude[bin]) + CALIBRATION_DB
            frequencyDecibels[bin] = decibels
            frequencyBytes[bin] = ((decibels - minDecibels) / span * 255f).roundToInt().coerceIn(0, 255)
        }
    }

    /**
     * `getByteTimeDomainData` into [out]: the frame's waveform resampled to the row's length,
     * with silence at 128, as the browser reports it. Left and right traces can be asked for
     * through [channel]: 0 for the mono mix, 1 for left, 2 for right. The trace is the file's own
     * amplitude, as a browser reads it; a port that wants the library's shared display gain
     * multiplies by `frame.waveformGain` itself.
     */
    fun timeDomainBytes(frame: SpectrumFrame, out: IntArray, channel: Int = 0) {
        val trace = when (channel) { 1 -> frame.scopeLeft; 2 -> frame.scopeRight; else -> frame.scope }
        resample(trace, out.size) { index, value ->
            out[index] = (128f + value * 127f).roundToInt().coerceIn(0, 255)
        }
    }

    /** `getFloatTimeDomainData` into [out]: the waveform between -1 and 1. */
    fun timeDomainFloats(frame: SpectrumFrame, out: FloatArray, channel: Int = 0) {
        val trace = when (channel) { 1 -> frame.scopeLeft; 2 -> frame.scopeRight; else -> frame.scope }
        resample(trace, out.size) { index, value -> out[index] = value }
    }

    private inline fun resample(trace: FloatArray, size: Int, write: (Int, Float) -> Unit) {
        if (trace.isEmpty()) {
            for (index in 0 until size) write(index, 0f)
            return
        }
        val step = (trace.size - 1).toFloat() / (size - 1).coerceAtLeast(1)
        for (index in 0 until size) {
            val at = index * step
            val lower = at.toInt().coerceIn(0, trace.size - 1)
            val upper = (lower + 1).coerceAtMost(trace.size - 1)
            val mix = at - lower
            val value = trace[lower] + (trace[upper] - trace[lower]) * mix
            write(index, value.coerceIn(-1f, 1f))
        }
    }

    fun reset() {
        magnitude.fill(0f)
        frequencyBytes.fill(0)
        frequencyDecibels.fill(minDecibels)
        lastPts = Long.MIN_VALUE
        lastRevision = Long.MIN_VALUE
        warmed = false
    }

    private companion object {
        /** Puts a full-scale sine near -6 dB, where a browser reports it. *Judgement.* */
        const val CALIBRATION_DB = -3f
    }
}
