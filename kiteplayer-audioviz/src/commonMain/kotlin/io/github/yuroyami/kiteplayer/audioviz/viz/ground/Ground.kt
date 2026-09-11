package io.github.yuroyami.kiteplayer.audioviz.viz.ground

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.DrawScope
import io.github.yuroyami.kiteplayer.audioviz.AudioVizAuthoringApi
import io.github.yuroyami.kiteplayer.audioviz.viz.Camera2D
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.ShaderInputs
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.ShaderLibrary
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.ShaderProgram

/** The full-screen fields a drawing can sit on. */
@AudioVizAuthoringApi
public enum class GroundKind { Plasma, Cloud, Stars, Grid, Rings, Spectrogram, Water, Voronoi, Rays, Hatch, Rain, Fog }

/**
 * A live field that fills the canvas under a drawing.
 *
 * One shader drawn straight on the canvas, so in a window it runs on the graphics card. It keeps
 * moving in silence, follows [camera] at [parallax], and fades over [fadeSeconds] when [kind] changes.
 */
@AudioVizAuthoringApi
public class Ground(
    kind: GroundKind,
    public var dim: Float = 1f,
    public val parallax: Float = 0.4f,
    private val camera: Camera2D? = null,
    seed: Float = 1f,
) {
    public var kind: GroundKind = kind
        set(value) {
            if (value == field) return
            leaving = field
            fade = 0f
            field = value
        }

    /** Seconds a change of [kind] takes. */
    public var fadeSeconds: Float = 2f

    /** An extra turn of the palette, for a colour that walks over the song. */
    public var walk: Float = 0f

    private val startKind = kind
    private var leaving: GroundKind? = null
    private var fade = 1f
    private val programs = HashMap<GroundKind, ShaderProgram>()
    private val inputs = ShaderInputs(seed)
    private var phase = 0f
    private var travel = 0f
    private var advancedAt = Float.NaN

    /** Moves the ground's own clocks on by one frame. Safe to call twice in a frame. */
    internal fun advance(state: VizRenderState) {
        if (state.timeSeconds == advancedAt) return
        advancedAt = state.timeSeconds
        val dt = state.deltaSeconds
        val frame = state.frame
        // Never slower than about a third of full pace, so a ground is alive even in silence.
        phase += dt * (0.3f + state.paced(1f))
        val locked = frame.beatConfidence > 0.4f && frame.bpm > 0f
        travel += dt * if (locked) frame.bpm / 60f else state.paced(2f)
        if (fade < 1f) fade = (fade + dt / fadeSeconds.coerceAtLeast(0.05f)).coerceAtMost(1f)
        if (fade >= 1f) leaving = null
    }

    internal fun DrawScope.draw(state: VizRenderState, alpha: Float = 1f) {
        advance(state)
        val arriving = program(kind)
        if (!arriving.available) {
            drawFallback(state)
            return
        }
        inputs.update(state)
        val gone = leaving
        if (gone != null && fade < 1f) {
            paint(program(gone), state, alpha)
            paint(arriving, state, fade * alpha)
        } else {
            paint(arriving, state, alpha)
        }
    }

    private fun DrawScope.paint(program: ShaderProgram, state: VizRenderState, alpha: Float) {
        if (!program.available) return
        inputs.publish(program, state, size.width, size.height)
        val cam = camera
        if (cam != null) {
            program.uniform("uCam", cam.panX * parallax, cam.panY * parallax, 1f + (cam.zoom - 1f) * parallax, cam.angle * parallax)
        } else {
            program.uniform("uCam", 0f, 0f, 1f, 0f)
        }
        program.uniform("uPhase", phase)
        program.uniform("uTravel", travel)
        program.uniform("uDim", dim)
        program.uniform("uWalk", walk)
        val brush = program.brush() ?: return
        drawRect(brush, alpha = alpha)
    }

    /** Where shaders cannot run: two colours of the palette sliding past each other. Dull, and still moving. */
    private fun DrawScope.drawFallback(state: VizRenderState) {
        val shift = phase * 0.05f
        val a = state.palette.cycled(shift, value = 0f, alpha = 0.35f)
        val b = state.palette.cycled(shift + 0.35f, value = 0.05f, alpha = 0.35f)
        val sway = kotlin.math.sin(phase * 0.3f) * size.width * 0.3f
        drawRect(
            Brush.linearGradient(
                listOf(a, b, a),
                start = androidx.compose.ui.geometry.Offset(sway, 0f),
                end = androidx.compose.ui.geometry.Offset(size.width + sway, size.height),
            ),
        )
    }

    private fun program(of: GroundKind): ShaderProgram = programs.getOrPut(of) {
        ShaderProgram(ShaderLibrary.HEADER + GroundShaders.HEADER + GroundShaders.source(of)).also {
            if (it.available) it.child("uNoiseTex", noiseTexture, tiled = true)
        }
    }

    /** Whether the program for [of] compiled, and the compiler's words when it did not. */
    internal fun compileError(of: GroundKind): String? {
        val program = program(of)
        return if (program.available) null else program.error ?: "did not compile"
    }

    public fun reset() {
        phase = 0f
        travel = 0f
        fade = 1f
        leaving = null
        kind = startKind
        leaving = null
        fade = 1f
        inputs.reset()
        advancedAt = Float.NaN
    }
}

/** Draws [ground] across the whole canvas, [alpha] of the way over what is there. */
internal fun DrawScope.drawGround(ground: Ground, state: VizRenderState, alpha: Float = 1f) {
    with(ground) { draw(state, alpha) }
}
