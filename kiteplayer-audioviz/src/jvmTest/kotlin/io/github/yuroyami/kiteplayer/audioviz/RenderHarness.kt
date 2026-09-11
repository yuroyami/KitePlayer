package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.restart
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.Visualization
import io.github.yuroyami.kiteplayer.audioviz.viz.SOFT_BUFFER_SCALE
import io.github.yuroyami.kiteplayer.audioviz.viz.drawComposedFrame
import io.github.yuroyami.kiteplayer.audioviz.viz.drawVisualizationFrame
import io.github.yuroyami.kiteplayer.audioviz.viz.ground.drawGround
import java.awt.image.BufferedImage
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Renders a drawing off screen exactly the way the window does: the echo layer into two swapped
 * bitmaps, then the ground, the echo layer and the front composed into one picture.
 */
internal object RenderHarness {

    /** Which fake song a render should listen to. */
    enum class Song { Silence, Calm, Lively }

    fun render(
        visualization: Visualization,
        width: Int,
        height: Int,
        frames: Int,
        palette: VizPalette,
        song: Song = Song.Lively,
    ): BufferedImage {
        var last: BufferedImage? = null
        forEachFrame(visualization, width, height, frames, palette, song) { image, step ->
            if (step == frames - 1) last = image.toBufferedImage().scaledTo(width, height)
        }
        return last ?: BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    }

    /** A real analyser fed with one of the fake songs, the way a window is fed by a player. */
    fun player(song: Song, seconds: Float): SongPlayer {
        val samples = when (song) {
            Song.Silence -> SyntheticSong.silence(seconds)
            Song.Calm -> SyntheticSong.calmPad(seconds)
            Song.Lively -> SyntheticSong.drumLoop(seconds)
        }
        val side = when (song) {
            Song.Silence -> null
            Song.Calm -> SyntheticSong.calmPadSide(seconds)
            Song.Lively -> SyntheticSong.drumLoopSide(seconds)
        }
        return SongPlayer(samples, bandCount = 48, side = side)
    }

    /**
     * Renders a run and hands every composed frame to [onFrame], newest last. On the steps where
     * [groundAt] says so, the ground alone is rendered first and handed to [onGround].
     */
    fun forEachFrame(
        visualization: Visualization,
        width: Int,
        height: Int,
        frames: Int,
        palette: VizPalette,
        song: Song,
        groundAt: (Int) -> Boolean = { false },
        onGround: (ImageBitmap, Int) -> Unit = { _, _ -> },
        onFrame: (ImageBitmap, Int) -> Unit,
    ) {
        val delta = 1f / 60f
        val player = player(song, frames * delta + 4f)
        val scale = if (visualization.bloom > 0) SOFT_BUFFER_SCALE else 1f
        val echoWidth = (width * scale).toInt().coerceAtLeast(1)
        val echoHeight = (height * scale).toInt().coerceAtLeast(1)
        var front = ImageBitmap(echoWidth, echoHeight)
        var back = ImageBitmap(echoWidth, echoHeight)
        val output = ImageBitmap(width, height)
        var groundOnly: ImageBitmap? = null
        val scope = CanvasDrawScope()
        val echoSize = Size(echoWidth.toFloat(), echoHeight.toFloat())
        val fullSize = Size(width.toFloat(), height.toFloat())

        visualization.restart()
        var elapsed = 0f
        var musicTime = 0f
        var echoes = 0
        for (step in 0 until frames) {
            elapsed += delta
            val frame = player.next(delta)
            musicTime += delta * frame.motionRate
            val state = VizRenderState(frame, elapsed, delta, palette, musicTime)
            if (visualization.trailAt(frame.mood) > 0f) {
                val previous = if (echoes == 0) null else back
                scope.draw(Density(1f), LayoutDirection.Ltr, Canvas(front), echoSize) {
                    drawVisualizationFrame(visualization, state, previous)
                }
                if (groundAt(step)) {
                    val alone = groundOnly ?: ImageBitmap(width, height).also { groundOnly = it }
                    scope.draw(Density(1f), LayoutDirection.Ltr, Canvas(alone), fullSize) {
                        drawRect(palette.background)
                        visualization.ground?.let { drawGround(it, state) }
                    }
                    onGround(alone, step)
                }
                val echo = front
                scope.draw(Density(1f), LayoutDirection.Ltr, Canvas(output), fullSize) {
                    drawComposedFrame(visualization, state, echo)
                }
                val held = front
                front = back
                back = held
                echoes++
            } else {
                if (groundAt(step)) {
                    val alone = groundOnly ?: ImageBitmap(width, height).also { groundOnly = it }
                    scope.draw(Density(1f), LayoutDirection.Ltr, Canvas(alone), fullSize) {
                        drawRect(palette.background)
                        visualization.ground?.let { drawGround(it, state) }
                    }
                    onGround(alone, step)
                }
                scope.draw(Density(1f), LayoutDirection.Ltr, Canvas(output), fullSize) {
                    drawComposedFrame(visualization, state, null)
                }
            }
            onFrame(output, step)
        }
    }

    /** Runs [work] once per item on several threads and answers the results in the same order. */
    fun <T, R> inParallel(items: List<T>, work: (T) -> R): List<R> {
        val threads = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 7)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val futures = items.map { item -> pool.submit(Callable { work(item) }) }
            return futures.map { it.get() }
        } finally {
            pool.shutdown()
        }
    }

    fun BufferedImage.scaledTo(width: Int, height: Int): BufferedImage {
        if (this.width == width && this.height == height) return this
        val out = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = out.createGraphics()
        graphics.setRenderingHint(
            java.awt.RenderingHints.KEY_INTERPOLATION,
            java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR,
        )
        graphics.drawImage(this, 0, 0, width, height, null)
        graphics.dispose()
        return out
    }

    fun ImageBitmap.toBufferedImage(): BufferedImage {
        val pixels = IntArray(width * height)
        readPixels(pixels)
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, width, height, pixels, 0, width)
        return image
    }
}
