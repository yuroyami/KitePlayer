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

internal actual fun rgbaToImageBitmap(rgba: ByteArray, width: Int, height: Int): ImageBitmap {
    // Skia reads the bytes as they are: RGBA_8888, opaque, one row every width * 4 bytes.
    val info = ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.OPAQUE)
    return Image.makeRaster(info, rgba, width * 4).toComposeImageBitmap()
}

internal actual fun phoneFrameToRgba(frame: VideoFrame): ByteArray =
    SoftwareConverter.toRgba(frame as KiteCodecVideoFrame)
