package io.github.yuroyami.kiteplayer.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.yuroyami.kiteplayer.KitePlayer

@Composable
internal actual fun platformKitePlayerSurface(player: KitePlayer?, modifier: Modifier) {
    EmptyKitePlayerSurface(modifier)
}
