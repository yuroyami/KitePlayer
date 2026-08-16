package io.github.yuroyami.kiteplayer.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.onSizeChanged
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
 * Android API 31+ can use the explicit-Window state overload to keep decoded pixels on the GPU:
 * MediaCodec to external-OES, one RGBA shader blit, then a HardwareBuffer-backed Compose image.
 * Other states/platforms retain their software or platform-specific fallback.
 *
 * Per frame, the UI tree does nothing: the frame state is read only inside the draw phase, so a
 * new frame invalidates drawing alone, never composition or layout (law 1 of 17.9).
 */
@Composable
public fun KiteVideo(state: KiteVideoState, modifier: Modifier = Modifier) {
    val frameCommitter = rememberKiteVideoFrameCommitter(state)
    val viewportOwner = remember(state) { Any() }
    DisposableEffect(state, viewportOwner) {
        onDispose { state.removeViewport(viewportOwner) }
    }
    Box(
        modifier
            .onSizeChanged { measuredSize ->
                // Compose reports layout size in physical pixels. Pass scale 1 rather than applying
                // density a second time. This callback mutates no Compose state, so the renderer's
                // output-size update cannot feed back into composition or layout.
                state.updateViewport(viewportOwner, measuredSize.width, measuredSize.height)
            }
            .drawBehind {
                // The two sanctioned draw-phase reads (law 1): the frame, and the overlay whose
                // writes arrive only on cue edges. Do not add another.
                val frame = state.acquireFrameForDraw(frameCommitter.canDrawCommitFencedFrames)
                if (frame == null) {
                    frameCommitter.frameRecorded(null)
                    // SOL-R2: audio-only media and the moment before the first frame still show
                    // their subtitles; the picture's absence is not the text's.
                    drawOverlayItems(state)
                    return@drawBehind
                }
                var recorded = false
                try {
                    // The mode, the picture-control filter and the framing are the remaining
                    // sanctioned draw-phase reads: all change on a user's setting, so their
                    // invalidations are rarer than the overlay's.
                    val mode = state.scaleMode.value
                    val videoFilter = state.videoColorFilter.value
                    val framing = state.transform.value
                    val layout = videoLayout(
                        areaWidth = size.width.toInt(),
                        areaHeight = size.height.toInt(),
                        size = frame.size,
                        rotationDegrees = frame.rotationDegrees,
                        mode = mode,
                        transform = framing,
                    ) ?: return@drawBehind
                    // Fill overhangs the component by design; the clip keeps the crop inside it.
                    // Fit and Stretch never overhang, so they keep the unclipped fast path.
                    val draw: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit = {
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
                                // The picture controls, on the VIDEO image only: subtitles below
                                // composite unfiltered, exactly like every platform renderer.
                                colorFilter = videoFilter,
                            )
                        }
                    }
                    // Fill overhangs by design; zoom and pan can overhang under ANY mode. Both
                    // clip; the unzoomed Fit and Stretch keep the unclipped fast path.
                    if (mode == io.github.yuroyami.kiteplayer.VideoScale.Fill || !framing.isIdentity) {
                        clipRect { draw() }
                    } else {
                        draw()
                    }
                    recorded = true
                } finally {
                    state.frameDrawFinished(frame, recorded)
                    if (recorded) {
                        // A software image carries no ImageReader lease, but its committed display
                        // list is still the proof that an older hardware image is no longer sampled.
                        frameCommitter.frameRecorded(frame.takeIf(KiteVideoFrame::requiresCommitFence))
                    }
                }
                // Subtitles composite above the picture in OUTPUT space, unrotated: overlays are
                // laid out for the output size, the same law the platform renderers obey.
                drawOverlayItems(state)
            },
    )
}

/** The overlay pass, shared by the with-picture and no-picture draws (SOL-R2). */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawOverlayItems(state: KiteVideoState) {
    val overlay = state.overlay.value ?: return
    val scaleX = size.width / overlay.viewportWidth.coerceAtLeast(1)
    val scaleY = size.height / overlay.viewportHeight.coerceAtLeast(1)
    overlay.items.forEach { item ->
        drawImage(
            image = item.image,
            dstOffset = IntOffset(
                (item.x * scaleX).roundToInt(),
                (item.y * scaleY).roundToInt(),
            ),
            dstSize = IntSize(
                (item.width * scaleX).roundToInt().coerceAtLeast(1),
                (item.height * scaleY).roundToInt().coerceAtLeast(1),
            ),
            filterQuality = FilterQuality.Low,
        )
    }
}
