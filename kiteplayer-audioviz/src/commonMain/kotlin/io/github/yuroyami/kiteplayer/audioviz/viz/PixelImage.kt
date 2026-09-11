package io.github.yuroyami.kiteplayer.audioviz.viz

import androidx.compose.ui.graphics.ImageBitmap

/**
 * A small picture whose pixels are written one by one, for drawings worked out on the processor a
 * cell at a time, such as the fluids.
 *
 * Fill [pixels] with opaque ARGB colours, row by row, call [upload], then draw [image].
 */
internal class PixelImage(val width: Int, val height: Int) {
    val pixels: IntArray = IntArray(width * height)
    val image: ImageBitmap = ImageBitmap(width, height)

    fun upload() {
        image.writePixels(pixels)
    }
}

/** Copies [pixels], opaque ARGB and row by row, into this picture. */
internal expect fun ImageBitmap.writePixels(pixels: IntArray)
