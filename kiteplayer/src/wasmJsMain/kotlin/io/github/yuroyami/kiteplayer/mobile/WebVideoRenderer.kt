@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.yuroyami.kiteplayer.mobile

import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.ffmpeg.KiteFFmpegWebPainter
import io.github.yuroyami.kiteplayer.output.WebCanvasVideoRenderer
import io.github.yuroyami.kiteplayer.output.WebFramePainter
import io.github.yuroyami.kiteplayer.spi.ColorMatrix
import io.github.yuroyami.kiteplayer.spi.ColorSpaceInfo
import io.github.yuroyami.kiteplayer.spi.ColorTransfer
import io.github.yuroyami.kiteplayer.spi.RendererEvent
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import io.github.yuroyami.kiteplayer.spi.VideoRenderer
import io.github.yuroyami.kiteplayer.spi.VideoRendererFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.merge
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
 * What the web canvas cannot draw exactly for [colorSpace], one warning detail for each limit, and
 * empty when it can. Its RGBA conversion is libswscale's: it applies no tone map, and it guesses
 * BT.709 or BT.601 by picture height for a matrix it does not know.
 */
internal fun webColorLimits(colorSpace: ColorSpaceInfo): List<String> = buildList {
    if (colorSpace.isHdr) {
        val transfer = if (colorSpace.transfer == ColorTransfer.Pq) "PQ" else "HLG"
        add("the web canvas shows $transfer HDR without tone mapping, so it looks flat and dim")
    }
    val guessed = when (colorSpace.matrix) {
        ColorMatrix.YCgCo -> "YCgCo"
        ColorMatrix.Fcc -> "FCC"
        else -> null
    }
    if (guessed != null) add("the web canvas converts $guessed video with the BT.709 or BT.601 matrix")
}

/**
 * Ties one [KiteFFmpegWebPainter] to one renderer's life.
 *
 * The painter holds a scratch buffer sized to the largest frame it has seen, 24.9 MB for 4K, and
 * that memory belongs to the codec module rather than to any collector that could reclaim it. So it
 * is closed with the renderer, explicitly. Everything else is the plain renderer's behaviour, handed
 * on whole by delegation: a list of calls written out by hand dropped `outputSize`, so subtitles were
 * laid out for the picture and stretched over the bars, and dropped `clearPicture` the same way
 * (#535). Only the two things this adds are written here.
 */
private class KiteFFmpegWebCanvasRenderer private constructor(
    private val painter: LimitReportingPainter,
    private val delegate: WebCanvasVideoRenderer,
) : VideoRenderer by delegate {

    constructor(canvas: JsAny, keepDisplayAwake: Boolean) : this(canvas, LimitReportingPainter(), keepDisplayAwake)

    private constructor(canvas: JsAny, painter: LimitReportingPainter, keepDisplayAwake: Boolean) : this(
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

    override val events: Flow<RendererEvent> get() = merge(delegate.events, painter.limits)
}

/**
 * Paints with KiteFFmpeg and says what it could not draw exactly.
 *
 * A frame from another backend is refused rather than cast, the same law the Compose converters
 * state: the renderer reports a drop and playback continues, instead of a ClassCastException
 * whose message reads differently on every platform.
 */
private class LimitReportingPainter {

    private val webPainter = KiteFFmpegWebPainter()

    /** Colour limits met while painting, published beside the plain renderer's own events. */
    val limits = MutableSharedFlow<RendererEvent>(extraBufferCapacity = 4)

    /** The limits reported for the generation being drawn, so a limit is published once per generation. */
    private val reported = mutableSetOf<String>()
    private var reportedFor: Generation? = null

    fun paint(frame: VideoFrame, destination: JsAny): Boolean {
        // Said out loud rather than drawn silently wrong. The engine keeps the first of each per open.
        if (frame.generation != reportedFor) {
            reportedFor = frame.generation
            reported.clear()
        }
        for (detail in webColorLimits(frame.colorSpace)) {
            if (reported.add(detail)) limits.tryEmit(RendererEvent.ColorApproximated(detail))
        }
        return webPainter.paint(frame, destination)
    }

    fun close() = webPainter.close()
}
