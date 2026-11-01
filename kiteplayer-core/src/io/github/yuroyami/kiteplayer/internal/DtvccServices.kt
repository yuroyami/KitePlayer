package io.github.yuroyami.kiteplayer.internal

import io.github.yuroyami.kiteplayer.Generation

/**
 * The CEA-708 caption services inside the A/53 bytes of a video stream's pictures (#678).
 *
 * One picture's `cc_data` is a run of triplets: a byte with the marker bits, `cc_valid` and
 * `cc_type`, then two bytes of data. Types 0 and 1 are the CEA-608 pairs of fields 1 and 2, which
 * FFmpeg's caption decoder reads for CC1. Types 3 and 2 carry DTVCC, CEA-708's own layer: a 3 starts
 * a caption channel packet, whose first byte holds a sequence number and the packet's size, and a 2
 * continues it. A packet is a run of service blocks, each a header with the service's number and the
 * block's size, then that many bytes of the service's own commands and text. A packet can span many
 * pictures, so this keeps the part of one already read.
 *
 * Each service is a caption track of its own, Service 1 for the primary language, Service 2 for the
 * secondary and so on, so [read] answers each service's bytes apart, and the engine queues them as
 * that track's packets at the time of the picture that completed the packet. The service decoder
 * reads them in order and needs nothing of the transport.
 *
 * Video lane, as the caption tracks are.
 */
internal class DtvccServices {

    private val packet = ByteArray(MAX_PACKET_BYTES)

    /** The bytes the packet being read holds, its header included, or 0 when none is open. */
    private var expected = 0
    private var filled = 0
    private var epoch: Generation? = null

    /**
     * Reads [triplets], one picture's `cc_data`, and gives the bytes of each service that a packet
     * completed in them, by service number, in the order the packets gave them. A new [epoch] drops
     * the part of a packet that a seek cut.
     */
    fun read(triplets: ByteArray, epoch: Generation): Map<Int, ByteArray> {
        if (this.epoch != epoch) {
            this.epoch = epoch
            expected = 0
            filled = 0
        }
        var out: MutableMap<Int, ByteArray>? = null
        val finish = {
            if (expected > 0) {
                out = splitServices(out)
                expected = 0
                filled = 0
            }
        }
        var at = 0
        while (at + 3 <= triplets.size) {
            val marker = triplets[at].toInt()
            val first = triplets[at + 1]
            val second = triplets[at + 2]
            at += 3
            val valid = marker and 0x04 != 0
            when (marker and 0x03) {
                // A pair of CEA-608, or one that carries nothing.
                0, 1 -> continue
                PACKET_START -> {
                    if (!valid) continue
                    // A start before the last packet filled ends it with what it holds.
                    finish()
                    val sizeCode = first.toInt() and 0x3F
                    expected = if (sizeCode == 0) MAX_PACKET_BYTES else sizeCode * 2
                    packet[0] = first
                    packet[1] = second
                    filled = 2
                }
                else -> {
                    // A data pair with no open packet belongs to one whose start was lost.
                    if (!valid || expected == 0) continue
                    if (filled < expected) packet[filled++] = first
                    if (filled < expected) packet[filled++] = second
                }
            }
            if (expected > 0 && filled >= expected) finish()
        }
        return out ?: emptyMap()
    }

    /**
     * Adds the service blocks of the packet read so far to [into]. A block that runs past what
     * arrived is dropped whole, because half a command reads as other commands.
     */
    private fun splitServices(into: MutableMap<Int, ByteArray>?): MutableMap<Int, ByteArray>? {
        var out = into
        var at = 1
        while (at < filled) {
            val header = packet[at++].toInt() and 0xFF
            var service = header ushr 5
            val size = header and 0x1F
            if (service == EXTENDED_SERVICE && size != 0) {
                if (at >= filled) break
                service = packet[at++].toInt() and 0x3F
            }
            // The null block: the rest of the packet is padding.
            if (service == 0) break
            if (at + size > filled) break
            if (size > 0) {
                val block = packet.copyOfRange(at, at + size)
                val map = out ?: LinkedHashMap<Int, ByteArray>().also { out = it }
                map[service] = map[service]?.plus(block) ?: block
            }
            at += size
        }
        return out
    }

    private companion object {
        const val PACKET_START = 3
        const val EXTENDED_SERVICE = 7

        /** Size code 0 is the largest packet, 64 pairs. */
        const val MAX_PACKET_BYTES = 128
    }
}
