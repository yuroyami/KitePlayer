@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.PlaybackCore
import io.github.yuroyami.kiteplayer.internal.PlaybackDispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

/**
 * A player keeps no record of its past statuses (#481). It lives as long as its application, so a
 * list that grew at every pause grew for as long as the application ran. Tests that read the
 * history ask for it, and CoreHarness does.
 */
class StatusHistoryTest {

    @Test
    fun aPlayerThatPausesAHundredTimesKeepsNoStatusHistory() = runTest {
        val clock = VirtualClock(testScheduler)
        val sink = ScriptedSink()
        val core = PlaybackCore(
            config = PlayerConfig(),
            backend = ScriptedBackend(),
            output = ScriptedOutput(clock, sink),
            dispatchers = PlaybackDispatchers.sharing(StandardTestDispatcher(testScheduler)),
            closeDispatchers = false,
            parent = backgroundScope.coroutineContext[Job],
        )
        val device = backgroundScope.launch { sink.runDevice(clock) }
        core.open(MediaItem("scripted://media"))
        repeat(100) {
            core.play()
            delay(20)
            core.pause()
            delay(20)
        }
        assertEquals(PlaybackStatus.Paused, core.snapshots.value.status)
        assertEquals(emptyList(), core.statusHistory)
        assertEquals(emptyList(), core.illegalTransitions)
        core.closeAndAwait()
        device.cancel()
    }

    @Test
    fun theHarnessKeepsTheHistoryItsTestsRead() = runTest {
        val harness = CoreHarness(this)
        harness.openWithRenderer()
        harness.core.play()
        harness.run(100.milliseconds)
        harness.core.pause()
        harness.run(100.milliseconds)
        val history = harness.core.statusHistory
        assertEquals(PlaybackStatus.Idle, history.first())
        assertEquals(PlaybackStatus.Paused, history.last())
        assertEquals(emptyList(), harness.core.illegalTransitions)
        harness.close()
    }
}
