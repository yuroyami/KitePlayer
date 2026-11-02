@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.yuroyami.kiteplayer.mobile

import io.github.yuroyami.kiteplayer.ffmpeg.KiteFFmpegWebPainter
import io.github.yuroyami.kiteplayer.output.WebCanvasVideoRenderer
import io.github.yuroyami.kiteplayer.output.WebFramePainter
import io.github.yuroyami.kiteplayer.spi.VideoRenderer
import io.github.yuroyami.kiteplayer.spi.VideoRendererFactory
import kotlin.js.JsAny

/**
 * The default KiteFFmpeg adapter for a web canvas.
 *
 * The same job `MobileAndroidPlayerViewRendererFactory` does on Android and for the same reason:
 * `:kiteplayer-output` may not depend on KiteFFmpeg, so the module that depends on BOTH is where the
 * two are introduced. On Android that seam hands back a `ByteArray`; here it fills a JS array,
 * because on the web a `ByteArray` of pixels is the twenty-times-slower path.
 *
 * @param canvas the `HTMLCanvasElement` or `OffscreenCanvas` to draw into.
 * @param keepDisplayAwake whether the page's screen stays awake while pictures are drawn, and for
 *        two seconds after the last one (#238). See `WebCanvasVideoRenderer`.
 */
public class WebCanvasRendererFactory(
    private val canvas: JsAny,
    private val keepDisplayAwake: Boolean = true,
) : VideoRendererFactory {
    /** Keeps the original canvas constructor with display wake enabled. */
    public constructor(canvas: JsAny) : this(canvas, true)

    override val name: String = "web-canvas-kiteffmpeg"
    override suspend fun create(): VideoRenderer = KiteFFmpegWebCanvasRenderer(canvas, keepDisplayAwake)
}

/**
 * Ties one [KiteFFmpegWebPainter] to one renderer's life.
 *
 * The painter holds a scratch buffer sized to the largest frame it has seen, 24.9 MB for 4K, and
 * that memory belongs to the codec module rather than to any collector that could reclaim it. So it
 * is closed with the renderer, explicitly. Everything else is the plain renderer's behaviour, handed
 * on whole by delegation: a list of calls written out by hand dropped `outputSize`, so subtitles were
 * laid out for the picture and stretched over the bars, and dropped `clearPicture` the same way
 * (#535). Only the close is written here.
 */
private class KiteFFmpegWebCanvasRenderer private constructor(
    private val painter: KiteFFmpegWebPainter,
    private val delegate: WebCanvasVideoRenderer,
) : VideoRenderer by delegate {

    constructor(canvas: JsAny, keepDisplayAwake: Boolean) : this(canvas, KiteFFmpegWebPainter(), keepDisplayAwake)

    private constructor(canvas: JsAny, painter: KiteFFmpegWebPainter, keepDisplayAwake: Boolean) : this(
        painter,
        WebCanvasVideoRenderer(canvas = canvas, painter = WebFramePainter(painter::paint), keepDisplayAwake = keepDisplayAwake),
    )

    override fun close() {
        try {
            delegate.close()
        } finally {
            painter.close()
        }
    }
}
