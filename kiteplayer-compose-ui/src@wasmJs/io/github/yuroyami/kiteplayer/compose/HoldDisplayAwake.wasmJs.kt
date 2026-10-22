@file:OptIn(io.github.yuroyami.kiteplayer.KitePlayerLowLevelApi::class)

package io.github.yuroyami.kiteplayer.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import io.github.yuroyami.kiteplayer.output.WebDisplayAwake

/**
 * The page's screen wake lock, held while this is composed. One hold among the page's others,
 * through the same counted lock the canvas renderer and the worker player use, so the screen is
 * released only when the last of them lets go.
 */
@Composable
internal actual fun HoldDisplayAwake() {
    DisposableEffect(Unit) {
        WebDisplayAwake.setHeld(true)
        onDispose { WebDisplayAwake.setHeld(false) }
    }
}
