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
