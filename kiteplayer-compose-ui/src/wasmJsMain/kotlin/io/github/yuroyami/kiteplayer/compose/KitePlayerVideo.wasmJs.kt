package io.github.yuroyami.kiteplayer.compose

import androidx.compose.runtime.Composable

/**
 * The web has one real path, and every request resolves to it: [KiteRenderPath.ComposeCanvas],
 * which draws the picture in Compose's own WebGL context (#125). The native view of the other
 * platforms has no web counterpart, `KitePlayerSurface` is an empty placeholder there, so a request
 * for [KiteRenderPath.NativeView] is coerced and onEffectivePath says so.
 */
internal actual fun resolveRenderPath(requested: KiteRenderPath): KiteRenderPath = KiteRenderPath.ComposeCanvas

@Composable
internal actual fun rememberPlatformKiteVideoState(): KiteVideoState = rememberKiteVideoState()
