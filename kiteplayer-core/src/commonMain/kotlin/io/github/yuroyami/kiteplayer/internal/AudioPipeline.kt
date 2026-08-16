package io.github.yuroyami.kiteplayer.internal

import io.github.yuroyami.kiteplayer.PlaybackWarning
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import kotlin.math.roundToInt
import kotlin.time.Duration

/**
 * Everything that happens to decoded audio between the decoder and the ring.
 *
 * Four stages, in this order, and the order is the design:
 *
 * 1. [ChannelMixer] puts the channels in the speakers the device has. Downmixing first means the rate
 *    conversion runs on two channels instead of eight.
 * 2. [LinearResampler] makes the rate the one the device accepted.
 * 3. [TempoStage] makes the sound take `1/speed` as long without moving its pitch. After the
 *    resampler, so pitch detection runs at one known rate; before the gain, so mute stays the
 *    last word.
 * 4. [GainStage] applies volume and mute, last, so a mute is silent immediately and no later stage
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
    /**
     * True runs [speed] through the tempo stage, which keeps pitch. False folds the rate into
     * the resampler instead: cheaper by a whole WSOLA pass, and the pitch moves with the rate,
     * which is mpv's `audio-pitch-correction=no` and sometimes exactly what a caller wants.
     */
    val preservePitch: Boolean = true,
) {
    private val mixer = ChannelMixer(sourceFormat, targetFormat, onWarning)

    /**
     * The uncorrected-pitch rate. Always 1.0 while [preservePitch] is true. When it is not,
     * the rate is folded into the resampler below: playing S times as fast IS resampling from
     * `source * S` to the device rate, and pitch moves with it by that same arithmetic.
     */
    private var resampleSpeed: Double = 1.0

    private var resampler = buildResampler()

    private fun buildResampler(): LinearResampler = LinearResampler(
        sourceRate = if (resampleSpeed == 1.0) {
            sourceFormat.sampleRate
        } else {
            (sourceFormat.sampleRate * resampleSpeed).roundToInt().coerceAtLeast(1)
        },
        targetRate = targetFormat.sampleRate,
        channels = targetFormat.channels,
    )

    private val tempo = TempoStage(targetFormat.channels, targetFormat.sampleRate)
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
    /**
     * The processed samples. SOL-P2: when every stage passes through, this ALIASES the last
     * [process] call's input rather than copying it; the caller's buffer is scratch by the
     * submit contract, consumed before the next decode reuses it.
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

    /**
     * The playback rate. 1.0 bypasses both mechanisms entirely.
     *
     * With [preservePitch] the tempo stage applies it; without, the resampler is rebuilt with
     * the rate folded into its source rate, which is safe for exactly the reason the tempo
     * route is: the engine routes a change here through a flush with the feeder quiescent, so
     * no buffer is ever spliced from two speeds and the discarded carry frame was going to be
     * dropped by [reset] anyway.
     *
     * Owned by the feeder like [volume].
     */
    var speed: Double
        get() = if (preservePitch) tempo.speed else resampleSpeed
        set(value) {
            if (preservePitch) {
                tempo.speed = value
                return
            }
            require(value.isFinite() && value >= TempoStage.MIN_SPEED && value <= TempoStage.MAX_SPEED) {
                "speed must be within ${TempoStage.MIN_SPEED}..${TempoStage.MAX_SPEED}, was $value"
            }
            if (resampleSpeed == value) return
            resampleSpeed = value
            resampler = buildResampler()
        }

    /** Frames the tempo stage has emitted since the last [reset]. The pts law reads this. */
    val tempoEmittedFrames: Long get() = tempo.emittedFrames

    /** True when this pipeline was built for exactly the format [decoderFormat] describes. */
    fun matches(decoderFormat: AudioFormat): Boolean = decoderFormat == sourceFormat

    /**
     * A pipeline for [decoderFormat] into the same target, carrying this one's volume settings over.
     *
     * The rate conversion's carried frame is deliberately not carried over: it belongs to the old
     * format and interpolating it into the new one is exactly the discontinuity the new pipeline
     * exists to avoid.
     */
    fun rebuiltFor(decoderFormat: AudioFormat, preservePitch: Boolean = this.preservePitch): AudioPipeline =
        AudioPipeline(decoderFormat, targetFormat, onWarning, rampDuration, preservePitch).also {
            it.volume = volume
            it.muted = muted
            it.speed = speed
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

        /* SOL-P2: a pass-through mixer used to copy the whole buffer anyway. Skipping it means
         * plain stereo-to-stereo playback runs zero pipeline copies until the ring write: the
         * mixer, the resampler at equal rates and the tempo stage at 1.0 all stand aside, and
         * the gain multiplies in place only when the volume is not unity. */
        var produced = frames
        var result: FloatArray
        if (mixer.isPassThrough) {
            result = input
        } else {
            mixed = grown(mixed, frames * targetChannels)
            mixer.mix(input, mixed, frames)
            result = mixed
        }
        if (!resampler.isPassThrough) {
            resampled = grown(resampled, resampler.outputCapacityFor(frames) * targetChannels)
            produced = resampler.resample(result, frames, resampled)
            result = resampled
        }

        // The tempo stage owns lookahead, so at speeds other than 1.0 it may answer zero while it
        // accumulates, and its output buffer replaces ours. At 1.0 with nothing queued the stage
        // is skipped outright, so normal playback pays not even a copy for it; the counters are
        // advanced so the pts law upstairs never notices which branch ran.
        if (tempo.speed != 1.0 || tempo.hasQueuedInput) {
            produced = tempo.process(result, produced)
            result = tempo.output
        } else {
            tempo.countBypassed(produced)
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
        tempo.reset()
    }

    private fun grown(buffer: FloatArray, values: Int): FloatArray =
        if (buffer.size >= values) buffer else FloatArray(values)
}
