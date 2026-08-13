@file:OptIn(ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.spi.OverlayImage
import io.github.yuroyami.kiteplayer.spi.SubtitleRasterizer
import io.github.yuroyami.kiteplayer.subtitle.CueAlignment
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
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGContextSetLineJoin
import platform.CoreGraphics.CGContextSetLineWidth
import platform.CoreGraphics.CGContextSetStrokeColorWithColor
import platform.CoreGraphics.CGContextSetTextDrawingMode
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGLineJoin
import platform.CoreGraphics.CGPathCreateWithRect
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.CoreText.CTFontCreateCopyWithSymbolicTraits
import platform.CoreText.CTFontCreateUIFontForLanguage
import platform.CoreText.CTFramesetterCreateFrame
import platform.CoreText.CTFramesetterCreateWithAttributedString
import platform.CoreText.CTFramesetterSuggestFrameSizeWithConstraints
import platform.CoreText.CTFrameDraw
import platform.CoreText.kCTFontAttributeName
import platform.CoreText.kCTFontBoldTrait
import platform.CoreText.kCTFontItalicTrait
import platform.CoreText.kCTFontUIFontSystem
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
    ): List<OverlayImage> {
        if (viewportWidth <= 0 || viewportHeight <= 0) return emptyList()
        val images = mutableListOf<OverlayImage>()
        var stackedBottom = 0
        for (cue in cues) {
            when (cue) {
                is SubtitleCue.Text -> rasterizeText(cue, viewportWidth, viewportHeight, fontScale, stackedBottom)
                    ?.let { image ->
                        images += image
                        if (cue.layout.alignment.isBottom) {
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
        return images
    }

    private fun rasterizeText(
        cue: SubtitleCue.Text,
        viewportWidth: Int,
        viewportHeight: Int,
        fontScale: Float,
        stackedBottom: Int,
    ): OverlayImage? {
        if (cue.spans.isEmpty() || cue.spans.all { it.text.isEmpty() }) return null
        val layoutSpec = cue.layout
        val firstStyle = cue.spans.first().style
        val authoredScale = layoutSpec.authoredHeight?.let { viewportHeight.toFloat() / it } ?: 1f
        val sizePx = (firstStyle.fontSizePx?.times(authoredScale) ?: (viewportHeight / 20f)) * fontScale

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
            var font = CTFontCreateUIFontForLanguage(kCTFontUIFontSystem, sizePx.toDouble(), null)
            if (style.bold || style.italic) {
                val traits = (if (style.bold) kCTFontBoldTrait else 0u) or
                    (if (style.italic) kCTFontItalicTrait else 0u)
                CTFontCreateCopyWithSymbolicTraits(font, 0.0, null, traits, traits)?.let { font = it }
            }
            // CoreText's own attribute keys, bridged toll-free; string literals would be the
            // WRONG keys for a CTFramesetter (the colour key is not AppKit's "NSColor").
            text.addAttribute(cfKey(kCTFontAttributeName), objcValue(font!!), range)
            text.addAttribute(cfKey(kCTForegroundColorAttributeName), objcValue(style.primaryColor.toCgColor()!!), range)
            if (style.underline) {
                text.addAttribute(
                    cfKey(kCTUnderlineStyleAttributeName),
                    NSNumber(int = kCTUnderlineStyleSingle.toInt()),
                    range,
                )
            }
        }
        val safeWidth = (viewportWidth * (1f - layoutSpec.marginLeft - layoutSpec.marginRight)).toInt()
        if (safeWidth <= 0) return null

        val framesetter = CTFramesetterCreateWithAttributedString(
            // Toll-free bridge, no ownership change: the framesetter retains what it needs.
            interpretCPointer(text.objcPtr())!!,
        )
        val fitted = CTFramesetterSuggestFrameSizeWithConstraints(
            framesetter,
            CFRangeMake(0, 0),
            null,
            CGSizeMake(safeWidth.toDouble(), viewportHeight.toDouble()),
            null,
        )
        val width = fitted.useContents { ceil(width).toInt() }.coerceIn(1, safeWidth)
        val height = fitted.useContents { ceil(height).toInt() }.coerceAtLeast(1)

        val pixels = ByteArray(width * height * 4)
        pixels.usePinned { pinned ->
            val colorSpace = CGColorSpaceCreateDeviceRGB()
            val context = CGBitmapContextCreate(
                data = pinned.addressOf(0),
                width = width.toULong(),
                height = height.toULong(),
                bitsPerComponent = 8u,
                bytesPerRow = (width * 4).toULong(),
                space = colorSpace,
                bitmapInfo = CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value,
            ) ?: return null
            try {
                if (firstStyle.outlineWidthPx > 0f) {
                    // Fill-stroke text mode: CG strokes each glyph in the outline colour and
                    // fills it in the span colour in the same draw.
                    CGContextSetTextDrawingMode(context, platform.CoreGraphics.CGTextDrawingMode.kCGTextFillStroke)
                    CGContextSetLineWidth(context, (firstStyle.outlineWidthPx * fontScale).toDouble())
                    CGContextSetLineJoin(context, CGLineJoin.kCGLineJoinRound)
                    val outline = firstStyle.outlineColor.toCgColor()
                    CGContextSetStrokeColorWithColor(context, interpretCPointer(outline!!.rawValue))
                }
                val path = CGPathCreateWithRect(
                    CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()),
                    null,
                )
                val frame = CTFramesetterCreateFrame(framesetter, CFRangeMake(0, 0), path, null)
                CTFrameDraw(frame, context)
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
            else -> viewportHeight - marginYPx - height - stackedBottom
        }
        return OverlayImage(x = x, y = y, bitmap = RgbaBitmap(width, height, pixels))
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

    private val CueAlignment.isBottom: Boolean
        get() = this == CueAlignment.BottomLeft || this == CueAlignment.BottomCenter ||
            this == CueAlignment.BottomRight

    private companion object {
        private const val STACK_GAP_PX: Int = 8
    }
}
