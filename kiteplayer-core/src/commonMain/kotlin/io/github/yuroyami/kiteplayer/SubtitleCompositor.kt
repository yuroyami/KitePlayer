package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.spi.SubtitleOverlay

/**
 * Draws [overlay] onto RGBA pixels, in place.
 *
 * For burning subtitles into a screenshot. The engine's own renderers composite on the GPU and
 * never come here; this is the path for a caller holding a [CapturedFrame] that wants one image
 * with the text on it.
 *
 * The cue alpha contract is premultiplied end to end, so the blend is `dst = src + dst * (1 - a)`
 * with no divide anywhere. Premultiplying again here would render white text grey.
 *
 * Pixels outside the overlay are untouched, so an overlay with nothing in it costs nothing.
 *
 * @param rgba tightly packed RGBA, four bytes per pixel, [width] by [height].
 * @throws IllegalArgumentException when [rgba] is not that size, or when the overlay was laid out
 *         for a different viewport: drawing it anyway would put the text in the wrong place.
 */
public fun SubtitleOverlay.drawOver(rgba: ByteArray, width: Int, height: Int) {
    require(width > 0 && height > 0) { "the picture is ${width}x$height, which cannot be drawn on" }
    val needed = width.toLong() * height.toLong() * 4L
    require(rgba.size.toLong() == needed) {
        "a ${width}x$height RGBA picture is $needed bytes and this one is ${rgba.size}"
    }
    require(viewportWidth == width && viewportHeight == height) {
        "the overlay was laid out for ${viewportWidth}x$viewportHeight and the picture is ${width}x$height"
    }
    images.forEach { image ->
        val bitmap = image.bitmap
        for (row in 0 until bitmap.height) {
            val destinationY = image.y + row
            if (destinationY < 0 || destinationY >= height) continue
            for (column in 0 until bitmap.width) {
                val destinationX = image.x + column
                if (destinationX < 0 || destinationX >= width) continue
                val from = (row * bitmap.width + column) * 4
                val alpha = bitmap.pixels[from + 3].toInt() and 0xFF
                if (alpha == 0) continue
                val to = (destinationY * width + destinationX) * 4
                if (alpha == 255) {
                    bitmap.pixels.copyInto(rgba, to, from, from + 4)
                    continue
                }
                val keep = 255 - alpha
                for (channel in 0 until 4) {
                    val source = bitmap.pixels[from + channel].toInt() and 0xFF
                    val under = rgba[to + channel].toInt() and 0xFF
                    // Rounded, not truncated: truncating darkens every blended edge by half a
                    // level, which shows as a grey rim around white text.
                    rgba[to + channel] = (source + (under * keep + 127) / 255).coerceAtMost(255).toByte()
                }
            }
        }
    }
}
