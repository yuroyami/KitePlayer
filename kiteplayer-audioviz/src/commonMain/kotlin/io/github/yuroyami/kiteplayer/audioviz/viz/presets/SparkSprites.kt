package io.github.yuroyami.kiteplayer.audioviz.viz.presets

import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Up to [capacity] soft round sprites, drawn together in one textured triangle call and added onto
 * the canvas.
 *
 * Each sprite is a square of two triangles that samples one shared texture: white, with an alpha
 * of `(1 - d)^3` at distance `d` from the middle, where `d` is 1 at the edge of the inscribed
 * circle. That is the fragment shader of a WebGL point sprite, evaluated once per texel, so a
 * sprite of any size keeps the falloff. Each corner's colour multiplies the texture.
 *
 * A slot that is not drawn this call is [hide]n: its square collapses to a point and draws
 * nothing. The arrays keep their full size, because the desktop's triangle call counts corners
 * from the length of the array.
 */
internal class SparkSprites(val capacity: Int) {
    init {
        // Corners are numbered in sixteen bits.
        require(capacity in 1..8_191) { "capacity must be 1..8191, was $capacity" }
    }

    /** Four corners a sprite, x then y, in pixels. */
    val positions: FloatArray = FloatArray(capacity * 8)

    /** The same corners in texture pixels: the whole texture on every sprite. */
    val texCoords: FloatArray = FloatArray(capacity * 8)

    /** One non-premultiplied ARGB colour a corner. */
    val colors: IntArray = IntArray(capacity * 4)

    /** Two triangles a sprite. */
    val indices: ShortArray = ShortArray(capacity * 6)

    init {
        val size = SPARK_TEXTURE_SIZE.toFloat()
        for (slot in 0 until capacity) {
            val at = slot * 8
            texCoords[at] = 0f
            texCoords[at + 1] = 0f
            texCoords[at + 2] = size
            texCoords[at + 3] = 0f
            texCoords[at + 4] = size
            texCoords[at + 5] = size
            texCoords[at + 6] = 0f
            texCoords[at + 7] = size
            val corner = slot * 4
            val index = slot * 6
            indices[index] = corner.toShort()
            indices[index + 1] = (corner + 1).toShort()
            indices[index + 2] = (corner + 2).toShort()
            indices[index + 3] = corner.toShort()
            indices[index + 4] = (corner + 2).toShort()
            indices[index + 5] = (corner + 3).toShort()
        }
    }

    /** Places sprite [slot] with its middle at [x], [y], [half] pixels to each side, in [argb]. */
    fun put(slot: Int, x: Float, y: Float, half: Float, argb: Int) {
        val at = slot * 8
        positions[at] = x - half
        positions[at + 1] = y - half
        positions[at + 2] = x + half
        positions[at + 3] = y - half
        positions[at + 4] = x + half
        positions[at + 5] = y + half
        positions[at + 6] = x - half
        positions[at + 7] = y + half
        val corner = slot * 4
        colors[corner] = argb
        colors[corner + 1] = argb
        colors[corner + 2] = argb
        colors[corner + 3] = argb
    }

    /** Leaves sprite [slot] out of this call. */
    fun hide(slot: Int) {
        val at = slot * 8
        for (value in 0 until 8) positions[at + value] = 0f
        val corner = slot * 4
        colors[corner] = 0
        colors[corner + 1] = 0
        colors[corner + 2] = 0
        colors[corner + 3] = 0
    }
}

/** Adds every sprite of [sprites] onto the canvas in one call. */
internal expect fun DrawScope.drawSparkSprites(sprites: SparkSprites)

/** The sprite texture's side, in texels. */
internal const val SPARK_TEXTURE_SIZE: Int = 128

/**
 * The sprite texture's alpha, row by row, 0 to 255: `(1 - d)^3` at each texel's centre, and 0 past
 * the inscribed circle, where the WebGL shader discards the fragment.
 */
internal fun sparkTextureAlpha(): IntArray {
    val size = SPARK_TEXTURE_SIZE
    return IntArray(size * size) { index ->
        val u = ((index % size) + 0.5) / size
        val v = ((index / size) + 0.5) / size
        val dx = (u - 0.5) * 2.0
        val dy = (v - 0.5) * 2.0
        val d = sqrt(dx * dx + dy * dy)
        if (d > 1.0) 0 else ((1.0 - d).pow(3) * 255.0).roundToInt()
    }
}
