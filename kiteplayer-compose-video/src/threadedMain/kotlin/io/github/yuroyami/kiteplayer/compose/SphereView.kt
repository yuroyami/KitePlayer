package io.github.yuroyami.kiteplayer.compose

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import io.github.yuroyami.kiteplayer.PictureCrop
import io.github.yuroyami.kiteplayer.SphericalView
import io.github.yuroyami.kiteplayer.VideoProjection
import io.github.yuroyami.kiteplayer.spi.SphericalLens

/**
 * The 360 degree picture the draw phase draws a view into (#460): the picture's projection and the
 * viewer's view. [KiteVideoRenderer] publishes one only when this platform draws the view, so none
 * means the flat picture.
 */
internal data class SphereView(val projection: VideoProjection, val view: SphericalView)

/** True where this platform draws a view into a 360 degree picture: Skia's always, Android's from version 13. */
internal expect val sphereViewsDrawable: Boolean

/**
 * Draws the view [lens] describes into [image] over the whole draw area (#460), reading only the
 * part of it that [crop] leaves, sampled smoothly, or with a cubic kernel when [filterQuality] is
 * high, and passed through [colorFilter]. Where a tile has no picture it draws nothing, so what lies
 * behind shows. Answers false, having drawn nothing, where this canvas cannot run the program, and
 * the caller then draws the picture flat.
 */
internal expect fun DrawScope.drawSphereView(
    image: ImageBitmap,
    crop: PictureCrop?,
    lens: SphericalLens,
    filterQuality: FilterQuality,
    colorFilter: ColorFilter?,
): Boolean

/**
 * The program that draws the view, in the shading language Skia and Android share: [SphericalLens]
 * for each pixel, with the turn handed in as three rows, so it takes no sine or cosine of its own.
 * `coord` is the centre of the pixel being drawn.
 */
internal const val SPHERE_VIEW_PROGRAM: String = """
uniform shader picture;
uniform float2 center;
uniform float scale;
uniform float3 row0;
uniform float3 row1;
uniform float3 row2;
uniform float4 bounds;
uniform float wraps;
uniform float4 source;

half4 main(float2 coord) {
    float3 ray = float3(scale * (coord.x - center.x), scale * (center.y - coord.y), -1.0);
    float3 d = float3(dot(row0, ray), dot(row1, ray), dot(row2, ray));
    float across = 0.5 + atan(d.x, -d.z) * 0.15915494309;
    float down = 0.5 - atan(d.y, length(d.xz)) * 0.31830988618;
    if (wraps > 0.5) {
        across = fract(across);
    }
    float u = (across - bounds.x) / bounds.z;
    float v = (down - bounds.y) / bounds.w;
    if (u < 0.0 || u > 1.0 || v < 0.0 || v > 1.0) {
        return half4(0.0);
    }
    return picture.eval(float2(source.x + u * source.z, source.y + v * source.w));
}
"""

/** The name of the picture [SPHERE_VIEW_PROGRAM] reads. */
internal const val SPHERE_VIEW_PICTURE: String = "picture"

/**
 * Hands [set] each value [SPHERE_VIEW_PROGRAM] takes, by name, for the view [lens] into the
 * picture that [crop] leaves of an image [width] by [height] pixels.
 */
internal inline fun sphereViewUniforms(
    lens: SphericalLens,
    crop: PictureCrop?,
    width: Int,
    height: Int,
    set: (name: String, values: FloatArray) -> Unit,
) {
    val m = lens.rotation
    set("center", floatArrayOf(lens.width / 2f, lens.height / 2f))
    set("scale", floatArrayOf(lens.scale.toFloat()))
    set("row0", floatArrayOf(m[0].toFloat(), m[1].toFloat(), m[2].toFloat()))
    set("row1", floatArrayOf(m[3].toFloat(), m[4].toFloat(), m[5].toFloat()))
    set("row2", floatArrayOf(m[6].toFloat(), m[7].toFloat(), m[8].toFloat()))
    set(
        "bounds",
        floatArrayOf(lens.pictureLeft.toFloat(), lens.pictureTop.toFloat(), lens.pictureWidth.toFloat(), lens.pictureHeight.toFloat()),
    )
    set("wraps", floatArrayOf(if (lens.wraps) 1f else 0f))
    val left = crop?.left ?: 0
    val top = crop?.top ?: 0
    val right = crop?.right ?: 0
    val bottom = crop?.bottom ?: 0
    set("source", floatArrayOf(left.toFloat(), top.toFloat(), (width - left - right).toFloat(), (height - top - bottom).toFloat()))
}

/**
 * True when the picture's left and right edges join in [image] itself, so a sample at the seam
 * may blend across it: the whole sphere, with no crop to cut the joined edges off.
 */
internal fun SphericalLens.joinsAcrossImage(crop: PictureCrop?): Boolean = wraps && (crop == null || crop.isEmpty)

/** A brush whose shader is made for one draw rather than for a size. */
internal class FixedShaderBrush(private val shader: Shader) : ShaderBrush() {
    override fun createShader(size: Size): Shader = shader
}
