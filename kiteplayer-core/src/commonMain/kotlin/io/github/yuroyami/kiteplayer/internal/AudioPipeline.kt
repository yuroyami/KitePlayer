package io.github.yuroyami.kiteplayer.internal

import io.github.yuroyami.kiteplayer.PlaybackWarning
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import kotlin.time.Duration

/**
 * Everything that happens to decoded audio between the decoder and the ring.
 *
 * Three stages, in this order, and the order is the design:
 *
 * 1. [ChannelMixer] puts the channels in the speakers the device has. Downmixing first means the rate
 *    conversion runs on two channels instead of eight.
 * 2. [LinearResampler] makes the rate the one the device accepted.
 * 3. [GainStage] applies volume and mute, last, so a mute is silent immediately and no later stage
 *    smears it.
 *
 * A stage that has nothing to do costs nothing beyond a copy: matching layouts copy channels,
 * matching rates skip the conversion entirely, and unity gain skips its own multiply.
 *
 * ### Rebuilding
 *
 * A pipeline is built for one pair of formats. A decoder may change its output format mid-stream, so
 * the feeder checks [matches] against the decoder's current format before every buffer and calls
 * [rebuiltFor] when it stops matching. Rebuilding rather than reconfiguring is what keeps the stages
 * free of half-applied state, and the volume settings carry over so a rebuild is inaudible.
 *
 * ### Ownership
 *
 * [output] is this pipeline's own buffer and it is reused by the next [process], so the caller reads
 * what it needs before calling again. The caller's input array is never written to, never held, and
 * never handed back.
 *
 * One instance per audio stream, owned by the audio feeder. Not thread safe by design: the feeder is
 * the ring's single producer and this sits directly in front of it.
 */
internal class AudioPipeline(
    /** What the decoder produces. The mask on it is what the mixer keys on. */
    val sourceFormat: AudioFormat,
    /** What the device accepted, which is what the ring and the sink expect. */
    val targetFormat: AudioFormat,
    private val onWarning: (PlaybackWarning) -> Unit = {},
    private val rampDuration: Duration = GainStage.DEFAULT_RAMP_DURATION,
) {
    private val mixer = ChannelMixer(sourceFormat, targetFormat, onWarning)
    private val resampler = LinearResampler(
        sourceRate = sourceFormat.sampleRate,
        targetRate = targetFormat.sampleRate,
        channels = targetFormat.channels,
    )
    private val gain = GainStage(targetFormat.sampleRate, targetFormat.channels, rampDuration)

    private val targetChannels = targetFormat.channels

    private var mixed = FloatArray(0)
    private var resampled = FloatArray(0)

    /**
     * The samples the last [process] produced, interleaved in [targetFormat].
     *
     * Only the first `frames * targetFormat.channels` values are the answer, where `frames` is what
     * [process] returned. The array is longer than that whenever an earlier buffer was longer.
     */
    var output: FloatArray = FloatArray(0)
        private set

    /** Wanted volume, from silence at 0 to unity at 1. */
    var volume: Float
        get() = gain.volume
        set(value) {
            gain.volume = value
        }

    var muted: Boolean
        get() = gain.muted
        set(value) {
            gain.muted = value
        }

    /** True when this pipeline was built for exactly the format [decoderFormat] describes. */
    fun matches(decoderFormat: AudioFormat): Boolean = decoderFormat == sourceFormat

    /**
     * A pipeline for [decoderFormat] into the same target, carrying this one's volume settings over.
     *
     * The rate conversion's carried frame is deliberately not carried over: it belongs to the old
     * format and interpolating it into the new one is exactly the discontinuity the new pipeline
     * exists to avoid.
     */
    fun rebuiltFor(decoderFormat: AudioFormat): AudioPipeline =
        AudioPipeline(decoderFormat, targetFormat, onWarning, rampDuration).also {
            it.volume = volume
            it.muted = muted
        }

    /**
     * Runs [frames] sample frames of interleaved [input], in [sourceFormat], through the three stages.
     *
     * @return sample frames written to [output], which differs from [frames] whenever the rates
     *         differ. It can be zero for a very short buffer being converted downward, when no output
     *         position fell inside it. Nothing is dropped by that: the read position and the last
     *         input frame carry to the next call, so the conversion stays continuous across it.
     */
    fun process(input: FloatArray, frames: Int): Int {
        if (frames <= 0) return 0

        mixed = grown(mixed, frames * targetChannels)
        mixer.mix(input, mixed, frames)

        var produced = frames
        var result = mixed
        if (!resampler.isPassThrough) {
            resampled = grown(resampled, resampler.outputCapacityFor(frames) * targetChannels)
            produced = resampler.resample(mixed, frames, resampled)
            result = resampled
        }

        gain.apply(result, produced)
        output = result
        return produced
    }

    /**
     * Drops what the rate conversion carried across the last buffer. The seek path.
     *
     * Belongs to whoever owns the flush, with the feeder quiescent, exactly like the ring's own
     * flush. The gain keeps its position: the volume did not change because the position did.
     */
    fun reset() {
        resampler.reset()
    }

    private fun grown(buffer: FloatArray, values: Int): FloatArray =
        if (buffer.size >= values) buffer else FloatArray(values)
}
