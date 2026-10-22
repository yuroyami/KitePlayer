package io.github.yuroyami.kiteplayer.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The background rules, one policy at a time.
 *
 * Two of these guard against a helpful-looking bug: switching video back on for an application
 * that had turned it off itself, and playing again after a pause the listener asked for.
 */
class BackgroundMachineTest {

    @Test
    fun `continuing audio parks video on the way out and brings it back`() {
        val machine = BackgroundMachine(BackgroundPolicy.ContinueAudio)
        val away = machine.on(foreground = false, playing = true, videoEnabled = true)
        assertEquals(SessionTransport.None, away.transport)
        assertEquals(false, away.videoEnabled)
        val back = machine.on(foreground = true, playing = true, videoEnabled = false)
        assertEquals(true, back.videoEnabled)
    }

    @Test
    fun `video the application turned off itself stays off`() {
        val machine = BackgroundMachine(BackgroundPolicy.ContinueAudio)
        assertNull(machine.on(foreground = false, playing = true, videoEnabled = false).videoEnabled)
        assertNull(machine.on(foreground = true, playing = true, videoEnabled = false).videoEnabled)
    }

    @Test
    fun `pause all pauses on the way out and plays again on the way back`() {
        val machine = BackgroundMachine(BackgroundPolicy.PauseAll)
        assertEquals(
            SessionTransport.Pause,
            machine.on(foreground = false, playing = true, videoEnabled = true).transport,
        )
        assertEquals(
            SessionTransport.Resume,
            machine.on(foreground = true, playing = false, videoEnabled = true).transport,
        )
    }

    @Test
    fun `pause all does not play something the listener had paused`() {
        val machine = BackgroundMachine(BackgroundPolicy.PauseAll)
        assertEquals(
            SessionTransport.None,
            machine.on(foreground = false, playing = false, videoEnabled = true).transport,
        )
        assertEquals(
            SessionTransport.None,
            machine.on(foreground = true, playing = false, videoEnabled = true).transport,
        )
    }

    @Test
    fun `pause all leaves video alone`() {
        val machine = BackgroundMachine(BackgroundPolicy.PauseAll)
        assertNull(machine.on(foreground = false, playing = true, videoEnabled = true).videoEnabled)
    }

    @Test
    fun `ignore does nothing at all`() {
        val machine = BackgroundMachine(BackgroundPolicy.Ignore)
        val away = machine.on(foreground = false, playing = true, videoEnabled = true)
        assertEquals(SessionTransport.None, away.transport)
        assertNull(away.videoEnabled)
    }

    @Test
    fun `the background applier parks video and brings it back`() {
        val target = FakeTarget()
        val applier = BackgroundApplier(target, BackgroundPolicy.ContinueAudio)
        applier.handle(foreground = false)
        applier.handle(foreground = true)
        assertEquals(listOf("video false", "video true"), target.calls)
    }

    @Test
    fun `pause all pauses on leaving and plays on return`() {
        val target = FakeTarget()
        val applier = BackgroundApplier(target, BackgroundPolicy.PauseAll)
        applier.handle(foreground = false)
        applier.handle(foreground = true)
        assertEquals(listOf("pause", "play"), target.calls)
    }

    // The lock screen example: the policy pauses, the listener plays and pauses there (#278).
    @Test
    fun aReturnDoesNotResumeAPlayerTheListenerPausedWhileAway() {
        val target = FakeTarget()
        val applier = BackgroundApplier(target, BackgroundPolicy.PauseAll)
        applier.handle(foreground = false)
        target.userPlays()
        target.userPauses()
        target.calls.clear()
        applier.handle(foreground = true)
        assertEquals(emptyList(), target.calls, "the listener's own pause was undone on return")
    }

    @Test
    fun aShowingWindowKeepsVideoWhenTheAppLeaves() {
        val machine = BackgroundMachine(BackgroundPolicy.ContinueAudio)
        assertNull(machine.onWindow(showing = true, playing = true, videoEnabled = true).videoEnabled)
        val away = machine.on(foreground = false, playing = true, videoEnabled = true)
        assertNull(away.videoEnabled, "the small window still shows the picture")
    }

    @Test
    fun theWindowClosingWhileAwayParksVideo() {
        val machine = BackgroundMachine(BackgroundPolicy.ContinueAudio)
        machine.onWindow(showing = true, playing = true, videoEnabled = true)
        machine.on(foreground = false, playing = true, videoEnabled = true)
        val closed = machine.onWindow(showing = false, playing = true, videoEnabled = true)
        assertEquals(false, closed.videoEnabled)
        assertEquals(SessionTransport.None, closed.transport)
    }

    @Test
    fun comingBackTurnsOnOnlyWhatThePolicyParked() {
        val parked = BackgroundMachine(BackgroundPolicy.ContinueAudio)
        parked.onWindow(showing = true, playing = true, videoEnabled = true)
        parked.on(foreground = false, playing = true, videoEnabled = true)
        parked.onWindow(showing = false, playing = true, videoEnabled = true)
        assertEquals(true, parked.on(foreground = true, playing = true, videoEnabled = false).videoEnabled)

        // Video the application had turned off itself was never parked, so nothing turns it on.
        val soundOnly = BackgroundMachine(BackgroundPolicy.ContinueAudio)
        soundOnly.onWindow(showing = true, playing = true, videoEnabled = false)
        soundOnly.on(foreground = false, playing = true, videoEnabled = false)
        assertNull(soundOnly.onWindow(showing = false, playing = true, videoEnabled = false).videoEnabled)
        assertNull(soundOnly.on(foreground = true, playing = true, videoEnabled = false).videoEnabled)
    }

    @Test
    fun theWindowOpeningWhileAwayBringsParkedVideoBack() {
        // An automatic start can open the window after the application has already left.
        val machine = BackgroundMachine(BackgroundPolicy.ContinueAudio)
        assertEquals(false, machine.on(foreground = false, playing = true, videoEnabled = true).videoEnabled)
        assertEquals(true, machine.onWindow(showing = true, playing = true, videoEnabled = false).videoEnabled)
        assertNull(machine.on(foreground = true, playing = true, videoEnabled = true).videoEnabled)
    }

    @Test
    fun theWindowOpeningAndClosingOnScreenChangesNothing() {
        val machine = BackgroundMachine(BackgroundPolicy.ContinueAudio)
        assertNull(machine.onWindow(showing = true, playing = true, videoEnabled = true).videoEnabled)
        assertNull(machine.onWindow(showing = false, playing = true, videoEnabled = true).videoEnabled)
    }

    @Test
    fun pauseAllKeepsPlayingWhileTheWindowShows() {
        val machine = BackgroundMachine(BackgroundPolicy.PauseAll)
        machine.onWindow(showing = true, playing = true, videoEnabled = true)
        assertEquals(
            SessionTransport.None,
            machine.on(foreground = false, playing = true, videoEnabled = true).transport,
        )
        assertEquals(
            SessionTransport.Pause,
            machine.onWindow(showing = false, playing = true, videoEnabled = true).transport,
        )
        assertEquals(
            SessionTransport.Resume,
            machine.on(foreground = true, playing = false, videoEnabled = true).transport,
        )
    }

    @Test
    fun aSecondLeaveInARowKeepsWhatWasParked() {
        val machine = BackgroundMachine(BackgroundPolicy.ContinueAudio)
        machine.on(foreground = false, playing = true, videoEnabled = true)
        assertNull(machine.on(foreground = false, playing = true, videoEnabled = false).videoEnabled)
        assertEquals(true, machine.on(foreground = true, playing = true, videoEnabled = false).videoEnabled)
    }

    @Test
    fun theApplierFollowsTheWindow() {
        val target = FakeTarget()
        val applier = BackgroundApplier(target, BackgroundPolicy.ContinueAudio)
        applier.handleWindow(showing = true)
        applier.handle(foreground = false)
        assertEquals(emptyList<String>(), target.calls, "video stays on while the window shows it")
        applier.handleWindow(showing = false)
        applier.handle(foreground = true)
        assertEquals(listOf("video false", "video true"), target.calls)
    }

    // Attached from onResume or a composable, after the activity already started (#281).
    @Test
    fun aHandleAttachedAfterTheStartSeesEveryLaterTransition() {
        val activities = StartedActivities(startedBeforeAttach = true)
        val first = Any()
        assertEquals(false, activities.onStopped(first, changingConfiguration = false), "the first stop is the trip away")
        assertEquals(true, activities.onStarted(first), "and the next start the return")
        assertEquals(false, activities.onStopped(first, changingConfiguration = false))
        assertEquals(true, activities.onStarted(first))
    }

    @Test
    fun aHandleAttachedBeforeAnyStartSeesEveryTransition() {
        val activities = StartedActivities(startedBeforeAttach = false)
        val first = Any()
        assertNull(activities.onStarted(first), "the application is assumed on screen from the start")
        assertEquals(false, activities.onStopped(first, changingConfiguration = false))
        assertEquals(true, activities.onStarted(first))
    }

    @Test
    fun aRotationIsNotATripToTheBackground() {
        val activities = StartedActivities(startedBeforeAttach = true)
        val old = Any()
        val new = Any()
        assertNull(activities.onStopped(old, changingConfiguration = true), "a rotation stop left the screen")
        assertNull(activities.onStarted(new), "the rotated activity came back from nowhere")
        assertEquals(false, activities.onStopped(new, changingConfiguration = false))
    }

    @Test
    fun anActivityOnTopOfAnEarlierOneKeepsTheApplicationOnScreen() {
        // The earlier one started before the handle; the new one starts before the earlier stops.
        val activities = StartedActivities(startedBeforeAttach = true)
        val earlier = Any()
        val later = Any()
        assertNull(activities.onStarted(later))
        assertNull(activities.onStopped(earlier, changingConfiguration = false), "the later activity is still on screen")
        assertEquals(false, activities.onStopped(later, changingConfiguration = false))
    }
}
