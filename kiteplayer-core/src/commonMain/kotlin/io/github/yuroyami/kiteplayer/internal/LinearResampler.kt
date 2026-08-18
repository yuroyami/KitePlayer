package io.github.yuroyami.kiteplayer.internal

/**
 * Rate conversion by linear interpolation between neighbouring sample frames.
 *
 * ### Interim quality, and what replaces it
 *
 * Linear interpolation is the cheapest correct answer and it is not a good one: it is a weak low-pass
 * on the way down and it leaves images of the signal on the way up, both of which are audible on
 * music as a dulled top end. It is here so that a device that will not take the decoder's rate plays
 * the right notes at the right speed instead of nothing, and it is replaced by libswresample in
 * Horizon B (KPKMP-PAST.md section 11, B4). No document may present it as the production rate conversion,
 * because it is not one.
 *
 * ### Why it holds state
 *
 * A buffer boundary is not a signal boundary. The output frame that falls between the last input
 * frame of one buffer and the first input frame of the next needs both of them, so this keeps the
 * last input frame and the exact read position across calls. Restarting at zero on every buffer
 * instead is a discontinuity per buffer, which at 21 ms per buffer is a 47 Hz buzz laid over the
 * audio: the single most common way a hand-written resampler sounds broken.
 *
 * The read position is kept as an integer frame index plus an exact fraction over the target rate, so
 * it is not a floating-point accumulator and it cannot drift over a long session. Each buffer moves
 * the index back by the number of frames it held, which is exact, so the conversion stays
 * sample-locked to the source for as long as the stream lasts.
 *
 * The one place it invents anything is the very first buffer, which has no earlier frame: the first
 * input frame stands in for it, so the stream is delayed by at most one input frame. Timestamps are
 * handed to the ring unchanged, so that delay is also the whole timing error of this stage, and at
 * 44.1 kHz it is 23 microseconds.
 *
 * One instance per format pair, held by [AudioPipeline]. Not thread safe: it belongs to the audio
 * feeder, and [reset] belongs to the seek path, which runs with the feeder quiescent.
 */
internal class LinearResampler(
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

    /**
     * Integer part of the next output frame's read position, in input frames from the start of the
     * buffer passed to the next [resample]. Minus one means the position sits inside [previous].
     */
    private var frameIndex: Int = -1

    /** Fractional part of that position, over [targetRate]. Always in `0 until targetRate`. */
    private var remainder: Int = 0

    /** The last input frame of the previous buffer, which the first outputs of the next one need. */
    private val previous = FloatArray(channels)
    private var hasPrevious = false

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

        // The stream starts somewhere. Holding the first frame is the one assumption in this class,
        // and it costs one input frame of delay rather than a click.
        if (!hasPrevious) {
            input.copyInto(previous, destinationOffset = 0, startIndex = 0, endIndex = channels)
            hasPrevious = true
        }

        var produced = 0
        // An output frame needs the input frames on both sides of its position. The last one this
        // buffer can serve alone sits at frames - 1, so the loop stops there and the next call
        // produces the ones that straddle the boundary out of [previous].
        while (frameIndex < frames - 1) {
            val fraction = remainder.toFloat() / targetRate
            val base = frameIndex * channels
            val outBase = produced * channels
            for (channel in 0 until channels) {
                val before = if (frameIndex < 0) previous[channel] else input[base + channel]
                val after = input[base + channels + channel]
                output[outBase + channel] = before + (after - before) * fraction
            }
            produced++

            remainder += sourceRate
            frameIndex += remainder / targetRate
            remainder %= targetRate
        }

        // The next buffer's frame zero is this buffer's frame count, so the origin moves by exactly
        // that. Integers only: this is the step that would drift if the position were a double.
        frameIndex -= frames
        input.copyInto(
            destination = previous,
            destinationOffset = 0,
            startIndex = (frames - 1) * channels,
            endIndex = frames * channels,
        )
        return produced
    }

    /** How many output frames [frames] input frames can produce, at most. */
    fun outputCapacityFor(frames: Int): Int {
        if (frames <= 0) return 0
        return (frames.toLong() * targetRate / sourceRate).toInt() + 2
    }

    /**
     * Forgets the carried frame and the read position. The seek path.
     *
     * Interpolating across a seek would mix one sample of the old position into the new one, and
     * carrying the fractional position would offset the new position by a fraction of a frame.
     */
    fun reset() {
        frameIndex = -1
        remainder = 0
        hasPrevious = false
    }
}
