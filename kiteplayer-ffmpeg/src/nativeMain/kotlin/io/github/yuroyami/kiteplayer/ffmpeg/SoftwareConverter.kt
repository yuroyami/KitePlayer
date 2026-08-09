@file:OptIn(KiteCodecLowLevelApi::class, ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.spi.ChromaLocation
import io.github.yuroyami.kiteplayer.spi.ColorMatrix
import io.github.yuroyami.kiteplayer.spi.ColorSpaceInfo
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kitecodec.KiteCodecLowLevelApi
import io.github.yuroyami.kitecodec.withPlanes
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get

/**
 * Turns a decoded frame into RGBA, on the CPU.
 *
 * This is the tier 0 renderer path, and it ships rather than being a
 * placeholder. It is the reference every GPU renderer is checked against, and it is the fallback when
 * a GPU path fails. At 1080p it costs a few milliseconds of CPU per frame, which is too much for
 * comfortable playback and fine for a screenshot, a thumbnail strip or a correctness test.
 *
 * ### What it has to get right
 *
 * Four things, and each one is visible when wrong:
 *
 * - **The row pitch.** A plane's rows are almost never exactly `width` bytes apart. Assuming they are
 *   produces an image that skews diagonally, and it is the most common first bug in a video pipeline.
 *   The pitch comes from the frame rather than being computed.
 * - **The matrix.** BT.601 and BT.709 differ enough that using one for the other shifts every hue,
 *   worst on saturated reds.
 * - **The range.** Video is usually studio range, where 16 is black and 235 is white. Treating it as
 *   full range turns blacks grey and clips whites.
 * - **Chroma position.** Colour planes are quarter size in the common formats, so each chroma sample
 *   covers four luma samples. Which four depends on the chroma location.
 *
 * Chroma upsampling here is nearest neighbour, which is tier 0's deliberate limit: it is exactly what
 * a GPU renderer does with a nearest-filtered texture, so the two can be compared pixel for pixel.
 * Bilinear chroma is a tier 1 improvement.
 */
public object SoftwareConverter {

    /**
     * Converts [frame] to tightly packed RGBA, one byte per component, no row padding.
     *
     * @return `width * height * 4` bytes.
     * @throws IllegalArgumentException when the pixel format is not one this converter handles.
     * @throws IllegalStateException when the frame lives in hardware memory.
     */
    public fun toRgba(frame: KiteCodecVideoFrame): ByteArray {
        val width = frame.size.width
        val height = frame.size.height
        require(width > 0 && height > 0) { "frame has no dimensions: ${width}x$height" }

        val out = ByteArray(width * height * 4)
        when (frame.pixelFormat) {
            PlayerPixelFormat.Yuv420p -> frame.convertPlanarYuv(out, subsampleX = 1, subsampleY = 1, depth = 8)
            PlayerPixelFormat.Yuv422p -> frame.convertPlanarYuv(out, subsampleX = 1, subsampleY = 0, depth = 8)
            PlayerPixelFormat.Yuv444p -> frame.convertPlanarYuv(out, subsampleX = 0, subsampleY = 0, depth = 8)
            PlayerPixelFormat.Yuv420p10le -> frame.convertPlanarYuv(out, subsampleX = 1, subsampleY = 1, depth = 10)
            PlayerPixelFormat.Yuv422p10le -> frame.convertPlanarYuv(out, subsampleX = 1, subsampleY = 0, depth = 10)
            PlayerPixelFormat.Nv12 -> frame.convertNv12(out, depth = 8)
            PlayerPixelFormat.P010le -> frame.convertNv12(out, depth = 10)
            PlayerPixelFormat.Rgba -> frame.copyPacked(out, sourceComponents = 4, redFirst = true)
            PlayerPixelFormat.Bgra -> frame.copyPacked(out, sourceComponents = 4, redFirst = false)
            PlayerPixelFormat.Rgb24 -> frame.copyPacked(out, sourceComponents = 3, redFirst = true)
            else -> throw IllegalArgumentException(
                "tier 0 cannot convert ${frame.pixelFormat}. A hardware frame needs its matching " +
                    "renderer, and an unusual software format needs a filter graph first.",
            )
        }
        return out
    }

    private fun KiteCodecVideoFrame.convertPlanarYuv(
        out: ByteArray,
        subsampleX: Int,
        subsampleY: Int,
        depth: Int,
    ) {
        val width = size.width
        val height = size.height
        val coefficients = Coefficients.of(colorSpace, depth)
        val chromaShift = chromaSampleShift(colorSpace.chromaLocation, subsampleX)

        frame.withPlanes { planes, strides, _ ->
            require(planes.size >= 3) { "a planar YUV frame needs three planes, got ${planes.size}" }
            val y = planes[0]
            val u = planes[1]
            val v = planes[2]
            val yStride = strides[0]
            val uStride = strides[1]
            val vStride = strides[2]
            val step = if (depth > 8) 2 else 1

            for (row in 0 until height) {
                val chromaRow = row shr subsampleY
                val yRow = row * yStride
                val uRow = chromaRow * uStride
                val vRow = chromaRow * vStride
                var outIndex = row * width * 4

                for (column in 0 until width) {
                    val chromaColumn = (column + chromaShift).coerceAtLeast(0) shr subsampleX
                    val luma = readComponent(y, yRow + column * step, depth)
                    val chromaB = readComponent(u, uRow + chromaColumn * step, depth)
                    val chromaR = readComponent(v, vRow + chromaColumn * step, depth)
                    outIndex = writeRgba(out, outIndex, coefficients, luma, chromaB, chromaR)
                }
            }
        }
    }

    private fun KiteCodecVideoFrame.convertNv12(out: ByteArray, depth: Int) {
        val width = size.width
        val height = size.height
        val coefficients = Coefficients.of(colorSpace, depth)

        frame.withPlanes { planes, strides, _ ->
            require(planes.size >= 2) { "an NV12 frame needs two planes, got ${planes.size}" }
            val y = planes[0]
            val uv = planes[1]
            val yStride = strides[0]
            val uvStride = strides[1]
            val step = if (depth > 8) 2 else 1

            for (row in 0 until height) {
                val yRow = row * yStride
                val uvRow = (row shr 1) * uvStride
                var outIndex = row * width * 4

                for (column in 0 until width) {
                    val chromaColumn = column shr 1
                    val luma = readComponent(y, yRow + column * step, depth)
                    // Chroma is interleaved in this format: U then V, per chroma sample.
                    val chromaB = readComponent(uv, uvRow + chromaColumn * 2 * step, depth)
                    val chromaR = readComponent(uv, uvRow + (chromaColumn * 2 + 1) * step, depth)
                    outIndex = writeRgba(out, outIndex, coefficients, luma, chromaB, chromaR)
                }
            }
        }
    }

    private fun KiteCodecVideoFrame.copyPacked(out: ByteArray, sourceComponents: Int, redFirst: Boolean) {
        val width = size.width
        val height = size.height
        frame.withPlanes { planes, strides, _ ->
            require(planes.isNotEmpty()) { "a packed frame needs one plane" }
            val source = planes[0]
            val stride = strides[0]
            for (row in 0 until height) {
                val sourceRow = row * stride
                var outIndex = row * width * 4
                for (column in 0 until width) {
                    val at = sourceRow + column * sourceComponents
                    val first = source[at].toInt() and 0xFF
                    val green = source[at + 1].toInt() and 0xFF
                    val third = source[at + 2].toInt() and 0xFF
                    val alpha = if (sourceComponents == 4) source[at + 3].toInt() and 0xFF else 255
                    out[outIndex++] = (if (redFirst) first else third).toByte()
                    out[outIndex++] = green.toByte()
                    out[outIndex++] = (if (redFirst) third else first).toByte()
                    out[outIndex++] = alpha.toByte()
                }
            }
        }
    }

    /**
     * Reads one component, scaled to the 0 to 255 range whatever the source depth.
     *
     * 10-bit samples are little endian and occupy the low bits of a 16-bit word, so the high two bits
     * of the value are dropped rather than the low two. Dropping the wrong end produces an image that
     * is dark and noisy, which looks like a decode failure rather than a conversion bug.
     */
    private fun readComponent(
        plane: kotlinx.cinterop.CPointer<kotlinx.cinterop.UByteVar>,
        offset: Int,
        depth: Int,
    ): Int = if (depth <= 8) {
        plane[offset].toInt()
    } else {
        val low = plane[offset].toInt()
        val high = plane[offset + 1].toInt()
        ((high shl 8) or low) shr (depth - 8)
    }

    private fun writeRgba(
        out: ByteArray,
        index: Int,
        c: Coefficients,
        luma: Int,
        chromaB: Int,
        chromaR: Int,
    ): Int {
        val y = (luma - c.lumaOffset) * c.lumaScale
        // Chroma has its own scale, and forgetting it is a mistake worth naming. Studio-range luma
        // spans 16 to 235, which is 219 levels, but studio-range chroma spans 16 to 240, which is
        // 224. Scaling both by the luma factor leaves every colour about 14 percent undersaturated:
        // a systematic error, not a rounding one, and invisible unless compared against a reference.
        val cb = (chromaB - 128) * c.chromaScale
        val cr = (chromaR - 128) * c.chromaScale

        val red = y + c.rCr * cr
        val green = y - c.gCb * cb - c.gCr * cr
        val blue = y + c.bCb * cb

        var at = index
        out[at++] = red.clampToByte()
        out[at++] = green.clampToByte()
        out[at++] = blue.clampToByte()
        out[at++] = -1  // 255, opaque
        return at
    }

    private fun Double.clampToByte(): Byte {
        val rounded = (this + 0.5).toInt()
        return when {
            rounded < 0 -> 0
            rounded > 255 -> -1
            else -> rounded.toByte()
        }
    }

    /**
     * Chroma sampling offset for the declared chroma location.
     *
     * With left-sited chroma, which is what MPEG-2 and H.264 use, a chroma sample sits on the same
     * column as the even luma sample. With centre-sited chroma, which JPEG uses, it sits between two
     * luma columns, so nearest neighbour should round to the nearer of the pair.
     */
    private fun chromaSampleShift(location: ChromaLocation, subsampleX: Int): Int =
        if (subsampleX > 0 && location == ChromaLocation.Center) 1 else 0

    /**
     * The matrix, as the six numbers the conversion actually uses.
     *
     * The values are the standard inverse matrices for each colour space. They are written out rather
     * than derived so that a reader can check them against the specification.
     */
    private class Coefficients(
        val lumaOffset: Int,
        val lumaScale: Double,
        val chromaScale: Double,
        val rCr: Double,
        val gCb: Double,
        val gCr: Double,
        val bCb: Double,
    ) {
        companion object {
            /**
             * The coefficients below are the full-range ones from each specification. The two range
             * scales convert them to studio range, which is what almost all video uses:
             *
             * - Luma spans 16 to 235, so 219 of 255 levels: scale by 255/219, about 1.164.
             * - Chroma spans 16 to 240, so 224 of 255 levels: scale by 255/224, about 1.138.
             *
             * Multiplying out gives the familiar published numbers. For BT.709,
             * 1.5748 times 1.138 is 1.793, which is the figure the standard studio-range matrix
             * quotes for the red-from-Cr term.
             */
            fun of(colorSpace: ColorSpaceInfo, depth: Int): Coefficients {
                val offset = if (colorSpace.fullRange) 0 else 16
                val lumaScale = if (colorSpace.fullRange) 1.0 else 255.0 / 219.0
                val chromaScale = if (colorSpace.fullRange) 1.0 else 255.0 / 224.0

                return when (colorSpace.matrix) {
                    ColorMatrix.Bt601, ColorMatrix.Smpte240m -> Coefficients(
                        offset, lumaScale, chromaScale,
                        rCr = 1.402, gCb = 0.344136, gCr = 0.714136, bCb = 1.772,
                    )
                    ColorMatrix.Bt2020Ncl, ColorMatrix.Bt2020Cl -> Coefficients(
                        offset, lumaScale, chromaScale,
                        rCr = 1.4746, gCb = 0.164553, gCr = 0.571353, bCb = 1.8814,
                    )
                    // BT.709, and the right default for anything unspecified above standard
                    // definition. See ColorInfo.guessFor, which KiteCodec applies before this.
                    else -> Coefficients(
                        offset, lumaScale, chromaScale,
                        rCr = 1.5748, gCb = 0.187324, gCr = 0.468124, bCb = 1.8556,
                    )
                }
            }
        }
    }
}
