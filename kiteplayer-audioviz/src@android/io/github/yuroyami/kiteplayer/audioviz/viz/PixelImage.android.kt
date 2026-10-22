package io.github.yuroyami.kiteplayer.audioviz.viz

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap

internal actual class PixelUpload actual constructor(image: ImageBitmap) {
    private val bitmap = image.asAndroidBitmap()

    actual fun write(pixels: IntArray) {
        bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }
}
