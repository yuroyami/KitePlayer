@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yuroyami.kiteplayer.output

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.js.JsAny
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A wait on a browser promise ends when its caller is cancelled (#479). `resume()` on a page nobody
 * has touched may stay pending, and a setup that settles after its caller left builds a device
 * nobody owns, which the wait closes.
 */
class AwaitJsTest {

    @Test
    fun aPromiseThatNeverSettlesDoesNotHoldACancelledWait() = runTest {
        val waiting = launch { awaitJs(neverSettles()) }
        runCurrent()
        assertTrue(waiting.isActive, "the wait should still be waiting")
        waiting.cancel()
        runCurrent()
        assertTrue(waiting.isCompleted, "a cancelled wait on a pending promise did not end")
    }

    @Test
    fun whatAPromiseFulfilsWithAfterItsCallerLeftIsDiscarded() = runTest {
        val pending = settleLater()
        var discarded: String? = null
        val waiting = launch { awaitJs(promiseOf(pending), discardLate = { discarded = textOf(it) }) }
        runCurrent()
        waiting.cancelAndJoin()
        fulfil(pending, "a device nobody owns")
        // Promise callbacks run on the page's own queue, so the test steps off its virtual clock.
        withContext(Dispatchers.Default) { delay(10) }
        assertEquals("a device nobody owns", discarded)
    }

    @Test
    fun aValueThatArrivesInTimeIsReturnedAndNothingIsDiscarded() = runTest {
        val pending = settleLater()
        var discarded: String? = null
        val waiting = launch {
            assertEquals("the device", awaitJs(promiseOf(pending), discardLate = { discarded = textOf(it) })?.let(::textOf))
        }
        runCurrent()
        fulfil(pending, "the device")
        withContext(Dispatchers.Default) { delay(10) }
        waiting.join()
        assertNull(discarded)
    }
}

@JsFun("() => new Promise(() => {})")
private external fun neverSettles(): Promise<JsAny?>

@JsFun("() => { const out = {}; out.promise = new Promise((resolve) => { out.resolve = resolve; }); return out; }")
private external fun settleLater(): JsAny

@JsFun("(pending) => pending.promise")
private external fun promiseOf(pending: JsAny): Promise<JsAny?>

@JsFun("(pending, text) => pending.resolve(text)")
private external fun fulfil(pending: JsAny, text: String)

@JsFun("(value) => String(value)")
private external fun textOf(value: JsAny): String
