package io.github.yuroyami.kiteplayer.audioviz.viz.shader

import androidx.compose.ui.graphics.drawscope.DrawScope
import io.github.yuroyami.kiteplayer.audioviz.viz.PostSpec
import io.github.yuroyami.kiteplayer.audioviz.viz.VizEnergy
import io.github.yuroyami.kiteplayer.audioviz.viz.VizFamily
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.Visualization

/**
 * A shader drawing whose program can be swapped while it runs. Made for writing new ones.
 *
 * Hand [replace] new source whenever the file it came from is saved. If it compiles, the drawing
 * switches to it on the next frame. If it does not, the old program keeps running and the
 * compiler's message is kept in [error], so a typo shows up as a message rather than as a black
 * screen. The source is only the body: the shared library, with the spectrum, the palette and the
 * rest, is put in front of it the same way it is for every built-in shader.
 */
public class LiveShader(
    override val name: String,
    source: String,
) : Visualization {
    override val family: VizFamily get() = VizFamily.Alchemy
    override val bucket: VizEnergy get() = VizEnergy.Mid

    private var body = LiveBody(name, source)

    /** What the compiler said about the last source tried, or null when it compiled. */
    public var error: String? = if (body.runs) null else body.compileError
        private set

    /** Compiles [source] and switches to it if it worked. Answers the compiler's message, or null. */
    public fun replace(source: String): String? {
        val next = LiveBody(name, source)
        return if (next.runs) {
            body = next
            error = null
            null
        } else {
            val message = next.compileError ?: "the shader did not compile"
            error = message
            message
        }
    }

    override val post: PostSpec get() = body.post

    override fun reset() {
        body.reset()
    }

    override fun DrawScope.draw(state: VizRenderState) {
        with(body) { draw(state) }
    }
}

private class LiveBody(name: String, source: String) : ShaderPreset(
    source = source,
    name = name,
    family = VizFamily.Alchemy,
)
