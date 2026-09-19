package io.github.yuroyami.kiteplayer.compose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GpuLeaseInfrastructureTest {
    @Test
    fun droppedMetricDoesNotRetireAnythingUntilALaterExactGpuCompletion() {
        val ledger = GpuCompletionBatchLedger<String>()
        ledger.record(100L, "first")
        ledger.record(116L, "second")
        ledger.record(133L, "proof")

        assertNull(ledger.completeThroughExact(120L), "an unpaired metric is not a completion proof")
        assertTrue(ledger.hasPending)
        assertEquals(
            listOf("first", "second", "proof"),
            ledger.completeThroughExact(133L),
            "the later matching GPU completion retires earlier submissions in queue order",
        )
        assertFalse(ledger.hasPending)
    }

    @Test
    fun repeatedDrawsAtOneVsyncStayInOneOrderedBatch() {
        val ledger = GpuCompletionBatchLedger<String>()
        ledger.record(50L, "underlay")
        ledger.record(50L, "overlay")

        assertEquals(listOf("underlay", "overlay"), ledger.completeThroughExact(50L))
    }

    @Test
    fun unpairedHardwareDrawsFoldIntoTheNextExactProof() {
        val ledger = GpuCompletionBatchLedger<String>()
        ledger.holdUntilNextProof("detached")
        ledger.holdUntilNextProof("stale-node")

        assertTrue(ledger.hasPending)
        ledger.record(75L, "proof")

        assertEquals(
            listOf("detached", "stale-node", "proof"),
            ledger.completeThroughExact(75L),
        )
        assertFalse(ledger.hasPending)
    }

    @Test
    fun aBatchNewerThanTheReportedRefreshNeedsNoProofDraw() {
        val ledger = GpuCompletionBatchLedger<String>()
        ledger.record(100L, "reported")
        ledger.record(116L, "newer")

        assertEquals(listOf("reported"), ledger.completeThroughExact(100L))
        assertFalse(ledger.needsProofAfter(100L), "the newer draw's own metrics are still on the way")
    }

    @Test
    fun aBatchOlderThanTheReportedRefreshNeedsAProofDraw() {
        val ledger = GpuCompletionBatchLedger<String>()
        ledger.record(100L, "lost metrics")

        assertNull(ledger.completeThroughExact(116L))
        assertTrue(ledger.needsProofAfter(116L), "only a later keyed draw can prove it now")
    }

    @Test
    fun anUnkeyedDrawNeedsAProofDraw() {
        val ledger = GpuCompletionBatchLedger<String>()
        ledger.holdUntilNextProof("detached")

        assertTrue(ledger.needsProofAfter(100L))
    }

    @Test
    fun anEmptyLedgerNeedsNoProofDraw() {
        assertFalse(GpuCompletionBatchLedger<String>().needsProofAfter(100L))
    }

    /**
     * The timing measured on a phone: a 24 fps picture every fifth refresh at 120 Hz, and GPU
     * metrics that arrive two refreshes after their draw. A proof request draws on the next
     * refresh. One extra draw, as any other change in the window makes, must not start a loop.
     */
    @Test
    fun steadyPlaybackDrawsOncePerPictureAfterOneExtraDraw() {
        val ledger = GpuCompletionBatchLedger<String>()
        val drawnAt = mutableSetOf<Long>()
        val proofDrawAt = mutableSetOf(1L)
        for (refresh in 0L until 600L) {
            if (refresh % 5L == 0L || refresh in proofDrawAt) {
                ledger.record(refresh, "draw")
                drawnAt += refresh
            }
            val reported = refresh - 2L
            if (reported in drawnAt) {
                ledger.completeThroughExact(reported)
                if (ledger.needsProofAfter(reported)) proofDrawAt += refresh + 1L
            }
        }
        assertEquals(121, drawnAt.size, "120 pictures plus the one extra draw")
    }

    @Test
    fun twoConsumerBindingsRemainIndependent() {
        val bindings = GpuConsumerBindingBook<String>()
        val first = bindings.bind("first")
        val second = bindings.bind("second")

        bindings.remove(first)

        assertNull(bindings[first])
        assertEquals("second", bindings[second])
        assertTrue(bindings.isNotEmpty)
    }

    @Test
    fun clearingLedgerDropsRecordedAndUnpairedOwnership() {
        val ledger = GpuCompletionBatchLedger<Any>()
        ledger.record(10L, Any())
        ledger.holdUntilNextProof(Any())

        ledger.clear()

        assertFalse(ledger.hasPending)
        assertNull(ledger.completeThroughExact(10L))
    }
}
