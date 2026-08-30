package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.spi.OverlayImage
import io.github.yuroyami.kiteplayer.spi.SubtitleRasterizer
import io.github.yuroyami.kiteplayer.subtitle.CueAlignment
import io.github.yuroyami.kiteplayer.subtitle.RgbaBitmap
import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.font.FontRenderContext
import java.awt.font.LineBreakMeasurer
import java.awt.font.TextAttribute
import java.awt.font.TextLayout
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.text.AttributedString
import kotlin.math.ceil

/**
 * The desktop text raster engine (register item W-04): each active text cue becomes one image
 * through `java.awt`, the JVM's own text engine, so wrapping, bidi and shaping are AWT's and not
 * this project's. That is the same division of labour StaticLayout gets on Android and CoreText
 * gets on Apple. Bitmap cues pass their pixels through untouched.
 *
 * The placement arithmetic mirrors `AndroidSubtitleRasterizer` line for line, because the three
 * backends must put the same cue in the same place: margins carve the safe area, alignment picks
 * the corner or centre, an authored `\pos` is an ANCHOR oriented by the alignment, and the
 * implicit bottom stack rides the viewer's sub-position.
 *
 * The outline is drawn the way every subtitle renderer fakes it cheaply and well: the glyph
 * outline stroked in the outline colour first, then the text filled on top.
 *
 * **Alpha.** The pixels come out PREMULTIPLIED, which is what [RgbaBitmap] says and what the
 * other two rasterizers already produce. `TYPE_INT_ARGB_PRE` is the AWT image
 * that stores exactly that, and the raster is read directly rather than through `getRGB`, because
 * `getRGB` UN-premultiplies on the way out and would hand three downstream consumers straight
 * alpha they would premultiply a second time.
 */
internal class DesktopSubtitleRasterizer : SubtitleRasterizer {

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
                is SubtitleCue.Text ->
                    rasterizeText(cue, viewportWidth, viewportHeight, fontScale, stackedBottom, position)
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
        val whole = cue.spans.joinToString("") { it.text }
        if (whole.isEmpty()) return null
        val layoutSpec = cue.layout
        val firstStyle = cue.spans.first().style
        // The classic subtitle size rule: about one twentieth of the picture height, scaled by the
        // user's preference and by the authoring resolution when the format declared one.
        val authoredScale = layoutSpec.authoredHeight?.let { viewportHeight.toFloat() / it } ?: 1f
        val sizePx = (firstStyle.fontSizePx?.times(authoredScale) ?: (viewportHeight / 20f)) * fontScale
        val safeWidth = (viewportWidth * (1f - layoutSpec.marginLeft - layoutSpec.marginRight)).toInt()
        if (safeWidth <= 0) return null

        val styled = AttributedString(whole)
        var cursor = 0
        for (span in cue.spans) {
            if (span.text.isEmpty()) continue
            val start = cursor
            cursor += span.text.length
            val style = span.style
            styled.addAttribute(TextAttribute.FAMILY, style.fontFamily ?: Font.SANS_SERIF, start, cursor)
            styled.addAttribute(TextAttribute.SIZE, sizePx, start, cursor)
            styled.addAttribute(TextAttribute.FOREGROUND, Color(style.primaryColor, true), start, cursor)
            if (style.bold) styled.addAttribute(TextAttribute.WEIGHT, TextAttribute.WEIGHT_BOLD, start, cursor)
            if (style.italic) styled.addAttribute(TextAttribute.POSTURE, TextAttribute.POSTURE_OBLIQUE, start, cursor)
            if (style.underline) styled.addAttribute(TextAttribute.UNDERLINE, TextAttribute.UNDERLINE_ON, start, cursor)
            if (style.strikeThrough) styled.addAttribute(TextAttribute.STRIKETHROUGH, TextAttribute.STRIKETHROUGH_ON, start, cursor)
        }

        // The cue's own wrap mode decides the width AWT breaks at; see wrapWidthFor.
        val wrapWidth = wrapWidthFor(layoutSpec.wrap, safeWidth) { breakInto(styled, whole, it).size }
        val lines = breakInto(styled, whole, wrapWidth)
        if (lines.isEmpty()) return null

        var height = 0
        var widest = 0f
        for (line in lines) {
            height += ceil((line.ascent + line.descent + line.leading).toDouble()).toInt()
            if (line.advance > widest) widest = line.advance
        }
        height = height.coerceAtLeast(1)

        // A POSITIONED cue's bitmap is its text extent, not the whole safe width:
        // the lines still break at their own width so they read identically, but the bitmap hugs
        // the glyphs and the placement anchors that extent on the authored point. An unpositioned
        // cue keeps the full-width bitmap, whose internal alignment IS its horizontal placement.
        val positioned = layoutSpec.positionX != null || layoutSpec.positionY != null
        val textWidth = ceil(widest.toDouble()).toInt()
        // An unwrapped cue may be wider than the safe area, and the viewport is where that stops:
        // a bitmap grown past the screen is pixels nobody can see, and a centred line wider than
        // its box hangs off both ends evenly, which is what an overlong unbroken cue looks like.
        val width = if (positioned) {
            textWidth.coerceIn(1, safeWidth)
        } else {
            textWidth.coerceIn(safeWidth, maxOf(safeWidth, viewportWidth))
        }

        // ARGB_PRE, read straight out of the raster: this is the whole alpha contract.
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB_PRE)
        val g = image.createGraphics()
        try {
            applyHints(g)
            val outlineWidth = firstStyle.outlineWidthPx * fontScale
            var baseline = 0f
            for (line in lines) {
                baseline += line.ascent
                val x = alignedX(line.advance, width, layoutSpec.alignment)
                if (outlineWidth > 0f) {
                    // Outline pass first, fill second: the cheap universal legibility trick.
                    g.color = Color(firstStyle.outlineColor, true)
                    g.stroke = BasicStroke(outlineWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                    g.draw(line.getOutline(AffineTransform.getTranslateInstance(x.toDouble(), baseline.toDouble())))
                }
                line.draw(g, x, baseline)
                baseline += line.descent + line.leading
            }
        } finally {
            g.dispose()
        }

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
        return OverlayImage(x = x, y = y, bitmap = RgbaBitmap(width, height, premultipliedRgba(image)))
    }

    /**
     * AWT's own line breaker at [width], one measurer run per authored paragraph so an explicit
     * newline breaks exactly where the author put it and everything else breaks at the width.
     */
    private fun breakInto(styled: AttributedString, whole: String, width: Int): List<TextLayout> {
        val lines = mutableListOf<TextLayout>()
        val measurer = LineBreakMeasurer(styled.iterator, RENDER_CONTEXT)
        while (measurer.position < whole.length) {
            val newline = whole.indexOf('\n', measurer.position)
            val limit = if (newline < 0) whole.length else newline + 1
            lines += measurer.nextLayout(width.toFloat(), limit, false) ?: break
        }
        return lines
    }

    /** Where one laid-out line starts inside a [boxWidth]-wide bitmap. */
    private fun alignedX(lineWidth: Float, boxWidth: Int, alignment: CueAlignment): Float =
        when (alignment) {
            CueAlignment.BottomLeft, CueAlignment.MiddleLeft, CueAlignment.TopLeft -> 0f
            CueAlignment.BottomRight, CueAlignment.MiddleRight, CueAlignment.TopRight -> boxWidth - lineWidth
            else -> (boxWidth - lineWidth) / 2f
        }

    /**
     * The raster's own premultiplied ARGB ints, repacked to the RGBA byte order every consumer
     * reads. `getRGB` is deliberately not used: it un-premultiplies.
     */
    private fun premultipliedRgba(image: BufferedImage): ByteArray {
        val argb = (image.raster.dataBuffer as DataBufferInt).data
        val out = ByteArray(argb.size * 4)
        for (i in argb.indices) {
            val pixel = argb[i]
            val at = i * 4
            out[at] = (pixel ushr 16).toByte()
            out[at + 1] = (pixel ushr 8).toByte()
            out[at + 2] = pixel.toByte()
            out[at + 3] = (pixel ushr 24).toByte()
        }
        return out
    }


    private companion object {
        private const val STACK_GAP_PX: Int = 8

        private fun applyHints(g: Graphics2D) {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
        }

        /** Measuring and drawing must agree, so the measurer gets the drawing hints. */
        private val RENDER_CONTEXT = FontRenderContext(null, true, true)
    }
}
