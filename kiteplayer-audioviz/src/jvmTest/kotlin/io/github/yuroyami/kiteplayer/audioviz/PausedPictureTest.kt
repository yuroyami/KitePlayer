package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.VizCatalog
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.ShaderPreset
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A paused player holds every drawing still.
 *
 * The mapping document says nothing travels while the player is paused, and a picture that keeps
 * moving or changing under a paused song reads as broken. Each drawing plays a drum loop, the
 * player pauses, the drawing gets five seconds to settle its springs and fades, and then no frame
 * of the next second may differ noticeably from the one before. A trail that feeds the picture back
 * through 8-bit buffers keeps a rounding noise of well under one level in 255, which is allowed.
 */
class PausedPictureTest {

    init { useSkiaGraphics() }

    private val delta = 1f / 60f

    /** The mean change of one pixel, 0 to 1, between two frames. */
    private fun change(a: IntArray, b: IntArray): Float {
        var sum = 0f
        for (index in a.indices) sum += abs(luma(a[index]) - luma(b[index]))
        return sum / a.size
    }

    private fun luma(pixel: Int): Float =
        (0.2126f * ((pixel shr 16) and 255) + 0.7152f * ((pixel shr 8) and 255) + 0.0722f * (pixel and 255)) / 255f

    /** The largest change between two neighbouring frames of the last second of a pause. */
    private fun changedWhilePaused(index: Int): Pair<String, Float> {
        val drawing = DriverProbe.drawing(index)
        val (width, height) = if (drawing is ShaderPreset) {
            DriverProbe.SHADER_WIDTH to DriverProbe.SHADER_HEIGHT
        } else {
            DriverProbe.WIDTH to DriverProbe.HEIGHT
        }
        val player = RenderHarness.player(RenderHarness.Song.Lively, 12f)
        var last: SpectrumFrame? = null
        var previous: IntArray? = null
        var widest = 0f
        RenderHarness.forEachFrameOf(
            drawing, width, height, PLAY_FRAMES + SETTLE_FRAMES + WATCH_FRAMES, VizPalette.Prism,
            source = { step ->
                if (step < PLAY_FRAMES) player.next(delta).also { last = it }
                else checkNotNull(last).withPulseHeld().withEvents(held = true)
            },
        ) { bitmap, step ->
            if (step < PLAY_FRAMES + SETTLE_FRAMES) return@forEachFrameOf
            val pixels = IntArray(bitmap.width * bitmap.height).also { bitmap.readPixels(it) }
            previous?.let { widest = maxOf(widest, change(it, pixels)) }
            previous = pixels
        }
        return drawing.name to widest
    }

    @Test
    fun everyDrawingStandsStillWhileThePlayerIsPaused() {
        val indices = VizCatalog.create().indices.toList()
        val rows = RenderHarness.inParallel(indices) { changedWhilePaused(it) }
        for ((name, widest) in rows) println("paused $name: widest change between frames $widest")
        val moving = rows.filter { it.second > MOST_CHANGE }
        assertTrue(
            moving.isEmpty(),
            "drawings that changed while paused (limit $MOST_CHANGE):\n" +
                moving.joinToString("\n") { "${it.first}: ${it.second}" },
        )
    }

    private companion object {
        /** Four seconds of music before the pause. */
        const val PLAY_FRAMES = 240

        /** Five seconds for springs and fades to settle after the pause. */
        const val SETTLE_FRAMES = 300

        /** One second that must not change. */
        const val WATCH_FRAMES = 60

        /** About a quarter of one level in 255 on average. The eye cannot see a picture move by less. */
        const val MOST_CHANGE = 0.001f
    }
}
