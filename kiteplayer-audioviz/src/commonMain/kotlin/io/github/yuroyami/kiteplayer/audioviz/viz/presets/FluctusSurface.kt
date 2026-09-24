package io.github.yuroyami.kiteplayer.audioviz.viz.presets

import androidx.compose.ui.geometry.Size
import io.github.yuroyami.kiteplayer.audioviz.viz.DisplayStep
import io.github.yuroyami.kiteplayer.audioviz.viz.Scene3D
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import kotlin.math.*

/**
 * Native reconstruction of Jordan Machado's Fluctus floating spectrum sheet.
 * Reference: https://github.com/JordanMachado/fluctus (PlaneAudio and Webgl).
 * Geometry, pose and spatial FFT packing follow that reference. The static field and colour
 * grade are procedural replacements for its image assets, not copies of those assets.
 *
 * Two changes from the reference, both for a phone's screen: the grid has 100 by 100 cells where
 * the page had 50 by 50, so the sheet stays smooth drawn large, and the camera frames the sheet at
 * [SHEET_SHARE] of the frame's width, looking down at it and its shadow, where the page's camera
 * sat 220 units away and showed a sheet about an eighth of a landscape frame.
 */
internal class FluctusSurface {
    val height = FloatArray(VERTICES)
    val worldX = FloatArray(VERTICES)
    val worldY = FloatArray(VERTICES)
    val worldZ = FloatArray(VERTICES)
    val projectedX = FloatArray(VERTICES)
    val projectedY = FloatArray(VERTICES)
    val shadowX = FloatArray(VERTICES)
    val shadowY = FloatArray(VERTICES)
    val red = FloatArray(VERTICES)
    val green = FloatArray(VERTICES)
    val blue = FloatArray(VERTICES)
    val indices = IntArray(TRIANGLES * 3)
    val order = IntArray(TRIANGLES) { it }
    private val depth = FloatArray(VERTICES)
    private val triangleDepth = FloatArray(TRIANGLES)
    private val sortScratch = IntArray(TRIANGLES)
    private val staticField = FloatArray(VERTICES)
    private val spectrum = FluctusSpectrum()
    private val step = DisplayStep()
    private val scene = Scene3D()
    private var flowTime = 0.0
    private var turn = 0.0
    private var wireTarget = false
    private var trebleArmed = true
    private var initialized = false
    private var lastDeformation = 1f
    private var lastWireMode = 0
    var wireMix: Float = 0f
        private set
    var horizon: Float = 0f
        private set

    init {
        var at = 0
        for (row in 0 until SEGMENTS) for (column in 0 until SEGMENTS) {
            val a = row * SIDE + column
            val b = a + SIDE
            // Same diagonal as THREE.PlaneGeometry. All vertices are shared.
            indices[at++] = a; indices[at++] = b; indices[at++] = a + 1
            indices[at++] = b; indices[at++] = b + 1; indices[at++] = a + 1
        }
        for (row in 0..SEGMENTS) for (column in 0..SEGMENTS) {
            val u = column.toFloat() / SEGMENTS
            val v = 1f - row.toFloat() / SEGMENTS
            staticField[row * SIDE + column] = 0.20f +
                0.24f * noise(u * 4.2f + 7f, v * 4.2f - 3f) +
                0.12f * noise(u * 11f - 2f, v * 11f + 8f)
        }
    }

    fun advance(state: VizRenderState, deformation: Float = 1f, drift: Float = 1f,
        rotation: Float = 1f, wireMode: Int = 0) {
        val dt = step.of(state)
        if (dt == null) {
            configure(deformation, wireMode)
            return
        }
        val first = !initialized
        val held = state.frame.held
        initialized = true
        if (first && held) spectrum.seed(state.frame) else spectrum.update(state.frame, dt)
        val motion = if (held) 0f else dt * state.frame.audible * state.motionScale
        flowTime += motion * drift * 0.25
        turn += motion * rotation * 0.06
        // A sustained noisy passage should reveal a lattice, not toggle it five times a second.
        // Rearm on release; the original 260/854 threshold identifies the same treble activity.
        if (!held) {
            if (spectrum.highActivity < 0.23f) trebleArmed = true
            if (spectrum.highActivity > 260f / 854f && trebleArmed && state.frame.audible > 0f) {
                wireTarget = !wireTarget
                trebleArmed = false
            }
        }
        val wanted = wireDestination(wireMode)
        if (held) {
            // Explicit controls remain usable while time stands still. An unchanged paused
            // frame preserves an in-progress automatic transition exactly where it stopped.
            if (first || wireMode != lastWireMode) wireMix = wanted
        } else {
            wireMix += (wanted - wireMix) * (1f - exp(-dt * 12f))
            if (abs(wanted - wireMix) < 0.001f) wireMix = wanted
        }
        lastWireMode = wireMode
        if (first || !held || deformation != lastDeformation) rebuild(deformation)
    }

    /** Apply controls when Layered has already consumed this display instant, including pause. */
    fun configure(deformation: Float, wireMode: Int) {
        if (!initialized) return
        if (wireMode != lastWireMode) {
            lastWireMode = wireMode
            wireMix = wireDestination(wireMode)
        }
        if (deformation != lastDeformation) rebuild(deformation)
    }

    private fun wireDestination(wireMode: Int): Float =
        when (wireMode) { 1 -> 0f; 2 -> 1f; else -> if (wireTarget) 1f else 0f }

    private fun rebuild(deformation: Float) {
        lastDeformation = deformation
        val elevation = (10f + spectrum.mean) * deformation
        val spinCos = cos(turn).toFloat(); val spinSin = sin(turn).toFloat()
        val tiltCos = cos(110f * PI.toFloat() / 180f)
        val tiltSin = sin(110f * PI.toFloat() / 180f)
        val rollCos = cos(155f * PI.toFloat() / 180f)
        val rollSin = sin(155f * PI.toFloat() / 180f)
        val phase = flowTime.toFloat()
        for (row in 0..SEGMENTS) for (column in 0..SEGMENTS) {
            val index = row * SIDE + column
            val u = column.toFloat() / SEGMENTS
            val v = 1f - row.toFloat() / SEGMENTS
            val field = noise(u * 1.3f + phase, v * 1.3f + phase) + staticField[index]
            val h = (field + 0.5f).coerceIn(0f, 1f)
            val cell = ((v * 12f).toInt().coerceIn(0, 11) * 12 +
                (u * 12f).toInt().coerceIn(0, 11)) * 3
            val audio = spectrum.cells[cell]
            val z = elevation * (h - audio)
            height[index] = z
            val x = (u - 0.5f) * 100f
            val y = (v - 0.5f) * 100f
            val rx = x * rollCos - y * rollSin
            val ry = x * rollSin + y * rollCos
            val rz = ry * tiltSin + z * tiltCos
            worldX[index] = rx * spinCos + rz * spinSin
            worldY[index] = ry * tiltCos - z * tiltSin + 20f
            worldZ[index] = -rx * spinSin + rz * spinCos
            // The reference material is unlit: height and adjacent FFT bins drive its colour.
            // This small analytic grade recreates its blue recesses and rose highlights.
            val r = 0.35f + 0.5f * h + 0.5f * audio
            val g = 0.35f + 0.5f * (field * 1.2f).coerceIn(0f, 1f) + 0.5f * spectrum.cells[cell + 2]
            val b = 0.35f + 0.5f * (field * 1.5f).coerceIn(0f, 1f) + 0.5f * spectrum.cells[cell + 1]
            red[index] = 0.36f + 0.64f * smooth(0.35f, 1f, r)
            green[index] = 0.38f + 0.60f * smooth(0.35f, 1f, g)
            blue[index] = 0.75f + 0.25f * smooth(0.35f, 1f, b)
        }
    }

    fun project(width: Float, height: Float, zoom: Float = 1f, tiltDegrees: Float = 0f) {
        // The page's lens, 50 degrees high, at the distance where the sheet spans SHEET_SHARE of
        // the frame's width.
        val focal = height * 0.5f / tan(FOV_DEGREES * 0.5f * PI.toFloat() / 180f)
        val distance = focal * SHEET_SPAN / (SHEET_SHARE * width.coerceAtLeast(1f)) / zoom.coerceAtLeast(0.1f)
        val angle = (ELEVATION_DEGREES + tiltDegrees) * PI.toFloat() / 180f
        scene.lens(Size(width, height), fovDegrees = FOV_DEGREES, near = 1f, far = 10_000f)
        // The camera looks down at a point between the sheet and its shadow. Its orbit stops above
        // the floor; looking up from beneath it would put the ground shadow into the sky.
        scene.camera(0f, (AIM_Y + sin(angle) * distance).coerceAtLeast(-35f),
            cos(angle) * distance, 0f, AIM_Y, 0f)
        scene.project(0f, -50f, -4_000f)
        horizon = scene.screenY
        for (index in 0 until VERTICES) {
            scene.project(worldX[index], worldY[index], worldZ[index])
            projectedX[index] = scene.screenX; projectedY[index] = scene.screenY
            depth[index] = scene.depth
            // Intersect the light-to-vertex ray with the actual floor at y=-50.
            val scale = 1_050f / (1_000f - worldY[index])
            scene.project(worldX[index] * scale, -50f, worldZ[index] * scale)
            shadowX[index] = scene.screenX; shadowY[index] = scene.screenY
        }
        for (triangle in order.indices) {
            val offset = triangle * 3
            triangleDepth[triangle] = depth[indices[offset]] + depth[indices[offset + 1]] + depth[indices[offset + 2]]
            order[triangle] = triangle
        }
        // Stable iterative merge sort without boxing or per-frame objects.
        var run = 1
        while (run < order.size) {
            var left = 0
            while (left < order.size) {
                val middle = min(left + run, order.size)
                val end = min(left + 2 * run, order.size)
                var a = left; var b = middle; var out = left
                while (a < middle || b < end) {
                    sortScratch[out++] = if (b >= end ||
                        (a < middle && triangleDepth[order[a]] >= triangleDepth[order[b]])) order[a++] else order[b++]
                }
                left = end
            }
            sortScratch.copyInto(order)
            run *= 2
        }
    }

    fun reset() {
        spectrum.reset(); step.reset()
        flowTime = 0.0; turn = 0.0; wireMix = 0f; initialized = false
        wireTarget = false; trebleArmed = true
        lastDeformation = 1f; lastWireMode = 0
    }

    companion object {
        const val SEGMENTS = 100
        const val SIDE = SEGMENTS + 1
        const val VERTICES = SIDE * SIDE
        const val TRIANGLES = SEGMENTS * SEGMENTS * 2

        /** The page's vertical field of view. */
        const val FOV_DEGREES = 50f

        /**
         * The share of the frame's width the sheet spans on average as it turns, and the width in
         * units that the distance is worked out for. The span is larger than the sheet's 100 units
         * because its near edge is closer than the point the camera looks at; it was measured.
         */
        const val SHEET_SHARE = 0.7f
        const val SHEET_SPAN = 155f

        /**
         * Where the camera looks, between the sheet at 20 and the floor at -50, and how far above
         * that point it sits, in degrees. With the sheet this large, the near edge of its shadow
         * runs off the bottom of a landscape frame, as a close camera over a floor would show it.
         */
        const val AIM_Y = -8f
        const val ELEVATION_DEGREES = 20f

        private fun smooth(low: Float, high: Float, value: Float): Float {
            val t = ((value - low) / (high - low)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }

        /** Continuous gradient noise, with a fixed integer hash and no texture or random state. */
        private fun noise(x: Float, y: Float): Float {
            val ix = floor(x).toInt(); val iy = floor(y).toInt()
            val fx = x - ix; val fy = y - iy
            val ux = fx * fx * fx * (fx * (fx * 6f - 15f) + 10f)
            val uy = fy * fy * fy * (fy * (fy * 6f - 15f) + 10f)
            fun dot(gx: Int, gy: Int, dx: Float, dy: Float): Float {
                var hash = gx * 374761393 + gy * 668265263
                hash = (hash xor (hash ushr 13)) * 1274126177
                return when ((hash xor (hash ushr 16)) and 7) {
                    0 -> dx; 1 -> -dx; 2 -> dy; 3 -> -dy
                    4 -> (dx + dy) * 0.7071f; 5 -> (dx - dy) * 0.7071f
                    6 -> (-dx + dy) * 0.7071f; else -> (-dx - dy) * 0.7071f
                }
            }
            val a = dot(ix, iy, fx, fy)
            val b = dot(ix + 1, iy, fx - 1f, fy)
            val c = dot(ix, iy + 1, fx, fy - 1f)
            val d = dot(ix + 1, iy + 1, fx - 1f, fy - 1f)
            return ((a + (b - a) * ux) * (1f - uy) + (c + (d - c) * ux) * uy) * 1.8f
        }
    }
}
