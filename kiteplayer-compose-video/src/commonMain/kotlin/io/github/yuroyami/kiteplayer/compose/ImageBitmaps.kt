package io.github.yuroyami.kiteplayer.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import io.github.yuroyami.kiteplayer.spi.VideoRenderer

/**
 * Builds drawable images from tightly packed RGBA bytes, one byte per component, no row padding.
 *
 * The Android software fallback deliberately creates immutable storage. Reusing even a small ring
 * is unsafe when its Window detaches or drops completion metrics: HWUI may still sample a bitmap
 * after the worker wants to refill it. The normal Android tier never reaches this fallback.
 *
 * Every `imageFor` call comes from the renderer's single worker thread. [release] is called by
 * the renderer's close AFTER that worker has been joined, so the two never race; the join is the
 * happens-before edge.
 */
internal class FrameImage(
    val image: ImageBitmap,
    val requiresCommitFence: Boolean = false,
    val release: () -> Unit = {},
)

internal expect class FrameImagePool() {
    fun imageFor(rgba: ByteArray, width: Int, height: Int): FrameImage
    fun release()
}

/**
 * Converts a frame from the aggregate's own FFmpeg backend to tightly packed RGBA. A frame from
 * any other backend fails the cast inside, which the renderer counts as a failed frame and
 * plays on, exactly like the platform views' converter seams.
 */
internal expect fun kiteCodecFrameToRgba(frame: VideoFrame): ByteArray

/**
 * Builds a PREMULTIPLIED-alpha image for a subtitle overlay (S2.d carrying S4.c's KiteVideo
 * half). A separate builder from [FrameImagePool] on purpose: frames are opaque and pooled,
 * overlays carry alpha, change about once a second, and pooling them would hand the draw phase
 * a bitmap the next cue overwrites.
 */
internal expect fun overlayImageBitmap(rgba: ByteArray, width: Int, height: Int): ImageBitmap

/** A platform renderer that can publish GPU-native images into the software renderer's state slot. */
internal interface KiteVideoHardwareRenderer : VideoRenderer {
    val presentedFrames: Long
    val supersededFrames: Long
    val failedFrames: Long
}

/** Records a platform proof that a frame is no longer sampled by the asynchronous UI renderer. */
internal interface KiteVideoFrameCommitter {
    /** True only when this exact Compose node can obtain a completion proof for hardware images. */
    val canDrawCommitFencedFrames: Boolean

    fun frameRecorded(frame: KiteVideoFrame?)
}

/** The frame-commit implementation bound to the platform view hosting [KiteVideo]. */
@Composable
internal expect fun rememberKiteVideoFrameCommitter(state: KiteVideoState): KiteVideoFrameCommitter
