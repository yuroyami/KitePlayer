package io.github.yuroyami.kiteplayer.internal

import io.github.yuroyami.kiteplayer.FrameDropPolicy

/**
 * Whether a video packet is thrown away without decoding it.
 *
 * A pure rule with no session state, for the same reason [SyncLaw] is one: this decides whether a
 * viewer sees a stretch of film at all, and a decision that only exists inside a worker loop can
 * only be proved by making a real decoder fall behind.
 *
 * ### Why a whole group of pictures and not one packet
 *
 * Dropping a single non-keyframe packet does not save one frame's work. Every later frame in that
 * group REFERENCES it, so the decoder goes on producing pictures built on something that never
 * arrived, which is visible garbage rather than a saved millisecond. The only drop with a clean
 * edge is "everything from here to the next keyframe", which is what [alreadySkipping] carries
 * between calls: once a drop starts it continues until a keyframe re-anchors the decoder. The
 * picture holds on its last decoded frame until then. That is the honest cost of catching up this
 * way, and it is why [FrameDropPolicy.LateAndDecode] is not the default.
 *
 * ### What "cannot keep up" means
 *
 * The packet's own timestamp is [lateThresholdUs] behind the position the player is publishing.
 * Packets normally arrive AHEAD of the clock, so one that far in the past means the decoder is
 * genuinely losing the race and every frame it makes from here would be dropped as late anyway.
 * The threshold sits far above the schedule's own late window on purpose: dropping after decode
 * costs one picture, dropping before it costs a group of them.
 *
 * ### The two packets that are never dropped
 *
 * A KEYFRAME, because it is the cheap re-anchor the skip is aiming for, and it ends the skip. And
 * a packet before [discardBeforeUs], because those are exactly the ones a precise seek decodes and
 * then throws away: skipping them would leave the frames AT the seek target referencing pictures
 * that were never decoded, so the landing would be garbage.
 */
internal fun skipVideoPacketBeforeDecode(
    policy: FrameDropPolicy,
    isKeyframe: Boolean,
    packetPtsUs: Long?,
    positionUs: Long,
    discardBeforeUs: Long,
    alreadySkipping: Boolean,
    lateThresholdUs: Long,
): Boolean {
    if (policy != FrameDropPolicy.LateAndDecode) return false
    if (isKeyframe) return false
    // A packet with no timestamp cannot be judged late, and guessing is how a player starts
    // dropping picture out of a stream whose container simply says nothing.
    val pts = packetPtsUs ?: return false
    if (pts < discardBeforeUs) return false
    if (alreadySkipping) return true
    return pts < positionUs - lateThresholdUs
}
