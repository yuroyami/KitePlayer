@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.Generation
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicArray
import kotlin.concurrent.atomics.AtomicReference

/**
 * Bounded analysis history aligned to the audible media clock.
 *
 * One thread pushes; any thread reads or resets. Reset replaces the whole history, so work already
 * publishing into the retired history cannot return to the new one. Each atomic slot carries its
 * sequence number: a wrapped slot cannot be mistaken for the older entry a reader was examining.
 */
@AudioVizAuthoringApi
public class SpectrumTimeline(public val capacity: Int = 128) {
    init {
        require(capacity in 2..1024) { "capacity must be in 2..1024, was $capacity" }
    }

    private class Entry(val sequence: Long, val frame: SpectrumFrame, val bytes: Long)
    private data class Head(
        val first: Long = 0L,
        val next: Long = 0L,
        val bytes: Long = 0L,
        val evicted: Long = 0L,
        val oversized: Long = 0L,
    )
    private class History(val generation: Generation, val revision: Long, val capacity: Int) {
        val events = AudioEventHistory(generation, revision)
        // Allocation belongs to the first analysis publication, never the tap's reset callback.
        @Volatile var slots: AtomicArray<Entry?>? = null
        val head = AtomicReference(Head())

        fun entry(sequence: Long): Entry? {
            if (sequence < 0) return null
            val entry = slots?.loadAt((sequence % capacity).toInt())
            return entry?.takeIf { it.sequence == sequence }
        }
        fun frame(sequence: Long): SpectrumFrame? = entry(sequence)?.frame
    }

    private val active = AtomicReference(History(Generation.Initial, 0L, capacity))

    /** The continuous audio timeline this history accepts. */
    public val generation: Generation get() = active.load().generation

    /** Local continuity identity. Also changes on a dropped visual block or an analysis reset. */
    public val revision: Long get() = active.load().revision

    /** Bounded event retention and detection-completion diagnostics for the current history. */
    public val eventStats: AudioEventHistoryStats get() = active.load().events.stats

    /** Current feature retention, including evictions by count, media time or payload size. */
    public val historyStats: SpectrumHistoryStats get() {
        val head = active.load().head.load()
        return SpectrumHistoryStats((head.next - head.first).toInt(), head.bytes, MAX_PAYLOAD_BYTES,
            RETENTION_MICROS, head.evicted, head.oversized)
    }

    /** A new independent consumer, initially discarding events at or before its first sample time. */
    public fun eventCursor(): AudioEventCursor = AudioEventCursor { active.load().events }

    /** Publishes only frames matching both [generation] and [revision] of this history. */
    public fun push(frame: SpectrumFrame) {
        val history = active.load()
        push(history, frame)
    }

    internal class Publication(val revision: Long, val push: (SpectrumFrame) -> Boolean)

    /** A publisher cannot write into a replacement history, even when its audio generation repeats. */
    internal fun publisher(): Publication {
        val history = active.load()
        return Publication(history.revision) { frame ->
            if (active.load() === history) push(history, frame) else false
        }
    }

    private fun push(history: History, frame: SpectrumFrame): Boolean {
        if (!frame.hasTimestamp || frame.generation != history.generation || frame.analysisRevision != history.revision) return false
        val old = history.head.load()
        val sequence = old.next
        val last = history.frame(sequence - 1)
        if (last != null && frame.ptsMicros < last.ptsMicros) return false
        val bytes = frame.retainedPayloadBytes
        if (bytes > MAX_PAYLOAD_BYTES) {
            history.head.store(old.copy(oversized = old.oversized + 1))
            return false
        }
        if (frame.availability == AnalysisAvailability.Ready) {
            frame.detections?.let { if (!history.events.publish(it)) return false }
        }
        val slots = history.slots ?: AtomicArray<Entry?>(capacity) { null }.also { history.slots = it }
        var first = old.first
        var retainedBytes = old.bytes
        var evicted = old.evicted
        val earliest = if (frame.ptsMicros < Long.MIN_VALUE + RETENTION_MICROS) Long.MIN_VALUE else frame.ptsMicros - RETENTION_MICROS
        while (first < sequence) {
            val oldest = checkNotNull(history.entry(first))
            if (sequence - first < capacity && retainedBytes + bytes <= MAX_PAYLOAD_BYTES && oldest.frame.ptsMicros >= earliest) break
            retainedBytes -= oldest.bytes
            slots.storeAt((first % capacity).toInt(), null)
            first++
            evicted++
        }
        slots.storeAt((sequence % capacity).toInt(), Entry(sequence, frame, bytes))
        history.head.store(Head(first, sequence + 1, retainedBytes + bytes, evicted, old.oversized))
        return active.load() === history
    }

    /** Newest retained analysis at or before [ptsMicros], expiring after 100 ms without new data. */
    public fun at(ptsMicros: Long): SpectrumFrame? {
        val history = active.load()
        val frame = beforeAndAfter(history, ptsMicros).first ?: return null
        return frame.takeIf { active.load() === history && ptsMicros - it.ptsMicros <= MAX_HOLD_MICROS }
    }

    /**
     * Actual, already analysed audio [seconds] ahead. Null beyond the available horizon or across a
     * gap; a past frame never substitutes for an unavailable future. Invalid offsets return null.
     */
    public fun ahead(ptsMicros: Long, seconds: Float): SpectrumFrame? {
        if (!seconds.isFinite() || seconds < 0f) return null
        val history = active.load()
        val newest = history.frame(history.head.load().next - 1) ?: return null
        val offset = (seconds.toDouble() * 1_000_000.0).toLong()
        if (offset > 0 && ptsMicros > Long.MAX_VALUE - offset) return null
        val at = ptsMicros + offset
        if (at > newest.ptsMicros) return null
        return interpolate(history, at).takeIf { active.load() === history }
    }

    /** Contiguous ready media seconds ahead, stopping at missing data or a gap over 100 ms. */
    public fun availableAheadSeconds(ptsMicros: Long): Float {
        val history = active.load()
        if (interpolate(history, ptsMicros) == null) return 0f
        val head = history.head.load()
        var through = ptsMicros
        for (sequence in head.first until head.next) {
            val frame = history.frame(sequence) ?: return 0f
            if (frame.ptsMicros <= ptsMicros) continue
            if (frame.availability != AnalysisAvailability.Ready || frame.ptsMicros - through > MAX_HOLD_MICROS) break
            through = frame.ptsMicros
        }
        return if (active.load() !== history) 0f else (through - ptsMicros) / 1_000_000f
    }

    /** Next retained event of [kind] strictly after [ptsMicros], preserving its original identity. */
    public fun nextEvent(ptsMicros: Long, kind: AudioEventKind): AudioEvent? {
        val history = active.load()
        val head = history.events.snapshot
        var next: AudioEvent? = null
        for (sequence in head.nextSequence - 1 downTo head.firstSequence) {
            val candidate = history.events.event(sequence) ?: return null
            if (candidate.detection.ptsMicros <= ptsMicros) break
            if (candidate.detection.kind == kind) next = candidate
        }
        return next.takeIf { active.load() === history }
    }

    /** Seconds until the next retained onset, or -1 when none has been observed. */
    public fun nextOnsetSeconds(ptsMicros: Long): Float {
        val history = active.load()
        val events = history.events.snapshot
        var soonest: Long? = null
        for (sequence in events.nextSequence - 1 downTo events.firstSequence) {
            val candidate = history.events.event(sequence)?.detection ?: return -1f
            if (candidate.ptsMicros <= ptsMicros) break
            if (candidate.kind == AudioEventKind.Onset) soonest = candidate.ptsMicros
        }
        val head = history.head.load()
        val end = head.next
        for (index in end - 1 downTo head.first) {
            val candidate = history.frame(index) ?: break
            if (candidate.ptsMicros <= ptsMicros) break
            // Author-created legacy frames may have no timestamped detection batch.
            if (candidate.detections == null && candidate.availability == AnalysisAvailability.Ready && candidate.beat > 0f) {
                soonest = minOf(soonest ?: candidate.ptsMicros, candidate.ptsMicros)
            }
        }
        return if (active.load() !== history) -1f else soonest?.let { (it - ptsMicros) / 1_000_000f } ?: -1f
    }

    /** Continuous interpolation within one generation; unavailable data expires after 100 ms. */
    public fun interpolated(ptsMicros: Long): SpectrumFrame? {
        val history = active.load()
        return interpolate(history, ptsMicros).takeIf { active.load() === history }
    }

    private fun beforeAndAfter(history: History, at: Long): Pair<SpectrumFrame?, SpectrumFrame?> {
        val head = history.head.load()
        val end = head.next
        var after: SpectrumFrame? = null
        for (index in end - 1 downTo head.first) {
            val frame = history.frame(index) ?: return null to null
            if (frame.ptsMicros <= at) return frame to after
            after = frame
        }
        return null to after
    }

    private fun interpolate(history: History, at: Long): SpectrumFrame? {
        val (before, after) = beforeAndAfter(history, at)
        val earlier = before ?: return null
        if (earlier.availability != AnalysisAvailability.Ready) return null
        if (at - earlier.ptsMicros > MAX_HOLD_MICROS) return null
        val later = after ?: return earlier
        val span = later.ptsMicros - earlier.ptsMicros
        if (span <= 0L || at == earlier.ptsMicros) return earlier
        if (later.availability != AnalysisAvailability.Ready) return null
        if (earlier.analysisRevision != later.analysisRevision) return null
        if (span > MAX_HOLD_MICROS) return null
        return earlier.blend(later, (at - earlier.ptsMicros).toFloat() / span)
    }

    /**
     * Scalar event projection over `(previousPtsMicros, ptsMicros]`. Continuous features are
     * interpolated. This compatibility projection retains the strongest hit of each kind and does
     * not preserve event multiplicity. It must not be used to count events.
     */
    public fun sample(ptsMicros: Long, previousPtsMicros: Long): SpectrumFrame? {
        val history = active.load()
        val frame = interpolate(history, ptsMicros) ?: return null
        val from = if (previousPtsMicros > ptsMicros) ptsMicros else previousPtsMicros
        var beat = 0f
        var kick = 0f
        var snare = 0f
        var hat = 0f
        var strength = 0f
        var drop = false
        val events = history.events.snapshot
        for (sequence in events.nextSequence - 1 downTo events.firstSequence) {
            val candidate = history.events.event(sequence)?.detection ?: return null
            if (candidate.ptsMicros <= from) break
            if (candidate.ptsMicros > ptsMicros) continue
            when (candidate.kind) {
                AudioEventKind.Onset -> {
                    beat = maxOf(beat, candidate.strength)
                    strength = maxOf(strength, candidate.strength)
                }
                AudioEventKind.LowTransient -> kick = maxOf(kick, candidate.strength)
                AudioEventKind.BodyTransient -> snare = maxOf(snare, candidate.strength)
                AudioEventKind.HighTransient -> hat = maxOf(hat, candidate.strength)
                AudioEventKind.EnergyRise -> drop = true
                AudioEventKind.Drop -> drop = true
                AudioEventKind.SectionBoundary, AudioEventKind.Breakdown -> Unit
            }
        }
        val head = history.head.load()
        val end = head.next
        for (index in end - 1 downTo head.first) {
            val candidate = history.frame(index) ?: break
            if (candidate.analysisRevision != frame.analysisRevision) break
            if (candidate.ptsMicros <= from) break
            if (candidate.detections != null || candidate.availability != AnalysisAvailability.Ready) continue
            if (candidate.ptsMicros <= ptsMicros) {
                beat = maxOf(beat, candidate.beat)
                kick = maxOf(kick, candidate.kick)
                snare = maxOf(snare, candidate.snare)
                hat = maxOf(hat, candidate.hat)
                strength = maxOf(strength, candidate.onsetStrength)
                drop = drop || candidate.drop
            }
        }
        return frame.withEvents(beat, kick, snare, hat, strength, drop).takeIf { active.load() === history }
    }

    /** The newest retained analysis, without alignment. */
    public fun newest(): SpectrumFrame? {
        val history = active.load()
        return history.frame(history.head.load().next - 1).takeIf { active.load() === history }
    }

    public fun clear() {
        reset(generation)
    }

    /** Retires history and advances [revision]. An older audio generation is ignored. */
    public fun reset(generation: Generation) {
        while (true) {
            val old = active.load()
            if (generation < old.generation) return
            if (active.compareAndSet(old, History(generation, old.revision + 1, capacity))) return
        }
    }
}

private const val MAX_HOLD_MICROS = 100_000L
private const val RETENTION_MICROS = 2_000_000L
private const val MAX_PAYLOAD_BYTES = 8 * 1024 * 1024L
