package io.github.yuroyami.kiteplayer.session

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The doing half, which is where a reversible duck either works or quietly does not.
 *
 * The order inside a single event matters as much as the calls themselves, so these assert the
 * whole recorded sequence rather than the final volume.
 */
class SessionApplierTest {

    private fun applier(target: FakeTarget, policy: InterruptionPolicy = InterruptionPolicy()) =
        InterruptionApplier(target, policy)

    @Test
    fun `ducking lowers the sound by a factor and lifts it on gain`() {
        val target = FakeTarget(volume = 0.6f)
        val applier = applier(target)
        applier.handle(InterruptionEvent.LostTransientCanDuck)
        assertEquals(0.2f, target.duckLevel)
        applier.handle(InterruptionEvent.Gained)
        assertEquals(1f, target.duckLevel)
        assertEquals(listOf("duck 0.2", "duck 1.0"), target.calls)
        assertEquals(0.6f, target.volume, "a duck never writes the listener's volume")
    }

    @Test
    fun `a second duckable loss does not duck twice`() {
        val target = FakeTarget(volume = 0.8f)
        val applier = applier(target)
        applier.handle(InterruptionEvent.LostTransientCanDuck)
        applier.handle(InterruptionEvent.LostTransientCanDuck)
        applier.handle(InterruptionEvent.Gained)
        assertEquals(listOf("duck 0.2", "duck 1.0"), target.calls)
    }

    @Test
    fun `a permanent loss while ducked lifts the duck before it pauses`() {
        val target = FakeTarget(volume = 0.9f)
        val applier = applier(target)
        applier.handle(InterruptionEvent.LostTransientCanDuck)
        target.calls.clear()
        applier.handle(InterruptionEvent.Lost)
        assertEquals(listOf("duck 1.0", "pause"), target.calls)
    }

    // A duck multiplies the volume, so quiet playback gets quieter, never louder (#280).
    @Test
    fun `a duck never raises a quiet volume`() {
        val target = FakeTarget(volume = 0.05f)
        val applier = applier(target)
        applier.handle(InterruptionEvent.LostTransientCanDuck)
        assertEquals(0.05f, target.volume, "the duck wrote the volume")
        assertEquals(true, target.volume * target.duckLevel <= 0.05f, "the duck made quiet playback louder")
    }

    @Test
    fun `a volume the listener sets while ducked survives the end of the duck`() {
        val target = FakeTarget(volume = 0.5f)
        val applier = applier(target)
        applier.handle(InterruptionEvent.LostTransientCanDuck)
        target.userSetsVolume(0f)
        applier.handle(InterruptionEvent.Gained)
        assertEquals(0f, target.volume, "the end of the duck overwrote the listener's own change")
        applier.handle(InterruptionEvent.LostTransientCanDuck)
        applier.release()
        assertEquals(1f, target.duckLevel, "closing left the player ducked")
    }

    // A policy resumes only the pause it made (#278).
    @Test
    fun `a regained focus does not resume a player the listener played and paused meanwhile`() {
        val target = FakeTarget()
        val applier = applier(target)
        applier.handle(InterruptionEvent.LostTransient)
        target.userPlays()
        target.userPauses()
        target.calls.clear()
        applier.handle(InterruptionEvent.Gained)
        assertEquals(emptyList(), target.calls, "the listener's own pause was undone")
    }

    @Test
    fun `a regained focus still resumes a pause nobody touched`() {
        val target = FakeTarget()
        val applier = applier(target)
        applier.handle(InterruptionEvent.LostTransient)
        applier.handle(InterruptionEvent.Gained)
        assertEquals(listOf("pause", "play"), target.calls)
    }

    @Test
    fun `closing lifts the duck`() {
        val target = FakeTarget(volume = 0.5f)
        val applier = applier(target)
        applier.handle(InterruptionEvent.LostTransientCanDuck)
        applier.release()
        assertEquals(1f, target.duckLevel)
        assertEquals(0.5f, target.volume)
    }

    @Test
    fun `a short loss pauses and then plays again`() {
        val target = FakeTarget()
        val applier = applier(target)
        applier.handle(InterruptionEvent.LostTransient)
        applier.handle(InterruptionEvent.Gained)
        assertEquals(listOf("pause", "play"), target.calls)
    }
}
