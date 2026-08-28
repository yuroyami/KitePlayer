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

    private class Entry(
        val packet: PlayerPacket,
        val generation: Generation,
        val bytes: Int,
        val durationUs: Long,
        val startUs: Long?,
        val endUs: Long?,
    )

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
                val knownDurationUs = packet.duration?.micros
                val durationUs = knownDurationUs ?: 0L
                val startUs = packet.pts?.micros ?: packet.dts?.micros
                items.addLast(
                    Entry(
                        packet = packet,
                        generation = generation,
                        bytes = packet.sizeBytes,
                        durationUs = durationUs,
                        startUs = startUs,
                        endUs = startUs?.let { start -> knownDurationUs?.let { start + it } },
                    ),
                )
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
     * Takes the next packet of the current generation without suspending.
     *
     * The non-suspending form is what a worker that must stay interruptible uses. Wrapping the suspending
     * [receive] in a timeout is not the same thing: a receive that has already taken a packet can still be
     * cancelled before it delivers it, and that packet is then owned by nobody and never closed.
     *
     * @return the packet, or null when nothing of the current generation is queued, whether because the
     *         queue is empty, has ended, or was closed. Packets from a superseded generation are closed and
     *         skipped rather than returned.
     */
    fun poll(): PlayerPacket? {
        val taken = synchronized(lock) {
            var found: PlayerPacket? = null
            while (items.isNotEmpty()) {
                val entry = items.removeFirst()
                bytes -= entry.bytes
                durationUs -= entry.durationUs
                if (entry.generation != generation) {
                    entry.packet.close()
                    continue
                }
                found = entry.packet
                break
            }
            found
        }
        if (taken != null) drained.trySend(Unit)
        return taken
    }

    /**
     * Suspends until there may be something to [poll], or until the queue ends or closes.
     *
     * The wake-up is advisory: the signal is conflated and a cancellation can consume one, so a caller
     * bounds this wait and polls again afterwards. No packet can be lost that way, because nothing is taken
     * out of the queue here.
     */
    suspend fun awaitData() {
        if (synchronized(lock) { items.isNotEmpty() || closed || endOfStream }) return
        notEmpty.receive()
    }

    /**
     * Takes the next packet of the current generation, suspending until there is one.
     *
     * @return the packet, or null when the stream has ended or the queue was closed. Packets from a
     *         superseded generation are closed and skipped without being returned.
     */
    suspend fun receive(): PlayerPacket? {
        while (true) {
            poll()?.let { return it }
            val done = synchronized(lock) { closed || endOfStream }
            if (done) return null
            notEmpty.receive()
        }
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
     * Legal only at the tail of a run that has not been decoded yet. Compressed frames reference each
     * other, so dropping an arbitrary packet from the middle of a group of pictures leaves every later
     * frame in that group undecodable, and the picture breaks up instead of skipping. A discard that
     * is safe anywhere has to cut whole groups of pictures, which needs a cache that knows where they
     * begin; that cache does not exist yet, so nothing may call this outside the case above.
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

    /**
     * Drops timestamped packets that finish at or before [cutoffUs] from the oldest end.
     *
     * This is for inactive audio/subtitle switch caches only. A decoder consuming the queue must
     * never race it, and video must never use it: compressed video frames can reference packets
     * before the cutoff. A packet with no timestamp at all stops the trim so a fully opaque packet
     * is never guessed away. The caller owns the single-consumer guarantee by parking or selecting
     * another lane.
     *
     * @param assumedDurationUs stands in for a missing duration: a packet with a start but no
     *        duration is treated as ending at start plus this. FFmpeg leaves `AVPacket.duration`
     *        at zero routinely, and without a stand-in one such packet would stop this trim
     *        forever and let its lane grow until it owned the whole byte budget. Each caller
     *        states a bound generous for its lane kind rather than sharing one guess.
     */
    fun dropBefore(cutoffUs: Long, assumedDurationUs: Long): Int {
        val toClose = mutableListOf<PlayerPacket>()
        synchronized(lock) {
            while (items.isNotEmpty()) {
                val entry = items.first()
                val endUs = entry.endUs
                    ?: entry.startUs?.let { start -> start + assumedDurationUs }
                    ?: break
                if (endUs > cutoffUs) break
                items.removeFirst()
                bytes -= entry.bytes
                durationUs -= entry.durationUs
                toClose += entry.packet
            }
        }
        if (toClose.isNotEmpty()) drained.trySend(Unit)
        toClose.forEach { it.close() }
        return toClose.size
    }

    /** Oldest timestamp still retained, for validating a current-position switch cache. */
    val firstTimestampUs: Long?
        get() = synchronized(lock) { items.firstOrNull()?.startUs }

    /** Newest packet end still retained, for validating a current-position switch cache. */
    val lastTimestampUs: Long?
        get() = synchronized(lock) { items.lastOrNull()?.endUs }

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
