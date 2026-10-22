package io.github.yuroyami.kiteplayer.audioviz

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import io.github.yuroyami.kiteplayer.audioviz.viz.Kit
import io.github.yuroyami.kiteplayer.audioviz.viz.Layered
import io.github.yuroyami.kiteplayer.audioviz.viz.MoodSpec
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDrive
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDriver
import io.github.yuroyami.kiteplayer.audioviz.viz.VizEnergy
import io.github.yuroyami.kiteplayer.audioviz.viz.VizMapping
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.VizProperty
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.WarpFields
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.WarpSpec
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A warp run twice over the same music draws the same picture.
 *
 * The warp reads the previous frame and bends it, so any randomness or leftover state compounds
 * frame after frame. This checks that nothing leaks: the same drawing, restarted, replays 120 frames
 * to within one step of brightness per channel. No drawing in the catalogue uses a warp now, so a
 * small drawing here stands in for one a library user might write.
 */
class WarpDeterminismTest {

    init { useSkiaGraphics() }

    @Test
    fun aWarpReplaysExactly() {
        val drawing = WarpedRing()
        val first = RenderHarness.render(drawing, 200, 125, 120, VizPalette.Prism)
        val second = RenderHarness.render(drawing, 200, 125, 120, VizPalette.Prism)
        var worst = 0
        var lit = 0
        for (y in 0 until first.height) {
            for (x in 0 until first.width) {
                val a = first.getRGB(x, y)
                val b = second.getRGB(x, y)
                if ((a shr 8 and 0xFF) > 100 && (a and 0xFF) > 100) lit++
                worst = maxOf(
                    worst,
                    abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF)),
                    abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF)),
                    abs((a and 0xFF) - (b and 0xFF)),
                )
            }
        }
        println("warped ring: the largest difference between two runs was $worst, $lit pixels lit")
        assertTrue(lit > 200, "the warped ring should draw something, lit $lit pixels")
        assertTrue(worst <= 1, "the warp did not replay: a channel differed by $worst")
    }

    /** A ring of light the level sizes, left to echo under the wells warp. */
    private class WarpedRing : Layered(name = "Warped Ring", bucket = VizEnergy.Mid, kit = Kit(seed = 9L)) {
        override val mapping: VizMapping by mappingOf(VizDrive(VizDriver.Level, VizProperty.Size))
        override val moodSpec: MoodSpec = MoodSpec(calmTrail = 0.9f, livelyTrail = 0.86f)
        override val warp: WarpSpec = WarpSpec(WarpFields.WELLS, calmAmount = 0.015f, livelyAmount = 0.05f)

        override fun advance(state: VizRenderState) {
            // A second well to the right, fully present, one ripple.
            warp.params[0] = 0.6f
            warp.params[1] = 0f
            warp.params[2] = 1f
            warp.params[3] = 1f
        }

        override fun DrawScope.drawEcho(state: VizRenderState) {
            val radius = size.minDimension * (0.15f + 0.2f * state.frame.level)
            drawCircle(Color.Cyan, radius, style = Stroke(3f))
        }
    }
}
