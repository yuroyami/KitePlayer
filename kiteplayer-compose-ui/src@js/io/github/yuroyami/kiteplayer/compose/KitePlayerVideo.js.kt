package io.github.yuroyami.kiteplayer.compose

import androidx.compose.runtime.Composable

/**
 * js has no native view, so every request resolves to [KiteRenderPath.ComposeCanvas]. `KitePlayerSurface` is an empty
 * placeholder here, so a request for [KiteRenderPath.NativeView] is coerced and onEffectivePath says so.
 */
internal actual fun resolveRenderPath(requested: KiteRenderPath): KiteRenderPath = KiteRenderPath.ComposeCanvas

@Composable
internal actual fun rememberPlatformKiteVideoState(): KiteVideoState = rememberKiteVideoState()

/** There is no native view here, so Compose content on top takes the clicks. */
internal actual val nativeViewTakesOverlayClicks: Boolean = true
