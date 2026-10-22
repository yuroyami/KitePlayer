package io.github.yuroyami.kiteplayer.output

/** Decoder configuration converted to the start-code form expected by MediaCodec. */
public class CodecSpecificData(
    /** SPS data for AVC, or VPS, SPS, and PPS data for HEVC. */
    public val csd0: ByteArray,
    /** PPS data for AVC. HEVC carries every parameter set in [csd0]. */
    public val csd1: ByteArray?,
    /** Number of bytes in each container NAL length field. */
    public val nalLengthSize: Int,
)

/** Converts AVC and HEVC container records and packets to Annex B start-code framing. */
public object AnnexB {
    private val startCode = byteArrayOf(0, 0, 0, 1)

    /** Parses an AVCDecoderConfigurationRecord. Returns null when the record is malformed. */
    public fun parseAvcC(extradata: ByteArray): CodecSpecificData? {
        if (extradata.size < 7 || extradata[0].toInt() != 1) return null
        val nalLengthSize = (extradata[4].toInt() and 0x03) + 1
        if (!isSupportedLengthSize(nalLengthSize)) return null

        var at = 5
        val spsCount = extradata[at].toInt() and 0x1F
        at += 1
        val sps = ArrayList<ByteArray>(spsCount)
        repeat(spsCount) {
            val length = readU16(extradata, at) ?: return null
            at += 2
            if (length == 0 || length > extradata.size - at) return null
            val unit = extradata.copyOfRange(at, at + length)
            if (avcNalType(unit) != 7) return null
            sps += unit
            at += length
        }

        if (at >= extradata.size) return null
        val ppsCount = extradata[at].toInt() and 0xFF
        at += 1
        val pps = ArrayList<ByteArray>(ppsCount)
        repeat(ppsCount) {
            val length = readU16(extradata, at) ?: return null
            at += 2
            if (length == 0 || length > extradata.size - at) return null
            val unit = extradata.copyOfRange(at, at + length)
            if (avcNalType(unit) != 8) return null
            pps += unit
            at += length
        }

        if (sps.isEmpty() || pps.isEmpty()) return null
        return CodecSpecificData(
            csd0 = concatWithStartCodes(sps) ?: return null,
            csd1 = concatWithStartCodes(pps) ?: return null,
            nalLengthSize = nalLengthSize,
        )
    }

    /** Parses an HEVCDecoderConfigurationRecord. Returns null when the record is malformed. */
    public fun parseHvcC(extradata: ByteArray): CodecSpecificData? {
        if (extradata.size < 23 || extradata[0].toInt() != 1) return null
        val nalLengthSize = (extradata[21].toInt() and 0x03) + 1
        if (!isSupportedLengthSize(nalLengthSize)) return null

        var at = 22
        val arrayCount = extradata[at].toInt() and 0xFF
        at += 1
        val vps = ArrayList<ByteArray>()
        val sps = ArrayList<ByteArray>()
        val pps = ArrayList<ByteArray>()
        repeat(arrayCount) {
            if (extradata.size - at < 3) return null
            val arrayHeader = extradata[at].toInt() and 0xFF
            if (arrayHeader and 0x40 != 0) return null
            val nalType = arrayHeader and 0x3F
            if (nalType !in supportedHevcArrayTypes) return null
            at += 1
            val unitCount = readU16(extradata, at) ?: return null
            at += 2
            repeat(unitCount) {
                val length = readU16(extradata, at) ?: return null
                at += 2
                if (length < 2 || length > extradata.size - at) return null
                val unit = extradata.copyOfRange(at, at + length)
                if (hevcNalType(unit) != nalType) return null
                when (nalType) {
                    32 -> vps += unit
                    33 -> sps += unit
                    34 -> pps += unit
                }
                at += length
            }
        }

        if (at != extradata.size) return null
        if (vps.isEmpty() || sps.isEmpty() || pps.isEmpty()) return null
        return CodecSpecificData(
            csd0 = concatWithStartCodes(vps + sps + pps) ?: return null,
            csd1 = null,
            nalLengthSize = nalLengthSize,
        )
    }

    /** Returns true when [payload] starts with a three-byte or four-byte Annex B start code. */
    public fun isAnnexB(payload: ByteArray): Boolean {
        var at = 0
        while (at < payload.size && payload[at].toInt() == 0) at += 1
        return at >= 2 && at < payload.lastIndex && payload[at].toInt() == 1
    }

    /**
     * Rewrites length-prefixed NAL units with four-byte start codes.
     *
     * Existing Annex B storage is returned without copying. Null means the packet is malformed or
     * [nalLengthSize] is not one, two, or four bytes.
     */
    public fun toAnnexB(payload: ByteArray, nalLengthSize: Int): ByteArray? {
        if (!isSupportedLengthSize(nalLengthSize)) return null
        if (payload.isEmpty()) return null
        val outputSize = lengthPrefixedOutputSize(payload, nalLengthSize)
            ?: return payload.takeIf(::isAnnexB)

        val output = ByteArray(outputSize)
        var read = 0
        var write = 0
        while (read < payload.size) {
            val length = readLength(payload, read, nalLengthSize)?.toInt() ?: return null
            read += nalLengthSize
            startCode.copyInto(output, write)
            write += startCode.size
            payload.copyInto(output, write, read, read + length)
            write += length
            read += length
        }
        return output
    }

    private fun lengthPrefixedOutputSize(payload: ByteArray, nalLengthSize: Int): Int? {
        var read = 0
        var outputSize = 0L
        while (read < payload.size) {
            val length = readLength(payload, read, nalLengthSize) ?: return null
            read += nalLengthSize
            if (length == 0L || length > payload.size.toLong() - read.toLong()) return null
            outputSize += startCode.size.toLong() + length
            if (outputSize > Int.MAX_VALUE.toLong()) return null
            read += length.toInt()
        }
        return outputSize.toInt()
    }

    private fun isSupportedLengthSize(size: Int): Boolean = size == 1 || size == 2 || size == 4

    private fun avcNalType(unit: ByteArray): Int = unit[0].toInt() and 0x1F

    private fun hevcNalType(unit: ByteArray): Int = (unit[0].toInt() ushr 1) and 0x3F

    private fun readU16(bytes: ByteArray, at: Int): Int? {
        if (at < 0 || bytes.size - at < 2) return null
        return ((bytes[at].toInt() and 0xFF) shl 8) or
            (bytes[at + 1].toInt() and 0xFF)
    }

    private fun readLength(bytes: ByteArray, at: Int, size: Int): Long? {
        if (at < 0 || bytes.size - at < size) return null
        var value = 0L
        repeat(size) { offset ->
            value = (value shl 8) or (bytes[at + offset].toLong() and 0xFFL)
        }
        return value
    }

    private fun concatWithStartCodes(units: List<ByteArray>): ByteArray? {
        val total = units.fold(0L) { size, unit ->
            val next = size + startCode.size.toLong() + unit.size.toLong()
            if (next > Int.MAX_VALUE.toLong()) return null
            next
        }
        val output = ByteArray(total.toInt())
        var write = 0
        for (unit in units) {
            startCode.copyInto(output, write)
            write += startCode.size
            unit.copyInto(output, write)
            write += unit.size
        }
        return output
    }

    private val supportedHevcArrayTypes: Set<Int> = setOf(32, 33, 34, 39, 40)
}
