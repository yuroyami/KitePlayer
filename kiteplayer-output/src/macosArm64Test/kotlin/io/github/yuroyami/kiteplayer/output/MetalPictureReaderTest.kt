@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.VideoSize
import io.github.yuroyami.kiteplayer.spi.ColorMatrix
import io.github.yuroyami.kiteplayer.spi.ColorPrimaries
import io.github.yuroyami.kiteplayer.spi.ColorSpaceInfo
import io.github.yuroyami.kiteplayer.spi.ColorTransfer
import io.github.yuroyami.kiteplayer.spi.HwSurfaceKind
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryCreate
import platform.CoreFoundation.CFGetRetainCount
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.CoreVideo.CVPixelBufferCreate
import platform.CoreVideo.CVPixelBufferGetBaseAddressOfPlane
import platform.CoreVideo.CVPixelBufferGetBytesPerRowOfPlane
import platform.CoreVideo.CVPixelBufferLockBaseAddress
import platform.CoreVideo.CVPixelBufferRef
import platform.CoreVideo.CVPixelBufferRefVar
import platform.CoreVideo.CVPixelBufferUnlockBaseAddress
import platform.CoreVideo.kCVPixelBufferIOSurfacePropertiesKey
import platform.CoreVideo.kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange
import platform.CoreVideo.kCVReturnSuccess
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The reader's ownership of its native storage (#476), on the real CoreVideo texture cache. */
class MetalPictureReaderTest {

    private class TestFrame : VideoFrame {
        var closed = false
        override val pts = Pts.Zero
        override val duration: Pts? = null
        override val size = VideoSize(SIDE, SIDE, 1, 1)
        override val colorSpace = ColorSpaceInfo(
            matrix = ColorMatrix.Bt709,
            primaries = ColorPrimaries.Bt709,
            transfer = ColorTransfer.Bt709,
            fullRange = false,
        )
        override val pixelFormat = PlayerPixelFormat.Nv12
        override val hardwareSurface: HwSurfaceKind? = HwSurfaceKind.CoreVideoPixelBuffer
        override val generation = Generation.Initial
        override fun close() { closed = true }
    }

    /** A red biplanar buffer on an IOSurface, which is what VideoToolbox hands over. Create rule. */
    private fun redPixelBuffer(): CVPixelBufferRef = memScoped {
        val surface = CFDictionaryCreate(
            null, null, null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr,
        )
        val keys = allocArrayOf(kCVPixelBufferIOSurfacePropertiesKey)
        val values = allocArrayOf(surface)
        val attributes = CFDictionaryCreate(
            null, keys.reinterpret(), values.reinterpret(), 1,
            kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr,
        )
        val out = alloc<CVPixelBufferRefVar>()
        val rc = CVPixelBufferCreate(
            allocator = null,
            width = SIDE.toULong(),
            height = SIDE.toULong(),
            pixelFormatType = kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
            pixelBufferAttributes = attributes,
            pixelBufferOut = out.ptr,
        )
        CFRelease(attributes)
        CFRelease(surface)
        check(rc == kCVReturnSuccess) { "CVPixelBufferCreate failed: $rc" }
        val buffer = checkNotNull(out.value)
        CVPixelBufferLockBaseAddress(buffer, 0uL)
        try {
            val luma = CVPixelBufferGetBaseAddressOfPlane(buffer, 0u)!!.reinterpret<ByteVar>()
            val lumaStride = CVPixelBufferGetBytesPerRowOfPlane(buffer, 0u).toInt()
            for (row in 0 until SIDE) for (column in 0 until SIDE) luma[row * lumaStride + column] = 63
            val chroma = CVPixelBufferGetBaseAddressOfPlane(buffer, 1u)!!.reinterpret<ByteVar>()
            val chromaStride = CVPixelBufferGetBytesPerRowOfPlane(buffer, 1u).toInt()
            for (row in 0 until SIDE / 2) {
                for (column in 0 until SIDE / 2) {
                    chroma[row * chromaStride + column * 2] = 102.toByte()
                    chroma[row * chromaStride + column * 2 + 1] = 240.toByte()
                }
            }
        } finally {
            CVPixelBufferUnlockBaseAddress(buffer, 0uL)
        }
        buffer
    }

    private fun assertRed(rgba: ByteArray) {
        assertEquals(SIDE * SIDE * 4, rgba.size)
        val at = (SIDE / 2 * SIDE + SIDE / 2) * 4
        val r = rgba[at].toInt() and 0xFF
        val g = rgba[at + 1].toInt() and 0xFF
        val b = rgba[at + 2].toInt() and 0xFF
        assertTrue(abs(r - 255) <= 3 && g <= 3 && b <= 3, "the picture reads rgb($r,$g,$b), expected red")
    }

    @Test
    fun closeReleasesTheTextureCacheAndEveryWrapper() {
        val buffer = redPixelBuffer()
        try {
            val reader = MetalPictureReader()
            assertEquals(0, reader.heldTextureCaches, "no cache before the first hardware frame")
            val rgba = reader.readRgba(TestFrame(), MetalPicture.CorePixelBuffer(buffer as COpaquePointer))
            assertEquals(1, reader.heldTextureCaches)
            assertEquals(0, reader.heldTextureWrappers, "a finished read holds no wrapper")
            reader.close()
            assertEquals(0, reader.heldTextureCaches)
            assertEquals(0, reader.heldTextureWrappers)
            assertRed(rgba)
        } finally {
            CFRelease(buffer)
        }
    }

    @Test
    fun aSecondCloseDoesNothingAndAClosedReaderRefusesToRead() {
        val buffer = redPixelBuffer()
        try {
            val reader = MetalPictureReader()
            reader.readRgba(TestFrame(), MetalPicture.CorePixelBuffer(buffer as COpaquePointer))
            reader.close()
            reader.close()
            assertFailsWith<IllegalStateException> {
                reader.readRgba(TestFrame(), MetalPicture.CorePixelBuffer(buffer as COpaquePointer))
            }
            assertEquals(0, reader.heldTextureCaches, "a refused read creates nothing")
        } finally {
            CFRelease(buffer)
        }
    }

    @Test
    fun aReaderClosedBeforeItsFirstReadHoldsNothing() {
        val reader = MetalPictureReader()
        reader.close()
        assertEquals(0, reader.heldTextureCaches)
    }

    @Test
    fun theReaderBorrowsItsInputs() {
        val buffer = redPixelBuffer()
        try {
            val before = CFGetRetainCount(buffer)
            val frame = TestFrame()
            val reader = MetalPictureReader()
            reader.readRgba(frame, MetalPicture.CorePixelBuffer(buffer as COpaquePointer))
            reader.close()
            assertEquals(false, frame.closed, "the frame is the caller's to close")
            assertEquals(before, CFGetRetainCount(buffer), "the reader keeps no reference to the buffer")
        } finally {
            CFRelease(buffer)
        }
    }

    @Test
    fun readersThatOverlapOwnSeparateCaches() {
        val buffer = redPixelBuffer()
        try {
            val picture = MetalPicture.CorePixelBuffer(buffer as COpaquePointer)
            val first = MetalPictureReader()
            val second = MetalPictureReader()
            first.readRgba(TestFrame(), picture)
            second.readRgba(TestFrame(), picture)
            first.close()
            assertEquals(0, first.heldTextureCaches)
            assertEquals(1, second.heldTextureCaches, "closing one reader leaves the other's cache")
            assertRed(second.readRgba(TestFrame(), picture))
            second.close()
            repeat(20) {
                val reader = MetalPictureReader()
                reader.readRgba(TestFrame(), picture)
                reader.close()
                assertEquals(0, reader.heldTextureCaches + reader.heldTextureWrappers)
            }
        } finally {
            CFRelease(buffer)
        }
    }

    private companion object {
        const val SIDE = 64
    }
}
