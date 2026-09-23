package io.github.yuroyami.kiteplayer

import java.util.Locale
import kotlin.test.assertTrue

/**
 * A regression gate for one hot path: a slowdown of ten times fails, and noise never does.
 *
 * Runs [block] three times to warm it up, then times seven runs. The median goes to standard output
 * in one fixed line, which CI copies into the run summary. The test fails when the median is over
 * [boundMillis]. Set the bound at ten times a median measured on a developer machine, and write that
 * median beside it.
 *
 * The same harness is in the JVM tests of kiteplayer-ffmpeg. Change both together.
 */
internal fun hotPathGate(name: String, boundMillis: Double, block: () -> Unit): Double {
    repeat(HOT_PATH_WARMUPS) { block() }
    val runs = DoubleArray(HOT_PATH_RUNS) {
        val start = System.nanoTime()
        block()
        (System.nanoTime() - start) / 1_000_000.0
    }
    runs.sort()
    val median = runs[HOT_PATH_RUNS / 2]
    println("HOTPATH | $name | ${millis(median)} ms | ${millis(boundMillis)} ms |")
    assertTrue(
        median <= boundMillis,
        "$name: the median of $HOT_PATH_RUNS runs was ${millis(median)} ms, over its bound of ${millis(boundMillis)} ms",
    )
    return median
}

private fun millis(value: Double): String = String.format(Locale.ROOT, "%.2f", value)

private const val HOT_PATH_WARMUPS = 3
private const val HOT_PATH_RUNS = 7
