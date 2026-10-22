package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.PictureCrop
import io.github.yuroyami.kiteplayer.VideoSize
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The GPU image path cuts a container's crop by narrowing the SurfaceTexture lookup (#497). The
 * lookup's coordinates run from the picture's bottom left, so a quad corner must land on the corner
 * of the part the crop leaves, whatever transform SurfaceTexture handed over.
 */
class CropTextureTransformTest {

    private fun identity() = FloatArray(16).also { for (i in 0 until 4) it[i * 5] = 1f }

    /** The texture coordinate a corner at ([s], [t]) samples, through the column-major [m]. */
    private fun lookup(m: FloatArray, s: Float, t: Float): Pair<Float, Float> =
        (m[0] * s + m[4] * t + m[12]) to (m[1] * s + m[5] * t + m[13])

    @Test
    fun theCornersLandOnThePartTheCropLeaves() {
        val transform = identity()
        cropTextureTransform(transform, PictureCrop(top = 10, bottom = 30, left = 20, right = 60), VideoSize(200, 100))
        val (left, bottom) = lookup(transform, 0f, 0f)
        val (right, top) = lookup(transform, 1f, 1f)
        // Bottom left lands past the left columns and the bottom rows, top right short of the others.
        assertEquals(0.1f, left, 1e-6f)
        assertEquals(0.3f, bottom, 1e-6f)
        assertEquals(0.7f, right, 1e-6f)
        assertEquals(0.9f, top, 1e-6f)
    }

    @Test
    fun theCropGoesBeforeTheTransformSurfaceTextureGives() {
        // SurfaceTexture's usual matrix flips the picture upside down: t becomes 1 - t.
        val flip = identity().also { it[5] = -1f; it[13] = 1f }
        cropTextureTransform(flip, PictureCrop(bottom = 8), VideoSize(1920, 1088))
        val (_, bottomEdge) = lookup(flip, 0f, 0f)
        val (_, topEdge) = lookup(flip, 0f, 1f)
        assertEquals(1f - 8f / 1088f, bottomEdge, 1e-6f, "the picture's bottom edge stops short of the padding rows")
        assertEquals(0f, topEdge, 1e-6f)
    }
}
