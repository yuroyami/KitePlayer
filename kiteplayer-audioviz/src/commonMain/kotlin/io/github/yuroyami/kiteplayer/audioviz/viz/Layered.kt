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
    private val step = DisplayStep()

    /**
     * Moves everything shared on by one frame. Returns the state the actors integrate, or null when
     * this state was already consumed. A new frame at a consumed instant integrates no time.
     */
    fun advance(state: VizRenderState, size: Size): VizRenderState? {
        val dt = step.of(state) ?: return null
        if (size.height > 0f) aspect = size.width / size.height
        gestures.update(state)
        genes.advance(gestures, dt)
        camera.advance(state)
        split.update(state.frame.bandsRel)
        ground?.walk = genes.walk
        detail?.walk = genes.walk
        return if (dt == state.deltaSeconds) state
            else VizRenderState(state.frame, state.timeSeconds, dt, state.palette, state.musicTime, state.future).also {
                it.motionScale = state.motionScale
                it.lightScale = state.lightScale
            }
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
        step.reset()
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

    /**
     * This drawing's declaration: the drives it names, with what it needs and what it can give up
     * taken from the drawing itself, so those cannot drift from what it does.
     *
     * A drive covers the whole picture, including the ground, the detail layer and the camera,
     * because that is what a viewer sees and what the tests measure. Read it as a delegate, so the
     * drawing is fully built before its own properties are asked for:
     * `override val mapping: VizMapping by mappingOf(...)`.
     * [echoes] and [softBuffer] also declare effects a person's controls can enable later.
     */
    protected fun mappingOf(
        vararg drives: VizDrive,
        silence: VizSilence = VizSilence.Idle,
        silenceSettleSeconds: Float = 2f,
        echoes: Boolean = false,
        softBuffer: Boolean = false,
    ): Lazy<VizMapping> = lazy {
        val needs = LinkedHashSet<VizNeed>()
        val quality = ArrayList<VizQualityControl>()
        if (isRuntimeShader) needs += VizNeed.RuntimeShader
        if (warp != null || ground != null || detail != null) needs += VizNeed.ShaderLayers
        if (echoes || trail > 0f || moodSpec != null) {
            needs += VizNeed.EchoBuffer
            quality += VizQualityControl.EchoResolution
        }
        if (softBuffer || bloom > 0) needs += VizNeed.SoftBuffer
        quality += VizQualityControl.BoundedPool
        VizMapping(drives.toList(), needs, silence, quality, silenceSettleSeconds)
    }

    /** True for a drawing whose whole picture is one runtime shader. */
    protected open val isRuntimeShader: Boolean get() = false

    /** False for full-screen shaders, which apply the camera themselves. */
    protected open val cameraOnEcho: Boolean get() = true

    final override fun DrawScope.draw(state: VizRenderState) {
        kit.advance(state, size)?.let { advance(it) }
        if (cameraOnEcho) withCamera(camera, 1f) { drawEcho(state) } else drawEcho(state)
    }

    final override fun DrawScope.drawFront(state: VizRenderState) {
        kit.advance(state, size)?.let { advance(it) }
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
