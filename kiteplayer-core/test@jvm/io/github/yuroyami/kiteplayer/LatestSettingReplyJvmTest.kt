@file:OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.PlaybackCore
import io.github.yuroyami.kiteplayer.internal.PlaybackDispatchers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Every call of a latest-value setting returns, however the calls of two threads meet (#593).
 *
 * A newer setting replaces an older one that is still waiting and answers it when it is answered.
 * The put and the take-over were two sections of one lock, so an actor that took the newer
 * command between them answered an empty list, and the older call waited for ever. The window is
 * a few instructions wide, so this hammers it from two threads with the actor on a real thread.
 */
class LatestSettingReplyJvmTest {

    private class ActorOnItsOwnThread : PlaybackDispatchers {
        private val sessionContext = newSingleThreadContext("latest-session")
        override val session: CoroutineContext get() = sessionContext
        override val demux: CoroutineContext get() = sessionContext
        override val videoDecode: CoroutineContext get() = sessionContext
        override val audioDecode: CoroutineContext get() = sessionContext
        override val audioFeed: CoroutineContext get() = sessionContext
        override val videoSchedule: CoroutineContext get() = sessionContext
        override val raster: CoroutineContext get() = sessionContext
        override fun close() = sessionContext.close()
    }

    @Test
    fun twoThreadsSettingTheVolumeAtOnceAreEveryOneAnswered(): Unit = runBlocking {
        val dispatchers = ActorOnItsOwnThread()
        val core = PlaybackCore(
            config = PlayerConfig(),
            backend = ScriptedBackend(MediaScript(), LeakLedger()),
            output = ScriptedOutput(MonotonicClock.System, ScriptedSink()),
            dispatchers = dispatchers,
            closeDispatchers = false,
        )
        try {
            withTimeout(60.seconds) {
                (0 until 2).map { thread ->
                    async(Dispatchers.Default) {
                        repeat(CALLS_PER_THREAD) { call -> core.setVolume(if ((call + thread) % 2 == 0) 0.5f else 0.75f) }
                    }
                }.awaitAll()
            }
        } finally {
            core.closeAndAwait()
            dispatchers.close()
        }
    }

    private companion object {
        const val CALLS_PER_THREAD = 50_000
    }
}
