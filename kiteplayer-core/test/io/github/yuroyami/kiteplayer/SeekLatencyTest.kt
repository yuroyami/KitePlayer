@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * How long a seek takes when the media work is free.
 *
 * The scripted decoder decodes in zero time and the container seek is instant, so everything this
 * measures is the engine's own signalling: how fast the workers park, and how fast the actor
 * notices the landed frame. Measured on real media before this test existed: a paused keyframe
 * seek cost about 200 ms with 0 ms of it in the container, because five idle workers were parked
 * ONE AFTER ANOTHER, each sleeping out its own 50 ms nap before it saw the request, and the landing
 * was then observed at a 50 ms sampling interval rather than when the frame arrived.
 *
 * The budget is no nap at all. The request goes to every worker before any acknowledgement is
 * awaited, an idle worker WAKES on the request instead of sleeping out its own poll, and the
 * landing and the first present wake the actor when they happen. 15 ms of virtual time leaves
 * room for the handshakes and nothing else.
 */
class SeekLatencyTest {

    @Test
    fun `a paused seek waits for no worker nap at all`() = runTest {
        val harness = CoreHarness(this)
        harness.openWithRenderer()
        // Paused on purpose: an idle pipeline is the seek-bar case, and the slowest. Every worker
        // is asleep in its own poll when the request arrives.
        harness.run(500.milliseconds)

        val before = harness.scheduler.currentTime
        harness.core.seek(Pts.ofDuration(2.seconds), SeekMode.Precise)
        val elapsed = harness.scheduler.currentTime - before

        assertTrue(
            elapsed <= 15,
            "a paused precise seek with instant media took ${elapsed}ms of virtual time; " +
                "the engine's own signalling is the only thing that can cost this much",
        )
        harness.close()
    }

    @Test
    fun `a seek while playing meets the same budget`() = runTest {
        val harness = CoreHarness(this)
        harness.openWithRenderer()
        harness.core.play()
        harness.run(500.milliseconds)

        val before = harness.scheduler.currentTime
        harness.core.seek(Pts.ofDuration(2.seconds), SeekMode.Precise)
        val elapsed = harness.scheduler.currentTime - before

        assertTrue(
            elapsed <= 15,
            "a playing precise seek with instant media took ${elapsed}ms of virtual time",
        )
        harness.close()
    }
}
