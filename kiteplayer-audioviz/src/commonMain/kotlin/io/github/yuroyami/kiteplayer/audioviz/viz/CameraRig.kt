package io.github.yuroyami.kiteplayer.audioviz.viz

import io.github.yuroyami.kiteplayer.audioviz.AudioVizAuthoringApi
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Anticipator
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Noise1
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Spring

/**
 * One camera for every drawing that flies, so they all answer the music the same way.
 *
 * - Speed follows [VizRenderState.drive], from a slow drift in silence up to [topSpeed].
 * - A kick shoves it forward through a spring. When the queued audio shows a kick coming, the camera
 *   first sinks back a little, and the shove goes in early enough that the surge peaks on the kick.
 * - A snare nudges it sideways, a drop flings the lens wide for about a bar, and it banks on noise
 *   that wanders faster when the middle of the spectrum is busy.
 * - With [cuts] on, a bar line with a sure tempo can jump it to a new lane instead of sliding there.
 *
 * Call [advance] once a frame, then read where the camera is.
 */
@AudioVizAuthoringApi
public class CameraRig(
    /** World units a second at full drive. */
    private val topSpeed: Float,
    /** World units a second in silence, so the picture still drifts. */
    private val restSpeed: Float = topSpeed * 0.1f,
    /** How much faster a kick makes it at the top of the surge, in world units a second. */
    private val shove: Float = topSpeed * 0.5f,
    /** How far the camera wanders across, in world units. */
    private val sway: Float = 0.4f,
    /** How far it wanders up and down. */
    private val swayUp: Float = sway * 0.9f,
    /** How far it banks, in radians. */
    private val lean: Float = 0.25f,
    /** The lens with nothing happening, in degrees. */
    private val baseFov: Float = 70f,
    /** Whether a bar line may jump the camera to a new lane. */
    private val cuts: Boolean = false,
    seed: Int = 1,
) {
    /** How far it has flown in all. */
    public var travelled: Float = 0f
        private set

    /** Where the eye is across and up, in world units from the middle. */
    public var eyeX: Float = 0f
        private set
    public var eyeY: Float = 0f
        private set

    /** The sideways nudge alone, for drawings that steer the eye themselves. */
    public var nudgeX: Float = 0f
        private set

    /** A point to look at, a little closer to the middle than the eye. */
    public val lookX: Float get() = eyeX * 0.2f
    public val lookY: Float get() = eyeY * 0.2f

    /** How far the camera is banked, in radians. */
    public var roll: Float = 0f
        private set

    /** The lens for this frame, in degrees. */
    public var fov: Float = baseFov
        private set

    private val surge = Spring(stiffness = 70f, damping = 0.6f)
    private val early = Anticipator(surge)
    private var shovedEarly = false
    private val sideways = Spring(stiffness = 60f, damping = 0.5f)
    private val widen = Spring(stiffness = 10f, damping = 0.85f)
    private val wander = Noise1(seed)
    private val random = Rng(seed * 7_919L + 1L)
    private var drift = 0f
    private var banking = 0f
    private var laneX = 0f
    private var laneY = 0f
    private var lastBar = 0f

    /**
     * Moves the camera on by one frame and answers how far it went.
     *
     * [speedScale] multiplies the whole flight. [cruise], when zero or more, replaces the speed the
     * music would pick, for a drawing that paces itself, such as one gate per beat.
     */
    public fun advance(state: VizRenderState, speedScale: Float = 1f, cruise: Float = -1f): Float {
        val frame = state.frame
        val dt = state.deltaSeconds

        // The kick. With the queue, the camera sinks back as the kick approaches and the shove is
        // timed so the surge peaks on the beat. Without it, the shove lands on the kick itself.
        val soon = state.nextOnsetIn
        val coming = if (soon >= 0f) state.ahead(soon).kick else 0f
        if (coming > 0f) {
            surge.target = -CROUCH * state.anticipation(CROUCH_SECONDS)
            if (early.due(soon)) {
                surge.kick(coming * KICK)
                shovedEarly = true
            }
        } else {
            surge.target = 0f
        }
        if (frame.kick > 0f) {
            if (!shovedEarly) surge.kick(frame.kick * KICK)
            shovedEarly = false
        }
        surge.advance(dt)

        if (frame.snare > 0f) sideways.kick(frame.snare * (if (random.next() < 0.5f) -NUDGE else NUDGE))
        sideways.advance(dt)
        if (frame.drop) widen.kick(frame.dropPulse.coerceAtLeast(0.5f) * WIDEN)
        widen.advance(dt)

        if (cuts && frame.beatConfidence > 0.7f && frame.barPhase < lastBar && random.next() < 0.3f) {
            laneX = random.signed() * sway * 1.2f
            laneY = random.signed() * swayUp
        }
        lastBar = frame.barPhase

        val base = if (cruise >= 0f) cruise else restSpeed + (topSpeed - restSpeed) * state.drive
        val speed = ((base + surge.value * shove) * speedScale).coerceAtLeast(0f)
        val moved = speed * dt
        travelled += moved

        drift += dt * state.paced(1f)
        banking += dt * (0.15f + 0.6f * frame.midRel)
        nudgeX = sideways.value * sway
        eyeX = wander.at(drift * 0.35f) * sway + laneX + nudgeX
        eyeY = wander.at(drift * 0.29f + 40f) * swayUp + laneY
        roll = wander.at(banking + 90f) * lean
        fov = baseFov + widen.value.coerceIn(-10f, 35f)
        return moved
    }

    public fun reset() {
        travelled = 0f
        surge.reset()
        early.reset()
        shovedEarly = false
        sideways.reset()
        widen.reset()
        random.reset()
        drift = 0f
        banking = 0f
        laneX = 0f
        laneY = 0f
        lastBar = 0f
        eyeX = 0f
        eyeY = 0f
        nudgeX = 0f
        roll = 0f
        fov = baseFov
    }

    private companion object {
        /** How much speed a kick adds to the surge spring, which peaks at about one. */
        const val KICK = 14f

        /** How far the camera sinks back before a kick it can see coming, and how early it starts. */
        const val CROUCH = 0.25f
        const val CROUCH_SECONDS = 0.15f

        const val NUDGE = 10f
        const val WIDEN = 120f
    }
}
