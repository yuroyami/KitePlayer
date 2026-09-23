package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteffmpeg.ChromaLocation
import io.github.yuroyami.kiteffmpeg.ColorInfo
import io.github.yuroyami.kiteffmpeg.ColorPrimaries
import io.github.yuroyami.kiteffmpeg.ColorTransfer
import io.github.yuroyami.kiteffmpeg.PixelFormat
import io.github.yuroyami.kiteplayer.spi.ColorMatrix
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import io.github.yuroyami.kiteffmpeg.ColorMatrix as KiteColorMatrix

/**
 * Video coded as RGB arrives from the H.264, HEVC and VP9 decoders as planar GBR. The engine models
 * it as 4:4:4 under the Identity matrix, which is exactly what that matrix means: the three planes
 * hold G, B and R.
 */
class RgbCodedVideoMappingTest {

    private val gbrp = PixelFormat("gbrp")

    private fun color(matrix: KiteColorMatrix, fullRange: Boolean, rangeSpecified: Boolean) = ColorInfo(
        matrix = matrix,
        primaries = ColorPrimaries.Unspecified,
        transfer = ColorTransfer.Unspecified,
        fullRange = fullRange,
        chromaLocation = ChromaLocation.Unspecified,
        rangeSpecified = rangeSpecified,
        matrixSpecified = matrix != KiteColorMatrix.Unspecified,
        primariesSpecified = false,
        transferSpecified = false,
    )

    @Test
    fun `planar GBR is a 4 4 4 frame under the Identity matrix whatever the tag says`() {
        assertEquals(PlayerPixelFormat.Yuv444p, gbrp.toPlayerFormat())

        val mapped = color(KiteColorMatrix.Unspecified, fullRange = false, rangeSpecified = false)
            .toPlayerColorSpace(gbrp)

        assertEquals(ColorMatrix.Identity, mapped.matrix)
        assertTrue(mapped.matrixSpecified, "the format itself says the planes are G, B and R")
    }

    @Test
    fun `planar GBR with no declared range is full range`() {
        // RGB video is full range. Read as studio range, black and white would lose 16 levels each.
        val mapped = color(KiteColorMatrix.Rgb, fullRange = false, rangeSpecified = false)
            .toPlayerColorSpace(gbrp)

        assertTrue(mapped.fullRange)
        assertFalse(mapped.rangeSpecified, "the range is still a fallback, not the file's own word")
    }

    @Test
    fun `planar GBR with a declared studio range keeps it`() {
        val mapped = color(KiteColorMatrix.Rgb, fullRange = false, rangeSpecified = true)
            .toPlayerColorSpace(gbrp)

        assertFalse(mapped.fullRange)
        assertTrue(mapped.rangeSpecified)
    }

    @Test
    fun `every other format keeps the colour it declares`() {
        val mapped = color(KiteColorMatrix.Unspecified, fullRange = false, rangeSpecified = false)
            .toPlayerColorSpace(PixelFormat("yuv444p"))

        assertEquals(ColorMatrix.Unspecified, mapped.matrix)
        assertFalse(mapped.fullRange)
    }
}
