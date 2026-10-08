package io.github.yuroyami.kiteplayer.network

import io.github.yuroyami.kiteplayer.NetworkStatus
import platform.Network.nw_path_get_status
import platform.Network.nw_path_monitor_cancel
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_status_satisfiable
import platform.Network.nw_path_status_satisfied
import platform.Network.nw_path_status_t
import platform.darwin.dispatch_queue_create
import kotlin.concurrent.AtomicInt

internal actual fun platformNetworkStatus(): NetworkStatus? = PathMonitorNetworkStatus

/**
 * Apple's network status (#461): the system's path monitor, which reports the path to the network
 * now and again at each change, on a queue of its own. A network is a path that is satisfied, or
 * one the system can bring up when a connection asks for it, such as a VPN on demand.
 */
internal object PathMonitorNetworkStatus : NetworkStatus {
    override fun watch(onChange: (online: Boolean) -> Unit): AutoCloseable {
        val reports = PathReports(onChange)
        val monitor = nw_path_monitor_create()
        nw_path_monitor_set_queue(monitor, dispatch_queue_create("kiteplayer-network-status", null))
        nw_path_monitor_set_update_handler(monitor) { path -> reports.report(nw_path_get_status(path)) }
        nw_path_monitor_start(monitor)
        return AutoCloseable {
            reports.close()
            nw_path_monitor_cancel(monitor)
        }
    }
}

/**
 * Turns each path the monitor reports into a call of [onChange], when it changes and until closed.
 * The monitor reports on its one queue, so [report] has one caller at a time.
 */
internal class PathReports(private val onChange: (online: Boolean) -> Unit) {
    private var last: Boolean? = null
    private val closed = AtomicInt(0)

    fun report(status: nw_path_status_t) {
        val online = status == nw_path_status_satisfied || status == nw_path_status_satisfiable
        // A cancelled monitor can still deliver a path that was already on its queue.
        if (closed.value != 0 || online == last) return
        last = online
        onChange(online)
    }

    fun close() {
        closed.value = 1
    }
}
