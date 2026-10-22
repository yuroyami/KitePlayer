package io.github.yuroyami.kiteplayer.view

import io.github.yuroyami.kiteplayer.spi.VideoRenderer

/**
 * Renderer contract used by a platform player view.
 *
 * The three counters form an exact lifetime ledger for frames accepted by the renderer: every
 * accepted frame is eventually presented, superseded by a newer frame, or failed. Views expose
 * these values across renderer rebuilds without knowing the concrete platform renderer type.
 */
public interface PlayerViewRenderer : VideoRenderer {
    /** Frames that reached the platform display. */
    public val presentedFrames: Long

    /** Frames replaced by a newer frame before they reached the display. */
    public val supersededFrames: Long

    /** Frames that could not be displayed for a reason other than supersession. */
    public val failedFrames: Long
}
