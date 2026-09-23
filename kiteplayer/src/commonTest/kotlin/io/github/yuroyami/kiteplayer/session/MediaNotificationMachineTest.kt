package io.github.yuroyami.kiteplayer.session

import io.github.yuroyami.kiteplayer.PlaybackStatus
import io.github.yuroyami.kiteplayer.session.MediaNotificationEvent.Closed
import io.github.yuroyami.kiteplayer.session.MediaNotificationEvent.Dismissed
import io.github.yuroyami.kiteplayer.session.MediaNotificationEvent.StatusChanged
import io.github.yuroyami.kiteplayer.session.MediaNotificationEvent.TaskRemoved
import io.github.yuroyami.kiteplayer.session.MediaNotificationEvent.TimerFired
import io.github.yuroyami.kiteplayer.session.MediaNotificationMode.Dismissable
import io.github.yuroyami.kiteplayer.session.MediaNotificationMode.Foreground
import io.github.yuroyami.kiteplayer.session.MediaNotificationMode.Hidden
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TestTimeSource

/**
 * Every row of the media notification's lifecycle, with the paused timeout on a clock the test
 * moves by hand.
 */
class MediaNotificationMachineTest {

    private val clock = TestTimeSource()

    private fun machine(timeout: Duration = 10.minutes) = MediaNotificationMachine(timeout, clock)

    private fun MediaNotificationMachine.status(status: PlaybackStatus) = on(StatusChanged(status))

    private fun MediaNotificationMachine.playedThenPaused(): MediaNotificationDecision {
        status(PlaybackStatus.Playing)
        return status(PlaybackStatus.Paused)
    }

    @Test
    fun playingShowsTheNotificationInTheForeground() {
        val decision = machine().status(PlaybackStatus.Playing)
        assertEquals(Foreground, decision.mode)
        assertFalse(decision.pause)
        assertNull(decision.checkAfter)
    }

    @Test
    fun bufferingHoldsTheForegroundLikePlaying() {
        val machine = machine()
        assertEquals(Foreground, machine.status(PlaybackStatus.Buffering).mode)
        machine.status(PlaybackStatus.Playing)
        assertEquals(Foreground, machine.status(PlaybackStatus.Buffering).mode)
    }

    @Test
    fun aPauseKeepsTheForegroundUntilTheTimeoutThenLetsGo() {
        val machine = machine()
        val paused = machine.playedThenPaused()
        assertEquals(Foreground, paused.mode)
        assertEquals(10.minutes, paused.checkAfter)

        clock += 9.minutes
        val early = machine.on(TimerFired)
        assertEquals(Foreground, early.mode)
        assertEquals(1.minutes, early.checkAfter)

        clock += 1.minutes
        val due = machine.on(TimerFired)
        assertEquals(Dismissable, due.mode)
        assertNull(due.checkAfter)
        assertFalse(due.pause)
    }

    @Test
    fun theEndKeepsTheNotificationLikeAPause() {
        val machine = machine()
        machine.status(PlaybackStatus.Playing)
        val ended = machine.status(PlaybackStatus.Ended)
        assertEquals(Foreground, ended.mode)
        assertEquals(10.minutes, ended.checkAfter)

        clock += 10.minutes
        assertEquals(Dismissable, machine.on(TimerFired).mode)
    }

    @Test
    fun playingAgainBeforeTheTimeoutCancelsIt() {
        val machine = machine()
        machine.playedThenPaused()
        clock += 5.minutes
        val resumed = machine.status(PlaybackStatus.Playing)
        assertEquals(Foreground, resumed.mode)
        assertNull(resumed.checkAfter)

        clock += 10.minutes
        assertEquals(Foreground, machine.on(TimerFired).mode)
    }

    @Test
    fun aNewPauseStartsAFreshTimeout() {
        val machine = machine()
        machine.playedThenPaused()
        clock += 5.minutes
        machine.status(PlaybackStatus.Playing)
        assertEquals(10.minutes, machine.status(PlaybackStatus.Paused).checkAfter)
    }

    @Test
    fun playingAfterTheTimeoutReturnsToTheForeground() {
        val machine = machine()
        machine.playedThenPaused()
        clock += 10.minutes
        machine.on(TimerFired)
        assertEquals(Foreground, machine.status(PlaybackStatus.Playing).mode)
    }

    @Test
    fun aZeroTimeoutLeavesTheForegroundAtOnce() {
        val paused = machine(timeout = Duration.ZERO).playedThenPaused()
        assertEquals(Dismissable, paused.mode)
        assertNull(paused.checkAfter)
    }

    @Test
    fun openingChangesNothing() {
        val fresh = machine()
        assertEquals(Hidden, fresh.status(PlaybackStatus.Opening).mode)

        val playing = machine()
        playing.status(PlaybackStatus.Playing)
        assertEquals(Foreground, playing.status(PlaybackStatus.Opening).mode)

        val paused = machine()
        paused.playedThenPaused()
        clock += 3.minutes
        val opening = paused.status(PlaybackStatus.Opening)
        assertEquals(Foreground, opening.mode)
        assertEquals(7.minutes, opening.checkAfter)
        // A pause after opening keeps the timeout already running rather than starting another.
        assertEquals(7.minutes, paused.status(PlaybackStatus.Paused).checkAfter)
    }

    @Test
    fun aPlayerThatHasNotPlayedShowsNothing() {
        val machine = machine()
        machine.status(PlaybackStatus.Opening)
        val paused = machine.status(PlaybackStatus.Paused)
        assertEquals(Hidden, paused.mode)
        assertNull(paused.checkAfter)
    }

    @Test
    fun idleAndFailedRemoveTheNotification() {
        for (end in listOf(PlaybackStatus.Idle, PlaybackStatus.Failed)) {
            val fromPlaying = machine()
            fromPlaying.status(PlaybackStatus.Playing)
            val decision = fromPlaying.status(end)
            assertEquals(Hidden, decision.mode, "from playing to $end")
            assertNull(decision.checkAfter, "from playing to $end")

            val fromDismissable = machine()
            fromDismissable.playedThenPaused()
            clock += 10.minutes
            fromDismissable.on(TimerFired)
            assertEquals(Hidden, fromDismissable.status(end).mode, "from dismissable to $end")
        }
    }

    @Test
    fun aDismissalRemovesTheNotificationAndLeavesThePlayerPaused() {
        val machine = machine()
        machine.playedThenPaused()
        clock += 10.minutes
        machine.on(TimerFired)

        val dismissed = machine.on(Dismissed)
        assertEquals(Hidden, dismissed.mode)
        assertTrue(dismissed.pause)
        // The pause the dismissal asked for does not bring the notification back.
        assertEquals(Hidden, machine.status(PlaybackStatus.Paused).mode)
    }

    @Test
    fun aDismissalWhilePlayingStillPauses() {
        val machine = machine()
        machine.status(PlaybackStatus.Playing)
        val dismissed = machine.on(Dismissed)
        assertEquals(Hidden, dismissed.mode)
        assertTrue(dismissed.pause)
    }

    @Test
    fun aDismissalOfNothingChangesNothing() {
        val dismissed = machine().on(Dismissed)
        assertEquals(Hidden, dismissed.mode)
        assertFalse(dismissed.pause)
    }

    @Test
    fun playingAgainAfterADismissalShowsTheNotificationAgain() {
        val machine = machine()
        machine.status(PlaybackStatus.Playing)
        machine.on(Dismissed)
        machine.status(PlaybackStatus.Paused)
        assertEquals(Foreground, machine.status(PlaybackStatus.Playing).mode)
    }

    @Test
    fun removingTheAppWhilePausedStopsEverything() {
        val machine = machine()
        machine.playedThenPaused()
        val removed = machine.on(TaskRemoved)
        assertEquals(Hidden, removed.mode)
        assertTrue(removed.pause)
        assertNull(removed.checkAfter)
    }

    @Test
    fun removingTheAppAfterTheEndStopsEverything() {
        val machine = machine()
        machine.status(PlaybackStatus.Playing)
        machine.status(PlaybackStatus.Ended)
        assertEquals(Hidden, machine.on(TaskRemoved).mode)
    }

    @Test
    fun removingTheAppWhilePlayingChangesNothing() {
        for (active in listOf(PlaybackStatus.Playing, PlaybackStatus.Buffering, PlaybackStatus.Opening)) {
            val machine = machine()
            machine.status(PlaybackStatus.Playing)
            machine.status(active)
            val removed = machine.on(TaskRemoved)
            assertEquals(Foreground, removed.mode, "while $active")
            assertFalse(removed.pause, "while $active")
        }
    }

    @Test
    fun removingTheAppWithNothingShownChangesNothing() {
        val machine = machine()
        machine.status(PlaybackStatus.Opening)
        machine.status(PlaybackStatus.Paused)
        val removed = machine.on(TaskRemoved)
        assertEquals(Hidden, removed.mode)
        assertFalse(removed.pause)
    }

    @Test
    fun closingRemovesEverythingForGood() {
        val machine = machine()
        machine.playedThenPaused()
        val closed = machine.on(Closed)
        assertEquals(Hidden, closed.mode)
        assertNull(closed.checkAfter)
        assertFalse(closed.pause)
        assertEquals(Hidden, machine.status(PlaybackStatus.Playing).mode)
        assertFalse(machine.on(Dismissed).pause)
    }
}
