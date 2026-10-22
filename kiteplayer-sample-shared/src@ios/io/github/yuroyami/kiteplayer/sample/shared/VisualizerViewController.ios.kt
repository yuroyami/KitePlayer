package io.github.yuroyami.kiteplayer.sample.shared

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.window.ComposeUIViewController
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.availability
import io.github.yuroyami.kiteplayer.compose.rememberKitePlayer
import io.github.yuroyami.kiteplayer.isAvailable
import io.github.yuroyami.kiteplayer.session.attachMediaSession
import io.github.yuroyami.kiteplayer.audioviz.SongMapStore
import platform.Foundation.NSBundle
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.NSFileManager
import platform.UIKit.UIViewController

/**
 * The sample screen for iOS, with a player of its own. It plays the songs the app was built with,
 * copied into the bundle as `sample-song` and `sample-song-2` up to `sample-song-9`, or the
 * conformance clip when there is none. The song shows on the lock screen and in the control centre,
 * and pauses for a call.
 */
fun visualizerViewController(): UIViewController = ComposeUIViewController {
    if (!KitePlayer.isAvailable) {
        BasicText(
            "KitePlayer cannot run here: ${KitePlayer.availability}",
            style = TextStyle(color = Color.White),
        )
        return@ComposeUIViewController
    }
    val player = rememberKitePlayer()
    // The now playing card, the background handling and the call handling. They close with the player.
    remember(player) { player.attachMediaSession() }
    val media = remember {
        val bundle = NSBundle.mainBundle
        // sample-song, then sample-song-2 and up, so a build may carry several.
        val names = listOf("sample-song") + (2..9).map { "sample-song-$it" }
        sampleMedia(
            requested = null,
            songs = names.mapNotNull { name ->
                SONG_TYPES.firstNotNullOfOrNull { bundle.pathForResource(name, ofType = it) }
            },
            clip = bundle.pathForResource("sync1080p30", ofType = "mp4").orEmpty(),
        ) { NSFileManager.defaultManager.fileExistsAtPath(it) }
    }
    // A scanned song map outlives the process here, so a song played before is mapped at once.
    val songMaps = remember {
        val caches = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).firstOrNull()
        if (caches == null) SongMapStore.None else SongMapStore.inDirectory("$caches/kiteplayer-songmaps")
    }
    SampleScreen(player, media, songMapStore = songMaps)
}

