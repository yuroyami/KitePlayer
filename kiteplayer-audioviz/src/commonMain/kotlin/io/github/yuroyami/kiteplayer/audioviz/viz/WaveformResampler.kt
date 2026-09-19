package io.github.yuroyami.kiteplayer.audioviz.viz

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Filtered trace sampling, with reusable coefficients and no retained audio. Downsampling uses
 * a centred Blackman-windowed sinc at 0.45 output-rate cutoff, with reflected boundaries.
 * Equal sizes copy; upsampling is linear; a single output point is the mean. This preserves a
 * band-limited trace, not individual peaks. A peak-preserving display needs a min/max envelope.
 */
internal class WaveformResampler {
    private var inputLength = 0
    private var outputLength = 0
    private var starts = IntArray(0)
    private var weights = emptyArray<FloatArray>()

    fun resample(input: FloatArray, output: FloatArray) {
        if (output.isEmpty()) return
        if (input.isEmpty()) { output.fill(0f); return }
        if (input.size == output.size) { input.copyInto(output); return }
        if (input.size == 1) { output.fill(input[0]); return }
        if (output.size == 1) { output[0] = input.sumOf { it.toDouble() }.div(input.size).toFloat(); return }
        if (output.size > input.size) {
            val step = (input.size - 1.0) / (output.size - 1)
            for (point in output.indices) {
                val at = point * step
                val first = at.toInt().coerceAtMost(input.lastIndex)
                val second = minOf(first + 1, input.lastIndex)
                output[point] = (input[first] + (input[second] - input[first]) * (at - first)).toFloat()
            }
            return
        }
        if (inputLength != input.size || outputLength != output.size) prepare(input.size, output.size)
        for (point in output.indices) {
            val coefficients = weights[point]
            val start = starts[point]
            var sum = 0.0
            if (start >= 0 && start + coefficients.size <= input.size) {
                for (tap in coefficients.indices) sum += input[start + tap] * coefficients[tap].toDouble()
            } else {
                val period = (input.size - 1) * 2
                for (tap in coefficients.indices) {
                    val folded = ((start + tap) % period + period) % period
                    val index = if (folded < input.size) folded else period - folded
                    sum += input[index] * coefficients[tap].toDouble()
                }
            }
            output[point] = sum.toFloat()
        }
    }

    private fun prepare(from: Int, to: Int) {
        inputLength = from
        outputLength = to
        val step = (from - 1.0) / (to - 1)
        val cutoff = 0.45 / step
        val radius = ceil(8 * step).toInt()
        starts = IntArray(to)
        weights = Array(to) { point ->
            val centre = point * step
            val start = floor(centre).toInt() - radius
            starts[point] = start
            val coefficients = DoubleArray(2 * radius + 2) { tap ->
                val distance = start + tap - centre
                if (abs(distance) > radius) 0.0 else {
                    val phase = 2 * PI * cutoff * distance
                    val sinc = if (abs(phase) < 1e-12) 1.0 else sin(phase) / phase
                    val window = 0.42 + 0.5 * cos(PI * distance / radius) + 0.08 * cos(2 * PI * distance / radius)
                    2 * cutoff * sinc * window
                }
            }
            val sum = coefficients.sum()
            FloatArray(coefficients.size) { (coefficients[it] / sum).toFloat() }
        }
    }
}
