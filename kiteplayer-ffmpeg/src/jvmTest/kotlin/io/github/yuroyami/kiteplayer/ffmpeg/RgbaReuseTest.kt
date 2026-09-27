package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.HwdecPolicy
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.TrackKind
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import kotlinx.coroutines.runBlocking
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A renderer that passes back the array it got for the previous frame allocates the RGBA bytes
 * once, not once per frame (#246). At 1080p those bytes are 8.3 MB, which at 30 frames a second
 * was about 250 MB/s of garbage from this one array.
 */
class RgbaReuseTest {

    private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    private fun allocatedBy(block: () -> Unit): Long {
        val before = threads.currentThreadAllocatedBytes
        block()
        return threads.currentThreadAllocatedBytes - before
    }

    @Test
    fun aReusedArrayIsWrittenInPlaceAndMatchesAFreshOne() = runBlocking {
        val mediaDir = formatMatrixMediaDir() ?: error("testmedia missing; run scripts/testmedia.sh")
        val source = KiteFFmpegSourceFactory().open(MediaItem("$mediaDir/sync1080p30.mp4")) as KiteFFmpegSource
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
                val decoded = checkNotNull(frame) { "sync1080p30.mp4 produced no frame" } as KiteFFmpegVideoFrame
                try {
                    val rgbaBytes = decoded.size.width.toLong() * decoded.size.height * 4
                    val fresh = SoftwareConverter.toRgba(decoded)
                    val reused = ByteArray(fresh.size)
                    // Warm both paths, so class loading is not counted as the conversion's own.
                    SoftwareConverter.toRgba(decoded, reused)
                    SoftwareConverter.toRgba(decoded)

                    val written = SoftwareConverter.toRgba(decoded, reused)
                    assertSame(reused, written, "an array of the right size is written in place")
                    assertTrue(reused.contentEquals(fresh), "the reused array holds the same picture as a fresh one")

                    val withReuse = allocatedBy { SoftwareConverter.toRgba(decoded, reused) }
                    val without = allocatedBy { SoftwareConverter.toRgba(decoded) }
                    println("1080p toRgba allocates $without bytes on its thread, and $withReuse with reuse")
                    assertTrue(
                        withReuse < rgbaBytes,
                        "a conversion into a reused array still allocated $withReuse bytes, more than the " +
                            "$rgbaBytes bytes of the RGBA array itself",
                    )
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
