package io.github.yuroyami.kiteplayer.internal

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Works out how long a frame lasts.
 *
 * The obvious method, subtracting one timestamp from the next, is wrong often enough to matter.
 * Matroska rounds every timestamp to a whole millisecond, and some muxers overshoot by another
 * millisecond then compensate in the opposite direction. A 23.976 fps file therefore reports
 * durations that alternate between 41 ms and 42 ms. Feed that into a presentation scheduler on a
 * 60 Hz display and the frame repeats alternate 2, 3, 2, 3, which is visible judder that looks
 * exactly like a renderer bug.
 *
 * So: measure, sanity check, and once enough measurements agree with the container's declared frame
 * rate, snap to that rate and stop wobbling.
 *
 * One instance per video stream. Reset it on a seek, because a measurement taken across a seek
 * boundary is meaningless.
 */
internal class FrameDurationEstimator(
    containerFrameRate: Double?,
    private val maxFrameDurationUs: Long,
) {
    /** The container's declared frame rate as a duration, when it declared a usable one. */
    private val declaredUs: Long? = containerFrameRate
        ?.takeIf { it.isFinite() && it > 0.0 && it < 1000.0 }
        ?.let { (1_000_000.0 / it).roundToLong() }

    private var agreeingSamples = 0
    private var snapped: Long? = null

    /** Measurements this far from the declared duration still count as agreeing with it. */
    private val toleranceUs = TIMESTAMP_UNROUNDING_TOLERANCE_US

    /**
     * @param measuredUs the next frame's timestamp minus this frame's, or null when there is no next
     *        frame yet, when the two frames are from different generations, or when either lacks a
     *        timestamp. All three are routine.
     * @param decoderReportedUs the duration the decoder attached to the frame, when it did.
     * @return the duration to schedule with. Never zero, never negative, never absurd.
     */
    fun estimate(measuredUs: Long?, decoderReportedUs: Long?): Long {
        val measured = measuredUs?.takeIf { it > 0 && it <= maxFrameDurationUs }

        if (measured != null && declaredUs != null) {
            if (abs(measured - declaredUs) <= toleranceUs) {
                if (agreeingSamples < SNAP_AFTER_SAMPLES) agreeingSamples++
                if (agreeingSamples >= SNAP_AFTER_SAMPLES) snapped = declaredUs
            } else {
                // A real rate change, or a discontinuity. Stop trusting the declared rate until the
                // measurements agree with it again.
                agreeingSamples = 0
                snapped = null
            }
        }

        snapped?.let { return it }
        measured?.let { return it }

        decoderReportedUs?.takeIf { it > 0 && it <= maxFrameDurationUs }?.let { return it }
        declaredUs?.let { return it }

        // Nothing usable anywhere. 25 fps is the conventional guess, and it only ever applies to a
        // stream that declares no rate and carries no timestamps, which is a broken file.
        return FALLBACK_DURATION_US
    }

    /** Called on a seek. A duration measured across the boundary would be nonsense. */
    fun reset() {
        agreeingSamples = 0
        snapped = null
    }

    internal companion object {
        /**
         * How far a measured duration may sit from the declared one and still be considered the
         * same. Covers Matroska's millisecond rounding plus a muxer's compensating overshoot.
         */
        const val TIMESTAMP_UNROUNDING_TOLERANCE_US: Long = 3_100

        /** How many agreeing measurements before the declared rate is trusted outright. */
        const val SNAP_AFTER_SAMPLES: Int = 16

        const val FALLBACK_DURATION_US: Long = 40_000
    }
}
