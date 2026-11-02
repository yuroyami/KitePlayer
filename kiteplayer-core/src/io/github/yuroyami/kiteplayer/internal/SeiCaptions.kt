package io.github.yuroyami.kiteplayer.internal

/**
 * The closed captions inside an H.264 or HEVC packet, read from the packet itself (#611).
 *
 * Broadcast H.264 and HEVC carry ATSC A/53 captions in SEI messages, as
 * `user_data_registered_itu_t_t35` with the United States country code, the ATSC provider and the
 * `GA94` identifier. FFmpeg's software decoders hand them out with the decoded picture, but a
 * platform decoder that writes straight to a surface, such as Android's MediaCodec on either of its
 * routes, gives none, so the engine reads them from the packets it holds before any decoder sees
 * them. The answer is the same `cc_data` FFmpeg attaches as `AV_FRAME_DATA_A53_CC`: three bytes per
 * caption pair, the triplets of every caption message in the packet, in order.
 *
 * Packets come framed as the container keeps them: with start codes in a transport stream or a raw
 * elementary stream, and with a length before each unit in MP4 and Matroska, whose size the stream's
 * configuration record names. [nalLengthSizeOf] reads it.
 */
internal object SeiCaptions {

    /** The caption triplets of [packet], or null when it carries none. [nalLengthSize] 0 is start codes. */
    fun extract(packet: ByteArray, hevc: Boolean, nalLengthSize: Int): ByteArray? {
        var out: ByteArray? = null
        forEachNal(packet, nalLengthSize) { from, to ->
            if (isSei(packet, from, to, hevc)) {
                val headerSize = if (hevc) 2 else 1
                captionsOfSei(unescape(packet, from + headerSize, to))?.let { found ->
                    out = out?.plus(found) ?: found
                }
            }
        }
        return out
    }

    /**
     * The size of the length before each unit of a packet of a stream with [extradata], or 0 for
     * start codes. An `avcC` or `hvcC` record starts with version 1; anything else, or nothing, is a
     * stream framed with start codes.
     */
    fun nalLengthSizeOf(extradata: ByteArray?, hevc: Boolean): Int {
        if (extradata == null || extradata.isEmpty() || extradata[0].toInt() != 1) return 0
        val at = if (hevc) 21 else 4
        if (extradata.size <= at) return 0
        return (extradata[at].toInt() and 0x03) + 1
    }

    private inline fun forEachNal(data: ByteArray, nalLengthSize: Int, block: (from: Int, to: Int) -> Unit) {
        if (nalLengthSize > 0) {
            var at = 0
            while (at + nalLengthSize <= data.size) {
                var length = 0L
                for (i in 0 until nalLengthSize) length = (length shl 8) or (data[at + i].toLong() and 0xFF)
                at += nalLengthSize
                if (length <= 0 || length > data.size - at) return
                block(at, at + length.toInt())
                at += length.toInt()
            }
            return
        }
        var start = nextStartCode(data, 0)
        while (start >= 0) {
            val from = start + 3
            val next = nextStartCode(data, from)
            var to = if (next < 0) data.size else next
            // A four byte start code leaves its leading zero on the unit before it.
            while (to > from && data[to - 1].toInt() == 0) to--
            if (to > from) block(from, to)
            start = next
        }
    }

    /** The offset of the next `00 00 01` at or after [from], or -1. */
    private fun nextStartCode(data: ByteArray, from: Int): Int {
        var i = from
        while (i + 2 < data.size) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) return i
            i++
        }
        return -1
    }

    private fun isSei(data: ByteArray, from: Int, to: Int, hevc: Boolean): Boolean {
        val first = data[from].toInt() and 0xFF
        return if (hevc) {
            // Prefix and suffix SEI.
            to - from > 2 && ((first shr 1) and 0x3F).let { it == 39 || it == 40 }
        } else {
            to - from > 1 && (first and 0x1F) == 6
        }
    }

    /** [data] from [from] to [to] with every emulation prevention byte, the 03 of `00 00 03`, taken out. */
    private fun unescape(data: ByteArray, from: Int, to: Int): ByteArray {
        val out = ByteArray(to - from)
        var size = 0
        var zeros = 0
        for (i in from until to) {
            val byte = data[i].toInt() and 0xFF
            if (zeros >= 2 && byte == 3) {
                zeros = 0
                continue
            }
            out[size++] = byte.toByte()
            zeros = if (byte == 0) zeros + 1 else 0
        }
        return out.copyOf(size)
    }

    /** The caption triplets of every A/53 message in one SEI's payload, or null when it holds none. */
    private fun captionsOfSei(rbsp: ByteArray): ByteArray? {
        var out: ByteArray? = null
        var at = 0
        // The last byte is the stop bit of the trailing bits, 0x80.
        while (at < rbsp.size - 1) {
            var type = 0
            while (at < rbsp.size && (rbsp[at].toInt() and 0xFF) == 0xFF) {
                type += 255
                at++
            }
            if (at >= rbsp.size) break
            type += rbsp[at++].toInt() and 0xFF
            var size = 0
            while (at < rbsp.size && (rbsp[at].toInt() and 0xFF) == 0xFF) {
                size += 255
                at++
            }
            if (at >= rbsp.size) break
            size += rbsp[at++].toInt() and 0xFF
            if (size > rbsp.size - at) break
            if (type == USER_DATA_REGISTERED_ITU_T_T35) {
                captionsOfT35(rbsp, at, at + size)?.let { found -> out = out?.plus(found) ?: found }
            }
            at += size
        }
        return out
    }

    /** The `cc_data` triplets of one T.35 message, when it is ATSC's A/53 caption data. */
    private fun captionsOfT35(data: ByteArray, from: Int, to: Int): ByteArray? {
        if (to - from < 10) return null
        fun at(i: Int) = data[from + i].toInt() and 0xFF
        if (at(0) != COUNTRY_UNITED_STATES || at(1) != 0x00 || at(2) != PROVIDER_ATSC) return null
        if (at(3) != 'G'.code || at(4) != 'A'.code || at(5) != '9'.code || at(6) != '4'.code) return null
        if (at(7) != CC_DATA_TYPE) return null
        val flags = at(8)
        if (flags and PROCESS_CC_DATA == 0) return null
        val count = flags and 0x1F
        // at(9) is em_data, which carries nothing.
        val start = from + 10
        val end = start + count * 3
        if (count == 0 || end > to) return null
        return data.copyOfRange(start, end)
    }

    private const val USER_DATA_REGISTERED_ITU_T_T35 = 4
    private const val COUNTRY_UNITED_STATES = 0xB5
    private const val PROVIDER_ATSC = 0x31
    private const val CC_DATA_TYPE = 0x03
    private const val PROCESS_CC_DATA = 0x40
}

/**
 * The captions read from the packets of one video lane, kept until the picture of their time
 * leaves the decoder (#611). Packets arrive in decode order and pictures leave in the order they
 * are shown, so each picture takes every caption up to its own time, oldest first, and the captions
 * keep the order they were written in. A picture the decoder never gives back, as before a precise
 * seek's target, hands its captions to the next one.
 *
 * Lane-local: one lane reads and takes. Made only for a hardware decoder of H.264 or HEVC, whose
 * frames may give no captions; once a frame gives its own, the packets are read no more, so a
 * decoder that gives them is never read twice and pays no copy.
 */
internal class PacketCaptions private constructor(private val hevc: Boolean, private val nalLengthSize: Int) {
    private val pending = ArrayList<Pair<Long, ByteArray>>()
    private var epoch: io.github.yuroyami.kiteplayer.Generation? = null
    private var framesCarryThem = false

    /** Reads [packet]'s captions for [epoch], the lane's epoch. */
    fun read(packet: io.github.yuroyami.kiteplayer.spi.PlayerPacket, epoch: io.github.yuroyami.kiteplayer.Generation) {
        if (framesCarryThem) return
        val ptsUs = packet.pts?.micros ?: return
        if (this.epoch != epoch) {
            pending.clear()
            this.epoch = epoch
        }
        val captions = SeiCaptions.extract(packet.copyBytes(), hevc, nalLengthSize) ?: return
        var at = pending.size
        while (at > 0 && pending[at - 1].first > ptsUs) at--
        pending.add(at, ptsUs to captions)
        // A decoder that gives no picture back for a long run holds no more than this.
        if (pending.size > LIMIT) pending.removeAt(0)
    }

    /**
     * The picture of [ptsUs] in [epoch] left the decoder with [own] captions, or none. With none, it
     * takes those read from the packets up to its time and hands each to [deliver], oldest first.
     */
    fun picture(ptsUs: Long, epoch: io.github.yuroyami.kiteplayer.Generation, own: ByteArray?, deliver: (Long, ByteArray) -> Unit) {
        if (own != null) {
            framesCarryThem = true
            pending.clear()
            return
        }
        if (this.epoch != epoch) return
        while (pending.isNotEmpty() && pending[0].first <= ptsUs) {
            val (at, bytes) = pending.removeAt(0)
            deliver(at, bytes)
        }
    }

    companion object {
        private const val LIMIT = 128

        /** The store for a lane decoding [stream] on [decoder], or null when its frames are expected to carry their own. */
        fun forLane(stream: io.github.yuroyami.kiteplayer.spi.PlayerStreamInfo?, decoder: io.github.yuroyami.kiteplayer.spi.VideoDecoder): PacketCaptions? {
            if (stream == null || decoder.hardware == io.github.yuroyami.kiteplayer.HwdecStatus.Software) return null
            val hevc = when (stream.codec) {
                "h264" -> false
                "hevc" -> true
                else -> return null
            }
            return PacketCaptions(hevc, SeiCaptions.nalLengthSizeOf(stream.codecExtradata, hevc))
        }
    }
}
