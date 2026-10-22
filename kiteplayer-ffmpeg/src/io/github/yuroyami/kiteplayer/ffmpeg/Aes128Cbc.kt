package io.github.yuroyami.kiteplayer.ffmpeg

/**
 * AES-128 in CBC mode, decryption only, as FIPS 197 and NIST SP 800-38A describe it. An HLS
 * segment under `METHOD=AES-128` is encrypted this way with PKCS7 padding (RFC 8216, section 4.3.2.4).
 *
 * The common code has no cipher, and the variant switch must hold the plain bytes of an fMP4
 * segment to write it again (#565). The key comes from the stream's own server and the plain bytes
 * are played, so the table lookups here guard nothing that timing could give away.
 */
internal object Aes128Cbc {
    private const val BLOCK = 16
    private const val ROUNDS = 10

    private val inverseBox = IntArray(256)
    private val box = IntArray(256)

    /** The products of each byte with 9, 11, 13 and 14 in the cipher's field, which undo the column mix. */
    private val times9 = IntArray(256)
    private val times11 = IntArray(256)
    private val times13 = IntArray(256)
    private val times14 = IntArray(256)

    init {
        // The substitution box: each byte's inverse in the field, then the affine step.
        var p = 1
        var q = 1
        do {
            p = (p xor (p shl 1) xor (if (p and 0x80 != 0) 0x1B else 0)) and 0xFF
            q = q xor (q shl 1)
            q = q xor (q shl 2)
            q = (q xor (q shl 4)) and 0xFF
            if (q and 0x80 != 0) q = q xor 0x09
            val x = q xor rotate(q, 1) xor rotate(q, 2) xor rotate(q, 3) xor rotate(q, 4) xor 0x63
            box[p] = x
            inverseBox[x] = p
        } while (p != 1)
        box[0] = 0x63
        inverseBox[0x63] = 0
        for (i in 0 until 256) {
            val two = double(i)
            val four = double(two)
            val eight = double(four)
            times9[i] = eight xor i
            times11[i] = eight xor two xor i
            times13[i] = eight xor four xor i
            times14[i] = eight xor four xor two
        }
    }

    private fun rotate(value: Int, by: Int): Int = ((value shl by) or (value ushr (8 - by))) and 0xFF

    private fun double(value: Int): Int = ((value shl 1) xor (if (value and 0x80 != 0) 0x1B else 0)) and 0xFF

    /**
     * The plain bytes of [data], which [key] and [iv] encrypted. With [padded], the PKCS7 padding
     * of the last block is checked and left out. Fails when [data] is not whole blocks or the
     * padding is not one, which a wrong key shows as.
     */
    fun decrypt(data: ByteArray, key: ByteArray, iv: ByteArray, padded: Boolean = true): ByteArray {
        require(key.size == BLOCK) { "an AES-128 key has 16 bytes, not ${key.size}" }
        require(iv.size == BLOCK) { "an AES-128 IV has 16 bytes, not ${iv.size}" }
        require(data.size % BLOCK == 0 && (data.isNotEmpty() || !padded)) { "${data.size} encrypted bytes are not whole blocks of 16" }
        val keys = roundKeys(key)
        val out = ByteArray(data.size)
        val state = IntArray(BLOCK)
        val moved = IntArray(BLOCK)
        var at = 0
        while (at < data.size) {
            for (i in 0 until BLOCK) state[i] = (data[at + i].toInt() and 0xFF) xor keys[ROUNDS * BLOCK + i]
            for (round in ROUNDS - 1 downTo 0) {
                // The row shift and the substitution undone in one pass: row r moved r columns right.
                for (column in 0 until 4) {
                    for (row in 0 until 4) moved[4 * ((column + row) and 3) + row] = inverseBox[state[4 * column + row]]
                }
                for (i in 0 until BLOCK) moved[i] = moved[i] xor keys[round * BLOCK + i]
                if (round == 0) {
                    for (i in 0 until BLOCK) state[i] = moved[i]
                } else {
                    for (column in 0 until 4) {
                        val a = moved[4 * column]
                        val b = moved[4 * column + 1]
                        val c = moved[4 * column + 2]
                        val d = moved[4 * column + 3]
                        state[4 * column] = times14[a] xor times11[b] xor times13[c] xor times9[d]
                        state[4 * column + 1] = times9[a] xor times14[b] xor times11[c] xor times13[d]
                        state[4 * column + 2] = times13[a] xor times9[b] xor times14[c] xor times11[d]
                        state[4 * column + 3] = times11[a] xor times13[b] xor times9[c] xor times14[d]
                    }
                }
            }
            for (i in 0 until BLOCK) {
                val chained = if (at == 0) iv[i] else data[at - BLOCK + i]
                out[at + i] = (state[i] xor (chained.toInt() and 0xFF)).toByte()
            }
            at += BLOCK
        }
        if (!padded) return out
        val padding = out[out.size - 1].toInt() and 0xFF
        require(padding in 1..BLOCK && (out.size - padding until out.size).all { out[it].toInt() and 0xFF == padding }) {
            "the decrypted bytes do not end in their padding, so the key or the IV is not the segment's"
        }
        return out.copyOf(out.size - padding)
    }

    /** The eleven round keys of [key], one byte to an entry. */
    private fun roundKeys(key: ByteArray): IntArray {
        val keys = IntArray((ROUNDS + 1) * BLOCK)
        for (i in 0 until BLOCK) keys[i] = key[i].toInt() and 0xFF
        var constant = 1
        for (word in 4 until 4 * (ROUNDS + 1)) {
            val at = 4 * word
            var a = keys[at - 4]
            var b = keys[at - 3]
            var c = keys[at - 2]
            var d = keys[at - 1]
            if (word % 4 == 0) {
                val first = a
                a = box[b] xor constant
                b = box[c]
                c = box[d]
                d = box[first]
                constant = double(constant)
            }
            keys[at] = keys[at - BLOCK] xor a
            keys[at + 1] = keys[at - BLOCK + 1] xor b
            keys[at + 2] = keys[at - BLOCK + 2] xor c
            keys[at + 3] = keys[at - BLOCK + 3] xor d
        }
        return keys
    }
}
