package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.spi.ColorSpaceInfo
import io.github.yuroyami.kiteplayer.spi.HwSurfaceKind
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.spi.SoftwareReadableFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A capture refuses geometry it cannot copy, and says which plane was wrong.
 *
 * These are checks on a BACKEND, not on an application: strides and heights come from a decoder.
 * Before them a zero or negative stride reached `ByteArray()` as a size and died there with
 * `NegativeArraySizeException`, which names neither the plane nor the value, and a plane shorter
 * than its own declared geometry would be read past its end by any consumer that trusted
 * `planeStride` and `planeHeight`, which is the entire reason those exist.
 */
class CapturedFrameGeometryTest {

    private class FakeReadable(
        private val strides: IntArray,
        private val heights: IntArray,
        override val planeCount: Int = strides.size,
    ) : SoftwareReadableFrame {
        override val pts: Pts = Pts.Zero
        override val duration: Pts? = null
        override val size: VideoSize = VideoSize(16, 16)
        override val pixelFormat: PlayerPixelFormat = PlayerPixelFormat.Yuv420p
        override val colorSpace: ColorSpaceInfo = ColorSpaceInfo()
        override val rotationDegrees: Int = 0
        override val hardwareSurface: HwSurfaceKind? = null
        override val generation: Generation = Generation(0)
        override fun planeStride(index: Int): Int = strides[index]
        override fun planeHeight(index: Int): Int = heights[index]
        override fun copyPlane(index: Int, into: ByteArray, offset: Int) = Unit
        override fun close() = Unit
    }

    @Test
    fun `a plane with no size is refused and named`() {
        val refusal = assertFailsWith<IllegalArgumentException> {
            CapturedFrame.of(FakeReadable(strides = intArrayOf(16, 0), heights = intArrayOf(16, 8)))
        }
        assertTrue("plane 1" in refusal.message.orEmpty(), "say WHICH plane: ${refusal.message}")
    }

    @Test
    fun `a negative stride is refused rather than reaching the allocator`() {
        val refusal = assertFailsWith<IllegalArgumentException> {
            CapturedFrame.of(FakeReadable(strides = intArrayOf(-16), heights = intArrayOf(16)))
        }
        assertTrue("-16" in refusal.message.orEmpty(), "say the value: ${refusal.message}")
    }

    @Test
    fun `geometry that overflows an array is refused by its real size`() {
        // 100000 x 100000 is ten billion bytes. Multiplied as Int it wraps to something small and
        // positive, so the allocation would SUCCEED at the wrong size and every read past it would
        // be silently short.
        val refusal = assertFailsWith<IllegalArgumentException> {
            CapturedFrame.of(FakeReadable(strides = intArrayOf(100_000), heights = intArrayOf(100_000)))
        }
        assertTrue(
            "10000000000" in refusal.message.orEmpty(),
            "the refusal must name the true 64-bit size: ${refusal.message}",
        )
    }

    @Test
    fun `an ordinary frame still captures`() {
        val captured = CapturedFrame.of(FakeReadable(strides = intArrayOf(16, 8), heights = intArrayOf(16, 8)))
        assertEquals(2, captured.planeCount)
        assertEquals(16, captured.planeStride(0))
        assertEquals(8, captured.planeHeight(1))
    }

    @Test
    fun `copying into a buffer that is too small says by how much`() {
        val captured = CapturedFrame.of(FakeReadable(strides = intArrayOf(16), heights = intArrayOf(16)))
        val refusal = assertFailsWith<IllegalArgumentException> {
            captured.copyPlane(0, ByteArray(10), 0)
        }
        assertTrue("256" in refusal.message.orEmpty(), "name what was needed: ${refusal.message}")
    }
}
