@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Shuffle, as an order OVER the queue rather than a reorder OF it.
 *
 * The queue an application shows is the queue someone built, and a shuffle that rewrites it takes
 * that away: the list jumps around, an edit made against what was on screen lands somewhere else,
 * and turning shuffle off cannot put anything back. So the items never move. What moves is a
 * separate list of positions into them, and everything that walks the queue walks that instead.
 *
 * The order is published, so an application can show what is coming next rather than guessing.
 */
class ShuffleTest {

    private fun items(count: Int) = (0 until count).map { MediaItem("scripted://item$it") }

    /** The order a queue of five has before anything shuffles it. */
    private val plain = listOf(0, 1, 2, 3, 4)

    /**
     * Refuses a seed that happened to leave the rest of the queue in list order.
     *
     * A case that walks a shuffled order proves nothing when the order IS the list order, and a
     * shuffle of four items lands on the list order once in every twenty-four seeds. That is not
     * rare enough to leave to luck: the first seed picked here did exactly that, and a mutation
     * that made the natural advance ignore the order entirely went undetected because of it.
     */
    private fun assertActuallyShuffled(order: List<Int>) {
        val listOrder = plain.filter { it != order.first() }
        assertNotEquals(
            listOrder,
            order.drop(1),
            "this seed left the rest of the queue in list order, so the case proves nothing",
        )
    }

    private suspend fun openFive(harness: CoreHarness, startIndex: Int = 2) {
        harness.attachRenderer()
        harness.core.openQueue(items(5), startIndex = startIndex)
    }

    private fun CoreHarness.order() = core.snapshots.value.queueOrder
    private fun CoreHarness.playing() = core.snapshots.value.media?.uri

    /** The items in play order, which is what shuffle actually promises to keep steady. */
    private fun CoreHarness.playSequence(): List<String> {
        val queue = core.snapshots.value.queue
        return order().map { queue[it].uri }
    }

    @Test
    fun `a fresh queue plays in the order it was given`() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 8_000_000))
        openFive(harness)
        assertEquals(false, harness.core.snapshots.value.shuffle)
        assertEquals(listOf(0, 1, 2, 3, 4), harness.order())
        harness.close()
    }

    @Test
    fun `shuffle puts the playing item first and permutes the rest`() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 8_000_000))
        openFive(harness)
        harness.core.play()
        harness.run(200.milliseconds)
        val opens = harness.backend.openCalls

        harness.core.setShuffle(true, seed = 42)

        assertEquals(true, harness.core.snapshots.value.shuffle)
        assertEquals(2, harness.order().first(), "the item already playing must be first in the order")
        assertEquals(listOf(0, 1, 2, 3, 4), harness.order().sorted(), "the order is not a permutation")
        assertEquals("scripted://item2", harness.playing(), "turning shuffle on changed the media")
        assertEquals(opens, harness.backend.openCalls, "turning shuffle on reopened the media")
        harness.close()
    }

    @Test
    fun `the items themselves never move`() = runTest {
        // The whole point. An application drawing the queue sees the list it built, in the order it
        // built it, whether or not shuffle is on.
        val harness = CoreHarness(this, script = MediaScript(durationUs = 8_000_000))
        openFive(harness)
        val before = harness.core.snapshots.value.queue.map { it.uri }

        harness.core.setShuffle(true, seed = 42)

        assertEquals(before, harness.core.snapshots.value.queue.map { it.uri })
        harness.close()
    }

    @Test
    fun `the same seed gives the same order twice`() = runTest {
        val first = CoreHarness(this, script = MediaScript(durationUs = 8_000_000))
        openFive(first)
        first.core.setShuffle(true, seed = 12345)
        val order = first.order()
        first.close()

        val second = CoreHarness(this, script = MediaScript(durationUs = 8_000_000))
        openFive(second)
        second.core.setShuffle(true, seed = 12345)
        assertEquals(order, second.order())
        second.close()
    }

    @Test
    fun `a different seed gives a different order`() = runTest {
        // Not a law of the universe, but with 24 possible orders for the four items behind the
        // playing one, two named seeds landing on the same one would mean the seed is ignored.
        val first = CoreHarness(this, script = MediaScript(durationUs = 8_000_000))
        openFive(first)
        first.core.setShuffle(true, seed = 1)
        val order = first.order()
        first.close()

        val second = CoreHarness(this, script = MediaScript(durationUs = 8_000_000))
        openFive(second)
        second.core.setShuffle(true, seed = 2)
        assertNotEquals(order, second.order())
        second.close()
    }

    @Test
    fun `next walks the shuffled order and wraps under LoopMode All`() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 20_000_000))
        openFive(harness)
        harness.core.setLoop(LoopMode.All)
        harness.core.setShuffle(true, seed = 42)
        val order = harness.order()
        assertActuallyShuffled(order)

        val visited = mutableListOf(harness.core.snapshots.value.queueIndex)
        repeat(5) {
            harness.core.queueNext()
            harness.run(100.milliseconds)
            visited += harness.core.snapshots.value.queueIndex
        }

        assertEquals(order, visited.take(5), "next did not follow the published order")
        assertEquals(order.first(), visited.last(), "the fifth step did not wrap back to the front")
        assertEquals(listOf(0, 1, 2, 3, 4), visited.take(5).sorted(), "some item was played twice and another never")
        harness.close()
    }

    @Test
    fun `previous walks the shuffled order backwards`() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 20_000_000))
        openFive(harness)
        harness.core.setShuffle(true, seed = 42)
        val order = harness.order()
        assertActuallyShuffled(order)

        harness.core.queueNext()
        harness.run(100.milliseconds)
        harness.core.queueNext()
        harness.run(100.milliseconds)
        assertEquals(order[2], harness.core.snapshots.value.queueIndex)

        harness.core.queuePrevious()
        harness.run(100.milliseconds)
        assertEquals(order[1], harness.core.snapshots.value.queueIndex, "previous left the shuffled order")
        harness.close()
    }

    @Test
    fun `the end of an item advances along the shuffled order`() = runTest {
        // The queue advances on its own at the end of an item, and that path is separate from
        // next(). A shuffle that only next() honoured would play in order whenever nobody touched
        // the controls, which is most of the time.
        val harness = CoreHarness(this, script = MediaScript(durationUs = 1_000_000))
        harness.attachRenderer()
        harness.core.openQueue(items(5), startIndex = 0)
        harness.core.setShuffle(true, seed = 42)
        val order = harness.order()
        assertActuallyShuffled(order)

        // Sampled across the run rather than read once at the end. A single reading has to guess
        // how many items went by, and the first version of this test guessed wrong: it asserted
        // the second item while the queue was already on the fourth, so it failed against working
        // code. What the advance owes is the ORDER, so the whole path is what gets checked.
        val visited = mutableListOf<Int>()
        harness.core.play()
        repeat(25) {
            harness.run(200.milliseconds)
            val at = harness.core.snapshots.value.queueIndex
            if (visited.lastOrNull() != at) visited += at
        }

        assertTrue(visited.size >= 3, "the queue never advanced twice, so nothing was exercised: $visited")
        assertEquals(order.take(visited.size), visited, "the natural advance ignored the order")
        harness.close()
    }

    @Test
    fun `turning shuffle off restores the plain order and keeps playing`() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 20_000_000))
        openFive(harness)
        harness.core.setShuffle(true, seed = 42)
        harness.core.queueNext()
        harness.run(200.milliseconds)
        val playingIndex = harness.core.snapshots.value.queueIndex
        val playingUri = harness.playing()
        val opens = harness.backend.openCalls

        harness.core.setShuffle(false)

        assertEquals(false, harness.core.snapshots.value.shuffle)
        assertEquals(listOf(0, 1, 2, 3, 4), harness.order())
        assertEquals(playingIndex, harness.core.snapshots.value.queueIndex, "the cursor moved when shuffle went off")
        assertEquals(playingUri, harness.playing(), "turning shuffle off changed the media")
        assertEquals(opens, harness.backend.openCalls, "turning shuffle off reopened the media")
        harness.close()
    }

    // -------------------------------------------------------------------------------------------
    // Edits keep the order valid.
    // -------------------------------------------------------------------------------------------

    @Test
    fun `added items land after the one playing and among the ones still to play`() = runTest {
        // Two rules at once, and the second is the one that is easy to get wrong. Landing after
        // the playing item stops an add from interrupting what is on screen. Landing AMONG the
        // rest, rather than always at the very end, is what makes an add feel shuffled; appending
        // satisfies the first rule perfectly and is still wrong.
        val harness = CoreHarness(this, script = MediaScript(durationUs = 20_000_000))
        openFive(harness)
        harness.core.setShuffle(true, seed = 42)

        val added = (0 until 4).map { MediaItem("scripted://late$it") }
        harness.core.addToQueue(added, index = 0)

        val order = harness.order()
        val queue = harness.core.snapshots.value.queue
        assertEquals((0..8).toList(), order.sorted(), "the order stopped being a permutation of the queue")

        val addedPositions = order.withIndex()
            .filter { queue[it.value].uri.startsWith("scripted://late") }
            .map { it.index }
        assertEquals(4, addedPositions.size, "the added items did not all reach the order")
        assertTrue(
            addedPositions.min() > order.indexOf(harness.core.snapshots.value.queueIndex),
            "an added item was placed at or before the one playing, so it jumped the queue",
        )

        val originalPositions = order.withIndex()
            .filter { !queue[it.value].uri.startsWith("scripted://late") }
            .map { it.index }
        assertTrue(
            originalPositions.max() > addedPositions.min(),
            "every added item went to the very end, so an add is not shuffled at all: $order",
        )
        harness.close()
    }

    @Test
    fun `a removed item leaves the order`() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 20_000_000))
        openFive(harness)
        harness.core.setShuffle(true, seed = 42)
        val goingUri = harness.core.snapshots.value.queue[4].uri
        val sequenceBefore = harness.playSequence().filterNot { it == goingUri }

        harness.core.removeFromQueue(4)

        assertEquals((0..3).toList(), harness.order().sorted(), "the order stopped being a permutation")
        assertEquals(sequenceBefore, harness.playSequence(), "removing one item reshuffled the others")
        harness.close()
    }

    @Test
    fun `a move changes the list without changing what plays next`() = runTest {
        // The two orders are independent, and this is the case that proves it: dragging an item up
        // the list a listener is looking at must not change the order they are hearing.
        val harness = CoreHarness(this, script = MediaScript(durationUs = 20_000_000))
        openFive(harness)
        harness.core.setShuffle(true, seed = 42)
        val sequence = harness.playSequence()

        harness.core.moveInQueue(from = 4, to = 0)

        assertEquals(
            listOf("scripted://item4", "scripted://item0", "scripted://item1", "scripted://item2", "scripted://item3"),
            harness.core.snapshots.value.queue.map { it.uri },
            "the move did not happen",
        )
        assertEquals(sequence, harness.playSequence(), "moving an item in the list changed the play order")
        assertEquals(
            "scripted://item2",
            harness.core.snapshots.value.queue[harness.core.snapshots.value.queueIndex].uri,
            "the cursor stopped naming the playing item",
        )
        harness.close()
    }

    @Test
    fun `clearing leaves an order of one`() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 20_000_000))
        openFive(harness)
        harness.core.setShuffle(true, seed = 42)
        harness.core.clearQueue()

        assertEquals(listOf(0), harness.order())
        assertEquals(0, harness.core.snapshots.value.queueIndex)
        harness.close()
    }

    @Test
    fun `a queue opened while shuffle is on comes out shuffled`() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 20_000_000))
        harness.attachRenderer()
        harness.core.setShuffle(true, seed = 42)
        harness.core.openQueue(items(5), startIndex = 0)

        assertEquals(true, harness.core.snapshots.value.shuffle)
        assertEquals(0, harness.order().first())
        assertEquals(listOf(0, 1, 2, 3, 4), harness.order().sorted())
        harness.close()
    }

    @Test
    fun `an empty queue has an empty order`() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 20_000_000))
        harness.core.setShuffle(true, seed = 42)
        assertEquals(emptyList(), harness.order())
        harness.openWithRenderer()
        assertEquals(emptyList(), harness.order(), "a plain open is not a queue until it is edited")
        harness.close()
    }
}
