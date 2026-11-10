package io.github.yuroyami.kiteplayer.audioviz

import androidx.compose.runtime.Composable
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.mutableStateMapOf
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
 * draws the state's own drawings, which the director and the application's controls act on, and
 * when it leaves, the next view shown takes them over (#950). Each
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
    // The view that leads the state draws its own drawings and steps its frames. The lead follows
    // the live views, so when the first one leaves, the next takes the director over (#950).
    val view = remember(state) { ShownView(state) }
    val first = ShownStates.leads(state, view)
    if (first) {
        // Once per state and display frame: a second loop allocated a second blended frame, and the
        // event cursor gave the frame's events to the first call alone (#950).
        LaunchedEffect(state) {
            while (true) withFrameNanos { state.nextFrame(it) }
        }
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

/**
 * One view of a state while its composition holds it (#950). It counts from the moment Compose
 * keeps the composition, and stops counting when Compose forgets it or abandons a composition that
 * never applied, which a registration made during composition and undone in a disposal missed.
 */
private class ShownView(private val state: AudioVizState) : RememberObserver {
    override fun onRemembered() = ShownStates.add(state, this)
    override fun onForgotten() = ShownStates.remove(state, this)
    override fun onAbandoned() = ShownStates.remove(state, this)
}

/**
 * The views that show each state now, in the order they arrived, so that only the first draws the
 * state's own drawings (#674). Snapshot state, so a view recomposes when the lead passes to it.
 */
internal object ShownStates {
    private val shown = mutableStateMapOf<AudioVizState, List<Any>>()

    fun add(state: AudioVizState, view: Any) {
        val views = shown[state].orEmpty()
        if (views.none { it === view }) shown[state] = views + view
    }

    fun remove(state: AudioVizState, view: Any) {
        val left = (shown[state] ?: return).filterNot { it === view }
        if (left.isEmpty()) shown.remove(state) else shown[state] = left
    }

    /**
     * Whether [view] leads [state]: it is the first of its views, or it is not counted yet and no
     * view is, as when it is composed for the first time and alone.
     */
    fun leads(state: AudioVizState, view: Any): Boolean {
        val views = shown[state].orEmpty()
        return if (views.any { it === view }) views.first() === view else views.isEmpty()
    }

    /** The views of [state] now, for a test. */
    fun count(state: AudioVizState): Int = shown[state]?.size ?: 0
}
