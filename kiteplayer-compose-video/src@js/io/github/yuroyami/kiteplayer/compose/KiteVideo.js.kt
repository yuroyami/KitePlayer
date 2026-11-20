package io.github.yuroyami.kiteplayer.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.yuroyami.kiteplayer.KiteLog

@Composable
internal actual fun PlatformKiteVideo(state: KiteVideoState, modifier: Modifier) {
    // An empty box with no word of why would read as a bug, so say it once.
    if (!saidNoVideo) {
        saidNoVideo = true
        KiteLog.log("KiteVideo", "js has no Compose video path, so this KiteVideo stays empty; use wasmJs for Compose video on the web")
    }
    Box(modifier)
}

/** The page has one thread, so a plain flag. */
private var saidNoVideo = false
