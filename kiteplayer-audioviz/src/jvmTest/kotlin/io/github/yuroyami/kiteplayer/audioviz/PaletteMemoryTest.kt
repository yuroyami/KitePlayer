package io.github.yuroyami.kiteplayer.audioviz

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kiteplayer.audioviz.viz.dominantColors
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A large album cover does not cost an array of all its pixels. */
class PaletteMemoryTest {

    init { useSkiaGraphics() }

    @Test
    fun aLargeCoverIsSampledNotCopied() {
        val side = 2_400
        val cover = ImageBitmap(side, side)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(cover), Size(side.toFloat(), side.toFloat())) {
            drawRect(Color(0xFF204080))
            drawRect(Color(0xFFE0A030), topLeft = androidx.compose.ui.geometry.Offset(0f, side / 2f),
                size = Size(side.toFloat(), side / 2f))
        }
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val thread = Thread.currentThread().id
        dominantColors(cover, 2)
        val before = bean.getThreadAllocatedBytes(thread)
        val colours = dominantColors(cover, 2)
        val allocated = bean.getThreadAllocatedBytes(thread) - before
        assertEquals(2, colours.size)
        // A copy of every pixel would be 23 MB. The 64 by 64 grid needs far less.
        assertTrue(allocated < 4_000_000, "the palette allocated $allocated bytes")
    }
}
