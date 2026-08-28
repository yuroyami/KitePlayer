package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.spi.ChromaLocation
import io.github.yuroyami.kiteplayer.spi.ColorMatrix
import io.github.yuroyami.kiteplayer.spi.ColorPrimaries
import io.github.yuroyami.kiteplayer.spi.ColorSpaceInfo
import io.github.yuroyami.kiteplayer.spi.ColorTransfer
import io.github.yuroyami.kiteplayer.spi.HwSurfaceKind
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kitecodec.ChromaLocation as KiteChromaLocation
import io.github.yuroyami.kitecodec.ColorInfo
import io.github.yuroyami.kitecodec.ColorMatrix as KiteColorMatrix
import io.github.yuroyami.kitecodec.ColorPrimaries as KiteColorPrimaries
import io.github.yuroyami.kitecodec.ColorTransfer as KiteColorTransfer
import io.github.yuroyami.kitecodec.FrameInfo
import io.github.yuroyami.kitecodec.PixelFormat
import io.github.yuroyami.kitecodec.SampleFormat as KiteSampleFormat

/**
 * Translation between KiteCodec's vocabulary and the engine's.
 *
 * Two vocabularies exist on purpose. The engine must not name FFmpeg types anywhere, or a second
 * backend becomes impossible. The cost is this file, and it is a small and honest cost.
 */

internal fun PixelFormat.toPlayerFormat(): PlayerPixelFormat = when (name) {
    // The deprecated JPEG twins are the same plane layout; FFmpeg still reports them for
    // full-range streams, and the range itself arrives through ColorInfo, not the format name.
    "yuv420p", "yuvj420p" -> PlayerPixelFormat.Yuv420p
    "yuv422p", "yuvj422p" -> PlayerPixelFormat.Yuv422p
    "yuv444p", "yuvj444p" -> PlayerPixelFormat.Yuv444p
    "yuv420p10le" -> PlayerPixelFormat.Yuv420p10le
    "yuv422p10le" -> PlayerPixelFormat.Yuv422p10le
    "nv12" -> PlayerPixelFormat.Nv12
    "p010le" -> PlayerPixelFormat.P010le
    "rgba" -> PlayerPixelFormat.Rgba
    "bgra" -> PlayerPixelFormat.Bgra
    "rgb24" -> PlayerPixelFormat.Rgb24
    // Everything else, including every hardware format, is opaque to the engine. Only a renderer
    // matched to the decoder that produced it can draw it, and the engine does not need to know more.
    else -> PlayerPixelFormat.Opaque
}

internal fun ColorInfo.toPlayerColorSpace(): ColorSpaceInfo = ColorSpaceInfo(
    matrix = when (matrix) {
        KiteColorMatrix.Unspecified -> ColorMatrix.Unspecified
        KiteColorMatrix.Bt709 -> ColorMatrix.Bt709
        KiteColorMatrix.Fcc -> ColorMatrix.Fcc
        KiteColorMatrix.Bt470bg -> ColorMatrix.Bt470bg
        KiteColorMatrix.Smpte170m -> ColorMatrix.Smpte170m
        KiteColorMatrix.Smpte240m -> ColorMatrix.Smpte240m
        KiteColorMatrix.YCgCo -> ColorMatrix.YCgCo
        KiteColorMatrix.Bt2020Ncl -> ColorMatrix.Bt2020Ncl
        KiteColorMatrix.Bt2020Cl -> ColorMatrix.Bt2020Cl
        KiteColorMatrix.ICtCp -> ColorMatrix.ICtCp
        KiteColorMatrix.Rgb -> ColorMatrix.Identity
    },
    primaries = when (primaries) {
        KiteColorPrimaries.Unspecified -> ColorPrimaries.Unspecified
        KiteColorPrimaries.Bt709 -> ColorPrimaries.Bt709
        KiteColorPrimaries.Bt470m -> ColorPrimaries.Bt470m
        KiteColorPrimaries.Bt470bg -> ColorPrimaries.Bt470bg
        KiteColorPrimaries.Smpte170m -> ColorPrimaries.Smpte170m
        KiteColorPrimaries.Smpte240m -> ColorPrimaries.Smpte240m
        KiteColorPrimaries.Film -> ColorPrimaries.Film
        KiteColorPrimaries.Bt2020 -> ColorPrimaries.Bt2020
        KiteColorPrimaries.SmpteSt428 -> ColorPrimaries.SmpteSt428
        KiteColorPrimaries.SmpteSt431 -> ColorPrimaries.DciP3
        KiteColorPrimaries.SmpteSt432 -> ColorPrimaries.DisplayP3
    },
    transfer = when (transfer) {
        KiteColorTransfer.Unspecified -> ColorTransfer.Unspecified
        KiteColorTransfer.Bt709 -> ColorTransfer.Bt709
        KiteColorTransfer.Smpte170m -> ColorTransfer.Bt601
        KiteColorTransfer.Iec6196621 -> ColorTransfer.Srgb
        KiteColorTransfer.Linear -> ColorTransfer.Linear
        KiteColorTransfer.Gamma22 -> ColorTransfer.Gamma22
        KiteColorTransfer.Gamma28 -> ColorTransfer.Gamma28
        KiteColorTransfer.Smpte240m -> ColorTransfer.Smpte240m
        KiteColorTransfer.Log -> ColorTransfer.Log
        KiteColorTransfer.LogSqrt -> ColorTransfer.LogSqrt
        KiteColorTransfer.Iec6196624 -> ColorTransfer.Iec6196624
        KiteColorTransfer.Bt1361Ecg -> ColorTransfer.Bt1361Ecg
        KiteColorTransfer.Bt2020Ten -> ColorTransfer.Bt2020Ten
        KiteColorTransfer.Bt2020Twelve -> ColorTransfer.Bt2020Twelve
        KiteColorTransfer.SmpteSt2084 -> ColorTransfer.Pq
        KiteColorTransfer.SmpteSt428 -> ColorTransfer.SmpteSt428
        KiteColorTransfer.AribStdB67 -> ColorTransfer.Hlg
    },
    fullRange = fullRange,
    chromaLocation = when (chromaLocation) {
        KiteChromaLocation.Center -> ChromaLocation.Center
        KiteChromaLocation.TopLeft -> ChromaLocation.TopLeft
        KiteChromaLocation.Top -> ChromaLocation.Top
        KiteChromaLocation.BottomLeft -> ChromaLocation.BottomLeft
        KiteChromaLocation.Bottom -> ChromaLocation.Bottom
        KiteChromaLocation.Left -> ChromaLocation.Left
        KiteChromaLocation.Unspecified -> ChromaLocation.Unspecified
    },
    rangeSpecified = rangeSpecified,
)

internal fun hardwareKindFor(pixelFormatName: String): HwSurfaceKind? = when (pixelFormatName) {
    "videotoolbox_vld" -> HwSurfaceKind.CoreVideoPixelBuffer
    "mediacodec" -> HwSurfaceKind.MediaCodecBuffer
    "vaapi", "vaapi_vld" -> HwSurfaceKind.VaapiSurface
    "d3d11" -> HwSurfaceKind.D3d11Texture
    "cuda" -> HwSurfaceKind.CudaDevicePointer
    else -> null
}

/** Converts the tightly packed plane layout returned by KiteCodec's copying API to RGBA. */
/**
 * Runs a row range on every core there is (W-19), or on this one when that would cost more.
 *
 * The conversion loop is load and store bound, so it scales with cores: measured on a 1080p frame,
 * one task takes 6.36 ms and four take 1.89 ms. Each slice writes a DISJOINT range of output rows
 * and reads a disjoint range of input rows, so there is no shared mutable state and no ordering
 * between slices; that is what makes this safe without a lock.
 *
 * Two rules the caller does not get to break. Slice boundaries are always EVEN rows, because a
 * subsampled layout's chroma row serves two luma rows and a slice that started on an odd row would
 * read the wrong one. And below [PARALLEL_PIXEL_THRESHOLD] pixels of work the whole thing runs inline,
 * because dispatching costs more than a small frame's conversion saves.
 *
 * EXPECT/ACTUAL since 17.14 X-09, and the note this replaced is worth keeping visible because it
 * was true when written and stopped being true: it said "no expect/actual: every target this module
 * compiles for has a multi-threaded `Dispatchers.Default`". Adding wasmJs falsified both halves at
 * once. The web has no `runBlocking` at all, and its `Dispatchers.Default` is one event loop, so the
 * web actual runs the body serially and the 3.36x this buys elsewhere is simply not available there.
 */
internal expect inline fun parallelRowSlices(
    width: Int,
    height: Int,
    crossinline body: (startRow: Int, endRowExclusive: Int) -> Unit,
)

/** How many slices this frame is worth. One means stay on this thread. */
internal fun parallelSliceCount(width: Int, height: Int): Int {
    if (width.toLong() * height < PARALLEL_PIXEL_THRESHOLD) return 1
    // Four is where the measured ladder flattens: 3.36x at four tasks, 3.68x at eight, so tasks
    // five to eight together buy less than a tenth of what the first three did.
    return 4
}

/**
 * Below this much WORK the conversion stays on one thread.
 *
 * On pixels rather than rows, and that is a correction the measurement forced: a 640x240 frame ran
 * FASTER in parallel than a 426x238 one ran sequentially despite having half again as many pixels,
 * so height alone was the wrong axis and a wide short frame would have been left on one core.
 *
 * MEASURED, not guessed. Mean milliseconds per frame on this machine, sequential against four
 * slices, which puts the crossover between 19k and 37k pixels:
 *
 *     64x64     4k px   0.013 seq   0.116 par   sequential wins by 9x
 *     160x120  19k px   0.068 seq   0.201 par   sequential wins by 3x
 *     256x144  37k px   0.134 seq   0.099 par   parallel wins
 *     320x180  58k px   0.210 seq   0.156 par   parallel wins
 *     640x360 230k px   0.765 seq   0.416 par   parallel wins by 1.8x
 *
 * 65536 sits above the crossover with margin, so nothing measured regresses, and every real video
 * frame is far above it: even 640x360 carries three and a half times this.
 */
internal const val PARALLEL_PIXEL_THRESHOLD: Long = 65_536L

internal fun tightlyPackedToRgba(
    bytes: ByteArray,
    width: Int,
    height: Int,
    pixelFormat: PlayerPixelFormat,
    colorSpace: ColorSpaceInfo,
): ByteArray {
    require(width > 0 && height > 0) { "frame has no dimensions: ${width}x$height" }
    val out = ByteArray(width * height * 4)
    when (pixelFormat) {
        PlayerPixelFormat.Yuv420p -> bytes.convertPlanarYuv(
            out, width, height, colorSpace,
            subsampleX = 1, subsampleY = 1, layout = PackedSampleLayout.Eight,
        )
        PlayerPixelFormat.Yuv422p -> bytes.convertPlanarYuv(
            out, width, height, colorSpace,
            subsampleX = 1, subsampleY = 0, layout = PackedSampleLayout.Eight,
        )
        PlayerPixelFormat.Yuv444p -> bytes.convertPlanarYuv(
            out, width, height, colorSpace,
            subsampleX = 0, subsampleY = 0, layout = PackedSampleLayout.Eight,
        )
        PlayerPixelFormat.Yuv420p10le -> bytes.convertPlanarYuv(
            out, width, height, colorSpace,
            subsampleX = 1, subsampleY = 1, layout = PackedSampleLayout.TenLowAligned,
        )
        PlayerPixelFormat.Yuv422p10le -> bytes.convertPlanarYuv(
            out, width, height, colorSpace,
            subsampleX = 1, subsampleY = 0, layout = PackedSampleLayout.TenLowAligned,
        )
        PlayerPixelFormat.Nv12 -> bytes.convertNv12(
            out, width, height, colorSpace, layout = PackedSampleLayout.Eight,
        )
        PlayerPixelFormat.P010le -> bytes.convertNv12(
            out, width, height, colorSpace, layout = PackedSampleLayout.TenHighAligned,
        )
        PlayerPixelFormat.Rgba -> bytes.copyPackedRgba(out, width, height, components = 4, redFirst = true)
        PlayerPixelFormat.Bgra -> bytes.copyPackedRgba(out, width, height, components = 4, redFirst = false)
        PlayerPixelFormat.Rgb24 -> bytes.copyPackedRgba(out, width, height, components = 3, redFirst = true)
        else -> throw IllegalArgumentException(
            "tier 0 cannot convert $pixelFormat. A hardware frame needs its matching renderer, " +
                "and an unusual software format needs a filter graph first.",
        )
    }
    // The software half of the HDR-to-SDR law (17.12 M3). SDR frames return null here, which
    // keeps every existing SDR pixel bit-exact.
    HdrToneMap.forColorSpaceOrNull(colorSpace)?.mapInPlace(out)
    return out
}

private enum class PackedSampleLayout(val bytesPerSample: Int, val dropBits: Int) {
    Eight(1, 0),
    TenLowAligned(2, 2),
    TenHighAligned(2, 8),
}

private fun ByteArray.convertPlanarYuv(
    out: ByteArray,
    width: Int,
    height: Int,
    colorSpace: ColorSpaceInfo,
    subsampleX: Int,
    subsampleY: Int,
    layout: PackedSampleLayout,
) {
    val chromaWidth = packedChromaSize(width, subsampleX)
    val chromaHeight = packedChromaSize(height, subsampleY)
    val step = layout.bytesPerSample
    val yStride = width * step
    val chromaStride = chromaWidth * step
    val yOffset = 0
    val uOffset = yStride * height
    val vOffset = uOffset + chromaStride * chromaHeight
    require(size >= vOffset + chromaStride * chromaHeight) {
        "short planar frame: $size bytes for ${width}x$height"
    }
    val coefficients = PackedCoefficients.of(colorSpace)
    val chromaShift = packedChromaSampleShift(colorSpace.chromaLocation, subsampleX)
    parallelRowSlices(width, height) { startRow, endRow ->
    for (row in startRow until endRow) {
        val chromaRow = row shr subsampleY
        var outIndex = row * width * 4
        for (column in 0 until width) {
            val chromaColumn = ((column + chromaShift) shr subsampleX).coerceIn(0, chromaWidth - 1)
            val luma = readPackedComponent(yOffset + row * yStride + column * step, layout)
            val chromaB = readPackedComponent(uOffset + chromaRow * chromaStride + chromaColumn * step, layout)
            val chromaR = readPackedComponent(vOffset + chromaRow * chromaStride + chromaColumn * step, layout)
            outIndex = writePackedRgba(out, outIndex, coefficients, luma, chromaB, chromaR)
        }
    }
    }
}

private fun ByteArray.convertNv12(
    out: ByteArray,
    width: Int,
    height: Int,
    colorSpace: ColorSpaceInfo,
    layout: PackedSampleLayout,
) {
    val chromaWidth = packedChromaSize(width, subsampling = 1)
    val chromaHeight = packedChromaSize(height, subsampling = 1)
    val step = layout.bytesPerSample
    val yStride = width * step
    val uvStride = chromaWidth * 2 * step
    val uvOffset = yStride * height
    require(size >= uvOffset + uvStride * chromaHeight) {
        "short semiplanar frame: $size bytes for ${width}x$height"
    }
    val coefficients = PackedCoefficients.of(colorSpace)
    val chromaShift = packedChromaSampleShift(colorSpace.chromaLocation, subsampleX = 1)
    parallelRowSlices(width, height) { startRow, endRow ->
    for (row in startRow until endRow) {
        var outIndex = row * width * 4
        for (column in 0 until width) {
            val chromaColumn = ((column + chromaShift) shr 1).coerceIn(0, chromaWidth - 1)
            val luma = readPackedComponent(row * yStride + column * step, layout)
            val uv = uvOffset + (row shr 1) * uvStride + chromaColumn * 2 * step
            val chromaB = readPackedComponent(uv, layout)
            val chromaR = readPackedComponent(uv + step, layout)
            outIndex = writePackedRgba(out, outIndex, coefficients, luma, chromaB, chromaR)
        }
    }
    }
}

private fun ByteArray.copyPackedRgba(
    out: ByteArray,
    width: Int,
    height: Int,
    components: Int,
    redFirst: Boolean,
) {
    val stride = width * components
    require(size >= stride * height) { "short packed frame: $size bytes for ${width}x$height" }
    parallelRowSlices(width, height) { startRow, endRow ->
    for (row in startRow until endRow) {
        var outIndex = row * width * 4
        for (column in 0 until width) {
            val at = row * stride + column * components
            val first = this[at].toInt() and 0xFF
            val green = this[at + 1].toInt() and 0xFF
            val third = this[at + 2].toInt() and 0xFF
            val alpha = if (components == 4) this[at + 3].toInt() and 0xFF else 255
            out[outIndex++] = (if (redFirst) first else third).toByte()
            out[outIndex++] = green.toByte()
            out[outIndex++] = (if (redFirst) third else first).toByte()
            out[outIndex++] = alpha.toByte()
        }
    }
    }
}

private fun ByteArray.readPackedComponent(offset: Int, layout: PackedSampleLayout): Int =
    if (layout.bytesPerSample == 1) {
        this[offset].toInt() and 0xFF
    } else {
        ((this[offset + 1].toInt() and 0xFF) shl 8 or (this[offset].toInt() and 0xFF)) shr layout.dropBits
    }

private fun writePackedRgba(
    out: ByteArray,
    index: Int,
    coefficients: PackedCoefficients,
    luma: Int,
    chromaB: Int,
    chromaR: Int,
): Int {
    val y = (luma - coefficients.lumaOffset) * coefficients.lumaScale
    val cb = (chromaB - 128) * coefficients.chromaScale
    val cr = (chromaR - 128) * coefficients.chromaScale
    var at = index
    out[at++] = (y + coefficients.rCr * cr).packedByte()
    out[at++] = (y - coefficients.gCb * cb - coefficients.gCr * cr).packedByte()
    out[at++] = (y + coefficients.bCb * cb).packedByte()
    out[at++] = -1
    return at
}

private fun Double.packedByte(): Byte = (this + 0.5).toInt().coerceIn(0, 255).toByte()

private fun packedChromaSize(value: Int, subsampling: Int): Int =
    (value + (1 shl subsampling) - 1) shr subsampling

/* Every siting maps to zero ON PURPOSE, mirroring the native converter's rule: nearest-neighbour
 * chroma lookup samples texel x >> subsample whatever the siting metadata says, exactly like the
 * nearest-filtered GPU texture this converter exists to be comparable with. The table names every
 * case rather than collapsing to a bare zero so a NEW siting has to be decided here, not silently
 * inherited; the full reasoning lives beside the native original in SoftwareConverter.native.kt. */
private fun packedChromaSampleShift(location: ChromaLocation, subsampleX: Int): Int {
    val shift = when (location) {
        ChromaLocation.Unspecified,
        ChromaLocation.Left, ChromaLocation.TopLeft, ChromaLocation.BottomLeft -> 0
        ChromaLocation.Center, ChromaLocation.Top, ChromaLocation.Bottom -> 0
    }
    return shift * subsampleX
}

private class PackedCoefficients(
    val lumaOffset: Int,
    val lumaScale: Double,
    val chromaScale: Double,
    val rCr: Double,
    val gCb: Double,
    val gCr: Double,
    val bCb: Double,
) {
    companion object {
        fun of(colorSpace: ColorSpaceInfo): PackedCoefficients {
            val offset = if (colorSpace.fullRange) 0 else 16
            val lumaScale = if (colorSpace.fullRange) 1.0 else 255.0 / 219.0
            val chromaScale = if (colorSpace.fullRange) 1.0 else 255.0 / 224.0
            return when (colorSpace.matrix) {
                ColorMatrix.Bt601, ColorMatrix.Bt470bg, ColorMatrix.Smpte170m -> PackedCoefficients(
                    offset, lumaScale, chromaScale,
                    rCr = 1.402, gCb = 0.344136, gCr = 0.714136, bCb = 1.772,
                )
                ColorMatrix.Smpte240m -> PackedCoefficients(
                    offset, lumaScale, chromaScale,
                    rCr = 1.576, gCb = 0.2266, gCr = 0.4769, bCb = 1.826,
                )
                ColorMatrix.Bt2020Ncl, ColorMatrix.Bt2020Cl -> PackedCoefficients(
                    offset, lumaScale, chromaScale,
                    rCr = 1.4746, gCb = 0.164553, gCr = 0.571353, bCb = 1.8814,
                )
                else -> PackedCoefficients(
                    offset, lumaScale, chromaScale,
                    rCr = 1.5748, gCb = 0.187324, gCr = 0.468124, bCb = 1.8556,
                )
            }
        }
    }
}

/**
 * Turns whatever the audio decoder produced into interleaved float.
 *
 * The layout depends on the sample format. A planar format holds every sample of channel 0, then
 * every sample of channel 1, and so on. A packed format interleaves them already. Both arrive
 * tightly packed with no padding, which is what KiteCodec's copy guarantees.
 *
 * Integer formats are scaled by their full-scale value rather than by the next power of two, so a
 * full-scale input reaches exactly 1.0 and does not clip on the way back out.
 *
 * Audio is copied and video is not. That is not an inconsistency: one second of 48 kHz stereo float
 * is 384 KB, against 187 MB for one second of 1080p60 video, and the engine has to touch every audio
 * sample anyway to resample and mix.
 */
internal fun decodeToFloat(bytes: ByteArray, info: FrameInfo, outChannels: Int = info.channelCount): FloatArray {
    val frames = info.sampleCount
    if (frames <= 0) return FloatArray(0)
    val channels = outChannels.coerceAtLeast(1)
    val sourceChannels = info.channelCount.coerceAtLeast(1)
    val format = info.sampleFormat
    val planar = format.name.endsWith("p")
    val width = format.bytesPerSample()
    val out = FloatArray(frames * channels)

    for (channel in 0 until channels) {
        // A mono source feeding a stereo device plays in both channels rather than leaving one silent.
        val sourceChannel = if (channel < sourceChannels) channel else 0
        for (frame in 0 until frames) {
            val offset = if (planar) {
                (sourceChannel * frames + frame) * width
            } else {
                (frame * sourceChannels + sourceChannel) * width
            }
            if (offset + width > bytes.size) break
            out[frame * channels + channel] = bytes.readSample(offset, format)
        }
    }
    return out
}

private fun KiteSampleFormat.bytesPerSample(): Int = when (this) {
    KiteSampleFormat.U8, KiteSampleFormat.U8p -> 1
    KiteSampleFormat.S16, KiteSampleFormat.S16p -> 2
    KiteSampleFormat.S32, KiteSampleFormat.S32p, KiteSampleFormat.Flt, KiteSampleFormat.FltP -> 4
    KiteSampleFormat.S64, KiteSampleFormat.S64p, KiteSampleFormat.Dbl, KiteSampleFormat.DblP -> 8
    else -> error("unsupported sample format ${this.name}")
}

private fun ByteArray.readSample(offset: Int, format: KiteSampleFormat): Float = when (format) {
    KiteSampleFormat.U8, KiteSampleFormat.U8p -> ((this[offset].toInt() and 0xFF) - 128) / 128f
    KiteSampleFormat.S16, KiteSampleFormat.S16p -> leShort(offset) / 32_768f
    KiteSampleFormat.S32, KiteSampleFormat.S32p -> leInt(offset) / 2_147_483_648f
    KiteSampleFormat.Flt, KiteSampleFormat.FltP -> Float.fromBits(leInt(offset))
    KiteSampleFormat.Dbl, KiteSampleFormat.DblP -> Double.fromBits(leLong(offset)).toFloat()
    KiteSampleFormat.S64, KiteSampleFormat.S64p -> (leLong(offset).toDouble() / 9.223372036854776E18).toFloat()
    else -> error("unsupported sample format ${format.name}")
}

private fun ByteArray.leShort(offset: Int): Int =
    ((this[offset].toInt() and 0xFF) or (this[offset + 1].toInt() shl 8)).toShort().toInt()

private fun ByteArray.leInt(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8) or
        ((this[offset + 2].toInt() and 0xFF) shl 16) or
        ((this[offset + 3].toInt() and 0xFF) shl 24)

private fun ByteArray.leLong(offset: Int): Long =
    (leInt(offset).toLong() and 0xFFFFFFFFL) or (leInt(offset + 4).toLong() shl 32)
