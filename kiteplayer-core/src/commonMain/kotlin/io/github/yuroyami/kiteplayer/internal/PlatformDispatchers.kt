package io.github.yuroyami.kiteplayer.internal

import kotlin.coroutines.CoroutineContext

/**
 * The dispatcher set a player builds for itself when nobody hands it one.
 *
 * [PlaybackDispatchers] documents why each worker wants a context that runs one thing at a time, and why
 * common code does not choose the pools behind them: the pools, and whether there is more than one thread
 * at all, differ by target. So the construction is the one platform-dependent step in the engine, and it
 * is this function. Everything
 * else in `commonMain` stays free of platform API, which is what keeps the whole engine testable in
 * virtual time.
 *
 * A target with real threads answers with [SharedLaneDispatchers], one serial lane per worker over the
 * shared pools. A single-threaded runtime answers with its one dispatcher for all of them, which is not a compromise there: there is no second thread to
 * confine anything to, and the engine's rule is confinement rather than parallelism.
 */
internal expect fun platformPlaybackDispatchers(): PlaybackDispatchers

/**
 * Where work that blocks its thread runs when it belongs to no player lane, such as an inspect.
 * The shared IO pool on a threaded target, and the one thread on the web, which refuses blocking.
 */
internal expect val blockingWorkDispatcher: kotlinx.coroutines.CoroutineDispatcher

/**
 * Eight SERIAL LANES over the runtime's shared pools instead of eight owned OS threads: the session
 * actor, the video scheduler, demux, video decode, audio decode, audio feed, subtitle raster and
 * release.
 *
 * The engine's contracts are about CONFINEMENT, and `limitedParallelism(1)` is confinement:
 * one task at a time per lane, happens-before between consecutive tasks, exactly the mutual
 * exclusion the per-worker threads provided, without one player costing eight threads and six
 * players costing forty-eight. A lane may run on a different pool thread from one task to the
 * next, so nothing in the engine may rely on thread identity or thread-local state. The split is by BEHAVIOUR: lanes that only suspend (the video
 * scheduler, the raster lane) ride [calm], the pool for computation; lanes that can BLOCK ride
 * [blocking], the pool built for exactly that, so a stall parks an elastic IO thread and never
 * starves computation. The session actor rides [blocking] too: its teardown
 * and seek paths call `sink.stop()`, and on Android that joins the writer thread, a real block
 * that on a two-core Default pool could sit on half the computation budget.
 *
 * The one pinned thread the platform genuinely demands, the audio DEVICE callback, was never
 * one of these eight: the platform output owns it, and the ring is all it touches.
 *
 * close() releases nothing because nothing here is owned; the pools are the runtime's.
 */
internal class SharedLaneDispatchers(
    calm: kotlinx.coroutines.CoroutineDispatcher,
    blocking: kotlinx.coroutines.CoroutineDispatcher,
) : PlaybackDispatchers {

    @kotlinx.coroutines.ExperimentalCoroutinesApi
    override val session: CoroutineContext = blocking.limitedParallelism(1)

    @kotlinx.coroutines.ExperimentalCoroutinesApi
    override val videoSchedule: CoroutineContext = calm.limitedParallelism(1)

    @kotlinx.coroutines.ExperimentalCoroutinesApi
    override val demux: CoroutineContext = blocking.limitedParallelism(1)

    @kotlinx.coroutines.ExperimentalCoroutinesApi
    override val videoDecode: CoroutineContext = blocking.limitedParallelism(1)

    @kotlinx.coroutines.ExperimentalCoroutinesApi
    override val audioDecode: CoroutineContext = blocking.limitedParallelism(1)

    @kotlinx.coroutines.ExperimentalCoroutinesApi
    override val audioFeed: CoroutineContext = blocking.limitedParallelism(1)

    @kotlinx.coroutines.ExperimentalCoroutinesApi
    override val raster: CoroutineContext = calm.limitedParallelism(1)

    // Blocking, because a session release is nothing but blocking native closes, and its OWN lane
    // so that a close which wedges cannot also park the actor that is timing it.
    @kotlinx.coroutines.ExperimentalCoroutinesApi
    override val release: CoroutineContext = blocking.limitedParallelism(1)

    override fun close() {}
}
