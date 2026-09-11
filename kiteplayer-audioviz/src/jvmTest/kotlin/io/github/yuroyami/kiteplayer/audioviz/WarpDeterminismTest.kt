package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.VizCatalog
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A warp run twice over the same music draws the same picture.
 *
 * The warp reads the previous frame and bends it, so any randomness or leftover state compounds
 * frame after frame. This checks that nothing leaks: the same drawing, restarted, replays 120 frames
 * to within one step of brightness per channel.
 */
class WarpDeterminismTest {

    init { useSkiaGraphics() }

    @Test
    fun aWarpReplaysExactly() {
        val catalogue = VizCatalog.create()
        for (name in listOf("Twist", "Flow Field", "Fractal Zoom", "Ink")) {
            val drawing = catalogue.first { it.name == name }
            val first = RenderHarness.render(drawing, 200, 125, 120, VizPalette.Prism)
            val second = RenderHarness.render(drawing, 200, 125, 120, VizPalette.Prism)
            var worst = 0
            for (y in 0 until first.height) {
                for (x in 0 until first.width) {
                    val a = first.getRGB(x, y)
                    val b = second.getRGB(x, y)
                    worst = maxOf(
                        worst,
                        abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF)),
                        abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF)),
                        abs((a and 0xFF) - (b and 0xFF)),
                    )
                }
            }
            println("$name: the largest difference between two runs was $worst")
            assertTrue(worst <= 1, "$name did not replay: a channel differed by $worst")
        }
    }
}
