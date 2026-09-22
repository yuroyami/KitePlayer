package io.github.yuroyami.kiteplayer.audioviz.viz.shader

import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Gestures
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/** Inertial flight and travelling impulses driven by audible features and delivered events. */
internal class OdysseyFlight {
    var speed = 0f; private set
    var activity = 0f; private set
    var impact = 0f; private set
    var launch = 0f; private set
    var retreat = 0f; private set
    var yaw = 0f; private set
    var pitch = 0f; private set
    var bank = 0f; private set
    var x = 0f; private set
    var y = 0f; private set
    var phase = 0.0; private set
    var travelled = 12.0; private set
    val waves = FloatArray(4)
    private var waveAge = 10f
    private var waveAge2 = 10f
    private var waveOrigin = 0.0
    private var waveOrigin2 = 0.0
    private var waveStrength = 0f
    private var waveStrength2 = 0f

    fun advance(state: VizRenderState, gestures: Gestures, pace: Float, response: Float,
        contrast: Float, camera: Float, banking: Float, waveAmount: Float) {
        // Pausing freezes the viewpoint and architecture, including stored hit envelopes.
        if (state.frame.held) return
        val dt = state.deltaSeconds.coerceIn(0f, 0.25f)
        val frame = state.frame
        val audible = frame.audible
        val motion = state.motionScale.coerceIn(0f, 1f)
        val busy = (0.45f * frame.density + 0.3f * frame.mood +
            0.25f * (frame.novelty / 2f).coerceIn(0f, 1f)).coerceIn(0f, 1f)
        val drive = (0.55f * busy + 0.45f * state.drive).pow(contrast) * audible
        activity = settle(activity, drive, dt, if (drive > activity) 0.16f else 0.65f)
        val accent = maxOf(gestures.kickAccent, gestures.snareAccent * 0.8f,
            gestures.hatAccent * 0.35f).coerceAtMost(1.5f) * audible
        impact = maxOf(impact * exp(-dt / 0.24f), accent)
        launch *= exp(-dt / 1.25f)
        retreat *= exp(-dt / 2.0f)
        if (gestures.drop && audible > 0f) { launch = 1f; retreat = 0f }
        if (gestures.breakdown && audible > 0f) { retreat = 1f; launch = 0f }

        // Quiet passages nearly suspend the flight. Busy audio and real attacks provide thrust.
        val thrust = (18f * activity.pow(1.4f) + 5f * frame.energy * activity +
            8f * launch + 5f * impact - 2f * retreat).coerceAtLeast(0f) * response
        val target = (0.45f + thrust) * pace * motion * audible
        speed = settle(speed, target, dt, if (target > speed) 0.12f else 0.65f)
        travelled += speed.toDouble() * dt * audible
        val pulseRate = if (gestures.pulseUsable) 1f / gestures.cycleSeconds else 0.12f + activity * 0.22f
        phase += dt.toDouble() * audible * motion * response * pulseRate * (0.25f + 0.75f * activity) * 2.0 * PI
        val sweep = sin(phase).toFloat()
        val turn = sin(phase + PI * 0.5).toFloat()
        val strength = camera * motion * response
        val targetYaw = sweep * (0.06f + 0.58f * activity) * strength
        val targetPitch = (sin(phase * 0.57).toFloat() * 0.23f * activity +
            0.18f * retreat - 0.12f * launch - 0.07f * impact) * strength
        yaw = settle(yaw, targetYaw, dt, 0.32f)
        pitch = settle(pitch, targetPitch, dt, 0.35f)
        bank = settle(bank, -turn * 0.38f * activity * banking * motion * response, dt, 0.35f)
        x = settle(x, sweep * 0.62f * activity * strength, dt, 0.35f).coerceIn(-0.7f, 0.7f)
        y = settle(y, (sin(phase * 0.57).toFloat() * 0.35f * activity - 0.1f * impact) * strength,
            dt, 0.3f).coerceIn(-0.4f, 0.4f)

        waveAge += dt * audible; waveAge2 += dt * audible
        // A held tone produces no wave train. Only delivered low/body attacks launch fronts.
        if ((gestures.kicks > 0 || gestures.snares > 0) && audible > 0f) {
            waveAge2 = waveAge; waveOrigin2 = waveOrigin; waveStrength2 = waveStrength
            waveAge = 0f; waveOrigin = travelled
            waveStrength = maxOf(gestures.kickAccent, gestures.snareAccent * 0.65f).coerceAtMost(1.2f)
        }
        waves[0] = (waveOrigin + waveAge * 42.0 - travelled).toFloat()
        waves[1] = waveStrength * exp(-waveAge / 1.1f) * waveAmount * motion
        waves[2] = (waveOrigin2 + waveAge2 * 42.0 - travelled).toFloat()
        waves[3] = waveStrength2 * exp(-waveAge2 / 1.1f) * waveAmount * motion
    }

    fun reset() {
        speed = 0f; activity = 0f; impact = 0f; launch = 0f; retreat = 0f
        yaw = 0f; pitch = 0f; bank = 0f; x = 0f; y = 0f
        phase = 0.0; travelled = 12.0
        waveAge = 10f; waveAge2 = 10f; waveOrigin = 0.0; waveOrigin2 = 0.0
        waveStrength = 0f; waveStrength2 = 0f; waves.fill(0f)
    }

    private fun settle(value: Float, target: Float, dt: Float, seconds: Float): Float =
        value + (target - value) * (1f - exp(-dt / seconds))
}
