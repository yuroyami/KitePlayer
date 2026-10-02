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
}
