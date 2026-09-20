package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.VizCatalog
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDriver
import io.github.yuroyami.kiteplayer.audioviz.viz.VizProperty
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test

/**
 * Prints what every drawing answers to every driver, and a first draft of its declaration.
 *
 * This is not a check. The declarations are written from this table and from the drawing's own
 * code; `DriverInjectionTest` is what holds them to it afterwards. Set AUDIOVIZ_MATRIX to run it.
 */
class DriverMatrixReport {

    init { useSkiaGraphics() }

    @Test
    fun printTheMatrix() {
        if (System.getenv("AUDIOVIZ_MATRIX") == null) return println("SKIP: set AUDIOVIZ_MATRIX to print it")
        val catalogue = VizCatalog.create()
        val drivers = VizDriver.entries
        val rows = RenderHarness.inParallel(catalogue.indices.toList()) { index ->
            val base = DriverProbe.run(index, null)
            catalogue[index].name to drivers.map { driver ->
                DriverProbe.compare(base, DriverProbe.run(index, driver))
            }
        }
        println("driver responses over the whole picture, in thousandths of full luma")
        for ((name, answers) in rows) {
            for ((driver, answer) in drivers.zip(answers)) {
                if (answer.before > 0f) println("  !! ${name}: $driver run differs before the change")
                if (answer.difference < DriverProbe.NOTICED) continue
                println(
                    "  ${name.padEnd(16)} ${driver.name.padEnd(11)}" +
                        " all=${thousandths(answer.difference).padStart(5)}" +
                        " size=${thousandths(answer.ink).padStart(6)}" +
                        " light=${thousandths(answer.brightness).padStart(6)}" +
                        " colour=${thousandths(answer.colour).padStart(5)}" +
                        " speed=${thousandths(answer.motion).padStart(6)}" +
                        " texture=${thousandths(answer.texture).padStart(6)}" +
                        " after=${if (answer.startStep < 0) -1 else answer.startStep - InjectedFrames.STEP_AT}",
                )
            }
        }
        println()
        println("a first draft of each declaration, to be checked against the drawing's own code")
        for ((name, answers) in rows) {
            println("    // $name")
            println("    override val mapping: VizMapping = VizMapping(")
            println("        drives = listOf(")
            for ((driver, answer) in drivers.zip(answers)) {
                if (answer.difference < DriverProbe.NOTICED) continue
                val property = suggest(driver, answer)
                val curve = when (driver) {
                    VizDriver.LowHit, VizDriver.BodyHit, VizDriver.HighHit, VizDriver.Onset -> ", VizCurve.Scaled"
                    VizDriver.Section, VizDriver.Drop, VizDriver.Breakdown -> ", VizCurve.Discrete"
                    else -> ""
                }
                println("            VizDrive(VizDriver.${driver.name}, VizProperty.${property.name}$curve),")
            }
            println("        ),")
            println("    )")
        }
    }

    /** The property whose measure moved most, as a starting point for the author. */
    private fun suggest(driver: VizDriver, answer: DriverProbe.Response): VizProperty {
        val ranked = listOf(
            VizProperty.Brightness to abs(answer.brightness) / 0.004f,
            VizProperty.Size to abs(answer.ink) / 0.004f,
            VizProperty.Speed to abs(answer.motion) / 0.004f,
            VizProperty.Colour to answer.colour / 0.004f,
            VizProperty.Texture to abs(answer.texture) / 0.02f,
        ).maxBy { it.second }
        if (ranked.second >= 1f) return ranked.first
        return when (driver) {
            VizDriver.LowHit, VizDriver.BodyHit, VizDriver.HighHit, VizDriver.Onset -> VizProperty.Spawn
            VizDriver.Section, VizDriver.Drop, VizDriver.Breakdown -> VizProperty.Cut
            else -> VizProperty.Shape
        }
    }

    private fun thousandths(value: Float): String = (value * 1000f).roundToInt().toString()
}
