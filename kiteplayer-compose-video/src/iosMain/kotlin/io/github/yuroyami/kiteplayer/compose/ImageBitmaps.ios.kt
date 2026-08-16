@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.compose

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.runtime.Composable
import io.github.yuroyami.kiteplayer.ffmpeg.KiteCodecVideoFrame
import io.github.yuroyami.kiteplayer.ffmpeg.corePixelBufferOrNull
import io.github.yuroyami.kiteplayer.ffmpeg.uploadPlanesOrNull
import io.github.yuroyami.kiteplayer.ffmpeg.SoftwareConverter
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

/**
 * One Skia raster per frame, unchanged behind the pool shape: Skia copies the bytes at
 * construction, so there is nothing to reuse at this seam. KV-2 (S2) owns replacing the whole
 * Apple path with YUV images and zero-copy, which is why no ring is built here.
 */
internal actual class FrameImagePool actual constructor() {

    actual fun imageFor(rgba: ByteArray, width: Int, height: Int): FrameImage {
        val info = ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.OPAQUE)
        return FrameImage(Image.makeRaster(info, rgba, width * 4).toComposeImageBitmap())
    }

    actual fun release() {
        // Nothing pooled.
    }
}

/**
 * The KV-2 reader, one per worker thread (S2.d): the renderer's worker owns its converter the
 * same way it owns everything else, and the thread-local keeps two simultaneous KiteVideo
 * states from racing one Metal queue. Null means Metal failed to initialise; the CPU converter
 * remains the measured fallback then, stated rather than silent.
 */
@kotlin.native.concurrent.ThreadLocal
private object GpuFrameReader {
    val reader: io.github.yuroyami.kiteplayer.output.MetalPictureReader? =
        runCatching { io.github.yuroyami.kiteplayer.output.MetalPictureReader() }.getOrNull()
}

internal actual fun kiteCodecFrameToRgba(frame: VideoFrame): ByteArray {
    val decoded = frame as KiteCodecVideoFrame
    // SOL-P1: the Metal reader serves HARDWARE frames only, where a GPU readback is the only
    // route from a CVPixelBuffer to bytes. Software planes used to ride the same path, which
    // was upload plus readback plus Skia's re-upload for pixels the CPU converter produces in
    // ONE pass with the same arithmetic (the shader is written to match it), tone mapping
    // included since M3. The GPU roundtrip for software frames was strictly waste.
    val reader = GpuFrameReader.reader
    if (reader != null) {
        val hardware = decoded.corePixelBufferOrNull()
            ?.let { io.github.yuroyami.kiteplayer.output.MetalPicture.CorePixelBuffer(it) }
        // toneMapped: this is the DISPLAY path, so an HDR CVPixelBuffer reads back as the SDR
        // the viewer should see (M3's law); SDR frames stay bit-exact through the same flag.
        if (hardware != null) return reader.readRgba(frame, hardware, toneMapped = true)
    }
    return SoftwareConverter.toRgba(decoded)
}

internal actual fun overlayImageBitmap(rgba: ByteArray, width: Int, height: Int): ImageBitmap {
    val info = ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.PREMUL)
    return Image.makeRaster(info, rgba, width * 4).toComposeImageBitmap()
}

@Composable
internal actual fun rememberKiteVideoFrameCommitter(
    state: KiteVideoState,
): KiteVideoFrameCommitter = object : KiteVideoFrameCommitter {
    private val owner = Any()
    override val canDrawCommitFencedFrames: Boolean get() = true

    override fun frameRecorded(frame: KiteVideoFrame?) = state.frameCommitted(owner, frame)
}
