package io.github.yuroyami.kiteplayer.audioviz.viz

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import org.jetbrains.skia.ColorType

internal actual class PixelUpload actual constructor(image: ImageBitmap) {
    private val bitmap = image.asSkiaBitmap()
    // Skia keeps pictures in the machine's own byte order: blue first on most, red first on a few.
    private val blueFirst = bitmap.colorType == ColorType.BGRA_8888
    private val bytes = ByteArray(image.width * image.height * 4)

    actual fun write(pixels: IntArray) {
        for (index in pixels.indices) {
            val colour = pixels[index]
            val at = index * 4
            val red = (colour shr 16).toByte()
            val blue = colour.toByte()
            bytes[at] = if (blueFirst) blue else red
            bytes[at + 1] = (colour shr 8).toByte()
            bytes[at + 2] = if (blueFirst) red else blue
            bytes[at + 3] = (colour ushr 24).toByte()
        }
        bitmap.installPixels(bytes)
    }
}
