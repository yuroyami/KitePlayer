package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.ofBytes
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A file whose tags are not valid UTF-8 opens on every target, with the bad byte read as U+FFFD
 * (#208). The JNI bridge used to refuse the whole open on the JVM and Android.
 */
class InvalidTagTextTest {

    @Test
    fun aLatin1TitleOpensWithAReplacementCharacter() = runBlocking {
        openSource(MediaItem("memory://latin1-title.mp3", io = MediaIo.ofBytes(mp3WithLatin1Title()))).use { source ->
            val title = source.metadata["title"]
            assertTrue(title != null && title.startsWith("Bj") && title.contains('�'), "the title read as $title")
        }
        Unit
    }

    /** Forty silent MPEG-1 Layer III frames, then an ID3v1 tag whose title is "Björk" in ISO-8859-1. */
    private fun mp3WithLatin1Title(): ByteArray {
        val frame = ByteArray(417).also {
            it[0] = 0xFF.toByte()
            it[1] = 0xFB.toByte()
            it[2] = 0x90.toByte()
            it[3] = 0x00
        }
        val tag = ByteArray(128)
        "TAG".encodeToByteArray().copyInto(tag, 0)
        byteArrayOf(0x42, 0x6A, 0xF6.toByte(), 0x72, 0x6B).copyInto(tag, 3)
        tag[127] = 0xFF.toByte()
        var bytes = ByteArray(0)
        repeat(40) { bytes += frame }
        return bytes + tag
    }
}
