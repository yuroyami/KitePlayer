package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.Survey.number
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The picture does not settle into one recipe. Two long exposures eight seconds apart must put their
 * brightest third in different places and differ overall, which moving in place cannot do.
 */
class ChangeTest {

    init { useSkiaGraphics() }

    @Test
    fun twoExposuresEightSecondsApartDiffer() {
        val failures = ArrayList<String>()
        println("change under the drum loop: exposure overlap, exposure difference")
        for (row in Survey.rows) {
            val d = row.drums
            val problems = ArrayList<String>()
            if (d.changeOverlap > 0.5f) problems += "overlap ${number(d.changeOverlap)}"
            if (d.changeDiff < 0.06f) problems += "difference ${number(d.changeDiff)}"
            println(
                "  ${row.name.padEnd(20)} ${number(d.changeOverlap)} ${number(d.changeDiff)}" +
                    if (problems.isEmpty()) "" else "   << ${problems.joinToString()}",
            )
            if (problems.isNotEmpty()) failures += "${row.name}: ${problems.joinToString()}"
        }
        println("change: ${Survey.rows.size - failures.size} of ${Survey.rows.size} pass")
        if (Survey.strict) assertTrue(failures.isEmpty(), "these keep one recipe:\n" + failures.joinToString("\n"))
    }
}
