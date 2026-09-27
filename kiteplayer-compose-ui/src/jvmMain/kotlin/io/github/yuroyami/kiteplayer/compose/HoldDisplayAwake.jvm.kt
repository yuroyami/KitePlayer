package io.github.yuroyami.kiteplayer.compose

import androidx.compose.runtime.Composable

/** Nothing: the desktop JVM has no call that keeps the display awake. */
@Composable
internal actual fun HoldDisplayAwake() = Unit
