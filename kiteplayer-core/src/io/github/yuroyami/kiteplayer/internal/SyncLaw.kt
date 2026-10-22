package io.github.yuroyami.kiteplayer.internal

import io.github.yuroyami.kiteplayer.Pts

/**
 * The rule that decides how long the frame currently on screen should stay there.
 *
 * This is the whole of audio/video synchronisation for the video path, and it is a pure function, so
 * every case in it is covered by a table-driven test rather than by watching a video and squinting.
 *
 * The thresholds are the ones ffplay and mpv converged on over a decade of bug reports against real
 * files. They are not arbitrary and they are not tuned here.
 */
internal object SyncLaw {

    /**
     * Below this, a correction is smaller than one frame at 24 fps and only makes the picture
     * oscillate between early and late.
     */
    const val SYNC_THRESHOLD_MIN_US: Long = 40_000

    /** Above this, the picture visibly lags the sound and must be corrected. */
    const val SYNC_THRESHOLD_MAX_US: Long = 100_000

    /**
     * For frames longer than this, waiting out the difference is better than showing the frame
     * twice, because a doubled long frame is a visible freeze.
     */
    const val FRAME_DUP_THRESHOLD_US: Long = 100_000

    /**
     * How far the presentation schedule may fall behind wall time before it is abandoned and
     * re-anchored to now.
     *
     * Without this, any stall (a slow seek, a suspended process, a filter graph rebuild) leaves the
     * schedule far in the past, and the player then presents a burst of frames back to back trying
     * to catch up. The viewer sees a fast-forward glitch.
     */
    const val FRAME_TIMER_RESYNC_US: Long = 100_000

    /** Sanity ceiling on a frame duration for a container that declares no discontinuities. */
    const val MAX_FRAME_DURATION_NORMAL_US: Long = 3_600_000_000

    /** Sanity ceiling for a container where timestamps may jump, MPEG-TS above all. */
    const val MAX_FRAME_DURATION_DISCONTINUOUS_US: Long = 10_000_000

    /**
     * How long the frame on screen should remain there.
     *
     * @param nominalDelayUs the frame's own duration, from [FrameDurationEstimator].
     * @param videoClock the video clock, or null when it has no valid reading.
     * @param masterClock the master clock, or null when it has no valid reading. When the master
     *        *is* the video clock, pass null for both and no correction is applied.
     * @param maxFrameDurationUs the container's sanity ceiling, and the thing that gates whether a
     *        correction happens at all. Pass the container's own ceiling: substituting the 10 second
     *        discontinuous one would disable correction on every ordinary file.
     */
    fun targetDelayUs(
        nominalDelayUs: Long,
        videoClock: Pts?,
        masterClock: Pts?,
        maxFrameDurationUs: Long = MAX_FRAME_DURATION_NORMAL_US,
    ): Long {
        if (videoClock == null || masterClock == null) return nominalDelayUs

        val diff = videoClock.micros - masterClock.micros
        if (diff <= -maxFrameDurationUs || diff >= maxFrameDurationUs) return nominalDelayUs

        val syncThreshold = nominalDelayUs.coerceIn(SYNC_THRESHOLD_MIN_US, SYNC_THRESHOLD_MAX_US)

        return when {
            // Video is behind the master: shorten the current frame, possibly to nothing, so the
            // next frame arrives sooner.
            diff <= -syncThreshold -> (nominalDelayUs + diff).coerceAtLeast(0)

            // Video is ahead on a long frame: extend it by exactly the difference. Repeating a
            // frame this long would be a visible freeze.
            diff >= syncThreshold && nominalDelayUs > FRAME_DUP_THRESHOLD_US -> nominalDelayUs + diff

            // Video is ahead on a short frame: show it for two periods. One repeat, never more,
            // because the next pass will re-measure and decide again.
            diff >= syncThreshold -> nominalDelayUs * 2

            else -> nominalDelayUs
        }
    }

    /** What [targetDelayUs] decided, for statistics and tests. */
    fun classify(nominalDelayUs: Long, correctedDelayUs: Long): SyncAction = when {
        correctedDelayUs == nominalDelayUs -> SyncAction.OnTime
        correctedDelayUs >= nominalDelayUs * 2 -> SyncAction.Repeated
        correctedDelayUs > nominalDelayUs -> SyncAction.Extended
        correctedDelayUs == 0L -> SyncAction.Immediate
        else -> SyncAction.Shortened
    }
}

internal enum class SyncAction { OnTime, Shortened, Immediate, Extended, Repeated }
