package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.spi.HwSurfaceKind
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.spi.ColorSpaceInfo
import io.github.yuroyami.kiteplayer.spi.SoftwareReadableFrame
import io.github.yuroyami.kiteplayer.spi.VideoFrame

/**
 * A screenshot (S4.e): the newest presented frame, plane-copied at the moment of presentation,
 * owned outright by the caller. This is the documented use of [SoftwareReadableFrame]: every
 * plane here is a copy taken before the renderer took ownership, so nothing the decoder or the
 * renderer does afterwards can touch it, and [close] has nothing to release.
 */
public class CapturedFrame internal constructor(
    override val pts: Pts,
    override val size: VideoSize,
    override val pixelFormat: PlayerPixelFormat,
    override val colorSpace: ColorSpaceInfo,
    override val rotationDegrees: Int,
    override val generation: Generation,
    private val strides: IntArray,
    private val heights: IntArray,
    private val planes: Array<ByteArray>,
) : SoftwareReadableFrame {

    override val duration: Pts? = null
    override val hardwareSurface: HwSurfaceKind? = null
    override val planeCount: Int get() = planes.size

    override fun planeStride(index: Int): Int = strides[index]
    override fun planeHeight(index: Int): Int = heights[index]

    override fun copyPlane(index: Int, into: ByteArray, offset: Int) {
        planes[index].copyInto(into, destinationOffset = offset)
    }

    /** The copies are plain arrays; there is nothing to release. */
    override fun close(): Unit = Unit

    internal companion object {
        /**
         * Copies [frame]'s planes, or refuses typed when the frame keeps its pixels somewhere a
         * CPU cannot read (a direct hardware frame with no software twin).
         */
        fun of(frame: VideoFrame): CapturedFrame {
            val readable = frame as? SoftwareReadableFrame ?: throw UnsupportedOperationException(
                "the presented frame is hardware-opaque (${frame.pixelFormat}, " +
                    "${frame.hardwareSurface}); capture needs a frame with readable planes, " +
                    "which the software and download decode paths produce",
            )
            val count = readable.planeCount
            val strides = IntArray(count) { readable.planeStride(it) }
            val heights = IntArray(count) { readable.planeHeight(it) }
            val planes = Array(count) { index ->
                ByteArray(strides[index] * heights[index]).also { readable.copyPlane(index, it) }
            }
            return CapturedFrame(
                pts = frame.pts,
                size = frame.size,
                pixelFormat = frame.pixelFormat,
                colorSpace = frame.colorSpace,
                rotationDegrees = frame.rotationDegrees,
                generation = frame.generation,
                strides = strides,
                heights = heights,
                planes = planes,
            )
        }
    }
}
