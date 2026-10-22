@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.yuroyami.kiteplayer.compose

import io.github.yuroyami.kiteplayer.ffmpeg.KiteFFmpegVideoFrame
import io.github.yuroyami.kiteplayer.ffmpeg.KiteFFmpegWebPainter
import io.github.yuroyami.kiteplayer.spi.VideoRenderer

/**
 * The web's [KiteVideoState]: one `WebKiteVideoRenderer` for the player, and the viewport book that
 * tells it how large the nodes drawing it are, so subtitles are laid out at the size they show.
 */
public actual class KiteVideoState internal constructor(internal val web: WebKiteVideoRenderer) {

    public actual constructor() : this(WebKiteVideoRenderer(kiteCodecWebPaint()))

    private val viewports = KiteVideoViewportBook(web::setViewport)

    internal fun updateViewport(owner: Any, width: Int, height: Int) {
        viewports.update(owner, width, height)
    }

    internal fun removeViewport(owner: Any) {
        viewports.remove(owner)
    }

    public actual val renderer: VideoRenderer get() = web

    public actual val presentedFrames: Long get() = web.presentedFrames

    public actual val supersededFrames: Long get() = web.supersededFrames

    public actual val failedFrames: Long get() = web.failedFrames

    /**
     * On the web, per published frame: the conversion to RGBA in the codec module plus the upload of
     * that RGBA to the GPU, both on the page's one thread.
     */
    public actual val frameCost: KiteVideoFrameCost get() = web.costSnapshot()
}

/**
 * The codec module's own RGBA converter, which writes straight into the staging array. A frame from
 * another backend is refused with [UnsupportedFrameType], as on every other platform.
 *
 * HDR is reported as tone mapped because KiteFFmpeg's web converter states that it maps PQ and HLG
 * to SDR beside the decoder, the law the other software paths follow.
 */
private fun kiteCodecWebPaint(): WebFramePaint {
    val painter = KiteFFmpegWebPainter()
    return WebFramePaint(
        paint = { frame, destination ->
            if (frame !is KiteFFmpegVideoFrame) {
                throw UnsupportedFrameType(
                    actual = frame::class.simpleName ?: "an unnamed frame type",
                    expected = "KiteFFmpegVideoFrame",
                )
            }
            painter.paint(frame, destination)
        },
        toneMaps = { frame -> frame.colorSpace.isHdr },
        close = painter::close,
    )
}
