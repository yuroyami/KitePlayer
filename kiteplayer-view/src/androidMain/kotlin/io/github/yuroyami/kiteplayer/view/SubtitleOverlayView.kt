package io.github.yuroyami.kiteplayer.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import io.github.yuroyami.kiteplayer.spi.OverlayImage
import io.github.yuroyami.kiteplayer.spi.SubtitleOverlay
import kotlin.math.min

/** Draws the engine's sparse subtitle bitmaps in a transparent layer above the video Surface. */
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
    private var rotationDegrees: Int = 0

    init {
        setWillNotDraw(false)
    }

    /** Must run on the main thread. Pixel uploads happen only when [SubtitleOverlay.contentHash] changes. */
    fun showOverlay(value: SubtitleOverlay?) {
        overlay = value
        if (value == null || value.contentHash != bitmapHash) retireBitmaps()
        invalidate()
    }

    fun setVideoRotation(value: Int) {
        val normalized = ((value % 360) + 360) % 360
        if (rotationDegrees == normalized) return
        rotationDegrees = normalized
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val active = overlay ?: return
        if (active.viewportWidth <= 0 || active.viewportHeight <= 0) return
        val quarterTurned = rotationDegrees == 90 || rotationDegrees == 270
        val displayWidth = if (quarterTurned) active.viewportHeight else active.viewportWidth
        val displayHeight = if (quarterTurned) active.viewportWidth else active.viewportHeight
        val scale = min(width.toFloat() / displayWidth.toFloat(), height.toFloat() / displayHeight.toFloat())
        if (!scale.isFinite() || scale <= 0f) return
        val left = (width - displayWidth * scale) / 2f
        val top = (height - displayHeight * scale) / 2f

        if (bitmapHash != active.contentHash) {
            retireBitmaps()
            active.images.forEach { image -> bitmaps += bitmapFor(image) }
            bitmapHash = active.contentHash
        }
        active.images.forEachIndexed { index, image ->
            val bitmap = bitmaps.getOrNull(index) ?: return@forEachIndexed
            overlayRect(
                rotationDegrees = rotationDegrees,
                viewportWidth = active.viewportWidth,
                viewportHeight = active.viewportHeight,
                x = image.x,
                y = image.y,
                imageWidth = image.bitmap.width,
                imageHeight = image.bitmap.height,
            ).let { rect ->
                destination.set(
                    left + rect.left * scale,
                    top + rect.top * scale,
                    left + rect.right * scale,
                    top + rect.bottom * scale,
                )
            }
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
        val colors = IntArray(count.toInt())
        var at = 0
        for (index in colors.indices) {
            val red = rgba[at].toInt() and 0xFF
            val green = rgba[at + 1].toInt() and 0xFF
            val blue = rgba[at + 2].toInt() and 0xFF
            val alpha = rgba[at + 3].toInt() and 0xFF
            colors[index] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
            at += RGBA_BYTES
        }
        return Bitmap.createBitmap(colors, width, height, Bitmap.Config.ARGB_8888)
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

internal data class OverlayRect(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** Maps the cue centre into display orientation while keeping rasterized text upright and undistorted. */
internal fun overlayRect(
    rotationDegrees: Int,
    viewportWidth: Int,
    viewportHeight: Int,
    x: Int,
    y: Int,
    imageWidth: Int,
    imageHeight: Int,
): OverlayRect = when (rotationDegrees) {
    90 -> {
        val centerX = viewportHeight - (y * 2 + imageHeight) / 2
        val centerY = (x * 2 + imageWidth) / 2
        OverlayRect(
            left = centerX - imageWidth / 2,
            top = centerY - imageHeight / 2,
            right = centerX - imageWidth / 2 + imageWidth,
            bottom = centerY - imageHeight / 2 + imageHeight,
        )
    }
    180 -> OverlayRect(
        left = viewportWidth - x - imageWidth,
        top = viewportHeight - y - imageHeight,
        right = viewportWidth - x,
        bottom = viewportHeight - y,
    )
    270 -> {
        val centerX = (y * 2 + imageHeight) / 2
        val centerY = viewportWidth - (x * 2 + imageWidth) / 2
        OverlayRect(
            left = centerX - imageWidth / 2,
            top = centerY - imageHeight / 2,
            right = centerX - imageWidth / 2 + imageWidth,
            bottom = centerY - imageHeight / 2 + imageHeight,
        )
    }
    else -> OverlayRect(x, y, x + imageWidth, y + imageHeight)
}
