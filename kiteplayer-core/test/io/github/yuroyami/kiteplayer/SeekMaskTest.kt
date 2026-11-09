package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.SeekResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** position() answers a seek's target only while that seek can still land (#785). */
class SeekMaskTest {

    private suspend fun CoreHarness.start() {
        openWithRenderer()
        core.play()
        run(2.seconds)
    }

    @Test
    fun aFailedSeekLeavesNoTargetInThePosition() = runTest {
        val faults = FaultPlan()
        val harness = CoreHarness(this, script = MediaScript(durationUs = 60_000_000), faults = faults)
        harness.start()
        faults.seekFailsOnce = {}
        val result = harness.core.seek(Pts(30_000_000), SeekMode.Precise)
        harness.run(1.seconds)
        assertTrue(result is SeekResult.Rejected, "the seek was answered $result")
        assertEquals(PlaybackStatus.Failed, harness.core.snapshots.value.status)
        assertNotNull(harness.core.snapshots.value.error)
        assertTrue(harness.core.position() < 30.seconds, "a failed player reports ${harness.core.position()}")
        harness.close()
    }

    @Test
    fun aSeekOnAClosedPlayerLeavesNoTargetInThePosition() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 60_000_000))
        harness.start()
        harness.close()
        assertFails { harness.core.seek(Pts(30_000_000), SeekMode.Precise) }
        assertEquals(Duration.ZERO, harness.core.position())
    }
}
