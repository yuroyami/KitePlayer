@file:OptIn(ExperimentalForeignApi::class, KiteCodecLowLevelApi::class)

package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.TrackKind
import io.github.yuroyami.kiteplayer.spi.ChromaLocation
import io.github.yuroyami.kiteplayer.spi.ColorMatrix
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import io.github.yuroyami.kitecodec.FilterGraph
import io.github.yuroyami.kitecodec.KiteCodecLowLevelApi
import io.github.yuroyami.kitecodec.PixelFormat
import io.github.yuroyami.kitecodec.Rational
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
import kotlin.test.assertFalse
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

    @Test
    fun `SMPTE 240M conversion matches the FFmpeg reference`() = runBlocking {
        // SMPTE 240M used to share BT.601's coefficient row. Its luma weights sit between BT.601's and
        // BT.709's, so that row is a real error and not a naming detail: measured on this clip it puts
        // the mean component error at 7.7 of 255 against the 0.18 the correct row gives.
        assertMatchesReference("colors-smpte240m.mp4", "colors-smpte240m.rgba") { frame ->
            // Asserted rather than assumed, for the same reason the P010 case asserts its tags: a
            // fixture that lost its tag would fall back to BT.709 and this would quietly become a
            // second BT.709 test that happens to pass.
            assertEquals(
                ColorMatrix.Smpte240m,
                frame.colorSpace.matrix,
                "the fixture must decode as SMPTE 240M, or nothing here is about its own matrix",
            )
        }
    }

    @Test
    fun `centre-sited NV12 conversion matches the FFmpeg reference`() = runBlocking {
        // Two things at once, and nothing covered either before: an NV12 frame converted at all, and
        // its chroma column rule read from the same place the planar path reads it. The clip is tagged
        // centre-sited, and shifting the chroma column for that siting moves the mean error from 0.31
        // to 8.12, so this pins the rule rather than merely exercising the format.
        val expected = readFile("$mediaDir/colors-nv12.rgba")
        val (source, frame) = firstVideoFrame("colors-nv12.mkv")
        val actual = try {
            assertEquals(PlayerPixelFormat.Nv12, frame.pixelFormat, "the fixture must decode as NV12")
            assertEquals(
                ChromaLocation.Center,
                frame.colorSpace.chromaLocation,
                "the fixture must declare centre-sited chroma, or it proves nothing about siting",
            )
            SoftwareConverter.toRgba(frame)
        } finally {
            frame.close()
            source.close()
        }
        assertCloseToReference("colors-nv12.mkv", expected, actual)
    }

    @Test
    fun `P010 conversion reads the high-aligned words`() = runBlocking {
        // P010 keeps its ten bits in the top ten of each 16-bit word, where yuv420p10le keeps them in
        // the low ten. Reading one as the other is off by a factor of sixteen: the mean component
        // error is 97 of 255 that way round and 0.58 this way.
        //
        // The frame is lifted to P010 here rather than read from a P010 file because no file can hand
        // one over. FFmpeg has no P010 entry in its raw pixel format tag table, so no container can
        // store the format, and no software decoder produces it either. The lift is one filter, and
        // the reference dump goes through the same intermediate, so what is compared is a real P010
        // frame against FFmpeg's own reading of the same P010 bytes.
        val expected = readFile("$mediaDir/colors-p010.rgba")
        val (source, decoded) = firstVideoFrame("colors-p010.mp4")
        val actual = try {
            assertEquals(PlayerPixelFormat.Yuv420p10le, decoded.pixelFormat, "the source must be 10-bit planar")
            // The two values the lift restates. Asserted rather than assumed, because the restatement
            // below is written out and a fixture retagged without it would measure a range conversion.
            assertEquals(ColorMatrix.Bt709, decoded.colorSpace.matrix, "the fixture must be tagged BT.709")
            assertFalse(decoded.colorSpace.fullRange, "the fixture must be tagged studio range")
            val asP010 = assertNotNull(decoded.liftedTo(PixelFormat.P010le), "the filter produced no frame")
            try {
                assertEquals(PlayerPixelFormat.P010le, asP010.pixelFormat, "the filter must produce P010")
                assertEquals(
                    decoded.colorSpace,
                    asP010.colorSpace,
                    "the lift must not change the colour, or the comparison below measures the matrix " +
                        "rather than the bit alignment",
                )
                SoftwareConverter.toRgba(asP010)
            } finally {
                asP010.close()
            }
        } finally {
            decoded.close()
            source.close()
        }
        assertCloseToReference("colors-p010.mp4 lifted to P010", expected, actual)
    }

    /**
     * Runs this frame through a two-filter graph and returns the result, still in native memory.
     *
     * The colour is restated first, and that is not decoration. A buffer source is configured with the
     * size, the pixel format and the timing and nothing else, so the graph's input link declares its
     * colour space and its range unspecified. The format filter then inserts a scale, and a scale
     * whose input range is unspecified expands studio range to full range on the way through.
     * Measured on this fixture: without the `setparams` the lifted luma of the first pixel is 349 of
     * 1023 where the source holds 288, and the converted picture misses the reference by a mean of
     * 7.38 instead of 0.58. So without it this case would measure a range conversion and call it a
     * bit alignment.
     *
     * `feedInput` takes ownership of what it is given, so the receiver is spent afterwards. Its
     * `close` is idempotent, which is what lets the caller keep a plain `finally`.
     */
    private fun KiteCodecVideoFrame.liftedTo(target: PixelFormat): KiteCodecVideoFrame? {
        val info = frame.info
        val graph = FilterGraph.buildVideo(
            description = "setparams=range=tv:colorspace=bt709,format=${target.name}",
            width = info.width,
            height = info.height,
            pixelFormat = info.pixelFormat,
            timeBase = info.timeBase,
            frameRate = Rational(25, 1),
            sampleAspectRatio = info.sampleAspectRatio,
        )
        var lifted: KiteCodecVideoFrame? = null
        try {
            graph.feedInput(0, frame) { filtered ->
                // A frame handed to this callback is valid only for the call, so what is kept is a
                // copy, which takes a reference rather than copying pixels.
                if (lifted == null) lifted = KiteCodecVideoFrame(filtered.copy(), pts, duration, generation)
            }
        } finally {
            graph.close()
        }
        return lifted
    }

    /**
     * Converts the first frame of [clip] and compares it against [reference], byte for byte.
     *
     * [check] runs on the decoded frame before the conversion, for a case whose fixture has to carry a
     * particular tag: a comparison against a reference proves the arithmetic and says nothing about
     * which arithmetic was chosen.
     */
    private suspend fun assertMatchesReference(
        clip: String,
        reference: String,
        check: (KiteCodecVideoFrame) -> Unit = {},
    ) {
        val expected = readFile("$mediaDir/$reference")
        val (source, frame) = firstVideoFrame(clip)
        val actual = try {
            check(frame)
            SoftwareConverter.toRgba(frame)
        } finally {
            frame.close()
            source.close()
        }
        assertCloseToReference(clip, expected, actual)
    }

    private fun assertCloseToReference(clip: String, expected: ByteArray, actual: ByteArray) {
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
