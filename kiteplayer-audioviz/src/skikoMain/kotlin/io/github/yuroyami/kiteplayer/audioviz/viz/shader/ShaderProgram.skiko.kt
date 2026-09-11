package io.github.yuroyami.kiteplayer.audioviz.viz.shader

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asComposeShader
import androidx.compose.ui.graphics.asSkiaBitmap
import io.github.yuroyami.kiteplayer.audioviz.AudioVizAuthoringApi
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import org.jetbrains.skia.SamplingMode

internal actual val runtimeShadersSupported: Boolean = true

/**
 * Desktop and iOS both draw through Skia, so both get this one.
 *
 * The compiled program and the builder holding its values are made once and reused. Compiling on
 * every frame would be far more expensive than the drawing.
 */
@AudioVizAuthoringApi
public actual class ShaderProgram actual constructor(source: String) {

    private var effect: RuntimeEffect? = null
    private var builder: RuntimeShaderBuilder? = null

    public actual val error: String?

    init {
        var message: String? = null
        try {
            val compiled = RuntimeEffect.makeForShader(source)
            effect = compiled
            builder = RuntimeShaderBuilder(compiled)
        } catch (failure: Throwable) {
            message = failure.message ?: "the shader did not compile"
        }
        error = message
    }

    public actual val available: Boolean get() = builder != null

    /**
     * Names this program turned out not to have.
     *
     * The library hands every shader the same two dozen readings whether it asked for them or not,
     * and the compiler removes the ones a shader never mentions. Setting one of those is an error,
     * so the first attempt is allowed to fail and the name is not tried again. Without the record
     * it would throw sixty times a second for the rest of the run.
     */
    private val absent = HashSet<String>()

    private inline fun set(name: String, apply: (RuntimeShaderBuilder) -> Unit) {
        val holder = builder ?: return
        if (name in absent) return
        try {
            apply(holder)
        } catch (ignored: Throwable) {
            absent += name
        }
    }

    public actual fun uniform(name: String, value: Float) {
        set(name) { it.uniform(name, value) }
    }

    public actual fun uniform(name: String, x: Float, y: Float) {
        set(name) { it.uniform(name, x, y) }
    }

    public actual fun uniform(name: String, x: Float, y: Float, z: Float) {
        set(name) { it.uniform(name, x, y, z) }
    }

    public actual fun uniform(name: String, x: Float, y: Float, z: Float, w: Float) {
        set(name) { it.uniform(name, x, y, z, w) }
    }

    public actual fun uniforms(name: String, values: FloatArray) {
        set(name) { it.uniform(name, values) }
    }

    public actual fun child(name: String, image: ImageBitmap, tiled: Boolean) {
        val holder = builder ?: return
        if (name in absent) return
        // Skia wants an image rather than a bitmap, and a shader rather than an image. Clamped at
        // the edges and sampled smoothly, which is what every one of these small lookup pictures
        // wants: a spectrum read between two bars should blend, not step.
        val skiaImage = Image.makeFromBitmap(image.asSkiaBitmap())
        try {
            holder.child(
                name,
                if (tiled) skiaImage.makeShader(FilterTileMode.REPEAT, FilterTileMode.REPEAT, SamplingMode.LINEAR)
                else skiaImage.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, SamplingMode.LINEAR),
            )
        } catch (ignored: Throwable) {
            absent += name
        }
    }

    public actual fun brush(): Brush? {
        val holder = builder ?: return null
        return SkiaProgramBrush(holder.makeShader().asComposeShader())
    }
}

private class SkiaProgramBrush(private val shader: Shader) : ShaderBrush() {
    override fun createShader(size: Size): Shader = shader
}
