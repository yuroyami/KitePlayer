@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.compose

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.runtime.Composable
import io.github.yuroyami.kiteplayer.ffmpeg.KiteFFmpegVideoFrame
import io.github.yuroyami.kiteplayer.ffmpeg.corePixelBufferOrNull
import io.github.yuroyami.kiteplayer.ffmpeg.uploadPlanesOrNull
import io.github.yuroyami.kiteplayer.ffmpeg.SoftwareConverter
import io.github.yuroyami.kiteplayer.output.MetalPicture
import io.github.yuroyami.kiteplayer.output.MetalPictureReader
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

/**
 * One Skia raster per frame, unchanged behind the pool shape: Skia copies the bytes at
 * construction, so there is nothing to reuse at this seam. The YUV image path owns replacing the whole
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

internal actual fun kiteCodecRgbaConverter(): FrameConverter {
    val converter = HardwareFrameConverter()
    return FrameConverter(
        toRgba = { frame ->
            val decoded = frame.asKiteFFmpegFrame()
            // The Metal reader serves HARDWARE frames only, where a GPU readback is the only
            // route from a CVPixelBuffer to bytes. Software planes used to ride the same path, which
            // was upload plus readback plus Skia's re-upload for pixels the CPU converter produces in
            // ONE pass with the same arithmetic (the shader is written to match it), tone mapping
            // included. The GPU roundtrip for software frames was strictly waste.
            val hardware = decoded.corePixelBufferOrNull()?.let { MetalPicture.CorePixelBuffer(it) }
            (if (hardware != null) converter.readOrNull(frame, hardware) else null)
                ?: SoftwareConverter.toRgba(decoded)
        },
        close = converter::close,
    )
}

/**
 * One renderer's Metal reader (#476), made on the renderer's worker when the first hardware frame
 * needs it and closed there by the renderer. Two renderers never share one, so they never race
 * one Metal queue. The native converter and the reader allocate their own array each call.
 */
internal class HardwareFrameConverter(
    private val makeReader: () -> MetalPictureReader = ::MetalPictureReader,
) {
    private var reader: MetalPictureReader? = null
    private var unavailable = false
    private var closed = false

    /**
     * The picture as the viewer should see it, an HDR one rolled off to SDR by the tone-mapping
     * rule. Null when Metal failed to initialise or the converter is closed: the CPU converter is
     * the measured fallback then.
     */
    fun readOrNull(frame: VideoFrame, picture: MetalPicture.CorePixelBuffer): ByteArray? {
        if (closed || unavailable) return null
        val ready = reader ?: runCatching(makeReader).getOrNull().also { reader = it }
        if (ready == null) {
            unavailable = true
            return null
        }
        return ready.readRgba(frame, picture, toneMapped = true)
    }

    /** Closes the reader, if one was made. A second call does nothing. */
    fun close() {
        closed = true
        reader?.close()
        reader = null
    }
}

// The Metal reader rolls a hardware HDR frame off with the same rule the CPU converter uses.
internal actual fun kiteCodecToneMaps(frame: VideoFrame): Boolean = SoftwareConverter.toneMapsHdr(frame.colorSpace)

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

/** The one place the backend pairing is checked, so all three actuals refuse the same way. */
private fun VideoFrame.asKiteFFmpegFrame(): KiteFFmpegVideoFrame = this as? KiteFFmpegVideoFrame
    ?: throw UnsupportedFrameType(
        actual = this::class.simpleName ?: "an unnamed frame type",
        expected = "KiteFFmpegVideoFrame",
    )
