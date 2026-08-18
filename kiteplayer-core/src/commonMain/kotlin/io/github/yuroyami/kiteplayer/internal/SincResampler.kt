package io.github.yuroyami.kiteplayer.internal

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Rate conversion by a windowed-sinc polyphase filter.
 *
 * ### Why this and not interpolation
 *
 * This replaced a two-tap linear interpolator (audit 15.3.1), whose own documentation said no
 * document may present it as production rate conversion. Linear interpolation is a weak low-pass on
 * the way down and leaves images of the signal on the way up: on music it dulls the top end and adds
 * a haze of aliasing that a listener hears as "not quite right" without being able to name it.
 *
 * A sinc kernel is the exact answer to the question "what was the signal between these samples", and
 * a windowed one is the practical version of it. Every output frame is a weighted sum of [TAPS]
 * input frames around its position. The weights depend only on where the output falls BETWEEN two
 * input frames, so all of them are computed once at construction into a table of [PHASES] rows, and
 * the hot loop is a dot product with no trigonometry in it.
 *
 * ### The one decision that matters
 *
 * The kernel's cutoff is the LOWER of the two Nyquist frequencies. Going up that is the source's, so
 * the filter is transparent. Going DOWN it is the target's, and that is the whole point: everything
 * above the new Nyquist is removed before it can fold back into the audible band. Interpolation
 * cannot do this, and folded content is the audible defect this class exists to remove.
 *
 * ### Why it holds state
 *
 * A buffer boundary is not a signal boundary. An output frame near the end of a buffer needs input
 * frames from the next one, and one near the start needs input from the previous one, so the input
 * is kept in one running buffer and only the frames no tap can still reach are dropped. Restarting
 * at every buffer instead is a discontinuity per buffer, which at 21 ms per buffer is a 47 Hz buzz
 * laid over the audio: the single most common way a hand-written resampler sounds broken.
 *
 * The read position is an integer frame index plus an exact fraction over the target rate, never a
 * floating-point accumulator, so it cannot drift over a long session. The stream stays sample-locked
 * to the source for as long as it lasts.
 *
 * ### Timing
 *
 * There is no timing error to compensate. Output frame `k` is defined at input position
 * `k * source / target` and the kernel is centred on it, so the output is aligned with the input
 * exactly; what the lookahead delays is when a frame can be PRODUCED, not where it sits in time. The
 * stream does start with [TAPS] / 2 input frames of implied silence before it, which fades the first
 * 0.36 ms in at 44.1 kHz, and ends holding the same amount until [drain] pushes it out.
 *
 * One instance per format pair, held by [AudioPipeline]. Not thread safe: it belongs to the audio
 * feeder, and [reset] belongs to the seek path, which runs with the feeder quiescent.
 */
internal class SincResampler(
    private val sourceRate: Int,
    private val targetRate: Int,
    private val channels: Int,
) {
    init {
        require(sourceRate > 0) { "a source rate of $sourceRate is not a rate" }
        require(targetRate > 0) { "a target rate of $targetRate is not a rate" }
        require(channels > 0) { "$channels channels cannot be resampled" }
    }

    /** True when the rates match, in which case the caller should not spend a copy on this stage. */
    val isPassThrough: Boolean get() = sourceRate == targetRate

    private val half = TAPS / 2

    /** [PHASES] rows of [TAPS] weights, one row per position between two input frames. */
    private val kernel: FloatArray = buildKernel()

    /**
     * Input frames not yet dropped: the taps a future output still needs, plus everything that has
     * arrived and not been reached. Seeded with [half] frames of silence so the first real frame
     * sits where the kernel can be centred on it without reading before the start.
     */
    private var pending: FloatArray = FloatArray(half * channels)
    private var pendingFrames: Int = half

    /** Integer frame position of the next output inside [pending]. */
    private var readIndex: Int = half

    /** Fractional part of that position, over [targetRate]. Always in `0 until targetRate`. */
    private var remainder: Int = 0

    /** One output frame under construction, so the tap loop walks memory in interleaved order. */
    private val accumulator = FloatArray(channels)

    /**
     * Converts [frames] sample frames of interleaved [input] into interleaved [output].
     *
     * @return frames written to [output]. Never more than [outputCapacityFor] of [frames].
     */
    fun resample(input: FloatArray, frames: Int, output: FloatArray): Int {
        if (frames <= 0) return 0
        require(input.size >= frames * channels) {
            "$frames frames of $channels channels need ${frames * channels} values, got ${input.size}"
        }
        val capacity = outputCapacityFor(frames)
        require(output.size >= capacity * channels) {
            "$frames input frames can produce $capacity output frames, which need " +
                "${capacity * channels} values, got ${output.size}"
        }
        append(input, frames)
        val produced = produce(output)
        dropConsumed()
        return produced
    }

    /**
     * Pushes out what the kernel is still holding, by running [half] frames of silence through it.
     *
     * The end of a stream is the one place the lookahead would otherwise cost real audio: without
     * this the last 0.36 ms at 44.1 kHz never leaves the filter. Silence is the correct thing to
     * feed, because silence is what follows the end of the media.
     *
     * @return frames written to [output]. Call once, at end of stream.
     */
    fun drain(output: FloatArray): Int {
        if (isPassThrough) return 0
        append(FloatArray(half * channels), half)
        val produced = produce(output)
        dropConsumed()
        return produced
    }

    /** Frames [drain] can produce, at most. */
    fun drainCapacity(): Int = outputCapacityFor(half)

    private fun append(input: FloatArray, frames: Int) {
        val needed = (pendingFrames + frames) * channels
        if (pending.size < needed) pending = pending.copyOf(maxOf(needed, pending.size * 2))
        input.copyInto(pending, pendingFrames * channels, 0, frames * channels)
        pendingFrames += frames
    }

    /**
     * Produces every output whose whole tap window has arrived.
     *
     * The window around position `p` spans `p - half + 1` to `p + half`, so the last output this
     * call can serve sits at `pendingFrames - 1 - half`. Everything past that waits for the next
     * buffer, which is what makes a buffer boundary invisible in the result.
     */
    private fun produce(output: FloatArray): Int {
        var produced = 0
        val last = pendingFrames - 1 - half
        while (readIndex <= last) {
            val phase = (remainder.toLong() * PHASES / targetRate).toInt().coerceIn(0, PHASES - 1)
            val row = phase * TAPS
            for (channel in 0 until channels) accumulator[channel] = 0f
            var at = (readIndex - half + 1) * channels
            for (tap in 0 until TAPS) {
                val weight = kernel[row + tap]
                for (channel in 0 until channels) accumulator[channel] += weight * pending[at + channel]
                at += channels
            }
            val outBase = produced * channels
            for (channel in 0 until channels) output[outBase + channel] = accumulator[channel]
            produced++

            // Integers only: this is the step that would drift if the position were a double.
            remainder += sourceRate
            readIndex += remainder / targetRate
            remainder %= targetRate
        }
        return produced
    }

    /** Drops the frames no future tap can reach, and moves the read position with them. */
    private fun dropConsumed() {
        val keep = readIndex - half + 1
        if (keep <= 0) return
        pending.copyInto(pending, 0, keep * channels, pendingFrames * channels)
        pendingFrames -= keep
        readIndex -= keep
    }

    /**
     * How many output frames [frames] input frames can produce, at most.
     *
     * Counted against the backlog as well as the input, because a call that arrives after several
     * short ones releases what they left behind as well as its own.
     */
    fun outputCapacityFor(frames: Int): Int {
        if (frames <= 0) return 0
        return ((frames + TAPS).toLong() * targetRate / sourceRate).toInt() + 2
    }

    /**
     * Forgets everything held and the read position. The seek path.
     *
     * Filtering across a seek would mix the old position into the new one, and carrying the
     * fractional position would offset the new position by a fraction of a frame.
     */
    fun reset() {
        pending.fill(0f, 0, min(pending.size, half * channels))
        pendingFrames = half
        readIndex = half
        remainder = 0
    }

    /**
     * The weights, once, for every position an output can fall at between two input frames.
     *
     * Each row is normalised to sum to one. Without that the output amplitude ripples from row to
     * row, which is heard as a whine at the beat frequency between the two rates: a resampler can
     * be perfectly aliasing-free and still sound wrong for exactly this reason.
     */
    private fun buildKernel(): FloatArray {
        // The lower of the two Nyquists, with a little transition room. Going down, this is what
        // removes the content that would otherwise fold back into the audible band.
        val cutoff = min(1.0, targetRate.toDouble() / sourceRate) * ROLLOFF
        val table = FloatArray(PHASES * TAPS)
        for (phase in 0 until PHASES) {
            val fraction = phase.toDouble() / PHASES
            val row = phase * TAPS
            var sum = 0.0
            for (tap in 0 until TAPS) {
                // Distance from this tap to the output position, in input frames.
                val distance = (tap - half + 1).toDouble() - fraction
                val value = cutoff * sinc(cutoff * distance) * window(distance)
                table[row + tap] = value.toFloat()
                sum += value
            }
            if (abs(sum) > 1e-9) {
                val scale = (1.0 / sum).toFloat()
                for (tap in 0 until TAPS) table[row + tap] *= scale
            }
        }
        return table
    }

    private fun sinc(x: Double): Double {
        if (x == 0.0) return 1.0
        val piX = PI * x
        return sin(piX) / piX
    }

    /** Blackman, which trades a little transition width for stopband rejection deep enough to hide. */
    private fun window(distance: Double): Double {
        val ratio = distance / half
        if (ratio <= -1.0 || ratio >= 1.0) return 0.0
        val angle = PI * (ratio + 1.0)
        return 0.42 - 0.5 * cos(angle) + 0.08 * cos(2.0 * angle)
    }

    internal companion object {
        /**
         * Taps per output frame.
         *
         * Thirty-two is the usual place to sit: the stopband is deep enough that the residual
         * aliasing is far below anything a listener can hear over the music, and the cost is 32
         * multiply-adds per channel per output frame, which at 48 kHz stereo is about 3 million
         * per second and disappears next to the decode.
         */
        const val TAPS: Int = 32

        /**
         * Positions between two input frames that get their own row of weights.
         *
         * The quantisation error left over is one part in [PHASES] of a frame, far below the
         * quantisation of the samples themselves, so nothing is gained by interpolating between
         * rows the way some implementations do.
         */
        const val PHASES: Int = 512

        /**
         * Where the passband ends, as a fraction of the Nyquist it is protecting.
         *
         * Not 1.0: a brick wall at exactly Nyquist needs infinite taps, so the last few percent are
         * given up as transition band. At 44.1 kHz this puts the corner near 20.4 kHz, above
         * anything most listeners can hear, and keeps the stopband properly deep.
         */
        const val ROLLOFF: Double = 0.925
    }
}
