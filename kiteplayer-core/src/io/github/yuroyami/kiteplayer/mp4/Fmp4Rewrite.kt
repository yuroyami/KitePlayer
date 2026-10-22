package io.github.yuroyami.kiteplayer.mp4

import io.github.yuroyami.kiteplayer.KitePlayerInternalApi

/**
 * The fragments of an MP4 segment, written again for a stream that began with another
 * initialization: a later Period of a DASH presentation (#403), or another variant of an HLS
 * stream (#464). The words below say Period for either.
 *
 * FFmpeg's HLS reader feeds every segment of a playlist to one MP4 reader, which keeps the first
 * `moov` it saw and skips any later one. So a Period whose initialization differs from the one
 * playback began with is read against the wrong track id, timescale, defaults and codec
 * configuration, and a Period whose media time does not continue the one before it moves time
 * backwards. Each fragment is therefore written anew: the target's track id, its timescale, every
 * sample's duration, size, flags and composition offset stated in the `trun` so no default from
 * either `moov` is needed, and decode times moved onto the presentation's timeline. Samples that
 * decode at or after the Period's end are dropped, because a Period ends there whatever its last
 * segment holds. Times are also moved by the difference of the two initializations' edit lists,
 * because the reader goes on taking the first one's offset from every time. For H.264 and HEVC, each sync sample also carries its Period's own parameter sets
 * in band, which decoders take in place of the ones they hold. That holds even for a Period whose
 * configuration is the one the stream began with, because a decoder that has played another
 * Period keeps that one's parameter sets until it is told otherwise. Other boxes of the segment,
 * such as its `sidx`, whose times would now be wrong, are left out, except the `styp` that opens
 * it. A Period of another codec than the stream began with cannot be read against its
 * initialization at all, and is refused.
 */
@KitePlayerInternalApi
public object Fmp4Rewrite {

    /**
     * [source], the track of the segment's own initialization, written for [target], the track of
     * the initialization the stream began with: times moved by [shiftMicros], and samples from
     * [endMicros] on, in the moved time, dropped.
     *
     * [joined] is for a segment the reader takes after one of another stream. A stream whose
     * pictures are reordered decodes ahead of what it shows, so its first decode times after a
     * stream that reorders less would not pass the last one read, and FFmpeg's MP4 reader has the
     * decoder drop every such sample as an overlap of two fragments. With [joined], no sample
     * decodes before the first picture of the segment shows.
     */
    public class Plan(
        public val source: Fmp4.Track,
        public val target: Fmp4.Track,
        public val shiftMicros: Long,
        public val endMicros: Long?,
        public val joined: Boolean = false,
    )

    /** Whether fragments of [source] read correctly against [target]'s initialization, time apart. */
    public fun sameLayout(source: Fmp4.Track, target: Fmp4.Track): Boolean =
        source.id == target.id && source.timescale == target.timescale &&
            source.defaultDuration == target.defaultDuration && source.defaultSize == target.defaultSize &&
            source.defaultFlags == target.defaultFlags && source.timeOffset == target.timeOffset && sameCodec(source, target)

    /** Whether the two sample entries, which hold the codec configuration, are the same bytes. */
    public fun sameCodec(source: Fmp4.Track, target: Fmp4.Track): Boolean {
        val a = source.sampleEntryBytes
        val b = target.sampleEntryBytes
        return a == null && b == null || a != null && b != null && a.contentEquals(b)
    }

    /**
     * Whether a decoder set up from [target]'s initialization plays [source]'s samples once they
     * are written for it. Pictures in H.264 or HEVC do when their parameter sets can go in band,
     * and pictures in AV1 or VP9 do when [describedByKeyFrames]. Sound does when only its bit rate
     * differs. Anything else needs the same configuration.
     */
    public fun joins(source: Fmp4.Track, target: Fmp4.Track): Boolean {
        if (sameCodec(source, target)) return true
        val codec = codecOf(source)
        if (codec == null || codec != codecOf(target)) return false
        if (configuration(source) != null) return inBandParameterSets(source, target) != null
        if (describedByKeyFrames(source, target)) return true
        return source.handler == "soun" && target.handler == "soun" && sameSound(source, target)
    }

    /** [segment] written as [plan] says, or [segment] itself when nothing about it would change. */
    public fun rewrite(segment: ByteArray, plan: Plan): ByteArray = rewrite(segment, listOf(plan))

    /**
     * [segment] with each track written as its plan in [plans] says, or [segment] itself when
     * nothing about it would change. A track with no plan is left out of a segment that is written.
     * Each fragment becomes one fragment for each of its tracks, in the order of [plans].
     */
    public fun rewrite(segment: ByteArray, plans: List<Plan>): ByteArray {
        val injects = plans.map { plan ->
            val sourceCodec = codecOf(plan.source)
            val targetCodec = codecOf(plan.target)
            if (sourceCodec != targetCodec) {
                throw Fmp4UnsupportedException("media in $sourceCodec cannot play in a stream that began in $targetCodec")
            }
            inBandParameterSets(plan.source, plan.target)
        }
        val unchanged = plans.indices.all { i ->
            plans[i].shiftMicros == 0L && injects[i] == null && !plans[i].joined && sameLayout(plans[i].source, plans[i].target)
        }
        // Nothing to move, inject or trim, so the samples need not be read at all.
        if (unchanged && plans.all { it.endMicros == null }) return segment
        val fragments = plans.map { Fmp4.fragments(segment, it.source) }
        val trims = plans.indices.any { i ->
            val plan = plans[i]
            plan.endMicros != null && fragments[i].any { fragment ->
                fragment.samples.any { micros(it.decodeTime, plan.source.timescale) + plan.shiftMicros >= plan.endMicros }
            }
        }
        if (unchanged && !trims) return segment
        val out = Bytes(segment.size + 1024)
        Fmp4.boxes(segment, 0, segment.size).firstOrNull()?.takeIf { it.type == "styp" }?.let { styp ->
            out.bytes(segment, styp.start, styp.end)
        }
        // The decode time each track's next sample must pass: for a joined plan, the time just
        // before the segment's first picture shows.
        val decoded = LongArray(plans.size) { track ->
            if (!plans[track].joined) return@LongArray -1L
            val placing = Placing(plans[track])
            val first = fragments[track].minOfOrNull { fragment ->
                fragment.samples.filter(placing::keeps).minOfOrNull { placing.placed(it.decodeTime + it.compositionOffset) } ?: Long.MAX_VALUE
            } ?: 0L
            first - 1
        }
        for (i in 0 until (fragments.maxOfOrNull { it.size } ?: 0)) {
            for (track in plans.indices) {
                val fragment = fragments[track].getOrNull(i) ?: continue
                decoded[track] = writeFragment(out, fragment, plans[track], injects[track], decoded[track])
            }
        }
        return out.toByteArray()
    }

    /** Where [plan] puts a time of its source, in the target's timescale. */
    private class Placing(private val plan: Plan) {
        private val source = plan.source.timescale
        private val target = plan.target.timescale

        // The reader takes the target's edit offset from a time the source's was meant for. A time
        // the difference would put before zero, which no box can state, stays at zero.
        private val edit = plan.target.timeOffset - scaled(plan.source.timeOffset, source, target)

        fun moved(ticks: Long): Long {
            val at = micros(ticks, source) + plan.shiftMicros
            if (at < 0) throw Fmp4UnsupportedException("the media lies before the start of the stream")
            return at
        }

        fun placed(ticks: Long): Long = (ticks(moved(ticks), target) + edit).coerceAtLeast(0)

        fun keeps(sample: Fmp4.Sample): Boolean = plan.endMicros == null || moved(sample.decodeTime) < plan.endMicros
    }

    /**
     * Writes [fragment] for [plan]'s target, each sample decoding after [after], the decode time
     * of the sample written before it. Answers the decode time of the last sample written.
     */
    private fun writeFragment(out: Bytes, fragment: Fmp4.Fragment, plan: Plan, inject: ByteArray?, after: Long): Long {
        val placing = Placing(plan)
        fun placed(ticks: Long): Long = placing.placed(ticks)
        val kept = fragment.samples.filter(placing::keeps)
        if (kept.isEmpty()) return after
        // Each decode time is converted on its own and the durations are the differences, so a
        // change of timescale never lets rounding drift add up across a segment.
        val decode = LongArray(kept.size + 1)
        for (i in kept.indices) decode[i] = placed(kept[i].decodeTime).coerceAtLeast((if (i == 0) after else decode[i - 1]) + 1)
        val last = kept.last()
        decode[kept.size] = placed(last.decodeTime + last.duration).coerceAtLeast(decode[kept.size - 1])
        val data = kept.map { sample -> if (inject != null && sample.isSync) inject + sample.data else sample.data }
        val count = kept.size
        val trunSize = 20 + 16 * count
        val trafSize = 8 + 16 + 20 + trunSize
        val moofSize = 8 + 16 + trafSize
        out.box(moofSize, "moof")
        out.fullBox(16, "mfhd", 0, 0)
        out.u32(fragment.sequence)
        out.box(trafSize, "traf")
        // default-base-is-moof: the data offset counts from this moof, and no default is set.
        out.fullBox(16, "tfhd", 0, 0x020000)
        out.u32(plan.target.id)
        out.fullBox(20, "tfdt", 1, 0)
        out.u64(decode[0])
        out.fullBox(trunSize, "trun", 1, 0x000F01)
        out.u32(count.toLong())
        out.u32((moofSize + 8).toLong())
        for (i in 0 until count) {
            val sample = kept[i]
            out.u32(decode[i + 1] - decode[i])
            out.u32(data[i].size.toLong())
            out.u32(sample.flags)
            // Never before its decode time: FFmpeg moves a whole stream for one offset below zero.
            val presentation = placed(sample.decodeTime + sample.compositionOffset).coerceAtLeast(decode[i])
            out.u32(presentation - decode[i])
        }
        out.box(8 + data.sumOf { it.size }, "mdat")
        data.forEach { out.bytes(it, 0, it.size) }
        return decode[kept.size - 1]
    }

    /** [ticks] of the timescale [from] in the timescale [to], exactly when the two are the same. */
    private fun scaled(ticks: Long, from: Long, to: Long): Long = when {
        from == to -> ticks
        ticks < 0 -> -ticks(micros(-ticks, from), to)
        else -> ticks(micros(ticks, from), to)
    }

    /**
     * Whether two sound sample entries set a decoder up the same way: the same codec, channels
     * and sample rate, and the same boxes, a stated bit rate aside. Variants of one stream often
     * differ in nothing else.
     */
    private fun sameSound(source: Fmp4.Track, target: Fmp4.Track): Boolean {
        val a = source.sampleEntryBytes ?: return false
        val b = target.sampleEntryBytes ?: return false
        if (a.size < SOUND_SAMPLE_ENTRY_END || b.size < SOUND_SAMPLE_ENTRY_END) return false
        // The entry's type, then its own fields. Only version 0, whose fields end where the boxes start.
        for (i in 4 until SOUND_SAMPLE_ENTRY_END) if (a[i] != b[i]) return false
        if (a[16].toInt() != 0 || a[17].toInt() != 0) return false
        val left = Fmp4.boxes(a, SOUND_SAMPLE_ENTRY_END, a.size).filter { it.type != "btrt" }
        val right = Fmp4.boxes(b, SOUND_SAMPLE_ENTRY_END, b.size).filter { it.type != "btrt" }
        if (left.size != right.size) return false
        return left.indices.all { i ->
            val one = left[i]
            val other = right[i]
            when {
                one.type != other.type -> false
                one.type == "esds" -> decoderSetup(a, one).let { it != null && it.contentEquals(decoderSetup(b, other)) }
                else -> a.copyOfRange(one.dataStart, one.end).contentEquals(b.copyOfRange(other.dataStart, other.end))
            }
        }
    }

    /**
     * What an `esds` box tells a decoder: the object type of its decoder configuration and the
     * decoder specific information inside it, without the buffer size and bit rates between them.
     * Null when the box is not laid out as ISO/IEC 14496-1, 7.2.6 says.
     */
    private fun decoderSetup(entry: ByteArray, esds: Fmp4.Box): ByteArray? {
        // A descriptor is a tag, a length of one to four bytes of seven bits each, and its data.
        fun descriptor(from: Int, to: Int): Triple<Int, Int, Int>? {
            if (from >= to) return null
            var at = from + 1
            var length = 0
            for (i in 0 until 4) {
                if (at >= to) return null
                val byte = entry[at++].toInt() and 0xFF
                length = (length shl 7) or (byte and 0x7F)
                if (byte and 0x80 == 0) break
            }
            if (at + length > to) return null
            return Triple(entry[from].toInt() and 0xFF, at, at + length)
        }
        val stream = descriptor(esds.dataStart + 4, esds.end)?.takeIf { it.first == 0x03 } ?: return null
        if (stream.second + 3 > stream.third) return null
        val flags = entry[stream.second + 2].toInt() and 0xFF
        var at = stream.second + 3
        if (flags and 0x80 != 0) at += 2
        if (flags and 0x40 != 0) at += 1 + ((entry.getOrNull(at) ?: return null).toInt() and 0xFF)
        if (flags and 0x20 != 0) at += 2
        val config = descriptor(at, stream.third)?.takeIf { it.first == 0x04 } ?: return null
        if (config.second + DECODER_CONFIG_FIELDS > config.third) return null
        val specific = descriptor(config.second + DECODER_CONFIG_FIELDS, config.third)?.takeIf { it.first == 0x05 }
        val setup = specific?.let { entry.copyOfRange(it.second, it.third) } ?: ByteArray(0)
        return byteArrayOf(entry[config.second]) + setup
    }

    /**
     * Whether [source] and [target] are both AV1 or both VP9, in one profile, bit depth and chroma
     * layout. Each key frame of those codecs states its own picture size: an AV1 sync sample holds
     * a sequence header (AV1 in ISOBMFF, section 2.4) and a VP9 key frame has the size in its
     * header. So a decoder takes another size with nothing added to the samples.
     */
    public fun describedByKeyFrames(source: Fmp4.Track, target: Fmp4.Track): Boolean {
        val a = keyFrameCodec(source) ?: return false
        val b = keyFrameCodec(target) ?: return false
        return a.contentEquals(b)
    }

    /** The sample entry type of [track]'s AV1 or VP9, then its profile and its bit depth and chroma byte, or null for another codec. */
    private fun keyFrameCodec(track: Fmp4.Track): ByteArray? {
        val entry = track.sampleEntryBytes ?: return null
        if (entry.size < 8 + VISUAL_SAMPLE_ENTRY_FIELDS) return null
        val type = entry.decodeToString(4, 8)
        val boxes = Fmp4.boxes(entry, 8 + VISUAL_SAMPLE_ENTRY_FIELDS, entry.size)
        return when (type) {
            "av01" -> {
                // `av1C`: a marker and version byte, then the profile in three bits, then the tier bit
                // over the bit depth, monochrome and chroma bits.
                val at = boxes.firstOrNull { it.type == "av1C" }?.takeIf { it.end - it.dataStart >= 4 }?.dataStart ?: return null
                byteArrayOf(1, ((entry[at + 1].toInt() and 0xFF) shr 5).toByte(), (entry[at + 2].toInt() and 0x7F).toByte())
            }
            "vp09" -> {
                // `vpcC`: a version and flags, the profile, the level, then the bit depth and chroma byte.
                val at = boxes.firstOrNull { it.type == "vpcC" }?.takeIf { it.end - it.dataStart >= 7 }?.dataStart ?: return null
                byteArrayOf(2, entry[at + 4], entry[at + 6])
            }
            else -> null
        }
    }

    /**
     * The parameter sets of [source]'s H.264 or HEVC configuration as NAL units with the length
     * prefix [target]'s configuration declares, or null when the codec is another, or when the two
     * prefix lengths differ and every NAL unit would need writing again.
     */
    public fun inBandParameterSets(source: Fmp4.Track, target: Fmp4.Track): ByteArray? {
        val sourceConfig = configuration(source) ?: return null
        val targetConfig = configuration(target) ?: return null
        if (sourceConfig.first != targetConfig.first) return null
        val (lengthSize, units) = sourceConfig.second
        val (targetLengthSize, _) = targetConfig.second
        if (lengthSize != targetLengthSize) return null
        val out = Bytes(units.sumOf { it.size + lengthSize })
        for (unit in units) {
            for (i in lengthSize - 1 downTo 0) out.u8((unit.size shr (8 * i)) and 0xFF)
            out.bytes(unit, 0, unit.size)
        }
        return out.toByteArray()
    }

    /**
     * The codec of [track]'s sample entry, by its four characters, with the spellings of H.264 and
     * of HEVC each counted as one, Dolby Vision's among them, or null when the initialization names
     * none. A Dolby Vision entry is the same bitstream with an RPU beside each picture.
     */
    private fun codecOf(track: Fmp4.Track): String? {
        val entry = track.sampleEntryBytes ?: return null
        if (entry.size < 8) return null
        return when (val type = entry.decodeToString(4, 8)) {
            in AVC_ENTRIES -> "H.264"
            in HEVC_ENTRIES -> "HEVC"
            else -> type
        }
    }

    /** The sample entries of H.264 and of HEVC: `avc1` and `avc3`, `hvc1` and `hev1`, and Dolby Vision's twins of each. */
    private val AVC_ENTRIES = setOf("avc1", "avc3", "dva1", "dvav")
    private val HEVC_ENTRIES = setOf("hvc1", "hev1", "dvh1", "dvhe")

    /** The codec family, and the NAL length size and parameter sets of [track]'s `avcC` or `hvcC`, or null for another codec. */
    private fun configuration(track: Fmp4.Track): Pair<String, Pair<Int, List<ByteArray>>>? {
        val entry = track.sampleEntryBytes ?: return null
        val type = if (entry.size >= 8) entry.decodeToString(4, 8) else return null
        val family = when (type) {
            in AVC_ENTRIES -> "avc"
            in HEVC_ENTRIES -> "hevc"
            else -> return null
        }
        // A visual sample entry has 78 bytes of its own fields after its header, then its boxes.
        val config = Fmp4.boxes(entry, 8 + VISUAL_SAMPLE_ENTRY_FIELDS, entry.size)
            .firstOrNull { it.type == if (family == "avc") "avcC" else "hvcC" } ?: return null
        val at = config.dataStart
        val units = ArrayList<ByteArray>()
        fun take(from: Int): Int {
            val length = ((entry[from].toInt() and 0xFF) shl 8) or (entry[from + 1].toInt() and 0xFF)
            require(from + 2 + length <= config.end) { "a parameter set runs past its configuration" }
            units += entry.copyOfRange(from + 2, from + 2 + length)
            return from + 2 + length
        }
        return if (family == "avc") {
            val lengthSize = (entry[at + 4].toInt() and 0x03) + 1
            var cursor = at + 6
            repeat(entry[at + 5].toInt() and 0x1F) { cursor = take(cursor) }
            val pictureSets = entry[cursor].toInt() and 0xFF
            cursor++
            repeat(pictureSets) { cursor = take(cursor) }
            family to (lengthSize to units)
        } else {
            val lengthSize = (entry[at + 21].toInt() and 0x03) + 1
            var cursor = at + 23
            repeat(entry[at + 22].toInt() and 0xFF) {
                val count = ((entry[cursor + 1].toInt() and 0xFF) shl 8) or (entry[cursor + 2].toInt() and 0xFF)
                cursor += 3
                repeat(count) { cursor = take(cursor) }
            }
            family to (lengthSize to units)
        }
    }

    /** [ticks] of [timescale] as microseconds, the division first so a large time cannot overflow. */
    public fun micros(ticks: Long, timescale: Long): Long = ticks / timescale * 1_000_000 + ticks % timescale * 1_000_000 / timescale

    /** [micros] as the nearest tick of [timescale], the division first so a large time cannot overflow. */
    public fun ticks(micros: Long, timescale: Long): Long = micros / 1_000_000 * timescale + (micros % 1_000_000 * timescale + 500_000) / 1_000_000

    private const val VISUAL_SAMPLE_ENTRY_FIELDS = 78

    /** Where a version 0 sound sample entry's boxes start: its 8 byte header, then 28 bytes of fields. */
    private const val SOUND_SAMPLE_ENTRY_END = 36

    /** The object type, stream type, buffer size and two bit rates a decoder configuration starts with. */
    private const val DECODER_CONFIG_FIELDS = 13

    /** A growing buffer of the big-endian fields boxes are made of. */
    private class Bytes(capacity: Int) {
        private var buffer = ByteArray(capacity.coerceAtLeast(64))
        private var size = 0

        private fun room(more: Int) {
            if (size + more > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, size + more))
        }

        fun u8(value: Int) {
            room(1)
            buffer[size++] = value.toByte()
        }

        fun u32(value: Long) {
            room(4)
            for (i in 3 downTo 0) buffer[size++] = (value shr (8 * i)).toByte()
        }

        fun u64(value: Long) {
            u32(value ushr 32)
            u32(value and 0xFFFF_FFFFL)
        }

        fun bytes(from: ByteArray, start: Int, end: Int) {
            room(end - start)
            from.copyInto(buffer, size, start, end)
            size += end - start
        }

        fun box(size: Int, type: String) {
            u32(size.toLong())
            type.encodeToByteArray().let { bytes(it, 0, 4) }
        }

        fun fullBox(size: Int, type: String, version: Int, flags: Int) {
            box(size, type)
            u8(version)
            u8(flags shr 16)
            u8(flags shr 8)
            u8(flags)
        }

        fun toByteArray(): ByteArray = buffer.copyOf(size)
    }
}
