@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * A snapshot's queue cannot be edited from Java, where every list has `remove`. It used to be the
 * player's own list, so an edit through a snapshot changed the queue the session walks (#409).
 */
class QueueSnapshotJvmTest {

    @Test
    fun aSnapshotsQueueRefusesAnEditThroughTheJavaListInterface() = runTest {
        val h = CoreHarness(this)
        h.attachRenderer()
        h.core.openQueue(mutableListOf(MediaItem("scripted://first"), MediaItem("scripted://second")), 0)
        @Suppress("UNCHECKED_CAST")
        val asJava = h.core.snapshots.value.queue as java.util.List<MediaItem>
        assertFailsWith<UnsupportedOperationException> { asJava.remove(0) }
        assertEquals(2, h.core.snapshots.value.queue.size)
        h.close()
    }
}
