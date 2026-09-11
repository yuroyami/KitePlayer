package io.github.yuroyami.kiteplayer.audioviz.viz

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import io.github.yuroyami.kiteplayer.audioviz.viz.ground.Detail
import io.github.yuroyami.kiteplayer.audioviz.viz.ground.DetailKind
import io.github.yuroyami.kiteplayer.audioviz.viz.ground.Ground
import io.github.yuroyami.kiteplayer.audioviz.viz.ground.GroundKind
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Genes
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Gestures

/**
 * What every layered drawing shares: a camera, the gestures, the genes, a ground, a detail layer,
 * the spectrum split into parts, anchors, and a random source. All of it moves on once a frame.
 */
internal class Kit(
    seed: Long,
    groundKind: GroundKind? = null,
    groundDim: Float = 1f,
    groundParallax: Float = 0.4f,
    val camera: Camera2D = Camera2D(seed = seed.toInt()),
    detailKind: DetailKind? = null,
    detailStrength: Float = 1f,
    splitParts: Int = 3,
) {
    val gestures = Gestures()
    val genes = Genes(seed)
    val random = Rng(seed * 31L + 17L)
    val split = SpectrumSplit(splitParts)
    val ground: Ground? = groundKind?.let { Ground(it, groundDim, groundParallax, camera, (seed % 97L).toFloat()) }
    val detail: Detail? = detailKind?.let { Detail(it, detailStrength, camera) }

    val anchors: List<Anchor>
        field = ArrayList<Anchor>()

    /** Width over height of the canvas, as last seen. */
    var aspect: Float = 16f / 9f
        private set
    private var advancedAt = Float.NaN

    /** Moves everything shared on by one frame. True on the first call of a frame. */
    fun advance(state: VizRenderState, size: Size): Boolean {
        if (state.timeSeconds == advancedAt) return false
        advancedAt = state.timeSeconds
        if (size.height > 0f) aspect = size.width / size.height
        gestures.update(state)
        genes.advance(gestures, state.deltaSeconds)
        camera.advance(state)
        split.update(state.frame.bandsRel, state.deltaSeconds)
        ground?.walk = genes.walk
        detail?.walk = genes.walk
        return true
    }

    /** Sets anchor [index] to a point given in shares of the screen before the camera. */
    fun place(index: Int, x: Float, y: Float, parallax: Float = 1f) {
        while (anchors.size <= index) anchors.add(Anchor())
        camera.project(x, y, aspect, parallax, anchors[index])
    }

    fun reset() {
        gestures.reset()
        genes.restart()
        camera.reset()
        random.reset()
        split.reset()
        ground?.reset()
        detail?.reset()
        advancedAt = Float.NaN
    }
}

/**
 * A drawing in layers: its echo layer and its front both go through the camera, the ground and the
 * detail come from the [Kit], and the actors move once a frame in [advance].
 *
 * Positions should be worked out as shares of the screen and turned into pixels while drawing,
 * because the echo layer may be a smaller bitmap than the canvas the front is drawn on.
 */
internal abstract class Layered(
    final override val name: String,
    final override val family: VizFamily,
    final override val bucket: VizEnergy,
    protected val kit: Kit,
) : Visualization {
    final override val ground: Ground? get() = kit.ground
    final override val anchors: List<Anchor> get() = kit.anchors
    final override val genes: Genes get() = kit.genes
    final override val detail: Detail? get() = kit.detail

    protected val camera: Camera2D get() = kit.camera
    protected val gestures: Gestures get() = kit.gestures
    protected val random: Rng get() = kit.random
    protected val split: SpectrumSplit get() = kit.split

    /** How far the front moves with the camera. A little more than the echo layer, so it reads as nearer. */
    protected open val frontParallax: Float get() = 1.15f

    /** False for full-screen shaders, which apply the camera themselves. */
    protected open val cameraOnEcho: Boolean get() = true

    final override fun DrawScope.draw(state: VizRenderState) {
        if (kit.advance(state, size)) advance(state)
        if (cameraOnEcho) withCamera(camera, 1f) { drawEcho(state) } else drawEcho(state)
    }

    final override fun DrawScope.drawFront(state: VizRenderState) {
        if (kit.advance(state, size)) advance(state)
        withCamera(camera, frontParallax) { drawTop(state) }
    }

    /** Moves this drawing's own actors on by one frame, before anything of it is drawn. */
    protected abstract fun advance(state: VizRenderState)

    /** The layer that leaves echoes. */
    protected abstract fun DrawScope.drawEcho(state: VizRenderState)

    /** The sharp layer over the echoes. */
    protected open fun DrawScope.drawTop(state: VizRenderState) {}

    final override fun reset() {
        kit.reset()
        resetLayers()
        onReset()
    }

    /** For a base class between this one and a drawing, so its own state is reset whatever the drawing does. */
    internal open fun resetLayers() {}

    protected open fun onReset() {}
}
