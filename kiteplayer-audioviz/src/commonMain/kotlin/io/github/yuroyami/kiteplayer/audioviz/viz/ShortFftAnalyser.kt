package io.github.yuroyami.kiteplayer.audioviz.viz

import io.github.yuroyami.kiteplayer.audioviz.PowerSpectrum
import io.github.yuroyami.kiteplayer.audioviz.SpectrumFrame
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * `getByteFrequencyData` of a browser `AnalyserNode` with a short FFT, rebuilt from a [SpectrumFrame].
 *
 * [WebAudioAnalyser] reads each of the page's bins at one point of the frame's spectrum. That is
 * right while the page's bins are about as narrow as the frame's. A page that asks for 128 bins runs
 * a 256-point FFT, and its bins are seven to eight times wider than the frame's. The browser gathers
 * all the power under a bin's window response, so one point misses most of a broadband sound: 30 to
 * 60 bytes a bin on real songs. This analyser adds up the frame's power under the page's bin shape
 * instead. Use it for an [fftSize] of 512 or less.
 *
 * The bin shape is the power response of the window Chrome applies, a periodic Blackman window of
 * [fftSize] points, at Chrome's scale: a full-scale sine at a bin's centre reads -7.5 dB. Chrome
 * smooths the magnitudes of short snapshots, and the mean magnitude of a noisy band is about 0.886 of
 * its root mean square, so the frame's steadier measure is scaled by that. Bytes are truncated, as
 * Chrome truncates them.
 *
 * Accuracy, against a direct 256-point analysis of the same audio: the sum of the lowest 80 bytes
 * averaged within 2 percent of the browser's on two real songs. A single reading was off by 1 to 3
 * percent on average on three songs, and by 13 percent on a quiet one. A steady tone reads about
 * 1 dB low. A burst shorter than the frame's window, such as a hat that dies in 10 ms, reads up to
 * 3 dB high, because a 5 ms snapshot often falls in the gap after it.
 *
 * [fftSize], [smoothing], [minDecibels], [maxDecibels] and [sampleRate] take the page's values, as
 * for [WebAudioAnalyser]. The frame's power averages the channels where the browser analyses their
 * mono mix, so a wide stereo mix reads up to 3 dB louder here.
 *
 * [read] is one call of `getByteFrequencyData`: it smooths once against the previous read, whatever
 * the frame, so call it at the page's own read rate. The row starts at zero, as in the browser. The
 * arrays are reused; read them after [read] and do not keep them.
 */
internal class ShortFftAnalyser(
    val fftSize: Int = 256,
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

    /** `getByteFrequencyData`: 0 to 255 per bin, after smoothing and the decibel mapping. */
    val frequencyBytes: IntArray = IntArray(binCount)

    /** The smoothed magnitude of each bin, at Chrome's scale. */
    private val magnitude = DoubleArray(binCount)

    /** The bin shape, |W|² / N², at [SHAPE_STEPS] points per bin from the centre out to [REACH] bins. */
    private val shape = DoubleArray(REACH * SHAPE_STEPS + 2)

    // Which of the frame's bins each page bin adds up, and with what weight. Rebuilt when the frame's layout changes.
    private var layoutFft = -1
    private var layoutRate = -1
    private val firstSource = IntArray(binCount)
    private val weights = Array(binCount) { DoubleArray(0) }

    init {
        val window = DoubleArray(fftSize) { n ->
            val x = n.toDouble() / fftSize
            0.42 - 0.5 * cos(2.0 * PI * x) + 0.08 * cos(4.0 * PI * x)
        }
        for (index in shape.indices) {
            // The window's transform at an offset of index / SHAPE_STEPS bins, by a turning phasor.
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
            shape[index] = (re * re + im * im) / (fftSize.toDouble() * fftSize)
        }
    }

    /** One `getByteFrequencyData` call on [frame]. A frame without a spectrum reads as silence. */
    fun read(frame: SpectrumFrame) {
        val power = frame.power
        val keep = smoothing.toDouble()
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
                val now = sqrt(2.0 * sum) * MEAN_OVER_RMS
                magnitude[bin] = keep * magnitude[bin] + (1.0 - keep) * now
            }
        }
        val span = (maxDecibels - minDecibels).toDouble()
        for (bin in 0 until binCount) {
            val value = magnitude[bin]
            frequencyBytes[bin] = if (value <= 0.0) 0 else
                (255.0 * (20.0 * log10(value) - minDecibels) / span).coerceIn(0.0, 255.0).toInt()
        }
    }

    fun reset() {
        magnitude.fill(0.0)
        frequencyBytes.fill(0)
    }

    private fun layout(power: PowerSpectrum) {
        val sourceFft = power.fftSize
        val sourceRate = power.window.sampleRate
        if (sourceFft == layoutFft && sourceRate == layoutRate) return
        layoutFft = sourceFft
        layoutRate = sourceRate
        // Where a frame bin sits on the page's layout, in page bins.
        val ratio = (sourceRate.toDouble() / sourceFft) / (sampleRate.toDouble() / fftSize)
        // The page hears nothing at or above its own Nyquist frequency.
        var lastSource = power.binCount - 1
        while (lastSource >= 0 && lastSource * ratio >= binCount) lastSource--
        for (bin in 0 until binCount) {
            val low = maxOf(0, ceil((bin - REACH) / ratio).toInt())
            val high = minOf(lastSource, floor((bin + REACH) / ratio).toInt())
            firstSource[bin] = low
            // Both images of a real sound reach a bin: the one at +f and, near 0 and Nyquist, the one at -f.
            weights[bin] = DoubleArray(maxOf(0, high - low + 1)) { offset ->
                val at = (low + offset) * ratio
                shapeAt(at - bin) + shapeAt(at + bin)
            }
        }
    }

    /** The bin shape [bins] away from a bin's centre. It repeats every [fftSize] bins and is even. */
    private fun shapeAt(bins: Double): Double {
        var at = bins % fftSize
        if (at < 0.0) at += fftSize
        if (at > fftSize / 2.0) at = fftSize - at
        if (at >= REACH) return 0.0
        val scaled = at * SHAPE_STEPS
        val lower = scaled.toInt()
        val mix = scaled - lower
        return shape[lower] + (shape[lower + 1] - shape[lower]) * mix
    }

    private companion object {
        /** How far a bin's shape is followed, in bins. The Blackman main lobe ends at 3; beyond is -58 dB. */
        const val REACH = 4

        const val SHAPE_STEPS = 64

        /** The mean over the root mean square of a noisy band's magnitude: the square root of pi, over 2. */
        const val MEAN_OVER_RMS = 0.886226925452758
    }
}
