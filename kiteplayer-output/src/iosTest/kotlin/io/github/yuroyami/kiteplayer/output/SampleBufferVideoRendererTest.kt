@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.get
import kotlinx.cinterop.plus
import kotlinx.cinterop.reinterpret
import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.VideoSize
import io.github.yuroyami.kiteplayer.spi.ColorSpaceInfo
import io.github.yuroyami.kiteplayer.spi.HwSurfaceKind
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import platform.AVFoundation.AVQueuedSampleBufferRenderingStatusFailed
import platform.AVFoundation.AVSampleBufferDisplayLayer
import platform.AVFoundation.error
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
 * The one part of the picture-in-picture path that can be proved without a device.
 *
 * Core Video's 8-bit 4:2:0 is bi-planar and the decoder's is three separate planes, so every
 * software frame is interleaved on the way in. Getting that loop wrong does not crash: it swaps
 * the colours or shears them, which is exactly the sort of thing that only shows up on a phone in
 * somebody's hand. So the interleaving is read back and compared byte for byte here.
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
            layer = layer,
            resolve = { picture },
            enqueueOnMain = { block -> block() },
        )
        assertTrue(renderer.present(FakeFrame(), targetNanos = 0L))
        assertEquals(1L, renderer.presentedFrames)
        assertEquals(0L, renderer.failedFrames)
        // Failed is the layer's own verdict on what it was handed, and the only one it reports.
        assertTrue(layer.status != AVQueuedSampleBufferRenderingStatusFailed, "layer error: ${layer.error}")
        renderer.close()
    }

    @Test
    fun `a frame the resolver refuses is counted and closed`() = runBlocking {
        val frame = FakeFrame()
        val renderer = SampleBufferVideoRenderer(
            layer = AVSampleBufferDisplayLayer(),
            resolve = { null },
            enqueueOnMain = { block -> block() },
        )
        assertFalse(renderer.present(frame, targetNanos = 0L))
        assertEquals(1L, renderer.failedFrames)
        assertTrue(frame.closed, "the renderer owns the frame and closes it even when it refuses")
        renderer.close()
    }
}

/** The smallest frame the renderer's contract accepts. Its pixels come from the resolver. */
private class FakeFrame : VideoFrame {
    var closed: Boolean = false
        private set

    override val pts: Pts = Pts(0)
    override val duration: Pts? = null
    override val size: VideoSize = VideoSize(4, 4)
    override val pixelFormat: PlayerPixelFormat = PlayerPixelFormat.Yuv420p
    override val colorSpace: ColorSpaceInfo = ColorSpaceInfo(fullRange = true)
    override val hardwareSurface: HwSurfaceKind? = null
    override val generation: Generation = Generation.Initial

    override fun close() {
        closed = true
    }
}
