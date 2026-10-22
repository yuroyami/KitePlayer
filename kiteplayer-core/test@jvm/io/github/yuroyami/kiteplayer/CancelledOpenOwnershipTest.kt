package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds

/**
 * The stop a cancelled open posts belongs to that open (#410). It may arrive after the caller's
 * reply was answered and other calls have stopped that item and opened another, and then it must
 * leave the newer item alone; while the cancelled open is still building, it still tears it down.
 */
class CancelledOpenOwnershipTest {

    /** Holds every resumption of the coroutines it runs until [release], so a caller can stay behind. */
    private class HeldCallerDispatcher : CoroutineDispatcher() {
        private val pending = ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            pending.add(block)
        }
        fun release() {
            while (true) (pending.poll() ?: return).run()
        }
    }

    @Test
    fun aLateCancelOfAnOldOpenLeavesTheNewerItemOpen() = runTest {
        val harness = CoreHarness(this)
        harness.attachRenderer()
        val held = HeldCallerDispatcher()
        val opening = launch(held, start = CoroutineStart.UNDISPATCHED) { harness.core.open(MediaItem("scripted://old")) }
        harness.run(100.milliseconds)
        assertEquals(PlaybackStatus.Paused, harness.core.snapshots.value.status, "the old item opened")

        harness.core.stop()
        harness.core.open(MediaItem("scripted://new"))
        assertEquals("scripted://new", harness.core.snapshots.value.media?.uri)

        opening.cancel()
        held.release()
        harness.run(100.milliseconds)
        assertEquals(PlaybackStatus.Paused, harness.core.snapshots.value.status, "the old open's cancel stopped the new item")
        assertEquals("scripted://new", harness.core.snapshots.value.media?.uri)
        harness.close()
    }

    @Test
    fun aCancelWhileTheOpenIsStillBuildingStillTearsItDown() = runTest {
        // Reads that take half a second each keep the open filling its queues while the caller leaves.
        val harness = CoreHarness(this, script = MediaScript(readDelayUs = 500_000))
        harness.attachRenderer()
        val opening = launch { harness.core.open(MediaItem("scripted://slow")) }
        harness.run(100.milliseconds)
        assertEquals(PlaybackStatus.Opening, harness.core.snapshots.value.status, "the open is still building")

        opening.cancel()
        harness.run(2_000.milliseconds)
        assertEquals(PlaybackStatus.Idle, harness.core.snapshots.value.status, "the cancelled open was left behind")
        assertNull(harness.core.snapshots.value.media)
        harness.close()
    }
}
