@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.session

import io.github.yuroyami.kiteplayer.KitePlayer
import platform.AppKit.NSImage
import platform.Foundation.NSMakeSize
import platform.MediaPlayer.MPNowPlayingInfoCenter
import platform.MediaPlayer.MPNowPlayingPlaybackStatePaused
import platform.MediaPlayer.MPNowPlayingPlaybackStatePlaying
import platform.MediaPlayer.MPNowPlayingPlaybackStateStopped
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The parts of the media session that only macOS has (#503). */
class MacMediaSessionTest {

    /** macOS gives the media keys to the application that says it plays, so a stall must still say so. */
    @Test
    fun bufferingTellsTheSystemThePlayerPlays() {
        assertEquals(MPNowPlayingPlaybackStatePlaying, playbackStateFor(MediaSessionPhase.Playing))
        assertEquals(MPNowPlayingPlaybackStatePlaying, playbackStateFor(MediaSessionPhase.Buffering))
        assertEquals(MPNowPlayingPlaybackStatePaused, playbackStateFor(MediaSessionPhase.Paused))
        assertEquals(MPNowPlayingPlaybackStateStopped, playbackStateFor(MediaSessionPhase.Stopped))
    }

    @Test
    fun aSessionOpensTakesAnAppKitPictureAndClosesWithNothingLeftOnTheCard() {
        val player = KitePlayer()
        val session = player.attachMediaSession()
        try {
            assertTrue(session.isAvailable)
            assertNull(session.platformToken)
            session.setArtwork(NSImage(size = NSMakeSize(64.0, 64.0)))
            session.setArtwork(null)
        } finally {
            session.close()
            session.close()
            player.close()
        }
        assertNull(MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo)
        assertEquals(MPNowPlayingPlaybackStateStopped, MPNowPlayingInfoCenter.defaultCenter().playbackState)
    }
}
