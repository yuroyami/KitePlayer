package io.github.yuroyami.kiteplayer.compose

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.github.yuroyami.kiteplayer.ffmpeg.KiteCodecVideoFrame
import io.github.yuroyami.kiteplayer.ffmpeg.SoftwareConverter
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import java.nio.ByteBuffer

/**
 * A ring of three reusable ARGB_8888 bitmaps, refilled in rotation while the frame dimensions
 * hold and rebuilt when they change.
 *
 * Three, not two: the image just published may still be inside HWUI's asynchronous draw while
 * the worker fills the next one, and a depth of two would hand the worker exactly the image
 * that can still be on its way to the screen. Depth three is the standing assumption to
 * re-examine at S3 with device numbers (17.4.6 A2).
 *
 * [release] drops references and deliberately does NOT call `recycle()`: the most recently
 * published image can still be mid-draw when the renderer closes, and drawing a recycled bitmap
 * is a crash. Dropping the references lets the collector reclaim them after the last draw.
 */
internal actual class FrameImagePool actual constructor() {

    private var ring: Array<Bitmap>? = null
    private var next = 0

    actual fun imageFor(rgba: ByteArray, width: Int, height: Int): ImageBitmap {
        val existing = ring
        val ready = if (
            existing != null &&
            existing[0].width == width &&
            existing[0].height == height
        ) {
            existing
        } else {
            Array(RING_DEPTH) { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) }
                .also {
                    ring = it
                    next = 0
                }
        }
        val bitmap = ready[next]
        next = (next + 1) % RING_DEPTH
        // ARGB_8888's in-memory byte order IS tightly packed RGBA, so the buffer copies straight
        // in with no per-pixel swizzle. That non-obvious equivalence is why this is one line.
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(rgba))
        return bitmap.asImageBitmap()
    }

    actual fun release() {
        ring = null
        next = 0
    }

    private companion object {
        private const val RING_DEPTH = 3
    }
}

internal actual fun phoneFrameToRgba(frame: VideoFrame): ByteArray =
    SoftwareConverter.toRgba(frame as KiteCodecVideoFrame)
