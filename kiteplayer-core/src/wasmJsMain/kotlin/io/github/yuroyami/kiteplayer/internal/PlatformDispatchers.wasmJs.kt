package io.github.yuroyami.kiteplayer.internal

import kotlinx.coroutines.Dispatchers

/**
 * Every worker on the one thread this runtime has, for the same reason as the JavaScript target.
 *
 * Confinement holds by construction where there is only one thread, and the workers still interleave at
 * their suspension points.
 */
internal actual fun platformPlaybackDispatchers(): PlaybackDispatchers =
    PlaybackDispatchers.sharing(Dispatchers.Default)
