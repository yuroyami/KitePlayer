package io.github.yuroyami.kiteplayer.audioviz.viz

import androidx.compose.ui.graphics.Color
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * A colour described the way the eye reads it, rather than the way a screen makes it.
 *
 * Red, green and blue say how to build a colour out of three lamps. They say nothing about how it
 * looks. Two colours with the same numeric brightness can look very different, and walking the hue
 * round the circle in the usual way makes the colour visibly pulse: yellow comes out glaring and
 * blue comes out muddy, at identical settings.
 *
 * This space is built from measurements of what people actually see. Equal steps look equal, so
 * a hue that cycles keeps its brightness, and two palettes set to the same lightness really are
 * as light as each other.
 *
 * [lightness] runs 0 to 1. [chroma] is how colourful, where 0 is grey and about 0.37 is as far as
 * a screen can go. [hue] is in degrees.
 */
internal data class Oklch(val lightness: Float, val chroma: Float, val hue: Float) {

    fun toColor(alpha: Float = 1f): Color = colourOf(lightness, chroma, hue, alpha)

    fun blend(to: Oklch, amount: Float): Oklch {
        val mix = amount.coerceIn(0f, 1f)
        // Round whichever way is shorter, so a ramp between two hues never takes the long way.
        var turn = to.hue - hue
        while (turn > 180f) turn -= 360f
        while (turn < -180f) turn += 360f
        return Oklch(
            lightness + (to.lightness - lightness) * mix,
            chroma + (to.chroma - chroma) * mix,
            hue + turn * mix,
        )
    }

    companion object
}

/**
 * The conversion on its own, without an object to hold the three numbers.
 *
 * The drawings that colour every shape separately ask for thousands of colours a frame, and a
 * short lived object for each one is work the garbage collector then has to undo. This is the same
 * arithmetic with nothing allocated.
 */
internal fun colourOf(lightness: Float, chroma: Float, hue: Float, alpha: Float = 1f): Color {
    val radians = hue * DEGREES_TO_RADIANS
    val a = chroma * cos(radians)
    val b = chroma * sin(radians)

    // Into the three cone responses, cubed, which is where the perceptual part lives.
    val longCone = lightness + 0.3963377774f * a + 0.2158037573f * b
    val mediumCone = lightness - 0.1055613458f * a - 0.0638541728f * b
    val shortCone = lightness - 0.0894841775f * a - 1.2914855480f * b
    val l = longCone * longCone * longCone
    val m = mediumCone * mediumCone * mediumCone
    val s = shortCone * shortCone * shortCone

    val red = 4.0767416621f * l - 3.3077115913f * m + 0.2309699292f * s
    val green = -1.2684380046f * l + 2.6097574011f * m - 0.3413193965f * s
    val blue = -0.0041960863f * l - 0.7034186147f * m + 1.7076147010f * s

    return Color(gamma(red), gamma(green), gamma(blue), alpha)
}

/**
 * Linear light into the numbers a screen expects.
 *
 * Screens do not respond evenly to their input, so a value has to be bent to compensate. Doing it
 * properly needs a fractional power, one of the slowest things a processor does, three times per
 * colour. A thousand steps read between neighbours is indistinguishable and costs an addition.
 */
private fun gamma(value: Float): Float {
    val clamped = value.coerceIn(0f, 1f)
    val at = clamped * (GAMMA_STEPS - 1)
    val lower = at.toInt().coerceIn(0, GAMMA_STEPS - 2)
    return GAMMA[lower] + (GAMMA[lower + 1] - GAMMA[lower]) * (at - lower)
}

private const val DEGREES_TO_RADIANS = 0.017453292f

private const val GAMMA_STEPS = 1024

private val GAMMA = FloatArray(GAMMA_STEPS) { step ->
    val linear = step.toFloat() / (GAMMA_STEPS - 1)
    if (linear <= 0.0031308f) linear * 12.92f else 1.055f * linear.pow(1f / 2.4f) - 0.055f
}
