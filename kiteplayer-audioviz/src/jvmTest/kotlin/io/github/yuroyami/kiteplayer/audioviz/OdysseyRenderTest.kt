package io.github.yuroyami.kiteplayer.audioviz

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.Odyssey
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.OdysseyScene
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.Recipe
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.RecipeComposer
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.ShaderLibrary
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class OdysseyRenderTest {
    init { useSkiaGraphics() }

    @Test
    fun connectedProgramsRenderVisibleArchitectureEvenBeforePlayback() {
        val odyssey = Odyssey(worldSeed = 2_029L)
        assertTrue(odyssey.runs, odyssey.compileError)
        val state = VizRenderState(SpectrumFrame(0, FloatArray(40), FloatArray(40), FloatArray(256),
            0.5f, 0.3f, 0.3f, 0.3f, 0f, 0f, held = true), 1f, 1f / 60f, VizPalette.Prism)
        fun render(): Float {
            val image = ImageBitmap(64, 40)
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(64f, 40f)) {
                with(odyssey) { draw(state) }
            }
            val pixels = image.toPixelMap()
            var visible = 0
            for (y in 0 until 40) for (x in 0 until 64) {
                val c = pixels[x, y]
                if (maxOf(c.red, c.green, c.blue) > 0.08f) visible++
            }
            return visible / 2560f
        }
        assertTrue(render() > 0.1f, "Odyssey must show architecture while paused, not a black screen")
        odyssey.reset()
        assertTrue(render() > 0.1f, "Reset must leave a visible starting world")
    }

    @Test
    fun exportExactProgramsForAndroidCompilerRegression() {
        // The Android probe consumes these exact runtime strings. Desktop compilation alone does
        // not exercise Android's expanded-program budget.
        val directory = File("build/odyssey-android-probe").apply { mkdirs() }
        // The probe script pushes every program and table here, so older exports must not linger.
        directory.listFiles { file -> file.extension == "sksl" || file.extension == "txt" }?.forEach { it.delete() }
        for ((name, source) in mapOf(
            "coarse" to OdysseyScene.COARSE_SOURCE,
            "pack" to OdysseyScene.PACK_SOURCE,
            "scene" to OdysseyScene.DISTANCE_SOURCE,
            "refine" to OdysseyScene.REFINE_SOURCE,
            "shade" to ShaderLibrary.HEADER + OdysseyScene.SOURCE,
        )) File(directory, "$name.sksl").writeText(source)
        // The probe cannot run the composer, so it reads the districts a fixed seed composes.
        // A composer keeps only its last twelve districts, so each music state walks a fresh one.
        for ((name, music) in listOf("recipes-quiet" to floatArrayOf(0f, 0f, 0f), "recipes-active" to floatArrayOf(0.765f, 0.68f, 0.7225f))) {
            val composer = RecipeComposer(2_026L)
            val lines = ArrayList<String>()
            var start = 0.0
            for (index in -1L..16L) {
                var bits = (index xor (index ushr 32)).toInt() xor 2_029
                bits = (bits xor (bits ushr 16)) * 0x45d9f3b
                bits = (bits xor (bits ushr 16)) * 0x45d9f3b
                bits = bits xor (bits ushr 16)
                val seed = (bits and 0xffff) / 65_535f
                val length = 66f + 18f * seed
                if (index == -1L) start = -length.toDouble()
                val recipe = composer.recipeFor(index, length)
                val steps = FloatArray(Recipe.STEPS * 4)
                val tails = FloatArray(4)
                recipe.pack(steps, tails, 0, bass = music[0], hit = music[1], mid = music[2])
                val row = floatArrayOf(start.toFloat(), length, recipe.safety, seed) + steps + tails
                lines += row.joinToString(" ")
                start += length
            }
            File(directory, "$name.txt").writeText(lines.joinToString("\n"))
        }
    }

    @Test
    fun depthPassesCoverTheCanvasAfterPortraitAndOddSizeResizes() {
        val odyssey = Odyssey(worldSeed = 2_029L)
        val state = VizRenderState(SpectrumFrame.silent(40, 256), 1f, 1f / 60f, VizPalette.Prism)
        for ((width, height) in listOf(67 to 41, 41 to 67, 120 to 53, 67 to 41)) {
            val image = ImageBitmap(width, height)
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(width.toFloat(), height.toFloat())) {
                with(odyssey) { draw(state) }
            }
            val pixels = image.toPixelMap()
            var visible = 0
            for (y in 0 until height) for (x in 0 until width) {
                val colour = pixels[x, y]
                assertTrue(colour.alpha > 0.99f, "The depth grid must not clip the full $width x $height output")
                if (maxOf(colour.red, colour.green, colour.blue) > 0.08f) visible++
            }
            assertTrue(visible > width * height / 10, "Resizing must retain the visible world")
        }
    }
}
