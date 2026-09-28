package io.github.yuroyami.kiteplayer.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.PlaybackStatus
import kotlin.time.Duration

@Composable
internal actual fun platformKitePlayerSurface(
    player: KitePlayer?,
    modifier: Modifier,
    keepDisplayAwake: Boolean,
    accessibilityVideoLabel: String?,
    accessibilityStateFormat: ((PlaybackStatus, Duration, Duration?) -> String)?,
) {
    EmptyKitePlayerSurface(modifier)
}
