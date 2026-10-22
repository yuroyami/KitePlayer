package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.TrackKind
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A reader's tags reach the player through FFmpeg (#423): a station's song, reported with the read
 * that starts at its byte, comes back on the first packet after that byte and stands in the source's
 * tags from then on. The stream is the test media's MP3 read as a radio station's bytes, in reads
 * that stop at the song's byte as a title block would stop them.
 */
class StreamTagsTest {

    /** [bytes] as a station's stream, with a song at [songAt]. */
    private class Station(private val bytes: ByteArray, private val songAt: Int) : MediaIo {
        private var position = 0
        private var pending: Map<String, String>? = mapOf("icy-name" to "Test FM")
        override val size: Long? get() = null
        override val seekable: Boolean get() = false

        override suspend fun read(into: ByteArray, offset: Int, length: Int): Int {
            if (position >= bytes.size) return -1
            val stop = if (position < songAt) songAt else bytes.size
            val count = minOf(length, stop - position)
            if (position == songAt) pending = (pending ?: emptyMap()) + ("StreamTitle" to "Miles - So What")
            bytes.copyInto(into, offset, position, position + count)
            position += count
            return count
        }

        override fun takeTags(): Map<String, String>? = pending.also { pending = null }
        override suspend fun seek(position: Long) = error("a station cannot seek")
        override fun close() = Unit
    }

    @Test
    fun aStationsSongArrivesOnThePacketAfterItsByte() = runBlocking {
        val dir = formatMatrixMediaDir() ?: return@runBlocking
        val mp3 = assertNotNull(readTestFile("$dir/audio-mp3.mp3"), "testmedia is missing; run scripts/testmedia.sh")
        val songAt = mp3.size / 2
        val item = MediaItem("http://radio.test/live", io = { Station(mp3, songAt) })
        val source = KiteFFmpegSourceFactory().open(item) as KiteFFmpegSource
        try {
            assertEquals("Test FM", source.metadata["icy-name"], "the station's own tags are not there at the open")
            assertNull(source.metadata["StreamTitle"])
            val audio = assertNotNull(source.streams.firstOrNull { it.kind == TrackKind.Audio })
            source.selectStreams(setOf(audio.index))
            var before = 0
            var changed: Map<String, String>? = null
            var changedAt: Long? = null
            while (changed == null) {
                val packet = source.readPacket() ?: break
                changed = packet.newContainerTags
                if (changed == null) before++ else changedAt = packet.bytePosition
                packet.close()
            }
            assertEquals("Miles - So What", changed?.get("StreamTitle"), "no packet brought the song")
            assertEquals("Test FM", changed?.get("icy-name"), "a change is the whole set")
            assertEquals("Miles - So What", source.metadata["StreamTitle"])
            assertTrue(before > 50, "the song came after only $before packets")
            // FFmpeg reads ahead into its own buffer, so the song comes on the first packet it hands
            // out after reading past the byte: a few frames early, as KiteFFmpeg documents.
            val at = assertNotNull(changedAt)
            assertTrue(at in (songAt - 4_096)..songAt, "the song came on the packet at byte $at, its byte being $songAt")
        } finally {
            source.close()
        }
    }
}
