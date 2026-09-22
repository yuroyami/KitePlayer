package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.Rng
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.Recipe
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.RecipeField
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.RecipeOp
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.RecipePrimitive
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.RecipeStep
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.RecipeTemplate
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OdysseyRecipeTest {
    private fun sponge(): Recipe = Recipe(
        template = 0,
        steps = listOf(
            RecipeStep(RecipeOp.None),
            RecipeStep(RecipeOp.MengerFold, 3f), RecipeStep(RecipeOp.MengerFold, 3f), RecipeStep(RecipeOp.MengerFold, 3f),
            RecipeStep(RecipeOp.None), RecipeStep(RecipeOp.None), RecipeStep(RecipeOp.None), RecipeStep(RecipeOp.None),
        ),
        primitive = RecipePrimitive.Box, size = 1f, cavern = true, cell = 3.5f,
    )

    @Test
    fun packingWritesTheShaderCodesAndTurnsAnglesIntoCosineAndSine() {
        val recipe = sponge().copy(steps = sponge().steps.toMutableList().also {
            it[0] = RecipeStep(RecipeOp.RotateXY, 0.4f)
            it[4] = RecipeStep(RecipeOp.PlaneFold, 3f, 0f, 4f)
        })
        val steps = FloatArray(Recipe.SLOTS * Recipe.STEPS * 4)
        val tails = FloatArray(Recipe.SLOTS * 4)
        recipe.pack(steps, tails, slot = 2)
        val base = 2 * Recipe.STEPS * 4
        assertEquals(7f, steps[base])
        assertTrue(abs(steps[base + 1] - cos(0.4f)) < 1e-6f && abs(steps[base + 2] - sin(0.4f)) < 1e-6f)
        assertEquals(6f, steps[base + 4]); assertEquals(3f, steps[base + 5])
        assertTrue(abs(steps[base + 16 + 1] - 0.6f) < 1e-6f && abs(steps[base + 16 + 3] - 0.8f) < 1e-6f, "plane normals are unit length")
        assertEquals(0f, tails[8]); assertEquals(1f, tails[9]); assertEquals(1f, tails[10]); assertEquals(3.5f, tails[11])
    }

    @Test
    fun theSpongeKeepsTheRouteOpenAlongItsAxis() {
        val steps = FloatArray(Recipe.STEPS * 4); val tails = FloatArray(4)
        sponge().pack(steps, tails, slot = 0)
        var z = 0f
        while (z < 80f) {
            val distance = RecipeField.distance(steps, tails, 0, 0f, 0f, z, 80f, Recipe.STEPS)
            assertTrue(distance > 0f, "the axis at z=$z sits inside the sponge's central hole, distance=$distance")
            z += 0.5f
        }
    }

    @Test
    fun theSpongeHasSolidMaterialAwayFromItsHoles() {
        val steps = FloatArray(Recipe.STEPS * 4); val tails = FloatArray(4)
        sponge().pack(steps, tails, slot = 0)
        var inside = 0
        for (i in 0 until 200) {
            val random = Rng(500L + i)
            val x = (random.next() * 2f - 1f) * 3.5f; val y = (random.next() * 2f - 1f) * 3.5f; val z = random.next() * 80f
            if (RecipeField.distance(steps, tails, 0, x, y, z, 80f, Recipe.STEPS) < 0f) inside++
        }
        assertTrue(inside > 40, "a Menger sponge keeps about half its cube; only $inside of 200 samples were inside")
    }

    @Test
    fun fewerActiveStepsGiveACoarserShape() {
        val steps = FloatArray(Recipe.STEPS * 4); val tails = FloatArray(4)
        sponge().pack(steps, tails, slot = 0)
        val fine = RecipeField.distance(steps, tails, 0, 1.2f, 0.4f, 5f, 80f, Recipe.STEPS)
        val coarse = RecipeField.distance(steps, tails, 0, 1.2f, 0.4f, 5f, 80f, 2)
        assertTrue(fine != coarse, "dropping two Menger folds must change the field")
    }

    @Test
    fun aRecipeWithASphereFoldTrustsLessOfEachStep() {
        // Three Menger folds already cost the sponge some trust, so it starts below the plain 0.9.
        assertEquals(0.65f, sponge().safety)
        val plain = sponge().copy(steps = List(Recipe.STEPS) { RecipeStep(RecipeOp.None) })
        assertEquals(0.9f, plain.safety)
        val folded = sponge().copy(steps = sponge().steps.toMutableList().also { it[4] = RecipeStep(RecipeOp.SphereFold, 0.25f, 1f) })
        assertEquals(0.6f, folded.safety)
        // A fold-free recipe, so the scale case is read on its own rather than under the Menger floor.
        val stretched = plain.copy(steps = plain.steps.toMutableList().also { it[5] = RecipeStep(RecipeOp.Scale, 1.8f, 0.3f, 0.3f) })
        assertEquals(0.7f, stretched.safety)
    }

    @Test
    fun everyTemplateDrawsEightStepsAndMutationChangesOnlyOneStep() {
        for ((index, template) in RecipeTemplate.ALL.withIndex()) {
            val random = Rng(77L + index)
            val recipe = template.draw(index, random)
            assertEquals(Recipe.STEPS, recipe.steps.size)
            assertEquals(index, recipe.template)
            val at = template.slots.indices.first { template.slots[it].varies }
            val changed = template.mutate(recipe, at, random)
            assertTrue(changed.steps[at] != recipe.steps[at], "${template.name} step $at must change")
            for (other in recipe.steps.indices) if (other != at) assertEquals(recipe.steps[other], changed.steps[other])
        }
    }

    @Test
    fun mutationChangesEverySlotThatCanVaryWhicheverOperationItHolds() {
        // The one slot whose only variation is its operation. Mutating it while it holds the box
        // fold is the case a number redraw cannot change, so the run has to reach it.
        var sawTheFixedNumberSlotHoldingItsFold = false
        for ((index, template) in RecipeTemplate.ALL.withIndex()) {
            for (at in template.slots.indices) {
                val slot = template.slots[at]
                if (!slot.varies) continue
                for (seed in 0 until 6) {
                    val random = Rng(1_000L + index * 100L + at * 10L + seed)
                    val recipe = template.draw(index, random)
                    if (slot.ops.size > 1 && recipe.steps[at].op == RecipeOp.BoxFold) sawTheFixedNumberSlotHoldingItsFold = true
                    val changed = template.mutate(recipe, at, random)
                    assertTrue(changed.steps[at] != recipe.steps[at],
                        "${template.name} slot $at holding ${recipe.steps[at].op} at seed $seed must change")
                    for (other in recipe.steps.indices) if (other != at) assertEquals(recipe.steps[other], changed.steps[other])
                }
            }
        }
        assertTrue(sawTheFixedNumberSlotHoldingItsFold, "no sample drew the fixed-number slot holding its box fold")
    }
}
