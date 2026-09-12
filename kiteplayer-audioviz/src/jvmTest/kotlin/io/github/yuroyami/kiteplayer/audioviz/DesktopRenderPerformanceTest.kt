package io.github.yuroyami.kiteplayer.audioviz

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import io.github.yuroyami.kiteplayer.audioviz.viz.PostProcessedBox
import io.github.yuroyami.kiteplayer.audioviz.viz.PostSpec
import io.github.yuroyami.kiteplayer.audioviz.viz.RenderQuality
import io.github.yuroyami.kiteplayer.audioviz.viz.RenderStats
import io.github.yuroyami.kiteplayer.audioviz.viz.VizCatalog
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderer
import io.github.yuroyami.kiteplayer.audioviz.viz.composeFrame
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertTrue

/** Opt-in real-window cadence measurement. Run alone, with the window visible. */
@OptIn(ExperimentalComposeUiApi::class)
class DesktopRenderPerformanceTest {
    @Test
    fun measureDesktopFrameCadence() {
        for (name in listOf("Alchemy", "Twist", "Flow Field", "Cathedral")) {
            val drawing = VizCatalog.create().first { it.name == name }
            val song = RenderHarness.player(RenderHarness.Song.Lively, 8f)
            val frames = List(180) { song.next(1f / 60f) }
            val renderer = VizRenderer()
            val quality = RenderQuality()
            val stats = RenderStats()
            val step = mutableIntStateOf(0)
            val done = CountDownLatch(1)
            val intervals = ArrayList<Double>()
            val preparation = ArrayList<Double>()
            var canvasSize = ""
            lateinit var window: ComposeWindow
            SwingUtilities.invokeAndWait {
                window = ComposeWindow().apply {
                    title = "Audio visualization benchmark: $name"
                    setSize(960, 600)
                    setContent {
                        LaunchedEffect(Unit) {
                            var previous = 0L
                            repeat(frames.size) { index ->
                                withFrameNanos { now ->
                                    if (index >= 60) intervals += (now - previous) / 1_000_000.0
                                    previous = now
                                    step.intValue = index
                                }
                            }
                            done.countDown()
                        }
                        PostProcessedBox(
                            spec = { PostSpec.Default },
                            frame = { frames[step.intValue] },
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            Canvas(Modifier.fillMaxSize()) {
                                val start = System.nanoTime()
                                canvasSize = "${size.width.toInt()}x${size.height.toInt()}"
                                val index = step.intValue
                                val state = VizRenderState(frames[index], (index + 1) / 60f, 1f / 60f, VizPalette.Prism)
                                val trailing = drawing.trailAt(state.mood) > 0f
                                val echo = if (trailing) renderer.render(this, drawing, state, quality.stepped, stats) else null
                                if (trailing) quality.afterFrame((System.nanoTime() - start) / 1_000_000f, state.deltaSeconds)
                                composeFrame(drawing, state, echo, null)
                                if (index >= 60) preparation += (System.nanoTime() - start) / 1_000_000.0
                            }
                        }
                    }
                    isVisible = true
                }
            }
            try {
                assertTrue(done.await(90, TimeUnit.SECONDS), "$name did not finish its visible window benchmark")
                SwingUtilities.invokeAndWait {
                    intervals.sort()
                    preparation.sort()
                    println("DESKTOP ${window.renderApi} $canvasSize $name cadence median=${intervals[intervals.size / 2]} p95=${intervals[(intervals.size * 0.95).toInt()]} ms; scene CPU median=${preparation[preparation.size / 2]} ms; feedback scale=${quality.stepped}; warp=${stats.warp} ms")
                }
            } finally {
                SwingUtilities.invokeAndWait { window.dispose() }
            }
        }
    }
}
