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
        if (js("typeof globalThis.addEventListener === 'function'") as Boolean) assertNotNull(platformNetworkStatus()) else assertNull(platformNetworkStatus())
    }

    @Test
    fun theStatusFollowsTheOnlineAndOfflineEventsUntilItCloses() {
        js(
            """
            const target = new EventTarget();
            globalThis.addEventListener = target.addEventListener.bind(target);
            globalThis.removeEventListener = target.removeEventListener.bind(target);
            globalThis.dispatchEvent = target.dispatchEvent.bind(target);
            """,
        )
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
            js(
                """
                delete globalThis.addEventListener;
                delete globalThis.removeEventListener;
                delete globalThis.dispatchEvent;
                """,
            )
        }
    }

    private fun fire(name: String) {
        val global: dynamic = js("globalThis")
        global.dispatchEvent(js("new Event(name)"))
    }
}
