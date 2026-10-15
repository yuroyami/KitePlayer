package io.github.yuroyami.kiteplayer.compose

import io.github.yuroyami.kiteplayer.PictureCrop
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
    /** Whether the picture is mirrored left to right before the turn: the frame's own and the viewer's together (#428). */
    val mirrored: Boolean = false,
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
    /** The frame's own mirror; the layout's folds the viewer's into it, as its turn does (#428). */
    mirrored: Boolean = false,
): VideoLayout? {
    if (areaWidth <= 0 || areaHeight <= 0) return null
    if (size.width <= 0 || size.height <= 0) return null

    // A nonsense pixel aspect that scales the width away falls back to the stored width: a
    // slightly wrong picture beats no picture, the same choice the output renderers make.
    val displayWidth = size.displayWidth.takeIf { it > 0 } ?: size.width
    // The viewer's turn and mirrors fold into the frame's own (#428), so the fit is of the turned picture.
    val orientation = transform.orient(rotationDegrees, mirrored)
    val turn = quarterTurn(orientation.rotationDegrees)
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
        mirrored = orientation.mirrored,
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

/**
 * The picture of [layout] as one textured quad: where each corner of the draw rectangle lands once
 * the picture is mirrored and turned, in the draw area's pixels, and which point of the image it
 * shows, as a fraction of the image's width and height from its top left.
 *
 * The corners are in triangle-strip order: the draw rectangle's top left, top right, bottom left
 * and bottom right, before the mirror and the turn move them. This is the law `drawVideoPicture`
 * draws with, the only part of [crop] the image keeps, mirrored about the layout's centre and then
 * turned about it, restated for a renderer that has no canvas transform to lean on, which is the
 * web's WebGL pass. The draw rectangle is rounded as `drawVideoPicture` rounds it, so the two put
 * every edge on the same pixel.
 */
internal class VideoQuad(
    val x: FloatArray,
    val y: FloatArray,
    val u: FloatArray,
    val v: FloatArray,
)

/** [VideoQuad] for [layout] over an image of [imageWidth] by [imageHeight], less [crop]. */
internal fun videoQuad(layout: VideoLayout, imageWidth: Int, imageHeight: Int, crop: PictureCrop? = null): VideoQuad {
    val left = layout.drawLeft.roundToInt().toFloat()
    val top = layout.drawTop.roundToInt().toFloat()
    val right = left + layout.drawWidth.roundToInt().coerceAtLeast(1)
    val bottom = top + layout.drawHeight.roundToInt().coerceAtLeast(1)
    val width = imageWidth.coerceAtLeast(1).toFloat()
    val height = imageHeight.coerceAtLeast(1).toFloat()
    val u0 = (crop?.left ?: 0) / width
    val u1 = (imageWidth - (crop?.right ?: 0)) / width
    val v0 = (crop?.top ?: 0) / height
    val v1 = (imageHeight - (crop?.bottom ?: 0)) / height

    val x = floatArrayOf(left, right, left, right)
    val y = floatArrayOf(top, top, bottom, bottom)
    val centerX = layout.centerX
    val centerY = layout.centerY
    // Exact quarter turns, so no corner lands a rounding error away from where the canvas puts it.
    val (cos, sin) = when (quarterTurn(layout.rotationDegrees)) {
        90 -> 0f to 1f
        180 -> -1f to 0f
        270 -> 0f to -1f
        else -> 1f to 0f
    }
    for (corner in 0 until 4) {
        // The mirror first, about the centre, then the turn about the same centre, the order the
        // canvas applies them in.
        val mirroredX = if (layout.mirrored) 2f * centerX - x[corner] else x[corner]
        val dx = mirroredX - centerX
        val dy = y[corner] - centerY
        x[corner] = centerX + dx * cos - dy * sin
        y[corner] = centerY + dx * sin + dy * cos
    }
    return VideoQuad(
        x = x,
        y = y,
        u = floatArrayOf(u0, u1, u0, u1),
        v = floatArrayOf(v0, v0, v1, v1),
    )
}
