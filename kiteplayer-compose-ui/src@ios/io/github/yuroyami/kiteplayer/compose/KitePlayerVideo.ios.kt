package io.github.yuroyami.kiteplayer.compose

import androidx.compose.runtime.Composable

internal actual fun resolveRenderPath(requested: KiteRenderPath): KiteRenderPath = when (requested) {
    KiteRenderPath.Auto, KiteRenderPath.NativeView -> KiteRenderPath.NativeView
    KiteRenderPath.ComposeCanvas -> KiteRenderPath.ComposeCanvas
}

@Composable
internal actual fun rememberPlatformKiteVideoState(): KiteVideoState = rememberKiteVideoState()
