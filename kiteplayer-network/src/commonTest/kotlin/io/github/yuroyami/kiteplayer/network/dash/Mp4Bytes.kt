package io.github.yuroyami.kiteplayer.network.dash

/** Fragmented MP4 bytes written by hand for the tests: an initialization segment and media segments. */
internal object Mp4Bytes {

    fun box(type: String, payload: ByteArray): ByteArray = u32((8 + payload.size).toLong()) + type.encodeToByteArray() + payload

    fun u32(value: Long): ByteArray = ByteArray(4) { ((value shr (24 - 8 * it)) and 0xFF).toByte() }

    fun u64(value: Long): ByteArray = u32(value ushr 32) + u32(value and 0xFFFF_FFFFL)

    private fun fullBox(type: String, version: Int, flags: Int, payload: ByteArray): ByteArray =
        box(type, byteArrayOf(version.toByte(), (flags shr 16).toByte(), (flags shr 8).toByte(), flags.toByte()) + payload)

    /**
     * An initialization segment of one track: [trackId], [timescale], the [handler] type, a sample
     * entry of type [sampleEntry], and trex defaults of [defaultDuration] and [defaultSize].
     */
    fun init(trackId: Long, timescale: Long, handler: String, sampleEntry: String, defaultDuration: Long = 0, defaultSize: Long = 0): ByteArray {
        val tkhd = fullBox("tkhd", 0, 3, u32(0) + u32(0) + u32(trackId) + ByteArray(68))
        val mdhd = fullBox("mdhd", 0, 0, u32(0) + u32(0) + u32(timescale) + u32(0) + ByteArray(4))
        val hdlr = fullBox("hdlr", 0, 0, u32(0) + handler.encodeToByteArray() + ByteArray(12) + byteArrayOf(0))
        val stsd = fullBox("stsd", 0, 0, u32(1) + box(sampleEntry, ByteArray(8)))
        val minf = box("minf", box("stbl", stsd))
        val trak = box("trak", tkhd + box("mdia", mdhd + hdlr + minf))
        val trex = fullBox("trex", 0, 0, u32(trackId) + u32(1) + u32(defaultDuration) + u32(defaultSize) + u32(0))
        return box("ftyp", "iso6".encodeToByteArray() + u32(0)) + box("moov", fullBox("mvhd", 0, 0, ByteArray(96)) + trak + box("mvex", trex))
    }

    /** One sample: its duration, or null to lean on the defaults, and its bytes. */
    class Sample(val duration: Long?, val data: ByteArray)

    /**
     * A media segment of one fragment of [trackId]: a `tfdt` of [decodeTime], and a `trun` with a
     * data offset and, for each sample, its size and its duration when it has one.
     */
    fun segment(trackId: Long, decodeTime: Long, samples: List<Sample>): ByteArray {
        val durations = samples.all { it.duration != null }
        // default-base-is-moof, as DASH packagers write it.
        val tfhd = fullBox("tfhd", 0, 0x020000, u32(trackId))
        val tfdt = fullBox("tfdt", 1, 0, u64(decodeTime))
        fun trun(dataOffset: Long): ByteArray {
            var entries = ByteArray(0)
            for (sample in samples) {
                if (durations) entries += u32(sample.duration!!)
                entries += u32(sample.data.size.toLong())
            }
            val flags = 0x1 or 0x200 or if (durations) 0x100 else 0
            return fullBox("trun", 0, flags, u32(samples.size.toLong()) + u32(dataOffset) + entries)
        }
        fun moof(dataOffset: Long) = box("moof", fullBox("mfhd", 0, 0, u32(1)) + box("traf", tfhd + tfdt + trun(dataOffset)))
        // The data starts right after the moof and the mdat's own header.
        val moofSize = moof(0).size
        val mdat = box("mdat", samples.fold(ByteArray(0)) { all, sample -> all + sample.data })
        return box("styp", "msdh".encodeToByteArray() + u32(0)) + moof((moofSize + 8).toLong()) + mdat
    }

    /** A WebVTT sample (ISO/IEC 14496-30) of one cue for each of [cues], or an empty one when there are none. */
    fun wvttSample(vararg cues: String): ByteArray =
        if (cues.isEmpty()) box("vtte", ByteArray(0)) else cues.fold(ByteArray(0)) { all, text ->
            all + box("vttc", box("sttg", "line:90%".encodeToByteArray()) + box("payl", text.encodeToByteArray()))
        }
}
