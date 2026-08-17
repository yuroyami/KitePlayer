@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.HwdecKind
import io.github.yuroyami.kiteplayer.HwdecPolicy
import io.github.yuroyami.kiteplayer.HwdecStatus
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.spi.HwSurfaceKind
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.spi.VideoDecoder
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import kotlinx.cinterop.toKString
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The S2.b hardware arm, Apple only.
 *
 * It lived in the shared native test set until phase W added the Kotlin/Native desktop targets,
 * where it failed for the right reason: Linux and Windows have no VideoToolbox and their decoder
 * selection says so honestly. A test that names one platform's hwaccel belongs to that platform.
 */
class VideoToolboxDecodeTest {

    private val mediaDir: String = platform.posix.getenv("KITEPLAYER_TESTMEDIA")?.toKString() ?: "testmedia"

    @Test
    fun videotoolboxFramesArriveHardwareAndConvertThroughTheDownload() = runBlocking {
        val (source, decoder, frame) = firstVideoFrameAuto("colors-bt709.mp4")
        try {
            assertEquals(
                HwdecStatus.HardwareWithDownload(HwdecKind.VideoToolbox),
                decoder.hardware,
                "Auto on Apple must select VideoToolbox for h264",
            )
            assertEquals(HwSurfaceKind.CoreVideoPixelBuffer, frame.hardwareSurface)
            assertEquals(PlayerPixelFormat.Opaque, frame.pixelFormat)
            val rgba = SoftwareConverter.toRgba(frame)
            assertEquals(320 * 240 * 4, rgba.size)
            assertTrue(frame.hasPts, "the first hardware frame carries its timestamp")
        } finally {
            frame.close()
            source.close()
        }
    }

    /** Decodes the first frame with the platform's own hwdec policy, for the S2.b hardware arm. */
    private suspend fun firstVideoFrameAuto(file: String): Triple<KiteCodecSource, VideoDecoder, KiteCodecVideoFrame> {
        val source = KiteCodecSourceFactory().open(MediaItem("$mediaDir/$file")) as KiteCodecSource
        val stream = assertNotNull(source.firstVideo, "no video stream in $file")
        source.selectStreams(setOf(stream.index))
        val decoder = assertNotNull(
            source.videoDecoderFactories().first().create(stream, HwdecPolicy.Auto),
        )
        var frame: VideoFrame? = null
        while (frame == null) {
            val packet = source.readPacket() ?: break
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
        return Triple(source, decoder, assertNotNull(frame, "no frame decoded from $file") as KiteCodecVideoFrame)
    }
}
