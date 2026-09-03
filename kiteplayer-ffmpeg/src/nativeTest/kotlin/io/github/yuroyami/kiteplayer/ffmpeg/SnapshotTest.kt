@file:OptIn(ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteffmpeg.MediaSource
import io.github.yuroyami.kiteffmpeg.MediaType
import io.github.yuroyami.kiteplayer.HwdecPolicy
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.runBlocking
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * A decoded frame becomes a standalone image in one call. The bytes are checked by their
 * signature and, for PNG, by decoding them back through KiteFFmpeg to the same size, which is the
 * only proof that the planes were packed the way the image encoder expects.
 */
class SnapshotTest {

    private val mediaDir: String = getenv("KITEPLAYER_TESTMEDIA")?.toKString() ?: "testmedia"

    private fun writeFile(path: String, bytes: ByteArray) {
        val file = fopen(path, "wb") ?: error("cannot write $path")
        try {
            bytes.usePinned { pinned ->
                val written = fwrite(pinned.addressOf(0), 1uL, bytes.size.toULong(), file)
                check(written.toInt() == bytes.size) { "short write on $path" }
            }
        } finally {
            fclose(file)
        }
    }

    /** The first software-decoded frame of [file], with hwdec off so the planes are readable. */
    private suspend fun firstVideoFrame(file: String): Pair<KiteFFmpegSource, KiteFFmpegVideoFrame> {
        val source = KiteFFmpegSourceFactory().open(MediaItem("$mediaDir/$file")) as KiteFFmpegSource
        val stream = assertNotNull(source.firstVideo, "no video stream in $file")
        source.selectStreams(setOf(stream.index))
        val decoder = assertNotNull(source.videoDecoderFactories().first().create(stream, HwdecPolicy.Off))
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
        return source to assertNotNull(frame, "no frame decoded from $file") as KiteFFmpegVideoFrame
    }

    @Test
    fun `a png snapshot carries the signature and decodes back to the same size`() = runBlocking {
        val (source, frame) = firstVideoFrame("colors-bt709.mp4")
        try {
            val png = frame.encode(SnapshotFormat.Png)
            assertEquals(
                listOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
                png.take(8).map { it.toInt() and 0xFF },
                "the eight PNG signature bytes",
            )

            val path = "$mediaDir/../build/snapshot-test.png"
            writeFile(path, png)
            val image = MediaSource.open(path)
            try {
                val video = assertNotNull(image.streams.first { it.type == MediaType.Video }.video)
                assertEquals(frame.size.width, video.width, "the image must be the frame's own width")
                assertEquals(frame.size.height, video.height)
            } finally {
                image.close()
            }
        } finally {
            frame.close()
            source.close()
        }
    }

    @Test
    fun `a jpeg snapshot starts with the jpeg marker`() = runBlocking {
        val (source, frame) = firstVideoFrame("colors-bt709.mp4")
        try {
            val jpeg = frame.encode()
            assertEquals(listOf(0xFF, 0xD8), jpeg.take(2).map { it.toInt() and 0xFF }, "JPEG is the default")
        } finally {
            frame.close()
            source.close()
        }
    }
}
