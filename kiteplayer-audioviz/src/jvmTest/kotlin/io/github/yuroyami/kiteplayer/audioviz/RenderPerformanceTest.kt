package io.github.yuroyami.kiteplayer.audioviz

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import io.github.yuroyami.kiteplayer.audioviz.viz.PostProcessedBox
import io.github.yuroyami.kiteplayer.audioviz.viz.PostSpec
import io.github.yuroyami.kiteplayer.audioviz.viz.VizCatalog
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderer
import io.github.yuroyami.kiteplayer.audioviz.viz.composeFrame
import io.github.yuroyami.kiteplayer.audioviz.viz.restart
import kotlin.test.Test

/** Opt-in end-to-end CPU raster benchmark. Timings are observations, not portable CI assertions. */
class RenderPerformanceTest {
    @Test
    fun measureSceneAndFinishingPass() {
        useSkiaGraphics()
        val song = RenderHarness.player(RenderHarness.Song.Lively, 5f)
        val frames = List(24) { song.next(1f / 60f) }
        for (name in listOf("Alchemy", "Twist")) {
            for (post in listOf(false, true)) {
                val drawing = VizCatalog.create().first { it.name == name }
                drawing.restart()
                val renderer = VizRenderer()
                val step = mutableIntStateOf(0)
                val scene = ImageComposeScene(640, 360, Density(1f), content = {
                    PostProcessedBox(
                        spec = { if (post) PostSpec.Default else PostSpec.Off },
                        frame = { frames[step.intValue] },
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        Canvas(Modifier.fillMaxSize()) {
                            val index = step.intValue
                            val state = VizRenderState(frames[index], (index + 1) / 60f, 1f / 60f, VizPalette.Prism)
                            val echo = if (drawing.trailAt(state.mood) > 0f) renderer.render(this, drawing, state) else null
                            composeFrame(drawing, state, echo, null)
                        }
                    }
                })
                try {
                    val costs = DoubleArray(frames.size - 4)
                    frames.indices.forEach { index ->
                        step.intValue = index
                        val start = System.nanoTime()
                        scene.render(index * 16_666_667L).close()
                        if (index >= 4) costs[index - 4] = (System.nanoTime() - start) / 1_000_000.0
                    }
                    costs.sort()
                    println("BENCH 640x360 $name post=$post min=${costs.first()} median=${costs[costs.size / 2]} p95=${costs[(costs.size * 0.95).toInt()]} ms")
                } finally {
                    scene.close()
                }
            }
        }
    }
}
