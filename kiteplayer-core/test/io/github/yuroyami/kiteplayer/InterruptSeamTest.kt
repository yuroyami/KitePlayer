package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.SeekResult
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    /**
     * The start seek of an open, which every "resume where you left off" makes, is bounded as a
     * user's seek is and gives way to a stop or a close (#591). It ran on the actor with no
     * deadline, so a long scan kept the player from its mailbox until it ended.
     */
    @Test
    fun aWedgedStartSeekGivesWayToAStopAndTheCloseReturns() = runTest(timeout = 20.seconds) {
        val faults = FaultPlan().apply { seekWedges = true }
        val harness = CoreHarness(this, script = MediaScript(durationUs = 120_000_000), faults = faults)
        harness.attachRenderer()
        val opening = async { runCatching { harness.core.open(MediaItem("scripted://media", startPosition = 60.seconds)) } }
        harness.run(1.seconds)
        assertTrue(!opening.isCompleted, "the start seek did not wedge, so this proves nothing")
        val stopping = async { runCatching { harness.core.stop() } }
        harness.run(1.seconds)
        assertTrue(opening.isCompleted, "the open did not give way to the stop")
        assertTrue(opening.await().isFailure, "the preempted open must fail its caller")
        assertTrue(stopping.isCompleted, "the stop did not run")
        assertTrue(harness.source.interruptCalls > 0, "the wedged seek was never interrupted")
        withTimeout(30.seconds) { harness.core.closeAndAwait() }
        assertEquals(0, harness.ledger.liveCount)
    }

    @Test
    fun aWedgedStartSeekFailsTypedAtTheDeadline() = runTest(timeout = 20.seconds) {
        val faults = FaultPlan().apply { seekWedges = true }
        val harness = CoreHarness(this, script = MediaScript(durationUs = 120_000_000), faults = faults)
        harness.attachRenderer()
        val failure = assertFailsWith<PlaybackException> {
            harness.core.open(MediaItem("scripted://media", startPosition = 60.seconds))
        }
        assertIs<PlaybackError.SourceUnavailable>(failure.error, "was ${failure.error}")
        assertEquals(PlaybackStatus.Failed, harness.core.snapshots.value.status)
        harness.close()
        assertEquals(0, harness.ledger.liveCount)
    }

    /** The seek of the software recovery after a hardware decoder fails gives way to a stop (#591). */
    @Test
    fun aWedgedRecoverySeekGivesWayToAStop() = runTest(timeout = 20.seconds) {
        val faults = FaultPlan().apply { videoDecodeFailsAfterFrames = 12 }
        val harness = CoreHarness(
            this,
            script = MediaScript(durationUs = 6_000_000),
            faults = faults,
            config = PlayerConfig(hardwareDecode = HwdecPolicy.Auto),
        )
        harness.backend.videoDecoderStatus.value = HwdecStatus.HardwareWithDownload(HwdecKind.VideoToolbox)
        harness.openWithRenderer()
        faults.seekWedges = true
        harness.core.play()
        harness.run(1.seconds)
        assertEquals(2, harness.backend.openCalls, "the decoder never failed, so no recovery seek ran")
        val stopping = async { runCatching { harness.core.stop() } }
        harness.run(1.seconds)
        assertTrue(stopping.isCompleted, "the stop waited behind the wedged recovery seek")
        assertTrue(harness.source.interruptCalls > 0, "the wedged seek was never interrupted")
        withTimeout(30.seconds) { harness.core.closeAndAwait() }
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
