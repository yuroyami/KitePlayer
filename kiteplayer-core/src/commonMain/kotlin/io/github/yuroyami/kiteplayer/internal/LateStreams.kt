package io.github.yuroyami.kiteplayer.internal

import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.spi.PlayerPacket

/**
 * The demux lane's side of a stream that appears after the open (#509): which streams it asked the
 * source for, and the packets of a new stream that wait for their queue.
 *
 * The source announces a stream on a packet, and the lane must ask for it before its next read, or
 * the source skips it from then on. The queue the stream's packets go to belongs to the session,
 * which only the actor changes, so the actor makes it a moment later. Until it does, this holds the
 * stream's packets in the order they were read, and hands them over first once the queue is there.
 *
 * Owned by one demux lane and touched by nothing else, so it needs no lock.
 */
internal class LateStreams(
    /** The streams the open asked the source for. */
    selection: Set<Int>,
) {
    /** The streams the source reads for this lane now: the open's, and every one added since. */
    var selection: Set<Int> = selection
        private set

    /** The streams asked for since the open whose packets go here until their queue exists. */
    private val waiting = HashSet<Int>()

    private val held = ArrayDeque<PlayerPacket>()
    private var heldBytes = 0L

    /** True when nothing waits, which is every file whose streams never change. */
    val idle: Boolean get() = waiting.isEmpty()

    /** The source reads [indexes] from now on, and their packets wait here until their queues exist. */
    fun added(indexes: Collection<Int>) {
        selection = selection + indexes
        waiting += indexes
    }

    /**
     * The source reads exactly [indexes] from now on, as a switch to a sound that is a download of
     * its own asks (#455). Those streams' queues exist already, so none of them waits.
     */
    fun reselect(indexes: Set<Int>) {
        selection = indexes
        waiting.retainAll(indexes)
    }

    /** Whether a packet of the stream at [index] must wait, because its queue was not there when it was last asked. */
    fun waits(index: Int): Boolean = index in waiting

    /**
     * Keeps [packet] for its queue. Past [HELD_BYTES_LIMIT] the oldest goes, which happens only when
     * the actor cannot take a new stream for seconds of reading.
     */
    fun hold(packet: PlayerPacket) {
        held.addLast(packet)
        heldBytes += packet.sizeBytes
        while (heldBytes > HELD_BYTES_LIMIT && held.size > 1) {
            val oldest = held.removeFirst()
            heldBytes -= oldest.sizeBytes
            oldest.close()
        }
    }

    /**
     * Hands each waiting stream whose queue [queueOf] now finds its held packets, under [epoch], and
     * stops it waiting. A queue that arrives after the media ended, as [ended] says, is told so too,
     * because the end the lane announced before it existed never reached it.
     */
    fun deliver(queueOf: (Int) -> PacketQueue?, epoch: Generation, ended: Boolean) {
        if (waiting.isEmpty()) return
        val arrived = waiting.mapNotNull { index -> queueOf(index)?.let { index to it } }
        if (arrived.isEmpty()) return
        val queues = arrived.toMap()
        val kept = ArrayDeque<PlayerPacket>(held.size)
        while (held.isNotEmpty()) {
            val packet = held.removeFirst()
            val queue = queues[packet.streamIndex]
            if (queue == null) {
                kept.addLast(packet)
            } else {
                heldBytes -= packet.sizeBytes
                // A queue at another epoch closes it, which is right: it was read before a seek.
                queue.offer(packet, epoch)
            }
        }
        held.addAll(kept)
        for ((index, queue) in arrived) {
            waiting.remove(index)
            if (ended) queue.signalEndOfStream(epoch)
        }
    }

    /** Closes every held packet, because a seek moved the reads and they belong before it. */
    fun dropHeld() {
        while (held.isNotEmpty()) held.removeFirst().close()
        heldBytes = 0
    }

    /** Closes every held packet; the lane is ending. */
    fun close() {
        dropHeld()
        waiting.clear()
    }

    companion object {
        /** How many bytes of a new stream's packets may wait for its queue: seconds of a broadcast. */
        const val HELD_BYTES_LIMIT: Long = 8L * 1024 * 1024
    }
}
