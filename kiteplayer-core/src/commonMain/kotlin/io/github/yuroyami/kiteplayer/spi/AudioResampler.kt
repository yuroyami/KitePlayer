package io.github.yuroyami.kiteplayer.spi

/**
 * Converts interleaved float audio from one sample rate to another.
 *
 * The engine converts the decoder's rate to the rate that the audio device accepted. By default it
 * uses its own windowed-sinc filter, written in Kotlin. An [AudioResamplerFactory] in
 * `AudioConfig.resampler` replaces that filter.
 *
 * ## Where it runs
 *
 * One instance for each audio stream. It runs after the channel mix, so every buffer has the
 * device's channel count, and before the tempo stage. Samples are interleaved: frame after frame,
 * with one value for each channel in a frame.
 *
 * ## Threading
 *
 * The engine never calls two members at the same time, so an implementation needs no lock. A
 * filter may hold input between calls, because it needs samples on both sides of each output.
 */
public interface AudioResampler : AutoCloseable {

    /**
     * The most frames that the next [process] call can write for [inputFrames] frames of input,
     * counting what this resampler holds from earlier calls. The engine sizes the output array
     * from this answer. With zero input, it is the most frames that [flush] can write.
     */
    public fun outputCapacity(inputFrames: Int): Int

    /**
     * Converts [frames] frames of interleaved [input] and writes the result to [output], which
     * holds at least `outputCapacity(frames)` frames. The engine never keeps [input] after the
     * call returns.
     *
     * @return the frames written to [output]. Zero is a valid answer while the filter fills.
     */
    public fun process(input: FloatArray, frames: Int, output: FloatArray): Int

    /**
     * Writes what this resampler still holds, at the end of the stream. [output] holds at least
     * `outputCapacity(0)` frames.
     *
     * @return the frames written to [output].
     */
    public fun flush(output: FloatArray): Int

    /** Drops everything held, for a seek. The next [process] call starts a new signal. */
    public fun reset()

    /** Releases what the resampler holds. The engine calls nothing after it. */
    override fun close()
}

/**
 * Makes an [AudioResampler] for one pair of rates. Set it in `AudioConfig.resampler`.
 *
 * The engine asks only when the two rates differ, and asks again after a format change. While
 * pitch correction is off, the playback speed is folded into `inputRate`, so expect any rate, not
 * only the standard ones.
 *
 * When [create] throws, the engine keeps its own resampler for that stream, stops asking for the
 * rest of the player's life, and reports `PlaybackWarning.ResamplerUnavailable` once. A factory
 * with no implementation on one platform may throw there.
 */
public fun interface AudioResamplerFactory {
    public fun create(inputRate: Int, outputRate: Int, channels: Int): AudioResampler
}
