package io.github.yuroyami.kiteplayer.network.dash

import io.github.yuroyami.kiteplayer.network.dash.WebmBytes.element
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** A later Period's WebM clusters and MPEG-TS packets moved onto the presentation's timeline (#403). */
class TimelineRewriteTest {

    private val block = element(0xA3, byteArrayOf(0x81.toByte(), 0, 0, 0x80.toByte(), 1, 2, 3))

    /** A cluster as ffmpeg writes one: a known size, then a two byte timestamp, then its blocks. */
    private fun cluster(timestamp: Int) = byteArrayOf(0x1F, 0x43, 0xB6.toByte(), 0x75) +
        WebmBytes.size(4L + block.size) + byteArrayOf(0xE7.toByte(), 0x82.toByte(), (timestamp shr 8).toByte(), timestamp.toByte()) + block

    /** Each cluster's timestamp and the bytes after it, read back. */
    private fun clusters(bytes: ByteArray): List<Pair<Long, ByteArray>> {
        val out = ArrayList<Pair<Long, ByteArray>>()
        var at = 0
        while (at < bytes.size) {
            assertContentEquals(byteArrayOf(0x1F, 0x43, 0xB6.toByte(), 0x75), bytes.copyOfRange(at, at + 4))
            assertContentEquals(WebmBytes.UNKNOWN_SIZE, bytes.copyOfRange(at + 4, at + 12), "the cluster's size is unknown now")
            assertEquals(0xE7.toByte(), bytes[at + 12])
            assertEquals(0x88.toByte(), bytes[at + 13], "the timestamp is eight bytes long")
            var time = 0L
            for (i in 0 until 8) time = (time shl 8) or (bytes[at + 14 + i].toLong() and 0xFF)
            out += time to bytes.copyOfRange(at + 22, at + 22 + block.size)
            at += 22 + block.size
        }
        return out
    }

    @Test
    fun eachClustersTimestampMovesAndItsBlocksStay() {
        val moved = WebmRewrite.shiftClusters(cluster(2007) + cluster(4007), shiftTicks = 70_000)
        assertEquals(listOf(72_007L, 74_007L), clusters(moved).map { it.first })
        clusters(moved).forEach { assertContentEquals(block, it.second) }
    }

    @Test
    fun clustersOfUnknownSizeEndAtTheNextCluster() {
        fun open(timestamp: Int) = byteArrayOf(0x1F, 0x43, 0xB6.toByte(), 0x75) + WebmBytes.UNKNOWN_SIZE +
            byteArrayOf(0xE7.toByte(), 0x82.toByte(), (timestamp shr 8).toByte(), timestamp.toByte()) + block
        val moved = WebmRewrite.shiftClusters(open(0) + open(2000), shiftTicks = 5)
        assertEquals(listOf(5L, 2005L), clusters(moved).map { it.first })
    }

    @Test
    fun noShiftLeavesTheBytesAlone() {
        val bytes = cluster(1)
        assertSame(bytes, WebmRewrite.shiftClusters(bytes, 0))
    }

    /** One 188 byte packet: an adaptation field with a PCR, then a PES header with a PTS and a DTS. */
    private fun tsPacket(pcr: Long, pts: Long, dts: Long): ByteArray {
        val packet = ByteArray(188) { 0xFF.toByte() }
        packet[0] = 0x47
        packet[1] = 0x41 // payload unit start, PID 0x100
        packet[2] = 0x00
        packet[3] = 0x30 // adaptation field and payload
        packet[4] = 7 // adaptation field length
        packet[5] = 0x10 // PCR present
        for (i in 0 until 4) packet[6 + i] = (pcr shr (25 - 8 * i)).toByte()
        packet[10] = (((pcr and 1).toInt() shl 7) or 0x7E).toByte()
        packet[11] = 0
        var at = 12
        val pes = byteArrayOf(0, 0, 1, 0xE0.toByte(), 0, 0, 0x80.toByte(), 0xC0.toByte(), 10)
        pes.copyInto(packet, at)
        at += pes.size
        fun timestamp(prefix: Int, value: Long) {
            packet[at] = (prefix or (((value shr 30) and 7).toInt() shl 1) or 1).toByte()
            packet[at + 1] = (value shr 22).toByte()
            packet[at + 2] = ((((value shr 15) and 0x7F).toInt() shl 1) or 1).toByte()
            packet[at + 3] = (value shr 7).toByte()
            packet[at + 4] = (((value and 0x7F).toInt() shl 1) or 1).toByte()
            at += 5
        }
        timestamp(0x30, pts)
        timestamp(0x10, dts)
        return packet
    }

    private fun readTimestamp(packet: ByteArray, at: Int): Long {
        fun b(i: Int) = packet[at + i].toLong() and 0xFF
        return (((b(0) shr 1) and 7) shl 30) or (b(1) shl 22) or ((b(2) shr 1) shl 15) or (b(3) shl 7) or (b(4) shr 1)
    }

    private fun readPcr(packet: ByteArray): Long {
        var base = 0L
        for (i in 0 until 4) base = (base shl 8) or (packet[6 + i].toLong() and 0xFF)
        return (base shl 1) or ((packet[10].toLong() and 0x80) shr 7)
    }

    @Test
    fun ptsDtsAndPcrMoveTogetherAndWrapAt33Bits() {
        val packet = tsPacket(pcr = 900_000, pts = 903_003, dts = 900_000)
        val moved = TsRewrite.shift(packet + tsPacket(pcr = (1L shl 33) - 10, pts = (1L shl 33) - 5, dts = (1L shl 33) - 10), shift90k = 1_800_000)
        assertEquals(2_700_000L, readPcr(moved))
        assertEquals(2_703_003L, readTimestamp(moved, 21))
        assertEquals(2_700_000L, readTimestamp(moved, 26))
        val second = moved.copyOfRange(188, 376)
        assertEquals(1_799_990L, readPcr(second), "the PCR wraps")
        assertEquals(1_799_995L, readTimestamp(second, 21), "the PTS wraps")
        assertEquals(0x3, (second[21].toInt() shr 4) and 0xF, "the PTS keeps its prefix")
        assertEquals(0x7E, second[10].toInt() and 0x7F, "the PCR keeps its extension")
        assertEquals(188 * 2, moved.size)
    }
}
