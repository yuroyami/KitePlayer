package io.github.yuroyami.kiteplayer.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The FLAGSHIP Compose path: video as a true Compose primitive.
 *
 * The frames are drawn through Compose's own pipeline, so every Compose modifier applies to the
 * video itself: clip it to a shape, animate its alpha and scale, rotate it, put it in a shared
 * element transition. No platform view, no interop hole. On this path the letterbox is
 * transparent by design, because video that is real Compose content should composite like it;
 * add a background modifier for the classic black bars.
 *
 * Android API 31+ can use the explicit-Window state overload to keep decoded pixels on the GPU:
 * MediaCodec to external-OES, one RGBA shader blit, then a HardwareBuffer-backed Compose image.
 * Other states and platforms retain their software or platform-specific fallback.
 *
 * Per frame, the UI tree does nothing: the frame state is read only inside the draw phase, so a
 * new frame invalidates drawing alone, never composition or layout.
 */
@Composable
public fun KiteVideo(state: KiteVideoState, modifier: Modifier = Modifier) {
    PlatformKiteVideo(state, modifier)
}

/** The platform's half of [KiteVideo]: a worker, and the image it builds drawn in the draw phase. */
@Composable
internal expect fun PlatformKiteVideo(state: KiteVideoState, modifier: Modifier)
