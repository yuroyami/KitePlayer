package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * [PlayerSnapshot.playRequested] says whether the player means to make sound, which is what the
 * interruption and background guards need (#226).
 */
class PlayIntentTest {

    @Test
    fun theIntentFollowsBufferingPlayingAndTheNextQueueOpen() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 1_000_000))
        val seen = mutableListOf<Pair<PlaybackStatus, Boolean>>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
            harness.core.snapshots.collect { seen += it.status to it.playRequested }
        }
        harness.attachRenderer()
        harness.core.openQueue(listOf(MediaItem("scripted://one"), MediaItem("scripted://two")), 0)
        assertEquals(false, harness.core.snapshots.value.playRequested, "an open ends paused")
        harness.core.play()
        harness.run(2.seconds)
        collector.cancel()

        val intent = seen.groupBy({ it.first }, { it.second })
        assertTrue(intent[PlaybackStatus.Buffering].orEmpty().all { it }, "a buffering player means to play: $seen")
        assertTrue(intent[PlaybackStatus.Playing].orEmpty().all { it }, "a playing player means to play: $seen")
        assertTrue(intent[PlaybackStatus.Paused].orEmpty().none { it }, "a paused player does not: $seen")
        val openings = seen.dropWhile { it.first != PlaybackStatus.Playing }.filter { it.first == PlaybackStatus.Opening }
        assertTrue(openings.isNotEmpty(), "the queue never opened its second item, so this proves nothing: $seen")
        assertTrue(openings.all { it.second }, "the next item's open carries the queue's play: $seen")
        harness.close()
    }

    @Test
    fun aPauseWithdrawsTheIntent() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 5_000_000))
        harness.openWithRenderer()
        harness.core.play()
        harness.run(500.milliseconds)
        assertTrue(harness.core.snapshots.value.playRequested)
        harness.core.pause()
        harness.run(100.milliseconds)
        assertEquals(false, harness.core.snapshots.value.playRequested)
        harness.close()
    }
}
