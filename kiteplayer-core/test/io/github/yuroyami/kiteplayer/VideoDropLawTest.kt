package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.skipVideoPacketBeforeDecode
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The pre-decode drop rule on its own.
 *
 * Every arm here is a picture the viewer either sees or does not, so each is stated as a case
 * rather than covered by one happy path. The number that matters is 500 ms: packets normally
 * arrive AHEAD of the clock, so a packet that far in the past is a decoder losing the race.
 */
class VideoDropLawTest {

    private val late = 500_000L

    private fun skip(
        policy: FrameDropPolicy = FrameDropPolicy.LateAndDecode,
        isKeyframe: Boolean = false,
        ptsUs: Long? = 0L,
        positionUs: Long = 10_000_000L,
        discardBeforeUs: Long = Long.MIN_VALUE,
        alreadySkipping: Boolean = false,
    ) = skipVideoPacketBeforeDecode(
        policy = policy,
        isKeyframe = isKeyframe,
        packetPtsUs = ptsUs,
        positionUs = positionUs,
        discardBeforeUs = discardBeforeUs,
        alreadySkipping = alreadySkipping,
        lateThresholdUs = late,
    )

    @Test
    fun `only LateAndDecode drops anything before decoding`() {
        for (policy in listOf(FrameDropPolicy.Never, FrameDropPolicy.LateOnly)) {
            assertFalse(skip(policy = policy), "$policy must never drop a packet undecoded")
            assertFalse(
                skip(policy = policy, alreadySkipping = true),
                "$policy cannot even be mid-skip, but must refuse if asked",
            )
        }
        assertTrue(skip(), "LateAndDecode over the threshold must drop")
    }

    @Test
    fun `a packet only just late is decoded`() {
        val position = 10_000_000L
        // One microsecond inside the window: still decoded, so the rule has one edge and not two.
        assertFalse(skip(ptsUs = position - late, positionUs = position))
        assertFalse(skip(ptsUs = position - late + 1, positionUs = position))
        assertTrue(skip(ptsUs = position - late - 1, positionUs = position))
    }

    @Test
    fun `a packet ahead of the clock is never late`() {
        assertFalse(skip(ptsUs = 12_000_000L, positionUs = 10_000_000L), "the normal case: buffered ahead")
    }

    @Test
    fun `a keyframe is never dropped even mid-skip`() {
        assertFalse(skip(isKeyframe = true), "a keyframe is the re-anchor the skip is aiming for")
        assertFalse(
            skip(isKeyframe = true, alreadySkipping = true),
            "and it is what ENDS the skip, so it has to be decoded",
        )
    }

    @Test
    fun `a skip continues through packets that are not themselves late`() {
        // The whole point: after the first drop every later frame in the group references
        // something that never decoded, so they go too, however fresh their timestamps look.
        assertFalse(skip(ptsUs = 12_000_000L, positionUs = 10_000_000L, alreadySkipping = false))
        assertTrue(skip(ptsUs = 12_000_000L, positionUs = 10_000_000L, alreadySkipping = true))
    }

    @Test
    fun `a packet before the seek discard boundary is decoded`() {
        // A precise seek decodes from the keyframe and throws away what lands before the target.
        // Skipping those would leave the frames AT the target with nothing to reference.
        assertTrue(skip(ptsUs = 1_000_000L, discardBeforeUs = Long.MIN_VALUE))
        assertFalse(skip(ptsUs = 1_000_000L, discardBeforeUs = 2_000_000L))
        assertFalse(skip(ptsUs = 1_000_000L, discardBeforeUs = 2_000_000L, alreadySkipping = true))
    }

    @Test
    fun `a packet with no timestamp is decoded`() {
        // Containers that give no timestamps are not rare. Guessing is how a player starts
        // throwing away picture from a stream that never said anything was wrong.
        assertFalse(skip(ptsUs = null))
        assertFalse(skip(ptsUs = null, alreadySkipping = true))
    }
}
