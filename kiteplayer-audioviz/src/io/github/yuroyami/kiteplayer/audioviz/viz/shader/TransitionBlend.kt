package io.github.yuroyami.kiteplayer.audioviz.viz.shader

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import io.github.yuroyami.kiteplayer.audioviz.viz.VizTransition

/**
 * Mixes the outgoing picture into the incoming one.
 *
 * All the ways of doing it are one program with a switch, rather than a program each, because they
 * differ only in how the mask between the two pictures is worked out. Where there is no way to run
 * a program at all, the caller falls back to drawing one over the other, which is the plain mix.
 */
internal class TransitionBlend {

    private val program by lazy { ShaderProgram(ShaderLibrary.HEADER + SOURCE) }
    private val maskProgram by lazy { ShaderProgram(ShaderLibrary.HEADER + MASK_SOURCE) }

    val available: Boolean get() = program.available

    val error: String? get() = program.error

    fun canDrawLayers(transition: VizTransition): Boolean =
        transition == VizTransition.Crossfade || transition == VizTransition.StrobeCut ||
            (transition != VizTransition.ZoomThrough && transition != VizTransition.WarpHandoff && maskProgram.available)

    /** A mask for a recorded layer. The drawing stays on the destination canvas, including its GPU. */
    fun mask(transition: VizTransition, progress: Float, width: Float, height: Float, incoming: Boolean): Brush? {
        val t = progress.coerceIn(0f, 1f)
        val constant = when (transition) {
            VizTransition.Crossfade -> t
            VizTransition.StrobeCut -> when {
                t > 0.8f -> 1f
                t < 0.2f -> 0f
                (t * 6f) % 1f >= 0.5f -> 1f
                else -> 0f
            }
            else -> null
        }
        if (constant != null) return SolidColor(Color.White.copy(alpha = if (incoming) constant else 1f - constant))
        maskProgram.uniform("uResolution", width, height)
        maskProgram.uniform("uProgress", t)
        maskProgram.uniform("uKind", transition.ordinal.toFloat())
        maskProgram.uniform("uIncoming", if (incoming) 1f else 0f)
        return maskProgram.brush()
    }

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
        const val MASK = """
uniform float uProgress;
uniform float uKind;
float transitionMask(float2 uv) {
    float t = uProgress;
    if (uKind < 0.5) return t;
    if (uKind < 1.5) {
        float edge = fbm(uv * 3.0) * 0.6 + uv.x * 0.4;
        return smoothstep(edge - 0.18, edge + 0.18, t * 1.4 - 0.2);
    }
    if (uKind < 2.5) {
        float reach = length(uv - 0.5) * 1.42;
        return smoothstep(reach + 0.12, reach - 0.12, t * 1.3);
    }
    float flicker = step(0.5, fract(t * 6.0));
    return t > 0.8 ? 1.0 : (t < 0.2 ? 0.0 : flicker);
}
"""

        const val MASK_SOURCE = MASK + """
uniform float uIncoming;
half4 main(float2 position) {
    float mask = transitionMask(position / uResolution);
    return half4(uIncoming > 0.5 ? mask : 1.0 - mask);
}
"""

        const val SOURCE = MASK + """
uniform shader uFrom;
uniform shader uTo;
uniform float2 uFromScale;
uniform float2 uToScale;
half4 readFrom(float2 position) { return uFrom.eval(position * uFromScale); }
half4 readTo(float2 position) { return uTo.eval(position * uToScale); }

half4 main(float2 position) {
    float2 uv = position / uResolution;
    float t = uProgress;

    // The old one rushes past and the new one arrives from a long way off.
    if (uKind > 2.5 && uKind < 3.5) {
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

    return mix(readFrom(position), readTo(position), transitionMask(uv));
}
"""
    }
}
