package io.github.yuroyami.kiteplayer.audioviz.viz.shader

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import io.github.yuroyami.kiteplayer.audioviz.viz.VizTransition

/**
 * Mixes the outgoing picture into the incoming one.
 *
 * All the ways of doing it are one program with a switch, rather than a program each, because they
 * differ only in how the mask between the two pictures is worked out. Where there is no way to run
 * a program at all, the caller falls back to drawing one over the other, which is the plain mix.
 */
internal class TransitionBlend {

    private val program = ShaderProgram(ShaderLibrary.HEADER + SOURCE)

    val available: Boolean get() = program.available

    val error: String? get() = program.error

    fun prepare(
        from: ImageBitmap,
        to: ImageBitmap,
        transition: VizTransition,
        progress: Float,
        width: Float,
        height: Float,
    ): Brush? {
        if (!program.available) return null
        program.uniform("uResolution", width, height)
        program.uniform("uProgress", progress.coerceIn(0f, 1f))
        program.uniform("uKind", transition.ordinal.toFloat())
        // The two pictures may be smaller than the canvas, because a blooming drawing renders into
        // a reduced buffer. The shader is told how to stretch each one back up.
        program.uniform("uFromScale", from.width / width, from.height / height)
        program.uniform("uToScale", to.width / width, to.height / height)
        program.child("uFrom", from)
        program.child("uTo", to)
        return program.brush()
    }

    private companion object {
        const val SOURCE = """
uniform shader uFrom;
uniform shader uTo;
uniform float uProgress;
uniform float uKind;
uniform float2 uFromScale;
uniform float2 uToScale;

half4 readFrom(float2 position) { return uFrom.eval(position * uFromScale); }
half4 readTo(float2 position) { return uTo.eval(position * uToScale); }

half4 main(float2 position) {
    float2 uv = position / uResolution;
    float t = uProgress;

    // Crossfade.
    if (uKind < 0.5) {
        return mix(readFrom(position), readTo(position), t);
    }

    // A ragged edge, shaped by noise, sweeping across.
    if (uKind < 1.5) {
        float edge = fbm(uv * 3.0) * 0.6 + uv.x * 0.4;
        float mask = smoothstep(edge - 0.18, edge + 0.18, t * 1.4 - 0.2);
        return mix(readFrom(position), readTo(position), mask);
    }

    // The new picture opening out of the middle.
    if (uKind < 2.5) {
        float reach = length(uv - 0.5) * 1.42;
        float mask = smoothstep(reach + 0.12, reach - 0.12, t * 1.3);
        return mix(readFrom(position), readTo(position), mask);
    }

    // The old one rushes past and the new one arrives from a long way off.
    if (uKind < 3.5) {
        float2 middle = uv - 0.5;
        float2 leaving = middle / max(1.0 - t * 0.85, 0.05) + 0.5;
        float2 arriving = middle / (0.15 + t * 0.85) + 0.5;
        half4 a = readFrom(leaving * uResolution) * (1.0 - t);
        half4 b = readTo(arriving * uResolution) * t;
        return a + b;
    }

    // A hand-off shows only the new drawing. The old one lives on in the echoes it handed over.
    if (uKind > 4.5) {
        return readTo(position);
    }

    // Three hard alternations, then it is done. Loud music only.
    float flicker = step(0.5, fract(t * 6.0));
    float mask = t > 0.8 ? 1.0 : (t < 0.2 ? 0.0 : flicker);
    return mix(readFrom(position), readTo(position), mask);
}
"""
    }
}
