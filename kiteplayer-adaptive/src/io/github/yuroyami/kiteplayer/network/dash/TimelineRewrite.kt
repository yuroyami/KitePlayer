package io.github.yuroyami.kiteplayer.network.dash

/**
 * A later Period's WebM clusters moved onto the presentation's timeline (#403). A block's time
 * counts from its cluster's `Timestamp`, so moving each cluster's timestamp moves every block in
 * it. The new timestamp is written in eight bytes and the cluster's size as unknown, so the
 * element keeps its place however many digits the time gains. Elements that are not clusters pass
 * through as they are.
 */
internal object WebmRewrite {

    /** [segment] with each cluster's timestamp moved by [shiftTicks] of the file's timestamp scale. */
    fun shiftClusters(segment: ByteArray, shiftTicks: Long): ByteArray {
        if (shiftTicks == 0L) return segment
        val out = ArrayList<ByteArray>()
        var at = 0
        while (at < segment.size) {
            val id = vint(segment, at, keepMarker = true) ?: break
            val size = vint(segment, at + id.length, keepMarker = false) ?: break
            val dataStart = at + id.length + size.length
            if (id.value != CLUSTER) {
                val end = if (size.value == UNKNOWN) segment.size else minOf(segment.size.toLong(), dataStart + size.value).toInt()
                out += segment.copyOfRange(at, end)
                at = end
                continue
            }
            // A cluster of unknown size runs until the next element of its own level.
            var end = if (size.value == UNKNOWN) segment.size else minOf(segment.size.toLong(), dataStart + size.value).toInt()
            var child = dataStart
            val kept = ArrayList<ByteArray>()
            var timestamp: Long? = null
            while (child < end) {
                val childId = vint(segment, child, keepMarker = true) ?: break
                if (size.value == UNKNOWN && childId.value in LEVEL_ONE) {
                    end = child
                    break
                }
                val childSize = vint(segment, child + childId.length, keepMarker = false) ?: break
                val childData = child + childId.length + childSize.length
                val childEnd = if (childSize.value == UNKNOWN) end else minOf(end.toLong(), childData + childSize.value).toInt()
                if (childId.value == TIMESTAMP && timestamp == null) {
                    var value = 0L
                    for (i in childData until childEnd) value = (value shl 8) or (segment[i].toLong() and 0xFF)
                    timestamp = value
                } else {
                    kept += segment.copyOfRange(child, childEnd)
                }
                child = childEnd
            }
            val moved = (timestamp ?: 0L) + shiftTicks
            if (moved < 0) throw DashUnsupportedException("a Period's WebM clusters lie before the start of the presentation")
            out += CLUSTER_HEADER
            out += byteArrayOf(0xE7.toByte(), 0x88.toByte()) + ByteArray(8) { ((moved shr (8 * (7 - it))) and 0xFF).toByte() }
            out.addAll(kept)
            at = end
        }
        val total = out.sumOf { it.size }
        val result = ByteArray(total)
        var cursor = 0
        for (part in out) {
            part.copyInto(result, cursor)
            cursor += part.size
        }
        return result
    }

    private class Vint(val value: Long, val length: Int)

    private fun vint(bytes: ByteArray, at: Int, keepMarker: Boolean): Vint? {
        if (at >= bytes.size) return null
        val first = bytes[at].toInt() and 0xFF
        if (first == 0) return null
        val length = first.countLeadingZeroBits() - 24 + 1
        if (at + length > bytes.size) return null
        var value = (if (keepMarker) first else first and (0xFF shr length)).toLong()
        var allOnes = value == (0xFF shr length).toLong()
        for (i in 1 until length) {
            val next = bytes[at + i].toInt() and 0xFF
            if (next != 0xFF) allOnes = false
            value = (value shl 8) or next.toLong()
        }
        return Vint(if (!keepMarker && allOnes) UNKNOWN else value, length)
    }

    private const val UNKNOWN = -1L
    private const val CLUSTER = 0x1F43B675L
    private const val TIMESTAMP = 0xE7L

    /** The IDs that end a cluster of unknown size: the elements of the Segment's own level. */
    private val LEVEL_ONE = setOf(CLUSTER, 0x1C53BB6BL, 0x1254C367L, 0x1549A966L, 0x1654AE6BL, 0x114D9B74L, 0x1941A469L, 0x1043A770L)

    /** A cluster's ID and a size of eight bytes of ones: unknown. */
    private val CLUSTER_HEADER = byteArrayOf(0x1F, 0x43, 0xB6.toByte(), 0x75, 0x01, -1, -1, -1, -1, -1, -1, -1)
}

/**
 * A later Period's MPEG-TS packets moved onto the presentation's timeline (#403): the PTS and DTS
 * of every PES header that starts in a packet, and every PCR, all in the 90 kHz clock and modulo
 * 2^33 as the format wraps them. Every field keeps its size, so packets keep theirs.
 */
internal object TsRewrite {

    /** [segment] with its times moved by [shift90k] ticks of the 90 kHz clock. */
    fun shift(segment: ByteArray, shift90k: Long): ByteArray {
        if (shift90k == 0L) return segment
        val out = segment.copyOf()
        var at = 0
        while (at + PACKET <= out.size) {
            if (out[at] != SYNC) {
                at++
                continue
            }
            val start = out[at + 1].toInt() and 0x40 != 0
            val control = (out[at + 3].toInt() shr 4) and 0x03
            var payload = at + 4
            if (control and 0x2 != 0) {
                val length = out[at + 4].toInt() and 0xFF
                if (length > 0 && out[at + 5].toInt() and 0x10 != 0 && length >= 7) shiftPcr(out, at + 6, shift90k)
                payload = at + 5 + length
            }
            if (start && control and 0x1 != 0 && payload + 14 <= at + PACKET) shiftPes(out, payload, at + PACKET, shift90k)
            at += PACKET
        }
        return out
    }

    private fun shiftPes(bytes: ByteArray, at: Int, end: Int, shift: Long) {
        if (bytes[at].toInt() != 0 || bytes[at + 1].toInt() != 0 || bytes[at + 2].toInt() != 1) return
        val streamId = bytes[at + 3].toInt() and 0xFF
        if (streamId in NO_HEADER_STREAMS) return
        val flags = (bytes[at + 7].toInt() shr 6) and 0x03
        if (flags and 0x2 != 0 && at + 14 <= end) writeTimestamp(bytes, at + 9, (readTimestamp(bytes, at + 9) + shift) and MASK_33)
        if (flags == 0x3 && at + 19 <= end) writeTimestamp(bytes, at + 14, (readTimestamp(bytes, at + 14) + shift) and MASK_33)
    }

    /** The 33-bit time of a PTS or DTS field: three, fifteen and fifteen bits around marker bits. */
    private fun readTimestamp(bytes: ByteArray, at: Int): Long {
        fun b(i: Int) = bytes[at + i].toLong() and 0xFF
        return ((b(0) shr 1) and 0x07 shl 30) or (b(1) shl 22) or ((b(2) shr 1) shl 15) or (b(3) shl 7) or (b(4) shr 1)
    }

    private fun writeTimestamp(bytes: ByteArray, at: Int, value: Long) {
        val prefix = bytes[at].toInt() and 0xF0
        bytes[at] = (prefix or (((value shr 30) and 0x07).toInt() shl 1) or 0x01).toByte()
        bytes[at + 1] = (value shr 22).toByte()
        bytes[at + 2] = ((((value shr 15) and 0x7F).toInt() shl 1) or 0x01).toByte()
        bytes[at + 3] = (value shr 7).toByte()
        bytes[at + 4] = (((value and 0x7F).toInt() shl 1) or 0x01).toByte()
    }

    /** The 33-bit base of a PCR moves; its extension, six reserved bits and nine bits of 27 MHz, stays. */
    private fun shiftPcr(bytes: ByteArray, at: Int, shift: Long) {
        var base = 0L
        for (i in 0 until 4) base = (base shl 8) or (bytes[at + i].toLong() and 0xFF)
        base = (base shl 1) or ((bytes[at + 4].toLong() and 0x80) shr 7)
        base = (base + shift) and MASK_33
        for (i in 0 until 4) bytes[at + i] = (base shr (25 - 8 * i)).toByte()
        bytes[at + 4] = (((base and 0x1).toInt() shl 7) or (bytes[at + 4].toInt() and 0x7F)).toByte()
    }

    private const val PACKET = 188
    private const val SYNC: Byte = 0x47
    private const val MASK_33 = (1L shl 33) - 1

    /** Stream ids whose PES packets have no optional header, so no times: ISO/IEC 13818-1, 2.4.3.7. */
    private val NO_HEADER_STREAMS = setOf(0xBC, 0xBE, 0xBF, 0xF0, 0xF1, 0xF2, 0xF8, 0xFF)
}
