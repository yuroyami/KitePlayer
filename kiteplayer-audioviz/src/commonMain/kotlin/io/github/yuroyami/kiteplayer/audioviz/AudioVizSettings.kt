package io.github.yuroyami.kiteplayer.audioviz

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.yuroyami.kiteplayer.audioviz.viz.PostSwitches
import io.github.yuroyami.kiteplayer.audioviz.viz.RenderStats
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.VizParam
import io.github.yuroyami.kiteplayer.audioviz.viz.VizTransition
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.ChoiceGene
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Gene
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.NumberGene
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * The settings for [state]: the drawing's own settings and its recipe, the palette, the finishing
 * pass, the render detail with a frame-time bar, and the director. Every control writes straight
 * into what the drawing reads on its next frame, so a change shows at once.
 *
 * [palettes] are the swatches offered. Add one made with `VizPalette.fromImage` to offer the album's colours.
 */
@Composable
public fun AudioVizSettings(
    state: AudioVizState,
    modifier: Modifier = Modifier,
    palettes: List<VizPalette> = VizPalette.entries,
) {
    // The drawing, its genes and the stats change without telling Compose, so the panel reads them
    // again twice a second.
    val tick by produceState(0) {
        while (true) {
            delay(500)
            value++
        }
    }
    val drawing = remember(tick, state.drawing, state.directed) { state.showing }

    Column(
        modifier
            .width(300.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xCC0B0E14))
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Heading("Tune ${drawing.name}")
        Params(drawing.params, tick)

        drawing.genes?.let { genes ->
            Heading("Recipe")
            Note("The song changes these by itself every phrase. ${genes.changes} changes so far.")
            for (gene in genes.all) GeneControl(gene, tick)
            PanelButton("Mutate now") { state.mutate() }
        }

        Heading("Palette")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (palette in palettes) Swatch(palette, chosen = palette == state.palette) { state.palette = palette }
        }
        Note(state.palette.name)

        Heading("Finishing")
        val switches = state.switches
        Toggle("Glow round bright parts", switches.bloom) { state.switches = switches.copy(bloom = it) }
        Toggle("Colour fringes at the edges", switches.aberration) { state.switches = switches.copy(aberration = it) }
        Toggle("Darker corners", switches.vignette) { state.switches = switches.copy(vignette = it) }
        Toggle("Film grain", switches.grain) { state.switches = switches.copy(grain = it) }
        Toggle("Tear when a drop lands", switches.glitch) { state.switches = switches.copy(glitch = it) }
        Toggle("Old screen lines", switches.scanlines) { state.switches = switches.copy(scanlines = it) }
        if (switches != PostSwitches.All) PanelButton("All back on") { state.switches = PostSwitches.All }

        Heading("Detail")
        Detail(state, tick)

        Heading("Director")
        Toggle("Change drawings with the music", state.directed) { state.directed = it }
        Transition(state)
    }
}

/** The drawing's own settings, with a button that puts them back. */
@Composable
private fun Params(params: List<VizParam>, tick: Int) {
    if (params.isEmpty()) {
        Note("This drawing has nothing of its own to tune.")
        return
    }
    var resets by remember(params) { mutableIntStateOf(0) }
    for (param in params) {
        var value by remember(param) { mutableFloatStateOf(param.value) }
        LaunchedEffect(param, tick, resets) { value = param.value }
        Note("${param.name}  ${value.hundredths()}")
        Slider(value, param.min..param.max) {
            value = it
            param.value = it
        }
    }
    PanelButton("Reset") {
        params.forEach { it.reset() }
        resets++
    }
}

/** One gene of the recipe: a slider for a number, a tick box for a switch, chips for a choice. */
@Composable
private fun GeneControl(gene: Gene, tick: Int) {
    when (gene) {
        is NumberGene -> {
            var value by remember(gene) { mutableFloatStateOf(gene.target) }
            // Follows the song's own changes; while a finger drags, the two are the same number.
            LaunchedEffect(gene, tick) { value = gene.target }
            Note("${gene.name}  ${value.hundredths()}")
            Slider(value, gene.min..gene.max) {
                value = it
                gene.target = it
            }
        }
        is ChoiceGene if gene.options == 2 -> Toggle(gene.name, gene.value == 1) { gene.choose(if (it) 1 else 0) }
        is ChoiceGene -> {
            Note(gene.name)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (option in 0 until gene.options) {
                    Chip("${option + 1}", chosen = gene.value == option) { gene.choose(option) }
                }
            }
        }
    }
}

/** How much of the canvas trailing drawings render at, and where the time of a frame goes. */
@Composable
private fun Detail(state: AudioVizState, tick: Int) {
    var scale by remember(state) { mutableFloatStateOf(state.quality.scale) }
    var dynamic by remember(state) { mutableStateOf(state.quality.dynamic) }
    Note("Trailing drawings render at ${(scale * 100).roundToInt()}% of the view")
    Slider(scale, 0.25f..1f) {
        scale = it
        state.quality.scale = it
    }
    Toggle("Lower it while frames run slow", dynamic) {
        dynamic = it
        state.quality.dynamic = it
    }
    // A new bar each tick: the stats change without telling Compose, so nothing else would redraw it.
    key(tick) { FrameBar(state.stats) }
    Note(timing(state.stats))
}

/** How the director changes drawings: one way every time, or whichever way the music asks for. */
@Composable
private fun Transition(state: AudioVizState) {
    var preferred by remember(state) { mutableStateOf(state.director.preferred) }
    fun step(by: Int) {
        val all = listOf<VizTransition?>(null) + VizTransition.entries
        preferred = all[(all.indexOf(preferred) + by + all.size) % all.size]
        state.director.preferred = preferred
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PanelButton("Back") { step(-1) }
        Note(preferred?.name ?: "Music decides")
        PanelButton("Next") { step(1) }
    }
}

/**
 * One frame at sixty a second as a bar, with the time spent on the ground, the drawing, the front,
 * the echo and the finishing pass laid along it. Anything past the end is a dropped frame.
 */
@Composable
private fun FrameBar(stats: RenderStats) {
    Canvas(Modifier.size(260.dp, 10.dp).clip(RoundedCornerShape(5.dp))) {
        drawRect(Color(0xFF1B2029))
        val perMilli = size.width / FRAME_MILLIS
        var from = 0f
        val parts = listOf(
            stats.ground to GROUND,
            stats.scene to SCENE,
            stats.front to FRONT,
            stats.warp to ECHO,
            stats.post to FINISH,
        )
        for ((millis, colour) in parts) {
            val wide = (millis * perMilli).coerceAtMost(size.width - from)
            drawRect(colour, topLeft = Offset(from, 0f), size = Size(wide, size.height))
            from += wide
        }
    }
}

private fun timing(stats: RenderStats): String =
    "Ground ${stats.ground.hundredths()}, drawing ${stats.scene.hundredths()}, front ${stats.front.hundredths()}, " +
        "echo ${stats.warp.hundredths()}, finishing ${stats.post.hundredths()} ms"

/** A small strip painted with the palette's ramp, outlined when it is the one in use. */
@Composable
private fun Swatch(palette: VizPalette, chosen: Boolean, onPick: () -> Unit) {
    Canvas(
        Modifier
            .size(44.dp, 22.dp)
            .clip(RoundedCornerShape(4.dp))
            .border(if (chosen) 2.dp else 0.dp, PanelColors.Bright, RoundedCornerShape(4.dp))
            .clickable(onClick = onPick),
    ) {
        drawRect(Brush.horizontalGradient(List(8) { palette.ramp(it / 7f) }))
    }
}

private fun Float.hundredths(): String = ((this * 100).roundToInt() / 100.0).toString()

private const val FRAME_MILLIS = 16.7f
private val GROUND = Color(0xFF4CD28A)
private val SCENE = Color(0xFF4C9AFF)
private val FRONT = Color(0xFF4DD8E8)
private val ECHO = Color(0xFFB44DE8)
private val FINISH = Color(0xFFFFB84D)
