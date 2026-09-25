package io.github.yuroyami.kiteplayer.session

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * Keeps the process alive while the player plays, and holds the media notification.
 *
 * `KitePlayerPlatform.attachMediaNotification` starts and stops it. The application only declares
 * it, in its own `AndroidManifest.xml`:
 *
 * ```xml
 * <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
 * <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
 * <uses-permission android:name="android.permission.WAKE_LOCK" />
 *
 * <application>
 *     <service
 *         android:name="io.github.yuroyami.kiteplayer.session.KitePlayerMediaService"
 *         android:exported="false"
 *         android:foregroundServiceType="mediaPlayback" />
 * </application>
 * ```
 *
 * `WAKE_LOCK` is for [MediaNotificationOptions.wakeLocks]. Without it, pass `WakeLockPolicy.None`.
 *
 * The library merges none of this into the application's manifest, so an application that never
 * plays in the background carries neither the permissions nor the service. The service is not
 * exported: the lock screen, a headset and a watch reach the player through the media session,
 * not through this service.
 */
public class KitePlayerMediaService : Service() {

    override fun onCreate() {
        super.onCreate()
        MediaNotificationRegistry.onServiceCreated(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        MediaNotificationRegistry.onServiceStarted(this, intent)
        // A process that Android starts again has no player to show, so it must not restart this.
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        MediaNotificationRegistry.onTaskRemoved()
    }

    override fun onDestroy() {
        MediaNotificationRegistry.onServiceDestroyed(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    internal fun enterForeground(id: Int, notification: Notification) {
        val type = foregroundServiceTypeFor(Build.VERSION.SDK_INT)
        if (type != null) startForeground(id, notification, type) else startForeground(id, notification)
    }

    internal fun leaveForeground(removeNotification: Boolean) {
        stopForeground(if (removeNotification) STOP_FOREGROUND_REMOVE else STOP_FOREGROUND_DETACH)
    }
}

/**
 * The one attached media notification and the running service, for the whole process.
 *
 * Used only on the main thread, where the service's callbacks arrive and where the handle acts,
 * so it needs no lock. A second attachment closes the first.
 */
internal object MediaNotificationRegistry {
    private var handle: MediaNotificationHandle? = null

    var service: KitePlayerMediaService? = null
        private set

    fun attach(next: MediaNotificationHandle) {
        val previous = handle
        handle = next
        previous?.close()
    }

    fun detach(leaving: MediaNotificationHandle) {
        if (handle === leaving) handle = null
    }

    fun onServiceCreated(created: KitePlayerMediaService) {
        service = created
    }

    fun onServiceStarted(started: KitePlayerMediaService, intent: Intent?) {
        val current = handle
        var refusal: IllegalStateException? = null
        if (intent?.action == MediaNotificationIntents.ACTION_FOREGROUND) {
            // Android ends the process when a service started for the foreground never gets there,
            // even one that is about to stop. So it always gets there first.
            val notification = current?.notificationForStart() ?: bareNotification(started, intent)
            try {
                started.enterForeground(intent.notificationId(), notification)
            } catch (error: IllegalStateException) {
                if (!isForegroundRefusal(error)) throw error
                refusal = error
            }
        }
        if (current != null) {
            current.onServiceStarted(intent, refusal)
        } else {
            // Left by a handle that has closed, or by a process that has gone: nothing plays behind
            // the notification any more.
            val manager = started.getSystemService(NotificationManager::class.java)
            intent?.let { manager.cancel(it.notificationId()) }
            stop(started)
        }
    }

    fun onTaskRemoved() {
        handle?.onTaskRemoved()
    }

    fun onServiceDestroyed(destroyed: KitePlayerMediaService) {
        if (service !== destroyed) return
        service = null
        handle?.onServiceGone()
    }

    /** Takes the service out of the foreground, notification and all, and stops it. */
    fun stop(running: KitePlayerMediaService) {
        running.leaveForeground(removeNotification = true)
        running.stopSelf()
        // Forgotten now rather than in onDestroy, so a start right after this creates a new service
        // instead of talking to one that is going away.
        if (service === running) service = null
    }

    private fun Intent.notificationId(): Int = getIntExtra(MediaNotificationIntents.EXTRA_NOTIFICATION_ID, 1)

    /** Shown for a moment by a start whose handle has gone, only because Android demands one. */
    private fun bareNotification(context: Context, intent: Intent): Notification {
        val channel = intent.getStringExtra(MediaNotificationIntents.EXTRA_CHANNEL_ID).orEmpty()
        val icon = intent.getIntExtra(MediaNotificationIntents.EXTRA_SMALL_ICON, 0)
            .takeIf { it != 0 } ?: android.R.drawable.ic_media_play
        return Notification.Builder(context, channel)
            .setSmallIcon(icon)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .build()
    }
}
