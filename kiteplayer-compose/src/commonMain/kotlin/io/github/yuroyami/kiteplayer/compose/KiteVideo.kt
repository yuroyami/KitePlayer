package io.github.yuroyami.kiteplayer.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

/**
 * The FLAGSHIP Compose path (D-6, 17.9): video as a true Compose primitive.
 *
 * The frames are drawn through Compose's own pipeline, so every Compose modifier applies to the
 * video itself: clip it to a shape, animate its alpha and scale, rotate it, put it in a shared
 * element transition. No platform view, no interop hole. On this path the letterbox is
 * transparent by design, because video that is real Compose content should composite like it;
 * add a background modifier for the classic black bars.
 *
 * The honest trade, until S2 measures and improves it: each frame is CPU-converted and uploaded,
 * which costs more power than [KitePlayerSurface]'s display-controller path. Sustained
 * fullscreen playback belongs to the baseline; video embedded inside UI loses nothing, because
 * Compose was compositing those pixels anyway. Per-frame cost is UNMEASURED until S2's exit.
 *
 * Per frame, the UI tree does nothing: the frame state is read only inside the draw phase, so a
 * new frame invalidates drawing alone, never composition or layout (law 1 of 17.9).
 */
@Composable
public fun KiteVideo(state: KiteVideoState, modifier: Modifier = Modifier) {
    Box(
        modifier.drawBehind {
            // The one sanctioned read of the frame state (law 1). Do not add another.
            val frame = state.frame.value ?: return@drawBehind
            val layout = videoLayout(
                areaWidth = size.width.toInt(),
                areaHeight = size.height.toInt(),
                size = frame.size,
                rotationDegrees = frame.rotationDegrees,
            ) ?: return@drawBehind
            rotate(
                degrees = layout.rotationDegrees.toFloat(),
                pivot = androidx.compose.ui.geometry.Offset(layout.centerX, layout.centerY),
            ) {
                drawImage(
                    image = frame.image,
                    dstOffset = IntOffset(layout.drawLeft.roundToInt(), layout.drawTop.roundToInt()),
                    dstSize = IntSize(
                        layout.drawWidth.roundToInt().coerceAtLeast(1),
                        layout.drawHeight.roundToInt().coerceAtLeast(1),
                    ),
                    filterQuality = FilterQuality.Low,
                )
            }
        },
    )
}
