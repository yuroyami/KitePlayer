package io.github.yuroyami.kiteplayer.network

import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.network.dash.Mp4Bytes
import io.github.yuroyami.kiteplayer.network.dash.Mp4Bytes.Sample
import kotlinx.coroutines.test.runTest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An AES-128 encrypted TTML rendition is decrypted before it is converted (#742). The rewrite kept
 * the key tag and read the ciphertext as TTML, which failed as "not a well formed TTML document",
 * so the subtitles showed nothing. Encrypted here with the JDK's own cipher.
 */
class HlsTtmlAesTest {

    private val key = ByteArray(16) { (it * 13 + 7).toByte() }

    private fun encrypt(plain: ByteArray, iv: ByteArray): ByteArray =
        Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            doFinal(plain)
        }

    private fun ttml(paragraph: String) =
        """<tt xmlns="http://www.w3.org/ns/ttml"><body><div>$paragraph</div></body></tt>""".encodeToByteArray()

    private val init = Mp4Bytes.init(trackId = 1, timescale = 1000, handler = "subt", sampleEntry = "stpp")
    private val first = Mp4Bytes.segment(1, decodeTime = 0, listOf(Sample(2000, ttml("""<p begin="0.5s" end="1.5s">Hello</p>"""))))
    private val second = Mp4Bytes.segment(1, decodeTime = 2000, listOf(Sample(2000, ttml("""<p begin="2.5s" end="3s">Again</p>"""))))
    private val explicitIv = ByteArray(16) { (0xA0 + it).toByte() }

    /** The second segment takes its IV from its media sequence number, 8 here. */
    private val sequenceIv = ByteArray(16).also { it[15] = 8 }

    private val files = mapOf(
        "https://cdn.test/hls/subs/en.m3u8" to """
            #EXTM3U
            #EXT-X-TARGETDURATION:2
            #EXT-X-MEDIA-SEQUENCE:7
            #EXT-X-MAP:URI="init.mp4"
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin",IV=0x${explicitIv.joinToString("") { "%02x".format(it) }}
            #EXTINF:2.0,
            s1.mp4
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin"
            #EXTINF:2.0,
            s2.mp4
            #EXT-X-ENDLIST
        """.trimIndent().encodeToByteArray(),
        "https://cdn.test/hls/subs/init.mp4" to init,
        "https://cdn.test/hls/subs/s1.mp4" to encrypt(first, explicitIv),
        "https://cdn.test/hls/subs/s2.mp4" to encrypt(second, sequenceIv),
        "https://cdn.test/hls/subs/key.bin" to key,
    )

    private inner class Served(override val location: String, private val bytes: ByteArray) : MediaIo {
        private var position = 0
        override val size: Long get() = bytes.size.toLong()
        override val seekable: Boolean get() = true
        override suspend fun read(into: ByteArray, offset: Int, length: Int): Int {
            if (position >= bytes.size) return -1
            val count = minOf(length, bytes.size - position)
            bytes.copyInto(into, offset, position, position + count)
            position += count
            return count
        }
        override suspend fun seek(position: Long) {
            this.position = position.toInt()
        }
        override suspend fun openRelated(uri: String): MediaIo? = files[uri]?.let { Served(uri, it) }
        override fun close() {}
    }

    private suspend fun MediaIo.text(): String {
        val out = StringBuilder()
        val buffer = ByteArray(4096)
        while (true) {
            val count = read(buffer, 0, buffer.size)
            if (count < 0) return out.toString()
            out.append(buffer.decodeToString(0, count))
        }
    }

    @Test
    fun anEncryptedTtmlRenditionIsDecryptedBeforeItIsConverted() = runTest {
        val reader = HlsTtmlMediaIo(Served("https://cdn.test/hls/master.m3u8", ByteArray(0)), setOf("https://cdn.test/hls/subs/en.m3u8"))
        val playlist = reader.openRelated("https://cdn.test/hls/subs/en.m3u8")!!.text().lines()
        assertTrue(playlist.none { it.startsWith("#EXT-X-KEY") }, "the plain WebVTT is still named as encrypted: $playlist")
        val segments = playlist.filter { it.startsWith("https://") }
        assertEquals("WEBVTT\n\n00:00:00.500 --> 00:00:01.500\nHello\n\n", reader.openRelated(segments[0])!!.text())
        assertEquals("WEBVTT\n\n00:00:02.500 --> 00:00:03.000\nAgain\n\n", reader.openRelated(segments[1])!!.text())
    }
}
