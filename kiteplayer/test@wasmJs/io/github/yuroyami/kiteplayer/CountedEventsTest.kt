package io.github.yuroyami.kiteplayer

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.suspendCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * The worker player's page counts every event a slow collector loses (#903). It emitted into a
 * shared flow of 64 slots and ignored the answer, so a collector more than 64 events behind lost
 * the rest with no count anywhere.
 */
class CountedEventsTest {

    /** A collector that takes the first event and then never returns from it. */
    private fun stuckCollector(events: CountedEvents, taken: MutableList<PlayerEvent>) {
        val collect: suspend () -> Unit = {
            events.events.collect { event ->
                taken += event
                suspendCoroutine<Unit> { }
            }
        }
        collect.startCoroutine(Continuation(EmptyCoroutineContext) { })
    }

    @Test
    fun eachEventPastTheBufferOfAStuckCollectorIsCounted() {
        val events = CountedEvents(capacity = 64)
        val taken = mutableListOf<PlayerEvent>()
        stuckCollector(events, taken)
        repeat(100) { events.emit(PlayerEvent.Ended) }
        // The first went to the collector, the next 64 wait in the buffer, and 35 had no room.
        assertEquals(1, taken.size)
        assertEquals(35L, events.dropped)
    }

    @Test
    fun theDropsJoinTheWorkersOwnInTheStats() {
        val events = CountedEvents(capacity = 1)
        val worker = PlaybackStats(droppedEvents = 3)
        assertSame(worker, events.withDrops(worker), "nothing dropped, so the worker's stats stand")
        stuckCollector(events, mutableListOf())
        repeat(4) { events.emit(PlayerEvent.Ended) }
        assertEquals(2L, events.dropped)
        assertEquals(5L, events.withDrops(worker).droppedEvents)
    }

    @Test
    fun withNoCollectorNothingIsDropped() {
        val events = CountedEvents(capacity = 1)
        repeat(10) { events.emit(PlayerEvent.Ended) }
        assertEquals(0L, events.dropped)
    }
}
