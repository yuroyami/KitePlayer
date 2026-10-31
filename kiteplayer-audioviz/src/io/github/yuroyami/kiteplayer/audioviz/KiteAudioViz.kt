package io.github.yuroyami.kiteplayer.audioviz

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import io.github.yuroyami.kiteplayer.KiteLog
import io.github.yuroyami.kiteplayer.audioviz.viz.DirectedVisualizerSurface
import io.github.yuroyami.kiteplayer.audioviz.viz.VisualizerSurface

/**
 * Draws the sound the player is playing, with the drawing, palette and director in [state].
 *
 * Show it instead of the video when [isAudioOnly] is true. [post] lays the finishing pass over the
 * drawing: glow, darker corners and grain.
 *
 * One view at a time for one [state], whose drawings it steps: a second view of the same state
 * restarts them and steps them twice a frame, and logs a warning through `KiteLog` (#674).
 */
@Composable
public fun KiteAudioViz(state: AudioVizState, modifier: Modifier = Modifier, post: Boolean = true) {
    DisposableEffect(state) {
        if (!ShownStates.add(state)) {
            KiteLog.log(
                "KiteAudioViz",
                "one AudioVizState is shown by two KiteAudioViz views, so its drawings restart and step twice " +
                    "a frame; give each view a state of its own",
            )
        }
        onDispose { ShownStates.remove(state) }
    }
    LaunchedEffect(state) {
        while (true) withFrameNanos { state.nextFrame(it) }
    }
    // This surface may draw less often than the display refreshes, so it reads through a source of
    // its own. Reading state.frame would lose the events of the display frames it skips.
    val source = remember(state) { state.frameSource() }
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

/** How many views show each state now, so a second one is noticed (#674). */
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
