package io.github.yuroyami.kiteplayer.session

import io.github.yuroyami.kiteplayer.LoopMode
import io.github.yuroyami.kiteplayer.PlaybackStatus
import io.github.yuroyami.kiteplayer.PlayerSnapshot
import io.github.yuroyami.kiteplayer.Progress
import kotlin.time.Duration

/**
 * What the platform's media session should show and accept, as one value.
 *
 * The lock screen, the headset buttons, the car and the notification all read the same few facts.
 * Working them out from a player snapshot is the part every application was writing again, and
 * getting slightly wrong. This is that mapping's answer, and it holds no platform types, so it is
 * testable on any target.
 */
public data class MediaSessionState(
    val playing: Boolean,
    val position: Duration,
    /** Null for a live stream, where a scrub bar has nothing to scrub along. */
    val duration: Duration?,
    val speed: Double,
    val canSeek: Boolean,
    val title: String?,
    val artist: String?,
    val album: String?,
    val hasNext: Boolean,
    val hasPrevious: Boolean,
)

/**
 * Reads a snapshot and a progress sample into the state a platform session wants.
 *
 * [PlayerSnapshot.metadata] carries the container's own tags, and the file name stands in when it
 * has no title. Next and previous follow the play order, so they answer for what the listener is
 * actually hearing rather than for the list order, and looping the whole queue makes both true.
 */
public fun PlayerSnapshot.toMediaSessionState(progress: Progress): MediaSessionState =
    MediaSessionState(
        playing = status == PlaybackStatus.Playing,
        position = progress.position,
        duration = duration,
        speed = speed,
        canSeek = seekable,
        title = metadata.tag("title") ?: media?.label,
        artist = metadata.tag("artist") ?: metadata.tag("album_artist"),
        album = metadata.tag("album"),
        hasNext = hasNeighbourInPlayOrder(1),
        hasPrevious = hasNeighbourInPlayOrder(-1),
    )

/** Container tags are written in whatever case the muxer felt like, so match without it. */
private fun Map<String, String>.tag(name: String): String? =
    entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.takeIf { it.isNotBlank() }

/** The same step the engine's own next and previous take, so the buttons never lie. */
private fun PlayerSnapshot.hasNeighbourInPlayOrder(delta: Int): Boolean {
    if (queueOrder.isEmpty()) return false
    val at = queueOrder.indexOf(queueIndex)
    if (at < 0) return false
    return (at + delta) in queueOrder.indices || loop == LoopMode.All
}

/**
 * The part of the state a platform's metadata carries.
 *
 * Split out so the two halves can be pushed at their own rates. Position moves five times a
 * second, and the title does not; pushing the title at the position's rate would rewrite the
 * lock screen's text several times a second for nothing.
 */
internal data class MediaSessionMetadata(
    val title: String?,
    val artist: String?,
    val album: String?,
    val duration: Duration?,
)

internal fun MediaSessionState.metadata(): MediaSessionMetadata =
    MediaSessionMetadata(title, artist, album, duration)
