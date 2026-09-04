@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.VideoScale
import io.github.yuroyami.kiteplayer.VideoTransform
import io.github.yuroyami.kiteplayer.spi.HwSurfaceKind
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.spi.RendererEvent
import io.github.yuroyami.kiteplayer.spi.SubtitleOverlay
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import io.github.yuroyami.kiteplayer.spi.VideoRenderer
import io.github.yuroyami.kiteplayer.spi.VideoRendererFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlin.js.JsAny

/**
 * Fills a JS byte array with one frame's RGBA, tightly packed, no row padding.
 *
 * This seam exists because of a hard boundary and a hard measurement, and it is the only shape that
 * satisfies both.
 *
 * The boundary: `:kiteplayer-output` must not depend on KiteFFmpeg or FFmpeg, which the module's own
 * build file states and the boundary scans enforce. So the renderer cannot read a frame's pixels
 * itself, exactly as `AndroidSurfaceVideoRenderer` cannot and takes a converter function instead.
 *
 * The measurement: the Android seam hands back a Kotlin `ByteArray`, and on the web that is the
 * slow path by a factor of twenty. Kotlin/Wasm has no bulk typed-array bridge, so pixels in a
 * `ByteArray` cross one byte per JS call, which the web spike measured at 160 to 240 ms per 1080p frame
 * against a 33.3 ms budget. Converting in C and writing straight into the array a canvas is about
 * to draw measured 8.5 to 9.7 ms.
 *
 * So this asks for a FILL rather than a return. The implementation lives wherever KiteFFmpeg is
 * already a dependency, and the pixels never enter Kotlin memory at all.
 */
public fun interface WebFramePainter {
    /**
     * @param destination a JS `Uint8ClampedArray` of exactly width times height times four bytes.
     * @return false when this frame cannot be painted. The renderer counts a drop and carries on.
     */
    public fun paintRgba(frame: VideoFrame, destination: JsAny): Boolean
}

/**
 * Draws frames onto an HTML canvas.
 *
 * ### The two canvases, and why there are two
 *
 * The frame is written into an offscreen canvas at its own stored size with `putImageData`, and
 * that canvas is then drawn onto the visible one with `drawImage`. `putImageData` alone would be
 * one step shorter and cannot do the job: it writes raw pixels and ignores every transform, so
 * letterboxing, zoom, pan and rotation would all be impossible and the picture would only ever
 * appear at its stored size in the top-left corner. `drawImage` respects the context transform,
 * which is what makes the geometry law below apply at all.
 *
 * ### What it does not do
 *
 * This is S6-D6 tier one: a canvas under the Compose controls, not the single Compose surface where
 * clip, alpha and rotation apply to the video pixels themselves. That is tier two and is not this.
 * There is also no hardware path: the wasm decoder is software by construction, so
 * [supportedHardwareSurfaces] is empty and always will be on this renderer.
 *
 * Not thread-safe, and on the web that is not a constraint: there are no threads. `present` is
 * already `suspend` and runs on the event loop with no worker, no dispatcher and no `runBlocking`.
 */
public class WebCanvasVideoRenderer(
    canvas: JsAny,
    private val painter: WebFramePainter,
) : VideoRenderer {

    private val state: JsAny? = webRendererState(canvas)

    private var viewportWidth: Int = webCanvasWidth(canvas)
    private var viewportHeight: Int = webCanvasHeight(canvas)
    private var scaleMode: VideoScale = VideoScale.Fit
    private var transform: VideoTransform = VideoTransform.Identity
    private var overlay: SubtitleOverlay? = null
    private var overlayHash: Long? = null
    private var closed: Boolean = false

    /** Diagnostics, in the same three counts the Android renderer keeps. */
    public var presentedFrames: Long = 0
        private set
    public var failedFrames: Long = 0
        private set

    private val _events = MutableSharedFlow<RendererEvent>(extraBufferCapacity = 8)
    override val events: Flow<RendererEvent> = _events

    /** Software only. A browser's own hardware decoder is WebCodecs' path and does not arrive here. */
    override fun supportedHardwareSurfaces(): Set<HwSurfaceKind> = emptySet()

    /**
     * Any software format, because the painter converts rather than this class. [PlayerPixelFormat.Opaque]
     * is refused: it means a hardware frame, nothing on the web produces one for this renderer, and
     * answering true would let a mis-wired decoder fail per frame instead of at attach (audit S-W5).
     */
    override fun supports(format: PlayerPixelFormat): Boolean =
        state != null && format != PlayerPixelFormat.Opaque

    override suspend fun present(frame: VideoFrame, targetNanos: Long): Boolean {
        // Ownership rule 2: this renderer owns the frame from here, including on every failure
        // path, and closes it exactly once. `use` is what makes that true without a finally per
        // return, and there are seven returns below.
        frame.use {
            val s = state ?: return false
            if (closed) return false
            if (frame.hardwareSurface != null) {
                failedFrames++
                return false
            }
            val size = frame.size
            if (size.width <= 0 || size.height <= 0) {
                failedFrames++
                return false
            }
            if (!webRendererStage(s, size.width, size.height)) {
                failedFrames++
                return false
            }
            if (!painter.paintRgba(frame, webStageBytes(s))) {
                failedFrames++
                return false
            }
            val layout = frameLayout(
                canvasWidth = viewportWidth,
                canvasHeight = viewportHeight,
                size = size,
                rotationDegrees = frame.rotationDegrees,
                mode = scaleMode,
                transform = transform,
            )
            if (layout == null) {
                failedFrames++
                return false
            }
            webCommitStage(s)
            webDrawStage(
                state = s,
                drawLeft = layout.drawLeft,
                drawTop = layout.drawTop,
                drawWidth = layout.drawWidth,
                drawHeight = layout.drawHeight,
                centerX = layout.centerX,
                centerY = layout.centerY,
                rotation = layout.rotationDegrees,
            )
            drawOverlay(s)
            presentedFrames++
            return true
        }
    }

    /**
     * Subtitles, drawn above the picture in output pixels and NOT with the picture's transform.
     *
     * They are laid out for the viewport already, which is what `SubtitleOverlay` carrying its own
     * viewport size means, so rotating or zooming them with the video would rotate the text too.
     *
     * Uploaded only when [SubtitleOverlay.contentHash] changes. That is not an optimisation to skip
     * later, it is what makes overlays affordable here: their pixels are a Kotlin `ByteArray`, so
     * they cross one byte per JS call, and a cue redrawn every frame would cost more than the video.
     * Cues change about once a second and frames arrive sixty times a second.
     */
    private fun drawOverlay(s: JsAny) {
        val current = overlay
        if (current == null) {
            if (overlayHash != null) {
                overlayHash = null
                webClearOverlay(s)
            }
            return
        }
        if (overlayHash != current.contentHash) {
            webBeginOverlay(s, current.images.size)
            current.images.forEach { image ->
                val bitmap = image.bitmap
                val handle = webOverlayImage(s, bitmap.width, bitmap.height, image.x, image.y)
                val bytes = bitmap.width * bitmap.height * 4
                for (i in 0 until bytes) webOverlayByte(handle, i, bitmap.pixels[i].toInt() and 0xFF)
                webOverlayCommit(s, handle)
            }
            overlayHash = current.contentHash
        }
        webPaintOverlay(s)
    }

    /**
     * Null, honestly. `requestAnimationFrame` follows the display and a page can read
     * `screen.refreshRate` nowhere portable, so a guess here would be a number the schedule trusts.
     * Rule 4 says the cost of null is smoothness on a high refresh display and nothing else.
     */
    override fun vsyncIntervalNanos(): Long? = null

    /**
     * The canvas's BACKING STORE is sized here, which is the only place that can be right.
     *
     * A canvas has two sizes: the CSS box the page lays out, and the pixel buffer it draws into.
     * Leaving the buffer at its 300x150 default and stretching it by CSS is the standard way to get
     * a blurry canvas, and no amount of correct geometry above fixes it. [scale] is the device
     * pixel ratio, so a HiDPI display gets the pixels it actually has.
     */
    override fun setViewport(width: Int, height: Int, scale: Float) {
        val s = state ?: return
        val pixelWidth = (width * scale).toInt().coerceAtLeast(0)
        val pixelHeight = (height * scale).toInt().coerceAtLeast(0)
        viewportWidth = pixelWidth
        viewportHeight = pixelHeight
        webResizeCanvas(s, pixelWidth, pixelHeight)
    }

    override fun setScaleMode(mode: VideoScale) {
        scaleMode = mode
    }

    override fun setTransform(transform: VideoTransform) {
        this.transform = transform
    }

    override suspend fun setOverlay(overlay: SubtitleOverlay?) {
        this.overlay = overlay
        // Not drawn here: the next present draws it above that frame. Drawing now would put
        // subtitles over a picture that is about to be cleared and replaced.
    }

    override fun close() {
        if (closed) return
        closed = true
        overlay = null
        overlayHash = null
        state?.let(::webReleaseState)
    }
}

/** Builds [WebCanvasVideoRenderer]s for one canvas. */
public class WebCanvasVideoRendererFactory(
    private val canvas: JsAny,
    private val painter: WebFramePainter,
) : VideoRendererFactory {
    override val name: String = "web-canvas"
    override suspend fun create(): VideoRenderer = WebCanvasVideoRenderer(canvas, painter)
}

/* The JS half. Every call takes the state object, so nothing here holds a JS reference in Kotlin
 * beyond that one handle, and the whole draw is a handful of crossings per frame. */

/** Null when the element is not a canvas or has no 2d context, which [supports] then reports. */
@JsFun(
    """(canvas) => {
      if (!canvas || typeof canvas.getContext !== 'function') return null;
      const ctx = canvas.getContext('2d');
      if (!ctx) return null;
      return { canvas: canvas, ctx: ctx, stage: null, sctx: null, image: null, w: 0, h: 0, overlay: [], pending: null };
    }""",
)
private external fun webRendererState(canvas: JsAny): JsAny?

@JsFun("(c) => (c && typeof c.width === 'number') ? c.width : 0")
private external fun webCanvasWidth(canvas: JsAny): Int

@JsFun("(c) => (c && typeof c.height === 'number') ? c.height : 0")
private external fun webCanvasHeight(canvas: JsAny): Int

/**
 * The offscreen canvas the frame is written into, rebuilt only when the frame size changes.
 *
 * `OffscreenCanvas` where it exists and a detached element otherwise, so this works in a worker as
 * well as a page, which the worker work will need.
 */
@JsFun(
    """(s, w, h) => {
      if (s.w === w && s.h === h && s.image) return true;
      let stage = null;
      if (typeof OffscreenCanvas !== 'undefined') { stage = new OffscreenCanvas(w, h); }
      else if (typeof document !== 'undefined') { stage = document.createElement('canvas'); stage.width = w; stage.height = h; }
      if (!stage) return false;
      const sctx = stage.getContext('2d');
      if (!sctx) return false;
      s.stage = stage; s.sctx = sctx; s.image = sctx.createImageData(w, h); s.w = w; s.h = h;
      return true;
    }""",
)
private external fun webRendererStage(state: JsAny, width: Int, height: Int): Boolean

@JsFun("(s) => s.image.data")
private external fun webStageBytes(state: JsAny): JsAny

@JsFun("(s) => { s.sctx.putImageData(s.image, 0, 0); }")
private external fun webCommitStage(state: JsAny)

/**
 * Clears the visible canvas and draws the staged picture through the geometry law.
 *
 * The turn is applied about the layout's centre and the picture drawn into the pre-turn rectangle,
 * which is exactly what the Android renderer does with the same [FrameLayout], so the two cannot
 * disagree about where a rotated frame lands.
 */
@JsFun(
    """(s, dl, dt, dw, dh, cx, cy, rot) => {
      const g = s.ctx, c = s.canvas;
      g.setTransform(1, 0, 0, 1, 0, 0);
      g.clearRect(0, 0, c.width, c.height);
      if (rot !== 0) { g.translate(cx, cy); g.rotate(rot * Math.PI / 180); g.translate(-cx, -cy); }
      g.drawImage(s.stage, dl, dt, dw, dh);
      g.setTransform(1, 0, 0, 1, 0, 0);
    }""",
)
private external fun webDrawStage(
    state: JsAny,
    drawLeft: Float,
    drawTop: Float,
    drawWidth: Float,
    drawHeight: Float,
    centerX: Float,
    centerY: Float,
    rotation: Int,
)

@JsFun("(s, w, h) => { if (s.canvas.width !== w) s.canvas.width = w; if (s.canvas.height !== h) s.canvas.height = h; }")
private external fun webResizeCanvas(state: JsAny, width: Int, height: Int)

@JsFun("(s, n) => { s.overlay = []; s.pending = null; }")
private external fun webBeginOverlay(state: JsAny, count: Int)

@JsFun(
    """(s, w, h, x, y) => {
      const c = (typeof OffscreenCanvas !== 'undefined') ? new OffscreenCanvas(w, h)
              : (typeof document !== 'undefined') ? Object.assign(document.createElement('canvas'), { width: w, height: h })
              : null;
      if (!c) return null;
      const g = c.getContext('2d');
      if (!g) return null;
      return { canvas: c, ctx: g, image: g.createImageData(w, h), x: x, y: y };
    }""",
)
private external fun webOverlayImage(state: JsAny, width: Int, height: Int, x: Int, y: Int): JsAny?

@JsFun("(h, i, v) => { if (h) h.image.data[i] = v; }")
private external fun webOverlayByte(handle: JsAny?, index: Int, value: Int)

@JsFun("(s, h) => { if (h) { h.ctx.putImageData(h.image, 0, 0); s.overlay.push(h); } }")
private external fun webOverlayCommit(state: JsAny, handle: JsAny?)

/** Straight over the picture, untransformed: the overlay was laid out in output pixels already. */
@JsFun(
    """(s) => {
      const g = s.ctx;
      g.setTransform(1, 0, 0, 1, 0, 0);
      for (const o of s.overlay) g.drawImage(o.canvas, o.x, o.y);
    }""",
)
private external fun webPaintOverlay(state: JsAny)

@JsFun("(s) => { s.overlay = []; }")
private external fun webClearOverlay(state: JsAny)

@JsFun("(s) => { s.overlay = []; s.stage = null; s.sctx = null; s.image = null; s.w = 0; s.h = 0; }")
private external fun webReleaseState(state: JsAny)
