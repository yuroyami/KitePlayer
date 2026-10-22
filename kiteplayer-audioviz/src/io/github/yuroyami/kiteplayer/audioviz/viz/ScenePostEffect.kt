package io.github.yuroyami.kiteplayer.audioviz.viz

import androidx.compose.ui.graphics.RenderEffect

/** Finishes one rasterized scene. All branches read the same source instead of replaying it. */
internal expect class ScenePostEffect() {
    fun prepare(spec: PostSpec, width: Float, height: Float, strength: Float, split: Float): RenderEffect?
    fun close()
}

/** The scene is already a texture here, so three samples do not execute the scene shader again. */
internal const val FRINGE_SHADER = """
uniform shader scene;
uniform float2 resolution;
uniform float split;
half4 main(float2 p) {
    float2 centre = resolution * 0.5;
    half4 green = scene.eval(p);
    if (split == 0.0) return green;
    half4 red = scene.eval(centre + (p - centre) / (1.0 + split));
    half4 blue = scene.eval(centre + (p - centre) / (1.0 - split));
    return half4(red.r, green.g, blue.b, min(1.0, red.a + green.a + blue.a));
}
"""
