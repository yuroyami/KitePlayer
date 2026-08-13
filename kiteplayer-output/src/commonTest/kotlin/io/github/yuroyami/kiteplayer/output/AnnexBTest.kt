package io.github.yuroyami.kiteplayer.output

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnnexBTest {
    private val avcc = byteArrayOf(
        0x01,
        0x64,
        0x00,
        0x28,
        0xFF.toByte(),
        0xE1.toByte(),
        0x00,
        0x04,
        0x67,
        0x64,
        0x00,
        0x28,
        0x01,
        0x00,
        0x02,
        0x68,
        0xEE.toByte(),
    )

    private val hvcc = byteArrayOf(
        0x01,
        0x01,
        0x00,
        0x00,
        0x00,
        0x00,
        0x00,
        0x00,
        0x00,
        0x00,
        0x00,
        0x00,
        0x78,
        0xF0.toByte(),
        0x00,
        0xFC.toByte(),
        0xFD.toByte(),
        0xF8.toByte(),
        0xF8.toByte(),
        0x00,
        0x00,
        0x0F,
        0x03,
        0xA2.toByte(),
        0x00,
        0x01,
        0x00,
        0x02,
        0x44,
        0x01,
        0xA0.toByte(),
        0x00,
        0x01,
        0x00,
        0x02,
        0x40,
        0x01,
        0xA1.toByte(),
        0x00,
        0x01,
        0x00,
        0x03,
        0x42,
        0x01,
        0x02,
    )

    @Test
    fun parsesAvcC() {
        val csd = AnnexB.parseAvcC(avcc)!!

        assertEquals(4, csd.nalLengthSize)
        assertContentEquals(
            byteArrayOf(0, 0, 0, 1, 0x67, 0x64, 0x00, 0x28),
            csd.csd0,
        )
        assertContentEquals(
            byteArrayOf(0, 0, 0, 1, 0x68, 0xEE.toByte()),
            csd.csd1!!,
        )
    }

    @Test
    fun refusesTruncatedOrIncompleteAvcC() {
        for (size in avcc.indices) {
            assertNull(AnnexB.parseAvcC(avcc.copyOf(size)), "accepted avcC prefix $size")
        }
        assertNull(
            AnnexB.parseAvcC(avcc.copyOf().also { it[4] = 0xFE.toByte() }),
            "accepted reserved three-byte NAL length",
        )
    }

    @Test
    fun refusesMislabeledAvcCParameterSets() {
        assertNull(AnnexB.parseAvcC(avcc.copyOf().also { it[8] = 0x68 }))
        assertNull(AnnexB.parseAvcC(avcc.copyOf().also { it[15] = 0x67 }))
    }

    @Test
    fun parsesHvcCParameterSetsInDecoderOrder() {
        val csd = AnnexB.parseHvcC(hvcc)!!

        assertEquals(4, csd.nalLengthSize)
        assertNull(csd.csd1)
        assertContentEquals(
            byteArrayOf(
                0, 0, 0, 1, 0x40, 0x01,
                0, 0, 0, 1, 0x42, 0x01, 0x02,
                0, 0, 0, 1, 0x44, 0x01,
            ),
            csd.csd0,
        )
    }

    @Test
    fun refusesTruncatedOrEmptyHvcC() {
        for (size in hvcc.indices) {
            assertNull(AnnexB.parseHvcC(hvcc.copyOf(size)), "accepted hvcC prefix $size")
        }
        assertNull(
            AnnexB.parseHvcC(hvcc.copyOf().also { it[21] = 0x0E }),
            "accepted reserved three-byte NAL length",
        )
    }

    @Test
    fun refusesReservedOrMislabeledHvcCArray() {
        assertNull(
            AnnexB.parseHvcC(hvcc.copyOf().also { it[23] = 0xE2.toByte() }),
        )
        assertNull(AnnexB.parseHvcC(hvcc.copyOf().also { it[28] = 0x40 }))
    }

    @Test
    fun refusesOneByteHvcCUnitAndTrailingGarbage() {
        val oneByteUnit = hvcc.toMutableList().apply {
            this[26] = 0
            this[27] = 1
            removeAt(29)
        }.toByteArray()

        assertNull(AnnexB.parseHvcC(oneByteUnit))
        assertNull(AnnexB.parseHvcC(hvcc + byteArrayOf(0)))
    }

    @Test
    fun skipsLegalHvcCSeiAndRejectsUnsupportedArrayTypes() {
        val withSei = (hvcc + byteArrayOf(
            0xA7.toByte(),
            0,
            1,
            0,
            2,
            0x4E,
            1,
        )).also { it[22] = 4 }
        val withUnsupportedArray = (hvcc + byteArrayOf(
            0xA3.toByte(),
            0,
            1,
            0,
            2,
            0x46,
            1,
        )).also { it[22] = 4 }

        assertContentEquals(AnnexB.parseHvcC(hvcc)!!.csd0, AnnexB.parseHvcC(withSei)!!.csd0)
        assertNull(AnnexB.parseHvcC(withUnsupportedArray))
    }

    @Test
    fun convertsLengthPrefixedUnitsToStartCodes() {
        val packet = byteArrayOf(
            0x00, 0x00, 0x00, 0x02, 0x65, 0x01,
            0x00, 0x00, 0x00, 0x01, 0x41,
        )

        assertContentEquals(
            byteArrayOf(0, 0, 0, 1, 0x65, 0x01, 0, 0, 0, 1, 0x41),
            AnnexB.toAnnexB(packet, nalLengthSize = 4)!!,
        )
    }

    @Test
    fun convertsOneAndTwoByteLengths() {
        assertContentEquals(
            byteArrayOf(0, 0, 0, 1, 0x65, 0x01),
            AnnexB.toAnnexB(byteArrayOf(2, 0x65, 0x01), nalLengthSize = 1)!!,
        )
        assertContentEquals(
            byteArrayOf(0, 0, 0, 1, 0x41),
            AnnexB.toAnnexB(byteArrayOf(0, 1, 0x41), nalLengthSize = 2)!!,
        )
    }

    @Test
    fun refusesOverrunningLengthAndUnsupportedLengthSize() {
        assertNull(
            AnnexB.toAnnexB(
                byteArrayOf(0x00, 0x00, 0x00, 0x09, 0x65),
                nalLengthSize = 4,
            ),
        )
        assertNull(AnnexB.toAnnexB(byteArrayOf(0, 0, 0, 0), nalLengthSize = 3))
    }

    @Test
    fun passesThroughExistingAnnexB() {
        val annexB = byteArrayOf(0, 0, 0, 1, 0x67, 0, 0, 1, 0x68)

        assertTrue(AnnexB.isAnnexB(annexB))
        assertContentEquals(annexB, AnnexB.toAnnexB(annexB, 4))
        assertFalse(AnnexB.isAnnexB(byteArrayOf(0, 0, 0, 9)))
        assertFalse(AnnexB.isAnnexB(byteArrayOf(0, 0, 1)))
        assertFalse(AnnexB.isAnnexB(byteArrayOf(0, 0, 0, 1)))
        assertTrue(AnnexB.isAnnexB(byteArrayOf(0, 0, 0, 0, 1, 0x67)))
    }

    @Test
    fun lengthPrefixThatLooksLikeThreeByteStartCodeIsStillConverted() {
        val packet = ByteArray(4 + 300) { 0x55 }
        packet[0] = 0x00
        packet[1] = 0x00
        packet[2] = 0x01
        packet[3] = 0x2C
        packet[4] = 0x65
        val expected = packet.copyOf().also {
            it[0] = 0
            it[1] = 0
            it[2] = 0
            it[3] = 1
        }

        assertTrue(AnnexB.isAnnexB(packet))
        assertContentEquals(expected, AnnexB.toAnnexB(packet, 4))
    }

    @Test
    fun refusesEmptyPacket() {
        assertNull(AnnexB.toAnnexB(ByteArray(0), 4))
    }
}
