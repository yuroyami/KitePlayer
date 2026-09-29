package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.presets.History
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.HistoryClock
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.ShaderLibrary
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A history of the spectrum covers the same seconds on any screen and stands still under a pause. */
class HistoryClockTest {

    @Test
    fun sixtyRowsForEachHeardSecondAtAnyRefreshRate() {
        for (hertz in listOf(24, 30, 45, 60, 90, 120, 144, 185, 240)) {
            val clock = HistoryClock()
            clock.rows(1f / hertz)
            var rows = 0
            repeat(hertz * 10) { rows += clock.rows(1f / hertz) }
            assertTrue(rows in 598..602, "$hertz Hz wrote $rows rows in ten heard seconds, not 600")
        }
    }

    @Test
    fun aPauseOrASilenceWritesNothing() {
        val clock = HistoryClock()
        assertEquals(1, clock.rows(0f), "the first step fills the history")
        repeat(600) { assertEquals(0, clock.rows(0f)) }
        assertEquals(0, clock.rows(Float.NaN))
    }

    @Test
    fun aStalledStepWritesNoMoreThanTheHistoryHolds() {
        val clock = HistoryClock()
        clock.rows(0f)
        assertEquals(ShaderLibrary.HISTORY, clock.rows(100f))
    }

    @Test
    fun aResetStartsAgain() {
        val clock = HistoryClock()
        clock.rows(0.5f)
        clock.reset()
        assertEquals(1, clock.rows(0f), "the first step after a reset fills the history")
    }

    @Test
    fun aRowOfTheBarsHistoryReachesBackTheSameWhateverTheRefreshRate() {
        for (hertz in listOf(30, 60, 120, 144, 185)) {
            val history = History()
            var heard = 0.0
            repeat(hertz * 6) {
                heard += 1.0 / hertz
                history.push(floatArrayOf(heard.toFloat()), heard)
            }
            val at = history.sample(history.row(2.4f), 0f)
            assertTrue(abs(heard - at - 2.4) < 0.06, "$hertz Hz: asked for 2.4 s back and got ${heard - at}")
        }
    }

    @Test
    fun theBarsHistoryStandsStillWhileNoTimeIsHeard() {
        val history = History()
        var heard = 0.0
        repeat(120) {
            heard += 1.0 / 60
            history.push(floatArrayOf(heard.toFloat()), heard)
        }
        val before = history.sample(history.row(1f), 0f)
        repeat(600) { history.push(floatArrayOf(-1f), heard) }
        assertEquals(before, history.sample(history.row(1f), 0f), "rows written under a pause moved the past")
        assertEquals(heard.toFloat(), history.sample(history.row(0f), 0f), "the newest row was replaced")
    }
}
