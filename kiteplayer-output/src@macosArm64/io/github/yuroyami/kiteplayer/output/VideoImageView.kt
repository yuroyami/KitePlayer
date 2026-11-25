package io.github.yuroyami.kiteplayer.output

import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.AppKit.NSImage
import platform.AppKit.NSImageView
import platform.CoreGraphics.CGImageCreateWithImageInRect
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGRect
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSize
import platform.CoreGraphics.CGSizeMake

/**
 * The image view of an [AppKitWindow]. It shows each picture as the AppKit renderer hands it over,
 * and for [fill] it first crops the picture to the view's own aspect (#768).
 *
 * `NSImageView` has no scaling that fills its bounds and cuts the overhang. A picture cropped to the
 * view's aspect and scaled proportionally does exactly that. The view crops again when it changes
 * size, so a paused picture follows a resize. Main thread only, as for any AppKit view.
 */
@OptIn(ExperimentalForeignApi::class)
internal class VideoImageView(frame: CValue<CGRect>) : NSImageView(frame) {

    /** The last picture handed over, before any crop. */
    private var source: NSImage? = null

    /** True crops each picture to the view's aspect, which is the Fill scale mode. */
    var fill: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            showSource()
        }

    /** Shows [picture], cropped when [fill] is on. */
    fun show(picture: NSImage?) {
        source = picture
        showSource()
    }

    override fun setFrameSize(newSize: CValue<CGSize>) {
        super.setFrameSize(newSize)
        if (fill) showSource()
    }

    private fun showSource() {
        val picture = source
        image = if (picture == null || !fill) {
            picture
        } else {
            bounds.useContents { cropToAspect(picture, size.width, size.height) } ?: picture
        }
    }
}

/**
 * [picture] with its overhang cut so that its shape is [viewWidth] by [viewHeight], centred. Null
 * when no crop is needed or possible. The crop shares the pixels of the original image.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun cropToAspect(picture: NSImage, viewWidth: Double, viewHeight: Double): NSImage? {
    val (shownWidth, shownHeight) = picture.size.useContents { width to height }
    val keep = fillKeep(shownWidth, shownHeight, viewWidth, viewHeight) ?: return null
    val cgImage = picture.CGImageForProposedRect(null, null, null) ?: return null
    val pixelWidth = CGImageGetWidth(cgImage).toDouble()
    val pixelHeight = CGImageGetHeight(cgImage).toDouble()
    // The kept share applies to the pixels too, so an image whose declared size is not its pixel
    // size, such as an anamorphic one, crops right.
    // Whole pixels, because Core Graphics widens a fractional rectangle to the pixels it touches.
    val keptWidth = kotlin.math.round(pixelWidth * keep.first).coerceAtLeast(1.0)
    val keptHeight = kotlin.math.round(pixelHeight * keep.second).coerceAtLeast(1.0)
    val rect = CGRectMake(
        kotlin.math.floor((pixelWidth - keptWidth) / 2),
        kotlin.math.floor((pixelHeight - keptHeight) / 2),
        keptWidth,
        keptHeight,
    )
    val cropped = CGImageCreateWithImageInRect(cgImage, rect) ?: return null
    return try {
        NSImage(cGImage = cropped, size = CGSizeMake(shownWidth * keep.first, shownHeight * keep.second))
    } finally {
        CGImageRelease(cropped)
    }
}

/**
 * The share of a picture's width and height that Fill keeps in a view of [viewWidth] by
 * [viewHeight]. Null when the shapes already match within half a percent, or when a size is zero.
 */
internal fun fillKeep(pictureWidth: Double, pictureHeight: Double, viewWidth: Double, viewHeight: Double): Pair<Double, Double>? {
    if (pictureWidth <= 0 || pictureHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) return null
    val picture = pictureWidth / pictureHeight
    val view = viewWidth / viewHeight
    if (kotlin.math.abs(picture / view - 1.0) < 0.005) return null
    // A wider picture loses its sides, and a taller one loses its top and bottom.
    return if (picture > view) (view / picture) to 1.0 else 1.0 to (picture / view)
}
