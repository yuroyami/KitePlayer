@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.yuroyami.kiteplayer

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** A worker that answers nothing by itself: the test plays its replies and its states. */
@JsFun("() => ({ postMessage: function () {}, terminate: function () {} })")
private external fun silentWorker(): JsAny

/**
 * The page's map from each item it sent to the worker back to the caller's own holds only what
 * the worker still holds (#687). It kept every item a long radio page had ever queued.
 */
class WorkerItemMapTest {

    /** A call in flight. With no dispatcher it ends as soon as the test hands it its reply. */
    private class Running {
        var result: Result<Unit>? = null
    }

    private fun start(call: suspend () -> Unit): Running {
        val running = Running()
        call.startCoroutine(Continuation(EmptyCoroutineContext) { running.result = it })
        return running
    }

    private fun item(n: Int) = MediaItem("https://radio.test/track-$n.mp3")

    private fun state(media: MediaItem?, queue: List<MediaItem>) =
        WorkerMessage.State(PlayerSnapshot(media = media, queue = queue)).encode()

    @Test
    fun aQueueThatStaysShortKeepsAShortMap() {
        val player = KitePlayerWorker.onWorkerForTest(silentWorker())
        var nextId = 1
        fun answered(call: suspend () -> Unit) {
            val running = start(call)
            player.receive(WorkerMessage.Reply(nextId++).encode())
            assertTrue(running.result!!.isSuccess, "${running.result}")
        }

        val first = item(0)
        answered { player.openQueue(listOf(first)) }
        player.receive(state(first, listOf(first)))
        var playing = first
        repeat(1_000) { n ->
            val next = item(n + 1)
            answered { player.addToQueue(next) }
            player.receive(state(playing, listOf(playing, next)))
            answered { player.removeFromQueue(0) }
            player.receive(state(next, listOf(next)))
            playing = next
        }
        assertEquals(1, player.retainedItemCount, "the map kept items the worker let go")
        assertSame(playing, player.state.value.queue.single(), "what the worker holds is still the caller's own object")

        // A refused add takes its own entries back.
        val refused = start { player.addToQueue(item(-1), index = 99) }
        player.receive(WorkerMessage.Reply(nextId++, failure = Failure.Argument("index 99 is outside the queue")).encode())
        assertIs<IllegalArgumentException>(refused.result!!.exceptionOrNull())
        assertEquals(1, player.retainedItemCount, "a refused add stayed in the map")

        // The end of the player clears it.
        player.close()
        player.receive(WorkerMessage.Reply(nextId++).encode())
        assertEquals(0, player.retainedItemCount)
    }

    @Test
    fun anItemSentButNotYetShownSurvivesAStateFromBeforeIt() {
        val player = KitePlayerWorker.onWorkerForTest(silentWorker())
        val first = item(0)
        val added = item(1)
        start { player.openQueue(listOf(first)) }
        player.receive(WorkerMessage.Reply(1).encode())
        val add = start { player.addToQueue(added) }
        // A state the worker sent before it applied the add does not show the new item.
        player.receive(state(first, listOf(first)))
        player.receive(WorkerMessage.Reply(2).encode())
        assertTrue(add.result!!.isSuccess)
        assertEquals(2, player.retainedItemCount, "an item in flight was forgotten")
        player.receive(state(first, listOf(first, added)))
        assertSame(added, player.state.value.queue.last(), "the item did not come back as the caller's own object")
        player.close()
    }

    /** A call still waiting when the worker answers the close fails, rather than waiting for ever (#908). */
    @Test
    fun aCallStillWaitingAtTheCloseReplyFails() {
        val player = KitePlayerWorker.onWorkerForTest(silentWorker())
        val open = start { player.openQueue(listOf(item(0))) }
        player.close()
        // The open was call 1 and the close call 2; the worker answers only the close.
        player.receive(WorkerMessage.Reply(2).encode())
        assertIs<IllegalStateException>(open.result?.exceptionOrNull(), "the open is still waiting: ${open.result}")
    }
}
