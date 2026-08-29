@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.yuroyami.kiteplayer.mobile

import io.github.yuroyami.kiteffmpeg.WebRgbaConverter
import io.github.yuroyami.kiteplayer.VideoScale
import io.github.yuroyami.kiteplayer.VideoTransform
import io.github.yuroyami.kiteplayer.ffmpeg.KiteFFmpegVideoFrame
import io.github.yuroyami.kiteplayer.output.WebCanvasVideoRenderer
import io.github.yuroyami.kiteplayer.output.WebFramePainter
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.spi.SubtitleOverlay
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import io.github.yuroyami.kiteplayer.spi.VideoRenderer
import io.github.yuroyami.kiteplayer.spi.VideoRendererFactory
import kotlin.js.JsAny

/**
 * The default KiteFFmpeg adapter for a web canvas (17.14 X-11).
 *
 * The same job `MobileAndroidPlayerViewRendererFactory` does on Android and for the same reason:
 * `:kiteplayer-output` may not depend on KiteFFmpeg, so the module that depends on BOTH is where the
 * two are introduced. On Android that seam hands back a `ByteArray`; here it fills a JS array,
 * because on the web a `ByteArray` of pixels is the twenty-times-slower path.
 *
 * @param canvas the `HTMLCanvasElement` or `OffscreenCanvas` to draw into.
 */
public class WebCanvasRendererFactory(private val canvas: JsAny) : VideoRendererFactory {
    override val name: String = "web-canvas-kiteffmpeg"
    override suspend fun create(): VideoRenderer = KiteFFmpegWebCanvasRenderer(canvas)
}

/**
 * Ties one [WebRgbaConverter] to one renderer's life.
 *
 * The converter holds a scratch buffer sized to the largest frame it has seen, 24.9 MB for 4K, and
 * that memory belongs to the codec module rather than to any collector that could reclaim it. So it
 * is closed with the renderer, explicitly. Everything else is the plain renderer's behaviour.
 */
private class KiteFFmpegWebCanvasRenderer(canvas: JsAny) : VideoRenderer {

    private val converter = WebRgbaConverter()

    private val delegate = WebCanvasVideoRenderer(
        canvas = canvas,
        painter = WebFramePainter { frame, destination -> paint(frame, destination) },
    )

    /**
     * A frame from another backend is refused rather than cast, the same law the Compose converters
     * state: the renderer reports a drop and playback continues, instead of a ClassCastException
     * whose message reads differently on every platform.
     */
    private fun paint(frame: VideoFrame, destination: JsAny): Boolean {
        val kiteCodec = frame as? KiteFFmpegVideoFrame ?: return false
        return converter.copyInto(kiteCodec.frame, destination)
    }

    override fun close() {
        try {
            delegate.close()
        } finally {
            converter.close()
        }
    }

    override fun supportedHardwareSurfaces() = delegate.supportedHardwareSurfaces()
    override fun supports(format: PlayerPixelFormat) = delegate.supports(format)
    override suspend fun present(frame: VideoFrame, targetNanos: Long) = delegate.present(frame, targetNanos)
    override fun vsyncIntervalNanos() = delegate.vsyncIntervalNanos()
    override fun setViewport(width: Int, height: Int, scale: Float) = delegate.setViewport(width, height, scale)
    override fun setScaleMode(mode: VideoScale) = delegate.setScaleMode(mode)
    override fun setTransform(transform: VideoTransform) = delegate.setTransform(transform)
    override suspend fun setOverlay(overlay: SubtitleOverlay?) = delegate.setOverlay(overlay)
    override val events get() = delegate.events
}
