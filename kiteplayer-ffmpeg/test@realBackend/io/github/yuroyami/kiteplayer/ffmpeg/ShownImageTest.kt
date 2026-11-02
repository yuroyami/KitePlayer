package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteffmpeg.MediaByteSource
import io.github.yuroyami.kiteffmpeg.MediaSource
import io.github.yuroyami.kiteffmpeg.MediaType
import io.github.yuroyami.kiteplayer.HwdecPolicy
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds

/**
 * A thumbnail and an encoded snapshot are the picture as it is shown (#608). The fixture
 * `rotated90ccw.mp4` stores a 320x240 picture and turns it a quarter, so it is shown 240 wide and
 * 320 high. Each image is decoded back through KiteFFmpeg, which is the only proof of its size.
 */
class ShownImageTest {

    @Test
    fun aThumbnailOfATurnedPictureIsTurned() = runBlocking {
        val dir = formatMatrixMediaDir() ?: return@runBlocking
        val thumbnail = Thumbnails.at(MediaItem("$dir/rotated90ccw.mp4"), listOf(0.seconds), maxWidth = 320).single()
        assertEquals(240 to 320, thumbnail.width to thumbnail.height, "the thumbnail lies on its side")
        assertEquals(240 to 320, decodedSize(thumbnail.bytes))
    }

    @Test
    fun aThumbnailOfATurnedPictureFitsItsWidthLimitAsShown() = runBlocking {
        val dir = formatMatrixMediaDir() ?: return@runBlocking
        val thumbnail = Thumbnails.at(MediaItem("$dir/rotated90ccw.mp4"), listOf(0.seconds), maxWidth = 120).single()
        assertEquals(120 to 160, decodedSize(thumbnail.bytes))
        assertEquals(120 to 160, thumbnail.width to thumbnail.height)
    }

    @Test
    fun aPictureThatIsNotTurnedKeepsItsSize() = runBlocking {
        val dir = formatMatrixMediaDir() ?: return@runBlocking
        val thumbnail = Thumbnails.at(MediaItem("$dir/colors-bt709.mp4"), listOf(0.seconds), maxWidth = 320).single()
        assertEquals(320 to 240, decodedSize(thumbnail.bytes))
    }

    @Test
    fun anEncodedSnapshotOfATurnedPictureIsTurned() = runBlocking {
        val dir = formatMatrixMediaDir() ?: return@runBlocking
        val source = KiteFFmpegSourceFactory().open(MediaItem("$dir/rotated90ccw.mp4")) as KiteFFmpegSource
        try {
            val frame = firstVideoFrame(source)
            try {
                assertEquals(320 to 240, frame.size.width to frame.size.height, "the frame is stored unturned")
                assertEquals(240 to 320, decodedSize(frame.encode(SnapshotFormat.Png)), "the snapshot lies on its side")
            } finally {
                frame.close()
            }
        } finally {
            source.close()
        }
    }

    private suspend fun firstVideoFrame(source: KiteFFmpegSource): KiteFFmpegVideoFrame {
        val stream = assertNotNull(source.firstVideo, "no video stream")
        source.selectStreams(setOf(stream.index))
        val decoder = assertNotNull(source.videoDecoderFactories().first().create(stream, HwdecPolicy.Off))
        try {
            var frame: VideoFrame? = null
            while (frame == null) {
                val packet = source.readPacket()
                if (packet == null) {
                    decoder.send(null)
                    frame = decoder.receive()
                    break
                }
                if (packet.streamIndex != stream.index) {
                    packet.close()
                    continue
                }
                while (!decoder.send(packet)) {
                    frame = decoder.receive()
                    if (frame != null) break
                }
                packet.close()
                if (frame == null) frame = decoder.receive()
            }
            return assertNotNull(frame, "no frame decoded") as KiteFFmpegVideoFrame
        } finally {
            decoder.close()
        }
    }

    private fun decodedSize(bytes: ByteArray): Pair<Int, Int> =
        MediaSource.open(Bytes(bytes)).use { image ->
            val video = assertNotNull(image.streams.first { it.type == MediaType.Video }.video)
            video.width to video.height
        }

    private class Bytes(private val bytes: ByteArray) : MediaByteSource {
        private var position = 0
        override val size: Long get() = bytes.size.toLong()
        override val seekable: Boolean get() = true

        override fun read(into: ByteArray, offset: Int, length: Int): Int {
            if (position >= bytes.size) return -1
            val count = minOf(length, bytes.size - position)
            bytes.copyInto(into, offset, position, position + count)
            position += count
            return count
        }

        override fun seek(position: Long) {
            this.position = position.coerceIn(0L, bytes.size.toLong()).toInt()
        }

        override fun close() = Unit
    }
}
