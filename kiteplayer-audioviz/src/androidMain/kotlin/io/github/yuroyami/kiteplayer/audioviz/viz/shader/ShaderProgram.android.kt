package io.github.yuroyami.kiteplayer.audioviz.viz.shader

import android.graphics.BitmapShader
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.os.Build
import android.util.Log
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import io.github.yuroyami.kiteplayer.audioviz.AudioVizAuthoringApi
import kotlin.math.ceil

internal actual val runtimeShadersSupported: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

internal actual fun DrawScope.canDrawRuntimeShaders(): Boolean =
    runtimeShadersSupported && drawContext.canvas.nativeCanvas.isHardwareAccelerated

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
    private var passNode: RenderNode? = null
    private val passPaint by lazy { Paint() }

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
                Log.e("KitePlayerShader", "Runtime shader compilation failed: $message")
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

    internal actual fun childProgram(name: String, program: ShaderProgram) {
        val target = checkNotNull(shader) { error ?: "parent shader is unavailable" }
        val child = checkNotNull(program.shader) { program.error ?: "child shader is unavailable" }
        target.setInputShader(name, child)
    }

    internal actual fun drawPasses(scope: DrawScope, input: ShaderProgram, width: Float, height: Float,
        passes: List<ShaderProgram>, sampler: String) {
        val node = passNode ?: RenderNode("KitePlayer shader passes").also { passNode = it }
        val w = ceil(scope.size.width).toInt()
        val h = ceil(scope.size.height).toInt()
        node.setPosition(0, 0, w, h)
        val canvas = node.beginRecording(w, h)
        try {
            // The full opaque extent gives the later native-resolution passes their output bounds.
            canvas.drawColor(Color.BLACK)
            passPaint.shader = checkNotNull(input.shader)
            canvas.drawRect(0f, 0f, ceil(width), ceil(height), passPaint)
        } finally {
            node.endRecording()
        }
        var effect: RenderEffect? = null
        for (pass in passes) {
            val next = RenderEffect.createRuntimeShaderEffect(checkNotNull(pass.shader), sampler)
            effect = effect?.let { RenderEffect.createChainEffect(next, it) } ?: next
        }
        val finish = RenderEffect.createRuntimeShaderEffect(checkNotNull(shader), sampler)
        node.setRenderEffect(effect?.let { RenderEffect.createChainEffect(finish, it) } ?: finish)
        scope.drawContext.canvas.nativeCanvas.drawRenderNode(node)
    }
}

private class AndroidProgramBrush(private val shader: Shader) : ShaderBrush() {
    override fun createShader(size: Size): Shader = shader
}
