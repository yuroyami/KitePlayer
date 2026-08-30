package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.SeekResult
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The engine half: a source call wedged inside an uncancellable
 * native boundary must never hold the actor or a teardown for ever. The scripted source models
 * the wedge with a NonCancellable gate only interrupt() opens, which is exactly the shape of a
 * blocking FFmpeg call behind the interrupt seam.
 */
class InterruptSeamTest {

    @Test
    fun aWedgedContainerSeekFailsTypedInsteadOfHoldingTheActorForEver() = runTest(timeout = 20.seconds) {
        val faults = FaultPlan().apply { seekWedges = true }
        val harness = CoreHarness(this, script = MediaScript(durationUs = 5_000_000), faults = faults)
        harness.openWithRenderer()
        harness.core.play()
        harness.run(200.milliseconds)

        val result = harness.core.seek(Pts(2_000_000), SeekMode.Precise)

        assertIs<SeekResult.Rejected>(result, "a wedged seek must answer its caller, got $result")
        assertTrue(harness.source.interruptCalls > 0, "the deadline never reached for the interrupt seam")
        assertEquals(PlaybackStatus.Failed, harness.core.snapshots.value.status)
        assertIs<PlaybackError.SourceUnavailable>(
            harness.core.snapshots.value.error,
            "a poisoned source is a source failure, was ${harness.core.snapshots.value.error}",
        )
        harness.close()
        assertEquals(0, harness.ledger.liveCount)
        assertEquals(0, harness.ledger.doubleCloseCount)
    }

    @Test
    fun aSourceThatCannotInterruptKeepsTheOldUnboundedWaitAndStillLands() = runTest(timeout = 20.seconds) {
        // The world before interruptible opens: interrupt() answers false. The engine must keep waiting, and
        // when the call finally returns on its own the seek completes normally.
        val faults = FaultPlan().apply {
            seekWedges = true
            interruptSupported = false
        }
        val harness = CoreHarness(this, script = MediaScript(durationUs = 5_000_000), faults = faults)
        harness.openWithRenderer()
        harness.core.play()
        harness.run(200.milliseconds)

        var result: SeekResult? = null
        val seekJob = launch { result = harness.core.seek(Pts(2_000_000), SeekMode.Precise) }
        harness.run(15.seconds)
        assertTrue(harness.source.interruptCalls > 0, "the engine never even asked the seam")
        assertTrue(!seekJob.isCompleted, "an uninterruptible source must keep the old unbounded wait")

        // The wedge clears on its own, as a slow-but-honest scan eventually does.
        harness.source.releaseWedge()
        harness.run(1.seconds)
        assertTrue(seekJob.isCompleted, "the seek never resumed after the source recovered")
        assertIs<SeekResult.Applied>(result, "a recovered seek must land, got $result")
        harness.close()
    }

    @Test
    fun stopWithAWedgedReadCompletesBecauseTeardownInterruptsTheSource() = runTest(timeout = 20.seconds) {
        val faults = FaultPlan().apply { readWedgesAfter = 40 }
        val harness = CoreHarness(this, script = MediaScript(durationUs = 30_000_000), faults = faults)
        harness.openWithRenderer()
        harness.core.play()
        harness.run(2.seconds)

        harness.core.stop()

        assertTrue(harness.source.interruptCalls > 0, "teardown never aborted the wedged read")
        assertTrue(
            harness.core.snapshots.value.status != PlaybackStatus.Failed,
            "a stop is not a failure: ${harness.core.snapshots.value.error}",
        )
        harness.close()
        assertEquals(0, harness.ledger.liveCount)
        assertEquals(0, harness.ledger.doubleCloseCount)
    }
}
