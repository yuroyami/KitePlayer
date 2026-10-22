package io.github.yuroyami.kiteplayer.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Every branch of the interruption rules, without a platform anywhere near it.
 *
 * The one that matters most is the last pair: a player the listener paused must never start again
 * on its own when the sound comes back. The machine can only know that by remembering whether it
 * was the one that paused, which is why the memory exists at all.
 */
class InterruptionMachineTest {

    private fun machine(
        pauseOnLoss: Boolean = true,
        duckOnTransient: Boolean = true,
        resumeAfterTransient: Boolean = true,
        pauseWhenBecomingNoisy: Boolean = true,
    ) = InterruptionMachine(
        InterruptionPolicy(
            pauseOnLoss = pauseOnLoss,
            duckOnTransient = duckOnTransient,
            resumeAfterTransient = resumeAfterTransient,
            pauseWhenBecomingNoisy = pauseWhenBecomingNoisy,
        ),
    )

    @Test
    fun `a permanent loss pauses a playing player`() {
        val decision = machine().on(InterruptionEvent.Lost, playing = true)
        assertEquals(SessionTransport.Pause, decision.transport)
        assertFalse(decision.ducked)
    }

    @Test
    fun `a permanent loss never resumes afterwards`() {
        val machine = machine()
        machine.on(InterruptionEvent.Lost, playing = true)
        assertEquals(SessionTransport.None, machine.on(InterruptionEvent.Gained, playing = false).transport)
    }

    @Test
    fun `the pause on loss can be turned off`() {
        val decision = machine(pauseOnLoss = false).on(InterruptionEvent.Lost, playing = true)
        assertEquals(SessionTransport.None, decision.transport)
    }

    @Test
    fun `a short loss pauses and then plays again`() {
        val machine = machine()
        assertEquals(SessionTransport.Pause, machine.on(InterruptionEvent.LostTransient, playing = true).transport)
        assertEquals(SessionTransport.Resume, machine.on(InterruptionEvent.Gained, playing = false).transport)
    }

    @Test
    fun `a short loss does not play again when the policy says not to`() {
        val machine = machine(resumeAfterTransient = false)
        machine.on(InterruptionEvent.LostTransient, playing = true)
        assertEquals(SessionTransport.None, machine.on(InterruptionEvent.Gained, playing = false).transport)
    }

    @Test
    fun `a duckable loss lowers the volume instead of pausing`() {
        val decision = machine().on(InterruptionEvent.LostTransientCanDuck, playing = true)
        assertEquals(SessionTransport.None, decision.transport)
        assertTrue(decision.ducked)
    }

    @Test
    fun `the volume comes back when the sound is ours again`() {
        val machine = machine()
        assertTrue(machine.on(InterruptionEvent.LostTransientCanDuck, playing = true).ducked)
        assertFalse(machine.on(InterruptionEvent.Gained, playing = true).ducked)
    }

    /** The case a single-action design cannot express: pause AND give the volume back. */
    @Test
    fun `a permanent loss while ducked pauses and gives the volume back`() {
        val machine = machine()
        assertTrue(machine.on(InterruptionEvent.LostTransientCanDuck, playing = true).ducked)
        val decision = machine.on(InterruptionEvent.Lost, playing = true)
        assertEquals(SessionTransport.Pause, decision.transport)
        assertFalse(decision.ducked)
    }

    @Test
    fun `a duckable loss pauses when ducking is turned off`() {
        val decision = machine(duckOnTransient = false)
            .on(InterruptionEvent.LostTransientCanDuck, playing = true)
        assertEquals(SessionTransport.Pause, decision.transport)
        assertFalse(decision.ducked)
    }

    @Test
    fun `unplugging the headphones pauses and plugging them back in does not`() {
        val machine = machine()
        assertEquals(SessionTransport.Pause, machine.on(InterruptionEvent.BecameNoisy, playing = true).transport)
        assertEquals(SessionTransport.None, machine.on(InterruptionEvent.Gained, playing = false).transport)
    }

    @Test
    fun `the noisy pause can be turned off`() {
        val decision = machine(pauseWhenBecomingNoisy = false).on(InterruptionEvent.BecameNoisy, playing = true)
        assertEquals(SessionTransport.None, decision.transport)
    }

    @Test
    fun `nothing is paused when nothing was playing`() {
        for (event in listOf(InterruptionEvent.Lost, InterruptionEvent.LostTransient, InterruptionEvent.BecameNoisy)) {
            assertEquals(SessionTransport.None, machine().on(event, playing = false).transport, "$event")
        }
    }

    @Test
    fun `a gain after a listener's own pause changes nothing`() {
        val machine = machine()
        val decision = machine.on(InterruptionEvent.Gained, playing = false)
        assertEquals(SessionTransport.None, decision.transport)
        assertFalse(decision.ducked)
    }

    @Test
    fun `a paused player is not ducked`() {
        assertFalse(machine().on(InterruptionEvent.LostTransientCanDuck, playing = false).ducked)
    }
}
