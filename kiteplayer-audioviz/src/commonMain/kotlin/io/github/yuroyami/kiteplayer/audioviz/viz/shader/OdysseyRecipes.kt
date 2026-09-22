package io.github.yuroyami.kiteplayer.audioviz.viz.shader

import io.github.yuroyami.kiteplayer.audioviz.viz.Rng
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** One fold operation of the recipe interpreter. [code] is the number the shader switches on. */
internal enum class RecipeOp(val code: Int) {
    None(0), Mirror(1), BoxFold(2), SphereFold(3), PlaneFold(4), Scale(5), MengerFold(6), RotateXY(7), RotateYZ(8),
}

/** What a recipe ends in after its folds. */
internal enum class RecipePrimitive(val code: Int) { Box(0), Sphere(1), Cross(2) }

/**
 * One step of a recipe: an operation and three numbers in the units a person writes.
 * A rotation keeps its angle here; packing turns it into the cosine and sine pair the shader reads.
 */
internal data class RecipeStep(val op: RecipeOp, val a: Float = 0f, val b: Float = 0f, val c: Float = 0f)

/**
 * The shape of one district: eight fold steps ending in a primitive.
 *
 * A cavern repeats the shape around the eye in cells of [cell] half size. A sculpture stands once
 * in the middle of its district, scaled to [cell] as its radius, with the sky visible around it.
 */
internal data class Recipe(
    val template: Int,
    val steps: List<RecipeStep>,
    val primitive: RecipePrimitive,
    val size: Float,
    val cavern: Boolean,
    val cell: Float,
) {
    init { require(steps.size == STEPS) { "a recipe has exactly $STEPS steps" } }

    /** How much of a distance estimate a ray may trust. Folds that stretch space earn a smaller share. */
    val safety: Float
        get() {
            var factor = 0.9f
            for (step in steps) when (step.op) {
                RecipeOp.SphereFold -> factor = minOf(factor, 0.6f)
                // A Menger fold multiplies space by about two and a quarter to three, so its estimate
                // overshoots near a fold plane and the coarse pass walks through the thin sheet it
                // should have hit. Measured on the foam's banked view: 0.75 lost 402 pixels of depth
                // against an allowance of 230, 0.65 loses 5.
                RecipeOp.MengerFold -> factor = minOf(factor, 0.65f)
                // The threshold reads the recipe's own number on purpose, before pack applies the music breath.
                RecipeOp.Scale -> if (abs(step.a) > 1.2f) factor = minOf(factor, 0.7f)
                else -> Unit
            }
            return factor
        }

    /**
     * Writes this recipe into the shader arrays for [slot], with the music applied.
     * [bass] pushes the mirror offsets, [hit] breathes the scale steps and [mid] turns the rotations.
     */
    fun pack(steps: FloatArray, tails: FloatArray, slot: Int, bass: Float = 0f, hit: Float = 0f, mid: Float = 0f) {
        val base = slot * STEPS * 4
        for ((index, step) in this.steps.withIndex()) {
            val at = base + index * 4
            steps[at] = step.op.code.toFloat()
            when (step.op) {
                RecipeOp.Mirror -> {
                    val push = 1f + MIRROR_PUSH * bass
                    steps[at + 1] = step.a * push; steps[at + 2] = step.b * push; steps[at + 3] = step.c * push
                }
                RecipeOp.PlaneFold -> {
                    val length = sqrt(step.a * step.a + step.b * step.b + step.c * step.c).coerceAtLeast(1e-6f)
                    steps[at + 1] = step.a / length; steps[at + 2] = step.b / length; steps[at + 3] = step.c / length
                }
                RecipeOp.Scale -> {
                    steps[at + 1] = step.a * (1f + SCALE_BREATH * hit); steps[at + 2] = step.b; steps[at + 3] = step.c
                }
                RecipeOp.RotateXY, RecipeOp.RotateYZ -> {
                    val angle = step.a + ROTATION_TURN * mid
                    steps[at + 1] = cos(angle); steps[at + 2] = sin(angle); steps[at + 3] = 0f
                }
                else -> { steps[at + 1] = step.a; steps[at + 2] = step.b; steps[at + 3] = step.c }
            }
        }
        tails[slot * 4] = primitive.code.toFloat()
        tails[slot * 4 + 1] = size
        tails[slot * 4 + 2] = if (cavern) 1f else 0f
        tails[slot * 4 + 3] = cell
    }

    companion object {
        const val STEPS: Int = 8
        /** The fewest folds the recipe detail control runs, and the shallower depth the screen judges. */
        const val LEAST_STEPS: Int = 4
        const val SLOTS: Int = 6
        // The music moves a step number by at most these shares, small enough for [safety] to hold.
        const val MIRROR_PUSH: Float = 0.12f
        const val SCALE_BREATH: Float = 0.06f
        const val ROTATION_TURN: Float = 0.25f
    }
}

/** A closed range one recipe number is drawn from. */
internal class Span(val min: Float, val max: Float) {
    val varies: Boolean get() = max > min
    fun draw(random: Rng): Float = min + (max - min) * random.next()
}

/** One step of a template: the operations it may be, and the ranges of its three numbers. */
internal class StepSlot(val ops: List<RecipeOp>, val a: Span = ZERO, val b: Span = ZERO, val c: Span = ZERO) {
    /** True when two draws can differ, so mutation has something to change. */
    val varies: Boolean get() = ops.size > 1 || a.varies || b.varies || c.varies

    fun draw(random: Rng): RecipeStep {
        val op = ops[(random.next() * ops.size).toInt().coerceIn(0, ops.lastIndex)]
        return if (op == RecipeOp.None) RecipeStep(RecipeOp.None) else RecipeStep(op, a.draw(random), b.draw(random), c.draw(random))
    }

    private companion object { val ZERO = Span(0f, 0f) }
}

/** A family of recipes known to look good: mostly fixed operations, open numbers. */
internal class RecipeTemplate(
    val name: String,
    val cavern: Boolean,
    val primitives: List<RecipePrimitive>,
    val size: Span,
    val cell: Span,
    val slots: List<StepSlot>,
) {
    init { require(slots.size == Recipe.STEPS) { "$name needs ${Recipe.STEPS} slots" } }

    fun draw(index: Int, random: Rng): Recipe = Recipe(
        template = index,
        steps = slots.map { it.draw(random) },
        primitive = primitives[(random.next() * primitives.size).toInt().coerceIn(0, primitives.lastIndex)],
        size = size.draw(random),
        cavern = cavern,
        cell = cell.draw(random),
    )

    /**
     * Changes step [at] of [recipe]. It redraws the step's numbers while the slot's spans can move
     * them, and otherwise takes one of the slot's other operations. A slot that cannot vary at all
     * is the one case that comes back unchanged.
     */
    fun mutate(recipe: Recipe, at: Int, random: Rng): Recipe {
        val slot = slots[at]
        val current = recipe.steps[at]
        var step = current
        // Redrawing the numbers only helps while a span can move them; fixed spans rebuild the same step.
        if (slot.a.varies || slot.b.varies || slot.c.varies) {
            val keepOp = current.op != RecipeOp.None && current.op in slot.ops
            var tries = 0
            while (step == current && tries < 8) {
                step = if (keepOp) RecipeStep(current.op, slot.a.draw(random), slot.b.draw(random), slot.c.draw(random))
                else slot.draw(random)
                tries++
            }
        }
        // A slot that varies only by operation, and any redraw that kept landing on the same step.
        val others = if (step == current) slot.ops.filter { it != current.op } else emptyList()
        if (others.isNotEmpty()) {
            val op = others[(random.next() * others.size).toInt().coerceIn(0, others.lastIndex)]
            step = if (op == RecipeOp.None) RecipeStep(RecipeOp.None)
            else RecipeStep(op, slot.a.draw(random), slot.b.draw(random), slot.c.draw(random))
        }
        return recipe.copy(steps = recipe.steps.toMutableList().also { it[at] = step })
    }

    companion object {
        private val unit = Span(-1f, 1f)
        private val angle = Span(-0.6f, 0.6f)
        private val none = StepSlot(listOf(RecipeOp.None))
        private fun plane(optional: Boolean = false) =
            StepSlot(if (optional) listOf(RecipeOp.PlaneFold, RecipeOp.None) else listOf(RecipeOp.PlaneFold), unit, unit, unit)
        private fun mirror(low: Float, high: Float, optional: Boolean = false) =
            StepSlot(if (optional) listOf(RecipeOp.Mirror, RecipeOp.None) else listOf(RecipeOp.Mirror),
                Span(low, high), Span(low, high), Span(low, high))
        private fun scale(low: Float, high: Float, shift: Span) = StepSlot(listOf(RecipeOp.Scale), Span(low, high), shift, shift)
        private fun menger(optional: Boolean = false, low: Float = 2.7f, high: Float = 3.0f) =
            StepSlot(if (optional) listOf(RecipeOp.MengerFold, RecipeOp.None) else listOf(RecipeOp.MengerFold), Span(low, high))
        // A box fold reflects whatever lies outside its limit back inside. A limit of one is the
        // cell wall itself, so that fold only does something after a step has scaled the point out.
        private fun boxFold(optional: Boolean = false, low: Float = 1f, high: Float = 1f) =
            StepSlot(if (optional) listOf(RecipeOp.BoxFold, RecipeOp.None) else listOf(RecipeOp.BoxFold), Span(low, high))
        // The first number is the inner radius squared. It bounds how much the inversion magnifies
        // space, so also how far the estimate can overshoot; below about 0.3 the coarse search loses walls.
        private fun sphereFold(optional: Boolean = false) =
            StepSlot(if (optional) listOf(RecipeOp.SphereFold, RecipeOp.None) else listOf(RecipeOp.SphereFold),
                Span(0.3f, 0.45f), Span(1f, 1f))
        private fun optionalScale(low: Float, high: Float, shift: Span) =
            StepSlot(listOf(RecipeOp.Scale, RecipeOp.None), Span(low, high), shift, shift)

        /**
         * The five starting families.
         *
         * Every range answers one measurement. A cavern reports the distance to the copy of its
         * shape in the point's own cell, so a shape that stops short of its own cell wall reports
         * a distance larger than the truth: the real nearest surface is the neighbour's copy. A
         * ray then steps straight into that neighbour, which is what the world test counts. So
         * the spans keep the folds' own scales modest and the primitives fat enough to reach the
         * wall. A feature must also stay wider than one quarter-resolution pixel of the coarse
         * search, which the same fat primitives give.
         */
        val ALL: List<RecipeTemplate> = listOf(
            RecipeTemplate("sponge", cavern = true, listOf(RecipePrimitive.Box), Span(0.9f, 1f), Span(4.5f, 6.5f), listOf(
                StepSlot(listOf(RecipeOp.RotateXY, RecipeOp.None), Span(-0.5f, 0.5f)),
                menger(), menger(), menger(optional = true), menger(optional = true), none, none, none)),
            // A scale step settles on shift divided by scale minus one. Keeping that point inside
            // the cell is what stops this family drawing a district of empty sky.
            RecipeTemplate("kaleidoscope", cavern = true, listOf(RecipePrimitive.Cross), Span(0.62f, 0.7f), Span(6.5f, 9f), listOf(
                boxFold(low = 0.5f, high = 0.9f), mirror(0.8f, 1.15f), StepSlot(listOf(RecipeOp.RotateXY), angle), plane(),
                scale(1.25f, 1.55f, Span(0f, 0.15f)), mirror(0.35f, 0.7f),
                StepSlot(listOf(RecipeOp.RotateYZ), angle), scale(1.2f, 1.45f, Span(0f, 0.12f)))),
            // The second fold round is optional. One round of this family is already a cavern of
            // shells, and two rounds put filigree between them that the coarse search walks through.
            RecipeTemplate("folded cavern", cavern = true, listOf(RecipePrimitive.Sphere, RecipePrimitive.Box), Span(0.75f, 0.88f), Span(6f, 7.5f), listOf(
                boxFold(low = 0.55f, high = 0.95f), sphereFold(), scale(-1.7f, -1.45f, Span(0.3f, 0.6f)), boxFold(optional = true),
                sphereFold(optional = true), optionalScale(-1.7f, -1.45f, Span(0.3f, 0.6f)),
                boxFold(optional = true), StepSlot(listOf(RecipeOp.RotateXY, RecipeOp.None), angle))),
            // Bubbles that repeat around the route, because the cell wrap copies them along it. The
            // family folds with Menger steps: a mirror after the cell wrap leaves an open gap along
            // the route that reads as a black bar across the picture. The multiplier stays below
            // three (a smaller multiplier magnifies the estimate's error less), which keeps the
            // coarse pass on the spheres.
            RecipeTemplate("foam", cavern = true, listOf(RecipePrimitive.Sphere), Span(1.15f, 1.35f), Span(5f, 7f), listOf(
                StepSlot(listOf(RecipeOp.RotateYZ), Span(-0.5f, 0.5f)), menger(low = 2.2f, high = 2.5f),
                StepSlot(listOf(RecipeOp.RotateXY), angle), menger(low = 2.2f, high = 2.5f),
                menger(optional = true, low = 2.2f, high = 2.5f), none, none, none)),
            RecipeTemplate("sculpture", cavern = false, listOf(RecipePrimitive.Box, RecipePrimitive.Sphere, RecipePrimitive.Cross), Span(1.8f, 2.3f), Span(4f, 5.5f), listOf(
                mirror(0.8f, 1.2f), plane(), StepSlot(listOf(RecipeOp.RotateXY), angle), scale(1.7f, 2f, Span(0.6f, 1f)),
                mirror(0.5f, 0.9f), StepSlot(listOf(RecipeOp.RotateYZ), angle), scale(1.7f, 2f, Span(0.4f, 0.8f)), menger(optional = true))),
        )
    }
}

/**
 * Chooses the recipe of every district from one seed.
 *
 * The next district mutates one to three steps of the current one. After three to five mutated
 * districts the composer jumps to the template shown least recently. A candidate the screen
 * refuses is drawn again; after [MOST_ATTEMPTS] refusals it is shown anyway and counted in
 * [forced].
 *
 * Only the last [KEPT] districts stay in memory, because the engine shows six at a time and a
 * mutation needs the one before it. Asking for an older district is a programming error.
 */
internal class RecipeComposer(seed: Long, private val budget: Float = RecipeScreen.STEP_BUDGET) {
    private val random = Rng(seed)
    private val recipes = ArrayList<Recipe>()
    // The position of recipes[0], so a district's place in the whole journey survives the trimming.
    private var base = 0
    private val lastShown = IntArray(RecipeTemplate.ALL.size) { -1 }
    private var untilJump = 0
    var forced: Int = 0
        private set
    var attempts: Int = 0
        private set

    /** The recipe of district [index]. Districts start at -1, the one behind the eye at launch. */
    fun recipeFor(index: Long, span: Float): Recipe {
        val position = (index + 1).toInt()
        require(position >= 0) { "district $index is before the first one" }
        require(position >= base) { "district $index is older than the last $KEPT the composer keeps" }
        while (base + recipes.size <= position) {
            recipes += next(base + recipes.size, span)
            if (recipes.size > KEPT) {
                recipes.removeAt(0)
                base++
            }
        }
        return recipes[position - base]
    }

    fun reset() {
        random.reset()
        recipes.clear()
        base = 0
        lastShown.fill(-1)
        untilJump = 0
        forced = 0
        attempts = 0
    }

    private fun next(position: Int, span: Float): Recipe {
        val current = recipes.lastOrNull()
        var jump = current == null || untilJump <= 0
        var tries = 0
        var candidate: Recipe
        while (true) {
            candidate = if (jump) {
                // One draw of the template, so a tie between two equally old templates is settled once.
                val template = leastRecent()
                RecipeTemplate.ALL[template].draw(template, random)
            } else {
                mutated(current!!)
            }
            attempts++
            tries++
            if (RecipeScreen.judge(candidate, span, budget).ok) break
            if (!jump && tries >= MOST_MUTATIONS) jump = true
            if (tries >= MOST_ATTEMPTS) { forced++; break }
        }
        untilJump = if (jump) 3 + (random.next() * 3f).toInt().coerceIn(0, 2) else untilJump - 1
        lastShown[candidate.template] = position
        return candidate
    }

    // Reservoir sampling over the oldest templates, so a tie between them is an even choice.
    private fun leastRecent(): Int {
        var best = 0
        var tied = 1
        for (template in 1 until lastShown.size) {
            if (lastShown[template] < lastShown[best]) {
                best = template
                tied = 1
            } else if (lastShown[template] == lastShown[best]) {
                tied++
                if (random.next() < 1f / tied) best = template
            }
        }
        return best
    }

    private fun mutated(current: Recipe): Recipe {
        val template = RecipeTemplate.ALL[current.template]
        val changeable = template.slots.indices.filter { template.slots[it].varies }.toMutableList()
        val count = (1 + (random.next() * 3f).toInt().coerceIn(0, 2)).coerceAtMost(changeable.size)
        var recipe = current
        // A partial Fisher-Yates over the varying slots: distinct slots, all from the one generator.
        for (taken in 0 until count) {
            val left = changeable.size - taken
            val pick = taken + (random.next() * left).toInt().coerceIn(0, left - 1)
            val slot = changeable[pick]
            changeable[pick] = changeable[taken]
            changeable[taken] = slot
            recipe = template.mutate(recipe, slot, random)
        }
        return recipe
    }

    private companion object {
        const val MOST_MUTATIONS = 12
        const val MOST_ATTEMPTS = 36
        // Six live districts, the one a mutation reads, and room to spare.
        const val KEPT = 12
    }
}
