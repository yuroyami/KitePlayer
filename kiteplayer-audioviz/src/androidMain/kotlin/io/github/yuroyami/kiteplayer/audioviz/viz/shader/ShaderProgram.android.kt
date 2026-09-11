package io.github.yuroyami.kiteplayer.audioviz.viz.shader

import android.graphics.BitmapShader
import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asAndroidBitmap
import io.github.yuroyami.kiteplayer.audioviz.AudioVizAuthoringApi

internal actual val runtimeShadersSupported: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

/**
 * Android's own runtime shaders, which arrived in version 33.
 *
 * The language is the same one Skia uses, with a smaller set of built in functions, so a program
 * written for the desktop usually runs here unchanged. Below version 33 there is no way to run one
 * at all: [available] is false and a drawing that needs one has to fall back to shapes or be left
 * out of the list on that device.
 */
@AudioVizAuthoringApi
public actual class ShaderProgram actual constructor(source: String) {

    private val shader: RuntimeShader?

    public actual val error: String?

    init {
        var built: RuntimeShader? = null
        var message: String? = null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            message = "runtime shaders need Android 13, this device is on ${Build.VERSION.SDK_INT}"
        } else {
            try {
                built = RuntimeShader(source)
            } catch (failure: Throwable) {
                message = failure.message ?: "the shader did not compile"
            }
        }
        shader = built
        error = message
    }

    public actual val available: Boolean get() = shader != null

    /**
     * Names this program turned out not to have.
     *
     * The library hands every shader the same two dozen readings whether it asked for them or not,
     * and the compiler removes the ones a shader never mentions. Setting one of those is an error,
     * so the first attempt is allowed to fail and the name is not tried again.
     */
    private val absent = HashSet<String>()

    private inline fun set(name: String, apply: (RuntimeShader) -> Unit) {
        val target = shader ?: return
        if (name in absent) return
        try {
            apply(target)
        } catch (ignored: Throwable) {
            absent += name
        }
    }

    public actual fun uniform(name: String, value: Float) {
        set(name) { it.setFloatUniform(name, value) }
    }

    public actual fun uniform(name: String, x: Float, y: Float) {
        set(name) { it.setFloatUniform(name, x, y) }
    }

    public actual fun uniform(name: String, x: Float, y: Float, z: Float) {
        set(name) { it.setFloatUniform(name, x, y, z) }
    }

    public actual fun uniform(name: String, x: Float, y: Float, z: Float, w: Float) {
        set(name) { it.setFloatUniform(name, x, y, z, w) }
    }

    public actual fun uniforms(name: String, values: FloatArray) {
        set(name) { it.setFloatUniform(name, values) }
    }

    public actual fun child(name: String, image: ImageBitmap, tiled: Boolean) {
        val target = shader ?: return
        if (name in absent) return
        // Clamped at the edges and sampled smoothly, so reading between two bars of a spectrum
        // blends rather than steps.
        val mode = if (tiled) android.graphics.Shader.TileMode.REPEAT else android.graphics.Shader.TileMode.CLAMP
        val bitmap = BitmapShader(image.asAndroidBitmap(), mode, mode)
        try {
            target.setInputShader(name, bitmap)
        } catch (ignored: Throwable) {
            absent += name
        }
    }

    public actual fun brush(): Brush? {
        val target = shader ?: return null
        return AndroidProgramBrush(target)
    }
}

private class AndroidProgramBrush(private val shader: Shader) : ShaderBrush() {
    override fun createShader(size: Size): Shader = shader
}
