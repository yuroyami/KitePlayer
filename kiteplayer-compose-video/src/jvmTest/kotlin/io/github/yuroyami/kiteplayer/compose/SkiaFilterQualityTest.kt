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
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 17.21 RQ-3, the drawing step's half: what [FilterQuality] actually buys on a Skia target.
 *
 * [KiteVideo] enlarges the published picture itself, so the kernel there is a `filterQuality` on
 * one `drawImage` call rather than a shader. Whether that is worth anything is a per-platform
 * fact, not a promise the API makes, and it decides where the Android tier has to put its own
 * kernel. This is the measurement for the Skia targets (desktop, iOS, web); the Android one is
 * `AndroidFilterQualityDeviceTest`, and the two do not agree.
 */
class SkiaFilterQualityTest {

    private val low = 40
    private val high = 210

    /** Eight by eight, left half dark and right half light: one step, enlarged eight times. */
    private fun stepEdge(): ImageBitmap {
        val bytes = ByteArray(8 * 8 * 4)
        for (index in 0 until 8 * 8) {
            val value = if (index % 8 < 4) low else high
            bytes[index * 4] = value.toByte()
            bytes[index * 4 + 1] = value.toByte()
            bytes[index * 4 + 2] = value.toByte()
            bytes[index * 4 + 3] = 0xFF.toByte()
        }
        val pool = FrameImagePool()
        return pool.imageFor(bytes, 8, 8).image
    }

    private fun scanAcross(source: ImageBitmap, quality: FilterQuality): List<Int> {
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
        val pixels = IntArray(64 * 64)
        target.readPixels(pixels)
        // The middle row, away from the edges where clamping decides the answer instead.
        return (16 until 48).map { (pixels[32 * 64 + it] shr 16) and 0xFF }
    }

    private fun steepness(scan: List<Int>) = scan.zipWithNext().maxOf { abs(it.second - it.first) }

    @Test
    fun `high is a real cubic on skia and low is not`() {
        val source = stepEdge()
        val bilinear = scanAcross(source, FilterQuality.Low)
        val cubic = scanAcross(source, FilterQuality.High)

        assertTrue(
            steepness(cubic) > steepness(bilinear),
            "Skia's High must rise faster than its Low: ${steepness(cubic)} against " +
                "${steepness(bilinear)}. Equal would mean the two map to the same sampler.",
        )
        assertTrue(
            bilinear.all { it in low..high },
            "bilinear cannot leave the source's own range, got ${bilinear.min()}..${bilinear.max()}",
        )
        assertTrue(
            cubic.min() < low || cubic.max() > high,
            "a cubic resampler rings past the step; got ${cubic.min()}..${cubic.max()} against " +
                "$low..$high, which is what plain bilinear would give",
        )
    }
}
