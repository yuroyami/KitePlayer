@file:OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)

package io.github.yuroyami.kiteplayer.internal

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.newSingleThreadContext

/**
 * One real thread per worker.
 *
 * The same answer as the JVM's, and separate from it because this target has no shared source set with
 * it. Android's main thread is never one of these: nothing in the engine touches it, and a renderer that
 * needs it hops there itself.
 */
internal actual fun platformPlaybackDispatchers(): PlaybackDispatchers = PerWorkerDispatchers { name ->
    val dispatcher = newSingleThreadContext(name)
    WorkerContext(dispatcher) { dispatcher.close() }
}
