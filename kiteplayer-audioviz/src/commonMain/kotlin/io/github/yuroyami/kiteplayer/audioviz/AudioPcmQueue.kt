@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import kotlin.concurrent.atomics.AtomicLong

/** Preallocated single-producer/single-consumer PCM storage. A peeked slot stays owned until release. */
internal class AudioPcmQueue(
    val capacity: Int = 16,
    val samplesPerSlot: Int = 4096,
    val maxQueuedNanos: Long = 250_000_000L,
) {
    init {
        require(capacity in 2..64)
        require(samplesPerSlot in 2..32_768)
        require(maxQueuedNanos in 1..1_000_000_000L)
    }

    enum class Offer { Accepted, Full, TooMuchTime, Invalid }

    class Slot(samples: Int) {
        val samples = FloatArray(samples)
        var frames = 0
        var ptsMicros = 0L
        var generation = Generation.Initial
        var revision = 0L
        var format: AudioFormat? = null
        internal var endNanos = 0L
        internal var sequence = -1L
    }

    private val slots = Array(capacity) { Slot(samplesPerSlot) }
    private val written = AtomicLong(0L)
    private val consumed = AtomicLong(0L)
    private val producedNanos = AtomicLong(0L)
    private val releasedNanos = AtomicLong(0L)

    val reservedBytes: Long = capacity.toLong() * samplesPerSlot * Float.SIZE_BYTES
    val pendingSlots: Int get() {
        val start = consumed.load()
        return (written.load() - start).coerceIn(0L, capacity.toLong()).toInt()
    }
    val pendingNanos: Long get() {
        val start = releasedNanos.load()
        return (producedNanos.load() - start).coerceIn(0L, maxQueuedNanos)
    }

    /** Producer only. Rejects a whole block before copying; never waits for a free slot. */
    fun offer(generation: Generation, revision: Long, ptsMicros: Long, samples: FloatArray, frames: Int, format: AudioFormat): Offer {
        val channels = format.channels
        if (channels <= 0 || channels > samplesPerSlot || format.sampleRate <= 0 ||
            frames <= 0 || frames > samples.size / channels
        ) return Offer.Invalid
        val framesPerSlot = samplesPerSlot / channels
        val needed = (frames.toLong() + framesPerSlot - 1) / framesPerSlot
        val end = written.load()
        if (needed > capacity - (end - consumed.load())) return Offer.Full
        val baseNanos = producedNanos.load()
        val duration = frames * 1_000_000_000L / format.sampleRate
        if (duration > maxQueuedNanos - (baseNanos - releasedNanos.load())) return Offer.TooMuchTime

        var offset = 0
        var sequence = end
        while (offset < frames) {
            val slot = slots[(sequence % capacity).toInt()]
            val count = minOf(framesPerSlot, frames - offset)
            samples.copyInto(slot.samples, 0, offset * channels, (offset + count) * channels)
            slot.frames = count
            slot.ptsMicros = ptsMicros + offset * 1_000_000L / format.sampleRate
            slot.generation = generation
            slot.revision = revision
            slot.format = format
            slot.sequence = sequence
            offset += count
            slot.endNanos = baseNanos + offset * 1_000_000_000L / format.sampleRate
            sequence++
        }
        producedNanos.store(baseNanos + duration)
        // Release publishes every copied sample and metadata field before the consumer can peek.
        written.store(sequence)
        return Offer.Accepted
    }

    /** Consumer only. The same slot remains leased until [release], including during analysis. */
    fun peek(): Slot? {
        val start = consumed.load()
        if (start >= written.load()) return null
        return slots[(start % capacity).toInt()]
    }

    /** Consumer only. Release after every use of the samples has ended, normally in finally. */
    fun release(slot: Slot) {
        val start = consumed.load()
        check(start < written.load() && slots[(start % capacity).toInt()] === slot && slot.sequence == start)
        releasedNanos.store(slot.endNanos)
        slot.format = null
        // Release makes the slot reusable only after the consumer has stopped touching it.
        consumed.store(start + 1)
    }
}
