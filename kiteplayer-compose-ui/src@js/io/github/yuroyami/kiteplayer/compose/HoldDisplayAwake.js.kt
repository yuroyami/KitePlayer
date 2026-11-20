package io.github.yuroyami.kiteplayer.compose

import androidx.compose.runtime.Composable

/** Nothing: js has no video path, so there is no picture to keep the screen awake for. */
@Composable
internal actual fun HoldDisplayAwake() = Unit
