package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.HwdecPolicy
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.TrackKind
import io.github.yuroyami.kiteplayer.spi.ColorMatrix
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * H.264 coded as RGB, converted by the JVM's software converter.
 *
 * The decoder hands it over as planar GBR, which is the Identity layout. The clip is lossless, so
 * its first frame is the source picture byte for byte.
 */
class RgbCodedVideoTest {

    @Test
    fun anRgbCodedH264FrameConvertsToExactlyItsSourcePicture() = runBlocking {
        val mediaDir = formatMatrixMediaDir() ?: error("testmedia missing; run scripts/testmedia.sh")
        val expected = File(mediaDir, "colors-gbr.rgba").readBytes()
        val source = KiteFFmpegSourceFactory().open(MediaItem("$mediaDir/colors-rgb-h264.mp4")) as KiteFFmpegSource
        try {
            val video = source.streams.first { it.kind == TrackKind.Video }
            source.selectStreams(setOf(video.index))
            val decoder = checkNotNull(
                source.videoDecoderFactories().firstNotNullOfOrNull { it.create(video, HwdecPolicy.Off) },
            ) { "no software decoder accepted ${video.codec}" }
            try {
                var frame: VideoFrame? = null
                while (frame == null) {
                    val packet = source.readPacket()
                    if (packet == null) {
                        // End of file: the clip is two frames long, and a decoder that reorders
                        // hands its first frame over only when it is drained.
                        decoder.send(null)
                        frame = decoder.receive()
                        break
                    }
                    try {
                        while (!decoder.send(packet)) {
                            frame = decoder.receive() ?: break
                        }
                    } finally {
                        packet.close()
                    }
                    if (frame == null) frame = decoder.receive()
                }
                val decoded = checkNotNull(frame) { "colors-rgb-h264.mp4 produced no frame" } as KiteFFmpegVideoFrame
                val actual = try {
                    assertEquals(PlayerPixelFormat.Yuv444p, decoded.pixelFormat, "planar GBR is modelled as 4:4:4")
                    assertEquals(ColorMatrix.Identity, decoded.colorSpace.matrix, "under the Identity matrix")
                    SoftwareConverter.toRgba(decoded)
                } finally {
                    decoded.close()
                }
                assertEquals(expected.size, actual.size, "the converted frame is the wrong size")
                val first = expected.indices.firstOrNull { expected[it] != actual[it] }
                assertEquals(
                    null,
                    first,
                    "an RGB-coded frame must come out as the picture it codes. First difference at byte $first",
                )
            } finally {
                decoder.close()
            }
        } finally {
            source.close()
        }
    }
}
