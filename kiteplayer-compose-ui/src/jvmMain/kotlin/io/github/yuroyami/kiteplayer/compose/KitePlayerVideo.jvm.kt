package io.github.yuroyami.kiteplayer.compose

import androidx.compose.runtime.Composable

/**
 * Desktop resolves every request to a real path, and [KiteRenderPath.Auto] is the native view.
 *
 * Both products are real here. The native view is an AWT canvas painted off the Compose frame
 * clock; the Compose canvas is video as ordinary Compose content, where clip, alpha and rotation
 * apply to the pixels and where controls can sit on top and be clicked.
 *
 * **Auto is the native view, owner-decided 2026-08-30, and it is a trade rather than a free
 * upgrade.** It was taken on measurements: with the UI choked to 4.7 frames a second the native
 * view kept painting about 29 frames a second of 1080p30, while the Compose canvas draws the
 * picture at whatever rate the UI is managing. What it costs is input. macOS routes a click to
 * the topmost NATIVE view, so Compose content drawn over the video never receives one, and a
 * consumer who wants controls ON the picture either puts them in a borderless window owned by the
 * video window or asks for [KiteRenderPath.ComposeCanvas] explicitly. Controls beside the video
 * are unaffected.
 *
 * The full method and every arrangement measured are in
 * `kiteplayer-sample-desktop/INTEROP-SPIKE.md`.
 */
internal actual fun resolveRenderPath(requested: KiteRenderPath): KiteRenderPath = when (requested) {
    KiteRenderPath.NativeView, KiteRenderPath.Auto -> KiteRenderPath.NativeView
    KiteRenderPath.ComposeCanvas -> KiteRenderPath.ComposeCanvas
}

@Composable
internal actual fun rememberPlatformKiteVideoState(): KiteVideoState = rememberKiteVideoState()
