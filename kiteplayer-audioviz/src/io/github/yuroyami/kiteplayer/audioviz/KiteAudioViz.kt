package io.github.yuroyami.kiteplayer.audioviz

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import io.github.yuroyami.kiteplayer.audioviz.viz.DirectedVisualizerSurface
import io.github.yuroyami.kiteplayer.audioviz.viz.VisualizerSurface
import io.github.yuroyami.kiteplayer.audioviz.viz.Visualization
import io.github.yuroyami.kiteplayer.audioviz.viz.VizCatalog
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.NeonLoFi

/**
 * Draws the sound the player is playing, with the drawing, palette and director in [state].
 *
 * Show it instead of the video when [isAudioOnly] is true. [post] lays the finishing pass over the
 * drawing: glow, darker corners and grain.
 *
 * One [state] may be shown by several views, a mini player beside a full one (#674). The first view
 * draws the state's own drawings, which the director and the application's controls act on. Each
 * further view draws copies of its own that follow it: the drawing the state shows, with the same
 * settings. Drawings keep state of their own, so two views stepping the same drawing ran its
 * motion at twice the speed and restarted it.
 */
@Composable
public fun KiteAudioViz(state: AudioVizState, modifier: Modifier = Modifier, post: Boolean = true) {
    KiteAudioVizOf(state, modifier, post, makeCopies = VizCatalog::create)
}

/** [KiteAudioViz], whose further views copy their drawings from [makeCopies], which a test hands its own. */
@Composable
internal fun KiteAudioVizOf(
    state: AudioVizState,
    modifier: Modifier,
    post: Boolean,
    makeCopies: () -> List<Visualization>,
) {
    // Decided once, as the view is composed: the first view of the state draws its own drawings.
    val first = remember(state) { ShownStates.add(state) }
    DisposableEffect(state) {
        onDispose { ShownStates.remove(state) }
    }
    LaunchedEffect(state) {
        while (true) withFrameNanos { state.nextFrame(it) }
    }
    // This surface may draw less often than the display refreshes, so it reads through a source of
    // its own. Reading state.frame would lose the events of the display frames it skips.
    val source = remember(state) { state.frameSource() }
    if (!first) {
        MirroredSurface(state, source, modifier, post, makeCopies)
        return
    }
    if (state.directed) {
        DirectedVisualizerSurface(
            director = state.director,
            frame = { source.take() },
            palette = state.palette,
            modifier = modifier,
            future = state.future,
            post = post,
            switches = state.switches,
            stats = state.stats,
            quality = state.quality,
            reducedMotion = state.reducedMotion,
            visible = state.visible,
            framesPerSecond = state.framesPerSecond,
        )
    } else {
        VisualizerSurface(
            visualization = state.drawing,
            frame = { source.take() },
            palette = state.palette,
            modifier = modifier,
            future = state.future,
            post = post,
            switches = state.switches,
            stats = state.stats,
            quality = state.quality,
            reducedMotion = state.reducedMotion,
            visible = state.visible,
            framesPerSecond = state.framesPerSecond,
        )
    }
}

/**
 * A further view of [state] (#674): the copy of the drawing the state shows now, from copies of its
 * own, with the state's settings copied in before each frame, as the drawing browser's tiles do.
 * A drawing the copies do not hold, one an application made itself, is drawn as the state's own.
 */
@Composable
private fun MirroredSurface(
    state: AudioVizState,
    source: AudioVizFrameSource,
    modifier: Modifier,
    post: Boolean,
    makeCopies: () -> List<Visualization>,
) {
    val copies = remember(state) { makeCopies() }
    val shown = if (state.directed) state.director.current else state.drawing
    val copy = copies.firstOrNull { it.name == shown.name } ?: shown
    LaunchedEffect(state, copy) {
        while (true) {
            withFrameNanos {
                if (copy !== shown) {
                    for (param in copy.params) {
                        shown.params.firstOrNull { it.name == param.name }?.let { param.value = it.value }
                    }
                    if (copy is NeonLoFi && shown is NeonLoFi) copy.copyRecipeFrom(shown)
                }
            }
        }
    }
    VisualizerSurface(
        visualization = copy,
        frame = { source.take() },
        palette = state.palette,
        modifier = modifier,
        future = state.future,
        post = post,
        switches = state.switches,
        stats = state.stats,
        quality = state.quality,
        reducedMotion = state.reducedMotion,
        visible = state.visible,
        framesPerSecond = state.framesPerSecond,
    )
}

/** How many views show each state now, so that only the first draws the state's own drawings (#674). */
@OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
internal object ShownStates {
    private val shown = kotlin.concurrent.atomics.AtomicReference<Map<AudioVizState, Int>>(emptyMap())

    /** Counts one more view of [state], and answers whether it is the only one. */
    fun add(state: AudioVizState): Boolean {
        while (true) {
            val old = shown.load()
            val count = (old[state] ?: 0) + 1
            if (shown.compareAndSet(old, old + (state to count))) return count == 1
        }
    }

    fun remove(state: AudioVizState) {
        while (true) {
            val old = shown.load()
            val count = (old[state] ?: return) - 1
            val next = if (count <= 0) old - state else old + (state to count)
            if (shown.compareAndSet(old, next)) return
        }
    }

    /** The views of [state] now, for a test. */
    fun count(state: AudioVizState): Int = shown.load()[state] ?: 0
}
