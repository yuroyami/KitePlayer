package io.github.yuroyami.kiteplayer.network.dash

/**
 * The index of a WebM file, which names its clusters the way a segment index (`sidx`) names the
 * fragments of an MP4 file: what a DASH representation of one WebM file needs to be played as
 * segments, each a run of clusters read by byte range (#401).
 *
 * WebM is Matroska, whose elements are EBML: an ID of one to four bytes, then a size of one to eight
 * bytes whose leading zero bits say its length, then the data. The `Cues` element lists, for each
 * cue point, its time and the position of the cluster that holds it, counted from the start of the
 * `Segment` element's data. The `Info` element's timestamp scale turns those times into nanoseconds.
 */
internal object WebmIndex {

    /** Where the parts of a WebM file are, read from its start. */
    class Layout(
        /** The byte the `Segment` element's data starts at, which cluster positions count from. */
        val segmentDataStart: Long,
        /** The byte after the `Segment` element, or null when its size is unknown. */
        val segmentEnd: Long?,
        /** Nanoseconds per timestamp tick. */
        val timestampScaleNanos: Long,
        /** The duration `Info` states, which it counts in timestamp ticks, in nanoseconds, or null when it states none. */
        val durationNanos: Double?,
        /** The byte the `Cues` element starts at, from the `SeekHead` or the bytes read, or null when neither says. */
        val cuesStart: Long?,
        /** Where the first cluster starts, or null when the bytes read end before one. */
        val firstCluster: Long?,
    )

    /** One cue point: its time in ticks of the timestamp scale, and its cluster's position in the segment's data. */
    class CuePoint(val time: Long, val clusterPosition: Long)

    /**
     * The layout read from [head], the first bytes of the file. The bytes need to reach past the
     * `Info` element; a `SeekHead` there names a `Cues` element anywhere in the file.
     */
    fun layout(head: ByteArray): Layout {
        val reader = Reader(head)
        val ebml = reader.element() ?: throw DashUnsupportedException("the file is too short to be WebM")
        if (ebml.id != EBML_HEADER) throw DashUnsupportedException("the file is not WebM: it starts with element ${hex(ebml.id)}")
        reader.skip(ebml)
        val segment = reader.element() ?: throw DashUnsupportedException("the WebM file has no Segment within its first ${head.size} bytes")
        if (segment.id != SEGMENT) throw DashUnsupportedException("the WebM file has element ${hex(segment.id)} where its Segment belongs")
        val dataStart = reader.position
        val segmentEnd = segment.size?.let { dataStart + it }
        var timestampScale = DEFAULT_TIMESTAMP_SCALE
        var duration: Double? = null
        var cuesStart: Long? = null
        var firstCluster: Long? = null
        while (true) {
            val child = reader.element() ?: break
            when (child.id) {
                SEEK_HEAD -> {
                    val end = reader.end(child)
                    while (reader.position < end) {
                        val seek = reader.element() ?: break
                        if (seek.id != SEEK) {
                            reader.skip(seek)
                            continue
                        }
                        val seekEnd = reader.end(seek)
                        var id: Long? = null
                        var position: Long? = null
                        while (reader.position < seekEnd) {
                            val field = reader.element() ?: break
                            when (field.id) {
                                SEEK_ID -> id = reader.unsigned(field)
                                SEEK_POSITION -> position = reader.unsigned(field)
                                else -> reader.skip(field)
                            }
                        }
                        if (id == CUES && position != null && cuesStart == null) cuesStart = dataStart + position
                    }
                }
                INFO -> {
                    val end = reader.end(child)
                    while (reader.position < end) {
                        val field = reader.element() ?: break
                        when (field.id) {
                            TIMESTAMP_SCALE -> timestampScale = reader.unsigned(field).takeIf { it > 0 } ?: DEFAULT_TIMESTAMP_SCALE
                            DURATION -> duration = reader.float(field)
                            else -> reader.skip(field)
                        }
                    }
                }
                CUES -> {
                    cuesStart = child.start
                    if (!reader.skip(child)) break
                }
                CLUSTER -> {
                    firstCluster = child.start
                    break
                }
                else -> if (!reader.skip(child)) break
            }
        }
        return Layout(
            segmentDataStart = dataStart,
            segmentEnd = segmentEnd,
            timestampScaleNanos = timestampScale,
            durationNanos = duration?.let { it * timestampScale },
            cuesStart = cuesStart,
            firstCluster = firstCluster,
        )
    }

    /** The size of the element that starts [header], its header included, or null when [header] is too short to say. */
    fun elementSize(header: ByteArray): Long? {
        val reader = Reader(header)
        val element = reader.element() ?: return null
        val size = element.size ?: return null
        return reader.position - element.start + size
    }

    /** The cue points of [cues], the whole `Cues` element, in time order, one per cluster. */
    fun cuePoints(cues: ByteArray): List<CuePoint> {
        val reader = Reader(cues)
        val element = reader.element() ?: throw DashUnsupportedException("the WebM Cues element is empty")
        if (element.id != CUES) throw DashUnsupportedException("the WebM index is element ${hex(element.id)}, not Cues")
        val end = reader.end(element)
        val points = ArrayList<CuePoint>()
        while (reader.position < end) {
            val point = reader.element() ?: break
            if (point.id != CUE_POINT) {
                reader.skip(point)
                continue
            }
            val pointEnd = reader.end(point)
            var time: Long? = null
            var cluster: Long? = null
            while (reader.position < pointEnd) {
                val field = reader.element() ?: break
                when (field.id) {
                    CUE_TIME -> time = reader.unsigned(field)
                    CUE_TRACK_POSITIONS -> {
                        val positionsEnd = reader.end(field)
                        while (reader.position < positionsEnd) {
                            val position = reader.element() ?: break
                            if (position.id == CUE_CLUSTER_POSITION) {
                                val value = reader.unsigned(position)
                                cluster = cluster?.let { minOf(it, value) } ?: value
                            } else {
                                reader.skip(position)
                            }
                        }
                    }
                    else -> reader.skip(field)
                }
            }
            if (time != null && cluster != null) points += CuePoint(time, cluster)
        }
        // A cluster can hold the cue points of several tracks. Each cluster run starts once.
        return points.sortedBy { it.time }.distinctBy { it.clusterPosition }.sortedBy { it.clusterPosition }
    }

    /** One element's header: its [id], the byte it starts at, and the size of its data, null when unknown. */
    private class Header(val id: Long, val start: Long, val size: Long?)

    /** Reads EBML elements from [bytes], whose first byte is at offset [base] of the file. */
    private class Reader(private val bytes: ByteArray, private val base: Long = 0) {
        var position: Long = base
            private set

        /** The next element's header, or null when the bytes end inside it. */
        fun element(): Header? {
            val start = position
            val id = vint(keepMarker = true) ?: return null.also { position = start }
            val size = vint(keepMarker = false) ?: return null.also { position = start }
            return Header(id, start, size.takeIf { it != UNKNOWN })
        }

        /** The byte after [element]'s data, or the end of the bytes when its size is unknown. */
        fun end(element: Header): Long = element.size?.let { position + it } ?: (base + bytes.size)

        /** Moves past [element]'s data; false when its size is unknown, which cannot be skipped. */
        fun skip(element: Header): Boolean {
            val size = element.size ?: return false
            position += size
            return true
        }

        fun unsigned(element: Header): Long {
            val size = element.size ?: throw DashUnsupportedException("a WebM number has no size")
            if (size > 8) throw DashUnsupportedException("a WebM number is $size bytes long")
            var value = 0L
            repeat(size.toInt()) { value = (value shl 8) or byte() }
            return value
        }

        fun float(element: Header): Double? {
            val bits = unsigned(element)
            return when (element.size) {
                4L -> Float.fromBits(bits.toInt()).toDouble()
                8L -> Double.fromBits(bits)
                else -> null
            }
        }

        private fun byte(): Long {
            val at = (position - base).toInt()
            if (at !in bytes.indices) throw DashUnsupportedException("the WebM data ends inside an element")
            position++
            return (bytes[at].toInt() and 0xFF).toLong()
        }

        /** A variable-length integer, with its length marker kept for an ID, or removed for a size. */
        private fun vint(keepMarker: Boolean): Long? {
            val at = (position - base).toInt()
            if (at !in bytes.indices) return null
            val first = bytes[at].toInt() and 0xFF
            if (first == 0) throw DashUnsupportedException("a WebM element length passes eight bytes")
            val length = first.countLeadingZeroBits() - 24 + 1
            if (at + length > bytes.size) return null
            var value = (if (keepMarker) first else first and (0xFF shr length)).toLong()
            var allOnes = value == (0xFF shr length).toLong()
            for (i in 1 until length) {
                val next = bytes[at + i].toInt() and 0xFF
                if (next != 0xFF) allOnes = false
                value = (value shl 8) or next.toLong()
            }
            position += length
            return if (!keepMarker && allOnes) UNKNOWN else value
        }
    }

    private fun hex(id: Long): String = "0x" + id.toString(16).uppercase()

    private const val UNKNOWN = -1L
    private const val DEFAULT_TIMESTAMP_SCALE = 1_000_000L

    private const val EBML_HEADER = 0x1A45DFA3L
    private const val SEGMENT = 0x18538067L
    private const val SEEK_HEAD = 0x114D9B74L
    private const val SEEK = 0x4DBBL
    private const val SEEK_ID = 0x53ABL
    private const val SEEK_POSITION = 0x53ACL
    private const val INFO = 0x1549A966L
    private const val TIMESTAMP_SCALE = 0x2AD7B1L
    private const val DURATION = 0x4489L
    private const val CUES = 0x1C53BB6BL
    private const val CUE_POINT = 0xBBL
    private const val CUE_TIME = 0xB3L
    private const val CUE_TRACK_POSITIONS = 0xB7L
    private const val CUE_CLUSTER_POSITION = 0xF1L
    private const val CLUSTER = 0x1F43B675L
}
