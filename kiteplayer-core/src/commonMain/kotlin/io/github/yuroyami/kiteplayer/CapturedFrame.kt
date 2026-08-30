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
 *
 * ## These pixels are DECODED, not colour managed
 *
 * The engine's display paths roll HDR off to standard dynamic range before you see it, and they
 * say so with [PlaybackWarning.HdrToneMapped]. **A captured frame has had none of that done to
 * it.** Its samples carry the source's own transfer function, so a PQ or HLG capture converted
 * naively as if it were SDR shows flat highlights and reads dull. [colorSpace] carries what it
 * actually is; convert accordingly.
 *
 * This used to be announced as a `TonemappingUnavailable` warning during playback, which told
 * every viewer about a caveat that only concerns a caller doing its own conversion
 * It lives here now, where that caller will meet it.
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

    init {
        // The geometry comes from a BACKEND, not from a caller, so these are checks on the
        // decoder rather than on the application. A stride or height that is zero or negative
        // reached ByteArray() as a size and died there with NegativeArraySizeException, naming
        // nothing; a plane shorter than its own geometry claims would have been read past its end
        // by any consumer that trusted planeStride and planeHeight, which is what they are for.
        require(strides.size == planes.size && heights.size == planes.size) {
            "plane geometry disagrees: ${strides.size} strides, ${heights.size} heights, " +
                "${planes.size} planes"
        }
        for (index in planes.indices) {
            require(strides[index] > 0 && heights[index] > 0) {
                "plane $index has non-positive geometry ${strides[index]}x${heights[index]}"
            }
            val needed = strides[index].toLong() * heights[index].toLong()
            require(planes[index].size >= needed) {
                "plane $index holds ${planes[index].size} bytes but its geometry " +
                    "${strides[index]}x${heights[index]} needs $needed"
            }
        }
    }

    override val duration: Pts? = null
    override val hardwareSurface: HwSurfaceKind? = null
    override val planeCount: Int get() = planes.size

    override fun planeStride(index: Int): Int = strides[index]
    override fun planeHeight(index: Int): Int = heights[index]

    override fun copyPlane(index: Int, into: ByteArray, offset: Int) {
        val plane = planes[index]
        // Named rather than an IndexOutOfBounds from inside copyInto: the caller sized a buffer
        // and this says by how much it was wrong.
        require(offset >= 0 && into.size - offset >= plane.size) {
            "plane $index needs ${plane.size} bytes at offset $offset, but the destination holds " +
                "${into.size}"
        }
        plane.copyInto(into, destinationOffset = offset)
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
            require(count > 0) { "a readable frame reported $count planes" }
            val strides = IntArray(count) { readable.planeStride(it) }
            val heights = IntArray(count) { readable.planeHeight(it) }
            val planes = Array(count) { index ->
                // Checked BEFORE the allocation, so a backend reporting nonsense is named here
                // rather than dying inside ByteArray() with a size it will not print.
                val stride = strides[index]
                val height = heights[index]
                require(stride > 0 && height > 0) {
                    "the frame reports plane $index as ${stride}x$height, which cannot be copied"
                }
                val bytes = stride.toLong() * height.toLong()
                require(bytes <= Int.MAX_VALUE) {
                    "plane $index would need $bytes bytes, which no array can hold"
                }
                ByteArray(bytes.toInt()).also { readable.copyPlane(index, it) }
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
