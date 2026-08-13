package io.github.yuroyami.kiteplayer.compose

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
 * One Skia raster per frame, unchanged behind the pool shape: Skia copies the bytes at
 * construction, so there is nothing to reuse at this seam. KV-2 (S2) owns replacing the whole
 * Apple path with YUV images and zero-copy, which is why no ring is built here.
 */
internal actual class FrameImagePool actual constructor() {

    actual fun imageFor(rgba: ByteArray, width: Int, height: Int): ImageBitmap {
        val info = ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.OPAQUE)
        return Image.makeRaster(info, rgba, width * 4).toComposeImageBitmap()
    }

    actual fun release() {
        // Nothing pooled.
    }
}

internal actual fun phoneFrameToRgba(frame: VideoFrame): ByteArray =
    SoftwareConverter.toRgba(frame as KiteCodecVideoFrame)

internal actual fun overlayImageBitmap(rgba: ByteArray, width: Int, height: Int): ImageBitmap {
    val info = ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.PREMUL)
    return Image.makeRaster(info, rgba, width * 4).toComposeImageBitmap()
}
