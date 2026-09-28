package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.VizCatalog
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import org.jetbrains.skia.impl.Stats
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A shader drawing does not leave one native shader behind for every frame it draws.
 *
 * Each shader holds copies of its child pictures. Nothing but a garbage collection releases a
 * shader that was never closed, and the collector sees only the small wrapper, so a long run can
 * pile up a lot of native memory.
 */
class ShaderBrushLifetimeTest {

    init { useSkiaGraphics() }

    @Test
    fun aShaderDrawingKeepsOnlyAFewShadersAlive() {
        Stats.enabled = true
        val catalogue = VizCatalog.create()
        val drawing = DriverProbe.drawing(catalogue.indexOfFirst { it.name == "Odyssey" })
        val player = RenderHarness.player(RenderHarness.Song.Lively, 8f)
        collect()
        val before = live()
        RenderHarness.forEachFrameOf(drawing, 64, 40, FRAMES, VizPalette.Prism, source = { player.next(1f / 60f) }) { _, _ -> }
        val added = live() - before
        assertTrue(added < MOST_ALIVE, "$added shaders were alive after $FRAMES frames")
    }

    private fun live(): Int = Stats.allocated["Shader"] ?: 0

    private fun collect() {
        repeat(2) {
            System.gc()
            Thread.sleep(300)
        }
    }

    private companion object {
        const val FRAMES = 300

        /** A few for the frames still in flight. One per frame would be 300. */
        const val MOST_ALIVE = 40
    }
}
