@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.yuroyami.kiteplayer.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The web's network status (#461), with an event target standing in for the page's own, because
 * Node raises no `online` or `offline` events itself. The same tests run in a browser, which does.
 */
class OnlineEventsNetworkStatusTest {

    @Test
    fun aStatusExistsOnlyWhereOnlineEventsDo() {
        if (hasPageEvents()) assertNotNull(platformNetworkStatus()) else assertNull(platformNetworkStatus())
    }

    @Test
    fun theStatusFollowsTheOnlineAndOfflineEventsUntilItCloses() {
        installEventTarget()
        try {
            val status = assertNotNull(platformNetworkStatus())
            val seen = mutableListOf<Boolean>()
            val watch = status.watch { seen += it }
            assertEquals(listOf(true), seen, "the network now")
            fire("offline")
            fire("online")
            assertEquals(listOf(true, false, true), seen)
            watch.close()
            fire("offline")
            assertEquals(listOf(true, false, true), seen, "nothing once closed")
        } finally {
            removeEventTarget()
        }
    }
}

private fun hasPageEvents(): Boolean = js("typeof globalThis.addEventListener === 'function'")

private fun installEventTarget(): Unit = js(
    """{
        const target = new EventTarget();
        globalThis.addEventListener = target.addEventListener.bind(target);
        globalThis.removeEventListener = target.removeEventListener.bind(target);
        globalThis.dispatchEvent = target.dispatchEvent.bind(target);
    }""",
)

private fun fire(name: String): Unit = js("{ globalThis.dispatchEvent(new Event(name)); }")

private fun removeEventTarget(): Unit = js(
    """{
        delete globalThis.addEventListener;
        delete globalThis.removeEventListener;
        delete globalThis.dispatchEvent;
    }""",
)
