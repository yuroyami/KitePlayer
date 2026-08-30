@file:OptIn(ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.spi.OverlayImage
import io.github.yuroyami.kiteplayer.spi.SubtitleRasterizer
import io.github.yuroyami.kiteplayer.subtitle.CueAlignment
import io.github.yuroyami.kiteplayer.subtitle.CueStyle
import io.github.yuroyami.kiteplayer.subtitle.RgbaBitmap
import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.interpretCPointer
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.useContents
import platform.CoreFoundation.CFRangeMake
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGColorCreateGenericRGB
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreFoundation.CFRelease
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGContextSetShadowWithColor
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGPathCreateWithRect
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.CoreText.CTFontCopyFamilyName
import platform.CoreText.CTFontCreateCopyWithSymbolicTraits
import platform.CoreText.CTFontCreateWithNameAndOptions
import platform.CoreText.CTFontRef
import platform.CoreText.kCTFontOptionsPreventAutoActivation
import platform.CoreText.CTFontCreateUIFontForLanguage
import platform.CoreText.CTFramesetterCreateFrame
import platform.CoreText.CTFramesetterCreateWithAttributedString
import platform.CoreText.CTFramesetterSuggestFrameSizeWithConstraints
import platform.CoreText.CTFrameDraw
import platform.CoreText.kCTFontAttributeName
import platform.CoreText.kCTFontBoldTrait
import platform.CoreText.kCTFontItalicTrait
import platform.CoreText.kCTFontUIFontSystem
import platform.CoreText.kCTStrokeColorAttributeName
import platform.CoreText.kCTStrokeWidthAttributeName
import platform.CoreText.kCTForegroundColorAttributeName
import platform.CoreText.kCTUnderlineStyleAttributeName
import platform.CoreText.kCTUnderlineStyleSingle
import platform.CoreFoundation.CFStringRef
import platform.Foundation.NSAttributedString
import platform.Foundation.NSMakeRange
import platform.Foundation.NSMutableAttributedString
import platform.Foundation.NSNumber
import platform.Foundation.NSString
import platform.Foundation.addAttribute
import platform.Foundation.create
import kotlinx.cinterop.interpretObjCPointer
import kotlinx.cinterop.objcPtr
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * The Apple text raster engine (S2.c, carrying S4.c landing 4's Apple half): each active text
 * cue becomes one image through CoreText, the platform's own shaper and line breaker, so
 * wrapping, bidi and shaping are Apple's and not this project's. Bitmap cues pass their pixels
 * through untouched. The placement arithmetic mirrors AndroidSubtitleRasterizer line for line,
 * because the two must put the same cue in the same place.
 *
 * The outline is CoreText's own fill-stroke text mode rather than a second pass: CG strokes and
 * fills glyphs in one draw, which is the same cheap legibility trick.
 *
 * Two honest limits, both S4.f's to lift: strikethrough is not rendered (CTFrameDraw ignores
 * the attribute; only libass owns full decoration), and the bitmap comes out of CGBitmapContext
 * PREMULTIPLIED, which the straight-alpha overlay blend reads with slightly darker antialiased
 * edges. Solid glyph cores are exact.
 */
internal class AppleSubtitleRasterizer : SubtitleRasterizer {

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
        // ASS `Collisions: Reverse` puts the NEWEST cue at the bottom, so the pile is built from
        // the end of the list and turned back the right way round: the images keep the caller's
        // order, which is the draw order, and only the stack offsets change.
        val reversed = cues.stacksLastAtBottom
        for (cue in if (reversed) cues.asReversed() else cues) {
            when (cue) {
                is SubtitleCue.Text -> rasterizeText(cue, viewportWidth, viewportHeight, fontScale, stackedBottom, position)
                    ?.let { image ->
                        images += image
                        if (cue.layout.usesImplicitBottomStack) {
                            stackedBottom += image.bitmap.height + STACK_GAP_PX
                        }
                    }
                is SubtitleCue.Bitmap -> cue.regions.forEach { region ->
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
        return if (reversed) images.asReversed() else images
    }

    private fun rasterizeText(
        cue: SubtitleCue.Text,
        viewportWidth: Int,
        viewportHeight: Int,
        fontScale: Float,
        stackedBottom: Int,
        position: Float,
    ): OverlayImage? {
        if (cue.spans.isEmpty() || cue.spans.all { it.text.isEmpty() }) return null
        val layoutSpec = cue.layout
        val firstStyle = cue.spans.first().style
        val authoredScale = layoutSpec.authoredHeight?.let { viewportHeight.toFloat() / it } ?: 1f
        fun sizeOf(style: CueStyle) =
            (style.fontSizePx?.times(authoredScale) ?: (viewportHeight / 20f)) * fontScale

        val whole = cue.spans.joinToString("") { it.text }
        if (whole.isEmpty()) return null
        val text = NSMutableAttributedString.create(attributedString = NSAttributedString.create(string = whole))
            .mutableCopy() as NSMutableAttributedString
        var cursor = 0
        cue.spans.forEach { span ->
            if (span.text.isEmpty()) return@forEach
            val range = NSMakeRange(cursor.toULong(), span.text.length.toULong())
            cursor += span.text.length
            val style = span.style
            val sizePx = sizeOf(style)
            val baseFont = namedFont(style.fontFamily, sizePx)
                ?: CTFontCreateUIFontForLanguage(kCTFontUIFontSystem, sizePx.toDouble(), null)
            var font = baseFont
            if (style.bold || style.italic) {
                val traits = (if (style.bold) kCTFontBoldTrait else 0u) or
                    (if (style.italic) kCTFontItalicTrait else 0u)
                CTFontCreateCopyWithSymbolicTraits(font, 0.0, null, traits, traits)?.let { font = it }
            }
            // CoreText's own attribute keys, bridged toll-free; string literals would be the
            // WRONG keys for a CTFramesetter (the colour key is not AppKit's "NSColor").
            text.addAttribute(cfKey(kCTFontAttributeName), objcValue(font!!), range)
            val spanColor = style.primaryColor.toCgColor()!!
            text.addAttribute(cfKey(kCTForegroundColorAttributeName), objcValue(spanColor), range)
            // The attributed string holds its own retains; every Create-rule reference this
            // loop made dies here: they used to leak, one set per span per cue.
            if (font != baseFont) CFRelease(font)
            CFRelease(baseFont)
            CFRelease(spanColor)
            if (style.underline) {
                text.addAttribute(
                    cfKey(kCTUnderlineStyleAttributeName),
                    NSNumber(int = kCTUnderlineStyleSingle.toInt()),
                    range,
                )
            }
            if (style.outlineWidthPx > 0f) {
                // CoreText's own stroke attributes rather than one context-wide setting, which is
                // what makes the outline PER SPAN. Its width is a percentage of the font size and
                // a NEGATIVE value means fill and stroke, which is the same one-draw legibility
                // trick kCGTextFillStroke used to do for the whole cue at once.
                val percent = -(style.outlineWidthPx * fontScale / sizePx * 100.0)
                text.addAttribute(cfKey(kCTStrokeWidthAttributeName), NSNumber(double = percent), range)
                val outline = style.outlineColor.toCgColor()!!
                text.addAttribute(cfKey(kCTStrokeColorAttributeName), objcValue(outline), range)
                CFRelease(outline)
            }
        }
        val safeWidth = (viewportWidth * (1f - layoutSpec.marginLeft - layoutSpec.marginRight)).toInt()
        if (safeWidth <= 0) return null

        // Every Create-rule object below is released on every exit: a two hour
        // film's cue edges used to leak the framesetter, the frame with its laid-out glyph
        // runs, the path, the colour space and one colour per styled cue, for ever.
        val framesetter = CTFramesetterCreateWithAttributedString(
            // Toll-free bridge, no ownership change: the framesetter retains what it needs.
            interpretCPointer(text.objcPtr())!!,
        )
        try {
            // The cue's own wrap mode decides the width CoreText breaks at; see wrapWidthFor. The
            // fitted HEIGHT is the extent it searches on, because CoreText answers with a size
            // rather than a line count and the height only falls as the width grows.
            val wrapWidth = wrapWidthFor(layoutSpec.wrap, safeWidth) { probe ->
                fittedSize(framesetter, probe, viewportHeight).second
            }
            val fitted = fittedSize(framesetter, wrapWidth, viewportHeight)
            // An unwrapped cue may be wider than the safe area, and the viewport is where that
            // stops: a bitmap grown past the screen is pixels nobody can see.
            val width = fitted.first.coerceIn(1, maxOf(safeWidth, viewportWidth))
            val height = fitted.second.coerceAtLeast(1)

            // The shadow lands outside the text box, so the bitmap grows for it and the placement
            // below subtracts the origin back off. See CueShadow.
            val shadow = cueShadow(firstStyle, fontScale)
            val bitmapWidth = width + shadow.pad
            val bitmapHeight = height + shadow.pad

            val pixels = ByteArray(bitmapWidth * bitmapHeight * 4)
            pixels.usePinned { pinned ->
                val colorSpace = CGColorSpaceCreateDeviceRGB()
                val context = CGBitmapContextCreate(
                    data = pinned.addressOf(0),
                    width = bitmapWidth.toULong(),
                    height = bitmapHeight.toULong(),
                    bitsPerComponent = 8u,
                    bytesPerRow = (bitmapWidth * 4).toULong(),
                    space = colorSpace,
                    bitmapInfo = CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value,
                )
                // The context holds its own reference to the space from here (or was never made).
                CFRelease(colorSpace)
                if (context == null) return null
                try {
                    if (shadow.draws) {
                        // CG's own drop shadow: one call, applied to the fill and the stroke
                        // alike, which is exactly the silhouette a subtitle shadow is. Its y
                        // runs UP, so down on screen is a negative offset, and a zero blur
                        // keeps the hard edge every subtitle format means by "shadow".
                        val shade = firstStyle.shadowColor.toCgColor()
                        CGContextSetShadowWithColor(
                            context,
                            CGSizeMake(shadow.offset.toDouble(), -shadow.offset.toDouble()),
                            0.0,
                            interpretCPointer(shade!!.rawValue),
                        )
                        CFRelease(shade)
                    }
                    // The text box inside the grown bitmap. CoreText fills a frame from the TOP
                    // of its rect downward, and CG measures y from the bottom, so a shadow that
                    // reaches down-right leaves its room BELOW the box (rect lifted by the pad)
                    // and one reaching up-left leaves it above and to the left.
                    val path = CGPathCreateWithRect(
                        CGRectMake(
                            shadow.origin.toDouble(),
                            (shadow.pad - shadow.origin).toDouble(),
                            width.toDouble(),
                            height.toDouble(),
                        ),
                        null,
                    )
                    val frame = CTFramesetterCreateFrame(framesetter, CFRangeMake(0, 0), path, null)
                    CFRelease(path)
                    try {
                        CTFrameDraw(frame, context)
                    } finally {
                        CFRelease(frame)
                    }
                } finally {
                    CGContextRelease(context)
                }
            }

            val marginXPx = (viewportWidth * layoutSpec.marginLeft).toInt()
            val marginYPx = (viewportHeight * layoutSpec.marginVertical).toInt()
            val x = layoutSpec.positionX?.let { (it * viewportWidth).toInt() } ?: when (layoutSpec.alignment) {
                CueAlignment.BottomLeft, CueAlignment.MiddleLeft, CueAlignment.TopLeft -> marginXPx
                CueAlignment.BottomRight, CueAlignment.MiddleRight, CueAlignment.TopRight ->
                    viewportWidth - marginXPx - width
                else -> (viewportWidth - width) / 2
            }
            val y = layoutSpec.positionY?.let { (it * viewportHeight).toInt() } ?: when (layoutSpec.alignment) {
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
                bitmap = RgbaBitmap(bitmapWidth, bitmapHeight, pixels),
            )
        } finally {
            CFRelease(framesetter)
        }
    }

    /** The whole-pixel size CoreText lays this text out in, given a break width. */
    private fun fittedSize(
        framesetter: platform.CoreText.CTFramesetterRef?,
        width: Int,
        viewportHeight: Int,
    ): Pair<Int, Int> {
        val size = CTFramesetterSuggestFrameSizeWithConstraints(
            framesetter,
            CFRangeMake(0, 0),
            null,
            // The height constraint is the picture, never the wrap width: a cue taller than the
            // screen is already broken, and CoreText would silently drop the overflow.
            CGSizeMake(width.toDouble(), viewportHeight.toDouble()),
            null,
        )
        return size.useContents { ceil(this.width).toInt() to ceil(this.height).toInt() }
    }

    /**
     * The face a cue ASKED for, or null when this system has no such family.
     *
     * Null rather than a substitute on purpose: CoreText would happily hand back a default face
     * for a name it does not know, and the caller's own fallback is the honest place for that
     * decision. An ASS script naming a Windows-only font gets the system face, not a surprise.
     */
    private fun namedFont(family: String?, sizePx: Float): CTFontRef? {
        if (family.isNullOrBlank()) return null
        val name = NSString.create(string = family)
        val font = CTFontCreateWithNameAndOptions(
            interpretCPointer(name.objcPtr()),
            sizePx.toDouble(),
            null,
            // Do NOT substitute: this returns null instead of a lookalike when the family is
            // missing, which is what lets the caller fall back deliberately.
            kCTFontOptionsPreventAutoActivation,
        )
        val resolved = CTFontCopyFamilyName(font)
        val matched = interpretObjCPointer<NSString>(resolved!!.rawValue).toString()
            .equals(family, ignoreCase = true)
        CFRelease(resolved)
        if (matched) return font
        CFRelease(font)
        return null
    }

    /** A CoreText CFString attribute key as the Kotlin string NSAttributedString wants. */
    private fun cfKey(key: CFStringRef?): String =
        interpretObjCPointer<NSString>(key!!.rawValue).toString()

    /** A CoreFoundation object as the ObjC value NSAttributedString wants, toll-free. */
    private fun objcValue(ref: COpaquePointer): Any =
        interpretObjCPointer<platform.darwin.NSObject>(ref.rawValue)

    /** ARGB int (the cue model's colour) to a CG colour, returned retained. */
    private fun Int.toCgColor(): COpaquePointer? {
        val alpha = ((this ushr 24) and 0xFF) / 255.0
        val red = ((this ushr 16) and 0xFF) / 255.0
        val green = ((this ushr 8) and 0xFF) / 255.0
        val blue = (this and 0xFF) / 255.0
        return CGColorCreateGenericRGB(red, green, blue, alpha)
    }


    private companion object {
        private const val STACK_GAP_PX: Int = 8
    }
}
