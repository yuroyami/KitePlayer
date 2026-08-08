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
    "yuv420p" -> PlayerPixelFormat.Yuv420p
    "yuv422p" -> PlayerPixelFormat.Yuv422p
    "yuv444p" -> PlayerPixelFormat.Yuv444p
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
        KiteColorMatrix.Bt709 -> ColorMatrix.Bt709
        KiteColorMatrix.Bt470bg, KiteColorMatrix.Smpte170m -> ColorMatrix.Bt601
        KiteColorMatrix.Bt2020Ncl -> ColorMatrix.Bt2020Ncl
        KiteColorMatrix.Bt2020Cl -> ColorMatrix.Bt2020Cl
        KiteColorMatrix.Smpte240m -> ColorMatrix.Smpte240m
        KiteColorMatrix.Rgb -> ColorMatrix.Identity
        else -> ColorMatrix.Bt709
    },
    primaries = when (primaries) {
        KiteColorPrimaries.Bt709 -> ColorPrimaries.Bt709
        KiteColorPrimaries.Bt470bg, KiteColorPrimaries.Smpte170m -> ColorPrimaries.Bt601
        KiteColorPrimaries.Bt2020 -> ColorPrimaries.Bt2020
        KiteColorPrimaries.SmpteSt431 -> ColorPrimaries.DciP3
        KiteColorPrimaries.SmpteSt432 -> ColorPrimaries.DisplayP3
        else -> ColorPrimaries.Bt709
    },
    transfer = when (transfer) {
        KiteColorTransfer.Bt709, KiteColorTransfer.Bt2020Ten, KiteColorTransfer.Bt2020Twelve -> ColorTransfer.Bt709
        KiteColorTransfer.Smpte170m -> ColorTransfer.Bt601
        KiteColorTransfer.Iec6196621 -> ColorTransfer.Srgb
        KiteColorTransfer.Linear -> ColorTransfer.Linear
        KiteColorTransfer.Gamma22 -> ColorTransfer.Gamma22
        KiteColorTransfer.Gamma28 -> ColorTransfer.Gamma28
        KiteColorTransfer.SmpteSt2084 -> ColorTransfer.Pq
        KiteColorTransfer.AribStdB67 -> ColorTransfer.Hlg
        else -> ColorTransfer.Bt709
    },
    fullRange = fullRange,
    chromaLocation = when (chromaLocation) {
        KiteChromaLocation.Center -> ChromaLocation.Center
        KiteChromaLocation.TopLeft -> ChromaLocation.TopLeft
        KiteChromaLocation.Top -> ChromaLocation.Top
        KiteChromaLocation.BottomLeft -> ChromaLocation.BottomLeft
        KiteChromaLocation.Bottom -> ChromaLocation.Bottom
        // Left is both the explicit value and the right default: it is what MPEG-2 and H.264 use.
        else -> ChromaLocation.Left
    },
)

internal fun hardwareKindFor(pixelFormatName: String): HwSurfaceKind? = when (pixelFormatName) {
    "videotoolbox_vld" -> HwSurfaceKind.CoreVideoPixelBuffer
    "mediacodec" -> HwSurfaceKind.MediaCodecBuffer
    "vaapi", "vaapi_vld" -> HwSurfaceKind.VaapiSurface
    "d3d11" -> HwSurfaceKind.D3d11Texture
    "cuda" -> HwSurfaceKind.CudaDevicePointer
    else -> null
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
