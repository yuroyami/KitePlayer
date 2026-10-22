package io.github.yuroyami.kiteplayer.compose

import android.graphics.BitmapShader
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import io.github.yuroyami.kiteplayer.PictureCrop
import io.github.yuroyami.kiteplayer.spi.SphericalLens

/** Android runs a runtime shader from version 13. Below it, a 360 degree picture is drawn flat. */
internal actual val sphereViewsDrawable: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

/**
 * The program, compiled once, for the main thread that every Compose draw runs on. A draw sets its
 * values and its picture and records it; Android takes a copy of a shader as it records it, so the
 * next draw changing the values leaves the recorded one alone. Null when it does not compile.
 */
@get:RequiresApi(Build.VERSION_CODES.TIRAMISU)
private val sphereViewProgram: RuntimeShader? by lazy {
    runCatching { RuntimeShader(SPHERE_VIEW_PROGRAM) }.getOrNull()
}

/**
 * Android's view (#460), through the same program as Skia's. A runtime shader runs only on a
 * hardware canvas, which is every Compose window's, so a software canvas draws the picture flat.
 * Android samples a picture smoothly at best, whatever [filterQuality] asks.
 */
internal actual fun DrawScope.drawSphereView(
    image: ImageBitmap,
    crop: PictureCrop?,
    lens: SphericalLens,
    filterQuality: FilterQuality,
    colorFilter: ColorFilter?,
): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
    if (!drawContext.canvas.nativeCanvas.isHardwareAccelerated) return false
    return drawThroughRuntimeShader(image, crop, lens, colorFilter)
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun DrawScope.drawThroughRuntimeShader(
    image: ImageBitmap,
    crop: PictureCrop?,
    lens: SphericalLens,
    colorFilter: ColorFilter?,
): Boolean {
    val program = sphereViewProgram ?: return false
    // Repeated across, so a sample at the seam of the whole sphere blends its two edges.
    val across = if (lens.joinsAcrossImage(crop)) Shader.TileMode.REPEAT else Shader.TileMode.CLAMP
    val picture = BitmapShader(image.asAndroidBitmap(), across, Shader.TileMode.CLAMP)
    // Fed to a runtime shader, a picture is read at its nearest pixel unless told to blend.
    picture.filterMode = BitmapShader.FILTER_MODE_LINEAR
    program.setInputShader(SPHERE_VIEW_PICTURE, picture)
    sphereViewUniforms(lens, crop, image.width, image.height) { name, values ->
        when (values.size) {
            1 -> program.setFloatUniform(name, values[0])
            2 -> program.setFloatUniform(name, values[0], values[1])
            3 -> program.setFloatUniform(name, values[0], values[1], values[2])
            else -> program.setFloatUniform(name, values[0], values[1], values[2], values[3])
        }
    }
    drawRect(brush = FixedShaderBrush(program), colorFilter = colorFilter)
    return true
}
