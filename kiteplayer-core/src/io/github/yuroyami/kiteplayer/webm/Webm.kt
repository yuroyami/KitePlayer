package io.github.yuroyami.kiteplayer.webm

import io.github.yuroyami.kiteplayer.KitePlayerInternalApi

/** A WebM header or segment that cannot be read as asked: one that is not WebM, or that ends inside an element. */
@KitePlayerInternalApi
public class WebmUnsupportedException(message: String) : IllegalArgumentException(message)

/**
 * The parts of WebM (Matroska in EBML) that the player reads by itself, to play the clusters of
 * one file after the header of another: a later Period of a DASH presentation, or another
 * representation of a stream (#566).
 *
 * FFmpeg's Matroska reader keeps the tracks of the first header it reads. A cluster's blocks name
 * their track by number and count their time in the header's timestamp scale, so clusters of
 * another file read correctly when those two agree, and a decoder plays them when the codec is set
 * up the same way. Where only the track numbers differ, [retrack] writes the other numbers.
 */
@KitePlayerInternalApi
public object Webm {

    /** One `TrackEntry` of a header. [type] is 1 for a picture and 2 for sound. */
    public class Track(
        public val number: Long,
        public val type: Long,
        public val codecId: String,
        public val codecPrivate: ByteArray?,
        public val samplingFrequency: Double?,
        public val channels: Long?,
    )

    /** What a header says of the clusters after it: nanoseconds per timestamp tick, and the tracks in the order it lists them. */
    public class Header(public val timestampScaleNanos: Long, public val tracks: List<Track>)

    /** True when [bytes] start with the EBML header every WebM file starts with. */
    public fun isWebm(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 0x1A.toByte() && bytes[1] == 0x45.toByte() && bytes[2] == 0xDF.toByte() && bytes[3] == 0xA3.toByte()

    /** The header [init] holds: an initialization segment, or the start of a file up to its first cluster. */
    public fun header(init: ByteArray): Header {
        if (!isWebm(init)) throw WebmUnsupportedException("the header is not WebM")
        val reader = Reader(init)
        reader.skip(reader.element() ?: throw WebmUnsupportedException("the WebM header is cut short"))
        val segment = reader.element()?.takeIf { it.id == SEGMENT } ?: throw WebmUnsupportedException("the WebM header has no Segment")
        val end = reader.end(segment)
        var scale = DEFAULT_TIMESTAMP_SCALE
        val tracks = ArrayList<Track>()
        while (reader.position < end) {
            val child = reader.element() ?: break
            when (child.id) {
                INFO -> reader.children(child) { field ->
                    if (field.id == TIMESTAMP_SCALE) scale = reader.unsigned(field).takeIf { it > 0 } ?: DEFAULT_TIMESTAMP_SCALE else reader.skip(field)
                }
                TRACKS -> reader.children(child) { entry ->
                    if (entry.id == TRACK_ENTRY) tracks += track(reader, entry) else reader.skip(entry)
                }
                CLUSTER -> break
                else -> if (!reader.skip(child)) break
            }
        }
        return Header(scale, tracks)
    }

    private fun track(reader: Reader, entry: Element): Track {
        var number = 0L
        var type = 0L
        var codec = ""
        var setup: ByteArray? = null
        var sampling: Double? = null
        var channels: Long? = null
        reader.children(entry) { field ->
            when (field.id) {
                TRACK_NUMBER -> number = reader.unsigned(field)
                TRACK_TYPE -> type = reader.unsigned(field)
                CODEC_ID -> codec = reader.bytes(field).decodeToString().trimEnd('\u0000')
                CODEC_PRIVATE -> setup = reader.bytes(field)
                AUDIO -> reader.children(field) { sound ->
                    when (sound.id) {
                        SAMPLING_FREQUENCY -> sampling = reader.float(sound)
                        CHANNELS -> channels = reader.unsigned(sound)
                        else -> reader.skip(sound)
                    }
                }
                else -> reader.skip(field)
            }
        }
        return Track(number, type, codec, setup, sampling, channels)
    }

    /**
     * Whether a decoder set up from [target] plays [source]'s blocks. Pictures in VP8, VP9 or AV1
     * do when the profile, bit depth and chroma layout agree, because each key frame of those
     * codecs states its own size. Sound does when it is set up the same way; two Opus tracks of one
     * or two channels are, whatever each one's header says of its encoder. Anything else needs the
     * same codec private data.
     */
    public fun joins(source: Track, target: Track): Boolean {
        if (source.type != target.type || source.codecId != target.codecId) return false
        val a = source.codecPrivate
        val b = target.codecPrivate
        val same = a == null && b == null || a != null && b != null && a.contentEquals(b)
        return when (source.type) {
            VIDEO -> when (source.codecId) {
                "V_VP8" -> true
                "V_VP9" -> same || vp9Layout(a) == vp9Layout(b)
                // `av1C`: a marker and version byte, the profile in three bits, then the bit depth and chroma bits.
                "V_AV1" -> same || a != null && b != null && a.size >= 3 && b.size >= 3 &&
                    (a[1].toInt() and 0xE0) == (b[1].toInt() and 0xE0) && (a[2].toInt() and 0x7F) == (b[2].toInt() and 0x7F)
                else -> same
            }
            // An Opus header of up to two channels holds only the encoder's delay, gain and input rate.
            AUDIO_TYPE -> source.samplingFrequency == target.samplingFrequency && source.channels == target.channels &&
                (same || source.codecId == "A_OPUS" && (source.channels ?: 1) <= 2)
            else -> false
        }
    }

    /**
     * The profile, bit depth and chroma layout a VP9 track's private data states, as the WebM
     * container guidelines list them: features of an id, a length and a value, where 1 is the
     * profile, 3 the bit depth and 4 the chroma layout. A track that states none is profile 0.
     */
    private fun vp9Layout(data: ByteArray?): List<Int> {
        val out = intArrayOf(0, 8, 1)
        var at = 0
        while (data != null && at + 2 < data.size) {
            val length = data[at + 1].toInt() and 0xFF
            if (length == 1) {
                when (data[at].toInt()) {
                    1 -> out[0] = data[at + 2].toInt()
                    3 -> out[1] = data[at + 2].toInt()
                    4 -> out[2] = data[at + 2].toInt()
                }
            }
            at += 2 + length
        }
        return out.toList()
    }

    /**
     * How clusters after the header [own] read after the header [base]: each track's number, by the
     * number of the track of its kind at the same place in [base]. Null when they cannot: the two
     * count time in different scales, hold other kinds of track or a different number of one kind,
     * hold a pair that a decoder cannot take in band, or have numbers [retrack] cannot write.
     */
    public fun numbersFor(own: Header, base: Header): Map<Long, Long>? {
        if (own.timestampScaleNanos != base.timestampScaleNanos) return null
        if (base.tracks.isEmpty() || own.tracks.size != base.tracks.size) return null
        if ((own.tracks + base.tracks).any { it.type != VIDEO && it.type != AUDIO_TYPE }) return null
        val out = HashMap<Long, Long>()
        for (type in listOf(VIDEO, AUDIO_TYPE)) {
            val from = own.tracks.filter { it.type == type }
            val to = base.tracks.filter { it.type == type }
            if (from.size != to.size) return null
            for (i in from.indices) {
                if (!joins(from[i], to[i])) return null
                // A number up to 126 is one byte in a block, so one is written over another in place.
                if (from[i].number != to[i].number && (from[i].number !in 1..MAX_SHORT_NUMBER || to[i].number !in 1..MAX_SHORT_NUMBER)) return null
                out[from[i].number] = to[i].number
            }
        }
        return out
    }

    /**
     * [segment] with each block's track number written as [numbers] says, or [segment] itself when
     * every number stays. A block of a track that [numbers] does not name is left as it is.
     */
    public fun retrack(segment: ByteArray, numbers: Map<Long, Long>): ByteArray {
        if (numbers.all { it.key == it.value }) return segment
        val out = segment.copyOf()
        val reader = Reader(out)
        fun blocks(end: Long) {
            while (reader.position < end) {
                val child = reader.element() ?: return
                when (child.id) {
                    SEGMENT, CLUSTER, BLOCK_GROUP -> blocks(reader.end(child))
                    SIMPLE_BLOCK, BLOCK -> {
                        val at = reader.position.toInt()
                        val first = out.getOrNull(at)?.toInt()?.and(0xFF) ?: return
                        // One byte with its length marker set: the numbers [numbersFor] lets through.
                        if (first and 0x80 != 0) numbers[(first and 0x7F).toLong()]?.let { out[at] = (0x80 or it.toInt()).toByte() }
                        if (!reader.skip(child)) return
                    }
                    else -> if (!reader.skip(child)) return
                }
            }
        }
        blocks(out.size.toLong())
        return out
    }

    private class Element(val id: Long, val size: Long?)

    /** Reads EBML elements from [bytes]: an id of one to four bytes, a size of one to eight, then the data. */
    private class Reader(private val bytes: ByteArray) {
        var position: Long = 0
            private set

        /** The next element's header, or null when the bytes end inside it. */
        fun element(): Element? {
            val start = position
            val id = vint(keepMarker = true) ?: return null.also { position = start }
            val size = vint(keepMarker = false) ?: return null.also { position = start }
            return Element(id, size.takeIf { it != UNKNOWN })
        }

        /** The byte after [element]'s data, or the end of the bytes when its size is unknown or passes them. */
        fun end(element: Element): Long = minOf(element.size?.let { position + it } ?: Long.MAX_VALUE, bytes.size.toLong())

        /** Moves past [element]'s data; false when its size is unknown, which cannot be skipped. */
        fun skip(element: Element): Boolean {
            position = minOf(position + (element.size ?: return false), bytes.size.toLong())
            return true
        }

        /** Calls [each] for every child of [element], which must read or skip it. */
        inline fun children(element: Element, each: (Element) -> Unit) {
            val end = end(element)
            while (position < end) {
                val before = position
                each(element() ?: break)
                if (position <= before) break
            }
            position = end
        }

        fun bytes(element: Element): ByteArray {
            val end = end(element)
            return bytes.copyOfRange(position.toInt(), end.toInt()).also { position = end }
        }

        fun unsigned(element: Element): Long {
            val data = bytes(element)
            if (data.size > 8) throw WebmUnsupportedException("a WebM number is ${data.size} bytes long")
            return data.fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 0xFF) }
        }

        fun float(element: Element): Double? {
            val size = element.size
            val bits = unsigned(element)
            return when (size) {
                4L -> Float.fromBits(bits.toInt()).toDouble()
                8L -> Double.fromBits(bits)
                else -> null
            }
        }

        /** A variable-length integer, with its length marker kept for an id, or taken out for a size. */
        private fun vint(keepMarker: Boolean): Long? {
            val at = position.toInt()
            if (at !in bytes.indices) return null
            val first = bytes[at].toInt() and 0xFF
            if (first == 0) throw WebmUnsupportedException("a WebM element length passes eight bytes")
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

    private const val UNKNOWN = -1L
    private const val DEFAULT_TIMESTAMP_SCALE = 1_000_000L
    private const val MAX_SHORT_NUMBER = 126L
    private const val VIDEO = 1L
    private const val AUDIO_TYPE = 2L

    private const val SEGMENT = 0x18538067L
    private const val INFO = 0x1549A966L
    private const val TIMESTAMP_SCALE = 0x2AD7B1L
    private const val TRACKS = 0x1654AE6BL
    private const val TRACK_ENTRY = 0xAEL
    private const val TRACK_NUMBER = 0xD7L
    private const val TRACK_TYPE = 0x83L
    private const val CODEC_ID = 0x86L
    private const val CODEC_PRIVATE = 0x63A2L
    private const val AUDIO = 0xE1L
    private const val SAMPLING_FREQUENCY = 0xB5L
    private const val CHANNELS = 0x9FL
    private const val CLUSTER = 0x1F43B675L
    private const val SIMPLE_BLOCK = 0xA3L
    private const val BLOCK_GROUP = 0xA0L
    private const val BLOCK = 0xA1L
}
