package io.github.yuroyami.kiteplayer.subtitle

/**
 * The protected codes of an EBU teletext packet (ETSI EN 300 706 §8), read from a DVB data unit
 * (ETSI EN 300 472), which carries every byte with its bits in reverse order (#510).
 */
internal object TeletextCoding {

    private val REVERSED = IntArray(256) { byte ->
        var reversed = 0
        for (bit in 0..7) if (byte shr bit and 1 == 1) reversed = reversed or (0x80 shr bit)
        reversed
    }

    /** The Hamming 8/4 codeword of each value, with its bits in the order the packet sends them. */
    private val HAMMING_8_4 = intArrayOf(0x15, 0x02, 0x49, 0x5E, 0x64, 0x73, 0x38, 0x2F, 0xD0, 0xC7, 0x8C, 0x9B, 0xA1, 0xB6, 0xFD, 0xEA)

    /** Each byte's value, from the codeword one bit or less away, or -1 for a byte two bits from every codeword. */
    private val DECODED_8_4 = IntArray(256) { byte ->
        HAMMING_8_4.indices.firstOrNull { (HAMMING_8_4[it] xor byte).countOneBits() <= 1 } ?: -1
    }

    /** [byte] as the line sent it, before DVB turned its bits round. */
    fun reversed(byte: Byte): Int = REVERSED[byte.toInt() and 0xFF]

    /** The four bits Hamming 8/4 protects in [byte], one error corrected, or -1 for two. */
    fun hamming84(byte: Int): Int = DECODED_8_4[byte and 0xFF]

    /**
     * The 18 bits Hamming 24/18 protects in three bytes sent low byte first, one error corrected, or
     * -1 for two. Each of the five checks and the whole word have odd parity.
     */
    fun hamming2418(low: Int, middle: Int, high: Int): Int {
        var word = (low and 0xFF) or (middle and 0xFF shl 8) or (high and 0xFF shl 16)
        // Each set bit adds its position, from 1, to the low five bits and one to the sixth, so a
        // clean word reads all ones.
        var test = 0
        for (bit in 0..22) if (word shr bit and 1 == 1) test = test xor (bit + 33)
        if (word shr 23 and 1 == 1) test = test xor 32
        if (test and 0x1F != 0x1F) {
            if (test and 0x20 != 0) return -1
            val wrong = 30 - (test and 0x1F)
            if (wrong !in 0..22) return -1
            word = word xor (1 shl wrong)
        }
        return (word and 0x4 shr 2) or (word and 0x70 shr 3) or (word and 0x7F00 shr 4) or (word and 0x7F0000 shr 5)
    }

    /** The seven bits of a character byte with odd parity, or -1 when its parity is even. */
    fun oddParity(byte: Int): Int = if ((byte and 0xFF).countOneBits() and 1 == 1) byte and 0x7F else -1
}
