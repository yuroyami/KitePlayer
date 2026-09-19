package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.ChannelLayout
import kotlin.math.atan
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.tan

/** Streaming ungated 400 ms K-weighted power. Only the analysis worker owns this state. */
internal class ProgrammePower(format: AudioFormat) {
    private val weights = programmeWeights(format)
    val supported = format.sampleRate in 8_000..768_000 && weights != null
    val windowSamples = (format.sampleRate * 0.4).roundToInt()
    private val ring = DoubleArray(if (supported) windowSamples else 0)
    private val filters = if (supported) Array(format.channels) { KWeighting(format.sampleRate) } else emptyArray()
    private var index = 0
    private var seen = 0L
    private var lastNonzero = Long.MIN_VALUE
    private var sum = 0.0
    private var cycleSum = 0.0
    private var framePower = 0.0
    private var frameNonzero = false
    val ready: Boolean get() = supported && seen >= windowSamples
    val meanSquare: Double? get() = if (ready) (sum / windowSamples).coerceAtLeast(0.0) else null
    val digitalSilence: Boolean get() = ready && (lastNonzero == Long.MIN_VALUE || seen - lastNonzero > windowSamples)

    fun addChannel(channel: Int, sample: Float) {
        if (!supported) return
        if (sample != 0f) frameNonzero = true
        val weight = checkNotNull(weights)[channel]
        if (weight == 0.0) return
        val filtered = filters[channel].process(sample.toDouble())
        framePower += filtered * filtered * weight
    }

    fun endFrame() {
        if (!supported) return
        if (frameNonzero) lastNonzero = seen
        sum += framePower - ring[index]
        ring[index] = framePower
        cycleSum += framePower
        if (++index == windowSamples) {
            // Rebase once per rotation using work already accumulated sample by sample. This
            // bounds subtraction drift without a periodic O(window) pause on the worker.
            index = 0
            sum = cycleSum
            cycleSum = 0.0
        }
        seen++
        framePower = 0.0
        frameNonzero = false
    }
}

/** Native channel order, with conventional mono/stereo and named-layout fallbacks. */
internal fun programmeWeights(format: AudioFormat): DoubleArray? {
    val mask = format.channelLayoutMask ?: when (format.channelLayout) {
        ChannelLayout.Mono -> 0x4L
        ChannelLayout.Stereo -> 0x3L
        ChannelLayout.Quad -> 0x33L
        ChannelLayout.Surround51 -> 0x60fL
        ChannelLayout.Surround71 -> 0x63fL
        ChannelLayout.Unknown -> when (format.channels) { 1 -> 0x4L; 2 -> 0x3L; else -> return null }
    }
    // The conventional native speaker mask through top-back-right. Higher/custom/ambisonic
    // labels require explicit positional metadata that AudioFormat does not currently provide.
    if (mask <= 0 || mask and 0x3ffffL != mask || mask.countOneBits() != format.channels) return null
    val weights = DoubleArray(format.channels)
    var channel = 0
    for (bit in 0..17) {
        if (mask and (1L shl bit) == 0L) continue
        weights[channel++] = when (bit) {
            3 -> 0.0 // LFE
            9, 10 -> 1.41 // Side +/-90 or +/-110 degrees.
            4, 5 -> if (mask == 0x33L || mask == 0x3fL) 1.41 else 1.0
            else -> 1.0 // Front, rear centre and elevated speakers, BS.1770-5 Annex 3.
        }
    }
    return weights
}

/** Two transposed-direct-form biquads from BS.1770's 48 kHz reference coefficients. */
private class KWeighting(sampleRate: Int) {
    private val shelf = Biquad(retime(
        doubleArrayOf(1.53512485958697, -2.69169618940638, 1.19839281085285),
        doubleArrayOf(1.0, -1.69065929318241, 0.73248077421585), sampleRate, false,
    ))
    private val highPass = Biquad(retime(
        doubleArrayOf(1.0, -2.0, 1.0),
        doubleArrayOf(1.0, -1.99004745483398, 0.99007225036621), sampleRate, true,
    ))

    fun process(sample: Double): Double = highPass.process(shelf.process(sample))

    private class Biquad(private val c: DoubleArray) {
        private var z1 = 0.0
        private var z2 = 0.0
        fun process(x: Double): Double {
            val y = c[0] * x + z1
            z1 = c[1] * x - c[3] * y + z2
            z2 = c[2] * x - c[4] * y
            return y
        }
    }
}

/**
 * Invert the reference bilinear transform, prewarp its pole frequency, then transform at the
 * requested rate. RLB keeps its unit [1 -2 1] numerator convention at every rate. The 48 kHz
 * case returns the published coefficients exactly. Independently checked against FFmpeg tones.
 */
private fun retime(b: DoubleArray, a: DoubleArray, rate: Int, rlb: Boolean): DoubleArray {
    if (rate == 48_000) return doubleArrayOf(b[0], b[1], b[2], a[1], a[2])
    fun inverse(c: DoubleArray) = doubleArrayOf(c[0] + c[1] + c[2], 2 * (c[0] - c[2]), c[0] - c[1] + c[2])
    val denominator = inverse(a)
    val pole = atan(sqrt(denominator[0] / denominator[2]))
    val ratio = tan(pole) / tan(pole * 48_000 / rate)
    fun forward(c: DoubleArray) = doubleArrayOf(
        c[0] + c[1] * ratio + c[2] * ratio * ratio,
        2 * (c[0] - c[2] * ratio * ratio),
        c[0] - c[1] * ratio + c[2] * ratio * ratio,
    )
    val d = forward(denominator)
    val n = forward(inverse(b))
    return doubleArrayOf(
        if (rlb) 1.0 else n[0] / d[0],
        if (rlb) -2.0 else n[1] / d[0],
        if (rlb) 1.0 else n[2] / d[0],
        d[1] / d[0], d[2] / d[0],
    )
}
