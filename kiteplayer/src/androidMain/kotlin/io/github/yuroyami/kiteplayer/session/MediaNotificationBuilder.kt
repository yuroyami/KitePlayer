package io.github.yuroyami.kiteplayer.session

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.session.MediaSession
import android.os.Build
import io.github.yuroyami.kiteplayer.PlaybackStatus
import io.github.yuroyami.kiteplayer.PlayerSnapshot
import io.github.yuroyami.kiteplayer.Progress

// The decisions live in plain functions so a host test can read them: the Android classes on a
// host JVM are stubs, and Notification.Builder does not run there. The builder at the end only
// copies these answers into the platform's classes.

/**
 * What the media notification shows. The notification is built again only when this changes, so
 * the position, which moves several times a second, is deliberately not here.
 */
internal data class MediaNotificationContent(
    val status: PlaybackStatus,
    val title: String?,
    val artist: String?,
    val hasPrevious: Boolean,
    val hasNext: Boolean,
    val artwork: Bitmap?,
) {
    /** True while the player is trying to play, so the middle button pauses. */
    val playing: Boolean get() = status.isActive
}

internal fun PlayerSnapshot.toMediaNotificationContent(artwork: Bitmap?): MediaNotificationContent {
    val state = toMediaSessionState(Progress())
    return MediaNotificationContent(
        status = status,
        title = state.title,
        artist = state.artist,
        hasPrevious = state.hasPrevious,
        hasNext = state.hasNext,
        artwork = artwork,
    )
}

private const val PREFIX = "io.github.yuroyami.kiteplayer.session"

/**
 * What the notification asks the service to do, and the request code of each pending intent.
 * Each one needs its own code: two pending intents with the same code and action are one intent,
 * and the second button would do what the first does.
 */
internal enum class MediaNotificationCommand(val action: String, val requestCode: Int) {
    Previous("$PREFIX.PREVIOUS", 1),
    Play("$PREFIX.PLAY", 2),
    Pause("$PREFIX.PAUSE", 3),
    Next("$PREFIX.NEXT", 4),

    /** The notification was swiped away. */
    Dismiss("$PREFIX.DISMISS", 5),

    /** The application's own buttons. Each adds its position among them to this code. */
    Custom("$PREFIX.CUSTOM", 100),
    ;

    companion object {
        fun forAction(action: String?): MediaNotificationCommand? =
            entries.firstOrNull { it.action == action }
    }
}

/** One button of the notification. [customId] is the application's id for one of its own. */
internal data class MediaNotificationButton(
    val command: MediaNotificationCommand,
    val label: CharSequence,
    val icon: Int,
    val requestCode: Int,
    val customId: String? = null,
)

/** The buttons in order, and the positions of the ones the collapsed notification shows. */
internal data class MediaNotificationLayout(
    val buttons: List<MediaNotificationButton>,
    val compact: List<Int>,
)

/** MediaStyle draws five buttons at most and drops the rest. */
internal const val MAX_NOTIFICATION_BUTTONS = 5

/**
 * Previous, play or pause, and next, then the application's own buttons.
 *
 * Previous and next are there only when the queue has somewhere to go, because a notification
 * button cannot be greyed out and one that does nothing is worse than none. The collapsed
 * notification shows those transport buttons, which are never more than the three it allows.
 * Buttons past the fifth are left out here rather than by the platform.
 */
internal fun mediaNotificationLayout(
    content: MediaNotificationContent,
    customActions: List<MediaNotificationAction>,
): MediaNotificationLayout {
    val transport = buildList {
        if (content.hasPrevious) add(transportButton(MediaNotificationCommand.Previous))
        val middle = if (content.playing) MediaNotificationCommand.Pause else MediaNotificationCommand.Play
        add(transportButton(middle))
        if (content.hasNext) add(transportButton(MediaNotificationCommand.Next))
    }
    val custom = customActions.mapIndexed { index, action ->
        MediaNotificationButton(
            MediaNotificationCommand.Custom,
            action.label,
            action.icon,
            MediaNotificationCommand.Custom.requestCode + index,
            action.id,
        )
    }
    return MediaNotificationLayout(
        buttons = (transport + custom).take(MAX_NOTIFICATION_BUTTONS),
        compact = transport.indices.toList(),
    )
}

// The platform's own icons. The labels are what a screen reader says for each button.
private fun transportButton(command: MediaNotificationCommand): MediaNotificationButton {
    val (label, icon) = when (command) {
        MediaNotificationCommand.Previous -> "Previous" to android.R.drawable.ic_media_previous
        MediaNotificationCommand.Play -> "Play" to android.R.drawable.ic_media_play
        MediaNotificationCommand.Pause -> "Pause" to android.R.drawable.ic_media_pause
        MediaNotificationCommand.Next -> "Next" to android.R.drawable.ic_media_next
        MediaNotificationCommand.Dismiss, MediaNotificationCommand.Custom ->
            error("$command is not a transport button")
    }
    return MediaNotificationButton(command, label, icon, command.requestCode)
}

/** The type `startForeground` takes, or null below Android 10, which has only the form without one. */
internal fun foregroundServiceTypeFor(sdk: Int): Int? =
    if (sdk >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else null

/** From Android 13 the system draws the picture from the session, so the notification carries none. */
internal fun notificationCarriesArtwork(sdk: Int): Boolean = sdk < Build.VERSION_CODES.TIRAMISU

/** From Android 12 a foreground notification may be held back for ten seconds unless it asks not to be. */
internal fun notificationAsksToShowAtOnce(sdk: Int): Boolean = sdk >= Build.VERSION_CODES.S

/** Android 12 and later refuse a foreground start from the background with an exception of its own. */
internal fun isForegroundRefusal(error: IllegalStateException): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && error is ForegroundServiceStartNotAllowedException

/** The intents the notification and the handle send to [KitePlayerMediaService]. */
internal object MediaNotificationIntents {
    /** The start that puts the service in the foreground. */
    const val ACTION_FOREGROUND: String = "$PREFIX.FOREGROUND"
    const val EXTRA_NOTIFICATION_ID: String = "$PREFIX.NOTIFICATION_ID"
    const val EXTRA_CHANNEL_ID: String = "$PREFIX.CHANNEL_ID"
    const val EXTRA_SMALL_ICON: String = "$PREFIX.SMALL_ICON"
    const val EXTRA_CUSTOM_ID: String = "$PREFIX.CUSTOM_ID"

    // Every intent names the notification, so a service that starts in a process with no handle
    // can still take it down.
    private fun toService(context: Context, action: String, options: MediaNotificationOptions): Intent =
        Intent(context, KitePlayerMediaService::class.java)
            .setAction(action)
            .putExtra(EXTRA_NOTIFICATION_ID, options.notificationId)
            .putExtra(EXTRA_CHANNEL_ID, options.channelId)
            .putExtra(EXTRA_SMALL_ICON, options.smallIcon)

    fun foregroundStart(context: Context, options: MediaNotificationOptions): Intent =
        toService(context, ACTION_FOREGROUND, options)

    fun forButton(
        context: Context,
        options: MediaNotificationOptions,
        button: MediaNotificationButton,
    ): PendingIntent {
        val intent = toService(context, button.command.action, options)
        button.customId?.let { intent.putExtra(EXTRA_CUSTOM_ID, it) }
        return PendingIntent.getService(context, button.requestCode, intent, PENDING_FLAGS)
    }

    fun dismiss(context: Context, options: MediaNotificationOptions): PendingIntent {
        val command = MediaNotificationCommand.Dismiss
        val intent = toService(context, command.action, options)
        return PendingIntent.getService(context, command.requestCode, intent, PENDING_FLAGS)
    }

    /** Opens the application the way its launcher icon does, for a notification given no content intent. */
    fun launchApp(context: Context): PendingIntent? =
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 0, it, PENDING_FLAGS)
        }

    // Immutable, so nothing that receives one can change where it goes. Updated in place, so a
    // button that moved to another position carries its new id.
    private const val PENDING_FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
}

/** Copies the answers above into a platform notification. [ongoing] is true while in the foreground. */
internal fun buildMediaNotification(
    context: Context,
    options: MediaNotificationOptions,
    content: MediaNotificationContent,
    token: MediaSession.Token,
    contentIntent: PendingIntent?,
    ongoing: Boolean,
): Notification {
    val sdk = Build.VERSION.SDK_INT
    val layout = mediaNotificationLayout(content, options.customActions)
    val builder = Notification.Builder(context, options.channelId)
        .setSmallIcon(options.smallIcon)
        .setContentTitle(content.title)
        .setContentText(content.artist)
        .setContentIntent(contentIntent)
        .setDeleteIntent(MediaNotificationIntents.dismiss(context, options))
        .setOngoing(ongoing)
        .setShowWhen(false)
        .setVisibility(Notification.VISIBILITY_PUBLIC)
        .setCategory(Notification.CATEGORY_TRANSPORT)
        .setOnlyAlertOnce(true)
        .setStyle(
            Notification.MediaStyle()
                .setMediaSession(token)
                .setShowActionsInCompactView(*layout.compact.toIntArray()),
        )
    for (button in layout.buttons) {
        builder.addAction(
            Notification.Action.Builder(
                Icon.createWithResource(context, button.icon),
                button.label,
                MediaNotificationIntents.forButton(context, options, button),
            ).build(),
        )
    }
    if (notificationCarriesArtwork(sdk)) content.artwork?.let { builder.setLargeIcon(it) }
    if (notificationAsksToShowAtOnce(sdk)) {
        builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
    }
    return builder.build()
}
