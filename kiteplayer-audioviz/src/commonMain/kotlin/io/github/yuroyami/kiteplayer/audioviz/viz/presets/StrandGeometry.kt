package io.github.yuroyami.kiteplayer.audioviz.viz.presets

import io.github.yuroyami.kiteplayer.audioviz.viz.TAU
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Thirty permanent filaments. Their endpoints and every interior sample keep their identity. */
internal class StrandGeometry {
    val positions = FloatArray(STRANDS * POINTS * 3)
    private val vertices = floatArrayOf(
        -1f, PHI, 0f, 1f, PHI, 0f, -1f, -PHI, 0f, 1f, -PHI, 0f,
        0f, -1f, PHI, 0f, 1f, PHI, 0f, -1f, -PHI, 0f, 1f, -PHI,
        PHI, 0f, -1f, PHI, 0f, 1f, -PHI, 0f, -1f, -PHI, 0f, 1f,
    )
    val edges = IntArray(STRANDS * 2)
    private val web = FloatArray(vertices.size)

    init {
        var edge = 0
        for (a in 0 until 12) for (b in a + 1 until 12) {
            var distance = 0f
            for (axis in 0..2) {
                val d = vertices[a * 3 + axis] - vertices[b * 3 + axis]
                distance += d * d
            }
            if (abs(distance - 4f) < 0.01f) { edges[edge++] = a; edges[edge++] = b }
        }
        check(edge == edges.size)
        val scale = 1.1f / sqrt(1f + PHI * PHI)
        for (i in vertices.indices) vertices[i] *= scale
    }

    fun update(weights: FloatArray, phase: Float, activity: Float, bands: FloatArray,
        scope: FloatArray, pulse: Float, aspect: Float, response: Float, depth: Float) {
        for (node in 0 until 12) {
            web[node * 3] = sin(node * 2.39996f + phase * 0.09f) * (0.9f + node % 3 * 0.24f)
            web[node * 3 + 1] = cos(node * 1.7f + phase * 0.12f) * 1.05f
            web[node * 3 + 2] = sin(node * 2.1f + phase * 0.16f) * 0.62f
        }
        val reach = aspect.coerceIn(1f, 1.8f)
        for (strand in 0 until STRANDS) {
            val seat = strand.toFloat() / (STRANDS - 1)
            val a = edges[strand * 2] * 3; val b = edges[strand * 2 + 1] * 3
            val energy = bands[strand] * response
            for (step in 0 until POINTS) {
                val t = step.toFloat() / (POINTS - 1)
                val envelope = sin(t * 3.14159265f)
                val wave = scope[step] * response
                val vibration = (sin(t * TAU * (1.5f + activity * 2f) - phase + strand * 0.31f) *
                    (0.025f + energy * 0.12f) + wave * 0.15f) * envelope
                val front = pulse * sin(t * TAU - phase * 1.7f) * envelope * 0.06f
                val angle = seat * TAU + phase * 0.06f + vibration * (1.3f + activity * 1.2f)
                val radial = 0.2f + t * (0.7f + energy * 0.48f) + front
                val mx = cos(angle) * radial
                val my = sin(angle) * radial
                val mz = sin(t * TAU + phase * 0.3f) * envelope * 0.18f
                val ma = 1f + bands[(edges[strand * 2] * 2 + 3) % STRANDS] * response * 0.2f
                val mb = 1f + bands[(edges[strand * 2 + 1] * 2 + 3) % STRANDS] * response * 0.2f
                val ix = vertices[a] * ma + (vertices[b] * mb - vertices[a] * ma) * t
                val iy = vertices[a + 1] * ma + (vertices[b + 1] * mb - vertices[a + 1] * ma) * t
                val iz = vertices[a + 2] * ma + (vertices[b + 2] * mb - vertices[a + 2] * ma) * t
                // Shared web endpoints stay welded. Corners soften as the web becomes strands.
                val kink = (1f - abs(t * 4f % 2f - 1f)) * 0.16f * sin(strand * 2.1f + phase * 0.2f)
                val wx = web[a] + (web[b] - web[a]) * t + kink
                val wy = web[a + 1] + (web[b + 1] - web[a + 1]) * t + kink * 0.7f
                val wz = web[a + 2] + (web[b + 2] - web[a + 2]) * t
                val sx = (t * 2f - 1f) * reach * 1.28f
                val sy = (seat - 0.5f) * 1.5f + sin(t * TAU * 0.8f + phase * 0.4f + strand * 1.7f) *
                    (0.18f + energy * 0.28f) + vibration
                val sz = sin(t * TAU + strand * 0.6f + phase * 0.22f) * 0.38f
                val hy = (seat - 0.5f) * 1.55f + sin(t * TAU * 1.25f - phase * 0.65f + seat * 2f) *
                    (0.09f + energy * 0.28f) + wave * 0.18f + front
                val hz = (seat - 0.5f) * 0.4f + sin(t * TAU - phase * 0.3f) * 0.1f
                val p = (strand * POINTS + step) * 3
                positions[p] = mx * weights[0] + (ix + vibration * ix * 0.15f) * weights[1] + wx * weights[2] + sx * (weights[3] + weights[4])
                positions[p + 1] = my * weights[0] + (iy + vibration * iy * 0.15f) * weights[1] + wy * weights[2] + sy * weights[3] + hy * weights[4]
                positions[p + 2] = (mz * weights[0] + (iz + vibration * iz * 0.15f) * weights[1] + wz * weights[2] + sz * weights[3] + hz * weights[4]) * depth
            }
        }
    }

    companion object {
        const val STRANDS = 30
        const val POINTS = 65
        private const val PHI = 1.61803399f
    }
}
