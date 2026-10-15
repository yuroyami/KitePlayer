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
 * On the web (wasmJs) the picture is drawn in Compose's own WebGL context, through Compose's
 * experimental WebGL render target. Each frame is converted to RGBA in C inside the codec module,
 * copied into one browser array and uploaded as one texture, so its pixels never enter Kotlin
 * memory. One shader pass then places it, scales it, applies the picture controls and lays the
 * subtitles over it, and Compose draws the result like any other content, so Compose content can
 * sit over the picture and clip and alpha apply to it. A browser without WebGL2 draws nothing
 * here.
 *
 * Per frame, the UI tree does nothing: the frame state is read only inside the draw phase, so a
 * new frame invalidates drawing alone, never composition or layout.
 */
@Composable
public fun KiteVideo(state: KiteVideoState, modifier: Modifier = Modifier) {
    PlatformKiteVideo(state, modifier)
}

/** The platform's half of [KiteVideo]: a worker and a drawn image, or the web's WebGL pass. */
@Composable
internal expect fun PlatformKiteVideo(state: KiteVideoState, modifier: Modifier)
