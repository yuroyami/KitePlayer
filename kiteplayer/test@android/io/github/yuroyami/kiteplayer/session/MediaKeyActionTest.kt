package io.github.yuroyami.kiteplayer.session

import android.view.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals

/** Play-pause acts at once and never skips; only a headset's own button keeps the double press (#437). */
class MediaKeyActionTest {

    @Test
    fun playPauseActsTheMomentItGoesDown() {
        assertEquals(MediaKeyAction.Toggle, mediaKeyAction(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.ACTION_DOWN, 0))
    }

    @Test
    fun twoQuickPlayPausePressesToggleTwiceAndNeverSkip() {
        // Down, up, down, up: each down toggles, and no answer is a skip.
        val presses = listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP, KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)
            .map { mediaKeyAction(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, it, 0) }
        assertEquals(
            listOf(MediaKeyAction.Toggle, MediaKeyAction.Consume, MediaKeyAction.Toggle, MediaKeyAction.Consume),
            presses,
        )
    }

    @Test
    fun aHeldPlayPauseTogglesOnce() {
        assertEquals(MediaKeyAction.Consume, mediaKeyAction(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.ACTION_DOWN, 3))
    }

    @Test
    fun theHeadsetHookKeepsThePlatformsDoublePress() {
        assertEquals(MediaKeyAction.Platform, mediaKeyAction(KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.ACTION_DOWN, 0))
        assertEquals(MediaKeyAction.Platform, mediaKeyAction(KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.ACTION_UP, 0))
    }

    @Test
    fun everyOtherKeyIsThePlatforms() {
        for (key in listOf(KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS)) {
            assertEquals(MediaKeyAction.Platform, mediaKeyAction(key, KeyEvent.ACTION_DOWN, 0), "key $key")
        }
    }
}
