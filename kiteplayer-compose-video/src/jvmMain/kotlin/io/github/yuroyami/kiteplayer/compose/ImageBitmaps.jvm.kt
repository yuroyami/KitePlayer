package io.github.yuroyami.kiteplayer.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import io.github.yuroyami.kiteplayer.ffmpeg.KiteFFmpegVideoFrame
import io.github.yuroyami.kiteplayer.ffmpeg.SoftwareConverter
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

/**
 * One Skia raster per frame, the same seam as the Apple software path: Skia copies the bytes at
 * construction, so there is nothing to reuse here. A GPU desktop tier would replace this whole
 * file, which is why no ring is built.
 */
internal actual class FrameImagePool actual constructor() {

    actual fun imageFor(rgba: ByteArray, width: Int, height: Int): FrameImage {
        val info = ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.OPAQUE)
        val image = Image.makeRaster(info, rgba, width * 4).toComposeImageBitmap()
        // The far end of KV-5's measured window. Off, this is one volatile read.
        KiteVideoUploadProfiler.frameFinished()
        return FrameImage(image)
    }

    actual fun release() {
        // Nothing pooled.
    }
}

/**
 * The measured software path and nothing else: the JVM target has no Metal or MediaCodec
 * reader, so every frame goes through KiteFFmpeg's CPU converter. A frame from any other
 * backend is refused with UnsupportedFrameType, reported once and then not attempted again.
 */
internal actual fun kiteCodecFrameToRgba(frame: VideoFrame): ByteArray {
    // The near end of KV-5's measured window, before any pixel is read.
    KiteVideoUploadProfiler.frameStarted()
    val rgba = SoftwareConverter.toRgba(frame.asKiteFFmpegFrame())
    KiteVideoUploadProfiler.frameConverted()
    return rgba
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

    /**
     * True, and on desktop the reason is not the one the name suggests.
     *
     * There is no hardware image on this path: `kiteCodecFrameToRgba` converts on the CPU and
     * `Image.makeRaster` COPIES those bytes into Skia, so the decoded frame is free the instant the
     * image exists and there is nothing left for a fence to wait on. The Apple twin answers true
     * because it really can obtain a completion proof; this one answers true because the question
     * does not arise. When KV-2's YUV image path lands and a frame's planes are uploaded rather
     * than copied, this has to be re-decided rather than inherited.
     */
    override val canDrawCommitFencedFrames: Boolean get() = true

    override fun frameRecorded(frame: KiteVideoFrame?) = state.frameCommitted(owner, frame)
}

/** The one place the backend pairing is checked, so all three actuals refuse the same way. */
private fun VideoFrame.asKiteFFmpegFrame(): KiteFFmpegVideoFrame = this as? KiteFFmpegVideoFrame
    ?: throw UnsupportedFrameType(
        actual = this::class.simpleName ?: "an unnamed frame type",
        expected = "KiteFFmpegVideoFrame",
    )
