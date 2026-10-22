package io.github.yuroyami.kiteplayer.audioviz

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kiteplayer.audioviz.viz.SOFT_BUFFER_SCALE
import io.github.yuroyami.kiteplayer.audioviz.viz.VizCatalog
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.Visualization
import io.github.yuroyami.kiteplayer.audioviz.viz.drawComposedFrame
import io.github.yuroyami.kiteplayer.audioviz.viz.drawVisualizationFrame
import io.github.yuroyami.kiteplayer.audioviz.viz.restart
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.ShaderPreset
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every drawing survives a run of things that happen to a real surface, in a random order.
 *
 * The other tests each change one thing. A surface changes several at once and does not wait: the
 * canvas takes a new size while a trail is on screen, the player pauses in the middle of a drop, a
 * seek starts another song, the drawing is restarted, the palette changes, the guard cuts the
 * light, reduced motion turns on, and a frame takes anything from 7 ms to 50 ms. Each drawing runs
 * through a seeded shuffle of these and must not throw. The run is deterministic, so a failure
 * names a seed to replay.
 */
class MonkeyTest {

    init { useSkiaGraphics() }

    private val sizes = listOf(1 to 1, 2 to 3, 7 to 5, 33 to 17, 64 to 36, 36 to 64, 100 to 3, 3 to 100, 65 to 37, 128 to 80)
    private val smallSizes = listOf(1 to 1, 2 to 3, 7 to 5, 33 to 17, 24 to 14, 14 to 24, 40 to 3, 3 to 40, 31 to 19)
    private val steps = listOf(1f / 144f, 1f / 120f, 1f / 60f, 1f / 60f, 1f / 30f, 0.05f)
    private val palettes = listOf(VizPalette.Prism, VizPalette.Classic)

    /** One drawing through [STEPS] random steps, answering what went wrong or null. */
    private fun monkey(index: Int, seed: Long): String? {
        val drawing: Visualization = DriverProbe.drawing(index)
        val random = Random(seed * 31 + index)
        val shader = drawing is ShaderPreset
        val canvasSizes = if (shader) smallSizes else sizes
        val songs = listOf(RenderHarness.Song.Lively, RenderHarness.Song.Calm, RenderHarness.Song.Silence)
        var player = RenderHarness.player(songs[random.nextInt(songs.size)], 12f)
        var (width, height) = canvasSizes[random.nextInt(canvasSizes.size)]
        var palette = palettes[0]
        var light = 1f
        var motion = 1f
        var holdFor = 0
        var elapsed = 0f
        var musicTime = 0f
        var front = ImageBitmap(width, height)
        var back = ImageBitmap(width, height)
        var output = ImageBitmap(width, height)
        var echoes = 0
        var last: SpectrumFrame? = null
        val scope = CanvasDrawScope()
        drawing.restart()
        var what = "start"
        try {
            repeat(if (shader) STEPS / 3 else STEPS) { step ->
                val roll = random.nextFloat()
                what = "step $step"
                when {
                    roll < 0.03f -> holdFor = 5 + random.nextInt(85)
                    roll < 0.04f -> player = RenderHarness.player(songs[random.nextInt(songs.size)], 12f)
                    roll < 0.05f -> drawing.restart().also { echoes = 0 }
                    roll < 0.07f -> {
                        val next = canvasSizes[random.nextInt(canvasSizes.size)]
                        width = next.first
                        height = next.second
                        front = ImageBitmap(width, height)
                        back = ImageBitmap(width, height)
                        output = ImageBitmap(width, height)
                        echoes = 0
                    }
                    roll < 0.09f -> palette = palettes[random.nextInt(palettes.size)]
                    roll < 0.12f -> light = listOf(1f, 0.5f, 0.05f, 0f)[random.nextInt(4)]
                    roll < 0.13f -> motion = if (motion == 1f) 0.15f else 1f
                }
                val delta = steps[random.nextInt(steps.size)]
                var frame = player.next(delta)
                if (holdFor > 0) {
                    holdFor--
                    frame = (last ?: frame).withPulseHeld().withEvents(held = true)
                }
                last = frame
                elapsed += delta
                musicTime += delta * frame.motionRate
                val state = VizRenderState(frame, elapsed, delta, palette, musicTime, null)
                state.lightScale = light
                state.motionScale = motion
                val echoSize = Size(width.toFloat(), height.toFloat())
                if (drawing.trailAt(frame.mood) > 0f) {
                    val previous = if (echoes == 0) null else back
                    scope.draw(Density(1f), LayoutDirection.Ltr, Canvas(front), echoSize) {
                        drawVisualizationFrame(drawing, state, previous)
                    }
                    val echo = front
                    scope.draw(Density(1f), LayoutDirection.Ltr, Canvas(output), echoSize) {
                        drawComposedFrame(drawing, state, echo)
                    }
                    val held = front
                    front = back
                    back = held
                    echoes++
                } else {
                    scope.draw(Density(1f), LayoutDirection.Ltr, Canvas(output), echoSize) {
                        drawComposedFrame(drawing, state, null)
                    }
                }
            }
            return null
        } catch (failure: Throwable) {
            return "${drawing.name} (seed $seed) at $what on ${width}x$height: $failure"
        }
    }

    @Test
    fun everyDrawingSurvivesAShuffleOfWhatHappensToASurface() {
        val indices = VizCatalog.create().indices.toList()
        val problems = RenderHarness.inParallel(indices) { index ->
            (1L..SEEDS).mapNotNull { seed -> monkey(index, seed) }
        }.flatten()
        assertTrue(problems.isEmpty(), "drawings that threw during a shuffled run:\n" + problems.joinToString("\n"))
    }

    private companion object {
        const val STEPS = 300
        const val SEEDS = 3L
    }
}
