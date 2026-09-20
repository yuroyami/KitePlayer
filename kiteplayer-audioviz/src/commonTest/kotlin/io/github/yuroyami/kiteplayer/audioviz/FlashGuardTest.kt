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

    /** How many flashes, as the policy counts them, are in [shown] at its busiest second. */
    private fun busiest(shown: List<Float>): Int {
        val counter = FlashGuard(mostPerSecond = 1_000)
        var most = 0
        for (value in shown) {
            counter.limit(value, 0f, step)
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
    fun aResetForgetsTheHistory() {
        val guard = FlashGuard()
        run(guard, List(120) { if ((it / 3) % 2 == 0) 0.05f else 0.85f })
        assertTrue(guard.recent > 0, "the guard counted nothing to forget")
        guard.reset()
        assertEquals(0, guard.recent, "the guard kept its history through a reset")
    }
}
