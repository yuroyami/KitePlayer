package io.github.yuroyami.kiteplayer.audioviz

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Known pass and fail pictures for the counter that reads flashes out of captured frames.
 *
 * The counter decides whether a drawing meets the flash policy, so it has to be right about
 * pictures whose answer is known before it is trusted about a picture whose answer is not. These
 * are frames built by hand: a full screen alternating at a known rate, the same alternation over a
 * patch too small to count, and a slow fade.
 *
 * Without these the counter is only a number. The first version of it averaged the whole frame and
 * called seventeen drawings unsafe; the second counted drift as a flash and called sixty-five
 * unsafe. Neither was measured against anything.
 */
class FlashCounterTest {

    private val width = 160
    private val height = 100
    private val step = 1f / 60f

    /**
     * Frames that alternate the whole screen between [low] and [high] [perSecond] times.
     *
     * The rate has to divide sixty evenly. A rate that does not, such as four a second, becomes
     * 4.29 a second once each half is a whole number of frames, and then a second holds five of
     * them. That is the fixture being crooked, not the counter.
     */
    private fun alternating(perSecond: Int, low: Float, high: Float, share: Float = 1f): List<FloatArray> {
        require(60 % (perSecond * 2) == 0) { "$perSecond a second does not divide sixty frames evenly" }
        val frames = ArrayList<FloatArray>()
        val halfPeriod = 60 / (perSecond * 2)
        val patch = (width * height * share).toInt()
        for (step in 0 until 180) {
            val on = (step / halfPeriod) % 2 == 0
            val frame = FloatArray(width * height) { index ->
                if (index < patch) (if (on) high else low) else low
            }
            frames += frame
        }
        return frames
    }

    /** Flashes counted in the busiest second of [frames]. */
    private fun count(frames: List<FloatArray>): Int {
        val counter = FlashCaptureTest.AreaFlashes()
        var most = 0
        for (frame in frames) {
            counter.add(frame, step)
            if (counter.recent > most) most = counter.recent
        }
        return most
    }

    @Test
    fun aWholeScreenAlternationCountsAtItsOwnRate() {
        // Four alternations a second is four flashes a second: each pair of opposing changes is one.
        assertEquals(6, count(alternating(6, 0.05f, 0.85f)), "a 6 Hz alternation")
        assertEquals(3, count(alternating(3, 0.05f, 0.85f)), "a 3 Hz alternation")
        assertEquals(2, count(alternating(2, 0.05f, 0.85f)), "a 2 Hz alternation")
        assertEquals(1, count(alternating(1, 0.05f, 0.85f)), "a 1 Hz alternation")
    }

    @Test
    fun aChangeTooSmallToSeeIsNotAFlash() {
        // Both states above 0.80: the policy does not count them however fast they alternate.
        assertEquals(0, count(alternating(3, 0.85f, 0.99f)), "a bright pair")
        // A step under 0.10 is not a flash either.
        assertEquals(0, count(alternating(3, 0.40f, 0.48f)), "a small step")
    }

    @Test
    fun aPatchTooSmallToCountIsNotAFlash() {
        // A twentieth of the screen, which is under the area the counter asks for.
        assertEquals(0, count(alternating(3, 0.05f, 0.85f, share = 0.05f)), "a small patch")
        // A fifth of the screen is over it.
        assertTrue(count(alternating(3, 0.05f, 0.85f, share = 0.2f)) >= 3, "a fifth of the screen")
    }

    @Test
    fun aSlowFadeIsNotAFlash() {
        val frames = List(240) { index ->
            val light = 0.05f + 0.8f * index / 239f
            FloatArray(width * height) { light }
        }
        assertEquals(0, count(frames), "a fade over four seconds")
    }

    @Test
    fun aStillPictureCountsNothing() {
        val frames = List(180) { FloatArray(width * height) { 0.3f } }
        assertEquals(0, count(frames), "a still picture")
    }
}
