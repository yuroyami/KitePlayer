@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.yuroyami.kiteplayer.network

import io.github.yuroyami.kiteplayer.NetworkStatus

internal actual fun platformNetworkStatus(): NetworkStatus? = if (hasOnlineEvents()) OnlineEventsNetworkStatus else null

/**
 * The web's network status (#461): the `online` and `offline` events of the page or the worker,
 * starting from `navigator.onLine`. Node has neither, so there is none there.
 */
private object OnlineEventsNetworkStatus : NetworkStatus {
    override fun watch(onChange: (online: Boolean) -> Unit): AutoCloseable {
        val stop = listenOnline { online -> onChange(online) }
        onChange(navigatorOnline())
        return AutoCloseable { callJs(stop) }
    }
}

private fun hasOnlineEvents(): Boolean = js("typeof globalThis.addEventListener === 'function'")

private fun navigatorOnline(): Boolean = js("!(globalThis.navigator && globalThis.navigator.onLine === false)")

private fun listenOnline(onChange: (Boolean) -> Unit): JsAny = js(
    """(() => {
        const on = () => onChange(true);
        const off = () => onChange(false);
        globalThis.addEventListener('online', on);
        globalThis.addEventListener('offline', off);
        return () => {
            globalThis.removeEventListener('online', on);
            globalThis.removeEventListener('offline', off);
        };
    })()""",
)

private fun callJs(stop: JsAny): Unit = js("stop()")
