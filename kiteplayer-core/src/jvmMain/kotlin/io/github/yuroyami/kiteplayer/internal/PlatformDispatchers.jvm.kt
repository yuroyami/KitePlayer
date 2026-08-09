@file:OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)

package io.github.yuroyami.kiteplayer.internal

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.newSingleThreadContext

/**
 * One real thread per worker, named so a thread dump reads as the pipeline.
 *
 * Same shape as every other threaded target: the engine's contracts are about confinement, and a JVM
 * decoder backend will want it for exactly the reasons a native one does.
 */
internal actual fun platformPlaybackDispatchers(): PlaybackDispatchers = PerWorkerDispatchers { name ->
    val dispatcher = newSingleThreadContext(name)
    WorkerContext(dispatcher) { dispatcher.close() }
}
