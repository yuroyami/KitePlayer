package io.github.yuroyami.kiteplayer.compose

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.github.yuroyami.kiteplayer.ffmpeg.KiteCodecVideoFrame
import io.github.yuroyami.kiteplayer.ffmpeg.SoftwareConverter
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import java.nio.ByteBuffer

internal actual fun rgbaToImageBitmap(rgba: ByteArray, width: Int, height: Int): ImageBitmap {
    // ARGB_8888's in-memory byte order IS tightly packed RGBA, so the buffer copies straight in
    // with no per-pixel swizzle. That non-obvious equivalence is why this is one line, and why
    // no channel swap appears here the way it must in the IntArray-based Surface renderer.
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(rgba))
    return bitmap.asImageBitmap()
}

internal actual fun phoneFrameToRgba(frame: VideoFrame): ByteArray =
    SoftwareConverter.toRgba(frame as KiteCodecVideoFrame)
