package io.github.yuroyami.kiteplayer.audioviz

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.yuroyami.kiteplayer.audioviz.viz.Visualization
import io.github.yuroyami.kiteplayer.audioviz.viz.VisualizerSurface
import io.github.yuroyami.kiteplayer.audioviz.viz.VizCatalog
import io.github.yuroyami.kiteplayer.audioviz.viz.VizFamily
import kotlinx.coroutines.delay

/**
 * Every drawing at once, each live in a small tile, grouped by family and searchable by name.
 * Tapping a tile shows that drawing in [state], then calls [onPick] so the caller can close the panel.
 *
 * The tiles draw their own copies of the drawings, so their trails stay apart from the big one, and
 * redraw fifteen times a second. Show it over a [KiteAudioViz] of the same state, which keeps the
 * analysis moving.
 */
@Composable
public fun AudioVizBrowser(state: AudioVizState, modifier: Modifier = Modifier, onPick: (Visualization) -> Unit = {}) {
    val tiles = remember { VizCatalog.create() }
    var query by remember { mutableStateOf("") }
    var shown by remember { mutableStateOf(state.frame) }
    LaunchedEffect(state) {
        while (true) {
            shown = state.frame
            delay(1_000L / TILE_RATE)
        }
    }

    val wanted = query.trim()
    val groups = tiles.filter { wanted.isEmpty() || it.name.contains(wanted, ignoreCase = true) }.groupBy { it.family }

    Column(modifier.fillMaxSize().background(Color(0xE6070910)).padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            BasicText(
                "Browse",
                style = TextStyle(color = PanelColors.Bright, fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
            )
            SearchField(
                query,
                hint = "Search by name",
                onChange = { query = it },
                modifier = Modifier.weight(1f).widthIn(max = 300.dp),
            )
        }
        Note(
            "${tiles.size} drawings. Tap one to show it.",
            Modifier.padding(top = 8.dp, bottom = 4.dp),
            PanelColors.Faint,
        )
        LazyVerticalGrid(columns = GridCells.Adaptive(184.dp), modifier = Modifier.fillMaxSize()) {
            for ((family, members) in groups) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "family ${family.name}") {
                    Heading(family.label, Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 6.dp))
                }
                items(members, key = { it.name }) { tile ->
                    Column(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                val picked = state.catalogue.firstOrNull { it.name == tile.name } ?: return@clickable
                                state.drawing = picked
                                onPick(picked)
                            }
                            .padding(6.dp),
                    ) {
                        VisualizerSurface(
                            visualization = tile,
                            frame = { shown },
                            palette = state.palette,
                            modifier = Modifier.size(172.dp, 96.dp).clip(RoundedCornerShape(6.dp)),
                            post = false,
                            framesPerSecond = TILE_RATE,
                        )
                        Note(tile.name, Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
    }
}

/** A family's name the way a person would write it. */
internal val VizFamily.label: String
    get() = when (this) {
        VizFamily.BarsAndWaves -> "Bars and waves"
        VizFamily.Battery -> "Battery"
        VizFamily.Ambience -> "Ambience"
        VizFamily.Plenoptic -> "Plenoptic"
        VizFamily.Alchemy -> "Alchemy"
        VizFamily.MusicalColors -> "Musical colours"
        VizFamily.Immersion -> "Immersion"
        VizFamily.Acid -> "Acid"
        VizFamily.Warp -> "Warp"
        VizFamily.Raymarch -> "Raymarch"
        VizFamily.Fluid -> "Fluid"
    }

private const val TILE_RATE = 15
