package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.VideoScale
import io.github.yuroyami.kiteplayer.VideoSize
import io.github.yuroyami.kiteplayer.VideoTransform
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import kotlin.math.roundToInt

/*
 * The one geometry law, shared by every renderer that draws into a rectangle.
 *
 * It lived inside AndroidSurfaceVideoRenderer until the web canvas needed the same arithmetic
 * canvas. Copying it would have put a hundred lines of pixel-aspect, quarter-turn, zoom and pan
 * reasoning in two places that must agree forever and have no way to notice when they stop, and the
 * failure would be a picture subtly the wrong shape on one platform. Nothing here is Android: it is
 * integer arithmetic over VideoSize, VideoScale and VideoTransform, all of which live in core.
 */

/**
 * Where one picture lands on the canvas, in canvas pixels.
 *
 * The rectangle named by [left], [top], [right] and [bottom] is where the picture ends up on screen,
 * after the turn. The rectangle the bitmap is actually drawn into is [drawLeft] to [drawRight] and
 * [drawTop] to [drawBottom], which for a quarter turn is the same rectangle with its sides exchanged
 * about the same centre: turning that one by [rotationDegrees] about ([centerX], [centerY]) is what
 * lands it exactly on the first one.
 *
 * The drawing rectangle is in fractions of a pixel because the exchange halves an odd side, and a
 * rounded half pixel is a visible seam of stale black down one edge of the picture.
 */
internal data class FrameLayout(
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
    val drawRight: Float get() = centerX + drawWidth / 2f
    val drawBottom: Float get() = centerY + drawHeight / 2f
}

/**
 * Fits a frame into a canvas, centred, keeping its shape, or returns null when there is nothing to
 * draw.
 *
 * Three things decide the shape and all three come from the frame rather than from the canvas. The
 * stored width and height are the pixels there are; the pixel aspect ratio says how wide those pixels
 * are meant to be shown, so anamorphic content is not drawn squeezed; and a quarter turn moves that
 * stretch onto the other axis, because the picture's stored width runs vertically afterwards.
 *
 * The fit is integer arithmetic on purpose. Exactly one axis fills the canvas exactly and the other is
 * centred with the remainder split in two, so the letterbox is symmetric to the pixel and the picture
 * never overhangs the canvas by a rounding error.
 */
internal fun frameLayout(
    canvasWidth: Int,
    canvasHeight: Int,
    size: VideoSize,
    rotationDegrees: Int,
    mode: VideoScale = VideoScale.Fit,
    transform: VideoTransform = VideoTransform.Identity,
): FrameLayout? {
    if (canvasWidth <= 0 || canvasHeight <= 0) return null
    if (size.width <= 0 || size.height <= 0) return null

    // The core rule for a non-square pixel aspect, borrowed rather than restated so the two cannot
    // drift. A nonsense aspect that scales the width away leaves the stored width, which shows the
    // picture slightly wrong instead of showing nothing at all.
    val displayWidth = size.displayWidth.takeIf { it > 0 } ?: size.width
    val turn = quarterTurn(rotationDegrees)
    val quarterTurned = turn == 90 || turn == 270
    val aspect = transform.aspectOverride
    val contentWidth: Long
    val contentHeight: Long
    if (aspect != null && aspect > 0f && aspect.isFinite()) {
        // The forced aspect describes the picture AS PRESENTED, after the turn, and only its
        // ratio matters to the fit: the same words as the Compose geometry, so no drift.
        contentWidth = (aspect * 100_000f).toLong().coerceAtLeast(1)
        contentHeight = 100_000L
    } else {
        contentWidth = (if (quarterTurned) size.height else displayWidth).toLong()
        contentHeight = (if (quarterTurned) displayWidth else size.height).toLong()
    }

    // Fit keeps the smaller axis ratio and letterboxes; Fill keeps the larger and overhangs the
    // canvas, which the Surface's own bounds crop; Stretch takes the canvas as it is. The pixel
    // aspect and rotation above are already folded into the content shape for all three.
    val fitsByHeight = contentWidth * canvasHeight <= contentHeight * canvasWidth
    val fillAxisFlipped = when (mode) {
        VideoScale.Fill -> !fitsByHeight
        else -> fitsByHeight
    }
    var destinationWidth: Int
    var destinationHeight: Int
    if (mode == VideoScale.Stretch) {
        destinationWidth = canvasWidth
        destinationHeight = canvasHeight
    } else if (fillAxisFlipped) {
        destinationHeight = canvasHeight
        destinationWidth = (contentWidth * canvasHeight / contentHeight).toInt().coerceAtLeast(1)
    } else {
        destinationWidth = canvasWidth
        destinationHeight = (contentHeight * canvasWidth / contentWidth).toInt().coerceAtLeast(1)
    }
    var left = (canvasWidth - destinationWidth) / 2
    var top = (canvasHeight - destinationHeight) / 2
    // Zoom about the centre, then pan by a fraction of the drawn size, after the fit so both
    // compose with every mode. The overhang is cropped by the canvas bounds like Fill's is.
    if (transform.zoom != 1f) {
        destinationWidth = (destinationWidth * transform.zoom).roundToInt().coerceAtLeast(1)
        destinationHeight = (destinationHeight * transform.zoom).roundToInt().coerceAtLeast(1)
        left = (canvasWidth - destinationWidth) / 2
        top = (canvasHeight - destinationHeight) / 2
    }
    if (transform.panX != 0f) left += (transform.panX * destinationWidth).roundToInt()
    if (transform.panY != 0f) top += (transform.panY * destinationHeight).roundToInt()
    return FrameLayout(
        left = left,
        top = top,
        right = left + destinationWidth,
        bottom = top + destinationHeight,
        rotationDegrees = turn,
    )
}

/**
 * The quarter turn this renderer will actually draw, given a frame's [VideoFrame.rotationDegrees].
 *
 * Only 0, 90, 180 and 270 are drawn. A display matrix can in principle describe any affine transform,
 * and a value that is not a quarter turn comes back as 0, which shows the picture as stored rather than
 * refusing to show it at all: a slightly skewed picture is not worth a black screen. Negative and
 * out-of-range values are normalised first, so a source that reports -90 rather than 270 is drawn the
 * same way.
 */
internal fun quarterTurn(rotationDegrees: Int): Int {
    val normalised = ((rotationDegrees % 360) + 360) % 360
    return if (normalised == 90 || normalised == 180 || normalised == 270) normalised else 0
}
