@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.time.Duration.Companion.milliseconds

/**
 * What plays next after a queue edit (#688).
 *
 * One rule decides it. Unshuffled, the play order is the list, so a move changes what plays next.
 * Shuffled, the play order is its own, so a move leaves it alone, and removing the playing item
 * opens the item that was due next in that order, not the one after it on screen.
 */
class QueueEditOrderTest {

    private fun items(vararg names: String) = names.map { MediaItem("scripted://$it") }

    private fun CoreHarness.playing() = core.snapshots.value.media?.uri?.removePrefix("scripted://")
    private fun CoreHarness.order() = core.snapshots.value.queueOrder

    private suspend fun openQueue(harness: CoreHarness, names: List<String>, startIndex: Int) {
        harness.attachRenderer()
        harness.core.openQueue(items(*names.toTypedArray()), startIndex = startIndex)
    }

    @Test
    fun anUnshuffledMoveChangesWhatNextOpens() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 8_000_000))
        openQueue(harness, listOf("a", "b", "c", "d"), startIndex = 0)

        harness.core.moveInQueue(from = 3, to = 1)

        assertEquals(listOf(0, 1, 2, 3), harness.order(), "the play order kept the list from before the move")
        harness.core.queueNext()
        harness.run(200.milliseconds)
        assertEquals("d", harness.playing(), "the track moved to play next did not")
        harness.core.queuePrevious()
        harness.run(200.milliseconds)
        assertEquals("a", harness.playing())
        harness.close()
    }

    @Test
    fun anUnshuffledMoveChangesWhatTheEndOfAnItemOpens() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 1_000_000))
        openQueue(harness, listOf("a", "b", "c", "d"), startIndex = 0)
        harness.core.moveInQueue(from = 3, to = 1)

        val heard = mutableListOf<String>()
        harness.core.play()
        repeat(12) {
            harness.run(200.milliseconds)
            harness.playing()?.let { if (heard.lastOrNull() != it) heard += it }
        }

        assertEquals(listOf("a", "d", "b"), heard.take(3), "the natural advance followed the list from before the move")
        harness.close()
    }

    @Test
    fun removingThePlayingItemUnderShuffleOpensTheOneDueNext() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 8_000_000))
        openQueue(harness, listOf("i0", "i1", "i2", "i3", "i4"), startIndex = 2)
        harness.core.setShuffle(true, seed = 0)
        val order = harness.order()
        val due = order[1]
        assertNotEquals(3, due, "this seed puts the next item in the list next in play order too, so it proves nothing")
        harness.core.play()
        harness.run(200.milliseconds)

        harness.core.removeFromQueue(2)
        harness.run(300.milliseconds)

        assertEquals("i$due", harness.playing(), "the order was $order")
        harness.close()
    }

    @Test
    fun removingTheLastItemOnScreenUnderShuffleKeepsPlayingWhileTheOrderGoesOn() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 8_000_000))
        openQueue(harness, listOf("i0", "i1", "i2", "i3", "i4"), startIndex = 4)
        harness.core.setShuffle(true, seed = 0)
        val order = harness.order()
        assertEquals(4, order.first(), "the playing item comes first in a fresh shuffle")
        harness.core.play()
        harness.run(200.milliseconds)

        harness.core.removeFromQueue(4)
        harness.run(300.milliseconds)

        assertEquals(PlaybackStatus.Playing, harness.core.snapshots.value.status, "the player stopped with $order still to play")
        assertEquals("i${order[1]}", harness.playing())
        harness.close()
    }

    @Test
    fun removingThePlayingItemLastInAShuffledOrderStopsOrWrapsByTheLoop() = runTest {
        for (loop in listOf(LoopMode.Off, LoopMode.All)) {
            val harness = CoreHarness(this, script = MediaScript(durationUs = 8_000_000))
            openQueue(harness, listOf("i0", "i1", "i2", "i3", "i4"), startIndex = 2)
            harness.core.setShuffle(true, seed = 0)
            harness.core.setLoop(loop)
            val order = harness.order()
            // Walk to the last item in play order.
            repeat(order.size - 1) {
                harness.core.queueNext()
                harness.run(200.milliseconds)
            }
            assertEquals("i${order.last()}", harness.playing())
            harness.core.play()
            harness.run(200.milliseconds)

            harness.core.removeFromQueue(order.last())
            harness.run(300.milliseconds)

            if (loop == LoopMode.Off) {
                assertEquals(PlaybackStatus.Idle, harness.core.snapshots.value.status, "nothing followed it in play order")
            } else {
                val first = order.first().let { if (it > order.last()) it - 1 else it }
                assertEquals(first, harness.core.snapshots.value.queueIndex, "LoopMode.All did not wrap to the start of the order")
            }
            harness.close()
        }
    }
}
