package io.github.yuroyami.kiteplayer.compose

import io.github.yuroyami.kiteplayer.VideoScale
import io.github.yuroyami.kiteplayer.VideoSize
import io.github.yuroyami.kiteplayer.VideoTransform
import kotlin.math.roundToInt

/**
 * Where one picture lands on the draw area, in output pixels. The same law the output renderers
 * obey, restated here because their versions are internal to their own modules: the pixel aspect
 * scales the stored width, a quarter turn exchanges the sides, the fit is integer, centred and
 * symmetric.
 *
 * The rectangle [left]/[top]/[right]/[bottom] is the picture's footprint after the turn. The
 * rectangle the bitmap is drawn into is [drawLeft]/[drawTop] by [drawWidth]/[drawHeight], which
 * for a quarter turn is the footprint with its sides exchanged about the same centre: rotating
 * it by [rotationDegrees] about ([centerX], [centerY]) lands it exactly on the footprint. The
 * draw rectangle is fractional because the exchange halves an odd side.
 */
internal data class VideoLayout(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val rotationDegrees: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    private val quarterTurned: Boolean get() = rotationDegrees == 90 || rotationDegrees == 270

    val drawWidth: Float get() = (if (quarterTurned) height else width).toFloat()
    val drawHeight: Float get() = (if (quarterTurned) width else height).toFloat()

    val drawLeft: Float get() = centerX - drawWidth / 2f
    val drawTop: Float get() = centerY - drawHeight / 2f
}

/**
 * Fits a frame into the draw area, centred, keeping its shape, or returns null when there is
 * nothing to draw. Integer arithmetic on purpose: exactly one axis fills the area exactly and
 * the other splits its remainder in two, so the letterbox is symmetric to the pixel.
 */
internal fun videoLayout(
    areaWidth: Int,
    areaHeight: Int,
    size: VideoSize,
    rotationDegrees: Int,
    mode: VideoScale = VideoScale.Fit,
    transform: VideoTransform = VideoTransform.Identity,
): VideoLayout? {
    if (areaWidth <= 0 || areaHeight <= 0) return null
    if (size.width <= 0 || size.height <= 0) return null

    // A nonsense pixel aspect that scales the width away falls back to the stored width: a
    // slightly wrong picture beats no picture, the same choice the output renderers make.
    val displayWidth = size.displayWidth.takeIf { it > 0 } ?: size.width
    val turn = quarterTurn(rotationDegrees)
    val quarterTurned = turn == 90 || turn == 270
    val aspect = transform.aspectOverride
    val contentWidth: Long
    val contentHeight: Long
    if (aspect != null && aspect > 0f && aspect.isFinite()) {
        // The forced aspect describes the picture AS PRESENTED, after the turn: a viewer who
        // forces 16:9 sees 16:9 whatever the container stored. Only the ratio matters to the
        // fit, so it is spelled over a fixed denominator.
        contentWidth = (aspect * 100_000f).toLong().coerceAtLeast(1)
        contentHeight = 100_000L
    } else {
        contentWidth = (if (quarterTurned) size.height else displayWidth).toLong()
        contentHeight = (if (quarterTurned) displayWidth else size.height).toLong()
    }

    // Fit letterboxes, Fill overhangs (the caller clips), Stretch takes the area whole; the
    // pixel aspect and the turn are already folded into the content shape for all three.
    val fitsByHeight = contentWidth * areaHeight <= contentHeight * areaWidth
    val heightBound = if (mode == VideoScale.Fill) !fitsByHeight else fitsByHeight
    var destinationWidth: Int
    var destinationHeight: Int
    if (mode == VideoScale.Stretch) {
        destinationWidth = areaWidth
        destinationHeight = areaHeight
    } else if (heightBound) {
        destinationHeight = areaHeight
        destinationWidth = (contentWidth * areaHeight / contentHeight).toInt().coerceAtLeast(1)
    } else {
        destinationWidth = areaWidth
        destinationHeight = (contentHeight * areaWidth / contentWidth).toInt().coerceAtLeast(1)
    }
    var left = (areaWidth - destinationWidth) / 2
    var top = (areaHeight - destinationHeight) / 2
    // The zoom scales the fitted rectangle about its centre, and the pan then moves it by a
    // fraction of its own drawn size, mpv's own composition order. Both after the fit, so they
    // compose with every mode, and the caller clips the overhang exactly as it does for Fill.
    if (transform.zoom != 1f) {
        destinationWidth = (destinationWidth * transform.zoom).roundToInt().coerceAtLeast(1)
        destinationHeight = (destinationHeight * transform.zoom).roundToInt().coerceAtLeast(1)
        left = (areaWidth - destinationWidth) / 2
        top = (areaHeight - destinationHeight) / 2
    }
    if (transform.panX != 0f) left += (transform.panX * destinationWidth).roundToInt()
    if (transform.panY != 0f) top += (transform.panY * destinationHeight).roundToInt()
    return VideoLayout(
        left = left,
        top = top,
        right = left + destinationWidth,
        bottom = top + destinationHeight,
        rotationDegrees = turn,
    )
}

/**
 * The quarter turn that will actually be drawn. Only 0, 90, 180 and 270; anything else is drawn
 * unrotated, because a slightly skewed picture is not worth a black screen. Negative and
 * out-of-range values are normalised first.
 */
internal fun quarterTurn(rotationDegrees: Int): Int {
    val normalised = ((rotationDegrees % 360) + 360) % 360
    return if (normalised == 90 || normalised == 180 || normalised == 270) normalised else 0
}
