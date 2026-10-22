@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.compose

import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.VideoSize
import io.github.yuroyami.kiteplayer.output.MetalPicture
import io.github.yuroyami.kiteplayer.output.MetalPictureReader
import io.github.yuroyami.kiteplayer.spi.ColorSpaceInfo
import io.github.yuroyami.kiteplayer.spi.HwSurfaceKind
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryCreate
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.CoreVideo.CVPixelBufferCreate
import platform.CoreVideo.CVPixelBufferRef
import platform.CoreVideo.CVPixelBufferRefVar
import platform.CoreVideo.kCVPixelBufferIOSurfacePropertiesKey
import platform.CoreVideo.kCVPixelFormatType_32BGRA
import platform.CoreVideo.kCVReturnSuccess
import platform.Metal.MTLCreateSystemDefaultDevice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** One renderer's Metal reader is made when a hardware frame needs it and closed with the renderer (#476). */
class HardwareFrameConverterSimTest {

    private class TestFrame : VideoFrame {
        override val pts = Pts.Zero
        override val duration: Pts? = null
        override val size = VideoSize(SIDE, SIDE, 1, 1)
        override val colorSpace = ColorSpaceInfo.Unspecified
        override val pixelFormat = PlayerPixelFormat.Bgra
        override val hardwareSurface: HwSurfaceKind? = HwSurfaceKind.CoreVideoPixelBuffer
        override val generation = Generation.Initial
        override fun close() = Unit
    }

    /** A packed BGRA buffer on an IOSurface, which is what a hardware frame is. Create rule. */
    private fun pixelBuffer(): CVPixelBufferRef = memScoped {
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
        val rc = CVPixelBufferCreate(null, SIDE.toULong(), SIDE.toULong(), kCVPixelFormatType_32BGRA, attributes, out.ptr)
        CFRelease(attributes)
        CFRelease(surface)
        check(rc == kCVReturnSuccess) { "CVPixelBufferCreate failed: $rc" }
        checkNotNull(out.value)
    }

    @Test
    fun theReaderIsMadeOnceForTheFirstHardwareFrameAndGoneAfterClose() {
        if (MTLCreateSystemDefaultDevice() == null) return // No Metal on this simulator host.
        val buffer = pixelBuffer()
        try {
            val picture = MetalPicture.CorePixelBuffer(buffer as COpaquePointer)
            var made = 0
            val converter = HardwareFrameConverter {
                made += 1
                MetalPictureReader()
            }
            assertEquals(0, made, "no reader before a hardware frame needs one")
            assertEquals(SIDE * SIDE * 4, assertNotNull(converter.readOrNull(TestFrame(), picture)).size)
            assertNotNull(converter.readOrNull(TestFrame(), picture))
            assertEquals(1, made)
            converter.close()
            converter.close()
            assertNull(converter.readOrNull(TestFrame(), picture), "a closed converter reads nothing")
            assertEquals(1, made, "and makes no new reader")
        } finally {
            CFRelease(buffer)
        }
    }

    @Test
    fun aReaderThatCannotBeMadeIsNotTriedAgain() {
        val buffer = pixelBuffer()
        try {
            val picture = MetalPicture.CorePixelBuffer(buffer as COpaquePointer)
            var tried = 0
            val converter = HardwareFrameConverter {
                tried += 1
                error("this machine has no Metal device")
            }
            assertNull(converter.readOrNull(TestFrame(), picture))
            assertNull(converter.readOrNull(TestFrame(), picture))
            assertEquals(1, tried)
            converter.close()
        } finally {
            CFRelease(buffer)
        }
    }

    private companion object {
        const val SIDE = 16
    }
}
