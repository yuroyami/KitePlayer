package io.github.yuroyami.kiteplayer.network.dash

/**
 * The fragments of one Period's MP4 segment, written again for a stream that began with another
 * initialization (#403).
 *
 * FFmpeg's HLS reader feeds every segment of a playlist to one MP4 reader, which keeps the first
 * `moov` it saw and skips any later one. So a Period whose initialization differs from the one
 * playback began with is read against the wrong track id, timescale, defaults and codec
 * configuration, and a Period whose media time does not continue the one before it moves time
 * backwards. Each fragment is therefore written anew: the target's track id, its timescale, every
 * sample's duration, size, flags and composition offset stated in the `trun` so no default from
 * either `moov` is needed, and decode times moved onto the presentation's timeline. Samples that
 * decode at or after the Period's end are dropped, because a Period ends there whatever its last
 * segment holds. For H.264 and HEVC, each sync sample also carries its Period's own parameter sets
 * in band, which decoders take in place of the ones they hold. That holds even for a Period whose
 * configuration is the one the stream began with, because a decoder that has played another
 * Period keeps that one's parameter sets until it is told otherwise. Other boxes of the segment,
 * such as its `sidx`, whose times would now be wrong, are left out, except the `styp` that opens
 * it. A Period of another codec than the stream began with cannot be read against its
 * initialization at all, and is refused.
 */
internal object Fmp4Rewrite {

    /**
     * [source], the track of the segment's own initialization, written for [target], the track of
     * the initialization the stream began with: times moved by [shiftMicros], and samples from
     * [endMicros] on, in the moved time, dropped.
     */
    class Plan(val source: Fmp4.Track, val target: Fmp4.Track, val shiftMicros: Long, val endMicros: Long?)

    /** Whether fragments of [source] read correctly against [target]'s initialization, time apart. */
    fun sameLayout(source: Fmp4.Track, target: Fmp4.Track): Boolean =
        source.id == target.id && source.timescale == target.timescale &&
            source.defaultDuration == target.defaultDuration && source.defaultSize == target.defaultSize &&
            source.defaultFlags == target.defaultFlags && sameCodec(source, target)

    /** Whether the two sample entries, which hold the codec configuration, are the same bytes. */
    fun sameCodec(source: Fmp4.Track, target: Fmp4.Track): Boolean {
        val a = source.sampleEntryBytes
        val b = target.sampleEntryBytes
        return a == null && b == null || a != null && b != null && a.contentEquals(b)
    }

    /** [segment] written as [plan] says, or [segment] itself when nothing about it would change. */
    fun rewrite(segment: ByteArray, plan: Plan): ByteArray {
        val sourceCodec = codecOf(plan.source)
        val targetCodec = codecOf(plan.target)
        if (sourceCodec != targetCodec) {
            throw DashUnsupportedException("a Period in $sourceCodec cannot play in a stream that began in $targetCodec")
        }
        val inject = inBandParameterSets(plan.source, plan.target)
        val unchanged = plan.shiftMicros == 0L && inject == null && sameLayout(plan.source, plan.target)
        // Nothing to move, inject or trim, so the samples need not be read at all.
        if (unchanged && plan.endMicros == null) return segment
        val fragments = Fmp4.fragments(segment, plan.source)
        val trims = plan.endMicros != null && fragments.any { fragment ->
            fragment.samples.any { micros(it.decodeTime, plan.source.timescale) + plan.shiftMicros >= plan.endMicros }
        }
        if (unchanged && !trims) return segment
        val out = Bytes(segment.size + 1024)
        Fmp4.boxes(segment, 0, segment.size).firstOrNull()?.takeIf { it.type == "styp" }?.let { styp ->
            out.bytes(segment, styp.start, styp.end)
        }
        for (fragment in fragments) writeFragment(out, fragment, plan, inject)
        return out.toByteArray()
    }

    private fun writeFragment(out: Bytes, fragment: Fmp4.Fragment, plan: Plan, inject: ByteArray?) {
        val source = plan.source.timescale
        val target = plan.target.timescale
        fun moved(ticks: Long): Long {
            val at = micros(ticks, source) + plan.shiftMicros
            if (at < 0) throw DashUnsupportedException("a Period's media lies before the start of the presentation")
            return at
        }
        val kept = fragment.samples.filter { plan.endMicros == null || moved(it.decodeTime) < plan.endMicros }
        if (kept.isEmpty()) return
        // Each decode time is converted on its own and the durations are the differences, so a
        // change of timescale never lets rounding drift add up across a segment.
        val decode = LongArray(kept.size + 1)
        for (i in kept.indices) decode[i] = ticks(moved(kept[i].decodeTime), target)
        val last = kept.last()
        decode[kept.size] = ticks(moved(last.decodeTime + last.duration), target)
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
            val presentation = ticks(moved(sample.decodeTime + sample.compositionOffset), target)
            out.u32((presentation - decode[i]).toInt().toLong() and 0xFFFF_FFFFL)
        }
        out.box(8 + data.sumOf { it.size }, "mdat")
        data.forEach { out.bytes(it, 0, it.size) }
    }

    /**
     * The parameter sets of [source]'s H.264 or HEVC configuration as NAL units with the length
     * prefix [target]'s configuration declares, or null when the codec is another, or when the two
     * prefix lengths differ and every NAL unit would need writing again.
     */
    fun inBandParameterSets(source: Fmp4.Track, target: Fmp4.Track): ByteArray? {
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
    fun micros(ticks: Long, timescale: Long): Long = ticks / timescale * 1_000_000 + ticks % timescale * 1_000_000 / timescale

    /** [micros] as the nearest tick of [timescale], the division first so a large time cannot overflow. */
    fun ticks(micros: Long, timescale: Long): Long = micros / 1_000_000 * timescale + (micros % 1_000_000 * timescale + 500_000) / 1_000_000

    private const val VISUAL_SAMPLE_ENTRY_FIELDS = 78

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
