package io.github.yuroyami.kiteplayer.network

import io.github.yuroyami.kiteplayer.NetworkStatus

// None yet, so the player's timer alone tries (#461). NWPathMonitor is the plan, and it needs a
// Mac to build and prove.
internal actual fun platformNetworkStatus(): NetworkStatus? = null
