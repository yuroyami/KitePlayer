package io.github.yuroyami.kiteplayer.audioviz.viz

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import kotlin.time.TimeSource

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
 * [frame] is a function so the newest analysis is read in the drawing pass. [future] is optional and
 * lets a drawing see the audio that has been analysed but not yet played.
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
) {
    PostProcessedBox(
        spec = { if (post) visualization.post.masked(switches) else PostSpec.Off },
        frame = frame,
        modifier = modifier,
        stats = stats,
    ) {
        VisualizerCanvas(visualization, frame, palette, Modifier.fillMaxSize(), future, stats, quality, framesPerSecond)
    }
}

/** The drawing itself, before the finishing pass. */
@Composable
private fun VisualizerCanvas(
    visualization: Visualization,
    frame: () -> SpectrumFrame,
    palette: VizPalette,
    modifier: Modifier = Modifier,
    future: VizFuture? = null,
    stats: RenderStats? = null,
    quality: RenderQuality? = null,
    framesPerSecond: Int = 0,
) {
    val buffers = remember { FeedbackBuffers() }
    val fade = remember { PaletteFade() }
    var timeSeconds by remember { mutableFloatStateOf(0f) }
    var musicTime by remember { mutableFloatStateOf(0f) }
    var deltaSeconds by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(visualization) {
        visualization.restart()
        buffers.discard()
        var previousNanos = 0L
        var waiting = 0f
        val every = if (framesPerSecond > 0) 1f / framesPerSecond else 0f
        while (true) {
            withFrameNanos { nowNanos ->
                if (previousNanos != 0L) {
                    waiting += ((nowNanos - previousNanos) / 1_000_000_000.0).toFloat()
                    // Held back until a whole frame at the chosen rate has passed.
                    if (waiting >= every) {
                        // Clamped, so a stalled window does not teleport every particle off screen.
                        deltaSeconds = waiting.coerceIn(0f, 0.1f)
                        timeSeconds += deltaSeconds
                        // The music clock slows in a quiet passage and stops in silence.
                        musicTime += deltaSeconds * frame().motionRate
                        waiting = 0f
                    }
                }
                previousNanos = nowNanos
            }
        }
    }

    Canvas(modifier) {
        val current = frame()
        // The palette asked for, faded in over two bars and leaning towards the key.
        val shown = fade.advance(palette, current, deltaSeconds)
        val state = VizRenderState(
            frame = current,
            timeSeconds = timeSeconds,
            deltaSeconds = deltaSeconds,
            palette = shown,
            musicTime = musicTime,
            future = future,
        )
        val trail = visualization.trailAt(current.mood).coerceIn(0f, 0.995f)
        if (trail <= 0f) {
            composeFrame(visualization, state, null, stats)
            return@Canvas
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
        buffers.scope.draw(this, layoutDirection, GraphicsCanvas(target), bufferSize) {
            drawEchoLayer(visualization, state, previous, stats)
        }
        quality?.let {
            it.afterFrame(started.elapsedNow().inWholeMicroseconds / 1000f, deltaSeconds)
            stats?.scale = it.stepped
        }
        composeFrame(visualization, state, target, stats)
        buffers.swap()
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

/** Paints the frame before this one back down, faded and moved. */
private fun DrawScope.replayPrevious(
    previous: ImageBitmap,
    visualization: Visualization,
    state: VizRenderState,
    trail: Float,
) {
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
    val spec = visualization.warp
    if (spec != null) {
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
        val scale = (if (visualization.bloom > 0) SOFT_BUFFER_SCALE else 1f) * quality
        val target = buffers.next(into.size, scale) ?: return null
        inherited?.let {
            buffers.seed(it)
            inherited = null
        }
        val previous = buffers.previous()
        val size = Size(target.width.toFloat(), target.height.toFloat())
        scope.draw(into, into.layoutDirection, GraphicsCanvas(target), size) {
            drawEchoLayer(visualization, state, previous, stats)
        }
        buffers.swap()
        latest = target
        return target
    }

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
    private var width = 0
    private var height = 0

    /** The bitmap to draw this frame into, or null when the canvas has no area yet. */
    fun next(size: Size, scale: Float): ImageBitmap? {
        val wanted = (size.width * scale).toInt()
        val tall = (size.height * scale).toInt()
        if (wanted <= 0 || tall <= 0) return null
        if (front == null || width != wanted || height != tall) {
            val before = if (width > 0) back else null
            front = ImageBitmap(wanted, tall)
            back = ImageBitmap(wanted, tall)
            width = wanted
            height = tall
            // A new size keeps the picture that was there, stretched, so the trail carries on.
            before?.let { seed(it) }
        }
        return front
    }

    /** The bitmap from last frame, or null on the first frame after a resize or a change. */
    fun previous(): ImageBitmap? = if (width == 0) null else back

    /** Paints [picture] in as last frame, stretched to fit, so the next frame builds on it. */
    fun seed(picture: ImageBitmap) {
        val into = back ?: return
        val area = Size(width.toFloat(), height.toFloat())
        scope.draw(Density(1f), LayoutDirection.Ltr, GraphicsCanvas(into), area) {
            drawRect(Color.Transparent, blendMode = BlendMode.Clear)
            drawImage(
                image = picture,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(picture.width, picture.height),
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(width, height),
            )
        }
    }

    fun swap() {
        val held = front
        front = back
        back = held
    }

    fun discard() {
        front = null
        back = null
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
 * During a change both echo layers are rendered, each into its own bitmap so neither loses its
 * trail, and mixed by a program that decides pixel by pixel which one wins. The two grounds and the
 * two fronts are faded into each other on the canvas.
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
) {
    PostProcessedBox(
        spec = { if (post) director.current.post.masked(switches) else PostSpec.Off },
        frame = frame,
        modifier = modifier,
        stats = stats,
    ) {
        DirectedCanvas(director, frame, palette, Modifier.fillMaxSize(), future, stats, quality)
    }
}

/** The directed drawing itself, before the finishing pass. */
@Composable
private fun DirectedCanvas(
    director: VizDirector,
    frame: () -> SpectrumFrame,
    palette: VizPalette,
    modifier: Modifier = Modifier,
    future: VizFuture? = null,
    stats: RenderStats? = null,
    quality: RenderQuality? = null,
) {
    val stage = remember { Stage() }
    val blend = remember { TransitionBlend() }
    val fade = remember { PaletteFade() }
    var timeSeconds by remember { mutableFloatStateOf(0f) }
    var musicTime by remember { mutableFloatStateOf(0f) }
    var deltaSeconds by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        var previousNanos = 0L
        while (true) {
            withFrameNanos { nowNanos ->
                if (previousNanos != 0L) {
                    deltaSeconds = ((nowNanos - previousNanos) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.1f)
                    timeSeconds += deltaSeconds
                    musicTime += deltaSeconds * frame().motionRate
                }
                previousNanos = nowNanos
            }
            director.advance(frame(), deltaSeconds)
        }
    }

    Canvas(modifier) {
        val current = frame()
        val shown = fade.advance(palette, current, deltaSeconds)
        val state = VizRenderState(current, timeSeconds, deltaSeconds, shown, musicTime, future)
        stage.follow(director)
        val next = director.incoming
        val scale = quality?.stepped ?: 1f
        val showing = director.current

        if (next == null) {
            val started = TimeSource.Monotonic.markNow()
            val trailing = showing.trailAt(current.mood) > 0f
            val echo = if (trailing) stage.steady.render(this, showing, state, scale, stats) else null
            if (trailing) {
                quality?.let {
                    it.afterFrame(started.elapsedNow().inWholeMicroseconds / 1000f, deltaSeconds)
                    stats?.scale = it.stepped
                }
            }
            composeFrame(showing, state, echo, stats)
            return@Canvas
        }

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
            stage.arriving.render(this, next, state, scale, stats)?.let { stretch(it) }
            withAlpha(1f - progress) { with(showing) { drawFront(state) } }
            withAlpha(progress) { with(next) { drawFront(state) } }
            showing.detail?.let { drawDetail(it, state, 1f - progress) }
            next.detail?.let { drawDetail(it, state, progress) }
            return@Canvas
        }

        val from = stage.steady.render(this, showing, state, scale, stats)
        val to = stage.arriving.render(this, next, state, scale, stats)
        if (from == null || to == null) return@Canvas
        val brush = blend.prepare(from, to, director.transition, progress, size.width, size.height)
        if (brush != null) {
            drawRect(brush)
        } else {
            // No way to run a program here, so the plain mix it is.
            stretch(from, 1f - progress)
            stretch(to, progress)
        }
        withAlpha(1f - progress) { with(showing) { drawFront(state) } }
        withAlpha(progress) { with(next) { drawFront(state) } }
        showing.detail?.let { drawDetail(it, state, 1f - progress) }
        next.detail?.let { drawDetail(it, state, progress) }
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
