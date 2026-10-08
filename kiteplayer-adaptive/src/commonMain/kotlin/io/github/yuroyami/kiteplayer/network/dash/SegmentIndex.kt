package io.github.yuroyami.kiteplayer.network.dash

/**
 * One subsegment that a segment index (`sidx`) names: the bytes that hold it in the file, and the
 * time it covers, in microseconds from the index's earliest presentation time.
 */
internal class IndexedSubsegment(val range: LongRange, val startMicros: Long, val durationMicros: Long)

/** A box of a file: its type, and its whole size, header included. */
internal class Box(val type: String, val size: Long)

/**
 * The segment index of an ISO BMFF file read as ISO/IEC 14496-12, section 8.16.3, defines it.
 *
 * A single-file DASH representation (`SegmentBase`) names its subsegments only there: each
 * reference gives a size, laid end to end from the first byte after the index plus its
 * `first_offset`, and a duration in the index's timescale.
 */
internal object SegmentIndex {

    /** The most references one index may hold. A day of one second fragments is 86,400. */
    const val MAX_REFERENCES: Int = 200_000

    /**
     * The subsegments of the `sidx` box in [box], which holds the box from its first byte and
     * which sits at [boxOffset] in its file. An index that refers to further indexes, rather than
     * to media, is refused: this tier reads one level.
     */
    fun parse(box: ByteArray, boxOffset: Long): List<IndexedSubsegment> {
        val reader = Reader(box)
        val header = reader.boxHeader() ?: throw IllegalArgumentException("the segment index is shorter than a box header")
        require(header.type == "sidx") { "expected a sidx box, found '${header.type}'" }
        require(header.size <= box.size) { "the segment index box needs ${header.size} bytes and ${box.size} were read" }
        val version = reader.u8()
        reader.skip(3) // flags
        reader.skip(4) // reference_ID
        val timescale = reader.u32()
        require(timescale > 0) { "a segment index timescale must be positive, not $timescale" }
        val earliest = if (version == 0) reader.u32() else reader.u64()
        val firstOffset = if (version == 0) reader.u32() else reader.u64()
        reader.skip(2) // reserved
        val count = reader.u16()
        require(count <= MAX_REFERENCES) { "the segment index names $count references, and the limit is $MAX_REFERENCES" }
        var offset = boxOffset + header.size + firstOffset
        var time = earliest
        val out = ArrayList<IndexedSubsegment>(count)
        repeat(count) {
            val word = reader.u32()
            if (word ushr 31 == 1L) {
                throw DashUnsupportedException("the segment index refers to further indexes, and this tier reads one level")
            }
            val size = word and 0x7FFF_FFFF
            val duration = reader.u32()
            reader.skip(4) // starts_with_SAP, SAP_type, SAP_delta_time
            require(size > 0) { "a segment index reference of $size bytes" }
            out += IndexedSubsegment(
                range = offset until offset + size,
                startMicros = microsOf(time, timescale),
                durationMicros = microsOf(duration, timescale),
            )
            offset += size
            time += duration
        }
        return out
    }

    /**
     * The box whose header starts [bytes], or null when its header is cut short or its size is
     * one a box cannot have. The box itself may end past [bytes].
     */
    fun first(bytes: ByteArray): Box? {
        val header = try {
            Reader(bytes).boxHeader()
        } catch (_: IllegalArgumentException) {
            null
        } ?: return null
        return if (header.size < 8) null else Box(header.type, header.size)
    }

    private fun microsOf(value: Long, timescale: Long): Long =
        value / timescale * 1_000_000 + value % timescale * 1_000_000 / timescale

    private class Header(val size: Long, val type: String)

    private class Reader(private val bytes: ByteArray, private var at: Int = 0) {
        fun need(count: Int) {
            if (count > bytes.size - at) throw IllegalArgumentException("the segment index ends early")
        }

        fun u8(): Int {
            need(1)
            return bytes[at++].toInt() and 0xFF
        }

        fun u16(): Int = (u8() shl 8) or u8()

        fun u32(): Long {
            need(4)
            var value = 0L
            repeat(4) { value = (value shl 8) or (bytes[at++].toLong() and 0xFF) }
            return value
        }

        fun u64(): Long {
            val high = u32()
            val low = u32()
            require(high < 0x8000_0000L) { "a 64-bit segment index field passes the range of a Long" }
            return (high shl 32) or low
        }

        fun skip(count: Int) {
            need(count)
            at += count
        }

        /** The size and type of the box at the read position, or null when fewer than 8 bytes remain. */
        fun boxHeader(): Header? {
            if (bytes.size - at < 8) return null
            val size32 = u32()
            val type = CharArray(4) { (u8()).toChar() }.concatToString()
            val size = when (size32) {
                1L -> u64()
                else -> size32
            }
            return Header(size, type)
        }
    }
}
