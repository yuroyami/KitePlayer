package io.github.yuroyami.kiteplayer.audioviz.viz.shader

/**
 * How many rows of a spectrum history to write for one display step.
 *
 * The history holds [ShaderLibrary.HISTORY] rows, and a lookup by age spans about four seconds, which
 * is 60 rows a second. A row for every display step would shorten that span on a faster screen and
 * would go on writing while the player is paused. This gives 60 rows for each heard second, so the
 * span is the same on any screen and a pause or a silence writes nothing.
 */
internal class HistoryClock {
    private var owed = 0.0
    private var started = false

    /**
     * The rows to write for a step of [heardSeconds] of audible time. The first step always gets one,
     * which fills the whole history with the present.
     */
    fun rows(heardSeconds: Float): Int {
        var rows = 0
        if (!started) {
            started = true
            rows = 1
        }
        if (heardSeconds.isFinite() && heardSeconds > 0f) owed += heardSeconds
        val due = (owed * ROWS_PER_SECOND).toInt()
        if (due > 0) {
            owed -= due / ROWS_PER_SECOND
            rows += due
        }
        return rows.coerceAtMost(ShaderLibrary.HISTORY)
    }

    fun reset() {
        owed = 0.0
        started = false
    }

    companion object {
        /** The rate the four second span of the history assumes. */
        const val ROWS_PER_SECOND = 60.0
    }
}
