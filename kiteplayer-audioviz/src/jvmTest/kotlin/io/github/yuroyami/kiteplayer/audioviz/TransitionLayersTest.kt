package io.github.yuroyami.kiteplayer.audioviz

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kiteplayer.audioviz.viz.VizTransition
import io.github.yuroyami.kiteplayer.audioviz.viz.drawTransitionLayers
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.TransitionBlend
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class TransitionLayersTest {
    init { useSkiaGraphics() }

    @Test
    fun layersMatchImageMixingIncludingTransparency() {
        val blend = TransitionBlend()
        val from: DrawScope.() -> Unit = {
            drawRect(Color(0.8f, 0.1f, 0.2f, 0.65f))
            drawCircle(Color.White, 8f, Offset(12f, 16f))
        }
        val to: DrawScope.() -> Unit = {
            drawRect(Color(0.1f, 0.4f, 0.9f, 0.4f))
            drawCircle(Color.Green, 10f, Offset(44f, 30f))
        }
        val first = render(from)
        val second = render(to)
        val transitions = listOf(VizTransition.Crossfade, VizTransition.NoiseWipe, VizTransition.Iris, VizTransition.StrobeCut)
        for (transition in transitions) {
            assertTrue(blend.canDrawLayers(transition))
            for (progress in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
                val expected = render {
                    drawRect(Color(0.15f, 0.18f, 0.2f))
                    drawRect(checkNotNull(blend.prepare(first, second, transition, progress, size.width, size.height)))
                }.toPixelMap()
                val actual = render {
                    drawRect(Color(0.15f, 0.18f, 0.2f))
                    drawTransitionLayers(blend, transition, progress, from, to)
                }.toPixelMap()
                var worst = 0f
                for (y in 0 until actual.height) for (x in 0 until actual.width) {
                    val a = actual[x, y]
                    val b = expected[x, y]
                    worst = maxOf(worst, abs(a.red - b.red), abs(a.green - b.green), abs(a.blue - b.blue), abs(a.alpha - b.alpha))
                }
                assertTrue(worst < 0.025f, "$transition at $progress changed a channel by $worst")
            }
        }
    }

    private fun render(draw: DrawScope.() -> Unit): ImageBitmap = ImageBitmap(64, 48).also {
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(it), Size(64f, 48f), draw)
    }
}
