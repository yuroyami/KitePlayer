package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.VizCatalog
import io.github.yuroyami.kiteplayer.audioviz.viz.VizCurve
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDriver
import io.github.yuroyami.kiteplayer.audioviz.viz.VizNeed
import io.github.yuroyami.kiteplayer.audioviz.viz.VizProperty
import io.github.yuroyami.kiteplayer.audioviz.viz.VizQualityControl
import io.github.yuroyami.kiteplayer.audioviz.viz.Visualization
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.ShaderPreset
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Holds every drawing to what it declares: each declared driver must move its part of the picture,
 * nothing that moves the picture strongly may be left out, and a hit must answer its strength.
 *
 * The renders are deterministic and differ in one driver only, so a difference between them is
 * that driver's doing. This does not establish that a viewer sees the music cause the picture; the
 * listening and viewing comparison is separate.
 */
class DriverInjectionTest {

    init { useSkiaGraphics() }

    private val hitDrivers = setOf(VizDriver.LowHit, VizDriver.BodyHit, VizDriver.HighHit, VizDriver.Onset)

    @Test
    fun everyBuiltInDeclaresItsMapping() {
        val failures = ArrayList<String>()
        for (drawing in VizCatalog.create()) {
            val mapping = drawing.mapping
            if (mapping == null) {
                failures += "${drawing.name}: no mapping"
                continue
            }
            if (mapping.drives.isEmpty()) failures += "${drawing.name}: no drives"
            failures += needProblems(drawing, mapping.needs)
            val echo = drawing.trail > 0f || drawing.moodSpec != null
            if (echo && !mapping.quality.contains(VizQualityControl.EchoResolution)) {
                failures += "${drawing.name}: feeds frames back but declares no echo resolution to give up"
            }
        }
        assertTrue(failures.isEmpty(), "declarations that do not match the drawing:\n" + failures.joinToString("\n"))
    }

    private fun needProblems(drawing: Visualization, needs: Set<VizNeed>): List<String> {
        val problems = ArrayList<String>()
        val shader = drawing is ShaderPreset
        if (shader != needs.contains(VizNeed.RuntimeShader)) {
            problems += "${drawing.name}: ${if (shader) "is a shader" else "is not a shader"}, declares $needs"
        }
        val layers = drawing.warp != null || drawing.ground != null || drawing.detail != null
        if (layers && !needs.contains(VizNeed.ShaderLayers)) {
            problems += "${drawing.name}: has a ground, detail or warp but does not declare ShaderLayers"
        }
        val echo = drawing.trail > 0f || drawing.moodSpec != null
        if (echo != needs.contains(VizNeed.EchoBuffer)) {
            problems += "${drawing.name}: ${if (echo) "keeps a trail" else "keeps no trail"}, declares $needs"
        }
        if ((drawing.bloom > 0) != needs.contains(VizNeed.SoftBuffer)) {
            problems += "${drawing.name}: bloom is ${drawing.bloom}, declares $needs"
        }
        return problems
    }

    @Test
    fun everyDeclaredDriveMovesItsPropertyAndNothingStrongIsHidden() {
        val catalogue = VizCatalog.create()
        val rows = RenderHarness.inParallel(catalogue.indices.toList()) { index ->
            val drawing = catalogue[index]
            val mapping = drawing.mapping ?: return@inParallel emptyList<String>()
            val base = DriverProbe.run(index, null)
            val answers = HashMap<VizDriver, DriverProbe.Response>()
            for (driver in VizDriver.entries) {
                answers[driver] = DriverProbe.compare(base, DriverProbe.run(index, driver))
            }
            val problems = ArrayList<String>()
            for ((driver, answer) in answers) {
                if (answer.before > 0f) {
                    problems += "${drawing.name}: the two renders differ by ${thousandths(answer.before)}" +
                        " before $driver moves, so this render is not repeatable"
                }
            }
            for (drive in mapping.drives) {
                val answer = answers.getValue(drive.driver)
                val moved = abs(answer.of(drive.property))
                val floor = floorFor(drive.property)
                if (moved < floor) {
                    problems += "${drawing.name}: ${drive.driver} moves ${drive.property} by only" +
                        " ${thousandths(moved)}, declared ${drive.curve}"
                }
                if (drive.driver in hitDrivers || drive.driver in STRUCTURE) {
                    val start = answer.startStep
                    val landed = if (drive.driver in hitDrivers) InjectedFrames.HIT_STEPS.first()
                        else InjectedFrames.STRUCTURE_STEP
                    // Two frames, not one: a drawing that spawns into its feedback buffer shows
                    // the spawn in the frame after the one that drew it.
                    val allowed = landed + 2 + (drive.response.delaySeconds * 60f).roundToInt()
                    if (start < 0 || start > allowed) {
                        problems += "${drawing.name}: ${drive.driver} answered at step $start, not by $allowed"
                    }
                }
            }
            val declared = mapping.drivers
            val strength = mapping.drives.map { abs(answers.getValue(it.driver).difference) }.sorted()
            val typical = if (strength.isEmpty()) 0f else strength[strength.size / 2]
            for (driver in VizDriver.entries) {
                if (driver in declared) continue
                val moved = answers.getValue(driver).difference
                if (moved >= maxOf(HIDDEN, typical)) {
                    problems += "${drawing.name}: $driver moves the picture by ${thousandths(moved)} and is not declared"
                }
            }
            problems
        }
        val failures = rows.flatten()
        assertTrue(failures.isEmpty(), "declarations the pictures do not keep:\n" + failures.joinToString("\n"))
    }

    @Test
    fun aHitIsGatedByItsConfidenceAndScaledByItsStrength() {
        val catalogue = VizCatalog.create()
        val rows = RenderHarness.inParallel(catalogue.indices.toList()) { index ->
            val drawing = catalogue[index]
            val mapping = drawing.mapping ?: return@inParallel emptyList<String>()
            val driver = mapping.drives.firstOrNull { it.driver in hitDrivers && it.curve == VizCurve.Scaled }
                ?.driver ?: return@inParallel emptyList<String>()
            val base = DriverProbe.run(index, null)
            val hard = DriverProbe.compare(base, DriverProbe.run(index, driver, strength = 0.9f))
            val soft = DriverProbe.compare(base, DriverProbe.run(index, driver, strength = 0.25f))
            val unsupported = DriverProbe.compare(base, DriverProbe.run(index, driver, confidence = 0.1f))
            val problems = ArrayList<String>()
            if (hard.difference <= soft.difference) {
                problems += "${drawing.name}: $driver answers a hard hit ${thousandths(hard.difference)}" +
                    " and a soft one ${thousandths(soft.difference)}"
            }
            if (unsupported.difference > DriverProbe.NOTICED) {
                problems += "${drawing.name}: $driver answered a detection the detector does not support," +
                    " by ${thousandths(unsupported.difference)}"
            }
            problems
        }
        val failures = rows.flatten()
        assertTrue(failures.isEmpty(), "hits that ignore strength or support:\n" + failures.joinToString("\n"))
    }

    private fun floorFor(property: VizProperty): Float = when (property) {
        VizProperty.Shape, VizProperty.Spawn, VizProperty.Camera, VizProperty.Cut -> MOVED
        VizProperty.Speed -> SPEED
        else -> SIGNED
    }

    private fun thousandths(value: Float): String = (value * 1000f).roundToInt().toString()

    private companion object {
        val STRUCTURE = setOf(VizDriver.Section, VizDriver.Drop, VizDriver.Breakdown)

        /** A whole-picture response this big is real. Shares of full luma. *Judgement.* */
        const val MOVED = 0.004f

        /** A response in one measure, such as brightness or size, that far from the baseline. */
        const val SIGNED = 0.002f

        /**
         * The same for speed, which is measured as a change in how much the picture moves.
         *
         * That is a difference of differences, so it reads smaller than the others for the same
         * visible change. The two runs are identical before the step, so anything above zero here
         * is the driver's doing rather than noise.
         */
        const val SPEED = 0.001f

        /** An undeclared driver moving the picture this much, or as much as the declared ones, fails. */
        const val HIDDEN = 0.01f
    }
}
