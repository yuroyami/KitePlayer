package io.github.yuroyami.kiteplayer.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.PlaybackStatus
import io.github.yuroyami.kiteplayer.mobile.installMobileRenderer
import io.github.yuroyami.kiteplayer.view.DEFAULT_VIDEO_ACCESSIBILITY_LABEL
import io.github.yuroyami.kiteplayer.view.KitePlayerUIView
import io.github.yuroyami.kiteplayer.view.accessibilityStateText
import kotlin.time.Duration

@Composable
internal actual fun platformKitePlayerSurface(
    player: KitePlayer?,
    modifier: Modifier,
    keepDisplayAwake: Boolean,
    accessibilityVideoLabel: String?,
    accessibilityStateFormat: ((PlaybackStatus, Duration, Duration?) -> String)?,
) {
    UIKitView(
        factory = { KitePlayerUIView().apply { installMobileRenderer() } },
        modifier = modifier,
        update = { view ->
            view.keepDisplayAwake = keepDisplayAwake
            // Only on a change: each assignment makes the view tell a screen reader again.
            val label = accessibilityVideoLabel ?: DEFAULT_VIDEO_ACCESSIBILITY_LABEL
            if (view.accessibilityVideoLabel != label) view.accessibilityVideoLabel = label
            val format = accessibilityStateFormat ?: ::accessibilityStateText
            if (view.accessibilityStateFormat != format) view.accessibilityStateFormat = format
            view.player = player
        },
        onRelease = { view -> view.release() },
    )
}
