package io.github.yuroyami.kiteplayer.ffmpeg

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class Aes128CbcTest {

    private fun bytes(hex: String): ByteArray {
        val digits = hex.filter { !it.isWhitespace() }
        return ByteArray(digits.length / 2) { digits.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    }

    @Test
    fun fourBlocksDecryptAsTheNistVectorSays() {
        // NIST SP 800-38A, F.2.2, CBC-AES128.Decrypt.
        val plain = Aes128Cbc.decrypt(
            bytes("7649abac8119b246cee98e9b12e9197d 5086cb9b507219ee95db113a917678b2 73bed6b8e3c1743b7116e69e22229516 3ff1caa1681fac09120eca307586e1a7"),
            key = bytes("2b7e151628aed2a6abf7158809cf4f3c"),
            iv = bytes("000102030405060708090a0b0c0d0e0f"),
            padded = false,
        )
        assertContentEquals(
            bytes("6bc1bee22e409f96e93d7e117393172a ae2d8a571e03ac9c9eb76fac45af8e51 30c81c46a35ce411e5fbc1191a0a52ef f69f2445df4f9b17ad2b417be66c3710"),
            plain,
        )
    }

    @Test
    fun thePaddingOfTheLastBlockIsLeftOut() {
        // Both made by `openssl aes-128-cbc -e`, which pads as an HLS segment is padded.
        val key = "0123456789abcdef".encodeToByteArray()
        val text = Aes128Cbc.decrypt(
            bytes("b966ed4454734ffdf516aca94970b5e7da574a5178fc40b19b5cc9e85d67edcc"), key, bytes("000102030405060708090a0b0c0d0e0f"),
        )
        assertEquals("a segment of an HLS stream", text.decodeToString())
        // Sixteen bytes take a whole block of padding.
        val block = Aes128Cbc.decrypt(
            bytes("0ad24560e1d7cb2518ea8b33a15bc68bdd55653692dd0d82f9d716228ad5f005"), key, bytes("00000000000000000000000000000007"),
        )
        assertEquals("0123456789abcdef", block.decodeToString())
    }

    @Test
    fun aWrongKeyOrBytesThatAreNotWholeBlocksFail() {
        val encrypted = bytes("b966ed4454734ffdf516aca94970b5e7da574a5178fc40b19b5cc9e85d67edcc")
        val iv = bytes("000102030405060708090a0b0c0d0e0f")
        assertFailsWith<IllegalArgumentException> { Aes128Cbc.decrypt(encrypted, "fedcba9876543210".encodeToByteArray(), iv) }
        assertFailsWith<IllegalArgumentException> { Aes128Cbc.decrypt(encrypted.copyOf(31), "0123456789abcdef".encodeToByteArray(), iv) }
        assertFailsWith<IllegalArgumentException> { Aes128Cbc.decrypt(ByteArray(0), "0123456789abcdef".encodeToByteArray(), iv) }
        assertFailsWith<IllegalArgumentException> { Aes128Cbc.decrypt(encrypted, ByteArray(24), iv) }
        assertFailsWith<IllegalArgumentException> { Aes128Cbc.decrypt(encrypted, "0123456789abcdef".encodeToByteArray(), ByteArray(8)) }
    }
}
