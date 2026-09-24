@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.get
import kotlinx.cinterop.plus
import kotlinx.cinterop.reinterpret
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import platform.AVFoundation.AVQueuedSampleBufferRenderingStatusFailed
import platform.AVFoundation.AVSampleBufferDisplayLayer
import platform.AVFoundation.error
import platform.AVFoundation.sampleBufferRenderer
import platform.AVFoundation.status
import platform.CoreVideo.CVPixelBufferGetBaseAddressOfPlane
import platform.CoreVideo.CVPixelBufferGetBytesPerRowOfPlane
import platform.CoreVideo.CVPixelBufferGetHeight
import platform.CoreVideo.CVPixelBufferGetPixelFormatType
import platform.CoreVideo.CVPixelBufferGetPlaneCount
import platform.CoreVideo.CVPixelBufferGetWidth
import platform.CoreVideo.CVPixelBufferLockBaseAddress
import platform.CoreVideo.CVPixelBufferRelease
import platform.CoreVideo.CVPixelBufferUnlockBaseAddress
import platform.CoreVideo.kCVPixelFormatType_32BGRA
import platform.CoreVideo.kCVPixelFormatType_420YpCbCr8BiPlanarFullRange
import platform.CoreVideo.kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange

/**
 * The parts of the picture in picture path that can be proved without a device.
 *
 * Core Video's 8-bit 4:2:0 is bi-planar and the decoder's is three separate planes, so every
 * software frame is interleaved on the way in. Getting that loop wrong does not crash: it swaps
 * the colours or shears them, which is exactly the sort of thing that only shows up on a phone in
 * somebody's hand. So the interleaving is read back and compared byte for byte here.
 *
 * The rest is what reaches the layer: every sample marked to show at once, and the picture as
 * decoded while there is no text. Burned-in text needs a Metal device, which the iOS simulator does
 * not give a test process, so `SampleBufferSubtitleTest` proves it on the macOS host.
 */
class SampleBufferVideoRendererTest {

    private fun plane(bytesPerRow: Int, rows: Int, fill: (Int, Int) -> Byte) =
        MetalPicture.SoftwarePlanes.Plane(
            bytes = ByteArray(bytesPerRow * rows) { at -> fill(at % bytesPerRow, at / bytesPerRow) },
            bytesPerRow = bytesPerRow,
            rows = rows,
        )

    private fun planarPicture(width: Int = 4, height: Int = 4) = MetalPicture.SoftwarePlanes(
        width = width,
        height = height,
        format = PlayerPixelFormat.Yuv420p,
        planes = listOf(
            plane(width, height) { x, y -> (10 + x + y * width).toByte() },
            plane(width / 2, height / 2) { x, y -> (100 + x + y * 10).toByte() },
            plane(width / 2, height / 2) { x, y -> (200 + x + y * 10).toByte() },
        ),
    )

    @Test
    fun `a planar frame becomes a two plane buffer`() {
        val buffer = assertNotNull(copyIntoPixelBuffer(planarPicture(), fullRange = true))
        try {
            assertEquals(2, CVPixelBufferGetPlaneCount(buffer).toInt())
            assertEquals(4uL, CVPixelBufferGetWidth(buffer))
            assertEquals(4uL, CVPixelBufferGetHeight(buffer))
        } finally {
            CVPixelBufferRelease(buffer)
        }
    }

    @Test
    fun `the chroma planes are interleaved in the order Core Video expects`() {
        val picture = planarPicture()
        val buffer = assertNotNull(copyIntoPixelBuffer(picture, fullRange = true))
        try {
            CVPixelBufferLockBaseAddress(buffer, 0uL)
            val base = assertNotNull(CVPixelBufferGetBaseAddressOfPlane(buffer, 1uL)).reinterpret<ByteVar>()
            val stride = CVPixelBufferGetBytesPerRowOfPlane(buffer, 1uL).toInt()
            val u = picture.planes[1]
            val v = picture.planes[2]
            for (row in 0 until u.rows) {
                val chromaRow = base + row.toLong() * stride
                for (sample in 0 until u.bytesPerRow) {
                    assertEquals(
                        u.bytes[row * u.bytesPerRow + sample],
                        chromaRow!![sample * 2],
                        "blue difference at row $row sample $sample",
                    )
                    assertEquals(
                        v.bytes[row * v.bytesPerRow + sample],
                        chromaRow[sample * 2 + 1],
                        "red difference at row $row sample $sample",
                    )
                }
            }
            CVPixelBufferUnlockBaseAddress(buffer, 0uL)
        } finally {
            CVPixelBufferRelease(buffer)
        }
    }

    @Test
    fun `the luma plane survives a stride the destination does not share`() {
        val picture = planarPicture()
        val buffer = assertNotNull(copyIntoPixelBuffer(picture, fullRange = true))
        try {
            CVPixelBufferLockBaseAddress(buffer, 0uL)
            val base = assertNotNull(CVPixelBufferGetBaseAddressOfPlane(buffer, 0uL)).reinterpret<ByteVar>()
            val stride = CVPixelBufferGetBytesPerRowOfPlane(buffer, 0uL).toInt()
            val luma = picture.planes[0]
            for (row in 0 until luma.rows) {
                val lumaRow = base + row.toLong() * stride
                for (x in 0 until luma.bytesPerRow) {
                    assertEquals(luma.bytes[row * luma.bytesPerRow + x], lumaRow!![x], "luma at $row $x")
                }
            }
            CVPixelBufferUnlockBaseAddress(buffer, 0uL)
        } finally {
            CVPixelBufferRelease(buffer)
        }
    }

    @Test
    fun `the range of the source picks the matching bi planar type`() {
        val full = assertNotNull(copyIntoPixelBuffer(planarPicture(), fullRange = true))
        val video = assertNotNull(copyIntoPixelBuffer(planarPicture(), fullRange = false))
        try {
            assertEquals(kCVPixelFormatType_420YpCbCr8BiPlanarFullRange, CVPixelBufferGetPixelFormatType(full))
            assertEquals(kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange, CVPixelBufferGetPixelFormatType(video))
        } finally {
            CVPixelBufferRelease(full)
            CVPixelBufferRelease(video)
        }
    }

    @Test
    fun `a packed frame becomes a single plane buffer`() {
        val picture = MetalPicture.SoftwarePlanes(
            width = 4,
            height = 4,
            format = PlayerPixelFormat.Bgra,
            planes = listOf(plane(16, 4) { x, y -> (x + y).toByte() }),
        )
        val buffer = assertNotNull(copyIntoPixelBuffer(picture, fullRange = true))
        try {
            assertEquals(kCVPixelFormatType_32BGRA, CVPixelBufferGetPixelFormatType(buffer))
            assertEquals(0, CVPixelBufferGetPlaneCount(buffer).toInt())
        } finally {
            CVPixelBufferRelease(buffer)
        }
    }

    @Test
    fun `a format this renderer cannot show is refused rather than guessed`() {
        val picture = MetalPicture.SoftwarePlanes(
            width = 4,
            height = 4,
            format = PlayerPixelFormat.Yuv444p,
            planes = listOf(plane(4, 4) { _, _ -> 0 }),
        )
        assertNull(copyIntoPixelBuffer(picture, fullRange = true))
    }

    @Test
    fun `the renderer draws the three formats it can turn into a pixel buffer`() {
        val renderer = SampleBufferVideoRenderer(AVSampleBufferDisplayLayer()) { null }
        assertTrue(renderer.supports(PlayerPixelFormat.Nv12))
        assertTrue(renderer.supports(PlayerPixelFormat.Yuv420p))
        assertTrue(renderer.supports(PlayerPixelFormat.Bgra))
        assertFalse(renderer.supports(PlayerPixelFormat.Yuv444p))
        assertFalse(renderer.supports(PlayerPixelFormat.Opaque))
        renderer.close()
    }

    @Test
    fun `a layer given one real sample does not go to failed`() = runBlocking {
        val layer = AVSampleBufferDisplayLayer()
        val picture = planarPicture()
        val renderer = SampleBufferVideoRenderer(
            resolve = { picture },
            sink = LayerSink(layer, onMain = { block -> block() }),
        )
        assertTrue(renderer.present(SampleTestFrame(), targetNanos = 0L))
        assertEquals(1L, renderer.presentedFrames)
        assertEquals(0L, renderer.failedFrames)
        // Failed is the layer's own verdict on what it was handed, and the only one it reports.
        assertTrue(layer.status != AVQueuedSampleBufferRenderingStatusFailed, "layer error: ${layer.error}")
        renderer.close()
    }

    @Test
    fun theLayersVideoRendererGivenOneRealSampleDoesNotGoToFailed() = runBlocking {
        val layer = AVSampleBufferDisplayLayer()
        val picture = planarPicture()
        val renderer = SampleBufferVideoRenderer(
            resolve = { picture },
            sink = VideoRendererSink(layer.sampleBufferRenderer),
        )
        assertTrue(renderer.present(SampleTestFrame(), targetNanos = 0L))
        val video = layer.sampleBufferRenderer
        assertTrue(video.status != AVQueuedSampleBufferRenderingStatusFailed, "renderer error: ${video.error}")
        renderer.close()
    }

    @Test
    fun theLayersVideoRendererTakesTheSamplesWhereTheSystemHasOne() {
        // Every host this runs on is macOS 14 or iOS 17 and later, where the layer has one.
        assertIs<VideoRendererSink>(sampleSinkFor(AVSampleBufferDisplayLayer()))
    }

    @Test
    fun `a frame the resolver refuses is counted and closed`() = runBlocking {
        val frame = SampleTestFrame()
        val renderer = SampleBufferVideoRenderer(
            resolve = { null },
            sink = LayerSink(AVSampleBufferDisplayLayer(), onMain = { block -> block() }),
        )
        assertFalse(renderer.present(frame, targetNanos = 0L))
        assertEquals(1L, renderer.failedFrames)
        assertTrue(frame.closed, "the renderer owns the frame and closes it even when it refuses")
        renderer.close()
    }

    @Test
    fun everySampleIsMarkedToDisplayImmediately() = runBlocking {
        val sink = RecordingSampleSink()
        val picture = planarPicture()
        val renderer = SampleBufferVideoRenderer(resolve = { picture }, sink = sink)
        try {
            repeat(3) { at -> assertTrue(renderer.present(SampleTestFrame(), targetNanos = at * 40_000_000L)) }
            assertEquals(3, sink.samples.size)
            sink.samples.forEachIndexed { at, sample ->
                assertTrue(displaysImmediately(sample), "sample $at is not marked to display at once")
            }
        } finally {
            renderer.close()
            sink.release()
        }
    }

    @Test
    fun withoutTextThePictureReachesTheLayerAsDecoded() = runBlocking {
        val sink = RecordingSampleSink()
        val renderer = SampleBufferVideoRenderer(resolve = { redNv12(64, 64) }, sink = sink)
        try {
            renderer.setOverlay(noText())
            assertTrue(renderer.present(SampleTestFrame(64, 64, limited709), targetNanos = 0L))
            val image = imageOf(sink.samples.single())
            assertEquals(kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange, CVPixelBufferGetPixelFormatType(image))
        } finally {
            renderer.close()
            sink.release()
        }
    }    @Test
    fun anOverlayWithoutTextOverAPlainPictureRedrawsNothing() = runBlocking {
        val sink = RecordingSampleSink()
        val renderer = SampleBufferVideoRenderer(resolve = { redNv12(64, 64) }, sink = sink)
        try {
            assertTrue(renderer.present(SampleTestFrame(64, 64, limited709), targetNanos = 0L))
            renderer.setOverlay(noText())
            renderer.setOverlay(null)
            assertEquals(1, sink.samples.size)
        } finally {
            renderer.close()
            sink.release()
        }
    }

    @Test
    fun withoutAMetalDeviceTheTextIsLeftOutAndThePictureStillShows() = runBlocking {
        val sink = RecordingSampleSink()
        val renderer = SampleBufferVideoRenderer(
            resolve = { redNv12(64, 64) },
            sink = sink,
            makeBurner = { null },
        )
        try {
            renderer.setOverlay(whiteSquare())
            assertTrue(renderer.present(SampleTestFrame(64, 64, limited709), targetNanos = 0L))
            assertEquals(
                kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
                CVPixelBufferGetPixelFormatType(imageOf(sink.samples.single())),
            )
        } finally {
            renderer.close()
            sink.release()
        }
    }

    @Test
    fun closeTakesThePictureOffTheLayerAndRefusesLaterFrames() = runBlocking {
        val sink = RecordingSampleSink()
        val picture = planarPicture()
        val renderer = SampleBufferVideoRenderer(resolve = { picture }, sink = sink)
        try {
            assertTrue(renderer.present(SampleTestFrame(), targetNanos = 0L))
            renderer.close()
            assertEquals(1, sink.flushes)
            val late = SampleTestFrame()
            assertFalse(renderer.present(late, targetNanos = 0L))
            assertTrue(late.closed)
            assertEquals(1, sink.samples.size, "nothing reaches the layer after close")
        } finally {
            sink.release()
        }
    }

}
