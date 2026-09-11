package io.github.yuroyami.kiteplayer.audioviz.viz

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.pow
import kotlin.math.sqrt

/** A colour as the eye reads it, in the rectangular form: lightness, and two colour axes. */
internal data class Oklab(val lightness: Float, val a: Float, val b: Float) {
    val chroma: Float get() = sqrt(a * a + b * b)

    val hue: Float get() = ((atan2(b, a) * 57.29578f) + 360f) % 360f

    fun distanceTo(other: Oklab): Float {
        val dl = lightness - other.lightness
        val da = a - other.a
        val db = b - other.b
        return sqrt(dl * dl + da * da + db * db)
    }

    fun toOklch(): Oklch = Oklch(lightness, chroma, hue)
}

/** The colour a screen shows, turned into how it looks. The reverse of [colourOf]. */
internal fun Color.toOklab(): Oklab {
    fun linear(channel: Float): Float =
        if (channel <= 0.04045f) channel / 12.92f else ((channel + 0.055f) / 1.055f).pow(2.4f)
    val r = linear(red)
    val g = linear(green)
    val b = linear(blue)
    val long = cbrt((0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b).toDouble()).toFloat()
    val medium = cbrt((0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b).toDouble()).toFloat()
    val short = cbrt((0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b).toDouble()).toFloat()
    return Oklab(
        lightness = 0.2104542553f * long + 0.7936177850f * medium - 0.0040720468f * short,
        a = 1.9779984951f * long - 2.4285922050f * medium + 0.4505937099f * short,
        b = 0.0259040371f * long + 0.7827717662f * medium - 0.8086757660f * short,
    )
}

/**
 * The main colours of a picture, darkest first.
 *
 * A grid of a few thousand pixels is read and grouped into [count] groups of similar colour, in
 * the space the eye uses, so two colours a person would call the same land in the same group.
 * Each group's average is one answer. The groups start spread from dark to light, which makes the
 * result the same every time for the same picture.
 */
internal fun dominantColors(image: ImageBitmap, count: Int = 5): List<Color> {
    val pixels = image.toPixelMap()
    val stepX = (image.width / GRID).coerceAtLeast(1)
    val stepY = (image.height / GRID).coerceAtLeast(1)
    val samples = ArrayList<Oklab>()
    var y = 0
    while (y < image.height) {
        var x = 0
        while (x < image.width) {
            val colour = pixels[x, y]
            if (colour.alpha > 0.5f) samples += colour.toOklab()
            x += stepX
        }
        y += stepY
    }
    if (samples.isEmpty()) return listOf(Color.Black)

    val groups = count.coerceIn(1, samples.size)
    val byLightness = samples.sortedBy { it.lightness }
    val centres = Array(groups) { byLightness[(((it + 0.5f) / groups) * byLightness.size).toInt().coerceIn(0, byLightness.size - 1)] }
    val owner = IntArray(samples.size)
    repeat(ROUNDS) {
        for (index in samples.indices) {
            var best = 0
            var bestDistance = Float.MAX_VALUE
            for (group in 0 until groups) {
                val distance = samples[index].distanceTo(centres[group])
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = group
                }
            }
            owner[index] = best
        }
        for (group in 0 until groups) {
            var l = 0f
            var a = 0f
            var b = 0f
            var members = 0
            for (index in samples.indices) {
                if (owner[index] != group) continue
                l += samples[index].lightness
                a += samples[index].a
                b += samples[index].b
                members++
            }
            // A group nobody joined keeps where it was rather than collapsing to nothing.
            if (members > 0) centres[group] = Oklab(l / members, a / members, b / members)
        }
    }
    return centres.sortedBy { it.lightness }.map { it.toOklch().toColor() }
}

/** Turns a handful of colours into a palette. See [VizPalette.fromColors]. */
internal fun paletteFromColors(name: String, colors: List<Color>): VizPalette {
    require(colors.isNotEmpty()) { "a palette needs at least one colour" }
    val sorted = colors.map { it.toOklab() }.sortedBy { it.lightness }

    fun stop(from: Oklab, lightest: Float, darkest: Float): Oklch =
        Oklch(from.lightness.coerceIn(darkest, lightest), from.chroma.coerceAtMost(0.2f), from.hue)

    // Each stop is held inside its own band of lightness, so the ramp always climbs from dark to
    // light even when the picture is all one brightness.
    val low = stop(sorted.first(), lightest = 0.45f, darkest = 0.22f)
    val mid = stop(sorted[sorted.size / 2], lightest = 0.72f, darkest = 0.46f)
    val high = stop(sorted.last(), lightest = 0.92f, darkest = 0.73f)
    val cap = Oklch(0.96f, high.chroma * 0.35f, high.hue)
    val ground = Oklch(0.09f, (low.chroma * 0.4f).coerceAtMost(0.04f), low.hue)

    val colourful = sorted.filter { it.chroma > 0.03f }.map { it.hue }
    val (start, span) = hueArc(colourful)
    return VizPalette(name, low, mid, high, cap, ground, baseHue = start, hueSpan = span.coerceIn(30f, 180f))
}

/**
 * The shortest stretch of the colour circle that holds every hue given, as a start and a width.
 *
 * Found by looking for the widest gap between neighbouring hues: the stretch is everything else.
 */
private fun hueArc(hues: List<Float>): Pair<Float, Float> {
    if (hues.isEmpty()) return 0f to 60f
    if (hues.size == 1) return (hues[0] - 20f + 360f) % 360f to 40f
    val sorted = hues.sorted()
    var widestGap = 0f
    var gapEnd = sorted[0]
    for (index in sorted.indices) {
        val here = sorted[index]
        val next = if (index + 1 < sorted.size) sorted[index + 1] else sorted[0] + 360f
        val gap = next - here
        if (gap > widestGap) {
            widestGap = gap
            gapEnd = next % 360f
        }
    }
    return gapEnd to (360f - widestGap)
}

/** How many pixels a side of the reading grid has. A few thousand pixels is plenty. */
private const val GRID = 64

/** How many times the groups are refined. They settle well before this. */
private const val ROUNDS = 12
