package io.github.yuroyami.kiteplayer.network

import io.github.yuroyami.kiteplayer.NetworkStatus

internal actual fun platformNetworkStatus(): NetworkStatus? =
    if (js("typeof globalThis.addEventListener === 'function'") as Boolean) OnlineEventsNetworkStatus else null

/**
 * The web's network status (#461): the `online` and `offline` events of the page or the worker,
 * starting from `navigator.onLine`. Node has neither, so there is none there.
 */
private object OnlineEventsNetworkStatus : NetworkStatus {
    override fun watch(onChange: (online: Boolean) -> Unit): AutoCloseable {
        val global: dynamic = js("globalThis")
        val on: (dynamic) -> Unit = { onChange(true) }
        val off: (dynamic) -> Unit = { onChange(false) }
        global.addEventListener("online", on)
        global.addEventListener("offline", off)
        onChange(js("!(globalThis.navigator && globalThis.navigator.onLine === false)") as Boolean)
        return AutoCloseable {
            global.removeEventListener("online", on)
            global.removeEventListener("offline", off)
        }
    }
}
