package io.github.yuroyami.kiteplayer.session

import io.github.yuroyami.kiteplayer.LoopMode
import io.github.yuroyami.kiteplayer.PlaybackStatus
import io.github.yuroyami.kiteplayer.PlayerSnapshot
import io.github.yuroyami.kiteplayer.Progress
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * What the platform's media session should show and accept, as one value.
 *
 * The lock screen, the headset buttons, the car and the notification all read the same few facts.
 * Working them out from a player snapshot is the part every application was writing again, and
 * getting slightly wrong. This is that mapping's answer, and it holds no platform types, so it is
 * testable on any target.
 */
public data class MediaSessionState(
    val phase: MediaSessionPhase,
    val position: Duration,
    /** Null for a live stream, where a scrub bar has nothing to scrub along. */
    val duration: Duration?,
    val speed: Double,
    val canSeek: Boolean,
    /** False for a song. iOS names the media type on its card from this. */
    val hasVideo: Boolean,
    val title: String?,
    val artist: String?,
    val album: String?,
    val hasNext: Boolean,
    val hasPrevious: Boolean,
) {
    /** True only while sound is moving. */
    public val playing: Boolean get() = phase == MediaSessionPhase.Playing
}

/**
 * The four things a lock screen can show. Buffering is kept apart from paused, because a stalled
 * stream that looks paused invites a press of play that does nothing.
 */
public enum class MediaSessionPhase { Playing, Paused, Buffering, Stopped }

/**
 * Reads a snapshot and a progress sample into the state a platform session wants.
 *
 * [PlayerSnapshot.metadata] carries the container's own tags, and the file name stands in when it
 * has no title. Next and previous follow the play order, so they answer for what the listener is
 * actually hearing rather than for the list order, and looping the whole queue makes both true.
 * Opening counts as buffering, and idle, ended and failed all count as stopped.
 */
public fun PlayerSnapshot.toMediaSessionState(progress: Progress): MediaSessionState =
    MediaSessionState(
        phase = when (status) {
            PlaybackStatus.Playing -> MediaSessionPhase.Playing
            PlaybackStatus.Paused -> MediaSessionPhase.Paused
            PlaybackStatus.Opening, PlaybackStatus.Buffering -> MediaSessionPhase.Buffering
            PlaybackStatus.Idle, PlaybackStatus.Ended, PlaybackStatus.Failed -> MediaSessionPhase.Stopped
        },
        position = progress.position,
        duration = duration,
        speed = speed,
        canSeek = seekable,
        hasVideo = videoSize != null,
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
    val hasVideo: Boolean,
)

internal fun MediaSessionState.metadata(): MediaSessionMetadata =
    MediaSessionMetadata(title, artist, album, duration, hasVideo)

/** The part of the state that sets the buttons and the rate. Any change here is pushed at once. */
internal data class MediaSessionTransport(
    val phase: MediaSessionPhase,
    val speed: Double,
    val canSeek: Boolean,
    val hasNext: Boolean,
    val hasPrevious: Boolean,
    val duration: Duration?,
)

internal fun MediaSessionState.transport(): MediaSessionTransport =
    MediaSessionTransport(phase, speed, canSeek, hasNext, hasPrevious, duration)

/**
 * Whether a platform must be told about this state, [elapsed] after it was told about [previous].
 *
 * Every platform walks the position on by itself from the last position and rate it was given, so
 * steady playback is no news. A change of phase, rate, buttons or length is. So is a position that
 * no longer matches what the platform shows by now: a seek, or a stall it cannot see.
 */
internal fun MediaSessionState.needsPush(previous: MediaSessionState?, elapsed: Duration): Boolean {
    if (previous == null) return true
    if (transport() != previous.transport()) return true
    if (!playing) return position != previous.position
    val shown = previous.position + elapsed * speed
    return (position - shown).absoluteValue > driftTolerance(speed)
}

/**
 * How far the truth may sit from what the platform shows before it is corrected. A progress sample
 * can be up to one interval old, so the allowance grows with the rate.
 */
internal fun driftTolerance(speed: Double): Duration = 1.seconds * maxOf(speed, 1.0)
