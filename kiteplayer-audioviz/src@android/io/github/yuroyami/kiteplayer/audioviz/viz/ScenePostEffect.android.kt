package io.github.yuroyami.kiteplayer.audioviz.viz

import android.graphics.BlendMode
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect as AndroidRenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect

internal actual class ScenePostEffect actual constructor() {
    private val shader = if (Build.VERSION.SDK_INT >= 33) RuntimeShader(FRINGE_SHADER) else null

    actual fun prepare(spec: PostSpec, width: Float, height: Float, strength: Float, split: Float): RenderEffect? {
        val shader = shader ?: return null
        shader.setFloatUniform("resolution", width, height)
        shader.setFloatUniform("split", split)
        var result = AndroidRenderEffect.createRuntimeShaderEffect(shader, "scene")
        if (spec.bloom > 0f && strength > 0f) {
            val cut = spec.threshold.coerceIn(0f, 0.95f)
            val gain = 1f / (1f - cut)
            val shift = -cut * gain * 255f
            val bright = AndroidRenderEffect.createColorFilterEffect(ColorMatrixColorFilter(floatArrayOf(
                gain, 0f, 0f, 0f, shift,
                0f, gain, 0f, 0f, shift,
                0f, 0f, gain, 0f, shift,
                0f, 0f, 0f, 1f, 0f,
            )))
            var radius = (minOf(width, height) * spec.bloomRadius).coerceAtLeast(1f)
            var weight = 0.8f
            repeat(4) {
                val blur = AndroidRenderEffect.createBlurEffect(radius, radius, bright, Shader.TileMode.DECAL)
                val alpha = (strength * weight).coerceIn(0f, 1f)
                val faded = AndroidRenderEffect.createColorFilterEffect(ColorMatrixColorFilter(floatArrayOf(
                    1f, 0f, 0f, 0f, 0f,
                    0f, 1f, 0f, 0f, 0f,
                    0f, 0f, 1f, 0f, 0f,
                    0f, 0f, 0f, alpha, 0f,
                )), blur)
                result = AndroidRenderEffect.createBlendModeEffect(result, faded, BlendMode.PLUS)
                radius *= 2f
                weight *= 0.5f
            }
        }
        return result.asComposeRenderEffect()
    }

    actual fun close() = Unit
}
