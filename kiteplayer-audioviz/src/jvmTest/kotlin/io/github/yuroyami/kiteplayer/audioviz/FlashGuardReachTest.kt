package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.VizCatalog
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.ShaderPreset
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The light that the flash guard allows reaches every drawing.
 *
 * The guard cannot dim a finished frame. It lowers `VizRenderState.lightScale`, and each drawing has
 * to scale its own light by it. A drawing that ignores the value keeps flashing when the guard asks
 * it to stop. Each drawing renders the same drum loop at full light and at half, and the half
 * render must be dimmer.
 */
class FlashGuardReachTest {

    init { useSkiaGraphics() }

    private fun meanLuma(index: Int, light: Float): Pair<String, Float> {
        val drawing = DriverProbe.drawing(index)
        val (width, height) = if (drawing is ShaderPreset) {
            DriverProbe.SHADER_WIDTH to DriverProbe.SHADER_HEIGHT
        } else {
            DriverProbe.WIDTH to DriverProbe.HEIGHT
        }
        val player = RenderHarness.player(RenderHarness.Song.Lively, 8f)
        var shown = 0f
        RenderHarness.forEachFrameOf(
            drawing, width, height, FRAMES, VizPalette.Prism,
            source = { player.next(1f / 60f) },
            beforeDraw = { it.lightScale = light },
        ) { bitmap, step ->
            if (step != FRAMES - 1) return@forEachFrameOf
            val pixels = IntArray(bitmap.width * bitmap.height).also { bitmap.readPixels(it) }
            var sum = 0f
            for (pixel in pixels) {
                sum += (0.2126f * ((pixel shr 16) and 255) + 0.7152f * ((pixel shr 8) and 255) +
                    0.0722f * (pixel and 255)) / 255f
            }
            shown = sum / pixels.size
        }
        return drawing.name to shown
    }

    @Test
    fun halfTheLightMakesEveryDrawingDimmer() {
        val indices = VizCatalog.create().indices.toList()
        val rows = RenderHarness.inParallel(indices) { index ->
            val (name, full) = meanLuma(index, 1f)
            val (_, half) = meanLuma(index, 0.5f)
            Triple(name, full, half)
        }
        for ((name, full, half) in rows) println("light $name: full $full, half $half, share ${if (full > 0f) half / full else 1f}")
        val stubborn = rows.filter { (_, full, half) -> full > MINIMUM && half > full * MOST_SHARE }
        assertTrue(
            stubborn.isEmpty(),
            "drawings that ignore the light the guard allows (limit $MOST_SHARE of full):\n" +
                stubborn.joinToString("\n") { (name, full, half) -> "$name: full $full, half $half" },
        )
    }

    private companion object {
        /** Long enough for every trail and spring to settle. */
        const val FRAMES = 150

        /** A picture darker than this has no light to lose. */
        const val MINIMUM = 0.01f

        /** Half the light must leave clearly less than the full picture. *Judgement.* */
        const val MOST_SHARE = 0.92f
    }
}
