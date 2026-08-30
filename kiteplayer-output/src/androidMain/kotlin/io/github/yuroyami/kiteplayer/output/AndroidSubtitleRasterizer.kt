package io.github.yuroyami.kiteplayer.output

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.TypefaceSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import io.github.yuroyami.kiteplayer.spi.OverlayImage
import io.github.yuroyami.kiteplayer.spi.SubtitleRasterizer
import io.github.yuroyami.kiteplayer.subtitle.CueAlignment
import io.github.yuroyami.kiteplayer.subtitle.CueStyle
import io.github.yuroyami.kiteplayer.subtitle.RgbaBitmap
import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue
import java.nio.ByteBuffer

/**
 * The Android text raster engine (S4.c): each active text cue becomes one image through
 * [StaticLayout], the platform's own line breaker, so wrapping, bidi and shaping are Android's
 * and not this project's. Bitmap cues pass their pixels through untouched.
 *
 * Placement is the cue's own [io.github.yuroyami.kiteplayer.subtitle.CueLayout]: margins carve
 * the safe area, alignment picks the corner or centre, stacking is the engine's draw order
 * (this rasteriser positions each cue independently; overlapping cues stack bottom-up because
 * the engine hands them over lowest layer first and later images draw above earlier ones).
 *
 * The outline the style asks for is drawn the way every subtitle renderer fakes it cheaply and
 * well: the text painted first in the outline colour with a stroke, then filled on top.
 */
internal class AndroidSubtitleRasterizer : SubtitleRasterizer {

    override fun rasterize(
        cues: List<SubtitleCue>,
        viewportWidth: Int,
        viewportHeight: Int,
        fontScale: Float,
        position: Float,
    ): List<OverlayImage> {
        if (viewportWidth <= 0 || viewportHeight <= 0) return emptyList()
        val images = mutableListOf<OverlayImage>()
        var stackedBottom = 0
        for (cue in cues) {
            when (cue) {
                is SubtitleCue.Text -> rasterizeText(cue, viewportWidth, viewportHeight, fontScale, stackedBottom, position)
                    ?.let { image ->
                        images += image
                        if (cue.layout.usesImplicitBottomStack) {
                            stackedBottom += image.bitmap.height + STACK_GAP_PX
                        }
                    }
                is SubtitleCue.Bitmap -> cue.regions.forEach { region ->
                    // Authored pixels: scale placement from the authored canvas to the viewport.
                    val sx = viewportWidth.toFloat() / region.canvasWidth.coerceAtLeast(1)
                    val sy = viewportHeight.toFloat() / region.canvasHeight.coerceAtLeast(1)
                    images += OverlayImage(
                        x = (region.x * sx).toInt(),
                        y = (region.y * sy).toInt(),
                        bitmap = region.bitmap,
                    )
                }
            }
        }
        return images
    }

    private fun rasterizeText(
        cue: SubtitleCue.Text,
        viewportWidth: Int,
        viewportHeight: Int,
        fontScale: Float,
        stackedBottom: Int,
        position: Float,
    ): OverlayImage? {
        val layoutSpec = cue.layout
        // The classic subtitle size rule: about one twentieth of the picture height, scaled by
        // the user's preference and by the authoring resolution when the format declared one.
        val authoredScale = layoutSpec.authoredHeight?.let { viewportHeight.toFloat() / it } ?: 1f
        fun sizeOf(style: CueStyle) =
            (style.fontSizePx?.times(authoredScale) ?: (viewportHeight / 20f)) * fontScale
        val baseSize = cue.spans.firstOrNull()?.style?.let { sizeOf(it) } ?: (viewportHeight / 20f)

        val text = SpannableStringBuilder()
        val runs = mutableListOf<StyleRun>()
        var baseColor = Color.WHITE
        cue.spans.forEachIndexed { index, span ->
            val start = text.length
            text.append(span.text)
            val end = text.length
            val style = span.style
            if (index == 0) baseColor = style.primaryColor
            if (start != end) runs += StyleRun(start, end, style)
            if (style.bold && style.italic) text.setSpan(StyleSpan(android.graphics.Typeface.BOLD_ITALIC), start, end, 0)
            else if (style.bold) text.setSpan(StyleSpan(android.graphics.Typeface.BOLD), start, end, 0)
            else if (style.italic) text.setSpan(StyleSpan(android.graphics.Typeface.ITALIC), start, end, 0)
            if (style.underline) text.setSpan(UnderlineSpan(), start, end, 0)
            if (style.strikeThrough) text.setSpan(StrikethroughSpan(), start, end, 0)
            if (style.primaryColor != baseColor) {
                text.setSpan(ForegroundColorSpan(style.primaryColor), start, end, 0)
            }
            // Per-span size: the paint carries the first span's, and a span that disagrees says
            // so as a ratio of it, which is what keeps mixed sizes in one cue from flattening.
            val ratio = sizeOf(style) / baseSize
            if (ratio != 1f) text.setSpan(RelativeSizeSpan(ratio), start, end, 0)
            // Typeface.create never fails: a family this device does not have comes back as the
            // default face, which IS the fallback and is why no lookup check is needed here.
            style.fontFamily?.takeIf { it.isNotBlank() }?.let { family ->
                text.setSpan(TypefaceSpan(family), start, end, 0)
            }
        }
        if (text.isEmpty()) return null

        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = baseColor
            textSize = baseSize
        }
        val safeWidth = (viewportWidth * (1f - layoutSpec.marginLeft - layoutSpec.marginRight)).toInt()
        if (safeWidth <= 0) return null

        val alignment = when (layoutSpec.alignment) {
            CueAlignment.BottomLeft, CueAlignment.MiddleLeft, CueAlignment.TopLeft -> Layout.Alignment.ALIGN_NORMAL
            CueAlignment.BottomRight, CueAlignment.MiddleRight, CueAlignment.TopRight -> Layout.Alignment.ALIGN_OPPOSITE
            else -> Layout.Alignment.ALIGN_CENTER
        }
        fun layoutAt(width: Int, forText: CharSequence = text): StaticLayout =
            StaticLayout.Builder.obtain(forText, 0, forText.length, paint, width)
                .setAlignment(alignment)
                .build()

        // The cue's own wrap mode decides the width StaticLayout breaks at; see wrapWidthFor.
        val wrapWidth = wrapWidthFor(layoutSpec.wrap, safeWidth) { layoutAt(it).lineCount }
        val layout = layoutAt(wrapWidth)
        var widest = 0f
        for (line in 0 until layout.lineCount) {
            val w = layout.getLineWidth(line)
            if (w > widest) widest = w
        }
        val textWidth = kotlin.math.ceil(widest).toInt().coerceAtLeast(1)
        // A POSITIONED cue's bitmap is its text extent, not the whole safe width (audit
        // F-POS1): the layout keeps its wrap width so the lines break identically, but the
        // draw below translates the glyphs to the bitmap's origin and the placement anchors
        // the extent on the authored point. An unpositioned cue keeps the full-width bitmap,
        // whose internal alignment IS its horizontal placement.
        val positioned = layoutSpec.positionX != null || layoutSpec.positionY != null
        // An UNWRAPPED cue may be wider than the safe area, and the viewport is where that stops:
        // a bitmap grown past the screen is pixels nobody can see.
        val ceiling = maxOf(safeWidth, viewportWidth)
        val width = if (positioned) {
            textWidth.coerceAtMost(ceiling)
        } else {
            textWidth.coerceIn(safeWidth, ceiling)
        }
        // The layout centres and right-aligns inside its own WRAP width, so whenever the bitmap
        // is narrower than that, the glyphs have to slide back onto it.
        val glyphShift = when (alignment) {
            Layout.Alignment.ALIGN_OPPOSITE -> (layout.width - width).toFloat()
            Layout.Alignment.ALIGN_CENTER -> (layout.width - width) / 2f
            else -> 0f
        }
        val height = layout.height.coerceAtLeast(1)

        val firstStyle = cue.spans.firstOrNull()?.style
        // The shadow lands outside the text box, so the bitmap grows for it and the placement
        // below subtracts the origin back off. See CueShadow.
        val shadow = firstStyle?.let { cueShadow(it, fontScale) } ?: NO_CUE_SHADOW
        val anyOutline = runs.any { it.style.outlineWidthPx > 0f }

        val bitmap = Bitmap.createBitmap(width + shadow.pad, height + shadow.pad, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.translate(shadow.origin - glyphShift, shadow.origin.toFloat())
        if (shadow.draws && firstStyle != null) {
            canvas.save()
            canvas.translate(shadow.offset, shadow.offset)
            // Stroke then fill, so the shadow is as fat as the outlined text casting it.
            if (anyOutline) {
                layoutAt(wrapWidth, strokeCopy(text, runs, fontScale, firstStyle.shadowColor)).draw(canvas)
            }
            layoutAt(wrapWidth, flatCopy(text, firstStyle.shadowColor)).draw(canvas)
            canvas.restore()
        }
        // Outline pass first, fill second: the cheap universal legibility trick.
        if (anyOutline) layoutAt(wrapWidth, strokeCopy(text, runs, fontScale, null)).draw(canvas)
        layout.draw(canvas)

        val pixels = ByteArray(bitmap.width * bitmap.height * 4)
        bitmap.copyPixelsToBuffer(ByteBuffer.wrap(pixels))

        val marginXPx = (viewportWidth * layoutSpec.marginLeft).toInt()
        val marginYPx = (viewportHeight * layoutSpec.marginVertical).toInt()
        // An authored position is the cue's ANCHOR point, oriented by the alignment:
        // \pos with \an2 puts the bottom-centre of the text on the point, not its top-left.
        val x = layoutSpec.positionX?.let { fraction ->
            val anchor = (fraction * viewportWidth).toInt()
            when (layoutSpec.alignment) {
                CueAlignment.BottomLeft, CueAlignment.MiddleLeft, CueAlignment.TopLeft -> anchor
                CueAlignment.BottomRight, CueAlignment.MiddleRight, CueAlignment.TopRight -> anchor - width
                else -> anchor - width / 2
            }
        } ?: when (layoutSpec.alignment) {
            CueAlignment.BottomLeft, CueAlignment.MiddleLeft, CueAlignment.TopLeft -> marginXPx
            CueAlignment.BottomRight, CueAlignment.MiddleRight, CueAlignment.TopRight ->
                viewportWidth - marginXPx - width
            else -> (viewportWidth - width) / 2
        }
        val y = layoutSpec.positionY?.let { fraction ->
            val anchor = (fraction * viewportHeight).toInt()
            when (layoutSpec.alignment) {
                CueAlignment.TopLeft, CueAlignment.TopCenter, CueAlignment.TopRight -> anchor
                CueAlignment.MiddleLeft, CueAlignment.MiddleCenter, CueAlignment.MiddleRight ->
                    anchor - height / 2
                else -> anchor - height
            }
        } ?: when (layoutSpec.alignment) {
            CueAlignment.TopLeft, CueAlignment.TopCenter, CueAlignment.TopRight -> marginYPx
            CueAlignment.MiddleLeft, CueAlignment.MiddleCenter, CueAlignment.MiddleRight ->
                (viewportHeight - height) / 2
            // The implicit bottom stack anchors at the viewer's sub-position: 1.0 is the plain
            // bottom edge, smaller lifts the stack. Explicit positions above are the author's
            // word and never move with it, exactly mpv's sub-pos rule.
            else -> (viewportHeight * position).toInt() - marginYPx - height - stackedBottom
        }
        // Placement above measured the TEXT box; the shadow's extra pixels hang off it.
        return OverlayImage(
            x = x - shadow.origin,
            y = y - shadow.origin,
            bitmap = RgbaBitmap(bitmap.width, bitmap.height, pixels),
        )
    }

    /** One span's character range and the style it carries, in the joined cue text. */
    private data class StyleRun(val start: Int, val end: Int, val style: CueStyle)

    /**
     * A copy of the text in one flat [color], keeping every size and weight span.
     *
     * This is the shadow's own body: a shadow that kept the per-span colours would read as a
     * second, offset subtitle rather than as a shadow.
     */
    private fun flatCopy(text: SpannableStringBuilder, color: Int): SpannableStringBuilder {
        val copy = SpannableStringBuilder(text)
        for (colored in copy.getSpans(0, copy.length, ForegroundColorSpan::class.java)) {
            copy.removeSpan(colored)
        }
        copy.setSpan(ForegroundColorSpan(color), 0, copy.length, 0)
        return copy
    }

    /**
     * A copy of the text stroked per SPAN, which is what makes the outline per-span here.
     *
     * [override] paints every stroke one colour (the shadow's), or null keeps each span's own
     * outline colour.
     */
    private fun strokeCopy(
        text: SpannableStringBuilder,
        runs: List<StyleRun>,
        fontScale: Float,
        override: Int?,
    ): SpannableStringBuilder {
        val copy = SpannableStringBuilder(text)
        for (colored in copy.getSpans(0, copy.length, ForegroundColorSpan::class.java)) {
            copy.removeSpan(colored)
        }
        for (run in runs) {
            copy.setSpan(
                StrokeSpan(run.style.outlineWidthPx * fontScale, override ?: run.style.outlineColor),
                run.start,
                run.end,
                0,
            )
        }
        return copy
    }

    /**
     * Paints one span as its outline only.
     *
     * A width of zero draws nothing at all rather than a fill: that span asked for no outline,
     * and the real text is drawn over this pass anyway.
     */
    private class StrokeSpan(private val widthPx: Float, private val color: Int) : CharacterStyle() {
        override fun updateDrawState(tp: TextPaint) {
            if (widthPx <= 0f) {
                tp.color = Color.TRANSPARENT
                return
            }
            tp.style = Paint.Style.STROKE
            tp.strokeWidth = widthPx
            tp.color = color
        }
    }


    private companion object {
        private const val STACK_GAP_PX: Int = 8
    }
}
