package io.github.yuroyami.kiteplayer.audioviz.viz

import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asSkiaColorFilter
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.IRect
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.Matrix33
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.impl.use

internal actual class ScenePostEffect actual constructor() {
    private val program = RuntimeEffect.makeForShader(COMPOSITE_SHADER)
    private val builder = RuntimeShaderBuilder(program)
    private val levels = arrayOfNulls<ImageFilter>(4)
    private var threshold = Float.NaN
    private var radius = Float.NaN
    private var width = 0f
    private var height = 0f
    private var output: ImageFilter? = null

    actual fun prepare(spec: PostSpec, width: Float, height: Float, strength: Float, split: Float): RenderEffect? {
        if (width <= 0f || height <= 0f) return null
        val radius = (minOf(width, height) * spec.bloomRadius).coerceAtLeast(1f)
        val blooming = spec.bloom > 0f && strength > 0f
        if (blooming && (spec.threshold != threshold || radius != this.radius || width != this.width || height != this.height)) {
            levels.forEach { it?.close() }
            thresholdFilter(spec.threshold).asSkiaColorFilter().use { filter ->
                ImageFilter.makeColorFilter(filter, null, null).use { bright ->
                    var scale = 0.5f
                    var spread = radius
                    var small = ImageFilter.makeMatrixTransform(Matrix33.makeScale(scale, scale), SamplingMode.LINEAR, bright)
                    try {
                        for (index in levels.indices) {
                            // Reduce twofold at a time, filtering between reductions so thin sparks
                            // keep contributing to the wide glow instead of falling between samples.
                            val sigma = (spread * 0.57735f + 0.5f) * scale
                            levels[index] = ImageFilter.makeBlur(
                                sigma, sigma, FilterTileMode.DECAL, small,
                                IRect.makeWH(kotlin.math.ceil(width * scale).toInt(), kotlin.math.ceil(height * scale).toInt()),
                            )
                            if (index < levels.lastIndex) {
                                val next = ImageFilter.makeBlur(0.5f, 0.5f, FilterTileMode.DECAL, small, null).use { filtered ->
                                    ImageFilter.makeMatrixTransform(Matrix33.makeScale(0.5f, 0.5f), SamplingMode.LINEAR, filtered)
                                }
                                small.close()
                                small = next
                            }
                            scale *= 0.5f
                            spread *= 2f
                        }
                    } finally {
                        small.close()
                    }
                }
            }
            threshold = spec.threshold
            this.radius = radius
            this.width = width
            this.height = height
        }
        builder.uniform("resolution", width, height)
        builder.uniform("split", split)
        builder.uniform("strength", if (spec.bloom > 0f) strength else 0f)
        val next = ImageFilter.makeRuntimeShader(
            builder,
            arrayOf("scene", "glow0", "glow1", "glow2", "glow3"),
            if (blooming) arrayOf(null, levels[0], levels[1], levels[2], levels[3]) else arrayOfNulls(5),
        )
        output?.close()
        output = next
        return next.asComposeRenderEffect()
    }

    actual fun close() {
        output?.close()
        output = null
        levels.forEach { it?.close() }
        levels.fill(null)
        builder.close()
        program.close()
    }
}

private const val COMPOSITE_SHADER = """
uniform shader scene;
uniform shader glow0;
uniform shader glow1;
uniform shader glow2;
uniform shader glow3;
uniform float2 resolution;
uniform float split;
uniform float strength;
half4 main(float2 p) {
    half4 colour = scene.eval(p);
    if (split > 0.0) {
        float2 centre = resolution * 0.5;
        half4 red = scene.eval(centre + (p - centre) / (1.0 + split));
        half4 blue = scene.eval(centre + (p - centre) / (1.0 - split));
        colour = half4(red.r, colour.g, blue.b, min(1.0, red.a + colour.a + blue.a));
    }
    if (strength > 0.0) {
        half4 glow = glow0.eval(p * 0.5) * 0.8 + glow1.eval(p * 0.25) * 0.4 +
            glow2.eval(p * 0.125) * 0.2 + glow3.eval(p * 0.0625) * 0.1;
        colour = min(half4(1.0), colour + glow * strength);
    }
    return colour;
}
"""
