@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteffmpeg.WebRgbaConverter
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import kotlin.js.JsAny

/**
 * Paints this backend's video frames into a web destination as RGBA, inside the codec module.
 *
 * It holds a scratch buffer sized to the largest frame it has seen, 24.9 MB for 4K, and that memory
 * belongs to the codec module rather than to any collector that could reclaim it, so close it with
 * the renderer that owns it.
 */
public class KiteFFmpegWebPainter : AutoCloseable {

    private val converter = WebRgbaConverter()

    /**
     * Converts [frame] into [destination], a JavaScript array the size of the frame's RGBA. False
     * for a frame from another backend, which the renderer counts as a drop.
     */
    public fun paint(frame: VideoFrame, destination: JsAny): Boolean {
        val decoded = frame as? KiteFFmpegVideoFrame ?: return false
        return converter.copyInto(decoded.frame, destination)
    }

    override fun close(): Unit = converter.close()
}
