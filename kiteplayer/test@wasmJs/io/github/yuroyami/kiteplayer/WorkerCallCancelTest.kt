@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlin.coroutines.Continuation
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A worker that keeps every message the page posts to it, and answers nothing by itself. */
@JsFun("() => ({ posted: [], postMessage: function (m) { this.posted.push(m); }, terminate: function () {} })")
private external fun recordingWorker(): JsAny

@JsFun("(w) => w.posted.length")
private external fun postedCount(worker: JsAny): Int

@JsFun("(w, i) => w.posted[i]")
private external fun posted(worker: JsAny, index: Int): JsAny?

/**
 * A worker player call whose caller is cancelled is cancelled in the worker too (#893). The page
 * stopped waiting and told the worker nothing, so an open went on there, and the player that was
 * to be Idle reached Paused with the item.
 */
class WorkerCallCancelTest {

    private fun messages(worker: JsAny): List<PageMessage?> = (0 until postedCount(worker)).map { decodePageMessage(posted(worker, it)) }

    @Test
    fun aCancelledOpenTellsTheWorkerToCancelThatCall() {
        val worker = recordingWorker()
        val player = KitePlayerWorker.onWorkerForTest(worker)
        val caller = Job()
        var outcome: Result<Unit>? = null
        val open: suspend () -> Unit = { player.open(MediaItem("https://media.test/slow.mp4")) }
        open.startCoroutine(Continuation(caller) { outcome = it })
        val call = assertIs<PageMessage.Call>(messages(worker).single())
        assertIs<Command.Open>(call.command)

        caller.cancel()
        assertTrue(outcome?.exceptionOrNull() is CancellationException, "the open ended with $outcome")
        assertEquals(PageMessage.Cancel(call.id), messages(worker).last())

        // A reply that comes after all the same finds nobody waiting, and nothing else is sent.
        player.receive(WorkerMessage.Reply(call.id).encode())
        assertEquals(2, postedCount(worker))
    }

    @Test
    fun aCallThatWasAnsweredSendsNoCancel() {
        val worker = recordingWorker()
        val player = KitePlayerWorker.onWorkerForTest(worker)
        val caller = Job()
        var outcome: Result<Unit>? = null
        val stop: suspend () -> Unit = { player.stop() }
        stop.startCoroutine(Continuation(caller) { outcome = it })
        val call = assertIs<PageMessage.Call>(messages(worker).single())
        player.receive(WorkerMessage.Reply(call.id).encode())
        assertTrue(outcome!!.isSuccess, "$outcome")
        caller.cancel()
        assertEquals(1, postedCount(worker))
    }
}
