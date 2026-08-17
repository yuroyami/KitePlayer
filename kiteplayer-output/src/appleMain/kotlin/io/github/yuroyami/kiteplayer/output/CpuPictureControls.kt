package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.VideoAdjustments
import io.github.yuroyami.kiteplayer.VideoTransform

/**
 * The picture controls and framing law for the UIKit and AppKit CPU fallbacks (17.11 SOL-R14).
 *
 * Both fallbacks draw with Core Graphics rather than a shader, so the same law the Metal path
 * packs into uniforms is written here once for bytes and Core Graphics rectangles. Gamma stays
 * absent by design, exactly as [VideoAdjustments] says.
 */

/**
 * Applies [VideoAdjustments.toColorMatrix] to tightly packed RGBA bytes.
 *
 * Returns [rgba] itself for the neutral value, so an untouched picture copies nothing. Alpha is
 * carried through: the matrix's alpha row is identity and these buffers are opaque anyway.
 */
internal fun adjustRgba(rgba: ByteArray, adjustments: VideoAdjustments): ByteArray {
    if (adjustments.isIdentity) return rgba
    val m = adjustments.toColorMatrix()
    // toColorMatrix offsets in 0..1; these bytes are in 0..255, so the fifth column scales.
    val offsetR = m[4] * 255f
    val offsetG = m[9] * 255f
    val offsetB = m[14] * 255f
    val out = ByteArray(rgba.size)
    var at = 0
    while (at + 3 < rgba.size) {
        val r = (rgba[at].toInt() and 0xFF).toFloat()
        val g = (rgba[at + 1].toInt() and 0xFF).toFloat()
        val b = (rgba[at + 2].toInt() and 0xFF).toFloat()
        out[at] = clampToByte(m[0] * r + m[1] * g + m[2] * b + offsetR)
        out[at + 1] = clampToByte(m[5] * r + m[6] * g + m[7] * b + offsetG)
        out[at + 2] = clampToByte(m[10] * r + m[11] * g + m[12] * b + offsetB)
        out[at + 3] = rgba[at + 3]
        at += 4
    }
    return out
}

private fun clampToByte(value: Float): Byte {
    val rounded = value + 0.5f
    return when {
        rounded <= 0f -> 0
        rounded >= 255f -> -1 // 0xFF
        else -> rounded.toInt().toByte()
    }
}

/**
 * The width the picture is PRESENTED at, once [VideoTransform.aspectOverride] has had its say.
 *
 * [presentedWidth] and [presentedHeight] are the picture's own presented size, pixel aspect and
 * rotation already folded in, which is the same point in the order the Metal geometry uses.
 */
internal fun framedPresentedWidth(
    presentedWidth: Int,
    presentedHeight: Int,
    transform: VideoTransform,
): Int {
    val aspect = transform.aspectOverride?.takeIf { it > 0f && it.isFinite() } ?: return presentedWidth
    val forced = (presentedHeight.toDouble() * aspect + 0.5).toInt()
    return forced.coerceIn(1, Int.MAX_VALUE)
}

/** True when the framing needs the drawing pass rather than only a different presented size. */
internal fun VideoTransform.needsDrawingPass(): Boolean =
    zoom != 1f || panX != 0f || panY != 0f

/**
 * Zoom and pan as a Core Graphics translate-then-scale over a canvas of [canvasWidth] by
 * [canvasHeight], returned as `[translateX, translateY, scale]`.
 *
 * Core Graphics applies the LAST concatenation to the point first, so a caller concatenates this
 * translate, then this scale, then its rotation: the rotated picture is magnified about the
 * canvas centre and then moved by a fraction of its own drawn size. Pan is positive-down while
 * Core Graphics y grows upward, hence the sign.
 */
internal fun framingConcat(
    canvasWidth: Int,
    canvasHeight: Int,
    transform: VideoTransform,
): DoubleArray {
    val zoom = transform.zoom.takeIf { it > 0f && it.isFinite() }?.toDouble() ?: 1.0
    val drawnWidth = canvasWidth * zoom
    val drawnHeight = canvasHeight * zoom
    val x = (canvasWidth - drawnWidth) / 2.0 + transform.panX * drawnWidth
    val y = (canvasHeight - drawnHeight) / 2.0 - transform.panY * drawnHeight
    return doubleArrayOf(x, y, zoom)
}
