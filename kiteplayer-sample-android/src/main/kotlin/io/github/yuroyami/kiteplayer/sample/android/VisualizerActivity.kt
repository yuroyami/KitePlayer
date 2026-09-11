package io.github.yuroyami.kiteplayer.sample.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.KitePlayerPlatform
import io.github.yuroyami.kiteplayer.sample.shared.SampleButton
import io.github.yuroyami.kiteplayer.sample.shared.SampleMedia
import io.github.yuroyami.kiteplayer.sample.shared.SampleScreen
import java.io.File

/**
 * The app's front screen: the song built in from `kiteplayer.sample.song`, drawn by the audio
 * visualiser, or the conformance clip as video with a note on setting a song up. "Other samples"
 * opens the launcher with the presentation comparisons.
 */
internal class VisualizerActivity : ComponentActivity() {
    private var player: KitePlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val player = KitePlayerPlatform.createOrNull()
        this.player = player
        val song = assets.list("")?.firstOrNull { name -> SONG_TYPES.any { name.endsWith(".$it", ignoreCase = true) } }
        val media = SampleMedia(materialise(song ?: CLIP).absolutePath, songMissing = song == null)
        setContent {
            if (player == null) {
                BasicText(
                    "KitePlayer cannot run here: ${KitePlayerPlatform.availability}",
                    style = TextStyle(color = Color.White),
                )
            } else {
                SampleScreen(player, media) {
                    SampleButton("Other samples") {
                        startActivity(Intent(this@VisualizerActivity, SampleLauncherActivity::class.java))
                    }
                }
            }
        }
    }

    override fun onStop() {
        // The other samples play their own clip; this one waits for Play when it comes back.
        player?.pause()
        super.onStop()
    }

    override fun onDestroy() {
        try {
            super.onDestroy()
        } finally {
            player?.close()
        }
    }

    /** Copies an asset out once, because the player opens a path. */
    private fun materialise(asset: String): File {
        val out = File(filesDir, asset)
        if (!out.isFile || out.length() == 0L) {
            assets.open(asset).use { input -> out.outputStream().use { input.copyTo(it) } }
        }
        return out
    }

    private companion object {
        const val CLIP = "sync1080p30.mp4"
        val SONG_TYPES = listOf("mp3", "m4a", "flac", "ogg", "wav", "aac")
    }
}
