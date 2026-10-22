package io.github.yuroyami.kiteplayer.output

import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A small drawing in the style the networks were trained on: flat colour, one smooth boundary and
 * dark outlines, anti-aliased. [drawing] is the picture at twice the size, and [picture] is that
 * picture halved by averaging each 2x2 block, so an upscaler can be judged by how close it gets
 * back to [drawing].
 *
 * Every value is a whole 8-bit level, as a decoded frame's would be.
 */
internal class Anime4kFixture(val width: Int = 64, val height: Int = 48) {
    val drawing: FloatArray = draw(width * 2, height * 2)
    val picture: FloatArray = halve(drawing, width * 2, height * 2)

    private fun draw(w: Int, h: Int): FloatArray {
        val out = FloatArray(w * h * 4)
        val samples = 4
        for (y in 0 until h) {
            for (x in 0 until w) {
                val sum = FloatArray(3)
                for (sy in 0 until samples) {
                    for (sx in 0 until samples) {
                        val colour = colourAt(
                            (x + (sx + 0.5f) / samples) * 128f / w,
                            (y + (sy + 0.5f) / samples) * 96f / h,
                        )
                        for (c in 0 until 3) sum[c] += colour[c]
                    }
                }
                val at = (y * w + x) * 4
                for (c in 0 until 3) out[at + c] = level(sum[c] / (samples * samples))
                out[at + 3] = 1f
            }
        }
        return out
    }

    /** The scene on a 128 by 96 canvas, row 0 at the top. */
    private fun colourAt(x: Float, y: Float): FloatArray {
        val ink = floatArrayOf(0.08f, 0.06f, 0.1f)
        val horizon = 60f + 8f * sin(x / 12f)
        var colour = if (y < horizon) floatArrayOf(0.55f, 0.75f, 0.95f) else floatArrayOf(0.4f, 0.7f, 0.3f)
        val face = hypot(x - 44f, y - 40f)
        if (face < 22f) colour = floatArrayOf(0.98f, 0.85f, 0.7f)
        if (x in 80f..110f && y in 50f..70f) colour = floatArrayOf(0.9f, 0.2f, 0.2f)
        if (kotlin.math.abs(face - 22f) < 1.25f) return ink
        val onBox = (x in 79f..111f && y in 49f..71f) && !(x in 81f..109f && y in 51f..69f)
        if (onBox) return ink
        // The line from (70, 10) to (120, 80), two units wide.
        val t = (((x - 70f) * 50f + (y - 10f) * 70f) / (50f * 50f + 70f * 70f)).coerceIn(0f, 1f)
        if (hypot(x - (70f + 50f * t), y - (10f + 70f * t)) < 1f) return ink
        return colour
    }

    private fun halve(source: FloatArray, w: Int, h: Int): FloatArray {
        val out = FloatArray((w / 2) * (h / 2) * 4)
        for (y in 0 until h / 2) {
            for (x in 0 until w / 2) {
                val at = (y * (w / 2) + x) * 4
                for (c in 0 until 3) {
                    var sum = 0f
                    for (dy in 0..1) for (dx in 0..1) sum += source[((2 * y + dy) * w + 2 * x + dx) * 4 + c]
                    out[at + c] = level(sum / 4f)
                }
                out[at + 3] = 1f
            }
        }
        return out
    }

    private fun level(value: Float): Float = (value.coerceIn(0f, 1f) * 255f).roundToInt() / 255f
}
