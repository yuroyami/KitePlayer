package io.github.yuroyami.kiteplayer.audioviz

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kiteplayer.audioviz.viz.VizCatalog
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.ShaderPreset
import io.github.yuroyami.kiteplayer.audioviz.viz.SOFT_BUFFER_SCALE
import io.github.yuroyami.kiteplayer.audioviz.viz.drawVisualizationFrame
import io.github.yuroyami.kiteplayer.audioviz.viz.ground.Detail
import io.github.yuroyami.kiteplayer.audioviz.viz.ground.DetailKind
import io.github.yuroyami.kiteplayer.audioviz.viz.ground.Ground
import io.github.yuroyami.kiteplayer.audioviz.viz.ground.drawDetail
import io.github.yuroyami.kiteplayer.audioviz.viz.ground.GroundKind
import io.github.yuroyami.kiteplayer.audioviz.viz.ground.drawGround
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Measures how long each drawing takes for one frame at a normal window size.
 *
 * A visualiser has 16.7 ms to produce a frame at sixty a second, and the rest of the application
 * needs some of that. This renders on the processor rather than the graphics card, so every number
 * here is a pessimistic one: a real window is faster. Anything close to the budget even under
 * those conditions is worth knowing about.
 */
class DrawCostTest {

    init { useSkiaGraphics() }

    private val width = 1280
    private val height = 720
    private val warmup = 20
    private val batchCount = 7
    private val batchFrames = 20
    private val measured = batchCount * batchFrames
    private val budgetMillis = 8.0
    private val feedbackBudgetMillis = 15.0

    /**
     * What a shader drawing is allowed here.
     *
     * In the application a shader runs on the graphics card, thousands of pixels at a time, and is
     * usually the cheapest thing in the catalogue. This test has no graphics card, so it runs the
     * same program on the processor one pixel at a time. The number below is therefore not a frame
     * budget at all: it is a guard against a shader whose cost has run away, measured at a smaller
     * size so the test still finishes.
     */
    private val shaderBudgetMillis = 120.0
    private val shaderWidth = 160
    private val shaderHeight = 100
    private val shaderBatchFrames = 4

    @Test
    fun everyVisualizationFitsInAFrame() {
        val scope = CanvasDrawScope()
        // The audio is analysed once, up front, and every drawing then sees exactly the same
        // frames. Analysing inside the timed loop would have charged each drawing for an FFT it
        // never runs, and charged the ones with the most frames the most.
        val frames = analyse(warmup + measured + 2)

        val costs = VizCatalog.create().map { visualization ->
            visualization.reset()
            // Two bitmaps swapped, exactly as the window does, so the feedback blits are counted,
            // and at the same reduced size the window gives a blooming drawing.
            val shader = visualization is ShaderPreset || visualization.warp != null
            val scale = if (visualization.bloom > 0) SOFT_BUFFER_SCALE else 1f
            val bufferWidth = ((if (shader) shaderWidth else width) * scale).toInt()
            val bufferHeight = ((if (shader) shaderHeight else height) * scale).toInt()
            var front = ImageBitmap(bufferWidth, bufferHeight)
            var back = ImageBitmap(bufferWidth, bufferHeight)
            val size = Size(bufferWidth.toFloat(), bufferHeight.toFloat())
            val canvasWidth = if (shader) shaderWidth else width
            val canvasHeight = if (shader) shaderHeight else height
            // The front is drawn at the canvas size, like the window. The ground is left out: it runs
            // on the graphics card there, and has its own test below.
            val frontLayer = ImageBitmap(canvasWidth, canvasHeight)
            val canvasSize = Size(canvasWidth.toFloat(), canvasHeight.toFloat())
            var step = 0

            fun oneFrame() {
                val state = frames[step.coerceAtMost(frames.lastIndex)]
                if (visualization.trailAt(state.frame.mood) > 0f) {
                    val previous = if (step == 0) null else back
                    scope.draw(Density(1f), LayoutDirection.Ltr, Canvas(front), size) {
                        drawVisualizationFrame(visualization, state, previous)
                    }
                    val held = front
                    front = back
                    back = held
                } else {
                    scope.draw(Density(1f), LayoutDirection.Ltr, Canvas(frontLayer), canvasSize) {
                        with(visualization) { draw(state) }
                    }
                }
                scope.draw(Density(1f), LayoutDirection.Ltr, Canvas(frontLayer), canvasSize) {
                    with(visualization) { drawFront(state) }
                }
                step++
            }

            val perBatch = if (shader) shaderBatchFrames else batchFrames
            repeat(if (shader) 2 else warmup) { oneFrame() }
            // The fastest of several short runs, not the mean of one long one. Every other
            // program on the machine can only ever make a batch slower, so the quickest batch is
            // the closest thing to this drawing's own cost. Averaging measures the laptop's mood.
            var best = Double.MAX_VALUE
            repeat(if (shader) 3 else batchCount) {
                val startedAt = System.nanoTime()
                repeat(perBatch) { oneFrame() }
                val perFrame = (System.nanoTime() - startedAt) / 1_000_000.0 / perBatch
                if (perFrame < best) best = perFrame
            }
            visualization.name to best
        }.sortedByDescending { it.second }

        println("draw cost at ${width}x$height, software rendering, fastest milliseconds per frame")
        costs.forEach { (name, millis) -> println("  ${millis.format()}  $name") }

        // A drawing with a feedback loop spends most of its time blending whole images, which
        // the window hands to the graphics card and this test does on the processor. Holding it to
        // the same number as a drawing that only strokes paths would be measuring the wrong thing.
        val feedback = VizCatalog.create().filter { it.trail > 0f || it.moodSpec != null }.map { it.name }.toSet()
        val shaders = VizCatalog.create()
            .filter { it is ShaderPreset || it.warp != null }
            .map { it.name }
            .toSet()
        val tooSlow = costs.filter {
            val budget = when {
                it.first in shaders -> shaderBudgetMillis
                it.first in feedback -> feedbackBudgetMillis
                else -> budgetMillis
            }
            it.second > budget
        }
        assertTrue(
            tooSlow.isEmpty(),
            "over budget: " + tooSlow.joinToString { "${it.first} at ${it.second.format()} ms" },
        )
    }

    @Test
    fun everyGroundIsCheap() {
        val scope = CanvasDrawScope()
        val frames = analyse(12)
        val size = Size(shaderWidth.toFloat(), shaderHeight.toFloat())
        val target = ImageBitmap(shaderWidth, shaderHeight)
        val slow = ArrayList<String>()
        for (kind in GroundKind.entries) {
            val ground = Ground(kind)
            var best = Double.MAX_VALUE
            frames.forEachIndexed { index, state ->
                val startedAt = System.nanoTime()
                scope.draw(Density(1f), LayoutDirection.Ltr, Canvas(target), size) { drawGround(ground, state) }
                val millis = (System.nanoTime() - startedAt) / 1_000_000.0
                if (index >= 2 && millis < best) best = millis
            }
            println("  ${best.format()}  ground $kind at ${shaderWidth}x$shaderHeight")
            if (best > GROUND_GUARD_MILLIS) slow += "$kind at ${best.format()} ms"
        }
        for (kind in DetailKind.entries) {
            val detail = Detail(kind)
            var best = Double.MAX_VALUE
            frames.forEachIndexed { index, state ->
                val startedAt = System.nanoTime()
                scope.draw(Density(1f), LayoutDirection.Ltr, Canvas(target), size) { drawDetail(detail, state) }
                val millis = (System.nanoTime() - startedAt) / 1_000_000.0
                if (index >= 2 && millis < best) best = millis
            }
            println("  ${best.format()}  detail $kind at ${shaderWidth}x$shaderHeight")
            if (best > GROUND_GUARD_MILLIS) slow += "detail $kind at ${best.format()} ms"
        }
        assertTrue(slow.isEmpty(), "grounds over their guard: " + slow.joinToString())
    }

    /** Every render state the run needs, analysed from real samples before any timing starts. */
    private fun analyse(count: Int): List<VizRenderState> {
        val delta = 1f / 60f
        val player = SongPlayer(SyntheticSong.drumLoop(seconds = count * delta + 4f))
        var elapsed = 0f
        var musicTime = 0f
        return List(count) {
            elapsed += delta
            val frame = player.next(delta)
            musicTime += delta * frame.motionRate
            VizRenderState(frame, elapsed, delta, VizPalette.Prism, musicTime)
        }
    }

    private companion object {
        /** A ground at 160 by 100 on the processor. Far above what they cost; it catches one that runs away. */
        const val GROUND_GUARD_MILLIS = 12.0
    }

    private fun Double.format(): String {
        val rounded = (this * 100).toLong() / 100.0
        return rounded.toString().padStart(6)
    }
}
