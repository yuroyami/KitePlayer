package io.github.yuroyami.kiteplayer

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The picture controls a viewer expects from a real player: brightness, contrast, saturation and
 * hue, live, without touching the decoder (mpv's `eq`). The engine owns the value and every
 * attached renderer is told it, exactly like [VideoScale]; the renderers apply it as one colour
 * matrix per drawn frame, which is why a change is instant and free of pipeline work.
 *
 * All four compose into a single affine colour transform, and [toColorMatrix] is the ONE place
 * that transform is written down, so no two renderers can disagree about what saturation means.
 *
 * Gamma is deliberately absent: it is not affine, so it cannot ride this matrix, and a control
 * honoured by some renderers and ignored by others would be worse than none (KPKMP 17.11).
 */
public data class VideoAdjustments(
    /** Additive lift, -1 to 1. 0 is neutral; positive lightens. */
    val brightness: Float = 0f,
    /** Scale about mid-grey, 0 to 2. 1 is neutral; 0 is flat grey. */
    val contrast: Float = 1f,
    /** Colourfulness, 0 to 2. 1 is neutral; 0 is greyscale by the BT.709 weights. */
    val saturation: Float = 1f,
    /** Rotation about the luma axis in degrees, -180 to 180. 0 is neutral. */
    val hueDegrees: Float = 0f,
) {
    /** True for the neutral value, which renderers use to skip the multiply entirely. */
    public val isIdentity: Boolean
        get() = brightness == 0f && contrast == 1f && saturation == 1f && hueDegrees == 0f

    /**
     * The whole adjustment as one 4x5 row-major colour matrix over non-premultiplied RGBA in the
     * UNIT domain: `r' = m[0]r + m[1]g + m[2]b + m[3]a + m[4]`, rows r, g, b, a. Offsets are in
     * 0..1; a consumer whose convention offsets in 0..255 (Android's ColorMatrix, Compose's)
     * multiplies the fifth column by 255 and changes nothing else.
     *
     * Order, applied to a pixel: saturation and hue about the luma axis first, then contrast
     * about mid-grey, then the brightness lift. Grey stays grey under saturation and hue alone,
     * because both are rotations and scalings AROUND the luma axis, never along it.
     */
    public fun toColorMatrix(): FloatArray {
        // Saturation: blend between the luma projection and identity.
        val s = saturation
        var rr = LUMA_R * (1f - s) + s; var rg = LUMA_G * (1f - s);      var rb = LUMA_B * (1f - s)
        var gr = LUMA_R * (1f - s);      var gg = LUMA_G * (1f - s) + s; var gb = LUMA_B * (1f - s)
        var br = LUMA_R * (1f - s);      var bg = LUMA_G * (1f - s);      var bb = LUMA_B * (1f - s) + s

        if (hueDegrees != 0f) {
            // Haeberli's luma-preserving hue rotation, composed onto the saturation matrix.
            val angle = (hueDegrees * PI / 180.0)
            val c = cos(angle).toFloat()
            val n = sin(angle).toFloat()
            val h00 = LUMA_R + c * (1f - LUMA_R) - n * LUMA_R
            val h01 = LUMA_G - c * LUMA_G - n * LUMA_G
            val h02 = LUMA_B - c * LUMA_B + n * (1f - LUMA_B)
            val h10 = LUMA_R - c * LUMA_R + n * 0.143f
            val h11 = LUMA_G + c * (1f - LUMA_G) + n * 0.140f
            val h12 = LUMA_B - c * LUMA_B - n * 0.283f
            val h20 = LUMA_R - c * LUMA_R - n * (1f - LUMA_R)
            val h21 = LUMA_G - c * LUMA_G + n * LUMA_G
            val h22 = LUMA_B + c * (1f - LUMA_B) + n * LUMA_B
            val nrr = h00 * rr + h01 * gr + h02 * br
            val nrg = h00 * rg + h01 * gg + h02 * bg
            val nrb = h00 * rb + h01 * gb + h02 * bb
            val ngr = h10 * rr + h11 * gr + h12 * br
            val ngg = h10 * rg + h11 * gg + h12 * bg
            val ngb = h10 * rb + h11 * gb + h12 * bb
            val nbr = h20 * rr + h21 * gr + h22 * br
            val nbg = h20 * rg + h21 * gg + h22 * bg
            val nbb = h20 * rb + h21 * gb + h22 * bb
            rr = nrr; rg = nrg; rb = nrb
            gr = ngr; gg = ngg; gb = ngb
            br = nbr; bg = nbg; bb = nbb
        }

        // Contrast about mid-grey, then the brightness lift, both on every colour row alike.
        val scale = contrast
        val offset = 0.5f * (1f - contrast) + brightness

        return floatArrayOf(
            rr * scale, rg * scale, rb * scale, 0f, offset,
            gr * scale, gg * scale, gb * scale, 0f, offset,
            br * scale, bg * scale, bb * scale, 0f, offset,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    public companion object {
        /** The neutral value every player starts at. */
        public val Identity: VideoAdjustments = VideoAdjustments()

        // BT.709 luma weights, the ones the rest of this engine's colour work uses.
        private const val LUMA_R = 0.2126f
        private const val LUMA_G = 0.7152f
        private const val LUMA_B = 0.0722f
    }
}
