package io.github.yuroyami.kiteplayer.session

import io.github.yuroyami.kiteplayer.KitePlayer
import platform.Foundation.NSNumber
import platform.MediaPlayer.MPMediaItemPropertyTitle
import platform.MediaPlayer.MPNowPlayingInfoCenter
import platform.MediaPlayer.MPNowPlayingInfoPropertyPlaybackRate
import platform.MediaPlayer.MPRemoteCommandCenter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The now playing card that iOS and macOS share (#503), read back from the system's own info
 * centre. The card is process-wide, so one session owns it at a time, and these cases check who
 * may write it and who may clear it.
 *
 * A test process runs no main loop, so the session's own collectors never start here. Each case
 * hands the card its states directly, which is the call those collectors make.
 */
class AppleNowPlayingTest {

    private val center = MPNowPlayingInfoCenter.defaultCenter()
    private val commands = MPRemoteCommandCenter.sharedCommandCenter()

    private fun state(
        title: String,
        phase: MediaSessionPhase = MediaSessionPhase.Playing,
        hasNext: Boolean = false,
        canSeek: Boolean = true,
    ) = MediaSessionState(
        phase = phase,
        position = 10.seconds,
        duration = 60.seconds,
        speed = 1.0,
        canSeek = canSeek,
        hasVideo = false,
        title = title,
        artist = null,
        album = null,
        hasNext = hasNext,
        hasPrevious = false,
    )

    private fun card(player: KitePlayer, played: MutableList<MediaSessionPhase> = mutableListOf(), released: () -> Unit = {}) =
        AppleNowPlaying(player, 15.seconds, coverArtwork = { null }, onPlayback = { played += it.phase }, onRelease = released)

    private val shownTitle: String? get() = center.nowPlayingInfo?.get(MPMediaItemPropertyTitle) as? String
    private val shownRate: Double? get() = (center.nowPlayingInfo?.get(MPNowPlayingInfoPropertyPlaybackRate) as? NSNumber)?.doubleValue

    @Test
    fun theCardShowsTheStateAndTheButtonsFollowIt() {
        val player = KitePlayer()
        val played = mutableListOf<MediaSessionPhase>()
        val card = card(player, played)
        try {
            card.show(state("A Holiday", hasNext = true), null)
            assertEquals("A Holiday", shownTitle)
            assertEquals(1.0, shownRate)
            assertTrue(commands.nextTrackCommand.enabled)
            assertTrue(commands.changePlaybackPositionCommand.enabled)
            assertEquals(listOf(MediaSessionPhase.Playing), played, "the platform hears each playback state")

            card.show(state("A Holiday", phase = MediaSessionPhase.Paused, canSeek = false), null)
            assertEquals(0.0, shownRate, "a paused card must not walk its position on")
            assertFalse(commands.nextTrackCommand.enabled)
            assertFalse(commands.changePlaybackPositionCommand.enabled)
            assertEquals(MediaSessionPhase.Paused, played.last())
        } finally {
            card.close()
            player.close()
        }
        assertNull(center.nowPlayingInfo, "closing the owner clears the card")
    }

    @Test
    fun aSecondSessionTakesTheCardOverAndTheFirstCanNoLongerWriteOrClearIt() {
        val firstPlayer = KitePlayer()
        val secondPlayer = KitePlayer()
        var firstReleased = false
        val first = card(firstPlayer, released = { firstReleased = true })
        first.show(state("First"), null)
        assertTrue(first.ownsCard)

        val second = card(secondPlayer)
        try {
            assertFalse(first.ownsCard, "the new session owns the card")
            assertTrue(second.ownsCard)
            second.show(state("Second"), null)

            first.show(state("First again"), null)
            assertEquals("Second", shownTitle, "a session that lost the card writes nothing")

            first.close()
            assertEquals("Second", shownTitle, "closing the old session leaves the new owner's card")
            assertFalse(firstReleased, "and it releases nothing it no longer owns")
            assertTrue(second.ownsCard)
        } finally {
            second.close()
            first.close()
            firstPlayer.close()
            secondPlayer.close()
        }
        assertNull(center.nowPlayingInfo)
    }

    @Test
    fun closingTwiceIsHarmlessAndASessionBuiltAfterwardsOwnsTheCard() {
        val player = KitePlayer()
        var releases = 0
        val first = card(player, released = { releases++ })
        first.show(state("First"), null)
        first.close()
        first.close()
        assertEquals(1, releases)

        val next = card(player)
        try {
            assertTrue(next.ownsCard)
            next.show(state("Next"), null)
            assertEquals("Next", shownTitle)
        } finally {
            next.close()
            player.close()
        }
    }
}
