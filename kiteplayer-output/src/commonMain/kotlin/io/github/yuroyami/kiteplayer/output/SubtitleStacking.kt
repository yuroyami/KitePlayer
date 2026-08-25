package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.subtitle.CueAlignment
import io.github.yuroyami.kiteplayer.subtitle.CueLayout

/**
 * Whether a cue takes its place FROM the implicit bottom stack, and so takes space IN it.
 *
 * SOL-S8: those were one question and had to be two. Every rasterizer grew the running stack offset
 * for any bottom-aligned cue, including one carrying an authored `positionY`. Such a cue is laid
 * out from its own fraction and never reads the offset, so it was reserving room in a stack it does
 * not stand in, and every later ordinary subtitle rose by its height.
 *
 * Shared rather than mirrored a fourth time: the three rasterizers each held a private
 * `CueAlignment.isBottom` with exactly one caller, which is three chances for this rule to drift.
 */
internal val CueLayout.usesImplicitBottomStack: Boolean
    get() = positionY == null &&
        (
            alignment == CueAlignment.BottomLeft ||
                alignment == CueAlignment.BottomCenter ||
                alignment == CueAlignment.BottomRight
            )
