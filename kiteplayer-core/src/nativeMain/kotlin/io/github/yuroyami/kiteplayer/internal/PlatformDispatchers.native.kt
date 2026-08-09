@file:OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)

package io.github.yuroyami.kiteplayer.internal

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.newSingleThreadContext

/**
 * One real thread per worker.
 *
 * This is the shape every contract in the engine is written against: a decoding context touched by
 * exactly one thread, a demuxer cursor read by exactly one, and an audio ring with one producer. The
 * threads are released by [PerWorkerDispatchers.close], which the player's own close calls.
 */
internal actual fun platformPlaybackDispatchers(): PlaybackDispatchers = PerWorkerDispatchers { name ->
    val dispatcher = newSingleThreadContext(name)
    WorkerContext(dispatcher) { dispatcher.close() }
}
