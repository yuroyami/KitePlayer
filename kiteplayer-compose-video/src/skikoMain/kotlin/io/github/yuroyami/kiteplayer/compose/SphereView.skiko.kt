package io.github.yuroyami.kiteplayer.compose

import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeShader
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import io.github.yuroyami.kiteplayer.PictureCrop
import io.github.yuroyami.kiteplayer.spi.SphericalLens
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.impl.use

/** The desktop and iOS both draw through Skia, which always runs a runtime shader. */
internal actual val sphereViewsDrawable: Boolean = true

/**
 * The program, compiled once for the process: compiling it for each draw would cost more than the
 * drawing. Null when it does not compile, which the draw tests catch.
 */
private val sphereViewEffect: RuntimeEffect? by lazy {
    runCatching { RuntimeEffect.makeForShader(SPHERE_VIEW_PROGRAM) }.getOrNull()
}

/**
 * Skia's view (#460). The picture is wrapped as Compose's own drawImage wraps it, which shares the
 * pixels of an immutable bitmap rather than copying them, and every wrapper made for the draw is
 * let go as soon as it is recorded: Skia counts its references, so the recording keeps what it uses.
 */
internal actual fun DrawScope.drawSphereView(
    image: ImageBitmap,
    crop: PictureCrop?,
    lens: SphericalLens,
    filterQuality: FilterQuality,
    colorFilter: ColorFilter?,
): Boolean {
    val effect = sphereViewEffect ?: return false
    // Repeated across, so a sample at the seam of the whole sphere blends its two edges.
    val across = if (lens.joinsAcrossImage(crop)) FilterTileMode.REPEAT else FilterTileMode.CLAMP
    val sampling = if (filterQuality == FilterQuality.High) SamplingMode.CATMULL_ROM else SamplingMode.LINEAR
    Image.makeFromBitmap(image.asSkiaBitmap()).use { skiaImage ->
        skiaImage.makeShader(across, FilterTileMode.CLAMP, sampling).use { picture ->
            RuntimeShaderBuilder(effect).use { builder ->
                builder.child(SPHERE_VIEW_PICTURE, picture)
                sphereViewUniforms(lens, crop, image.width, image.height) { name, values ->
                    when (values.size) {
                        1 -> builder.uniform(name, values[0])
                        2 -> builder.uniform(name, values[0], values[1])
                        3 -> builder.uniform(name, values[0], values[1], values[2])
                        else -> builder.uniform(name, values[0], values[1], values[2], values[3])
                    }
                }
                builder.makeShader().use { shader ->
                    drawRect(brush = FixedShaderBrush(shader.asComposeShader()), colorFilter = colorFilter)
                }
            }
        }
    }
    return true
}
