package io.github.yuroyami.kiteplayer.compose

import androidx.compose.ui.graphics.ImageBitmap
import io.github.yuroyami.kiteplayer.spi.VideoFrame

/**
 * Builds a drawable image from tightly packed RGBA bytes, one byte per component, no row
 * padding. One allocation per published frame is the honest S1 cost; KV-2 (S2) owns removing
 * it along with the CPU conversion itself.
 */
internal expect fun rgbaToImageBitmap(rgba: ByteArray, width: Int, height: Int): ImageBitmap

/**
 * Converts a frame from the aggregate's own FFmpeg backend to tightly packed RGBA. A frame from
 * any other backend fails the cast inside, which the renderer counts as a failed frame and
 * plays on, exactly like the platform views' converter seams.
 */
internal expect fun phoneFrameToRgba(frame: VideoFrame): ByteArray
