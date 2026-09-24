package io.github.yuroyami.kiteplayer.audioviz.viz.shader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.colourOf
import io.github.yuroyami.kiteplayer.audioviz.viz.mostChroma
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Slew
import kotlin.math.*

/**
 * Linear colour roles and one exposure and display transfer shared by the processor and the shader.
 *
 * The default palette paints a warm dusk: an indigo sky over a rose horizon, a gold to coral sun,
 * cyan to magenta neon, amber lamps and pale gold windows. A chosen palette paints the same roles
 * with its own colours. The key turns the sky and the sun by twenty degrees at most.
 */
internal class NeonLoFiPaint {
    val roles = FloatArray(ROLES * 3)
    var exposure = 1f; private set
    private val keyTurn = Slew(maxPerSecond = 12f)
    private var paletteFor: VizPalette? = null
    private var turnFor = Float.NaN
    private var vividFor = Float.NaN

    fun prepare(state: VizRenderState, world: NeonLoFiWorld) {
        // Light follows how loud the music is, from a parked car's forty percent to full.
        val loud = state.frame.energy.coerceIn(0f, 1f).pow(0.6f)
        exposure = world.controls[3] * (IDLE_LIGHT + (1f - IDLE_LIGHT) * loud) * state.lightScale
        val frame = state.frame
        val sure = ((frame.keyConfidence - 0.6f) / 0.4f).coerceIn(0f, 1f)
        var towards = frame.keyHue * 360f - ROSE_HUE
        while (towards > 180f) towards -= 360f
        while (towards < -180f) towards += 360f
        keyTurn.advance(towards.coerceIn(-KEY_TURN, KEY_TURN) * sure, state.stepSeconds)
        val turn = round(keyTurn.value)
        val vivid = world.controls[12]
        val palette = state.palette
        if (palette === paletteFor && turn == turnFor && vivid == vividFor) return
        paletteFor = palette; turnFor = turn; vividFor = vivid
        if (palette.name == VizPalette.Prism.name) {
            put(LOW, swatch(0.20f, 0.11f, 285f + turn), vivid)
            put(MID, swatch(0.55f, 0.20f, ROSE_HUE + turn), vivid)
            put(HIGH, swatch(0.84f, 0.17f, 85f + turn), vivid)
            put(CAP, swatch(0.65f, 0.21f, 30f + turn), vivid)
            put(INK, swatch(0.08f, 0.02f, 285f), 1f)
            put(NEAR, swatch(0.86f, 0.14f, 205f), vivid)
            put(FAR, swatch(0.64f, 0.26f, 350f), vivid)
            put(LAMP, swatch(0.80f, 0.16f, 70f), vivid)
            put(WINDOW, swatch(0.93f, 0.08f, 88f), vivid)
        } else {
            put(LOW, palette.low, vivid); put(MID, palette.mid, vivid)
            put(HIGH, palette.high, vivid); put(CAP, palette.cap, vivid); put(INK, palette.background, 1f)
            put(NEAR, palette.high, vivid); put(FAR, palette.mid, vivid)
            put(LAMP, palette.cap, vivid); put(WINDOW, palette.high, vivid)
        }
        put(WHITE, Color.White, 1f)
    }

    /** A swatch at its lightness, at the most chroma the screen shows there, never above [chroma]. */
    private fun swatch(lightness: Float, chroma: Float, hue: Float): Color =
        colourOf(lightness, min(chroma, mostChroma(lightness, hue) - 0.004f).coerceAtLeast(0f), hue)

    private fun put(role: Int, color: Color, vivid: Float) {
        val r = decode(color.red); val g = decode(color.green); val b = decode(color.blue)
        val grey = 0.2126f * r + 0.7152f * g + 0.0722f * b
        roles[role * 3] = max(0f, grey + (r - grey) * vivid)
        roles[role * 3 + 1] = max(0f, grey + (g - grey) * vivid)
        roles[role * 3 + 2] = max(0f, grey + (b - grey) * vivid)
    }
    fun color(role: Int, power: Float, alpha: Float = 1f): Color = mix(role, role, 0f, power, alpha)
    fun mix(a: Int, b: Int, t: Float, power: Float, alpha: Float = 1f): Color {
        val scale = power * exposure
        val r = max(0f, (roles[a * 3] * (1f - t) + roles[b * 3] * t) * scale)
        val g = max(0f, (roles[a * 3 + 1] * (1f - t) + roles[b * 3 + 1] * t) * scale)
        val bl = max(0f, (roles[a * 3 + 2] * (1f - t) + roles[b * 3 + 2] * t) * scale)
        val roll = shoulder(max(r, max(g, bl)))
        return Color(transfer(r * roll), transfer(g * roll), transfer(bl * roll), alpha.coerceIn(0f, 1f))
    }
    fun packed(role: Int, power: Float): Int = color(role, power).toArgb()
    fun reset() { keyTurn.reset(); paletteFor = null }
    companion object {
        /** The roles: sky overhead, horizon glow, the sun's top and bottom, ground, near and far neon, lamps, windows and white. */
        const val LOW = 0; const val MID = 1; const val HIGH = 2; const val CAP = 3; const val INK = 4
        const val NEAR = 5; const val FAR = 6; const val LAMP = 7; const val WINDOW = 8; const val WHITE = 9
        const val ROLES = 10
        /** The share of full light a parked car's scene keeps in a silence. */
        const val IDLE_LIGHT = 0.4f
        /** Light above this rolls off smoothly to full, scaled on the brightest channel so the hue holds. */
        const val KNEE = 0.75f
        private const val ROSE_HUE = 355f
        private const val KEY_TURN = 20f
        fun decode(x: Float): Float = if (x <= 0.04045f) x / 12.92f else ((x + 0.055f) / 1.055f).pow(2.4f)
        /** The factor that rolls a colour whose brightest channel is [peak] under full light, hue kept. */
        fun shoulder(peak: Float): Float {
            if (peak <= KNEE) return 1f
            val rolled = KNEE + (1f - KNEE) * (1f - exp(-(peak - KNEE) / (1f - KNEE)))
            return rolled / peak
        }
        /** The sRGB display transfer of linear light already inside 0 to 1. */
        fun transfer(t: Float): Float {
            val c = t.coerceIn(0f, 1f)
            return if (c <= 0.0031308f) 12.92f * c else 1.055f * c.pow(1f / 2.4f) - 0.055f
        }
        /** One grey channel of linear light, through the shoulder and the display transfer. */
        fun encode(linear: Float): Float {
            val t = max(0f, linear)
            return transfer(t * shoulder(t))
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
        val radius = min(0.115f * world.controls[7] * (1f + 0.04f * world.slowLevel + 0.02f * world.release), aspect * 0.37f)
        val x = 0f; val y = -0.175f
        val c = cos(world.flight.bank); val s = sin(world.flight.bank)
        // Fit the actual rotated circle, including the maximum size control and narrow viewports.
        val sx = (x * c - y * s).coerceIn(-aspect * 0.5f + radius + aspect * 0.05f, aspect * 0.5f - radius - aspect * 0.05f)
        val sy = (x * s + y * c).coerceIn(-world.flight.horizon + radius + 0.035f, -radius * 0.25f)
        sun[0] = sx * c + sy * s
        sun[1] = -sx * s + sy * c
        sun[2] = radius
    }
}
