package io.github.yuroyami.kiteplayer.audioviz.viz

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.TransitionBlend

/** Mixes premultiplied layers exactly like the image shader, while keeping drawing on the GPU. */
internal fun DrawScope.drawTransitionLayers(
    blend: TransitionBlend,
    transition: VizTransition,
    progress: Float,
    from: DrawScope.() -> Unit,
    to: DrawScope.() -> Unit,
) {
    val canvas = drawContext.canvas
    val bounds = Rect(Offset.Zero, size)
    // Isolate the sum so Plus cannot add either drawing to the ground underneath it.
    canvas.saveLayer(bounds, Paint())
    try {
        for (incoming in 0..1) {
            val mask = blend.mask(transition, progress, size.width, size.height, incoming == 1) ?: continue
            canvas.saveLayer(bounds, Paint().also { if (incoming == 1) it.blendMode = BlendMode.Plus })
            try {
                if (incoming == 1) to() else from()
                drawRect(mask, blendMode = BlendMode.DstIn)
            } finally {
                canvas.restore()
            }
        }
    } finally {
        canvas.restore()
    }
}
