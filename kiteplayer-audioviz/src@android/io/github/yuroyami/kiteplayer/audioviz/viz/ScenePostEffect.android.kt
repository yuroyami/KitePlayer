package io.github.yuroyami.kiteplayer.audioviz.viz

import android.graphics.BlendMode
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect as AndroidRenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect

internal actual class ScenePostEffect actual constructor() {
    private val shader = if (Build.VERSION.SDK_INT >= 33) RuntimeShader(FRINGE_SHADER) else null

    /**
     * What one chain of effects was built from. Android's effects cannot change once made, and
     * the strength moves every frame, so the chains are kept by these inputs, with the strength and
     * the split rounded to steps too fine to see, and a frame whose inputs were seen before reuses
     * its chain instead of building fourteen native objects again (#951).
     */
    private data class Key(
        val bloom: Boolean, val threshold: Float, val bloomRadius: Float,
        val width: Float, val height: Float, val strength: Int, val split: Int,
    )

    private val kept = object : LinkedHashMap<Key, RenderEffect>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, RenderEffect>?): Boolean = size > KEPT
    }

    actual fun prepare(spec: PostSpec, width: Float, height: Float, strength: Float, split: Float): RenderEffect? {
        val shader = shader ?: return null
        val bloom = spec.bloom > 0f && strength > 0f
        val strengthStep = (strength.coerceIn(0f, 1f) * STRENGTH_STEPS).roundToInt()
        val splitStep = (split * SPLIT_STEPS).roundToInt()
        val key = Key(bloom, spec.threshold, spec.bloomRadius, width, height, if (bloom) strengthStep else 0, splitStep)
        kept[key]?.let { chain ->
            // Set again in case an effect reads its shader's uniforms when drawn rather than when
            // made; either way the kept chain then shows this frame's values.
            shader.setFloatUniform("resolution", width, height)
            shader.setFloatUniform("split", splitStep.toFloat() / SPLIT_STEPS)
            return chain
        }
        return build(spec, width, height, strengthStep.toFloat() / STRENGTH_STEPS, splitStep.toFloat() / SPLIT_STEPS, bloom)
            .also { kept[key] = it }
    }

    private fun build(spec: PostSpec, width: Float, height: Float, strength: Float, split: Float, bloom: Boolean): RenderEffect {
        val shader = checkNotNull(this.shader)
        shader.setFloatUniform("resolution", width, height)
        shader.setFloatUniform("split", split)
        var result = AndroidRenderEffect.createRuntimeShaderEffect(shader, "scene")
        if (bloom) {
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

    actual fun close() {
        kept.clear()
    }

    private companion object {
        /** Chains kept at once, enough for the strengths one song moves through. */
        const val KEPT = 96
        /** Steps of the bloom strength: 1/64 moves the glow's alpha by under 1.3 percent. */
        const val STRENGTH_STEPS = 64
        /** Steps of the colour split, in the shader's own units. */
        const val SPLIT_STEPS = 256
    }
}
