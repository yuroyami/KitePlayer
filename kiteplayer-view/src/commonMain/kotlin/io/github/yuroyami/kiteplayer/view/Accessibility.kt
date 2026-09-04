package io.github.yuroyami.kiteplayer.view

import io.github.yuroyami.kiteplayer.PlaybackStatus
import kotlin.time.Duration

/** What a screen reader calls the video surface when nothing more specific is set. */
public const val DEFAULT_VIDEO_ACCESSIBILITY_LABEL: String = "Video"

/**
 * What a screen reader should say about the player's state, as one sentence.
 *
 * One pure function, in common code, because the three views must say the same thing and none of
 * them is a good place to keep the wording: a viewer who switches between a phone and a tablet
 * should not hear the video described two different ways.
 *
 * The shape is state first, then position, because a screen reader user hears the beginning of a
 * label most reliably and "Playing" is the part they asked for. Position is omitted rather than
 * guessed when the media has no duration to measure against, which is what a live stream is.
 *
 * @param status what the player is doing.
 * @param position where playback is now.
 * @param duration how long the media is, or null for a live or unmeasured source.
 */
public fun accessibilityStateText(
    status: PlaybackStatus,
    position: Duration,
    duration: Duration?,
): String {
    val state = when (status) {
        PlaybackStatus.Playing -> "Playing"
        PlaybackStatus.Paused -> "Paused"
        PlaybackStatus.Buffering -> "Buffering"
        PlaybackStatus.Ended -> "Ended"
        PlaybackStatus.Failed -> "Failed"
        PlaybackStatus.Opening -> "Opening"
        PlaybackStatus.Idle -> "No media"
    }
    // Nothing to place the position against, so it is left out rather than read as "of 0:00".
    if (duration == null || duration <= Duration.ZERO) return state
    return "$state, ${clockText(position)} of ${clockText(duration)}"
}

/**
 * A duration as a screen reader should hear it: `1:23`, or `1:02:03` once there are hours.
 *
 * Minutes are not zero padded at the front, because a reader says "one twenty three" for `1:23`
 * and "oh one twenty three" for `01:23`.
 */
internal fun clockText(value: Duration): String {
    val total = value.inWholeSeconds.coerceAtLeast(0)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    val paddedSeconds = if (seconds < 10) "0$seconds" else "$seconds"
    if (hours == 0L) return "$minutes:$paddedSeconds"
    val paddedMinutes = if (minutes < 10) "0$minutes" else "$minutes"
    return "$hours:$paddedMinutes:$paddedSeconds"
}
