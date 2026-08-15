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
    val reader = GpuFrameReader.reader
    if (reader != null) {
        // The two frame truths, mapped exactly like every Metal consumer: a VideoToolbox
        // CVPixelBuffer with no copy, or native-format planes with one memcpy each. The colour
        // arithmetic runs in the fragment shader either way (law 2 of 17.9); the CPU pays one
        // readback.
        val picture = decoded.corePixelBufferOrNull()
            ?.let { io.github.yuroyami.kiteplayer.output.MetalPicture.CorePixelBuffer(it) }
            ?: decoded.uploadPlanesOrNull()?.let { planes ->
                io.github.yuroyami.kiteplayer.output.MetalPicture.SoftwarePlanes(
                    width = planes.width,
                    height = planes.height,
                    format = planes.format,
                    planes = planes.planes.map {
                        io.github.yuroyami.kiteplayer.output.MetalPicture.SoftwarePlanes.Plane(
                            it.bytes, it.bytesPerRow, it.rows,
                        )
                    },
                )
            }
        if (picture != null) return reader.readRgba(frame, picture)
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
