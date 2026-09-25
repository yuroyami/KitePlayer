package io.github.yuroyami.kiteplayer.session

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.github.yuroyami.kiteplayer.KitePlayerPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** How the media notification looks and behaves. Only [smallIcon] has no default. */
public data class MediaNotificationOptions(
    /** The application's monochrome notification icon, a drawable resource id. */
    val smallIcon: Int,
    /** The channel the library creates when it attaches, with low importance, so it makes no sound. */
    val channelId: String = "kiteplayer.playback",
    /** The channel's name in the system settings. Pass a translated one. */
    val channelName: CharSequence = "Playback",
    /** Any id but zero, which Android refuses for a foreground service. */
    val notificationId: Int = 1,
    /**
     * Opens the application from the notification and from the system media controls, and
     * becomes the session activity. Null uses the session activity already set, or else opens the
     * application the way its launcher icon does.
     */
    val contentIntent: PendingIntent? = null,
    /**
     * How long a paused or finished player keeps the application in the foreground, so a press of
     * play from the lock screen or a headset can still start the sound. media3 uses ten minutes.
     * Zero leaves the foreground at the pause.
     */
    val pausedForegroundTimeout: Duration = 10.minutes,
    /**
     * Called on the main thread when Android refuses the foreground, which it may do from
     * Android 12 when playback starts while the application is in the background. The
     * notification is still posted, but nothing then keeps the process alive.
     */
    val onForegroundRefused: ((Throwable) -> Unit)? = null,
    /**
     * What stays awake while the player plays or buffers, so a stream keeps loading after the
     * screen turns off. See [WakeLockPolicy]. A lock needs the `WAKE_LOCK` permission in the
     * manifest, which [KitePlayerMediaService] shows. media3 calls this the wake mode.
     */
    val wakeLocks: WakeLockPolicy = WakeLockPolicy.Network,
) {
    init {
        require(smallIcon != 0) { "the notification needs a small icon" }
        require(channelId.isNotBlank()) { "the notification needs a channel id" }
        require(notificationId != 0) { "Android refuses notification id 0 for a foreground service" }
        require(!pausedForegroundTimeout.isNegative()) { "the paused foreground timeout cannot be negative" }
    }
}

/**
 * What the media notification keeps awake while the player plays or buffers.
 *
 * Once the screen is off, Android may put the processor to sleep and Wi-Fi into power saving. Sound
 * that is already playing keeps the processor awake, but a stream that is waiting for data does
 * not, and after `BufferPolicy.stallTimeout` without data the session ends. The locks are held only
 * while the player plays or buffers, never while it is paused.
 */
public enum class WakeLockPolicy {
    /** Holds nothing. */
    None,

    /** Keeps the processor awake. Enough for media on the device. */
    Local,

    /** Keeps the processor awake and Wi-Fi at full power. For media streamed over the network. */
    Network,
}

/**
 * A button of the application's own, in the media notification and in the system media controls.
 * Give them to [KitePlayerMediaSession.setCustomActions]. The notification shows five buttons at most.
 */
public data class MediaNotificationAction(
    /** What a press hands back to the handler given to [KitePlayerMediaSession.setCustomActions]. */
    val id: String,
    /** What a screen reader says for the button. */
    val label: CharSequence,
    /** A drawable resource id. */
    val icon: Int,
) {
    init {
        require(id.isNotEmpty()) { "a custom action needs an id" }
        require(label.isNotEmpty()) { "a custom action needs a label" }
        require(icon != 0) { "a custom action needs an icon" }
    }
}

/**
 * Shows [session] as a media notification and keeps playback going after the application leaves
 * the screen.
 *
 * The notification appears when playback starts. It carries the title, the artist, previous, play
 * or pause and next, and the buttons given to [KitePlayerMediaSession.setCustomActions], the same
 * ones the system media controls show. While the player plays or buffers,
 * [KitePlayerMediaService] holds the application in the foreground, so Android does not stop the
 * process. After a pause or at the end of the media it stays there for
 * [MediaNotificationOptions.pausedForegroundTimeout], then leaves the foreground, and the
 * notification can be swiped away. A swipe stops the service and leaves the player paused.
 * Removing the application from the recent apps screen does the same unless the player is
 * playing. Idle and a failure remove the notification.
 *
 * The application declares the service and two permissions in its manifest;
 * [KitePlayerMediaService] shows how. Android 13 and later do not need the notification
 * permission for a media session's notification. One notification shows at a time: attaching
 * again closes the one before. Close it before the session.
 *
 * @throws IllegalStateException when the application's manifest does not declare
 *         [KitePlayerMediaService].
 */
public fun KitePlayerPlatform.attachMediaNotification(
    session: KitePlayerMediaSession,
    context: Context,
    options: MediaNotificationOptions,
): AutoCloseable {
    val application = context.applicationContext
    check(declaresMediaService(application)) {
        "the manifest does not declare io.github.yuroyami.kiteplayer.session.KitePlayerMediaService; " +
            "the class documentation has the declaration"
    }
    check(options.wakeLocks == WakeLockPolicy.None || grants(application, Manifest.permission.WAKE_LOCK)) {
        "the manifest does not declare android.permission.WAKE_LOCK, which MediaNotificationOptions.wakeLocks " +
            "needs; declare it, or pass WakeLockPolicy.None"
    }
    return MediaNotificationHandle(session, application, options)
}

private fun grants(context: Context, permission: String): Boolean =
    context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

private fun declaresMediaService(context: Context): Boolean {
    val component = ComponentName(context, KitePlayerMediaService::class.java)
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getServiceInfo(component, PackageManager.ComponentInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getServiceInfo(component, 0)
        }
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}

/**
 * One attached media notification.
 *
 * It reads the player and the session's picture, decides with [MediaNotificationMachine], and acts
 * on the main thread, where the service's callbacks arrive too, so nothing here needs a lock.
 */
internal class MediaNotificationHandle(
    private val session: KitePlayerMediaSession,
    private val context: Context,
    private val options: MediaNotificationOptions,
) : AutoCloseable {

    private val manager = context.getSystemService(NotificationManager::class.java)
    private val machine = MediaNotificationMachine(options.pausedForegroundTimeout)
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val timer = Runnable { decide(MediaNotificationEvent.TimerFired) }
    private val launchApp: PendingIntent? by lazy { MediaNotificationIntents.launchApp(context) }
    private val wakeLocks = PlaybackWakeLocks.forPolicy(context, options.wakeLocks)

    // Main thread only, from here down.
    private var mode = MediaNotificationMode.Hidden
    private var content: MediaNotificationContent? = null

    /** The running service is in the foreground because this handle put it there. */
    private var inForeground = false

    /** A start for the foreground was sent, and the service has not received it yet. */
    private var startInFlight = false

    /** Android refused the foreground, and is asked again only when playback starts again. */
    private var refused = false
    private var closed = false

    init {
        manager.createNotificationChannel(
            NotificationChannel(options.channelId, options.channelName, NotificationManager.IMPORTANCE_LOW),
        )
        options.contentIntent?.let(session::setSessionActivity)
        onMain { if (!closed) MediaNotificationRegistry.attach(this) }
        scope.launch {
            combine(
                session.player.state,
                session.artworkState,
                session.customActionsState,
            ) { snapshot, artwork, actions ->
                snapshot.toMediaNotificationContent(artwork, actions)
            }.distinctUntilChanged().collect { next -> main.post { onContent(next) } }
        }
    }

    /** What a start for the foreground shows: the newest notification, or null before anything was read. */
    fun notificationForStart(): Notification? = content?.let { build(it, ongoing = true) }

    /** The service received [intent]: the start this handle sent, or a press in the notification. */
    fun onServiceStarted(intent: Intent?, refusal: IllegalStateException?) {
        if (closed) return
        if (intent?.action == MediaNotificationIntents.ACTION_FOREGROUND) {
            startInFlight = false
            if (refusal == null) inForeground = true else onRefused(refusal)
        }
        val controls = session.controls
        when (MediaNotificationCommand.forAction(intent?.action)) {
            MediaNotificationCommand.Previous -> controls.onSkipToPrevious()
            MediaNotificationCommand.Play -> controls.onPlay()
            MediaNotificationCommand.Pause -> controls.onPause()
            MediaNotificationCommand.Next -> controls.onSkipToNext()
            MediaNotificationCommand.Custom -> {
                val id = intent?.getStringExtra(MediaNotificationIntents.EXTRA_CUSTOM_ID)
                if (id != null) controls.onCustomAction(id, null)
            }
            MediaNotificationCommand.Dismiss -> return decide(MediaNotificationEvent.Dismissed)
            null -> Unit
        }
        // A press can start the service when it was not running; this settles it either way.
        render()
    }

    fun onTaskRemoved() {
        if (!closed) decide(MediaNotificationEvent.TaskRemoved)
    }

    /** Android stopped the service, which it does to a background service after a while. */
    fun onServiceGone() {
        inForeground = false
        startInFlight = false
    }

    override fun close() {
        scope.cancel()
        onMain {
            if (closed) return@onMain
            decide(MediaNotificationEvent.Closed)
            closed = true
            wakeLocks.release()
            main.removeCallbacks(timer)
            MediaNotificationRegistry.detach(this)
        }
    }

    private fun onContent(next: MediaNotificationContent) {
        if (closed) return
        val previous = content
        content = next
        wakeLocks.onActive(next.playing)
        // Playback starting again is a new chance for a foreground that Android refused.
        if (next.playing && previous?.playing != true) refused = false
        when (previous?.status) {
            next.status -> render()
            else -> decide(MediaNotificationEvent.StatusChanged(next.status))
        }
    }

    private fun decide(event: MediaNotificationEvent) {
        // A closed handle must not touch the service, which may belong to a newer handle by now.
        if (closed) return
        val decision = machine.on(event)
        mode = decision.mode
        main.removeCallbacks(timer)
        decision.checkAfter?.let { main.postDelayed(timer, it.inWholeMillisecondsRoundedUp()) }
        // The player may already be closed when an application closes it before this handle.
        if (decision.pause) runCatching { session.player.pause() }
        render()
    }

    private fun render() {
        val content = content
        when {
            mode == MediaNotificationMode.Hidden -> hide()
            content == null -> Unit
            mode == MediaNotificationMode.Foreground -> showInForeground(content)
            else -> showDismissable(content)
        }
    }

    private fun hide() {
        val service = MediaNotificationRegistry.service
        // A service started for the foreground must get there before it may stop, or Android ends
        // the process. When that start lands it asks again, and is stopped then.
        if (service != null && !startInFlight) MediaNotificationRegistry.stop(service)
        inForeground = false
        manager.cancel(options.notificationId)
    }

    private fun showInForeground(content: MediaNotificationContent) {
        val service = MediaNotificationRegistry.service
        when {
            // The start shows the newest notification when it lands.
            startInFlight -> Unit
            // Posting under the same id replaces it and keeps it the service's.
            inForeground && service != null ->
                manager.notify(options.notificationId, build(content, ongoing = true))
            refused -> manager.notify(options.notificationId, build(content, ongoing = false))
            service != null -> askForForeground {
                service.enterForeground(options.notificationId, build(content, ongoing = true))
                inForeground = true
            }
            else -> askForForeground {
                val start = MediaNotificationIntents.foregroundStart(context, options)
                startInFlight = context.startForegroundService(start) != null
                if (!startInFlight) manager.notify(options.notificationId, build(content, ongoing = false))
            }
        }
    }

    private fun showDismissable(content: MediaNotificationContent) {
        // Posted before leaving the foreground, so the notification is never missing in between.
        manager.notify(options.notificationId, build(content, ongoing = false))
        val service = MediaNotificationRegistry.service
        if (inForeground && service != null) service.leaveForeground(removeNotification = false)
        inForeground = false
    }

    private inline fun askForForeground(start: () -> Unit) {
        try {
            start()
        } catch (error: IllegalStateException) {
            if (!isForegroundRefusal(error)) throw error
            onRefused(error)
        }
    }

    private fun onRefused(error: IllegalStateException) {
        refused = true
        startInFlight = false
        inForeground = false
        content?.let { manager.notify(options.notificationId, build(it, ongoing = false)) }
        options.onForegroundRefused?.invoke(error)
    }

    private fun build(content: MediaNotificationContent, ongoing: Boolean): Notification =
        buildMediaNotification(
            context,
            options,
            content,
            session.platformToken,
            options.contentIntent ?: session.sessionActivityIntent ?: launchApp,
            ongoing,
        )

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }
}

/** Rounded up, so the timer never fires before the machine's own clock says the wait is over. */
private fun Duration.inWholeMillisecondsRoundedUp(): Long = (inWholeMicroseconds + 999) / 1000
