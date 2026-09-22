package io.github.yuroyami.kiteplayer.audioviz.viz.presets

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** A ring opens into a hexagonal tunnel and then a pair of horizon planes, vertex for vertex. */
internal object PipeShape {
    fun point(out: FloatArray, angle: Float, radius: Float, twist: Float, weights: FloatArray) {
        val c = cos(angle); val s = sin(angle)
        val hexAngle = ((angle % 1.04719755f) + 1.04719755f) % 1.04719755f - 0.5235988f
        val hex = 0.8660254f / cos(hexAngle)
        val box = maxOf(abs(c), abs(s)).coerceAtLeast(0.001f)
        val x = radius * c * (weights[0] + weights[1] * hex * 1.15f + weights[2] * 5.5f / box)
        val y = radius * s * (weights[0] + weights[1] * hex * 1.15f + weights[2] * 0.8f / box)
        val rotation = twist * (1f - weights[2])
        out[0] = x * cos(rotation) - y * sin(rotation)
        out[1] = x * sin(rotation) + y * cos(rotation)
    }
}
