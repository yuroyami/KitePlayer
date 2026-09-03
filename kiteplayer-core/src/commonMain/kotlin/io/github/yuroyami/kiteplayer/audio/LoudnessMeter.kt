package io.github.yuroyami.kiteplayer.audio

import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.tan

/** What a measurement found. [integratedLufs] is negative infinity when nothing was loud enough to count. */
public data class LoudnessResult(
    val integratedLufs: Double,
    /** The largest sample magnitude seen, where 1 is full scale. May exceed 1 for material that clips. */
    val samplePeak: Float,
    /** Blocks that survived both gates. Zero means the answer is negative infinity. */
    val blocksMeasured: Int,
)

/**
 * Integrated loudness, to ITU-R BS.1770-4, which is what EBU R128 and ReplayGain 2.0 both measure.
 *
 * Loudness is not amplitude. A bass-heavy track and a bright one at the same peak level sound
 * nothing alike, and the whole point of this standard is a number that agrees with what a listener
 * would say. It gets there in three steps: filter each channel the way a head and torso colour
 * sound, take the mean square over overlapping 400 ms blocks, and then throw away the blocks that
 * are too quiet to count.
 *
 * That last step is why a simple average is not good enough. A film with long silences would
 * measure far quieter than it sounds, so the standard gates twice: absolutely, dropping anything
 * below -70 LUFS, and then relatively, dropping anything more than 10 LU below the mean of what
 * survived the first gate. What is left is the loudness of the parts anyone was listening to.
 *
 * Feed interleaved float samples with [feed] and read [result] once at the end. The meter holds one
 * block of history and nothing else, so a long file costs no more memory than a short one.
 *
 * Channel weighting follows the standard: the front channels count once, the surrounds count 1.41
 * times because sound arriving from behind is heard as louder than the same sound in front, and the
 * LFE is not counted at all. Roles are taken from the channel COUNT, which is what an interleaved
 * buffer can tell us: 1 is mono, 2 is stereo, 6 is the usual 5.1 order with the LFE fourth.
 */
public class LoudnessMeter(
    private val sampleRate: Int,
    private val channels: Int,
) {
    init {
        require(sampleRate > 0) { "a loudness meter needs a real sample rate, was $sampleRate" }
        require(channels in 1..8) { "a loudness meter handles 1 to 8 channels, got $channels" }
    }

    private val weights = FloatArray(channels) { channel ->
        when {
            channels == 6 && channel == 3 -> 0.0f
            channels == 6 && channel >= 4 -> 1.41f
            channels == 8 && channel == 3 -> 0.0f
            channels == 8 && channel >= 4 -> 1.41f
            else -> 1.0f
        }
    }

    private val preFilter = BiquadCoefficients.preFilter(sampleRate)
    private val rlbFilter = BiquadCoefficients.rlb(sampleRate)

    /** Per channel: x1, x2, y1, y2 for each of the two filters. */
    private val preState = DoubleArray(channels * 4)
    private val rlbState = DoubleArray(channels * 4)

    private val blockFrames = (sampleRate * BLOCK_MILLIS / 1000).coerceAtLeast(1)
    private val stepFrames = (sampleRate * STEP_MILLIS / 1000).coerceAtLeast(1)

    /** Running sum of squares per channel for the block being filled. */
    private val blockSums = DoubleArray(channels)
    private var framesInStep = 0

    /** The last three steps' sums, so a 400 ms block is four steps: three remembered plus this one. */
    private val history = ArrayDeque<DoubleArray>()

    private val blockLoudness = ArrayList<Double>()
    private var peak = 0f
    private var read = false

    /** Feeds [frames] sample frames of interleaved [samples]. */
    public fun feed(samples: FloatArray, frames: Int) {
        check(!read) { "a loudness meter cannot be fed after its result has been read" }
        require(frames >= 0) { "frames must not be negative, was $frames" }
        require(frames * channels <= samples.size) {
            "$frames frames of $channels channels need ${frames * channels} samples, got ${samples.size}"
        }
        var base = 0
        for (frame in 0 until frames) {
            for (channel in 0 until channels) {
                val raw = samples[base + channel]
                val magnitude = if (raw < 0f) -raw else raw
                if (magnitude > peak) peak = magnitude
                val filtered = rlbFilter.step(preFilter.step(raw.toDouble(), preState, channel), rlbState, channel)
                blockSums[channel] += filtered * filtered
            }
            base += channels
            framesInStep++
            if (framesInStep == stepFrames) closeStep()
        }
    }

    /** The measurement. Reading it ends the meter; feeding it afterwards throws. */
    public fun result(): LoudnessResult {
        read = true
        if (blockLoudness.isEmpty()) {
            return LoudnessResult(Double.NEGATIVE_INFINITY, peak, 0)
        }
        // The absolute gate, then the relative one against the mean of what survived it. Both are
        // means of POWER and not of decibels, which is why each stage re-derives its own sum.
        val aboveAbsolute = blockLoudness.filter { it > ABSOLUTE_GATE_LUFS }
        if (aboveAbsolute.isEmpty()) {
            return LoudnessResult(Double.NEGATIVE_INFINITY, peak, 0)
        }
        val relativeThreshold = meanLoudness(aboveAbsolute) + RELATIVE_GATE_LU
        val counted = aboveAbsolute.filter { it > relativeThreshold }
        if (counted.isEmpty()) {
            return LoudnessResult(meanLoudness(aboveAbsolute), peak, aboveAbsolute.size)
        }
        return LoudnessResult(meanLoudness(counted), peak, counted.size)
    }

    /** The loudness of a set of blocks: the mean of their POWER, expressed in LUFS. */
    private fun meanLoudness(blocks: List<Double>): Double {
        var power = 0.0
        for (loudness in blocks) power += 10.0.pow((loudness + LOUDNESS_OFFSET) / 10.0)
        return -LOUDNESS_OFFSET + 10.0 * log10(power / blocks.size)
    }

    private fun closeStep() {
        history.addLast(blockSums.copyOf())
        blockSums.fill(0.0)
        framesInStep = 0
        if (history.size > BLOCK_STEPS) history.removeFirst()
        if (history.size < BLOCK_STEPS) return

        var weighted = 0.0
        for (channel in 0 until channels) {
            if (weights[channel] == 0f) continue
            var sum = 0.0
            for (step in history) sum += step[channel]
            weighted += weights[channel] * (sum / (stepFrames * BLOCK_STEPS))
        }
        if (weighted > 0.0) blockLoudness += -0.691 + 10.0 * log10(weighted)
    }

    private companion object {
        const val BLOCK_MILLIS = 400
        const val STEP_MILLIS = 100

        /** Four 100 ms steps make one 400 ms block, which is the standard's 75 percent overlap. */
        const val BLOCK_STEPS = 4

        const val ABSOLUTE_GATE_LUFS = -70.0
        const val RELATIVE_GATE_LU = -10.0

        /** The standard's -0.691 offset, carried through the power/decibel conversions. */
        const val LOUDNESS_OFFSET = 0.691
    }
}

/**
 * One biquad, in double precision because the RLB filter's poles sit very close to the unit circle
 * at low sample rates and a `Float` accumulates enough error there to move the answer.
 */
internal class BiquadCoefficients(
    private val b0: Double,
    private val b1: Double,
    private val b2: Double,
    private val a1: Double,
    private val a2: Double,
) {
    /** Direct form 1 over [state], four doubles per channel at `channel * 4`. */
    fun step(x: Double, state: DoubleArray, channel: Int): Double {
        val at = channel * 4
        val x1 = state[at]
        val x2 = state[at + 1]
        val y1 = state[at + 2]
        val y2 = state[at + 3]
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        state[at + 1] = x1
        state[at] = x
        state[at + 3] = y1
        state[at + 2] = y
        return y
    }

    companion object {
        /**
         * The head-and-torso shelf: a high shelf of about +4 dB near 1.7 kHz.
         *
         * The constants are the standard's own, and they are given at 48 kHz there. Deriving them
         * from the design formulas instead is what makes this correct at 44.1 and 96 too, which a
         * table of 48 kHz numbers would not be.
         */
        fun preFilter(sampleRate: Int): BiquadCoefficients {
            val f0 = 1681.974450955533
            val gainDb = 3.999843853973347
            val q = 0.7071752369554196
            val k = tan(PI * f0 / sampleRate)
            val vh = 10.0.pow(gainDb / 20.0)
            val vb = vh.pow(0.4996667741545416)
            val a0 = 1.0 + k / q + k * k
            return BiquadCoefficients(
                b0 = (vh + vb * k / q + k * k) / a0,
                b1 = 2.0 * (k * k - vh) / a0,
                b2 = (vh - vb * k / q + k * k) / a0,
                a1 = 2.0 * (k * k - 1.0) / a0,
                a2 = (1.0 - k / q + k * k) / a0,
            )
        }

        /** The RLB weighting: a high pass at about 38 Hz, which is what takes rumble out of the answer. */
        fun rlb(sampleRate: Int): BiquadCoefficients {
            val f0 = 38.13547087602444
            val q = 0.5003270373238773
            val k = tan(PI * f0 / sampleRate)
            val a0 = 1.0 + k / q + k * k
            return BiquadCoefficients(
                b0 = 1.0,
                b1 = -2.0,
                b2 = 1.0,
                a1 = 2.0 * (k * k - 1.0) / a0,
                a2 = (1.0 - k / q + k * k) / a0,
            )
        }
    }
}
