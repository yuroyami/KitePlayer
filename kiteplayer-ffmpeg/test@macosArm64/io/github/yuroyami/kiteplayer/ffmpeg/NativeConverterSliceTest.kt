@file:OptIn(ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.HwdecPolicy
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * The native software converter splits a frame into row slices, as the common kernel does, and
 * still produces the same bytes (#247). It walked every row on the calling thread, which measured
 * 13.5 times slower than the sliced common kernel on a 1080p frame in a debug test binary.
 */
class NativeConverterSliceTest {

    private val mediaDir: String = platform.posix.getenv("KITEPLAYER_TESTMEDIA")?.toKString() ?: "testmedia"

    private suspend fun firstFrame(file: String): Pair<KiteFFmpegSource, KiteFFmpegVideoFrame> {
        val source = KiteFFmpegSourceFactory().open(MediaItem("$mediaDir/$file")) as KiteFFmpegSource
        val stream = assertNotNull(source.firstVideo, "no video stream in $file")
        source.selectStreams(setOf(stream.index))
        val decoder = assertNotNull(source.videoDecoderFactories().first().create(stream, HwdecPolicy.Off))
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
        return source to assertNotNull(frame, "no frame decoded from $file") as KiteFFmpegVideoFrame
    }

    private fun medianMillis(runs: Int, block: () -> Unit): Double {
        val times = List(runs) {
            val mark = TimeSource.Monotonic.markNow()
            block()
            mark.elapsedNow().inWholeMicroseconds / 1000.0
        }
        return times.sorted()[runs / 2]
    }

    @Test
    fun theNativeConverterSlicesA1080pFrameAndMatchesTheCommonKernel() = runBlocking {
        val (source, frame) = firstFrame("sync1080p30.mp4")
        try {
            val readable = frame.readableFrame()
            val planes = readable.copyPlanesToByteArray()
            fun common(): ByteArray = tightlyPackedToRgba(
                bytes = planes,
                width = frame.size.width,
                height = frame.size.height,
                pixelFormat = readable.info.pixelFormat.toPlayerFormat(),
                colorSpace = frame.colorSpace,
            )

            val native = SoftwareConverter.toRgba(frame)
            val packed = common()
            assertTrue(
                native.contentEquals(packed),
                "the sliced native converter and the common kernel differ first at index " +
                    native.indices.firstOrNull { native[it] != packed[it] },
            )

            val nativeMillis = medianMillis(RUNS) { SoftwareConverter.toRgba(frame) }
            val commonMillis = medianMillis(RUNS) { common() }
            val ratio = nativeMillis / commonMillis
            println("1080p to RGBA, median of $RUNS: native $nativeMillis ms, common kernel $commonMillis ms, ratio $ratio")
            assertTrue(
                ratio <= MOST_RATIO,
                "the native converter took $ratio times the common kernel ($nativeMillis ms against " +
                    "$commonMillis ms); one thread measured 13.5 times",
            )
        } finally {
            frame.close()
            source.close()
        }
    }

    private companion object {
        const val RUNS = 5

        /** The bound on native time over common time. See the measurement in the commit that set it. */
        const val MOST_RATIO = 8.0
    }
}
