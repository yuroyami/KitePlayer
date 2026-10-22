package io.github.yuroyami.kiteplayer.mp4

import io.github.yuroyami.kiteplayer.KitePlayerInternalApi

/**
 * A fragment that cannot be read or written as asked: one that names more samples than any real
 * segment holds, one in another codec than its stream began in, or one whose media lies before
 * the stream's start.
 */
@KitePlayerInternalApi
public class Fmp4UnsupportedException(message: String) : IllegalArgumentException(message)

/**
 * The boxes of fragmented MP4 (ISO/IEC 14496-12) that the player reads by itself: an
 * initialization segment's tracks, and a media segment's samples. The subtitle tracks of a DASH
 * presentation are read this way, because FFmpeg's HLS reader takes subtitles only as WebVTT text
 * (#402), and the fragments of a later Period or of another HLS variant are rewritten from them
 * by [Fmp4Rewrite] (#403, #464).
 *
 * A box is a 32-bit size, a four-character type, and its data; a size of 1 is followed by a 64-bit
 * size, and a size of 0 runs to the end of its parent.
 */
@KitePlayerInternalApi
public object Fmp4 {

    /** One track of an initialization segment, with the defaults its fragments may lean on. */
    public class Track(
        public val id: Long,
        public val timescale: Long,
        /** The handler type: `vide`, `soun`, `subt`, `text`. */
        public val handler: String?,
        /** The four-character type of the track's sample entry, such as `stpp` or `wvtt`. */
        public val sampleEntry: String?,
        public val defaultDuration: Long,
        public val defaultSize: Long,
        public val defaultFlags: Long = 0,
        /** The whole sample entry box, which holds the codec's configuration, or null. */
        public val sampleEntryBytes: ByteArray? = null,
        /**
         * What the track's edit list takes from every time of its samples, in [timescale]: where
         * its one edit starts in the media, less an empty edit before it. FFmpeg's MP4 reader
         * applies exactly that to a fragment, and nothing for a list of more edits.
         */
        public val timeOffset: Long = 0,
    )

    /** One sample of a media segment: when it decodes, how long it lasts, its flags and its bytes. */
    public class Sample(public val decodeTime: Long, public val duration: Long, public val compositionOffset: Long, public val data: ByteArray, public val flags: Long = 0) {
        /** Whether a decoder can start here: the sample-is-non-sync-sample flag is clear. */
        public val isSync: Boolean get() = flags and NON_SYNC == 0L
    }

    /** The samples of one fragment, a `moof` and its data, with the fragment's sequence number. */
    public class Fragment(public val sequence: Long, public val samples: List<Sample>)

    /** The sample-is-non-sync-sample bit of a sample's flags. */
    public const val NON_SYNC: Long = 0x10000

    /**
     * The most samples one media segment may hold, counted over all its fragments. A run's count
     * is a 32-bit number that costs four bytes, and a run of samples whose size is zero by default
     * needs no other byte at all, so without this a few bytes could ask for billions of samples
     * (#474). Ten seconds of 240 fps video are 2,400 samples and an hour of 48 kHz AAC about
     * 169,000, so no real segment comes near it, and the samples it allows cost about 12 MB.
     */
    public const val MAX_SAMPLES_PER_SEGMENT: Long = 1L shl 18

    private val EMPTY = ByteArray(0)

    /** The tracks of the initialization segment [init]. */
    public fun tracks(init: ByteArray): List<Track> {
        val moov = boxes(init, 0, init.size).firstOrNull { it.type == "moov" } ?: return emptyList()
        val defaults = HashMap<Long, LongArray>()
        children(init, moov, "mvex").flatMap { children(init, it, "trex") }.forEach { trex ->
            val at = trex.dataStart + 4
            defaults[u32(init, at)] = longArrayOf(u32(init, at + 8), u32(init, at + 12), u32(init, at + 16))
        }
        val movieTimescale = children(init, moov, "mvhd").firstOrNull()?.let { mvhd ->
            u32(init, mvhd.dataStart + if (init[mvhd.dataStart].toInt() == 1) 20 else 12)
        } ?: 0L
        return children(init, moov, "trak").mapNotNull { trak ->
            val tkhd = children(init, trak, "tkhd").firstOrNull() ?: return@mapNotNull null
            val version = init[tkhd.dataStart].toInt()
            val id = u32(init, tkhd.dataStart + if (version == 1) 20 else 12)
            val mdia = children(init, trak, "mdia").firstOrNull() ?: return@mapNotNull null
            val mdhd = children(init, mdia, "mdhd").firstOrNull() ?: return@mapNotNull null
            val timescale = u32(init, mdhd.dataStart + if (init[mdhd.dataStart].toInt() == 1) 20 else 12)
            val handler = children(init, mdia, "hdlr").firstOrNull()?.let { type(init, it.dataStart + 8) }
            val stsd = children(init, mdia, "minf").flatMap { children(init, it, "stbl") }.flatMap { children(init, it, "stsd") }.firstOrNull()
            val entry = stsd?.let { boxes(init, it.dataStart + 8, it.end).firstOrNull() }
            val trex = defaults[id] ?: LongArray(3)
            val edits = children(init, trak, "edts").flatMap { children(init, it, "elst") }.firstOrNull()
            Track(
                id, timescale.coerceAtLeast(1), handler, entry?.type, trex[0], trex[1], trex[2],
                entry?.let { init.copyOfRange(it.start, it.end) },
                edits?.let { timeOffset(init, it, timescale.coerceAtLeast(1), movieTimescale) } ?: 0L,
            )
        }
    }

    /**
     * The time offset of the edit list [elst], as FFmpeg's MP4 reader works it out: an empty edit
     * first, whose length is in [movieTimescale], then one edit that starts in the media.
     */
    private fun timeOffset(init: ByteArray, elst: Box, timescale: Long, movieTimescale: Long): Long {
        val long = init[elst.dataStart].toInt() == 1
        val count = u32(init, elst.dataStart + 4)
        val entry = if (long) 20 else 12
        if (count > 2 || elst.dataStart + 8 + count * entry > elst.end) return 0L
        var empty = 0L
        var start = 0L
        var first = 0L
        for (i in 0 until count) {
            val at = elst.dataStart + 8 + i.toInt() * entry
            val duration = if (long) u64(init, at) else u32(init, at)
            val time = if (long) u64(init, at + 8) else s32(init, at + 4)
            when {
                i == 0L && time == -1L -> { empty = duration; first = 1 }
                i == first && time >= 0 -> start = time
                else -> return 0L
            }
        }
        if (movieTimescale <= 0) return 0L
        return start - (empty / movieTimescale * timescale + empty % movieTimescale * timescale / movieTimescale)
    }

    /** The samples of [track] in the media segment [segment], in decode order. */
    public fun samples(segment: ByteArray, track: Track, maxSamples: Long = MAX_SAMPLES_PER_SEGMENT): List<Sample> =
        fragments(segment, track, maxSamples).flatMap { it.samples }

    /**
     * The fragments of [track] in the media segment [segment], each with its samples in decode order.
     *
     * @throws Fmp4UnsupportedException when the segment holds more than [maxSamples] samples,
     *         before any sample of the run that passes it is made
     * @throws IllegalArgumentException when a run's fields or samples lie outside their box or segment
     */
    public fun fragments(segment: ByteArray, track: Track, maxSamples: Long = MAX_SAMPLES_PER_SEGMENT): List<Fragment> {
        val fragments = ArrayList<Fragment>()
        var budget = maxSamples
        for (moof in boxes(segment, 0, segment.size).filter { it.type == "moof" }) {
            val out = ArrayList<Sample>()
            val sequence = children(segment, moof, "mfhd").firstOrNull()?.let { u32(segment, it.dataStart + 4) } ?: 0L
            for (traf in children(segment, moof, "traf")) {
                val tfhd = children(segment, traf, "tfhd").firstOrNull() ?: continue
                val flags = u24(segment, tfhd.dataStart + 1)
                if (u32(segment, tfhd.dataStart + 4) != track.id) continue
                var at = tfhd.dataStart + 8
                var base = moof.start.toLong()
                if (flags and 0x1 != 0) { base = u64(segment, at); at += 8 }
                if (flags and 0x2 != 0) at += 4
                var defaultDuration = track.defaultDuration
                var defaultSize = track.defaultSize
                var defaultFlags = track.defaultFlags
                if (flags and 0x8 != 0) { defaultDuration = u32(segment, at); at += 4 }
                if (flags and 0x10 != 0) { defaultSize = u32(segment, at); at += 4 }
                if (flags and 0x20 != 0) { defaultFlags = u32(segment, at); at += 4 }
                var time = children(segment, traf, "tfdt").firstOrNull()?.let { tfdt ->
                    if (segment[tfdt.dataStart].toInt() == 1) u64(segment, tfdt.dataStart + 4) else u32(segment, tfdt.dataStart + 4)
                } ?: 0L
                var dataAt = base
                for (trun in children(segment, traf, "trun")) {
                    val version = segment[trun.dataStart].toInt()
                    val runFlags = u24(segment, trun.dataStart + 1)
                    val count = u32(segment, trun.dataStart + 4)
                    if (count > budget) {
                        throw Fmp4UnsupportedException(
                            "a media segment asks for more than $maxSamples samples, which no real segment holds",
                        )
                    }
                    budget -= count
                    var field = trun.dataStart + 8
                    if (runFlags and 0x1 != 0) { dataAt = base + s32(segment, field); field += 4 }
                    var firstFlags: Long? = null
                    if (runFlags and 0x4 != 0) { firstFlags = u32(segment, field); field += 4 }
                    // Every field the run's samples carry must lie in the run, and samples of the
                    // default size in the segment, before any sample is made.
                    val perSample = 4L * (runFlags and 0xF00).countOneBits()
                    require(field + count * perSample <= trun.end) { "a run's sample fields run past its box" }
                    if (runFlags and 0x200 == 0) {
                        require(dataAt >= 0 && dataAt <= segment.size && count * defaultSize <= segment.size - dataAt) {
                            "a sample lies outside its segment"
                        }
                    }
                    for (i in 0 until count) {
                        var duration = defaultDuration
                        var size = defaultSize
                        var sampleFlags = if (i == 0L && firstFlags != null) firstFlags else defaultFlags
                        var offset = 0L
                        if (runFlags and 0x100 != 0) { duration = u32(segment, field); field += 4 }
                        if (runFlags and 0x200 != 0) { size = u32(segment, field); field += 4 }
                        if (runFlags and 0x400 != 0) { sampleFlags = u32(segment, field); field += 4 }
                        if (runFlags and 0x800 != 0) {
                            offset = if (version == 0) u32(segment, field) else s32(segment, field)
                            field += 4
                        }
                        require(dataAt >= 0 && dataAt <= segment.size && size <= segment.size - dataAt) {
                            "a sample lies outside its segment"
                        }
                        val start = dataAt.toInt()
                        val end = (dataAt + size).toInt()
                        val data = if (end == start) EMPTY else segment.copyOfRange(start, end)
                        out += Sample(time, duration, offset, data, sampleFlags)
                        dataAt += size
                        time += duration
                    }
                }
            }
            fragments += Fragment(sequence, out)
        }
        return fragments
    }

    /** One box: where it starts, where its data starts, where it ends, and its type. */
    public class Box(public val type: String, public val start: Int, public val dataStart: Int, public val end: Int)

    /** The boxes laid end to end from [from] to [to] of [bytes]. A box that runs past [to] ends the list. */
    public fun boxes(bytes: ByteArray, from: Int, to: Int): List<Box> {
        val out = ArrayList<Box>()
        var at = from
        while (at + 8 <= to) {
            var size = u32(bytes, at)
            val type = type(bytes, at + 4)
            var header = 8
            if (size == 1L) {
                if (at + 16 > to) break
                size = u64(bytes, at + 8)
                header = 16
            } else if (size == 0L) {
                size = (to - at).toLong()
            }
            if (size < header || at + size > to) break
            out += Box(type, at, at + header, (at + size).toInt())
            at += size.toInt()
        }
        return out
    }

    private fun children(bytes: ByteArray, parent: Box, type: String): List<Box> =
        boxes(bytes, parent.dataStart, parent.end).filter { it.type == type }

    private fun type(bytes: ByteArray, at: Int): String =
        if (at + 4 > bytes.size) "" else CharArray(4) { (bytes[at + it].toInt() and 0xFF).toChar() }.concatToString()

    private fun u24(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 0xFF) shl 16) or ((bytes[at + 1].toInt() and 0xFF) shl 8) or (bytes[at + 2].toInt() and 0xFF)

    public fun u32(bytes: ByteArray, at: Int): Long {
        require(at >= 0 && at + 4 <= bytes.size) { "a box ends inside its fields" }
        return ((bytes[at].toLong() and 0xFF) shl 24) or ((bytes[at + 1].toLong() and 0xFF) shl 16) or
            ((bytes[at + 2].toLong() and 0xFF) shl 8) or (bytes[at + 3].toLong() and 0xFF)
    }

    private fun s32(bytes: ByteArray, at: Int): Long = u32(bytes, at).toInt().toLong()

    public fun u64(bytes: ByteArray, at: Int): Long = (u32(bytes, at) shl 32) or u32(bytes, at + 4)
}
