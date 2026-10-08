@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.output

import kotlinx.coroutines.runBlocking
import platform.CoreVideo.CVPixelBufferGetHeight
import platform.CoreVideo.CVPixelBufferGetPixelFormatType
import platform.CoreVideo.CVPixelBufferGetWidth
import platform.CoreVideo.kCVPixelFormatType_32BGRA
import platform.CoreVideo.kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Subtitles burned into the picture that the sample buffer layer gets, read back from the buffer.
 *
 * These need a Metal device, which the iOS simulator does not give a test process, so they run on
 * the macOS host with the other Metal tests.
 */
class SampleBufferSubtitleTest {

    @Test
    fun textIsBurnedIntoThePictureWhileTheOverlayHasSome() = runBlocking {
        val sink = RecordingSampleSink()
        val renderer = SampleBufferVideoRenderer(resolve = { redNv12(64, 64) }, sink = sink)
        try {
            renderer.setOverlay(whiteSquare())
            assertTrue(renderer.present(SampleTestFrame(64, 64, limited709), targetNanos = 0L))
            val image = imageOf(sink.samples.single())
            assertEquals(kCVPixelFormatType_32BGRA, CVPixelBufferGetPixelFormatType(image))
            assertEquals(64uL, CVPixelBufferGetWidth(image))
            assertEquals(64uL, CVPixelBufferGetHeight(image))
            val text = bgraAt(image, 32, 32)
            assertTrue(text.take(3).all { it > 200 }, "the text pixel must be white, got ${text.toList()}")
            val picture = bgraAt(image, 8, 8)
            assertTrue(picture[2] > 150 && picture[0] < 100, "the picture pixel must be red, got ${picture.toList()}")
        } finally {
            renderer.close()
            sink.release()
        }
    }

    /** NV12, full range, dark in the left half and bright in the right half. */
    private fun darkLeftBrightRight(width: Int, height: Int) = MetalPicture.SoftwarePlanes(
        width = width,
        height = height,
        format = io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat.Nv12,
        planes = listOf(
            MetalPicture.SoftwarePlanes.Plane(ByteArray(width * height) { at -> if (at % width < width / 2) 40 else 200.toByte() }, width, height),
            MetalPicture.SoftwarePlanes.Plane(ByteArray(width * height / 2) { 128.toByte() }, width, height / 2),
        ),
    )

    private val fullRange709 = limited709.copy(fullRange = true)

    @Test
    fun aTurnedPictureReachesTheLayerTurnedClockwise() = runBlocking {
        val sink = RecordingSampleSink()
        val renderer = SampleBufferVideoRenderer(resolve = { darkLeftBrightRight(64, 32) }, sink = sink)
        try {
            assertTrue(renderer.present(SampleTestFrame(64, 32, fullRange709, rotationDegrees = 90), targetNanos = 0L))
            val image = imageOf(sink.samples.single())
            assertEquals(32uL, CVPixelBufferGetWidth(image), "a quarter turn exchanges the sides")
            assertEquals(64uL, CVPixelBufferGetHeight(image))
            // Turned clockwise, the stored left half is on top.
            assertTrue(bgraAt(image, 16, 8)[1] < 80, "the top must be the dark half, got ${bgraAt(image, 16, 8).toList()}")
            assertTrue(bgraAt(image, 16, 56)[1] > 160, "the bottom must be the bright half, got ${bgraAt(image, 16, 56).toList()}")
        } finally {
            renderer.close()
            sink.release()
        }
    }

    @Test
    fun aMirroredPictureReachesTheLayerMirrored() = runBlocking {
        val sink = RecordingSampleSink()
        val renderer = SampleBufferVideoRenderer(resolve = { darkLeftBrightRight(64, 32) }, sink = sink)
        try {
            assertTrue(renderer.present(SampleTestFrame(64, 32, fullRange709, mirrored = true), targetNanos = 0L))
            val image = imageOf(sink.samples.single())
            assertEquals(64uL, CVPixelBufferGetWidth(image))
            assertTrue(bgraAt(image, 8, 16)[1] > 160, "the left must be the bright half, got ${bgraAt(image, 8, 16).toList()}")
            assertTrue(bgraAt(image, 56, 16)[1] < 80, "the right must be the dark half, got ${bgraAt(image, 56, 16).toList()}")
        } finally {
            renderer.close()
            sink.release()
        }
    }

    @Test
    fun aClearKeepsTheTextOnBlackUntilTheTextGoes() = runBlocking {
        val sink = RecordingSampleSink()
        val renderer = SampleBufferVideoRenderer(resolve = { redNv12(64, 64) }, sink = sink)
        try {
            renderer.setOverlay(whiteSquare())
            assertTrue(renderer.present(SampleTestFrame(64, 64, limited709), targetNanos = 0L))
            renderer.clearPicture()
            assertEquals(2, sink.samples.size, "the text stays on the layer without the picture")
            val image = imageOf(sink.samples[1])
            assertEquals(kCVPixelFormatType_32BGRA, CVPixelBufferGetPixelFormatType(image))
            assertEquals(64uL, CVPixelBufferGetWidth(image), "the buffer takes the overlay's viewport")
            val text = bgraAt(image, 32, 32)
            assertTrue(text.take(3).all { it > 200 }, "the text pixel must be white, got ${text.toList()}")
            val background = bgraAt(image, 8, 8)
            assertTrue(background.take(3).all { it == 0 }, "the picture must be gone, got ${background.toList()}")

            renderer.setOverlay(noText())
            assertEquals(1, sink.flushes, "with the text gone there is nothing left to show")
            assertEquals(2, sink.samples.size)
            assertTrue(sink.samples.all { displaysImmediately(it) }, "the text on black must show at once too")
        } finally {
            renderer.close()
            sink.release()
        }
    }

    @Test
    fun aSubtitleChangeRedrawsThePausedPicture() = runBlocking {
        val sink = RecordingSampleSink()
        val renderer = SampleBufferVideoRenderer(resolve = { redNv12(64, 64) }, sink = sink)
        try {
            assertTrue(renderer.present(SampleTestFrame(64, 64, limited709), targetNanos = 0L))
            // Text arrives with no new frame, as it does while paused.
            renderer.setOverlay(whiteSquare())
            assertEquals(2, sink.samples.size, "the text must reach the layer without a new frame")
            assertEquals(kCVPixelFormatType_32BGRA, CVPixelBufferGetPixelFormatType(imageOf(sink.samples[1])))
            // And leaves the same way.
            renderer.setOverlay(noText())
            assertEquals(3, sink.samples.size, "the text must leave the layer without a new frame")
            assertEquals(
                kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
                CVPixelBufferGetPixelFormatType(imageOf(sink.samples[2])),
            )
            assertTrue(sink.samples.all { displaysImmediately(it) }, "a redraw must show at once too")
        } finally {
            renderer.close()
            sink.release()
        }
    }

    // ── The flash guard (#562) ───────────────────────────────────────────────────────────────

    /** NV12, full range, one grey. */
    private fun greyNv12(luma: Int) = MetalPicture.SoftwarePlanes(
        width = 64,
        height = 64,
        format = io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat.Nv12,
        planes = listOf(
            MetalPicture.SoftwarePlanes.Plane(ByteArray(64 * 64) { luma.toByte() }, 64, 64),
            MetalPicture.SoftwarePlanes.Plane(ByteArray(64 * 32) { 128.toByte() }, 64, 32),
        ),
    )

    /**
     * Presents a black and white strobe, three pictures each at 30 a second, and answers the
     * samples the layer got, one for each picture.
     */
    private fun strobed(
        mode: io.github.yuroyami.kiteplayer.FlashGuard?,
        systemSetting: Boolean = false,
        pictures: Int = 60,
        then: suspend (SampleBufferVideoRenderer, RecordingSampleSink) -> Unit = { _, _ -> },
    ): List<Pair<Boolean, Int>> = runBlocking {
        val sink = RecordingSampleSink()
        var luma = 0
        var clock = 0L
        val renderer = SampleBufferVideoRenderer(
            resolve = { greyNv12(luma) },
            sink = sink,
            dimFlashingLights = { systemSetting },
            flashNanos = { clock },
        )
        try {
            if (mode != null) renderer.setFlashGuard(mode)
            repeat(pictures) { index ->
                luma = if (index / 3 % 2 == 0) 0 else 255
                clock = index * 1_000_000_000L / 30
                assertTrue(renderer.present(SampleTestFrame(64, 64, fullRange709), targetNanos = clock))
            }
            then(renderer, sink)
            // For each sample: whether it was composed, and the green of its centre when it was.
            sink.samples.map { sample ->
                val image = imageOf(sample)
                val composed = CVPixelBufferGetPixelFormatType(image) == kCVPixelFormatType_32BGRA
                composed to if (composed) bgraAt(image, 32, 32)[1] else -1
            }
        } finally {
            renderer.close()
            sink.release()
        }
    }

    @Test
    fun aStrobeIsDimmedFromThePictureThatMakesItsRun() {
        val shown = strobed(io.github.yuroyami.kiteplayer.FlashGuard.On)
        assertEquals(60, shown.size)
        // The seventh leg is on picture 21. It is measured before it is shown, so it is dimmed itself.
        assertTrue(shown.take(21).none { it.first }, "before the run every picture reaches the layer as it is stored")
        assertTrue(shown.drop(21).all { it.first }, "inside the run every picture is composed")
        val whites = shown.drop(21).filterIndexed { index, _ -> (index + 21) / 3 % 2 == 1 }.map { it.second }
        // 0.08 of the light of white is about 81 of 255.
        assertTrue(whites.all { it in 70..92 }, "a white picture of the run came out as $whites")
    }

    @Test
    fun aStrobeIsShownWholeWithTheGuardOffAndFollowsTheSystemSettingOtherwise() {
        assertTrue(strobed(io.github.yuroyami.kiteplayer.FlashGuard.Off, systemSetting = true).none { it.first })
        assertTrue(strobed(mode = null, systemSetting = false).none { it.first }, "FollowSystem with the setting off")
        assertTrue(strobed(mode = null, systemSetting = true).drop(21).all { it.first }, "FollowSystem with the setting on")
    }

    @Test
    fun aPictureThatDoesNotFlashReachesTheLayerAsItIsStored() = runBlocking {
        val sink = RecordingSampleSink()
        val renderer = SampleBufferVideoRenderer(resolve = { greyNv12(200) }, sink = sink, flashNanos = { sink.samples.size * 33_000_000L })
        try {
            renderer.setFlashGuard(io.github.yuroyami.kiteplayer.FlashGuard.On)
            repeat(30) { assertTrue(renderer.present(SampleTestFrame(64, 64, fullRange709), targetNanos = 0L)) }
            assertTrue(sink.samples.all { CVPixelBufferGetPixelFormatType(imageOf(it)) != kCVPixelFormatType_32BGRA })
        } finally {
            renderer.close()
            sink.release()
        }
    }

    @Test
    fun turningTheGuardOffDrawsADimmedPictureAgainWhole() {
        val shown = strobed(io.github.yuroyami.kiteplayer.FlashGuard.On, pictures = 30) { renderer, _ ->
            renderer.setFlashGuard(io.github.yuroyami.kiteplayer.FlashGuard.Off)
        }
        assertEquals(31, shown.size, "the picture on screen is shown once more")
        assertTrue(shown[29].first, "the last picture of the strobe was dimmed")
        assertTrue(!shown[30].first, "and it is shown again as it is stored")
    }
}
