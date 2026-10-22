package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.spi.OverlayImage
import io.github.yuroyami.kiteplayer.spi.SubtitleOverlayLimitException
import io.github.yuroyami.kiteplayer.spi.SubtitleRasterizer
import io.github.yuroyami.kiteplayer.subtitle.CueLayout
import io.github.yuroyami.kiteplayer.subtitle.CueRegion
import io.github.yuroyami.kiteplayer.subtitle.CueStyle
import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue
import kotlin.math.ceil

/**
 * The pixels of one overlay's images so far, against [SubtitleRasterizer.overlayPixelBudget].
 * A rasterizer asks before it allocates an image, and the first image that does not fit ends the
 * overlay.
 */
internal class OverlayPixels(viewportWidth: Int, viewportHeight: Int) {
    private val budget = SubtitleRasterizer.overlayPixelBudget(viewportWidth, viewportHeight)
    private var used = 0L

    /** True once an image did not fit. */
    var exhausted: Boolean = false
        private set

    /** True, with [pixels] counted, when they still fit. */
    fun take(pixels: Long): Boolean {
        if (exhausted || pixels > budget - used) {
            exhausted = true
            return false
        }
        used += pixels
        return true
    }

    /** [take] for a [width] by [height] image. */
    fun take(width: Int, height: Int): Boolean = take(width.toLong() * height.toLong())
}

/**
 * The loop that every built-in rasterizer runs: the limits of [SubtitleRasterizer.Companion], the
 * implicit bottom stack and the pixel budget. [drawText] lays out one text cue above a stack that
 * is `stackedBottom` pixels high, asks the budget before it allocates, and returns null when it
 * draws nothing.
 *
 * @throws SubtitleOverlayLimitException when an image does not fit the budget.
 */
internal fun rasterizeCues(
    cues: List<SubtitleCue>,
    viewportWidth: Int,
    viewportHeight: Int,
    drawText: (cue: SubtitleCue.Text, stackedBottom: Int, budget: OverlayPixels) -> OverlayImage?,
): List<OverlayImage> {
    if (viewportWidth !in 1..SubtitleRasterizer.MAX_VIEWPORT_SIZE) return emptyList()
    if (viewportHeight !in 1..SubtitleRasterizer.MAX_VIEWPORT_SIZE) return emptyList()
    val admitted = SubtitleRasterizer.limitCues(cues)
    val budget = OverlayPixels(viewportWidth, viewportHeight)
    val images = mutableListOf<OverlayImage>()
    var stackedBottom = 0
    // ASS `Collisions: Reverse` puts the NEWEST cue at the bottom, so the pile is built from
    // the end of the list and turned back the right way round: the images keep the caller's
    // order, which is the draw order, and only the stack offsets change.
    val reversed = admitted.stacksLastAtBottom
    val order = if (reversed) admitted.asReversed() else admitted
    // Each TTML region is laid out once, as one group, at the place of the first of its cues (#492).
    val placedRegions = HashSet<CueRegion>()
    for ((index, cue) in order.withIndex()) {
        val region = (cue as? SubtitleCue.Text)?.layout?.region
        when {
            region != null -> if (placedRegions.add(region)) {
                val members = order.filter { it is SubtitleCue.Text && it.layout.region == region }.map { it as SubtitleCue.Text }
                val group = regionImages(region, members, viewportWidth, viewportHeight, budget, drawText)
                // Turned now so that the final turn of a reversed pile leaves the group in its own order.
                images += if (reversed) group.asReversed() else group
            }
            cue is SubtitleCue.Text -> drawText(cue, stackedBottom, budget)?.let { image ->
                images += image
                if (cue.layout.usesImplicitBottomStack) {
                    stackedBottom += image.bitmap.height + STACK_GAP_PX
                }
            }
            cue is SubtitleCue.Bitmap -> for (picture in cue.regions) {
                // Origin and extent both scale from the authored canvas to the viewport.
                regionImage(picture, viewportWidth, viewportHeight, budget)?.let { images += it }
                if (budget.exhausted) break
            }
        }
        if (budget.exhausted) {
            val drawn = if (reversed) images.asReversed() else images
            throw SubtitleOverlayLimitException(drawn.toList(), skipped = order.size - index)
        }
    }
    return if (reversed) images.asReversed() else images
}

/** The gap between two cues of the implicit bottom stack, in pixels. */
internal const val STACK_GAP_PX: Int = 8

/**
 * The font size of [style] in viewport pixels: about one twentieth of the viewport height unless
 * the cue says otherwise, scaled by the cue's [CueStyle.relativeSize], by the viewer's [fontScale]
 * and by the authoring resolution when the format declared one. From 1 pixel up to the viewport
 * height, whatever the cue asked for.
 */
internal fun cueFontSizePx(style: CueStyle, layout: CueLayout, viewportHeight: Int, fontScale: Float): Float {
    val default = viewportHeight / 20f
    val authoredScale = layout.authoredHeight?.takeIf { it > 0 }?.let { viewportHeight.toFloat() / it } ?: 1f
    val relative = style.relativeSize.takeIf { it.isFinite() && it > 0f } ?: 1f
    val size = (style.fontSizePx?.times(authoredScale) ?: default) * relative * fontScale
    return if (size.isNaN()) default.coerceAtLeast(1f) else size.coerceIn(1f, viewportHeight.toFloat().coerceAtLeast(1f))
}

/** The outline width of [style] in pixels, from 0 up to the [fontSizePx] it outlines. */
internal fun cueOutlinePx(style: CueStyle, fontScale: Float, fontSizePx: Float): Float {
    val width = style.outlineWidthPx * fontScale
    return if (width.isNaN()) 0f else width.coerceIn(0f, fontSizePx)
}

/**
 * The padding of the viewer's box behind each line, in pixels, or 0 when the box is transparent.
 * At most [MAX_BOX_PAD_PX]: a pad past that is no longer a box behind the text.
 */
internal fun cueBoxPadPx(style: CueStyle, fontScale: Float): Int {
    if (style.backgroundColor ushr 24 == 0) return 0
    val pad = ceil((style.backgroundPaddingPx * fontScale).toDouble())
    return if (pad.isNaN()) 0 else pad.coerceIn(0.0, MAX_BOX_PAD_PX.toDouble()).toInt()
}

private const val MAX_BOX_PAD_PX: Int = 64

/**
 * The width that the margins of [layout] leave in a [viewportWidth] viewport, in pixels. Never more
 * than the viewport, whatever the margins say, and 0 when they leave nothing.
 */
internal fun cueSafeWidth(layout: CueLayout, viewportWidth: Int): Int {
    val fraction = 1f - layout.marginLeft - layout.marginRight
    if (!(fraction > 0f)) return 0
    return (viewportWidth * fraction.coerceAtMost(1f)).toInt()
}
