package io.github.yuroyami.kiteplayer.compose

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertContentEquals

/**
 * Why the Android GPU tier enlarges in its own blit instead of at the draw.
 *
 * The obvious way to put a kernel into [KiteVideo] is to raise the `filterQuality` of the one
 * `drawImage` it makes, and on Skia that works (`SkiaFilterQualityTest` measures it). On Android
 * it does not, and this is the proof in pixels rather than in a reading of somebody's source:
 * every quality above None maps onto the single `isFilterBitmap` flag, so High, Medium and Low
 * are one bilinear and produce byte-identical output.
 *
 * That is the whole reason [io.github.yuroyami.kiteplayer.VideoScaler.CatmullRom] makes the
 * Android blit take the enlargement itself. If this test ever starts failing, Android has grown a
 * real resampler and that decision is worth revisiting.
 */
@RunWith(AndroidJUnit4::class)
class AndroidFilterQualityDeviceTest {

    private fun stepEdge(): ImageBitmap {
        val bytes = ByteArray(8 * 8 * 4)
        for (index in 0 until 8 * 8) {
            val value = if (index % 8 < 4) 40 else 210
            bytes[index * 4] = value.toByte()
            bytes[index * 4 + 1] = value.toByte()
            bytes[index * 4 + 2] = value.toByte()
            bytes[index * 4 + 3] = 0xFF.toByte()
        }
        return FrameImagePool().imageFor(bytes, 8, 8).image
    }

    private fun enlarged(source: ImageBitmap, quality: FilterQuality): IntArray {
        val target = ImageBitmap(64, 64)
        CanvasDrawScope().draw(
            Density(1f), LayoutDirection.Ltr, Canvas(target), Size(64f, 64f),
        ) {
            drawImage(
                image = source,
                dstOffset = IntOffset(0, 0),
                dstSize = IntSize(64, 64),
                filterQuality = quality,
            )
        }
        return IntArray(64 * 64).also(target::readPixels)
    }

    @Test
    fun everyFilterQualityAboveNoneIsTheSameBilinear() {
        val source = stepEdge()
        val low = enlarged(source, FilterQuality.Low)
        assertContentEquals(
            low,
            enlarged(source, FilterQuality.Medium),
            "Medium differs from Low, so Android has gained a mipmapped sampler",
        )
        assertContentEquals(
            low,
            enlarged(source, FilterQuality.High),
            "High differs from Low, so Android has gained a cubic resampler and the kernel rung's Android " +
                "half could move to the draw instead of the blit",
        )
    }
}
