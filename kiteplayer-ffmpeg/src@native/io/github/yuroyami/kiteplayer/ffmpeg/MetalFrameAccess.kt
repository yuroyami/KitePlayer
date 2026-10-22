@file:OptIn(ExperimentalForeignApi::class, KiteFFmpegLowLevelApi::class)

package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.spi.HwSurfaceKind
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteffmpeg.KiteFFmpegLowLevelApi
import io.github.yuroyami.kiteffmpeg.hardwareSurface
import io.github.yuroyami.kiteffmpeg.withPlanes
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.posix.memcpy

/**
 * What a GPU renderer needs from a frame, in the backend's own words. The output module
 * defines the renderer seam and never sees this backend; a consumer that owns both maps these
 * two answers onto it in a handful of lines. The memcpy discipline lives HERE, once: a hardware
 * frame crosses as its CVPixelBuffer with no copy at all, and a software frame crosses as its
 * planes copied out whole (stride times rows, one memcpy each) still in their native pixel
 * format, which is the law: YUV until the GPU, never RGBA on the CPU.
 */
public class UploadPlanes(
    public val width: Int,
    public val height: Int,
    public val format: PlayerPixelFormat,
    public val planes: List<UploadPlane>,
) {
    public class UploadPlane(
        public val bytes: ByteArray,
        public val bytesPerRow: Int,
        public val rows: Int,
    )
}

/** The CVPixelBuffer of a VideoToolbox frame, or null when the frame is not that kind. */
public fun KiteFFmpegVideoFrame.corePixelBufferOrNull(): COpaquePointer? =
    if (hardwareSurface == HwSurfaceKind.CoreVideoPixelBuffer) frame.hardwareSurface else null

/**
 * The frame's planes for texture upload, or null when the frame lives in hardware memory (use
 * [corePixelBufferOrNull] there instead; downloading to feed a GPU that could wrap the surface
 * directly would be the exact copy this path exists to avoid).
 */
public fun KiteFFmpegVideoFrame.uploadPlanesOrNull(): UploadPlanes? {
    if (hardwareSurface != null) return null
    val copied = frame.withPlanes { planes, strides, heights ->
        planes.mapIndexed { index, plane ->
            UploadPlanes.UploadPlane(
                bytes = copyOut(plane, strides[index] * heights[index]),
                bytesPerRow = strides[index],
                rows = heights[index],
            )
        }
    }
    return UploadPlanes(
        width = size.width,
        height = size.height,
        format = pixelFormat,
        planes = copied,
    )
}

/**
 * [size] bytes at [source] in a new array, in one C copy. cinterop's `readBytes` stores one byte
 * per loop turn and measured about 1,300 times slower on a 1080p plane.
 */
internal fun copyOut(source: CPointer<*>, size: Int): ByteArray {
    val bytes = ByteArray(size)
    if (size > 0) bytes.usePinned { memcpy(it.addressOf(0), source, size.convert()) }
    return bytes
}
