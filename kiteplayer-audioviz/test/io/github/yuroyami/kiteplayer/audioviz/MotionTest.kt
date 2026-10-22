package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Anticipator
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Envelope
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.MusicClock
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Noise1
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Slew
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Spring
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/** The pieces that turn a number arriving into something that looks like movement. */
class MotionTest {

    private val frame = 1f / 60f

    @Test
    fun aSpringTakesTimeToArrive() {
        // This is the whole reason springs are here. Writing a size straight from a beat makes it
        // snap, because the number snaps. A spring has to travel, so the growth has a beginning,
        // a peak and a recovery, and the peak lands a little after the hit.
        val spring = Spring(stiffness = 200f, damping = 0.45f)
        spring.kick(6f)
        var peakAt = 0f
        var peak = 0f
        var time = 0f
        repeat(120) {
            spring.advance(frame)
            time += frame
            if (spring.value > peak) {
                peak = spring.value
                peakAt = time
            }
        }
        println("spring peak ${peak} at ${peakAt * 1000} ms")
        assertTrue(peak > 0.1f, "a kick should move it, peaked at $peak")
        assertTrue(peakAt > 0.02f, "the peak should arrive after the hit, not on it, arrived at $peakAt")
        assertTrue(peakAt < 0.25f, "and not a quarter of a second later, arrived at $peakAt")
        assertTrue(spring.value < peak * 0.5f, "it should come back down, sat at ${spring.value}")
    }

    @Test
    fun twoKicksAddUpRatherThanReplacingEachOther() {
        val once = Spring(stiffness = 200f, damping = 0.45f)
        val twice = Spring(stiffness = 200f, damping = 0.45f)
        once.kick(5f)
        twice.kick(5f)
        var peakOnce = 0f
        var peakTwice = 0f
        repeat(60) { step ->
            if (step == 3) twice.kick(5f)
            once.advance(frame)
            twice.advance(frame)
            peakOnce = maxOf(peakOnce, once.value)
            peakTwice = maxOf(peakTwice, twice.value)
        }
        assertTrue(peakTwice > peakOnce * 1.4f, "a second hit should build on the first, $peakTwice against $peakOnce")
    }

    @Test
    fun anEnvelopeLooksTheSameAtAnyRefreshRate() {
        fun settle(step: Float, steps: Int): Float {
            val envelope = Envelope(attackPerSecond = 14f, releasePerSecond = 2.4f)
            repeat(steps) { envelope.advance(1f, step) }
            return envelope.value
        }
        val slow = settle(1f / 30f, 30)
        val fast = settle(1f / 120f, 120)
        println("one second of attack: 30 Hz gives $slow, 120 Hz gives $fast")
        assertTrue(abs(slow - fast) < 0.05f, "the same second should look the same, $slow against $fast")
    }

    @Test
    fun aSlewCannotBeRushed() {
        val slew = Slew(maxPerSecond = 1f)
        slew.advance(10f, 0.5f)
        assertTrue(abs(slew.value - 0.5f) < 1e-4f, "half a second at one a second is half, was ${slew.value}")
    }

    @Test
    fun noiseIsSmoothAndDoesNotRepeat() {
        val noise = Noise1(seed = 5)
        var biggestStep = 0f
        var previous = noise.at(0f)
        var index = 1
        while (index < 400) {
            val now = noise.at(index * 0.05f)
            biggestStep = maxOf(biggestStep, abs(now - previous))
            previous = now
            index++
        }
        assertTrue(biggestStep < 0.4f, "it should wander rather than jump, biggest step was $biggestStep")
        assertTrue(abs(noise.at(3f) - noise.at(11f)) > 0.01f, "it should not repeat on a short loop")
    }

    @Test
    fun theMusicClockLocksToTheBeatAndKeepsGoingWithoutOne() {
        val clock = MusicClock(beatsPerCycle = 4f)
        // No tempo: it still turns, at the pace it was given.
        repeat(60) { clock.advance(frame, bpm = 0f, beatConfidence = 0f, phrasePhase = 0f, moodPaced = 0.5f) }
        println("free running phase after a second: ${clock.phase}")
        assertTrue(clock.phase > 0.3f, "with no tempo it should still turn, reached ${clock.phase}")

        // A tempo arrives, and it should settle onto the music rather than jump.
        val locked = MusicClock(beatsPerCycle = 4f)
        var phrase = 0.6f
        var worstJump = 0f
        var previous = locked.phase
        repeat(240) {
            phrase = (phrase + frame * 130f / 60f / 16f) % 1f
            locked.advance(frame, bpm = 130f, beatConfidence = 1f, phrasePhase = phrase, moodPaced = 0.2f)
            var jump = abs(locked.phase - previous)
            if (jump > 0.5f) jump = 1f - jump
            worstJump = maxOf(worstJump, jump)
            previous = locked.phase
        }
        val target = (phrase * 16f % 4f) / 4f
        var error = abs(locked.phase - target)
        if (error > 0.5f) error = 1f - error
        println("locked phase ${locked.phase} against ${target}, worst single step $worstJump")
        assertTrue(worstJump < 0.1f, "it should never jump, worst step was $worstJump")
        assertTrue(error < 0.12f, "after four seconds it should be on the music, was $error out")
    }

    @Test
    fun anticipationRisesAsTheOnsetApproaches() {
        fun stateWith(secondsAway: Float): VizRenderState {
            val frameData = SpectrumFrame.silent(8, 16)
            return VizRenderState(
                frame = frameData,
                timeSeconds = 0f,
                deltaSeconds = frame,
                palette = VizPalette.Classic,
                future = object : io.github.yuroyami.kiteplayer.audioviz.viz.VizFuture {
                    override fun at(secondsAhead: Float): SpectrumFrame = frameData
                    override val nextOnsetSeconds: Float = secondsAway
                },
            )
        }
        assertTrue(stateWith(0.5f).anticipation() == 0f, "a distant beat should not pull yet")
        val near = stateWith(0.05f).anticipation()
        val nearer = stateWith(0.01f).anticipation()
        println("anticipation at 50 ms $near, at 10 ms $nearer")
        assertTrue(nearer > near, "it should build as the beat gets closer")
        assertTrue(nearer > 0.85f, "and be nearly full right before it, was $nearer")
    }

    @Test
    fun aKickTimedByTheQueueLandsOnTheBeat() {
        val onset = 1f
        val step = 0.001f
        fun peakAgainstOnset(early: Boolean): Float {
            val spring = Spring(stiffness = 400f, damping = 0.5f)
            val ahead = Anticipator(spring)
            var time = 0f
            var kicked = false
            var highest = 0f
            var highestAt = 0f
            while (time < 1.5f) {
                if (early) {
                    if (ahead.due(onset - time)) spring.kick(5f)
                } else if (!kicked && time >= onset) {
                    spring.kick(5f)
                    kicked = true
                }
                spring.advance(step)
                if (spring.value > highest) {
                    highest = spring.value
                    highestAt = time
                }
                time += step
            }
            return highestAt - onset
        }
        val early = peakAgainstOnset(early = true)
        val late = peakAgainstOnset(early = false)
        println("peak against the onset: ${early * 1000} ms timed by the queue, ${late * 1000} ms without it")
        assertTrue(abs(early) < 0.01f, "timed by the queue the peak should land on the beat, was ${early * 1000} ms out")
        assertTrue(late in 0.04f..0.08f, "kicked on the beat the peak should land about 60 ms later, was ${late * 1000} ms")
    }
}
