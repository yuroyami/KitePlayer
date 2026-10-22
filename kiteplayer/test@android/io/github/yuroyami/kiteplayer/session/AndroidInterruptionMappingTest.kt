package io.github.yuroyami.kiteplayer.session

import android.media.AudioAttributes
import android.media.AudioManager
import io.github.yuroyami.kiteplayer.AudioContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    @Test
    fun `the focus request declares the content the audio track declares`() {
        assertEquals(AudioAttributes.CONTENT_TYPE_MUSIC, focusContentType(AudioContent.Music))
        assertEquals(AudioAttributes.CONTENT_TYPE_SPEECH, focusContentType(AudioContent.Speech))
        assertEquals(AudioAttributes.CONTENT_TYPE_MOVIE, focusContentType(AudioContent.Movie))
    }

    @Test
    fun `each kind of focus asks for its own gain`() {
        assertEquals(AudioManager.AUDIOFOCUS_GAIN, focusGainFor(AudioFocusKind.Permanent))
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT, focusGainFor(AudioFocusKind.Transient))
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, focusGainFor(AudioFocusKind.TransientMayDuck))
    }

    // A delayed answer is not a refusal (#451).
    @Test
    fun `a request is granted, delayed or refused`() {
        assertEquals(FocusResult.Granted, focusResultFor(AudioManager.AUDIOFOCUS_REQUEST_GRANTED))
        assertEquals(FocusResult.Delayed, focusResultFor(AudioManager.AUDIOFOCUS_REQUEST_DELAYED))
        assertEquals(FocusResult.Failed, focusResultFor(AudioManager.AUDIOFOCUS_REQUEST_FAILED))
        assertEquals(FocusResult.Failed, focusResultFor(12345))
    }

    /** Only a refusal that can be Android 15's background rule waits for the media service (#454). */
    @Test
    fun `a refusal waits for the foreground only where it can be the background rule`() {
        val android15 = 35
        assertTrue(waitsForForeground(android15, notificationAttached = true, inForeground = false))
        assertTrue(waitsForForeground(android15 + 2, notificationAttached = true, inForeground = false))
        assertFalse(waitsForForeground(android15 - 1, notificationAttached = true, inForeground = false), "Android 14 has no such rule")
        assertFalse(waitsForForeground(android15, notificationAttached = false, inForeground = false), "no service will enter the foreground")
        assertFalse(waitsForForeground(android15, notificationAttached = true, inForeground = true), "already there, so the refusal is real")
    }
}
