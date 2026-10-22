package io.github.yuroyami.kiteplayer.audioviz.viz

import io.github.yuroyami.kiteplayer.audioviz.SpectrumFrame

/**
 * How much display time one render state adds to a per-frame consumer.
 *
 * The same state twice is consumed once. A different frame at an already consumed display instant
 * still delivers its events, but adds no elapsed time, so springs and clocks never integrate one
 * instant twice.
 */
internal class DisplayStep {
    private var instant = Double.NaN
    private var frame: SpectrumFrame? = null

    /** Seconds to integrate, zero for a new frame at the same instant, or null when already consumed. */
    fun of(state: VizRenderState): Float? {
        if (state.instant == instant) {
            if (state.frame === frame) return null
            frame = state.frame
            return 0f
        }
        instant = state.instant
        frame = state.frame
        val delta = state.deltaSeconds
        return if (delta.isFinite() && delta > 0f) delta else 0f
    }

    fun reset() {
        instant = Double.NaN
        frame = null
    }
}
