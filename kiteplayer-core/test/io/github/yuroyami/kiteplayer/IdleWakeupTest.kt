@file:OptIn(ExperimentalCoroutinesApi::class, InternalCoroutinesApi::class)

package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Delay
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * How often the engine arms a timer, counted in virtual time.
 *
 * Every armed timer is a wake-up. On a phone a short poll keeps cores out of idle, and a paused
 * player should cost next to nothing. This counts every timer the engine's workers arm, by the wait
 * they asked for, while playing and while paused.
 */
class IdleWakeupTest {

    /** The test dispatcher, counting each timer it is asked to arm. */
    private class CountingDispatcher(private val inner: TestDispatcher) : CoroutineDispatcher(), Delay {
        val waits = HashMap<Long, Int>()
        val total: Int get() = waits.values.sum()

        override fun dispatch(context: CoroutineContext, block: Runnable) = inner.dispatch(context, block)
        override fun isDispatchNeeded(context: CoroutineContext): Boolean = inner.isDispatchNeeded(context)

        override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) {
            count(timeMillis)
            (inner as Delay).scheduleResumeAfterDelay(timeMillis, continuation)
        }

        override fun invokeOnTimeout(timeMillis: Long, block: Runnable, context: CoroutineContext): DisposableHandle {
            count(timeMillis)
            return (inner as Delay).invokeOnTimeout(timeMillis, block, context)
        }

        private fun count(timeMillis: Long) {
            waits[timeMillis] = (waits[timeMillis] ?: 0) + 1
        }

        /** Each requested wait with how many times it was armed, shortest first. */
        fun describe(): String = waits.entries.sortedBy { it.key }.joinToString { "${it.key} ms x ${it.value}" }
    }

    @Test
    fun `a paused player arms only a few timers a second`() = runTest {
        var counting: CountingDispatcher? = null
        val harness = CoreHarness(
            this,
            script = MediaScript(durationUs = 20_000_000),
            engineDispatcher = { scheduler -> CountingDispatcher(StandardTestDispatcher(scheduler)).also { counting = it } },
        )
        val timers = checkNotNull(counting)
        harness.openWithRenderer()
        harness.core.play()
        harness.run(2.seconds)

        timers.waits.clear()
        harness.run(1.seconds)
        val playing = timers.total
        val playingWaits = timers.describe()

        harness.core.pause()
        harness.run(500.milliseconds)
        timers.waits.clear()
        harness.run(1.seconds)
        val paused = timers.total
        val pausedWaits = timers.describe()

        println("timers armed in one virtual second, playing: $playing $playingWaits")
        println("timers armed in one virtual second, paused: $paused $pausedWaits")
        assertTrue(paused <= PAUSED_MOST, "a paused second armed $paused timers: $pausedWaits")
        assertTrue(playing <= PLAYING_MOST, "a playing second armed $playing timers: $playingWaits")
        harness.close()
    }

    private companion object {
        /**
         * 120 measured, every one a 50 ms poll. It was 780 when the audio feeder polled a full ring
         * every 2 ms and the video handover every 5 ms (#245), and either poll alone breaks this.
         */
        const val PAUSED_MOST = 150

        /**
         * 311 measured: the session's 5 ms frame wake, the frame timer and the 50 ms polls. It was
         * 996 with the two polls above, and the 2 ms feeder poll alone adds about 500.
         */
        const val PLAYING_MOST = 400
    }
}
