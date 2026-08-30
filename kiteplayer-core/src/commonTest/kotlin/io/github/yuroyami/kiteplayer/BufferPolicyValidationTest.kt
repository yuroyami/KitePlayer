package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.PacketQueue
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Why `readyDuration` may exceed `totalDuration`, pinned so nobody forbids it later.
 *
 * A note from an earlier session claimed that a policy asking to buffer more than the cache may
 * hold wedges the session in Buffering for ever, and proposed refusing it at construction. That
 * was tried here and it is WRONG, which the existing suite caught immediately: a test deliberately
 * runs a 400 millisecond budget against the default one second readiness and plays fine.
 *
 * The reason is that readiness is an OR of three conditions, not one: a queue is ready when the
 * stream has ended, OR it holds enough microseconds, OR it holds enough packets. A duration
 * threshold the budget cannot reach is therefore survivable, because the packet threshold or the
 * end of the stream still answers it.
 *
 * Something did wedge when that note was written, but not for the stated reason; a genuine wedge
 * needs ALL THREE unreachable at once, which takes a byte cap tight enough to stop the queue
 * before it reaches the packet count, and no policy field can see that. So there is nothing to
 * refuse here, and this file exists to keep the refusal from being added on the strength of a
 * plausible sentence.
 */
class BufferPolicyValidationTest {

    @Test
    fun `a readiness duration larger than the whole budget is legal because packets can answer it`() {
        val policy = BufferPolicy(readyDuration = 40.seconds, totalDuration = 30.seconds)
        assertTrue(policy.readyDuration > policy.totalDuration)
    }

    @Test
    fun `a queue is ready on packet count alone which is what makes that policy survivable`() {
        val queue = PacketQueue(streamIndex = 0, softLimitUs = 1_000_000)
        assertTrue(
            queue.isReady(readyUs = Long.MAX_VALUE, readyPackets = 0),
            "with a packet threshold of zero the queue is ready whatever the duration asks for",
        )
    }

    @Test
    fun `the documented defaults construct`() {
        BufferPolicy()
    }
}
