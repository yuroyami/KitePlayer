package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.KitePlayerInternalApi
import io.github.yuroyami.kiteplayer.SphericalView
import io.github.yuroyami.kiteplayer.VideoProjection
import io.github.yuroyami.kiteplayer.spi.SphericalLens
import io.github.yuroyami.kiteplayer.spi.VideoFrame

/** The projection a Metal renderer draws a view into, and the view (#586). */
internal data class SphereLook(val projection: VideoProjection, val view: SphericalView)

/**
 * The shader's SphereUniforms for [look] into [frame] on a target of [width] by [height] pixels:
 * the lens, the three rows of its turn, the picture's bounds on the sphere, and the part of the
 * texture the picture is after the frame's crop. The shader reads the picture as [SphericalLens]
 * says, so the Metal view agrees with every other renderer.
 */
@OptIn(KitePlayerInternalApi::class)
internal fun packSphereUniforms(look: SphereLook, frame: VideoFrame, width: Int, height: Int): FloatArray {
    val lens = SphericalLens(look.projection, look.view, width.toFloat(), height.toFloat())
    val m = lens.rotation
    val stored = frame.size
    val crop = frame.crop?.takeIf { !it.isEmpty && it.fits(stored.width, stored.height) }
    val textureWidth = stored.width.coerceAtLeast(1).toFloat()
    val textureHeight = stored.height.coerceAtLeast(1).toFloat()
    val left = (crop?.left ?: 0) / textureWidth
    val top = (crop?.top ?: 0) / textureHeight
    val right = (crop?.right ?: 0) / textureWidth
    val bottom = (crop?.bottom ?: 0) / textureHeight
    return floatArrayOf(
        lens.width / 2f, lens.height / 2f, lens.scale.toFloat(), if (lens.wraps) 1f else 0f,
        m[0].toFloat(), m[1].toFloat(), m[2].toFloat(), 0f,
        m[3].toFloat(), m[4].toFloat(), m[5].toFloat(), 0f,
        m[6].toFloat(), m[7].toFloat(), m[8].toFloat(), 0f,
        lens.pictureLeft.toFloat(), lens.pictureTop.toFloat(), lens.pictureWidth.toFloat(), lens.pictureHeight.toFloat(),
        left, top, 1f - left - right, 1f - top - bottom,
    )
}

/** SphereUniforms with a lens scale of 0: the picture draws flat through its quad. */
internal val DISABLED_SPHERE_UNIFORMS: FloatArray = FloatArray(24)

/** The quad that covers the whole target, which a view into the sphere draws over (#586). */
internal val FULL_TARGET_QUAD: FloatArray = floatArrayOf(1f, 1f, 1f, 0f, 0f, 1f, 0f, 0f, 0f, 0f)
