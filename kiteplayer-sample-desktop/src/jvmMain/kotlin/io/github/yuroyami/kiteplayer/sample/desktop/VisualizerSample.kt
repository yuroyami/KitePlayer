package io.github.yuroyami.kiteplayer.sample.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import io.github.yuroyami.kiteplayer.KitePlayerPlatform
import io.github.yuroyami.kiteplayer.compose.KiteRenderPath
import io.github.yuroyami.kiteplayer.sample.shared.SampleMedia
import io.github.yuroyami.kiteplayer.sample.shared.SampleScreen

/** The front screen: [media] drawn by the audio visualiser, or shown as video when it has a picture. */
@Composable
internal fun VisualizerSample(media: SampleMedia) {
    val player = remember { KitePlayerPlatform.createOrNull() }
    if (player == null) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            BasicText(
                "KitePlayer is unavailable on this JVM: ${KitePlayerPlatform.availability}",
                style = TextStyle(color = Color.White),
            )
        }
        return
    }
    DisposableEffect(player) { onDispose { player.close() } }
    // The controls sit over the picture, and on desktop only the Compose canvas lets them show and take clicks.
    SampleScreen(player, media, videoPath = KiteRenderPath.ComposeCanvas)
}
