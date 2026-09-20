package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.VizCatalog
import io.github.yuroyami.kiteplayer.audioviz.viz.retentionOf
import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A trail fades by time, not by frames.
 *
 * A drawing declares the share of the picture that survives one sixtieth of a second, which is how
 * these were authored. The surface turns that into a half life, so the same drawing looks the same
 * on a 30 Hz screen, a 60 Hz one and a 120 Hz one. Before this, a 120 Hz screen held every trail
 * twice as long.
 */
class TrailHalfLifeTest {

    @Test
    fun theSameSecondFadesTheSameAtAnyFrameRate() {
        for (declared in listOf(0.58f, 0.8f, 0.9f, 0.95f, 0.98f)) {
            val slow = left(declared, 1f / 30f, 30)
            val normal = left(declared, 1f / 60f, 60)
            val fast = left(declared, 1f / 120f, 120)
            assertTrue(
                abs(slow - normal) < 0.01f && abs(fast - normal) < 0.01f,
                "a trail of $declared leaves $slow at 30, $normal at 60 and $fast at 120 frames a second",
            )
        }
    }

    @Test
    fun sixtyFramesASecondIsUnchanged() {
        for (declared in listOf(0.58f, 0.8f, 0.9f, 0.95f, 0.98f)) {
            val once = retentionOf(declared, 1f / 60f)
            assertTrue(abs(once - declared) < 1e-4f, "60 frames a second changed $declared into $once")
        }
    }

    @Test
    fun everyDrawingsHalfLifeIsAReasonableTime() {
        println("trail half lives, calm and lively, in milliseconds")
        val problems = ArrayList<String>()
        for (drawing in VizCatalog.create()) {
            val calm = drawing.trailHalfLifeAt(0f)
            val lively = drawing.trailHalfLifeAt(1f)
            if (calm <= 0f && lively <= 0f) continue
            println("  ${drawing.name.padEnd(20)} ${(calm * 1000).toInt()} ${(lively * 1000).toInt()}")
            // The standard's ordinary range is 50 to 250 ms, and it says so as judgement rather
            // than as a limit. Plenty of drawings here sit below it: a 20 ms half life is a smear
            // on a moving edge rather than a trail, which is what those drawings want. What is
            // caught here is a trail that cannot survive one frame, and one that holds the picture
            // for seconds without being built to.
            for (value in listOf(calm, lively)) {
                if (value > 0f && value < 0.008f) problems += "${drawing.name}: ${value * 1000} ms is gone within a frame"
                if (value > 2f) problems += "${drawing.name}: ${value * 1000} ms holds the picture for seconds"
            }
        }
        assertTrue(problems.isEmpty(), "trails outside the range a viewer reads as a trail:\n" + problems.joinToString("\n"))
    }

    /** What is left of one frame after a second, fading once per frame at [delta]. */
    private fun left(declared: Float, delta: Float, frames: Int): Float =
        retentionOf(declared, delta).toDouble().pow(frames.toDouble()).toFloat()
}
