package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.TrackKind
import io.github.yuroyami.kiteplayer.HwdecPolicy
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.spi.SoftwareReadableFrame
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * No production frame type implemented [SoftwareReadableFrame], so
 * `CapturedFrame.of` took its refusal branch for every real decoded frame on every platform and
 * the public captureFrame() feature was dead. This decodes a real frame and proves the planes
 * can be read through the SPI the capture path uses.
 */
class SoftwareReadableFrameTest {

    @Test
    fun aDecodedSoftwareFrameExposesItsPlanes() = runBlocking {
        val mediaDir = formatMatrixMediaDir() ?: error("testmedia missing; run scripts/testmedia.sh")
        val source = KiteFFmpegSourceFactory().open(MediaItem("$mediaDir/baseline.mkv")) as KiteFFmpegSource
        try {
            val video = source.streams.first { it.kind == TrackKind.Video }
            source.selectStreams(setOf(video.index))
            val decoder = checkNotNull(
                source.videoDecoderFactories().firstNotNullOfOrNull { it.create(video, HwdecPolicy.Off) },
            ) { "no software decoder accepted ${video.codec}" }
            try {
                var frame: VideoFrame? = null
                while (frame == null) {
                    val packet = source.readPacket() ?: break
                    try {
                        while (!decoder.send(packet)) {
                            frame = decoder.receive() ?: break
                            if (frame != null) break
                        }
                    } finally {
                        packet.close()
                    }
                    if (frame == null) frame = decoder.receive()
                }
                val decoded = checkNotNull(frame) { "baseline.mkv produced no frame" }
                try {
                    val readable = assertIs<SoftwareReadableFrame>(
                        decoded,
                        "a software-decoded frame must expose its planes to the capture path",
                    )
                    assertEquals(PlayerPixelFormat.Yuv420p, decoded.pixelFormat)
                    assertEquals(3, readable.planeCount)
                    val width = decoded.size.width
                    val height = decoded.size.height
                    assertEquals(width, readable.planeStride(0), "luma stride must be the width, tightly packed")
                    assertEquals(height, readable.planeHeight(0))
                    assertEquals((width + 1) / 2, readable.planeStride(1))
                    assertEquals((height + 1) / 2, readable.planeHeight(1))
                    val luma = ByteArray(readable.planeStride(0) * readable.planeHeight(0))
                    readable.copyPlane(0, luma)
                    assertTrue(luma.any { it.toInt() != 0 }, "the copied luma plane is all zeroes")
                    val chroma = ByteArray(readable.planeStride(2) * readable.planeHeight(2))
                    readable.copyPlane(2, chroma)
                    assertTrue(chroma.isNotEmpty())
                } finally {
                    decoded.close()
                }
            } finally {
                decoder.close()
            }
        } finally {
            source.close()
        }
    }
}
