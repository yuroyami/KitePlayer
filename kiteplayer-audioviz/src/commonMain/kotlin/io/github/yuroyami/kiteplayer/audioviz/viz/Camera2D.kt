package io.github.yuroyami.kiteplayer.audioviz.viz

import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import io.github.yuroyami.kiteplayer.audioviz.AudioVizAuthoringApi
import io.github.yuroyami.kiteplayer.audioviz.AudioEventKind
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Noise1
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Spring
import kotlin.math.cos
import kotlin.math.sin

/**
 * Moves a whole flat drawing, the way [CameraRig] moves a flight.
 *
 * A wander, a zoom punch on the kick (early, when the queue shows it coming), a nudge on the
 * snare, a roll, a whip on a drop, and now and then a cut to a new framing at a supported section boundary.
 * Pans are shares of the screen, [angle] is radians.
 */
@AudioVizAuthoringApi
public class Camera2D(
    private val wander: Float = 0.1f,
    private val punch: Float = 0.1f,
    private val roll: Float = 0.1f,
    private val shake: Float = 0.004f,
    private val cuts: Boolean = true,
    private val minZoom: Float = 0.94f,
    private val maxZoom: Float = 1.3f,
    seed: Int = 1,
) {
    public var panX: Float = 0f
        private set
    public var panY: Float = 0f
        private set
    public var zoom: Float = 1f
        private set
    public var angle: Float = 0f
        private set

    /** Radius of a slow orbit, as a share of the screen. Zero leaves it off. */
    public var orbit: Float = 0f

    /** Laps per four-pulse visual cycle when rhythm is usable. */
    public var orbitRate: Float = 0.5f

    // Its theoretical peak is about 96 ms after an impulse, inside the 100 ms lookahead limit.
    private val punchSpring = Spring(stiffness = 160f, damping = 0.5f)
    private val impulse = AnticipatedImpulse(punchSpring)
    private val nudge = Spring(stiffness = 50f, damping = 0.55f)
    private var nudgeSide = 1f
    private val whip = Spring(stiffness = 14f, damping = 0.6f)
    private val noise = Noise1(seed)
    private val random = Rng(seed * 104_729L + 7L)
    private var drift = 0f
    private var bank = 0f
    private var orbitPhase = 0f
    private var laneX = 0f
    private var laneY = 0f
    private var laneZoom = 1f
    private var boundaries = MusicalBoundaryGate()
    private val step = DisplayStep()

    /**
     * Moves the camera on by one frame. The same state twice moves it once. A new frame at an
     * instant already drawn still applies its hits, without adding elapsed time.
     */
    public fun advance(state: VizRenderState) {
        val dt = step.of(state) ?: return
        val frame = state.frame
        val boundary = boundaries.read(frame)

        // Advance the previous state to this display instant before applying newly delivered hits.
        punchSpring.advance(dt)
        val upcoming = state.future?.nextEvent(AudioEventKind.LowTransient)
        impulse.apply(frame, upcoming, PUNCH_KICK)

        nudge.advance(dt)
        if (frame.snare > 0f) {
            nudge.kick(frame.snare * NUDGE_KICK * nudgeSide)
            nudgeSide = -nudgeSide
        }
        whip.advance(dt)
        if (boundary?.detection?.kind == AudioEventKind.Drop) {
            whip.kick(WHIP_KICK * boundary.detection.strength * if (random.next() < 0.5f) -1f else 1f)
        }

        if (cuts && boundary != null && random.next() < CUT_CHANCE * state.motionScale) {
            laneX = random.signed() * wander * 1.4f
            laneY = random.signed() * wander
            laneZoom = 1f + random.next() * 0.12f
        }

        val locked = frame.rhythm?.usable == true
        drift += dt * state.paced(1.6f)
        bank += dt * state.paced(0.3f + 0.9f * frame.midRel)
        orbitPhase += dt * orbitRate * if (locked) frame.bpm / 240f else state.paced(0.5f)
        // A reduced-motion setting damps everything that throws the picture about and leaves the
        // slow wander, so the drawing still breathes rather than freezing.
        val scale = state.motionScale
        val jitter = shake * frame.hatPulse * scale

        panX = noise.layered(drift * 0.5f, 2) * wander + laneX * scale + nudge.value * NUDGE * scale +
            orbit * cos(orbitPhase * TAU) + random.signed() * jitter
        panY = noise.layered(drift * 0.43f + 50f, 2) * wander * 0.8f + laneY * scale +
            orbit * 0.7f * sin(orbitPhase * TAU) + random.signed() * jitter
        val rest = 1f + 0.05f * frame.loudLong
        zoom = (rest * laneZoom * (1f + punch * scale * punchSpring.value.coerceIn(-0.5f, 1.5f)))
            .coerceIn(minZoom, maxZoom)
        angle = noise.at(bank + 90f) * roll + whip.value * WHIP * scale
    }

    /** Pushes the zoom the way a kick does, for a drawing's own hits. */
    public fun shove(amount: Float) {
        punchSpring.kick(amount * PUNCH_KICK)
    }

    /**
     * Where a point at [x], [y] (shares of the screen, before the camera) lands on screen, as shares.
     * [aspect] is width over height. Answers into [into].
     */
    public fun project(x: Float, y: Float, aspect: Float, parallax: Float, into: Anchor) {
        val z = 1f + (zoom - 1f) * parallax
        val turn = angle * parallax
        val dx = (x - 0.5f) * aspect
        val dy = y - 0.5f
        val c = cos(turn)
        val s = sin(turn)
        into.x = 0.5f + (dx * c - dy * s) * z / aspect + panX * parallax
        into.y = 0.5f + (dx * s + dy * c) * z + panY * parallax
    }

    public fun reset() {
        panX = 0f
        panY = 0f
        zoom = 1f
        angle = 0f
        punchSpring.reset()
        impulse.reset()
        nudge.reset()
        nudgeSide = 1f
        whip.reset()
        random.reset()
        drift = 0f
        bank = 0f
        orbitPhase = 0f
        laneX = 0f
        laneY = 0f
        laneZoom = 1f
        boundaries = MusicalBoundaryGate()
        step.reset()
    }

    private companion object {
        const val PUNCH_KICK = 12f
        const val NUDGE_KICK = 8f
        const val NUDGE = 0.06f
        const val WHIP_KICK = 6f
        const val WHIP = 0.26f
        const val CUT_CHANCE = 0.25f
    }
}

/** A place on screen, as shares of the width and the height. */
@AudioVizAuthoringApi
public class Anchor(x: Float = 0.5f, y: Float = 0.5f) {
    public var x: Float = x
        internal set
    public var y: Float = y
        internal set
}

/** Draws [block] through [camera], moved [parallax] as far as the camera moves: less for far layers. */
@AudioVizAuthoringApi
public inline fun DrawScope.withCamera(camera: Camera2D?, parallax: Float = 1f, block: DrawScope.() -> Unit) {
    if (camera == null || parallax == 0f) {
        block()
        return
    }
    val x = camera.panX * parallax * size.width
    val y = camera.panY * parallax * size.height
    val z = 1f + (camera.zoom - 1f) * parallax
    val degrees = camera.angle * parallax * 57.29578f
    withTransform({
        translate(x, y)
        scale(z, z, center)
        rotate(degrees, center)
    }, block)
}
