package io.github.yuroyami.kiteplayer.internal

import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.spi.PlayerPacket
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel

/**
 * Compressed packets for one stream, on their way from the demux worker to a decoder.
 *
 * Single producer, single consumer. The demux worker writes and one decoder worker reads, which is
 * what lets the wake-up mechanism be two conflated channels instead of a condition variable.
 *
 * Every packet carries the [Generation] it was read under. On a seek the core bumps the generation
 * and flushes, and anything that slips through afterwards is filtered by comparison at the next hop.
 * That single integer replaces a flush handshake across four workers.
 *
 * Accounting is kept in three dimensions because each answers a different question. Packet count and
 * byte total bound memory. Media duration bounds latency, and it is the only one of the three that
 * means the same thing for a 4K video stream and for a low bitrate audio stream. ffplay's network
 * latency servo measures buffer health in packet counts, and that is exactly why it behaves
 * differently on every stream.
 */
internal class PacketQueue(
    val streamIndex: Int,
    /** Marks the stream well buffered. Does not stall the producer. */
    private val softLimitUs: Long,
) {
    private val lock = SynchronizedObject()
    private val items = ArrayDeque<Entry>()

    private var bytes = 0L
    private var durationUs = 0L
    private var generation = Generation.Initial
    private var closed = false
    private var endOfStream = false

    /** Conflated, so a signal sent before the consumer waits is kept rather than lost. */
    private val notEmpty = Channel<Unit>(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val drained = Channel<Unit>(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private class Entry(val packet: PlayerPacket, val generation: Generation, val bytes: Int, val durationUs: Long)

    val count: Int get() = synchronized(lock) { items.size }
    val bytesBuffered: Long get() = synchronized(lock) { bytes }
    val bufferedUs: Long get() = synchronized(lock) { durationUs }
    val isEndOfStream: Boolean get() = synchronized(lock) { endOfStream }

    /** True when this stream holds enough to start, or has nothing more to give. */
    fun isReady(readyUs: Long, readyPackets: Int): Boolean = synchronized(lock) {
        endOfStream || durationUs >= readyUs || items.size >= readyPackets
    }

    /** True when the stream is buffered past its soft target. Only informs the buffering state. */
    val isWellBuffered: Boolean get() = synchronized(lock) { endOfStream || durationUs >= softLimitUs }

    /**
     * Adds a packet. Never suspends and never rejects.
     *
     * Backpressure is deliberately not here. A per-stream limit that stalls the producer deadlocks
     * on badly interleaved files: the video queue fills, the demuxer stops, the audio decoder
     * starves, the audio clock stops, and the video is never consumed. The stall decision belongs to
     * the core, which can see every stream at once.
     */
    fun offer(packet: PlayerPacket, generation: Generation) {
        val accepted = synchronized(lock) {
            if (closed || generation != this.generation) {
                false
            } else {
                val durationUs = packet.duration?.micros ?: 0L
                items.addLast(Entry(packet, generation, packet.sizeBytes, durationUs))
                bytes += packet.sizeBytes
                this.durationUs += durationUs
                true
            }
        }
        if (accepted) notEmpty.trySend(Unit) else packet.close()
    }

    /**
     * Marks the end of the stream.
     *
     * The consumer sees this as a null packet from [receive], which is exactly the in-band drain
     * signal libavcodec expects. An in-band signal cannot overtake real data and needs no side
     * channel.
     */
    fun signalEndOfStream(generation: Generation) {
        synchronized(lock) {
            if (generation != this.generation) return
            endOfStream = true
        }
        notEmpty.trySend(Unit)
    }

    /**
     * Takes the next packet of the current generation.
     *
     * @return the packet, or null when the stream has ended or the queue was closed. Packets from a
     *         superseded generation are closed and skipped without being returned.
     */
    suspend fun receive(): PlayerPacket? {
        while (true) {
            val result: TakeResult = synchronized(lock) {
                var taken: PlayerPacket? = null
                while (items.isNotEmpty()) {
                    val entry = items.removeFirst()
                    bytes -= entry.bytes
                    durationUs -= entry.durationUs
                    if (entry.generation != generation) {
                        entry.packet.close()
                        continue
                    }
                    taken = entry.packet
                    break
                }
                val packet = taken
                when {
                    packet != null -> TakeResult.Taken(packet)
                    closed -> TakeResult.Closed
                    endOfStream -> TakeResult.Ended
                    else -> TakeResult.Empty
                }
            }
            when (result) {
                is TakeResult.Taken -> {
                    drained.trySend(Unit)
                    return result.packet
                }
                TakeResult.Closed, TakeResult.Ended -> return null
                TakeResult.Empty -> notEmpty.receive()
            }
        }
    }

    private sealed interface TakeResult {
        class Taken(val packet: PlayerPacket) : TakeResult
        data object Empty : TakeResult
        data object Closed : TakeResult
        data object Ended : TakeResult
    }

    /** Suspends until the queue has been read from, or until it is closed. */
    suspend fun awaitDrain() {
        if (synchronized(lock) { closed }) return
        drained.receive()
    }

    /**
     * Discards everything and moves to [generation].
     *
     * Called during a seek, after the demux worker has acknowledged that it stopped reading and
     * before any decoder is flushed. That order is what stops a packet of the old generation from
     * reaching a freshly flushed decoder.
     */
    fun flushTo(generation: Generation) {
        val toClose = synchronized(lock) {
            val pending = items.toList()
            items.clear()
            bytes = 0
            durationUs = 0
            endOfStream = false
            this.generation = generation
            pending
        }
        toClose.forEach { it.packet.close() }
        // Wake both ends: a consumer waiting for a packet must re-check, and a producer waiting for
        // room must learn there is room.
        notEmpty.trySend(Unit)
        drained.trySend(Unit)
    }

    /**
     * Drops packets from the newest end.
     *
     * Used only for the pathological interleaving case, where one
     * stream must be truncated so another can keep playing. A gap in one stream beats a frozen
     * player.
     *
     * @return how many packets were dropped.
     */
    fun dropFromTail(targetBytes: Long): Int {
        val toClose = mutableListOf<PlayerPacket>()
        synchronized(lock) {
            while (bytes > targetBytes && items.isNotEmpty()) {
                val entry = items.removeLast()
                bytes -= entry.bytes
                durationUs -= entry.durationUs
                toClose += entry.packet
            }
        }
        toClose.forEach { it.close() }
        return toClose.size
    }

    fun close() {
        val toClose = synchronized(lock) {
            if (closed) return
            closed = true
            val pending = items.toList()
            items.clear()
            bytes = 0
            durationUs = 0
            pending
        }
        toClose.forEach { it.packet.close() }
        notEmpty.trySend(Unit)
        drained.trySend(Unit)
    }
}
