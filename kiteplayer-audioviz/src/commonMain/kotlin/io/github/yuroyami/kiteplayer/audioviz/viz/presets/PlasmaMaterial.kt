package io.github.yuroyami.kiteplayer.audioviz.viz.presets

import kotlin.math.sin

/** Interference material shared by Plasma's shader and portable sheet. */
internal object PlasmaMaterial {
    fun waves(x: Float, y: Float, radius: Float, flow: Float, body: Float, bend: Float, density: Float): Float =
        sin(x * (7f + 4f * body) * density + flow) +
            sin(y * 5.5f * density - flow * 0.7f) +
            sin((x + y) * 4.5f * density + flow * 1.3f) +
            sin(radius * (14f + 7f * bend) * density - flow * 2f)

    const val SOURCE: String = """
float plasmaWaves(float2 q, float radius, float flow, float body, float bend, float density) {
    return sin(q.x * (7.0 + 4.0 * body) * density + flow)
        + sin(q.y * 5.5 * density - flow * 0.7)
        + sin((q.x + q.y) * 4.5 * density + flow * 1.3)
        + sin(radius * (14.0 + 7.0 * bend) * density - flow * 2.0);
}
"""
}
