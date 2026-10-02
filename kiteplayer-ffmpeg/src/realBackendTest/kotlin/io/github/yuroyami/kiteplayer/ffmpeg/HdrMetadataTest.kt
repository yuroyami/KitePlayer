package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.HwdecPolicy
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.TrackKind
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A frame reports the static HDR metadata its stream carries (#378): the `hdr10-meta.mp4` fixture
 * was encoded with a 4000 nit BT.2020 master, MaxCLL 4000 and MaxFALL 400.
 */
class HdrMetadataTest {

    @Test
    fun aDecodedFrameCarriesTheMasteringDisplayAndTheContentLight() = runBlocking {
        val mediaDir = formatMatrixMediaDir() ?: return@runBlocking
        val source = KiteFFmpegSourceFactory().open(MediaItem("$mediaDir/hdr10-meta.mp4")) as KiteFFmpegSource
        try {
            val video = source.streams.first { it.kind == TrackKind.Video }
            source.selectStreams(setOf(video.index))
            val decoder = checkNotNull(source.videoDecoderFactories().firstNotNullOfOrNull { it.create(video, HwdecPolicy.Off) })
            try {
                var frame: VideoFrame? = null
                while (frame == null) {
                    val packet = source.readPacket() ?: break
                    try {
                        while (!decoder.send(packet)) frame = decoder.receive() ?: break
                    } finally {
                        packet.close()
                    }
                    if (frame == null) frame = decoder.receive()
                }
                val decoded = checkNotNull(frame) { "hdr10-meta.mp4 produced no frame" }
                try {
                    val hdr = assertNotNull(decoded.hdr, "the frame carries no HDR metadata; the stream says ${video.hdr}")
                    assertEquals(4000f, hdr.masteringMaxNits)
                    assertTrue(abs(hdr.masteringMinNits!! - 0.005f) < 1e-6f, "the master's black is ${hdr.masteringMinNits}")
                    assertEquals(4000, hdr.maxContentLightNits)
                    assertEquals(400, hdr.maxFrameAverageNits)
                    val primaries = assertNotNull(hdr.masteringPrimaries)
                    assertTrue(abs(primaries.redX - 0.708f) < 1e-4f && abs(primaries.greenY - 0.797f) < 1e-4f, "primaries $primaries")
                    assertEquals(4000f, hdr.peakNits)
                } finally {
                    decoded.close()
                }
            } finally {
                decoder.close()
            }
        } finally {
            source.close()
        }
        Unit
    }
}
