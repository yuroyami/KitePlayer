package io.github.yuroyami.kiteplayer.audioviz.viz.motion

import io.github.yuroyami.kiteplayer.audioviz.AudioVizAuthoringApi
import io.github.yuroyami.kiteplayer.audioviz.SpectrumFrame
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
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

    // The springs were tuned by eye with one Euler step every sixtieth of a second. That step is a
    // 2 by 2 map M of the offset and the speed, and this keeps its power for any step length, so a step
    // of r sixtieths is M^r. At 60 Hz that is the tuned step itself, and on any other refresh rate it
    // is the same motion. Two shapes of M are handled:
    // - It turns (the eigenvalues are complex, which is every spring that overshoots and some that
    //   barely do): M = mu * (cos(theta) * I + sin(theta) * J) with J * J = -I, so
    //   M^r = mu^r * (cos(r * theta) * I + sin(r * theta) * J).
    // - It does not turn but has two positive real eigenvalues l1 above l2, as a heavily damped spring
    //   has: M^r = l2^r * I + (l1^r - l2^r) * A, with A = (M - l2 * I) / (l1 - l2).
    // A map with neither shape, a spring too stiff or too damped for a step of a sixtieth to stand for,
    // keeps the Euler step.
    private val kind: Int
    private val growA: Double
    private val growB: Double
    private val k11: Double
    private val k12: Double
    private val k21: Double
    private val k22: Double

    init {
        val friction = 2.0 * damping * kotlin.math.sqrt(stiffness.toDouble())
        val h = TUNED_STEP
        val m11 = 1.0 - stiffness * h * h
        val m12 = h * (1.0 - friction * h)
        val m21 = -stiffness * h
        val m22 = 1.0 - friction * h
        val trace = m11 + m22
        val determinant = m11 * m22 - m12 * m21
        var shape = EULER
        var a = Double.NaN
        var b = Double.NaN
        var scale = Double.NaN
        var shift = trace / 2.0
        if (determinant > 0.0 && trace.isFinite()) {
            val gap = trace * trace - 4.0 * determinant
            if (gap < 0.0) {
                // Turns: a is ln(mu), b is theta, and J is the map less half its trace, over mu * sin(theta).
                val root = kotlin.math.sqrt(determinant)
                val cosine = (trace / (2.0 * root)).coerceIn(-1.0, 1.0)
                val sine = kotlin.math.sqrt(1.0 - cosine * cosine)
                if (sine > 1e-9) {
                    shape = TURNS
                    a = kotlin.math.ln(root)
                    b = kotlin.math.acos(cosine)
                    scale = root * sine
                }
            } else {
                // Two real eigenvalues: a is ln(l1), b is ln(l2), and A is the map less l2, over l1 - l2.
                val spread = kotlin.math.sqrt(gap)
                val fast = (trace + spread) / 2.0
                val slow = (trace - spread) / 2.0
                if (slow > 1e-9 && fast - slow > 1e-9) {
                    shape = REAL
                    a = kotlin.math.ln(fast)
                    b = kotlin.math.ln(slow)
                    scale = fast - slow
                    shift = slow
                }
            }
        }
        kind = shape
        growA = a
        growB = b
        k11 = (m11 - shift) / scale
        k12 = m12 / scale
        k21 = m21 / scale
        k22 = (m22 - shift) / scale
    }

    /** Moves the spring on by one frame. */
    public fun advance(deltaSeconds: Float) {
        if (kind == EULER) {
            // Clamped, because a stalled window would otherwise hand it a huge step and explode it.
            val step = deltaSeconds.coerceIn(0f, 0.05f)
            val pull = (target - value) * stiffness
            val friction = speed * 2f * damping * kotlin.math.sqrt(stiffness)
            speed += (pull - friction) * step
            value += speed * step
            return
        }
        // The power of the map is stable for a step of any length, so a stall only lets the spring settle.
        val sixtieths = deltaSeconds.coerceIn(0f, 1f) / TUNED_STEP
        val offset = (value - target).toDouble()
        val velocity = speed.toDouble()
        if (kind == TURNS) {
            val grow = kotlin.math.exp(sixtieths * growA)
            val c = grow * cos(sixtieths * growB)
            val s = grow * sin(sixtieths * growB)
            value = (target + c * offset + s * (k11 * offset + k12 * velocity)).toFloat()
            speed = (c * velocity + s * (k21 * offset + k22 * velocity)).toFloat()
        } else {
            val slow = kotlin.math.exp(sixtieths * growB)
            val lift = kotlin.math.exp(sixtieths * growA) - slow
            value = (target + slow * offset + lift * (k11 * offset + k12 * velocity)).toFloat()
            speed = (slow * velocity + lift * (k21 * offset + k22 * velocity)).toFloat()
        }
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
            if (kind == TURNS) return (atan2(growB, -growA) / (growB / TUNED_STEP)).toFloat()
            // Heavily damped: the response is l1^r - l2^r, which peaks where l1^r * ln(l1) = l2^r * ln(l2).
            if (kind == REAL) return (kotlin.math.ln(growB / growA) / (growA - growB) * TUNED_STEP).toFloat()
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

    private companion object {
        /** The step the springs were tuned at, in seconds. */
        const val TUNED_STEP = 1.0 / 60.0

        // The shape of the tuned step's map.
        const val EULER = 0
        const val TURNS = 1
        const val REAL = 2
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
        // The rates were tuned as the share of the gap that one sixtieth of a second closes. What is
        // left after one sixtieth carries on to any step length, so a step of 1/30 closes the gap by
        // as much as two of 1/60 and the value looks the same on any refresh rate.
        val kept = (1f - rate / 60f).coerceIn(0f, 1f).toDouble()
        val share = 1.0 - kept.pow(deltaSeconds.coerceAtLeast(0f) * 60.0)
        value += (target - value) * share.toFloat()
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
 * is only pulled gently towards an accepted pulse phase, over a couple of seconds and without jerks.
 *
 * [beatsPerCycle] is the number of pulses per visual cycle. It does not describe musical meter
 * or establish a phrase boundary. One is a single pulse; larger cycles have an artistic origin.
 */
@AudioVizAuthoringApi
public class MusicClock(
    private val beatsPerCycle: Float = 4f,
    /** How hard the music pulls the turn into line. Higher locks faster and wobbles more. */
    private val pullPerSecond: Float = 0.6f,
) {
    init { require(beatsPerCycle.isFinite() && beatsPerCycle > 0f) }
    private var rhythmMix = 0f
    private var rhythmBpm = 0f

    /** Where the turn is, 0 to 1. */
    public var phase: Float = 0f
        private set

    /**
     * Follow an accepted pulse phase, with a two-second rate handover to/from free motion.
     * The cycle origin is chosen locally; no bar or phrase is inferred from counted pulses.
     */
    public fun advance(deltaSeconds: Float, frame: SpectrumFrame, moodPaced: Float): Float {
        val dt = deltaSeconds.takeIf { it.isFinite() && it > 0f }?.coerceAtMost(0.1f) ?: return phase
        val rhythm = frame.rhythm?.takeIf { it.usable && it.bpm > 0f }
        if (rhythm != null) rhythmBpm = rhythm.bpm
        rhythmMix = (rhythmMix + if (rhythm != null) dt / 2f else -dt / 2f).coerceIn(0f, 1f)
        val free = moodPaced.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
        val turns = free * (1f - rhythmMix) + rhythmBpm / 60f / beatsPerCycle * rhythmMix
        phase += turns * dt
        if (rhythm != null) {
            // Choose the nearest matching pulse within this visual cycle. We need no downbeat
            // or sixteen-pulse origin, and switching a hypothesis never assigns phase directly.
            val localBeats = phase * beatsPerCycle
            val target = kotlin.math.round(localBeats - rhythm.beatPhase) + rhythm.beatPhase
            val error = (target - localBeats) / beatsPerCycle
            phase += error * (1f - exp(-pullPerSecond * dt)) * rhythmMix
        }
        phase -= kotlin.math.floor(phase)
        return phase
    }

    /**
     * Moves the turn on by one frame and answers the new phase.
     *
     * [moodPaced] is the speed used when no tempo is known, and it is scaled by the mood
     * so a calm passage still turns slowly rather than at the same rate as a chorus.
     */
    @Deprecated("Use the SpectrumFrame overload for accepted rhythm evidence and unknown meter.")
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
        rhythmMix = 0f
        rhythmBpm = 0f
    }
}
