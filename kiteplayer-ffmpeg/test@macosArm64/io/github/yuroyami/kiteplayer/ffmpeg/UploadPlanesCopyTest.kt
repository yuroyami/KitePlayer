@file:OptIn(ExperimentalForeignApi::class, KiteFFmpegLowLevelApi::class)

package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteffmpeg.KiteFFmpegLowLevelApi
import io.github.yuroyami.kiteffmpeg.withPlanes
import io.github.yuroyami.kiteplayer.HwdecPolicy
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.runBlocking
import platform.posix.memcpy
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * [uploadPlanesOrNull] copies each plane in one C copy (#207). With cinterop's `readBytes` it
 * stored one byte per loop turn: 368 ms against 0.29 ms of `memcpy` for a 1080p frame in a debug
 * test binary.
 */
class UploadPlanesCopyTest {

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

    private fun medianMillis(block: () -> Unit): Double {
        val times = List(RUNS) {
            val mark = TimeSource.Monotonic.markNow()
            block()
            mark.elapsedNow().inWholeNanoseconds / 1e6
        }
        return times.sorted()[RUNS / 2]
    }

    @Test
    fun theUploadPlanesHoldTheFrameBytesAtAboutTheCostOfMemcpy() = runBlocking {
        val (source, frame) = firstFrame("sync1080p30.mp4")
        try {
            val uploaded = assertNotNull(frame.uploadPlanesOrNull(), "a software frame has planes")
            val expected = frame.frame.withPlanes { planes, strides, heights ->
                planes.indices.map { planes[it].readBytes(strides[it] * heights[it]) }
            }
            assertEquals(expected.size, uploaded.planes.size)
            expected.indices.forEach { assertContentEquals(expected[it], uploaded.planes[it].bytes, "plane $it") }

            val targets = expected.map { ByteArray(it.size) }
            val memcpyMillis = medianMillis {
                frame.frame.withPlanes { planes, _, _ ->
                    planes.indices.forEach { index ->
                        targets[index].usePinned { memcpy(it.addressOf(0), planes[index], targets[index].size.convert()) }
                    }
                }
            }
            val uploadMillis = medianMillis { frame.uploadPlanesOrNull() }
            val ratio = uploadMillis / memcpyMillis.coerceAtLeast(0.001)
            println("1080p upload planes, median of $RUNS: uploadPlanesOrNull $uploadMillis ms, memcpy $memcpyMillis ms, ratio $ratio")
            assertTrue(ratio <= MOST_RATIO, "uploadPlanesOrNull took $ratio times the memcpy; readBytes measured about 1,300 times")
        } finally {
            frame.close()
            source.close()
        }
    }

    private companion object {
        const val RUNS = 7

        /**
         * Five times the highest ratio an M2 measured for these copies, which ranged from 4 to 10,
         * because allocating and zeroing the new array varies more than the copy does. The old byte
         * loop sat near 1,300.
         */
        const val MOST_RATIO = 50.0
    }
}
