package io.github.yuroyami.kiteplayer.sample.android

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.media.session.MediaSession
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.PlaybackStatus
import io.github.yuroyami.kiteplayer.session.toMediaSessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The media notification the lock screen and the quick settings panel read. This is the part the
 * library leaves to the app: a channel, the notification permission, and a MediaStyle that carries
 * the session token.
 *
 * It is posted from the Activity with no foreground service, so the system may stop the process
 * after a while in the background. A real app adds the service; a sample does not need to. Build it
 * in onCreate, because the permission request must be registered before the Activity starts.
 */
internal class SampleMediaNotification(
    private val activity: ComponentActivity,
    private val token: MediaSession.Token,
) {
    private val manager = activity.getSystemService(NotificationManager::class.java)
    private val permission = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    init {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Playback", NotificationManager.IMPORTANCE_LOW),
        )
        if (!allowed()) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** Shows the notification while there is something to control, and takes it down otherwise. */
    fun follow(player: KitePlayer, scope: CoroutineScope) {
        scope.launch {
            player.state.map { it.status }.distinctUntilChanged().collect { status ->
                when (status) {
                    PlaybackStatus.Playing, PlaybackStatus.Paused, PlaybackStatus.Buffering ->
                        post(player, ongoing = status == PlaybackStatus.Playing)
                    else -> cancel()
                }
            }
        }
    }

    fun cancel() = manager.cancel(ID)

    private fun post(player: KitePlayer, ongoing: Boolean) {
        if (!allowed()) return
        // Android 11 and later draw the title from the session. Older versions read it from here.
        val title = player.state.value.toMediaSessionState(player.progress.value).title
        manager.notify(
            ID,
            Notification.Builder(activity, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title)
                .setOngoing(ongoing)
                .setStyle(Notification.MediaStyle().setMediaSession(token))
                .build(),
        )
    }

    private fun allowed(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val CHANNEL = "playback"
        const val ID = 1
    }
}
