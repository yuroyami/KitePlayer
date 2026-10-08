package io.github.yuroyami.kiteplayer.network

import io.github.yuroyami.kiteplayer.NetworkStatus

/**
 * Whether the device has a network, as this platform tells it, for the player's wait after the
 * network failed an item (#461), or null where the platform cannot tell, and the player's timer
 * alone tries. See `docs/cancellation-and-bounded-waits.md`.
 */
internal expect fun platformNetworkStatus(): NetworkStatus?
