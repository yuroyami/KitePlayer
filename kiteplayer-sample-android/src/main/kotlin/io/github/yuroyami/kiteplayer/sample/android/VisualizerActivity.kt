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
import io.github.yuroyami.kiteplayer.session.KitePlayerMediaSession
import io.github.yuroyami.kiteplayer.session.attachBackgroundHandling
import io.github.yuroyami.kiteplayer.session.attachInterruptionHandling
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File

/**
 * The app's front screen: the song built into the APK, drawn by the audio visualiser, or the
 * conformance clip as video when the song is missing. "Other samples" opens the launcher with the
 * presentation comparisons.
 *
 * It also mirrors the player into the media session, posts the notification the lock screen reads,
 * and attaches the interruption and background handling, so the song behaves like a music app's.
 */
internal class VisualizerActivity : ComponentActivity() {
    private var player: KitePlayer? = null

    /** The session and the two playback guards, closed before the player. */
    private val handles = mutableListOf<AutoCloseable>()
    private var notification: SampleMediaNotification? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val player = KitePlayerPlatform.createOrNull()
        this.player = player
        if (player != null) {
            val session = KitePlayerMediaSession(player, this)
            handles += session
            handles += KitePlayerPlatform.attachInterruptionHandling(player, this)
            handles += KitePlayerPlatform.attachBackgroundHandling(player, this)
            notification = SampleMediaNotification(this, session.platformToken).also { it.follow(player, scope) }
        }
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
                        // The other samples play their own clip, so this one pauses first. Leaving
                        // any other way keeps the song going, which is what the lock screen is for.
                        player.pause()
                        startActivity(Intent(this@VisualizerActivity, SampleLauncherActivity::class.java))
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        try {
            super.onDestroy()
        } finally {
            scope.cancel()
            notification?.cancel()
            handles.forEach { runCatching { it.close() } }
            handles.clear()
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
