package io.github.yuroyami.kiteplayer.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import io.github.yuroyami.kiteplayer.ffmpeg.KiteCodecVideoFrame
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
        return FrameImage(Image.makeRaster(info, rgba, width * 4).toComposeImageBitmap())
    }

    actual fun release() {
        // Nothing pooled.
    }
}

/**
 * The measured software path and nothing else: the JVM target has no Metal or MediaCodec
 * reader, so every frame goes through KiteCodec's CPU converter. A frame from any other
 * backend fails the cast inside, which the renderer counts as a failed frame and plays on.
 */
internal actual fun kiteCodecFrameToRgba(frame: VideoFrame): ByteArray =
    SoftwareConverter.toRgba(frame as KiteCodecVideoFrame)

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
