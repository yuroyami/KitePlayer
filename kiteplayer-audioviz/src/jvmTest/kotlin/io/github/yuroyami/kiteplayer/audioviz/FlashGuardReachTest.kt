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

    /** The mean luma of the ground alone, drawn over the background, at one allowed light. */
    private fun groundLuma(index: Int, light: Float): Pair<String, Float>? {
        val drawing = DriverProbe.drawing(index)
        if (drawing.ground == null) return null
        val player = RenderHarness.player(RenderHarness.Song.Lively, 6f)
        var shown = 0f
        RenderHarness.forEachFrameOf(
            drawing, DriverProbe.WIDTH, DriverProbe.HEIGHT, 60, VizPalette.Prism,
            source = { player.next(1f / 60f) },
            groundAt = { it == 59 },
            onGround = { bitmap, _ ->
                val pixels = IntArray(bitmap.width * bitmap.height).also { bitmap.readPixels(it) }
                var sum = 0f
                for (pixel in pixels) {
                    sum += (0.2126f * ((pixel shr 16) and 255) + 0.7152f * ((pixel shr 8) and 255) +
                        0.0722f * (pixel and 255)) / 255f
                }
                shown = sum / pixels.size
            },
            beforeDraw = { it.lightScale = light },
        ) { _, _ -> }
        return drawing.name to shown
    }

    @Test
    fun theGroundFollowsTheAllowedLightToo() {
        val indices = VizCatalog.create().indices.toList()
        // What the ground adds is what it lifts above the plain background it is drawn over.
        val background = VizPalette.Prism.background.let { 0.2126f * it.red + 0.7152f * it.green + 0.0722f * it.blue }
        val rows = RenderHarness.inParallel(indices) { index ->
            val full = groundLuma(index, 1f) ?: return@inParallel null
            val half = groundLuma(index, 0.5f)!!
            Triple(full.first, full.second - background, half.second - background)
        }.filterNotNull()
        for ((name, full, half) in rows) println("ground $name: added light at full $full, at half $half")
        assertTrue(rows.isNotEmpty(), "no drawing has a ground")
        val stubborn = rows.filter { (_, full, half) -> full > 0.002f && half > full * 0.75f }
        assertTrue(
            stubborn.isEmpty(),
            "grounds that ignore the light the guard allows:\n" +
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
