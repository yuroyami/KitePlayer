package io.github.yuroyami.kiteplayer.internal

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The captions of an H.264 or HEVC packet, read from its SEI as FFmpeg's software decoders read
 * them (#611), so a decoder whose frames give none still has a CC1 track.
 */
class SeiCaptionsTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    /** One A/53 caption message, as `user_data_registered_itu_t_t35`, with [triplets]. */
    private fun t35(triplets: ByteArray, processCcData: Boolean = true): ByteArray {
        val count = triplets.size / 3
        val flags = (if (processCcData) 0x40 else 0) or count
        return bytes(0xB5, 0x00, 0x31, 'G'.code, 'A'.code, '9'.code, '4'.code, 0x03, flags, 0xFF) + triplets + bytes(0xFF)
    }

    /** An SEI payload holding one message of [type], with its trailing bits. */
    private fun seiPayload(type: Int, message: ByteArray): ByteArray = bytes(type, message.size) + message + bytes(0x80)

    /** [raw] with an emulation prevention byte after every `00 00`, as a writer escapes a unit. */
    private fun escape(raw: ByteArray): ByteArray {
        val out = ArrayList<Byte>()
        var zeros = 0
        for (b in raw) {
            if (zeros >= 2 && (b.toInt() and 0xFF) <= 3) {
                out += 3
                zeros = 0
            }
            out += b
            zeros = if (b.toInt() == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    private fun annexB(vararg units: ByteArray): ByteArray =
        units.fold(ByteArray(0)) { all, unit -> all + bytes(0, 0, 0, 1) + unit }

    private fun lengthPrefixed(vararg units: ByteArray): ByteArray =
        units.fold(ByteArray(0)) { all, unit ->
            all + bytes(unit.size ushr 24, (unit.size ushr 16) and 0xFF, (unit.size ushr 8) and 0xFF, unit.size and 0xFF) + unit
        }

    private val hello = "HELLO!".encodeToByteArray()
    private val avcSlice = bytes(0x65, 0x88, 0x84, 0x00, 0x10)

    @Test
    fun anH264PacketWithStartCodesGivesItsCaptions() {
        val sei = bytes(0x06) + escape(seiPayload(4, t35(hello)))
        assertContentEquals(hello, SeiCaptions.extract(annexB(sei, avcSlice), hevc = false, nalLengthSize = 0))
    }

    @Test
    fun anH264PacketWithLengthsGivesItsCaptions() {
        val sei = bytes(0x06) + escape(seiPayload(4, t35(hello)))
        assertContentEquals(hello, SeiCaptions.extract(lengthPrefixed(sei, avcSlice), hevc = false, nalLengthSize = 4))
    }

    @Test
    fun anHevcPrefixSeiGivesItsCaptions() {
        val sei = bytes(39 shl 1, 0x01) + escape(seiPayload(4, t35(hello)))
        val slice = bytes(0x26, 0x01, 0xAF, 0x00)
        assertContentEquals(hello, SeiCaptions.extract(lengthPrefixed(sei, slice), hevc = true, nalLengthSize = 4))
    }

    @Test
    fun anEscapedZeroRunInsideTheCaptionsComesBackWhole() {
        val triplets = bytes(0xFC, 0x00, 0x00, 0x01, 0x80, 0x80)
        val sei = bytes(0x06) + escape(seiPayload(4, t35(triplets)))
        val escaped = (0 until sei.size - 3).any { sei[it].toInt() == 0 && sei[it + 1].toInt() == 0 && sei[it + 2].toInt() == 3 && sei[it + 3].toInt() == 1 }
        assertEquals(true, escaped, "the unit carries no emulation prevention byte to take out")
        assertContentEquals(triplets, SeiCaptions.extract(annexB(sei, avcSlice), hevc = false, nalLengthSize = 0))
    }

    @Test
    fun twoCaptionMessagesInOnePacketAreJoinedInOrder() {
        val again = "AGAIN!".encodeToByteArray()
        val first = bytes(0x06) + escape(seiPayload(4, t35(hello)))
        val second = bytes(0x06) + escape(seiPayload(4, t35(again)))
        assertContentEquals(hello + again, SeiCaptions.extract(annexB(first, second, avcSlice), hevc = false, nalLengthSize = 0))
    }

    @Test
    fun otherSeiAndOtherUserDataGiveNothing() {
        val timing = bytes(0x06) + seiPayload(1, bytes(0x12, 0x34))
        val notAtsc = bytes(0x06) + seiPayload(4, bytes(0xB5, 0x00, 0x2F, 0x44, 0x54, 0x47, 0x31, 0x41, 0xF8))
        val notProcessed = bytes(0x06) + escape(seiPayload(4, t35(hello, processCcData = false)))
        assertNull(SeiCaptions.extract(annexB(timing, notAtsc, notProcessed, avcSlice), hevc = false, nalLengthSize = 0))
        assertNull(SeiCaptions.extract(annexB(avcSlice), hevc = false, nalLengthSize = 0))
    }

    @Test
    fun aTruncatedPacketGivesNothingRatherThanFailing() {
        val sei = bytes(0x06) + escape(seiPayload(4, t35(hello)))
        val packet = lengthPrefixed(sei, avcSlice)
        for (cut in 1 until packet.size) SeiCaptions.extract(packet.copyOf(cut), hevc = false, nalLengthSize = 4)
        for (cut in 1 until packet.size) SeiCaptions.extract(annexB(sei).copyOf(cut.coerceAtMost(annexB(sei).size)), hevc = false, nalLengthSize = 0)
    }

    @Test
    fun theLengthSizeComesFromTheConfigurationRecord() {
        assertEquals(0, SeiCaptions.nalLengthSizeOf(null, hevc = false))
        assertEquals(0, SeiCaptions.nalLengthSizeOf(bytes(0, 0, 0, 1, 0x67), hevc = false), "start codes")
        assertEquals(4, SeiCaptions.nalLengthSizeOf(bytes(1, 0x64, 0, 0x1F, 0xFF, 0xE1), hevc = false))
        assertEquals(2, SeiCaptions.nalLengthSizeOf(bytes(1, 0x64, 0, 0x1F, 0xFD, 0xE1), hevc = false))
        val hvcC = ByteArray(23).also { it[0] = 1; it[21] = 0x0F }
        assertEquals(4, SeiCaptions.nalLengthSizeOf(hvcC, hevc = true))
    }
}
