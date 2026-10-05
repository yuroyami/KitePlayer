package io.github.yuroyami.kiteplayer.ffmpeg

/** One page a teletext descriptor lists: its language, its type, and its number such as 0x888. */
internal class TeletextListing(val language: String, val type: Int, val page: Int)

/** One page sent at [second]: a header that erases it, then [text] boxed on row 22, or nothing. */
internal class TeletextSend(val second: Double, val page: Int, val text: String?)

/**
 * A transport stream of one DVB teletext stream on PID 0x101 (ETSI EN 300 472), its programme table
 * listing [pages] in a teletext descriptor, and one PES packet for each of [sends], written the way
 * a broadcaster's inserter writes them (#510). The tables and a clock reference go before every
 * PES packet, so FFmpeg finds the stream at once.
 */
internal fun teletextTransportStream(pages: List<TeletextListing>, sends: List<TeletextSend>): ByteArray {
    val out = ArrayList<Byte>()
    val counters = HashMap<Int, Int>()
    fun counter(pid: Int): Int = (counters[pid] ?: 0).also { counters[pid] = (it + 1) and 15 }

    fun put(pid: Int, payload: ByteArray) {
        var offset = 0
        var first = true
        while (offset < payload.size) {
            val chunk = payload.copyOfRange(offset, minOf(payload.size, offset + 184))
            offset += chunk.size
            val head = listOf(0x47, (if (first) 0x40 else 0) or (pid shr 8), pid and 0xFF)
            val cc = counter(pid)
            val fill = 184 - chunk.size
            val adaptation = when {
                fill == 0 -> listOf(0x10 or cc)
                fill == 1 -> listOf(0x30 or cc, 0)
                else -> listOf(0x30 or cc, fill - 1, 0) + List(fill - 2) { 0xFF }
            }
            (head + adaptation).forEach { out += it.toByte() }
            chunk.forEach { out += it }
            first = false
        }
    }

    fun table(pid: Int, section: ByteArray) = put(pid, byteArrayOf(0) + section + ByteArray(183 - section.size) { -1 })

    fun clock(pid: Int, base: Long) {
        val cc = counters[pid] ?: 0
        val field = listOf(
            183, 0x10, (base shr 25).toInt() and 0xFF, (base shr 17).toInt() and 0xFF, (base shr 9).toInt() and 0xFF,
            (base shr 1).toInt() and 0xFF, ((base.toInt() and 1) shl 7) or 0x7E, 0,
        )
        (listOf(0x47, pid shr 8, pid and 0xFF, 0x20 or cc) + field + List(184 - field.size) { 0xFF }).forEach { out += it.toByte() }
    }

    val pid = 0x101
    val pat = section(0x00, intArrayOf(0, 1, 0xE1, 0x00))
    val descriptor = pages.flatMap { listing ->
        listing.language.encodeToByteArray().map { it.toInt() } +
            listOf((listing.type shl 3) or (listing.page shr 8 and 7), listing.page and 0xFF)
    }
    val es = listOf(0x06, 0xE0 or (pid shr 8), pid and 0xFF, 0xF0, descriptor.size + 2, 0x56, descriptor.size) + descriptor
    val pmt = section(0x02, (listOf(0xE0 or (pid shr 8), pid and 0xFF, 0xF0, 0) + es).toIntArray())
    for (send in sends) {
        val pts = (send.second * 90_000).toLong()
        table(0, pat)
        table(0x100, pmt)
        clock(pid, maxOf(0, pts - 9_000))
        val units = buildList {
            add(TeletextData.header(send.page))
            send.text?.let { add(TeletextData.row(send.page, 22, TeletextData.boxed(it))) }
        }
        put(pid, pes(pts, units))
    }
    return out.toByteArray()
}

private fun pes(pts: Long, units: List<IntArray>): ByteArray {
    val body = ArrayList<Int>()
    body += 0x10
    for (unit in units) body += listOf(0x03, 0x2C) + unit.toList()
    while ((body.size + 45) % 184 != 0) body += listOf(0xFF, 0x2C) + List(44) { 0xFF }
    val header = listOf(
        0x84, 0x80, 0x24,
        0x21 or ((pts shr 29).toInt() and 0x0E), (pts shr 22).toInt() and 0xFF, 0x01 or ((pts shr 14).toInt() and 0xFE),
        (pts shr 7).toInt() and 0xFF, 0x01 or ((pts shl 1).toInt() and 0xFE),
    ) + List(36 - 5) { 0xFF }
    val length = header.size + body.size
    return (listOf(0, 0, 1, 0xBD, length shr 8, length and 0xFF) + header + body).map { it.toByte() }.toByteArray()
}

private fun section(tableId: Int, body: IntArray): ByteArray {
    val length = 5 + body.size + 4
    val bytes = listOf(tableId, 0xB0 or (length shr 8), length and 0xFF, 0, 1, 0xC1, 0, 0) + body.toList()
    var crc = 0xFFFFFFFFL
    for (byte in bytes) {
        crc = crc xor (byte.toLong() shl 24)
        repeat(8) { crc = (if (crc and 0x80000000L != 0L) (crc shl 1) xor 0x04C11DB7L else crc shl 1) and 0xFFFFFFFFL }
    }
    val sum = listOf((crc shr 24).toInt(), (crc shr 16).toInt() and 0xFF, (crc shr 8).toInt() and 0xFF, crc.toInt() and 0xFF)
    return (bytes + sum).map { it.toByte() }.toByteArray()
}

/** Teletext data units as EN 300 706 codes them and DVB sends them, each bit turned round. */
private object TeletextData {
    private val HAMMING_8_4 = intArrayOf(0x15, 0x02, 0x49, 0x5E, 0x64, 0x73, 0x38, 0x2F, 0xD0, 0xC7, 0x8C, 0x9B, 0xA1, 0xB6, 0xFD, 0xEA)

    private fun hamming(value: Int): Int = HAMMING_8_4[value and 15]

    private fun odd(code: Int): Int = (code and 0x7F).let { if (it.countOneBits() % 2 == 0) it or 0x80 else it }

    private fun reverse(byte: Int): Int {
        var reversed = 0
        for (bit in 0..7) if (byte shr bit and 1 == 1) reversed = reversed or (0x80 shr bit)
        return reversed
    }

    /** The 44 bytes after a unit's id and length: field and line, then the line's bytes turned round. */
    private fun unit(magazine: Int, row: Int, data: IntArray): IntArray {
        val address = (magazine and 7) or (row shl 3)
        val line = intArrayOf(0xE4, hamming(address and 15), hamming(address shr 4)) + data
        return intArrayOf(0xE0) + IntArray(43) { reverse(line[it]) }
    }

    /** The header of a subtitle page in serial mode that erases it and hides the header row. */
    fun header(page: Int): IntArray {
        val number = page and 0xFF
        val control = intArrayOf(
            hamming(number and 15), hamming(number shr 4), hamming(0), hamming(8), hamming(0), hamming(8), hamming(1), hamming(1),
        )
        return unit(page shr 8, 0, control + IntArray(32) { odd(' '.code) })
    }

    fun row(page: Int, row: Int, codes: IntArray): IntArray = unit(page shr 8, row, IntArray(40) { odd(codes.getOrElse(it) { 0x20 }) })

    fun boxed(text: String): IntArray = intArrayOf(0x0B, 0x0B) + text.map { it.code }.toIntArray() + intArrayOf(0x0A, 0x0A)
}
