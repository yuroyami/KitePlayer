package io.github.yuroyami.kiteplayer.audioviz.viz

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import kotlin.math.floor

/**
 * The colours a drawing works in.
 *
 * Each palette is three stops through a colour space built on what the eye actually sees, plus a
 * ground to sit on and a bright marker. Working that way rather than in red, green and blue keeps a
 * drawing that cycles its colour from pulsing: walking hue round the circle in the usual way makes
 * the picture pulse, because yellow at a given setting is far lighter than blue at the same
 * setting. Here a full turn keeps its brightness the whole way round.
 *
 * [low] to [high] runs from the bottom of a bar to the top, so a loud bar reaches the hot end of
 * the ramp. [cap] is the falling marker and [background] is what sits behind everything.
 *
 * [baseHue] and [hueSpan] are for the drawings that cycle colour over time. Staying inside a
 * palette's own span is what keeps a rotating hue looking like the same palette rather than like
 * a rainbow that wandered in.
 *
 * Twelve are built in. [fromColors] and [fromImage] build new ones, for example from an album cover.
 */
@Immutable
public class VizPalette internal constructor(
    public val name: String,
    private val lowStop: Oklch,
    private val midStop: Oklch,
    private val highStop: Oklch,
    private val capStop: Oklch,
    private val groundStop: Oklch,
    public val baseHue: Float,
    public val hueSpan: Float,
) {
    public val low: Color = lowStop.toColor()
    public val mid: Color = midStop.toColor()
    public val high: Color = highStop.toColor()
    public val cap: Color = capStop.toColor()
    public val background: Color = groundStop.toColor()

    /**
     * The ramp read at [position], 0 at [low] and 1 at [high].
     *
     * Baked once into a table rather than mixed on every call, because the drawings that use it
     * ask for a colour per bar, per ring or per row, and the conversion is not free.
     */
    public fun ramp(position: Float): Color {
        val at = position.coerceIn(0f, 1f) * (RAMP_STEPS - 1)
        return table[at.toInt().coerceIn(0, RAMP_STEPS - 1)]
    }

    /**
     * A colour from this palette's own span. [position] wraps, so feeding it a rising number
     * cycles forever.
     *
     * [value] sets how light it is and [saturation] how colourful. Two colours asked for at the
     * same [value] look equally bright.
     */
    public fun cycled(
        position: Float,
        saturation: Float = 0.85f,
        value: Float = 1f,
        alpha: Float = 1f,
    ): Color {
        val wrapped = position - floor(position)
        return colourOf(
            lightness = (0.24f + 0.64f * value).coerceIn(0f, 1f),
            chroma = saturation.coerceIn(0f, 1f) * MOST_CHROMA,
            hue = baseHue + wrapped * hueSpan,
            alpha = alpha,
        )
    }

    private val table: Array<Color> = Array(RAMP_STEPS) { step ->
        val at = step.toFloat() / (RAMP_STEPS - 1)
        if (at < 0.5f) {
            lowStop.blend(midStop, at * 2f).toColor()
        } else {
            midStop.blend(highStop, (at - 0.5f) * 2f).toColor()
        }
    }

    /** This palette moved [amount] of the way towards [other], for a change that fades rather than cuts. */
    internal fun mixedWith(other: VizPalette, amount: Float): VizPalette {
        val mix = amount.coerceIn(0f, 1f)
        var turn = other.baseHue - baseHue
        while (turn > 180f) turn -= 360f
        while (turn < -180f) turn += 360f
        return VizPalette(
            other.name,
            lowStop.blend(other.lowStop, mix),
            midStop.blend(other.midStop, mix),
            highStop.blend(other.highStop, mix),
            capStop.blend(other.capStop, mix),
            groundStop.blend(other.groundStop, mix),
            baseHue = baseHue + turn * mix,
            hueSpan = hueSpan + (other.hueSpan - hueSpan) * mix,
        )
    }

    /** The same palette with every hue turned by [degrees], used to lean it towards the music's key. */
    internal fun turned(degrees: Float): VizPalette = if (degrees == 0f) this else VizPalette(
        name,
        lowStop.copy(hue = lowStop.hue + degrees),
        midStop.copy(hue = midStop.hue + degrees),
        highStop.copy(hue = highStop.hue + degrees),
        capStop.copy(hue = capStop.hue + degrees),
        groundStop.copy(hue = groundStop.hue + degrees),
        baseHue = baseHue + degrees,
        hueSpan = hueSpan,
    )

    override fun toString(): String = name

    public companion object {
        /** The blue and cyan ramp of the players everyone remembers. */
        public val Classic: VizPalette = VizPalette(
            "Classic",
            Oklch(0.30f, 0.12f, 264f),
            Oklch(0.60f, 0.15f, 245f),
            Oklch(0.88f, 0.10f, 212f),
            Oklch(0.98f, 0.01f, 240f),
            Oklch(0.11f, 0.03f, 264f),
            baseHue = 245f,
            hueSpan = 60f,
        )

        /** Green through amber to red, the way a level meter warns you. */
        public val Fire: VizPalette = VizPalette(
            "Fire",
            Oklch(0.44f, 0.13f, 145f),
            Oklch(0.78f, 0.16f, 95f),
            Oklch(0.64f, 0.21f, 32f),
            Oklch(0.95f, 0.05f, 90f),
            Oklch(0.10f, 0.02f, 40f),
            baseHue = 40f,
            hueSpan = 60f,
        )

        /** Deep violet into pink, for the slower drifting drawings. */
        public val Ambience: VizPalette = VizPalette(
            "Ambience",
            Oklch(0.32f, 0.15f, 300f),
            Oklch(0.56f, 0.19f, 330f),
            Oklch(0.80f, 0.15f, 350f),
            Oklch(0.95f, 0.04f, 340f),
            Oklch(0.10f, 0.03f, 300f),
            baseHue = 300f,
            hueSpan = 70f,
        )

        /** The whole circle. Loud, and the right answer for anything that rotates. */
        public val Prism: VizPalette = VizPalette(
            "Prism",
            Oklch(0.38f, 0.17f, 270f),
            Oklch(0.70f, 0.18f, 145f),
            Oklch(0.86f, 0.16f, 100f),
            Oklch(0.99f, 0.00f, 0f),
            Oklch(0.10f, 0.01f, 0f),
            baseHue = 0f,
            hueSpan = 360f,
        )

        /** Teal into pink. Cold at the bottom, hot at the top, nothing in the middle. */
        public val Vapor: VizPalette = VizPalette(
            "Vapor",
            Oklch(0.38f, 0.11f, 195f),
            Oklch(0.65f, 0.14f, 252f),
            Oklch(0.82f, 0.16f, 340f),
            Oklch(0.95f, 0.05f, 330f),
            Oklch(0.11f, 0.03f, 240f),
            baseHue = 195f,
            hueSpan = 150f,
        )

        /** Almost nothing, then coal, then flame. For the drawings that should look hot. */
        public val Ember: VizPalette = VizPalette(
            "Ember",
            Oklch(0.26f, 0.07f, 40f),
            Oklch(0.56f, 0.17f, 50f),
            Oklch(0.86f, 0.14f, 78f),
            Oklch(0.96f, 0.06f, 70f),
            Oklch(0.08f, 0.02f, 40f),
            baseHue = 30f,
            hueSpan = 55f,
        )

        /** Navy into white blue. The quietest palette here, and the right one for a slow track. */
        public val Ice: VizPalette = VizPalette(
            "Ice",
            Oklch(0.32f, 0.10f, 265f),
            Oklch(0.66f, 0.09f, 232f),
            Oklch(0.93f, 0.04f, 215f),
            Oklch(1.00f, 0.00f, 220f),
            Oklch(0.11f, 0.03f, 265f),
            baseHue = 222f,
            hueSpan = 50f,
        )

        /** Green into magenta, which is a jump no daylight ever makes. Deliberately synthetic. */
        public val Acid: VizPalette = VizPalette(
            "Acid",
            Oklch(0.48f, 0.19f, 140f),
            Oklch(0.72f, 0.20f, 112f),
            Oklch(0.68f, 0.24f, 330f),
            Oklch(0.95f, 0.08f, 320f),
            Oklch(0.09f, 0.02f, 150f),
            baseHue = 118f,
            hueSpan = 215f,
        )

        /** One hue and nothing else. Everything is said with lightness, which is oddly restful. */
        public val Mono: VizPalette = VizPalette(
            "Mono",
            Oklch(0.28f, 0.03f, 250f),
            Oklch(0.58f, 0.03f, 250f),
            Oklch(0.92f, 0.02f, 250f),
            Oklch(1.00f, 0.00f, 250f),
            Oklch(0.09f, 0.01f, 250f),
            baseHue = 250f,
            hueSpan = 0f,
        )

        /** The amber of a street light at night. Warm, narrow and slightly sad. */
        public val Sodium: VizPalette = VizPalette(
            "Sodium",
            Oklch(0.32f, 0.09f, 70f),
            Oklch(0.66f, 0.14f, 80f),
            Oklch(0.90f, 0.10f, 92f),
            Oklch(0.97f, 0.05f, 85f),
            Oklch(0.09f, 0.02f, 70f),
            baseHue = 68f,
            hueSpan = 26f,
        )

        /** Violet into electric blue. Everything drawn in it looks like it is glowing from inside. */
        public val Ultraviolet: VizPalette = VizPalette(
            "Ultraviolet",
            Oklch(0.30f, 0.16f, 306f),
            Oklch(0.54f, 0.20f, 290f),
            Oklch(0.78f, 0.16f, 266f),
            Oklch(0.94f, 0.08f, 280f),
            Oklch(0.09f, 0.04f, 300f),
            baseHue = 300f,
            hueSpan = 60f,
        )

        /** Purple, red and gold, in that order, which is the sky doing it. */
        public val Sunset: VizPalette = VizPalette(
            "Sunset",
            Oklch(0.34f, 0.15f, 320f),
            Oklch(0.60f, 0.20f, 25f),
            Oklch(0.86f, 0.15f, 85f),
            Oklch(0.96f, 0.06f, 60f),
            Oklch(0.10f, 0.03f, 320f),
            baseHue = 318f,
            hueSpan = 130f,
        )

        /** Every built-in palette, in the order a player cycles through them. */
        public val entries: List<VizPalette> = listOf(Classic, Fire, Ambience, Prism, Vapor, Ember, Ice, Acid, Mono, Sodium, Ultraviolet, Sunset)

        /**
         * A palette built from a handful of colours, in any order.
         *
         * They are sorted dark to light and the darkest, middle and lightest become the ramp. The
         * ground is a very dark version of the darkest and the marker a near white version of the
         * lightest. The cycling span covers the hues actually present, so a palette built from a
         * blue and orange cover cycles between blue and orange rather than round the whole circle.
         */
        public fun fromColors(name: String, colors: List<Color>): VizPalette = paletteFromColors(name, colors)

        /**
         * A palette taken from a picture: its main colours, found by grouping similar pixels.
         *
         * Meant for album covers. The picture can be any size: only a grid of a few thousand
         * pixels is read.
         */
        public fun fromImage(name: String, image: ImageBitmap): VizPalette =
            paletteFromColors(name, dominantColors(image))
    }
}

/**
 * Kept outside the class so the ramp table each palette bakes can read it while the palette is
 * still being built.
 */
private const val RAMP_STEPS = 256

/** As colourful as anything here gets. Beyond this a screen starts clipping. */
private const val MOST_CHROMA = 0.17f
