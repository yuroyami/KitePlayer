package io.github.yuroyami.kiteplayer.session

import io.github.yuroyami.kiteplayer.PlaybackStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Asking for the sound once, and giving it back once. */
class SessionFocusLifecycleTest {

    @Test
    fun `playing asks once and asks no more`() {
        val lifecycle = SessionFocusLifecycle()
        assertEquals(true, lifecycle.on(PlaybackStatus.Playing))
        lifecycle.answered(FocusResult.Granted)
        assertNull(lifecycle.on(PlaybackStatus.Playing))
        assertNull(lifecycle.on(PlaybackStatus.Buffering))
    }

    // A denial holds nothing, so the next play asks again (#282).
    @Test
    fun `a denied request is asked again at the next play`() {
        val lifecycle = SessionFocusLifecycle()
        assertEquals(true, lifecycle.on(PlaybackStatus.Playing))
        lifecycle.answered(FocusResult.Failed)
        assertNull(lifecycle.on(PlaybackStatus.Paused))
        assertEquals(true, lifecycle.on(PlaybackStatus.Playing), "a play after a denial did not ask again")
        assertFalse(lifecycle.release(), "a denial left something to give back")
    }

    @Test
    fun `a permanent loss is asked again at the next play`() {
        val lifecycle = SessionFocusLifecycle()
        lifecycle.on(PlaybackStatus.Playing)
        lifecycle.answered(FocusResult.Granted)
        lifecycle.lost()
        assertNull(lifecycle.on(PlaybackStatus.Paused))
        assertEquals(true, lifecycle.on(PlaybackStatus.Playing), "a play after a permanent loss did not ask again")
    }

    @Test
    fun `a pause keeps it so a call can hand it back`() {
        val lifecycle = SessionFocusLifecycle()
        lifecycle.on(PlaybackStatus.Playing)
        lifecycle.answered(FocusResult.Granted)
        assertNull(lifecycle.on(PlaybackStatus.Paused))
        assertNull(lifecycle.on(PlaybackStatus.Playing))
    }

    @Test
    fun `going idle gives it back once`() {
        val lifecycle = SessionFocusLifecycle()
        lifecycle.on(PlaybackStatus.Playing)
        lifecycle.answered(FocusResult.Granted)
        assertEquals(false, lifecycle.on(PlaybackStatus.Idle))
        assertNull(lifecycle.on(PlaybackStatus.Idle))
    }

    @Test
    fun `ending and failing both give it back`() {
        for (status in listOf(PlaybackStatus.Ended, PlaybackStatus.Failed)) {
            val lifecycle = SessionFocusLifecycle()
            lifecycle.on(PlaybackStatus.Playing)
            lifecycle.answered(FocusResult.Granted)
            assertEquals(false, lifecycle.on(status), "$status")
        }
    }

    @Test
    fun `nothing is given back when nothing was asked for`() {
        val lifecycle = SessionFocusLifecycle()
        assertNull(lifecycle.on(PlaybackStatus.Idle))
        assertNull(lifecycle.on(PlaybackStatus.Opening))
        assertFalse(lifecycle.release())
    }

    @Test
    fun `closing while holding gives it back`() {
        val lifecycle = SessionFocusLifecycle()
        lifecycle.on(PlaybackStatus.Playing)
        lifecycle.answered(FocusResult.Granted)
        assertTrue(lifecycle.release())
        assertFalse(lifecycle.release())
    }

    // The request is on its way to the platform when close runs, so close finds nothing held (#415).
    @Test
    fun aGrantThatComesBackAfterCloseIsGivenBack() {
        val lifecycle = SessionFocusLifecycle()
        assertEquals(true, lifecycle.on(PlaybackStatus.Playing))
        assertFalse(lifecycle.release(), "nothing was held yet when close ran")
        assertEquals(FocusAnswer.AfterClose, lifecycle.answered(FocusResult.Granted))
        assertFalse(lifecycle.release(), "the late grant was recorded as held, with no owner left to give it back")
    }

    @Test
    fun aDenialAfterCloseIsNotALossToActOn() {
        val lifecycle = SessionFocusLifecycle()
        lifecycle.on(PlaybackStatus.Playing)
        lifecycle.release()
        assertEquals(FocusAnswer.AfterClose, lifecycle.answered(FocusResult.Failed))
    }

    @Test
    fun aClosedLifecycleAsksForNothingAndGivesNothingBack() {
        val lifecycle = SessionFocusLifecycle()
        lifecycle.on(PlaybackStatus.Playing)
        lifecycle.answered(FocusResult.Granted)
        assertTrue(lifecycle.release())
        assertNull(lifecycle.on(PlaybackStatus.Playing))
        assertNull(lifecycle.on(PlaybackStatus.Idle))
    }

    @Test
    fun anAnswerBeforeCloseSaysWhetherItIsHeld() {
        val lifecycle = SessionFocusLifecycle()
        lifecycle.on(PlaybackStatus.Playing)
        assertEquals(FocusAnswer.Held, lifecycle.answered(FocusResult.Granted))
        assertEquals(false, lifecycle.on(PlaybackStatus.Idle))
        lifecycle.on(PlaybackStatus.Playing)
        assertEquals(FocusAnswer.Denied, lifecycle.answered(FocusResult.Failed))
    }

    // A muted preview in a feed leaves another app's music playing (#436).
    @Test
    fun anItemWithNoSoundAsksForNothing() {
        val lifecycle = SessionFocusLifecycle()
        assertNull(lifecycle.on(PlaybackStatus.Playing, hasSound = false))
        assertNull(lifecycle.on(PlaybackStatus.Buffering, hasSound = false))
        assertNull(lifecycle.on(PlaybackStatus.Paused, hasSound = false))
        assertFalse(lifecycle.release(), "an item with no sound held something")
    }

    @Test
    fun anItemWithNoSoundGivesBackWhatTheItemBeforeItHeld() {
        val lifecycle = SessionFocusLifecycle()
        assertEquals(true, lifecycle.on(PlaybackStatus.Playing))
        lifecycle.answered(FocusResult.Granted)
        assertNull(lifecycle.on(PlaybackStatus.Opening, hasSound = false), "an item still opening has no tracks yet")
        assertEquals(false, lifecycle.on(PlaybackStatus.Playing, hasSound = false))
        assertNull(lifecycle.on(PlaybackStatus.Playing, hasSound = false))
        assertEquals(true, lifecycle.on(PlaybackStatus.Playing), "sound coming back did not ask again")
    }

    @Test
    fun aPausedItemWithNoSoundGivesBackToo() {
        val lifecycle = SessionFocusLifecycle()
        lifecycle.on(PlaybackStatus.Playing)
        lifecycle.answered(FocusResult.Granted)
        assertEquals(false, lifecycle.on(PlaybackStatus.Paused, hasSound = false))
        assertFalse(lifecycle.release())
    }

    // During a call Android answers "later" (#451): nothing is held, and the gain that comes holds it.
    @Test
    fun `a delayed request waits and the gain that arrives holds it`() {
        val lifecycle = SessionFocusLifecycle()
        assertEquals(true, lifecycle.on(PlaybackStatus.Playing))
        assertEquals(FocusAnswer.Waiting, lifecycle.answered(FocusResult.Delayed))
        assertNull(lifecycle.on(PlaybackStatus.Paused), "the wait was given back at the pause it causes")
        lifecycle.gained()
        assertNull(lifecycle.on(PlaybackStatus.Playing), "a gain after the wait did not hold the sound")
        assertEquals(false, lifecycle.on(PlaybackStatus.Idle), "the held sound was not given back at idle")
    }

    @Test
    fun `a wait is given back at idle and at close, and a play while waiting asks again`() {
        val waitingAtIdle = SessionFocusLifecycle()
        waitingAtIdle.on(PlaybackStatus.Playing)
        waitingAtIdle.answered(FocusResult.Delayed)
        assertEquals(false, waitingAtIdle.on(PlaybackStatus.Ended), "a wait left the request with the platform")

        val waitingAtClose = SessionFocusLifecycle()
        waitingAtClose.on(PlaybackStatus.Playing)
        waitingAtClose.answered(FocusResult.Delayed)
        assertTrue(waitingAtClose.release(), "close did not give the waiting request back")

        val playedAgain = SessionFocusLifecycle()
        playedAgain.on(PlaybackStatus.Playing)
        playedAgain.answered(FocusResult.Delayed)
        assertEquals(true, playedAgain.on(PlaybackStatus.Playing), "a play while waiting did not ask again")
    }

    @Test
    fun `a gain that nobody waited for holds nothing`() {
        val lifecycle = SessionFocusLifecycle()
        lifecycle.gained()
        assertFalse(lifecycle.release())
    }
}
