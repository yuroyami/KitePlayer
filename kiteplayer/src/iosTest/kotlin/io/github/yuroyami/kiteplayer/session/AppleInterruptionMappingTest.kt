package io.github.yuroyami.kiteplayer.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import platform.AVFAudio.AVAudioSessionInterruptionOptionShouldResume
import platform.AVFAudio.AVAudioSessionInterruptionTypeBegan
import platform.AVFAudio.AVAudioSessionInterruptionTypeEnded

/**
 * An audio session interruption, as this player's events.
 *
 * The end of an interruption without the resume flag has to answer null. Treating it as a gain
 * would start the sound again after a call the listener took, which is the one thing iOS asks a
 * player never to do.
 */
class AppleInterruptionMappingTest {

    @Test
    fun `an interruption that began is a short loss`() {
        assertEquals(
            InterruptionEvent.LostTransient,
            interruptionEventFor(AVAudioSessionInterruptionTypeBegan, null),
        )
    }

    @Test
    fun `an interruption that ended with the resume flag is a gain`() {
        assertEquals(
            InterruptionEvent.Gained,
            interruptionEventFor(
                AVAudioSessionInterruptionTypeEnded,
                AVAudioSessionInterruptionOptionShouldResume,
            ),
        )
    }

    @Test
    fun `an interruption that ended without the resume flag starts nothing`() {
        assertNull(interruptionEventFor(AVAudioSessionInterruptionTypeEnded, 0uL))
        assertNull(interruptionEventFor(AVAudioSessionInterruptionTypeEnded, null))
    }

    @Test
    fun `a notification with no type at all is ignored`() {
        assertNull(interruptionEventFor(null, AVAudioSessionInterruptionOptionShouldResume))
    }
}
