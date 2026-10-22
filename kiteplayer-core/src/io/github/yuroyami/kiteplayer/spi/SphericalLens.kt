package io.github.yuroyami.kiteplayer.spi

import io.github.yuroyami.kiteplayer.KitePlayerInternalApi
import io.github.yuroyami.kiteplayer.ProjectionKind
import io.github.yuroyami.kiteplayer.SphericalView
import io.github.yuroyami.kiteplayer.VideoProjection
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The law every renderer draws a view into a 360 degree video by (#460), so that they all agree
 * with this one, which their tests compare them against.
 *
 * A pixel at (x, y) of the area the view fills, counted in pixels from its top left corner, looks
 * along `(scale * (x - width / 2), scale * (height / 2 - y), -1)`. The axes are OpenGL's: x to the
 * right, y up and z toward the viewer, so the view looks along -z. [rotation] turns that direction
 * into one on the picture's own sphere: the view's roll, pitch and yaw turn it into the world, and
 * undoing the file's pose turns the world into the picture's sphere. The picture is read at that
 * direction's longitude across, wrapping round for the whole sphere, and its latitude down, as
 * [pictureAt] does.
 *
 * A renderer computes this once for each draw, on the processor, and hands [rotation], [scale],
 * the centre and the bounds to its shader, which takes only the longitude and the latitude itself.
 * A shader is handed each pixel's centre, so the pixel at column x reads [pictureAt] of x + 0.5.
 */
@KitePlayerInternalApi
public class SphericalLens(
    projection: VideoProjection,
    view: SphericalView,
    /** The width of the area the view fills, in pixels. */
    public val width: Float,
    /** The height of the area the view fills, in pixels. */
    public val height: Float,
) {
    init {
        require(draws(projection.kind)) { "a view is drawn into an equirectangular picture or a tile of one, not ${projection.kind}" }
        require(width > 0f && height > 0f) { "the view has no area: $width by $height" }
    }

    /**
     * The turn from a direction the view looks in to the same direction on the picture's sphere, as
     * a 3 by 3 matrix row by row: the first three entries make the x of the turned direction.
     */
    public val rotation: DoubleArray = transposedTimes(
        turn(yaw = -projection.yaw, pitch = projection.pitch, roll = -projection.roll),
        turn(yaw = -view.yaw.toDouble(), pitch = view.pitch.toDouble(), roll = -view.roll.toDouble()),
    )

    /**
     * How far apart two neighbouring pixels look, at a distance of one: the tangent of half the
     * field of view over half the shorter side, so the view spans its field across that side.
     */
    public val scale: Double = tan(view.fieldOfView.toDouble() * PI / 360.0) / (min(width, height).toDouble() / 2.0)

    /** True when the picture is the whole sphere, so its left and right edges join. */
    public val wraps: Boolean = projection.kind is ProjectionKind.Equirectangular

    /** The share of the whole sphere's picture to the left of this picture: 0 for the whole sphere. */
    public val pictureLeft: Double

    /** The share of the whole sphere's picture above this picture: 0 for the whole sphere. */
    public val pictureTop: Double

    /** This picture's width as a share of the whole sphere's picture: 1 for the whole sphere. */
    public val pictureWidth: Double

    /** This picture's height as a share of the whole sphere's picture: 1 for the whole sphere. */
    public val pictureHeight: Double

    init {
        val tile = projection.kind as? ProjectionKind.EquirectangularTile
        pictureLeft = tile?.left ?: 0.0
        pictureTop = tile?.top ?: 0.0
        pictureWidth = tile?.let { 1.0 - it.left - it.right } ?: 1.0
        pictureHeight = tile?.let { 1.0 - it.top - it.bottom } ?: 1.0
    }

    /**
     * Where the view at ([x], [y]) of its area reads the picture, as shares of the picture's width
     * across and its height down, each from 0 to 1, or null where a tile has no picture.
     */
    public fun pictureAt(x: Double, y: Double): PicturePoint? {
        val rayX = scale * (x - width / 2.0)
        val rayY = scale * (height / 2.0 - y)
        val m = rotation
        val dx = m[0] * rayX + m[1] * rayY - m[2]
        val dy = m[3] * rayX + m[4] * rayY - m[5]
        val dz = m[6] * rayX + m[7] * rayY - m[8]
        val longitude = atan2(dx, -dz)
        val latitude = atan2(dy, sqrt(dx * dx + dz * dz))
        var across = 0.5 + longitude / (2.0 * PI)
        val down = 0.5 - latitude / PI
        if (wraps) across -= floor(across)
        val u = (across - pictureLeft) / pictureWidth
        val v = (down - pictureTop) / pictureHeight
        if (u < 0.0 || u > 1.0 || v < 0.0 || v > 1.0) return null
        return PicturePoint(u, v)
    }

    public companion object {
        /** True for the kinds of projection a view is drawn into: the whole sphere and a tile of it. */
        public fun draws(kind: ProjectionKind): Boolean =
            kind is ProjectionKind.Equirectangular || kind is ProjectionKind.EquirectangularTile
    }
}

/** A point of a picture, as shares of its width [across] and of its height [down], each from 0 to 1 (#460). */
@KitePlayerInternalApi
public data class PicturePoint(val across: Double, val down: Double)

/**
 * The turn by [yaw], then [pitch], then [roll], in degrees, each a turn by the right-hand rule:
 * `Ry(yaw) * Rx(pitch) * Rz(roll)`, row by row.
 */
private fun turn(yaw: Double, pitch: Double, roll: Double): DoubleArray {
    val (cy, sy) = cos(yaw * PI / 180.0) to sin(yaw * PI / 180.0)
    val (cp, sp) = cos(pitch * PI / 180.0) to sin(pitch * PI / 180.0)
    val (cr, sr) = cos(roll * PI / 180.0) to sin(roll * PI / 180.0)
    val aboutY = doubleArrayOf(cy, 0.0, sy, 0.0, 1.0, 0.0, -sy, 0.0, cy)
    val aboutX = doubleArrayOf(1.0, 0.0, 0.0, 0.0, cp, -sp, 0.0, sp, cp)
    val aboutZ = doubleArrayOf(cr, -sr, 0.0, sr, cr, 0.0, 0.0, 0.0, 1.0)
    return times(times(aboutY, aboutX), aboutZ)
}

private fun times(a: DoubleArray, b: DoubleArray): DoubleArray = DoubleArray(9) { index ->
    val row = index / 3
    val column = index % 3
    a[row * 3] * b[column] + a[row * 3 + 1] * b[3 + column] + a[row * 3 + 2] * b[6 + column]
}

/** The transpose of [a], which undoes the turn [a] is, times [b]. */
private fun transposedTimes(a: DoubleArray, b: DoubleArray): DoubleArray = DoubleArray(9) { index ->
    val row = index / 3
    val column = index % 3
    a[row] * b[column] + a[3 + row] * b[3 + column] + a[6 + row] * b[6 + column]
}
