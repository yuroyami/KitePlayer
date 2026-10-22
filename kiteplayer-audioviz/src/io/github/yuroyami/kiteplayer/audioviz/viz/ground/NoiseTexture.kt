package io.github.yuroyami.kiteplayer.audioviz.viz.ground

import androidx.compose.ui.graphics.ImageBitmap
import io.github.yuroyami.kiteplayer.audioviz.viz.PixelImage

/** Smooth random values in a 256 pixel square that tiles without a seam. Built once, shared by every ground. */
internal val noiseTexture: ImageBitmap by lazy { buildNoise() }

private const val SIZE = 256

private fun buildNoise(): ImageBitmap {
    // Three octaves on lattices that divide the square, so the left edge meets the right one.
    val cells = intArrayOf(8, 16, 32)
    val weights = floatArrayOf(0.55f, 0.3f, 0.15f)
    val values = FloatArray(SIZE * SIZE)
    var low = Float.MAX_VALUE
    var high = -Float.MAX_VALUE
    for (y in 0 until SIZE) {
        for (x in 0 until SIZE) {
            var total = 0f
            for (octave in cells.indices) {
                total += latticeNoise(x, y, cells[octave], octave) * weights[octave]
            }
            values[y * SIZE + x] = total
            if (total < low) low = total
            if (total > high) high = total
        }
    }
    val picture = PixelImage(SIZE, SIZE)
    val span = (high - low).coerceAtLeast(1e-6f)
    for (index in values.indices) {
        val level = (((values[index] - low) / span) * 255f + 0.5f).toInt().coerceIn(0, 255)
        picture.pixels[index] = (0xFF shl 24) or (level shl 16) or (level shl 8) or level
    }
    picture.upload()
    return picture.image
}

/** Value noise with [cells] lattice points across the square, wrapping at the edges. */
private fun latticeNoise(x: Int, y: Int, cells: Int, octave: Int): Float {
    val step = SIZE.toFloat() / cells
    val gx = x / step
    val gy = y / step
    val left = gx.toInt()
    val top = gy.toInt()
    val fx = smooth(gx - left)
    val fy = smooth(gy - top)
    val right = (left + 1) % cells
    val bottom = (top + 1) % cells
    val a = hash(left, top, octave)
    val b = hash(right, top, octave)
    val c = hash(left, bottom, octave)
    val d = hash(right, bottom, octave)
    val upper = a + (b - a) * fx
    val lower = c + (d - c) * fx
    return upper + (lower - upper) * fy
}

private fun smooth(t: Float): Float = t * t * (3f - 2f * t)

private fun hash(x: Int, y: Int, octave: Int): Float {
    var h = x * 374_761_393 + y * 668_265_263 + octave * 1_274_126_177
    h = (h xor (h ushr 13)) * 1_274_126_177
    h = h xor (h ushr 16)
    return (h and 0xFFFFFF) / 16_777_215f
}
