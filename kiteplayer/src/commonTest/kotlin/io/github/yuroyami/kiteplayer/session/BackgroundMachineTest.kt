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
}
