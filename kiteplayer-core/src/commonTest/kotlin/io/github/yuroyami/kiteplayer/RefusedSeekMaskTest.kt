package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** A seek the player refuses or drops leaves position() where it was (#255). */
class RefusedSeekMaskTest {

    @Test
    fun aSeekRefusedOnAnIdlePlayerLeavesThePositionAlone() = runTest {
        val harness = CoreHarness(this)
        assertFailsWith<IllegalStateException> { harness.core.seek(Pts(10_000_000), SeekMode.Precise) }
        assertEquals(Duration.ZERO, harness.core.position(), "the refused target became the position")
        harness.close()
    }

    @Test
    fun aSeekLaterDroppedOnAFailedPlayerLeavesThePositionAlone() = runTest {
        val harness = CoreHarness(this, faults = FaultPlan().also { it.failReadAfter = 12 })
        runCatching { harness.openWithRenderer() }
        harness.run(2.seconds)
        assertEquals(PlaybackStatus.Failed, harness.core.snapshots.value.status, "the player never failed")
        val before = harness.core.position()

        harness.core.seekLater(Pts(15_000_000), SeekMode.Precise)
        harness.run(1.seconds)
        assertEquals(before, harness.core.position(), "the dropped target became the position")
        harness.close()
    }
}
