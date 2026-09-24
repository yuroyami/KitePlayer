package io.github.yuroyami.kiteplayer.internal

import io.github.yuroyami.kiteplayer.spi.OverlayImage
import io.github.yuroyami.kiteplayer.spi.SubtitleRasterizer
import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue
import io.github.yuroyami.kiteplayer.subtitle.SubtitleSafeArea
import kotlin.math.roundToInt

/** A rectangle of the output, in pixels. */
internal class PixelRect(val left: Int, val top: Int, val width: Int, val height: Int)

/** The safe area of a [width] by [height] output, in whole pixels, at least one pixel each way. */
internal fun safeAreaRect(width: Int, height: Int, safeArea: SubtitleSafeArea): PixelRect {
    val left = (width * safeArea.left).roundToInt()
    val right = (width * safeArea.right).roundToInt()
    val top = (height * safeArea.top).roundToInt()
    val bottom = (height * safeArea.bottom).roundToInt()
    return PixelRect(left, top, (width - left - right).coerceAtLeast(1), (height - top - bottom).coerceAtLeast(1))
}

/**
 * Lays [cues] out inside the safe area of a [width] by [height] output, as if the safe area were
 * the whole output, then moves the images by its left and top insets into output pixels. Rule 3 of
 * docs/subtitle-placement.md.
 */
internal fun SubtitleRasterizer.rasterizeInSafeArea(
    safeArea: SubtitleSafeArea,
    cues: List<SubtitleCue>,
    width: Int,
    height: Int,
    fontScale: Float,
    position: Float,
): List<OverlayImage> {
    val area = safeAreaRect(width, height, safeArea)
    val images = rasterize(cues, area.width, area.height, fontScale, position)
    if (area.left == 0 && area.top == 0) return images
    return images.map { image -> image.copy(x = image.x + area.left, y = image.y + area.top) }
}

/** Whether [rotationDegrees] is a quarter turn, which swaps the picture's width and height. */
internal fun isQuarterTurn(rotationDegrees: Int?): Boolean {
    val normalised = (((rotationDegrees ?: 0) % 360) + 360) % 360
    return normalised == 90 || normalised == 270
}
