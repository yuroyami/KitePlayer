package io.github.yuroyami.kiteplayer.compose

import androidx.compose.runtime.Composable

/** JVM has no native video view: the interop surface draws an empty box there, so it coerces. */
internal actual fun resolveRenderPath(requested: KiteRenderPath): KiteRenderPath =
    KiteRenderPath.ComposeCanvas

@Composable
internal actual fun rememberPlatformKiteVideoState(): KiteVideoState = rememberKiteVideoState()
