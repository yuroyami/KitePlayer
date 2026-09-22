package io.github.yuroyami.kiteplayer.audioviz

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kiteplayer.audioviz.viz.Rng
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.OdysseyScene
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.Recipe
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.RecipeField
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.RecipeTemplate
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.ShaderProgram
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertTrue

/** The shader's recipe interpreter against its Kotlin copy, on random recipes at random points. */
class OdysseyRecipeFieldTest {
    init { useSkiaGraphics() }
    private val grid = 16

    @Test
    fun theShaderAndTheKotlinFieldAgreeOnRandomRecipes() {
        val probe = ShaderProgram(OdysseyScene.PROBE_SOURCE)
        assertTrue(probe.available, probe.error)
        compare(probe, Recipe.STEPS)
        // The recipe detail control stops the chain early, so the partial fold path needs an oracle too.
        compare(probe, 5)
    }

    /** Compares every point of the grid with the Kotlin field, with only [activeSteps] folds running. */
    private fun compare(probe: ShaderProgram, activeSteps: Int) {
        val steps = FloatArray(Recipe.SLOTS * Recipe.STEPS * 4)
        val tails = FloatArray(Recipe.SLOTS * 4)
        var compared = 0
        var mismatches = 0
        var worst = 0.0
        for (trial in 0 until 200) {
            val random = Rng(4_000L + trial)
            val template = trial % RecipeTemplate.ALL.size
            val recipe = RecipeTemplate.ALL[template].draw(template, random)
            val slot = trial % Recipe.SLOTS
            // Every other slot holds a different recipe, so a read from the wrong slot is caught.
            val other = (template + 1) % RecipeTemplate.ALL.size
            for (filler in 0 until Recipe.SLOTS) RecipeTemplate.ALL[other].draw(other, Rng(1L + filler)).pack(steps, tails, filler)
            recipe.pack(steps, tails, slot)
            val span = 66f + 18f * random.next()
            val originX = (random.next() * 2f - 1f) * 6f
            val originY = (random.next() * 2f - 1f) * 6f
            val originZ = random.next() * span
            val step = 0.37f
            probe.uniforms("uRecipe", steps)
            probe.uniforms("uRecipeTail", tails)
            probe.uniform("uShape", activeSteps.toFloat(), 0f, 0f, 0f)
            probe.uniform("uWave", 0f, 0f, 0f, 0f)
            probe.uniform("uResponse", 0.85f, 1f, 0.85f, 0.8f)
            probe.uniform("uProbeOrigin", originX, originY, originZ)
            probe.uniform("uProbeStep", step, step)
            probe.uniform("uProbeSlot", slot.toFloat())
            probe.uniform("uProbeSpan", span)
            val pixels = render(probe)
            for (y in 0 until grid) for (x in 0 until grid) {
                val expected = RecipeField.distance(steps, tails, slot, originX + x * step, originY + y * step, originZ, span, activeSteps).toDouble()
                val actual = depth(pixels[y * grid + x]) - 64.0
                val error = abs(actual - expected)
                compared++
                // A fold branch can flip on the last float bit; those points may disagree, but only a few.
                if (error > 0.004 + 0.004 * abs(expected)) {
                    mismatches++
                    if (mismatches <= 5) println("trial $trial (${RecipeTemplate.ALL[template].name}) at ($x,$y): shader=$actual kotlin=$expected")
                } else {
                    worst = maxOf(worst, error)
                }
            }
        }
        println("recipe oracle at $activeSteps steps compared $compared points, $mismatches branch mismatches, worst agreed error $worst")
        assertTrue(mismatches <= compared / 200, "$mismatches of $compared points disagree between the shader and the Kotlin field at $activeSteps steps")
        // The agreeing points must agree to the depth packet's own resolution, not merely closely.
        assertTrue(worst < 1e-4, "the shader drifts from the Kotlin field by $worst at $activeSteps steps")
    }

    private fun render(program: ShaderProgram): IntArray {
        val bitmap = ImageBitmap(grid, grid)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(grid.toFloat(), grid.toFloat())) {
            drawRect(checkNotNull(program.brush()))
        }
        val pixels = bitmap.toPixelMap()
        return IntArray(grid * grid) {
            val c = pixels[it % grid, it / grid]
            -0x1000000 or ((c.red * 255).roundToInt() shl 16) or ((c.green * 255).roundToInt() shl 8) or (c.blue * 255).roundToInt()
        }
    }

    private fun depth(pixel: Int): Double =
        (((pixel ushr 16) and 127) * 65025.0 + ((pixel ushr 8) and 255) * 255.0 + (pixel and 255)) * 128.0 / 8258175.0
}
