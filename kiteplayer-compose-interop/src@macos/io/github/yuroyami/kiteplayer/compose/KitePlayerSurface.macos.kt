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
    // Native macOS Compose cannot host an AppKit view, so this stays empty. Say why once, or the
    // empty box reads as a bug.
    if (!saidNoVideo) {
        saidNoVideo = true
        io.github.yuroyami.kiteplayer.KiteLog.log(
            "KitePlayerSurface",
            "native macOS Compose cannot host the AppKit video view, so this KitePlayerSurface stays empty; use KiteVideo for Compose video on macOS",
        )
    }
    EmptyKitePlayerSurface(modifier)
}

/** Read and written on the main thread only, where composition runs. */
private var saidNoVideo = false
