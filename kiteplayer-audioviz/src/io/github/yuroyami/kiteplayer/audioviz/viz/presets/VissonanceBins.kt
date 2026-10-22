package io.github.yuroyami.kiteplayer.audioviz.viz.presets

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow

/**
 * `Spectrum.GetVisualBins` from Vissonance by Tariq Soliman (MIT, https://github.com/tariqksoliman/Vissonance),
 * the bar mapping that every Vissonance visualiser shares, ported line by line.
 *
 * It turns one row of `getByteFrequencyData` bytes into [count] bars in four steps:
 * 1. Bar `i` starts at bin `round((i / count)^2.55 * (end - start) + start)`. A bar that would not start
 *    past the bar before starts one bin after it, so the first bars take one bin each.
 * 2. Each bar takes the loudest bin of its range. The range of the last bar runs to [end].
 * 3. Each bar averages its peak with the peak of the bar before. Bar 0 uses bin [start] instead.
 * 4. Each value goes through the gate `255 * (v / 255)^e`, with `e` from 5 at bar 0 to 3 at the last
 *    bar, and is floored at 1. Only loud bins get through the gate, and the bass side least of all.
 *
 * A bin outside the row reads as the page's `undefined`: it never wins a comparison, and an average
 * with it is NaN, which the page turns into 0. So a [start] below zero makes bar 0 the floor of 1.
 * The layout depends only on [count], [start] and [end], so it is worked out once.
 */
internal class VissonanceBins(val count: Int, val start: Int, val end: Int) {

    /** `SamplePoints`: the first bin of each bar. */
    val firstBins: IntArray = IntArray(count)

    /** `MaxSamplePoints`: the loudest bin of each bar, from the last call. */
    private val peakBins = IntArray(count)

    init {
        require(count > 0) { "count must be positive, was $count" }
        var lastSpot = 0
        for (i in 0 until count) {
            var bin = jsRound((i.toDouble() / count).pow(EASE_POWER) * (end - start) + start)
            if (bin <= lastSpot) bin = lastSpot + 1
            lastSpot = bin
            firstBins[i] = bin
        }
    }

    /** Fills the first [count] entries of [out] with the bars for [bytes], one row of 0 to 255 values. */
    fun visualBins(bytes: IntArray, out: FloatArray) {
        for (i in 0 until count) {
            val first = firstBins[i]
            val next = if (i + 1 < count) firstBins[i + 1] else end
            var peak = byteAt(bytes, first)
            var peakBin = first
            for (bin in first + 1 until next) {
                // A comparison with NaN is false either way round, as it is with `undefined` in the page.
                val value = byteAt(bytes, bin)
                if (value > peak) {
                    peak = value
                    peakBin = bin
                }
            }
            peakBins[i] = peakBin
        }
        for (i in 0 until count) {
            val before = if (i == 0) start else peakBins[i - 1]
            var average = (byteAt(bytes, before) + byteAt(bytes, peakBins[i])) / 2.0
            if (average.isNaN()) average = 0.0
            out[i] = max((average / HEIGHT).pow(exponent(i)) * HEIGHT, 1.0).toFloat()
        }
    }

    /** The gate's power for bar [index]: 5 at bar 0, falling in a straight line toward 3. */
    fun exponent(index: Int): Double = MAX_EXPONENT + (MIN_EXPONENT - MAX_EXPONENT) * (index.toDouble() / count)

    internal companion object {
        /** `SpectrumEase`: the power that spaces the bars' first bins. */
        const val EASE_POWER = 2.55

        /** `spectrumMaxExponent` and `spectrumMinExponent`: the gate's power at the first and the last bar. */
        const val MAX_EXPONENT = 5.0
        const val MIN_EXPONENT = 3.0

        /** `spectrumHeight`: the top of the byte range. */
        const val HEIGHT = 255.0

        /** `getLoudness`: the plain mean of every byte in the row. */
        fun loudness(bytes: IntArray): Float {
            if (bytes.isEmpty()) return 0f
            var sum = 0L
            for (value in bytes) sum += value
            return (sum.toDouble() / bytes.size).toFloat()
        }

        /** A byte of the row, or NaN for a bin outside it, as the page's `undefined` behaves in arithmetic. */
        private fun byteAt(bytes: IntArray, bin: Int): Double =
            if (bin >= 0 && bin < bytes.size) bytes[bin].toDouble() else Double.NaN

        /** JavaScript's `Math.round`: halves go up. */
        private fun jsRound(value: Double): Int = floor(value + 0.5).toInt()
    }
}
