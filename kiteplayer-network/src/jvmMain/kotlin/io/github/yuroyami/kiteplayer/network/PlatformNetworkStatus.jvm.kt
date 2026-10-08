package io.github.yuroyami.kiteplayer.network

import io.github.yuroyami.kiteplayer.NetworkStatus
import java.net.NetworkInterface
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal actual fun platformNetworkStatus(): NetworkStatus? = InterfaceNetworkStatus()

/**
 * The JVM's network status (#461): a look at the network interfaces every [interval] while it is
 * watched, on a thread of its own. A network is an interface that is up, is not the loopback and
 * has an address. A look that fails says there is one, so it never holds an attempt back.
 */
internal class InterfaceNetworkStatus(
    private val interval: Duration = 2.seconds,
    private val online: () -> Boolean = ::anInterfaceIsUp,
) : NetworkStatus {
    override fun watch(onChange: (online: Boolean) -> Unit): AutoCloseable {
        val looker = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "kiteplayer-network-status").apply { isDaemon = true }
        }
        // Read and written on the looker's one thread only.
        var last: Boolean? = null
        looker.scheduleWithFixedDelay(
            {
                val now = runCatching(online).getOrDefault(true)
                if (now != last) {
                    last = now
                    onChange(now)
                }
            },
            0,
            interval.inWholeMilliseconds,
            TimeUnit.MILLISECONDS,
        )
        return AutoCloseable { looker.shutdownNow() }
    }
}

internal fun anInterfaceIsUp(): Boolean =
    NetworkInterface.getNetworkInterfaces()?.asSequence().orEmpty()
        .any { it.isUp && !it.isLoopback && it.inetAddresses.hasMoreElements() }
