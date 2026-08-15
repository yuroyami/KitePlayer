package io.github.yuroyami.kiteplayer.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.mobile.installMobileRenderer
import io.github.yuroyami.kiteplayer.view.KitePlayerUIView

@Composable
internal actual fun platformKitePlayerSurface(player: KitePlayer?, modifier: Modifier) {
    UIKitView(
        factory = { KitePlayerUIView().apply { installMobileRenderer() } },
        modifier = modifier,
        update = { view -> view.player = player },
        onRelease = { view -> view.release() },
    )
}
