package io.github.yuroyami.kiteplayer.internal

/**
 * A per-channel pre-gain on the way into the ring.
 *
 * Two knobs need this and neither of them is the volume. ReplayGain is a property of the MATERIAL,
 * measured by whoever encoded it, so it is applied once when a track opens and never touched again;
 * putting it here costs one multiply per sample on the feeder's thread and nothing on the device's.
 * The user's volume is the opposite kind of thing and lives in the ring, because a gain applied on
 * the way in cannot reach audio already buffered and would stay inaudible for the ring's whole
 * depth. Do not move either one to the other's side.
 *
 * Per channel rather than one scalar because balance is the next thing to use it, and a single
 * scalar cannot express a balance at all.
 *
 * [isIdentity] is what keeps this free: every gain at unity means the stage is skipped entirely
 * rather than multiplying a buffer by ones, so an ordinary file with no tags pays nothing.
 *
 * Not thread safe, and it does not need to be: the feeder owns it, and a change is applied by that
 * same worker between buffers.
 */
internal class TrimStage(val channels: Int) {

    init {
        require(channels >= 1) { "a trim stage needs at least one channel, was $channels" }
    }

    private val gains = FloatArray(channels) { 1f }

    /** True while every channel is at unity, in which case [apply] does nothing at all. */
    var isIdentity: Boolean = true
        private set

    /** Sets every channel to [gain]. */
    fun setAll(gain: Float) {
        require(gain.isFinite() && gain >= 0f) { "a trim gain must be finite and not negative, was $gain" }
        gains.fill(gain)
        isIdentity = gain == 1f
    }

    /** Sets one gain per channel, in the interleave's own order. */
    fun set(perChannel: FloatArray) {
        require(perChannel.size == channels) {
            "a trim needs one gain per channel: $channels wanted, ${perChannel.size} given"
        }
        require(perChannel.all { it.isFinite() && it >= 0f }) {
            "every trim gain must be finite and not negative, got ${perChannel.toList()}"
        }
        perChannel.copyInto(gains)
        isIdentity = gains.all { it == 1f }
    }

    /**
     * Scales the first [frames] sample frames of interleaved [samples] in place.
     *
     * Only those frames: the pipeline reuses one buffer across calls, so its tail holds audio from
     * an earlier and longer conversion that has already been handed on.
     */
    fun apply(samples: FloatArray, frames: Int) {
        if (isIdentity || frames <= 0) return
        var base = 0
        for (frame in 0 until frames) {
            for (channel in 0 until channels) samples[base + channel] *= gains[channel]
            base += channels
        }
    }
}
