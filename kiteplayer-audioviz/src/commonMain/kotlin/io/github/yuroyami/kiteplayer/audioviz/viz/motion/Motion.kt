package io.github.yuroyami.kiteplayer.audioviz.viz.motion

import io.github.yuroyami.kiteplayer.audioviz.AudioVizAuthoringApi
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * A value that chases a target and overshoots a little before settling.
 *
 * This is the difference between a drawing that answers the music and one that reads as a meter.
 * Setting a size straight from a beat makes it snap, because the number snaps. Pushing a spring
 * with the beat instead gives the growth a beginning, a peak and a recovery, which is what a
 * physical object does and what an eye expects.
 *
 * Beats should call [kick], never write [value]. A kick adds speed, so two hits close together
 * add up instead of the second one cancelling the first.
 */
@AudioVizAuthoringApi
public class Spring(
    /** How hard it pulls back to the target. Higher is snappier. */
    private val stiffness: Float = 120f,
    /** How much it fights its own speed. Near 1 barely overshoots, near 0 wobbles for ages. */
    private val damping: Float = 0.55f,
    initial: Float = 0f,
) {
    public var value: Float = initial
        private set

    public var speed: Float = 0f
        private set

    public var target: Float = initial

    /** Moves the spring on by one frame. */
    public fun advance(deltaSeconds: Float) {
        // Clamped, because a stalled window would otherwise hand it a huge step and explode it.
        val step = deltaSeconds.coerceIn(0f, 0.05f)
        val pull = (target - value) * stiffness
        val friction = speed * 2f * damping * kotlin.math.sqrt(stiffness)
        speed += (pull - friction) * step
        value += speed * step
    }

    /** Adds speed. This is how a beat pushes something rather than placing it. */
    public fun kick(amount: Float) {
        speed += amount
    }

    /**
     * How long after a kick this spring reaches its peak, in seconds.
     *
     * Kick it this long before a beat and the peak lands on the beat itself. The queued audio says
     * exactly when the next beat is, so the picture can arrive with the sound instead of just after.
     */
    public val peakDelay: Float
        get() {
            val natural = kotlin.math.sqrt(stiffness)
            val ratio = damping.coerceIn(0f, 0.99f)
            val ringing = natural * kotlin.math.sqrt(1f - ratio * ratio)
            return atan2(ringing, ratio * natural) / ringing
        }

    public fun reset(to: Float = 0f) {
        value = to
        speed = 0f
        target = to
    }
}

/**
 * Kicks a spring early enough that its peak lands on the next onset instead of just after it.
 *
 * Hand it the seconds until the next onset every frame. It says yes once, when that time drops to
 * the spring's own [Spring.peakDelay]. The queued audio knows exactly when the onset is, which is
 * what makes this possible; with nothing queued, kick on the onset as usual.
 */
@AudioVizAuthoringApi
public class Anticipator(private val spring: Spring) {
    private var armed = true

    /** True on the one frame the spring should be kicked. */
    public fun due(secondsToOnset: Float): Boolean {
        if (secondsToOnset < 0f) return false
        if (secondsToOnset > spring.peakDelay) {
            armed = true
            return false
        }
        if (!armed) return false
        armed = false
        return true
    }

    public fun reset() {
        armed = true
    }
}

/**
 * Rises fast and falls slowly, which is what the bars in every music player do.
 *
 * Both rates are in units per second rather than per frame, so it looks the same at 60 Hz and at
 * 120 Hz. That matters more than it sounds: rates per frame would make it twice as twitchy on a
 * fast display.
 */
@AudioVizAuthoringApi
public class Envelope(
    private val attackPerSecond: Float = 14f,
    private val releasePerSecond: Float = 2.4f,
    initial: Float = 0f,
) {
    public var value: Float = initial
        private set

    public fun advance(target: Float, deltaSeconds: Float): Float {
        val rate = if (target > value) attackPerSecond else releasePerSecond
        val share = (rate * deltaSeconds).coerceIn(0f, 1f)
        value += (target - value) * share
        return value
    }

    /** Lifts the value immediately, for an onset that should be felt at once. */
    public fun hit(amount: Float) {
        value = (value + amount).coerceAtMost(1f)
    }

    public fun reset(to: Float = 0f) {
        value = to
    }
}

/** Stops a value moving faster than [maxPerSecond]. Useful for anything a viewer tracks by eye. */
@AudioVizAuthoringApi
public class Slew(private val maxPerSecond: Float, initial: Float = 0f) {
    public var value: Float = initial
        private set

    public fun advance(target: Float, deltaSeconds: Float): Float {
        val limit = maxPerSecond * deltaSeconds
        value += (target - value).coerceIn(-limit, limit)
        return value
    }

    public fun reset(to: Float = 0f) {
        value = to
    }
}

/**
 * Smooth random movement over time.
 *
 * A camera that drifts on a sine wave reads as machinery, because a sine repeats and the eye
 * notices. This blends between random values at whole numbers of time, so it wanders without
 * ever repeating and without jumping.
 */
@AudioVizAuthoringApi
public class Noise1(private val seed: Int = 1) {

    /** The value at [time], between -1 and 1. */
    public fun at(time: Float): Float {
        val whole = kotlin.math.floor(time)
        val fraction = time - whole
        val index = whole.toInt()
        val from = random(index)
        val to = random(index + 1)
        // Smoothstep between the two, so there is no corner where one ends and the next starts.
        val eased = fraction * fraction * (3f - 2f * fraction)
        return from + (to - from) * eased
    }

    /** Several octaves added together, which gives both slow drift and small detail. */
    public fun layered(time: Float, octaves: Int = 3): Float {
        var total = 0f
        var amplitude = 1f
        var scale = 1f
        var weight = 0f
        repeat(octaves) {
            total += at(time * scale + it * 17f) * amplitude
            weight += amplitude
            amplitude *= 0.5f
            scale *= 2f
        }
        return if (weight <= 0f) 0f else total / weight
    }

    private fun random(step: Int): Float {
        var hash = step * 374_761_393 + seed * 668_265_263
        hash = (hash xor (hash shr 13)) * 1_274_126_177
        hash = hash xor (hash shr 16)
        return (hash and 0xFFFFFF) / 8_388_608f - 1f
    }
}

/** Shapes for anything that travels from one value to another. */
@AudioVizAuthoringApi
public object Ease {
    public fun inQuad(t: Float): Float = t * t

    public fun outQuad(t: Float): Float = 1f - (1f - t) * (1f - t)

    public fun inOut(t: Float): Float = if (t < 0.5f) 2f * t * t else 1f - 2f * (1f - t) * (1f - t)

    /** Overshoots and comes back. Good for something arriving. */
    public fun outBack(t: Float): Float {
        val shift = t - 1f
        return 1f + 2.70158f * shift * shift * shift + 1.70158f * shift * shift
    }

    /** Wobbles into place. Use sparingly: it is loud. */
    public fun outElastic(t: Float): Float {
        if (t <= 0f) return 0f
        if (t >= 1f) return 1f
        return exp(-7f * t) * sin((t * 10f - 0.75f) * (2f * PI.toFloat() / 3f)) + 1f
    }

    /** A triangle from 0 to 1 and back, for anything that should breathe. */
    public fun pingPong(t: Float): Float {
        val wrapped = t - kotlin.math.floor(t)
        return 1f - abs(wrapped * 2f - 1f)
    }

    /** A cosine hump, 0 at both ends and 1 in the middle. Softer than [pingPong]. */
    public fun hump(t: Float): Float = 0.5f - 0.5f * cos(t * 2f * PI.toFloat())
}

/**
 * A rotation that lands on the beat when there is one, and keeps turning when there is not.
 *
 * Locking straight to the tempo tracker's phase looks wrong twice: it jumps the moment a tempo is
 * found, and it freezes on music with no steady beat. So this always turns at its own speed and
 * is only pulled gently towards the music's phase, which locks within a bar and never jerks.
 *
 * [beatsPerCycle] is how much music one full turn covers. Four is a bar, sixteen is a phrase,
 * one is a single beat.
 */
@AudioVizAuthoringApi
public class MusicClock(
    private val beatsPerCycle: Float = 4f,
    /** How hard the music pulls the turn into line. Higher locks faster and wobbles more. */
    private val pullPerSecond: Float = 0.6f,
) {
    /** Where the turn is, 0 to 1. */
    public var phase: Float = 0f
        private set

    /**
     * Moves the turn on by one frame and answers the new phase.
     *
     * [freeTurnsPerSecond] is the speed used when no tempo is known, and it is scaled by the mood
     * so a calm passage still turns slowly rather than at the same rate as a chorus.
     */
    public fun advance(
        deltaSeconds: Float,
        bpm: Float,
        beatConfidence: Float,
        phrasePhase: Float,
        moodPaced: Float,
    ): Float {
        val locked = beatConfidence > 0.4f && bpm > 0f
        val turnsPerSecond = if (locked) bpm / 60f / beatsPerCycle else moodPaced
        phase += turnsPerSecond * deltaSeconds

        if (locked) {
            // Where in this cycle the music says we should be. The phrase position is used as a
            // running count of beats, so any cycle length that divides a phrase lines up.
            val beatsIntoPhrase = phrasePhase * 16f
            val target = (beatsIntoPhrase % beatsPerCycle) / beatsPerCycle
            var error = target - phase
            // Round rather than floor, so it corrects whichever way round is shorter.
            error -= kotlin.math.round(error)
            phase += error * pullPerSecond * deltaSeconds
        }

        phase -= kotlin.math.floor(phase)
        return phase
    }

    public fun reset() {
        phase = 0f
    }
}
