@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A queue item that cannot be opened, such as a moved file or a dead link (#487).
 *
 * By default the queue stops on it in Failed, as a single open does, so an application that handles
 * the error itself sees nothing change. With [QueueItemFailure.Skip] the queue reports the item,
 * marks it, and moves on in the direction it was going, as mpv does, and a queue in which every
 * item fails stops after one pass rather than going round for ever.
 */
class QueueItemFailureTest {

    private val twoSeconds = MediaScript(durationUs = 2_000_000, hasVideo = false)
    private val items = listOf(MediaItem("scripted://first"), MediaItem("scripted://second"), MediaItem("scripted://third"))
    private val skip = PlayerConfig(queue = QueueConfig(onItemFailure = QueueItemFailure.Skip))

    private suspend fun CoreHarness.runUntil(limit: Duration, condition: () -> Boolean): Boolean {
        var waited = Duration.ZERO
        while (!condition()) {
            if (waited >= limit) return false
            run(5.milliseconds)
            waited += 5.milliseconds
        }
        return true
    }

    private fun CoreHarness.skipped(): List<PlaybackWarning.QueueItemSkipped> =
        core.warningHistory().map { it.warning }.filterIsInstance<PlaybackWarning.QueueItemSkipped>()

    private fun CoreHarness.breakItems(vararg names: String) {
        backend.openFailureFor = { item ->
            if (names.any { item.uri.endsWith(it) }) IllegalStateException("no such file") else null
        }
    }

    @Test
    fun skipPassesOverAnItemThatCannotBeOpenedAndKeepsPlaying() = runTest {
        val harness = CoreHarness(this, script = twoSeconds, config = skip)
        harness.breakItems("second")
        harness.core.openQueue(items, 0)
        harness.core.play()
        assertTrue(
            harness.runUntil(5.seconds) {
                harness.core.snapshots.value.queueIndex == 2 && harness.core.snapshots.value.status == PlaybackStatus.Playing
            },
            "the third item plays: ${harness.core.snapshots.value.status} at ${harness.core.snapshots.value.queueIndex}",
        )
        val warning = harness.skipped().single()
        assertEquals(1, warning.index)
        assertEquals("scripted://second", warning.uri)
        assertTrue("no such file" in warning.error.message, warning.error.message)
        assertEquals(setOf(1), harness.core.snapshots.value.failedQueueItems)
        assertNull(harness.core.snapshots.value.error, "the player never failed")
        harness.close()
        assertEquals(0, harness.ledger.liveCount, "nothing leaked")
    }

    @Test
    fun stopIsTheDefaultAndFailsOnTheItem() = runTest {
        val harness = CoreHarness(this, script = twoSeconds)
        harness.breakItems("second")
        harness.core.openQueue(items, 0)
        harness.core.play()
        assertTrue(harness.runUntil(5.seconds) { harness.core.snapshots.value.status == PlaybackStatus.Failed })
        assertEquals(1, harness.core.snapshots.value.queueIndex)
        assertTrue(harness.skipped().isEmpty())
        assertEquals(emptySet(), harness.core.snapshots.value.failedQueueItems)
        harness.close()
    }

    @Test
    fun aQueueOfBrokenItemsFailsAfterOnePassEvenWhenItRepeats() = runTest {
        val harness = CoreHarness(this, script = twoSeconds, config = skip)
        harness.breakItems("first", "second", "third")
        harness.core.setLoop(LoopMode.All)
        val failure = assertFailsWith<PlaybackException> { harness.core.openQueue(items, 0) }
        assertTrue("no such file" in failure.error.message, failure.error.message)
        assertEquals(PlaybackStatus.Failed, harness.core.snapshots.value.status)
        assertEquals(listOf(0, 1, 2), harness.skipped().map { it.index })
        assertEquals(3, harness.backend.openCalls, "each item was tried once")
        assertEquals(setOf(0, 1, 2), harness.core.snapshots.value.failedQueueItems)
        harness.close()
    }

    @Test
    fun theLastItemFailingEndsTheQueueInFailed() = runTest {
        val harness = CoreHarness(this, script = twoSeconds, config = skip)
        harness.breakItems("third")
        harness.core.openQueue(items, 1)
        harness.core.play()
        assertTrue(harness.runUntil(5.seconds) { harness.core.snapshots.value.status == PlaybackStatus.Failed })
        assertEquals(2, harness.core.snapshots.value.queueIndex)
        assertEquals(listOf(2), harness.skipped().map { it.index })
        harness.close()
    }

    @Test
    fun previousSkipsBackwardAndNextSkipsForward() = runTest {
        val harness = CoreHarness(this, script = twoSeconds, config = skip)
        harness.breakItems("second")
        harness.core.openQueue(items, 2)
        harness.core.queuePrevious()
        assertEquals(0, harness.core.snapshots.value.queueIndex, "previous passed back over the second item")
        assertEquals(PlaybackStatus.Paused, harness.core.snapshots.value.status)
        harness.core.queueNext()
        assertEquals(2, harness.core.snapshots.value.queueIndex, "next passed forward over it")
        assertEquals(listOf(1, 1), harness.skipped().map { it.index })
        harness.close()
    }

    @Test
    fun aSkippedItemIsTriedAgainOnTheNextLapAndLeavesTheFailedSetWhenItOpens() = runTest {
        val harness = CoreHarness(this, script = twoSeconds, config = skip)
        harness.breakItems("second")
        harness.core.setLoop(LoopMode.All)
        harness.core.openQueue(items, 0)
        harness.core.queueNext()
        assertEquals(2, harness.core.snapshots.value.queueIndex)
        assertEquals(setOf(1), harness.core.snapshots.value.failedQueueItems)
        // The file comes back before the queue comes round to it again.
        harness.breakItems()
        harness.core.queueNext()
        harness.core.queueNext()
        assertEquals(1, harness.core.snapshots.value.queueIndex, "the second item opened on the next lap")
        assertEquals(emptySet(), harness.core.snapshots.value.failedQueueItems)
        harness.close()
    }

    @Test
    fun anEditCarriesTheFailedSetWithTheItemsItMoves() = runTest {
        val harness = CoreHarness(this, script = twoSeconds, config = skip)
        harness.breakItems("second")
        harness.core.openQueue(items, 0)
        harness.core.queueNext()
        assertEquals(setOf(1), harness.core.snapshots.value.failedQueueItems)
        harness.core.removeFromQueue(0)
        assertEquals(setOf(0), harness.core.snapshots.value.failedQueueItems, "the removal moved the broken item to 0")
        harness.core.addToQueue(listOf(MediaItem("scripted://fourth")), 0)
        assertEquals(setOf(1), harness.core.snapshots.value.failedQueueItems, "the insertion moved it to 1")
        harness.core.moveInQueue(1, 2)
        assertEquals(setOf(2), harness.core.snapshots.value.failedQueueItems, "and the move took it to 2")
        harness.close()
    }
}
