@file:OptIn(ExperimentalForeignApi::class)

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
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Metal.MTLCreateSystemDefaultDevice
import platform.Metal.MTLRegionMake2D
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Metal picture turns and mirrors the way the display matrix says, with real Metal on the
 * host: a quarter turn is clockwise (#379), and a mirrored stream is mirrored left to right before
 * it turns (#233). Each quadrant of the stored picture has its own grey, so where a grey lands says
 * which way the picture went.
 */
class MetalTurnTest {

    private class TurnFrame(
        override val rotationDegrees: Int,
        override val mirrored: Boolean = false,
    ) : VideoFrame {
        override val pts: Pts = Pts.Zero
        override val duration: Pts? = null
        override val size: VideoSize = VideoSize(SIZE, SIZE, 1, 1)
        override val pixelFormat: PlayerPixelFormat = PlayerPixelFormat.Yuv444p
        override val colorSpace: ColorSpaceInfo = ColorSpaceInfo(
            matrix = ColorMatrix.Bt709,
            primaries = ColorPrimaries.Bt709,
            transfer = ColorTransfer.Bt709,
            fullRange = true,
        )
        override val hardwareSurface: HwSurfaceKind? = null
        override val generation: Generation = Generation.Initial
        override fun close() = Unit
    }

    private val device = MTLCreateSystemDefaultDevice() ?: error("this host has no Metal device")
    private val composer = MetalFrameComposer(device)

    /** Four quadrants, each its own grey: top left, top right, bottom left, bottom right. */
    private fun quadrants(): MetalPicture.SoftwarePlanes {
        val luma = ByteArray(SIZE * SIZE) { at ->
            val right = at % SIZE >= SIZE / 2
            val bottom = at / SIZE >= SIZE / 2
            GREYS[(if (bottom) 2 else 0) + (if (right) 1 else 0)].toByte()
        }
        fun neutral() = MetalPicture.SoftwarePlanes.Plane(ByteArray(SIZE * SIZE) { 128.toByte() }, SIZE, SIZE)
        return MetalPicture.SoftwarePlanes(
            width = SIZE,
            height = SIZE,
            format = PlayerPixelFormat.Yuv444p,
            planes = listOf(MetalPicture.SoftwarePlanes.Plane(luma, SIZE, SIZE), neutral(), neutral()),
        )
    }

    /** The grey at the centre of each quadrant of the drawn picture, in the order of [GREYS]. */
    private fun drawn(frame: VideoFrame): List<Int> {
        val target = device.makeTargetTexture(SIZE, SIZE)
        composer.encode(target, frame, quadrants(), null, SIZE, SIZE).waitUntilCompleted()
        val bytes = ByteArray(SIZE * SIZE * 4)
        bytes.usePinned { pinned ->
            target.getBytes(
                pinned.addressOf(0),
                bytesPerRow = (SIZE * 4).toULong(),
                fromRegion = MTLRegionMake2D(0u, 0u, SIZE.toULong(), SIZE.toULong()),
                mipmapLevel = 0u,
            )
        }
        val near = SIZE / 4
        val far = SIZE - SIZE / 4
        return listOf(near to near, far to near, near to far, far to far).map { (x, y) ->
            bytes[(y * SIZE + x) * 4 + 1].toInt() and 0xFF
        }
    }

    /**
     * Where the stored quadrant ([x], [y]) lands, each 0 or 1 with y from the top: mirrored left to
     * right first when [mirrored], then turned clockwise by [rotation].
     */
    private fun landing(x: Int, y: Int, rotation: Int, mirrored: Boolean = false): Int {
        val mx = if (mirrored) 1 - x else x
        val (tx, ty) = when (rotation) {
            90 -> (1 - y) to mx
            180 -> (1 - mx) to (1 - y)
            270 -> y to (1 - mx)
            else -> mx to y
        }
        return ty * 2 + tx
    }

    @Test
    fun aQuarterTurnIsClockwise() {
        val upright = drawn(TurnFrame(0))
        assertEquals(4, upright.toSet().size, "the four quadrants must draw four different greys: $upright")
        for (rotation in listOf(90, 180, 270)) {
            val expected = MutableList(4) { 0 }
            for (quadrant in 0 until 4) expected[landing(quadrant % 2, quadrant / 2, rotation)] = upright[quadrant]
            assertEquals(expected, drawn(TurnFrame(rotation)), "turned $rotation degrees")
        }
    }

    @Test
    fun aMirrorComesBeforeTheTurn() {
        val upright = drawn(TurnFrame(0))
        for (rotation in listOf(0, 90, 180, 270)) {
            val expected = MutableList(4) { 0 }
            for (quadrant in 0 until 4) {
                expected[landing(quadrant % 2, quadrant / 2, rotation, mirrored = true)] = upright[quadrant]
            }
            assertEquals(expected, drawn(TurnFrame(rotation, mirrored = true)), "mirrored and turned $rotation degrees")
        }
    }

    private companion object {
        const val SIZE = 16
        val GREYS = intArrayOf(40, 100, 160, 220)
    }
}
