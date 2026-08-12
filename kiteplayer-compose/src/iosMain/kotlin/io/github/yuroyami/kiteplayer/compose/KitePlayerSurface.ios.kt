package io.github.yuroyami.kiteplayer.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.phone.KitePlayerUIView

@Composable
public actual fun KitePlayerSurface(player: KitePlayer?, modifier: Modifier) {
    UIKitView(
        factory = { KitePlayerUIView() },
        modifier = modifier,
        update = { view -> view.player = player },
        onRelease = { view -> view.player = null },
    )
}
