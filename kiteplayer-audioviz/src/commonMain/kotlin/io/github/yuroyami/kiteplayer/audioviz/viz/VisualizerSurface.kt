package io.github.yuroyami.kiteplayer.audioviz.viz

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas as GraphicsCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kiteplayer.audioviz.AudioVizAuthoringApi
import io.github.yuroyami.kiteplayer.audioviz.SpectrumFrame
import io.github.yuroyami.kiteplayer.audioviz.viz.ground.drawDetail
import io.github.yuroyami.kiteplayer.audioviz.viz.ground.drawGround
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.TransitionBlend
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.canDrawRuntimeShaders
import kotlin.time.TimeSource
import kotlin.math.pow

/**
 * Draws [visualization] every display frame, in layers: the ground, the echo layer, the front, and
 * then the finishing pass.
 *
 * The echo layer is the one with a memory. A drawing with a trail draws it into a transparent
 * bitmap that also holds the last frame, faded and usually moved, so every mark leaves an echo.
 * The ground and the front are drawn straight on the canvas, so in a window they cost the graphics
 * card rather than the processor. Two bitmaps are kept and swapped, because a frame cannot safely be
 * read and written at once.
 *
 * [frame] is a function, read once per step of the surface's own clock, so a change of analysis does
 * not redraw the surface by itself. [future] is optional and lets a drawing see the audio that has
 * been analysed but not yet played.
 */
@Composable
@AudioVizAuthoringApi
public fun VisualizerSurface(
    visualization: Visualization,
    frame: () -> SpectrumFrame,
    palette: VizPalette,
    modifier: Modifier = Modifier,
    future: VizFuture? = null,
    /** Whether to lay the finishing pass over it: glow, darker corners and grain. */
    post: Boolean = true,
    /** Parts of the finishing pass to leave out for every drawing. */
    switches: PostSwitches = PostSwitches.All,
    /** Filled with how long each part of a frame took, when given. */
    stats: RenderStats? = null,
    /** How much of the canvas trailing drawings render at, and whether that may drop when slow. */
    quality: RenderQuality? = null,
    /** Frames a second to redraw at, or 0 for every display frame. For thumbnails and previews. */
    framesPerSecond: Int = 0,
    /**
     * Keeps the spectrum, the colours and the shapes, and damps what throws the picture about:
     * the camera's punch, shake and cuts, the trail's swim, and any flash at all.
     */
    reducedMotion: Boolean = false,
    /**
     * False draws the background alone. The audio is not touched: the analysis keeps running and
     * the player keeps playing, so turning the picture off costs nothing but the picture.
     */
    visible: Boolean = true,
) {
    val renderQuality = quality ?: remember { RenderQuality() }
    val clock = remember { VizClock() }
    if (!visible) {
        Canvas(modifier.fillMaxSize()) { drawRect(palette.background) }
        return
    }
    val motion = if (reducedMotion) REDUCED_MOTION else 1f
    val flashes = if (reducedMotion) 0 else FLASHES_PER_SECOND
    if (!post) {
        VisualizerCanvas(visualization, frame, palette, clock, modifier.fillMaxSize(), future, stats, renderQuality, framesPerSecond, motion, flashes)
        return
    }
    PostProcessedBox(
        spec = { if (post) visualization.post.masked(switches).calmed(reducedMotion) else PostSpec.Off },
        frame = { clock.tick?.frame ?: frame() },
        modifier = modifier,
        stats = stats,
    ) {
        VisualizerCanvas(visualization, frame, palette, clock, Modifier.fillMaxSize(), future, stats, renderQuality, framesPerSecond, motion, flashes)
    }
}

/** The drawing itself, before the finishing pass. */
@Composable
private fun VisualizerCanvas(
    visualization: Visualization,
    frame: () -> SpectrumFrame,
    palette: VizPalette,
    clock: VizClock,
    modifier: Modifier = Modifier,
    future: VizFuture? = null,
    stats: RenderStats? = null,
    quality: RenderQuality? = null,
    framesPerSecond: Int = 0,
    motionScale: Float = 1f,
    flashesPerSecond: Int = FLASHES_PER_SECOND,
) {
    val guard = remember(flashesPerSecond) { FlashGuard(flashesPerSecond) }
    val calmReading = remember { CalmReading() }
    // Buffers of its own for each drawing, so no redraw lays another drawing's echoes under it.
    val buffers = remember(visualization) { FeedbackBuffers() }
    val fade = remember { PaletteFade() }
    // Read at every step, so a replaced analysis source is followed without restarting the drawing.
    val currentFrame by rememberUpdatedState(frame)

    LaunchedEffect(visualization, framesPerSecond) {
        visualization.restart()
        buffers.discard()
        clock.run(framesPerSecond, { currentFrame() })
    }

    Canvas(modifier) {
        // The step is all that moves here, so this canvas redraws at its own rate however often the
        // shared analysis changes.
        val tick = clock.tick
        val fresh = clock.firstDraw(tick)
        // A redraw of a step already drawn passes no time, so nothing below counts it twice.
        val deltaSeconds = if (fresh) tick?.deltaSeconds ?: 0f else 0f
        val heard = tick?.frame ?: Snapshot.withoutReadObservation { frame() }
        val current = calmReading.of(heard, motionScale, deltaSeconds)
        // The palette asked for, faded in over eight usable pulses or three seconds, leaning towards the key.
        val shown = fade.advance(palette, current, deltaSeconds)
        val state = VizRenderState(
            frame = current,
            timeSeconds = tick?.timeSeconds ?: 0f,
            deltaSeconds = deltaSeconds,
            palette = shown,
            musicTime = tick?.musicTime ?: 0f,
            future = future,
        )
        state.motionScale = motionScale
        state.lightScale = guard.allowance(lightFor(current.energy), deltaSeconds)
        val trail = visualization.trailAt(current.mood).coerceIn(0f, 0.995f)
        if (trail <= 0f) {
            composeFrame(visualization, state, null, stats)
            return@Canvas
        }
        // A redraw of the same step shows the echo layer that step already rendered.
        if (!fresh) {
            buffers.previous()?.let {
                composeFrame(visualization, state, it, stats)
                return@Canvas
            }
        }

        // A drawing that blooms is already soft, so its buffer runs at a fraction of the canvas.
        val started = TimeSource.Monotonic.markNow()
        val scale = (if (visualization.bloom > 0) SOFT_BUFFER_SCALE else 1f) * (quality?.stepped ?: 1f)
        val target = buffers.next(size, scale)
        val previous = buffers.previous()
        if (target == null) {
            composeFrame(visualization, state, null, stats)
            return@Canvas
        }
        val bufferSize = Size(target.width.toFloat(), target.height.toFloat())
        buffers.scope.draw(this, layoutDirection, buffers.canvas, bufferSize) {
            drawEchoLayer(visualization, state, previous, stats)
        }
        quality?.let {
            if (fresh) it.afterFrame(started.elapsedNow().inWholeMicroseconds / 1000f, deltaSeconds)
            stats?.scale = it.stepped
        }
        composeFrame(visualization, state, target, stats)
        buffers.swap()
    }
}

/**
 * One step of a surface's clock: the reading it draws from and the times it draws at.
 *
 * A frame draws from the newest step alone, so the analysis cannot redraw a surface between steps,
 * and a redraw of a step already drawn passes no time.
 */
private class VizTick(
    /** Counts the steps, so a redraw of this one can be told from a new one. */
    val serial: Long,
    val frame: SpectrumFrame,
    val timeSeconds: Float,
    val deltaSeconds: Float,
    val musicTime: Float,
)

/** The steps of one surface's clock, and which of them was drawn last. */
private class VizClock {
    /** The newest step, or null before the first. Snapshot state, so a new step redraws the surface. */
    var tick: VizTick? by mutableStateOf(null)
        private set
    private var drawn = 0L

    /**
     * Steps every display frame, or at [framesPerSecond] when that is above 0, until cancelled.
     * [frame] is read once a step, and [onStep] runs in the same frame callback, before the frame
     * that shows the step is drawn.
     */
    suspend fun run(framesPerSecond: Int, frame: () -> SpectrumFrame, onStep: (VizTick) -> Unit = {}) {
        val every = if (framesPerSecond > 0) 1f / framesPerSecond else 0f
        var started = false
        var previousNanos = 0L
        var sinceStep = 0f
        var due = 0f
        while (true) {
            withFrameNanos { nowNanos ->
                if (started) {
                    val gap = ((nowNanos - previousNanos) / 1_000_000_000.0).toFloat()
                    sinceStep += gap
                    due += gap
                    // On the display frame nearest the time a step is due, so a cap of 30 on a
                    // 120 Hz screen steps every fourth frame rather than every fifth.
                    if (due + gap / 2f >= every) {
                        onStep(step(frame(), sinceStep))
                        sinceStep = 0f
                        // The remainder carries, so the steps average the cap, but a stall does not
                        // turn into a burst of steps.
                        due = (due - every).coerceIn(-every / 2f, every / 2f)
                    }
                }
                previousNanos = nowNanos
                started = true
            }
        }
    }

    private fun step(heard: SpectrumFrame, waited: Float): VizTick {
        val before = tick
        // Clamped, so a stalled window does not teleport every particle off screen.
        val delta = waited.coerceIn(0f, 0.1f)
        val next = VizTick(
            serial = (before?.serial ?: 0L) + 1L,
            frame = heard,
            timeSeconds = (before?.timeSeconds ?: 0f) + delta,
            deltaSeconds = delta,
            // The music clock slows in a quiet passage and stops in silence.
            musicTime = (before?.musicTime ?: 0f) + delta * heard.motionRate,
        )
        tick = next
        return next
    }

    /** True the first time [tick] is drawn. False for a redraw of the same step, and before any step. */
    fun firstDraw(tick: VizTick?): Boolean {
        val serial = tick?.serial ?: 0L
        if (serial == drawn) return false
        drawn = serial
        return true
    }
}

/**
 * The echo layer of one frame, on a transparent bitmap: the frame before it faded and moved, then
 * the new drawing. Pass the bitmap from the frame before as [previous].
 *
 * Public because offscreen rendering has to produce the same picture the window does. Lay the
 * result over the rest with [drawComposedFrame].
 */
@AudioVizAuthoringApi
public fun DrawScope.drawVisualizationFrame(
    visualization: Visualization,
    state: VizRenderState,
    previous: ImageBitmap?,
) {
    drawEchoLayer(visualization, state, previous, null)
}

/**
 * One whole frame: the background, the ground, the echo layer and the front.
 *
 * [echo] is the drawing's echo bitmap for this frame from [drawVisualizationFrame]. Pass null for a
 * drawing with no trail, and its echo layer is drawn straight here instead.
 */
@AudioVizAuthoringApi
public fun DrawScope.drawComposedFrame(visualization: Visualization, state: VizRenderState, echo: ImageBitmap?) {
    composeFrame(visualization, state, echo, null)
}

/** The same frame, with the time spent on each layer noted in [stats] when given. */
internal fun DrawScope.composeFrame(
    visualization: Visualization,
    state: VizRenderState,
    echo: ImageBitmap?,
    stats: RenderStats?,
) {
    drawRect(state.palette.background)
    visualization.ground?.let { ground ->
        val started = if (stats != null) TimeSource.Monotonic.markNow() else null
        drawGround(ground, state)
        if (started != null) stats?.addGround(started.elapsedNow().inWholeMicroseconds / 1000f)
    }
    if (echo != null) {
        stretch(echo, blendMode = if (visualization.echoBlend == EchoBlend.Add) BlendMode.Plus else BlendMode.SrcOver)
    } else {
        val started = if (stats != null) TimeSource.Monotonic.markNow() else null
        with(visualization) { draw(state) }
        if (started != null) stats?.addScene(started.elapsedNow().inWholeMicroseconds / 1000f)
    }
    val started = if (stats != null) TimeSource.Monotonic.markNow() else null
    with(visualization) { drawFront(state) }
    if (started != null) stats?.addFront(started.elapsedNow().inWholeMicroseconds / 1000f)
    visualization.detail?.let { drawDetail(it, state) }
}

/** Clears the bitmap, lays the last frame back faded and moved, then draws the new echo layer. */
internal fun DrawScope.drawEchoLayer(
    visualization: Visualization,
    state: VizRenderState,
    previous: ImageBitmap?,
    stats: RenderStats?,
) {
    drawRect(Color.Transparent, blendMode = BlendMode.Clear)
    val trail = visualization.trailAt(state.frame.mood).coerceIn(0f, 0.995f)
    if (previous != null && trail > 0f) {
        val started = if (stats != null) TimeSource.Monotonic.markNow() else null
        replayPrevious(previous, visualization, state, trail)
        if (started != null) stats?.addWarp(started.elapsedNow().inWholeMicroseconds / 1000f)
    }
    visualization.onPreviousFrame(previous)
    val started = if (stats != null) TimeSource.Monotonic.markNow() else null
    with(visualization) { draw(state) }
    if (started != null) stats?.addScene(started.elapsedNow().inWholeMicroseconds / 1000f)
}

/**
 * The share of the last frame that survives a frame that took [deltaSeconds].
 *
 * [trail] is the share that survives one sixtieth of a second, so this is 2^(-dt / half life) with
 * that half life. Without it a drawing fades once a frame, and the same drawing holds its trail
 * twice as long on a 120 Hz screen as on a 60 Hz one.
 */
internal fun retentionOf(trail: Float, deltaSeconds: Float): Float {
    if (trail <= 0f) return 0f
    val dt = deltaSeconds.takeIf { it.isFinite() && it > 0f } ?: (1f / 60f)
    return trail.toDouble().pow((dt * 60f).toDouble()).toFloat().coerceIn(0f, 0.995f)
}

/** Paints the frame before this one back down, faded and moved. */
private fun DrawScope.replayPrevious(
    previous: ImageBitmap,
    visualization: Visualization,
    state: VizRenderState,
    declaredTrail: Float,
) {
    val trail = retentionOf(declaredTrail, state.deltaSeconds)
    val mood = state.frame.mood
    val echo = visualization.echo(state)
    // Per second rather than per frame, so the look does not change with the refresh rate.
    val frames = state.deltaSeconds * 60f
    val zoomX = 1f + (echo.zoomX - 1f) * frames
    val zoomY = 1f + (echo.zoomY - 1f) * frames
    val spin = echo.spin * state.deltaSeconds
    val shiftX = echo.driftX * state.deltaSeconds * size.width
    val shiftY = echo.driftY * state.deltaSeconds * size.height
    val pivot = Offset(echo.centreX * size.width, echo.centreY * size.height)
    val copy = echo.copy
    val copyShare = copy?.share?.coerceIn(0f, 0.8f) ?: 0f

    // Whatever comes back must total LESS than one, or the picture climbs to white in about a
    // second. So the bloom copies and the echo copy come out of the trail's budget.
    val copies = visualization.bloom
    val bloomShare = if (copies > 0) visualization.bloomShareAt(mood).coerceIn(0f, 0.8f) else 0f

    // A per pixel warp replaces the lot: it scales, turns, shifts, copies and fades by itself.
    // Where the buffer cannot take a shader, the plain replay below stands in for it.
    val spec = visualization.warp
    if (spec != null && canDrawRuntimeShaders()) {
        val runner = spec.runner
        val ready = runner.prepare(
            previous = previous,
            state = state,
            width = size.width,
            height = size.height,
            zoomX = zoomX,
            zoomY = zoomY,
            spin = spin,
            amount = spec.amount(mood),
            decay = trail,
            shiftX = shiftX,
            shiftY = shiftY,
            centreX = pivot.x,
            centreY = pivot.y,
            copy = copy,
        )
        if (ready) {
            val brush = runner.brush()
            if (brush != null) {
                drawRect(brush)
                return
            }
        }
    }

    withTransform({
        translate(shiftX, shiftY)
        if (zoomX != 1f || zoomY != 1f) scale(zoomX, zoomY, pivot)
        if (spin != 0f) rotate(spin * 57.29578f, pivot)
    }) {
        drawImage(previous, alpha = trail * (1f - bloomShare) * (1f - copyShare))
        // The bloom copies sit slightly wider and much fainter, which spreads light outward.
        var weight = 0f
        for (copyIndex in 1..copies) weight += 1f / copyIndex
        for (copyIndex in 1..copies) {
            val spread = 1f + copyIndex * 0.014f
            withTransform({ scale(spread, spread, pivot) }) {
                drawImage(
                    previous,
                    alpha = trail * bloomShare * (1f - copyShare) * (1f / copyIndex) / weight,
                    blendMode = BlendMode.Plus,
                )
            }
        }
    }
    if (copy != null && copyShare > 0f) {
        val middle = Offset(size.width / 2f, size.height / 2f)
        withTransform({
            scale(copy.zoom * if (copy.mirrorX) -1f else 1f, copy.zoom * if (copy.mirrorY) -1f else 1f, middle)
            if (copy.angle != 0f) rotate(copy.angle * 57.29578f, middle)
        }) {
            drawImage(previous, alpha = trail * copyShare, blendMode = BlendMode.Plus)
        }
    }
}

/**
 * Draws one visualisation's echo layer into a bitmap of its own, keeping its history.
 *
 * Two are needed while one drawing is being replaced by another, and each has to keep its own
 * history or the outgoing one loses its trail halfway through the change.
 */
internal class VizRenderer {
    private val buffers = FeedbackBuffers()
    private val scope = CanvasDrawScope()
    private var inherited: ImageBitmap? = null

    /** The frame rendered last, or null before the first. */
    var latest: ImageBitmap? = null
        private set

    /** Makes [picture] the frame before this renderer's next one, which is how a hand-off works. */
    fun inherit(picture: ImageBitmap?) {
        inherited = picture
    }

    /** Renders one echo layer and answers the bitmap it went into, or null when there is no room. */
    fun render(
        into: DrawScope,
        visualization: Visualization,
        state: VizRenderState,
        quality: Float = 1f,
        stats: RenderStats? = null,
    ): ImageBitmap? {
        val requestedScale = (if (visualization.bloom > 0) SOFT_BUFFER_SCALE else 1f) * quality
        // A shader normally draws directly on the GPU. The occasional snapshot needed to seed
        // another drawing's history must not turn it into a full-resolution CPU shader frame.
        val scale = if (visualization.paintsWholeScreen && visualization.trailAt(state.mood) <= 0f) {
            minOf(requestedScale, 256f / into.size.maxDimension.coerceAtLeast(1f))
        } else requestedScale
        val target = buffers.next(into.size, scale) ?: return null
        inherited?.let {
            buffers.seed(it)
            inherited = null
        }
        val previous = buffers.previous()
        val size = Size(target.width.toFloat(), target.height.toFloat())
        scope.draw(into, into.layoutDirection, buffers.canvas, size) {
            drawEchoLayer(visualization, state, previous, stats)
        }
        buffers.swap()
        latest = target
        return target
    }

    /** [render] for a new step, and the frame already rendered for a redraw of the same step. */
    fun frameFor(
        fresh: Boolean,
        into: DrawScope,
        visualization: Visualization,
        state: VizRenderState,
        quality: Float = 1f,
        stats: RenderStats? = null,
    ): ImageBitmap? = latest?.takeIf { !fresh } ?: render(into, visualization, state, quality, stats)

    fun discard() {
        buffers.discard()
        latest = null
        inherited = null
    }
}

/** Two bitmaps, swapped each frame, rebuilt when the canvas resizes. */
private class FeedbackBuffers {
    val scope = CanvasDrawScope()
    private var front: ImageBitmap? = null
    private var back: ImageBitmap? = null
    private var frontCanvas: GraphicsCanvas? = null
    private var backCanvas: GraphicsCanvas? = null
    private var hasPrevious = false
    private var width = 0
    private var height = 0
    val canvas: GraphicsCanvas get() = checkNotNull(frontCanvas)

    /** The bitmap to draw this frame into, or null when the canvas has no area yet. */
    fun next(size: Size, scale: Float): ImageBitmap? {
        val wanted = (size.width * scale).toInt()
        val tall = (size.height * scale).toInt()
        if (wanted <= 0 || tall <= 0) return null
        if (front == null || width != wanted || height != tall) {
            val before = previous()
            front = ImageBitmap(wanted, tall).also { frontCanvas = GraphicsCanvas(it) }
            back = ImageBitmap(wanted, tall).also { backCanvas = GraphicsCanvas(it) }
            hasPrevious = false
            width = wanted
            height = tall
            // A new size keeps the picture that was there, stretched, so the trail carries on.
            before?.let { seed(it) }
        }
        return front
    }

    /** The bitmap from last frame, or null on the first frame after a resize or a change. */
    fun previous(): ImageBitmap? = if (hasPrevious) back else null

    /** Paints [picture] in as last frame, stretched to fit, so the next frame builds on it. */
    fun seed(picture: ImageBitmap) {
        val canvas = backCanvas ?: return
        val area = Size(width.toFloat(), height.toFloat())
        scope.draw(Density(1f), LayoutDirection.Ltr, canvas, area) {
            drawRect(Color.Transparent, blendMode = BlendMode.Clear)
            drawImage(
                image = picture,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(picture.width, picture.height),
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(width, height),
            )
        }
        hasPrevious = true
    }

    fun swap() {
        val held = front
        front = back
        back = held
        val heldCanvas = frontCanvas
        frontCanvas = backCanvas
        backCanvas = heldCanvas
        hasPrevious = true
    }

    fun discard() {
        front = null
        back = null
        frontCanvas = null
        backCanvas = null
        hasPrevious = false
        width = 0
        height = 0
    }
}

/**
 * How much of the canvas a blooming drawing's buffer covers on each axis.
 *
 * The buffer is an ordinary bitmap, so every pass over it is processor work. Area is the square of
 * this number, so 0.45 costs a fifth of full resolution, and the drawings that use it are soft anyway.
 */
internal const val SOFT_BUFFER_SCALE: Float = 0.45f

/**
 * The same surface, with a director deciding what to show and how to change to it.
 *
 * During a change drawings with history keep their own feedback buffers. Other drawings stay on
 * the destination canvas, including its GPU, and layer masks mix them without CPU snapshots.
 * The two grounds and the two fronts are faded into each other on the canvas.
 */
@Composable
@AudioVizAuthoringApi
public fun DirectedVisualizerSurface(
    director: VizDirector,
    frame: () -> SpectrumFrame,
    palette: VizPalette,
    modifier: Modifier = Modifier,
    future: VizFuture? = null,
    /** Whether to lay the finishing pass over it: glow, darker corners and grain. */
    post: Boolean = true,
    /** Parts of the finishing pass to leave out for every drawing. */
    switches: PostSwitches = PostSwitches.All,
    /** Filled with how long each part of a frame took, when given. */
    stats: RenderStats? = null,
    /** How much of the canvas trailing drawings render at, and whether that may drop when slow. */
    quality: RenderQuality? = null,
    /**
     * Keeps the spectrum, the colours and the shapes, and damps what throws the picture about:
     * the camera's punch, shake and cuts, the trail's swim, any flash, and every change of drawing
     * becomes a plain fade.
     */
    reducedMotion: Boolean = false,
    /**
     * False draws the background alone. The audio is not touched: the analysis keeps running and
     * the player keeps playing, so turning the picture off costs nothing but the picture.
     */
    visible: Boolean = true,
    /** Frames a second to redraw at, or 0 for every display frame. A cap under the display's rate skips frames. */
    framesPerSecond: Int = 0,
) {
    val renderQuality = quality ?: remember { RenderQuality() }
    val clock = remember { VizClock() }
    if (!visible) {
        Canvas(modifier.fillMaxSize()) { drawRect(palette.background) }
        return
    }
    val motion = if (reducedMotion) REDUCED_MOTION else 1f
    val flashes = if (reducedMotion) 0 else FLASHES_PER_SECOND
    director.calmChanges = reducedMotion
    if (!post) {
        DirectedCanvas(director, frame, palette, clock, framesPerSecond, modifier.fillMaxSize(), future, stats, renderQuality, motion, flashes)
        return
    }
    PostProcessedBox(
        spec = { if (post) director.current.post.masked(switches).calmed(reducedMotion) else PostSpec.Off },
        frame = { clock.tick?.frame ?: frame() },
        modifier = modifier,
        stats = stats,
    ) {
        DirectedCanvas(director, frame, palette, clock, framesPerSecond, Modifier.fillMaxSize(), future, stats, renderQuality, motion, flashes)
    }
}

/** The directed drawing itself, before the finishing pass. */
@Composable
private fun DirectedCanvas(
    director: VizDirector,
    frame: () -> SpectrumFrame,
    palette: VizPalette,
    clock: VizClock,
    framesPerSecond: Int,
    modifier: Modifier = Modifier,
    future: VizFuture? = null,
    stats: RenderStats? = null,
    quality: RenderQuality? = null,
    motionScale: Float = 1f,
    flashesPerSecond: Int = FLASHES_PER_SECOND,
) {
    val guard = remember(flashesPerSecond) { FlashGuard(flashesPerSecond) }
    val calmReading = remember { CalmReading() }
    // A replaced director starts afresh: its drawings restart and none of the old one's echoes carry
    // over. The clock, the palette fade and the flash allowance carry on, as they belong to the screen.
    val stage = remember(director) { Stage() }
    val blend = remember { TransitionBlend() }
    val fade = remember { PaletteFade() }
    // Read at every step, so a replaced analysis source is followed without restarting anything.
    val currentFrame by rememberUpdatedState(frame)

    // Keyed on the director, so a replaced one is the one that moves on and the old one stops.
    LaunchedEffect(director, framesPerSecond) {
        // Moved on inside the step, from the step's own reading, before the frame that shows it.
        clock.run(framesPerSecond, { currentFrame() }) { director.advance(it.frame, it.deltaSeconds) }
    }

    Canvas(modifier) {
        // The step is all that moves here, as on the plain canvas: the analysis is not observed.
        val tick = clock.tick
        val fresh = clock.firstDraw(tick)
        // A redraw of a step already drawn passes no time, so nothing below counts it twice.
        val deltaSeconds = if (fresh) tick?.deltaSeconds ?: 0f else 0f
        val heard = tick?.frame ?: Snapshot.withoutReadObservation { frame() }
        val current = calmReading.of(heard, motionScale, deltaSeconds)
        val shown = fade.advance(palette, current, deltaSeconds)
        val state = VizRenderState(current, tick?.timeSeconds ?: 0f, deltaSeconds, shown, tick?.musicTime ?: 0f, future)
        state.motionScale = motionScale
        state.lightScale = guard.allowance(lightFor(current.energy), deltaSeconds)
        stage.follow(director)
        val next = director.incoming
        val scale = quality?.stepped ?: 1f
        val showing = director.current

        if (next == null) {
            val started = TimeSource.Monotonic.markNow()
            val trailing = showing.trailAt(current.mood) > 0f
            val echo = if (trailing) stage.steady.frameFor(fresh, this, showing, state, scale, stats) else null
            if (trailing && fresh) {
                quality?.let {
                    it.afterFrame(started.elapsedNow().inWholeMicroseconds / 1000f, deltaSeconds)
                    stats?.scale = it.stepped
                }
            }
            composeFrame(showing, state, echo, stats)
            return@Canvas
        }

        val transitionStarted = TimeSource.Monotonic.markNow()
        val progress = director.progress
        drawRect(shown.background)
        showing.ground?.let { drawGround(it, state, if (next.ground == null) 1f - progress else 1f) }
        next.ground?.let { drawGround(it, state, progress) }

        if (director.transition == VizTransition.WarpHandoff) {
            if (stage.handingOff) {
                // One more frame of the old drawing, and that frame becomes the new drawing's past.
                stage.arriving.inherit(stage.steady.render(this, showing, state, scale))
                stage.handingOff = false
            }
            stage.arriving.frameFor(fresh, this, next, state, scale, stats)?.let { stretch(it) }
            withAlpha(1f - progress) { with(showing) { drawFront(state) } }
            withAlpha(progress) { with(next) { drawFront(state) } }
            showing.detail?.let { drawDetail(it, state, 1f - progress) }
            next.detail?.let { drawDetail(it, state, progress) }
            if (fresh) quality?.afterFrame(transitionStarted.elapsedNow().inWholeMicroseconds / 1000f, deltaSeconds)
            return@Canvas
        }

        if (blend.canDrawLayers(director.transition)) {
            drawTransitionLayers(
                blend, director.transition, progress,
                from = { drawTransitionEcho(stage.steady, showing, state, scale, stats, fresh) },
                to = { drawTransitionEcho(stage.arriving, next, state, scale, stats, fresh) },
            )
        } else {
            val from = stage.steady.frameFor(fresh, this, showing, state, scale, stats)
            val to = stage.arriving.frameFor(fresh, this, next, state, scale, stats)
            if (from == null || to == null) return@Canvas
            val brush = blend.prepare(from, to, director.transition, progress, size.width, size.height)
            if (brush != null) {
                drawRect(brush)
            } else {
                stretch(from, 1f - progress)
                stretch(to, progress)
            }
        }
        withAlpha(1f - progress) { with(showing) { drawFront(state) } }
        withAlpha(progress) { with(next) { drawFront(state) } }
        showing.detail?.let { drawDetail(it, state, 1f - progress) }
        next.detail?.let { drawDetail(it, state, progress) }
        if (fresh) quality?.afterFrame(transitionStarted.elapsedNow().inWholeMicroseconds / 1000f, deltaSeconds)
    }
}

/** Only history needs a bitmap. A full-screen shader keeps executing on the window's GPU. */
private fun DrawScope.drawTransitionEcho(
    renderer: VizRenderer,
    visualization: Visualization,
    state: VizRenderState,
    scale: Float,
    stats: RenderStats?,
    fresh: Boolean,
) {
    if (visualization.trailAt(state.mood) > 0f) {
        renderer.frameFor(fresh, this, visualization, state, scale, stats)?.let { stretch(it) }
    } else {
        with(visualization) { draw(state) }
    }
}

/**
 * The two renderers behind a directed surface, and which drawing each belongs to.
 *
 * When a change finishes they swap, so the new drawing carries on with the echoes it already built.
 * A drawing is started afresh when it begins to arrive, not when it finishes arriving.
 */
private class Stage {
    var steady = VizRenderer()
    var arriving = VizRenderer()
    var handingOff = false
    private var arrivingFor: Visualization? = null
    private var showingFor: Visualization? = null

    fun follow(director: VizDirector) {
        val next = director.incoming
        if (next != null && next !== arrivingFor) {
            next.restart()
            arriving.discard()
            arrivingFor = next
            handingOff = director.transition == VizTransition.WarpHandoff
        }
        if (next == null && arrivingFor != null) {
            if (director.current === arrivingFor) {
                val finished = steady
                steady = arriving
                arriving = finished
                showingFor = director.current
            }
            arrivingFor = null
            handingOff = false
        }
        if (director.current !== showingFor) {
            // Chosen by hand, or the very first frame: start it cleanly.
            director.current.restart()
            steady.discard()
            showingFor = director.current
        }
    }
}

/** Draws a buffer back up to the full canvas. */
private fun DrawScope.stretch(buffer: ImageBitmap, alpha: Float = 1f, blendMode: BlendMode = BlendMode.SrcOver) {
    drawImage(
        image = buffer,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(buffer.width, buffer.height),
        dstOffset = IntOffset.Zero,
        dstSize = IntSize(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1)),
        alpha = alpha,
        blendMode = blendMode,
    )
}

/** Draws [block] faded to [alpha] as one layer, so overlapping parts do not add up. */
private inline fun DrawScope.withAlpha(alpha: Float, block: DrawScope.() -> Unit) {
    if (alpha <= 0.002f) return
    if (alpha >= 0.998f) {
        block()
        return
    }
    drawIntoCanvas { canvas ->
        canvas.saveLayer(Rect(Offset.Zero, size), Paint().also { it.alpha = alpha })
        block()
        canvas.restore()
    }
}

/** A whole frame of a drawing that is not inside a [VisualizerSurface], with no trail. */
@AudioVizAuthoringApi
public fun DrawScope.drawVisualization(visualization: Visualization, state: VizRenderState) {
    composeFrame(visualization, state, null, null)
}

/**
 * The reading a drawing sees, slowed down while a reduced-motion setting is on.
 *
 * A drawing flashes mostly through what it draws from the spectrum: bars that jump on the beat
 * change a tenth of the picture by more than the flash step, whatever the camera and the hits are
 * doing. Holding the reading back is the only lever that reaches all of that, and the standard asks
 * for a restrained spectrum rather than none.
 *
 * At full motion the reading passes through untouched.
 */
private class CalmReading {
    private var last: SpectrumFrame? = null

    fun of(heard: SpectrumFrame, motionScale: Float, deltaSeconds: Float): SpectrumFrame {
        if (motionScale >= 1f) {
            last = null
            return heard
        }
        val before = last
        val settled = if (before == null || !before.hasTimestamp || !heard.hasTimestamp ||
            before.generation != heard.generation || before.analysisRevision != heard.analysisRevision
        ) {
            heard
        } else {
            // Half a second to cross, which is slower than any flash the policy counts.
            before.blend(heard, (deltaSeconds / 0.5f).coerceIn(0f, 1f))
        }
        last = settled
        return settled
    }
}

/**
 * How much of a picture's own movement a reduced-motion setting keeps.
 *
 * Not zero: a still picture with a moving spectrum in it reads as broken rather than as calm. The
 * camera still wanders slowly, and the flashes, cuts, shakes and punches are gone. *Judgement.*
 */
private const val REDUCED_MOTION = 0.15f

/** The project's flash policy: at most three in any rolling second, with no area exception. */
private const val FLASHES_PER_SECOND = 3
