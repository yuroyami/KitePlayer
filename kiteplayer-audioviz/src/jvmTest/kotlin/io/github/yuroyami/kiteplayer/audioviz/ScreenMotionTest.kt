package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.Survey.number
import io.github.yuroyami.kiteplayer.audioviz.viz.VizEnergy
import kotlin.test.Test
import kotlin.test.assertTrue

/** Things cross the screen: much of it changes every 50 ms, blocks travel, and the actors go from edge to edge. */
class ScreenMotionTest {

    init { useSkiaGraphics() }

    @Test
    fun thingsCrossTheScreen() {
        val failures = ArrayList<String>()
        println("screen motion under the drum loop | the pad: busy, flow, flow p90, travel, crossing")
        for (row in Survey.rows) {
            val d = row.drums
            val p = row.pad
            val calm = row.bucket == VizEnergy.Calm
            // A drawing only the director reaches for stays at one end of the scale: a calm one is
            // judged under the pad and a lively one under the drum loop. The mood test still makes
            // both answer the music.
            val own = row.name in DIRECTOR_ONLY
            val judgeDrums = !(own && calm)
            val judgePad = !(own && !calm)
            val problems = ArrayList<String>()
            if (judgeDrums && d.busy < if (calm) 0.35f else 0.5f) problems += "busy ${number(d.busy)}"
            if (judgeDrums && d.flow < if (calm) 0.3f else 0.5f) problems += "flow ${number(d.flow)}"
            if (judgeDrums && d.flowP90 < if (calm) 0.6f else 1f) problems += "flow p90 ${number(d.flowP90)}"
            if (judgeDrums && d.travel < if (calm) 0.2f else 0.5f) problems += "travel ${number(d.travel)}"
            val crossing = if (calm) d.crossingSlow else d.crossing
            if (judgeDrums && crossing < 0.75f) problems += "crossing ${number(crossing)}"
            if (judgePad && p.busy < if (calm) 0.06f else 0.1f) problems += "pad busy ${number(p.busy)}"
            if (judgePad && p.flow < 0.05f) problems += "pad flow ${number(p.flow)}"
            if (judgePad && p.travel < 0.1f) problems += "pad travel ${number(p.travel)}"
            if (judgePad && p.crossing < 0.5f) problems += "pad crossing ${number(p.crossing)}"
            println(
                "  ${row.name.padEnd(20)} ${number(d.busy)} ${number(d.flow)} ${number(d.flowP90)} ${number(d.travel)} ${number(crossing)}  |" +
                    " ${number(p.busy)} ${number(p.flow)} ${number(p.travel)} ${number(p.crossing)}  ${number(d.millis)} ms" +
                    if (problems.isEmpty()) "" else "   << ${problems.joinToString()}",
            )
            if (problems.isNotEmpty()) failures += "${row.name}: ${problems.joinToString()}"
        }
        println("screen motion: ${Survey.rows.size - failures.size} of ${Survey.rows.size} pass")
        if (Survey.strict) assertTrue(failures.isEmpty(), "these do not move enough:\n" + failures.joinToString("\n"))
    }
}

/** The eight drawings only the director reaches for: four for loud music, four for quiet music. */
private val DIRECTOR_ONLY = setOf("Piston", "Riot", "Blackout", "Shatter", "Breath", "Tide", "Lantern", "Drift")
