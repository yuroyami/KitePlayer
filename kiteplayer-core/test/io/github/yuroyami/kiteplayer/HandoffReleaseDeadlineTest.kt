@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A gapless handoff releases the item before it under the close deadline, as every other
 * teardown does (#590). The handoff released it inline on the actor with no deadline, so a decoder
 * close that never returned at the join kept the next item from becoming current, and a later
 * close waited for ever (#992). Here the release lane is held, which is a release that never ends.
 */
class HandoffReleaseDeadlineTest {

    private val items = listOf(MediaItem("scripted://first"), MediaItem("scripted://second"))

    /** A release lane that runs nothing until [release]: every release on it is stuck. */
    private class HeldLane(private val next: CoroutineDispatcher) : CoroutineDispatcher() {
        private val held = ArrayDeque<Pair<CoroutineContext, Runnable>>()
        private var open = false
        val holding: Int get() = held.size

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (open) next.dispatch(context, block) else held += context to block
        }

        fun release() {
            open = true
            while (held.isNotEmpty()) held.removeFirst().let { (context, block) -> next.dispatch(context, block) }
        }
    }

    private suspend fun CoreHarness.runUntil(limit: Duration, condition: () -> Boolean): Boolean {
        var waited = Duration.ZERO
        while (!condition()) {
            if (waited >= limit) return false
            run(5.milliseconds)
            waited += 5.milliseconds
        }
        return true
    }

    private fun CoreHarness.overranReleases(): List<PlaybackWarning.ResourcesNotReleased> =
        core.warningHistory().map { it.warning }.filterIsInstance<PlaybackWarning.ResourcesNotReleased>()
            .filter { "did not finish within" in it.detail }

    /** The actor still reads its mailbox: a pause sent now is taken. */
    private suspend fun CoreHarness.assertTakesAPause() {
        core.pause()
        assertTrue(runUntil(2.seconds) { core.snapshots.value.status == PlaybackStatus.Paused }, "the player took no pause")
    }

    /** A close reports the stuck release, and once the lane runs again everything is released. */
    private suspend fun CoreHarness.assertCloseReportsTheStuckRelease(lane: HeldLane) {
        val failure = withTimeout(30.seconds) { assertFailsWith<PlaybackException> { core.closeAndAwait() } }
        assertIs<PlaybackError.RuntimeCompromised>(failure.error, "the close must say the runtime is compromised")
        lane.release()
        run(2.seconds)
        stopDevice()
        assertEquals(0, ledger.liveCount, "nothing leaked once the release could run")
    }

    /**
     * Runs [block], and on a failure frees the lane and closes, so the test fails rather than
     * hangs: a looping player left open keeps the test scheduler busy for ever.
     */
    private suspend fun CoreHarness.closingOnFailure(lane: HeldLane, block: suspend () -> Unit) {
        try {
            block()
        } catch (failure: Throwable) {
            lane.release()
            runCatching { withTimeout(30.seconds) { core.closeAndAwait() } }
            stopDevice()
            throw failure
        }
    }

    @Test
    fun aReleaseThatNeverEndsAtTheJoinLetsTheNextItemPlayAndACloseReportIt() = runTest {
        val lane = HeldLane(StandardTestDispatcher(testScheduler))
        val harness = CoreHarness(
            this,
            script = MediaScript(durationUs = 3_000_000),
            releaseDispatcher = lane,
            closeDeadline = 1.seconds,
        )
        harness.attachRenderer()
        harness.core.openQueue(items, 0)
        harness.core.play()

        harness.closingOnFailure(lane) {
            assertTrue(harness.runUntil(6.seconds) { harness.core.snapshots.value.queueIndex == 1 }, "the queue moved on")
            assertTrue(lane.holding > 0, "the first item's release is stuck on the release lane")
            assertTrue(harness.overranReleases().isNotEmpty(), "the stuck release was reported: ${harness.core.warningHistory()}")
            val renderer = assertNotNull(harness.renderer)
            val before = renderer.count
            harness.run(1.seconds)
            assertTrue(renderer.count > before + 10, "the second item's pictures present after the join")
            assertEquals(PlaybackStatus.Playing, harness.core.snapshots.value.status)
            harness.assertTakesAPause()
        }
        harness.assertCloseReportsTheStuckRelease(lane)
    }

    @Test
    fun aReleaseThatNeverEndsAtARepeatLetsTheNextPassPlayAndACloseReportIt() = runTest {
        val lane = HeldLane(StandardTestDispatcher(testScheduler))
        val harness = CoreHarness(
            this,
            script = MediaScript(durationUs = 3_000_000, hasVideo = false),
            releaseDispatcher = lane,
            closeDeadline = 1.seconds,
        )
        harness.open()
        harness.core.setLoop(LoopMode.One)
        harness.core.play()

        harness.closingOnFailure(lane) {
            assertTrue(harness.runUntil(6.seconds) { harness.overranReleases().isNotEmpty() }, "the stuck release was reported")
            assertTrue(lane.holding > 0, "the first pass's release is stuck on the release lane")
            assertEquals(PlaybackStatus.Playing, harness.core.snapshots.value.status)
            val position = harness.core.position()
            harness.run(500.milliseconds)
            assertTrue(harness.core.position() > position, "the next pass plays on: $position then ${harness.core.position()}")
            harness.assertTakesAPause()
        }
        harness.assertCloseReportsTheStuckRelease(lane)
    }
}
