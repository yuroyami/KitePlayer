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
 * One worker's context, and how to give its thread back.
 *
 * The pair exists because the type a platform's dispatcher factory returns is not the same type on every
 * platform, while "close this when the player closes" is the same idea everywhere. Naming the shutdown
 * explicitly also makes it impossible to build a set whose threads nothing releases.
 */
internal class WorkerContext(val context: CoroutineContext, private val shutdown: () -> Unit) {
    fun close(): Unit = shutdown()
}

/**
 * One context per worker, built by [create], released together.
 *
 * The names are passed through to the platform so a thread dump reads as the pipeline rather than as six
 * anonymous workers. Diagnosing a stalled player starts with knowing which of them is not running.
 */
internal class PerWorkerDispatchers(create: (String) -> WorkerContext) : PlaybackDispatchers {

    private val sessionWorker = create("kiteplayer-session")
    private val demuxWorker = create("kiteplayer-demux")
    private val videoDecodeWorker = create("kiteplayer-video-decode")
    private val audioDecodeWorker = create("kiteplayer-audio-decode")
    private val audioFeedWorker = create("kiteplayer-audio-feed")
    private val videoScheduleWorker = create("kiteplayer-video-schedule")

    override val session: CoroutineContext get() = sessionWorker.context
    override val demux: CoroutineContext get() = demuxWorker.context
    override val videoDecode: CoroutineContext get() = videoDecodeWorker.context
    override val audioDecode: CoroutineContext get() = audioDecodeWorker.context
    override val audioFeed: CoroutineContext get() = audioFeedWorker.context
    override val videoSchedule: CoroutineContext get() = videoScheduleWorker.context

    override fun close() {
        sessionWorker.close()
        demuxWorker.close()
        videoDecodeWorker.close()
        audioDecodeWorker.close()
        audioFeedWorker.close()
        videoScheduleWorker.close()
    }
}
