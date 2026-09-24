package io.github.yuroyami.kiteplayer.audioviz.viz.shader

import io.github.yuroyami.kiteplayer.audioviz.Fft
import io.github.yuroyami.kiteplayer.audioviz.viz.StereoHistory
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The constant-Q transform of ShowCQTBar, re-implemented from its published formulas.
 *
 * Columns are spaced on a musical scale over exactly ten octaves, from E0 minus 50 cents to E10
 * minus 50 cents, so a semitone is [columns] / 120 columns wide. Each column is the average of two
 * transform bins. A bin is a four-term Nuttall window in the frequency domain, [kernelWidth] bins of
 * one [fftSize]-point FFT wide, with an alternating sign that centres its time window on the middle
 * of the FFT input. The two channels ride in one complex FFT, left as the real part and right as the
 * imaginary part, and each bin's sums at `k` and `N - k` pull them apart again.
 *
 * The input is [span] samples per channel, oldest first: half an FFT before the moment analysed and
 * [attackSize] samples (33 ms) after it, the last of which fade out under a falling half Nuttall
 * window. The rest of the FFT input stays zero.
 */
internal class ConstantQBars(val sampleRate: Int, val columns: Int) {

    /** A power of two of at least a third of a second: 16384 at 44.1 and 48 kHz. */
    val fftSize: Int = 1 shl ceil(ln(sampleRate * 0.33) / ln(2.0)).toInt()

    /** 33 ms of samples after the analysed moment. */
    val attackSize: Int = ceil(sampleRate * 0.033).toInt()

    /** Samples read per transform. */
    val span: Int = fftSize / 2 + attackSize

    /** Transform bins: two per column. */
    val binCount: Int = columns * 2

    private val attack = FloatArray(attackSize) { x ->
        nuttall(PI * x / (sampleRate * 0.033)).toFloat()
    }

    private val kernelStart = IntArray(binCount)
    private val kernelLength = IntArray(binCount)
    private val kernelOffset = IntArray(binCount)
    private val kernel: FloatArray

    private val fft = Fft(fftSize)
    private val real = FloatArray(fftSize)
    private val imaginary = FloatArray(fftSize)

    /** Squared left and right magnitude of each bin, from the last [transform]. */
    private val leftPower = FloatArray(binCount)
    private val rightPower = FloatArray(binCount)

    init {
        var total = 0
        for (bin in 0 until binCount) {
            val frequency = centreHz(bin, binCount)
            if (frequency >= 0.5 * sampleRate) continue
            val flen = kernelWidth(frequency)
            val centre = frequency * fftSize / sampleRate
            // Clamped to the positive half, so the mirrored bin N - k is always a different bin.
            val start = ceil(centre - 0.5 * flen).toInt().coerceAtLeast(1)
            val end = floor(centre + 0.5 * flen).toInt().coerceAtMost(fftSize / 2 - 1)
            if (end < start) continue
            kernelStart[bin] = start
            kernelLength[bin] = end - start + 1
            kernelOffset[bin] = total
            total += end - start + 1
        }
        kernel = FloatArray(total)
        for (bin in 0 until binCount) {
            if (kernelLength[bin] == 0) continue
            val frequency = centreHz(bin, binCount)
            val flen = kernelWidth(frequency)
            val centre = frequency * fftSize / sampleRate
            var at = kernelOffset[bin]
            for (x in kernelStart[bin] until kernelStart[bin] + kernelLength[bin]) {
                val sign = if (x and 1 == 1) -1.0 else 1.0
                val y = 2.0 * PI * (x - centre) / flen
                kernel[at++] = (nuttall(y) * sign / fftSize).toFloat()
            }
        }
    }

    /** Coefficients held, for a cost estimate. */
    val kernelSize: Int get() = kernel.size

    /** The width of a bin's kernel in FFT bins: eight over its time window. */
    fun kernelWidth(frequency: Double): Double = 8.0 * fftSize / (timeWindow(frequency) * sampleRate)

    /**
     * Transforms [left] and [right] ([span] samples each, oldest first, times [gain]) and writes one
     * column per four floats of [out]: red, green and blue from 0 to 1, then the bar height, where 1
     * is the whole bar area. [barVolume] and [colourVolume] are the page's bar and brightness knobs
     * as linear factors.
     */
    fun transform(left: FloatArray, right: FloatArray, gain: Float, barVolume: Float, colourVolume: Float, out: FloatArray) {
        val half = fftSize / 2
        for (index in 0 until span) {
            val fade = if (index < half) gain else gain * attack[index - half]
            real[index] = left[index] * fade
            imaginary[index] = right[index] * fade
        }
        real.fill(0f, span, fftSize)
        imaginary.fill(0f, span, fftSize)
        fft.forward(real, imaginary)
        for (bin in 0 until binCount) {
            val length = kernelLength[bin]
            if (length == 0) {
                leftPower[bin] = 0f
                rightPower[bin] = 0f
                continue
            }
            val start = kernelStart[bin]
            var at = kernelOffset[bin]
            var aRe = 0f
            var aIm = 0f
            var bRe = 0f
            var bIm = 0f
            for (k in start until start + length) {
                val weight = kernel[at++]
                val mirror = fftSize - k
                aRe += weight * real[k]
                aIm += weight * imaginary[k]
                bRe += weight * real[mirror]
                bIm += weight * imaginary[mirror]
            }
            // Left and right apart, each doubled so a sine of amplitude A reads A.
            val leftRe = aRe + bRe
            val leftIm = aIm - bIm
            val rightRe = bIm + aIm
            val rightIm = bRe - aRe
            leftPower[bin] = leftRe * leftRe + leftIm * leftIm
            rightPower[bin] = rightRe * rightRe + rightIm * rightIm
        }
        for (column in 0 until columns) {
            var red = 0f
            var green = 0f
            var blue = 0f
            var height = 0f
            for (pair in 0 until 2) {
                val bin = column * 2 + pair
                val l = leftPower[bin]
                val r = rightPower[bin]
                val middle = 0.5f * (l + r)
                red += sqrt(colourVolume * sqrt(l))
                green += sqrt(colourVolume * sqrt(middle))
                blue += sqrt(colourVolume * sqrt(r))
                height += barVolume * sqrt(middle)
            }
            val at = column * 4
            out[at] = (0.5f * red).coerceIn(0f, 1f)
            out[at + 1] = (0.5f * green).coerceIn(0f, 1f)
            out[at + 2] = (0.5f * blue).coerceIn(0f, 1f)
            out[at + 3] = (0.5f * height).coerceAtLeast(0f)
        }
    }

    internal companion object {
        /** E0 minus 50 cents. */
        const val BASE_HZ = 20.01523126408007475

        /** E10 minus 50 cents. */
        const val END_HZ = 20495.59681441799654

        /** The centre frequency of bin [bin] of [bins] spread over the ten octaves. */
        fun centreHz(bin: Int, bins: Int): Double {
            val logBase = ln(BASE_HZ)
            return exp(logBase + (bin + 0.5) * (ln(END_HZ) - logBase) / bins)
        }

        /**
         * Seconds of audio a bin at [frequency] listens to: about 0.31 s at 20 Hz, 0.1 s at 1 kHz
         * and 16 ms at 20 kHz. The page's own time-length expression.
         */
        fun timeWindow(frequency: Double): Double =
            384.0 * 0.33 / (384.0 / 0.17 + 0.33 * frequency / (1.0 - 0.17)) +
                384.0 * 0.33 / (0.33 * frequency / 0.17 + 384.0 / (1.0 - 0.17))

        /** The four-term Nuttall window at phase [y], 1 at 0 and 0 at plus or minus pi. */
        fun nuttall(y: Double): Double =
            0.355768 + 0.487396 * cos(y) + 0.144232 * cos(2.0 * y) + 0.012604 * cos(3.0 * y)
    }
}

/**
 * A Web Audio `BiquadFilterNode` of type `peaking`, with the coefficients the Web Audio
 * specification gives, running in double precision as the browsers run it.
 */
internal class PeakingBiquad(sampleRate: Int, frequency: Double, q: Double, gainDecibels: Double) {
    private val b0: Double
    private val b1: Double
    private val b2: Double
    private val a1: Double
    private val a2: Double
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    init {
        val a = 10.0.pow(gainDecibels / 40.0)
        val w0 = 2.0 * PI * frequency / sampleRate
        val alpha = sin(w0) / (2.0 * q)
        val cosine = cos(w0)
        val a0 = 1.0 + alpha / a
        b0 = (1.0 + alpha * a) / a0
        b1 = -2.0 * cosine / a0
        b2 = (1.0 - alpha * a) / a0
        a1 = -2.0 * cosine / a0
        a2 = (1.0 - alpha / a) / a0
    }

    fun process(sample: Float): Float {
        val x = sample.toDouble()
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1
        x1 = x
        y2 = y1
        y1 = y
        return y.toFloat()
    }

    fun reset() {
        x1 = 0.0
        x2 = 0.0
        y1 = 0.0
        y2 = 0.0
    }
}

/**
 * The page's audio graph between the source and its two analysers: the stereo pair through the
 * bass cut, a peaking filter at 10 Hz with a Q of 0.33 and -30 dB, run on the stream so its state
 * carries from one read to the next, as a browser runs it. [latest] holds the newest [span] samples.
 *
 * A jump of the read position, a new analysis revision or a gap longer than the window restarts
 * the filter [LEAD_SECONDS] earlier, long enough for its slowest part (a time constant of about a
 * quarter of a second) to settle.
 */
internal class BassCutStream(private val sampleRate: Int, val span: Int) {
    private val leftCut = PeakingBiquad(sampleRate, CUT_HZ, CUT_Q, CUT_DECIBELS)
    private val rightCut = PeakingBiquad(sampleRate, CUT_HZ, CUT_Q, CUT_DECIBELS)
    private val ringLeft = FloatArray(span)
    private val ringRight = FloatArray(span)
    private var ringAt = 0
    private val chunkLeft = FloatArray(CHUNK)
    private val chunkRight = FloatArray(CHUNK)
    private var end = Long.MIN_VALUE
    private var revision = Long.MIN_VALUE
    private var history: StereoHistory? = null

    /** Brings the filtered stream up to, not including, sample [until] of [source]'s audio of [revision]. */
    fun advanceTo(source: StereoHistory, revision: Long, until: Long) {
        val continuous = source === history && revision == this.revision && end != Long.MIN_VALUE &&
            until >= end && until - end <= span
        var from = end
        if (!continuous) {
            leftCut.reset()
            rightCut.reset()
            ringLeft.fill(0f)
            ringRight.fill(0f)
            ringAt = 0
            from = until - span - (LEAD_SECONDS * sampleRate).toLong()
        }
        while (from < until) {
            val count = minOf(CHUNK.toLong(), until - from).toInt()
            source.read(revision, from, chunkLeft, chunkRight, 0, count)
            for (index in 0 until count) {
                ringLeft[ringAt] = leftCut.process(chunkLeft[index])
                ringRight[ringAt] = rightCut.process(chunkRight[index])
                if (++ringAt == span) ringAt = 0
            }
            from += count
        }
        end = until
        this.revision = revision
        history = source
    }

    /** Copies the newest [span] filtered samples, oldest first. */
    fun latest(left: FloatArray, right: FloatArray) {
        val older = span - ringAt
        ringLeft.copyInto(left, 0, ringAt, span)
        ringRight.copyInto(right, 0, ringAt, span)
        ringLeft.copyInto(left, older, 0, ringAt)
        ringRight.copyInto(right, older, 0, ringAt)
    }

    fun reset() {
        leftCut.reset()
        rightCut.reset()
        ringLeft.fill(0f)
        ringRight.fill(0f)
        ringAt = 0
        end = Long.MIN_VALUE
        revision = Long.MIN_VALUE
        history = null
    }

    internal companion object {
        const val CUT_HZ = 10.0
        const val CUT_Q = 0.33
        const val CUT_DECIBELS = -30.0

        /** Seconds the filter runs before the window after a restart: five of its time constants. */
        const val LEAD_SECONDS = 1.25f

        private const val CHUNK = 4096
    }
}
