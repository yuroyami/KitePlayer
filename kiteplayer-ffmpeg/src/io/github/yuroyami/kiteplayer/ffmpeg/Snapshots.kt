package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteffmpeg.CodecId
import io.github.yuroyami.kiteffmpeg.FilterGraph
import io.github.yuroyami.kiteffmpeg.Frame
import io.github.yuroyami.kiteffmpeg.PixelFormat
import io.github.yuroyami.kiteffmpeg.Rational
import io.github.yuroyami.kiteplayer.spi.ColorMatrix
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.spi.SoftwareReadableFrame

/** The standalone image formats a snapshot can take. Both encoders are in every KiteFFmpeg build. */
public enum class SnapshotFormat { Png, Jpeg }

/**
 * Encodes a frame with readable planes as a standalone image: a `CapturedFrame` from
 * `KitePlayer.captureFrame`, or any software frame a backend produced.
 *
 * The image is the picture as a renderer shows it (#608): the frame's crop comes off, its pixels
 * become square by its pixel aspect, and its mirror and turn apply, so a portrait phone recording
 * saves upright and an anamorphic picture keeps its proportions. A subtitle overlay is not drawn.
 * Runs the pixel conversion FFmpeg needs on the calling thread and leaves this frame untouched.
 * A hardware frame that is read through a downloaded copy, such as a VideoToolbox frame, encodes
 * that copy.
 *
 * @throws UnsupportedOperationException for a frame whose pixel format has no FFmpeg name
 * @throws io.github.yuroyami.kiteffmpeg.FFmpegException when the encoder refuses the frame
 */
public fun SoftwareReadableFrame.encode(format: SnapshotFormat = SnapshotFormat.Jpeg): ByteArray {
    // FFmpeg's scaler has no Identity matrix: named yuv444p, these planes would be read as YCbCr.
    // Named gbrp, they are the picture.
    val name = if (planeFormat == PlayerPixelFormat.Yuv444p && colorSpace.matrix == ColorMatrix.Identity) {
        "gbrp"
    } else {
        planeFormat.ffmpegName()
            ?: throw UnsupportedOperationException("a $planeFormat frame has no pixels an image encoder can take")
    }
    val codec = if (format == SnapshotFormat.Png) CodecId.Png else CodecId.Mjpeg
    val frame = Frame.ofVideo(tightlyPackedPlanes(), size.width, size.height, PixelFormat(name))
    val shown = shownGeometry(
        size.width, size.height, size.pixelAspectNumerator, size.pixelAspectDenominator,
        crop, rotationDegrees, mirrored,
    )
    val filter = shown.filter
    if (filter == null) {
        try {
            return frame.encodeImage(codec)
        } finally {
            frame.close()
        }
    }
    val graph = try {
        FilterGraph.buildVideo(
            description = filter,
            width = size.width,
            height = size.height,
            pixelFormat = PixelFormat(name),
            timeBase = Rational(1, 1_000_000),
            frameRate = Rational(25, 1),
            sampleAspectRatio = Rational(1, 1),
        )
    } catch (failure: Throwable) {
        frame.close()
        throw failure
    }
    try {
        var image: ByteArray? = null
        val take: (Frame) -> Unit = { out -> if (image == null) image = out.encodeImage(codec) }
        // The graph closes the frame it is fed.
        graph.feedInput(0, frame, take)
        if (image == null) graph.flushInput(0, take)
        return image ?: error("the filters produced no frame to encode")
    } finally {
        graph.close()
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
    require(total <= Int.MAX_VALUE) { "a ${size.width}x${size.height} $planeFormat frame does not fit one array" }
    val packed = ByteArray(total.toInt())
    var at = 0
    for (plane in 0 until planeCount) {
        val stride = planeStride(plane)
        val height = planeHeight(plane)
        val rowBytes = rows[plane]
        require(rowBytes <= stride) { "plane $plane of a $planeFormat frame $width wide claims a stride of $stride" }
        val raw = ByteArray(stride * height).also { copyPlane(plane, it, 0) }
        for (row in 0 until height) {
            raw.copyInto(packed, destinationOffset = at, startIndex = row * stride, endIndex = row * stride + rowBytes)
            at += rowBytes
        }
    }
    return packed
}

private fun SoftwareReadableFrame.rowBytes(plane: Int): Int = planeFormat.rowBytes(plane, size.width)
