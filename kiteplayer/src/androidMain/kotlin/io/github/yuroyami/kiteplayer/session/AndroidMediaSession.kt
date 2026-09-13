package io.github.yuroyami.kiteplayer.session

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import io.github.yuroyami.kiteplayer.KitePlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Mirrors [player] into Android's media session and routes the buttons back.
 *
 * That session is what the lock screen, the headset buttons, the car and the media area of the
 * quick settings panel all read. Building one is what every application was writing by hand.
 *
 * The notification is deliberately not here. Posting one needs a Service the application owns, its
 * own foreground type and its own channel, none of which a library can declare. Give
 * [platformToken] to a `Notification.MediaStyle` and the two lines below are the whole of it:
 *
 * ```kotlin
 * Notification.Builder(context, channelId)
 *     .setStyle(Notification.MediaStyle().setMediaSession(session.platformToken))
 * ```
 *
 * Close it with the player.
 */
public class KitePlayerMediaSession(
    private val player: KitePlayer,
    context: Context,
    tag: String = "KitePlayer",
) : AutoCloseable {

    private val session = MediaSession(context.applicationContext, tag)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val artwork = MutableStateFlow<Bitmap?>(null)
    private val mirror = MediaSessionMirror<Bitmap>(::pushMetadata, ::pushPlaybackState)

    /** The token a `Notification.MediaStyle` needs. */
    public val platformToken: MediaSession.Token get() = session.sessionToken

    /** Always true here. Other platforms answer false when they have no session, so one check works everywhere. */
    public val isAvailable: Boolean = true

    init {
        session.setCallback(Callback(), Handler(Looper.getMainLooper()))
        session.isActive = true
        // One collector writes both halves in order, so artwork a caller sets cannot be overwritten
        // by a write that started before it.
        scope.launch {
            combine(player.state, player.progress, artwork) { snapshot, progress, image ->
                snapshot.toMediaSessionState(progress) to image
            }.collect { (state, image) -> mirror.update(state, image) }
        }
    }

    /**
     * The picture the session shows. The application supplies it: the engine reads a file's cover
     * art but does not decode it, so there is nothing here to hand over on its own.
     */
    public fun setArtwork(image: Bitmap?) {
        artwork.value = image
    }

    private fun pushMetadata(metadata: MediaSessionMetadata, image: Bitmap?) {
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, metadata.title)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, metadata.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, metadata.artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, metadata.album)
                // Live media has no length, and -1 is how the platform spells that.
                .putLong(MediaMetadata.METADATA_KEY_DURATION, metadata.duration?.inWholeMilliseconds ?: -1L)
                .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, image)
                .build(),
        )
    }

    private fun pushPlaybackState(state: MediaSessionState) {
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actionsFor(state))
                .setState(
                    platformStateFor(state.phase),
                    state.position.inWholeMilliseconds,
                    // The platform walks the position on at this rate, so anything but playing must
                    // report zero or the lock screen's position moves while nothing plays.
                    if (state.playing) state.speed.toFloat() else 0f,
                )
                .build(),
        )
    }

    override fun close() {
        scope.cancel()
        session.isActive = false
        session.release()
    }

    private inner class Callback : MediaSession.Callback() {
        override fun onPlay() = player.play()
        override fun onPause() = player.pause()
        override fun onStop() = player.pause()

        override fun onSeekTo(positionMillis: Long) {
            scope.launch { player.seek(positionMillis.milliseconds) }
        }

        override fun onSkipToNext() {
            scope.launch { runCatching { player.next() } }
        }

        override fun onSkipToPrevious() {
            scope.launch { runCatching { player.previous() } }
        }

        override fun onFastForward() = skipBy(SKIP)
        override fun onRewind() = skipBy(-SKIP)

        private fun skipBy(by: Duration) {
            scope.launch {
                val target = (player.position() + by).coerceAtLeast(Duration.ZERO)
                runCatching { player.seek(target) }
            }
        }
    }

    private companion object {
        val SKIP = 15_000.milliseconds
    }
}

/**
 * The buttons a session should offer for a given state.
 *
 * Advertised from what the player can actually do right now, so a greyed-out next button means
 * there really is nothing next rather than the session having forgotten to say.
 */
internal fun actionsFor(state: MediaSessionState): Long {
    var actions = PlaybackState.ACTION_PLAY or
        PlaybackState.ACTION_PAUSE or
        PlaybackState.ACTION_PLAY_PAUSE or
        PlaybackState.ACTION_STOP
    if (state.canSeek) {
        actions = actions or PlaybackState.ACTION_SEEK_TO or
            PlaybackState.ACTION_FAST_FORWARD or
            PlaybackState.ACTION_REWIND
    }
    if (state.hasNext) actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT
    if (state.hasPrevious) actions = actions or PlaybackState.ACTION_SKIP_TO_PREVIOUS
    return actions
}

/** The platform's own word for each phase. Stopped rather than none, so the card stays up. */
internal fun platformStateFor(phase: MediaSessionPhase): Int = when (phase) {
    MediaSessionPhase.Playing -> PlaybackState.STATE_PLAYING
    MediaSessionPhase.Paused -> PlaybackState.STATE_PAUSED
    MediaSessionPhase.Buffering -> PlaybackState.STATE_BUFFERING
    MediaSessionPhase.Stopped -> PlaybackState.STATE_STOPPED
}
