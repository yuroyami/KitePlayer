package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.spi.OverlayImage
import io.github.yuroyami.kiteplayer.subtitle.CueAlignment
import io.github.yuroyami.kiteplayer.subtitle.CueDisplayAlign
import io.github.yuroyami.kiteplayer.subtitle.CueRegion
import io.github.yuroyami.kiteplayer.subtitle.CueShowBackground
import io.github.yuroyami.kiteplayer.subtitle.RgbaBitmap
import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue
import kotlin.math.roundToInt

/**
 * The images of the [cues] on screen in one TTML [region], laid out as one group (#492): the
 * region's background first, then each paragraph in document order, one under the other, the block
 * placed at the top, middle or bottom of the space inside the padding, and with
 * [CueRegion.clip] every image cut to the region's box. docs/subtitle-regions.md has the rule.
 *
 * [drawText] is the rasterizer's own: each paragraph is handed to it as a positioned cue whose
 * lines break at the width inside the padding, so every built-in rasterizer lays a region out
 * through this one function.
 */
internal fun regionImages(
    region: CueRegion,
    cues: List<SubtitleCue.Text>,
    viewportWidth: Int,
    viewportHeight: Int,
    budget: OverlayPixels,
    drawText: (cue: SubtitleCue.Text, stackedBottom: Int, budget: OverlayPixels) -> OverlayImage?,
): List<OverlayImage> {
    val left = (region.left * viewportWidth).roundToInt()
    val top = (region.top * viewportHeight).roundToInt()
    val right = ((region.left + region.width) * viewportWidth).roundToInt()
    val bottom = ((region.top + region.height) * viewportHeight).roundToInt()
    if (right <= left || bottom <= top) return emptyList()
    val width = right - left
    val height = bottom - top
    val innerLeft = left + (region.padding.left * width).roundToInt()
    val innerRight = right - (region.padding.right * width).roundToInt()
    val innerTop = top + (region.padding.top * height).roundToInt()
    val innerBottom = bottom - (region.padding.bottom * height).roundToInt()

    val out = ArrayList<OverlayImage>()
    val showsText = cues.any { cue -> cue.spans.any { it.text.isNotEmpty() } }
    if (region.backgroundColor ushr 24 != 0 && (region.showBackground == CueShowBackground.Always || showsText)) {
        filledImage(left, top, right, bottom, viewportWidth, viewportHeight, region.backgroundColor, budget)?.let { out += it }
        if (budget.exhausted) return out
    }
    val innerWidth = innerRight - innerLeft
    if (innerWidth <= 0) return out

    val block = ArrayList<OverlayImage>()
    for (cue in cues.sortedBy { it.layout.regionOrder }) {
        if (cue.spans.none { it.text.isNotEmpty() }) continue
        drawText(inRegion(cue, innerLeft, innerWidth, innerTop, viewportWidth, viewportHeight), 0, budget)?.let { block += it }
        if (budget.exhausted) break
    }
    // Each paragraph starts where the one above it ended; the block sits as the region says.
    val total = block.sumOf { it.bitmap.height }
    var y = when (region.displayAlign) {
        CueDisplayAlign.Before -> innerTop
        CueDisplayAlign.Center -> innerTop + (innerBottom - innerTop - total) / 2
        CueDisplayAlign.After -> innerBottom - total
    }
    for (image in block) {
        val moved = image.copy(y = y)
        y += image.bitmap.height
        if (!region.clip) out += moved else clipped(moved, left, top, right, bottom)?.let { out += it }
    }
    return out
}

/**
 * [cue] as a cue the rasterizer places by itself: anchored on the top edge of the space inside the
 * padding, at its left, middle or right as its alignment says, with margins that leave exactly that
 * width for its lines.
 */
private fun inRegion(
    cue: SubtitleCue.Text,
    innerLeft: Int,
    innerWidth: Int,
    innerTop: Int,
    viewportWidth: Int,
    viewportHeight: Int,
): SubtitleCue.Text {
    val (alignment, anchor) = when (cue.layout.alignment) {
        CueAlignment.BottomLeft, CueAlignment.MiddleLeft, CueAlignment.TopLeft -> CueAlignment.TopLeft to innerLeft
        CueAlignment.BottomRight, CueAlignment.MiddleRight, CueAlignment.TopRight -> CueAlignment.TopRight to innerLeft + innerWidth
        else -> CueAlignment.TopCenter to innerLeft + innerWidth / 2
    }
    return cue.copy(
        layout = cue.layout.copy(
            alignment = alignment,
            // Half a pixel in, so the placement's own rounding down lands on the pixel meant.
            positionX = (anchor + 0.5f) / viewportWidth,
            positionY = (innerTop + 0.5f) / viewportHeight,
            marginLeft = 0f,
            marginRight = 1f - (innerWidth + 0.5f) / viewportWidth,
            region = null,
        ),
    )
}

/** The part of [image] inside the box from [left], [top] to [right], [bottom], or null when none is. */
private fun clipped(image: OverlayImage, left: Int, top: Int, right: Int, bottom: Int): OverlayImage? {
    val source = image.bitmap
    val keptLeft = maxOf(image.x, left)
    val keptTop = maxOf(image.y, top)
    val keptRight = minOf(image.x + source.width, right)
    val keptBottom = minOf(image.y + source.height, bottom)
    if (keptRight <= keptLeft || keptBottom <= keptTop) return null
    if (keptLeft == image.x && keptTop == image.y && keptRight - keptLeft == source.width && keptBottom - keptTop == source.height) {
        return image
    }
    val width = keptRight - keptLeft
    val height = keptBottom - keptTop
    return OverlayImage(keptLeft, keptTop, RgbaBitmap(width, height, crop(source, keptLeft - image.x, keptTop - image.y, width, height)))
}

/**
 * The box from [left], [top] to [right], [bottom], cut to the viewport, filled with [argb], a
 * straight-alpha colour, as the premultiplied pixels every consumer reads. Null when nothing of it
 * is on the viewport or it does not fit [budget].
 */
private fun filledImage(
    left: Int,
    top: Int,
    right: Int,
    bottom: Int,
    viewportWidth: Int,
    viewportHeight: Int,
    argb: Int,
    budget: OverlayPixels,
): OverlayImage? {
    val keptLeft = left.coerceIn(0, viewportWidth)
    val keptTop = top.coerceIn(0, viewportHeight)
    val width = right.coerceIn(0, viewportWidth) - keptLeft
    val height = bottom.coerceIn(0, viewportHeight) - keptTop
    if (width <= 0 || height <= 0 || !budget.take(width, height)) return null
    val alpha = argb ushr 24
    fun premultiplied(channel: Int): Byte = ((channel * alpha + 127) / 255).toByte()
    val pixel = byteArrayOf(
        premultiplied((argb shr 16) and 0xFF),
        premultiplied((argb shr 8) and 0xFF),
        premultiplied(argb and 0xFF),
        alpha.toByte(),
    )
    val pixels = ByteArray(width * height * 4)
    for (at in pixels.indices step 4) pixel.copyInto(pixels, at)
    return OverlayImage(keptLeft, keptTop, RgbaBitmap(width, height, pixels))
}
