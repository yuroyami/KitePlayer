package io.github.yuroyami.kiteplayer.compose

import io.github.yuroyami.kiteplayer.VideoAdjustments

/**
 * The picture controls' colour matrix with the flash guard's [dim] folded in (#500), in Compose's
 * convention: the same 4x5 rows, the translation column in the 0..255 domain. The colour rows,
 * offsets included, are multiplied by [dim], so the picture comes out [dim] times what the viewer's
 * controls make of it; alpha is untouched.
 */
internal fun dimmedColorMatrix(adjustments: VideoAdjustments, dim: Float): FloatArray {
    val values = adjustments.toColorMatrix().copyOf()
    for (row in 0 until 3) {
        for (column in 0 until 5) values[row * 5 + column] *= dim
    }
    values[4] *= 255f
    values[9] *= 255f
    values[14] *= 255f
    values[19] *= 255f
    return values
}
