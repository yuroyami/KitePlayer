package io.github.yuroyami.kiteplayer.audioviz.viz

import io.github.yuroyami.kiteplayer.audioviz.AudioVizAuthoringApi

/**
 * Holds the finished picture to a flash limit, whatever produced the light.
 *
 * WCAG 2.2 calls one flash a pair of opposing changes in relative luminance of at least 0.10,
 * where the darker of the two states is below 0.80. A red flash is the same pattern in saturated
 * red. This guard watches the light of the composed picture rather than the flashes a drawing asks
 * for, because feedback, a palette change, a transition and a shader can all flash without asking.
 *
 * Call [limit] once a frame with the light the picture is about to show. It answers the light the
 * picture may show. The caller scales the finished frame by the ratio of the two, so a held frame
 * is dimmer rather than dropped, and the picture keeps moving.
 *
 * The policy is the project's own and is stricter than WCAG's: at most [mostPerSecond] flashes in
 * any rolling second, no area exception, and no saturated red flashing at all.
 */
@AudioVizAuthoringApi
public class FlashGuard(
    /** How many flashes may land inside any one second. */
    mostPerSecond: Int = 3,
) {
    private val light = Channel(mostPerSecond, STEP, DARK)
    private val red = Channel(0, RED_STEP, 1f)
    private var seconds = 0f

    /** How many flashes are inside the last second of what was shown. Diagnostics. */
    public val recent: Int get() = light.recent(seconds)

    /** How many red flashes are inside the last second. Always 0 while the guard is in the path. */
    public val recentRed: Int get() = red.recent(seconds)

    /**
     * The light this frame may show, given [wanted], the light it is about to show.
     *
     * [redShare] is how much of the picture is a saturated red, 0 to 1, which may not flash at
     * all. [deltaSeconds] is the time since the last frame.
     */
    public fun limit(wanted: Float, redShare: Float, deltaSeconds: Float): Float {
        seconds += deltaSeconds.takeIf { it.isFinite() && it > 0f }?.coerceAtMost(0.5f) ?: 0f
        val allowed = light.allow(wanted.coerceIn(0f, 1f), seconds)
        // A red flash is never allowed, so the red channel can only hold the picture back further.
        // The whole frame is dimmed by how much of the red had to go, and never below [DARKEST]:
        // the red cannot be separated from the rest without reading the frame back, and a picture
        // that drops to black is its own kind of flash.
        val share = redShare.coerceIn(0f, 1f)
        val byRed = red.allow(share, seconds)
        val scale = if (share <= 0f) 1f else (byRed / share).coerceAtLeast(DARKEST)
        return if (scale >= 1f) allowed else allowed * scale
    }

    /**
     * How much of [wanted] the picture may give this frame, 0 to 1.
     *
     * The same decision as [limit], as a factor, for a caller that scales the light the drawings
     * are told to give rather than dimming a finished frame.
     */
    public fun allowance(wanted: Float, deltaSeconds: Float): Float {
        val asked = wanted.coerceIn(0f, 1f)
        if (asked <= 0f) return 1f
        return (limit(asked, 0f, deltaSeconds) / asked).coerceIn(0f, 1f)
    }

    /** Forgets the history. Call it when the picture is replaced rather than changed. */
    public fun reset() {
        seconds = 0f
        light.reset()
        red.reset()
    }

    /**
     * One measure watched for opposing swings.
     *
     * [step] is the swing that makes a leg, [dark] the state below which a leg counts, and [most]
     * how many completed pairs are allowed in a second. A channel with [most] of zero allows none.
     */
    private class Channel(private val most: Int, private val step: Float, private val dark: Float) {
        private var extreme = Float.NaN
        private var extremeAt = 0f
        private var rising = true
        private var halfway = false
        private val flashes = ArrayDeque<Float>()

        fun recent(now: Float): Int {
            forget(now)
            return flashes.size
        }

        fun reset() {
            extreme = Float.NaN
            halfway = false
            flashes.clear()
        }

        /** The value that may be shown, and the state updated to what will be shown. */
        fun allow(wanted: Float, now: Float): Float {
            forget(now)
            if (extreme.isNaN()) {
                extreme = wanted
                extremeAt = now
                return wanted
            }
            val swing = wanted - extreme
            val away = if (rising) swing >= 0f else swing <= 0f
            if (away) {
                extreme = wanted
                extremeAt = now
                return wanted
            }
            val back = if (rising) extreme - wanted else wanted - extreme
            if (back < step || minOf(wanted, extreme) >= dark) return wanted
            // A flash is a fast change. A picture that takes longer than this to cross the step is
            // fading, and holding a fade would stop the picture rather than protect anyone.
            if (now - extremeAt > QUICK) {
                halfway = false
                rising = !rising
                extreme = wanted
                extremeAt = now
                return wanted
            }
            // This swing completes a leg. Two legs are one flash, so only the second one counts.
            if (halfway && flashes.size >= most) {
                // Hold just inside the step. The picture still moves and the pair never qualifies.
                return if (rising) extreme - step * HOLD else extreme + step * HOLD
            }
            if (halfway) {
                flashes.addLast(now)
                halfway = false
            } else {
                halfway = true
            }
            rising = !rising
            extreme = wanted
            extremeAt = now
            return wanted
        }

        private fun forget(now: Float) {
            while (flashes.isNotEmpty() && now - flashes.first() > 1f) flashes.removeFirst()
        }
    }

    private companion object {
        /** The opposing change that makes a leg of a general flash. WCAG 2.2. */
        const val STEP = 0.10f

        /** The state below which a change of [STEP] counts. WCAG 2.2. */
        const val DARK = 0.80f

        /** How much of the picture in saturated red makes a leg of a red flash. *Judgement.* */
        const val RED_STEP = 0.06f

        /** How much of the step a held frame may still move. *Judgement.* */
        const val HOLD = 0.8f

        /** The dimmest a held red frame is drawn, as a share of the light it asked for. *Judgement.* */
        const val DARKEST = 0.4f

        /**
         * How quickly a change has to cross the step to be a flash rather than a fade, in seconds.
         *
         * Three flashes a second is six crossings a second, so a crossing that takes longer than
         * this is slower than anything the policy counts. *Judgement.*
         */
        const val QUICK = 0.3f
    }
}
