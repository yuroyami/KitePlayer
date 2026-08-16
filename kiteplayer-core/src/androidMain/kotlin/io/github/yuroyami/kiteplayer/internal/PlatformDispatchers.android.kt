@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.yuroyami.kiteplayer.internal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi

/**
 * SOL-P4: serial lanes over the shared pools. Suspend-only lanes ride Default; the lanes that
 * enter blocking C or a blocking bridge ride IO, whose whole purpose is parked threads. The
 * confinement contract is [SharedLaneDispatchers]'s, identical on every threaded target.
 */
internal actual fun platformPlaybackDispatchers(): PlaybackDispatchers =
    SharedLaneDispatchers(calm = Dispatchers.Default, blocking = Dispatchers.IO)
