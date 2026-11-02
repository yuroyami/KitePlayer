package io.github.yuroyami.kiteplayer.internal

import io.github.yuroyami.kiteplayer.Generation
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The CEA-708 services inside a picture's A/53 bytes (#678): DTVCC packets assembled from the
 * triplets of type 3 and 2, across pictures, and split into each service's bytes.
 */
class DtvccServicesTest {

    private val epoch = Generation(1L)

    @Test
    fun onePacketInOnePictureGivesItsServiceBytes() {
        val services = DtvccServices().read(dtvccTriplets(0, 1 to "HI".encodeToByteArray()), epoch)
        assertEquals(setOf(1), services.keys)
        assertEquals("HI", services.getValue(1).decodeToString())
    }

    @Test
    fun aPacketSpreadOverTwoPicturesIsGivenWithTheSecond() {
        val all = dtvccTriplets(0, 2 to "HOLA AMIGOS".encodeToByteArray())
        val reader = DtvccServices()
        val cut = 6
        assertTrue(reader.read(all.copyOfRange(0, cut), epoch).isEmpty(), "the packet is not complete yet")
        val services = reader.read(all.copyOfRange(cut, all.size), epoch)
        assertEquals("HOLA AMIGOS", services.getValue(2).decodeToString())
    }

    @Test
    fun twoServicesInOnePacketComeApart() {
        val services = DtvccServices().read(
            dtvccTriplets(1, 1 to "HELLO".encodeToByteArray(), 2 to "HOLA".encodeToByteArray()),
            epoch,
        )
        assertEquals("HELLO", services.getValue(1).decodeToString())
        assertEquals("HOLA", services.getValue(2).decodeToString())
    }

    @Test
    fun anExtendedServiceNumberIsReadFromItsSecondByte() {
        val services = DtvccServices().read(dtvccTriplets(0, 9 to "NINE".encodeToByteArray()), epoch)
        assertEquals("NINE", services.getValue(9).decodeToString())
    }

    @Test
    fun cea608PairsAndInvalidPairsAreLeftAlone() {
        val dtvcc = dtvccTriplets(0, 1 to "OK".encodeToByteArray())
        // A field 1 pair, then an invalid DTVCC pair, between the packet's start and its data.
        val mixed = dtvcc.copyOfRange(0, 3) +
            bytes(0xFC, 0x94, 0x2C) + bytes(0xFA, 0x00, 0x00) +
            dtvcc.copyOfRange(3, dtvcc.size)
        assertEquals("OK", DtvccServices().read(mixed, epoch).getValue(1).decodeToString())
    }

    @Test
    fun aNewStartEndsAnUnfinishedPacketWithItsWholeBlocks() {
        // The first packet says it holds 12 pairs, but only its first block arrives whole.
        val first = bytes(0xFF, 0x06, (1 shl 5) or 2, 0xFE, 'A'.code, 'B'.code, 0xFE, (1 shl 5) or 8, 'C'.code)
        val second = dtvccTriplets(1, 1 to "NEXT".encodeToByteArray())
        val services = DtvccServices().read(first + second, epoch)
        assertEquals("ABNEXT", services.getValue(1).decodeToString(), "the cut block is dropped, the whole ones kept")
    }

    @Test
    fun aNewEpochDropsThePartOfAPacketASeekCut() {
        val all = dtvccTriplets(0, 1 to "OLD TEXT".encodeToByteArray())
        val reader = DtvccServices()
        reader.read(all.copyOfRange(0, 6), epoch)
        val after = reader.read(all.copyOfRange(6, all.size), Generation(2L))
        assertTrue(after.isEmpty(), "the rest of a packet from before the seek reads as nothing: $after")
        assertEquals("NEW", reader.read(dtvccTriplets(1, 1 to "NEW".encodeToByteArray()), Generation(2L)).getValue(1).decodeToString())
    }

    @Test
    fun sizeCodeZeroIsTheLargestPacket() {
        val text = ByteArray(31) { 'x'.code.toByte() }
        // Three blocks of 31 bytes, one of 30 and their headers fill the 127 bytes after the packet header.
        val triplets = dtvccTriplets(0, 1 to text, 1 to text, 1 to text, 1 to text.copyOf(30))
        assertEquals(0, triplets[1].toInt() and 0x3F, "the packet's size code")
        assertEquals(123, DtvccServices().read(triplets, epoch).getValue(1).size)
    }

    @Test
    fun aNullBlockEndsThePacket() {
        val services = DtvccServices().read(dtvccTriplets(0, 1 to "A".encodeToByteArray(), 0 to ByteArray(0), 2 to "B".encodeToByteArray()), epoch)
        assertEquals(setOf(1), services.keys)
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun theTestPacketsAreWhatTheStandardWrites() {
        // A packet of one pair: its header and one byte of padding.
        assertContentEquals(bytes(0xFF, 0x01, 0x00), dtvccTriplets(0))
    }
}

/**
 * The `cc_data` triplets of one DTVCC packet with [sequence] and [blocks], each a service number and
 * that service's bytes, as a caption encoder writes them: the start pair, then data pairs, padded
 * to whole pairs. Service 0 writes the null block.
 */
internal fun dtvccTriplets(sequence: Int, vararg blocks: Pair<Int, ByteArray>): ByteArray {
    var body = ByteArray(0)
    for ((service, data) in blocks) {
        body += if (service == 0) {
            byteArrayOf(0)
        } else if (service < 7) {
            byteArrayOf(((service shl 5) or data.size).toByte()) + data
        } else {
            byteArrayOf(((7 shl 5) or data.size).toByte(), service.toByte()) + data
        }
    }
    // The header byte and the body, padded to whole pairs.
    if ((body.size + 1) % 2 != 0) body += byteArrayOf(0)
    val pairs = (body.size + 1) / 2
    require(pairs in 1..64) { "a DTVCC packet holds 1 to 64 pairs, not $pairs" }
    val header = (((sequence and 3) shl 6) or (if (pairs == 64) 0 else pairs)).toByte()
    val packet = byteArrayOf(header) + body
    val out = ArrayList<Byte>()
    for (pair in 0 until pairs) {
        out += (if (pair == 0) 0xFF else 0xFE).toByte()
        out += packet[pair * 2]
        out += packet[pair * 2 + 1]
    }
    return out.toByteArray()
}
