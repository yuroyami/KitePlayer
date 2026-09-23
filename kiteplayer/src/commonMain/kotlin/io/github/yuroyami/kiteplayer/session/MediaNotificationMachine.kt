package io.github.yuroyami.kiteplayer.session

import io.github.yuroyami.kiteplayer.PlaybackStatus
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** Where the media notification stands, together with the service behind it. */
internal enum class MediaNotificationMode {
    /** No notification, and the service is stopped. */
    Hidden,

    /** The notification cannot be swiped away, and the service keeps the process alive. */
    Foreground,

    /** The notification stays until it is swiped away. The service has left the foreground. */
    Dismissable,
}

/** Something that happened to the player, the notification or the app. */
internal sealed interface MediaNotificationEvent {
    /** The player's status changed. */
    data class StatusChanged(val status: PlaybackStatus) : MediaNotificationEvent

    /** The wait that [MediaNotificationDecision.checkAfter] asked for is over. */
    data object TimerFired : MediaNotificationEvent

    /** The user swiped the notification away. */
    data object Dismissed : MediaNotificationEvent

    /** The user removed the app from the recent apps screen. */
    data object TaskRemoved : MediaNotificationEvent

    /** The app closed the notification's handle. */
    data object Closed : MediaNotificationEvent
}

/**
 * One answer. [pause] asks for the player to be paused. [checkAfter] is how long to wait before
 * sending [MediaNotificationEvent.TimerFired], or null when nothing is waiting.
 */
internal data class MediaNotificationDecision(
    val mode: MediaNotificationMode,
    val pause: Boolean,
    val checkAfter: Duration?,
)

/**
 * The lifecycle of the media notification and its service, with no platform type in it, so every
 * rule can be tested on any target.
 *
 * - The notification appears when playback starts. While the player plays or buffers, the service
 *   holds the app in the foreground, so Android does not stop the process.
 * - A pause or the end of the media keeps the foreground for [pausedForegroundTimeout], so play
 *   from the lock screen or a headset can still start the sound. After that the service leaves the
 *   foreground, and the notification stays until it is swiped away.
 * - Opening changes nothing, because a change of track passes through it.
 * - Idle and a failure remove the notification and stop the service.
 * - A swipe removes the notification and pauses the player.
 * - Removing the app from the recent apps screen does the same, unless the player plays, buffers
 *   or opens. Then the sound goes on.
 * - After [MediaNotificationEvent.Closed], the answer is always hidden.
 *
 * One instance per notification, fed from one thread.
 */
internal class MediaNotificationMachine(
    private val pausedForegroundTimeout: Duration,
    private val clock: TimeSource = TimeSource.Monotonic,
) {
    private var mode = MediaNotificationMode.Hidden
    private var status = PlaybackStatus.Idle
    private var leaveForegroundAt: TimeMark? = null
    private var closed = false

    fun on(event: MediaNotificationEvent): MediaNotificationDecision {
        var pause = false
        if (!closed) {
            when (event) {
                is MediaNotificationEvent.StatusChanged -> onStatus(event.status)
                MediaNotificationEvent.TimerFired -> leaveForegroundIfDue()
                MediaNotificationEvent.Dismissed -> if (mode != MediaNotificationMode.Hidden) {
                    hide()
                    pause = true
                }
                MediaNotificationEvent.TaskRemoved ->
                    if (mode != MediaNotificationMode.Hidden && !status.carriesOn()) {
                        hide()
                        pause = true
                    }
                MediaNotificationEvent.Closed -> {
                    hide()
                    closed = true
                }
            }
        }
        val remaining = leaveForegroundAt?.let { maxOf(-it.elapsedNow(), Duration.ZERO) }
        return MediaNotificationDecision(mode, pause, remaining)
    }

    private fun onStatus(next: PlaybackStatus) {
        status = next
        when (next) {
            PlaybackStatus.Playing, PlaybackStatus.Buffering -> {
                mode = MediaNotificationMode.Foreground
                leaveForegroundAt = null
            }
            PlaybackStatus.Paused, PlaybackStatus.Ended -> {
                // Only a pause from playback starts the wait. A pause while one is running keeps it,
                // so the foreground cannot be stretched by stepping between tracks.
                if (mode == MediaNotificationMode.Foreground && leaveForegroundAt == null) {
                    leaveForegroundAt = clock.markNow() + pausedForegroundTimeout
                }
                leaveForegroundIfDue()
            }
            PlaybackStatus.Opening -> Unit
            PlaybackStatus.Idle, PlaybackStatus.Failed -> hide()
        }
    }

    private fun leaveForegroundIfDue() {
        val at = leaveForegroundAt ?: return
        if (at.hasPassedNow()) {
            mode = MediaNotificationMode.Dismissable
            leaveForegroundAt = null
        }
    }

    private fun hide() {
        mode = MediaNotificationMode.Hidden
        leaveForegroundAt = null
    }

    /** Opening counts, because the next track of a playing queue opens on its way to playing. */
    private fun PlaybackStatus.carriesOn(): Boolean =
        this == PlaybackStatus.Playing || this == PlaybackStatus.Buffering || this == PlaybackStatus.Opening
}
