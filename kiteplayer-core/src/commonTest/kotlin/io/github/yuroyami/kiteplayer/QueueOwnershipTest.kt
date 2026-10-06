@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

/**
 * The queue is the player's own from the moment `openQueue` is called. It used to keep the list
 * the caller passed in, so a later edit to that list changed the queue and every snapshot already
 * published, and `next()` then indexed the shorter list and failed the session (#409).
 */
class QueueOwnershipTest {

    private fun items(vararg names: String): MutableList<MediaItem> =
        names.mapTo(mutableListOf()) { MediaItem("scripted://$it") }

    private fun CoreHarness.queueUris(): List<String> = core.snapshots.value.queue.map { it.uri }

    @Test
    fun editingTheCallersListAfterOpenQueueChangesNothing() = runTest {
        val h = CoreHarness(this)
        h.attachRenderer()
        val list = items("first", "second", "third")
        h.core.openQueue(list, 0)
        val before = h.core.snapshots.value
        list.removeAt(1)
        list.add(MediaItem("scripted://added"))
        list[0] = MediaItem("scripted://swapped")
        assertEquals(listOf("first", "second", "third").map { "scripted://$it" }, before.queue.map { it.uri }, "a published snapshot changed")
        assertEquals(listOf("first", "second", "third").map { "scripted://$it" }, h.queueUris())
        h.core.queueNext()
        h.run(100.milliseconds)
        assertEquals("scripted://second", h.core.snapshots.value.media?.uri, "next walks the items the queue was opened with")
        h.core.queueNext()
        h.run(100.milliseconds)
        assertEquals("scripted://third", h.core.snapshots.value.media?.uri)
        assertEquals(PlaybackStatus.Paused, h.core.snapshots.value.status)
        h.close()
    }

    @Test
    fun clearingTheCallersListAfterOpenQueueChangesNothing() = runTest {
        val h = CoreHarness(this)
        h.attachRenderer()
        val list = items("first", "second")
        h.core.openQueue(list, 0)
        list.clear()
        h.core.queueNext()
        h.run(100.milliseconds)
        assertEquals("scripted://second", h.core.snapshots.value.media?.uri)
        h.core.queuePrevious()
        h.run(100.milliseconds)
        assertEquals("scripted://first", h.core.snapshots.value.media?.uri)
        h.close()
    }

    /** The edit lands after the call was made and before the session loop took the command. */
    @Test
    fun editingTheListBeforeTheLoopTakesTheCommandChangesNothing() = runTest {
        val h = CoreHarness(this)
        h.attachRenderer()
        val list = items("first", "second")
        val opening = launch(start = CoroutineStart.UNDISPATCHED) { h.core.openQueue(list, 1) }
        list.removeAt(1)
        opening.join()
        assertEquals(listOf("scripted://first", "scripted://second"), h.queueUris())
        assertEquals("scripted://second", h.core.snapshots.value.media?.uri, "the start index reads the list as it was passed")
        h.close()
    }

    @Test
    fun twoPlayersGivenOneListKeepQueuesOfTheirOwn() = runTest {
        val first = CoreHarness(this)
        val second = CoreHarness(this)
        first.attachRenderer()
        second.attachRenderer()
        val list = items("a", "b")
        first.core.openQueue(list, 0)
        second.core.openQueue(list, 0)
        second.core.addToQueue(items("c"))
        list.removeAt(0)
        assertEquals(listOf("scripted://a", "scripted://b"), first.queueUris())
        assertEquals(listOf("scripted://a", "scripted://b", "scripted://c"), second.queueUris())
        first.close()
        second.close()
    }

    @Test
    fun editingTheListPassedToAddToQueueChangesNothing() = runTest {
        val h = CoreHarness(this)
        h.attachRenderer()
        h.core.openQueue(items("first"), 0)
        val added = items("second", "third")
        val adding = launch(start = CoroutineStart.UNDISPATCHED) { h.core.addToQueue(added) }
        added.clear()
        adding.join()
        assertEquals(listOf("first", "second", "third").map { "scripted://$it" }, h.queueUris())
        h.close()
    }
}
