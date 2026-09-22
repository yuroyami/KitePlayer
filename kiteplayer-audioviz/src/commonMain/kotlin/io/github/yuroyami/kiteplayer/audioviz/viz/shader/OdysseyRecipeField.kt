package io.github.yuroyami.kiteplayer.audioviz.viz.shader

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** The Kotlin copy of the shader's recipe distance field. It screens recipes and tests the shader. */
internal object RecipeField {
    /**
     * The signed distance of point (x, y, z) from the recipe packed in [slot], in district-local
     * coordinates where the district starts at z = 0 and is [span] units long. Only the first
     * [activeSteps] folds run, the way `uShape.x` limits them in the shader.
     */
    fun distance(steps: FloatArray, tails: FloatArray, slot: Int, x: Float, y: Float, z: Float, span: Float, activeSteps: Int): Float {
        val tail = slot * 4
        val cell = tails[tail + 3]
        var qx = x; var qy = y; var qz = z
        if (tails[tail + 2] > 0.5f) {
            qx = mod(qx + cell, cell * 2f) - cell
            qy = mod(qy + cell, cell * 2f) - cell
            qz = mod(qz + cell, cell * 2f) - cell
        } else {
            qz -= span * 0.5f
        }
        qx /= cell; qy /= cell; qz /= cell
        var scale = 1f / cell
        val base = slot * Recipe.STEPS * 4
        for (i in 0 until min(activeSteps, Recipe.STEPS)) {
            val at = base + i * 4
            val a = steps[at + 1]; val b = steps[at + 2]; val c = steps[at + 3]
            when (steps[at].toInt()) {
                1 -> { qx = abs(qx) - a; qy = abs(qy) - b; qz = abs(qz) - c }
                2 -> { qx = qx.coerceIn(-a, a) * 2f - qx; qy = qy.coerceIn(-a, a) * 2f - qy; qz = qz.coerceIn(-a, a) * 2f - qz }
                3 -> {
                    val r2 = qx * qx + qy * qy + qz * qz
                    val f = if (r2 < a) b / a else if (r2 < b) b / r2 else 1f
                    qx *= f; qy *= f; qz *= f; scale *= f
                }
                4 -> {
                    val dot = qx * a + qy * b + qz * c
                    if (dot < 0f) { qx -= 2f * dot * a; qy -= 2f * dot * b; qz -= 2f * dot * c }
                }
                5 -> { qx = qx * a - b; qy = qy * a - c; qz = qz * a - 0.5f * (b + c); scale *= abs(a) }
                6 -> {
                    qx = abs(qx); qy = abs(qy); qz = abs(qz)
                    if (qx < qy) { val t = qx; qx = qy; qy = t }
                    if (qx < qz) { val t = qx; qx = qz; qz = t }
                    if (qy < qz) { val t = qy; qy = qz; qz = t }
                    qx = qx * a - (a - 1f); qy = qy * a - (a - 1f); qz = qz * a - (a - 1f)
                    if (qz < -0.5f * (a - 1f)) qz += a - 1f
                    scale *= a
                }
                7 -> { val nx = qx * a - qy * b; val ny = qx * b + qy * a; qx = nx; qy = ny }
                8 -> { val ny = qy * a - qz * b; val nz = qy * b + qz * a; qy = ny; qz = nz }
                else -> Unit
            }
        }
        val size = tails[tail + 1]
        val d = when (tails[tail].toInt()) {
            0 -> box(qx, qy, qz, size)
            1 -> sqrt(qx * qx + qy * qy + qz * qz) - size
            else -> { val ax = abs(qx); val ay = abs(qy); val az = abs(qz); min(max(ax, ay), min(max(ay, az), max(az, ax))) - size }
        }
        return d / scale
    }

    // GLSL mod: never negative for a positive divisor.
    private fun mod(a: Float, b: Float): Float = a - b * floor(a / b)

    private fun box(x: Float, y: Float, z: Float, half: Float): Float {
        val qx = abs(x) - half; val qy = abs(y) - half; val qz = abs(z) - half
        val ox = max(qx, 0f); val oy = max(qy, 0f); val oz = max(qz, 0f)
        return sqrt(ox * ox + oy * oy + oz * oz) + min(max(qx, max(qy, qz)), 0f)
    }
}

/**
 * Judges a recipe before it is shown, the way the eye will meet it: is there open space near the
 * route, is there anything to see, and can rays reach a surface within the step budget.
 */
internal object RecipeScreen {
    data class Verdict(
        val ok: Boolean,
        val reason: String,
        val openness: Float,
        val presence: Float,
        val steps: Float,
        val surface: Float,
    )

    /**
     * Mean march steps a ray may need before the recipe counts as too fine. The screening march
     * steps the way the shader steps, so the number counts the shader's own steps: it starts at
     * the default of the Ray steps control, and a phone measurement fixes it later. A budget at or
     * above [MOST_MARCH] turns the test off, because the march stops counting there.
     *
     * Do not compare this 88 with the 72 that stood here before: the march used to take a longer
     * step than the shader, so its count meant something else. The budget now sits at 79 percent
     * of [MOST_MARCH]. Tests pass their own budget to [judge] rather than changing this one.
     */
    const val STEP_BUDGET: Float = 88f

    fun judge(recipe: Recipe, span: Float, budget: Float = STEP_BUDGET): Verdict {
        val steps = FloatArray(Recipe.STEPS * 4)
        val tails = FloatArray(4)
        recipe.pack(steps, tails, 0)
        // The recipe detail control runs as few as Recipe.LEAST_STEPS folds, so the screen judges
        // both depths and the worse reading of each decides.
        var openness = 1f
        var presence = 1f
        for (activeSteps in intArrayOf(Recipe.LEAST_STEPS, Recipe.STEPS)) {
            var open = 0
            var present = 0
            var samples = 0
            for (i in 0 until ALONG) {
                val z = span * (i + 0.5f) / ALONG
                for (direction in 0 until 4) {
                    val x = when (direction) { 0 -> NEAR; 1 -> -NEAR; else -> 0f }
                    val y = when (direction) { 2 -> NEAR; 3 -> -NEAR; else -> 0f }
                    if (RecipeField.distance(steps, tails, 0, x, y, z, span, activeSteps) > 0f) open++
                    val far = FAR / NEAR
                    if (RecipeField.distance(steps, tails, 0, x * far, y * far, z, span, activeSteps) < REACH) present++
                    samples++
                }
            }
            openness = min(openness, open.toFloat() / samples)
            presence = min(presence, present.toFloat() / samples)
        }
        // The shader breathes the recipe with the music, so the cost is the worse of resting and
        // full music, at both fold depths the recipe detail control can run.
        val loudSteps = FloatArray(Recipe.STEPS * 4)
        val loudTails = FloatArray(4)
        recipe.pack(loudSteps, loudTails, 0, bass = 1f, hit = 1f, mid = 1f)
        var marchSteps = 0f
        var surface = 1f
        for (activeSteps in intArrayOf(Recipe.LEAST_STEPS, Recipe.STEPS)) {
            for (fan in listOf(march(steps, tails, recipe.safety, span, activeSteps),
                march(loudSteps, loudTails, recipe.safety, span, activeSteps))) {
                marchSteps = max(marchSteps, fan.steps)
                surface = min(surface, fan.surface)
            }
        }
        // A ray fan that reaches nothing has no world to show, however open and near the samples
        // read. The nearby samples can all sit in open space beside a field that is empty out to
        // the draw distance, which is how a recipe of sky and one doorway used to pass.
        val wantedSurface = if (recipe.cavern) CAVERN_SURFACE else SCULPTURE_SURFACE
        val reason = when {
            recipe.cavern && openness < 0.15f -> "solid"
            recipe.cavern && presence < 0.3f -> "empty"
            !recipe.cavern && presence < 0.2f -> "empty"
            surface < wantedSurface -> "empty"
            marchSteps > budget -> "too fine"
            else -> ""
        }
        return Verdict(reason.isEmpty(), reason, openness, presence, marchSteps, surface)
    }

    /** What the ray fan found: the mean step count, and the share of rays that reached a surface. */
    private class Fan(val steps: Float, val surface: Float)

    // Twelve rays from the route, marched the way the shader marches, give a cost the GPU will echo.
    private fun march(steps: FloatArray, tails: FloatArray, safety: Float, span: Float, activeSteps: Int): Fan {
        var total = 0
        var found = 0
        val trust = safety / STEP_DIVISOR
        for (ray in 0 until RAYS) {
            val angle = ray * 6.2831855f / RAYS
            val dx = cos(angle) * RAY_SPREAD
            val dy = sin(angle) * RAY_SPREAD
            val dz = RAY_FORWARD
            val startZ = span * 0.3f
            var t = 0f
            var count = 0
            while (count < MOST_MARCH && t < MARCH_REACH && startZ + dz * t < span) {
                val x = dx * t; val y = dy * t; val z = startZ + dz * t
                val body = RecipeField.distance(steps, tails, 0, x, y, z, span, activeSteps)
                val distance = max(body, TUBE - sqrt(x * x + y * y))
                val epsilon = max(EPSILON_NEAR, t * EPSILON_SLOPE)
                if (distance < epsilon) { found++; break }
                t += max(distance - epsilon, 0.001f) * trust
                count++
            }
            // A ray that runs out of steps still has something in front of it. It counts as found,
            // so the budget reports the recipe as too fine rather than the surface test as empty.
            if (count == MOST_MARCH) found++
            total += count
        }
        return Fan(total.toFloat() / RAYS, found.toFloat() / RAYS)
    }

    private const val ALONG = 40
    private const val NEAR = 1.6f
    private const val FAR = 6f
    private const val REACH = 8f
    private const val RAYS = 12
    /**
     * The share of the ray fan that must reach a surface for a cavern. A cavern stands around the
     * eye, so most of its rays land.
     */
    internal const val CAVERN_SURFACE: Float = 0.5f
    // A sculpture stands alone in open sky, so a quarter of the fan is enough.
    private const val SCULPTURE_SURFACE = 0.25f
    // The shader divides its own step by the route bend and the impact-front slope (safetyOf in
    // OdysseyScene). The screen assumes the widest bend the Path curves control offers, because a
    // recipe is screened once and the viewer can widen the bend at any time afterwards.
    private const val ROUTE_BEND = 1f
    // An impact front lasts about a second, so the screen reads the cost of a resting world.
    private const val WAVE_SLOPE = 0f
    private const val STEP_DIVISOR = (1f + 0.5f * ROUTE_BEND) * (1f + WAVE_SLOPE)
    // The radius of the tunnel the shader carves down the route, which no fold may close.
    private const val TUBE = 1.15f
    // How far one screening ray travels, in district units: the Draw distance control's default.
    // The control runs to 100, so a recipe screened here can need more steps at the far end.
    private const val MARCH_REACH = 72f
    // The march loop's own ceiling, so a step budget at or above it can never refuse a recipe.
    private const val MOST_MARCH = 112
    // The ray fan, sideways against forward, as the shader's view rays spread from the eye.
    private const val RAY_SPREAD = 0.6f
    private const val RAY_FORWARD = 0.8f
    // The shader's hit epsilon: this much at the eye, growing with distance. The shader's slope is
    // 2 over the quarter-resolution height times the focal term (uRoute.z), about 0.0024 on a
    // 2400 pixel phone; 0.002 is stricter than any screen, so the screen never undercounts steps.
    private const val EPSILON_NEAR = 0.0015f
    private const val EPSILON_SLOPE = 0.002f
}
