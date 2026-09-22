package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.Rng
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.RecipeComposer
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.RecipeScreen
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.RecipeTemplate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OdysseyComposerTest {
    private val span = 80f

    @Test
    fun theSameSeedComposesTheSameDistricts() {
        val first = RecipeComposer(41L); val second = RecipeComposer(41L)
        // The opening district is kept here, because the walk pushes it out of the composer's window.
        val walk = (-1L until 30L).map { index ->
            val recipe = first.recipeFor(index, span)
            assertEquals(recipe, second.recipeFor(index, span))
            recipe
        }
        first.reset()
        assertEquals(walk[0], first.recipeFor(-1L, span))
    }

    @Test
    fun differentSeedsOpenOnDifferentDistricts() {
        val opened = (0 until 10).map { RecipeComposer(1000L + it * 7L).recipeFor(0L, span) }.toSet()
        assertTrue(opened.size >= 8, "ten seeds gave only ${opened.size} different opening districts")
    }

    @Test
    fun everyShownRecipePassesTheScreenAndTheTemplatesRotate() {
        val windows = 25
        for (seedIndex in 0 until 10) {
            val composer = RecipeComposer(9_000L + seedIndex)
            val shown = (-1L until 99L).map { composer.recipeFor(it, span) }
            assertEquals(0, composer.forced, "seed $seedIndex forced a recipe past the screen")
            for (recipe in shown) assertTrue(RecipeScreen.judge(recipe, span).ok, "seed $seedIndex shows a recipe the screen refuses")
            for (start in 0..shown.size - windows) {
                val templates = shown.subList(start, start + windows).map { it.template }.toSet()
                assertEquals(RecipeTemplate.ALL.size, templates.size, "seed $seedIndex window $start misses a template: $templates")
            }
            for (i in 1 until shown.size) assertTrue(shown[i] != shown[i - 1], "seed $seedIndex repeats district ${i - 1}")
            assertTrue(composer.attempts < shown.size * 4, "seed $seedIndex needed ${composer.attempts} screen calls for ${shown.size} districts")
        }
    }

    @Test
    fun everyTemplateDrawsRecipesTheScreenAcceptsMostOfTheTime() {
        val short = ArrayList<String>()
        for ((index, template) in RecipeTemplate.ALL.withIndex()) {
            val random = Rng(300L + index)
            val refusals = HashMap<String, Int>()
            var accepted = 0
            repeat(40) {
                val verdict = RecipeScreen.judge(template.draw(index, random), span)
                if (verdict.ok) accepted++ else {
                    refusals[verdict.reason] = (refusals[verdict.reason] ?: 0) + 1
                    println("${template.name} refused ${verdict.reason}: openness ${verdict.openness} presence ${verdict.presence} surface ${verdict.surface} steps ${verdict.steps}")
                }
            }
            // Every template is measured and printed before anything fails, so the run's own
            // record carries all five counts even when one of them is short.
            println("${template.name} accepted $accepted of 40, refused $refusals")
            if (accepted < 24) short += "${template.name} passes the screen only $accepted times of 40"
        }
        assertTrue(short.isEmpty(), short.joinToString("; "))
    }

    @Test
    fun theScreenRefusesASolidAndAnEmptyRecipe() {
        val solid = RecipeTemplate.ALL[0].draw(0, Rng(5L)).copy(size = 40f)
        assertEquals("solid", RecipeScreen.judge(solid, span).reason)
        // A sculpture shrunk to half a unit leaves nothing within reach of the route.
        val empty = RecipeTemplate.ALL[4].draw(4, Rng(5L)).copy(cell = 0.5f, size = 0.001f)
        val emptyVerdict = RecipeScreen.judge(empty, span)
        assertEquals("empty", emptyVerdict.reason)
        assertEquals(0f, emptyVerdict.surface, "no ray may reach a recipe this small")
    }

    @Test
    fun anAcceptedCavernHasASurfaceInFrontOfMostOfItsRays() {
        val random = Rng(300L)
        val accepted = (0 until 40).map { RecipeTemplate.ALL[0].draw(0, random) }
            .map { RecipeScreen.judge(it, span) }
            .filter { it.ok }
        assertTrue(accepted.isNotEmpty(), "the sponge has to accept something for this test to mean anything")
        for (verdict in accepted) {
            assertTrue(verdict.surface >= RecipeScreen.CAVERN_SURFACE,
                "an accepted cavern reported only ${verdict.surface} of its rays reaching a surface")
        }
    }

    @Test
    fun aStepBudgetOfZeroRefusesEveryRecipeAndForcesTheComposer() {
        val recipe = RecipeTemplate.ALL[1].draw(1, Rng(7L))
        assertTrue(RecipeScreen.judge(recipe, span).ok, "the sample recipe has to pass at the standing budget")
        assertEquals("too fine", RecipeScreen.judge(recipe, span, budget = 0f).reason)
        val composer = RecipeComposer(77L, budget = 0f)
        val shown = composer.recipeFor(0L, span)
        assertTrue(shown.template in RecipeTemplate.ALL.indices, "a forced recipe still names its template")
        assertTrue(composer.forced > 0, "a budget of zero has to force the composer past the screen")
    }

    @Test
    fun theComposerKeepsTheRecentDistrictsAndRefusesTheOnesItHasForgotten() {
        val composer = RecipeComposer(53L)
        // Districts -1 to 98, so the newest sits at position 99 and the window holds 88 to 99.
        val shown = (-1L until 99L).map { composer.recipeFor(it, span) }
        assertEquals(shown[91], composer.recipeFor(90L, span), "a district inside the window must not be recomposed")
        assertFailsWith<IllegalArgumentException> { composer.recipeFor(0L, span) }
    }
}
