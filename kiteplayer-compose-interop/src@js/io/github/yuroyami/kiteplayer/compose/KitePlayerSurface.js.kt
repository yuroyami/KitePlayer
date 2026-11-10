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
    // The default player already refuses js, so only a custom one reaches here, and it would
    // otherwise get an empty box with no word of why (#928).
    if (!saidNoVideo) {
        saidNoVideo = true
        io.github.yuroyami.kiteplayer.KiteLog.log(
            "KitePlayerSurface",
            "js has no video surface, so this KitePlayerSurface stays empty; Compose video on the web is KiteVideo, on wasmJs",
        )
    }
    EmptyKitePlayerSurface(modifier)
}

/** The page has one thread, so a plain flag. */
private var saidNoVideo = false
