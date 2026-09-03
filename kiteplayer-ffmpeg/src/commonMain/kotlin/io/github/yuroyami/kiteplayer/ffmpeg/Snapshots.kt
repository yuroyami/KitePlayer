package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteffmpeg.CodecId
import io.github.yuroyami.kiteffmpeg.Frame
import io.github.yuroyami.kiteffmpeg.PixelFormat
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.spi.SoftwareReadableFrame

/** The standalone image formats a snapshot can take. Both encoders are in every KiteFFmpeg build. */
public enum class SnapshotFormat { Png, Jpeg }

/**
 * Encodes a frame with readable planes as a standalone image: a `CapturedFrame` from
 * `KitePlayer.captureFrame`, or any software frame a backend produced.
 *
 * Runs the pixel conversion FFmpeg needs on the calling thread and leaves this frame untouched.
 * Hardware-opaque frames have no readable planes and never reach here: capture refuses them
 * first, typed.
 *
 * @throws UnsupportedOperationException for a frame whose pixel format has no FFmpeg name
 * @throws io.github.yuroyami.kiteffmpeg.FFmpegException when the encoder refuses the frame
 */
public fun SoftwareReadableFrame.encode(format: SnapshotFormat = SnapshotFormat.Jpeg): ByteArray {
    val name = pixelFormat.ffmpegName()
        ?: throw UnsupportedOperationException("a $pixelFormat frame has no pixels an image encoder can take")
    val frame = Frame.ofVideo(tightlyPackedPlanes(), size.width, size.height, PixelFormat(name))
    try {
        return frame.encodeImage(if (format == SnapshotFormat.Png) CodecId.Png else CodecId.Mjpeg)
    } finally {
        frame.close()
    }
}

/** The FFmpeg name of a readable format, or null for the opaque one. */
internal fun PlayerPixelFormat.ffmpegName(): String? = when (this) {
    PlayerPixelFormat.Yuv420p -> "yuv420p"
    PlayerPixelFormat.Yuv422p -> "yuv422p"
    PlayerPixelFormat.Yuv444p -> "yuv444p"
    PlayerPixelFormat.Yuv420p10le -> "yuv420p10le"
    PlayerPixelFormat.Yuv422p10le -> "yuv422p10le"
    PlayerPixelFormat.Nv12 -> "nv12"
    PlayerPixelFormat.P010le -> "p010le"
    PlayerPixelFormat.Rgba -> "rgba"
    PlayerPixelFormat.Bgra -> "bgra"
    PlayerPixelFormat.Rgb24 -> "rgb24"
    PlayerPixelFormat.Opaque -> null
}

/** The bytes one row of [plane] holds for a picture [width] wide, without stride padding. */
internal fun PlayerPixelFormat.rowBytes(plane: Int, width: Int): Int {
    val chroma = (width + 1) / 2
    return when (this) {
        PlayerPixelFormat.Yuv420p, PlayerPixelFormat.Yuv422p -> if (plane == 0) width else chroma
        PlayerPixelFormat.Yuv444p -> width
        PlayerPixelFormat.Yuv420p10le, PlayerPixelFormat.Yuv422p10le -> (if (plane == 0) width else chroma) * 2
        PlayerPixelFormat.Nv12 -> if (plane == 0) width else chroma * 2
        PlayerPixelFormat.P010le -> (if (plane == 0) width else chroma * 2) * 2
        PlayerPixelFormat.Rgba, PlayerPixelFormat.Bgra -> width * 4
        PlayerPixelFormat.Rgb24 -> width * 3
        PlayerPixelFormat.Opaque -> throw UnsupportedOperationException("an opaque frame has no rows")
    }
}

/**
 * Every plane in order with its stride padding removed, which is the layout [Frame.ofVideo]
 * documents: tightly packed planes, Y then U then V, or interleaved for packed formats.
 */
internal fun SoftwareReadableFrame.tightlyPackedPlanes(): ByteArray {
    val width = size.width
    val rows = IntArray(planeCount) { rowBytes(it) }
    val total = (0 until planeCount).sumOf { rows[it].toLong() * planeHeight(it) }
    require(total <= Int.MAX_VALUE) { "a ${size.width}x${size.height} $pixelFormat frame does not fit one array" }
    val packed = ByteArray(total.toInt())
    var at = 0
    for (plane in 0 until planeCount) {
        val stride = planeStride(plane)
        val height = planeHeight(plane)
        val rowBytes = rows[plane]
        require(rowBytes <= stride) { "plane $plane of a $pixelFormat frame $width wide claims a stride of $stride" }
        val raw = ByteArray(stride * height).also { copyPlane(plane, it, 0) }
        for (row in 0 until height) {
            raw.copyInto(packed, destinationOffset = at, startIndex = row * stride, endIndex = row * stride + rowBytes)
            at += rowBytes
        }
    }
    return packed
}

private fun SoftwareReadableFrame.rowBytes(plane: Int): Int = pixelFormat.rowBytes(plane, size.width)
