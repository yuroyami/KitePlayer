package io.github.yuroyami.kiteplayer.output

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import io.github.yuroyami.kiteplayer.spi.OverlayImage
import io.github.yuroyami.kiteplayer.spi.SubtitleRasterizer
import io.github.yuroyami.kiteplayer.subtitle.CueAlignment
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
        val text = SpannableStringBuilder()
        var baseColor = Color.WHITE
        cue.spans.forEachIndexed { index, span ->
            val start = text.length
            text.append(span.text)
            val end = text.length
            val style = span.style
            if (index == 0) baseColor = style.primaryColor
            if (style.bold && style.italic) text.setSpan(StyleSpan(android.graphics.Typeface.BOLD_ITALIC), start, end, 0)
            else if (style.bold) text.setSpan(StyleSpan(android.graphics.Typeface.BOLD), start, end, 0)
            else if (style.italic) text.setSpan(StyleSpan(android.graphics.Typeface.ITALIC), start, end, 0)
            if (style.underline) text.setSpan(UnderlineSpan(), start, end, 0)
            if (style.strikeThrough) text.setSpan(StrikethroughSpan(), start, end, 0)
            if (style.primaryColor != baseColor) {
                text.setSpan(ForegroundColorSpan(style.primaryColor), start, end, 0)
            }
        }
        if (text.isEmpty()) return null

        val layoutSpec = cue.layout
        // The classic subtitle size rule: about one twentieth of the picture height, scaled by
        // the user's preference and by the authoring resolution when the format declared one.
        val authoredScale = layoutSpec.authoredHeight?.let { viewportHeight.toFloat() / it } ?: 1f
        val sizePx = (cue.spans.firstOrNull()?.style?.fontSizePx?.times(authoredScale)
            ?: (viewportHeight / 20f)) * fontScale

        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = baseColor
            textSize = sizePx
        }
        val safeWidth = (viewportWidth * (1f - layoutSpec.marginLeft - layoutSpec.marginRight)).toInt()
        if (safeWidth <= 0) return null

        val alignment = when (layoutSpec.alignment) {
            CueAlignment.BottomLeft, CueAlignment.MiddleLeft, CueAlignment.TopLeft -> Layout.Alignment.ALIGN_NORMAL
            CueAlignment.BottomRight, CueAlignment.MiddleRight, CueAlignment.TopRight -> Layout.Alignment.ALIGN_OPPOSITE
            else -> Layout.Alignment.ALIGN_CENTER
        }
        fun layoutAt(width: Int, forPaint: TextPaint = paint): StaticLayout =
            StaticLayout.Builder.obtain(text, 0, text.length, forPaint, width)
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

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (glyphShift != 0f) canvas.translate(-glyphShift, 0f)
        // Outline pass first, fill second: the cheap universal legibility trick.
        val style = cue.spans.firstOrNull()?.style
        if (style != null && style.outlineWidthPx > 0f) {
            val outline = TextPaint(paint).apply {
                this.style = Paint.Style.STROKE
                strokeWidth = style.outlineWidthPx * fontScale
                color = style.outlineColor
            }
            layoutAt(wrapWidth, outline).draw(canvas)
        }
        layout.draw(canvas)

        val pixels = ByteArray(width * height * 4)
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
        return OverlayImage(x = x, y = y, bitmap = RgbaBitmap(width, height, pixels))
    }


    private companion object {
        private const val STACK_GAP_PX: Int = 8
    }
}
