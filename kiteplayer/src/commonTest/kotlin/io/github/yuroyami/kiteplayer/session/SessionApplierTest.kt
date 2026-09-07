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
    fun `ducking remembers the listener's own volume and gives it back`() {
        val target = FakeTarget(volume = 0.6f)
        val applier = applier(target)
        applier.handle(InterruptionEvent.LostTransientCanDuck)
        assertEquals(0.2f, target.volume)
        applier.handle(InterruptionEvent.Gained)
        assertEquals(0.6f, target.volume)
        assertEquals(listOf("volume 0.2", "volume 0.6"), target.calls)
    }

    @Test
    fun `a second duckable loss does not overwrite the remembered volume`() {
        val target = FakeTarget(volume = 0.8f)
        val applier = applier(target)
        applier.handle(InterruptionEvent.LostTransientCanDuck)
        applier.handle(InterruptionEvent.LostTransientCanDuck)
        applier.handle(InterruptionEvent.Gained)
        assertEquals(0.8f, target.volume)
    }

    @Test
    fun `a permanent loss while ducked restores the volume before it pauses`() {
        val target = FakeTarget(volume = 0.9f)
        val applier = applier(target)
        applier.handle(InterruptionEvent.LostTransientCanDuck)
        target.calls.clear()
        applier.handle(InterruptionEvent.Lost)
        assertEquals(listOf("volume 0.9", "pause"), target.calls)
    }

    @Test
    fun `closing gives the volume back`() {
        val target = FakeTarget(volume = 0.5f)
        val applier = applier(target)
        applier.handle(InterruptionEvent.LostTransientCanDuck)
        applier.release()
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
