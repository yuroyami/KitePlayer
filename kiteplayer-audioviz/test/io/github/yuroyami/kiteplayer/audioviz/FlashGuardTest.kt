package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.FlashGuard
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Known pass and fail pictures for the guard that holds the finished frame to the flash policy.
 *
 * The fixtures are light values a frame at a time, not rendered pictures. The captured-output
 * check that runs every drawing through the same analyser is separate; this one fixes what the
 * guard itself does with a signal.
 */
class FlashGuardTest {

    private val step = 1f / 60f

    /** Runs [frames] through a guard and answers what it allowed. */
    private fun run(guard: FlashGuard, frames: List<Float>, red: (Int) -> Float = { 0f }): List<Float> =
        frames.mapIndexed { index, wanted -> guard.limit(wanted, red(index), step) }

    /** What a surface shows: each light scaled by the allowance, which can only take light away. */
    private fun runAllowance(guard: FlashGuard, frames: List<Float>): List<Float> =
        frames.map { wanted -> wanted * guard.allowance(wanted, step) }

    /** How many flashes, as the policy counts them, are in [shown] at its busiest second. */
    private fun busiest(shown: List<Float>, frameStep: Float = step): Int {
        val counter = FlashGuard(mostPerSecond = 1_000)
        var most = 0
        for (value in shown) {
            counter.limit(value, 0f, frameStep)
            if (counter.recent > most) most = counter.recent
        }
        return most
    }

    @Test
    fun aSlowFadeIsNeverHeld() {
        val frames = List(120) { it / 119f }
        val shown = run(FlashGuard(), frames)
        for (index in frames.indices) {
            assertTrue(
                abs(shown[index] - frames[index]) < 1e-4f,
                "the guard changed a slow fade at frame $index: ${shown[index]} for ${frames[index]}",
            )
        }
    }

    @Test
    fun aFlashTrainIsHeldToThePolicy() {
        // Ten flashes a second, from nearly black to nearly white.
        val frames = List(180) { if ((it / 3) % 2 == 0) 0.05f else 0.85f }
        val guard = FlashGuard()
        val shown = run(guard, frames)
        assertTrue(busiest(frames) > 3, "the fixture does not flash: ${busiest(frames)}")
        assertTrue(busiest(shown) <= 3, "the guard let ${busiest(shown)} flashes through")
        assertTrue(shown.any { it > 0.5f }, "the guard held the picture black rather than holding it back")
    }

    @Test
    fun aBrightPairIsNotAFlash() {
        // Both states are above 0.80, so the policy does not count the pair, however fast it runs.
        val frames = List(180) { if ((it / 3) % 2 == 0) 0.85f else 0.99f }
        val shown = run(FlashGuard(), frames)
        for (index in frames.indices) {
            assertTrue(
                abs(shown[index] - frames[index]) < 1e-4f,
                "the guard held a pair that is not a flash at frame $index",
            )
        }
    }

    @Test
    fun redNeverFlashes() {
        val frames = List(120) { 0.4f }
        val shown = run(FlashGuard(), frames) { if ((it / 6) % 2 == 0) 0.0f else 0.5f }
        assertTrue(shown.any { it < 0.399f }, "a red flash passed the guard untouched")
    }

    @Test
    fun aSlowFadeIsNotHeldEvenWhileTheGuardIsHolding() {
        // A flash train, then a long fade down. The fade must arrive where it was going: a guard
        // that holds a fade stops the picture instead of protecting anyone.
        val train = List(120) { if ((it / 3) % 2 == 0) 0.05f else 0.85f }
        // Four seconds to cross the whole range, which is a fifth of the step a second: slower
        // than anything the policy counts as a flash.
        val fade = List(240) { 0.85f - 0.8f * it / 239f }
        val guard = FlashGuard()
        run(guard, train)
        val shown = run(guard, fade)
        assertTrue(
            abs(shown.last() - fade.last()) < 0.02f,
            "the fade ended at ${shown.last()} rather than ${fade.last()}",
        )
    }

    @Test
    fun aFlashTrainAfterASlowFadeDownIsHeldToThePolicyToo() {
        // A slow fade down turns the guard round: the first leg of the next pair is a rise, and the
        // pair then completes on a fall. Light can only be taken away, so a fall cannot be held.
        val steady = List(30) { 0.9f }
        val fade = List(240) { 0.9f - 0.4f * it / 239f }
        val train = List(180) { if ((it / 6) % 2 == 0) 0.9f else 0.3f }
        val frames = steady + fade + train
        val shown = runAllowance(FlashGuard(), frames)
        assertTrue(busiest(frames.drop(steady.size + fade.size)) > 3, "the fixture does not flash")
        assertTrue(busiest(shown) <= 3, "the guard let ${busiest(shown)} flashes through after a fade")
    }

    @Test
    fun aFlashTrainIsHeldThroughTheAllowanceThatTheSurfaceUses() {
        // The guard starts out following a rise, so both trains reach their first pair as a fall and then a rise.
        // The pair that starts with a rise is the one after a slow fade, in the test above.
        val rising = List(180) { if ((it / 3) % 2 == 0) 0.05f else 0.85f }
        val falling = List(180) { if ((it / 3) % 2 == 0) 0.85f else 0.05f }
        for ((name, frames) in listOf("rising" to rising, "falling" to falling)) {
            val shown = runAllowance(FlashGuard(), frames)
            assertTrue(busiest(frames) > 3, "the $name fixture does not flash")
            assertTrue(busiest(shown) <= 3, "a $name train let ${busiest(shown)} flashes through")
        }
    }

    @Test
    fun theLightAHeldFrameMayShowIsNeverAboveWhatItAsked() {
        val frames = List(120) { 0.9f - 0.4f * it / 119f } +
            List(180) { if ((it / 6) % 2 == 0) 0.9f else 0.3f }
        val guard = FlashGuard()
        for (wanted in frames) {
            val allowed = guard.limit(wanted, 0f, step)
            assertTrue(allowed <= wanted + 1e-5f, "the guard asked for $wanted and allowed the brighter $allowed")
        }
    }

    @Test
    fun aResetForgetsTheHistory() {
        val guard = FlashGuard()
        run(guard, List(120) { if ((it / 3) % 2 == 0) 0.05f else 0.85f })
        assertTrue(guard.recent > 0, "the guard counted nothing to forget")
        guard.reset()
        assertEquals(0, guard.recent, "the guard kept its history through a reset")
    }

    /** Runs a steady light through [guard] for [seconds] of surface time, in the longest step it takes. */
    private fun age(guard: FlashGuard, seconds: Double) {
        repeat((seconds / 0.5).toInt()) { guard.allowance(0.25f, 0.5f) }
    }

    /**
     * The guard's second is still a second after 36 hours on a 120 Hz screen (#656). On a Float
     * clock an 8.33 ms step rounded to 15.6 ms there, and a 6 Hz flicker showed 5.4 flashes a second.
     */
    @Test
    fun aDayAndAHalfOfSurfaceTimeKeepsTheLimitAt120Hz() {
        val guard = FlashGuard()
        age(guard, LONG_UPTIME_SECONDS)
        val frameStep = 1f / 120f
        // A 6 Hz step between 0.25 and 0.60 for ten seconds: ten frames each way.
        val frames = List(1_200) { if (it / 10 % 2 == 0) 0.25f else 0.60f }
        val shown = frames.map { it * guard.allowance(it, frameStep) }
        val most = busiest(shown, frameStep)
        assertTrue(most <= 3, "the busiest second showed $most flashes after $LONG_UPTIME_SECONDS s")
    }

    /** On a Float clock a 144 Hz step added nothing after 36 hours, so the clock stopped and a slow fade was held. */
    @Test
    fun aSlowFadeIsNeverHeldAfterADayAndAHalfAt144Hz() {
        val guard = FlashGuard()
        age(guard, LONG_UPTIME_SECONDS)
        val frameStep = 1f / 144f
        // Up and down between 0.2 and 0.6 every two seconds, for ten seconds.
        val frames = List(1_440) { frame ->
            val phase = (frame % 288) / 288f
            0.2f + 0.4f * (if (phase < 0.5f) phase * 2f else 2f - phase * 2f)
        }
        val held = frames.indices.filter { guard.allowance(frames[it], frameStep) < 1f }
        assertEquals(0, held.size, "a fade of one second per leg is not a flash, held from frame ${held.firstOrNull()}")
    }

    private companion object {
        /** Past 2^17 seconds, where a Float step of 8.33 ms stops adding correctly. */
        const val LONG_UPTIME_SECONDS = 131_100.0
    }
}
