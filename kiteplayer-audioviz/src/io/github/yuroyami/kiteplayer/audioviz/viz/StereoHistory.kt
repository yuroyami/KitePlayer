@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package io.github.yuroyami.kiteplayer.audioviz.viz

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference

/**
 * The last few seconds of the source as a stereo pair, for a drawing that analyses raw samples.
 *
 * A drawing receives the frame for the instant it shows, and that instant is some hundreds of
 * milliseconds behind the newest samples the analyser has been fed. So one analysis window at the
 * write head is not enough: this keeps [SECONDS] of audio, indexed by sample, and a drawing copies
 * the stretch that ends where it wants on its own thread.
 *
 * The pair is what a browser's analyser would see after its default downmix to two channels: mono
 * is copied to both sides, four channels are averaged into two, 5.1 adds its centre and surrounds at
 * -3 dB and drops the low-frequency channel, and any other layout gives its first two channels.
 * Samples are sanitised as the analyser sanitises them.
 *
 * The storage (about 1.2 MB at 48 kHz) exists only while a drawing reads it. The first read asks
 * for it, the next write creates it, and it is dropped after [IDLE_SECONDS] without a read. Until
 * it has filled, a read gives zeros for what is not held, as a browser's analyser does just after
 * the page starts.
 *
 * The analyser writes, on its own thread. Any number of drawings read, each on its own thread.
 */
internal class StereoHistory(val sampleRate: Int) {

    /** Samples held per channel: [SECONDS] of audio, capped for very high sample rates. */
    val capacity: Int = minOf(sampleRate.toLong() * SECONDS, MOST_SAMPLES.toLong()).toInt()

    private class Store(capacity: Int) {
        val left = FloatArray(capacity)
        val right = FloatArray(capacity)
    }

    /**
     * What a reader may rely on: everything the writer published with it was written before it.
     * [written] counts samples since the last reset; [filledFrom] is the first one the store holds.
     */
    private class Mark(
        val epoch: Long,
        val revision: Long,
        val originMicros: Long,
        val written: Long,
        val filledFrom: Long,
        val channels: Int,
        val store: Store?,
    )

    private val mark = AtomicReference(Mark(0L, Long.MIN_VALUE, NO_ORIGIN, 0L, 0L, 0, null))
    private val wanted = AtomicBoolean(false)

    // Writer only.
    private var epoch = 0L
    private var originMicros = NO_ORIGIN
    private var written = 0L
    private var filledFrom = 0L
    private var channels = 0
    private var store: Store? = null
    private var idleSamples = 0L
    private val idleLimit = sampleRate.toLong() * IDLE_SECONDS

    /**
     * Appends [frames] frames of [sourceChannels]-channel [interleaved] audio. [startMicros] is the
     * media time of sample zero since the last [reset], or null while unknown; [revision] is the
     * analysis revision the frames of this audio carry. Writer thread only.
     */
    fun write(interleaved: FloatArray, frames: Int, sourceChannels: Int, startMicros: Long?, revision: Long) {
        if (frames <= 0 || sourceChannels <= 0) return
        if (originMicros == NO_ORIGIN && startMicros != null) originMicros = startMicros
        channels = sourceChannels
        val asked = wanted.exchange(false)
        idleSamples = if (asked) 0L else idleSamples + frames
        var target = store
        if (target == null && asked) {
            target = Store(capacity)
            store = target
            filledFrom = written
        } else if (target != null && idleSamples > idleLimit) {
            target = null
            store = null
        }
        if (target != null) {
            var at = (written % capacity).toInt()
            for (frame in 0 until frames) {
                val base = frame * sourceChannels
                val left: Float
                val right: Float
                when (sourceChannels) {
                    1 -> { left = clean(interleaved[base]); right = left }
                    4 -> {
                        left = 0.5f * (clean(interleaved[base]) + clean(interleaved[base + 2]))
                        right = 0.5f * (clean(interleaved[base + 1]) + clean(interleaved[base + 3]))
                    }
                    6 -> {
                        val centre = clean(interleaved[base + 2])
                        left = clean(interleaved[base]) + HALF_POWER * (centre + clean(interleaved[base + 4]))
                        right = clean(interleaved[base + 1]) + HALF_POWER * (centre + clean(interleaved[base + 5]))
                    }
                    else -> { left = clean(interleaved[base]); right = clean(interleaved[base + 1]) }
                }
                target.left[at] = left
                target.right[at] = right
                if (++at == capacity) at = 0
            }
        }
        written += frames
        mark.store(Mark(epoch, revision, originMicros, written, filledFrom, channels, target))
    }

    /** Forgets every sample, as the analyser does at a seek. The storage is kept. Writer thread only. */
    fun reset() {
        epoch++
        originMicros = NO_ORIGIN
        written = 0L
        filledFrom = 0L
        channels = 0
        mark.store(Mark(epoch, Long.MIN_VALUE, NO_ORIGIN, 0L, 0L, 0, store))
    }

    /**
     * The index of the sample at [micros], for audio of analysis [revision], or null when that audio
     * is no longer this history's or its time is not known yet. Rounds up, so the index of a time
     * the analyser worked out from a sample index is that sample index.
     */
    fun indexAt(revision: Long, micros: Long): Long? {
        wanted.store(true)
        val now = mark.load()
        if (now.revision != revision || now.originMicros == NO_ORIGIN) return null
        val scaled = (micros - now.originMicros) * sampleRate
        val whole = scaled / 1_000_000L
        return if (scaled > whole * 1_000_000L) whole + 1 else whole
    }

    /** How many channels the source of analysis [revision] had, or 0 when unknown. */
    fun sourceChannels(revision: Long): Int {
        val now = mark.load()
        return if (now.revision == revision) now.channels else 0
    }

    /**
     * Copies [count] samples of each channel, starting at sample [from], into [left] and [right]
     * at [offset]. What is not held, not yet written, or overwritten while copying reads zero.
     * Answers how many of the samples were held. Any thread.
     */
    fun read(revision: Long, from: Long, left: FloatArray, right: FloatArray, offset: Int = 0, count: Int = left.size - offset): Int {
        wanted.store(true)
        val before = mark.load()
        val source = before.store
        if (before.revision != revision || source == null) {
            left.fill(0f, offset, offset + count)
            right.fill(0f, offset, offset + count)
            return 0
        }
        val end = from + count
        val low = maxOf(from, before.filledFrom, before.written - capacity)
        val high = minOf(end, before.written)
        if (low >= high) {
            left.fill(0f, offset, offset + count)
            right.fill(0f, offset, offset + count)
            return 0
        }
        copy(source, low, high, left, right, offset + (low - from).toInt())
        val after = mark.load()
        // A reset while copying spoils all of it; a writer that lapped the ring spoils the oldest part.
        val kept = if (after.epoch != before.epoch || after.store !== source) high else maxOf(low, after.written - capacity)
        val heldFrom = minOf(maxOf(low, kept), high)
        left.fill(0f, offset, offset + (heldFrom - from).toInt())
        right.fill(0f, offset, offset + (heldFrom - from).toInt())
        left.fill(0f, offset + (high - from).toInt(), offset + count)
        right.fill(0f, offset + (high - from).toInt(), offset + count)
        return (high - heldFrom).toInt()
    }

    private fun copy(source: Store, from: Long, until: Long, left: FloatArray, right: FloatArray, at: Int) {
        var index = (from % capacity).toInt()
        var remaining = (until - from).toInt()
        var into = at
        while (remaining > 0) {
            val run = minOf(remaining, capacity - index)
            source.left.copyInto(left, into, index, index + run)
            source.right.copyInto(right, into, index, index + run)
            into += run
            remaining -= run
            index = 0
        }
    }

    private fun clean(sample: Float): Float = if (sample.isFinite()) sample.coerceIn(-16f, 16f) else 0f

    internal companion object {
        /** Seconds held: one long analysis window plus the time a shown frame trails the analyser. */
        const val SECONDS = 3

        /** Seconds without a read before the storage is let go. */
        const val IDLE_SECONDS = 30L

        /** 2.7 seconds at 192 kHz, 4 MB for both channels. */
        const val MOST_SAMPLES = 1 shl 19

        private const val NO_ORIGIN = Long.MIN_VALUE
        private const val HALF_POWER = 0.70710677f
    }
}
