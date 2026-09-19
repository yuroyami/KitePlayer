@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.Generation
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicArray
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference

/** One writer publishes; replacing this entire object retires all of its publications and cursors. */
internal class AudioEventHistory(
    val generation: Generation,
    val revision: Long,
    val capacity: Int = 1024,
    private val retentionMicros: Long = 2_000_000L,
) {
    init {
        require(capacity in 1..1024)
        require(retentionMicros in 1..2_000_000L)
    }

    internal class Snapshot(
        val firstSequence: Long = 0L,
        val nextSequence: Long = 0L,
        val completeThroughMicros: Long? = null,
        val availableThroughMicros: Long? = null,
        val evictedEvents: Long = 0L,
    ) {
        val retainedPayloadBytes: Long get() = (nextSequence - firstSequence) * 64L
    }

    // Allocate slots on first worker publication, never the playback tap's reset callback.
    @Volatile private var slots: AtomicArray<AudioEvent?>? = null
    private val head = AtomicReference(Snapshot())
    private val rejected = AtomicLong(0L)
    val snapshot: Snapshot get() = head.load()
    val rejectedPublications: Long get() = rejected.load()
    val stats: AudioEventHistoryStats get() {
        val current = snapshot
        return AudioEventHistoryStats((current.nextSequence - current.firstSequence).toInt(), current.retainedPayloadBytes,
            current.evictedEvents, rejectedPublications, current.completeThroughMicros)
    }

    fun event(sequence: Long): AudioEvent? = slots?.loadAt((sequence % capacity).toInt())?.takeIf { it.sequence == sequence }

    fun publish(batch: AudioDetections): Boolean {
        val old = snapshot
        fun reject(): Boolean { rejected.fetchAndAdd(1L); return false }
        if (batch.completeThroughMicros > batch.availableThroughMicros ||
            old.completeThroughMicros?.let { batch.completeThroughMicros < it } == true ||
            old.availableThroughMicros?.let { batch.availableThroughMicros < it } == true) return reject()
        var previous: Long? = null
        for (index in 0 until batch.size) {
            val detection = batch[index]
            if (detection.ptsMicros > batch.completeThroughMicros ||
                old.completeThroughMicros?.let { detection.ptsMicros <= it } == true ||
                previous?.let { detection.ptsMicros < it } == true ||
                detection.availableMicros < detection.ptsMicros || detection.availableMicros > batch.availableThroughMicros ||
                !detection.strength.isFinite() || detection.strength !in 0f..1f ||
                !detection.confidence.isFinite() || detection.confidence !in 0f..1f ||
                !detection.surprise.isFinite() || detection.surprise !in 0f..1f) return reject()
            previous = detection.ptsMicros
        }
        var first = old.firstSequence
        var next = old.nextSequence
        var evicted = old.evictedEvents
        if (batch.size > 0) {
            val storage = slots ?: AtomicArray<AudioEvent?>(capacity) { null }.also { slots = it }
            for (index in 0 until batch.size) {
                if (next - first == capacity.toLong()) { first++; evicted++ }
                storage.storeAt((next % capacity).toInt(), AudioEvent(generation, revision, next, batch[index]))
                next++
            }
        }
        val earliest = if (batch.availableThroughMicros < Long.MIN_VALUE + retentionMicros) Long.MIN_VALUE
            else batch.availableThroughMicros - retentionMicros
        while (first < next) {
            val item = event(first) ?: break
            if (item.detection.ptsMicros >= earliest) break
            slots?.storeAt((first % capacity).toInt(), null)
            first++
            evicted++
        }
        head.store(Snapshot(first, next, batch.completeThroughMicros, batch.availableThroughMicros, evicted))
        return true
    }
}

/**
 * One view's ordered event reader. Call from one consumer thread, with the authoritative media
 * time. Initial attachment and gaps over 250 ms discard past bursts; newly confirmed events may
 * arrive up to 30 ms late. [sample] reports both kinds of discard. Pausing never emits an event.
 */
@AudioVizAuthoringApi
public class AudioEventCursor internal constructor(private val historySource: () -> AudioEventHistory) {
    private var history: AudioEventHistory? = null
    private var nextSequence = 0L
    private var previousMicros: Long? = null
    private var discardThroughMicros: Long? = null

    public var lateDiscards: Long = 0L
        private set
    public var catchUpDiscards: Long = 0L
        private set

    /** Mark the next sample as a fresh attachment; totals survive the reset. */
    public fun reset() { previousMicros = null }

    public fun sample(ptsMicros: Long, paused: Boolean = false): AudioEventDelivery {
        // An overwritten slot means the publisher raced this read. Retry a bounded number of times
        // and otherwise defer delivery to the next display callback, without blocking the worker.
        repeat(3) {
            val current = historySource()
            val head = current.snapshot
            val replaced = history !== current
            val previous = previousMicros
            var next = if (replaced) head.firstSequence else nextSequence
            val missed = if (!replaced && next < head.firstSequence) head.firstSequence - next else 0L
            val reset = replaced || previous == null || paused || missed > 0 || ptsMicros < previous ||
                (previous <= Long.MAX_VALUE - MAX_ADVANCE_MICROS && ptsMicros > previous + MAX_ADVANCE_MICROS)
            var discarded = missed
            var late = 0L
            if (next < head.firstSequence) next = head.firstSequence
            val boundary = if (reset) ptsMicros else discardThroughMicros
            val found = ArrayList<DeliveredAudioEvent>()
            var raced = false
            while (next < head.nextSequence) {
                val event = current.event(next)
                if (event == null) { raced = true; break }
                val time = event.detection.ptsMicros
                if (time > ptsMicros) break
                next++
                if (reset || (boundary != null && time <= boundary)) {
                    discarded++
                } else if (time <= previous) {
                    val age = if (time < 0 && ptsMicros > Long.MAX_VALUE + time) Long.MAX_VALUE else ptsMicros - time
                    if (age > MAX_LATENESS_MICROS) late++ else found.add(DeliveredAudioEvent(event, age))
                } else {
                    found.add(DeliveredAudioEvent(event, 0L))
                }
            }
            if (raced || historySource() !== current) return@repeat
            history = current
            nextSequence = next
            previousMicros = ptsMicros
            discardThroughMicros = boundary
            lateDiscards += late
            catchUpDiscards += discarded
            return AudioEventDelivery(current.generation, current.revision, head.completeThroughMicros,
                found.toTypedArray(), late, discarded, reset)
        }
        val current = historySource()
        return AudioEventDelivery(current.generation, current.revision, current.snapshot.completeThroughMicros, emptyArray())
    }
}

private const val MAX_ADVANCE_MICROS = 250_000L
private const val MAX_LATENESS_MICROS = 30_000L
