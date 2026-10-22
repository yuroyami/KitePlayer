package io.github.yuroyami.kiteplayer.audioviz.viz

import io.github.yuroyami.kiteplayer.audioviz.PowerSpectrum
import io.github.yuroyami.kiteplayer.audioviz.SpectrumFrame
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
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
 * which frequency each bin stands for.
 *
 * How a bin is rebuilt: the frame's power per bin, divided by the frame's bin width, is a power
 * density. Each page bin adds that density up under the page's own bin shape, the power response of
 * the periodic Blackman window Chrome applies, so a page bin gathers what a browser's bin gathers
 * whether it is narrower or wider than the frame's. The scale is Chrome's: a full-scale sine at a
 * bin's centre reads -7.5 dB. Chrome smooths the magnitudes of single snapshots, and the mean
 * magnitude of a noisy bin is about 0.886 of its root mean square. The frame's bins scatter the same
 * way on their own when only one or two of them fill a page bin, so the factor shrinks towards 1 as
 * fewer frame bins contribute. Bytes are truncated, as Chrome truncates them.
 *
 * Accuracy, against Chrome's own algorithm on the same audio, as the mean error of a bin: within
 * 1 dB on two songs for an FFT of 256 to 4096 points, and on a quiet third song 2.7 dB high at 256
 * and within 0.6 dB above that. Reading one point of the frame's spectrum instead was 9 to 13 dB
 * low at 256 and 2 to 3 dB high at 4096. Limits:
 * - A steady tone reads low where the page's bins are as narrow as the frame's own spread: about
 *   1 dB at 256, 2 dB at 2048 and 3 dB at 4096.
 * - A burst shorter than the frame's 43 ms window, such as a hat that dies in 10 ms, reads up to
 *   3 dB high, because a browser's short snapshot often falls in the gap after it.
 * - The frame's power averages the channels where the browser analyses their mono mix, so a wide
 *   stereo mix reads up to 3 dB louder here.
 *
 * Call [update] once per page frame, or [read] at a page's own timer. The arrays are reused; read
 * them after a call and do not keep them.
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
        require(sampleRate > 0)
    }

    /** How many frequency bins there are: half the FFT size, as in the browser. */
    val binCount: Int = fftSize / 2

    /** The smoothed magnitude of each bin, at Chrome's scale. */
    private val magnitude = DoubleArray(binCount)

    /** `getByteFrequencyData`: 0 to 255 per bin, after smoothing and the decibel mapping. */
    val frequencyBytes: IntArray = IntArray(binCount)

    /** `getFloatFrequencyData`: decibels per bin, after smoothing. */
    val frequencyDecibels: FloatArray = FloatArray(binCount) { minDecibels }

    private var lastPts = Long.MIN_VALUE
    private var lastRevision = Long.MIN_VALUE
    private var warmed = false

    /** The bin shape, |W|² / N², at [SHAPE_STEPS] points per bin from the centre out to [REACH] bins. Built on first use. */
    private var shape: DoubleArray? = null

    // Which of the frame's bins each page bin adds up, with what weight and which scatter factor.
    // Rebuilt when the frame's layout changes.
    private var layoutFft = -1
    private var layoutRate = -1
    private val firstSource = IntArray(binCount)
    private val weights = Array(binCount) { DoubleArray(0) }
    private val scatter = DoubleArray(binCount)

    /**
     * One reading for a page that reads once per frame. The same frame read twice is smoothed once,
     * so a paused player holds its picture rather than fading it. The first reading is taken whole,
     * so a drawing shown part way through a song starts at the song's level.
     */
    fun update(frame: SpectrumFrame) {
        if (frame.ptsMicros == lastPts && frame.analysisRevision == lastRevision && warmed) return
        listen(frame, if (warmed) smoothing.toDouble() else 0.0)
    }

    /**
     * One call of `getByteFrequencyData`, for a page that reads on its own timer: it smooths once
     * against the previous call, whatever the frame, and the row starts at zero, as in the browser.
     */
    fun read(frame: SpectrumFrame) {
        listen(frame, smoothing.toDouble())
    }

    private fun listen(frame: SpectrumFrame, keep: Double) {
        lastPts = frame.ptsMicros
        lastRevision = frame.analysisRevision
        warmed = true
        val power = frame.power
        if (power == null) {
            for (bin in 0 until binCount) magnitude[bin] *= keep
        } else {
            layout(power)
            for (bin in 0 until binCount) {
                val weight = weights[bin]
                val first = firstSource[bin]
                var sum = 0.0
                for (offset in weight.indices) sum += power.binMeanSquare(first + offset) * weight[offset]
                // A sine of amplitude A has a mean square of A² / 2, and Chrome reads it as A |W| / N.
                val now = sqrt(2.0 * sum) * scatter[bin]
                magnitude[bin] = keep * magnitude[bin] + (1.0 - keep) * now
            }
        }
        val span = (maxDecibels - minDecibels).toDouble()
        for (bin in 0 until binCount) {
            val value = magnitude[bin]
            if (value <= 0.0) {
                frequencyDecibels[bin] = minDecibels
                frequencyBytes[bin] = 0
            } else {
                val decibels = 20.0 * log10(value)
                frequencyDecibels[bin] = decibels.toFloat()
                frequencyBytes[bin] = (255.0 * (decibels - minDecibels) / span).coerceIn(0.0, 255.0).toInt()
            }
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
        magnitude.fill(0.0)
        frequencyBytes.fill(0)
        frequencyDecibels.fill(minDecibels)
        lastPts = Long.MIN_VALUE
        lastRevision = Long.MIN_VALUE
        warmed = false
    }

    private fun layout(power: PowerSpectrum) {
        val sourceFft = power.fftSize
        val sourceRate = power.window.sampleRate
        if (sourceFft == layoutFft && sourceRate == layoutRate) return
        layoutFft = sourceFft
        layoutRate = sourceRate
        val table = shape ?: binShape().also { shape = it }
        // The width of a frame bin, in page bins.
        val ratio = (sourceRate.toDouble() / sourceFft) / (sampleRate.toDouble() / fftSize)
        // The page hears nothing at or above its own Nyquist frequency.
        var lastSource = power.binCount - 1
        while (lastSource >= 0 && lastSource * ratio >= binCount) lastSource--
        // A frame bin stands for a band of density, so the shape is averaged over its width.
        val samples = maxOf(1, ceil(ratio * CELL_SAMPLES).toInt())
        for (bin in 0 until binCount) {
            val low = maxOf(0, ceil((bin - REACH) / ratio - 0.5).toInt())
            val high = minOf(lastSource, floor((bin + REACH) / ratio + 0.5).toInt())
            firstSource[bin] = low
            val weight = DoubleArray(maxOf(0, high - low + 1))
            var total = 0.0
            var squares = 0.0
            for (offset in weight.indices) {
                val centre = (low + offset) * ratio
                var sum = 0.0
                for (sample in 0 until samples) {
                    val at = centre + ratio * ((sample + 0.5) / samples - 0.5)
                    // Both images of a real sound reach a bin: the one at +f and, near 0 and Nyquist, the one at -f.
                    sum += shapeAt(table, at - bin) + shapeAt(table, at + bin)
                }
                weight[offset] = sum / samples
                total += weight[offset]
                squares += weight[offset] * weight[offset]
            }
            weights[bin] = weight
            // How many frame bins fill this page bin, counted by weight.
            val filling = if (squares > 0.0) total * total / squares else 1.0
            scatter[bin] = MEAN_OVER_RMS / meanOverRms(filling)
        }
    }

    /** The power response of Chrome's periodic Blackman window, by a turning phasor at each offset. */
    private fun binShape(): DoubleArray {
        val window = DoubleArray(fftSize) { n ->
            val x = n.toDouble() / fftSize
            0.42 - 0.5 * cos(2.0 * PI * x) + 0.08 * cos(4.0 * PI * x)
        }
        val table = DoubleArray(REACH * SHAPE_STEPS + 2)
        for (index in table.indices) {
            val turn = 2.0 * PI * index / SHAPE_STEPS / fftSize
            val c = cos(turn)
            val s = sin(turn)
            var phaseRe = 1.0
            var phaseIm = 0.0
            var re = 0.0
            var im = 0.0
            for (n in 0 until fftSize) {
                re += window[n] * phaseRe
                im += window[n] * phaseIm
                val next = phaseRe * c + phaseIm * s
                phaseIm = phaseIm * c - phaseRe * s
                phaseRe = next
            }
            table[index] = (re * re + im * im) / (fftSize.toDouble() * fftSize)
        }
        return table
    }

    /** The bin shape [bins] away from a bin's centre. It repeats every [fftSize] bins and is even. */
    private fun shapeAt(table: DoubleArray, bins: Double): Double {
        var at = bins % fftSize
        if (at < 0.0) at += fftSize
        if (at > fftSize / 2.0) at = fftSize - at
        if (at >= REACH) return 0.0
        val scaled = at * SHAPE_STEPS
        val lower = scaled.toInt()
        val mix = scaled - lower
        return table[lower] + (table[lower + 1] - table[lower]) * mix
    }

    private companion object {
        /** How far a bin's shape is followed, in bins. The Blackman main lobe ends at 3; beyond is -58 dB. */
        const val REACH = 4

        const val SHAPE_STEPS = 64

        /** Samples of the shape per page bin of a frame bin's width, when the shape is averaged over it. */
        const val CELL_SAMPLES = 16

        /** The mean over the root mean square of one noisy bin's magnitude: the square root of pi, over 2. */
        const val MEAN_OVER_RMS = 0.886226925452758

        /**
         * The mean over the root mean square of the magnitude of [count] noisy bins added together,
         * Γ(n + ½) / (Γ(n) √n), by its series. 0.886 for one bin, and towards 1 for many.
         */
        fun meanOverRms(count: Double): Double {
            val n = maxOf(count, 1.0)
            return 1.0 - 1.0 / (8.0 * n) + 1.0 / (128.0 * n * n) + 5.0 / (1024.0 * n * n * n) - 21.0 / (32768.0 * n * n * n * n)
        }
    }
}
