package io.github.yuroyami.kiteplayer.output

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Decides the frame rate a video Surface asks the display for, from the timestamps of its frames.
 *
 * One gap between two frames is not enough. Matroska rounds every timestamp to a whole millisecond,
 * so a 23.976 fps file has gaps of 41 and 42 ms, and one gap reads as 24.39 or 23.81 fps. A phone
 * asked for 24.39 picked a 48 Hz mode in which over a third of the frames stayed the wrong time; with
 * no request it picked 24 Hz and 98 percent were right (#137). So this waits for a steady run of
 * frames long enough to tell 23.976 from 24, and snaps the average to the standard rate it matches.
 *
 * The rate asked for is the rate frames reach the screen, so it follows the stream and the speed
 * (#598). A new stream, told by the frame's generation, is measured again from its first frame. The
 * content rate comes from the media timestamps, which carry no clock noise, and the speed from how
 * fast the presentation targets advance against them, rounded to a twentieth, so the half-percent
 * nudge that catches the picture up with the sound cannot change the request. Measuring goes on
 * after a request, and a later steady run that disagrees with it asks again: any other standard
 * rate, or a measured rate more than a percent away. Asking the same rate twice is never done.
 *
 * Not thread safe: one caller feeds it, and a new Surface gets a new instance.
 */
internal class SurfaceFrameRateRequest {
    private var runStartUs = NO_TIME
    private var runStartTargetUs = NO_TIME
    private var lastUs = NO_TIME
    private var lastTargetUs = NO_TIME
    private var gapsInRun = 0
    private var stream = NO_STREAM
    private var asked: Float? = null

    /**
     * Takes the next frame's media time [ptsUs], its presentation target [targetUs] on the engine's
     * monotonic clock, and its stream's [generation]. Returns a rate to request when one is due,
     * otherwise null.
     */
    fun offer(ptsUs: Long, targetUs: Long = ptsUs, generation: Long = 0L): Float? {
        if (generation != stream) {
            // Another stream, or a seek in this one: nothing measured before says anything now.
            stream = generation
            lastUs = NO_TIME
        }
        val last = lastUs
        val lastTarget = lastTargetUs
        lastUs = ptsUs
        lastTargetUs = targetUs
        if (last == NO_TIME) {
            startRun(ptsUs, targetUs)
            return null
        }
        val gapUs = ptsUs - last
        val targetGapUs = targetUs - lastTarget
        val meanGapUs = if (gapsInRun == 0) gapUs.toDouble() else (last - runStartUs).toDouble() / gapsInRun
        val meanTargetGapUs =
            if (gapsInRun == 0) targetGapUs.toDouble() else (lastTarget - runStartTargetUs).toDouble() / gapsInRun
        if (gapUs !in MIN_GAP_US..MAX_GAP_US || abs(gapUs - meanGapUs) > GAP_TOLERANCE_US ||
            targetGapUs !in 1..MAX_TARGET_GAP_US ||
            abs(targetGapUs - meanTargetGapUs) > maxOf(GAP_TOLERANCE_US.toDouble(), meanTargetGapUs * TARGET_GAP_TOLERANCE)
        ) {
            // A seek, a stall, a pause, a speed change or variable-rate content. Only a steady run
            // can be measured, or a run across a speed change asks for the speed between the two.
            startRun(ptsUs, targetUs)
            return null
        }
        gapsInRun++
        val spanUs = ptsUs - runStartUs
        if (spanUs < MIN_SPAN_US) return null
        val content = snap(gapsInRun * 1_000_000.0 / spanUs)
        val speed = (spanUs.toDouble() / (targetUs - runStartTargetUs) * SPEED_STEPS).roundToLong().toDouble() / SPEED_STEPS
        startRun(ptsUs, targetUs)
        if (speed <= 0.0) return null
        val rate = if (speed == 1.0) content else snap(content.toDouble() * speed)
        val before = asked
        if (before != null && sameRequest(rate, before)) return null
        asked = rate
        return rate
    }

    private fun startRun(ptsUs: Long, targetUs: Long) {
        runStartUs = ptsUs
        runStartTargetUs = targetUs
        gapsInRun = 0
    }

    /**
     * Two snapped standard rates are exact, so they are the same only when equal, and 29.97 after
     * 30 is asked for. A measured rate moves with the rounding of its timestamps, within a percent.
     */
    private fun sameRequest(rate: Float, before: Float): Boolean =
        if (isStandard(rate) && isStandard(before)) rate == before else abs(rate - before) <= before * REASK_TOLERANCE

    private fun isStandard(rate: Float): Boolean = STANDARD_RATES.any { it.toFloat() == rate }

    private fun snap(measured: Double): Float {
        val nearest = STANDARD_RATES.minBy { abs(it - measured) }
        return if (abs(nearest - measured) <= nearest * SNAP_TOLERANCE) nearest.toFloat() else measured.toFloat()
    }

    private companion object {
        const val NO_TIME = Long.MIN_VALUE
        const val NO_STREAM = Long.MIN_VALUE

        /**
         * A target gap longer than a second is a pause or a stall, not a frame rate: at a quarter
         * of the speed, 5 fps content still shows a frame every 800 ms.
         */
        const val MAX_TARGET_GAP_US = 1_000_000L

        /**
         * A target gap this far from the run's mean ends the run: a speed change of a tenth or
         * more does, and the engine's clock corrections, a few milliseconds now and then, do not.
         */
        const val TARGET_GAP_TOLERANCE = 0.1

        /** Speeds are measured in twentieths: 1.25x and 0.95x stay apart, a sync nudge does not. */
        const val SPEED_STEPS = 20.0

        /**
         * How far a measured rate that is not a standard one must move to be asked again. The
         * rounding of its timestamps moves it by well under a percent, and a new speed by at least
         * five.
         */
        const val REASK_TOLERANCE = 0.01

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
