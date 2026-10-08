package io.github.yuroyami.kiteplayer.session

import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.view.KitePlayerPictureInPicture
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Mirrors [player] into the system's now playing card and routes its buttons back, on iOS and on
 * macOS. Code shared between the two builds and closes one through this declaration; setting a
 * picture is `setArtwork` on each platform, with that platform's image type.
 *
 * The card and the buttons are process-wide, so one session owns them at a time. Building another
 * takes them over, and the first player keeps playing.
 *
 * @param skipInterval how far the skip back and skip forward buttons move. Positive.
 */
public expect class KitePlayerMediaSession(
    player: KitePlayer,
    skipInterval: Duration = 15.seconds,
) : AutoCloseable {

    /** Apple has no token to hand out; the card is process-wide. Always null. */
    public val platformToken: Any?

    /** Always true on Apple platforms. */
    public val isAvailable: Boolean

    /** Closes what this session owns, then the card. Only the first call does anything. */
    override fun close()
}

/**
 * Creates the media session for this player and returns it. The session closes with the player.
 *
 * - [interruptions] pauses when the headphones come out, and on iOS for a call. Null turns that off.
 * - [background] and [pictureInPicture] say what happens when an iOS app leaves the screen. macOS
 *   ignores both.
 *
 * Call this on the main thread.
 */
public expect fun KitePlayer.attachMediaSession(
    background: BackgroundPolicy? = BackgroundPolicy.ContinueAudio,
    interruptions: InterruptionPolicy? = InterruptionPolicy(),
    pictureInPicture: KitePlayerPictureInPicture? = null,
    skipInterval: Duration = 15.seconds,
): KitePlayerMediaSession
