package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.subtitle.CueAlignment
import io.github.yuroyami.kiteplayer.subtitle.CueLayout

/** The top-left corner of a cue's text box, in viewport pixels. */
internal class CueOrigin(val x: Int, val y: Int)

/**
 * Where a cue's text box goes. Every built-in rasterizer places through this, so a cue lands in the
 * same place on every platform.
 *
 * An authored position is the anchor point that the alignment names: an ASS `\pos` with `\an2`
 * puts the bottom centre of the text on the point, not its top-left corner. Without a position,
 * the alignment picks a margin, and the bottom row stacks upward from the viewer's sub-position.
 *
 * [width] and [height] are the text box, without the pixels that a shadow or a background box adds.
 * [position] is the viewer's sub-position, where 1 is the bottom edge. [stackedBottom] is how far
 * the cues already stacked below this one reach.
 */
internal fun cueOrigin(
    layout: CueLayout,
    viewportWidth: Int,
    viewportHeight: Int,
    width: Int,
    height: Int,
    position: Float,
    stackedBottom: Int,
): CueOrigin {
    val marginXPx = (viewportWidth * layout.marginLeft).toInt()
    val marginYPx = (viewportHeight * layout.marginVertical).toInt()
    val x = layout.positionX?.let { fraction ->
        val anchor = (fraction * viewportWidth).toInt()
        when (layout.alignment) {
            CueAlignment.BottomLeft, CueAlignment.MiddleLeft, CueAlignment.TopLeft -> anchor
            CueAlignment.BottomRight, CueAlignment.MiddleRight, CueAlignment.TopRight -> anchor - width
            else -> anchor - width / 2
        }
    } ?: when (layout.alignment) {
        CueAlignment.BottomLeft, CueAlignment.MiddleLeft, CueAlignment.TopLeft -> marginXPx
        CueAlignment.BottomRight, CueAlignment.MiddleRight, CueAlignment.TopRight -> viewportWidth - marginXPx - width
        else -> (viewportWidth - width) / 2
    }
    val y = layout.positionY?.let { fraction ->
        val anchor = (fraction * viewportHeight).toInt()
        when (layout.alignment) {
            CueAlignment.TopLeft, CueAlignment.TopCenter, CueAlignment.TopRight -> anchor
            CueAlignment.MiddleLeft, CueAlignment.MiddleCenter, CueAlignment.MiddleRight -> anchor - height / 2
            else -> anchor - height
        }
    } ?: when (layout.alignment) {
        CueAlignment.TopLeft, CueAlignment.TopCenter, CueAlignment.TopRight -> marginYPx
        CueAlignment.MiddleLeft, CueAlignment.MiddleCenter, CueAlignment.MiddleRight -> (viewportHeight - height) / 2
        // The implicit bottom stack anchors at the viewer's sub-position: 1.0 is the plain bottom
        // edge, and a smaller value lifts the stack. Explicit positions above are the author's word
        // and never move with it, which is mpv's sub-pos rule.
        else -> (viewportHeight * position).toInt() - marginYPx - height - stackedBottom
    }
    return CueOrigin(x, y)
}
