package io.github.yuroyami.kiteplayer.audioviz.viz.shader

import kotlin.math.*

/** A bounded road, expressed in world distance. Shader, floor and dressing use this one route. */
internal class NeonLoFiFlight {
    var travel = 0.0; private set
    var velocity = 0f; private set
    var bank = 0f; private set
    var bob = 0f; private set
    var layout = 0.37f
    var motion = 1f
    var phase = 0f; private set
    /** How far the view has lifted off the road, 0 to 1: the drop's lift-off, set once a frame. */
    var lift = 0f
    val height: Float get() = 1.6f + bob + LIFT_HEIGHT * lift
    /** The horizon's height as a share of the screen. It drops away as the view lifts off. */
    val horizon: Float get() = HORIZON + LIFT_HORIZON * lift

    /**
     * With a beat to follow, the road passes one lane dash a beat, and so one lamp pair a bar.
     * Without one, the mood sets the pace.
     */
    fun advance(dt: Float, audible: Float, mood: Float, speed: Float, pulse: Float, beatSeconds: Float = 0f) {
        val cruise = if (beatSeconds > 0.15f) DASH / beatSeconds else 6f + 18f * mood
        val target = if (audible > 0f) cruise * speed * motion else 0f
        // Flight speed and zero motion are explicit stops, not long braking distances.
        velocity = if (speed == 0f || motion == 0f) 0f else approach(velocity, target.coerceIn(0f, 48f), dt, 0.7f)
        travel += velocity * dt
        if (motion == 0f) return
        phase += dt * audible * motion
        val targetBank = (-slope(travel.toFloat() + 15f) * 0.55f).coerceIn(-0.075f, 0.075f) * motion
        bank = approach(bank, targetBank * audible, dt, 1.2f)
        // The camera holds still on the beat: the music moves the scene, not the view.
        bob = 0f
    }
    fun road(z: Float): Float = 2.2f * sin(z * 0.009f + layout * 4f) + 0.7f * sin(z * 0.023f + layout * 9f)
    fun slope(z: Float): Float = 0.0198f * cos(z * 0.009f + layout * 4f) + 0.0161f * cos(z * 0.023f + layout * 9f)
    fun reset() { travel = 0.0; velocity = 0f; bank = 0f; bob = 0f; phase = 0f; lift = 0f }

    /** Road-local point, with a restrained bank around a fixed, readable horizon. */
    fun project(x: Float, y: Float, z: Float, width: Float, heightPx: Float, into: FloatArray, offset: Int = 0) {
        val distance = max(z - travel.toFloat(), 0.8f)
        val focal = heightPx * FOCAL
        val dx = (x - road(travel.toFloat()) - slope(travel.toFloat()) * distance) * horizontalFocal(width, heightPx) / distance
        val dy = (height - y) * focal / distance
        val c = cos(bank); val s = sin(bank)
        into[offset] = width * 0.5f + dx * c - dy * s
        into[offset + 1] = heightPx * horizon + dx * s + dy * c
    }
    fun laneWidth(aspect: Float): Float = ((aspect * 18f / (2f * min(FOCAL, aspect * 0.19f)) - CORRIDOR - 1f) / 16f).coerceIn(0.5f, 1.55f)
    fun horizontalFocal(width: Float, height: Float): Float = min(height * FOCAL, width * 0.19f)
    companion object {
        const val FOCAL = 0.714074f // 70 degree vertical field of view.
        const val HORIZON = 0.48f
        /** One lane dash in world distance; a lamp pair stands every [LAMP_EVERY] of them. */
        const val DASH = 7f
        const val LAMP_EVERY = 4
        /** How high the view rises on a lift-off, and how far down the screen the horizon drops. */
        const val LIFT_HEIGHT = 26f
        const val LIFT_HORIZON = 0.14f
        const val CORRIDOR = 3.6f // 7.2-wide swept clearance, in road-local coordinates.
        fun approach(value: Float, target: Float, dt: Float, seconds: Float): Float =
            value + (target - value) * (1f - exp(-dt / seconds))
    }
}

/** Evidence admits a region change; elapsed time alone never does. Retargeting preserves velocity. */
internal class NeonLoFiRegions {
    val weights = floatArrayOf(1f, 0f, 0f, 0f)
    private val velocity = FloatArray(4)
    var target = 0; private set
    var starts = 0; private set
    var clock = 0f; private set
    var lastStart = -60f; private set
    private var recent = -1
    private var previousManual = -1
    private var initialized = false
    private val baseline = FloatArray(3)
    private var contrastTime = 0f
    private var decisions = 0
    fun advance(dt: Float, audible: Float, manual: Int, pace: Float, seed: Float,
        level: Float, density: Float, centroid: Float, structure: Boolean) {
        if (!initialized) {
            target = if (manual >= 0) manual else ((seed * 71f + density * 2f).toInt() % 4).coerceAtLeast(0)
            weights.fill(0f); weights[target] = 1f
            baseline[0] = level; baseline[1] = density; baseline[2] = centroid
            initialized = true; previousManual = manual
        }
        clock += dt * audible
        val contrast = abs(level - baseline[0]) > 0.19f &&
            (abs(density - baseline[1]) > 0.17f || abs(centroid - baseline[2]) > 0.16f)
        contrastTime = if (contrast && audible > 0f) contrastTime + dt else max(0f, contrastTime - dt * 2f)
        val eligible = manual < 0 && clock - lastStart >= 60f && (structure || contrastTime >= 8f / pace)
        if ((manual >= 0 && manual != previousManual && manual != target) || eligible) {
            var next = manual
            if (next < 0) {
                val hint = (seed * 997 + density * 31 + centroid * 17 + decisions * 1.618f).toInt()
                next = (target + 1 + (abs(hint) % 3)) % 4
                if (next == recent) next = (next + 1) % 4
                if (next == target) next = (next + 1) % 4
            }
            recent = target; target = next; decisions++; starts++; lastStart = clock
            contrastTime = 0f; baseline[0] = level; baseline[1] = density; baseline[2] = centroid
        }
        previousManual = manual
        // Critically damped closed form; 99% convergence in 8-16 s, even when retargeted midway.
        val omega = 6.64f / (12f / pace).coerceIn(8f, 16f)
        val damping = exp(-omega * dt)
        for (i in 0..3) {
            val goal = if (i == target) 1f else 0f
            val x = weights[i] - goal
            val c = velocity[i] + omega * x
            weights[i] = goal + (x + c * dt) * damping
            velocity[i] = (velocity[i] - omega * c * dt) * damping
        }
    }
    fun reset() {
        weights.fill(0f); weights[0] = 1f; velocity.fill(0f); target = 0; starts = 0; clock = 0f
        lastStart = -60f; recent = -1; previousManual = -1; initialized = false; contrastTime = 0f; decisions = 0
    }
}
