package io.github.yuroyami.kiteplayer.output

import kotlin.math.abs

/**
 * Decides the frame rate a video Surface asks the display for, from the timestamps of its frames.
 *
 * One gap between two frames is not enough. Matroska rounds every timestamp to a whole millisecond,
 * so a 23.976 fps file has gaps of 41 and 42 ms, and one gap reads as 24.39 or 23.81 fps. A phone
 * asked for 24.39 picked a 48 Hz mode in which over a third of the frames stayed the wrong time; with
 * no request it picked 24 Hz and 98 percent were right (#137). So this waits for a steady run of
 * frames long enough to tell 23.976 from 24, and snaps the average to the standard rate it matches.
 *
 * Not thread safe: one caller feeds it, and a new Surface gets a new instance.
 */
internal class SurfaceFrameRateRequest {
    private var runStartUs = NO_TIME
    private var lastUs = NO_TIME
    private var gapsInRun = 0
    private var decided = false

    /** Takes the next frame's timestamp. Returns the rate to request exactly once, otherwise null. */
    fun offer(ptsUs: Long): Float? {
        if (decided) return null
        val last = lastUs
        lastUs = ptsUs
        if (last == NO_TIME) {
            startRun(ptsUs)
            return null
        }
        val gapUs = ptsUs - last
        val meanGapUs = if (gapsInRun == 0) gapUs.toDouble() else (last - runStartUs).toDouble() / gapsInRun
        if (gapUs !in MIN_GAP_US..MAX_GAP_US || abs(gapUs - meanGapUs) > GAP_TOLERANCE_US) {
            // A seek, a stall or variable-rate content. Only a steady run can be measured.
            startRun(ptsUs)
            return null
        }
        gapsInRun++
        val spanUs = ptsUs - runStartUs
        if (spanUs < MIN_SPAN_US) return null
        decided = true
        return snap(gapsInRun * 1_000_000.0 / spanUs)
    }

    private fun startRun(ptsUs: Long) {
        runStartUs = ptsUs
        gapsInRun = 0
    }

    private fun snap(measured: Double): Float {
        val nearest = STANDARD_RATES.minBy { abs(it - measured) }
        return if (abs(nearest - measured) <= nearest * SNAP_TOLERANCE) nearest.toFloat() else measured.toFloat()
    }

    private companion object {
        const val NO_TIME = Long.MIN_VALUE

        /** 250 fps down to 5 fps. Anything outside is a timestamp problem, not a frame rate. */
        const val MIN_GAP_US = 4_000L
        const val MAX_GAP_US = 200_000L

        /** Covers millisecond rounding (up to 1 ms off the mean) and nothing looser. */
        const val GAP_TOLERANCE_US = 1_500L

        /**
         * Rounding can move the measured span by at most 1 ms. Over 3 s that is 0.033 percent, below
         * half the 0.1 percent between 23.976 and 24, so the nearest standard rate is the right one.
         */
        const val MIN_SPAN_US = 3_000_000L

        const val SNAP_TOLERANCE = 0.0005

        val STANDARD_RATES = doubleArrayOf(
            12.0, 15.0,
            24_000.0 / 1_001.0, 24.0, 25.0, 30_000.0 / 1_001.0, 30.0,
            48_000.0 / 1_001.0, 48.0, 50.0, 60_000.0 / 1_001.0, 60.0,
            90.0, 100.0, 120_000.0 / 1_001.0, 120.0,
        )
    }
}
