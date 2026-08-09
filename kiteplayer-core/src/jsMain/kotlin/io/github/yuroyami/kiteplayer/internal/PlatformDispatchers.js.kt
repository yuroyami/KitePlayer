package io.github.yuroyami.kiteplayer.internal

import kotlinx.coroutines.Dispatchers

/**
 * Every worker on the one thread this runtime has.
 *
 * Not a compromise here. The engine's rule is that a decoder context, a demuxer cursor and the audio
 * ring's producer are each touched by one thread at a time, and a JavaScript runtime has one thread to
 * offer, so the rule holds by construction. The workers still interleave at their suspension points,
 * which is all the concurrency the engine asks for.
 */
internal actual fun platformPlaybackDispatchers(): PlaybackDispatchers =
    PlaybackDispatchers.sharing(Dispatchers.Default)
