package io.github.yuroyami.kiteplayer.internal

import kotlin.coroutines.CoroutineContext

/**
 * The dispatcher set a player builds for itself when nobody hands it one.
 *
 * [PlaybackDispatchers] documents why each worker wants a thread of its own, and why common code cannot
 * build such a set: `newSingleThreadContext` does not exist on every target this module compiles for. So
 * the construction is the one platform-dependent step in the engine, and it is this function. Everything
 * else in `commonMain` stays free of platform API, which is what keeps the whole engine testable in
 * virtual time.
 *
 * A target with real threads answers with one thread per worker. A single-threaded runtime answers with
 * its one dispatcher for all of them, which is not a compromise there: there is no second thread to
 * confine anything to, and the engine's rule is confinement rather than parallelism.
 */
internal expect fun platformPlaybackDispatchers(): PlaybackDispatchers

/**
 * SOL-P4: six SERIAL LANES over the runtime's shared pools instead of six owned OS threads.
 *
 * The engine's contracts are about CONFINEMENT, and `limitedParallelism(1)` is confinement:
 * one task at a time per lane, happens-before between consecutive tasks, exactly the mutual
 * exclusion the per-worker threads provided, without a player costing six threads and six
 * players costing thirty-six. The split is by BEHAVIOUR: lanes that only suspend (the video
 * scheduler, the raster lane) ride [calm], the pool for computation; lanes that can BLOCK ride
 * [blocking], the pool built for exactly that, so a stall parks an elastic IO thread and never
 * starves computation. The session actor rides [blocking] too (audit F-LANE1): its teardown
 * and seek paths call `sink.stop()`, and on Android that joins the writer thread, a real block
 * that on a two-core Default pool could sit on half the computation budget.
 *
 * The one pinned thread the platform genuinely demands, the audio DEVICE callback, was never
 * one of these six: the C ring owns it.
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

    override fun close() {}
}
