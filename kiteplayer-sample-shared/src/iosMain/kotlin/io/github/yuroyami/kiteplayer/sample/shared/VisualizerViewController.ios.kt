package io.github.yuroyami.kiteplayer.sample.shared

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.window.ComposeUIViewController
import io.github.yuroyami.kiteplayer.KitePlayerPlatform
import platform.Foundation.NSBundle
import platform.Foundation.NSFileManager
import platform.UIKit.UIViewController

/**
 * The sample screen for iOS, with a player of its own. It plays the song the app was built with,
 * copied into the bundle as `sample-song`, or the conformance clip when the song is missing.
 */
fun visualizerViewController(): UIViewController = ComposeUIViewController {
    val player = remember { KitePlayerPlatform.createOrNull() }
    if (player == null) {
        BasicText(
            "KitePlayer cannot run here: ${KitePlayerPlatform.availability}",
            style = TextStyle(color = Color.White),
        )
        return@ComposeUIViewController
    }
    DisposableEffect(player) { onDispose { player.close() } }
    val media = remember {
        val bundle = NSBundle.mainBundle
        sampleMedia(
            requested = null,
            song = SONG_TYPES.firstNotNullOfOrNull { bundle.pathForResource("sample-song", ofType = it) },
            clip = bundle.pathForResource("sync1080p30", ofType = "mp4").orEmpty(),
        ) { NSFileManager.defaultManager.fileExistsAtPath(it) }
    }
    SampleScreen(player, media)
}

private val SONG_TYPES = listOf("mp3", "m4a", "flac", "ogg", "wav", "aac")
