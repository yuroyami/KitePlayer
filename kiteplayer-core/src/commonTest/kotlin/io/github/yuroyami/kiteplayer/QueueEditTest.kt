@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Editing the queue while it plays.
 *
 * Opening a queue was the only way to shape one, so an application that let someone add a track,
 * drag one up the list, or swipe one away had to reopen the whole queue and lose the playing item
 * with it. Every edit here is one actor command, so the items and the cursor into them move
 * together and nothing can read a cursor that points at the wrong track.
 *
 * The rule the index follows is the same in all four: the item that was playing keeps playing, and
 * the cursor goes wherever that item went. The only edit that can open anything is removing the
 * item that is playing, because that item is gone and something has to take its place.
 */
class QueueEditTest {

    private fun items(vararg names: String) = names.map { MediaItem("scripted://$it") }

    private suspend fun openThree(harness: CoreHarness, startIndex: Int = 1) {
        harness.attachRenderer()
        harness.core.openQueue(items("a", "b", "c"), startIndex = startIndex)
    }

    private fun CoreHarness.uris() = core.snapshots.value.queue.map { it.uri.removePrefix("scripted://") }

    // -------------------------------------------------------------------------------------------
    // Adding.
    // -------------------------------------------------------------------------------------------

    @Test
    fun `an insert before the playing item moves the cursor with it`() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 8_000_000))
        openThree(harness)
        harness.core.play()
        harness.run(200.milliseconds)
        val opens = harness.backend.openCalls

        harness.core.addToQueue(items("x"), index = 0)

        assertEquals(listOf("x", "a", "b", "c"), harness.uris())
        assertEquals(2, harness.core.snapshots.value.queueIndex, "the cursor did not follow the playing item")
        assertEquals("scripted://b", harness.core.snapshots.value.media?.uri, "the playing item changed")
        assertEquals(opens, harness.backend.openCalls, "an insert reopened the media")
        harness.close()
    }

    @Test
    fun `an insert after the playing item leaves the cursor where it is`() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 8_000_000))
        openThree(harness)
        harness.core.addToQueue(items("x"), index = 2)

        assertEquals(listOf("a", "b", "x", "c"), harness.uris())
        assertEquals(1, harness.core.snapshots.value.queueIndex)
        harness.close()
    }

    @Test
    fun `an add with no index goes to the end`() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 8_000_000))
        openThree(harness)
        harness.core.addToQueue(items("x", "y"))

        assertEquals(listOf("a", "b", "c", "x", "y"), harness.uris())
        assertEquals(1, harness.core.snapshots.value.queueIndex)
        harness.close()
    }

    @Test
    fun `next after an insert follows the new order`() = runTest {
        // The edit is not just a change of what the snapshot says. What plays next has to come
        // from the edited list, or the queue an application sees and the queue the engine walks
        // are two different queues.
        val harness = CoreHarness(this, script = MediaScript(durationUs = 8_000_000))
        openThree(harness, startIndex = 0)
        harness.core.addToQueue(items("x"), index = 1)
        harness.core.queueNext()
        harness.run(200.milliseconds)

        assertEquals("scripted://x", harness.core.snapshots.value.media?.uri, "next skipped the inserted item")
        assertEquals(1, harness.core.snapshots.value.queueIndex)
        harness.close()
    }

    // -------------------------------------------------------------------------------------------
    // Moving.
    // -------------------------------------------------------------------------------------------

    @Test
    fun `moving the playing item takes the cursor to where it lands`() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 8_000_000))
        openThree(harness, startIndex = 2)
        harness.core.play()
        harness.run(200.milliseconds)
        val opens = harness.backend.openCalls

        harness.core.moveInQueue(from = 2, to = 0)

        assertEquals(listOf("c", "a", "b"), harness.uris())
        assertEquals(0, harness.core.snapshots.value.queueIndex)
        assertEquals("scripted://c", harness.core.snapshots.value.media?.uri, "the playing item changed")
        assertEquals(opens, harness.backend.openCalls, "a move reopened the media")
        harness.close()
    }

    @Test
    fun `moving another item over the playing one pushes the cursor along`() = runTest {
        // Playing "b" at 1. Dragging "c" from the end to the front puts one more item in front of
        // "b", so the cursor is 2. A cursor that stayed at 1 would name "a".
        val harness = CoreHarness(this, script = MediaScript(durationUs = 8_000_000))
        openThree(harness)
        harness.core.moveInQueue(from = 2, to = 0)

        assertEquals(listOf("c", "a", "b"), harness.uris())
        assertEquals(2, harness.core.snapshots.value.queueIndex)
        assertEquals("scripted://b", harness.core.snapshots.value.media?.uri)
        harness.close()
    }

    @Test
    fun `moving another item from in front to behind pulls the cursor back`() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 8_000_000))
        openThree(harness)
        harness.core.moveInQueue(from = 0, to = 2)

        assertEquals(listOf("b", "c", "a"), harness.uris())
        assertEquals(0, harness.core.snapshots.value.queueIndex)
        assertEquals("scripted://b", harness.core.snapshots.value.media?.uri)
        harness.close()
    }

    @Test
    fun `a move that does not cross the playing item leaves the cursor alone`() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 8_000_000))
        harness.attachRenderer()
        harness.core.openQueue(items("a", "b", "c", "d"), startIndex = 1)
        harness.core.moveInQueue(from = 2, to = 3)

        assertEquals(listOf("a", "b", "d", "c"), harness.uris())
        assertEquals(1, harness.core.snapshots.value.queueIndex)
        harness.close()
    }

    // -------------------------------------------------------------------------------------------
    // Removing.
    // -------------------------------------------------------------------------------------------

    @Test
    fun `removing an item in front of the playing one pulls the cursor back`() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 8_000_000))
        openThree(harness)
        harness.core.play()
        harness.run(200.milliseconds)
        val opens = harness.backend.openCalls

        harness.core.removeFromQueue(0)

        assertEquals(listOf("b", "c"), harness.uris())
        assertEquals(0, harness.core.snapshots.value.queueIndex)
        assertEquals("scripted://b", harness.core.snapshots.value.media?.uri, "the playing item changed")
        assertEquals(opens, harness.backend.openCalls, "removing another item reopened the media")
        harness.close()
    }

    @Test
    fun `removing the playing item opens the one behind it and keeps playing`() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 8_000_000))
        openThree(harness)
        harness.core.play()
        harness.run(200.milliseconds)
        val opens = harness.backend.openCalls

        harness.core.removeFromQueue(1)
        harness.run(300.milliseconds)

        assertEquals(listOf("a", "c"), harness.uris())
        assertEquals(1, harness.core.snapshots.value.queueIndex)
        assertEquals("scripted://c", harness.core.snapshots.value.media?.uri, "the item behind it did not open")
        assertEquals(opens + 1, harness.backend.openCalls, "the replacement was never opened")
        assertTrue(
            harness.core.snapshots.value.status.isActive,
            "a playing queue stopped playing across the removal, status is ${harness.core.snapshots.value.status}",
        )
        harness.close()
    }

    @Test
    fun `removing the playing last item stops the player and leaves the rest of the queue`() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 8_000_000))
        openThree(harness, startIndex = 2)
        harness.core.play()
        harness.run(200.milliseconds)

        harness.core.removeFromQueue(2)
        harness.run(200.milliseconds)

        assertEquals(PlaybackStatus.Idle, harness.core.snapshots.value.status, "nothing followed it, so it stops")
        assertEquals(listOf("a", "b"), harness.uris(), "the rest of the queue was thrown away with it")
        assertEquals(1, harness.core.snapshots.value.queueIndex, "the cursor left the queue it is still inside")
        harness.close()
    }

    @Test
    fun `removing the playing last item wraps under LoopMode All`() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 8_000_000))
        openThree(harness, startIndex = 2)
        harness.core.setLoop(LoopMode.All)
        harness.core.play()
        harness.run(200.milliseconds)

        harness.core.removeFromQueue(2)
        harness.run(300.milliseconds)

        assertEquals(0, harness.core.snapshots.value.queueIndex)
        assertEquals("scripted://a", harness.core.snapshots.value.media?.uri, "All did not make the ends meet")
        harness.close()
    }

    @Test
    fun `removing the only item stops the player and empties the queue`() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 8_000_000))
        harness.attachRenderer()
        harness.core.openQueue(items("a"), startIndex = 0)
        harness.core.play()
        harness.run(200.milliseconds)

        harness.core.removeFromQueue(0)
        harness.run(200.milliseconds)

        assertEquals(PlaybackStatus.Idle, harness.core.snapshots.value.status)
        assertEquals(emptyList(), harness.core.snapshots.value.queue)
        assertEquals(-1, harness.core.snapshots.value.queueIndex)
        harness.close()
    }

    // -------------------------------------------------------------------------------------------
    // Clearing.
    // -------------------------------------------------------------------------------------------

    @Test
    fun `clearing leaves the playing item alone at the front`() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 8_000_000))
        openThree(harness)
        harness.core.play()
        harness.run(200.milliseconds)
        val opens = harness.backend.openCalls

        harness.core.clearQueue()

        assertEquals(listOf("b"), harness.uris())
        assertEquals(0, harness.core.snapshots.value.queueIndex)
        assertEquals("scripted://b", harness.core.snapshots.value.media?.uri)
        assertEquals(opens, harness.backend.openCalls, "clearing reopened the media")
        assertTrue(harness.core.snapshots.value.status.isActive, "clearing stopped playback")
        harness.close()
    }

    // -------------------------------------------------------------------------------------------
    // A plain open, and the refusals.
    // -------------------------------------------------------------------------------------------

    @Test
    fun `a plain open is a queue of one and an edit writes that queue down`() = runTest {
        // Nothing about a single open item says it cannot become a queue. Refusing here would make
        // an application call openQueue with the item it is already playing just to add a second
        // one, which reopens what is on screen for no reason.
        val harness = CoreHarness(this, script = MediaScript(durationUs = 8_000_000))
        harness.openWithRenderer("scripted://only")
        harness.core.play()
        harness.run(200.milliseconds)
        val opens = harness.backend.openCalls
        assertEquals(-1, harness.core.snapshots.value.queueIndex, "an unedited plain open reports no queue")

        harness.core.addToQueue(items("second"))

        assertEquals(2, harness.core.snapshots.value.queue.size)
        assertEquals(0, harness.core.snapshots.value.queueIndex)
        assertEquals("scripted://only", harness.core.snapshots.value.media?.uri)
        assertEquals(opens, harness.backend.openCalls, "the edit reopened the playing item")

        harness.core.queueNext()
        harness.run(200.milliseconds)
        assertEquals("scripted://second", harness.core.snapshots.value.media?.uri, "the new item never became reachable")
        harness.close()
    }

    @Test
    fun `an edit with nothing open refuses`() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 8_000_000))
        assertFailsWith<IllegalStateException> { harness.core.addToQueue(items("a")) }
        assertFailsWith<IllegalStateException> { harness.core.removeFromQueue(0) }
        assertFailsWith<IllegalStateException> { harness.core.moveInQueue(0, 0) }
        assertFailsWith<IllegalStateException> { harness.core.clearQueue() }
        harness.close()
    }

    @Test
    fun `an index outside the queue refuses`() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 8_000_000))
        openThree(harness)

        assertFailsWith<IllegalArgumentException> { harness.core.addToQueue(items("x"), index = 4) }
        assertFailsWith<IllegalArgumentException> { harness.core.addToQueue(items("x"), index = -1) }
        assertFailsWith<IllegalArgumentException> { harness.core.addToQueue(emptyList()) }
        assertFailsWith<IllegalArgumentException> { harness.core.removeFromQueue(3) }
        assertFailsWith<IllegalArgumentException> { harness.core.removeFromQueue(-1) }
        assertFailsWith<IllegalArgumentException> { harness.core.moveInQueue(0, 3) }
        assertFailsWith<IllegalArgumentException> { harness.core.moveInQueue(3, 0) }

        // An insert may sit one past the end, because that is what appending means.
        harness.core.addToQueue(items("x"), index = 3)
        assertEquals(listOf("a", "b", "c", "x"), harness.uris())

        assertEquals(1, harness.core.snapshots.value.queueIndex, "a refused edit moved the cursor")
        harness.close()
    }

    @Test
    fun `a refused edit leaves the queue exactly as it was`() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 8_000_000))
        openThree(harness)
        val before = harness.uris()

        assertFailsWith<IllegalArgumentException> { harness.core.removeFromQueue(9) }
        harness.run(200.milliseconds)

        assertEquals(before, harness.uris())
        assertEquals(1, harness.core.snapshots.value.queueIndex)
        harness.close()
    }

    @Test
    fun `the queue keeps playing through a batch of edits`() = runTest {
        // The point of the whole family: a playing queue survives being rearranged around the
        // item that is playing.
        val harness = CoreHarness(this, script = MediaScript(durationUs = 20_000_000))
        harness.attachRenderer()
        harness.core.openQueue(items("a", "b", "c", "d"), startIndex = 2)
        harness.core.play()
        harness.run(400.milliseconds)
        val opens = harness.backend.openCalls

        harness.core.addToQueue(items("x"), index = 0)
        harness.core.moveInQueue(from = 4, to = 1)
        harness.core.removeFromQueue(0)
        harness.core.addToQueue(items("y"))
        harness.run(1.seconds)

        assertEquals("scripted://c", harness.core.snapshots.value.media?.uri, "the playing item was lost")
        assertEquals(
            "scripted://c",
            harness.core.snapshots.value.queue[harness.core.snapshots.value.queueIndex].uri,
            "the cursor stopped naming the playing item",
        )
        assertEquals(opens, harness.backend.openCalls, "an edit that touched nothing playing reopened the media")
        assertTrue(harness.core.snapshots.value.status.isActive, "playback stopped somewhere in the batch")
        harness.close()
    }
}
