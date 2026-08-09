package io.github.yuroyami.kiteplayer.internal

import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.SeekMode
import kotlin.time.Duration

/**
 * A pending seek.
 *
 * Immutable, with a pure [merge], so coalescing is a unit test rather than a race. mpv mutates its
 * pending seek in place and its merge rules live inside the mutation; expressing them as a function
 * on a value makes every rule checkable in isolation.
 */
internal data class SeekRequest(
    val target: SeekTarget,
    val mode: SeekMode,
) {
    /**
     * Folds [next] into this pending request.
     *
     * The rules, and each one has a reason:
     *
     * - Two relative offsets add up, because holding an arrow key must move by the total, not by
     *   the last press.
     * - An absolute target absorbs any pending relative offsets, because dragging a seek bar
     *   supersedes whatever key presses came before it.
     * - A frame step or a factor overwrites, because both are exact requests about a specific place.
     * - The stricter of the two modes wins, so a precise request is never silently downgraded to a
     *   keyframe seek by a later coarse one.
     */
    fun merge(next: SeekRequest): SeekRequest {
        val mergedTarget = when {
            next.target is SeekTarget.Relative && target is SeekTarget.Relative ->
                SeekTarget.Relative(target.offset + next.target.offset)
            else -> next.target
        }
        return SeekRequest(mergedTarget, strictest(mode, next.mode))
    }

    /** Resolves this request against the current position and duration. */
    fun resolve(position: Pts, duration: Pts?): Pts {
        val raw = when (target) {
            is SeekTarget.Absolute -> target.position
            is SeekTarget.Relative -> Pts(position.micros + target.offset.inWholeMicroseconds)
            is SeekTarget.Factor -> {
                val total = duration?.micros ?: return position
                Pts((total * target.fraction).toLong())
            }
        }
        val clampedLow = if (raw.micros < 0) Pts.Zero else raw
        val total = duration ?: return clampedLow
        return if (clampedLow > total) total else clampedLow
    }

    private companion object {
        /** Precise is strictest, then the refining mode, then plain keyframe. */
        fun strictest(a: SeekMode, b: SeekMode): SeekMode = when {
            a == SeekMode.Precise || b == SeekMode.Precise -> SeekMode.Precise
            a == SeekMode.KeyframeThenRefine || b == SeekMode.KeyframeThenRefine -> SeekMode.KeyframeThenRefine
            else -> SeekMode.Keyframe
        }
    }
}

internal sealed interface SeekTarget {
    data class Absolute(val position: Pts) : SeekTarget
    data class Relative(val offset: Duration) : SeekTarget
    /** A fraction of the total duration, from 0.0 to 1.0. What a seek bar produces. */
    data class Factor(val fraction: Double) : SeekTarget
}

/**
 * Where a seek is in its life.
 *
 * The two waiting rules are the whole reason this is a state machine rather than a boolean.
 *
 * Within [COALESCE_WINDOW_US] of the previous seek, a new request waits until a frame from that seek
 * has actually reached the screen. Without that rule, holding an arrow key freezes the picture
 * completely, because every seek is superseded before it can present anything.
 *
 * A precise seek additionally waits for the previous restart to complete. Without that rule, a seek
 * past the end has its end-of-stream result overwritten by the next seek, and playback never
 * terminates.
 */
internal enum class SeekPhase {
    /** Nothing pending. */
    Idle,

    /** A request is held, waiting for one of the two rules above. */
    Pending,

    /** Queues are being cleared and decoders flushed. */
    Flushing,

    /** Reading and decoding again under the new generation. */
    Filling,

    /** Decoding forward, discarding frames before the target. Precise modes only. */
    Discarding,
    ;

    /**
     * True while the pipeline is actually being moved.
     *
     * [Pending] is deliberately not one of these. A request that is being held by one of the two waiting
     * rules must not stop playback: the picture and the sound carry on at the old position until the seek
     * runs. Only the phases that have already flushed something are the ones that stop it.
     */
    val isRunning: Boolean get() = this == Flushing || this == Filling || this == Discarding
}

internal object SeekTiming {
    /** How long after a seek a new one waits for a frame to be shown first. */
    const val COALESCE_WINDOW_US: Long = 300_000

    /** How close a decoded frame must be to the target to count as landing on it. */
    const val PRECISE_TOLERANCE_US: Long = 5_000

    /**
     * The overshoot retry ladder.
     *
     * A keyframe seek is documented to land at or before the target, but containers without an index
     * resolve it by byte position and can land after it. Aim at the target first, and only back off
     * when the first decoded frame proves the seek overshot. Almost every seek costs one attempt.
     * KiteCodec's current answer is a flat 5 second backoff on every seek, which is right for a
     * thumbnail and five seconds of wasted decoding for a player.
     */
    val OVERSHOOT_BACKOFF_US: LongArray = longArrayOf(0, 500_000, 2_000_000, 8_000_000)
}
