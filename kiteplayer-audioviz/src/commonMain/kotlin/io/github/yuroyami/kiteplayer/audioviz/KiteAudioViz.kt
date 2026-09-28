package io.github.yuroyami.kiteplayer.audioviz

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import io.github.yuroyami.kiteplayer.audioviz.viz.DirectedVisualizerSurface
import io.github.yuroyami.kiteplayer.audioviz.viz.VisualizerSurface

/**
 * Draws the sound the player is playing, with the drawing, palette and director in [state].
 *
 * Show it instead of the video when [isAudioOnly] is true. [post] lays the finishing pass over the
 * drawing: glow, darker corners and grain.
 */
@Composable
public fun KiteAudioViz(state: AudioVizState, modifier: Modifier = Modifier, post: Boolean = true) {
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
