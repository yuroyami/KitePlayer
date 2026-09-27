package io.github.yuroyami.kiteplayer.internal

import io.github.yuroyami.kiteplayer.spi.OverlayImage
import io.github.yuroyami.kiteplayer.spi.SubtitleOverlayLimitException
import io.github.yuroyami.kiteplayer.spi.SubtitleRasterizer
import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue
import io.github.yuroyami.kiteplayer.subtitle.SubtitleSafeArea
import kotlin.coroutines.cancellation.CancellationException
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

/** Why subtitles of an overlay were not drawn. Each cause warns on its own. */
internal enum class UndrawnSubtitles { Limited, Failed }

/**
 * Lays [cues] out inside the safe area of a [width] by [height] output, as if the safe area were
 * the whole output, then moves the images by its left and top insets into output pixels. Rule 3 of
 * docs/subtitle-placement.md.
 *
 * The limits of [SubtitleRasterizer.Companion] hold for any rasterizer here. Only what
 * [SubtitleRasterizer.limitCues] keeps is passed on, a safe area larger than
 * [SubtitleRasterizer.MAX_VIEWPORT_SIZE] draws nothing, and images past [SubtitleRasterizer.MAX_CUES]
 * or [SubtitleRasterizer.overlayPixelBudget] are dropped in order. Each of those tells [notDrawn].
 *
 * @return the images, or null when the rasterizer failed, so the caller clears what was shown.
 */
internal fun SubtitleRasterizer.rasterizeWithinLimits(
    safeArea: SubtitleSafeArea,
    cues: List<SubtitleCue>,
    width: Int,
    height: Int,
    fontScale: Float,
    position: Float,
    notDrawn: (UndrawnSubtitles, String) -> Unit,
): List<OverlayImage>? {
    val area = safeAreaRect(width, height, safeArea)
    if (area.width > SubtitleRasterizer.MAX_VIEWPORT_SIZE || area.height > SubtitleRasterizer.MAX_VIEWPORT_SIZE) {
        notDrawn(
            UndrawnSubtitles.Limited,
            "the subtitle area is ${area.width}x${area.height} pixels, and the limit is " +
                "${SubtitleRasterizer.MAX_VIEWPORT_SIZE} on a side",
        )
        return emptyList()
    }
    val admitted = SubtitleRasterizer.limitCues(cues)
    if (admitted !== cues) {
        notDrawn(
            UndrawnSubtitles.Limited,
            "the subtitles on screen went past the limit of ${SubtitleRasterizer.MAX_CUES} cues, " +
                "${SubtitleRasterizer.MAX_CUE_LENGTH} characters in a cue, " +
                "${SubtitleRasterizer.MAX_OVERLAY_TEXT_LENGTH} characters or " +
                "${SubtitleRasterizer.MAX_OVERLAY_SPANS} styled spans",
        )
    }
    val images = try {
        rasterize(admitted, area.width, area.height, fontScale, position)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (limit: SubtitleOverlayLimitException) {
        notDrawn(UndrawnSubtitles.Limited, "${limit.skipped} cues went past the pixel limit of the subtitle images")
        limit.drawn
    } catch (failure: Exception) {
        notDrawn(UndrawnSubtitles.Failed, "the subtitle rasterizer failed: $failure")
        return null
    }
    val kept = keptWithinBudget(images, SubtitleRasterizer.overlayPixelBudget(area.width, area.height))
    if (kept.size < images.size) {
        notDrawn(
            UndrawnSubtitles.Limited,
            "the rasterizer returned ${images.size} subtitle images, and ${images.size - kept.size} " +
                "went past the limit of ${SubtitleRasterizer.MAX_CUES} images or its pixel limit",
        )
    }
    if (area.left == 0 && area.top == 0) return kept
    return kept.map { image -> image.copy(x = image.x + area.left, y = image.y + area.top) }
}

/** The leading [images] that fit [SubtitleRasterizer.MAX_CUES] images and [budget] pixels. */
private fun keptWithinBudget(images: List<OverlayImage>, budget: Long): List<OverlayImage> {
    var used = 0L
    for ((index, image) in images.withIndex()) {
        used += image.bitmap.width.toLong() * image.bitmap.height.toLong()
        if (index >= SubtitleRasterizer.MAX_CUES || used > budget) return images.subList(0, index).toList()
    }
    return images
}

/** Whether [rotationDegrees] is a quarter turn, which swaps the picture's width and height. */
internal fun isQuarterTurn(rotationDegrees: Int?): Boolean {
    val normalised = (((rotationDegrees ?: 0) % 360) + 360) % 360
    return normalised == 90 || normalised == 270
}
