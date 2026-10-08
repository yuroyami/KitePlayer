package io.github.yuroyami.kiteplayer.session

import io.github.yuroyami.kiteplayer.CoverArt
import io.github.yuroyami.kiteplayer.KitePlayer
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.AppKit.NSImage
import platform.Foundation.NSData
import platform.Foundation.create
import platform.MediaPlayer.MPMediaItemArtwork
import platform.MediaPlayer.MPNowPlayingInfoCenter
import platform.MediaPlayer.MPNowPlayingPlaybackState
import platform.MediaPlayer.MPNowPlayingPlaybackStatePaused
import platform.MediaPlayer.MPNowPlayingPlaybackStatePlaying
import platform.MediaPlayer.MPNowPlayingPlaybackStateStopped
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Mirrors [player] into the macOS Now Playing item and routes its buttons back.
 *
 * Now Playing is what Control Centre and the menu bar show, and the buttons are what the play and
 * pause key, the next and previous keys, the Touch Bar and a tap on AirPods send. Both are
 * process-wide, so one session owns them at a time: build one for the player the listener is
 * hearing. Building another takes them over, and the first player keeps playing. A button press
 * that arrives after the player closed does nothing.
 *
 * macOS gives the keys to the application that last said it plays, so this also tells the system
 * whether the player plays, which iOS works out by itself.
 *
 * Most apps get one from [KitePlayer.attachMediaSession], which closes it with the player.
 *
 * @param skipInterval how far the skip back and skip forward buttons move. Positive.
 */
public class KitePlayerMediaSession(
    private val player: KitePlayer,
    skipInterval: Duration = 15.seconds,
) : AutoCloseable {

    private val infoCenter = MPNowPlayingInfoCenter.defaultCenter()

    /** The card and the buttons, which macOS shares with iOS. */
    private val card = AppleNowPlaying(
        player = player,
        skipInterval = skipInterval,
        coverArtwork = ::coverArtwork,
        onPlayback = { state -> infoCenter.playbackState = playbackStateFor(state.phase) },
        onRelease = { infoCenter.playbackState = MPNowPlayingPlaybackStateStopped },
    )

    /** Apple has no token to hand out; Now Playing is process-wide. Always null. */
    public val platformToken: Any? = null

    /** Always true here. Other platforms answer false when they have no session, so one check works everywhere. */
    public val isAvailable: Boolean = true

    /**
     * The picture Now Playing shows. Without one it shows the item's own cover, the picture a music
     * file carries, when it has one; a picture set here wins over it, and null goes back to it.
     */
    public fun setArtwork(image: NSImage?) {
        card.setArtwork(image?.let(::imageArtwork))
    }

    /**
     * MediaPlayer's headers only name `NSImage` without importing AppKit, so its binding wants its
     * own stand-in type for the same class. The cast is between two names of one class.
     */
    @OptIn(ExperimentalForeignApi::class)
    @Suppress("UNCHECKED_CAST", "CAST_NEVER_SUCCEEDS")
    private fun imageArtwork(picture: NSImage): MPMediaItemArtwork =
        MPMediaItemArtwork(boundsSize = picture.size) { _ -> picture as objcnames.classes.NSImage }

    /** The cover's picture, or null when AppKit cannot read its bytes. */
    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    private fun coverArtwork(cover: CoverArt): MPMediaItemArtwork? {
        val bytes = cover.bytes
        if (bytes.isEmpty()) return null
        val data = bytes.usePinned { pinned -> NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong()) }
        val image = NSImage(data = data)
        return if (image.isValid()) imageArtwork(image) else null
    }

    /** What [KitePlayer.attachMediaSession] gave this session to close with it. */
    internal val parts: SessionParts = SessionParts()

    private var closed = false

    /** Closes what this session owns, newest first, then Now Playing. Only the first call does anything. */
    override fun close() {
        if (closed) return
        closed = true
        try {
            parts.closeAll()
        } finally {
            card.close()
        }
    }

    /** Closes this session once [player] is asked to close, on the main thread like every other call here. */
    internal fun closeWithPlayer() {
        card.scope.closeWithPlayer(player, ::close)
    }
}

/**
 * What macOS is told the player does. Buffering counts as playing: the listener asked for sound,
 * so the keys must keep coming to this application while a stream stalls.
 */
internal fun playbackStateFor(phase: MediaSessionPhase): MPNowPlayingPlaybackState = when (phase) {
    MediaSessionPhase.Playing, MediaSessionPhase.Buffering -> MPNowPlayingPlaybackStatePlaying
    MediaSessionPhase.Paused -> MPNowPlayingPlaybackStatePaused
    MediaSessionPhase.Stopped -> MPNowPlayingPlaybackStateStopped
}

/**
 * Creates the media session for this player and returns it: Now Playing and its buttons follow
 * the player, and the session closes with it, so most apps never close it by hand.
 *
 * Call this on the main thread.
 */
public fun KitePlayer.attachMediaSession(skipInterval: Duration = 15.seconds): KitePlayerMediaSession {
    val session = KitePlayerMediaSession(this, skipInterval)
    session.closeWithPlayer()
    return session
}
