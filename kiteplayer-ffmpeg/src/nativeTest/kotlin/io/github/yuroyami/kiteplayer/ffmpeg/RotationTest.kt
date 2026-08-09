package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.HwdecPolicy
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.TrackKind
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * A container's display matrix, from the file to the frame a renderer receives.
 *
 * Rotation is the one piece of video metadata that is wrong in a way nobody can miss: a phone records
 * in portrait, stores the pixels landscape, and writes a matrix saying which way to turn them. A player
 * that drops the matrix shows the whole video on its side.
 *
 * `rotated90ccw.mp4` is `colors-bt709.mp4` remuxed with `-display_rotation 90` and nothing else, so the
 * two clips carry the same pictures and differ only in that matrix. Measured: extracted as elementary
 * streams the two are byte identical, `shasum cb05cecae0e2`. That is what makes the pair a measurement
 * rather than an observation, because the unrotated clip is the negative control for the value below.
 *
 * The drawing itself is asserted in `kiteplayer-output`, by `AppKitVideoRendererTest`, because the
 * renderer's testable seam is its internal constructor and `internal` in Kotlin does not cross a module
 * boundary. What is asserted here is everything up to the renderer: the value the container carries,
 * that it reaches every frame, and that nothing before the renderer turns the picture.
 */
@OptIn(ExperimentalForeignApi::class)
class RotationTest {

    /** Set by the Gradle test task. Falls back to a relative path for a hand-run binary. */
    private val mediaDir: String = platform.posix.getenv("KITEPLAYER_TESTMEDIA")
        ?.toKString()
        ?: "testmedia"

    /**
     * 270 and not 90, and the sign is the whole point.
     *
     * `ffmpeg`'s `-display_rotation` takes counter-clockwise degrees, so the fixture's matrix asks for a
     * quarter turn counter-clockwise. KiteCodec reports clockwise degrees, which is what a renderer
     * applies, and a quarter turn counter-clockwise is three quarters clockwise. Both swap the output
     * width and height, which is the behaviour the renderer test measures.
     */
    private val fixtureRotation = 270

    @Test
    fun `the display matrix reaches the stream and leaves the stored size alone`() = runBlocking {
        val source = KiteCodecSourceFactory().open(MediaItem("$mediaDir/rotated90ccw.mp4")) as KiteCodecSource
        try {
            val video = assertNotNull(source.firstVideo, "the fixture has a video stream")
            assertEquals(
                fixtureRotation,
                video.rotationDegrees,
                "the container's display matrix has to arrive as a clockwise rotation in degrees",
            )
            // The size is storage and not presentation. A source that swapped it here would report a
            // 240x320 picture whose pixels are 320x240, and every stride in the converter would be wrong.
            assertEquals(320, video.videoSize?.width, "the stored width is what the pictures are")
            assertEquals(240, video.videoSize?.height, "the stored height is what the pictures are")

            // Nothing but a video stream carries a matrix, and the audio-free fixture proves the field
            // is not being filled in from somewhere else.
            assertEquals(
                listOf(TrackKind.Video),
                source.streams.map { it.kind },
                "the fixture is video only, so the rotation above can only have come from that stream",
            )
        } finally {
            source.close()
        }
    }

    @Test
    fun `a clip with no display matrix reports no rotation`() = runBlocking {
        // The clip the fixture was remuxed from, so the same pictures with no matrix over them. Without
        // this the assertion above would pass just as well on a source that reported 270 for everything.
        val source = KiteCodecSourceFactory().open(MediaItem("$mediaDir/colors-bt709.mp4")) as KiteCodecSource
        try {
            assertEquals(0, assertNotNull(source.firstVideo).rotationDegrees)
        } finally {
            source.close()
        }
    }

    @Test
    fun `every decoded frame carries the rotation and none of them is turned before the renderer`() = runBlocking {
        val source = KiteCodecSourceFactory().open(MediaItem("$mediaDir/rotated90ccw.mp4")) as KiteCodecSource
        val stream = assertNotNull(source.firstVideo)
        source.selectStreams(setOf(stream.index))
        val decoder = assertNotNull(source.videoDecoderFactories().first().create(stream, HwdecPolicy.Auto))

        var frames = 0
        try {
            while (true) {
                val packet = source.readPacket()
                if (packet == null) {
                    decoder.send(null)
                    while (true) {
                        frames += checkFrame(decoder.receive() ?: break)
                    }
                    break
                }
                while (!decoder.send(packet)) {
                    frames += checkFrame(decoder.receive() ?: break)
                }
                packet.close()
                while (true) {
                    frames += checkFrame(decoder.receive() ?: break)
                }
            }
        } finally {
            decoder.close()
            source.close()
        }

        // One second at 25 fps. The rotation is a property of the stream, so it has to be on the last
        // frame as surely as on the first.
        assertEquals(25, frames, "expected every frame of the clip to decode")
    }

    /**
     * Asserts what one frame of the rotated clip must look like, and closes it.
     *
     * The conversion is the one the renderer is given, `SoftwareConverter.toRgba`, and it hands back the
     * picture as stored. Turning it is the renderer's job and nothing else's: a converter that rotated
     * would have to rotate for every renderer on every platform, including the ones that turn a picture
     * for free in a transform matrix.
     */
    private fun checkFrame(frame: VideoFrame): Int {
        val kiteFrame = frame as KiteCodecVideoFrame
        try {
            assertEquals(fixtureRotation, kiteFrame.rotationDegrees, "the frame carries its stream's rotation")
            assertEquals(320, kiteFrame.size.width)
            assertEquals(240, kiteFrame.size.height)
            assertEquals(
                320 * 240 * 4,
                SoftwareConverter.toRgba(kiteFrame).size,
                "the conversion hands over the stored picture, so the turn is still ahead of it",
            )
        } finally {
            kiteFrame.close()
        }
        return 1
    }
}
