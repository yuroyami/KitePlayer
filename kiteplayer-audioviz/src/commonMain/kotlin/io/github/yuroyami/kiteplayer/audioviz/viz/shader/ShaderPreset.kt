package io.github.yuroyami.kiteplayer.audioviz.viz.shader

import androidx.compose.ui.graphics.drawscope.DrawScope
import io.github.yuroyami.kiteplayer.audioviz.viz.PostSpec
import io.github.yuroyami.kiteplayer.audioviz.viz.VizEnergy
import io.github.yuroyami.kiteplayer.audioviz.viz.VizFamily
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.Kit
import io.github.yuroyami.kiteplayer.audioviz.viz.Layered
import io.github.yuroyami.kiteplayer.audioviz.viz.withCamera

/**
 * A drawing whose whole picture is one program run for every pixel.
 *
 * Everything the shader can read is handed over here, once per frame, in one place. A preset that
 * subclasses this writes SkSL and nothing else, unless it wants values of its own, which go in
 * [extraUniforms].
 *
 * Devices that cannot run shaders fall back to [drawFallback]. That is not a nicety: Android only
 * gained runtime shaders in version 13, and this library still supports version 8.
 */
internal abstract class ShaderPreset(
    private val source: String,
    name: String,
    family: VizFamily,
    bucket: VizEnergy = VizEnergy.Mid,
    /** A number that makes this preset's randomness its own. */
    private val seed: Float = 1f,
    kit: Kit = Kit((seed * 7_919f).toLong() + 13L, detailKind = null),
) : Layered(name, family, bucket, kit) {

    private val program: ShaderProgram by lazy { ShaderProgram(ShaderLibrary.HEADER + source) }
    private val inputs = ShaderInputs(seed)

    /**
     * A narrower glow than the other drawings get.
     *
     * A full screen field is bright nearly everywhere, so the ordinary glow would lift all of it and
     * wash the picture out. Here only the
     * brightest highlights spill, which is what a lit window or a hot core should do.
     */
    override val post: PostSpec get() = if (bucket == VizEnergy.Calm) SHADER_POST_CALM else SHADER_POST

    /** True when this device ran the program. Useful for a catalogue that hides what cannot run. */
    val runs: Boolean get() = program.available

    /** What the compiler said, so a failure is visible rather than a black screen. */
    val compileError: String? get() = program.error

    /** Whether this drawing has something to show where shaders cannot run. Without one it is left out there. */
    open val hasFallback: Boolean get() = false

    /** The front lines up with the shader's own picture, which moves at the camera's full parallax. */
    override val frontParallax: Float get() = 1f

    /** The whole picture is the shader, so there is no separate ground. */
    override val paintsWholeScreen: Boolean get() = true

    /** The shader moves its own picture through the camera, rather than being moved as a flat picture. */
    override val cameraOnEcho: Boolean get() = false

    /** Forgets what the shader remembers. The drawing's own state is reset in [onReset]. */
    override fun resetLayers() {
        inputs.reset()
    }

    /** Moves this drawing's own actors on by one frame. Most shader drawings do it in [extraUniforms]. */
    override fun advance(state: VizRenderState) {}

    /** Values belonging to this drawing alone, on top of the ones every drawing gets. */
    open fun extraUniforms(program: ShaderProgram, state: VizRenderState) {}

    /** What to draw where a shader cannot run. The default is an empty screen. */
    open fun DrawScope.drawFallback(state: VizRenderState) {}

    final override fun DrawScope.drawEcho(state: VizRenderState) {
        if (!program.available) {
            withCamera(camera, 1f) { drawFallback(state) }
            return
        }
        inputs.update(state)
        inputs.publish(program, state, size.width, size.height)
        program.uniform("uCam", camera.panX, camera.panY, camera.zoom, camera.angle)
        program.uniform("uWalk", genes.walk)
        extraUniforms(program, state)
        val brush = program.brush() ?: return
        drawRect(brush)
    }
}

// Half the usual grain as well: over a bright full screen field it shows as static.
private val SHADER_POST: PostSpec = PostSpec(bloom = 0.3f, bloomRadius = 0.05f, threshold = 0.62f, grain = 0.006f)
private val SHADER_POST_CALM: PostSpec = SHADER_POST.copy(glitch = false)
