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
 * - **The bit alignment.** A ten bit component sits either in the low ten bits of its word or in the
 *   high ten, and the two are sixteen times apart. Guessing produces a picture that is entirely white
 *   or entirely black.
 *
 * Chroma upsampling here is nearest neighbour, which is tier 0's deliberate limit: it is exactly what
 * a GPU renderer does with a nearest-filtered texture, so the two can be compared pixel for pixel.
 * Bilinear chroma is a tier 1 improvement, and it is the point at which the declared chroma location
 * starts to change the picture; see `chromaSampleShift` for why nearest neighbour does not care.
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

        // A VideoToolbox frame converts through its downloaded software twin (S2.b): the twin
        // carries the REAL pixel format (nv12) where the wrapper honestly says Opaque, and
        // av_frame_copy_props preserved the colour metadata, so the wrapper's colorSpace holds.
        // Hardware kinds that cannot be read back refuse inside readableFrame.
        val format = if (frame.hardwareSurface == null) {
            frame.pixelFormat
        } else {
            frame.readableFrame().info.pixelFormat.toPlayerFormat()
        }

        val out = ByteArray(width * height * 4)
        when (format) {
            PlayerPixelFormat.Yuv420p ->
                frame.convertPlanarYuv(out, subsampleX = 1, subsampleY = 1, layout = SampleLayout.Eight)
            PlayerPixelFormat.Yuv422p ->
                frame.convertPlanarYuv(out, subsampleX = 1, subsampleY = 0, layout = SampleLayout.Eight)
            PlayerPixelFormat.Yuv444p ->
                frame.convertPlanarYuv(out, subsampleX = 0, subsampleY = 0, layout = SampleLayout.Eight)
            PlayerPixelFormat.Yuv420p10le ->
                frame.convertPlanarYuv(out, subsampleX = 1, subsampleY = 1, layout = SampleLayout.TenLowAligned)
            PlayerPixelFormat.Yuv422p10le ->
                frame.convertPlanarYuv(out, subsampleX = 1, subsampleY = 0, layout = SampleLayout.TenLowAligned)
            PlayerPixelFormat.Nv12 -> frame.convertNv12(out, layout = SampleLayout.Eight)
            PlayerPixelFormat.P010le -> frame.convertNv12(out, layout = SampleLayout.TenHighAligned)
            PlayerPixelFormat.Rgba -> frame.copyPacked(out, sourceComponents = 4, redFirst = true)
            PlayerPixelFormat.Bgra -> frame.copyPacked(out, sourceComponents = 4, redFirst = false)
            PlayerPixelFormat.Rgb24 -> frame.copyPacked(out, sourceComponents = 3, redFirst = true)
            else -> throw IllegalArgumentException(
                "tier 0 cannot convert $format. A hardware frame needs its matching " +
                    "renderer, and an unusual software format needs a filter graph first.",
            )
        }
        return out
    }

    private fun KiteCodecVideoFrame.convertPlanarYuv(
        out: ByteArray,
        subsampleX: Int,
        subsampleY: Int,
        layout: SampleLayout,
    ) {
        val width = size.width
        val height = size.height
        val coefficients = Coefficients.of(colorSpace)
        val chromaShift = chromaSampleShift(colorSpace.chromaLocation, subsampleX)
        val lastChromaColumn = chromaColumns(width, subsampleX) - 1

        readableFrame().withPlanes { planes, strides, _ ->
            require(planes.size >= 3) { "a planar YUV frame needs three planes, got ${planes.size}" }
            val y = planes[0]
            val u = planes[1]
            val v = planes[2]
            val yStride = strides[0]
            val uStride = strides[1]
            val vStride = strides[2]
            val step = layout.bytesPerSample

            for (row in 0 until height) {
                val chromaRow = row shr subsampleY
                val yRow = row * yStride
                val uRow = chromaRow * uStride
                val vRow = chromaRow * vStride
                var outIndex = row * width * 4

                for (column in 0 until width) {
                    val chromaColumn = ((column + chromaShift) shr subsampleX).coerceIn(0, lastChromaColumn)
                    val luma = readComponent(y, yRow + column * step, layout)
                    val chromaB = readComponent(u, uRow + chromaColumn * step, layout)
                    val chromaR = readComponent(v, vRow + chromaColumn * step, layout)
                    outIndex = writeRgba(out, outIndex, coefficients, luma, chromaB, chromaR)
                }
            }
        }
    }

    private fun KiteCodecVideoFrame.convertNv12(out: ByteArray, layout: SampleLayout) {
        val width = size.width
        val height = size.height
        val coefficients = Coefficients.of(colorSpace)
        // The same rule as the planar path, from the same function. NV12 and P010 are 4:2:0, so the
        // horizontal subsampling that rule is asked about is 1.
        val chromaShift = chromaSampleShift(colorSpace.chromaLocation, subsampleX = 1)
        val lastChromaColumn = chromaColumns(width, subsampleX = 1) - 1

        readableFrame().withPlanes { planes, strides, _ ->
            require(planes.size >= 2) { "an NV12 frame needs two planes, got ${planes.size}" }
            val y = planes[0]
            val uv = planes[1]
            val yStride = strides[0]
            val uvStride = strides[1]
            val step = layout.bytesPerSample

            for (row in 0 until height) {
                val yRow = row * yStride
                val uvRow = (row shr 1) * uvStride
                var outIndex = row * width * 4

                for (column in 0 until width) {
                    val chromaColumn = ((column + chromaShift) shr 1).coerceIn(0, lastChromaColumn)
                    val luma = readComponent(y, yRow + column * step, layout)
                    // Chroma is interleaved in this format: U then V, per chroma sample.
                    val chromaB = readComponent(uv, uvRow + chromaColumn * 2 * step, layout)
                    val chromaR = readComponent(uv, uvRow + (chromaColumn * 2 + 1) * step, layout)
                    outIndex = writeRgba(out, outIndex, coefficients, luma, chromaB, chromaR)
                }
            }
        }
    }

    private fun KiteCodecVideoFrame.copyPacked(out: ByteArray, sourceComponents: Int, redFirst: Boolean) {
        val width = size.width
        val height = size.height
        readableFrame().withPlanes { planes, strides, _ ->
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
     * How one component sits in memory, which is not decided by its bit depth alone.
     *
     * Ten bit formats come in two shapes and they are not interchangeable. `yuv420p10le` is
     * low aligned: the ten bits sit in the low ten of a 16-bit little endian word and the top six are
     * zero. `p010le` is high aligned: the ten bits sit in the TOP ten and the low six are zero, which
     * is what the hardware decoders that produce it want. Reading one as the other is not a rounding
     * difference. A high aligned word read as low aligned comes out sixteen times too large and
     * clamps to white almost everywhere; a low aligned word read as high aligned comes out sixteen
     * times too small and the picture goes black.
     */
    private enum class SampleLayout(val bytesPerSample: Int, val dropBits: Int) {
        /** One byte per component, nothing to shift. */
        Eight(1, 0),

        /** Ten bits in the low ten of a 16-bit word: keep the top eight of the ten, drop two. */
        TenLowAligned(2, 2),

        /** Ten bits in the high ten of a 16-bit word: the high byte already is the top eight. */
        TenHighAligned(2, 8),
    }

    /**
     * Reads one component, scaled to the 0 to 255 range whatever the source depth.
     *
     * Scaling ten bits to eight keeps the most significant eight of the ten and drops the rest, which
     * is what [SampleLayout.dropBits] says for each shape. Keeping the low eight instead throws away
     * each sample's magnitude and produces an image that is dark and noisy, which looks like a decode
     * failure rather than a conversion bug.
     */
    private fun readComponent(
        plane: kotlinx.cinterop.CPointer<kotlinx.cinterop.UByteVar>,
        offset: Int,
        layout: SampleLayout,
    ): Int = if (layout.bytesPerSample == 1) {
        plane[offset].toInt()
    } else {
        val low = plane[offset].toInt()
        val high = plane[offset + 1].toInt()
        ((high shl 8) or low) shr layout.dropBits
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
     * Chroma sampling offset for the declared chroma location, in luma columns.
     *
     * With left-sited chroma, which is what MPEG-2 and H.264 use, chroma sample `j` sits on luma
     * column `2j`. With centre-sited chroma, which JPEG uses, it sits at `2j + 0.5`, between two luma
     * columns. Either way the nearest chroma sample to luma column `x` is `x / 2`: at `2j + 0.5` the
     * distances are 0.5 for both columns of the pair and 1.5 for the next sample along, and at `2j`
     * the two candidates tie at a distance of one, so the same sample is a nearest one. The offset is
     * therefore zero for every horizontal siting, and this function exists so both conversion paths
     * read the rule from one place rather than each inventing it.
     *
     * Siting is not irrelevant in general. It sets the phase of an INTERPOLATING upsampler, which is
     * a tier 1 improvement; it cannot change which single sample a nearest neighbour picks. Two
     * independent checks agree. FFmpeg's own `format=rgba` with `-sws_flags neighbor` produces
     * bit-identical output for a left-sited and a centre-sited copy of the same clip, measured. And a
     * nearest-filtered GPU texture, which this converter exists to be comparable with, samples chroma
     * texel `x / 2` whatever the metadata says.
     */
    private fun chromaSampleShift(location: ChromaLocation, subsampleX: Int): Int {
        // A table over the sitings that exist rather than a bare zero, so the one place this rule
        // lives names every case it answers for, and so a new siting has to be decided here.
        val shift = when (location) {
            // Co-sited horizontally: chroma sample j sits on luma column 2j.
            ChromaLocation.Left, ChromaLocation.TopLeft, ChromaLocation.BottomLeft -> 0
            // Half a luma column to the right of that: chroma sample j sits at 2j + 0.5.
            ChromaLocation.Center, ChromaLocation.Top, ChromaLocation.Bottom -> 0
        }
        return shift * subsampleX
    }

    /**
     * Chroma samples per row for a luma width of [width].
     *
     * Rounded up, because an odd width still needs the chroma sample that covers its last column.
     * Used to keep the sampled column inside the plane: a converter reading native memory must not be
     * one shift away from reading past the end of a row.
     */
    private fun chromaColumns(width: Int, subsampleX: Int): Int =
        (width + (1 shl subsampleX) - 1) shr subsampleX

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
            fun of(colorSpace: ColorSpaceInfo): Coefficients {
                val offset = if (colorSpace.fullRange) 0 else 16
                val lumaScale = if (colorSpace.fullRange) 1.0 else 255.0 / 219.0
                val chromaScale = if (colorSpace.fullRange) 1.0 else 255.0 / 224.0

                return when (colorSpace.matrix) {
                    ColorMatrix.Bt601 -> Coefficients(
                        offset, lumaScale, chromaScale,
                        rCr = 1.402, gCb = 0.344136, gCr = 0.714136, bCb = 1.772,
                    )
                    // SMPTE 240M is its own matrix and not BT.601 under another name. Its luma
                    // weights are 0.212 and 0.087, between BT.601's and BT.709's, so borrowing
                    // BT.601's row shifts every hue on this content by a mean of 7.7 of 255.
                    ColorMatrix.Smpte240m -> Coefficients(
                        offset, lumaScale, chromaScale,
                        rCr = 1.576, gCb = 0.2266, gCr = 0.4769, bCb = 1.826,
                    )
                    // Constant luminance is converted with the non-constant luminance row, which is
                    // an approximation: a correct path needs the whole transfer function, not just a
                    // matrix. The decoder warns once per stream that it did this, in the same way and
                    // for the same reason as it warns about HDR.
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
