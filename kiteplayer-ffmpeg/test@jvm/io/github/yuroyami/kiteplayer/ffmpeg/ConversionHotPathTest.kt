package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.spi.ColorSpaceInfo
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import kotlin.test.Test
import kotlin.test.assertEquals

/** The CPU colour conversion against a bound of ten times its measured median. */
class ConversionHotPathTest {

    @Test
    fun convertingOne1080pPlanarFrameToRgbaStaysUnderItsBound() {
        val chromaWidth = (WIDTH + 1) shr 1
        val chromaHeight = (HEIGHT + 1) shr 1
        val planes = ByteArray(WIDTH * HEIGHT + 2 * chromaWidth * chromaHeight) { (it * 31 % 255).toByte() }
        var converted = 0
        hotPathGate("convert one 1920x1080 yuv420p frame to RGBA", CONVERSION_BOUND_MS) {
            converted = tightlyPackedToRgba(
                bytes = planes,
                width = WIDTH,
                height = HEIGHT,
                pixelFormat = PlayerPixelFormat.Yuv420p,
                colorSpace = ColorSpaceInfo(),
            ).size
        }
        // Not vacuous: a converter that stopped converting would time an empty call.
        assertEquals(WIDTH * HEIGHT * 4, converted)
    }

    private companion object {
        const val WIDTH = 1920
        const val HEIGHT = 1080

        // Ten times the median measured on an Apple M2 with JDK 21, which is in the comment.
        const val CONVERSION_BOUND_MS = 60.0 // measured 5.4 to 5.8 ms, once 8.6 ms
    }
}
