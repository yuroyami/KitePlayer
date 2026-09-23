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

/**
 * The most colourful a screen can show at this [lightness] and [hue], as Oklch chroma.
 *
 * The converter above clamps a channel that a screen cannot show, and clamping bends the hue: a
 * blue asked for beyond the screen's range comes back as a different, greyer blue. The drawings
 * want the opposite, the strongest colour that is still the colour asked for. That limit depends
 * on both lightness and hue (a screen shows a far stronger blue at low lightness than a yellow, and
 * the other way round near white), so it is searched once per cell of a small table and read back
 * between neighbours.
 */
internal fun mostChroma(lightness: Float, hue: Float): Float {
    val l = lightness.coerceIn(0f, 1f) * (CHROMA_LIGHTNESS_STEPS - 1)
    var h = hue % 360f
    if (h < 0f) h += 360f
    val hIndex = h / 360f * CHROMA_HUE_STEPS
    val l0 = l.toInt().coerceIn(0, CHROMA_LIGHTNESS_STEPS - 2)
    val h0 = hIndex.toInt().coerceIn(0, CHROMA_HUE_STEPS - 1)
    val h1 = (h0 + 1) % CHROMA_HUE_STEPS
    // The least of the four neighbours rather than a blend of them: the limit bends between cells,
    // and a blend can land a little outside the screen, which is exactly the clipping this avoids.
    return minOf(
        minOf(CHROMA_LIMIT[l0 * CHROMA_HUE_STEPS + h0], CHROMA_LIMIT[l0 * CHROMA_HUE_STEPS + h1]),
        minOf(CHROMA_LIMIT[(l0 + 1) * CHROMA_HUE_STEPS + h0], CHROMA_LIMIT[(l0 + 1) * CHROMA_HUE_STEPS + h1]),
    )
}

/**
 * The lightness at which [hue] reaches the most chroma a screen can show, its cusp.
 *
 * A screen's blue is strongest well below mid lightness and its yellow just under white, so a
 * line drawn at one lightness for every hue comes out pastel for some and muddy for others. A
 * foreground colour reads at full strength when its lightness sits near this point.
 */
internal fun cuspLightness(hue: Float): Float {
    var h = hue % 360f
    if (h < 0f) h += 360f
    val hIndex = h / 360f * CHROMA_HUE_STEPS
    val h0 = hIndex.toInt().coerceIn(0, CHROMA_HUE_STEPS - 1)
    val h1 = (h0 + 1) % CHROMA_HUE_STEPS
    val mix = hIndex - h0
    return CUSP[h0] * (1f - mix) + CUSP[h1] * mix
}

/**
 * [hue] at its cusp lightness moved by [lift], at the strongest chroma the screen shows there.
 *
 * The lightness is pulled back toward the cusp until the chroma is at least 80 percent of the
 * hue's peak, so a lift that would leave a narrow hue such as yellow nearly white is refused. Lines
 * read well at a lift of 0 to +0.1 and fills at -0.1 to 0. For a colour that must be dark or
 * near white, call [colourOf] with an explicit lightness instead.
 */
internal fun vividColour(hue: Float, lift: Float = 0f, alpha: Float = 1f, headroom: Float = 0.03f): Color {
    val cusp = cuspLightness(hue)
    val lightness = vividLightness(hue, cusp, (cusp + lift).coerceIn(0f, 1f))
    return colourOf(lightness, (mostChroma(lightness, hue) - headroom).coerceAtLeast(0f), hue, alpha)
}

/** [wanted] pulled toward [cusp] until the chroma there is at least 80 percent of the peak. */
internal fun vividLightness(hue: Float, cusp: Float, wanted: Float): Float {
    val floor = mostChroma(cusp, hue) * VIVID_FLOOR
    var lightness = wanted
    var steps = 0
    while (mostChroma(lightness, hue) < floor && steps < 40) {
        lightness += (cusp - lightness).coerceIn(-0.005f, 0.005f)
        steps++
    }
    return lightness
}

private const val VIVID_FLOOR = 0.8f

/** Whether every linear channel of this colour is one a screen can show. */
internal fun inGamut(lightness: Float, chroma: Float, hue: Float): Boolean {
    val radians = hue * DEGREES_TO_RADIANS
    val a = chroma * cos(radians)
    val b = chroma * sin(radians)
    val longCone = lightness + 0.3963377774f * a + 0.2158037573f * b
    val mediumCone = lightness - 0.1055613458f * a - 0.0638541728f * b
    val shortCone = lightness - 0.0894841775f * a - 1.2914855480f * b
    val l = longCone * longCone * longCone
    val m = mediumCone * mediumCone * mediumCone
    val s = shortCone * shortCone * shortCone
    val red = 4.0767416621f * l - 3.3077115913f * m + 0.2309699292f * s
    val green = -1.2684380046f * l + 2.6097574011f * m - 0.3413193965f * s
    val blue = -0.0041960863f * l - 0.7034186147f * m + 1.7076147010f * s
    return red in -GAMUT_SLACK..1f + GAMUT_SLACK && green in -GAMUT_SLACK..1f + GAMUT_SLACK && blue in -GAMUT_SLACK..1f + GAMUT_SLACK
}

private const val GAMUT_SLACK = 0.002f

private const val CHROMA_LIGHTNESS_STEPS = 65

private const val CHROMA_HUE_STEPS = 360

/** The lightness of the most colourful cell of every hue column, read from the table below. */
private val CUSP: FloatArray by lazy {
    FloatArray(CHROMA_HUE_STEPS) { hueStep ->
        var best = 0
        for (lightStep in 0 until CHROMA_LIGHTNESS_STEPS) {
            if (CHROMA_LIMIT[lightStep * CHROMA_HUE_STEPS + hueStep] > CHROMA_LIMIT[best * CHROMA_HUE_STEPS + hueStep]) best = lightStep
        }
        best.toFloat() / (CHROMA_LIGHTNESS_STEPS - 1)
    }
}

/** The limit for every cell, found by halving the interval a dozen times. Built once, on first use, in a few milliseconds. */
private val CHROMA_LIMIT: FloatArray by lazy {
    FloatArray(CHROMA_LIGHTNESS_STEPS * CHROMA_HUE_STEPS) { cell ->
        val lightness = (cell / CHROMA_HUE_STEPS).toFloat() / (CHROMA_LIGHTNESS_STEPS - 1)
        val hue = (cell % CHROMA_HUE_STEPS).toFloat() / CHROMA_HUE_STEPS * 360f
        var lower = 0f
        var upper = 0.4f
        repeat(14) {
            val middle = (lower + upper) * 0.5f
            if (inGamut(lightness, middle, hue)) lower = middle else upper = middle
        }
        lower
    }
}

