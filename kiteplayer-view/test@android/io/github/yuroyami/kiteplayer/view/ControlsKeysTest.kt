package io.github.yuroyami.kiteplayer.view

import android.view.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/**
 * The key and pointer rules of the Android controls that need no view (#469). The host tests of
 * this module cannot build a view, so the views themselves are checked on a device.
 */
class ControlsKeysTest {

    @Test
    fun spaceAndTheMediaKeyPlayOrPauseWhetherTheControlsShowOrNot() {
        for (hidden in listOf(true, false)) {
            assertEquals(ControlsKeyAction.TogglePlay, controlsKeyAction(KeyEvent.KEYCODE_SPACE, hidden))
            assertEquals(ControlsKeyAction.TogglePlay, controlsKeyAction(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, hidden))
        }
    }

    @Test
    fun theFirstArrowOrEnterOnlyShowsHiddenControls() {
        val wakeKeys = listOf(
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
        )
        wakeKeys.forEach { key ->
            assertEquals(ControlsKeyAction.Wake, controlsKeyAction(key, hidden = true), "key $key")
            // With the controls up the key goes on to the button that has the focus.
            assertEquals(ControlsKeyAction.Pass, controlsKeyAction(key, hidden = false), "key $key")
        }
        // Back and a letter are never taken, so the application keeps them.
        assertEquals(ControlsKeyAction.Pass, controlsKeyAction(KeyEvent.KEYCODE_BACK, hidden = true))
        assertEquals(ControlsKeyAction.Pass, controlsKeyAction(KeyEvent.KEYCODE_A, hidden = true))
    }

    @Test
    fun leftAndRightMoveTheSeekBarTenSeconds() {
        assertEquals((-10).seconds, seekBarKeyStep(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(10.seconds, seekBarKeyStep(KeyEvent.KEYCODE_DPAD_RIGHT))
        // Up and down leave the bar, so focus can move to the buttons.
        assertNull(seekBarKeyStep(KeyEvent.KEYCODE_DPAD_UP))
        assertNull(seekBarKeyStep(KeyEvent.KEYCODE_DPAD_CENTER))
    }

    @Test
    fun aPointOnTheBarIsAFractionBetweenItsInsetEnds() {
        assertEquals(0f, barFractionAt(x = 0f, width = 220, inset = 10f))
        assertEquals(0f, barFractionAt(x = 10f, width = 220, inset = 10f))
        assertEquals(0.25f, barFractionAt(x = 60f, width = 220, inset = 10f))
        assertEquals(1f, barFractionAt(x = 500f, width = 220, inset = 10f))
        // A bar with no room between its ends answers the start and does not divide by zero.
        assertEquals(0f, barFractionAt(x = 5f, width = 20, inset = 10f))
    }
}
