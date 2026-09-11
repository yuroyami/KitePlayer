package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.Survey.number
import kotlin.test.Test
import kotlin.test.assertTrue

/** Every pixel is doing something: the screen is lit nearly everywhere, it changes everywhere, and the light reaches the edges. */
class DensityTest {

    init { useSkiaGraphics() }

    @Test
    fun everyPixelIsDoingSomething() {
        val failures = ArrayList<String>()
        println("density under the drum loop | the pad: ink, alive, edge, spread")
        for (row in Survey.rows) {
            val d = row.drums
            val p = row.pad
            val problems = ArrayList<String>()
            if (d.ink < 0.85f) problems += "ink ${number(d.ink)}"
            if (d.alive < 0.97f) problems += "alive ${number(d.alive)}"
            if (d.edge < 0.45f) problems += "edge ${number(d.edge)}"
            if (d.spread < 0.45f) problems += "spread ${number(d.spread)}"
            if (p.ink < 0.6f) problems += "pad ink ${number(p.ink)}"
            if (p.alive < 0.85f) problems += "pad alive ${number(p.alive)}"
            if (p.edge < 0.4f) problems += "pad edge ${number(p.edge)}"
            if (p.spread < 0.4f) problems += "pad spread ${number(p.spread)}"
            println(
                "  ${row.name.padEnd(20)} ${number(d.ink)} ${number(d.alive)} ${number(d.edge)} ${number(d.spread)}  |" +
                    " ${number(p.ink)} ${number(p.alive)} ${number(p.edge)} ${number(p.spread)}" +
                    if (problems.isEmpty()) "" else "   << ${problems.joinToString()}",
            )
            if (problems.isNotEmpty()) failures += "${row.name}: ${problems.joinToString()}"
        }
        println("density: ${Survey.rows.size - failures.size} of ${Survey.rows.size} pass")
        if (Survey.strict) assertTrue(failures.isEmpty(), "these leave part of the screen idle:\n" + failures.joinToString("\n"))
    }
}
