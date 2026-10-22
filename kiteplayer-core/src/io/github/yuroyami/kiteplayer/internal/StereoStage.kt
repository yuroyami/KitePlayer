package io.github.yuroyami.kiteplayer.internal

import io.github.yuroyami.kiteplayer.StereoMode

/**
 * What the two front speakers play (#462): a two by two mix of the first two channels, after the
 * downmix, so a surround film folded to two speakers obeys it as a stereo file does. The other
 * channels pass untouched, as they do for the balance.
 *
 * A change while audio flows crossfades from the old mix to the new one over [rampFrames], because
 * jumping from one side's sample to the other's is a click. A stage that has played nothing takes
 * its mode at once, so a pipeline rebuilt for a format change starts where the old one stood.
 *
 * [isIdentity] is what keeps this free: in stereo with no change in flight the stage is skipped, so
 * an ordinary file pays nothing for the feature.
 *
 * Not thread safe, and it does not need to be: the feeder owns it, as it owns the trim.
 */
internal class StereoStage(val channels: Int, private val rampFrames: Int) {

    init {
        require(channels >= 1) { "a stereo stage needs at least one channel, was $channels" }
        require(rampFrames >= 0) { "a ramp cannot be negative, was $rampFrames" }
    }

    /** The mix from which a change ramps, as left from left, left from right, right from left, right from right. */
    private var from = matrixOf(StereoMode.Stereo)

    /** The mix the stage is at, or ramping to. */
    private var to = from

    /** Frames of the ramp still to play, counting down from [rampFrames]. */
    private var rampLeft = 0

    private var mode = StereoMode.Stereo

    /** True once audio has passed, after which a change ramps. */
    private var started = false

    /** True while the stage would leave every sample as it is. */
    val isIdentity: Boolean get() = rampLeft == 0 && mode == StereoMode.Stereo

    /** Moves to [next], ramping from wherever the stage stands when audio has already passed. */
    fun set(next: StereoMode) {
        if (next == mode) return
        mode = next
        if (!started || rampFrames == 0 || channels < 2) {
            from = matrixOf(next)
            to = from
            rampLeft = 0
            return
        }
        from = current()
        to = matrixOf(next)
        rampLeft = rampFrames
    }

    /**
     * Mixes the first [frames] sample frames of interleaved [samples] in place. Only those frames,
     * because the pipeline reuses one buffer across calls.
     */
    fun apply(samples: FloatArray, frames: Int) {
        if (frames <= 0) return
        started = true
        if (channels < 2 || isIdentity) return
        var base = 0
        for (frame in 0 until frames) {
            val weight = if (rampLeft > 0) {
                rampLeft--
                1f - rampLeft.toFloat() / rampFrames
            } else {
                1f
            }
            val ll = from[0] + (to[0] - from[0]) * weight
            val lr = from[1] + (to[1] - from[1]) * weight
            val rl = from[2] + (to[2] - from[2]) * weight
            val rr = from[3] + (to[3] - from[3]) * weight
            val l = samples[base]
            val r = samples[base + 1]
            samples[base] = ll * l + lr * r
            samples[base + 1] = rl * l + rr * r
            base += channels
        }
        if (rampLeft == 0) from = to
    }

    /** The mix the ramp has reached, which a second change ramps from. */
    private fun current(): FloatArray {
        if (rampLeft == 0) return to
        val weight = 1f - rampLeft.toFloat() / rampFrames
        return FloatArray(4) { from[it] + (to[it] - from[it]) * weight }
    }

    private companion object {
        fun matrixOf(mode: StereoMode): FloatArray = when (mode) {
            StereoMode.Stereo -> floatArrayOf(1f, 0f, 0f, 1f)
            // Half of each on each side, so two full-scale sides stay at full scale.
            StereoMode.Mono -> floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f)
            StereoMode.LeftOnly -> floatArrayOf(1f, 0f, 1f, 0f)
            StereoMode.RightOnly -> floatArrayOf(0f, 1f, 0f, 1f)
            StereoMode.Swapped -> floatArrayOf(0f, 1f, 1f, 0f)
        }
    }
}
