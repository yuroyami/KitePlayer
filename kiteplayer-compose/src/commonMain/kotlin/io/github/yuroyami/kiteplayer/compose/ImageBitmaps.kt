package io.github.yuroyami.kiteplayer.compose

import androidx.compose.ui.graphics.ImageBitmap
import io.github.yuroyami.kiteplayer.spi.VideoFrame

/**
 * Builds drawable images from tightly packed RGBA bytes, one byte per component, no row padding.
 *
 * A POOL rather than a function (A2 of the 17.4.6 rider), because the difference between them is
 * the whole Android allocation story: a 1080p RGBA image is 8.3 MB, so one fresh image per
 * published frame at 30 fps is 250 MB/s of garbage. The Android actual reuses a small ring; the
 * iOS actual stays one raster per frame because KV-2 (S2) owns the Apple path.
 *
 * Every [imageFor] call comes from the renderer's single worker thread. [release] is called by
 * the renderer's close AFTER that worker has been joined, so the two never race; the join is the
 * happens-before edge.
 */
internal expect class FrameImagePool() {
    fun imageFor(rgba: ByteArray, width: Int, height: Int): ImageBitmap
    fun release()
}

/**
 * Converts a frame from the aggregate's own FFmpeg backend to tightly packed RGBA. A frame from
 * any other backend fails the cast inside, which the renderer counts as a failed frame and
 * plays on, exactly like the platform views' converter seams.
 */
internal expect fun phoneFrameToRgba(frame: VideoFrame): ByteArray

/**
 * Builds a PREMULTIPLIED-alpha image for a subtitle overlay (S2.d carrying S4.c's KiteVideo
 * half). A separate builder from [FrameImagePool] on purpose: frames are opaque and pooled,
 * overlays carry alpha, change about once a second, and pooling them would hand the draw phase
 * a bitmap the next cue overwrites.
 */
internal expect fun overlayImageBitmap(rgba: ByteArray, width: Int, height: Int): ImageBitmap
