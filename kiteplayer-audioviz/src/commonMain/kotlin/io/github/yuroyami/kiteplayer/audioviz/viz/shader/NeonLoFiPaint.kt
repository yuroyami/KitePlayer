package io.github.yuroyami.kiteplayer.audioviz.viz.shader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.lift
import kotlin.math.*

/** Linear palette roles and one exposure/tone transfer shared by CPU and shader. */
internal class NeonLoFiPaint {
    val roles = FloatArray(15)
    var exposure = 1f; private set
    fun prepare(state: VizRenderState, world: NeonLoFiWorld) {
        exposure = world.controls[3] * state.lift
        val p = state.palette
        put(0, p.low, world.controls[12]); put(1, p.mid, world.controls[12])
        put(2, p.high, world.controls[12]); put(3, p.cap, world.controls[12]); put(4, p.background, 1f)
    }
    private fun put(role: Int, color: Color, vivid: Float) {
        val r = decode(color.red); val g = decode(color.green); val b = decode(color.blue)
        val grey = 0.2126f * r + 0.7152f * g + 0.0722f * b
        roles[role * 3] = max(0f, grey + (r - grey) * vivid)
        roles[role * 3 + 1] = max(0f, grey + (g - grey) * vivid)
        roles[role * 3 + 2] = max(0f, grey + (b - grey) * vivid)
    }
    fun color(role: Int, power: Float, alpha: Float = 1f): Color = mix(role, role, 0f, power, alpha)
    fun mix(a: Int, b: Int, t: Float, power: Float, alpha: Float = 1f): Color {
        fun channel(c: Int): Float = encode((roles[a * 3 + c] * (1f - t) + roles[b * 3 + c] * t) * power * exposure)
        return Color(channel(0), channel(1), channel(2), alpha.coerceIn(0f, 1f))
    }
    fun packed(role: Int, power: Float): Int = color(role, power).toArgb()
    companion object {
        fun decode(x: Float): Float = if (x <= 0.04045f) x / 12.92f else ((x + 0.055f) / 1.055f).pow(2.4f)
        // Reinhard in linear light, followed once by the sRGB display transfer.
        fun encode(linear: Float): Float {
            val t = max(0f, linear) / (1f + max(0f, linear))
            return if (t <= 0.0031308f) 12.92f * t else 1.055f * t.pow(1f / 2.4f) - 0.055f
        }
    }
}

internal class NeonLoFiView {
    var width = 1f; private set
    var height = 1f; private set
    val sun = FloatArray(3)
    fun prepare(width: Float, height: Float, world: NeonLoFiWorld) {
        this.width = width; this.height = height
        val aspect = width / height
        val radius = min(0.0877f * world.controls[7] * (1f + 0.04f * world.slowLevel + 0.02f * world.release), aspect * 0.37f)
        val x = 0f; val y = -0.175f
        val c = cos(world.flight.bank); val s = sin(world.flight.bank)
        // Fit the actual rotated circle, including the maximum size control and narrow viewports.
        val sx = (x * c - y * s).coerceIn(-aspect * 0.5f + radius + aspect * 0.05f, aspect * 0.5f - radius - aspect * 0.05f)
        val sy = (x * s + y * c).coerceIn(-NeonLoFiFlight.HORIZON + radius + 0.035f, -radius * 0.25f)
        sun[0] = sx * c + sy * s
        sun[1] = -sx * s + sy * c
        sun[2] = radius
    }
}
