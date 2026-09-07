package io.github.yuroyami.kiteplayer.session

import android.media.AudioManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Android's four focus codes, and everything else.
 *
 * The unknown code answering null is the point: reacting to a code this player does not model
 * would pause or duck for a reason nobody wrote down.
 */
class AndroidInterruptionMappingTest {

    @Test
    fun `each focus code becomes its own event`() {
        assertEquals(InterruptionEvent.Lost, interruptionEventFor(AudioManager.AUDIOFOCUS_LOSS))
        assertEquals(
            InterruptionEvent.LostTransient,
            interruptionEventFor(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT),
        )
        assertEquals(
            InterruptionEvent.LostTransientCanDuck,
            interruptionEventFor(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK),
        )
        assertEquals(InterruptionEvent.Gained, interruptionEventFor(AudioManager.AUDIOFOCUS_GAIN))
    }

    @Test
    fun `a code this player does not model is ignored`() {
        assertNull(interruptionEventFor(AudioManager.AUDIOFOCUS_NONE))
        assertNull(interruptionEventFor(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT))
        assertNull(interruptionEventFor(12345))
    }
}
