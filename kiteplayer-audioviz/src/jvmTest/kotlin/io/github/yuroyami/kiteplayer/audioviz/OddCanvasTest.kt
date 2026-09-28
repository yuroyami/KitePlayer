package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.VizCatalog
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every drawing survives a canvas of any shape.
 *
 * A surface is laid out at a size of one pixel or a sliver while a window opens, rotates or
 * splits, and a drawing that divides by the width or the height must not throw. Each drawing
 * renders a second of music at each odd size.
 */
class OddCanvasTest {

    init { useSkiaGraphics() }

    private val sizes = listOf(1 to 1, 2 to 2, 1 to 48, 48 to 1, 5 to 300, 300 to 5, 33 to 19)

    @Test
    fun everyDrawingRendersAtEveryOddSize() {
        val indices = VizCatalog.create().indices.toList()
        val failures = RenderHarness.inParallel(indices) { index ->
            val problems = ArrayList<String>()
            for ((width, height) in sizes) {
                val drawing = DriverProbe.drawing(index)
                val player = RenderHarness.player(RenderHarness.Song.Lively, 6f)
                try {
                    RenderHarness.forEachFrameOf(drawing, width, height, 60, VizPalette.Prism,
                        source = { player.next(1f / 60f) }) { _, _ -> }
                } catch (failure: Throwable) {
                    problems += "${drawing.name} at ${width}x$height: $failure"
                }
            }
            problems
        }.flatten()
        assertTrue(failures.isEmpty(), "drawings that failed at an odd size:\n" + failures.joinToString("\n"))
    }
}
