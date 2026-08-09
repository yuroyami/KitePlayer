@file:OptIn(ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.TrackKind
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.runBlocking
import platform.posix.SEEK_END
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Decodes real media through the engine's interfaces, and checks the picture against FFmpeg's own.
 *
 * The colour tests are the important ones. A wrong matrix or a wrong range produces a picture that is
 * present and subtly wrong, which no amount of watching playback reliably catches. Comparing against
 * the reference the `ffmpeg` command line produces turns that into a failing assertion.
 */
class DecodeAndConvertTest {

    /** Set by the Gradle test task. Falls back to a relative path for a hand-run binary. */
    private val mediaDir: String = platform.posix.getenv("KITEPLAYER_TESTMEDIA")
        ?.toKString()
        ?: "testmedia"

    private fun readFile(path: String): ByteArray {
        val file = fopen(path, "rb") ?: error("cannot open $path. Run scripts/testmedia.sh first.")
        try {
            fseek(file, 0, SEEK_END)
            val size = ftell(file).toInt()
            fseek(file, 0, 0)
            val bytes = ByteArray(size)
            bytes.usePinned { pinned ->
                val read = fread(pinned.addressOf(0), 1uL, size.toULong(), file)
                check(read.toInt() == size) { "short read on $path: $read of $size" }
            }
            return bytes
        } finally {
            fclose(file)
        }
    }

    /** Opens a source with only its video stream selected, and decodes the first frame. */
    private suspend fun firstVideoFrame(file: String): Pair<KiteCodecSource, KiteCodecVideoFrame> {
        val source = KiteCodecSourceFactory().open(MediaItem("$mediaDir/$file")) as KiteCodecSource
        val stream = assertNotNull(source.firstVideo, "no video stream in $file")
        source.selectStreams(setOf(stream.index))
        val decoder = assertNotNull(
            source.videoDecoderFactories().first().create(stream, io.github.yuroyami.kiteplayer.HwdecPolicy.Auto),
        )

        var frame: VideoFrame? = null
        while (frame == null) {
            val packet = source.readPacket()
            if (packet == null) {
                // End of file: drain the decoder, which is what the null packet is for.
                decoder.send(null)
                frame = decoder.receive()
                break
            }
            if (packet.streamIndex != stream.index) {
                packet.close()
                continue
            }
            // A false return means the decoder is full and the packet was NOT consumed, so it must be
            // offered again after draining rather than discarded.
            while (!decoder.send(packet)) {
                frame = decoder.receive()
                if (frame != null) break
            }
            packet.close()
            if (frame == null) frame = decoder.receive()
        }
        return source to assertNotNull(frame, "no frame decoded from $file") as KiteCodecVideoFrame
    }

    @Test
    fun `the new packet reader and decoder produce a frame`() = runBlocking {
        val (source, frame) = firstVideoFrame("colors-bt709.mp4")
        try {
            assertEquals(320, frame.size.width)
            assertEquals(240, frame.size.height)
            assertEquals(PlayerPixelFormat.Yuv420p, frame.pixelFormat)
            assertTrue(frame.hasPts, "the first frame of a normal file carries a timestamp")
        } finally {
            frame.close()
            source.close()
        }
    }

    @Test
    fun `stream metadata a track menu needs is populated`() = runBlocking {
        val source = KiteCodecSourceFactory().open(MediaItem("$mediaDir/subbed.mkv")) as KiteCodecSource
        try {
            val subtitle = source.streams.firstOrNull { it.kind == TrackKind.Subtitle }
            assertNotNull(subtitle, "the Matroska fixture has a subtitle track")
            assertEquals("ass", subtitle.codec)

            val video = assertNotNull(source.firstVideo)
            assertEquals(1920, video.videoSize?.width)
            assertNotNull(video.frameRate, "a frame rate is needed to snap measured durations")
            assertTrue(video.frameRate!! > 29.0 && video.frameRate!! < 31.0, "expected about 30 fps")

            val audio = assertNotNull(source.firstAudio)
            assertEquals(48_000, audio.sampleRate)
        } finally {
            source.close()
        }
    }

    @Test
    fun `BT709 conversion matches the FFmpeg reference`() = runBlocking {
        assertMatchesReference("colors-bt709.mp4", "colors-bt709.rgba")
    }

    @Test
    fun `BT601 conversion matches the FFmpeg reference`() = runBlocking {
        // The whole point of reading the matrix off the frame. Converting this clip with the BT.709
        // coefficients shifts every hue, and the mean error below would be several times the limit.
        assertMatchesReference("colors-bt601.mp4", "colors-bt601.rgba")
    }

    @Test
    fun `ten bit conversion keeps the high bits`() = runBlocking {
        // Shifting the wrong way produces an image that is dark and noisy. The mean error catches it
        // immediately, because it would be enormous rather than marginal.
        assertMatchesReference("colors-10bit.mp4", "colors-10bit.rgba")
    }

    private suspend fun assertMatchesReference(clip: String, reference: String) {
        val expected = readFile("$mediaDir/$reference")
        val (source, frame) = firstVideoFrame(clip)
        val actual = try {
            SoftwareConverter.toRgba(frame)
        } finally {
            frame.close()
            source.close()
        }

        assertEquals(expected.size, actual.size, "the converted frame is the wrong size")

        var worst = 0
        var totalError = 0L
        var comparedComponents = 0
        for (i in expected.indices) {
            // Alpha is always opaque on both sides and carries no information.
            if (i % 4 == 3) continue
            val difference = abs((expected[i].toInt() and 0xFF) - (actual[i].toInt() and 0xFF))
            if (difference > worst) worst = difference
            totalError += difference
            comparedComponents++
        }
        val meanError = totalError.toDouble() / comparedComponents

        // A few units of difference is expected: rounding, and the exact point at which each
        // implementation clamps. A wrong matrix, range or bit shift produces a mean in the tens.
        assertTrue(
            meanError < 2.0,
            "mean component error $meanError is too high for $clip. Worst single component: $worst. " +
                "That size of error means the matrix, the range or the bit depth handling is wrong, " +
                "not that rounding differs.",
        )
        assertTrue(
            worst < 40,
            "worst component error $worst on $clip suggests a geometry or stride problem rather than " +
                "a rounding one",
        )
    }

    @Test
    fun `timestamps are monotonic across a whole clip and nothing leaks`() = runBlocking {
        val source = KiteCodecSourceFactory().open(MediaItem("$mediaDir/colors-bt709.mp4")) as KiteCodecSource
        val stream = assertNotNull(source.firstVideo)
        source.selectStreams(setOf(stream.index))
        val decoder = assertNotNull(
            source.videoDecoderFactories().first().create(stream, io.github.yuroyami.kiteplayer.HwdecPolicy.Auto),
        )

        var count = 0
        var previous = Pts(Long.MIN_VALUE)
        try {
            while (true) {
                val packet = source.readPacket()
                if (packet == null) {
                    decoder.send(null)
                    while (true) {
                        val frame = decoder.receive() ?: break
                        assertTrue(frame.pts >= previous, "timestamps went backwards at frame $count")
                        previous = frame.pts
                        count++
                        frame.close()
                    }
                    break
                }
                while (!decoder.send(packet)) {
                    val frame = decoder.receive() ?: break
                    assertTrue(frame.pts >= previous, "timestamps went backwards at frame $count")
                    previous = frame.pts
                    count++
                    frame.close()
                }
                packet.close()
                while (true) {
                    val frame = decoder.receive() ?: break
                    assertTrue(frame.pts >= previous, "timestamps went backwards at frame $count")
                    previous = frame.pts
                    count++
                    frame.close()
                }
            }
        } finally {
            decoder.close()
            source.close()
        }

        // One second at 25 fps.
        assertEquals(25, count, "expected every frame of the clip to decode")
    }
}
