package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * The worker player's events on the page, with every event a slow collector loses counted (#903),
 * as the engine counts its own in [PlaybackStats.droppedEvents]. A collector more than [capacity]
 * events behind makes the next one drop. A page has one thread, so nothing here is shared between two.
 */
internal class CountedEvents(capacity: Int = 64) {
    private val flow = MutableSharedFlow<PlayerEvent>(extraBufferCapacity = capacity)

    val events: SharedFlow<PlayerEvent> = flow.asSharedFlow()

    /** The events this page dropped, from the first. */
    var dropped: Long = 0
        private set

    /** Hands [event] to every collector, and counts it when one is too far behind to take it. False when it was dropped. */
    fun emit(event: PlayerEvent): Boolean = flow.tryEmit(event).also { if (!it) dropped++ }

    /** [stats], the worker's, with the events this page dropped added to the ones the worker did. */
    fun withDrops(stats: PlaybackStats): PlaybackStats =
        if (dropped == 0L) stats else stats.copy(droppedEvents = stats.droppedEvents + dropped)
}
