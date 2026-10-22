package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Envelope
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Spring
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A spring moves the same on every refresh rate, and at 60 Hz as it did when the drawings were tuned. */
class SpringRateTest {

    /** The springs that the drawings use: stiffness and damping. */
    private val shipped = listOf(
        160f to 0.6f, 260f to 0.42f, 220f to 0.45f, 120f to 0.5f, 14f to 0.6f, 10f to 0.85f,
        50f to 0.55f, 60f to 0.5f, 140f to 0.55f, 160f to 0.5f,
    )

    /** Springs that barely overshoot or do not overshoot at all. Their tuned step has two real eigenvalues. */
    private val heavy = listOf(260f to 0.9f, 120f to 1.2f, 14f to 2f, 400f to 1.2f)

    /** One Euler step every sixtieth of a second: the spring as it was before, kept here as the reference. */
    private fun tuned(stiffness: Float, damping: Float, kick: Float, steps: Int): List<Float> {
        var value = 0f
        var speed = kick
        val step = 1f / 60f
        return List(steps) {
            val pull = (0f - value) * stiffness
            val friction = speed * 2f * damping * sqrt(stiffness)
            speed += (pull - friction) * step
            value += speed * step
            value
        }
    }

    private fun run(stiffness: Float, damping: Float, kick: Float, hertz: Int, seconds: Float): List<Float> {
        val spring = Spring(stiffness, damping)
        spring.kick(kick)
        return List((seconds * hertz).toInt()) {
            spring.advance(1f / hertz)
            spring.value
        }
    }

    @Test
    fun atSixtyHertzASpringMovesAsItDidWhenTheDrawingsWereTuned() {
        for ((stiffness, damping) in shipped + heavy) {
            val reference = tuned(stiffness, damping, 14f, 120)
            val now = run(stiffness, damping, 14f, 60, 2f)
            val scale = reference.maxOf { abs(it) }
            for (index in reference.indices) {
                assertEquals(reference[index], now[index], scale * 1e-4f + 1e-6f, "k $stiffness, damping $damping, step $index")
            }
        }
    }

    @Test
    fun theSpringIsInTheSamePlaceAtTheSameTimeOnEveryRefreshRate() {
        for ((stiffness, damping) in shipped + heavy) {
            val reference = run(stiffness, damping, 14f, 60, 1f)
            val peak = reference.maxOf { abs(it) }
            // Every one of these rates has a step ending on each of these times.
            // 15 Hz is the rate of a browser tile, and its step is longer than 50 ms.
            for (hertz in listOf(15, 30, 45, 90, 120, 150, 240)) {
                val other = run(stiffness, damping, 14f, hertz, 1f)
                for (time in listOf(0.2f, 0.4f, 0.6f, 0.8f)) {
                    val a = reference[(time * 60).toInt() - 1]
                    val b = other[(time * hertz).toInt() - 1]
                    assertTrue(abs(a - b) <= peak * 0.01f, "k $stiffness, damping $damping at $time s: $b at $hertz Hz and $a at 60 Hz")
                }
            }
        }
    }

    @Test
    fun stepsThatAreNotEvenGiveTheSameAnswerAsEvenOnes() {
        val even = Spring(160f, 0.6f)
        val uneven = Spring(160f, 0.6f)
        even.kick(14f)
        uneven.kick(14f)
        repeat(30) { even.advance(0.02f) }
        val steps = listOf(0.005f, 0.035f, 0.02f, 0.03f, 0.01f)
        var spent = 0f
        while (spent < 0.6f - 1e-4f) {
            for (step in steps) {
                if (spent + step > 0.6f + 1e-4f) break
                uneven.advance(step)
                spent += step
            }
        }
        assertEquals(even.value, uneven.value, 2e-3f)
    }

    @Test
    fun thePeakDelayIsWhenTheSpringPeaks() {
        for ((stiffness, damping) in shipped + heavy) {
            val spring = Spring(stiffness, damping)
            spring.kick(14f)
            var peakAt = 0f
            var peak = 0f
            var time = 0f
            repeat(2000) {
                spring.advance(0.0005f)
                time += 0.0005f
                if (spring.value > peak) {
                    peak = spring.value
                    peakAt = time
                }
            }
            assertEquals(peakAt, spring.let { Spring(stiffness, damping).peakDelay }, 0.002f, "k $stiffness, damping $damping")
        }
    }

    @Test
    fun atSixtyHertzAnEnvelopeMovesAsItDidWhenTheDrawingsWereTuned() {
        val envelope = Envelope(attackPerSecond = 14f, releasePerSecond = 2.4f)
        var reference = 0f
        repeat(120) { index ->
            val target = if ((index / 30) % 2 == 0) 1f else 0f
            val rate = if (target > reference) 14f else 2.4f
            reference += (target - reference) * (rate / 60f).coerceIn(0f, 1f)
            assertEquals(reference, envelope.advance(target, 1f / 60f), 1e-5f, "step $index")
        }
    }

    @Test
    fun anEnvelopeIsInTheSamePlaceAtTheSameTimeOnEveryRefreshRate() {
        fun at(hertz: Int, time: Float): Float {
            val envelope = Envelope(attackPerSecond = 14f, releasePerSecond = 2.4f)
            var value = 0f
            repeat((time * hertz).toInt()) { value = envelope.advance(1f, 1f / hertz) }
            return value
        }
        // Every one of these rates has a step ending on each of these times.
        for (time in listOf(0.1f, 0.2f, 0.3f)) {
            val base = at(60, time)
            for (hertz in listOf(30, 120, 240)) {
                assertEquals(base, at(hertz, time), 1e-4f, "$time s at $hertz Hz")
            }
        }
    }
}
