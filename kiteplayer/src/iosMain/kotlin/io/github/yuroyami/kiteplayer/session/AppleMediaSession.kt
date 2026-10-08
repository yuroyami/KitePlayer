package io.github.yuroyami.kiteplayer.session

import io.github.yuroyami.kiteplayer.CoverArt
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.view.KitePlayerPictureInPicture
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create
import platform.MediaPlayer.MPMediaItemArtwork
import platform.UIKit.UIImage
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Mirrors [player] into the iOS now playing card and routes its buttons back.
 *
 * That card is what the lock screen, the control centre and CarPlay show, and the buttons are what
 * a headset, a steering wheel and a watch send. Both are process-wide on iOS, so one session owns
 * them at a time: build one for the player the listener is hearing. Building another takes the
 * card and the buttons over, and the first player keeps playing. A button press that arrives
 * after the player closed does nothing.
 *
 * The audio session category the card needs is already the one the player's own output sets, so
 * nothing has to change there. The application still declares the background audio capability
 * itself if it wants the card to survive locking the screen.
 *
 * Most apps get one from [KitePlayer.attachMediaSession], which also adds background handling and
 * interruption handling, and closes it all with the player.
 *
 * @param skipInterval how far the skip back and skip forward buttons move, on the lock screen, the
 *        control centre, CarPlay and a headset. Positive.
 */
public class KitePlayerMediaSession(
    private val player: KitePlayer,
    skipInterval: Duration = 15.seconds,
) : AutoCloseable {

    /** The card and the buttons, which iOS shares with macOS. */
    private val card = AppleNowPlaying(player, skipInterval, ::coverArtwork)

    /** Apple has no token to hand out; the card is process-wide. Always null. */
    public val platformToken: Any? = null

    /** Always true here. Other platforms answer false when they have no session, so one check works everywhere. */
    public val isAvailable: Boolean = true

    /**
     * The picture the card shows. Without one the card shows the item's own cover, the picture a
     * music file carries, when it has one; a picture set here wins over it, and null goes back to it.
     */
    public fun setArtwork(image: UIImage?) {
        card.setArtwork(image?.let(::imageArtwork))
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun imageArtwork(picture: UIImage): MPMediaItemArtwork =
        MPMediaItemArtwork(boundsSize = picture.size) { _ -> picture }

    /** The cover's picture, or null when UIKit cannot read its bytes. */
    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    private fun coverArtwork(cover: CoverArt): MPMediaItemArtwork? {
        val bytes = cover.bytes
        if (bytes.isEmpty()) return null
        val data = bytes.usePinned { pinned -> NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong()) }
        return UIImage.imageWithData(data)?.let(::imageArtwork)
    }

    /** The handlers that [KitePlayer.attachMediaSession] gave this session. */
    internal val parts: SessionParts = SessionParts()

    private var closed = false

    /** Closes what this session owns, newest first, then the card. Only the first call does anything. */
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
 * Creates the media session for this player, with what an app needs for the lock screen and for
 * background playback, and returns it.
 *
 * - The now playing card and its buttons follow the player.
 * - [background] says what happens when the app leaves the screen, and [pictureInPicture] keeps
 *   the picture decoding while its small window shows. Null leaves that alone.
 * - [interruptions] pauses for a call and when the headphones come out. Null turns that off.
 *
 * The session owns all of it and closes it with the player, so most apps never close it by hand.
 * Call this on the main thread.
 */
public fun KitePlayer.attachMediaSession(
    background: BackgroundPolicy? = BackgroundPolicy.ContinueAudio,
    interruptions: InterruptionPolicy? = InterruptionPolicy(),
    pictureInPicture: KitePlayerPictureInPicture? = null,
    skipInterval: Duration = 15.seconds,
): KitePlayerMediaSession {
    val session = KitePlayerMediaSession(this, skipInterval)
    try {
        interruptions?.let { session.parts.add(interruptionHandling(this, it)) }
        background?.let { session.parts.add(backgroundHandling(this, it, pictureInPicture)) }
    } catch (failure: Throwable) {
        session.close()
        throw failure
    }
    session.closeWithPlayer()
    return session
}
