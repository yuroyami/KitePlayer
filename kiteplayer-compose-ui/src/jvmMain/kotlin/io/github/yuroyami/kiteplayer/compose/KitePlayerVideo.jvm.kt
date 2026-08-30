package io.github.yuroyami.kiteplayer.compose

import androidx.compose.runtime.Composable

/**
 * Desktop honours an explicit native-view request and still defaults to the Compose canvas.
 *
 * Both paths are real here since 2026-08-30. The native view is an AWT canvas painted off the
 * Compose frame clock, which is what keeps video steady while the UI is busy; the Compose canvas
 * is video as ordinary Compose content, which is what lets clip, alpha and rotation apply to the
 * pixels and what lets controls sit on top of it and be clicked.
 *
 * [KiteRenderPath.Auto] stays on the Compose canvas deliberately rather than by omission. The
 * native view wins on jank and loses on input, because macOS routes a click to the topmost native
 * view and Compose content painted over it never receives one. Which of those a consumer should
 * get without asking is an owner decision to take on measurements, not a default to drift into.
 */
internal actual fun resolveRenderPath(requested: KiteRenderPath): KiteRenderPath = when (requested) {
    KiteRenderPath.NativeView -> KiteRenderPath.NativeView
    KiteRenderPath.ComposeCanvas, KiteRenderPath.Auto -> KiteRenderPath.ComposeCanvas
}

@Composable
internal actual fun rememberPlatformKiteVideoState(): KiteVideoState = rememberKiteVideoState()
