package io.github.yuroyami.kiteplayer.output

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AppleAudioSessionPolicyTest {

    @Test
    fun `managed leases activate once and only the final close deactivates`() {
        val controller = RecordingAppleAudioSessionController()
        val manager = AppleAudioSessionLeaseManager(controller)

        val first = manager.acquire(AppleAudioSessionPolicy.ManagedPlayback)
        val second = manager.acquire(AppleAudioSessionPolicy.ManagedPlayback)

        assertEquals(listOf("category:playback:moviePlayback:none", "active:true:none"), controller.calls)
        assertEquals(2, manager.activeLeaseCount)

        first.close()
        first.close()
        assertEquals(listOf("category:playback:moviePlayback:none", "active:true:none"), controller.calls)
        assertEquals(1, manager.activeLeaseCount)

        second.close()
        second.close()
        assertEquals(
            listOf(
                "category:playback:moviePlayback:none",
                "active:true:none",
                "active:false:notifyOthers",
            ),
            controller.calls,
        )
        assertEquals(0, manager.activeLeaseCount)
    }

    @Test
    fun `activation failure rolls back the lease count and the next acquire retries`() {
        val controller = RecordingAppleAudioSessionController(failActivationCount = 1)
        val manager = AppleAudioSessionLeaseManager(controller)

        assertFailsWith<PlannedAppleAudioSessionFailure> {
            manager.acquire(AppleAudioSessionPolicy.ManagedPlayback)
        }
        assertEquals(0, manager.activeLeaseCount)

        val retry = manager.acquire(AppleAudioSessionPolicy.ManagedPlayback)
        assertEquals(1, manager.activeLeaseCount)
        retry.close()
        assertEquals(
            listOf(
                "category:playback:moviePlayback:none",
                "active:true:none",
                "category:playback:moviePlayback:none",
                "active:true:none",
                "active:false:notifyOthers",
            ),
            controller.calls,
        )
    }

    @Test
    fun `application managed policy makes no session call`() {
        val controller = RecordingAppleAudioSessionController()
        val manager = AppleAudioSessionLeaseManager(controller)

        val first = manager.acquire(AppleAudioSessionPolicy.ApplicationManaged)
        val second = manager.acquire(AppleAudioSessionPolicy.ApplicationManaged)
        first.close()
        second.close()

        assertEquals(emptyList(), controller.calls)
        assertEquals(0, manager.activeLeaseCount)
    }

    @Test
    fun `concurrent managed acquires still form one process lease`() = runBlocking {
        val controller = RecordingAppleAudioSessionController()
        val manager = AppleAudioSessionLeaseManager(controller)

        val leases = List(64) {
            async(Dispatchers.Default) {
                manager.acquire(AppleAudioSessionPolicy.ManagedPlayback)
            }
        }.awaitAll()

        assertEquals(64, manager.activeLeaseCount)
        assertEquals(listOf("category:playback:moviePlayback:none", "active:true:none"), controller.calls)

        leases.map { lease -> async(Dispatchers.Default) { lease.close() } }.awaitAll()
        assertEquals(0, manager.activeLeaseCount)
        assertEquals(
            listOf(
                "category:playback:moviePlayback:none",
                "active:true:none",
                "active:false:notifyOthers",
            ),
            controller.calls,
        )
    }

}

/** Shared fake seam for the lease-manager and sink-transaction fixtures. */
internal class RecordingAppleAudioSessionController(
    private var failActivationCount: Int = 0,
    private val observe: (String) -> Unit = {},
) : AppleAudioSessionController {
    private val lock = SynchronizedObject()
    private val recorded = mutableListOf<String>()

    val calls: List<String> get() = synchronized(lock) { recorded.toList() }

    override fun setPlaybackCategory() {
        record("category:playback:moviePlayback:none")
    }

    override fun setActive(active: Boolean, notifyOthers: Boolean) {
        synchronized(lock) {
            val event = "active:$active:${if (notifyOthers) "notifyOthers" else "none"}"
            recorded += event
            observe(event)
            if (active && failActivationCount > 0) {
                failActivationCount--
                throw PlannedAppleAudioSessionFailure()
            }
        }
    }

    private fun record(event: String) {
        synchronized(lock) {
            recorded += event
            observe(event)
        }
    }
}

internal class PlannedAppleAudioSessionFailure : IllegalStateException("planned activation failure")
