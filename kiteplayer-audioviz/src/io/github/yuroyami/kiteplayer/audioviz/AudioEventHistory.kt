@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.Generation
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicArray
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference

/**
 * One source's bounded history. One writer publishes; replacing the whole object retires all of its
 * publications and cursors. Structural sources keep longer, sparser histories than transients.
 */
internal class AudioEventHistory(
    val generation: Generation,
    val revision: Long,
    val capacity: Int = 1024,
    private val retentionMicros: Long = 2_000_000L,
    val source: AudioEventSource = AudioEventSource.LiveTransient,
) {
    init {
        require(capacity in 1..1024)
        require(retentionMicros in 1..MAX_RETENTION_MICROS)
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

    /** Validates and publishes [batch], counting a rejection. */
    fun publish(batch: AudioDetections): Boolean {
        if (!accepts(batch)) return reject()
        commit(batch)
        return true
    }

    fun reject(): Boolean {
        rejected.fetchAndAdd(1L)
        return false
    }

    /** Whether [batch] belongs to this source and keeps every ordering and range rule. */
    fun accepts(batch: AudioDetections): Boolean {
        val old = snapshot
        if (batch.source != source || batch.completeThroughMicros > batch.availableThroughMicros ||
            old.completeThroughMicros?.let { batch.completeThroughMicros < it } == true ||
            old.availableThroughMicros?.let { batch.availableThroughMicros < it } == true) return false
        var previous: Long? = null
        for (index in 0 until batch.size) {
            val detection = batch[index]
            if (detection.ptsMicros > batch.completeThroughMicros ||
                old.completeThroughMicros?.let { detection.ptsMicros <= it } == true ||
                previous?.let { detection.ptsMicros < it } == true ||
                detection.availableMicros < detection.ptsMicros || detection.availableMicros > batch.availableThroughMicros ||
                !detection.strength.isFinite() || detection.strength !in 0f..1f ||
                !detection.confidence.isFinite() || detection.confidence !in 0f..1f ||
                !detection.surprise.isFinite() || detection.surprise !in 0f..1f) return false
            previous = detection.ptsMicros
        }
        return true
    }

    /** Publishes a batch that [accepts] approved. Only the single writer calls this. */
    fun commit(batch: AudioDetections) {
        val old = snapshot
        var first = old.firstSequence
        var next = old.nextSequence
        var evicted = old.evictedEvents
        if (batch.size > 0) {
            val storage = slots ?: AtomicArray<AudioEvent?>(capacity) { null }.also { slots = it }
            for (index in 0 until batch.size) {
                if (next - first == capacity.toLong()) { first++; evicted++ }
                storage.storeAt((next % capacity).toInt(), AudioEvent(generation, revision, next, batch[index], source))
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
    }
}

/** The event sources of one feature history, read together by a cursor. */
internal class EventSources(
    val transient: AudioEventHistory,
    val structure: AudioEventHistory,
    val map: SongMapEvents?,
) {
    /** A transient history alone, as focused tests and author-built pipelines use it. */
    constructor(transient: AudioEventHistory) : this(transient, structureHistory(transient.generation, transient.revision), null)
}

/** The structural source's bounded history for one feature history. */
internal fun structureHistory(generation: Generation, revision: Long): AudioEventHistory =
    AudioEventHistory(generation, revision, STRUCTURE_CAPACITY, STRUCTURE_RETENTION_MICROS, AudioEventSource.LiveStructure)

/**
 * One view's ordered event reader across every source. Call from one consumer thread, with the
 * authoritative media time. Initial attachment and gaps over 250 ms discard past bursts. A late
 * transient may arrive up to 30 ms late and a late structural confirmation up to 3 s late; song
 * map events are delivered on time only. [sample] reports every kind of discard. Pausing never
 * emits an event.
 */
@AudioVizAuthoringApi
public class AudioEventCursor internal constructor(private val sources: () -> EventSources) {
    private var history: AudioEventHistory? = null
    private var nextSequence = 0L
    private var structure: AudioEventHistory? = null
    private var nextStructure = 0L
    private var map: SongMapEvents? = null
    private var mapIndex = 0
    private var mapFrom = Long.MIN_VALUE
    private var previousMicros: Long? = null
    private var discardThroughMicros: Long? = null

    public var lateDiscards: Long = 0L
        private set
    public var catchUpDiscards: Long = 0L
        private set
    /** Live structural events dropped because a complete song map covers their time. */
    public var duplicateDiscards: Long = 0L
        private set

    /** Mark the next sample as a fresh attachment; totals survive the reset. */
    public fun reset() { previousMicros = null }

    public fun sample(ptsMicros: Long, paused: Boolean = false): AudioEventDelivery {
        // An overwritten slot means the publisher raced this read. Retry a bounded number of times
        // and otherwise defer delivery to the next display callback, without blocking the worker.
        repeat(3) {
            val current = sources()
            val head = current.transient.snapshot
            val structureHead = current.structure.snapshot
            val replaced = history !== current.transient
            val structureReplaced = replaced || structure !== current.structure
            val previous = previousMicros
            var next = if (replaced) head.firstSequence else nextSequence
            var nextFromStructure = if (structureReplaced) structureHead.firstSequence else nextStructure
            val missed = (if (!replaced && next < head.firstSequence) head.firstSequence - next else 0L) +
                (if (!structureReplaced && nextFromStructure < structureHead.firstSequence) {
                    structureHead.firstSequence - nextFromStructure
                } else 0L)
            val reset = replaced || previous == null || paused || missed > 0 || ptsMicros < previous ||
                (previous <= Long.MAX_VALUE - MAX_ADVANCE_MICROS && ptsMicros > previous + MAX_ADVANCE_MICROS)
            if (next < head.firstSequence) next = head.firstSequence
            if (nextFromStructure < structureHead.firstSequence) nextFromStructure = structureHead.firstSequence
            val boundary = if (reset) ptsMicros else discardThroughMicros
            val currentMap = current.map
            val newMap = currentMap !== map
            // A view reads a map from where it first sees it; earlier map events are not delivered.
            val readsMapFrom = when {
                reset -> ptsMicros
                newMap -> previous ?: ptsMicros
                else -> mapFrom
            }
            val found = ArrayList<DeliveredAudioEvent>()
            var discarded = missed
            var late = 0L
            var duplicates = 0L
            var raced = false

            fun take(event: AudioEvent, budget: Long) {
                val time = event.detection.ptsMicros
                if (reset || (boundary != null && time <= boundary)) {
                    discarded++
                } else if (previous != null && time <= previous) {
                    val age = if (time < 0 && ptsMicros > Long.MAX_VALUE + time) Long.MAX_VALUE else ptsMicros - time
                    if (age > budget) late++ else found.add(DeliveredAudioEvent(event, age))
                } else {
                    found.add(DeliveredAudioEvent(event, 0L))
                }
            }

            while (next < head.nextSequence) {
                val event = current.transient.event(next)
                if (event == null) { raced = true; break }
                if (event.detection.ptsMicros > ptsMicros) break
                next++
                take(event, MAX_LATENESS_MICROS)
            }
            while (!raced && nextFromStructure < structureHead.nextSequence) {
                val event = current.structure.event(nextFromStructure)
                if (event == null) { raced = true; break }
                val time = event.detection.ptsMicros
                if (time > ptsMicros) break
                nextFromStructure++
                val duplicate = currentMap != null && time > readsMapFrom && currentMap.covers(time) &&
                    !(reset || (boundary != null && time <= boundary))
                if (duplicate) duplicates++ else take(event, STRUCTURE_LATENESS_MICROS)
            }
            var index = if (newMap || reset) currentMap?.firstAfter(readsMapFrom) ?: 0 else mapIndex
            if (currentMap != null) {
                while (index < currentMap.detections.size) {
                    val detection = currentMap.detections[index]
                    if (detection.ptsMicros > ptsMicros) break
                    found.add(DeliveredAudioEvent(AudioEvent(current.transient.generation, current.transient.revision,
                        index.toLong(), detection, AudioEventSource.SongMap), 0L))
                    index++
                }
            }
            if (raced || sources().transient !== current.transient) return@repeat
            history = current.transient
            nextSequence = next
            structure = current.structure
            nextStructure = nextFromStructure
            map = currentMap
            mapIndex = index
            mapFrom = readsMapFrom
            previousMicros = ptsMicros
            discardThroughMicros = boundary
            lateDiscards += late
            catchUpDiscards += discarded
            duplicateDiscards += duplicates
            found.sortWith(DELIVERY_ORDER)
            return AudioEventDelivery(current.transient.generation, current.transient.revision, head.completeThroughMicros,
                found.toTypedArray(), late, discarded, reset, structureHead.completeThroughMicros, duplicates)
        }
        val current = sources()
        return AudioEventDelivery(current.transient.generation, current.transient.revision,
            current.transient.snapshot.completeThroughMicros, emptyArray(),
            structureCompleteThroughMicros = current.structure.snapshot.completeThroughMicros)
    }
}

/** Original time, then source, then sequence within that source. */
private val DELIVERY_ORDER = Comparator<DeliveredAudioEvent> { first, second ->
    val a = first.event
    val b = second.event
    when {
        a.detection.ptsMicros != b.detection.ptsMicros -> a.detection.ptsMicros.compareTo(b.detection.ptsMicros)
        a.source != b.source -> a.source.ordinal.compareTo(b.source.ordinal)
        else -> a.sequence.compareTo(b.sequence)
    }
}

internal const val STRUCTURE_CAPACITY = 64
internal const val STRUCTURE_RETENTION_MICROS = 12_000_000L
private const val STRUCTURE_LATENESS_MICROS = 3_000_000L
private const val MAX_RETENTION_MICROS = 12_000_000L
private const val MAX_ADVANCE_MICROS = 250_000L
private const val MAX_LATENESS_MICROS = 30_000L
