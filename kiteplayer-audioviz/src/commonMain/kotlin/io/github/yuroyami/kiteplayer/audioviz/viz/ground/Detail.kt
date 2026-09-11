package io.github.yuroyami.kiteplayer.audioviz.viz.ground

import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import io.github.yuroyami.kiteplayer.audioviz.AudioVizAuthoringApi
import io.github.yuroyami.kiteplayer.audioviz.viz.Camera2D
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.ShaderInputs
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.ShaderLibrary
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.ShaderProgram

/** The fine textures a drawing can wear on top. */
@AudioVizAuthoringApi
public enum class DetailKind { Hatch, Dots, Specks, Grid, Scan }

/**
 * A fine, dim, moving texture added over everything: the smallest of the three scales. Added as
 * light, so it can never darken what is under it.
 */
@AudioVizAuthoringApi
public class Detail(
    public val kind: DetailKind,
    public var strength: Float = 1f,
    private val camera: Camera2D? = null,
    public val parallax: Float = 1.15f,
) {
    private val program: ShaderProgram by lazy {
        ShaderProgram(ShaderLibrary.HEADER + GroundShaders.HEADER + source(kind)).also {
            if (it.available) it.child("uNoiseTex", noiseTexture, tiled = true)
        }
    }
    private val inputs = ShaderInputs(5f)
    private var phase = 0f
    private var advancedAt = Float.NaN

    /** An extra turn of the palette, for a colour that walks over the song. */
    public var walk: Float = 0f

    internal fun DrawScope.draw(state: VizRenderState, alpha: Float = 1f) {
        if (state.timeSeconds != advancedAt) {
            advancedAt = state.timeSeconds
            phase += state.deltaSeconds * (0.3f + state.paced(2f))
        }
        if (strength <= 0f || !program.available) return
        inputs.update(state)
        inputs.publish(program, state, size.width, size.height)
        val cam = camera
        if (cam != null) {
            program.uniform("uCam", cam.panX * parallax, cam.panY * parallax, 1f + (cam.zoom - 1f) * parallax, cam.angle * parallax)
        } else {
            program.uniform("uCam", 0f, 0f, 1f, 0f)
        }
        program.uniform("uPhase", phase)
        program.uniform("uTravel", phase)
        program.uniform("uDim", strength * alpha)
        program.uniform("uWalk", walk)
        val brush = program.brush() ?: return
        drawRect(brush, blendMode = BlendMode.Plus)
    }

    /** Whether the program compiled, and the compiler's words when it did not. */
    internal val compileError: String? get() = if (program.available) null else program.error ?: "did not compile"

    public fun reset() {
        phase = 0f
        inputs.reset()
        advancedAt = Float.NaN
    }

    private companion object {
        fun source(kind: DetailKind): String = when (kind) {
            DetailKind.Hatch -> """
half4 main(float2 position) {
    float2 uv = groundUv(position);
    float angle = 0.5 + uBarPhase * 1.5708;
    float2 across = float2(cos(angle), sin(angle));
    float lines = smoothstep(0.82, 1.0, sin(dot(uv, across) * 90.0 + uPhase * 7.0));
    return detailOut(tint(0.6) * lines * (0.035 + 0.05 * uTreble));
}
"""
            DetailKind.Dots -> """
half4 main(float2 position) {
    float2 uv = groundUv(position);
    float2 g = fract(uv * 22.0 + float2(uPhase * 0.35, uPhase * 0.1)) - 0.5;
    float level = band(fract(uv.x * 0.25 + 0.5));
    float spot = smoothstep(0.08 + 0.22 * level * (0.5 + uTreble), 0.03, length(g));
    return detailOut(tint(uv.x * 0.2) * spot * 0.06);
}
"""
            DetailKind.Specks -> """
half4 main(float2 position) {
    float2 uv = groundUv(position);
    float3 colour = float3(0.0);
    for (int layer = 0; layer < 2; layer++) {
        float scale = 12.0 + float(layer) * 9.0;
        float2 q = uv * scale + float2(uPhase * (0.8 + float(layer) * 0.5), -uPhase * 0.35);
        float2 cell = floor(q);
        float h = hash21(cell + float(layer) * 11.0);
        float2 at = cell + 0.5 + 0.8 * (fract(float2(h * 31.0, h * 57.0)) - 0.5);
        float speck = smoothstep(0.1, 0.0, length(q - at)) * step(0.55, h);
        colour += tint(h) * speck * (0.14 + 0.25 * uHat);
    }
    return detailOut(colour);
}
"""
            DetailKind.Grid -> """
half4 main(float2 position) {
    float2 uv = groundUv(position);
    float2 g = abs(fract(uv * (4.0 + uBass) + float2(uPhase * 0.2, 0.0)) - 0.5);
    float line = smoothstep(0.46, 0.5, max(g.x, g.y));
    return detailOut(tint(0.4) * line * (0.04 + 0.06 * uKick));
}
"""
            DetailKind.Scan -> """
half4 main(float2 position) {
    float rows = sin(position.y * 1.6 + uPhase * 18.0) * 0.5 + 0.5;
    float flicker = tex(float2(position.y * 0.004, uPhase * 0.5));
    return detailOut(tint(0.5) * rows * flicker * 0.045);
}
"""
        }
    }
}

/** Adds [detail] across the whole canvas, at [alpha] of its strength. */
internal fun DrawScope.drawDetail(detail: Detail, state: VizRenderState, alpha: Float = 1f) {
    with(detail) { draw(state, alpha) }
}
