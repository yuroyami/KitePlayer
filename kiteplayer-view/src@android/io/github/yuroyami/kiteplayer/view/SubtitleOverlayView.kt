package io.github.yuroyami.kiteplayer.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import io.github.yuroyami.kiteplayer.spi.OverlayImage
import io.github.yuroyami.kiteplayer.spi.SubtitleOverlay

/**
 * Draws the engine's sparse subtitle bitmaps in a transparent layer above the video Surface. The
 * layer covers the whole player view, so a cue can sit in the bar of a letterboxed picture.
 */
internal class SubtitleOverlayView(context: Context) : View(context) {
    private val paint = Paint().apply {
        isAntiAlias = false
        isDither = false
        isFilterBitmap = true
    }
    private val destination = RectF()
    private val bitmaps = mutableListOf<Bitmap?>()
    private var overlay: SubtitleOverlay? = null
    private var bitmapHash: Long = Long.MIN_VALUE

    init {
        setWillNotDraw(false)
    }

    /** Must run on the main thread. Pixel uploads happen only when [SubtitleOverlay.contentHash] changes. */
    fun showOverlay(value: SubtitleOverlay?) {
        overlay = value
        if (value == null || value.contentHash != bitmapHash) retireBitmaps()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val active = overlay ?: return
        if (bitmapHash != active.contentHash) {
            retireBitmaps()
            active.images.forEach { image -> bitmaps += bitmapFor(image) }
            bitmapHash = active.contentHash
        }
        active.images.forEachIndexed { index, image ->
            val bitmap = bitmaps.getOrNull(index) ?: return@forEachIndexed
            val box = overlayDestination(width, height, active, image) ?: return@forEachIndexed
            destination.set(box.left, box.top, box.right, box.bottom)
            canvas.drawBitmap(bitmap, null, destination, paint)
        }
    }

    override fun onDetachedFromWindow() {
        dropBitmaps()
        super.onDetachedFromWindow()
    }

    private fun bitmapFor(image: OverlayImage): Bitmap? {
        val rgba = image.bitmap.pixels
        val width = image.bitmap.width
        val height = image.bitmap.height
        if (width <= 0 || height <= 0) return null
        val count = width.toLong() * height.toLong()
        if (count > Int.MAX_VALUE || rgba.size.toLong() != count * RGBA_BYTES) return null
        // Raw copy: the cue bytes are PREMULTIPLIED (the RgbaBitmap contract since the
        // 2026-08-17 audit) and ARGB_8888 stores premultiplied, so any conversion here is one
        // premultiply too many; the old straight-colour path darkened every antialiased edge
        // Mutable but written exactly once, before HWUI ever samples it.
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(rgba))
        return bitmap
    }

    private fun retireBitmaps() {
        // A hardware-accelerated View records Bitmap references for RenderThread. Explicit recycle
        // can invalidate the prior display list before that thread consumes it, so retirement is GC-owned.
        dropBitmaps()
    }

    private fun dropBitmaps() {
        bitmaps.clear()
        bitmapHash = Long.MIN_VALUE
    }

    private companion object {
        const val RGBA_BYTES: Int = 4
    }
}

/** A rectangle of the subtitle layer, in pixels. */
internal data class OverlayBox(val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * Where [image] lands on a layer of [layerWidth] by [layerHeight] pixels, rule 1 of
 * docs/subtitle-placement.md: the overlay's viewport maps onto the whole layer, each axis on its
 * own, and nothing turns with the picture. The engine lays the text out upright for this layer,
 * because the view gives its size to the renderer, so the scale is 1 except for the moment after
 * a resize. Null when the overlay has no viewport to map from.
 */
internal fun overlayDestination(
    layerWidth: Int,
    layerHeight: Int,
    overlay: SubtitleOverlay,
    image: OverlayImage,
): OverlayBox? {
    if (overlay.viewportWidth <= 0 || overlay.viewportHeight <= 0) return null
    val scaleX = layerWidth.toFloat() / overlay.viewportWidth
    val scaleY = layerHeight.toFloat() / overlay.viewportHeight
    return OverlayBox(
        left = image.x * scaleX,
        top = image.y * scaleY,
        right = (image.x + image.bitmap.width) * scaleX,
        bottom = (image.y + image.bitmap.height) * scaleY,
    )
}
