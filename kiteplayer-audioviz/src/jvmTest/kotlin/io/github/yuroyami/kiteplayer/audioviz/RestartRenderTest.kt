package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.VizCatalog
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.Visualization
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.ShaderPreset
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A drawing that was used draws like a fresh one after it is restarted.
 *
 * The director shows a drawing many times in one run. A drawing that keeps particles, history or a
 * spring from its last showing starts the next showing with the last song's picture. Each drawing
 * renders a lively song fresh, and again after it played a calm song and was restarted.
 */
class RestartRenderTest {

    init { useSkiaGraphics() }

    private fun lastFrame(drawing: Visualization, width: Int, height: Int, song: RenderHarness.Song): IntArray {
        val player = RenderHarness.player(song, 8f)
        var last = IntArray(0)
        // The harness restarts the drawing before its first frame.
        RenderHarness.forEachFrameOf(drawing, width, height, FRAMES, VizPalette.Prism, source = { player.next(1f / 60f) }) { bitmap, step ->
            if (step == FRAMES - 1) last = IntArray(bitmap.width * bitmap.height).also { bitmap.readPixels(it) }
        }
        return last
    }

    private fun luma(pixel: Int): Float =
        (0.2126f * ((pixel shr 16) and 255) + 0.7152f * ((pixel shr 8) and 255) + 0.0722f * (pixel and 255)) / 255f

    @Test
    fun aRestartedDrawingDrawsLikeAFreshOne() {
        val indices = VizCatalog.create().indices.toList()
        val rows = RenderHarness.inParallel(indices) { index ->
            val fresh = DriverProbe.drawing(index)
            val reused = DriverProbe.drawing(index)
            val (width, height) = if (fresh is ShaderPreset) {
                DriverProbe.SHADER_WIDTH to DriverProbe.SHADER_HEIGHT
            } else {
                DriverProbe.WIDTH to DriverProbe.HEIGHT
            }
            val expected = lastFrame(fresh, width, height, RenderHarness.Song.Lively)
            lastFrame(reused, width, height, RenderHarness.Song.Calm)
            val again = lastFrame(reused, width, height, RenderHarness.Song.Lively)
            var sum = 0f
            for (pixel in expected.indices) sum += abs(luma(expected[pixel]) - luma(again[pixel]))
            fresh.name to sum / expected.size
        }
        for ((name, difference) in rows) println("restart $name: mean difference $difference")
        val leaking = rows.filter { it.second > MOST_DIFFERENCE }
        assertTrue(
            leaking.isEmpty(),
            "drawings that keep state across a restart (limit $MOST_DIFFERENCE):\n" +
                leaking.joinToString("\n") { "${it.first}: ${it.second}" },
        )
    }

    private companion object {
        const val FRAMES = 120

        /** Under a quarter of one level in 255 on average, which the eye cannot see. */
        const val MOST_DIFFERENCE = 0.001f
    }
}
