package io.github.yuroyami.kiteplayer.internal

import io.github.yuroyami.kiteplayer.PlaybackWarning
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.AudioResampler
import io.github.yuroyami.kiteplayer.spi.AudioResamplerFactory
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import kotlin.time.Duration

/**
 * Everything that happens to decoded audio between the decoder and the ring.
 *
 * Four stages, in this order, and the order is the design:
 *
 * 1. [ChannelMixer] puts the channels in the speakers the device has. Downmixing first means the rate
 *    conversion runs on two channels instead of eight.
 * 2. The resampler makes the rate the one the device accepted: [SincResampler], or what the
 *    configured [AudioResamplerFactory] makes.
 * 3. [TempoStage] makes the sound take `1/speed` as long without moving its pitch. After the
 *    resampler, so pitch detection runs at one known rate; before the gain, so mute stays the
 *    last word.
 * The gain is NOT here. Volume and mute moved to the ring's read side on 2026-08-31, because a gain
 * applied on the way INTO the ring cannot reach audio already buffered and a change stayed inaudible
 * for the ring's whole depth. See AudioRingHandle.setGain.
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
    /**
     * True runs [speed] through the tempo stage, which keeps pitch. False folds the rate into
     * the resampler instead: cheaper by a whole WSOLA pass, and the pitch moves with the rate,
     * which is mpv's `audio-pitch-correction=no` and sometimes exactly what a caller wants.
     */
    val preservePitch: Boolean = true,
    /** The LFE and headroom policy the downmix applies; see `DownmixConfig`. */
    private val downmix: io.github.yuroyami.kiteplayer.DownmixConfig =
        io.github.yuroyami.kiteplayer.DownmixConfig(),
    /**
     * Makes the rate conversion when the rates differ. Null uses [SincResampler]. Dropped after
     * its first refusal, so a later speed change does not ask again.
     */
    private var resamplerFactory: AudioResamplerFactory? = null,
    /** Told when [resamplerFactory] throws, after this pipeline fell back to [SincResampler]. */
    private val onResamplerRefused: (Throwable) -> Unit = {},
    /**
     * The epoch's speed. Without pitch correction it is folded into the resampler, so a pipeline
     * built mid-epoch makes its resampler once, at the right rate.
     */
    initialSpeed: Double = 1.0,
) : AutoCloseable {
    private val mixer = ChannelMixer(sourceFormat, targetFormat, onWarning, downmix)

    /**
     * The uncorrected-pitch rate. Always 1.0 while [preservePitch] is true. When it is not,
     * the rate is folded into the resampler below: playing S times as fast IS resampling from
     * `source * S` to the device rate, and pitch moves with it by that same arithmetic.
     */
    private var resampleSpeed: Double = if (preservePitch) 1.0 else checkedSpeed(initialSpeed)

    /** The rate conversion, or null when the rates match and there is nothing to convert. */
    private var resampler: AudioResampler? = buildResampler()

    private fun buildResampler(): AudioResampler? {
        val sourceRate = if (resampleSpeed == 1.0) {
            sourceFormat.sampleRate
        } else {
            (sourceFormat.sampleRate * resampleSpeed).roundToInt().coerceAtLeast(1)
        }
        val targetRate = targetFormat.sampleRate
        if (sourceRate == targetRate) return null
        val channels = targetFormat.channels
        val factory = resamplerFactory ?: return SincResampler(sourceRate, targetRate, channels)
        return try {
            factory.create(sourceRate, targetRate, channels)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            resamplerFactory = null
            onResamplerRefused(failure)
            SincResampler(sourceRate, targetRate, channels)
        }
    }

    private val tempo = TempoStage(targetFormat.channels, targetFormat.sampleRate)

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
     * The processed samples. When every stage passes through, this ALIASES the last
     * [process] call's input rather than copying it; the caller's buffer is scratch by the
     * submit contract, consumed before the next decode reuses it.
     */
    var output: FloatArray = FloatArray(0)
        private set

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
            checkedSpeed(value)
            if (resampleSpeed == value) return
            resampleSpeed = value
            resampler?.close()
            resampler = buildResampler()
        }

    private fun checkedSpeed(value: Double): Double {
        require(value.isFinite() && value >= TempoStage.MIN_SPEED && value <= TempoStage.MAX_SPEED) {
            "speed must be within ${TempoStage.MIN_SPEED}..${TempoStage.MAX_SPEED}, was $value"
        }
        return value
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
     * exists to avoid. The caller closes this pipeline once it has the new one.
     */
    fun rebuiltFor(
        decoderFormat: AudioFormat,
        preservePitch: Boolean = this.preservePitch,
        speed: Double = this.speed,
        resamplerFactory: AudioResamplerFactory? = this.resamplerFactory,
    ): AudioPipeline =
        AudioPipeline(
            decoderFormat, targetFormat, onWarning, preservePitch, downmix,
            resamplerFactory, onResamplerRefused, initialSpeed = speed,
        ).also {
            it.speed = speed
            // No gain crosses here any more, and none needs to: the gain lives in the ring, which
            // outlives every pipeline rebuild. A rebuild used to have to carry the ramp POSITION
            // across or a swap un-muted itself for one whole ramp.
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

        /* An identity mixer used to copy the whole buffer anyway. Skipping it means
         * plain stereo-to-stereo playback runs zero pipeline copies until the ring write: the
         * mixer, the resampler at equal rates and the tempo stage at 1.0 all stand aside. The
         * gain is not one of these stages and has not been since it moved to the read side; it is
         * applied as frames LEAVE the ring, which is what makes a volume change audible within one
         * device period instead of one ring depth. The alias keys on isIdentity, NOT isPassThrough:
         * a pass-through with unequal counts still restrides frame by frame, and aliasing it played
         * raw interleave on the wrong speakers. */
        var produced = frames
        var result: FloatArray
        if (mixer.isIdentity) {
            result = input
        } else {
            mixed = grown(mixed, frames * targetChannels)
            mixer.mix(input, mixed, frames)
            result = mixed
        }
        val conversion = resampler
        if (conversion != null) {
            resampled = grown(resampled, conversion.outputCapacity(frames) * targetChannels)
            produced = conversion.process(result, frames, resampled)
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

        /* Last, so it scales exactly what reaches the ring, and skipped entirely at unity so a file
         * with no ReplayGain tags pays nothing for the feature. In place: `result` is either our own
         * scratch or, in the all-bypass case, the caller's input, and the caller hands that buffer
         * over for the duration of the call. */
        // Before the trim, so ReplayGain and balance scale what the equaliser produced rather than
        // the equaliser amplifying a level the trim already set.
        equalizer.apply(result, produced)
        trim.apply(result, produced)

        output = result
        return produced
    }

    /**
     * The per-channel pre-gain: ReplayGain today, balance next.
     *
     * Exposed rather than configured through the constructor because it is set AFTER open, once the
     * container's tags have been read and the peak clamp resolved against the volume ceiling.
     */
    val trim: TrimStage = TrimStage(targetFormat.channels)

    /**
     * The ten-band equaliser, at the device's own rate because that is what its coefficients are
     * derived from. Flat by default and then free: see [EqualizerStage].
     */
    val equalizer: EqualizerStage = EqualizerStage(targetFormat.channels, targetFormat.sampleRate)

    /**
     * Pushes out what the stages are still holding, for the end of the stream.
     *
     * TWO stages hold something now. The tempo stage keeps up to two pitch periods of lookahead
     * that no further input will ever trigger, and dropping them loses the end of the media. The
     * rate conversion holds half a kernel, which is 0.36 ms at 44.1 kHz: small, but it
     * is real audio and the old interpolator's excuse for skipping it (it held under one frame, and
     * producing that frame would have meant inventing the sample after the end of the stream) no
     * longer applies. Silence after the end of the media is not an invention, it is the truth, so
     * the filter is drained with it and the tail comes out.
     *
     * The drained tail still passes the tempo stage and the gain, in that order, exactly as every
     * other buffer does; a mute or a ramp therefore reaches the tail too.
     *
     * Call once, at end of stream, on the feeder that owns this pipeline. Safe to call again: the
     * second call finds nothing queued and answers zero.
     *
     * @return sample frames written to [output], zero when no stage was holding anything.
     */
    fun finish(): Int {
        var total = 0
        // 1. The rate conversion's tail, through the tempo stage like any other buffer.
        val conversion = resampler
        if (conversion != null) {
            resampled = grown(resampled, conversion.outputCapacity(0) * targetChannels)
            val drained = conversion.flush(resampled)
            if (drained > 0) {
                if (tempo.speed != 1.0 || tempo.hasQueuedInput) {
                    val stretched = tempo.process(resampled, drained)
                    total = appendFinished(tempo.output, stretched, 0)
                } else {
                    tempo.countBypassed(drained)
                    total = appendFinished(resampled, drained, 0)
                }
            }
        }
        // 2. Whatever the tempo stage was still holding, after it.
        val last = tempo.finish()
        if (last > 0) total = appendFinished(tempo.output, last, total)

        if (total <= 0) return 0
        output = finished
        return total
    }

    /** The end-of-stream tail, which is up to two pieces and has to leave as one buffer. */
    private var finished: FloatArray = FloatArray(0)

    private fun appendFinished(source: FloatArray, frames: Int, at: Int): Int {
        if (frames <= 0) return at
        val values = (at + frames) * targetChannels
        if (finished.size < values) finished = finished.copyOf(values)
        source.copyInto(finished, at * targetChannels, 0, frames * targetChannels)
        return at + frames
    }

    /**
     * Drops what the rate conversion carried across the last buffer. The seek path.
     *
     * Belongs to whoever owns the flush, with the feeder quiescent, exactly like the ring's own
     * flush. The gain keeps its position: the volume did not change because the position did.
     */
    fun reset() {
        resampler?.reset()
        tempo.reset()
        // The filters ring for a few dozen samples, so a seek that kept their history would splice
        // the tail of the old position onto the head of the new one.
        equalizer.reset()
    }

    /** Releases the rate conversion. The owner calls it when it replaces this pipeline or closes. */
    override fun close() {
        resampler?.close()
        resampler = null
    }

    private fun grown(buffer: FloatArray, values: Int): FloatArray =
        if (buffer.size >= values) buffer else FloatArray(values)
}
