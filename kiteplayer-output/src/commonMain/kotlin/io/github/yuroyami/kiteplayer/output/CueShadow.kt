package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.subtitle.CueStyle
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Where a cue's drop shadow falls, and what room the bitmap needs for it.
 *
 * A shadow is a copy of the text (glyphs AND outline) in the shadow colour, drawn under the real
 * one and pushed down-right. That copy lands OUTSIDE the text box, so the bitmap has to grow or
 * the shadow is simply clipped away, which is the shape of the bug this replaces: the field was
 * read by every parser and drawn by nobody.
 *
 * Growing the bitmap alone would move the cue, because every placement is measured from the
 * bitmap's own size. So the rasterizers keep placing the TEXT box and then subtract [origin]:
 * the box lands exactly where it landed before and the extra pixels hang off the edge.
 */
internal class CueShadow(
    /** How far right and down to push the copy, in output pixels. Negative reaches up and left. */
    val offset: Float,
    /** Extra pixels the bitmap needs on both axes. Zero when no shadow is drawn. */
    val pad: Int,
    /** Where the text box starts inside the grown bitmap: only non-zero for a negative offset. */
    val origin: Int,
) {
    val draws: Boolean get() = pad > 0
}

/** A shadow no bitmap has to grow for, which is what a transparent or zero shadow means. */
internal val NO_CUE_SHADOW = CueShadow(offset = 0f, pad = 0, origin = 0)

/**
 * Reads a cue's shadow, in output pixels.
 *
 * [scale] carries the viewer's font scale and the authoring resolution, the same way the font
 * size does: a shadow authored against a 720p script has to grow with everything else or it
 * vanishes on a 4K screen.
 */
internal fun cueShadow(style: CueStyle, scale: Float): CueShadow {
    val transparent = (style.shadowColor ushr 24) == 0
    val offset = style.shadowOffsetPx * scale
    if (transparent || offset == 0f || !offset.isFinite()) return NO_CUE_SHADOW
    // A script is free to ask for a 500 pixel shadow. Nothing sane does, and honouring it would
    // allocate a bitmap out of all proportion to the text, so it is clamped rather than trusted.
    val pad = ceil(abs(offset)).toInt().coerceAtMost(MAX_SHADOW_PX)
    return CueShadow(offset = offset.coerceIn(-pad.toFloat(), pad.toFloat()), pad = pad, origin = if (offset < 0) pad else 0)
}

private const val MAX_SHADOW_PX: Int = 64
