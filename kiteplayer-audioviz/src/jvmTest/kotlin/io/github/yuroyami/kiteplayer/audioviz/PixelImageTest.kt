package io.github.yuroyami.kiteplayer.audioviz

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kiteplayer.audioviz.viz.PixelImage
import kotlin.test.Test
import kotlin.test.assertEquals

/** Pixels written by hand have to be the ones that get drawn, frame after frame. */
class PixelImageTest {

    init { useSkiaGraphics() }

    @Test
    fun writtenPixelsAreTheOnesDrawn() {
        val picture = PixelImage(4, 2)
        picture.pixels.fill(RED)
        picture.pixels[5] = GREEN
        picture.upload()
        val first = drawn(picture)
        assertEquals(RED, first[0], "the first pixel should be red")
        assertEquals(GREEN, first[5], "the sixth pixel should be green")

        // A second upload has to replace the first rather than being hidden behind a cached copy.
        picture.pixels.fill(BLUE)
        picture.upload()
        assertEquals(BLUE, drawn(picture)[5], "the new pixels should replace the old ones")
    }

    private fun drawn(picture: PixelImage): IntArray {
        val target = ImageBitmap(picture.width, picture.height)
        val size = Size(picture.width.toFloat(), picture.height.toFloat())
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(target), size) {
            drawImage(picture.image)
        }
        val read = IntArray(picture.width * picture.height)
        target.readPixels(read)
        return read
    }

    private companion object {
        const val RED = 0xFFFF0000.toInt()
        const val GREEN = 0xFF00FF00.toInt()
        const val BLUE = 0xFF0000FF.toInt()
    }
}
