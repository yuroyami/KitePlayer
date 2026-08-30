package io.github.yuroyami.kiteplayer.internal

import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Volume and mute, as one multiply per sample.
 *
 * It is last in the pipeline on purpose. Applied before the rate conversion, a volume change would be
 * smeared across the interpolation and a mute would not be silent until the conversion caught up.
 *
 * ### The ramp
 *
 * A gain that jumps from one value to another is a step in the waveform, and a step is a click, which
 * is audible even when the change is small and even when it is a mute. So the applied gain walks
 * towards the wanted one at a fixed slope: the whole range from silence to unity takes
 * [DEFAULT_RAMP_DURATION], and a smaller change takes proportionally less. The slope is what the
 * click depends on, not the duration, which is why it is the slope that is fixed and published as
 * [slopePerFrame].
 *
 * A fresh stage starts at unity rather than at silence, so opening a file does not fade in.
 *
 * Not thread safe: it belongs to the audio feeder, like the rest of [AudioPipeline]. A volume change
 * arriving from another thread is a message to the feeder, not a write to this object.
 */
internal class GainStage(
    sampleRate: Int,
    private val channels: Int,
    rampDuration: Duration = DEFAULT_RAMP_DURATION,
) {
    init {
        require(sampleRate > 0) { "a sample rate of $sampleRate is not a rate" }
        require(channels > 0) { "$channels channels cannot be scaled" }
        require(rampDuration > Duration.ZERO) { "a ramp of $rampDuration is not a ramp" }
    }

    /** Sample frames the gain takes to cross the whole range from silence to unity. */
    val rampFrames: Int =
        max(1, (sampleRate.toLong() * rampDuration.inWholeMicroseconds / 1_000_000L).toInt())

    /** The most the gain may change from one sample frame to the next. */
    val slopePerFrame: Float = 1f / rampFrames

    /** Wanted volume, from silence at 0 to unity at 1. Above unity is amplification and is refused. */
    var volume: Float = 1f
        set(value) {
            require(value.isFinite() && value >= 0f && value <= 1f) {
                "volume must be between 0 and 1, was $value"
            }
            field = value
        }

    var muted: Boolean = false

    /** What [apply] is walking towards. */
    val target: Float get() = if (muted) 0f else volume

    /** What the last sample frame was actually multiplied by. */
    var current: Float = 1f
        private set

    /**
     * Continues [from]'s ramp instead of starting at unity: a pipeline rebuilt
     * mid-stream while muted used to play up to one whole ramp of near-full-scale samples,
     * because its fresh gain stage began at 1 and only then walked down.
     */
    fun adoptRamp(from: GainStage) {
        current = from.current.coerceIn(0f, 1f)
    }

    /** Multiplies [frames] sample frames of interleaved [samples] in place. */
    fun apply(samples: FloatArray, frames: Int) {
        if (frames <= 0) return
        require(samples.size >= frames * channels) {
            "$frames frames of $channels channels need ${frames * channels} values, got ${samples.size}"
        }

        val wanted = target
        if (current == wanted) {
            if (wanted == 1f) return
            for (i in 0 until frames * channels) samples[i] *= wanted
            return
        }

        var gain = current
        var base = 0
        for (frame in 0 until frames) {
            gain = if (gain < wanted) min(wanted, gain + slopePerFrame) else max(wanted, gain - slopePerFrame)
            for (channel in 0 until channels) samples[base + channel] *= gain
            base += channels
        }
        current = gain
    }

    internal companion object {
        /**
         * Long enough that no click is left, short enough that a mute feels immediate.
         *
         * See MASTER_PLAN.md: it is one of the project's tuned constants and it lives here.
         */
        val DEFAULT_RAMP_DURATION: Duration = 5.milliseconds
    }
}
