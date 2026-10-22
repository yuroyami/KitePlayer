package io.github.yuroyami.kiteplayer.output

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.style.AlignmentSpan
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
 * The Android text raster engine: each active text cue becomes one image through
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
    ): List<OverlayImage> = rasterizeCues(cues, viewportWidth, viewportHeight) { cue, stackedBottom, budget ->
        rasterizeText(cue, viewportWidth, viewportHeight, fontScale, stackedBottom, position, budget)
    }

    private fun rasterizeText(
        cue: SubtitleCue.Text,
        viewportWidth: Int,
        viewportHeight: Int,
        fontScale: Float,
        stackedBottom: Int,
        position: Float,
        budget: OverlayPixels,
    ): OverlayImage? {
        val layoutSpec = cue.layout
        // The classic subtitle size rule; see cueFontSizePx.
        fun sizeOf(style: CueStyle) = cueFontSizePx(style, layoutSpec, viewportHeight, fontScale)
        val baseSize = sizeOf(cue.spans.firstOrNull()?.style ?: CueStyle())
        fun strokeOf(style: CueStyle) = cueOutlinePx(style, fontScale, sizeOf(style))

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
        val safeWidth = cueSafeWidth(layoutSpec, viewportWidth)
        if (safeWidth <= 0) return null

        // A cue's alignment names a physical side, and Android's names the start or the end of each
        // paragraph's own direction, so NORMAL is the right edge of a Hebrew or Arabic line. Each
        // paragraph of a side-aligned cue therefore gets the one that lands on the side the cue
        // names; the copies drawn for the shadow and the outline keep those spans (#478).
        val side = when (layoutSpec.alignment) {
            CueAlignment.BottomLeft, CueAlignment.MiddleLeft, CueAlignment.TopLeft -> CueSide.Left
            CueAlignment.BottomRight, CueAlignment.MiddleRight, CueAlignment.TopRight -> CueSide.Right
            else -> null
        }
        if (side != null) alignParagraphs(text, side)
        val alignment = if (side == null) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL
        fun layoutAt(width: Int, forText: CharSequence = text): StaticLayout =
            StaticLayout.Builder.obtain(forText, 0, forText.length, paint, width)
                .setAlignment(alignment)
                .build()

        // The cue's own wrap mode decides the width StaticLayout breaks at; see wrapWidthFor.
        val wrapWidth = wrapWidthFor(layoutSpec.wrap, safeWidth) { layoutAt(it).lineCount }
        val layout = layoutAt(wrapWidth)
        // Where the lines' ink lies inside the layout, measured rather than inferred from the
        // alignment, which a right-to-left paragraph turns around. A line of nothing but blanks has
        // none, and trailing blanks are not ink either: the line's left and right leave them out.
        var inkLeft = Float.MAX_VALUE
        var inkRight = -Float.MAX_VALUE
        for (line in 0 until layout.lineCount) {
            if (layout.getLineMax(line) <= 0f) continue
            inkLeft = minOf(inkLeft, layout.getLineLeft(line))
            inkRight = maxOf(inkRight, layout.getLineRight(line))
        }
        if (inkRight < inkLeft) {
            inkLeft = 0f
            inkRight = 0f
        }
        val inkWidth = inkRight - inkLeft
        val textWidth = kotlin.math.ceil(inkWidth).toInt().coerceAtLeast(1)
        // A POSITIONED cue's bitmap is its text extent, not the whole safe width:
        // the layout keeps its wrap width so the lines break identically, but the
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
        // The layout places the lines inside its own WRAP width, so whenever the bitmap is narrower
        // or wider than that, the glyphs have to slide onto it: their ink flush with the side the
        // cue names, or centred. The shift comes from the measured ink, so a right-to-left line,
        // which the layout puts at the far edge of its width, is not cropped away (#478).
        val inkX = when (side) {
            CueSide.Left -> 0f
            CueSide.Right -> width - inkWidth
            null -> (width - inkWidth) / 2f
        }
        val glyphShift = inkLeft - inkX
        // Never taller than the viewport: the lines that fit are drawn from the top, as CoreText
        // does on Apple, and the rest would be pixels nobody can see.
        val height = layout.height.coerceIn(1, viewportHeight)

        val firstStyle = cue.spans.firstOrNull()?.style
        // The shadow lands outside the text box, so the bitmap grows for it and the placement
        // below subtracts the origin back off. See CueShadow.
        val shadow = firstStyle?.let { cueShadow(it, fontScale) } ?: NO_CUE_SHADOW
        val anyOutline = runs.any { strokeOf(it.style) > 0f }

        // The viewer's box: the bitmap grows by the padding on every side so the box is
        // never clipped, and the placement subtracts it back off. Transparent draws nothing.
        val boxColor = firstStyle?.backgroundColor ?: 0
        val boxPad = firstStyle?.let { cueBoxPadPx(it, fontScale) } ?: 0
        val bitmapWidth = width + shadow.pad + 2 * boxPad
        val bitmapHeight = height + shadow.pad + 2 * boxPad
        if (!budget.take(bitmapWidth, bitmapHeight)) return null

        val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.translate(shadow.origin - glyphShift + boxPad, (shadow.origin + boxPad).toFloat())
        if (boxPad > 0) {
            // One padded rectangle per laid-out line, drawn under the shadow and the text. The
            // canvas is already translated to the text origin, so the layout's own line
            // coordinates are the right frame.
            val boxPaint = android.graphics.Paint().apply { color = boxColor }
            val pad = boxPad.toFloat()
            for (line in 0 until layout.lineCount) {
                canvas.drawRect(
                    layout.getLineLeft(line) - pad,
                    layout.getLineTop(line) - pad,
                    layout.getLineRight(line) + pad,
                    layout.getLineBottom(line) + pad,
                    boxPaint,
                )
            }
        }
        if (shadow.draws && firstStyle != null) {
            canvas.save()
            canvas.translate(shadow.offset, shadow.offset)
            // Stroke then fill, so the shadow is as fat as the outlined text casting it.
            if (anyOutline) {
                layoutAt(wrapWidth, strokeCopy(text, runs, ::strokeOf, firstStyle.shadowColor)).draw(canvas)
            }
            layoutAt(wrapWidth, flatCopy(text, firstStyle.shadowColor)).draw(canvas)
            canvas.restore()
        }
        // Outline pass first, fill second: the cheap universal legibility trick.
        if (anyOutline) layoutAt(wrapWidth, strokeCopy(text, runs, ::strokeOf, null)).draw(canvas)
        layout.draw(canvas)

        val pixels = ByteArray(bitmap.width * bitmap.height * 4)
        bitmap.copyPixelsToBuffer(ByteBuffer.wrap(pixels))

        // Shared by every rasterizer, so a cue lands in the same place on every platform.
        val origin = cueOrigin(layoutSpec, viewportWidth, viewportHeight, width, height, position, stackedBottom)
        // Placement above measured the TEXT box; the shadow's and the box's extra pixels hang
        // off it, so the words do not move when either is switched on.
        return OverlayImage(
            x = origin.x - shadow.origin - boxPad,
            y = origin.y - shadow.origin - boxPad,
            bitmap = RgbaBitmap(bitmap.width, bitmap.height, pixels),
        )
    }

    /** One span's character range and the style it carries, in the joined cue text. */
    private data class StyleRun(val start: Int, val end: Int, val style: CueStyle)

    /** The physical side a cue is aligned to. */
    private enum class CueSide { Left, Right }

    /**
     * Gives each paragraph of [text] the layout alignment that puts it on [side]: NORMAL is the
     * left edge of a left-to-right paragraph and the right edge of a right-to-left one, which the
     * paragraph's first strong character decides, as StaticLayout itself decides it.
     */
    private fun alignParagraphs(text: SpannableStringBuilder, side: CueSide) {
        var start = 0
        while (start < text.length) {
            val newline = text.indexOf('\n', start)
            val end = if (newline < 0) text.length else newline + 1
            val rtl = TextDirectionHeuristics.FIRSTSTRONG_LTR.isRtl(text, start, end - start)
            val alignment = if ((side == CueSide.Left) != rtl) Layout.Alignment.ALIGN_NORMAL else Layout.Alignment.ALIGN_OPPOSITE
            text.setSpan(AlignmentSpan.Standard(alignment), start, end, Spanned.SPAN_PARAGRAPH)
            start = end
        }
    }

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
        strokeOf: (CueStyle) -> Float,
        override: Int?,
    ): SpannableStringBuilder {
        val copy = SpannableStringBuilder(text)
        for (colored in copy.getSpans(0, copy.length, ForegroundColorSpan::class.java)) {
            copy.removeSpan(colored)
        }
        for (run in runs) {
            copy.setSpan(
                StrokeSpan(strokeOf(run.style), override ?: run.style.outlineColor),
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
}
