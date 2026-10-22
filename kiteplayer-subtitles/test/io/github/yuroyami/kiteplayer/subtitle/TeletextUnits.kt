package io.github.yuroyami.kiteplayer.subtitle

/**
 * Hand-built DVB teletext data units, written the way a broadcaster's inserter writes them (ETSI EN
 * 300 706 and EN 300 472), for the reader's tests (#510). The same bytes, put in a transport stream,
 * are what FFmpeg's libzvbi decoder was measured on.
 */
internal object TeletextUnits {

    private val HAMMING_8_4 = intArrayOf(0x15, 0x02, 0x49, 0x5E, 0x64, 0x73, 0x38, 0x2F, 0xD0, 0xC7, 0x8C, 0x9B, 0xA1, 0xB6, 0xFD, 0xEA)

    const val START_BOX = 0x0B
    const val END_BOX = 0x0A

    fun reverse(byte: Int): Int {
        var reversed = 0
        for (bit in 0..7) if (byte shr bit and 1 == 1) reversed = reversed or (0x80 shr bit)
        return reversed
    }

    fun hamming84(value: Int): Int = HAMMING_8_4[value and 15]

    /** [code] with odd parity in its top bit. */
    fun odd(code: Int): Int = (code and 0x7F).let { if (it.countOneBits() % 2 == 0) it or 0x80 else it }

    /** [value]'s 18 bits as three Hamming 24/18 bytes, low byte first, every check of odd parity. */
    fun hamming2418(value: Int): IntArray {
        val bits = IntArray(25)
        val dataPositions = intArrayOf(3, 5, 6, 7, 9, 10, 11, 12, 13, 14, 15, 17, 18, 19, 20, 21, 22, 23)
        for ((index, position) in dataPositions.withIndex()) bits[position] = value shr index and 1
        for (check in intArrayOf(1, 2, 4, 8, 16)) {
            var sum = 0
            for (position in 1..23) if (position != check && position and check != 0) sum = sum xor bits[position]
            bits[check] = 1 xor sum
        }
        var all = 0
        for (position in 1..23) all = all xor bits[position]
        bits[24] = 1 xor all
        var word = 0
        for (position in 1..24) word = word or (bits[position] shl (position - 1))
        return intArrayOf(word and 0xFF, word shr 8 and 0xFF, word shr 16 and 0xFF)
    }

    fun triplet(address: Int, mode: Int, data: Int): Int = address or (mode shl 6) or (data shl 11)

    /** One subtitle data unit: its id, its length, and 44 bytes with every bit turned round as DVB sends them. */
    fun unit(magazine: Int, row: Int, data: IntArray): ByteArray {
        require(data.size == 40)
        val address = (magazine and 7) or (row shl 3)
        val line = intArrayOf(0xE4, hamming84(address and 15), hamming84(address shr 4)) + data
        return byteArrayOf(0x03, 0x2C, 0xE0.toByte()) + ByteArray(43) { reverse(line[it]).toByte() }
    }

    /** The header of [page], such as 0x888, with the national [option] in the standard's order. */
    fun header(
        page: Int,
        erase: Boolean = true,
        subtitle: Boolean = true,
        serial: Boolean = true,
        option: Int = 0,
        newsflash: Boolean = false,
        inhibit: Boolean = false,
    ): ByteArray {
        val number = page and 0xFF
        val control = intArrayOf(
            hamming84(number and 15),
            hamming84(number shr 4),
            hamming84(0),
            hamming84(if (erase) 8 else 0),
            hamming84(0),
            hamming84((if (newsflash) 4 else 0) or (if (subtitle) 8 else 0)),
            // C7 suppresses the header row, as subtitle pages do.
            hamming84(1 or (if (inhibit) 8 else 0)),
            hamming84((if (serial) 1 else 0) or (option and 1 shl 3) or (option shr 1 and 1 shl 2) or (option shr 2 and 1 shl 1)),
        )
        return unit(page shr 8, 0, control + IntArray(32) { odd(' '.code) })
    }

    /** Row [row] of [page]'s magazine holding [codes], then spaces. */
    fun row(page: Int, row: Int, vararg codes: Int): ByteArray =
        unit(page shr 8, row, IntArray(40) { odd(codes.getOrElse(it) { 0x20 }) })

    /** [text] in a box, as a subtitle page sends it, after any attribute [lead] codes. */
    fun boxed(text: String, vararg lead: Int): IntArray =
        lead + intArrayOf(START_BOX, START_BOX) + text.map { it.code }.toIntArray() + intArrayOf(END_BOX, END_BOX)

    /** X/28/0 Format 1 for a page of basic level one: its first set [code] and an optional [second] set. */
    fun pageSets(page: Int, code: Int, second: Int = 0): ByteArray {
        val first = (code and 0x7F shl 7) or (second and 0xF shl 14)
        val data = intArrayOf(hamming84(0)) + hamming2418(first) + hamming2418(second shr 4 and 7) +
            IntArray(11 * 3).also { rest -> for (index in 0 until 11) hamming2418(0).copyInto(rest, index * 3) }
        return unit(page shr 8, 28, data)
    }

    /** M/29/0: the first set [code] of every page of the magazine that names none. */
    fun magazineSets(magazine: Int, code: Int): ByteArray {
        val data = intArrayOf(hamming84(0)) + hamming2418(code and 0x7F shl 7) +
            IntArray(12 * 3).also { rest -> for (index in 0 until 12) hamming2418(0).copyInto(rest, index * 3) }
        return unit(magazine, 29, data)
    }

    /** X/26 packets carrying [triplets], thirteen to a packet, padded with termination markers. */
    fun enhancements(page: Int, triplets: List<Int>): List<ByteArray> {
        val end = triplet(63, 0x1F, 0x7F)
        return triplets.chunked(13).mapIndexed { designation, chunk ->
            val padded = chunk + List(13 - chunk.size) { end }
            unit(page shr 8, 26, intArrayOf(hamming84(designation)) + padded.flatMap { hamming2418(it).toList() }.toIntArray())
        }
    }

    /** A data unit of stuffing, which a stream sends to fill its packets. */
    val stuffing: ByteArray = byteArrayOf(0xFF.toByte(), 0x2C) + ByteArray(44) { 0xFF.toByte() }

    /** One PES payload, as FFmpeg hands it over: the EBU data identifier and then [units]. */
    fun payload(vararg units: ByteArray): ByteArray = units.fold(byteArrayOf(0x10)) { all, unit -> all + unit }

    fun payload(units: List<ByteArray>): ByteArray = payload(*units.toTypedArray())
}
