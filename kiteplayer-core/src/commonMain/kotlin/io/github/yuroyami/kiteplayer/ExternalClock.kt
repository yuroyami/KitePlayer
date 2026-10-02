package io.github.yuroyami.kiteplayer

import kotlin.time.Duration

/**
 * A clock the caller owns, which playback follows once it is set with
 * [KitePlayer.setExternalClock]. Synchronised playback across devices is the use: each player
 * follows the position that its group agreed on.
 *
 * While the player plays, it asks the clock about every 50 ms and compares the answer with the
 * position that is audible now:
 *
 * - A difference under 2 ms changes nothing.
 * - A difference up to 150 ms changes the speed slightly, by at most [ExternalClockPolicy.maxTrim],
 *   until the difference is gone. The pitch stays, because the change goes through the same stage
 *   as a speed change.
 * - A larger difference, such as a jump of the clock, is one precise seek to the clock's position.
 * - No answer, or an answer that has not moved since the question before, changes nothing. The
 *   player keeps playing on its own audio clock, and after 2 s of that it warns
 *   [PlaybackWarning.ExternalClockSilent] once. The warning is armed again when the answers move.
 *
 * Play and pause stay with the caller's commands. A clock that stops is never read as a pause.
 */
public fun interface ExternalClock {
    /**
     * The media position that should be audible at [atNanos], or null when this clock has no
     * answer now.
     *
     * [atNanos] is the instant of the question on the player's own clock, the clock that
     * [AudioClockSnapshot.hostTimeNanos] reads. The player calls this on its session thread, so it
     * must return at once and must not call back into the player.
     */
    public fun positionAt(atNanos: Long): Duration?
}

/** How playback follows an [ExternalClock]. */
public data class ExternalClockPolicy(
    /**
     * The largest speed change that closes a small difference, as a fraction of the speed:
     * 0.005 is 0.5 percent, about 9 cents of pitch if the pitch were not kept. At most 0.05.
     */
    val maxTrim: Double = 0.005,
) {
    init {
        require(maxTrim.isFinite() && maxTrim > 0.0 && maxTrim <= 0.05) {
            "maxTrim must be above 0 and at most 0.05, was $maxTrim"
        }
    }
}
