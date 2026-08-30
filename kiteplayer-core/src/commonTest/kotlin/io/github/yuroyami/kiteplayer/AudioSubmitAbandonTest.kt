@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A decoded audio buffer abandoned half way into the ring must still be accounted for.
 *
 * The submit writes a buffer into the ring in chunks, and while the ring is full it polls rather
 * than blocking. A quiesce request during that poll abandons the unaccepted remainder, which is
 * correct: the flush that follows was going to discard it anyway. What must NOT happen is the
 * abandoned buffer staying counted as in flight, because parking the audio workers asserts that
 * count is zero and would take the whole track switch down with it.
 *
 * The reachable way to hold a submit half way is a paused device: the ring fills, nothing drains
 * it, and the feeder sits in the poll until something asks it to park.
 *
 * ### One thing this does not prove, measured rather than guessed
 *
 * Abandoning is a cooperative RETURN, not a coroutine cancellation. Moving the in-flight decrement
 * out of its `finally` and onto the success path changes nothing here, because the abandoned submit
 * still returns normally and still reaches it. What this test does hold down is the abandonment
 * itself: neuter the abort callback so the submit polls forever and the switch comes back
 * `Discarded(audio workers did not reach a safe switch boundary within 2s)`. So read this as a pin
 * on the abort being wired and the count surviving it, not as a pin on the `finally`.
 */
class AudioSubmitAbandonTest {

    @Test
    fun `a track switch while the ring is full applies and leaves nothing owned`() = runTest {
        val harness = CoreHarness(
            this,
            script = MediaScript(
                durationUs = 30_000_000,
                hasVideo = false,
                additionalAudioTracks = listOf(ScriptedAudioTrack(index = 2, marker = 2f)),
            ),
            renderer = null,
        )
        harness.open()
        harness.core.play()
        harness.run(300.milliseconds)

        // Pause, then wait long enough for the feeder to fill the ring and start polling. The queue
        // depth reaching the ring's own capacity is the evidence a submit is actually parked, not
        // an assumption that one is.
        harness.core.pause()
        harness.run(2.seconds)
        assertTrue(
            harness.core.stats.value.audioQueueDepth > 100.milliseconds,
            "the ring must be full before the switch, or this test is not about an abandoned " +
                "submit at all; depth was ${harness.core.stats.value.audioQueueDepth}",
        )

        // The switch parks the audio workers, which asserts nothing is still owned. An abandoned
        // buffer left counted turns this into a thrown invariant rather than a track change.
        val outcome = harness.core.selectTrack(TrackKind.Audio, TrackId(2))
        assertIs<TrackChange.Applied>(
            outcome,
            "a switch over a parked submit must complete, got $outcome",
        )

        // And the player really works afterwards: an accounting fix that wedged the feeder would
        // satisfy everything above and play nothing.
        harness.core.play()
        harness.run(2.seconds)
        assertEquals(PlaybackStatus.Playing, harness.core.snapshots.value.status)
        assertTrue(harness.sink.framesPlayed > 0, "the device must be hearing the new track")

        harness.close()
        assertEquals(0, harness.ledger.liveCount, "the abandoned buffer must still have been closed")
        assertEquals(0, harness.ledger.doubleCloseCount, "and must not have been closed twice")
    }
}
